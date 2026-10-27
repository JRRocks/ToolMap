package fr.toolmap.download

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.toolmap.BuildConfig
import fr.toolmap.R
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.data.download.RefusalReason
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.download.ZoneJobStore
import fr.toolmap.data.download.ZoneResult
import fr.toolmap.data.tiles.TileLocation
import fr.toolmap.perf.MapPerfLog
import fr.toolmap.storage.TileStorages
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadEntryPoint {
    fun jobs(): ZoneJobStore

    fun downloader(): ZoneDownloader
}

/**
 * Runs one zone download in a foreground service (WorkManager `dataSync`). Pausing cancels the work: the tiles
 * stored so far stay, and resuming runs the same job again, skipping them. Failures to reach the server end in
 * [Result.retry] (exponential back-off) a few times before the job is marked failed.
 */
class ZoneDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val entry = EntryPointAccessors.fromApplication(applicationContext, DownloadEntryPoint::class.java)
        val jobs = entry.jobs()
        val job = jobs.get(jobId) ?: return Result.failure()
        // The maps are on a card that is out: nothing could be stored, so the job is refused now (ADR-042).
        if (!TileStorages.isUsable(applicationContext, TileLocation(applicationContext.filesDir).active())) {
            jobs.save(
                job.copy(
                    status = ZoneStatus.FAILED,
                    message = applicationContext.getString(R.string.download_refused_card),
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            return Result.failure()
        }
        DownloadNotifications.ensureChannel(applicationContext)
        val notificationId = jobId.hashCode()
        val pause = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        fun notification(progress: ZoneProgress) =
            DownloadNotifications.progress(applicationContext, job.request.name, progress, pause)

        try {
            setForeground(foregroundInfo(notificationId, notification(job.progress)))
        } catch (e: IllegalStateException) {
            // Android refused to start the foreground service (app in the background on API 31+): carry on without.
            Log.w(TAG, "Running without a foreground notification", e)
        }
        var current =
            job.copy(
                status = ZoneStatus.RUNNING,
                message = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        jobs.save(current)
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val startNanos = SystemClock.elapsedRealtimeNanos()
        try {
            val result =
                entry.downloader().run(job.request) { progress ->
                    current = current.copy(progress = progress, updatedAtMillis = System.currentTimeMillis())
                    jobs.save(current)
                    manager.notify(notificationId, notification(progress))
                }
            if (BuildConfig.PERF_LOG) logTiming(result, (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L)
            return finish(jobs, current, result, manager, notificationId)
        } catch (e: CancellationException) {
            jobs.save(current.copy(status = ZoneStatus.PAUSED, updatedAtMillis = System.currentTimeMillis()))
            throw e
        }
    }

    private fun finish(
        jobs: ZoneJobStore,
        job: ZoneJob,
        result: ZoneResult,
        manager: NotificationManager,
        notificationId: Int,
    ): Result {
        val name = job.request.name
        val now = System.currentTimeMillis()
        return when (result) {
            is ZoneResult.Completed -> {
                jobs.save(job.copy(status = ZoneStatus.DONE, progress = result.progress, updatedAtMillis = now))
                val text = applicationContext.getString(R.string.download_notification_done, result.progress.processed)
                manager.notify(notificationId, DownloadNotifications.finished(applicationContext, name, text))
                Result.success()
            }

            is ZoneResult.Interrupted -> {
                val giveUp = runAttemptCount >= MAX_ATTEMPTS
                jobs.save(
                    job.copy(
                        status = if (giveUp) ZoneStatus.FAILED else ZoneStatus.INTERRUPTED,
                        progress = result.progress,
                        message = result.reason,
                        updatedAtMillis = now,
                    ),
                )
                if (giveUp) {
                    val text = applicationContext.getString(R.string.download_notification_failed)
                    manager.notify(notificationId, DownloadNotifications.finished(applicationContext, name, text))
                    Result.failure()
                } else {
                    Result.retry()
                }
            }

            is ZoneResult.Refused -> {
                jobs.save(job.copy(status = ZoneStatus.FAILED, message = refusal(result), updatedAtMillis = now))
                manager.cancel(notificationId)
                Result.failure()
            }
        }
    }

    /** The line the zone scenario of tools/perf reads (docs/specs/performance.md §7, scenario Z). */
    private fun logTiming(
        result: ZoneResult,
        ms: Long,
    ) {
        // Named here, not from the class: R8 renames the classes of the benchmark build.
        val (outcome, progress) =
            when (result) {
                is ZoneResult.Completed -> "completed" to result.progress
                is ZoneResult.Interrupted -> "interrupted" to result.progress
                is ZoneResult.Refused -> "refused" to null
            }
        Log.i(
            MapPerfLog.TAG,
            "zone $outcome ms=$ms total=${progress?.total} " +
                "downloaded=${progress?.downloaded} stored=${progress?.alreadyStored} missing=${progress?.missing} " +
                "failed=${progress?.failed} bytes=${progress?.bytes}",
        )
    }

    private fun refusal(result: ZoneResult.Refused): String =
        applicationContext.getString(
            when (result.reason) {
                RefusalReason.OFFLINE -> R.string.download_refused_offline
                RefusalReason.UNKNOWN_SOURCE -> R.string.download_refused_unknown_source
                RefusalReason.TOO_MANY_TILES -> R.string.download_refused_too_many
                RefusalReason.RESTRICTED_SOURCE -> R.string.download_refused_restricted
                RefusalReason.NOTHING_TO_DOWNLOAD -> R.string.download_refused_nothing
            },
        )

    private fun foregroundInfo(
        notificationId: Int,
        notification: android.app.Notification,
    ): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    companion object {
        const val KEY_JOB_ID = "job-id"
        private const val MAX_ATTEMPTS = 6
        private const val TAG = "ZoneDownloadWorker"
    }
}
