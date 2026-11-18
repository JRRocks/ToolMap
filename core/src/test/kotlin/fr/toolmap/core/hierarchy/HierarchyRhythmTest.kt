package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.ShareCadence
import fr.toolmap.core.p2p.WireException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rhythm of the positions of an operation (P39.15, the user's answers of 2026-11-18, spec § 10 octies): one
 * setting of the ensemble, signed, the same on every phone, a minute until someone changes it, 1 s to 24 h.
 */
class HierarchyRhythmTest {
    /** [deployed], C leads the ensemble and D is its deputy, E leads G2 and F is its deputy, I a member, H waits. */
    private fun staffedOperation(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
        deployed(rules).apply {
            must(leader(ROOT, C))
            must(deputy(ROOT, D))
            must(leader(G2, E))
            must(deputy(G2, F))
            must(admit(I, "India"))
            must(Change.AssignMember(I, G1))
            must(admit(H, "Hotel"))
        }

    /** The rules of 0.4.261: the same table, without the right of the rhythm (it did not exist). */
    private val rulesBefore =
        HierarchyRules(
            rights =
                Role.entries.fold(RightsTable.DEFAULT) { table, role ->
                    table.withMask(Operation.SET_POSITION_INTERVAL, role, 0)
                },
        )

    @Test
    fun `a minute from the creation`() {
        val a = newEnsemble()
        assertEquals(60, HierarchyState.DEFAULT_POSITION_INTERVAL_S)
        assertEquals(60, a.state.positionIntervalS)
        assertEquals(60, HierarchyState.empty(1).positionIntervalS)
        // The minute again changes nothing.
        assertEquals(Rejection.NO_CHANGE, a.refused(Change.SetPositionInterval(60)))
    }

    @Test
    fun `the creator, the administrators, the leader of the operation and its deputy set it, nobody else`() {
        val a = staffedOperation()
        val change = Change.SetPositionInterval(30)
        // F3 A: the first right of the leader of the operation, outside the hierarchy; its deputy has its rights.
        for (author in listOf(A, B, C, D)) assertNull(HierarchyReducer.check(a.state, author, change), "$author")
        // The leaders of the groups, their deputies, the members and who waits only read it.
        for (author in listOf(E, F, I, H)) {
            assertEquals(Rejection.FORBIDDEN, HierarchyReducer.check(a.state, author, change), "$author")
            assertEquals(Rejection.FORBIDDEN, a.copyFor(author).refused(change), "$author")
        }
        assertEquals(Rejection.NOT_A_MEMBER, HierarchyReducer.check(a.state, OUTSIDER, change))
        // A leader of a group who is also an administrator may, as an administrator.
        val admin = a.state.copy(admins = a.state.admins + E)
        assertNull(HierarchyReducer.check(admin, E, change))
        // The leader of the operation who stops being one loses it.
        val after = a.copyFor(A).apply { must(Change.ClearPost(ROOT, Post.LEADER)) }
        assertEquals(Rejection.FORBIDDEN, HierarchyReducer.check(after.state, C, change))
        assertNull(HierarchyReducer.check(after.state, D, change), "the deputy holds its post")
        // R5 unchanged: the leader of the operation still has no right over the hierarchy.
        assertEquals(Rejection.FORBIDDEN, a.copyFor(C).refused(Change.CreateGroup(G3, hidden("Groupe 3"))))
    }

    @Test
    fun `every phone agrees on the rhythm, whatever the order it receives the operations in`() {
        val a = staffedOperation()
        val c = a.copyFor(C)
        val d = a.copyFor(D)
        val i = a.copyFor(I)
        // The leader of the operation and its deputy set it at the same time, each on its own phone.
        val byC = c.must(Change.SetPositionInterval(30))
        val byD = d.must(Change.SetPositionInterval(300))
        assertEquals(byC.clock, byD.clock)
        assertEquals(30, c.state.positionIntervalS)
        assertEquals(300, d.state.positionIntervalS)
        // C < D: at equal clocks C's comes first, D's last, on every phone.
        sync(c, i)
        sync(d, i)
        sync(c, d)
        for (phone in listOf(c, d, i)) assertEquals(300, phone.state.positionIntervalS)
        assertSame(c, d)
        assertSame(c, i)
        // A phone that restores the operations from its storage reads the same.
        val stored = HierarchyWire.encodeOpBlocks(i.ops()).flatMap(HierarchyWire::decodeOps)
        assertEquals(300, HierarchyReplica.restore(i.ensembleId, I, stored, keysOf(I)).state.positionIntervalS)
        // The member that waits for a group reads it too: it sends nothing, but it is told.
        val h = a.copyFor(H)
        sync(c, h)
        assertEquals(300, h.state.positionIntervalS)
    }

    @Test
    fun `out of 1 s to 24 h, refused by every phone`() {
        assertEquals(
            ShareCadence.MIN_INTERVAL_S,
            HierarchyState.MIN_POSITION_INTERVAL_S,
            "the window of « Entre amis »",
        )
        assertEquals(
            ShareCadence.MAX_INTERVAL_S,
            HierarchyState.MAX_POSITION_INTERVAL_S,
            "the window of « Entre amis »",
        )
        val a = staffedOperation()
        for (seconds in listOf(0, -1, 86_401, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertTrue(Change.SetPositionInterval(seconds).isMalformed(), "$seconds")
            assertEquals(Rejection.MALFORMED, a.refused(Change.SetPositionInterval(seconds)), "$seconds")
            // Signed by hand and sent: the format refuses it, and so would a phone that received the operation.
            val forged = signedOp(a.ensembleId, A, 100, 1_000, Change.SetPositionInterval(seconds))
            assertEquals(AddResult.MALFORMED, a.copyFor(B).add(forged), "$seconds")
            assertFailsWith<WireException>("$seconds") { HierarchyWire.decodeOp(HierarchyWire.encodeOp(forged)) }
        }
        a.must(Change.SetPositionInterval(1))
        assertEquals(1, a.state.positionIntervalS)
        // Both ends of the window are values.
        a.must(Change.SetPositionInterval(HierarchyState.MAX_POSITION_INTERVAL_S))
        assertEquals(86_400, a.state.positionIntervalS)
    }

    @Test
    fun `an operation of 0_4_261 reads a minute, and nobody changes it`() {
        // Its first operation carries the rules of then: no cell for the rhythm.
        val old = HierarchyReplica.create(keysOf(A), hidden("Alpha"), hidden("Sortie"), NONCE, rulesBefore)
        old.apply {
            must(admit(B, "Bravo"))
            must(Change.AddAdmin(B))
            must(admit(C, "Charlie"))
            must(leader(ROOT, C))
        }
        // As the phone reads it back from its storage.
        val stored = HierarchyWire.encodeOpBlocks(old.ops()).flatMap(HierarchyWire::decodeOps)
        val restored = HierarchyReplica.restore(old.ensembleId, A, stored, keysOf(A))
        assertEquals(60, restored.state.positionIntervalS)
        assertEquals(old.state, restored.state)
        // The rules are fixed at the creation: nobody has the right, the creator included.
        for (author in listOf(A, B, C)) {
            assertEquals(
                Rejection.FORBIDDEN,
                HierarchyReducer.check(restored.state, author, Change.SetPositionInterval(30)),
                "$author",
            )
        }
        // The state of such an operation is written as before: no flag, no rhythm.
        val bytes = HierarchyWire.encodeState(restored.state)
        assertEquals(0, bytes[FLAGS_AT].toInt() and 2, "no flag of a rhythm")
    }

    @Test
    fun `the state carries the rhythm only when it is not the minute, in one form`() {
        val a = staffedOperation()
        val minute = HierarchyWire.encodeState(a.state)
        assertEquals(a.state, HierarchyWire.decodeState(minute))
        a.must(Change.SetPositionInterval(45))
        val state = a.state
        val bytes = HierarchyWire.encodeState(state)
        assertEquals(minute.size + 4, bytes.size, "4 bytes more, after the flags")
        assertEquals(2, bytes[FLAGS_AT].toInt() and 2)
        val back = HierarchyWire.decodeState(bytes)
        assertEquals(state, back)
        assertEquals(45, back.positionIntervalS)
        assertContentEquals(bytes, HierarchyWire.encodeState(back))

        // The minute written out is not the one form; neither is a value out of the window.
        fun withRhythm(seconds: Int) =
            minute.copyOf(FLAGS_AT + 1).also { it[FLAGS_AT] = (it[FLAGS_AT].toInt() or 2).toByte() } +
                ByteWriter().i32(seconds).toByteArray() +
                minute.copyOfRange(FLAGS_AT + 1, minute.size)
        assertEquals(45, HierarchyWire.decodeState(withRhythm(45)).positionIntervalS)
        for (seconds in listOf(60, 0, 86_401)) {
            assertFailsWith<WireException>("$seconds") { HierarchyWire.decodeState(withRhythm(seconds)) }
        }
        // A flag this version does not know.
        assertFailsWith<WireException> {
            HierarchyWire.decodeState(minute.copyOf().also { it[FLAGS_AT] = (it[FLAGS_AT].toInt() or 4).toByte() })
        }
    }

    @Test
    fun `a phone of 0_4_261 refuses the first operation of a new operation, so never meets the rhythm`() {
        // It reads the rights of the rules cell by cell and refuses an operation it does not know (here: one this
        // version does not know either, in place of the rhythm).
        val bytes = HierarchyWire.encodeOp(newEnsemble().ops().first())
        // Its three cells, side by side (the rights are written operation by operation, role by role).
        val op = Operation.SET_POSITION_INTERVAL
        val cells =
            Role.entries
                .map { it to RightsTable.DEFAULT.mask(op, it) }
                .filter { it.second != 0 }
                .flatMap { (role, mask) -> listOf(op.code, role.code, mask) }
                .map(Int::toByte)
        assertEquals(9, cells.size, "creator, administrators, leader of the operation")
        val cell = (0..bytes.size - cells.size).single { at -> cells.indices.all { bytes[at + it] == cells[it] } }
        assertFailsWith<WireException> { HierarchyWire.decodeOp(bytes.copyOf().also { it[cell] = 99 }) }
    }

    private companion object {
        /** `version | kind | ensemble | name`, then the flags. */
        const val FLAGS_AT = 2 + 8 + HiddenName.BYTES
    }
}
