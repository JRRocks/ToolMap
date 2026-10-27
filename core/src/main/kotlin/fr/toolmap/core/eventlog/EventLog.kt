package fr.toolmap.core.eventlog

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** One thing that happened: done by the user or the app (a button, a write to the server) or undergone (GPS off). */
class Event(
    val timeMs: Long,
    val name: String,
    val fields: List<Pair<String, Any?>>,
)

/** Where events go (the files of [EventStore]); called on the thread that logs, so it must not block. */
fun interface EventSink {
    fun write(event: Event)
}

/** Started once with the main process. The debug build adds its test tools this way; a release has none (P21). */
fun interface AppExtension {
    fun start()
}

/**
 * The event log of the app (P21, `docs/specs/event-log.md`): one line per event, kept on the phone. Logging costs
 * almost nothing while no [EventSink] is installed (JVM tests); [detail] events are kept only while [verbose] is on,
 * which the test environment of the debug build turns on.
 *
 * Gauges are values read on demand (the state of the server connection, the members of the group): components declare
 * them with [gauge] and the debug build writes them all in its periodic snapshots.
 */
object EventLog {
    /** Keeps the [detail] events too (the high-rate ones: every frame, every keep-alive). */
    @Volatile
    var verbose = false

    @Volatile
    private var sinks: List<EventSink> = emptyList()

    private val gauges = ConcurrentHashMap<String, () -> Map<String, Any?>>()

    /** The clock of the events; tests set their own. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    val isOn: Boolean get() = sinks.isNotEmpty()

    @Synchronized
    fun install(sink: EventSink) {
        sinks = sinks + sink
    }

    @Synchronized
    fun uninstall(sink: EventSink) {
        sinks = sinks - sink
    }

    /** Logs [name] (dotted, lowercase: `relay.write`) with its [fields]; `null` values are left out. */
    fun event(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val targets = sinks
        if (targets.isEmpty()) return
        val e = Event(clock(), name, fields.filter { it.second != null })
        for (sink in targets) {
            try {
                sink.write(e)
            } catch (ex: RuntimeException) {
                // The log must never break what it observes.
            }
        }
    }

    /** Waits at most [timeoutMs] until the events logged so far are on disk (the process is about to die). */
    fun flush(timeoutMs: Long) {
        for (sink in sinks) (sink as? EventStore)?.flush(timeoutMs)
    }

    /** Like [event], kept only while [verbose] is on. */
    fun detail(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        if (verbose) event(name, *fields)
    }

    /** Declares a gauge read by the snapshots; closing the result removes it (only if it is still this one). */
    fun gauge(
        name: String,
        read: () -> Map<String, Any?>,
    ): AutoCloseable {
        gauges[name] = read
        return AutoCloseable { gauges.remove(name, read) }
    }

    /** Every gauge, read now; one that fails is reported as such instead of stopping the others. */
    fun readGauges(): Map<String, Map<String, Any?>> =
        gauges.entries.sortedBy { it.key }.associate { (name, read) ->
            name to
                try {
                    read()
                } catch (e: RuntimeException) {
                    mapOf("error" to e.javaClass.simpleName)
                }
        }
}

/**
 * The text of a logged event: `<seq> <epoch ms> <HH:mm:ss.SSS> <name> k=v k="v w"`. Values with a space, a quote, an
 * equals sign or a line break, and empty ones, are quoted with `\"`, `\\`, `\n` escaped.
 */
object EventLine {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun format(
        seq: Long,
        event: Event,
        zone: ZoneId,
    ): String {
        val sb = StringBuilder(64 + event.fields.size * 16)
        sb.append(seq).append(' ').append(event.timeMs).append(' ')
        sb.append(TIME.withZone(zone).format(Instant.ofEpochMilli(event.timeMs))).append(' ').append(event.name)
        for ((key, value) in event.fields) {
            if (value == null) continue
            sb.append(' ').append(key).append('=')
            appendValue(sb, value)
        }
        return sb.toString()
    }

    /** The sequence number a line starts with, `null` for anything else (a cut line). */
    fun seqOf(line: String): Long? {
        val end = line.indexOf(' ')
        if (end <= 0) return null
        return line.substring(0, end).toLongOrNull()
    }

    /** The fields of [line] (after the name), unquoted; for the tests and the readers of the log. */
    fun fieldsOf(line: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = nthSpace(line, FIELDS_START) ?: return out
        while (i < line.length) {
            while (i < line.length && line[i] == ' ') i++
            val eq = line.indexOf('=', i)
            if (eq < 0) break
            val key = line.substring(i, eq)
            i = eq + 1
            val value = StringBuilder()
            if (i < line.length && line[i] == '"') {
                i++
                while (i < line.length && line[i] != '"') {
                    val c = line[i]
                    if (c == '\\' && i + 1 < line.length) {
                        i++
                        value.append(if (line[i] == 'n') '\n' else line[i])
                    } else {
                        value.append(c)
                    }
                    i++
                }
                i++
            } else {
                while (i < line.length && line[i] != ' ') value.append(line[i++])
            }
            out[key] = value.toString()
        }
        return out
    }

    /** The event name of [line] (fourth token). */
    fun nameOf(line: String): String? {
        val start = nthSpace(line, FIELDS_START - 1) ?: return null
        val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
        return line.substring(start, end)
    }

    private fun nthSpace(
        line: String,
        n: Int,
    ): Int? {
        var at = -1
        repeat(n) {
            at = line.indexOf(' ', at + 1)
            if (at < 0) return null
        }
        return at + 1
    }

    private fun appendValue(
        sb: StringBuilder,
        value: Any,
    ) {
        val text =
            when (value) {
                is Double -> if (value.isNaN() || value.isInfinite()) value.toString() else trim(value)
                is Float -> trim(value.toDouble())
                else -> value.toString()
            }
        val plain = text.isNotEmpty() && text.none { it == ' ' || it == '"' || it == '=' || it == '\n' || it == '\\' }
        if (plain) {
            sb.append(text)
            return
        }
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** At most 7 decimals (1 cm in latitude), no trailing zeros. */
    private fun trim(d: Double): String {
        if (kotlin.math.abs(d) >= LARGE) return Math.round(d).toString()
        val rounded = Math.round(d * SCALE) / SCALE
        val s = rounded.toBigDecimal().stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    private const val SCALE = 1e7

    /** Beyond this, `d * SCALE` would not fit in a long: written as a whole number. */
    private const val LARGE = 1e11

    /** Fields start after the fourth space: seq, epoch, time, name. */
    private const val FIELDS_START = 4
}
