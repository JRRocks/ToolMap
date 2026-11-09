package fr.toolmap.problem

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.toolmap.BuildConfig
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.ProblemReport
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.p2p.FileAuthStore
import fr.toolmap.p2p.FirebaseRefused
import fr.toolmap.p2p.FirebaseReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the waiting problem reports (ADR-116) to the coordination server built into the APK, when there is a network.
 * One unique WorkManager work: a report queued while it waits joins the same upload. Nothing happens without a
 * built-in server (local builds) nor in the JVM tests ([AppProblems.canUpload]).
 */
object ProblemUpload {
    private const val WORK = "problem-upload"
    private const val DELAY_S = 20L
    private const val BACKOFF_MIN = 15L

    /** Schedules an upload if reports wait; safe to call often. */
    fun schedule(context: Context) {
        if (!AppProblems.canUpload) return
        val request =
            OneTimeWorkRequestBuilder<Worker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(DELAY_S, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                .build()
        catchExpected("problems.upload.schedule", IllegalStateException::class) {
            // KEEP: an upload already waiting will take the new report too.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }
    }

    class Worker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                val queue = AppProblems.queue() ?: return@withContext Result.success()
                val config =
                    RelayConfig.of(BuildConfig.FIREBASE_DB_URL, BuildConfig.FIREBASE_API_KEY).takeIf { it.isUsable }
                        ?: return@withContext Result.success()
                val reports =
                    FirebaseReports(config, FileAuthStore(File(applicationContext.filesDir, "problems/firebase.token")))
                var retry = false
                for (report in queue.all()) {
                    try {
                        reports.send("${report.fp}-${nonce()}", fitting(report))
                        queue.remove(report.fp)
                    } catch (e: FirebaseRefused) {
                        // The rules of `reports` are not published yet (docs/specs/problems.md § 5): kept for later.
                        Problems.expected("problems.upload.refused", e)
                        retry = true
                        break
                    } catch (e: IOException) {
                        Problems.expected("problems.upload.network", e)
                        retry = true
                        break
                    }
                }
                if (retry) Result.retry() else Result.success()
            }

        /** The JSON of [report], its stack shortened if needed to fit the rules (16 000 characters). */
        private fun fitting(report: ProblemReport): String {
            val json = report.toJson()
            return if (json.length <= MAX_JSON) json else report.copy(stack = report.stack.take(SHORT_STACK)).toJson()
        }

        private fun nonce(): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val random = java.security.SecureRandom()
            return String(CharArray(NONCE_CHARS) { chars[random.nextInt(chars.length)] })
        }

        private companion object {
            const val NONCE_CHARS = 10
            const val MAX_JSON = 15_000
            const val SHORT_STACK = 3_000
        }
    }
}
