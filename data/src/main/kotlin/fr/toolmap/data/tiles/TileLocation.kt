package fr.toolmap.data.tiles

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the tile files are (ADR-042): the `tiles` folder of the app's internal files by default, or the folder the user
 * moved them to, on a removable card. Two small text files keep the choice: the folder in use, and a move asked for
 * that has not been done yet. The move runs in a process that opens no tile file (see `StorageMoveActivity`), so that
 * the files can be copied safely.
 */
class TileLocation(
    private val filesDir: File,
) {
    /** The folder of the tile files in the internal storage of the app. */
    val defaultDirectory: File get() = File(filesDir, DEFAULT_FOLDER)

    /** The folder the tile files are in now (the default one when nothing was chosen). */
    fun active(): File = readPath(IN_USE) ?: defaultDirectory

    /** Records [directory] as the folder in use, once its files are there. Written in one step: a crash keeps the old one. */
    fun setActive(directory: File) {
        writePath(IN_USE, directory)
    }

    /** The folder the tile files must move to at the next start, or null when no move is waiting. */
    fun requestedMove(): File? = readPath(MOVE)

    /** Asks for the tile files to move to [directory] at the next start. */
    fun requestMove(directory: File) {
        writePath(MOVE, directory)
    }

    /** Forgets the move asked for: it is done, or it is given up. */
    fun clearMove() {
        File(filesDir, MOVE).delete()
    }

    private fun readPath(name: String): File? =
        File(filesDir, name)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)

    private fun writePath(
        name: String,
        directory: File,
    ) {
        val temp = File(filesDir, "$name.tmp")
        temp.writeText(directory.absolutePath + "\n")
        Files.move(
            temp.toPath(),
            File(filesDir, name).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    companion object {
        /** The folder of the tile files, in the internal files of the app. */
        const val DEFAULT_FOLDER = "tiles"
        private const val IN_USE = "tiles-location.txt"
        private const val MOVE = "tiles-move.txt"
    }
}
