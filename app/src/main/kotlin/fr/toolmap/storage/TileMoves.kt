package fr.toolmap.storage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import fr.toolmap.MainActivity
import fr.toolmap.data.tiles.TileLocation
import fr.toolmap.data.tiles.TileRelocator
import java.io.File
import java.io.IOException

/**
 * Asks for the offline maps to move (ADR-042). The app is closed and started again: the process that is closed may hold
 * tile files open, and the one that starts opens none, so the copy that follows is safe.
 */
object TileMoves {
    /** Asks for the tile files to move to [target], then closes the app; it starts again and the move runs first. */
    fun request(
        context: Context,
        target: File,
    ) {
        TileLocation(context.filesDir).requestMove(target)
        val start = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val again = PendingIntent.getActivity(context, 0, start, PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).set(
            AlarmManager.RTC,
            System.currentTimeMillis() + RESTART_DELAY_MS,
            again,
        )
        Process.killProcess(Process.myPid())
    }

    private const val RESTART_DELAY_MS = 500L
}

/**
 * Does the move asked for (ADR-042): the copy of the tile files, the switch to the new folder, then the removal of the
 * old files. It runs in the process of [fr.toolmap.storage.StorageMoveActivity], which opens no tile file.
 */
class TileMover(
    private val context: Context,
) {
    private val location = TileLocation(context.filesDir)

    /**
     * Runs the move waiting, if any, and returns the folder in use afterwards. Throws when the move cannot be done: the
     * files then stay where they were, and the request is dropped.
     */
    fun execute(onProgress: (copied: Long, total: Long) -> Unit): File {
        val target = location.requestedMove() ?: return location.active()
        val source = location.active()
        if (target.absolutePath == source.absolutePath) {
            location.clearMove()
            return source
        }
        val relocator = TileRelocator(source, target)
        try {
            stopDownloads()
            check(TileStorages.isUsable(context, source)) { "The folder in use is not available" }
            check(TileStorages.isUsable(context, target)) { "The target folder is not available" }
            relocator.copy(onProgress)
        } catch (e: Exception) {
            relocator.discardCopies()
            location.clearMove()
            throw e
        }
        // The switch: from here the copies are the tile files in use. A crash before it keeps the old folder.
        location.setActive(target)
        location.clearMove()
        relocator.removeSource()
        return target
    }

    /** Cancels the zone downloads, which write tiles, and waits until the workers have stopped (30 s at most). */
    private fun stopDownloads() {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWork().result.get()
        val unfinished =
            WorkQuery.Builder
                .fromStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
                .build()
        repeat(STOP_CHECKS) {
            if (manager.getWorkInfos(unfinished).get().isEmpty()) return
            Thread.sleep(STOP_CHECK_MS)
        }
        throw IOException("Downloads are still running")
    }

    private companion object {
        const val STOP_CHECKS = 60
        const val STOP_CHECK_MS = 500L
    }
}
