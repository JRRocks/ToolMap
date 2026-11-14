package fr.toolmap.data.hierarchy

/**
 * The smallest view of a key store that never lets its private keys out (the Android Keystore: [AndroidKeyVault]).
 * Everything else of the « Opération » storage is plain code over it, tested on the JVM with a software vault;
 * this interface is what an emulator has to check (`tools/emulator/hierarchy-keys.sh`, ADR-128).
 *
 * Keys are named by an alias. A failure of the store (refused, gone, out of order) throws a [KeyVaultException];
 * an alias that does not exist reads as `null`.
 */
interface KeyVault {
    /** Whether [createAgreementKey] works here: the Android Keystore does ECDH from API 31 on only. */
    val agreesInHardware: Boolean

    /** A new P-256 signing key under [alias], replacing any key of that alias. */
    fun createSigningKey(alias: String): VaultSigningKey

    fun signingKey(alias: String): VaultSigningKey?

    /** A new P-256 agreement (ECDH) key under [alias]; only where [agreesInHardware]. */
    fun createAgreementKey(alias: String): VaultAgreementKey

    fun agreementKey(alias: String): VaultAgreementKey?

    /** A new AES-256-GCM key under [alias]. */
    fun createSealingKey(alias: String): VaultSealingKey

    fun sealingKey(alias: String): VaultSealingKey?

    /** Deletes the key of [alias], if any. */
    fun delete(alias: String)

    /** The aliases of the store that start with [prefix]. */
    fun aliases(prefix: String): List<String>
}

/** A signing key whose private half stays in the store. */
interface VaultSigningKey {
    /** The public key, as the engine writes it: the point `0x04 | X | Y`, 65 bytes. */
    val public: ByteArray

    /** The ECDSA (SHA-256) signature of [message], `r | s` on 64 bytes. */
    fun sign(message: ByteArray): ByteArray
}

/** An agreement key whose private half stays in the store. */
interface VaultAgreementKey {
    val public: ByteArray

    /** The ECDH secret with [peer] (a point), 32 bytes; `null` when [peer] is not a point of the curve. */
    fun agree(peer: ByteArray): ByteArray?
}

/** An AES-256-GCM key that stays in the store: it seals small secrets (a key of this storage, a private key). */
interface VaultSealingKey {
    /** `nonce | ciphertext | tag`; the store draws the nonce. */
    fun seal(
        plain: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /** The plain bytes; `null` when [sealed] is not sealed by this key for [aad] (damaged, or not ours). */
    fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray?
}

/** The key store refused, or is out of order. Never carries a key, an alias or a path in its message. */
class KeyVaultException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
