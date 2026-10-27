package fr.toolmap.debugkit

import fr.toolmap.core.eventlog.EventLine

/**
 * Puts together the logs the members send (`docs/specs/event-log.md` §4): keeps each member's lines in order, once,
 * and says what it has in [acks]. A payload that starts after a hole (a frame lost on the way) is refused: the sender
 * sends again from the last line acknowledged. Lines the phone itself no longer had (old files) and a new log (data
 * cleared, app reinstalled) are marked with a `#` line.
 */
class DiagCollector(
    private val clock: () -> Long,
) {
    private class Member(
        var epoch: String,
        var seq: Long,
        var heardAt: Long,
        var lines: Long = 0,
        var refused: Long = 0,
    )

    private val members = LinkedHashMap<String, Member>()

    /** The lines of [payload] to keep, in order, with the `#` markers; empty when it is refused or brings nothing. */
    @Synchronized
    fun accept(
        member: String,
        payload: DiagPayload.Lines,
    ): List<String> {
        val now = clock()
        val out = ArrayList<String>()
        var m = members[member]
        if (m == null || m.epoch != payload.epoch) {
            out += "# log epoch=${payload.epoch} after=${payload.after}" + (m?.let { " previous=${it.epoch}" } ?: "")
            m = Member(payload.epoch, payload.after, now)
            members[member] = m
        } else if (payload.after > m.seq) {
            m.refused++
            m.heardAt = now
            return out
        }
        m.heardAt = now
        for (line in payload.lines) {
            val n = EventLine.seqOf(line) ?: continue
            if (n <= m.seq) continue
            if (n > m.seq + 1) out += "# gap from=${m.seq + 1} to=${n - 1}"
            out += line
            m.seq = n
            m.lines++
        }
        return out
    }

    /** What the collector has of each member's log, and how long the phones keep their screen on ([awakeS]). */
    @Synchronized
    fun acks(awakeS: Long = 0): DiagPayload.Acks =
        DiagPayload.Acks(members.mapValues { (_, m) -> Ack(m.epoch, m.seq) }, awakeS)

    /** What [acks] holds, as text kept across a restart of the collector: `hex epoch seq;…`. */
    @Synchronized
    fun saved(): String = members.entries.joinToString(";") { (id, m) -> "$id ${m.epoch} ${m.seq}" }

    /** Takes back what [saved] gave before a restart: each member goes on where it stood, with no line twice. */
    @Synchronized
    fun restore(saved: String) {
        val now = clock()
        for (entry in saved.split(';')) {
            val parts = entry.split(' ')
            val seq = parts.getOrNull(2)?.toLongOrNull() ?: continue
            members[parts[0]] = Member(parts[1], seq, now)
        }
    }

    /** One line per member: `hex seq=… lines=… refused=… heardS=…`. */
    @Synchronized
    fun state(): List<String> {
        val now = clock()
        return members.map { (id, m) ->
            "$id epoch=${m.epoch} seq=${m.seq} lines=${m.lines} refused=${m.refused} heardS=${(now - m.heardAt) / MS_PER_S}"
        }
    }

    private companion object {
        const val MS_PER_S = 1000
    }
}
