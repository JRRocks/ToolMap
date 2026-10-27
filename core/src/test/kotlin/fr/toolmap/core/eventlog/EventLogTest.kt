package fr.toolmap.core.eventlog

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventLogTest {
    private val dir: File = Files.createTempDirectory("eventlog").toFile()
    private val stores = ArrayList<EventStore>()

    private fun store(
        maxFileBytes: Long = EventStore.DEFAULT_MAX_FILE_BYTES,
        memoryLines: Int = EventStore.DEFAULT_MEMORY_LINES,
    ) = EventStore(dir, maxFileBytes, memoryLines, ZoneOffset.UTC).also { stores += it }

    private fun event(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) = Event(1_790_000_000_123L, name, fields.toList())

    @AfterTest
    fun cleanUp() {
        stores.forEach { EventLog.uninstall(it) }
        stores.forEach { it.close() }
        EventLog.verbose = false
        dir.deleteRecursively()
    }

    @Test
    fun `a line holds the number, the time, the name and the fields, quoted when needed`() {
        val line =
            EventLine.format(
                12,
                event(
                    "relay.write",
                    "ch" to "p",
                    "ms" to 85,
                    "why" to "socket closed",
                    "lat" to 45.123456789,
                    "n" to null,
                ),
                ZoneOffset.UTC,
            )
        assertEquals(
            """12 1790000000123 14:13:20.123 relay.write ch=p ms=85 why="socket closed" lat=45.1234568""",
            line,
        )
        assertEquals(12L, EventLine.seqOf(line))
        assertEquals("relay.write", EventLine.nameOf(line))
        assertEquals(
            mapOf("ch" to "p", "ms" to "85", "why" to "socket closed", "lat" to "45.1234568"),
            EventLine.fieldsOf(line),
        )
    }

    @Test
    fun `quotes, backslashes, equal signs, line breaks and empty values survive a round trip`() {
        val text = "a \"b\" c" + '\\' + "d=e\nf"
        val line = EventLine.format(1, event("test.mark", "text" to text, "empty" to ""), ZoneOffset.UTC)
        assertTrue('\n' !in line)
        assertEquals(mapOf("text" to text, "empty" to ""), EventLine.fieldsOf(line))
        assertNull(EventLine.seqOf("cut line"))
    }

    @Test
    fun `details are kept only in verbose mode and a failing gauge does not stop the others`() {
        val s = store()
        EventLog.install(s)
        EventLog.detail("mesh.frame.in")
        EventLog.event("share.fix")
        EventLog.verbose = true
        EventLog.detail("mesh.ping.out")
        assertEquals(listOf("share.fix", "mesh.ping.out"), s.linesAfter(0, 10_000).map { EventLine.nameOf(it) })
        val a = EventLog.gauge("a") { mapOf("x" to 1) }
        val b = EventLog.gauge("b") { throw IllegalStateException() }
        assertEquals(mapOf("x" to 1), EventLog.readGauges()["a"])
        assertEquals(mapOf("error" to "IllegalStateException"), EventLog.readGauges()["b"])
        a.close()
        b.close()
        assertTrue(EventLog.readGauges().keys.none { it == "a" || it == "b" })
    }

    @Test
    fun `numbers go on after a restart, under the same epoch, and old lines come from the files`() {
        val first = store(memoryLines = 4)
        repeat(10) { first.write(event("e$it")) }
        assertEquals(10L, first.lastSeq)
        assertEquals(listOf("e7", "e8", "e9"), first.linesAfter(7, 10_000).map { EventLine.nameOf(it) })
        // Older than what memory keeps: read back from the file.
        first.flush()
        assertEquals(listOf("e2", "e3"), first.linesAfter(2, 10_000).map { EventLine.nameOf(it) }.take(2))
        val epoch = first.epoch
        first.close()
        stores.remove(first)

        val second = store()
        assertEquals(epoch, second.epoch)
        assertEquals(10L, second.lastSeq)
        second.write(event("again"))
        assertEquals(11L, EventLine.seqOf(second.linesAfter(10, 10_000).single()))
    }

    @Test
    fun `a full file rotates to the previous one and both are read`() {
        val s = store(maxFileBytes = 2_000, memoryLines = 2)
        repeat(60) { s.write(event("rotating.event.$it", "pad" to "x".repeat(40))) }
        s.flush()
        assertTrue(File(dir, EventStore.PREVIOUS).isFile)
        assertTrue(File(dir, EventStore.CURRENT).length() <= 2_000 + 200)
        // The oldest lines are gone; the files give the next ones in order, then memory the last ones.
        val numbers = s.linesAfter(0, 100_000).map { EventLine.seqOf(it)!! }
        assertTrue(numbers.first() > 1)
        assertEquals((numbers.first()..numbers.last()).toList(), numbers)
        assertEquals(listOf(59L, 60L), s.linesAfter(58, 100_000).map { EventLine.seqOf(it)!! })
    }

    @Test
    fun `at most the asked number of bytes, but one line at least`() {
        val s = store()
        repeat(5) { s.write(event("big", "pad" to "y".repeat(100))) }
        assertEquals(1, s.linesAfter(0, 10).size)
        assertEquals(2, s.linesAfter(0, 300).size)
        assertTrue(s.linesAfter(5, 1_000).isEmpty())
    }

    @Test
    fun `a new directory gets a new epoch`() {
        val a = store().epoch
        val other = EventStore(Files.createTempDirectory("eventlog2").toFile())
        assertNotEquals(a, other.epoch)
        other.close()
    }
}
