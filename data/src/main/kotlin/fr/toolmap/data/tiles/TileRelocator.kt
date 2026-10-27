package fr.toolmap.data.tiles

import java.io.File
import java.io.IOException

/**
 * The move of the tile files from [source] to [target] (ADR-042): each `.mbtiles` file with its `-wal` and `-shm`
 * journals. [copy] writes the copies and checks their sizes; the caller then switches the folder in use and calls
 * [removeSource]. Nothing is deleted before the switch, so that an interrupted move leaves the tiles where they were.
 */
class TileRelocator(
    private val source: File,
    private val target: File,
) {
    /** The tile files of [source], in name order. */
    fun files(): List<File> =
        source
            .listFiles { file -> file.isFile && isTileFile(file.name) }
            .orEmpty()
            .sortedBy { it.name }

    /**
     * Copies every file into [target] (created when missing), calling [onProgress] with the bytes copied so far and the
     * total. Each copy must have the size of its source. When anything fails, the copies are removed and the exception
     * is thrown again, so that the target keeps nothing of a move that did not finish. Returns the bytes copied.
     */
    fun copy(onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }): Long {
        require(source.absolutePath != target.absolutePath) { "The move would copy a folder onto itself" }
        val list = files()
        val total = list.sumOf { it.length() }
        if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create ${target.path}")
        // Journals left by an earlier move would be applied to the tile set copied in: they go first.
        target.listFiles { file -> isTileFile(file.name) }.orEmpty().forEach { it.delete() }
        val written = mutableListOf<File>()
        var done = 0L
        try {
            for (file in list) {
                val copy = File(target, file.name)
                written += copy
                file.inputStream().use { input ->
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
                if (copy.length() != file.length()) throw IOException("Incomplete copy of ${file.name}")
            }
        } catch (e: Exception) {
            written.forEach { it.delete() }
            throw e
        }
        return done
    }

    /** Deletes the copies made by [copy], the files of [target] that have the name of a file of [source]. */
    fun discardCopies() {
        val names = files().map { it.name }.toSet()
        target.listFiles()?.filter { it.name in names }?.forEach { it.delete() }
    }

    /** Deletes the files of [source], once the target is the folder in use. A file that stays is only wasted space. */
    fun removeSource() {
        files().forEach { it.delete() }
    }

    companion object {
        private const val BUFFER_BYTES = 1 shl 20

        /** True for a tile set and for its journals (`.mbtiles`, `.mbtiles-wal`, `.mbtiles-shm`). */
        fun isTileFile(name: String): Boolean =
            name.endsWith(MbTilesStore.EXTENSION) ||
                name.endsWith(MbTilesStore.EXTENSION + "-wal") ||
                name.endsWith(MbTilesStore.EXTENSION + "-shm")
    }
}
