package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.P256
import java.security.KeyPair
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [KeyVault] in memory, for the JVM tests: what the Android Keystore does, without the hardware. Its keys outlive
 * the [OperationStorage] that uses them, as the Keystore's outlive a process: a test « restarts the process » by
 * building a new storage over the same folder and the same vault.
 */
class SoftwareKeyVault(
    override val agreesInHardware: Boolean = true,
) : KeyVault {
    private val signing = LinkedHashMap<String, KeyPair>()
    private val agreement = LinkedHashMap<String, KeyPair>()
    private val sealing = LinkedHashMap<String, ByteArray>()

    /** While true, every call refuses, as a Keystore out of order. */
    var refusing = false

    /** How many signatures were asked: a signature goes through the vault, never through a key in memory. */
    var signatures = 0
        private set

    private fun check() {
        if (refusing) throw KeyVaultException("refused (test)")
    }

    override fun createSigningKey(alias: String): VaultSigningKey {
        check()
        signing[alias] = P256.generate()
        return requireNotNull(signingKey(alias))
    }

    override fun signingKey(alias: String): VaultSigningKey? {
        check()
        val pair = signing[alias] ?: return null
        return object : VaultSigningKey {
            override val public = P256.encode(pair.public)

            override fun sign(message: ByteArray): ByteArray {
                check()
                signatures++
                return P256.sign(pair.private, message)
            }
        }
    }

    override fun createAgreementKey(alias: String): VaultAgreementKey {
        check()
        if (!agreesInHardware) throw KeyVaultException("no key agreement here (test)")
        agreement[alias] = P256.generate()
        return requireNotNull(agreementKey(alias))
    }

    override fun agreementKey(alias: String): VaultAgreementKey? {
        check()
        val pair = agreement[alias] ?: return null
        return object : VaultAgreementKey {
            override val public = P256.encode(pair.public)

            override fun agree(peer: ByteArray): ByteArray? {
                check()
                return P256.agree(pair.private, peer)
            }
        }
    }

    override fun createSealingKey(alias: String): VaultSealingKey {
        check()
        sealing[alias] = ByteArray(32).also(SecureRandom()::nextBytes)
        return requireNotNull(sealingKey(alias))
    }

    override fun sealingKey(alias: String): VaultSealingKey? {
        check()
        val key = sealing[alias] ?: return null
        return object : VaultSealingKey {
            override fun seal(
                plain: ByteArray,
                aad: ByteArray,
            ): ByteArray {
                check()
                val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                return nonce + cipher.doFinal(plain)
            }

            override fun open(
                sealed: ByteArray,
                aad: ByteArray,
            ): ByteArray? {
                check()
                if (sealed.size < 28) return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
                cipher.updateAAD(aad)
                return try {
                    cipher.doFinal(sealed, 12, sealed.size - 12)
                } catch (_: AEADBadTagException) {
                    // Like the real vault: not ours, or damaged.
                    null
                }
            }
        }
    }

    override fun delete(alias: String) {
        check()
        signing.remove(alias)
        agreement.remove(alias)
        sealing.remove(alias)
    }

    override fun aliases(prefix: String): List<String> {
        check()
        return (signing.keys + agreement.keys + sealing.keys).filter { it.startsWith(prefix) }.distinct()
    }

    /** Forgets a key, as a Keystore that lost it (a reset of the lock screen on some phones). */
    fun lose(alias: String) {
        signing.remove(alias)
        agreement.remove(alias)
        sealing.remove(alias)
    }
}
