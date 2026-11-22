package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The colour of a group (P39.14, the user's answer L1 of 2026-11-16, spec § 19): one field of the group, signed, the
 * same on every phone, set by whoever may rename the group (the creator and the administrators).
 */
class HierarchyGroupColorTest {
    private val red = 0xD32F2F
    private val teal = 0x14918C

    @Test
    fun `none until an administrator chooses one, then the same on every phone`() {
        val a = deployed()
        assertNull(a.state.groups.getValue(G1).rgb, "the screens give the colour of its place until then")
        val b = a.copyFor(B)
        b.must(Change.SetGroupColor(G1, red))
        assertEquals(red, b.state.groups.getValue(G1).rgb)
        val c = a.copyFor(C)
        sync(b, c)
        sync(b, a)
        assertEquals(red, a.state.groups.getValue(G1).rgb)
        assertEquals(red, c.state.groups.getValue(G1).rgb)
        assertNull(c.state.groups.getValue(G2).rgb, "only that group")
        // The same colour again changes nothing.
        assertEquals(Rejection.NO_CHANGE, a.refused(Change.SetGroupColor(G1, red)))
        // Read back from the storage, the same.
        val stored = HierarchyWire.encodeOpBlocks(c.ops()).flatMap(HierarchyWire::decodeOps)
        assertEquals(red, HierarchyReplica.restore(c.ensembleId, C, stored, keysOf(C)).state.groups.getValue(G1).rgb)
    }

    @Test
    fun `who may rename the group may colour it, nobody else`() {
        val a = deployed().apply { must(leader(G1, C)) }
        val change = Change.SetGroupColor(G1, teal)
        for (author in listOf(A, B)) assertNull(HierarchyReducer.check(a.state, author, change), "$author")
        // The leader of the group, the members and who waits do not (R5: the leaders have no right over the hierarchy).
        for (author in listOf(C, D, E)) {
            assertEquals(Rejection.FORBIDDEN, HierarchyReducer.check(a.state, author, change), "$author")
            assertEquals(Rejection.FORBIDDEN, a.copyFor(author).refused(change), "$author")
        }
        assertEquals(Rejection.NOT_A_MEMBER, HierarchyReducer.check(a.state, OUTSIDER, change))
        assertEquals(Operation.RENAME_GROUP, change.operation)
    }

    @Test
    fun `a group that does not exist, a colour out of 24 bits, refused by every phone`() {
        val a = deployed()
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.SetGroupColor(G3, red)))
        a.must(Change.DeleteGroup(G2))
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.SetGroupColor(G2, red)))
        for (rgb in listOf(-1, 0x1000000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertTrue(Change.SetGroupColor(G1, rgb).isMalformed(), "$rgb")
            assertEquals(Rejection.MALFORMED, a.refused(Change.SetGroupColor(G1, rgb)), "$rgb")
            val forged = signedOp(a.ensembleId, A, 100, 1_000, Change.SetGroupColor(G1, rgb))
            assertEquals(AddResult.MALFORMED, a.copyFor(B).add(forged), "$rgb")
            assertFailsWith<WireException>("$rgb") { HierarchyWire.decodeOp(HierarchyWire.encodeOp(forged)) }
        }
        assertTrue(Change.SetGroupColor(0L, red).isMalformed(), "no group 0")
        // Both ends of 24 bits are colours.
        a.must(Change.SetGroupColor(G1, 0))
        a.must(Change.SetGroupColor(G1, Group.MAX_RGB))
        assertEquals(0xFFFFFF, a.state.groups.getValue(G1).rgb)
    }

    @Test
    fun `a colour gone with its group, and a group made again starts without one`() {
        val a = deployed()
        a.must(Change.SetGroupColor(G2, red))
        a.must(Change.CreateGroup(G3, hidden("Groupe 3")))
        a.must(Change.AssignMember(E, G1))
        a.must(Change.AssignMember(F, G1))
        a.must(Change.DeleteGroup(G2))
        assertTrue(G2 !in a.state.groups)
        assertNull(a.state.groups.getValue(G3).rgb)
        assertEquals(a.state, HierarchyWire.decodeState(HierarchyWire.encodeState(a.state)))
    }

    @Test
    fun `an operation of 0_4_276 reads as before, and its groups can be coloured`() {
        // The rules of 0.4.276 are the ones of now: the colour uses the line of renaming a group, which they have.
        val a = deployed()
        val before = HierarchyWire.encodeState(a.state)
        assertEquals(0, before[FLAGS_AT].toInt() and GROUP_COLORS, "a state without a colour is written as before")
        assertEquals(a.state, HierarchyWire.decodeState(before))
        assertNull(HierarchyReducer.check(a.state, B, Change.SetGroupColor(G1, red)))
    }

    @Test
    fun `the state carries the colours only when a group has one, in one form`() {
        val a = deployed()
        val plain = HierarchyWire.encodeState(a.state)
        a.must(Change.SetGroupColor(G2, teal))
        a.must(Change.SetGroupColor(G1, red))
        val bytes = HierarchyWire.encodeState(a.state)
        assertEquals(plain.size + 2 + 2 * 12, bytes.size, "a count, then a group and a colour for each")
        assertEquals(GROUP_COLORS, bytes[FLAGS_AT].toInt() and GROUP_COLORS)
        val back = HierarchyWire.decodeState(bytes)
        assertEquals(a.state, back)
        assertContentEquals(bytes, HierarchyWire.encodeState(back))
        // The flag without a colour, a colour of a group that does not exist or out of 24 bits: not the one form.
        val groupsEnd = plain.size - 2 // the deleted groups (none) follow the groups

        fun withColors(
            vararg entries: Pair<Long, Int>,
            count: Int = entries.size,
        ): ByteArray {
            val w = ByteWriter().u16(count)
            for ((id, rgb) in entries) w.u64(id).i32(rgb)
            val flagged = plain.copyOf().also { it[FLAGS_AT] = (it[FLAGS_AT].toInt() or GROUP_COLORS).toByte() }
            return flagged.copyOf(groupsEnd) + w.toByteArray() + flagged.copyOfRange(groupsEnd, flagged.size)
        }
        assertEquals(red, HierarchyWire.decodeState(withColors(G1 to red)).groups.getValue(G1).rgb)
        for (bad in listOf(
            withColors(),
            withColors(G3 to red),
            withColors(G1 to -1),
            withColors(G1 to red, G1 to teal),
        )) {
            assertFailsWith<WireException> { HierarchyWire.decodeState(bad) }
        }
        // A flag this version does not know.
        assertFailsWith<WireException> {
            HierarchyWire.decodeState(plain.copyOf().also { it[FLAGS_AT] = (it[FLAGS_AT].toInt() or 8).toByte() })
        }
    }

    private companion object {
        /** `version | kind | ensemble | name`, then the flags. */
        const val FLAGS_AT = 2 + 8 + HiddenName.BYTES
        const val GROUP_COLORS = 4
    }
}
