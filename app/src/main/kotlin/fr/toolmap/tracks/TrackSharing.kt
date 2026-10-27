package fr.toolmap.tracks

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands a GPX file written in the cache to another app (mail, messaging, a file manager). */
object TrackSharing {
    const val GPX_MIME = "application/gpx+xml"
    private const val DIRECTORY = "exports"
    private const val MAX_NAME = 60

    /** Where shared files are written; matches `res/xml/file_paths.xml`. */
    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** A safe file name for a track called [name]. */
    fun fileName(name: String?): String {
        val clean = (name ?: "").replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.').take(MAX_NAME)
        return (clean.ifEmpty { "trace" }) + ".gpx"
    }

    fun share(
        context: Context,
        file: File,
        title: String,
    ) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(GPX_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun authority(context: Context) = "${context.packageName}.files"
}
