package fr.toolmap.data.hierarchy

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.toolmap.core.hierarchy.P256
import fr.toolmap.core.problem.Problems
import java.io.IOException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] over the Android Keystore (`AndroidKeyStore`): the private keys are made there and never leave it, not
 * even to this process. No key asks for the user to unlock the phone: the sharing goes on with the screen off.
 * Nothing is read from the Keystore before a method is called, so building one costs nothing.
 *
 * Only an emulator or a phone runs this: `tools/emulator/hierarchy-keys.sh` (ADR-128).
 */
class AndroidKeyVault : KeyVault {
    private val store: KeyStore by lazy { keystore { KeyStore.getInstance(PROVIDER).apply { load(null) } } }

    override val agreesInHardware: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun createSigningKey(alias: String): VaultSigningKey =
        keystore {
            val spec =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }
                .generateKeyPair()
            signingKey(alias) ?: throw KeyVaultException("signing key not kept by the Keystore")
        }

    override fun signingKey(alias: String): VaultSigningKey? =
        keystore {
            val private = store.getKey(alias, null) as? PrivateKey ?: return@keystore null
            val public = store.getCertificate(alias)?.publicKey ?: return@keystore null
            KeystoreSigningKey(private, P256.encode(public))
        }

    override fun createAgreementKey(alias: String): VaultAgreementKey {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw KeyVaultException("no key agreement in this Keystore")
        return keystore {
            val spec =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }
                .generateKeyPair()
            agreementKey(alias) ?: throw KeyVaultException("agreement key not kept by the Keystore")
        }
    }

    override fun agreementKey(alias: String): VaultAgreementKey? =
        keystore {
            val private = store.getKey(alias, null) as? PrivateKey ?: return@keystore null
            val public = store.getCertificate(alias)?.publicKey ?: return@keystore null
            KeystoreAgreementKey(private, P256.encode(public))
        }

    override fun createSealingKey(alias: String): VaultSealingKey =
        keystore {
            val spec =
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_BITS)
                    .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
            sealingKey(alias) ?: throw KeyVaultException("sealing key not kept by the Keystore")
        }

    override fun sealingKey(alias: String): VaultSealingKey? =
        keystore { (store.getKey(alias, null) as? SecretKey)?.let(::KeystoreSealingKey) }

    override fun delete(alias: String) {
        keystore { if (store.containsAlias(alias)) store.deleteEntry(alias) }
    }

    override fun aliases(prefix: String): List<String> =
        keystore { store.aliases().toList().filter { it.startsWith(prefix) } }

    private class KeystoreSigningKey(
        private val private: PrivateKey,
        override val public: ByteArray,
    ) : VaultSigningKey {
        override fun sign(message: ByteArray): ByteArray =
            keystore {
                val signature = Signature.getInstance(ECDSA)
                signature.initSign(private)
                signature.update(message)
                P256.derToRaw(signature.sign())
            }
    }

    private class KeystoreAgreementKey(
        private val private: PrivateKey,
        override val public: ByteArray,
    ) : VaultAgreementKey {
        override fun agree(peer: ByteArray): ByteArray? {
            val key = publicKey(peer) ?: return null
            return keystore {
                val agreement = KeyAgreement.getInstance(ECDH, PROVIDER)
                agreement.init(private)
                agreement.doPhase(key, true)
                agreement.generateSecret()
            }
        }
    }

    private class KeystoreSealingKey(
        private val key: SecretKey,
    ) : VaultSealingKey {
        override fun seal(
            plain: ByteArray,
            aad: ByteArray,
        ): ByteArray =
            keystore {
                // The Keystore draws the nonce itself (randomized encryption is required by default).
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(aad)
                val sealed = cipher.doFinal(plain)
                val nonce = cipher.iv
                if (nonce.size != NONCE_BYTES) throw KeyVaultException("unexpected nonce from the Keystore")
                nonce + sealed
            }

        override fun open(
            sealed: ByteArray,
            aad: ByteArray,
        ): ByteArray? {
            if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
            return try {
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES))
                cipher.updateAAD(aad)
                cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
            } catch (e: AEADBadTagException) {
                // Damaged, or sealed by another key: refused, and said to whoever debugs.
                Problems.expected("hierarchy.vault.open", e)
                null
            } catch (e: GeneralSecurityException) {
                throw KeyVaultException("Keystore refused to open", e)
            } catch (e: ProviderException) {
                throw KeyVaultException("Keystore refused to open", e)
            }
        }
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val CURVE = "secp256r1"
        private const val ECDSA = "SHA256withECDSA"
        private const val ECDH = "ECDH"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val AES_BITS = 256
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val COORDINATE_BYTES = 32

        private val curve: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec(CURVE))
                getParameterSpec(ECParameterSpec::class.java)
            }
        }

        /** [point] as a key of the platform, once the engine has checked it is on the curve. */
        private fun publicKey(point: ByteArray): PublicKey? {
            if (!P256.isPoint(point)) return null
            val x = BigInteger(1, point.copyOfRange(1, 1 + COORDINATE_BYTES))
            val y = BigInteger(1, point.copyOfRange(1 + COORDINATE_BYTES, P256.POINT_BYTES))
            return keystore { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve)) }
        }

        /** Every refusal of the Keystore as a [KeyVaultException]: the caller says it (Problems) and falls back. */
        private inline fun <T> keystore(block: () -> T): T =
            try {
                block()
            } catch (e: GeneralSecurityException) {
                throw KeyVaultException("Keystore refused", e)
            } catch (e: ProviderException) {
                throw KeyVaultException("Keystore refused", e)
            } catch (e: IOException) {
                throw KeyVaultException("Keystore unreadable", e)
            } catch (e: IllegalArgumentException) {
                // P256.derToRaw: the Keystore gave a signature that is not DER.
                throw KeyVaultException("Keystore gave a malformed value", e)
            }
    }
}
