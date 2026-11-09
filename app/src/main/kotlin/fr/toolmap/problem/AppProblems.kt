package fr.toolmap.problem

import android.content.Context
import android.os.Build
import android.util.Log
import fr.toolmap.BuildConfig
import fr.toolmap.core.crash.CrashDevice
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.problem.LocalRedaction
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.ProblemDevice
import fr.toolmap.core.problem.ProblemFingerprint
import fr.toolmap.core.problem.ProblemKind
import fr.toolmap.core.problem.ProblemReport
import fr.toolmap.core.problem.Problems
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app's side of « aucune erreur silencieuse » (ADR-116, `docs/specs/problems.md`). Every problem goes to logcat
 * (with its stack, tag `ToolMap`) and to the event log (`problem`, `problem.expected`); an unexpected one also becomes a
 * report in the [ProblemQueue], sent later to the coordination server by [ProblemUpload], from where the GitHub
 * workflow `problems.yml` opens or updates an issue. Nothing is shown to the user.
 *
 * The same problem met again and again is logged at most once per [LOG_INTERVAL_MS] (the next line says how many
 * were skipped) and queued once per process, its count added at most once per [COUNT_FLUSH_MS].
 */
object AppProblems {
    private const val TAG = "ToolMap"
    private const val LOG_INTERVAL_MS = 10_000L
    private const val COUNT_FLUSH_MS = 60_000L
    private const val LOG_CHUNK = 3_500

    @Volatile
    private var queue: ProblemQueue? = null

    /** Read on the first report only: it asks the system a few things, which the start-up should not wait for. */
    @Volatile
    private var deviceSource: (() -> CrashDevice)? = null

    private val device: ProblemDevice? by lazy { deviceSource?.invoke()?.toProblemDevice() }

    @Volatile
    private var onQueued: () -> Unit = {}

    private val lastLogged = ConcurrentHashMap<String, Long>()
    private val skipped = ConcurrentHashMap<String, AtomicInteger>()

    /** Fingerprints queued by this process, with the occurrences not yet added to their file. */
    private val pending = ConcurrentHashMap<String, Pending>()

    private class Pending(
        @Volatile var flushedAtMs: Long,
    ) {
        val count = AtomicInteger()

        @Volatile
        var lastMs = 0L
    }

    /** Installs the sink, first thing in the process: problems met before are printed by the default sink. */
    fun install(
        context: Context,
        device: () -> CrashDevice,
    ) {
        queue = ProblemQueue(context.filesDir)
        deviceSource = device
        Problems.sink = Problems.Sink(::take)
    }

    /** Called when a report is queued (the app schedules its upload); not before the app is set up. */
    fun onQueued(action: () -> Unit) {
        onQueued = action
    }

    /** The waiting reports, the occurrences counted by this process added first. */
    fun queue(): ProblemQueue? {
        flushCounts(force = true)
        return queue
    }

    /** Queues the report of an uncaught exception, at once: the process is about to die. */
    fun recordCrash(
        thread: Thread,
        error: Throwable,
        atMs: Long,
    ) {
        val q = queue ?: return
        val d = device ?: return
        q.add(ProblemReport.of(ProblemKind.CRASH, "uncaught", error, thread.name, d, atMs))
    }

    /** Queues the report of a crash found after the fact (native crash, ANR). */
    fun record(report: (ProblemDevice) -> ProblemReport) {
        val q = queue ?: return
        val d = device ?: return
        q.add(report(d))
        onQueued()
    }

    fun device(): ProblemDevice? = device

    private fun take(problem: Problem) {
        log(problem)
        if (!problem.expected) enqueue(problem)
    }

    private fun log(problem: Problem) {
        val key = problem.where + '|' + problem.error.javaClass.name
        val last = lastLogged[key]
        if (last != null && problem.timeMs - last < LOG_INTERVAL_MS) {
            skipped.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            return
        }
        lastLogged[key] = problem.timeMs
        val before = skipped.remove(key)?.get() ?: 0
        val again = if (before > 0) " (+$before)" else ""
        // The stack is written by hand: Log would print the messages as they are, secrets included (LocalRedaction).
        val stack = LocalRedaction.stackText(problem.error)
        if (problem.expected) {
            logLong(Log.WARN, "Expected failure at ${problem.where}$again\n$stack")
        } else {
            logLong(Log.ERROR, "UNEXPECTED at ${problem.where}$again [${problem.thread}]\n$stack")
        }
        // The event log's own failures are not written back into it.
        if (problem.where.startsWith("eventlog.")) return
        val fields =
            listOf(
                "where" to problem.where,
                "error" to problem.error.javaClass.name,
                "why" to LocalRedaction.message(problem.error.message),
                "skipped" to before.takeIf { it > 0 },
            ) + problem.context
        EventLog.event(if (problem.expected) "problem.expected" else "problem", *fields.toTypedArray())
    }

    /** Logcat cuts an entry at about 4 000 bytes: a long stack goes in several entries. */
    private fun logLong(
        priority: Int,
        text: String,
    ) {
        val chunk = StringBuilder()
        for (line in text.lineSequence()) {
            if (chunk.isNotEmpty() && chunk.length + line.length + 1 > LOG_CHUNK) {
                Log.println(priority, TAG, chunk.toString())
                chunk.setLength(0)
            }
            if (chunk.isNotEmpty()) chunk.append('\n')
            chunk.append(line.take(LOG_CHUNK))
        }
        if (chunk.isNotEmpty()) Log.println(priority, TAG, chunk.toString())
    }

    private fun enqueue(problem: Problem) {
        val q = queue ?: return
        val d = device ?: return
        // The fingerprint alone first: a problem met again only adds to its count.
        val fp = ProblemFingerprint.of(ProblemKind.ERROR, problem.where, problem.error)
        val known = pending[fp]
        if (known != null) {
            known.count.incrementAndGet()
            known.lastMs = problem.timeMs
            flushCounts(force = false)
            return
        }
        pending[fp] = Pending(problem.timeMs)
        val report =
            ProblemReport.of(
                ProblemKind.ERROR,
                problem.where,
                problem.error,
                problem.thread,
                d,
                problem.timeMs,
                problem.context,
            )
        try {
            q.add(report)
        } catch (e: IOException) {
            // Inside the sink: this goes to standard error (logcat « System.err »), not back here.
            Problems.report("problems.queue.write", e)
            return
        }
        onQueued()
    }

    private fun flushCounts(force: Boolean) {
        val q = queue ?: return
        val now = System.currentTimeMillis()
        for ((fp, p) in pending) {
            if (p.count.get() == 0 || (!force && now - p.flushedAtMs < COUNT_FLUSH_MS)) continue
            val n = p.count.getAndSet(0)
            p.flushedAtMs = now
            try {
                q.addCount(fp, n, p.lastMs)
            } catch (e: IOException) {
                Problems.report("problems.queue.count", e)
            }
        }
    }

    private fun CrashDevice.toProblemDevice() =
        ProblemDevice(
            app = appVersion,
            commit = commit,
            android = androidRelease,
            api = apiLevel,
            device = device,
            abi = abis.firstOrNull().orEmpty(),
        )

    /** True when the app may send reports: a built-in server, a real phone (not the JVM tests). */
    val canUpload: Boolean
        get() =
            BuildConfig.FIREBASE_DB_URL.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
                Build.FINGERPRINT != "robolectric"
}
