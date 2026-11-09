package fr.toolmap.core.problem

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** What kind of event a report is about; its [label] heads the GitHub issue. */
enum class ProblemKind(
    val label: String,
) {
    /** An error caught by the code that it did not expect ([Problems.report]). */
    ERROR("erreur inattendue"),

    /** An uncaught Java or Kotlin exception: the app stopped. */
    CRASH("plantage"),

    /** The process was killed by a signal in native code (MapLibre, WebRTC…), found at the next start. */
    NATIVE_CRASH("plantage natif"),

    /** The main thread was blocked and Android stopped the app (Application Not Responding). */
    ANR("blocage (ANR)"),
}

/** The phone and the build of a report: nothing that names the user. */
@Serializable
data class ProblemDevice(
    val app: String,
    val commit: String,
    val android: String,
    val api: Int,
    val device: String,
    val abi: String,
)

/**
 * A problem as it is sent to GitHub (ADR-116). **The repository is public**: a report holds no message, name, path,
 * address, coordinate or log line as it was, only the exception types, their stack frames (classes, methods, lines of
 * the app's code), the [ProblemScrubber]ed messages, the build and the phone model.
 */
@Serializable
data class ProblemReport(
    /** Format of the report, checked by the import workflow. */
    val v: Int = FORMAT,
    /** Same bug, same fingerprint ([ProblemFingerprint]): one GitHub issue per fingerprint. */
    val fp: String,
    val kind: ProblemKind,
    val where: String,
    /** The class of the root cause (`java.lang.IllegalStateException`), or the signal of a native crash. */
    val error: String,
    /** The scrubbed message of the root cause. */
    val message: String,
    /** The stack trace, scrubbed, at most [MAX_STACK_CHARS]. */
    val stack: String,
    val thread: String,
    val device: ProblemDevice,
    /** Context values that cannot name anything: numbers, booleans, enum values. */
    val context: Map<String, String> = emptyMap(),
    val count: Int = 1,
    val firstMs: Long,
    val lastMs: Long = firstMs,
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = 1
        const val MAX_STACK_CHARS = 9_000
        const val MAX_FRAMES = 40
        const val MAX_CAUSES = 6

        private val JSON = Json { encodeDefaults = true }
        private val LENIENT = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): ProblemReport = LENIENT.decodeFromString(serializer(), text)

        /** The report of a caught or uncaught [error] ([ProblemKind.ERROR] or [ProblemKind.CRASH]). */
        fun of(
            kind: ProblemKind,
            where: String,
            error: Throwable,
            thread: String,
            device: ProblemDevice,
            atMs: Long,
            context: List<Pair<String, Any?>> = emptyList(),
        ): ProblemReport {
            val root = rootCause(error)
            return ProblemReport(
                fp = ProblemFingerprint.of(kind, where, error),
                kind = kind,
                where = where,
                error = root.javaClass.name,
                message = ProblemScrubber.message(root.message),
                stack = stackText(error),
                thread = ProblemScrubber.message(thread),
                device = device,
                context = safeContext(context),
                firstMs = atMs,
            )
        }

        /**
         * The report of a native crash: [headline] is the signal (`signal 11 (SIGSEGV)`), [tombstone] the printable
         * strings of the tombstone, of which only the library paths and the function names are kept.
         */
        fun nativeCrash(
            headline: String,
            tombstone: String?,
            process: String,
            device: ProblemDevice,
            atMs: Long,
        ): ProblemReport {
            val frames = NativeFrames.of(tombstone)
            val signal = headline.substringBefore(" - ")
            return ProblemReport(
                fp = ProblemFingerprint.native(signal, frames),
                kind = ProblemKind.NATIVE_CRASH,
                where = "native",
                error = signal,
                message = ProblemScrubber.message(headline.substringAfter(" - ", "")),
                stack = frames.joinToString("\n").ifEmpty { "(no readable frame)" }.take(MAX_STACK_CHARS),
                thread = ProblemScrubber.message(process),
                device = device,
                firstMs = atMs,
            )
        }

        /** The report of an ANR: [trace] is the thread dump Android kept, of which the main thread's frames are kept. */
        fun anr(
            trace: String?,
            description: String?,
            device: ProblemDevice,
            atMs: Long,
        ): ProblemReport {
            val frames = AnrTrace.mainFrames(trace)
            return ProblemReport(
                fp = ProblemFingerprint.anr(frames),
                kind = ProblemKind.ANR,
                where = "anr",
                error = "ANR",
                message = ProblemScrubber.message(description),
                stack = frames.joinToString("\n").ifEmpty { "(no main thread in the trace)" }.take(MAX_STACK_CHARS),
                thread = "main",
                device = device,
                firstMs = atMs,
            )
        }

        fun rootCause(error: Throwable): Throwable =
            generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES * 4).last()

        /** Like [Throwable.stackTraceToString], with scrubbed messages, [MAX_FRAMES] frames and [MAX_CAUSES] causes. */
        fun stackText(error: Throwable): String {
            val out = StringBuilder()
            var current: Throwable? = error
            var depth = 0
            val seen = HashSet<Throwable>()
            while (current != null && depth < MAX_CAUSES && seen.add(current)) {
                if (depth > 0) out.append("Caused by: ")
                out.append(current.javaClass.name)
                ProblemScrubber.message(current.message).takeIf { it.isNotEmpty() }?.let { out.append(": ").append(it) }
                out.append('\n')
                val frames = current.stackTrace
                for (frame in frames.take(MAX_FRAMES)) out.append("\tat ").append(frame).append('\n')
                if (frames.size > MAX_FRAMES) out.append("\t... ").append(frames.size - MAX_FRAMES).append(" more\n")
                current = current.cause
                depth++
            }
            return out.toString().take(MAX_STACK_CHARS)
        }

        private fun safeContext(context: List<Pair<String, Any?>>): Map<String, String> =
            context
                .filter { (_, value) -> value is Number || value is Boolean || value is Enum<*> }
                .associate { (key, value) -> key.take(MAX_KEY) to value.toString().take(MAX_VALUE) }

        private const val MAX_KEY = 32
        private const val MAX_VALUE = 32
    }
}

/**
 * Removes from a message what could name the user or their data before it goes to the public repository: addresses
 * (URL, URI, e-mail), file paths, quoted text, decimal numbers (coordinates), long numbers and identifiers (keys,
 * tokens, room ids). What is left says what went wrong (`Unexpected end of stream`, `no such table: tiles`).
 */
object ProblemScrubber {
    const val MAX_MESSAGE = 240

    private val RULES =
        listOf(
            Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""") to "<url>",
            Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""") to "<e-mail>",
            // A path runs to the next colon or quote: a file name may hold spaces (« Mont Blanc.gpx »).
            Regex("""(?:[A-Za-z]:)?(?:[/\\][^/\\:"'()\[\]\r\n]+){2,}[/\\]?""") to "<chemin>",
            Regex("""["«“][^"»”]*["»”]""") to "\"…\"",
            Regex("""'[^'\s][^']*'""") to "'…'",
            Regex("""-?\d+[.,]\d+""") to "<n>",
            Regex("""\b[0-9A-Fa-f]{12,}\b""") to "<id>",
            // Keys and tokens mix letters and digits; a long identifier of the code (a class name) has no digit.
            Regex("""(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{20,}""") to "<id>",
            Regex("""\d{5,}""") to "<n>",
        )

    fun message(text: String?): String {
        if (text.isNullOrBlank()) return ""
        // First what libraries quote of their input (a JSON excerpt may start in the middle of a string).
        var out = LocalRedaction.message(text).orEmpty().replace(Regex("""\s+"""), " ").trim()
        for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
        return if (out.length <= MAX_MESSAGE) out else out.take(MAX_MESSAGE - 1) + "…"
    }
}

/**
 * What the app's own logs (logcat, event log) keep of a message: everything useful to debug, but not the secrets that
 * some libraries copy into their messages. kotlinx.serialization quotes its input after `JSON input:` (the shared
 * state holds the group key), OkHttp quotes a refused header value, an address may carry a key in its query.
 * The public reports go further ([ProblemScrubber]).
 */
object LocalRedaction {
    private val RULES =
        listOf(
            Regex("""JSON input:[\s\S]*""") to "JSON input: …",
            Regex("""(?i)(\bvalue: )[\s\S]*""") to "$1…",
            Regex("""([A-Za-z][A-Za-z0-9+.-]*://[^\s?#]*)[?#]\S*""") to "$1?…",
        )

    fun message(text: String?): String? {
        if (text == null) return null
        var out: String = text
        for ((pattern, replacement) in RULES) out = pattern.replace(out, replacement)
        return out
    }

    /** Like [Throwable.stackTraceToString], every message of the chain passed through [message]. */
    fun stackText(error: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        var depth = 0
        while (current != null && depth < ProblemReport.MAX_CAUSES * 2 && seen.add(current)) {
            if (depth > 0) out.append("Caused by: ")
            out.append(current.javaClass.name)
            message(current.message)?.let { out.append(": ").append(it) }
            out.append('\n')
            for (frame in current.stackTrace) out.append("\tat ").append(frame).append('\n')
            current = current.cause
            depth++
        }
        return out.toString()
    }
}

/**
 * The fingerprint of a problem: the same bug gives the same one whatever the message, the line numbers or the version,
 * so that GitHub gets one issue per bug. It hashes the kind, the place, the class of the root cause and the first
 * frames of the app's own code (`fr.toolmap.`) on the way to it, without line numbers.
 */
object ProblemFingerprint {
    private const val APP_PACKAGE = "fr.toolmap."
    private const val FRAMES = 6
    private const val HEX_CHARS = 16
    private const val DEFAULT_SUFFIX = "\$default"

    fun of(
        kind: ProblemKind,
        where: String,
        error: Throwable,
    ): String {
        val root = ProblemReport.rootCause(error)
        val frames =
            generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
                .take(ProblemReport.MAX_CAUSES)
                .flatMap { it.stackTrace.asSequence() }
                // A call that uses default arguments goes through a synthetic `name$default`: the same place.
                .map { "${it.className}.${it.methodName.removeSuffix(DEFAULT_SUFFIX)}" }
                .toList()
                .let { all -> all.filterIndexed { i, frame -> i == 0 || frame != all[i - 1] } }
        val own = frames.filter { it.startsWith(APP_PACKAGE) }.take(FRAMES)
        val picked = own.ifEmpty { frames.take(FRAMES) }
        // The crash kinds share a fingerprint space: a bug caught here and uncaught there is the same bug.
        val family = if (kind == ProblemKind.ERROR || kind == ProblemKind.CRASH) "java" else kind.name
        return hash(listOf(family, where, root.javaClass.name) + picked)
    }

    fun native(
        signal: String,
        frames: List<String>,
    ): String = hash(listOf("native", signal) + frames.filterNot { it.startsWith("/") }.take(FRAMES))

    fun anr(frames: List<String>): String {
        val calls = frames.map { it.removePrefix("at ").substringBefore('(') }
        val own = calls.filter { it.startsWith(APP_PACKAGE) }.take(FRAMES)
        return hash(listOf("anr") + own.ifEmpty { calls.take(FRAMES) })
    }

    private fun hash(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HEX_CHARS)
    }
}

/** The readable frames of a tombstone's printable strings: library paths and function names only. */
object NativeFrames {
    private const val MAX = 60
    private val LIBRARY = Regex("""^/[\w./~=+@-]+\.so$""")
    private val SYMBOL = Regex("""^(_Z\w+|Java_\w+|[A-Za-z_][\w:~<>]*::[\w~<>]+(\([\w:,*& <>]*\))?)$""")

    fun of(tombstone: String?): List<String> =
        tombstone
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { LIBRARY.matches(it) || SYMBOL.matches(it) }
            // Installed apps live under a random directory: /data/app/~~<random>/fr.toolmap-<random>/lib/…
            .map { it.replace(Regex("""/data/app/[^/]+/[^/]+/"""), "/data/app/…/") }
            .distinct()
            .take(MAX)
            .toList()
}

/** The main thread's frames in the text trace of an ANR. */
object AnrTrace {
    private const val MAX = 60
    private val FRAME = Regex("""^\s*(at [\w$.<>]+\([^)]*\)|- (waiting|locked|sleeping) .*)$""")

    fun mainFrames(trace: String?): List<String> {
        val lines = trace.orEmpty().lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return emptyList()
        return lines
            .drop(start + 1)
            .takeWhile { it.isNotBlank() }
            .filter { FRAME.matches(it) }
            // Frames and lock lines hold class names, methods, lines and monitor addresses: nothing about the user.
            .map { it.trim() }
            .take(MAX)
    }
}
