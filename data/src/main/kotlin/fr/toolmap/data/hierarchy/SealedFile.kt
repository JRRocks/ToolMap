package fr.toolmap.data.hierarchy

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The files of the « Opération » storage: written whole or not at all, and sealed with AES-256-GCM. */
internal object SealedFile {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /**
     * Writes [bytes] to [target] whole or not at all: a temporary file of its own (two writers never share one),
     * flushed to the disk, then renamed over the target. A process killed in between leaves the previous version.
     */
    fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val dir = target.parentFile ?: throw IOException("no folder for a stored file")
        val temp = File.createTempFile(".${target.name}.", ".tmp", dir)
        try {
            FileOutputStream(temp).use {
                it.write(bytes)
                it.fd.sync()
            }
            // Not File.renameTo: on Windows (the unit tests of a workstation) it refuses to replace an existing file.
            // A failure is an IOException, like any other write of this storage.
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** `nonce | ciphertext | tag` of [plain] under [key], authenticated with [aad]. */
    fun seal(
        key: ByteArray,
        plain: ByteArray,
        aad: ByteArray,
        random: SecureRandom,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    /** The plain bytes of [sealed]; an [IOException] when they are damaged, cut, or not sealed by [key] for [aad]. */
    fun open(
        key: ByteArray,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw IOException("stored file too short")
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw IOException("stored file damaged or not ours", e)
        }
    }
}
