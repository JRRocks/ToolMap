package fr.toolmap.debugkit

import fr.toolmap.core.eventlog.Event
import fr.toolmap.core.eventlog.EventLine
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A log of [count] lines numbered from 1, as [fr.toolmap.core.eventlog.EventStore] writes them. */
private class FakeLog(
    count: Int,
    val epoch: String = "ab12cd34",
) {
    val lines = ArrayList<String>()

    init {
        add(count)
    }

    fun add(count: Int) {
        repeat(count) {
            val seq = lines.size + 1L
            val event =
                Event(
                    1_790_000_000_000L + seq * 37,
                    "mesh.pos.in",
                    listOf(
                        "member" to "3f2a",
                        "seq" to seq,
                        "fid" to java.lang.Long.toHexString(seq * 2_654_435_761L % 4_294_967_291L),
                    ),
                )
            lines += EventLine.format(seq, event, ZoneOffset.UTC)
        }
    }

    val lastSeq: Long get() = lines.size.toLong()

    fun after(
        seq: Long,
        maxBytes: Int,
    ): List<String> {
        val out = ArrayList<String>()
        var bytes = 0
        for (line in lines.drop(seq.toInt())) {
            if (out.isNotEmpty() && bytes + line.length > maxBytes) break
            out += line
            bytes += line.length + 1
        }
        return out
    }
}

class DiagTest {
    private var now = 1_000_000L

    private fun sender(
        log: FakeLog,
        backlog: Long = 500,
    ) = DiagSender(log.epoch, { log.lastSeq }, log::after, { now }, backlog = backlog)

    /** Sends everything [sender] has to the collector, payloads decoded on the way like on the wire. */
    private fun pump(
        sender: DiagSender,
        collector: DiagCollector,
        out: MutableList<String>,
        lose: (Int) -> Boolean = { false },
    ): Int {
        var n = 0
        while (true) {
            val body = sender.next(MAX) ?: return n
            assertTrue(body.size <= MAX)
            if (!lose(n)) out += collector.accept("3f2a", DiagCodec.decode(body) as DiagPayload.Lines)
            n++
        }
    }

    private fun ack(collector: DiagCollector) =
        (DiagCodec.decode(DiagCodec.encode(collector.acks())) as DiagPayload.Acks).acks["3f2a"]

    @Test
    fun `both kinds of payload round trip and damaged ones are refused`() {
        val lines = listOf("1 1 00:00:00.000 a", "2 2 00:00:00.001 b k=\"v w\"")
        val back = DiagCodec.decode(DiagCodec.encode(DiagPayload.Lines("e1", 7, lines))) as DiagPayload.Lines
        assertEquals("e1", back.epoch)
        assertEquals(7L, back.after)
        assertEquals(lines, back.lines)
        val acks =
            DiagCodec.decode(
                DiagCodec.encode(DiagPayload.Acks(mapOf("3f2a" to Ack("e1", 99)))),
            ) as DiagPayload.Acks
        assertEquals(99L, acks.acks.getValue("3f2a").seq)
        assertNull(DiagCodec.decode(byteArrayOf(1, 2, 3, 4)))
        assertNull(DiagCodec.decode(DiagCodec.encode(DiagPayload.Acks(emptyMap())).copyOf(3)))
    }

    @Test
    fun `lines are packed up to the size limit, a too long line is cut`() {
        val log = FakeLog(3_000)
        val (body, count) = assertNotNull(DiagCodec.packLines(log.epoch, 0, log.lines, MAX))
        assertTrue(body.size <= MAX)
        assertTrue(count > 50, "only $count lines in a payload")
        val long = "1 1 00:00:00.000 big text=" + "x".repeat(50_000)
        val (one, n) = assertNotNull(DiagCodec.packLines("e", 0, listOf(long), MAX))
        assertEquals(1, n)
        val cut = (DiagCodec.decode(one) as DiagPayload.Lines).lines.single()
        assertEquals(DiagCodec.MAX_LINE_CHARS + 1, cut.length)
    }

    @Test
    fun `nothing is sent before the collector shows up, then the backlog and what follows, in order`() {
        val log = FakeLog(2_000)
        val s = sender(log)
        val c = DiagCollector { now }
        val got = ArrayList<String>()
        assertNull(s.next(MAX))
        s.onAck(null)
        pump(s, c, got)
        log.add(30)
        pump(s, c, got)
        assertEquals("# log epoch=ab12cd34 after=1500", got.first())
        assertEquals(log.lines.drop(1_500), got.drop(1))
        s.onAck(ack(c))
        assertEquals(0L, s.behind)
    }

    @Test
    fun `a lost payload is refused after it and sent again once the acknowledgement stops moving`() {
        val log = FakeLog(1_500)
        val s = sender(log, backlog = 1_500)
        val c = DiagCollector { now }
        val got = ArrayList<String>()
        s.onAck(null)
        val sent = pump(s, c, got) { it == 1 }
        assertTrue(sent >= 3, "$sent payloads")
        // Everything after the hole was refused: nothing is doubled, nothing is out of order.
        assertEquals(log.lines.take(got.size - 1), got.drop(1))
        s.onAck(ack(c))
        assertNull(s.next(MAX))
        now += DiagSender.REWIND_AFTER_MS
        pump(s, c, got)
        assertEquals(log.lines, got.drop(1))
        assertEquals(1, s.rewinds)
        s.onAck(ack(c))
        assertEquals(0L, s.behind)
    }

    @Test
    fun `an app restarted resumes where the collector stands, a new log is marked`() {
        val log = FakeLog(800)
        val c = DiagCollector { now }
        val got = ArrayList<String>()
        val first = sender(log)
        first.onAck(null)
        pump(first, c, got)
        log.add(100)
        val second = sender(log)
        second.onAck(ack(c))
        pump(second, c, got)
        assertEquals(log.lines.drop(300), got.drop(1))

        val cleared = FakeLog(20, epoch = "99887766")
        val third = sender(cleared)
        third.onAck(ack(c))
        pump(third, c, got)
        assertEquals("# log epoch=99887766 after=0 previous=ab12cd34", got[got.size - 21])
        assertEquals(cleared.lines, got.takeLast(20))
    }

    @Test
    fun `a collector restarted goes on where it stood, with no line twice`() {
        val log = FakeLog(600)
        val s = sender(log)
        val c = DiagCollector { now }
        val got = ArrayList<String>()
        s.onAck(null)
        pump(s, c, got)
        s.onAck(ack(c))
        val restarted = DiagCollector { now }
        restarted.restore(c.saved())
        log.add(50)
        s.onAck(ack(restarted))
        pump(s, restarted, got)
        assertEquals(listOf("# log epoch=ab12cd34 after=100"), got.filter { it.startsWith("#") })
        assertEquals(log.lines.drop(100), got.drop(1))
        assertEquals(0, s.rewinds)
        assertTrue(DiagCollector { now }.apply { restore("junk;3f2a x;;a b c") }.acks().acks.isEmpty())
    }

    @Test
    fun `lines the phone no longer has are marked as a gap`() {
        val c = DiagCollector { now }
        val log = FakeLog(10)
        c.accept("3f2a", DiagPayload.Lines(log.epoch, 0, log.lines.take(3)))
        val out = c.accept("3f2a", DiagPayload.Lines(log.epoch, 3, log.lines.drop(6)))
        assertEquals("# gap from=4 to=6", out.first())
        assertEquals(log.lines.drop(6), out.drop(1))
        assertEquals(10L, c.acks().acks.getValue("3f2a").seq)
    }

    @Test
    fun `no more than the window is sent without acknowledgement`() {
        val log = FakeLog(20_000)
        val s = DiagSender(log.epoch, { log.lastSeq }, log::after, { now }, backlog = 20_000, window = 1_000)
        val c = DiagCollector { now }
        s.onAck(null)
        val got = ArrayList<String>()
        pump(s, c, got)
        assertTrue(got.size - 1 in 1_000 until 2_000, "${got.size} lines")
    }

    private companion object {
        const val MAX = 4096
    }
}
