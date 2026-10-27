package fr.toolmap.data.backup

import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupPaths
import java.io.File

/**
 * Where the data of each [BackupArea] lives on this device. Archive paths are relative to [filesDir], except the
 * database, which Android keeps elsewhere.
 */
class BackupLocations(
    val filesDir: File,
    val databaseFile: File,
    /** The folder of the offline maps: the internal one, or the one a move put them in (ADR-042). */
    val tilesDir: File = File(filesDir, TILES_FOLDER),
    /** Whether [tilesDir] can be read and written now: false while its removable card is not mounted. */
    val tilesReady: () -> Boolean = { true },
) {
    /** Where a downloaded archive is unpacked before it is applied at the next start. */
    val stagingDir: File get() = File(filesDir, STAGING)

    /** The data of an area as it was before a restore, kept until the restore has worked. */
    val undoDir: File get() = File(filesDir, UNDO)

    val reportFile: File get() = File(filesDir, REPORT)

    /** The live file for an archive path. */
    fun fileFor(path: String): File =
        when {
            path == BackupPaths.DATABASE_FILE -> databaseFile
            path.startsWith(TILES_PREFIX) -> File(tilesDir, path.substringAfterLast('/'))
            else -> File(filesDir, path)
        }

    /** Everything that holds the data of [area]: replaced as a whole by a restore. */
    fun targets(area: BackupArea): List<File> =
        when (area) {
            BackupArea.DATABASE -> {
                listOf(databaseFile) + SQLITE_SIBLINGS.map { File(databaseFile.path + it) }
            }

            BackupArea.SETTINGS -> {
                listOf(File(filesDir, BackupPaths.SETTINGS_FILE))
            }

            BackupArea.ALERTS -> {
                listOf(File(filesDir, BackupPaths.ALERTS_FILE))
            }

            BackupArea.STATE -> {
                listOf(File(filesDir, "state"))
            }

            BackupArea.SOURCES -> {
                listOf(File(filesDir, "sources"))
            }

            BackupArea.WORKSPACES -> {
                listOf(File(filesDir, "workspaces"))
            }

            BackupArea.PRINT -> {
                listOf(File(filesDir, "print"))
            }

            BackupArea.TILES -> {
                listOf(tilesDir)
            }
        }

    /** The archive path and live file of each file of [area] that exists now. */
    fun liveFiles(area: BackupArea): List<Pair<String, File>> =
        when (area) {
            BackupArea.DATABASE -> single(BackupPaths.DATABASE_FILE, databaseFile)
            BackupArea.SETTINGS -> single(BackupPaths.SETTINGS_FILE, File(filesDir, BackupPaths.SETTINGS_FILE))
            BackupArea.ALERTS -> single(BackupPaths.ALERTS_FILE, File(filesDir, BackupPaths.ALERTS_FILE))
            BackupArea.STATE -> folder("state")
            BackupArea.SOURCES -> folder("sources")
            BackupArea.WORKSPACES -> folder("workspaces")
            BackupArea.PRINT -> folder("print")
            BackupArea.TILES -> folderAt(tilesDir, TILES_FOLDER)
        }

    private fun single(
        path: String,
        file: File,
    ) = if (file.isFile) listOf(path to file) else emptyList()

    private fun folder(name: String): List<Pair<String, File>> = folderAt(File(filesDir, name), name)

    private fun folderAt(
        directory: File,
        name: String,
    ): List<Pair<String, File>> =
        directory
            .listFiles { f -> f.isFile }
            .orEmpty()
            .map { "$name/${it.name}" to it }
            .filter { (path, _) -> BackupPaths.areaOf(path) != null }
            .sortedBy { it.first }

    companion object {
        const val TILES_FOLDER = "tiles"
        private const val TILES_PREFIX = "$TILES_FOLDER/"
        const val STAGING = "restore-pending"
        const val UNDO = "restore-undo"
        const val REPORT = "restore-report.json"
        const val PAYLOAD = "payload"
        const val MARKER = "restore.json"
        private val SQLITE_SIBLINGS = listOf("-wal", "-shm", "-journal")
    }
}

/** What the app does to its live data around a backup, supplied by the layer that owns the database and the tiles. */
class BackupHooks(
    /** Runs [copy] while nothing can write to the database, with its write-ahead log folded into the main file. */
    val freezeDatabase: (copy: () -> Unit) -> Unit,
    /** Folds the write-ahead log of every offline map into its file so that the file can be copied alone. */
    val checkpointTiles: () -> Unit,
)
