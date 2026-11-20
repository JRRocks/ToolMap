package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The summary's addressee (P39.17, ADR-138): written only when it is one phone, read the same way back. */
class OperationSummaryTest {
    private val summary = Summary(0x1234L, mapOf(7L to 3, 9L to 1), 0x55L)
    private val objects = mapOf(Channel.ENSEMBLE to 9L)

    @Test
    fun `a summary for one phone carries it, one for the whole room reads as before P39_17`() {
        val forOne = OperationSummary(summary, 7L, 1, objects, to = 42L)
        val back = OperationSummary.decode(forOne.encode())
        assertEquals(42L, back.to)
        assertEquals(summary, back.summary)
        assertEquals(7L, back.keysDigest)
        assertEquals(1, back.namesMissing)
        assertEquals(objects, back.objects)

        val forAll = OperationSummary(summary, 7L, 1, objects)
        assertEquals(0L, OperationSummary.decode(forAll.encode()).to)
        // The addressee is 8 bytes at the end, and nothing at all for the whole room: the bytes of before P39.17.
        assertEquals(forAll.encode().size + 8, forOne.encode().size)
        assertContentEquals(forAll.encode(), forOne.encode().copyOf(forAll.encode().size))
        assertContentEquals(beforeP3917(forAll), forAll.encode())
    }

    @Test
    fun `an addressee of 0 written out, or cut short, is refused`() {
        val forAll = OperationSummary(summary, 7L, 1, objects).encode()
        assertFailsWith<WireException> { OperationSummary.decode(forAll + ByteArray(8)) }
        val forOne = OperationSummary(summary, 7L, 1, objects, to = 42L).encode()
        assertFailsWith<WireException> { OperationSummary.decode(forOne.copyOf(forOne.size - 1)) }
        assertFailsWith<WireException> { OperationSummary.decode(forOne + byteArrayOf(0)) }
    }

    /** How a summary was written up to 0.4.271. */
    private fun beforeP3917(s: OperationSummary): ByteArray {
        val ops = HierarchyWire.encodeSummary(s.summary)
        val w = ByteWriter().u16(ops.size).bytes(ops).u64(s.keysDigest).u16(s.namesMissing)
        w.u16(s.objects.size)
        for ((channel, digest) in s.objects) OperationWire.writeChannel(w, channel).u64(digest)
        return w.toByteArray()
    }
}
