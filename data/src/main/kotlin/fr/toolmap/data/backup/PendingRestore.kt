package fr.toolmap.data.backup

import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.BackupManifestCodec
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.core.backup.RestoreReportCodec
import java.io.File
import java.io.IOException

/** The file operations of a restore, apart so that a failure half-way can be tested. */
interface RestoreFileOps {
    fun move(
        from: File,
        to: File,
    )
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
}

/**
 * Puts a verified archive (see [BackupService.stage]) in place of the live data. It runs at the very start of the
 * process, before anything opens the database or reads a file, so that nothing has to be closed or reloaded.
 *
 * Each covered area is moved aside, then the staged one moved in; if any step fails, every area already switched is
 * put back as it was, so a restore either happens completely or not at all. The result is kept in a small report the
 * backup screen shows once.
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
            for (area in BackupArea.entries.filter { manifest.covers(it) }) {
                // Checked before anything moves: a folder under an unmounted card would be created in the hidden mount point.
                if (area == BackupArea.TILES && !locations.tilesReady()) {
                    throw IOException("The offline maps are on a card that is not mounted")
                }
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
