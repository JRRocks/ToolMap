package fr.toolmap.core.hierarchy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agreement between phones without a central authority (`docs/specs/group-hierarchy.md` § 6). */
class HierarchyReplicaTest {
    private val g3 = 0x103L

    /** Hands [ops] to a new phone in every order given and expects one state; returns that phone. */
    private fun agreeInAnyOrder(
        ensembleId: Long,
        ops: List<Op>,
        orders: List<List<Op>> = listOf(ops, ops.reversed(), ops.sortedWith(Op.ORDER)),
    ): HierarchyReplica {
        val phones = orders.map { order -> HierarchyReplica(ensembleId, OUTSIDER).apply { order.forEach(::add) } }
        for (phone in phones) assertSame(phones.first(), phone)
        return phones.first()
    }

    /** Both phones exchange what they wrote, then a third one receives everything in other orders. */
    private fun settle(
        a: HierarchyReplica,
        b: HierarchyReplica,
    ): HierarchyReplica {
        sync(a, b)
        assertSame(a, b)
        val third = agreeInAnyOrder(a.ensembleId, a.ops())
        assertSame(a, third)
        return a
    }

    @Test
    fun `two administrators assign the same member at the same time, every phone keeps the same one`() {
        val a = staffed()
        val b = a.copyFor(B)
        val fromA = a.must(Change.AssignMember(C, G1))
        val fromB = b.must(Change.AssignMember(C, G2))
        assertEquals(fromA.clock, fromB.clock, "neither had seen the other")
        // Before they talk each one sees its own choice.
        assertEquals(G1, a.state.groupOf(C)?.id)
        assertEquals(G2, b.state.groupOf(C)?.id)
        val agreed = settle(a, b)
        // Same clock: the operation of the author with the higher identifier comes last, and stays.
        assertEquals(G2, agreed.state.groupOf(C)?.id)
        assertNull(agreed.rejectionOf(fromA))
        assertNull(agreed.rejectionOf(fromB))
    }

    @Test
    fun `a member moved while it is named leader of the group it leaves is not leader`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        val b = a.copyFor(B)
        val named = a.must(Change.SetGroupLeader(G1, C))
        val moved = b.must(Change.AssignMember(C, G2))
        val agreed = settle(a, b)
        // A's naming comes first and applies, B's move then takes C, and its post, out of the group.
        assertNull(agreed.rejectionOf(named))
        assertNull(agreed.rejectionOf(moved))
        assertEquals(G2, agreed.state.groupOf(C)?.id)
        assertNull(agreed.state.groups.getValue(G1).leader)

        // The other way round: the move first, the naming is refused.
        val a2 = staffed().apply { must(Change.AssignMember(C, G1)) }
        val b2 = a2.copyFor(B)
        val moved2 = a2.must(Change.AssignMember(C, G2))
        val named2 = b2.must(Change.SetGroupLeader(G1, C))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(moved2))
        assertEquals(Rejection.NOT_IN_GROUP, agreed2.rejectionOf(named2))
        assertNull(agreed2.state.groups.getValue(G1).leader)
    }

    @Test
    fun `an administrator removed while it acts loses what comes after its removal`() {
        val a = staffed()
        val b = a.copyFor(B)
        val removal = a.must(Change.RemoveAdmin(B))
        val created = b.must(Change.CreateGroup(g3, "Groupe 3"))
        val assigned = b.must(Change.AssignMember(C, g3))
        // B saw its group on its own phone until it heard of its removal.
        assertTrue(g3 in b.state.groups)
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(removal))
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(created))
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(assigned))
        assertFalse(g3 in agreed.state.groups)
        assertFalse(g3 in b.state.groups)
        assertEquals(setOf(A), agreed.state.admins)
    }

    @Test
    fun `what an administrator did before the order reaches its removal stays`() {
        val a = staffed()
        val b = a.copyFor(B)
        // At the same clock A comes before B: A's group is created, then B's removal of A applies.
        val created = a.must(Change.CreateGroup(g3, "Groupe 3"))
        val removal = b.must(Change.RemoveAdmin(A))
        val late = a.must(Change.AssignMember(C, g3))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(created))
        assertNull(agreed.rejectionOf(removal))
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(late))
        assertTrue(g3 in agreed.state.groups)
        assertNull(agreed.state.groupOf(C))
        assertEquals(setOf(B), agreed.state.admins)
    }

    @Test
    fun `two administrators who remove each other at the same time leave one administrator`() {
        val a = staffed()
        val b = a.copyFor(B)
        val byA = a.must(Change.RemoveAdmin(B))
        val byB = b.must(Change.RemoveAdmin(A))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(byA))
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(byB))
        assertEquals(setOf(A), agreed.state.admins)
    }

    @Test
    fun `a group deleted while someone is assigned to it is gone and the member has no group`() {
        // The deletion first: the assignment finds no group.
        val a = staffed()
        val b = a.copyFor(B)
        val deleted = a.must(Change.DeleteGroup(G1))
        val assigned = b.must(Change.AssignMember(C, G1))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(deleted))
        assertEquals(Rejection.NO_SUCH_GROUP, agreed.rejectionOf(assigned))
        assertFalse(G1 in agreed.state.groups)
        assertNull(agreed.state.groupOf(C))

        // The assignment first: it applies, then the deletion puts the member out of the group.
        val a2 = staffed()
        val b2 = a2.copyFor(B)
        val assigned2 = a2.must(Change.AssignMember(C, G1))
        val deleted2 = b2.must(Change.DeleteGroup(G1))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(assigned2))
        assertNull(agreed2.rejectionOf(deleted2))
        assertFalse(G1 in agreed2.state.groups)
        assertNull(agreed2.state.members.getValue(C).group)
        assertNull(agreed2.state.inconsistency())
    }

    @Test
    fun `an operation received again changes nothing`() {
        val a = staffed()
        val op = a.must(Change.AssignMember(C, G1))
        val b = a.copyFor(B)
        val before = b.state
        val history = b.history().size
        assertEquals(AddResult.DUPLICATE, b.add(op))
        assertEquals(AddResult.DUPLICATE, b.add(HierarchyWire.decodeOp(HierarchyWire.encodeOp(op))))
        assertEquals(0, b.addAll(a.ops()))
        assertEquals(0, b.addAll(a.ops().reversed()))
        assertEquals(before, b.state)
        assertEquals(history, b.history().size)
        assertEquals(a.summary(), b.summary())
    }

    @Test
    fun `an operation of an author without the right is kept and changes nothing`() {
        val a = staffed()
        val before = a.state
        val clock = a.ops().maxOf { it.clock } + 1
        // Written by hand: the phone of C would not have written them.
        val byMember = Op(a.ensembleId, C, 1, clock, Change.AddAdmin(C))
        val byOutsider = Op(a.ensembleId, OUTSIDER, 1, clock, Change.CreateGroup(g3, "Intrus"))
        assertEquals(AddResult.ADDED, a.add(byMember))
        assertEquals(AddResult.ADDED, a.add(byOutsider))
        assertEquals(Rejection.FORBIDDEN, a.rejectionOf(byMember))
        assertEquals(Rejection.NOT_A_MEMBER, a.rejectionOf(byOutsider))
        assertEquals(before, a.state)

        // Someone removed has no right left, whatever it had.
        a.must(Change.RemoveMember(B))
        val byRemoved = Op(a.ensembleId, B, 1, a.ops().maxOf { it.clock } + 1, Change.AddAdmin(C))
        assertEquals(AddResult.ADDED, a.add(byRemoved))
        assertEquals(Rejection.NOT_A_MEMBER, a.rejectionOf(byRemoved))
        assertEquals(setOf(A), a.state.admins)
        agreeInAnyOrder(a.ensembleId, a.ops())
    }

    @Test
    fun `operations for another ensemble or that no phone accepts are not kept`() {
        val a = staffed()
        val kept = a.ops().size
        val elsewhere = HierarchyReplica.create(A, "Alpha", "Autre sortie", NONCE + 1)
        assertEquals(AddResult.WRONG_ENSEMBLE, a.add(elsewhere.ops().single()))
        assertEquals(AddResult.MALFORMED, a.add(Op(a.ensembleId, B, 1, 50, Change.AdmitMember(0, "Zéro"))))
        assertEquals(AddResult.MALFORMED, a.add(Op(a.ensembleId, B, 0, 50, Change.AddAdmin(C))))
        assertEquals(AddResult.MALFORMED, a.add(Op(a.ensembleId, B, 1, 0, Change.AddAdmin(C))))
        assertEquals(AddResult.MALFORMED, a.add(Op(a.ensembleId, 0, 1, 50, Change.AddAdmin(C))))
        assertEquals(kept, a.ops().size)
    }

    @Test
    fun `only its creator can write the first operation of an ensemble`() {
        val real = newEnsemble()
        val genesis = real.ops().single()
        // Someone else claims the creation, with a smaller identifier: it comes first in the order.
        val thief = Op(real.ensembleId, 0x01, 1, 1, genesis.change)
        val phone = agreeInAnyOrder(real.ensembleId, listOf(thief, genesis))
        assertEquals(Rejection.BAD_GENESIS, phone.rejectionOf(thief))
        assertEquals(setOf(A), phone.state.admins)
        // Someone writes, in the name of the creator, another creation under the same number: other names, other
        // rules. Whatever its bytes, the real one stays.
        for (name in listOf("A", "zzz")) {
            val twin =
                Op(
                    real.ensembleId,
                    A,
                    1,
                    1,
                    Change.CreateEnsemble(NONCE, name, "Alpha", HierarchyRules(RightsTable.NONE)),
                )
            val seen = agreeInAnyOrder(real.ensembleId, listOf(twin, genesis))
            assertEquals("Sortie du jour", seen.state.name)
            assertEquals(listOf(genesis), seen.ops())
        }
        // Before the creation nothing applies.
        val early = HierarchyReplica(real.ensembleId, B)
        assertEquals(Rejection.NO_ENSEMBLE, early.refused(Change.CreateGroup(G1, "Groupe 1")))
        assertFalse(early.state.created)
        assertNull(early.state.inconsistency())
    }

    @Test
    fun `operations of one author wait for the ones before them`() {
        val a = staffed()
        val b = HierarchyReplica(a.ensembleId, B)
        val ops = a.ops()
        assertEquals(AddResult.HELD, b.add(ops[2]))
        assertEquals(AddResult.HELD, b.add(ops[1]))
        assertFalse(b.state.created)
        assertEquals(emptyMap(), b.summary().counts)
        assertEquals(AddResult.ADDED, b.add(ops[0]))
        assertEquals(mapOf(A to 3), b.summary().counts)
        assertEquals(setOf(A, B, C), b.state.members.keys)
        // A phone that holds operations of its own that are not applied yet must catch up before it writes.
        val late = HierarchyReplica(a.ensembleId, A)
        late.add(ops[1])
        assertEquals(Rejection.LOG_FULL, late.refused(Change.AdmitMember(0x50, "Echo")))
    }

    @Test
    fun `an author whose clock goes backwards loses the earlier operation and goes on`() {
        val a = staffed()
        val next = a.ops().count { it.author == B } + 1
        val clock = a.ops().maxOf { it.clock }
        val first = Op(a.ensembleId, B, next, clock + 10, Change.CreateGroup(g3, "Groupe 3"))
        val second = Op(a.ensembleId, B, next + 1, clock + 5, Change.CreateGroup(0x104, "Groupe 4"))
        val third = Op(a.ensembleId, B, next + 2, clock + 20, Change.CreateGroup(0x105, "Groupe 5"))
        val phone = agreeInAnyOrder(a.ensembleId, a.ops() + listOf(first, second, third))
        assertNull(phone.rejectionOf(second))
        assertEquals(Rejection.OUT_OF_ORDER, phone.rejectionOf(first))
        assertNull(phone.rejectionOf(third))
        assertEquals(setOf(G1, G2, 0x104L, 0x105L), phone.state.groups.keys)
    }

    @Test
    fun `a phone that was away catches up by exchanging summaries`() {
        val a = staffed()
        val b = a.copyFor(B)
        val away = a.copyFor(C)
        a.must(Change.AssignMember(C, G1))
        a.must(Change.SetGroupLeader(G1, C))
        sync(a, b)
        b.must(Change.AssignMember(D, G2))
        b.must(Change.RenameGroup(G2, "Arrière"))
        b.must(Change.AdmitMember(0x50, "Echo"))
        sync(a, b)
        assertEquals(G1, a.state.groupOf(C)?.id)
        assertNull(away.state.groupOf(C))

        // The summary says what the phone holds; the answer is exactly what it lacks.
        val missing = a.missingFor(away.summary())
        assertEquals(5, missing.size)
        assertEquals(emptyList(), away.missingFor(a.summary()))
        assertEquals(1, sync(away, a), "one exchange is enough")
        assertSame(a, away)
        assertEquals(setOf(Role.GROUP_LEADER, Role.MEMBER), away.state.rolesOf(C))
        assertEquals(0, sync(away, b), "nothing more to say")

        // A long absence comes back in several blocks.
        val longAway = a.copyFor(D)
        repeat(40) { a.must(Change.RenameGroup(G1, "Nom $it")) }
        assertTrue(sync(longAway, a, max = 7) >= 6)
        assertSame(a, longAway)
        // A summary of another ensemble gets nothing.
        assertEquals(emptyList(), a.missingFor(Summary(a.ensembleId + 1, emptyMap(), 0)))
    }

    @Test
    fun `a copy stored and read back is the same copy`() {
        val a = staffed().apply { must(Change.AssignMember(C, G1)) }
        val stored = HierarchyWire.encodeOpBlocks(a.ops())
        val back = HierarchyReplica.restore(a.ensembleId, A, stored.flatMap(HierarchyWire::decodeOps))
        assertSame(a, back)
        // It goes on writing where it stopped.
        val op = back.must(Change.AssignMember(D, G1))
        assertEquals(a.ops().count { it.author == A } + 1, op.seq)
        assertEquals(a.ops().maxOf { it.clock } + 1, op.clock)
    }

    @Test
    fun `two operations under one number end as the same one on every phone`() {
        val a = staffed()
        val b = a.copyFor(B)
        val c = a.copyFor(C)
        val seq = a.ops().count { it.author == B } + 1
        val clock = a.ops().maxOf { it.clock } + 1
        // B, or someone in its name, wrote two different operations with the same number.
        val one = Op(a.ensembleId, B, seq, clock, Change.AssignMember(C, G1))
        val other = Op(a.ensembleId, B, seq, clock, Change.AssignMember(C, G2))
        assertEquals(AddResult.ADDED, a.add(one))
        assertEquals(AddResult.ADDED, c.add(other))
        assertEquals(a.summary().counts, c.summary().counts)
        sync(a, c)
        assertSame(a, c)
        val kept = a.ops().single { it.author == B && it.seq == seq }
        assertEquals(AddResult.ADDED, b.add(kept))
        assertEquals(AddResult.DUPLICATE, b.add(kept))
        val lost = if (kept == one) other else one
        assertEquals(AddResult.CONFLICT_KEPT, b.add(lost))
        assertSame(a, b)
        val late = a.copyFor(D).apply { add(lost) }
        assertEquals(AddResult.CONFLICT_KEPT, late.add(lost))
        agreeInAnyOrder(a.ensembleId, a.ops() + lost)
        // A phone that had the losing one and more operations after it still ends with the kept one.
        val ahead = HierarchyReplica(a.ensembleId, D)
        ahead.addAll(a.ops().filter { it != kept } + lost)
        assertEquals(AddResult.CONFLICT_REPLACED, ahead.add(kept))
        assertSame(a, ahead)
    }

    @Test
    fun `phones that received the same operations in different orders hold the same state`() {
        for (seed in 1..60L) convergence(seed)
    }

    private val memberPool = listOf(A, B, C, D, 0x50L, 0x60L, 0x70L, 0x80L, 0x90L, OUTSIDER)
    private val groupPool = listOf(G1, G2, g3, 0x104L, 0x105L, 0x106L)

    private fun randomChange(
        random: Random,
        state: HierarchyState,
    ): Change {
        // Mostly members and groups that exist, sometimes any of the pool.
        val known = random.nextInt(5) > 0
        val member = state.members.keys.takeIf { known }?.randomOrNull(random) ?: memberPool.random(random)
        val group = state.groups.keys.takeIf { known }?.randomOrNull(random) ?: groupPool.random(random)
        return when (random.nextInt(24)) {
            0, 1, 2 -> Change.AdmitMember(memberPool.random(random), "Membre ${random.nextInt(4)}")
            3 -> Change.RemoveMember(member)
            4, 5 -> Change.AddAdmin(member)
            6 -> Change.RemoveAdmin(member)
            7, 8 -> Change.CreateGroup(groupPool.random(random), "Groupe ${random.nextInt(4)}")
            9, 10 -> Change.RenameGroup(group, "Groupe ${random.nextInt(4)}")
            11 -> Change.DeleteGroup(group)
            12, 13, 14, 15, 16 -> Change.AssignMember(member, group)
            17, 18 -> Change.UnassignMember(member)
            19, 20 -> Change.SetGroupLeader(group, state.membersOf(group).randomOrNull(random)?.id ?: member)
            21 -> Change.ClearGroupLeader(group)
            else -> Change.SetEnsembleLeader(member)
        }
    }

    /**
     * Five phones and three authors without a phone (two members and someone outside) write at random, with the
     * rights or without, sometimes two operations under one number, and exchange part of what they hold. At the end
     * every operation ever written is handed to new phones in different orders: one state.
     */
    private fun convergence(seed: Long) {
        val random = Random(seed)
        val rules =
            if (seed % 2 == 0L) {
                HierarchyRules.DEFAULT
            } else {
                HierarchyRules(
                    RightsTable.DEFAULT
                        .with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                        .with(Operation.UNASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                        .with(Operation.ASSIGN_MEMBER, Role.MEMBER, Reach.SELF)
                        .with(Operation.UNASSIGN_MEMBER, Role.MEMBER, Reach.SELF)
                        .with(Operation.SET_GROUP_LEADER, Role.ENSEMBLE_LEADER, Reach.ANYONE)
                        .with(Operation.CLEAR_GROUP_LEADER, Role.GROUP_LEADER, Reach.SELF),
                )
            }
        val origin = staffed(rules)
        for (id in listOf(0x50L, 0x60L, 0x70L)) origin.must(Change.AdmitMember(id, "Membre"))
        origin.must(Change.AddAdmin(C))
        origin.must(Change.AddAdmin(0x60L))
        val phones = listOf(origin) + listOf(B, C, D, 0x50L).map(origin::copyFor)
        val written = origin.ops().toMutableList()
        val ghostSeq = HashMap<Long, Int>()
        repeat(400) {
            // The first three phones are administrators at the start: they write more often than the two others.
            val phone = if (random.nextInt(4) > 0) phones[random.nextInt(3)] else phones.random(random)
            when (random.nextInt(20)) {
                in 0..11 -> {
                    (phone.issue(randomChange(random, phone.state)) as? IssueResult.Issued)?.let { written.add(it.op) }
                }

                in 12..13 -> {
                    // An author without a phone: its operations are written by hand, clocks not always forward.
                    val author = listOf(0x60L, 0x70L, OUTSIDER).random(random)
                    val seq = (ghostSeq[author] ?: 0) + 1
                    ghostSeq[author] = seq
                    val clock = random.nextLong(1, written.maxOf { it.clock } + 3)
                    val op = Op(origin.ensembleId, author, seq, clock, randomChange(random, phone.state))
                    written.add(op)
                    phone.add(op)
                }

                14 -> {
                    // A second operation under the number of one already written.
                    val twin = written.random(random)
                    val op = Op(twin.ensembleId, twin.author, twin.seq, twin.clock, randomChange(random, phone.state))
                    written.add(op)
                    phone.add(op)
                }

                else -> {
                    // Part of what another phone holds, in any order.
                    val some = phones.random(random).ops().filter { random.nextInt(3) > 0 }
                    phone.addAll(some.shuffled(random))
                }
            }
        }
        val all = (written + phones.flatMap { it.ops() }).distinctBy { it.author to it.seq to it.change to it.clock }
        val orders = List(6) { all.shuffled(Random(seed * 31 + it)) } + listOf(all.sortedWith(Op.ORDER), all.reversed())
        val fresh = agreeInAnyOrder(origin.ensembleId, all, orders)
        assertTrue(fresh.state.created, "seed $seed")
        assertTrue(fresh.state.admins.isNotEmpty(), "seed $seed")
        assertTrue(fresh.history().any { it.rejection != null }, "seed $seed: some operations must have been refused")
        assertTrue(fresh.history().count { it.rejection == null } > 20, "seed $seed: many must have applied")
        // The phones themselves, once each has talked with a phone that heard everything.
        val hub = HierarchyReplica(origin.ensembleId, OUTSIDER).apply { addAll(all) }
        for (phone in phones) sync(phone, hub)
        for (phone in phones) sync(phone, hub)
        for (phone in phones) assertSame(fresh, phone, "seed $seed, phone ${phone.self}")
        // And the state itself survives its own format.
        assertEquals(fresh.state, HierarchyWire.decodeState(HierarchyWire.encodeState(fresh.state)), "seed $seed")
    }
}
