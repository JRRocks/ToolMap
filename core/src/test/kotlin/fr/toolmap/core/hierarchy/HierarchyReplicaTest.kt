package fr.toolmap.core.hierarchy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agreement between phones without a central authority (`docs/specs/group-hierarchy.md` § 6). */
class HierarchyReplicaTest {
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
    fun `the creator and an administrator assign the same member at the same time, every phone keeps the same one`() {
        val a = staffed()
        val b = a.copyFor(B)
        val fromA = a.must(Change.AssignMember(C, G1))
        val fromB = b.must(Change.AssignMember(C, G2))
        assertEquals(fromA.clock, fromB.clock, "neither had seen the other")
        // Before they talk each one sees its own choice.
        assertEquals(G1, a.state.groupOf(C)?.id)
        assertEquals(G2, b.state.groupOf(C)?.id)
        val agreed = settle(a, b)
        // Same clock: the operation of the author with the higher identifier comes last, and stays. One group.
        assertEquals(G2, agreed.state.groupOf(C)?.id)
        assertEquals(emptyList(), agreed.state.membersOf(G1))
        assertNull(agreed.rejectionOf(fromA))
        assertNull(agreed.rejectionOf(fromB))
    }

    @Test
    fun `waiting - a newcomer assigned while another administrator puts it back to waiting ends the same everywhere`() {
        // The unassignment first (A < B at equal clocks): it finds no group, the assignment then applies.
        val a = staffed()
        val b = a.copyFor(B)
        val unassigned = a.must(Change.AssignMember(C, G1)).let { a.must(Change.UnassignMember(C)) }
        val assigned = b.must(Change.AssignMember(D, G2)).let { b.must(Change.AssignMember(C, G2)) }
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(unassigned))
        assertNull(agreed.rejectionOf(assigned))
        assertEquals(G2, agreed.state.groupOf(C)?.id)
        assertFalse(agreed.state.isWaiting(C))

        // The other way round: a move first, then the unassignment leaves the member waiting.
        val a2 = staffed().apply { must(Change.AssignMember(C, G1)) }
        val b2 = a2.copyFor(B)
        val moved2 = a2.must(Change.AssignMember(C, G2))
        val unassigned2 = b2.must(Change.UnassignMember(C))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(moved2))
        assertNull(agreed2.rejectionOf(unassigned2))
        assertTrue(agreed2.state.isWaiting(C))
        assertEquals(listOf(A, B), agreed2.state.readersOf(ROOT).map { it.id })
        assertEquals(emptyList(), agreed2.state.readersOf(G2))
    }

    @Test
    fun `a member moved while it is named leader of the group it leaves is not leader`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        val b = a.copyFor(B)
        val named = a.must(leader(G1, C))
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
        val named2 = b2.must(leader(G1, C))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(moved2))
        assertEquals(Rejection.NOT_IN_GROUP, agreed2.rejectionOf(named2))
        assertNull(agreed2.state.groups.getValue(G1).leader)
    }

    @Test
    fun `deputies - two deputies named at the same time for one leader, one stays, the same everywhere`() {
        val a = deployed()
        val b = a.copyFor(B)
        val byA = a.must(deputy(G1, C))
        val byB = b.must(deputy(G1, D))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(byA))
        assertNull(agreed.rejectionOf(byB))
        // One deputy: the naming the order puts last.
        assertEquals(D, agreed.state.groups.getValue(G1).deputy)
        assertEquals(setOf(Role.MEMBER), agreed.state.rolesOf(C))

        // The deputy is named leader on one phone while another deputy is named on the other: both orders.
        val a2 = deployed().apply { must(deputy(ROOT, C)) }
        val b2 = a2.copyFor(B)
        val promoted = a2.must(leader(ROOT, C))
        val replaced = b2.must(deputy(ROOT, D))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(promoted))
        assertNull(agreed2.rejectionOf(replaced))
        assertEquals(C, agreed2.state.leader)
        assertEquals(D, agreed2.state.deputy)

        val a3 = deployed().apply { must(deputy(ROOT, C)) }
        val b3 = a3.copyFor(B)
        val replaced3 = a3.must(deputy(ROOT, D))
        val promoted3 = b3.must(leader(ROOT, C))
        val agreed3 = settle(a3, b3)
        assertNull(agreed3.rejectionOf(replaced3))
        assertNull(agreed3.rejectionOf(promoted3))
        assertEquals(C, agreed3.state.leader)
        assertEquals(D, agreed3.state.deputy)
        assertNull(agreed3.state.inconsistency())
    }

    @Test
    fun `an administrator removed while it acts loses what comes after its removal`() {
        val a = staffed()
        val b = a.copyFor(B)
        val removal = a.must(Change.RemoveAdmin(B))
        val created = b.must(Change.CreateGroup(G3, hidden("Groupe 3")))
        val assigned = b.must(Change.AssignMember(C, G3))
        // B saw its group on its own phone until it heard of its removal.
        assertTrue(G3 in b.state.groups)
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(removal))
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(created))
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(assigned))
        assertFalse(G3 in agreed.state.groups)
        assertFalse(G3 in b.state.groups)
        assertEquals(emptySet(), agreed.state.admins)
        assertTrue(agreed.state.isWaiting(C))
    }

    @Test
    fun `what an administrator did before the order reaches its removal stays`() {
        val a = staffed()
        val b = a.copyFor(B)
        // B writes two operations; A, who saw none, removes B at the clock of the second one.
        val created = b.must(Change.CreateGroup(G3, hidden("Groupe 3")))
        a.must(Change.RenameGroup(G1, hidden("Tête")))
        val removal = a.must(Change.RemoveAdmin(B))
        val late = b.must(Change.AssignMember(C, G3))
        assertTrue(created.clock < removal.clock && removal.clock == late.clock)
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(created))
        assertNull(agreed.rejectionOf(removal))
        // At equal clocks A comes before B: the removal applies first.
        assertEquals(Rejection.FORBIDDEN, agreed.rejectionOf(late))
        assertTrue(G3 in agreed.state.groups)
        assertNull(agreed.state.groupOf(C))
    }

    @Test
    fun `removal - a member removed by the creator while an administrator assigns it is gone, in both orders`() {
        // The removal first: the assignment finds no member.
        val a = staffed()
        val b = a.copyFor(B)
        val removed = a.must(Change.RemoveMember(C))
        val assigned = b.must(Change.AssignMember(C, G1))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(removed))
        assertEquals(Rejection.NO_SUCH_MEMBER, agreed.rejectionOf(assigned))
        assertFalse(C in agreed.state.members)

        // The assignment and a post first: the removal takes the member out of both.
        val a2 = staffed()
        val b2 = a2.copyFor(B)
        val assigned2 = b2.must(Change.AssignMember(C, G1))
        val named2 = b2.must(leader(G1, C))
        a2.must(Change.RenameGroup(G2, hidden("Arrière")))
        a2.must(Change.RenameGroup(G2, hidden("Queue")))
        val removed2 = a2.must(Change.RemoveMember(C))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(assigned2))
        assertNull(agreed2.rejectionOf(named2))
        assertNull(agreed2.rejectionOf(removed2))
        assertFalse(C in agreed2.state.members)
        assertNull(agreed2.state.groups.getValue(G1).leader)
        assertEquals(emptyList(), agreed2.state.readersOf(G1))

        // What the removed member wrote that the order puts after its removal changes nothing.
        val a3 = deployed()
        val c3 = a3.copyFor(C)
        val out = a3.must(Change.RemoveMember(C))
        val left = c3.must(Change.RemoveMember(C))
        val agreed3 = settle(a3, c3)
        assertNull(agreed3.rejectionOf(out))
        assertEquals(Rejection.NOT_A_MEMBER, agreed3.rejectionOf(left))
    }

    /** [deployed] where C leads G1 with D as its deputy, and H is a third member of G1. */
    private fun withPosts(): HierarchyReplica =
        deployed().apply {
            must(admit(H))
            must(Change.AssignMember(H, G1))
            must(leader(G1, C))
            must(deputy(G1, D))
        }

    @Test
    fun `promotion - a leader put out while another deputy is named, the same leader on every phone`() {
        // The removal first (A < B at equal clocks): the deputy of then becomes the leader, then H is named deputy.
        val a = withPosts()
        val b = a.copyFor(B)
        val removed = a.must(Change.RemoveMember(C))
        val named = b.must(deputy(G1, H))
        assertEquals(removed.clock, named.clock, "neither had seen the other")
        // Before they talk each one sees its own result.
        assertEquals(D to null, a.state.groups.getValue(G1).let { it.leader to it.deputy })
        assertEquals(C to H, b.state.groups.getValue(G1).let { it.leader to it.deputy })
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(removed))
        assertNull(agreed.rejectionOf(named))
        assertEquals(D to H, agreed.state.groups.getValue(G1).let { it.leader to it.deputy })

        // The naming first (written before the removal): the deputy of then is H, and H becomes the leader.
        val a2 = withPosts()
        val b2 = a2.copyFor(B)
        val named2 = b2.must(deputy(G1, H))
        a2.must(Change.RenameGroup(G2, hidden("Arrière")))
        val removed2 = a2.must(Change.RemoveMember(C))
        assertTrue(named2.clock < removed2.clock)
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(named2))
        assertNull(agreed2.rejectionOf(removed2))
        assertEquals(H to null, agreed2.state.groups.getValue(G1).let { it.leader to it.deputy })
        assertEquals(setOf(Role.MEMBER), agreed2.state.rolesOf(D))
        assertNull(agreed2.state.inconsistency())
    }

    @Test
    fun `promotion - a leader put back to waiting while it is moved, its deputy leads in both orders`() {
        // Back to waiting first (A < B at equal clocks): the deputy becomes the leader, then the move applies.
        val a = withPosts()
        val b = a.copyFor(B)
        val unassigned = a.must(Change.UnassignMember(C))
        val moved = b.must(Change.AssignMember(C, G2))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(unassigned))
        assertNull(agreed.rejectionOf(moved))
        assertEquals(D to null, agreed.state.groups.getValue(G1).let { it.leader to it.deputy })
        assertEquals(G2, agreed.state.groupOf(C)?.id)
        assertEquals(setOf(Role.MEMBER), agreed.state.rolesOf(C))

        // The move first: the deputy becomes the leader when its leader changes group; then the former leader waits.
        val a2 = withPosts()
        val b2 = a2.copyFor(B)
        val moved2 = b2.must(Change.AssignMember(C, G2))
        a2.must(Change.RenameGroup(G2, hidden("Arrière")))
        val unassigned2 = a2.must(Change.UnassignMember(C))
        assertTrue(moved2.clock < unassigned2.clock)
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(moved2))
        assertNull(agreed2.rejectionOf(unassigned2))
        assertEquals(D to null, agreed2.state.groups.getValue(G1).let { it.leader to it.deputy })
        assertTrue(agreed2.state.isWaiting(C))
    }

    @Test
    fun `promotion - the ensemble leader leaves while the creator puts it out, its deputy leads in both orders`() {
        fun start() =
            deployed().apply {
                must(leader(ROOT, C))
                must(deputy(ROOT, D))
            }
        // The creator first (A < C at equal clocks): its removal applies, the leaving finds nobody.
        val a = start()
        val c = a.copyFor(C)
        val removed = a.must(Change.RemoveMember(C))
        val left = c.must(Change.RemoveMember(C))
        val agreed = settle(a, c)
        assertNull(agreed.rejectionOf(removed))
        assertEquals(Rejection.NOT_A_MEMBER, agreed.rejectionOf(left))
        assertEquals(D to null, agreed.state.leader to agreed.state.deputy)

        // The leaving first: it applies, and the removal finds nobody. The deputy leads all the same.
        val a2 = start()
        val c2 = a2.copyFor(C)
        val left2 = c2.must(Change.RemoveMember(C))
        a2.must(Change.RenameGroup(G2, hidden("Arrière")))
        val removed2 = a2.must(Change.RemoveMember(C))
        val agreed2 = settle(a2, c2)
        assertNull(agreed2.rejectionOf(left2))
        assertEquals(Rejection.NO_SUCH_MEMBER, agreed2.rejectionOf(removed2))
        assertEquals(D to null, agreed2.state.leader to agreed2.state.deputy)

        // The deputy is put out while its leader leaves: whatever the order, both posts end vacant.
        val a3 = start()
        val c3 = a3.copyFor(C)
        val out = a3.must(Change.RemoveMember(D))
        val gone = c3.must(Change.RemoveMember(C))
        val agreed3 = settle(a3, c3)
        assertNull(agreed3.rejectionOf(out))
        assertNull(agreed3.rejectionOf(gone))
        assertEquals(null to null, agreed3.state.leader to agreed3.state.deputy)
    }

    @Test
    fun `creator transfer - what the former creator wrote after handing over is refused on every phone`() {
        val a = deployed()
        val b = a.copyFor(B)
        val handed = a.must(Change.TransferCreator(B))
        sync(a, b)
        assertEquals(B, b.state.creator)
        // The new creator acts; the former one, on a phone that would still let it, writes by hand.
        val named = b.must(Change.AddAdmin(D))
        val seq = a.ops().count { it.author == A } + 1
        val stale = signedOp(a.ensembleId, A, seq, handed.clock + 1, Change.AddAdmin(E))
        val dissolve = signedOp(a.ensembleId, A, seq + 1, handed.clock + 2, Change.Dissolve)
        val phone = agreeInAnyOrder(a.ensembleId, b.ops() + stale + dissolve)
        assertNull(phone.rejectionOf(handed))
        assertNull(phone.rejectionOf(named))
        assertEquals(Rejection.FORBIDDEN, phone.rejectionOf(stale))
        assertEquals(Rejection.FORBIDDEN, phone.rejectionOf(dissolve))
        assertEquals(B, phone.state.creator)
        assertEquals(setOf(B, D), phone.state.admins)
        assertFalse(phone.state.dissolved)
        // It cannot write « before » its own handing over either: a clock that goes backwards counts for nothing.
        val antedated = signedOp(a.ensembleId, A, seq, handed.clock - 1, Change.AddAdmin(E))
        val other = agreeInAnyOrder(a.ensembleId, b.ops() + antedated)
        assertEquals(Rejection.OUT_OF_ORDER, other.rejectionOf(antedated))
        assertNull(other.rejectionOf(handed), "the transfer stays")
        assertEquals(B, other.state.creator)
        assertEquals(setOf(B, D), other.state.admins)
    }

    @Test
    fun `creator transfer - the successor leaves at the same time, there is always exactly one creator`() {
        // The successor leaves first: the transfer finds nobody, the creator stays the creator and cannot leave.
        // Its leaving is not read as the end of the ensemble: nothing is dissolved by surprise.
        val a = deployed().apply { must(Change.AddAdmin(C)) }
        val c = a.copyFor(C)
        val left = c.must(Change.RemoveMember(C))
        a.must(Change.RenameGroup(G1, hidden("Tête")))
        val handed = a.must(Change.TransferCreator(C))
        val gone = a.must(Change.RemoveMember(A))
        assertTrue(left.clock < handed.clock)
        val agreed = settle(a, c)
        assertNull(agreed.rejectionOf(left))
        assertEquals(Rejection.NO_SUCH_MEMBER, agreed.rejectionOf(handed))
        assertEquals(Rejection.IS_CREATOR, agreed.rejectionOf(gone))
        assertEquals(A, agreed.state.creator)
        assertTrue(A in agreed.state.members)
        assertFalse(agreed.state.dissolved)

        // The transfer first: the successor is the creator, and its leaving is refused.
        val a2 = deployed().apply { must(Change.AddAdmin(C)) }
        val c2 = a2.copyFor(C)
        val handed2 = a2.must(Change.TransferCreator(C))
        val left2 = c2.must(Change.RemoveMember(C))
        assertEquals(handed2.clock, left2.clock)
        val agreed2 = settle(a2, c2)
        assertNull(agreed2.rejectionOf(handed2))
        assertEquals(Rejection.IS_CREATOR, agreed2.rejectionOf(left2))
        assertEquals(C, agreed2.state.creator)
        assertTrue(C in agreed2.state.members)
        assertNull(agreed2.state.inconsistency())

        // Q13: the successor loses its role of administrator at the same time. The role taken away first: the
        // handing over is refused, the creator stays. The handing over first: the successor is the creator.
        val a3 = deployed().apply { must(Change.AddAdmin(C)) }
        val twin = a3.copyFor(A)
        val unnamed = a3.must(Change.RemoveAdmin(C))
        a3.must(Change.RenameGroup(G1, hidden("Tête")))
        val handed3 = signedOp(twin.ensembleId, A, unnamed.seq + 2, unnamed.clock + 2, Change.TransferCreator(C))
        val late = agreeInAnyOrder(a3.ensembleId, a3.ops() + handed3)
        assertEquals(Rejection.NOT_AN_ADMIN, late.rejectionOf(handed3))
        assertEquals(A, late.state.creator)
    }

    @Test
    fun `dissolution - what the order puts after it is refused on every phone, what it puts before stays`() {
        val a = deployed()
        val b = a.copyFor(B)
        val c = a.copyFor(C)
        // B acts twice, C leaves, then A, who heard none of it, dissolves at the clock of B's second operation.
        val before = b.must(Change.CreateGroup(G3, hidden("Groupe 3")))
        val leftBefore = c.must(Change.RemoveMember(C))
        a.must(Change.RenameGroup(G1, hidden("Tête")))
        val end = a.must(Change.Dissolve)
        val after = b.must(Change.AssignMember(D, G3))
        assertTrue(before.clock < end.clock && end.clock == after.clock)
        sync(a, b)
        sync(a, c)
        sync(b, c)
        val agreed = settle(a, b)
        assertSame(agreed, c)
        assertNull(agreed.rejectionOf(before))
        assertNull(agreed.rejectionOf(leftBefore))
        assertNull(agreed.rejectionOf(end))
        assertEquals(Rejection.DISSOLVED, agreed.rejectionOf(after))
        assertTrue(agreed.state.dissolved)
        assertTrue(G3 in agreed.state.groups)
        assertFalse(C in agreed.state.members)
        assertEquals(G1, agreed.state.groupOf(D)?.id)
        // Nobody writes after the end, on any phone that heard of it.
        for (phone in listOf(
            a,
            b,
            c,
        )) {
            assertEquals(Rejection.DISSOLVED, phone.refused(Change.CreateGroup(0x104, hidden("Trop tard"))))
        }
        // An operation written later by someone who had not heard is kept, and refused everywhere.
        val d = HierarchyReplica.restore(a.ensembleId, D, a.ops().filter { it != end }, keysOf(D))
        assertFalse(d.state.dissolved)
        val unaware = d.must(Change.RemoveMember(D))
        sync(a, d)
        assertSame(a, d)
        assertEquals(Rejection.DISSOLVED, a.rejectionOf(unaware))
        assertTrue(D in a.state.members)
    }

    @Test
    fun `a group deleted while someone is assigned to it is gone and the member waits`() {
        // The deletion first: the assignment finds no group.
        val a = staffed()
        val b = a.copyFor(B)
        val deleted = a.must(Change.DeleteGroup(G1))
        val assigned = b.must(Change.AssignMember(C, G1))
        val agreed = settle(a, b)
        assertNull(agreed.rejectionOf(deleted))
        assertEquals(Rejection.NO_SUCH_GROUP, agreed.rejectionOf(assigned))
        assertFalse(G1 in agreed.state.groups)
        assertTrue(agreed.state.isWaiting(C))

        // The assignment first: it applies, then the deletion puts the member out of the group.
        val a2 = staffed()
        val b2 = a2.copyFor(B)
        val assigned2 = a2.must(Change.AssignMember(C, G1))
        val deleted2 = b2.must(Change.DeleteGroup(G1))
        val agreed2 = settle(a2, b2)
        assertNull(agreed2.rejectionOf(assigned2))
        assertNull(agreed2.rejectionOf(deleted2))
        assertFalse(G1 in agreed2.state.groups)
        assertTrue(agreed2.state.isWaiting(C))
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
        val byMember = signedOp(a.ensembleId, C, 1, clock, Change.AddAdmin(C))
        val byOutsider = signedOp(a.ensembleId, OUTSIDER, 1, clock, Change.CreateGroup(G3, hidden("Intrus")))
        assertEquals(AddResult.ADDED, a.add(byMember))
        assertEquals(AddResult.ADDED, a.add(byOutsider))
        assertEquals(Rejection.FORBIDDEN, a.rejectionOf(byMember))
        assertEquals(Rejection.NOT_A_MEMBER, a.rejectionOf(byOutsider))
        assertEquals(before, a.state)

        // Someone removed has no right left, whatever it had.
        a.must(Change.RemoveMember(B))
        val byRemoved =
            signedOp(a.ensembleId, B, 1, a.ops().maxOf { it.clock } + 1, Change.CreateGroup(G3, hidden("Revenant")))
        assertEquals(AddResult.ADDED, a.add(byRemoved))
        assertEquals(Rejection.NOT_A_MEMBER, a.rejectionOf(byRemoved))
        assertEquals(emptySet(), a.state.admins)
        agreeInAnyOrder(a.ensembleId, a.ops())
    }

    @Test
    fun `operations for another ensemble, that no phone accepts or that are not signed are not kept`() {
        val a = staffed()
        val kept = a.ops().size
        val elsewhere = HierarchyReplica.create(keysOf(A), hidden("Alpha"), hidden("Autre sortie"), NONCE + 1)
        assertEquals(AddResult.WRONG_ENSEMBLE, a.add(elsewhere.ops().single()))
        assertEquals(AddResult.MALFORMED, a.add(signedOp(a.ensembleId, B, 1, 50, Change.AddAdmin(0))))
        assertEquals(AddResult.MALFORMED, a.add(signedOp(a.ensembleId, B, 0, 50, Change.AddAdmin(C))))
        assertEquals(AddResult.MALFORMED, a.add(signedOp(a.ensembleId, B, 1, 0, Change.AddAdmin(C))))
        assertEquals(AddResult.MALFORMED, a.add(Op(a.ensembleId, 0, 1, 50, Change.AddAdmin(C))))
        assertEquals(
            AddResult.UNAUTHENTICATED,
            a.add(Op(a.ensembleId, B, 5, 50, Change.CreateGroup(G3, hidden("Sans preuve")))),
        )
        assertEquals(kept, a.ops().size)
    }

    @Test
    fun `only its creator can write the first operation of an ensemble`() {
        val real = newEnsemble()
        val first = real.ops().single()
        // Someone else claims the creation, with a smaller identifier: it comes first in the order.
        val thief = signedOp(real.ensembleId, FIRST, 1, 1, genesis(creator = FIRST))
        val phone = agreeInAnyOrder(real.ensembleId, listOf(thief, first))
        assertEquals(Rejection.BAD_GENESIS, phone.rejectionOf(thief))
        assertEquals(A, phone.state.creator)
        assertEquals(setOf(A), phone.state.members.keys)
        // The creator itself writes another creation under the same number: other names, other rules. Whatever
        // its bytes, the one the identifier of the ensemble was made of stays.
        for (name in listOf("A", "zzz")) {
            val twin =
                signedOp(real.ensembleId, A, 1, 1, genesis(name = name, rules = HierarchyRules(RightsTable.NONE)))
            val seen = agreeInAnyOrder(real.ensembleId, listOf(twin, first))
            assertEquals(hidden("Sortie du jour"), seen.state.name)
            assertEquals(listOf(first), seen.ops())
        }
        // Before the creation nothing applies.
        val early = HierarchyReplica(real.ensembleId, B, keysOf(B))
        assertEquals(Rejection.NO_ENSEMBLE, early.refused(Change.CreateGroup(G1, hidden("Groupe 1"))))
        assertFalse(early.state.created)
        assertNull(early.state.inconsistency())
    }

    @Test
    fun `operations of one author wait for the ones before them`() {
        val a = staffed()
        val b = HierarchyReplica(a.ensembleId, B, keysOf(B))
        val ops = a.ops()
        assertEquals(AddResult.HELD, b.add(ops[2]))
        assertEquals(AddResult.HELD, b.add(ops[1]))
        assertFalse(b.state.created)
        assertEquals(emptyMap(), b.summary().counts)
        assertEquals(AddResult.ADDED, b.add(ops[0]))
        assertEquals(mapOf(A to 3), b.summary().counts)
        assertEquals(setOf(A, B, C), b.state.members.keys)
        // A phone that holds operations of its own that are not applied yet must catch up before it writes.
        val late = HierarchyReplica(a.ensembleId, A, keysOf(A))
        late.add(ops[1])
        assertEquals(Rejection.LOG_FULL, late.refused(admit(E, "Echo")))
    }

    @Test
    fun `an author whose clock goes backwards loses that operation, not the ones it wrote before`() {
        val a = staffed()
        val next = a.ops().count { it.author == B } + 1
        val clock = a.ops().maxOf { it.clock }
        val first = signedOp(a.ensembleId, B, next, clock + 10, Change.CreateGroup(G3, hidden("Groupe 3")))
        val second = signedOp(a.ensembleId, B, next + 1, clock + 5, Change.CreateGroup(0x104, hidden("Groupe 4")))
        val third = signedOp(a.ensembleId, B, next + 2, clock + 20, Change.CreateGroup(0x105, hidden("Groupe 5")))
        val phone = agreeInAnyOrder(a.ensembleId, a.ops() + listOf(first, second, third))
        assertNull(phone.rejectionOf(first))
        assertEquals(Rejection.OUT_OF_ORDER, phone.rejectionOf(second))
        assertNull(phone.rejectionOf(third))
        assertEquals(setOf(G1, G2, G3, 0x105L), phone.state.groups.keys)
        // The same for the end of an ensemble: its creator cannot write « before » its own dissolution.
        val ended = staffed()
        val end = ended.must(Change.Dissolve)
        val seq = ended.ops().count { it.author == A } + 1
        val undo = signedOp(ended.ensembleId, A, seq, end.clock - 1, Change.CreateGroup(G3, hidden("Avant la fin")))
        val after = agreeInAnyOrder(ended.ensembleId, ended.ops() + undo)
        assertEquals(Rejection.OUT_OF_ORDER, after.rejectionOf(undo))
        assertTrue(after.state.dissolved)
    }

    @Test
    fun `a phone that was away catches up by exchanging summaries`() {
        val a = staffed()
        val b = a.copyFor(B)
        val away = a.copyFor(C)
        a.must(Change.AssignMember(C, G1))
        a.must(leader(G1, C))
        sync(a, b)
        b.must(Change.AssignMember(D, G2))
        b.must(Change.RenameGroup(G2, hidden("Arrière")))
        b.must(admit(E, "Echo"))
        sync(a, b)
        assertEquals(G1, a.state.groupOf(C)?.id)
        // The phone that was away still sees itself waiting.
        assertTrue(away.state.isWaiting(C))

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
        repeat(40) { a.must(Change.RenameGroup(G1, hidden("Nom $it"))) }
        assertTrue(sync(longAway, a, max = 7) >= 6)
        assertSame(a, longAway)
        // A summary of another ensemble gets nothing.
        assertEquals(emptyList(), a.missingFor(Summary(a.ensembleId + 1, emptyMap(), 0)))
    }

    @Test
    fun `a copy stored and read back is the same copy`() {
        val a = staffed().apply { must(Change.AssignMember(C, G1)) }
        val stored = HierarchyWire.encodeOpBlocks(a.ops())
        val back = HierarchyReplica.restore(a.ensembleId, A, stored.flatMap(HierarchyWire::decodeOps), keysOf(A))
        assertSame(a, back)
        // It goes on writing where it stopped.
        val op = back.must(Change.AssignMember(D, G1))
        assertEquals(a.ops().count { it.author == A } + 1, op.seq)
        assertEquals(a.ops().maxOf { it.clock } + 1, op.clock)
        // Without its keys a copy reads and writes nothing.
        val reader = HierarchyReplica.restore(a.ensembleId, A, a.ops())
        assertSame(a, reader)
        assertEquals(Rejection.UNAUTHENTICATED, reader.refused(Change.AssignMember(D, G1)))
    }

    @Test
    fun `two operations under one number end as the same one on every phone`() {
        val a = staffed()
        val b = a.copyFor(B)
        val c = a.copyFor(C)
        val seq = a.ops().count { it.author == B } + 1
        val clock = a.ops().maxOf { it.clock } + 1
        // B (a phone restored from a backup, or a modified one) signed two different operations with one number.
        val one = signedOp(a.ensembleId, B, seq, clock, Change.AssignMember(C, G1))
        val other = signedOp(a.ensembleId, B, seq, clock, Change.AssignMember(C, G2))
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
        val ahead = HierarchyReplica(a.ensembleId, D, keysOf(D))
        ahead.addAll(a.ops().filter { it != kept } + lost)
        assertEquals(AddResult.CONFLICT_REPLACED, ahead.add(kept))
        assertSame(a, ahead)
    }

    @Test
    fun `phones that received the same operations in different orders hold the same state`() {
        for (seed in 1..30L) convergence(seed)
        // One run may apply little (its creator ended the ensemble early, or rewrote its first operations under
        // their own numbers); over all of them, many operations applied.
        assertTrue(applied > 30 * 30, "$applied operations applied")
    }

    private val memberPool = listOf(A, B, C, D, E, F, H, I, J, OUTSIDER)
    private val groupPool = listOf(G1, G2, G3, 0x104L, 0x105L, 0x106L)

    private fun randomChange(
        random: Random,
        state: HierarchyState,
    ): Change {
        // Mostly members and groups that exist, sometimes any of the pool.
        val known = random.nextInt(5) > 0
        val member = state.members.keys.takeIf { known }?.randomOrNull(random) ?: memberPool.random(random)
        val group = state.groups.keys.takeIf { known }?.randomOrNull(random) ?: groupPool.random(random)
        val unit = if (random.nextInt(3) == 0) ROOT else group
        val post = if (random.nextBoolean()) Post.LEADER else Post.DEPUTY
        return when (random.nextInt(40)) {
            0, 1, 2, 3 -> {
                admit(memberPool.random(random), "Membre ${random.nextInt(4)}")
            }

            4, 5 -> {
                Change.RemoveMember(member)
            }

            6, 7, 8 -> {
                Change.AddAdmin(member)
            }

            9 -> {
                Change.RemoveAdmin(member)
            }

            10, 11, 12 -> {
                Change.CreateGroup(groupPool.random(random), hidden("Groupe ${random.nextInt(4)}"))
            }

            // A group under another one: allowed by the rules of some of the ensembles drawn.
            13 -> {
                Change.CreateGroup(groupPool.random(random), hidden("Sous-groupe"), parent = group)
            }

            14, 15 -> {
                Change.RenameGroup(group, hidden("Groupe ${random.nextInt(4)}"))
            }

            16 -> {
                Change.DeleteGroup(group)
            }

            17, 18, 19, 20, 21, 22, 23, 24 -> {
                Change.AssignMember(member, group)
            }

            25, 26, 27 -> {
                Change.UnassignMember(member)
            }

            28, 29, 30, 31, 32 -> {
                val candidate = if (unit == ROOT) member else state.membersOf(group).randomOrNull(random)?.id ?: member
                Change.SetPost(unit, post, candidate)
            }

            33, 34 -> {
                Change.ClearPost(unit, post)
            }

            // To an administrator most of the time: the others are refused.
            35, 36, 37 -> {
                val successor = state.admins.takeIf { random.nextInt(4) > 0 }?.randomOrNull(random) ?: member
                Change.TransferCreator(successor)
            }

            // Rare: once it applies nothing else does.
            38 -> {
                if (random.nextInt(5) == 0) Change.Dissolve else Change.AssignMember(member, group)
            }

            else -> {
                Change.RemoveMember(state.creator ?: member)
            }
        }
    }

    /**
     * Five phones and three authors without a phone (two members and someone outside) write at random, with the
     * rights or without, sometimes two operations under one number, and exchange part of what they hold. At the end
     * every operation ever written is handed to new phones in different orders: one state.
     */
    private fun convergence(seed: Long) {
        val random = Random(seed)
        val limits = HierarchyLimits(maxMembers = 8, maxGroups = 5, maxDepth = if (seed % 3 == 0L) 2 else 1)
        val rules =
            if (seed % 2 == 0L) {
                // One ensemble in five keeps the rule of the first round: no deputy takes the place of its leader.
                HierarchyRules(limits = limits, deputySucceeds = seed % 10 != 0L)
            } else {
                HierarchyRules(
                    rights =
                        RightsTable.DEFAULT
                            .with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                            .with(Operation.UNASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                            .with(Operation.ASSIGN_MEMBER, Role.MEMBER, Reach.SELF)
                            .with(Operation.UNASSIGN_MEMBER, Role.MEMBER, Reach.SELF)
                            .with(Operation.NAME_GROUP_LEADER, Role.ENSEMBLE_LEADER, Reach.ANYONE)
                            .with(Operation.NAME_GROUP_DEPUTY, Role.GROUP_LEADER, Reach.OWN_GROUP)
                            .with(Operation.REMOVE_MEMBER, Role.ADMIN, Reach.ANYONE),
                    limits = limits,
                )
            }
        val origin = staffed(rules)
        for (id in listOf(E, F, H)) origin.must(admit(id))
        origin.must(Change.AddAdmin(C))
        origin.must(Change.AddAdmin(F))
        val phones = listOf(origin) + listOf(B, C, D, E).map(origin::copyFor)
        val written = origin.ops().toMutableList()
        val ghostSeq = HashMap<Long, Int>()
        repeat(400) {
            // The first three phones hold the creator and two administrators at the start: they write more often.
            val phone = if (random.nextInt(4) > 0) phones[random.nextInt(3)] else phones.random(random)
            when (random.nextInt(20)) {
                in 0..11 -> {
                    (phone.issue(randomChange(random, phone.state)) as? IssueResult.Issued)?.let { written.add(it.op) }
                }

                in 12..13 -> {
                    // An author without a phone: its operations are written by hand, clocks not always forward.
                    val author = listOf(F, H, OUTSIDER).random(random)
                    val seq = (ghostSeq[author] ?: 0) + 1
                    ghostSeq[author] = seq
                    val clock = random.nextLong(1, written.maxOf { it.clock } + 3)
                    val op = signedOp(origin.ensembleId, author, seq, clock, randomChange(random, phone.state))
                    written.add(op)
                    phone.add(op)
                }

                14 -> {
                    // A second operation under the number of one already written, signed by the same author.
                    val twin = written.random(random)
                    val op =
                        signedOp(twin.ensembleId, twin.author, twin.seq, twin.clock, randomChange(random, phone.state))
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
        val state = fresh.state
        assertTrue(state.created, "seed $seed")
        assertTrue(state.creator in state.members, "seed $seed: always exactly one creator")
        assertTrue(fresh.history().any { it.rejection != null }, "seed $seed: some operations must have been refused")
        applied += fresh.history().count { it.rejection == null }
        // Who waits is in no list of readers but the one of its own lane, whatever happened; nobody reads a
        // channel without being in the ensemble; a leader and its deputy are two members.
        for (channel in state.channels()) {
            for (reader in state.readersOf(channel)) {
                val own = channel == Channel.waiting(reader.id)
                assertEquals(own, state.isWaiting(reader.id), "seed $seed, $channel")
            }
        }
        for (unit in listOf(ROOT) + state.groups.keys) {
            val leader = state.leaderOf(unit)
            assertTrue(leader == null || leader != state.deputyOf(unit), "seed $seed")
        }
        seen.addAll(fresh.history().filter { it.rejection == null }.map { it.op.change::class })
        refusals.addAll(fresh.history().mapNotNull { it.rejection })
        if (state.dissolved) dissolved++
        // The phones themselves, once each has talked with a phone that heard everything.
        val hub = HierarchyReplica(origin.ensembleId, OUTSIDER).apply { addAll(all) }
        for (phone in phones) sync(phone, hub)
        for (phone in phones) sync(phone, hub)
        for (phone in phones) assertSame(fresh, phone, "seed $seed, phone ${phone.self}")
        // And the state itself survives its own format.
        assertEquals(state, HierarchyWire.decodeState(HierarchyWire.encodeState(state)), "seed $seed")
    }

    private val seen = HashSet<kotlin.reflect.KClass<out Change>>()
    private val refusals = HashSet<Rejection>()
    private var dissolved = 0
    private var applied = 0

    @Test
    fun `the random runs go through every kind of change, the new ones included`() {
        for (seed in 101..120L) convergence(seed)
        val expected =
            setOf(
                Change.CreateEnsemble::class,
                Change.AdmitMember::class,
                Change.RemoveMember::class,
                Change.AddAdmin::class,
                Change.RemoveAdmin::class,
                Change.CreateGroup::class,
                Change.RenameGroup::class,
                Change.DeleteGroup::class,
                Change.AssignMember::class,
                Change.UnassignMember::class,
                Change.SetPost::class,
                Change.ClearPost::class,
                Change.TransferCreator::class,
                Change.Dissolve::class,
            )
        assertEquals(expected, seen.toSet(), "kinds of change applied")
        assertTrue(dissolved > 0, "some runs end dissolved")
        assertTrue(applied > 20 * 30, "$applied operations applied")
        val reasons =
            setOf(
                Rejection.FORBIDDEN,
                Rejection.DISSOLVED,
                Rejection.IS_CREATOR,
                Rejection.NOT_AN_ADMIN,
                Rejection.NOT_A_MEMBER,
                Rejection.TOO_MANY,
            )
        assertTrue(refusals.containsAll(reasons), "refusals seen: $refusals")
    }
}
