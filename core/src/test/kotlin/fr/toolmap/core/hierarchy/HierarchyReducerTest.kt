package fr.toolmap.core.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of the user, one test each (`docs/specs/group-hierarchy.md` § 10 bis: V1 to V5, R1 to R20, and § 10 ter).
 * The name of a test starts with the answer it checks.
 */
class HierarchyReducerTest {
    private fun HierarchyState.ids(members: List<Member>) = members.map { it.id }

    private fun HierarchyState.readerIds(unit: Long) = ids(readersOf(unit))

    @Test
    fun `creation - the author is the creator and the only member, every post is vacant`() {
        val a = newEnsemble()
        val state = a.state
        assertTrue(state.created)
        assertEquals(OpAuth.ensembleId(A, genesis()), state.ensembleId)
        assertEquals("Sortie du jour", state.name)
        assertEquals(A, state.creator)
        assertNull(state.leader)
        assertNull(state.deputy)
        assertEquals(emptySet(), state.admins)
        assertEquals(Member(A, "Alpha", publicOf(A)), state.members[A])
        assertEquals(setOf(Role.CREATOR, Role.MEMBER), state.rolesOf(A))
        assertEquals(emptySet(), state.rolesOf(OUTSIDER))
        assertTrue(state.hasAccess(A))
        assertFalse(state.dissolved)
        assertNull(state.inconsistency())
        assertEquals(Rejection.ALREADY_CREATED, a.refused(genesis(name = "Autre")))
    }

    @Test
    fun `R1 a newcomer waits until an administrator assigns it to a group, and has access to nothing`() {
        val a = staffed()
        val state = a.state
        assertEquals(listOf(C, D), state.ids(state.waiting()))
        for (member in listOf(C, D)) {
            assertTrue(state.isWaiting(member))
            assertFalse(state.hasAccess(member))
            assertEquals(emptyList(), state.unitsReadBy(member))
            assertEquals(emptyList(), state.visibleTo(member))
            for (kind in ShareKind.entries) {
                for (unit in listOf(ROOT, G1, G2)) assertFalse(state.mayShare(member, kind, unit), "$kind in $unit")
            }
        }
        // No list of readers holds them: no content key is ever wrapped to their phone.
        assertEquals(listOf(A, B), state.readerIds(ROOT))
        assertEquals(emptyList(), state.readerIds(G1))

        val b = a.copyFor(B)
        b.must(Change.AssignMember(C, G1))
        val after = b.state
        assertFalse(after.isWaiting(C))
        assertTrue(after.hasAccess(C))
        assertEquals(listOf(G1, ROOT), after.unitsReadBy(C))
        assertEquals(listOf(A, B, C), after.readerIds(ROOT))
        assertEquals(listOf(C), after.readerIds(G1))
        assertEquals(listOf(D), after.ids(after.waiting()))

        // Out of its group, it waits again.
        b.must(Change.UnassignMember(C))
        assertTrue(b.state.isWaiting(C))
        assertEquals(listOf(A, B), b.state.readerIds(ROOT))
    }

    @Test
    fun `R1 the creator, the administrators, the ensemble leader and its deputy keep access without a group`() {
        val a =
            staffed().apply {
                must(admit(E))
                must(leader(ROOT, C))
                must(deputy(ROOT, D))
            }
        val state = a.state
        for (member in listOf(A, B, C, D)) {
            assertNull(state.members.getValue(member).group)
            assertTrue(state.hasAccess(member))
            assertEquals(listOf(ROOT), state.unitsReadBy(member))
        }
        assertTrue(state.isWaiting(E))
        assertEquals(listOf(A, B, C, D), state.readerIds(ROOT))
        // The access goes with the post.
        a.must(Change.ClearPost(ROOT, Post.DEPUTY))
        assertTrue(a.state.isWaiting(D))
        a.must(Change.RemoveAdmin(B))
        assertTrue(a.state.isWaiting(B))
        assertEquals(listOf(A, C), a.state.readerIds(ROOT))
    }

    @Test
    fun `V3 a member has one group, a new assignment overwrites the old one`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        a.must(Change.AssignMember(D, G1))
        a.must(leader(G1, C))
        a.must(deputy(G1, D))
        a.must(Change.AssignMember(C, G2))
        var state = a.state
        assertEquals(G2, state.groupOf(C)?.id)
        assertEquals(listOf(D), state.ids(state.membersOf(G1)))
        assertEquals(listOf(C), state.ids(state.membersOf(G2)))
        // The post it held in the group it leaves is vacant; it brings no post into the new one.
        assertNull(state.groups.getValue(G1).leader)
        assertEquals(D, state.groups.getValue(G1).deputy)
        assertEquals(setOf(Role.MEMBER), state.rolesOf(C))
        a.must(Change.AssignMember(D, G2))
        state = a.state
        assertNull(state.groups.getValue(G1).deputy)
        assertEquals(emptyList(), state.membersOf(G1))
        assertEquals(Rejection.NO_CHANGE, a.refused(Change.AssignMember(D, G2)))
        assertNull(state.inconsistency())
    }

    @Test
    fun `R2 among the members who have access everyone sees everyone, who waits sees nobody and is seen by nobody`() {
        val state = deployed().apply { must(admit(H)) }.state
        val inside = listOf(A, B, C, D, E, F)
        for (viewer in inside) for (target in inside) assertTrue(state.canSee(viewer, target), "$viewer $target")
        assertEquals(inside - A, state.ids(state.visibleTo(A)))
        assertEquals(emptyList(), state.visibleTo(H))
        for (member in inside) {
            assertFalse(state.canSee(member, H))
            assertFalse(state.canSee(H, member))
        }
        assertTrue(state.canSee(H, H), "everyone sees oneself")
        assertFalse(state.canSee(OUTSIDER, A))
        assertFalse(state.canSee(A, OUTSIDER))
        assertFalse(state.canSee(OUTSIDER, OUTSIDER))
    }

    @Test
    fun `R2 positions go to the whole ensemble, from everyone who has access`() {
        val state = deployed().apply { must(admit(H)) }.state
        for (member in listOf(A, B, C, D, E, F)) {
            assertEquals(listOf(ROOT), state.shareUnits(member, ShareKind.POSITION), "$member")
        }
        assertFalse(state.mayShare(C, ShareKind.POSITION, G1))
        assertEquals(emptyList(), state.shareUnits(H, ShareKind.POSITION))
        assertEquals(emptyList(), state.shareUnits(OUTSIDER, ShareKind.POSITION))
    }

    @Test
    fun `R3 only the administrators and the creator assign members to groups`() {
        val a =
            deployed().apply {
                must(leader(G1, C))
                must(deputy(G1, D))
                must(leader(ROOT, E))
                must(deputy(ROOT, F))
                must(admit(H))
            }
        // Leaders and deputies of a group or of the ensemble, plain or waiting members: none of them.
        for (author in listOf(C, D, E, F, H)) {
            val phone = a.copyFor(author)
            assertEquals(Rejection.FORBIDDEN, phone.refused(Change.AssignMember(H, G1)), "$author")
            assertEquals(Rejection.FORBIDDEN, phone.refused(Change.UnassignMember(D)), "$author")
            assertEquals(a.state, phone.state)
        }
        a.copyFor(B).must(Change.AssignMember(H, G1))
        a.must(Change.AssignMember(H, G2))
        a.must(Change.UnassignMember(H))
    }

    @Test
    fun `R4 a member shares sketches, waypoints and messages inside its own group only`() {
        val state = deployed().state
        for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT, ShareKind.MESSAGE)) {
            assertEquals(listOf(G1), state.shareUnits(C, kind), "$kind")
            assertFalse(state.mayShare(C, kind, G2), "$kind into another group")
            assertFalse(state.mayShare(C, kind, ROOT), "$kind into the ensemble")
            assertFalse(state.mayShare(C, kind, G3), "$kind into a group that does not exist")
        }
    }

    @Test
    fun `R4 the leader of a group and its deputy share sketches and waypoints one level up, never higher`() {
        val sub = 0x201L
        val state =
            deployed(TWO_LEVELS)
                .apply {
                    must(Change.CreateGroup(sub, "Sous-groupe", parent = G1))
                    must(admit(H))
                    must(admit(I))
                    must(admit(J))
                    must(Change.AssignMember(H, sub))
                    must(Change.AssignMember(I, sub))
                    must(Change.AssignMember(J, sub))
                    must(leader(G1, C))
                    must(deputy(G1, D))
                    must(leader(sub, H))
                    must(deputy(sub, I))
                }.state
        for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT)) {
            // The leader of a sub-group and its deputy: into the group, not into the ensemble.
            assertEquals(listOf(sub, G1), state.shareUnits(H, kind), "$kind")
            assertEquals(listOf(sub, G1), state.shareUnits(I, kind), "$kind")
            assertFalse(state.mayShare(H, kind, ROOT))
            // The leader of a group and its deputy: into the ensemble, and there is nothing above.
            assertEquals(listOf(G1, ROOT), state.shareUnits(C, kind), "$kind")
            assertEquals(listOf(G1, ROOT), state.shareUnits(D, kind), "$kind")
            // A plain member: its own unit.
            assertEquals(listOf(sub), state.shareUnits(J, kind), "$kind")
            assertEquals(listOf(G2), state.shareUnits(E, kind), "$kind")
            // Never downwards, never sideways.
            assertFalse(state.mayShare(C, kind, sub))
            assertFalse(state.mayShare(C, kind, G2))
            assertFalse(state.mayShare(H, kind, G2))
        }
        // Messages do not go up with the leaders.
        assertEquals(listOf(sub), state.shareUnits(H, ShareKind.MESSAGE))
        assertEquals(listOf(G1), state.shareUnits(C, ShareKind.MESSAGE))
    }

    @Test
    fun `R5 the ensemble leader has a title and no right over the hierarchy`() {
        val a =
            deployed().apply {
                must(leader(ROOT, C))
                must(deputy(ROOT, D))
                must(leader(G2, E))
                must(admit(H))
            }
        val changes =
            listOf(
                admit(I),
                Change.RemoveMember(F),
                Change.AddAdmin(F),
                Change.RemoveAdmin(B),
                Change.CreateGroup(G3, "Groupe 3"),
                Change.RenameGroup(G1, "Mon groupe"),
                Change.DeleteGroup(G2),
                Change.AssignMember(H, G1),
                Change.UnassignMember(F),
                leader(G1, D),
                deputy(G1, D),
                leader(ROOT, F),
                deputy(ROOT, F),
                Change.TransferCreator(C),
                Change.Dissolve,
            )
        assertEquals(Operation.entries.toSet(), changes.map { it.operation }.toSet(), "one change per operation")
        // C leads the ensemble, D is its deputy, E leads a group: titles.
        for (author in listOf(C, D, E)) {
            val phone = a.copyFor(author)
            for (change in changes) assertEquals(Rejection.FORBIDDEN, phone.refused(change), "$author $change")
            assertEquals(a.state, phone.state)
        }
        // The creator may do all of it; an administrator, all but what is the creator's.
        val creatorOnly =
            setOf(
                Operation.REMOVE_MEMBER,
                Operation.ADD_ADMIN,
                Operation.REMOVE_ADMIN,
                Operation.TRANSFER_CREATOR,
                Operation.DISSOLVE,
            )
        for (change in changes) {
            assertNull(HierarchyReducer.check(a.state, A, change), "$change")
            val expected = if (change.operation in creatorOnly) Rejection.FORBIDDEN else null
            assertEquals(expected, HierarchyReducer.check(a.state, B, change), "$change")
        }
        // Someone who is not in the ensemble may do nothing.
        assertEquals(Rejection.NOT_A_MEMBER, a.copyFor(OUTSIDER).refused(Change.CreateGroup(G3, "Groupe 3")))
    }

    @Test
    fun `R6 only the ensemble leader, its deputy, the administrators and the creator message the whole ensemble`() {
        val state =
            deployed()
                .apply {
                    must(leader(ROOT, C))
                    must(deputy(ROOT, E))
                    must(leader(G1, D))
                    must(leader(G2, F))
                }.state
        for (member in listOf(A, B, C, E)) assertTrue(state.mayShare(member, ShareKind.MESSAGE, ROOT), "$member")
        // The leaders of the groups do not: what they send up are sketches and waypoints.
        for (member in listOf(D, F)) assertFalse(state.mayShare(member, ShareKind.MESSAGE, ROOT), "$member")
        // The leader of the ensemble, member of G1, also writes to its own group.
        assertEquals(listOf(G1, ROOT), state.shareUnits(C, ShareKind.MESSAGE))
        assertEquals(listOf(ROOT), state.shareUnits(B, ShareKind.MESSAGE))
    }

    @Test
    fun `R7 two threads - a member reads its own group and the whole ensemble, not another group`() {
        val state = deployed().state
        assertEquals(listOf(G1, ROOT), state.unitsReadBy(C))
        assertEquals(listOf(G2, ROOT), state.unitsReadBy(E))
        // An administrator who is in no group reads the ensemble, and no group's thread.
        assertEquals(listOf(ROOT), state.unitsReadBy(B))
        assertEquals(listOf(C, D), state.readerIds(G1))
        assertEquals(listOf(E, F), state.readerIds(G2))
        assertEquals(listOf(A, B, C, D, E, F), state.readerIds(ROOT))
        assertEquals(emptyList(), state.readerIds(G3))
    }

    @Test
    fun `R8 roles cumulate`() {
        val a =
            deployed().apply {
                must(Change.AddAdmin(C))
                must(leader(ROOT, C))
                must(leader(G1, C))
                must(deputy(ROOT, D))
                must(deputy(G1, D))
                must(Change.TransferCreator(C))
            }
        assertEquals(
            setOf(Role.CREATOR, Role.ADMIN, Role.ENSEMBLE_LEADER, Role.GROUP_LEADER, Role.MEMBER),
            a.state.rolesOf(C),
        )
        assertEquals(setOf(Role.ENSEMBLE_DEPUTY, Role.GROUP_DEPUTY, Role.MEMBER), a.state.rolesOf(D))
        assertNull(a.state.inconsistency())
    }

    @Test
    fun `R8 a leader has one deputy, who has the rights of the leader`() {
        val rights =
            RightsTable.DEFAULT
                .with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.CREATE_GROUP, Role.ENSEMBLE_LEADER, Reach.ANYONE)
        val a =
            deployed(HierarchyRules(rights = rights)).apply {
                must(leader(G1, C))
                must(deputy(G1, D))
                must(leader(ROOT, E))
                must(deputy(ROOT, F))
                must(admit(H))
            }
        // Nothing is written for the deputies: what the leader may do, its deputy may do.
        assertEquals(emptySet(), rights.reach(Operation.ASSIGN_MEMBER, Role.GROUP_DEPUTY))
        assertEquals(emptySet(), rights.reach(Operation.CREATE_GROUP, Role.ENSEMBLE_DEPUTY))
        val d = a.copyFor(D)
        assertEquals(Rejection.FORBIDDEN, d.refused(Change.AssignMember(H, G2)))
        d.must(Change.AssignMember(H, G1))
        a.copyFor(F).must(Change.CreateGroup(G3, "Groupe 3"))
        // The same for what is shared: one level up for the deputy of a group, the whole ensemble for the other.
        assertTrue(a.state.mayShare(D, ShareKind.SKETCH, ROOT))
        assertTrue(a.state.mayShare(F, ShareKind.MESSAGE, ROOT))
        assertEquals(emptySet(), ShareTable.DEFAULT.reach(ShareKind.SKETCH, Role.GROUP_DEPUTY))

        // One deputy: another one named takes the place.
        a.must(Change.AssignMember(H, G1))
        a.must(deputy(G1, H))
        assertEquals(H, a.state.groups.getValue(G1).deputy)
        assertEquals(setOf(Role.MEMBER), a.state.rolesOf(D))
        assertFalse(a.state.mayShare(D, ShareKind.SKETCH, ROOT))
        assertEquals(Rejection.NO_CHANGE, a.refused(deputy(G1, H)))
        // One post of a unit at most: the deputy named leader is no longer the deputy, and the reverse.
        a.must(leader(G1, H))
        assertEquals(H, a.state.groups.getValue(G1).leader)
        assertNull(a.state.groups.getValue(G1).deputy)
        assertEquals(setOf(Role.MEMBER), a.state.rolesOf(C))
        a.must(deputy(G1, H))
        assertNull(a.state.groups.getValue(G1).leader)
        // A deputy whose leader's post is vacant keeps the rights.
        assertTrue(a.state.mayShare(H, ShareKind.WAYPOINT, ROOT))
        a.must(leader(ROOT, F))
        assertNull(a.state.deputy)
        assertEquals(F, a.state.leader)
        assertNull(a.state.inconsistency())
    }

    @Test
    fun `R9 only the creator removes a member from the ensemble`() {
        val a =
            deployed().apply {
                must(leader(ROOT, E))
                must(leader(G1, C))
            }
        // An administrator, the leader of the ensemble, the leader of its group, a member of its group: none.
        for (author in listOf(B, E, C, D)) {
            assertEquals(Rejection.FORBIDDEN, a.copyFor(author).refused(Change.RemoveMember(F)), "$author")
        }
        a.must(leader(G2, F))
        a.must(Change.AddAdmin(F))
        a.must(deputy(ROOT, F))
        a.must(Change.RemoveMember(F))
        val state = a.state
        // Out of everything it held.
        assertFalse(F in state.members)
        assertFalse(F in state.admins)
        assertNull(state.deputy)
        assertNull(state.groups.getValue(G2).leader)
        assertNull(state.inconsistency())
        // Someone removed may be admitted again: it waits, like a newcomer.
        a.must(admit(F, "Foxtrot"))
        assertEquals(setOf(Role.MEMBER), a.state.rolesOf(F))
        assertTrue(a.state.isWaiting(F))
    }

    @Test
    fun `R10 the creator who leaves names a successor, who becomes the creator`() {
        val a = deployed()
        assertEquals(Rejection.NO_SUCH_MEMBER, a.refused(Change.TransferCreator(OUTSIDER)))
        assertEquals(Rejection.NO_CHANGE, a.refused(Change.TransferCreator(A)))
        a.must(Change.TransferCreator(C))
        val state = a.state
        assertEquals(C, state.creator)
        assertEquals(setOf(Role.CREATOR, Role.MEMBER), state.rolesOf(C))
        // The one who handed over is a member like another: no right left, and it leaves like any member.
        assertEquals(setOf(Role.MEMBER), state.rolesOf(A))
        assertEquals(Rejection.FORBIDDEN, a.refused(Change.AddAdmin(D)))
        assertEquals(Rejection.FORBIDDEN, a.refused(Change.TransferCreator(A)))
        a.must(Change.RemoveMember(A))
        val c = a.copyFor(C)
        c.must(Change.AddAdmin(D))
        c.must(Change.RemoveMember(F))
        assertEquals(Rejection.IS_CREATOR, c.refused(Change.RemoveMember(C)))
        assertNull(c.state.inconsistency())
    }

    @Test
    fun `R10 the creator neither leaves nor is removed while it is the creator`() {
        val a = staffed()
        assertEquals(Rejection.IS_CREATOR, a.refused(Change.RemoveMember(A)))
        // Even in an ensemble whose table lets the administrators remove members.
        val open =
            staffed(
                HierarchyRules(rights = RightsTable.DEFAULT.with(Operation.REMOVE_MEMBER, Role.ADMIN, Reach.ANYONE)),
            )
        val b = open.copyFor(B)
        assertEquals(Rejection.IS_CREATOR, b.refused(Change.RemoveMember(A)))
        b.must(Change.RemoveMember(C))
        assertEquals(A, b.state.creator)
    }

    @Test
    fun `R10 a creator who names nobody dissolves the ensemble for everyone, and nothing applies after`() {
        val a = deployed().apply { must(leader(ROOT, C)) }
        for (author in listOf(B, C, D)) {
            assertEquals(Rejection.FORBIDDEN, a.copyFor(author).refused(Change.Dissolve), "$author")
        }
        a.must(Change.Dissolve)
        val state = a.state
        assertTrue(state.dissolved)
        for (member in state.members.keys) {
            assertFalse(state.hasAccess(member))
            assertEquals(emptyList(), state.unitsReadBy(member))
            assertEquals(emptyList(), state.visibleTo(member))
            for (kind in ShareKind.entries) assertEquals(emptyList(), state.shareUnits(member, kind))
        }
        for (unit in listOf(ROOT, G1, G2)) assertEquals(emptyList(), state.readersOf(unit))
        assertEquals(emptyList(), state.allReaders())
        // Terminal: nothing applies any more, for anyone, not even the creator.
        val later =
            listOf(
                admit(H),
                Change.AssignMember(C, G2),
                Change.TransferCreator(B),
                Change.Dissolve,
                Change.RemoveMember(B),
            )
        for (change in later) assertEquals(Rejection.DISSOLVED, a.refused(change), "$change")
        assertEquals(Rejection.DISSOLVED, a.copyFor(B).refused(Change.RemoveMember(B)))
        assertEquals(Rejection.DISSOLVED, a.copyFor(C).refused(Change.RemoveMember(C)))
        assertEquals(state, a.state)
        assertNull(state.inconsistency())
        assertEquals(state, HierarchyWire.decodeState(HierarchyWire.encodeState(state)))
    }

    @Test
    fun `R11 the post of ensemble leader stays vacant until an administrator names someone`() {
        val a =
            deployed().apply {
                must(leader(ROOT, C))
                must(deputy(ROOT, D))
            }
        // The leader leaves: the post is vacant, the deputy is still the deputy, nobody is promoted.
        val c = a.copyFor(C)
        c.must(Change.RemoveMember(C))
        assertNull(c.state.leader)
        assertEquals(D, c.state.deputy)
        assertNull(c.state.inconsistency())
        // Nothing is blocked meanwhile, and an administrator names whom it wants.
        val b = c.copyFor(B)
        b.must(Change.AssignMember(E, G1))
        b.must(leader(ROOT, E))
        assertEquals(E, b.state.leader)
        // An administrator may also leave the post vacant.
        a.must(Change.ClearPost(ROOT, Post.LEADER))
        assertNull(a.state.leader)
        assertEquals(Rejection.NO_CHANGE, a.refused(Change.ClearPost(ROOT, Post.LEADER)))
    }

    @Test
    fun `R12 a group without a leader is allowed and blocks nothing`() {
        val a = deployed()
        assertNull(a.state.groups.getValue(G1).leader)
        assertTrue(a.state.mayShare(C, ShareKind.MESSAGE, G1))
        a.must(Change.RenameGroup(G1, "Reconnaissance"))
        a.must(admit(H))
        a.must(Change.AssignMember(H, G1))
        a.must(leader(G1, C))
        a.must(Change.ClearPost(G1, Post.LEADER))
        assertNull(a.state.groups.getValue(G1).leader)
        assertEquals(Rejection.NO_CHANGE, a.refused(Change.ClearPost(G1, Post.LEADER)))
        // A leader who is taken out of its group leaves the post vacant; it is not the leader from outside.
        a.must(leader(G1, C))
        a.must(Change.UnassignMember(C))
        assertNull(a.state.groups.getValue(G1).leader)
        assertEquals(Rejection.NOT_IN_GROUP, a.refused(leader(G1, C)))
        assertTrue(a.state.mayShare(D, ShareKind.SKETCH, G1))
        assertNull(a.state.inconsistency())
    }

    @Test
    fun `R13 a member may leave the ensemble but cannot change group alone`() {
        val a = deployed().apply { must(admit(H)) }
        val c = a.copyFor(C)
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.AssignMember(C, G2)))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.UnassignMember(C)))
        c.must(Change.RemoveMember(C))
        assertFalse(C in c.state.members)
        assertEquals(listOf(D), c.state.readerIds(G1))
        assertEquals(Rejection.NOT_A_MEMBER, c.refused(admit(C)))
        // Someone who waits may leave too; nobody leaves in the place of another.
        val h = a.copyFor(H)
        assertEquals(Rejection.FORBIDDEN, h.refused(Change.RemoveMember(D)))
        h.must(Change.RemoveMember(H))
        assertFalse(H in h.state.members)
    }

    @Test
    fun `R14 the creator names and removes the administrators, an administrator cannot`() {
        val a = staffed()
        val b = a.copyFor(B)
        assertEquals(Rejection.FORBIDDEN, b.refused(Change.AddAdmin(C)))
        a.must(Change.AddAdmin(C))
        assertEquals(setOf(Role.ADMIN, Role.MEMBER), a.state.rolesOf(C))
        sync(a, b)
        assertEquals(Rejection.FORBIDDEN, b.refused(Change.RemoveAdmin(C)))
        assertEquals(Rejection.FORBIDDEN, b.refused(Change.RemoveAdmin(B)), "not even itself")
        a.must(Change.RemoveAdmin(C))
        a.must(Change.RemoveAdmin(B))
        assertEquals(Rejection.NOT_AN_ADMIN, a.refused(Change.RemoveAdmin(B)))
        // An ensemble may have no administrator at all: its creator does what they do.
        assertEquals(emptySet(), a.state.admins)
        a.must(Change.CreateGroup(G3, "Groupe 3"))
        a.must(Change.AssignMember(C, G3))
    }

    @Test
    fun `R14 the administrators name the ensemble leader, the group leaders and their deputies`() {
        val a = deployed()
        val b = a.copyFor(B)
        b.must(leader(ROOT, C))
        b.must(deputy(ROOT, E))
        b.must(leader(G1, C))
        b.must(deputy(G1, D))
        b.must(leader(G2, E))
        b.must(deputy(G2, F))
        val state = b.state
        assertEquals(setOf(Role.ENSEMBLE_LEADER, Role.GROUP_LEADER, Role.MEMBER), state.rolesOf(C))
        assertEquals(setOf(Role.ENSEMBLE_DEPUTY, Role.GROUP_LEADER, Role.MEMBER), state.rolesOf(E))
        assertEquals(setOf(Role.GROUP_DEPUTY, Role.MEMBER), state.rolesOf(D))
        assertEquals(C, state.leaderOf(ROOT))
        assertEquals(E, state.deputyOf(ROOT))
        assertEquals(E, state.leaderOf(G2))
        assertEquals(F, state.deputyOf(G2))
        // Nobody else names anybody, nor leaves a post vacant, not even its own.
        val naming =
            listOf(
                leader(ROOT, F),
                deputy(ROOT, D),
                leader(G1, D),
                deputy(G2, E),
                Change.ClearPost(ROOT, Post.LEADER),
                Change.ClearPost(G1, Post.DEPUTY),
            )
        for (author in listOf(C, D, E, F)) {
            val phone = b.copyFor(author)
            for (change in naming) assertEquals(Rejection.FORBIDDEN, phone.refused(change), "$author $change")
        }
        b.must(Change.ClearPost(G1, Post.DEPUTY))
        a.copyFor(A).must(leader(ROOT, D))
    }

    @Test
    fun `R14 only the administrators and the creator create, rename and delete groups`() {
        val a =
            deployed().apply {
                must(leader(ROOT, C))
                must(leader(G1, C))
                must(deputy(G1, D))
            }
        for (author in listOf(C, D, E)) {
            val phone = a.copyFor(author)
            assertEquals(Rejection.FORBIDDEN, phone.refused(Change.CreateGroup(G3, "Groupe 3")), "$author")
            assertEquals(Rejection.FORBIDDEN, phone.refused(Change.RenameGroup(G1, "Mon groupe")), "$author")
            assertEquals(Rejection.FORBIDDEN, phone.refused(Change.DeleteGroup(G1)), "$author")
        }
        val b = a.copyFor(B)
        b.must(Change.CreateGroup(G3, "Groupe 3"))
        b.must(Change.RenameGroup(G3, "Reconnaissance"))
        assertEquals("Reconnaissance", b.state.groups.getValue(G3).name)
        b.must(Change.DeleteGroup(G3))
        a.must(Change.RenameGroup(G1, "Tête"))
    }

    @Test
    fun `R17 one level of groups today - a group under a group is refused`() {
        val a = staffed()
        assertEquals(1, a.state.rules.limits.maxDepth)
        assertEquals(Rejection.TOO_DEEP, a.refused(Change.CreateGroup(G3, "Sous-groupe", parent = G1)))
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.CreateGroup(G3, "Orphelin", parent = 0x999)))
    }

    @Test
    fun `R17 the same rules hold for sub-groups when the limits of an ensemble allow them`() {
        val s1 = 0x201L
        val s2 = 0x202L
        val a = deployed(TWO_LEVELS)
        a.must(Change.CreateGroup(s1, "Sous-groupe 1", parent = G1))
        a.must(Change.CreateGroup(s2, "Sous-groupe 2", parent = G1))
        assertEquals(Rejection.TOO_DEEP, a.refused(Change.CreateGroup(0x301, "Trop profond", parent = s1)))
        // C stays in the group itself, D goes into the first sub-group, E and F move under G1 too.
        a.must(Change.AssignMember(D, s1))
        a.must(Change.AssignMember(E, s1))
        a.must(Change.AssignMember(F, s2))
        a.must(leader(G1, C))
        a.must(leader(s1, D))
        a.must(deputy(s1, E))
        // The leader of a unit is a member of that very unit, not of a unit under it.
        assertEquals(Rejection.NOT_IN_GROUP, a.refused(leader(G1, D)))
        var state = a.state
        assertEquals(listOf(0, 1, 2), listOf(ROOT, G1, s1).map(state::depthOf))
        assertEquals(listOf(s1, G1, ROOT), state.pathToRoot(s1))
        assertTrue(state.isWithin(s1, G1))
        assertFalse(state.isWithin(s1, G2))
        assertEquals(listOf(s1, s2), state.childrenOf(G1).map { it.id })
        assertEquals(G1, state.parentOf(s1))
        assertNull(state.parentOf(ROOT))
        // One reads its unit and every unit above; a unit is read by everyone in it and under it.
        assertEquals(listOf(s1, G1, ROOT), state.unitsReadBy(D))
        assertEquals(listOf(G1, ROOT), state.unitsReadBy(C))
        assertEquals(listOf(C, D, E, F), state.readerIds(G1))
        assertEquals(listOf(D, E), state.readerIds(s1))
        assertEquals(emptyList(), state.readerIds(G2))
        assertEquals(setOf(Role.GROUP_LEADER, Role.MEMBER), state.rolesOf(D))
        assertNull(state.inconsistency())
        assertEquals(state, HierarchyWire.decodeState(HierarchyWire.encodeState(state)))

        // A group that still has groups under it is not deleted; emptied of them, it is.
        assertEquals(Rejection.HAS_SUBGROUPS, a.refused(Change.DeleteGroup(G1)))
        a.must(Change.DeleteGroup(s2))
        assertTrue(a.state.isWaiting(F))
        a.must(Change.DeleteGroup(s1))
        a.must(Change.DeleteGroup(G1))
        state = a.state
        assertEquals(listOf(C, D, E, F), state.ids(state.waiting()))
        assertEquals(
            Rejection.NO_SUCH_GROUP,
            a.refused(Change.CreateGroup(0x203, "Sous un groupe supprimé", parent = G1)),
        )
        assertNull(state.inconsistency())
    }

    @Test
    fun `R19 thirty members and six groups at the start, the limits are data of the ensemble`() {
        assertEquals(HierarchyLimits(maxMembers = 30, maxGroups = 6, maxDepth = 1), HierarchyRules.DEFAULT.limits)
        val a = newEnsemble()
        val others = List(30) { OpAuth.memoryKeys() }
        for (keys in others.take(29)) a.must(Change.AdmitMember(keys.memberId, "Membre", keys.public))
        assertEquals(30, a.state.members.size)
        assertEquals(
            Rejection.TOO_MANY,
            a.refused(Change.AdmitMember(others[29].memberId, "De trop", others[29].public)),
        )
        for (i in 1..6) a.must(Change.CreateGroup(2000L + i, "Groupe $i"))
        assertEquals(Rejection.TOO_MANY, a.refused(Change.CreateGroup(3000, "De trop")))
        assertNull(a.state.inconsistency())

        // Another ensemble, other limits: nothing else changes.
        val small = newEnsemble(HierarchyRules(limits = HierarchyLimits(maxMembers = 3, maxGroups = 2)))
        small.must(admit(B))
        small.must(admit(C))
        assertEquals(Rejection.TOO_MANY, small.refused(admit(D)))
        small.must(Change.CreateGroup(G1, "Groupe 1"))
        small.must(Change.CreateGroup(G2, "Groupe 2"))
        assertEquals(Rejection.TOO_MANY, small.refused(Change.CreateGroup(G3, "Groupe 3")))
        // A member who leaves, a group deleted, free a place.
        small.copyFor(C).also { c ->
            c.must(Change.RemoveMember(C))
            sync(c, small)
        }
        small.must(admit(D))
        small.must(Change.DeleteGroup(G1))
        small.must(Change.CreateGroup(G3, "Groupe 3"))
        val big = newEnsemble(HierarchyRules(limits = HierarchyLimits(maxMembers = 300, maxGroups = 40, maxDepth = 3)))
        assertEquals(300, big.state.rules.limits.maxMembers)
        // Limits the format cannot carry are no limits: no phone takes such an ensemble.
        for (limits in listOf(
            HierarchyLimits(maxMembers = 5000),
            HierarchyLimits(maxGroups = 2000),
            HierarchyLimits(maxDepth = 9),
        )) {
            assertTrue(genesis(rules = HierarchyRules(limits = limits)).isMalformed(), "$limits")
        }
        assertTrue(genesis(rules = HierarchyRules(limits = HierarchyLimits(maxDepth = 0))).isMalformed())
    }

    @Test
    fun `deleting a group leaves its members in the ensemble, waiting for a group`() {
        val a = deployed()
        a.must(leader(G1, D))
        a.must(Change.DeleteGroup(G1))
        val state = a.state
        assertFalse(G1 in state.groups)
        assertEquals(listOf(C, D), state.ids(state.waiting()))
        assertEquals(setOf(Role.MEMBER), state.rolesOf(D))
        assertEquals(listOf(A, B, E, F), state.readerIds(ROOT))
        assertNull(state.inconsistency())
        // The identifier of a deleted group never comes back, and nothing can be done with it.
        assertEquals(Rejection.GROUP_DELETED, a.refused(Change.CreateGroup(G1, "Revenant")))
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.AssignMember(C, G1)))
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.RenameGroup(G1, "Revenant")))
    }

    @Test
    fun `what cannot be is refused with its reason and changes nothing`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        val before = a.state
        val notAKey = MemberKeys(publicOf(E).signing, ByteArray(P256.POINT_BYTES))
        val refusals =
            listOf(
                admit(C, "Charlie") to Rejection.ALREADY_MEMBER,
                Change.AdmitMember(0, "Zéro", publicOf(E)) to Rejection.MALFORMED,
                admit(E, " ") to Rejection.MALFORMED,
                admit(E, "x".repeat(Names.MAX_BYTES + 1)) to Rejection.MALFORMED,
                admit(E, "deux\nlignes") to Rejection.MALFORMED,
                // Keys that are the ones of another phone, or that are no keys.
                Change.AdmitMember(E, "Echo", publicOf(F)) to Rejection.MALFORMED,
                Change.AdmitMember(E, "Echo", notAKey) to Rejection.MALFORMED,
                Change.RemoveMember(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.RemoveMember(A) to Rejection.IS_CREATOR,
                Change.AddAdmin(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.AddAdmin(B) to Rejection.NO_CHANGE,
                Change.RemoveAdmin(C) to Rejection.NOT_AN_ADMIN,
                Change.CreateGroup(G1, "Encore") to Rejection.GROUP_EXISTS,
                Change.CreateGroup(0, "Zéro") to Rejection.MALFORMED,
                Change.CreateGroup(G3, "Sous-groupe", parent = G1) to Rejection.TOO_DEEP,
                Change.CreateGroup(G3, "Orphelin", parent = 0x999) to Rejection.NO_SUCH_GROUP,
                Change.RenameGroup(G3, "Rien") to Rejection.NO_SUCH_GROUP,
                Change.RenameGroup(G1, "Groupe 1") to Rejection.NO_CHANGE,
                Change.DeleteGroup(G3) to Rejection.NO_SUCH_GROUP,
                Change.AssignMember(OUTSIDER, G1) to Rejection.NO_SUCH_MEMBER,
                Change.AssignMember(C, G3) to Rejection.NO_SUCH_GROUP,
                Change.AssignMember(C, G1) to Rejection.NO_CHANGE,
                Change.UnassignMember(D) to Rejection.NO_CHANGE,
                Change.UnassignMember(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                leader(G1, D) to Rejection.NOT_IN_GROUP,
                deputy(G2, C) to Rejection.NOT_IN_GROUP,
                leader(G1, OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                leader(G3, C) to Rejection.NO_SUCH_GROUP,
                leader(G1, 0) to Rejection.MALFORMED,
                leader(ROOT, OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.ClearPost(G1, Post.LEADER) to Rejection.NO_CHANGE,
                Change.ClearPost(ROOT, Post.DEPUTY) to Rejection.NO_CHANGE,
                Change.ClearPost(G3, Post.LEADER) to Rejection.NO_SUCH_GROUP,
                Change.TransferCreator(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.TransferCreator(A) to Rejection.NO_CHANGE,
                Change.TransferCreator(0) to Rejection.MALFORMED,
            )
        for ((change, reason) in refusals) {
            assertEquals(reason, a.refused(change), "$change")
            assertEquals(reason, HierarchyReducer.check(a.state, A, change), "$change")
        }
        assertEquals(before, a.state)
        assertNull(HierarchyReducer.check(a.state, A, Change.AssignMember(D, G2)))
    }

    @Test
    fun `the rights table is data, another table gives other rights without another engine`() {
        val rights =
            RightsTable.DEFAULT
                .with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.UNASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.RENAME_GROUP, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.NAME_GROUP_LEADER, Role.GROUP_LEADER, Reach.SELF)
                .with(Operation.UNASSIGN_MEMBER, Role.MEMBER, Reach.SELF)
                .with(Operation.CREATE_GROUP, Role.ENSEMBLE_LEADER, Reach.ANYONE)
        assertEquals(setOf(Reach.OWN_GROUP), rights.reach(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER))
        assertEquals(setOf(Reach.ANYONE), rights.reach(Operation.ASSIGN_MEMBER, Role.ADMIN))
        assertEquals(emptySet(), rights.reach(Operation.ASSIGN_MEMBER, Role.MEMBER))

        val a = staffed(HierarchyRules(rights = rights))
        a.must(Change.AssignMember(C, G1))
        a.must(leader(G1, C))
        a.must(leader(ROOT, D))
        a.must(admit(E, "Echo"))
        a.must(Change.AssignMember(E, G2))

        // The leader of G1 takes members into its own group, not into another one, and renames only its own.
        val c = a.copyFor(C)
        c.must(Change.AssignMember(D, G1))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.AssignMember(B, G2)))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.UnassignMember(E)))
        c.must(Change.UnassignMember(D))
        c.must(Change.RenameGroup(G1, "Tête"))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.RenameGroup(G2, "Queue")))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.DeleteGroup(G1)))

        // A plain member leaves its group by itself; it cannot take another member out.
        val e = c.copyFor(E)
        assertEquals(Rejection.FORBIDDEN, e.refused(Change.UnassignMember(C)))
        e.must(Change.UnassignMember(E))

        // The leader of the ensemble creates groups and nothing else.
        val d = c.copyFor(D)
        d.must(Change.CreateGroup(G3, "Groupe 3"))
        assertEquals(Rejection.FORBIDDEN, d.refused(Change.DeleteGroup(G3)))

        // The leader of a group steps down by itself; nobody but an administrator can do it for it.
        assertEquals(Rejection.FORBIDDEN, d.refused(Change.ClearPost(G1, Post.LEADER)))
        c.must(Change.ClearPost(G1, Post.LEADER))
        // Its right went with its post: the rights are the ones of the moment the operation applies.
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.AssignMember(D, G1)))
    }

    @Test
    fun `the share table is data, but who waits shares nothing whatever the table`() {
        val shares =
            ShareTable.DEFAULT
                .with(ShareKind.MESSAGE, Role.GROUP_LEADER, ShareReach.PARENT_UNIT)
                .with(ShareKind.SKETCH, Role.MEMBER, ShareReach.OWN_UNIT, ShareReach.ENSEMBLE)
        assertEquals(setOf(ShareReach.OWN_UNIT, ShareReach.ENSEMBLE), shares.reach(ShareKind.SKETCH, Role.MEMBER))
        assertEquals(setOf(ShareReach.ENSEMBLE), shares.reach(ShareKind.MESSAGE, Role.ADMIN))
        val state =
            deployed(HierarchyRules(shares = shares))
                .apply {
                    must(leader(G1, C))
                    must(admit(H))
                }.state
        // In this ensemble the leader of a group messages the whole ensemble, and every member sketches for all.
        assertEquals(listOf(G1, ROOT), state.shareUnits(C, ShareKind.MESSAGE))
        assertEquals(listOf(G1), state.shareUnits(D, ShareKind.MESSAGE))
        assertEquals(listOf(G1, ROOT), state.shareUnits(D, ShareKind.SKETCH))
        // The role « member » has a cell for the whole ensemble; someone who waits is a member, and shares nothing.
        assertTrue(Role.MEMBER in state.rolesOf(H))
        for (kind in ShareKind.entries) assertEquals(emptyList(), state.shareUnits(H, kind), "$kind")
        assertEquals(
            ShareTable.NONE,
            ShareKind.entries.fold(shares) { t, k ->
                Role.entries.fold(t) { u, r -> u.with(k, r) }
            },
        )
    }

    @Test
    fun `the visibility table decides who sees whom among those who have access`() {
        val visibility =
            VisibilityTable.NONE
                .with(Role.MEMBER, Audience.SAME_GROUP, Audience.ENSEMBLE_LEADER)
                .with(Role.GROUP_LEADER, Audience.GROUP_LEADERS)
                .with(Role.ENSEMBLE_LEADER, Audience.GROUP_LEADERS, Audience.ADMINS)
                .with(Role.ADMIN, Audience.EVERYONE)
        assertEquals(setOf(Audience.SAME_GROUP, Audience.ENSEMBLE_LEADER), visibility.sees(Role.MEMBER))
        val state =
            deployed(HierarchyRules(visibility = visibility))
                .apply {
                    must(admit(H))
                    must(admit(I))
                    must(leader(ROOT, H))
                    must(leader(G1, C))
                    must(leader(G2, E))
                }.state

        fun seenBy(viewer: Long) = state.visibleTo(viewer).map { it.id }
        // B, administrator without a group: everyone who has access; I, who waits, is not shown even to it.
        assertEquals(listOf(A, C, D, E, F, H), seenBy(B))
        // H, leader of the ensemble: the group leaders and the administrators.
        assertEquals(listOf(B, C, E), seenBy(H))
        // C, leader of G1: its group, the leader of the ensemble, the other group leaders.
        assertEquals(listOf(D, E, H), seenBy(C))
        // D, member of G1: its group and the leader of the ensemble.
        assertEquals(listOf(C, H), seenBy(D))
        assertEquals(listOf(E, H), seenBy(F))
        // A, the creator, in no group: as a member, the leader of the ensemble.
        assertEquals(listOf(H), seenBy(A))
        assertEquals(emptyList(), seenBy(I))
        assertTrue(state.canSee(D, D))
    }
}
