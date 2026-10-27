package fr.toolmap.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Lazy
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.data.download.ZoneJobStore
import javax.inject.Inject
import javax.inject.Singleton

/** Where a new zone download is queued; the app's implementation is [DownloadScheduler]. */
fun interface ZoneQueue {
    fun start(request: ZoneRequest)
}

/** Starts, pauses, resumes and removes zone download jobs (one unique WorkManager work per job). */
@Singleton
class DownloadScheduler
    @Inject
    constructor(
        private val jobs: ZoneJobStore,
        private val workManagerProvider: Lazy<WorkManager>,
    ) : ZoneQueue {
        // Asked for on the first download action only: building the scheduler (with the map screen's view
        // models) must not need WorkManager to be up.
        private val workManager: WorkManager get() = workManagerProvider.get()

        override fun start(request: ZoneRequest) {
            jobs.save(ZoneJob(request, ZoneStatus.QUEUED, updatedAtMillis = System.currentTimeMillis()))
            enqueue(request.id)
        }

        fun resume(jobId: String) {
            val job = jobs.get(jobId) ?: return
            jobs.save(
                job.copy(status = ZoneStatus.QUEUED, message = null, updatedAtMillis = System.currentTimeMillis()),
            )
            enqueue(jobId)
        }

        /** Stops the work; the worker marks the job paused, and what was downloaded is kept. */
        fun pause(jobId: String) {
            workManager.cancelUniqueWork(workName(jobId))
            val job = jobs.get(jobId) ?: return
            if (job.status in ACTIVE) {
                jobs.save(job.copy(status = ZoneStatus.PAUSED, updatedAtMillis = System.currentTimeMillis()))
            }
        }

        /** Forgets the job; the tiles already stored stay in the offline maps. */
        fun remove(jobId: String) {
            workManager.cancelUniqueWork(workName(jobId))
            jobs.delete(jobId)
        }

        /**
         * « Annuler » after a removal (P29.7): the job comes back as it was; one that was going is queued again, from
         * the tiles it had already stored.
         */
        fun restore(job: ZoneJob) {
            if (job.status in ACTIVE) {
                jobs.save(job.copy(status = ZoneStatus.QUEUED, updatedAtMillis = System.currentTimeMillis()))
                enqueue(job.request.id)
            } else {
                jobs.save(job)
            }
        }

        private fun enqueue(jobId: String) {
            val work =
                OneTimeWorkRequestBuilder<ZoneDownloadWorker>()
                    .setInputData(workDataOf(ZoneDownloadWorker.KEY_JOB_ID to jobId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            workManager.enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.REPLACE, work)
        }

        private fun workName(jobId: String) = "zone-$jobId"

        private companion object {
            val ACTIVE = setOf(ZoneStatus.QUEUED, ZoneStatus.RUNNING, ZoneStatus.INTERRUPTED)
        }
    }
