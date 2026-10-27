package fr.toolmap.debugkit

import fr.toolmap.core.eventlog.EventLine

/**
 * Ships the event log of this phone to the collector, in order and without holes (`docs/specs/event-log.md` §4).
 *
 * Nothing is sent before the collector shows up (its first [onAck]): the log is then sent from where the collector
 * stands, or from the last [backlog] lines for a collector that has nothing of this log yet. Frames get lost (a stream
 * cut, a write refused): when the collector's acknowledgement does not move for [rewindAfterMs] while lines are on
 * their way, sending starts again after the last line it has.
 */
class DiagSender(
    private val epoch: String,
    private val lastSeq: () -> Long,
    private val linesAfter: (seq: Long, maxBytes: Int) -> List<String>,
    private val clock: () -> Long,
    private val backlog: Long = BACKLOG,
    private val rewindAfterMs: Long = REWIND_AFTER_MS,
    private val window: Long = WINDOW,
) {
    /** Last line sent; `null` until the collector shows up. */
    private var sentUpTo: Long? = null
    private var acked = 0L
    private var waitingSince = 0L

    var frames = 0L
        private set
    var bytes = 0L
        private set
    var rewinds = 0
        private set

    val started: Boolean @Synchronized get() = sentUpTo != null

    /** How many lines of the log the collector does not have yet. */
    val behind: Long @Synchronized get() = if (sentUpTo == null) 0 else (lastSeq() - acked).coerceAtLeast(0)

    /** The collector says what it has of this phone's log ([ack] `null`: nothing yet). */
    @Synchronized
    fun onAck(ack: Ack?) {
        val now = clock()
        val known = ack?.takeIf { it.epoch == epoch }?.seq
        val sent = sentUpTo
        if (sent == null) {
            val from = known ?: (lastSeq() - backlog).coerceAtLeast(0)
            sentUpTo = from
            acked = from
            waitingSince = now
            return
        }
        if (known != null && known > acked) {
            acked = known
            waitingSince = now
            if (known > sent) sentUpTo = known
        }
    }

    /** The next payload to send (at most [maxBytes]), `null` when there is nothing to send now. */
    @Synchronized
    fun next(maxBytes: Int): ByteArray? {
        var from = sentUpTo ?: return null
        val now = clock()
        if (from > acked && now - waitingSince >= rewindAfterMs) {
            from = acked
            sentUpTo = acked
            waitingSince = now
            rewinds++
        }
        if (from - acked >= window) return null
        if (from >= lastSeq()) return null
        val lines = linesAfter(from, maxBytes * TEXT_PER_PAYLOAD_BYTE)
        if (lines.isEmpty()) return null
        val (body, count) = DiagCodec.packLines(epoch, from, lines, maxBytes) ?: return null
        if (from == acked) waitingSince = now
        sentUpTo = EventLine.seqOf(lines[count - 1]) ?: (from + count)
        frames++
        bytes += body.size
        return body
    }

    /** What this sender stands at, for the status lines. */
    @Synchronized
    fun state(): List<Pair<String, Any?>> =
        listOf(
            "started" to started,
            "sentUpTo" to sentUpTo,
            "acked" to acked,
            "behind" to behind,
            "frames" to frames,
            "bytes" to bytes,
            "rewinds" to rewinds,
        )

    companion object {
        /** Lines of the past sent to a collector that has nothing of this log yet. */
        const val BACKLOG = 2_000L

        const val REWIND_AFTER_MS = 15_000L

        /** Lines sent and not acknowledged yet, at most. */
        const val WINDOW = 4_000L

        /** Log text read for a payload: compression is about 5 to 1, ask for more and keep what fits. */
        private const val TEXT_PER_PAYLOAD_BYTE = 8
    }
}
