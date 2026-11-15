package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.MemberKeys
import fr.toolmap.core.hierarchy.P256
import fr.toolmap.core.hierarchy.PhoneKeys
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.problem.catchExpected
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Where the « Opération » mode keeps the operations this phone takes part in (`docs/specs/group-hierarchy.md` § 9,
 * ADR-128): one folder per operation under [root], each with its own identity (the two key pairs of
 * [PhoneKeys]). Nothing calls it yet: the transport (next step of the wiring) will.
 *
 * - The **signing key** is made in the [vault] and never leaves it. So is the **agreement key** where the vault can
 *   do a key agreement ([KeyVault.agreesInHardware]: the Android Keystore from API 31 on); elsewhere it is drawn
 *   here and kept in a file sealed by an AES key of the vault. The choice is made once, when the identity is made,
 *   and kept: a phone updated to a newer Android keeps the key it had.
 * - **What the operation holds** (its operations, content keys, name cards, objects) is sealed with a storage key
 *   of its own (AES-256-GCM), itself sealed by the vault ([StoredOperation]).
 * - **One identity per operation**: two operations side by side share no key, and do not tell a member of both is
 *   the same phone; leaving one deletes its keys, the others keep theirs.
 * - **None of it is in a backup**: the app's own backup only takes the paths it lists (`docs/specs/backup-format.md`
 *   § 3), and Android's backups exclude this folder (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`); the
 *   vault's keys never leave the phone anyway, so a copied folder opens nowhere else.
 * - **A dissolved operation leaves a trace** for a week ([traces], P39.9, ADR-131): everything else is wiped.
 */
class OperationStorage(
    private val root: File,
    private val vault: KeyVault,
    /** Every key of this storage in the vault starts so, then the operation's identifier. */
    private val aliasPrefix: String = ALIAS_PREFIX,
    private val random: SecureRandom = SecureRandom(),
    /** The clock of the traces of dissolved operations: when one was dissolved, and when it is over a week old. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * The traces of the operations dissolved while this phone was a member (P39.9, ADR-131), in a folder of [root]
     * that no operation can be named after; their keys start with [traceAliasPrefix], which no key of an operation
     * starts with.
     */
    internal val traces =
        DissolutionTraces(File(root, DissolutionTraces.FOLDER), vault, traceAliasPrefix(aliasPrefix), random)

    /** The operations stored on this phone, by identifier. */
    fun ids(): List<String> =
        root
            .listFiles { f -> f.isDirectory && ID.matches(f.name) }
            .orEmpty()
            .map { it.name }
            .sorted()

    /**
     * A new identity, for an operation this phone creates or joins: two key pairs, and nothing else yet. `null` when
     * the key store or the disk refused (said to whoever debugs; nothing is left behind).
     */
    fun create(): StoredOperation? = create(fileAgreement = !vault.agreesInHardware)

    /** [create], with the agreement key in a sealed file even where the vault could hold it (the emulator check). */
    internal fun create(fileAgreement: Boolean): StoredOperation? {
        val id = newId()
        val dir = File(root, id)
        val done =
            catchExpected("hierarchy.store.create", IOException::class, KeyVaultException::class) {
                if (!dir.mkdirs()) throw IOException("cannot make the folder of an operation")
                val sealing = vault.createSealingKey(alias(aliasPrefix, id, SEAL))
                val signing = vault.createSigningKey(alias(aliasPrefix, id, SIGN))
                val agreement: AgreementSide =
                    if (fileAgreement) {
                        val pair = P256.generate()
                        val public = P256.encode(pair.public)
                        val plain = lengthPrefixed(pair.private.encoded) + public
                        SealedFile.writeAtomically(File(dir, AGREEMENT_FILE), sealing.seal(plain, agreementAad(id)))
                        AgreementSide.Software(pair.private, public)
                    } else {
                        AgreementSide.Vault(vault.createAgreementKey(alias(aliasPrefix, id, AGREE)))
                    }
                val keys = VaultPhoneKeys(signing, agreement)
                if (!keys.selfTest()) throw KeyVaultException("keys made by the vault do not work")
                val header = Identity(if (fileAgreement) Identity.FILE else Identity.VAULT, ensembleId = 0L)
                SealedFile.writeAtomically(File(dir, IDENTITY_FILE), header.encode())
                StoredOperation(dir, id, keys, header, vault, aliasPrefix, sealing, random, traces, clock)
            }
        return done.getOrElse {
            discard(id)
            null
        }
    }

    /**
     * The operation [id] as it was stored, keys loaded from the vault. `null` when its identity can no longer be
     * read (the key store lost its keys, the folder is damaged): said to whoever debugs; the operation is lost for
     * this phone, which only [wipe] cleans up. A new identity can join it again as a new member.
     */
    fun open(id: String): StoredOperation? {
        if (!ID.matches(id)) return null
        val dir = File(root, id)
        if (!dir.isDirectory) return null
        return catchExpected("hierarchy.store.open_identity", IOException::class, KeyVaultException::class) {
            val header =
                Identity.decode(File(dir, IDENTITY_FILE).readBytes()) ?: throw IOException("damaged identity header")
            val signing = vault.signingKey(alias(aliasPrefix, id, SIGN)) ?: throw KeyVaultException("signing key gone")
            val sealing = vault.sealingKey(alias(aliasPrefix, id, SEAL)) ?: throw KeyVaultException("sealing key gone")
            val agreement: AgreementSide =
                when (header.mode) {
                    Identity.VAULT -> {
                        val key = vault.agreementKey(alias(aliasPrefix, id, AGREE))
                        AgreementSide.Vault(key ?: throw KeyVaultException("agreement key gone"))
                    }

                    else -> {
                        val plain =
                            sealing.open(File(dir, AGREEMENT_FILE).readBytes(), agreementAad(id))
                                ?: throw IOException("damaged agreement key")
                        softwareAgreement(plain)
                    }
                }
            StoredOperation(
                dir,
                id,
                VaultPhoneKeys(signing, agreement),
                header,
                vault,
                aliasPrefix,
                sealing,
                random,
                traces,
                clock,
            )
        }.getOrNull()
    }

    /** Deletes the operation [id], its keys in the vault first: what is left on the disk can no longer be opened. */
    fun wipe(id: String) {
        if (ID.matches(id)) discard(id)
    }

    /**
     * Deletes every operation of this storage, the traces of the dissolved ones, and any key of the vault left by an
     * operation whose folder is gone.
     */
    fun wipeAll() {
        ids().forEach(::discard)
        traces.wipeAll()
        catchExpected("hierarchy.store.wipe_keys", KeyVaultException::class) {
            for (alias in vault.aliases(aliasPrefix)) {
                val id = alias.removePrefix(aliasPrefix).substringBefore('.')
                if (!File(root, id).isDirectory) vault.delete(alias)
            }
        }
    }

    private fun discard(id: String) {
        deleteKeys(vault, aliasPrefix, id)
        deleteFolder(File(root, id))
    }

    private fun newId(): String {
        while (true) {
            val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
            val id = bytes.joinToString("") { "%02x".format(it) }
            if (!File(root, id).exists()) return id
        }
    }

    private fun softwareAgreement(plain: ByteArray): AgreementSide {
        if (plain.size < 2) throw IOException("damaged agreement key")
        val length = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        if (plain.size != 2 + length + P256.POINT_BYTES) throw IOException("damaged agreement key")
        val private =
            try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(plain.copyOfRange(2, 2 + length)))
            } catch (e: GeneralSecurityException) {
                throw IOException("damaged agreement key", e)
            }
        val public = plain.copyOfRange(2 + length, plain.size)
        if (!P256.isPoint(public)) throw IOException("damaged agreement key")
        return AgreementSide.Software(private, public)
    }

    companion object {
        /** Under the app's files: excluded by name from Android's backups. */
        const val FOLDER = "hierarchy/operations"

        /** The start of the aliases of the operations' keys in the vault. */
        const val ALIAS_PREFIX = "toolmap.hierarchy.op."
        private const val ID_BYTES = 8
        private val ID = Regex("[0-9a-f]{16}")
        internal const val IDENTITY_FILE = "identity"
        internal const val AGREEMENT_FILE = "agreement.sealed"
        private const val SIGN = "sign"
        private const val AGREE = "agree"
        internal const val SEAL = "seal"

        internal fun alias(
            prefix: String,
            id: String,
            what: String,
        ) = "$prefix$id.$what"

        /**
         * The start of the keys of the traces of a storage whose operations' keys start with [aliasPrefix]:
         * `toolmap.hierarchy.op.` gives `toolmap.hierarchy.op-trace.`, which does not start with it, so that what
         * looks for the keys of the operations never finds the traces' (and each storage keeps its own).
         */
        internal fun traceAliasPrefix(aliasPrefix: String): String = aliasPrefix.trimEnd('.') + "-trace."

        private fun agreementAad(id: String) = "toolmap-hierarchy-agreement-v1:$id".toByteArray(Charsets.US_ASCII)

        private fun lengthPrefixed(bytes: ByteArray): ByteArray =
            byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes

        internal fun deleteKeys(
            vault: KeyVault,
            prefix: String,
            id: String,
        ) {
            for (what in listOf(SIGN, AGREE, SEAL)) {
                catchExpected("hierarchy.store.delete_key", KeyVaultException::class) {
                    vault.delete(alias(prefix, id, what))
                }
            }
        }

        internal fun deleteFolder(dir: File) {
            if (dir.exists() && !dir.deleteRecursively()) {
                Problems.expected("hierarchy.store.delete_folder", IOException("folder of an operation not deleted"))
            }
        }
    }
}

/**
 * The header of an operation's folder, in clear: how its agreement key is kept, and the ensemble once known. Its last
 * bytes are a fingerprint of the others: a damaged header is refused rather than read as another ensemble.
 */
internal class Identity(
    val mode: Int,
    val ensembleId: Long,
) {
    fun encode(): ByteArray {
        val body = MAGIC.copyOf(BODY)
        body[MAGIC.size] = VERSION.toByte()
        body[MAGIC.size + 1] = mode.toByte()
        for (i in 0 until Long.SIZE_BYTES) body[MAGIC.size + 2 + i] = (ensembleId ushr (56 - 8 * i)).toByte()
        return body + check(body)
    }

    fun bound(ensembleId: Long) = Identity(mode, ensembleId)

    companion object {
        const val VAULT = 1
        const val FILE = 2
        private const val VERSION = 1
        private const val CHECK_BYTES = 8
        private val MAGIC = "TMHI".toByteArray(Charsets.US_ASCII)
        private val BODY = MAGIC.size + 2 + Long.SIZE_BYTES

        private fun check(body: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(body).copyOf(CHECK_BYTES)

        fun decode(bytes: ByteArray): Identity? {
            if (bytes.size != BODY + CHECK_BYTES) return null
            val body = bytes.copyOf(BODY)
            if (!check(body).contentEquals(bytes.copyOfRange(BODY, bytes.size))) return null
            if (!body.copyOf(MAGIC.size).contentEquals(MAGIC) || body[MAGIC.size].toInt() != VERSION) return null
            val mode = body[MAGIC.size + 1].toInt()
            if (mode != VAULT && mode != FILE) return null
            var id = 0L
            for (i in 0 until Long.SIZE_BYTES) id = (id shl 8) or (body[MAGIC.size + 2 + i].toLong() and 0xFF)
            return Identity(mode, id)
        }
    }
}

/** Where the private agreement key of a phone is. */
internal sealed interface AgreementSide {
    val public: ByteArray

    fun agree(peer: ByteArray): ByteArray?

    class Vault(
        private val key: VaultAgreementKey,
    ) : AgreementSide {
        override val public: ByteArray get() = key.public

        override fun agree(peer: ByteArray): ByteArray? = key.agree(peer)
    }

    /** Below API 31: drawn here, kept in a file sealed by the vault, in memory while the operation is open. */
    class Software(
        private val private: PrivateKey,
        override val public: ByteArray,
    ) : AgreementSide {
        override fun agree(peer: ByteArray): ByteArray? = P256.agree(private, peer)
    }
}

/**
 * The [PhoneKeys] of one operation, over the vault. A refusal of the vault to sign is said (Problems) and thrown as
 * a [KeyVaultException]: the engine has no other way to tell that nothing was signed, and the transport catches it
 * around what it asks the engine to write. A refusal to agree reads as « no key » (`null`), which the engine already
 * treats as a batch it cannot open.
 */
internal class VaultPhoneKeys(
    private val signing: VaultSigningKey,
    private val agreement: AgreementSide,
) : PhoneKeys {
    override val public = MemberKeys(signing.public, agreement.public)

    /** Whether the agreement key is in the vault rather than in a sealed file. */
    val agreesInVault: Boolean get() = agreement is AgreementSide.Vault

    override fun sign(message: ByteArray): ByteArray =
        try {
            signing.sign(message)
        } catch (e: KeyVaultException) {
            Problems.expected("hierarchy.keys.sign", e)
            throw e
        }

    override fun agree(peer: ByteArray): ByteArray? =
        try {
            agreement.agree(peer)
        } catch (e: KeyVaultException) {
            Problems.expected("hierarchy.keys.agree", e)
            null
        }

    /** One signature checked, one agreement compared with a key drawn here: what a new identity must pass. */
    fun selfTest(): Boolean {
        val probe = "toolmap-hierarchy-probe".toByteArray(Charsets.US_ASCII)
        if (!P256.verify(public.signing, probe, sign(probe))) return false
        val other = P256.generate()
        val mine = agree(P256.encode(other.public)) ?: return false
        return P256.agree(other.private, public.agreement)?.contentEquals(mine) == true
    }
}
