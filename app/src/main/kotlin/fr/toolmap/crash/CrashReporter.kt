package fr.toolmap.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import fr.toolmap.BuildConfig
import fr.toolmap.core.crash.CrashDevice
import fr.toolmap.core.crash.CrashKind
import fr.toolmap.core.crash.CrashReport
import fr.toolmap.core.crash.ErrorReport
import fr.toolmap.core.crash.ExitSelection
import fr.toolmap.core.crash.NativeCrash
import fr.toolmap.core.crash.ProcessExit
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.problem.ProblemReport
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.problem.catchReported
import fr.toolmap.problem.AppProblems
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Makes a crash visible on the phone, where the user has no logcat: the report (error, phone, build) is written to a
 * file and shown by [CrashActivity], which can copy or share it.
 *
 * - A Java/Kotlin exception goes through the default uncaught exception handler: the report is written at once and the
 *   report screen is started in its own process before this one dies.
 * - A native crash (a signal in MapLibre, WebRTC...) kills the process without running any of our code: it is found at
 *   the next start in the platform's record of past exits (Android 11+), with its tombstone.
 *
 * A report not yet seen makes the next start skip the risky set-up and open the report screen instead (see
 * `ToolMapApp` and `MainActivity`), so that a crash at start-up cannot hide its own report.
 */
object CrashReporter {
    private const val TAG = "ToolMap"
    private const val CRASH_PROCESS_SUFFIX = ":crash"
    private const val DIR = "crash"
    private const val PENDING = "pending.txt"
    private const val LAST = "last.txt"
    private const val PREFS = "crash_reporter"
    private const val KEY_EXITS_CHECKED_UP_TO = "exits_checked_up_to"
    private const val MAX_EXITS = 16
    private const val MAX_TRACE_BYTES = 4 * 1024 * 1024
    private const val LOG_LINES = 400
    private const val LOG_TIMEOUT_MS = 2_000L
    private const val EXIT_STATUS = 10
    private const val MAX_CAUSES = 32
    private const val EVENT_FLUSH_MS = 500L

    /** The past exits sent to GitHub at the next start; Java crashes are queued as they happen. */
    private val QUEUED_REASONS = setOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

    /** True in the process of the report screen, which must not set up (nor report) anything itself. */
    fun isCrashProcess(context: Context): Boolean = processName(context).endsWith(CRASH_PROCESS_SUFFIX)

    /** True in the app's own process (not the report screen's nor the storage move's). */
    fun isMainProcess(context: Context): Boolean = processName(context) == context.packageName

    /**
     * Installs the handler that turns an uncaught exception into a report and opens the report screen. [exit] ends the
     * process afterwards (replaced in tests).
     */
    fun install(
        context: Context,
        exit: () -> Unit = ::killProcess,
    ) {
        Thread.setDefaultUncaughtExceptionHandler(Handler(context, Thread.getDefaultUncaughtExceptionHandler(), exit))
    }

    /** The handler of [install]; [previous] is the one it replaced (put back by tests). */
    class Handler internal constructor(
        private val context: Context,
        val previous: Thread.UncaughtExceptionHandler?,
        private val exit: () -> Unit,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            thread: Thread,
            error: Throwable,
        ) {
            catchReported("crash.eventlog") {
                EventLog.event(
                    "app.crash",
                    "thread" to thread.name,
                    "error" to error.javaClass.name,
                    "why" to error.message,
                )
                EventLog.flush(EVENT_FLUSH_MS)
            }
            // The report for GitHub (ADR-116), written at once: the report screen below may fail to start.
            catchReported("crash.queue") { AppProblems.recordCrash(thread, error, System.currentTimeMillis()) }
            val saved = catchReported("crash.save") { recordJavaCrash(context, thread, error) }.isSuccess
            if (!saved) {
                // Nothing to show: let the platform do what it usually does.
                previous?.uncaughtException(thread, error)
                return
            }
            catchReported("crash.screen") {
                context.startActivity(
                    CrashActivity
                        .intent(context)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
            exit()
        }
    }

    fun recordJavaCrash(
        context: Context,
        thread: Thread,
        error: Throwable,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        Log.e(TAG, "Crash in thread ${thread.name}", error)
        // The root cause says what went wrong; the wrappers (ExceptionInInitializerError, RuntimeException...) say where.
        val cause = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).last()
        save(
            context,
            CrashReport(
                kind = CrashKind.JAVA,
                atMillis = atMillis,
                process = processName(context),
                thread = thread.name,
                headline = cause.toString(),
                details = error.stackTraceToString(),
                device = device(context),
                log = logTail(),
            ),
        )
    }

    /**
     * Looks for native crashes and ANRs of the previous runs (Android 11+): each new one is queued for GitHub
     * (ADR-116), and the latest new native crash becomes the report shown at start-up.
     */
    fun collectNativeCrash(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        catchReported("crash.exits") { collectNativeCrashSince30(context) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collectNativeCrashSince30(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        val exits =
            records.map {
                ProcessExit(it.timestamp, it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE, it.processName, it)
            }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val checked = prefs.getLong(KEY_EXITS_CHECKED_UP_TO, 0L)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val crash = ExitSelection.nativeCrashToReport(exits, context.packageName, installed, checked)
        // A trace can be read once: each new one is read here, for GitHub and for the report screen.
        val fresh =
            records.filter {
                it.processName == context.packageName &&
                    it.timestamp > maxOf(installed, checked) &&
                    it.reason in QUEUED_REASONS
            }
        // With something to report, written at once: the start-up that follows may crash again before an asynchronous
        // write, and the same crash would be reported twice. With nothing to report (nearly every start), a write
        // that is lost changes nothing, and the main thread does not wait for the disk before the first screen
        // (P36 audit).
        prefs.edit(commit = crash != null || fresh.isNotEmpty()) {
            putLong(KEY_EXITS_CHECKED_UP_TO, ExitSelection.checkedUpTo(exits, checked))
        }
        val traces = fresh.associateWith { readTrace(it) }
        for (record in fresh) queueForGitHub(record, traces[record])
        val info = crash?.source ?: return
        val tombstone = traces[info] ?: readTrace(info)
        save(
            context,
            CrashReport(
                kind = CrashKind.NATIVE,
                atMillis = info.timestamp,
                process = info.processName,
                thread = null,
                headline = NativeCrash.signal(info.status) + (info.description?.let { " - $it" } ?: ""),
                details = tombstone?.let { NativeCrash.printableStrings(it) } ?: "(no tombstone)",
                device = device(context),
                log = logTail(),
            ),
        )
    }

    fun hasPending(context: Context): Boolean = pendingFile(context).exists()

    /**
     * The report to show: the pending one, which is then marked as seen (the next start is a normal one again), or else
     * the last one seen.
     */
    fun takeForDisplay(context: Context): String? {
        val pending = pendingFile(context)
        val last = File(pending.parentFile, LAST)
        if (pending.exists()) {
            val text = pending.readText()
            last.delete()
            if (!pending.renameTo(last)) pending.delete()
            return text
        }
        return last.takeIf { it.exists() }?.readText()
    }

    /**
     * The text of « Signaler une erreur » (P16.3): the phone and the build, the last crash report (pending or seen,
     * which is not marked as seen by this), and the end of the app's own log. Waits up to [LOG_TIMEOUT_MS] for the
     * log: call it off the main thread.
     */
    fun errorReport(context: Context): String {
        val directory = File(context.filesDir, DIR)
        val last =
            listOf(File(directory, PENDING), File(directory, LAST))
                .firstOrNull { it.exists() }
                ?.let { catchExpected("crash.report.read", IOException::class) { it.readText() }.getOrNull() }
        return ErrorReport.text(device(context), System.currentTimeMillis(), last, logTail())
    }

    fun device(context: Context): CrashDevice {
        val packageManager = context.packageManager
        val info =
            catchExpected("crash.device.package", PackageManager.NameNotFoundException::class) {
                packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
        val version = info?.let { "${it.versionName} (${PackageInfoCompat.getLongVersionCode(it)})" } ?: "?"
        val glEs =
            catchReported("crash.device.gles") {
                NativeCrash.glEsVersion(
                    context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion,
                )
            }.getOrDefault("?")
        val vulkan =
            catchReported("crash.device.vulkan") {
                packageManager.systemAvailableFeatures
                    .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                    ?.let { NativeCrash.vulkanVersion(it.version) }
            }.getOrNull()
        return CrashDevice(
            appVersion = version,
            commit = BuildConfig.GIT_SHA,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            glEsVersion = glEs,
            vulkanVersion = vulkan,
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): ByteArray? =
        catchExpected("crash.trace.read", IOException::class) {
            info.traceInputStream?.use { it.readAtMost(MAX_TRACE_BYTES) }
        }.getOrNull()

    /** Queues a native crash or an ANR of a previous run for GitHub (ADR-116). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun queueForGitHub(
        info: ApplicationExitInfo,
        trace: ByteArray?,
    ) {
        when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> {
                AppProblems.record { device ->
                    ProblemReport.nativeCrash(
                        headline = NativeCrash.signal(info.status) + (info.description?.let { " - $it" } ?: ""),
                        tombstone = trace?.let { NativeCrash.printableStrings(it) },
                        process = info.processName,
                        device = device,
                        atMs = info.timestamp,
                    )
                }
            }

            ApplicationExitInfo.REASON_ANR -> {
                AppProblems.record { device ->
                    ProblemReport.anr(trace?.toString(Charsets.UTF_8), info.description, device, info.timestamp)
                }
            }
        }
    }

    private fun pendingFile(context: Context) = File(File(context.filesDir, DIR), PENDING)

    private fun save(
        context: Context,
        report: CrashReport,
    ) {
        val target = pendingFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$PENDING.tmp")
        temp.writeText(report.toText())
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Could not write $target" }
        }
    }

    /**
     * The end of the app's own log (Android lets an app read its own lines): what MapLibre or the platform said just
     * before the crash, including the lines of the previous process after a native crash.
     */
    private fun logTail(): String? =
        catchExpected("crash.logcat", IOException::class, SecurityException::class) {
            val process =
                ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES.toString())
                    .redirectErrorStream(true)
                    .start()
            val lines = ConcurrentLinkedQueue<String>()
            val reader =
                thread(isDaemon = true, name = "crash-log") {
                    catchExpected("crash.logcat.read", IOException::class) {
                        process.inputStream.bufferedReader().useLines { all -> all.forEach(lines::add) }
                    }
                }
            reader.join(LOG_TIMEOUT_MS)
            process.destroy()
            lines.joinToString("\n")
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun processName(context: Context): String {
        val name =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                catchExpected("crash.process.name", IOException::class) {
                    File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
                }.getOrNull()
            }
        return name?.takeIf { it.isNotEmpty() } ?: context.packageName
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun killProcess() {
        Process.killProcess(Process.myPid())
        exitProcess(EXIT_STATUS)
    }
}
