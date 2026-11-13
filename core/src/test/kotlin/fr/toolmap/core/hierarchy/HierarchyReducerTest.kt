package fr.toolmap.core.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyReducerTest {
    @Test
    fun `the creator of an ensemble is its first administrator and its leader`() {
        val a = newEnsemble()
        val state = a.state
        assertTrue(state.created)
        assertEquals(OpAuth.ensembleId(A, Change.CreateEnsemble(NONCE, "Sortie du jour", "Alpha")), state.ensembleId)
        assertEquals("Sortie du jour", state.name)
        assertEquals(setOf(A), state.admins)
        assertEquals(A, state.leader)
        assertEquals(Member(A, "Alpha"), state.members[A])
        assertEquals(setOf(Role.ADMIN, Role.ENSEMBLE_LEADER, Role.MEMBER), state.rolesOf(A))
        assertEquals(emptySet(), state.rolesOf(OUTSIDER))
        assertNull(state.inconsistency())
        assertEquals(Rejection.ALREADY_CREATED, a.refused(Change.CreateEnsemble(NONCE, "Autre", "Alpha")))
    }

    @Test
    fun `members are admitted, put in groups, given posts and removed`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        a.must(Change.AssignMember(D, G1))
        a.must(Change.SetGroupLeader(G1, C))
        a.must(Change.SetEnsembleLeader(B))
        var state = a.state
        assertEquals(listOf(C, D), state.membersOf(G1).map { it.id })
        assertEquals(listOf(A, B), state.membersOf(null).map { it.id })
        assertEquals(C, state.groups.getValue(G1).leader)
        assertEquals(G1, state.groupOf(D)?.id)
        assertEquals(setOf(Role.GROUP_LEADER, Role.MEMBER), state.rolesOf(C))
        assertEquals(setOf(Role.ADMIN, Role.ENSEMBLE_LEADER, Role.MEMBER), state.rolesOf(B))
        assertEquals(setOf(Role.ADMIN, Role.MEMBER), state.rolesOf(A))

        a.must(Change.RenameGroup(G1, "Reconnaissance"))
        assertEquals("Reconnaissance", a.state.groups.getValue(G1).name)

        // A leader who changes group stops leading the one it leaves.
        a.must(Change.AssignMember(C, G2))
        state = a.state
        assertNull(state.groups.getValue(G1).leader)
        assertEquals(setOf(Role.MEMBER), state.rolesOf(C))

        a.must(Change.SetGroupLeader(G2, C))
        a.must(Change.ClearGroupLeader(G2))
        assertNull(a.state.groups.getValue(G2).leader)
        a.must(Change.SetGroupLeader(G2, C))
        a.must(Change.UnassignMember(C))
        assertNull(a.state.groups.getValue(G2).leader)
        assertNull(a.state.groupOf(C))

        // Removing a member takes it out of everything it held.
        a.must(Change.AssignMember(B, G2))
        a.must(Change.SetGroupLeader(G2, B))
        a.must(Change.RemoveMember(B))
        state = a.state
        assertFalse(B in state.members)
        assertEquals(setOf(A), state.admins)
        assertNull(state.leader)
        assertNull(state.groups.getValue(G2).leader)
        assertNull(state.inconsistency())

        // Someone removed may be admitted again, as a plain member.
        a.must(Change.AdmitMember(B, "Bravo"))
        assertEquals(setOf(Role.MEMBER), a.state.rolesOf(B))
    }

    @Test
    fun `deleting a group leaves its members in the ensemble without a group`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        a.must(Change.AssignMember(D, G1))
        a.must(Change.SetGroupLeader(G1, D))
        a.must(Change.DeleteGroup(G1))
        val state = a.state
        assertFalse(G1 in state.groups)
        assertNull(state.members.getValue(C).group)
        assertNull(state.members.getValue(D).group)
        assertEquals(setOf(Role.MEMBER), state.rolesOf(D))
        assertNull(state.inconsistency())
        // The identifier of a deleted group never comes back, and nothing can be done with it.
        assertEquals(Rejection.GROUP_DELETED, a.refused(Change.CreateGroup(G1, "Revenant")))
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.AssignMember(C, G1)))
        assertEquals(Rejection.NO_SUCH_GROUP, a.refused(Change.RenameGroup(G1, "Revenant")))
    }

    @Test
    fun `the ensemble always keeps one administrator`() {
        val a = staffed()
        a.must(Change.RemoveAdmin(B))
        assertEquals(Rejection.LAST_ADMIN, a.refused(Change.RemoveAdmin(A)))
        assertEquals(Rejection.LAST_ADMIN, a.refused(Change.RemoveMember(A)))
        assertEquals(Rejection.NOT_AN_ADMIN, a.refused(Change.RemoveAdmin(B)))
        a.must(Change.AddAdmin(C))
        // An administrator may step down, and leave, once another one is there.
        a.must(Change.RemoveAdmin(A))
        assertEquals(setOf(C), a.state.admins)
        a.must(Change.RemoveMember(A))
        assertEquals(setOf(C), a.state.admins)
        assertEquals(Rejection.NOT_A_MEMBER, a.refused(Change.AdmitMember(A, "Alpha")))
    }

    @Test
    fun `what cannot be is refused with its reason and changes nothing`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        val before = a.state
        val refusals =
            listOf(
                Change.AdmitMember(C, "Charlie") to Rejection.ALREADY_MEMBER,
                Change.AdmitMember(0, "Zéro") to Rejection.MALFORMED,
                Change.AdmitMember(0x50, " ") to Rejection.MALFORMED,
                Change.AdmitMember(0x50, "x".repeat(Names.MAX_BYTES + 1)) to Rejection.MALFORMED,
                Change.AdmitMember(0x50, "deux\nlignes") to Rejection.MALFORMED,
                Change.RemoveMember(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.AddAdmin(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.AddAdmin(B) to Rejection.NO_CHANGE,
                Change.RemoveAdmin(C) to Rejection.NOT_AN_ADMIN,
                Change.CreateGroup(G1, "Encore") to Rejection.GROUP_EXISTS,
                Change.CreateGroup(0, "Zéro") to Rejection.MALFORMED,
                Change.CreateGroup(0x103, "Sous-groupe", parent = G1) to Rejection.UNSUPPORTED_DEPTH,
                Change.RenameGroup(0x103, "Rien") to Rejection.NO_SUCH_GROUP,
                Change.RenameGroup(G1, "Groupe 1") to Rejection.NO_CHANGE,
                Change.DeleteGroup(0x103) to Rejection.NO_SUCH_GROUP,
                Change.AssignMember(OUTSIDER, G1) to Rejection.NO_SUCH_MEMBER,
                Change.AssignMember(C, 0x103) to Rejection.NO_SUCH_GROUP,
                Change.AssignMember(C, G1) to Rejection.NO_CHANGE,
                Change.UnassignMember(D) to Rejection.NO_CHANGE,
                Change.SetGroupLeader(G1, D) to Rejection.NOT_IN_GROUP,
                Change.SetGroupLeader(G2, C) to Rejection.NOT_IN_GROUP,
                Change.SetGroupLeader(G1, OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.ClearGroupLeader(G1) to Rejection.NO_CHANGE,
                Change.SetEnsembleLeader(OUTSIDER) to Rejection.NO_SUCH_MEMBER,
                Change.SetEnsembleLeader(A) to Rejection.NO_CHANGE,
            )
        for ((change, reason) in refusals) {
            assertEquals(reason, a.refused(change), "$change")
            assertEquals(reason, HierarchyReducer.check(a.state, A, change), "$change")
        }
        assertEquals(before, a.state)
        assertNull(HierarchyReducer.check(a.state, A, Change.AssignMember(D, G2)))
    }

    @Test
    fun `the limits on members and groups hold`() {
        val a = newEnsemble()
        for (i in 1 until HierarchyState.MAX_MEMBERS) a.must(Change.AdmitMember(1000L + i, "Membre $i"))
        assertEquals(Rejection.TOO_MANY, a.refused(Change.AdmitMember(5000, "De trop")))
        for (i in 1..HierarchyState.MAX_GROUPS) a.must(Change.CreateGroup(2000L + i, "Groupe $i"))
        assertEquals(Rejection.TOO_MANY, a.refused(Change.CreateGroup(6000, "De trop")))
        assertNull(a.state.inconsistency())
    }

    @Test
    fun `with the starting table only administrators change roles and assignments`() {
        val a = staffed()
        a.must(Change.AssignMember(C, G1))
        a.must(Change.AssignMember(D, G1))
        a.must(Change.SetGroupLeader(G1, C))
        a.must(Change.SetEnsembleLeader(D))
        val changes =
            listOf(
                Change.AdmitMember(0x50, "Echo"),
                Change.RemoveMember(B),
                Change.AddAdmin(C),
                Change.RemoveAdmin(B),
                Change.CreateGroup(0x103, "Groupe 3"),
                Change.RenameGroup(G1, "Mon groupe"),
                Change.DeleteGroup(G1),
                Change.AssignMember(A, G1),
                Change.UnassignMember(D),
                Change.SetGroupLeader(G1, D),
                Change.ClearGroupLeader(G1),
                Change.SetEnsembleLeader(C),
            )
        assertEquals(Operation.entries.toSet(), changes.map { it.operation }.toSet(), "one change per operation")
        // C leads a group, D leads the ensemble: a title, no right. B, administrator, may do all of it.
        for (author in listOf(C, D)) {
            val phone = a.copyFor(author)
            for (change in changes) assertEquals(Rejection.FORBIDDEN, phone.refused(change), "$author $change")
            assertEquals(a.state, phone.state)
        }
        for (change in changes) assertNull(HierarchyReducer.check(a.state, B, change), "$change")
        // Someone who is not in the ensemble may do nothing.
        assertEquals(Rejection.NOT_A_MEMBER, a.copyFor(OUTSIDER).refused(Change.CreateGroup(0x103, "Groupe 3")))
        // A member may always leave by itself.
        val c = a.copyFor(C)
        c.must(Change.RemoveMember(C))
        assertFalse(C in c.state.members)
        assertNull(c.state.groups.getValue(G1).leader)
    }

    @Test
    fun `the table is data, another table gives other rights without another engine`() {
        val rights =
            RightsTable.DEFAULT
                .with(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.UNASSIGN_MEMBER, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.RENAME_GROUP, Role.GROUP_LEADER, Reach.OWN_GROUP)
                .with(Operation.CLEAR_GROUP_LEADER, Role.GROUP_LEADER, Reach.SELF)
                .with(Operation.UNASSIGN_MEMBER, Role.MEMBER, Reach.SELF)
                .with(Operation.CREATE_GROUP, Role.ENSEMBLE_LEADER, Reach.ANYONE)
        assertEquals(setOf(Reach.OWN_GROUP), rights.reach(Operation.ASSIGN_MEMBER, Role.GROUP_LEADER))
        assertEquals(setOf(Reach.ANYONE), rights.reach(Operation.ASSIGN_MEMBER, Role.ADMIN))
        assertEquals(emptySet(), rights.reach(Operation.ASSIGN_MEMBER, Role.MEMBER))

        val a = staffed(HierarchyRules(rights = rights))
        a.must(Change.AssignMember(C, G1))
        a.must(Change.SetGroupLeader(G1, C))
        a.must(Change.SetEnsembleLeader(D))
        a.must(Change.AdmitMember(0x50, "Echo"))
        a.must(Change.AssignMember(0x50, G2))

        // The leader of G1 takes members into its own group, not into another one, and renames only its own.
        val c = a.copyFor(C)
        c.must(Change.AssignMember(D, G1))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.AssignMember(B, G2)))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.UnassignMember(0x50)))
        c.must(Change.UnassignMember(D))
        c.must(Change.RenameGroup(G1, "Tête"))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.RenameGroup(G2, "Queue")))
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.DeleteGroup(G1)))

        // A plain member leaves its group by itself; it cannot take another member out.
        val e = c.copyFor(0x50)
        assertEquals(Rejection.FORBIDDEN, e.refused(Change.UnassignMember(C)))
        e.must(Change.UnassignMember(0x50))

        // The leader of the ensemble creates groups and nothing else.
        val d = c.copyFor(D)
        d.must(Change.CreateGroup(0x103, "Groupe 3"))
        assertEquals(Rejection.FORBIDDEN, d.refused(Change.DeleteGroup(0x103)))

        // The leader of a group steps down by itself; nobody but an administrator can do it for it.
        assertEquals(Rejection.FORBIDDEN, d.refused(Change.ClearGroupLeader(G1)))
        c.must(Change.ClearGroupLeader(G1))
        // Its right went with its post: the rights are the ones of the moment the operation applies.
        assertEquals(Rejection.FORBIDDEN, c.refused(Change.AssignMember(D, G1)))
    }

    @Test
    fun `at the start everyone sees everyone, and nobody outside the ensemble`() {
        val state = staffed().apply { must(Change.AssignMember(C, G1)) }.state
        val everyone = listOf(A, B, C, D)
        for (viewer in everyone) for (target in everyone) assertTrue(state.canSee(viewer, target), "$viewer $target")
        assertEquals(listOf(B, C, D), state.visibleTo(A).map { it.id })
        assertFalse(state.canSee(OUTSIDER, A))
        assertFalse(state.canSee(A, OUTSIDER))
        assertFalse(state.canSee(OUTSIDER, OUTSIDER))
    }

    @Test
    fun `the visibility table decides who sees whom`() {
        val visibility =
            VisibilityTable.NONE
                .with(Role.MEMBER, Audience.SAME_GROUP, Audience.ENSEMBLE_LEADER)
                .with(Role.GROUP_LEADER, Audience.GROUP_LEADERS)
                .with(Role.ENSEMBLE_LEADER, Audience.GROUP_LEADERS, Audience.ADMINS)
                .with(Role.ADMIN, Audience.EVERYONE)
        assertEquals(setOf(Audience.SAME_GROUP, Audience.ENSEMBLE_LEADER), visibility.sees(Role.MEMBER))
        val e = 0x50L
        val f = 0x60L
        val state =
            staffed(HierarchyRules(visibility = visibility))
                .apply {
                    must(Change.AdmitMember(e, "Echo"))
                    must(Change.AdmitMember(f, "Foxtrot"))
                    must(Change.RemoveAdmin(B))
                    must(Change.SetEnsembleLeader(B))
                    must(Change.AssignMember(C, G1))
                    must(Change.AssignMember(D, G1))
                    must(Change.SetGroupLeader(G1, C))
                    must(Change.AssignMember(e, G2))
                    must(Change.AssignMember(f, G2))
                    must(Change.SetGroupLeader(G2, e))
                }.state

        fun seenBy(viewer: Long) = state.visibleTo(viewer).map { it.id }
        // A, administrator without a group: everyone.
        assertEquals(listOf(B, C, D, e, f), seenBy(A))
        // B, leader of the ensemble: the group leaders and the administrators.
        assertEquals(listOf(A, C, e), seenBy(B))
        // C, leader of G1: its group, the leader of the ensemble, the other group leaders.
        assertEquals(listOf(B, D, e), seenBy(C))
        // D, member of G1: its group and the leader of the ensemble.
        assertEquals(listOf(B, C), seenBy(D))
        // F, member of G2.
        assertEquals(listOf(B, e), seenBy(f))
        // Everyone always sees oneself; two members without a group are not « in the same group ».
        assertTrue(state.canSee(D, D))
        val alone =
            staffed(
                HierarchyRules(visibility = VisibilityTable.NONE.with(Role.MEMBER, Audience.SAME_GROUP)),
            ).state
        assertFalse(alone.canSee(C, D))
        assertTrue(alone.canSee(C, C))
    }
}
