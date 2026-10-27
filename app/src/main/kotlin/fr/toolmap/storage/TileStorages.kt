package fr.toolmap.storage

import android.content.Context
import android.os.Environment
import fr.toolmap.core.settings.StorageLocation
import fr.toolmap.data.backup.BackupLocations
import fr.toolmap.data.tiles.TileLocation
import fr.toolmap.data.tiles.TileRelocator
import fr.toolmap.data.tracks.TrackDatabase
import java.io.File

/**
 * Where the offline maps can be kept: the phone's own storage, or the app's `tiles` folder on a removable card (ADR-042).
 * A card is used only while it is mounted: its content is hidden while it is out, so nothing may be written then.
 */
object TileStorages {
    /** Whether [folder] can be read and written now. */
    fun isUsable(
        context: Context,
        folder: File,
    ): Boolean {
        if (folder.absolutePath.startsWith(context.filesDir.absolutePath)) return true
        // A folder that exists is on a card that is mounted: the folder is hidden while the card is out.
        return folder.isDirectory || mounted(folder)
    }

    /** The `tiles` folder on each removable card mounted now (none when no card is in). */
    fun removableFolders(context: Context): List<File> =
        context
            .getExternalFilesDirs(null)
            .filterNotNull()
            .filter { dir -> removable(dir) && mounted(dir) }
            .map { File(it, TileLocation.DEFAULT_FOLDER) }

    /** The backup and restore locations, the offline maps included wherever they are kept now. */
    fun backupLocations(context: Context): BackupLocations {
        val tiles = TileLocation(context.filesDir).active()
        return BackupLocations(
            filesDir = context.filesDir,
            databaseFile = context.getDatabasePath(TrackDatabase.FILE_NAME),
            tilesDir = tiles,
            tilesReady = { isUsable(context, tiles) },
        )
    }

    /** What the settings show about where the offline maps are kept. */
    fun state(context: Context): TileStorageState {
        val location = TileLocation(context.filesDir)
        val active = location.active()
        val inUse =
            if (active.absolutePath == location.defaultDirectory.absolutePath) {
                StorageLocation.INTERNAL
            } else {
                StorageLocation.EXTERNAL
            }
        return TileStorageState(
            inUse = inUse,
            internal = location.defaultDirectory,
            removable = removableFolders(context).firstOrNull(),
            activeUsable = isUsable(context, active),
            tilesBytes =
                active.listFiles { file ->
                    TileRelocator.isTileFile(file.name)
                }.orEmpty().sumOf { it.length() },
        )
    }

    private fun removable(dir: File): Boolean =
        runCatching {
            Environment.isExternalStorageRemovable(dir)
        }.getOrDefault(false)

    private fun mounted(dir: File): Boolean =
        runCatching { Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED }.getOrDefault(false)
}

/** What the settings show about the offline maps: where they are, and where they could move. */
class TileStorageState(
    val inUse: StorageLocation,
    val internal: File,
    val removable: File?,
    val activeUsable: Boolean,
    val tilesBytes: Long,
) {
    /** The folder the maps would move to with [choice], or null when such a move cannot be made now. */
    fun moveTarget(choice: StorageLocation): File? =
        when {
            choice == inUse -> null

            // The maps are copied from the folder in use: it must be readable.
            choice == StorageLocation.INTERNAL -> internal.takeIf { activeUsable }

            else -> removable
        }
}
