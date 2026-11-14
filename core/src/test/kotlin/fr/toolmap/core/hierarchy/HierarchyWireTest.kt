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
            rights = RightsTable.DEFAULT.with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP, Reach.SELF),
            visibility =
                VisibilityTable.NONE.with(
                    Role.MEMBER,
                    Audience.SAME_GROUP,
                ).with(Role.ADMIN, Audience.EVERYONE),
            shares =
                ShareTable.DEFAULT
                    .with(ShareKind.MESSAGE, Role.GROUP_DEPUTY, ShareReach.UP, ShareReach.OWN_UNIT)
                    .with(ShareKind.ALERT, Role.WAITING, ShareReach.WAITING)
                    .with(ShareKind.ROSTER, Role.ADMIN, *ShareReach.entries.toTypedArray()),
            limits = HierarchyLimits(maxMembers = 120, maxGroups = 20, maxDepth = 3),
            signedShares = setOf(ShareKind.MESSAGE, ShareKind.ALERT),
            deputySucceeds = false,
        )
    private val changes =
        listOf(
            genesis(name = "Sortie d'été", rules = rules),
            genesis(name = "Défaut", nonce = -1),
            admit(B, "Bravo"),
            Change.RemoveMember(B),
            Change.AddAdmin(-2),
            Change.RemoveAdmin(B),
            Change.CreateGroup(G1, hidden("Éclaireurs")),
            Change.CreateGroup(G2, hidden("Sous-groupe"), parent = G1),
            Change.RenameGroup(G1, hidden("x".repeat(Names.MAX_BYTES))),
            Change.DeleteGroup(G1),
            Change.AssignMember(C, G1),
            Change.UnassignMember(C),
            leader(G1, C),
            deputy(ROOT, B),
            Change.ClearPost(G1, Post.DEPUTY),
            Change.ClearPost(ROOT, Post.LEADER),
            Change.TransferCreator(B),
            Change.Dissolve,
        )
    private val ops = changes.mapIndexed { i, change -> signedOp(0x1234_5678_9ABC_DEF0, A, i + 1, 10L + i, change) }

    /** What every block starts with, then the length of the content, then ensemble, author, number and clock. */
    private val beforeChange = 2 + 2 + 24
    private val proofBytes = P256.POINT_BYTES + P256.SIGNATURE_BYTES

    private fun sampleState(): HierarchyState =
        deployed(rules)
            .apply {
                must(leader(G1, D))
                must(deputy(G1, C))
                must(leader(ROOT, B))
                must(deputy(ROOT, E))
                must(Change.CreateGroup(G3, hidden("Sous-groupe"), parent = G1))
                must(Change.AssignMember(F, G3))
                must(Change.CreateGroup(0x104, hidden("Éphémère")))
                must(Change.DeleteGroup(0x104))
            }.state

    @Test
    fun `every change survives the format, with its proof`() {
        assertEquals(14, changes.map { it::class }.toSet().size, "one sample per kind of change")
        for (op in ops) {
            val bytes = HierarchyWire.encodeOp(op)
            assertEquals(HierarchyWire.Kind.OP, HierarchyWire.kindOf(bytes))
            val back = HierarchyWire.decodeOp(bytes)
            assertEquals(op, back)
            assertEquals(op.change, back.change)
            assertTrue(back.authentic, "$op")
            assertContentEquals(bytes, HierarchyWire.encodeOp(back))
        }
        val block = HierarchyWire.encodeOps(ops)
        assertEquals(HierarchyWire.Kind.OPS, HierarchyWire.kindOf(block))
        assertEquals(ops, HierarchyWire.decodeOps(block))
        assertEquals(emptyList(), HierarchyWire.decodeOps(HierarchyWire.encodeOps(emptyList())))
        // The largest values of the numbers.
        val last = signedOp(-1, A, Int.MAX_VALUE, Op.MAX_CLOCK, Change.AssignMember(-1, -1))
        assertEquals(last, HierarchyWire.decodeOp(HierarchyWire.encodeOp(last)))
        // An operation stays small, it travels in the frames of the mesh: header, length, content and proof. The
        // longest ones are the two that carry the keys of a member; the others fit in fifty bytes plus the proof.
        // A name weighs the same whatever its length: what travels is its commitment.
        val longest = signedOp(-1, A, 1, 1, Change.CreateGroup(-1, hidden("x".repeat(Names.MAX_BYTES)), parent = -1))
        assertEquals(beforeChange + 1 + 16 + HiddenName.BYTES + proofBytes, HierarchyWire.encodeOp(longest).size)
        assertTrue(ops.drop(3).all { HierarchyWire.encodeOp(it).size <= HierarchyWire.encodeOp(longest).size })
        assertEquals(
            beforeChange + 1 + 16 + proofBytes,
            HierarchyWire.encodeOp(signedOp(1, A, 1, 1, Change.AssignMember(C, G1))).size,
        )
        val admission = HierarchyWire.encodeOp(signedOp(1, A, 1, 1, admit(B, "x".repeat(Names.MAX_BYTES))))
        assertEquals(beforeChange + 1 + 8 + HiddenName.BYTES + 2 * P256.POINT_BYTES + proofBytes, admission.size)
        // No operation carries a name in clear: not even the one that lets a member in.
        val bravo = HierarchyWire.encodeOp(signedOp(1, A, 1, 1, admit(B, "Bravo-en-clair")))
        assertEquals(-1, String(bravo, Charsets.ISO_8859_1).indexOf("Bravo-en-clair"))
        // The first operation of an ensemble, with the rules of the start: the largest of all.
        assertEquals(530, HierarchyWire.encodeOp(ops[1]).size)
        assertEquals(beforeChange + 1 + 16 + proofBytes + HiddenName.BYTES, 206)
        assertEquals(328, admission.size)
        // An operation never travels without its proof.
        assertFailsWith<IllegalArgumentException> { HierarchyWire.encodeOp(Op(1, A, 1, 1, Change.AddAdmin(B))) }
    }

    @Test
    fun `long lists are cut in blocks`() {
        val proof = signedOp(1, A, 1, 1, Change.AddAdmin(B)).proof
        val many = List(HierarchyWire.MAX_BATCH * 2 + 3) { Op(1, A, it + 1, it + 1L, Change.AddAdmin(B), proof) }
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
        // Before the ensemble exists there is no state to write.
        assertFailsWith<IllegalArgumentException> { HierarchyWire.encodeState(HierarchyState.empty(5)) }
    }

    @Test
    fun `a state that cannot be is refused`() {
        val state = sampleState()
        val broken =
            listOf(
                state.copy(creator = OUTSIDER),
                state.copy(leader = OUTSIDER),
                state.copy(deputy = OUTSIDER),
                state.copy(deputy = state.leader),
                state.copy(members = state.members + (C to Member(C, hidden("Charlie"), publicOf(C), group = 0x999))),
                state.copy(members = state.members + (C to Member(C, hidden("Charlie"), publicOf(D), group = G1))),
                state.copy(groups = state.groups + (G2 to Group(G2, ROOT, hidden("Groupe 2"), leader = C))),
                state.copy(groups = state.groups + (G2 to Group(G2, ROOT, hidden("Groupe 2"), deputy = C))),
                state.copy(groups = state.groups + (G1 to Group(G1, ROOT, hidden("Groupe 1"), leader = C, deputy = C))),
                // A group under a group that does not exist, under itself, or deeper than the limits.
                state.copy(groups = state.groups + (G2 to Group(G2, 0x999, hidden("Groupe 2")))),
                state.copy(groups = state.groups + (G2 to Group(G2, G2, hidden("Groupe 2")))),
                state.copy(
                    rules = state.rules.copy(limits = HierarchyLimits(maxMembers = 120, maxGroups = 20, maxDepth = 1)),
                ),
                state.copy(
                    rules = state.rules.copy(limits = HierarchyLimits(maxMembers = 3, maxGroups = 20, maxDepth = 3)),
                ),
                state.copy(deletedGroups = setOf(G1)),
                state.copy(members = state.members + (0L to Member(0, hidden("Zéro"), publicOf(A)))),
            )
        // A name that is not hidden the way every phone hides it cannot even be written.
        val unhidden = HiddenName("Sortie".toByteArray())
        assertTrue(state.copy(name = unhidden).inconsistency() != null)
        assertTrue(state.copy(name = null).inconsistency() != null)
        val renamed = state.groups.getValue(G1).copy(name = unhidden)
        assertTrue(state.copy(groups = state.groups + (G1 to renamed)).inconsistency() != null)
        // The format cannot even write an administrator who is not a member: the mark is on the member.
        assertTrue(state.copy(admins = state.admins + OUTSIDER).inconsistency() != null)
        for ((i, bad) in broken.withIndex()) {
            assertTrue(bad.inconsistency() != null, "case $i")
            assertFailsWith<WireException>("case $i") { HierarchyWire.decodeState(HierarchyWire.encodeState(bad)) }
        }
    }

    @Test
    fun `what is not in the one form of this version is refused`() {
        val op = HierarchyWire.encodeOp(ops[6])
        assertFailsWith<WireException> { HierarchyWire.decodeOp(byteArrayOf()) }
        // Another version: the first two, never deployed, are not read; nor one to come.
        for (version in listOf(1, 2, 4)) {
            val other = op.copyOf().also { it[0] = version.toByte() }
            assertEquals("unknown version", assertFailsWith<WireException> { HierarchyWire.decodeOp(other) }.message)
            assertNull(HierarchyWire.kindOf(other))
        }
        assertFailsWith<WireException> { HierarchyWire.decodeOps(op) }
        assertFailsWith<WireException> { HierarchyWire.decodeOp(op + 0) }
        assertFailsWith<WireException> {
            HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(Summary(1, emptyMap(), 0)) + 0)
        }
        assertNull(HierarchyWire.kindOf(byteArrayOf(3)))
        assertNull(HierarchyWire.kindOf(byteArrayOf(1, 1)))
        assertNull(HierarchyWire.kindOf(byteArrayOf(2, 1)))
        assertNull(HierarchyWire.kindOf(byteArrayOf(3, 9)))
        assertEquals(HierarchyWire.Kind.OP, HierarchyWire.kindOf(byteArrayOf(3, 1)))

        val proof = ops[0].proof

        fun refused(vararg wrong: Op) =
            wrong.forEach {
                assertFailsWith<WireException>("$it") { HierarchyWire.decodeOp(HierarchyWire.encodeOp(it)) }
            }
        refused(
            Op(1, A, 0, 5, Change.AddAdmin(B), proof),
            Op(1, A, 1, 0, Change.AddAdmin(B), proof),
            Op(1, 0, 1, 5, Change.AddAdmin(B), proof),
            Op(1, A, 1, 5, Change.AddAdmin(0), proof),
            // Keys that are not the ones of the member admitted, and a creation with the keys of another.
            Op(1, A, 1, 5, Change.AdmitMember(B, hidden("Bravo"), publicOf(C)), proof),
            Op(1, B, 1, 5, genesis(creator = A), proof),
        )
        // A name that is not hidden cannot be written at all.
        assertFailsWith<IllegalArgumentException> {
            HierarchyWire.encodeOp(Op(1, A, 1, 5, Change.CreateGroup(G1, HiddenName("Groupe".toByteArray())), proof))
        }
        // A code this version does not know: the phone must be updated before it can apply it.
        val unknown = HierarchyWire.encodeOp(ops[3]).also { it[beforeChange] = 99 }
        assertEquals("unknown change", assertFailsWith<WireException> { HierarchyWire.decodeOp(unknown) }.message)
        // A post that is neither leader nor deputy.
        val post = HierarchyWire.encodeOp(ops[12]).also { it[beforeChange + 1 + 8] = 3 }
        assertEquals("unknown post", assertFailsWith<WireException> { HierarchyWire.decodeOp(post) }.message)
        // Rules with a kind of share, a rule or limits this version cannot carry: the last seven bytes of the
        // creation (signed kinds, flags, limits).
        val creation = HierarchyWire.encodeOp(ops[1])
        val signedKinds = creation.size - proofBytes - 7
        assertEquals(HierarchyRules.SIGNED_BY_DEFAULT.sumOf { it.bit }, creation[signedKinds].toInt())
        assertFailsWith<WireException> { HierarchyWire.decodeOp(creation.copyOf().also { it[signedKinds] = 0x40 }) }
        assertEquals(1, creation[signedKinds + 1].toInt(), "the deputy takes the place of a leader who goes")
        assertFailsWith<WireException> { HierarchyWire.decodeOp(creation.copyOf().also { it[signedKinds + 1] = 2 }) }
        assertFailsWith<WireException> {
            HierarchyWire.decodeOp(
                creation.copyOf().also {
                    it[
                        creation.size - proofBytes -
                            1,
                    ] =
                        0
                },
            )
        }
        // A key that is not a point of the curve.
        val admission = HierarchyWire.encodeOp(ops[2])
        val keyAt = admission.size - proofBytes - 2 * P256.POINT_BYTES
        assertEquals(4, admission[keyAt].toInt())
        assertFailsWith<WireException> {
            HierarchyWire.decodeOp(
                admission.copyOf().also {
                    it[keyAt + 40] =
                        (it[keyAt + 40] + 1).toByte()
                },
            )
        }
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

                HierarchyWire.Kind.KEYS -> {
                    assertContentEquals(bytes, HierarchyWire.encodeKeyBatch(HierarchyWire.decodeKeyBatch(bytes)))
                }

                HierarchyWire.Kind.SHARE -> {
                    HierarchyWire.readShareHeader(bytes.copyOf(minOf(bytes.size, HierarchyWire.SHARE_HEADER_BYTES)))
                    if (bytes.size < HierarchyWire.SHARE_HEADER_BYTES) throw WireException("truncated share")
                }
            }
            true
        } catch (_: WireException) {
            false
        }

    @Test
    fun `damaged or made-up bytes are refused without anything else than a refusal`() {
        val random = Random(20261113)
        val state = sampleState()
        val batch = ContentKeys.issue(state.readers(ROOT)!!, keysOf(A)).batch
        val samples =
            ops.map { HierarchyWire.Kind.OP to HierarchyWire.encodeOp(it) } +
                listOf(
                    HierarchyWire.Kind.OPS to HierarchyWire.encodeOps(ops.drop(2)),
                    HierarchyWire.Kind.SUMMARY to HierarchyWire.encodeSummary(Summary(7, mapOf(A to 3, B to 9), 11)),
                    HierarchyWire.Kind.STATE to HierarchyWire.encodeState(state),
                    HierarchyWire.Kind.KEYS to HierarchyWire.encodeKeyBatch(batch),
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
            // Any byte changed (one in three for the long blocks: each try checks the keys of a state again).
            for (i in bytes.indices step if (bytes.size > 1000) 3 else 1) {
                repeat(if (bytes.size > 1000) 1 else 3) {
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
        assertTrue(refused > 8_000, "$refused refused")
        assertTrue(accepted > 100, "$accepted accepted: changes that still mean something are read")
    }
}
