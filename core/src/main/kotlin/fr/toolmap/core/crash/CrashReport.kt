package fr.toolmap.core.crash

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The phone and the build a crash happened on: what is needed to reproduce it without the phone. */
data class CrashDevice(
    val appVersion: String,
    val commit: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    val abis: List<String>,
    val glEsVersion: String,
    val vulkanVersion: String?,
)

enum class CrashKind(
    val label: String,
) {
    /** An exception thrown by Java or Kotlin code: its stack trace is known. */
    JAVA("java exception"),

    /** The process was killed by a signal in native code (MapLibre, WebRTC...): only the tombstone tells where. */
    NATIVE("native crash"),
}

/**
 * A crash as the user sees it on the report screen and copies or shares it. The text is plain and stable so that it can
 * be pasted into an issue as is.
 */
data class CrashReport(
    val kind: CrashKind,
    val atMillis: Long,
    val process: String,
    val thread: String?,
    val headline: String,
    val details: String,
    val device: CrashDevice,
    val log: String? = null,
) {
    fun toText(zone: ZoneId = ZoneId.systemDefault()): String =
        buildString {
            appendLine("ToolMap ${device.appVersion} (${device.commit})")
            appendLine("when: ${DATE.withZone(zone).format(Instant.ofEpochMilli(atMillis))}")
            appendLine("kind: ${kind.label}")
            appendLine("device: ${device.device}")
            appendLine("android: ${device.androidRelease} (API ${device.apiLevel})")
            appendLine("abis: ${device.abis.joinToString(", ")}")
            appendLine("opengl es: ${device.glEsVersion}")
            appendLine("vulkan: ${device.vulkanVersion ?: "none"}")
            appendLine("process: $process" + (thread?.let { ", thread: $it" } ?: ""))
            appendLine()
            appendLine(headline)
            appendLine()
            appendLine(details.trimEnd())
            if (!log.isNullOrBlank()) {
                appendLine()
                appendLine("--- log ---")
                appendLine(log.trimEnd())
            }
        }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
    }
}

/** Decoding helpers for what Android hands over about a native crash. */
object NativeCrash {
    private val SIGNALS =
        mapOf(
            4 to "SIGILL",
            5 to "SIGTRAP",
            6 to "SIGABRT",
            7 to "SIGBUS",
            8 to "SIGFPE",
            9 to "SIGKILL",
            11 to "SIGSEGV",
            31 to "SIGSYS",
        )

    /** "signal 11 (SIGSEGV)": the status of a native crash exit is the signal number. */
    fun signal(status: Int): String = "signal $status" + (SIGNALS[status]?.let { " ($it)" } ?: "")

    /**
     * The readable parts of a tombstone. Android gives it as a protocol buffer: rather than embedding its schema, every
     * run of printable ASCII of at least [minLength] characters is kept (abort message, signal, library paths, function
     * names of the backtrace), one per line, cut at [maxChars].
     */
    fun printableStrings(
        bytes: ByteArray,
        minLength: Int = 4,
        maxChars: Int = 64_000,
    ): String {
        val out = StringBuilder()
        val run = StringBuilder()
        var last: String? = null

        fun flush() {
            if (run.length >= minLength) {
                val text = run.toString()
                if (text != last && out.length < maxChars) {
                    out.append(text).append('\n')
                    last = text
                }
            }
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e || c == '\t'.code) run.append(c.toChar()) else flush()
        }
        flush()
        if (out.length > maxChars) out.setLength(maxChars)
        return out.toString()
    }

    /** The Vulkan version advertised by `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`: major.minor.patch packed. */
    fun vulkanVersion(packed: Int): String = "${packed ushr 22}.${(packed ushr 12) and 0x3ff}.${packed and 0xfff}"

    /** The OpenGL ES version of `ConfigurationInfo.reqGlEsVersion`: major in the high 16 bits. */
    fun glEsVersion(packed: Int): String = "${packed ushr 16}.${packed and 0xffff}"
}

/** One past exit of a process of the app, as `ActivityManager.getHistoricalProcessExitReasons` lists them. */
data class ProcessExit<out T>(
    val timestampMillis: Long,
    val nativeCrash: Boolean,
    val processName: String,
    /** The platform record it was read from, handed back by the selection. */
    val source: T,
)

object ExitSelection {
    /**
     * The native crash to report at start-up, if any: the latest one of [mainProcess] that happened with the installed
     * APK (after [installedAtMillis]) and after the previous check ([checkedUpToMillis]), so that a crash is reported
     * once and a crash of an older version never is.
     */
    fun <T> nativeCrashToReport(
        exits: List<ProcessExit<T>>,
        mainProcess: String,
        installedAtMillis: Long,
        checkedUpToMillis: Long,
    ): ProcessExit<T>? =
        exits
            .filter {
                it.nativeCrash &&
                    it.processName == mainProcess &&
                    it.timestampMillis > maxOf(installedAtMillis, checkedUpToMillis)
            }.maxByOrNull { it.timestampMillis }

    /** The new bound of the checks: nothing up to it is reported again. */
    fun checkedUpTo(
        exits: List<ProcessExit<*>>,
        checkedUpToMillis: Long,
    ): Long = maxOf(checkedUpToMillis, exits.maxOfOrNull { it.timestampMillis } ?: 0L)
}
