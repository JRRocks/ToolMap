package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The messages a phone keeps of an operation (P39.9, the user's answer R2: « les 500 derniers de chaque fil »). */
class MessageLogTest {
    private val group = Channel.unit(0x51L)

    private fun m(
        id: Long,
        text: String = "message $id",
        author: Long = 7L,
    ) = LoggedMessage(author, id, 1_000L * id, 1_000L * id + 5, text)

    @Test
    fun `a thread keeps its last 500 messages, oldest first, and a message once`() {
        val log = MessageLog(ensembleId = 42L)
        for (i in 1L..520L) assertTrue(log.add(group, m(i)))
        assertTrue(log.add(Channel.ENSEMBLE, m(1)), "the same identifier in another thread is another message")
        val kept = log.threads().getValue(group)
        assertEquals(MessageLog.MAX_PER_THREAD, kept.size)
        assertEquals((21L..520L).toList(), kept.map { it.id })
        assertFalse(log.add(group, m(300)), "played again: not twice")
        assertTrue(log.add(group, m(300, author = 8L)), "another author")
        assertEquals(501, log.size)
    }

    @Test
    fun `the threads of channels no longer read go`() {
        val log = MessageLog(42L)
        log.add(group, m(1))
        log.add(Channel.ENSEMBLE, m(2))
        log.add(Channel.up(0x51L), m(3))
        assertTrue(log.retain(listOf(Channel.ENSEMBLE, group)))
        assertEquals(setOf(Channel.ENSEMBLE, group), log.threads().keys)
        assertFalse(log.retain(listOf(Channel.ENSEMBLE, group)))
        log.clear()
        assertTrue(log.threads().isEmpty())
    }

    @Test
    fun `a text is kept as it travels, cut on a character`() {
        val log = MessageLog(42L)
        val long = "é".repeat(OperationPayloads.MAX_TEXT_BYTES)
        log.add(group, m(1, text = long))
        val kept = log.threads().getValue(group).single().text
        assertEquals(OperationPayloads.MAX_TEXT_BYTES / 2, kept.length)
        assertEquals(OperationPayloads.readMessage(OperationPayloads.message(OperationText(1, 1, long))).text, kept)
    }

    @Test
    fun `the stored messages read back as written, and damage is refused whole`() {
        val log = MessageLog(42L)
        log.add(group, m(1, text = "Départ à 7 h"))
        log.add(group, m(2, text = "Arrivés au col", author = 9L))
        log.add(Channel.LEADERS, m(3, text = ""))
        val bytes = HierarchySnapshot.encodeMessages(log)
        val back = MessageLog(42L)
        HierarchySnapshot.restoreMessages(back, bytes)
        assertEquals(log.threads(), back.threads())
        assertContentEquals(bytes, HierarchySnapshot.encodeMessages(back))

        // Another operation, a cut, a byte changed in a count: refused, and nothing taken.
        assertFailsWith<WireException> { HierarchySnapshot.restoreMessages(MessageLog(43L), bytes) }
        val target = MessageLog(42L)
        assertFailsWith<WireException> { HierarchySnapshot.restoreMessages(target, bytes.copyOf(bytes.size - 2)) }
        assertTrue(target.threads().isEmpty())
        assertFailsWith<WireException> {
            HierarchySnapshot.restoreMessages(target, HierarchySnapshot.encodeNames(NameBook()))
        }
        // A longer thread (another version's limit) keeps its last messages.
        val small = MessageLog(42L, maxPerThread = 1)
        HierarchySnapshot.restoreMessages(small, bytes)
        assertEquals(listOf(2L), small.threads().getValue(group).map { it.id })
    }
}
