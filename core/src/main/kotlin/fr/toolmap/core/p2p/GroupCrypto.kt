package fr.toolmap.core.p2p

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption of the messages with the key of the group, on top of the DTLS of each link: a member relaying a message
 * for another cannot forge or read what is not for its group. The key used is `HKDF-SHA256(groupKey, "toolmap-p2p-v1")`.
 * After a re-key the previous key still opens messages for a while, since they travel hop by hop.
 */
class GroupCrypto(
    groupKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(groupKey.size == KEY_BYTES) { "group key must be $KEY_BYTES bytes" }
    }

    private val keys = ArrayList<SecretKeySpec>().apply { add(derive(groupKey)) }

    /** Makes [newKey] the one that seals; the last [KEPT_KEYS] − 1 older ones still open. */
    @Synchronized
    fun rekey(newKey: ByteArray) {
        require(newKey.size == KEY_BYTES)
        keys.add(0, derive(newKey))
        while (keys.size > KEPT_KEYS) keys.removeAt(keys.size - 1)
    }

    /** `nonce (12) | ciphertext | tag (16)`; [aad] is authenticated but not stored. */
    @Synchronized
    fun seal(
        plain: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keys.first(), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    /** The plain bytes, or a [WireException] when no key of the group authenticates [sealed]. */
    @Synchronized
    fun open(
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw WireException("message too short")
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        for (key in keys) {
            try {
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad)
                return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
            } catch (e: GeneralSecurityException) {
                continue
            }
        }
        throw WireException("message does not belong to this group")
    }

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEPT_KEYS = 2
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val INFO = "toolmap-p2p-v1".toByteArray(Charsets.US_ASCII)

        private fun derive(groupKey: ByteArray) =
            SecretKeySpec(hkdfSha256(groupKey, ByteArray(0), INFO, KEY_BYTES), "AES")

        /** RFC 5869 HKDF with HMAC-SHA256; an empty [salt] means a string of zeros. */
        fun hkdfSha256(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            require(length in 1..HASH_BYTES * 255)
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, "HmacSHA256"))
            val prk = extract.doFinal(ikm)
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = java.io.ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (out.size() < length) {
                expand.update(previous)
                expand.update(info)
                expand.update(counter.toByte())
                previous = expand.doFinal()
                out.write(previous)
                counter++
            }
            return out.toByteArray().copyOf(length)
        }

        private const val HASH_BYTES = 32

        fun randomKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)
    }
}
