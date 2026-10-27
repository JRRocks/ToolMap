package fr.toolmap.sources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The text of a document picked by the user and its display name, if known. */
data class UriText(
    val text: String,
    val name: String?,
)

/** The bytes of a document picked by the user and its display name, if known. */
class UriBytes(
    val bytes: ByteArray,
    val name: String?,
)

/** Reads small text documents (map definitions, tracks) from a content or file [Uri] with a size limit. */
object UriReader {
    /** 5 million characters is the parser limit; UTF-8 needs at most 4 bytes per character. */
    const val DEFAULT_MAX_BYTES = 6_000_000

    @Throws(IOException::class)
    fun readText(
        context: Context,
        uri: Uri,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): UriText {
        val read = readBytes(context, uri, maxBytes)
        return UriText(String(read.bytes, Charsets.UTF_8), read.name)
    }

    @Throws(IOException::class)
    fun readBytes(
        context: Context,
        uri: Uri,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): UriBytes {
        val buffer = ByteArrayOutputStream()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { stream ->
            val chunk = ByteArray(CHUNK)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > maxBytes) throw IOException("File too large (over ${maxBytes / BYTES_PER_MB} MB)")
            }
        }
        return UriBytes(buffer.toByteArray(), displayName(context, uri))
    }

    private fun displayName(
        context: Context,
        uri: Uri,
    ): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    private const val CHUNK = 16 * 1024
    private const val BYTES_PER_MB = 1_000_000
}
