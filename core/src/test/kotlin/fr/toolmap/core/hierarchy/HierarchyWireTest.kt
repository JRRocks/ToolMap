package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyWireTest {
    private val rules =
        HierarchyRules(
            RightsTable.DEFAULT.with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP, Reach.SELF),
            VisibilityTable.NONE.with(Role.MEMBER, Audience.SAME_GROUP).with(Role.ADMIN, Audience.EVERYONE),
            signedOps = true,
        )
    private val changes =
        listOf(
            Change.CreateEnsemble(NONCE, "Sortie d'été", "Alpha", rules),
            Change.CreateEnsemble(-1, "Défaut", "Alpha"),
            Change.AdmitMember(B, "Bravo"),
            Change.RemoveMember(B),
            Change.AddAdmin(-2),
            Change.RemoveAdmin(B),
            Change.CreateGroup(G1, "Éclaireurs"),
            Change.CreateGroup(G2, "Sous-groupe", parent = G1),
            Change.RenameGroup(G1, "x".repeat(Names.MAX_BYTES)),
            Change.DeleteGroup(G1),
            Change.AssignMember(C, G1),
            Change.UnassignMember(C),
            Change.SetGroupLeader(G1, C),
            Change.ClearGroupLeader(G1),
            Change.SetEnsembleLeader(B),
        )
    private val ops = changes.mapIndexed { i, change -> Op(0x1234_5678_9ABC_DEF0, A, i + 1, 10L + i, change) }

    private fun sampleState(): HierarchyState =
        staffed(rules.copy(signedOps = false))
            .apply {
                must(Change.AssignMember(C, G1))
                must(Change.AssignMember(D, G1))
                must(Change.SetGroupLeader(G1, D))
                must(Change.CreateGroup(0x103, "Éphémère"))
                must(Change.DeleteGroup(0x103))
            }.state

    @Test
    fun `every change survives the format`() {
        assertEquals(13, changes.map { it::class }.toSet().size, "one sample per kind of change")
        for (op in ops) {
            val bytes = HierarchyWire.encodeOp(op)
            assertEquals(HierarchyWire.Kind.OP, HierarchyWire.kindOf(bytes))
            val back = HierarchyWire.decodeOp(bytes)
            assertEquals(op, back)
            assertEquals(op.change, back.change)
            assertNull(back.proof)
            assertContentEquals(bytes, HierarchyWire.encodeOp(back))
        }
        val block = HierarchyWire.encodeOps(ops)
        assertEquals(HierarchyWire.Kind.OPS, HierarchyWire.kindOf(block))
        assertEquals(ops, HierarchyWire.decodeOps(block))
        assertEquals(emptyList(), HierarchyWire.decodeOps(HierarchyWire.encodeOps(emptyList())))
        // The largest values of the numbers.
        val last = Op(-1, -1, Int.MAX_VALUE, Op.MAX_CLOCK, Change.AssignMember(-1, -1))
        assertEquals(last, HierarchyWire.decodeOp(HierarchyWire.encodeOp(last)))
        // An operation stays small, it travels in the frames of the mesh: header, lengths, content, the longest change.
        val longest = Op(-1, -1, 1, 1, Change.CreateGroup(-1, "x".repeat(Names.MAX_BYTES), parent = -1))
        assertEquals(2 + 2 + 25 + 16 + 1 + Names.MAX_BYTES + 2, HierarchyWire.encodeOp(longest).size)
        assertTrue(ops.drop(2).all { HierarchyWire.encodeOp(it).size <= HierarchyWire.encodeOp(longest).size })
        assertEquals(2 + 2 + 25 + 16 + 2, HierarchyWire.encodeOp(Op(1, A, 1, 1, Change.AssignMember(C, G1))).size)
    }

    @Test
    fun `long lists are cut in blocks`() {
        val many = List(HierarchyWire.MAX_BATCH * 2 + 3) { Op(1, A, it + 1, it + 1L, Change.AddAdmin(B)) }
        val blocks = HierarchyWire.encodeOpBlocks(many)
        assertEquals(3, blocks.size)
        assertEquals(many, blocks.flatMap(HierarchyWire::decodeOps))
        assertFailsWith<IllegalArgumentException> { HierarchyWire.encodeOps(many) }
    }

    @Test
    fun `a summary survives the format`() {
        val summary = Summary(0x77, mapOf(B to 3, A to 12, -5L to Int.MAX_VALUE), digest = -42)
        val bytes = HierarchyWire.encodeSummary(summary)
        assertEquals(HierarchyWire.Kind.SUMMARY, HierarchyWire.kindOf(bytes))
        assertEquals(summary, HierarchyWire.decodeSummary(bytes))
        // The same summary gives the same bytes whatever the order of its map.
        assertContentEquals(
            bytes,
            HierarchyWire.encodeSummary(
                summary.copy(
                    counts =
                        summary.counts.entries.reversed().associate {
                            it.toPair()
                        },
                ),
            ),
        )
        val empty = Summary(0x77, emptyMap(), 0)
        assertEquals(empty, HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(empty)))
    }

    @Test
    fun `a state survives the format`() {
        val state = sampleState()
        val bytes = HierarchyWire.encodeState(state)
        assertEquals(HierarchyWire.Kind.STATE, HierarchyWire.kindOf(bytes))
        val back = HierarchyWire.decodeState(bytes)
        assertEquals(state, back)
        assertContentEquals(bytes, HierarchyWire.encodeState(back))
        val empty = HierarchyState.empty(5)
        assertEquals(empty, HierarchyWire.decodeState(HierarchyWire.encodeState(empty)))
    }

    @Test
    fun `a state that cannot be is refused`() {
        val state = sampleState()
        val broken =
            listOf(
                state.copy(admins = emptySet()),
                state.copy(leader = OUTSIDER),
                state.copy(members = state.members + (C to Member(C, "Charlie", group = 0x999))),
                state.copy(groups = state.groups + (G2 to Group(G2, HierarchyState.ROOT, "Groupe 2", leader = C))),
                state.copy(groups = state.groups + (G2 to Group(G2, G1, "Groupe 2"))),
                state.copy(deletedGroups = setOf(G1)),
                state.copy(name = " "),
                state.copy(members = state.members + (0L to Member(0, "Zéro"))),
            )
        // The format cannot even write an administrator who is not a member: the mark is on the member.
        assertTrue(state.copy(admins = state.admins + OUTSIDER).inconsistency() != null)
        for ((i, bad) in broken.withIndex()) {
            assertTrue(bad.inconsistency() != null, "case $i")
            assertFailsWith<WireException>("case $i") { HierarchyWire.decodeState(HierarchyWire.encodeState(bad)) }
        }
    }

    @Test
    fun `what is not in the one form of this version is refused`() {
        val op = HierarchyWire.encodeOp(ops[2])
        assertFailsWith<WireException> { HierarchyWire.decodeOp(byteArrayOf()) }
        assertFailsWith<WireException> { HierarchyWire.decodeOp(op.copyOf().also { it[0] = 2 }) }
        assertFailsWith<WireException> { HierarchyWire.decodeOps(op) }
        assertFailsWith<WireException> { HierarchyWire.decodeOp(op + 0) }
        assertFailsWith<WireException> {
            HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(Summary(1, emptyMap(), 0)) + 0)
        }
        assertNull(HierarchyWire.kindOf(byteArrayOf(1)))
        assertNull(HierarchyWire.kindOf(byteArrayOf(2, 1)))
        assertNull(HierarchyWire.kindOf(byteArrayOf(1, 9)))

        fun refused(vararg wrong: Op) =
            wrong.forEach {
                assertFailsWith<WireException>("$it") { HierarchyWire.decodeOp(HierarchyWire.encodeOp(it)) }
            }
        refused(
            Op(1, A, 0, 5, Change.AddAdmin(B)),
            Op(1, A, 1, 0, Change.AddAdmin(B)),
            Op(1, 0, 1, 5, Change.AddAdmin(B)),
            Op(1, A, 1, 5, Change.AddAdmin(0)),
            Op(1, A, 1, 5, Change.AdmitMember(B, "")),
            Op(1, A, 1, 5, Change.AdmitMember(B, "a\u0000b")),
            Op(1, A, 1, 5, Change.CreateGroup(G1, "\t")),
            // Half a proof.
            Op(1, A, 1, 5, Change.AddAdmin(B), OpProof(byteArrayOf(1, 2, 3), byteArrayOf())),
        )
        // A name cut in the middle of a character does not read back as it was written: refused, not repaired.
        val name = "é".toByteArray()
        val cut = op.copyOf().also { it[it.size - 3] = name[0] }
        assertFailsWith<WireException> { HierarchyWire.decodeOp(cut) }
        // A code this version does not know: the phone must be updated before it can apply it.
        val unknown = HierarchyWire.encodeOp(ops[3]).also { it[2 + 2 + 24] = 99 }
        assertEquals("unknown change", assertFailsWith<WireException> { HierarchyWire.decodeOp(unknown) }.message)
        // Rules with a bit this version does not know.
        val genesis = HierarchyWire.encodeOp(ops[1])
        val flags = 2 + 2 + 25 + 8 + 1 + "Défaut".toByteArray().size + 1 + "Alpha".length
        assertEquals(0, genesis[flags].toInt())
        assertFailsWith<WireException> { HierarchyWire.decodeOp(genesis.copyOf().also { it[flags] = 2 }) }
    }

    /** Decodes [bytes] as [kind]; `true` when accepted. Anything but a [WireException] fails the test. */
    private fun survives(
        kind: HierarchyWire.Kind,
        bytes: ByteArray,
    ): Boolean =
        try {
            when (kind) {
                HierarchyWire.Kind.OP -> {
                    assertContentEquals(
                        bytes,
                        HierarchyWire.encodeOp(HierarchyWire.decodeOp(bytes)),
                    )
                }

                HierarchyWire.Kind.OPS -> {
                    assertContentEquals(
                        bytes,
                        HierarchyWire.encodeOps(HierarchyWire.decodeOps(bytes)),
                    )
                }

                HierarchyWire.Kind.SUMMARY -> {
                    HierarchyWire.decodeSummary(bytes)
                }

                HierarchyWire.Kind.STATE -> {
                    assertNull(HierarchyWire.decodeState(bytes).inconsistency())
                }
            }
            true
        } catch (_: WireException) {
            false
        }

    @Test
    fun `damaged or made-up bytes are refused without anything else than a refusal`() {
        val random = Random(20261113)
        val signed = OpAuth.sign(ops[6], OpAuth.signer(OpAuth.generateKey()))
        val samples =
            ops.map { HierarchyWire.Kind.OP to HierarchyWire.encodeOp(it) } +
                listOf(
                    HierarchyWire.Kind.OP to HierarchyWire.encodeOp(signed),
                    HierarchyWire.Kind.OPS to HierarchyWire.encodeOps(ops + signed),
                    HierarchyWire.Kind.SUMMARY to HierarchyWire.encodeSummary(Summary(7, mapOf(A to 3, B to 9), 11)),
                    HierarchyWire.Kind.STATE to HierarchyWire.encodeState(sampleState()),
                )
        var accepted = 0
        var refused = 0

        fun attempt(
            kind: HierarchyWire.Kind,
            bytes: ByteArray,
        ) {
            if (survives(kind, bytes)) accepted++ else refused++
        }
        for ((kind, bytes) in samples) {
            assertTrue(survives(kind, bytes), "the sample itself")
            // Cut anywhere.
            for (length in 0 until bytes.size) assertTrue(!survives(kind, bytes.copyOf(length)), "$kind cut at $length")
            // Any byte changed.
            for (i in bytes.indices) {
                repeat(4) {
                    attempt(
                        kind,
                        bytes.copyOf().also {
                            it[i] = (it[i].toInt() xor (1 + random.nextInt(255))).toByte()
                        },
                    )
                }
            }
            // Noise after a good header, and read as every other kind.
            repeat(300) {
                attempt(kind, bytes.copyOf(2) + random.nextBytes(random.nextInt(200)))
            }
            for (other in HierarchyWire.Kind.entries) {
                if (other != kind) assertTrue(!survives(other, bytes), "$kind read as $other")
            }
        }
        repeat(2000) {
            val noise = random.nextBytes(random.nextInt(120))
            for (kind in HierarchyWire.Kind.entries) attempt(kind, noise)
        }
        assertTrue(refused > 10_000, "$refused refused")
        assertTrue(accepted > 100, "$accepted accepted: changes that still mean something are read")
    }
}
