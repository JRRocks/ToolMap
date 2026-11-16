package fr.toolmap.operation

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.HiddenName
import fr.toolmap.core.hierarchy.HierarchyReplica
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.OpAuth
import fr.toolmap.core.hierarchy.PhoneKeys
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.memberId
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.data.hierarchy.Ending
import fr.toolmap.data.hierarchy.GroupView
import fr.toolmap.data.hierarchy.MemberView
import fr.toolmap.data.hierarchy.NameAnswerView
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What each role sees and may do on the screens of the « Opération » mode (P39.8): the facts of the maquettes
 * (« Exercice Vallon », `tour3/REMONTEE.md`) written by the engine itself, then read by [OperationFacts] and [Notices]
 * from the place of each member. Pure JVM: no screen is drawn here.
 */
class OperationModelsTest {
    /** The operation of the maquettes, with the keys of its 22 phones; [view] reads it from the place of one of them. */
    private class Vallon {
        val keys: Map<Int, PhoneKeys> = (1..22).associateWith { OpAuth.memoryKeys() }
        val id: Map<Int, Long> = keys.mapValues { it.value.memberId }
        private val names = HashMap<HiddenName, String>()
        val groups = LinkedHashMap<String, Long>()

        fun hide(name: String): HiddenName = Names.hide(name).also { names[it.hidden] = name }.hidden

        val replica: HierarchyReplica =
            HierarchyReplica.create(keys.getValue(1), hide("Membre 1"), hide("Exercice Vallon"), NONCE)

        fun must(change: Change) {
            val result = replica.issue(change)
            assertTrue(result is IssueResult.Issued, "$change: $result")
        }

        init {
            for (n in 2..22) must(Change.AdmitMember(id.getValue(n), hide("Membre $n"), keys.getValue(n).public))
            listOf(
                "Nord" to listOf(4, 5, 2, 7, 8),
                "Sud" to listOf(9, 10, 3, 11, 12, 13),
                "Est" to listOf(14, 1, 15, 16, 17),
                "Ouest" to listOf(6, 18, 19, 20),
            ).forEachIndexed { i, (name, members) ->
                val group = GROUP + i
                must(Change.CreateGroup(group, hide(name)))
                groups[name] = group
                for (n in members) must(Change.AssignMember(id.getValue(n), group))
            }
            must(Change.SetPost(HierarchyState.ROOT, Post.LEADER, id.getValue(2)))
            must(Change.SetPost(HierarchyState.ROOT, Post.DEPUTY, id.getValue(3)))
            must(Change.SetPost(groups.getValue("Nord"), Post.LEADER, id.getValue(4)))
            must(Change.SetPost(groups.getValue("Nord"), Post.DEPUTY, id.getValue(5)))
            must(Change.SetPost(groups.getValue("Sud"), Post.LEADER, id.getValue(9)))
            must(Change.SetPost(groups.getValue("Sud"), Post.DEPUTY, id.getValue(10)))
            must(Change.SetPost(groups.getValue("Est"), Post.LEADER, id.getValue(14)))
            must(Change.AddAdmin(id.getValue(5)))
            must(Change.AddAdmin(id.getValue(9)))
        }

        /** The number of [member]; 0 for a member added by a test beyond the 22 of the maquettes. */
        fun number(member: Long): Int = id.entries.firstOrNull { it.value == member }?.key ?: 0

        /** What the phone of Membre [self] shows; [state] another moment of the operation. */
        fun view(
            self: Int,
            state: HierarchyState = replica.state,
            offline: Set<Int> = setOf(8, 12, 20),
        ): OperationView {
            val me = id.getValue(self)
            val phase =
                when {
                    me !in state.members -> OperationPhase.ENDED
                    state.isWaiting(me) -> OperationPhase.WAITING
                    else -> OperationPhase.PLACED
                }
            return OperationView(
                phase = phase,
                ensembleId = replica.ensembleId,
                self = me,
                operationName = "Exercice Vallon",
                hierarchy = state,
                members =
                    state.members.values.map { m ->
                        MemberView(
                            m.id,
                            names[m.name],
                            state.rolesOf(m.id),
                            m.group,
                            state.isWaiting(m.id),
                            number(m.id) !in offline,
                            state.nameLabel(me, m.id),
                        )
                    },
                groups =
                    state.groups.values.map { g ->
                        GroupView(
                            g.id,
                            names[g.name],
                            g.parent,
                            g.leader,
                            g.deputy,
                            state.membersOf(g.id).map {
                                it.id
                            },
                        )
                    },
                mayInvite = me == state.creator || me in state.admins,
                nameAnswers =
                    state.nameAnswers.mapValues { (m, a) ->
                        NameAnswerView(m, a.by, names[a.name], a.accepted)
                    },
                server = ServerState.ONLINE,
            )
        }

        /** The operation as another phone writes [change]: [author] signs it (an administrator, a member leaving). */
        fun by(
            author: Int,
            change: Change,
        ): HierarchyState {
            val other =
                HierarchyReplica.restore(
                    replica.ensembleId,
                    id.getValue(author),
                    replica.ops(),
                    keys.getValue(author),
                )
            val result = other.issue(change)
            assertTrue(result is IssueResult.Issued, "$change by $author: $result")
            for (op in other.ops()) replica.add(op)
            return replica.state
        }
    }

    private fun facts(
        world: Vallon,
        self: Int,
        lastSeen: Map<Long, Long> = emptyMap(),
    ) = OperationFacts(world.view(self), NOW, lastSeen)

    @Test
    fun `the creator has the tiles, the reminder, and every gesture on a member`() {
        val w = Vallon()
        val f = facts(w, 1)
        assertTrue(f.administers && f.showsAdminTiles && f.isCreatorHere)
        val g = f.gestures(w.id.getValue(11))
        assertTrue(g.changeGroup && g.unassign)
        assertEquals(PostOffer.CHOOSE, g.post)
        assertEquals(Gesture.ALLOWED, g.nameAdmin)
        assertEquals(Gesture.ALLOWED, g.remove)
        assertEquals(Gesture.HIDDEN, f.gestures(w.id.getValue(1)).remove, "nobody removes itself from the sheet")
        assertEquals(listOf(5, 9), f.successors.map(w::number), "a successor is an administrator (Q13)")
    }

    @Test
    fun `an administrator places and names, the gestures of the creator are locked for it`() {
        val w = Vallon()
        val f = facts(w, 5)
        assertTrue(f.administers && f.showsAdminTiles)
        assertFalse(f.isCreatorHere, "the reminder is the creator's only")
        val g = f.gestures(w.id.getValue(11))
        assertTrue(g.changeGroup && g.unassign)
        assertEquals(Gesture.LOCKED, g.nameAdmin, "« Réservé au créateur »")
        assertEquals(Gesture.LOCKED, g.remove, "« Réservé au créateur »")
        assertEquals(PostOffer.DEPUTY, f.gestures(w.id.getValue(10)).post, "a deputy is offered to become chief")
        assertEquals(PostOffer.NONE, f.gestures(w.id.getValue(9)).post, "a chief has nothing more to be named to")
        assertEquals(Gesture.HIDDEN, f.gestures(w.id.getValue(9)).nameAdmin, "already an administrator")
    }

    @Test
    fun `a member, a chief and the chief of the operation see no administration`() {
        val w = Vallon()
        for (n in listOf(7, 4, 2, 3)) {
            val f = facts(w, n)
            assertFalse(f.administers, "Membre $n")
            assertFalse(f.showsAdminTiles, "Membre $n: neither « Administrer » nor « Inviter » (Q5)")
            assertFalse(f.gestures(w.id.getValue(11)).changeGroup, "Membre $n does not place (R3)")
        }
    }

    @Test
    fun `who waits sees the wait, and is seen grey and dashed`() {
        val w = Vallon()
        val f = facts(w, 21)
        assertTrue(f.waiting)
        assertFalse(f.showsAdminTiles)
        assertTrue(f.token(w.id.getValue(21)).waiting)
        assertNull(f.token(w.id.getValue(21)).palette)
        assertEquals(listOf(21, 22), facts(w, 5).waitingMembers.map(w::number))
    }

    @Test
    fun `lists, counts, colours and marks are those of the maquettes`() {
        val w = Vallon()
        val f = facts(w, 5)
        assertEquals(listOf("Nord", "Sud", "Est", "Ouest"), f.groupOrder.map { f.groupName(it) })
        assertEquals(listOf(0, 1, 2, 3), f.groupOrder.map { f.palette.getValue(it) }, "blue, orange, green, violet")
        assertEquals(
            listOf(4, 5, 2, 7, 8),
            f.membersOf(w.groups.getValue("Nord")).map(w::number),
            "chief, deputy, then by arrival",
        )
        assertEquals(listOf(9, 10, 3, 11, 12, 13), f.membersOf(w.groups.getValue("Sud")).map(w::number))
        assertEquals(listOf(1, 5, 9), f.adminList.map(w::number), "the creator first")
        assertEquals(20, f.placedCount)
        assertEquals(22, f.total)
        assertEquals(30, f.memberLimit)
        assertFalse(f.full)
        assertEquals(4, f.onlineIn(w.groups.getValue("Nord")), "« 4 sur 5 en ligne »: Membre 8 is off")
        assertEquals(TokenLook("2", 0, false, crown = Mark.FULL), f.token(w.id.getValue(2)))
        assertEquals(TokenLook("3", 1, false, crown = Mark.HOLLOW), f.token(w.id.getValue(3)))
        assertEquals(TokenLook("4", 0, false, star = Mark.FULL), f.token(w.id.getValue(4)))
        assertEquals(TokenLook("5", 0, false, star = Mark.HOLLOW), f.token(w.id.getValue(5)))
        assertEquals(RoleWord.OP_CHIEF, f.postWord(w.id.getValue(2)))
        assertEquals(listOf(RoleWord.GROUP_DEPUTY, RoleWord.ADMIN), f.roleWords(w.id.getValue(5)))
        assertEquals(listOf(RoleWord.GROUP_CHIEF, RoleWord.ADMIN), f.roleWords(w.id.getValue(9)))
        assertEquals(listOf(RoleWord.CREATOR), f.roleWords(w.id.getValue(1)))
        assertNull(f.leaderOf(w.groups.getValue("Ouest")), "Ouest is « sans chef »")
    }

    @Test
    fun `the text of a token`() {
        assertEquals("21", OperationFacts.badgeText("Membre 21"))
        assertEquals("AM", OperationFacts.badgeText("Alice Martin"))
        assertEquals("BR", OperationFacts.badgeText("Bruno"))
        assertEquals("…", OperationFacts.badgeText(null))
        assertEquals("A2", OperationFacts.badgeText("Alpha 2024x"), "a number only when the last word is one")
        assertEquals("P1", OperationFacts.badgeText("Poste 1234"), "a number of four digits or more is not a call sign")
    }

    @Test
    fun `online, out of sync for a short silence, offline after it`() {
        val w = Vallon()
        val seen = mapOf(w.id.getValue(8) to NOW - 4 * MINUTE, w.id.getValue(12) to NOW - 14 * MINUTE)
        val f = facts(w, 7, seen)
        assertEquals(PeerState.ONLINE, f.peerState(w.id.getValue(4)))
        assertEquals(PeerState.LOST_SYNC, f.peerState(w.id.getValue(8)))
        assertEquals(PeerState.OFFLINE, f.peerState(w.id.getValue(12)))
        assertEquals(PeerState.OFFLINE, f.peerState(w.id.getValue(20)), "never seen here")
        assertEquals(NOW - 4 * MINUTE, f.silentSince(w.id.getValue(8)))
        assertNull(f.silentSince(w.id.getValue(4)))
    }

    @Test
    fun `the full operation greys « Inviter »`() {
        val w = Vallon()
        val extra = (23..30).associateWith { OpAuth.memoryKeys() }
        for ((n, k) in extra) w.must(Change.AdmitMember(k.memberId, Names.hide("Membre $n").hidden, k.public))
        val f = facts(w, 1)
        assertEquals(30, f.total)
        assertTrue(f.full)
    }

    @Test
    fun `the member taken out, the operation dissolved, said once and not to whoever did it`() {
        val w = Vallon()
        val before = w.view(13)
        val removed =
            OperationView(OperationPhase.ENDED, ensembleId = before.ensembleId, self = before.self, ending = Ending.OUT)
        assertEquals(listOf<Notice>(Notice.Removed("Exercice Vallon")), Notices.between(before, removed))
        assertEquals(emptyList(), Notices.between(before, removed, byItself = true), "it left by itself")
        val dissolved = removed.copy(ending = Ending.DISSOLVED)
        assertEquals(listOf<Notice>(Notice.Dissolved("Exercice Vallon")), Notices.between(before, dissolved))
    }

    @Test
    fun `back to the wait, the group deleted, or taken out of it`() {
        val w = Vallon()
        val before18 = w.view(18)
        val before11 = w.view(11)
        val deleted = w.by(5, Change.DeleteGroup(w.groups.getValue("Ouest")))
        assertEquals(listOf<Notice>(Notice.GroupDeleted("Ouest")), Notices.between(before18, w.view(18, deleted)))
        val unassigned = w.by(5, Change.UnassignMember(w.id.getValue(11)))
        assertEquals(listOf<Notice>(Notice.Unassigned("Sud")), Notices.between(before11, w.view(11, unassigned)))
    }

    @Test
    fun `a chief leaves, its deputy is told why, its group gets a message, the others nothing`() {
        val w = Vallon()
        val before5 = w.view(5)
        val before7 = w.view(7)
        val before15 = w.view(15)
        val after = w.by(4, Change.RemoveMember(w.id.getValue(4)))
        assertEquals(listOf<Notice>(Notice.GroupChief("Nord", "Membre 4")), Notices.between(before5, w.view(5, after)))
        assertEquals(
            listOf<Notice>(Notice.NewGroupChief("Membre 4", "Membre 5", "Nord")),
            Notices.between(before7, w.view(7, after)),
        )
        assertEquals(emptyList(), Notices.between(before15, w.view(15, after)), "another group is not told")
    }

    @Test
    fun `the chief of the operation leaves, its deputy becomes chief, everyone placed is told`() {
        val w = Vallon()
        val before3 = w.view(3)
        val before15 = w.view(15)
        val before21 = w.view(21)
        val after = w.by(2, Change.RemoveMember(w.id.getValue(2)))
        assertEquals(listOf<Notice>(Notice.OperationChief("Membre 2")), Notices.between(before3, w.view(3, after)))
        assertEquals(
            listOf<Notice>(Notice.NewOperationChief("Membre 2", "Membre 3")),
            Notices.between(before15, w.view(15, after)),
        )
        assertEquals(emptyList(), Notices.between(before21, w.view(21, after)), "who waits is told nothing")
    }

    @Test
    fun `the creator hands over, left or stayed`() {
        val w = Vallon()
        val before5 = w.view(5)
        val before7 = w.view(7)
        val transferred = w.by(1, Change.TransferCreator(w.id.getValue(5)))
        assertEquals(
            listOf<Notice>(Notice.Creator("Membre 1", left = false)),
            Notices.between(before5, w.view(5, transferred)),
        )
        assertEquals(
            emptyList(),
            Notices.between(before7, w.view(7, transferred)),
            "nobody left: the others are not told",
        )
        val gone = w.by(1, Change.RemoveMember(w.id.getValue(1)))
        assertEquals(listOf<Notice>(Notice.Creator("Membre 1", left = true)), Notices.between(before5, w.view(5, gone)))
        assertEquals(
            listOf<Notice>(Notice.NewCreator("Membre 1", "Membre 5")),
            Notices.between(before7, w.view(7, gone)),
        )
    }

    @Test
    fun `a new name is told to the renamed and to who asked, the others are told nothing`() {
        val w = Vallon()
        // An administrator renames Membre 11 (N2 A): it is told, the others are not (N9 B).
        val before11 = w.view(11)
        val before7 = w.view(7)
        val renamed = w.by(5, Change.RenameMember(w.id.getValue(11), w.hide("Éclaireur 11")))
        assertEquals(
            listOf<Notice>(Notice.RenamedByAdmin("Éclaireur 11")),
            Notices.between(before11, w.view(11, renamed)),
        )
        assertEquals(emptyList(), Notices.between(before7, w.view(7, renamed)), "the others are told nothing")
        // Membre 7 asks for a name (N11 B): refused, without saying by whom (N8 A), then accepted.
        val guide = w.hide("Guide 7")
        val asked = w.by(7, Change.RequestName(w.id.getValue(7), guide))
        val refused = w.by(5, Change.AnswerName(w.id.getValue(7), guide, accept = false))
        assertEquals(
            listOf<Notice>(Notice.NameRefused("Guide 7", "Membre 7")),
            Notices.between(w.view(7, asked), w.view(7, refused)),
        )
        val again = w.by(7, Change.RequestName(w.id.getValue(7), guide))
        val accepted = w.by(9, Change.AnswerName(w.id.getValue(7), guide, accept = true))
        assertEquals(
            listOf<Notice>(Notice.NameAccepted("Guide 7")),
            Notices.between(w.view(7, again), w.view(7, accepted)),
            "accepted, and not also « renamed by an administrator »",
        )
        assertEquals(emptyList(), Notices.between(w.view(4, again), w.view(4, accepted)), "its chief is told nothing")
    }

    @Test
    fun `back closes the window, then the sheet, then the page, then the screen`() {
        val nav =
            OperationNav().open(
                OpPage.Admin,
            ).open(OpPage.Admins).copy(sheet = OpSheet.NameAdmin, dialog = OpDialog.Quit)
        val one = requireNotNull(nav.back())
        assertNull(one.dialog)
        val two = requireNotNull(one.back())
        assertNull(two.sheet)
        assertEquals(OpPage.Admin, requireNotNull(two.back()).page)
        assertNull(OperationNav().back(), "the screen itself closes")
    }

    @Test
    fun `the captures change the demonstration last`() {
        val names = OperationScenes.names
        assertEquals(names.size, names.toSet().size, "one name per capture")
        val first = names.indexOfFirst { it in OperationScenes.changing }
        assertTrue(first > 0 && names.drop(first).all { it in OperationScenes.changing })
    }

    private companion object {
        const val NONCE = 7L
        const val GROUP = 0x100L
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
    }
}
