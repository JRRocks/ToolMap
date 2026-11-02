package fr.toolmap.data.backup

import android.database.sqlite.SQLiteException
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.BackupManifestCodec
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.core.backup.RestoreReportCodec
import fr.toolmap.data.tiles.MbTilesFile
import java.io.File
import java.io.IOException

/** The file operations of a restore, apart so that a failure half-way can be tested. */
interface RestoreFileOps {
    fun move(
        from: File,
        to: File,
    )

    /** Merges the offline map [from] (a backup's) into the phone's [into] (ADR-106). */
    fun mergeTiles(
        from: File,
        into: File,
    ) = RealRestoreFileOps.mergeTiles(from, into)
}

object RealRestoreFileOps : RestoreFileOps {
    override fun move(
        from: File,
        to: File,
    ) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        // Another file system: copy, then remove.
        if (from.isDirectory) from.copyRecursively(to, overwrite = true) else from.copyTo(to, overwrite = true)
        from.deleteRecursively()
    }

    override fun mergeTiles(
        from: File,
        into: File,
    ) {
        try {
            MbTilesFile.open(into).use { live ->
                live.mergeFrom(from)
                live.refreshMetadata()
            }
        } catch (e: SQLiteException) {
            throw IOException("Cannot merge ${into.name}: ${e.message}", e)
        }
    }
}

/**
 * Puts a verified archive (see [BackupService.stage]) in place of the live data. It runs at the very start of the
 * process, before anything opens the database or reads a file, so that nothing has to be closed or reloaded.
 *
 * Each area asked for is moved aside, then the staged one moved in; the offline maps are merged into the phone's
 * (ADR-106). If any step fails, every area already switched is put back as it was, so the data is restored completely
 * or not at all (a map already merged keeps the tiles it received: a merge removes nothing). The result is kept in a
 * small report the backup screen shows once.
 */
object PendingRestore {
    /** Applies the staged restore, if there is one; returns its report, or `null` when nothing was waiting. */
    fun apply(
        locations: BackupLocations,
        ops: RestoreFileOps = RealRestoreFileOps,
        clock: () -> Long = System::currentTimeMillis,
    ): RestoreReport? {
        val marker = File(locations.stagingDir, BackupLocations.MARKER)
        if (!locations.stagingDir.exists()) return null
        val manifest =
            runCatching { BackupManifestCodec.decode(marker.readText(Charsets.UTF_8)) }.getOrNull()
        if (manifest == null) {
            // An unpacking that never finished (killed half-way): not usable, not to be kept.
            locations.stagingDir.deleteRecursively()
            return null
        }
        val report =
            try {
                swap(locations, manifest, ops)
                RestoreReport(
                    ok = true,
                    atMillis = clock(),
                    backupMillis = manifest.createdAtMillis,
                    files = manifest.entries.size,
                )
            } catch (e: IOException) {
                RestoreReport(
                    ok = false,
                    atMillis = clock(),
                    backupMillis = manifest.createdAtMillis,
                    error = e.message,
                )
            } catch (e: SecurityException) {
                RestoreReport(
                    ok = false,
                    atMillis = clock(),
                    backupMillis = manifest.createdAtMillis,
                    error = e.message,
                )
            }
        locations.stagingDir.deleteRecursively()
        locations.undoDir.deleteRecursively()
        runCatching { atomicWrite(locations.reportFile, RestoreReportCodec.encode(report)) }
        return report
    }

    private class Moved(
        val live: File,
        val undo: File,
    )

    private fun swap(
        locations: BackupLocations,
        manifest: BackupManifest,
        ops: RestoreFileOps,
    ) {
        val payload = File(locations.stagingDir, BackupLocations.PAYLOAD)
        locations.undoDir.deleteRecursively()
        val aside = ArrayList<Moved>()
        val placed = ArrayList<File>()
        try {
            // Checked before anything moves: a folder under an unmounted card would be created in the hidden mount point.
            if (manifest.covers(BackupArea.TILES) && !locations.tilesReady()) {
                throw IOException("The offline maps are on a card that is not mounted")
            }
            for (area in BackupArea.entries.filter { manifest.covers(it) && it != BackupArea.TILES }) {
                for ((index, live) in locations.targets(area).withIndex()) {
                    if (!live.exists()) continue
                    val undo = File(locations.undoDir, "${area.id}/$index-${live.name}")
                    ops.move(live, undo)
                    aside += Moved(live, undo)
                }
                for (path in stagedPaths(area, manifest)) {
                    val staged = File(payload, path)
                    val live = locations.fileFor(path)
                    ops.move(staged, live)
                    placed += live
                }
            }
            // The offline maps, once the data is in place, whatever the format of the backup (ADR-106): a map the
            // phone lacks is put in place, a map it has receives the missing and newer tiles; the others stay.
            for (path in stagedPaths(BackupArea.TILES, manifest)) {
                val staged = File(payload, path)
                val live = locations.fileFor(path)
                if (live.exists()) {
                    ops.mergeTiles(staged, live)
                } else {
                    // Logs left by a map deleted since would be read as this one's.
                    SQLITE_LOGS.forEach { File(live.path + it).delete() }
                    ops.move(staged, live)
                    placed += live
                }
            }
        } catch (e: IOException) {
            rollback(aside, placed)
            throw e
        } catch (e: SecurityException) {
            rollback(aside, placed)
            throw e
        }
    }

    /** The files the backup holds for [area], by archive path. */
    private fun stagedPaths(
        area: BackupArea,
        manifest: BackupManifest,
    ): List<String> =
        manifest.entries
            .map { it.path }
            .filter { fr.toolmap.core.backup.BackupPaths.areaOf(it) == area }

    private val SQLITE_LOGS = listOf("-wal", "-shm", "-journal")

    private fun rollback(
        aside: List<Moved>,
        placed: List<File>,
    ) {
        for (file in placed.asReversed()) file.deleteRecursively()
        for (moved in aside.asReversed()) {
            moved.live.deleteRecursively()
            runCatching { RealRestoreFileOps.move(moved.undo, moved.live) }
        }
    }
}
