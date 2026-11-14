package fr.toolmap.core.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The choices of the second round (`docs/specs/group-hierarchy.md` § 10 quater, Q1 to Q20, and the answers S1 to S7
 * of 2026-11-14), one test each; the name of a test starts with the answer it checks. Whatever closes something is
 * checked with the keys: every phone receives the frame, and only the readers open it.
 */
class HierarchyChoicesTest {
    private val everyone = setOf(A, B, C, D, E, F, H)

    /** [commanded], and F leads the ensemble with H, who then no longer waits, as its deputy. */
    private fun led(): World =
        commanded().apply {
            this[B].replica.must(leader(ROOT, F))
            this[B].replica.must(deputy(ROOT, H))
            spread()
            handOutMissing()
        }

    /** A state with every role: [deployed], I a plain member of G1, J an administrator in G2, H deputy of F. */
    private fun staffedState(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyState =
        deployed(rules)
            .apply {
                must(admit(H))
                must(admit(I))
                must(admit(J))
                must(Change.AssignMember(I, G1))
                must(Change.AssignMember(J, G2))
                must(Change.AddAdmin(J))
                must(leader(G1, C))
                must(deputy(G1, D))
                must(leader(ROOT, F))
                must(deputy(ROOT, H))
            }.state

    @Test
    fun `Q3 S1 what a group leader sends up is read by those who command the ensemble, not by the other leaders`() {
        val w = led()
        val up = Channel.up(G1)
        assertEquals(listOf(A, B, C, D, F, H), w[A].state.readerIds(up))
        val sketch = w[C].frame(ShareKind.SKETCH, up, "croquis remonté par le chef du groupe 1")
        // The leader of the ensemble, its deputy, the administrator, the creator; and the two posts of the group.
        assertEquals(setOf(A, B, C, D, F, H), w.openers(sketch))
        // E leads the other group: no copy of the key was ever made for its phone.
        assertEquals(ShareRefusal.NO_KEY, w[E].refusal(sketch))
        for (batch in w[A].ring.batchesFor(w[A].state).filter { it.channel == up }) {
            assertEquals(listOf(A, B, C, D, F, H), batch.wraps.map { it.recipient })
        }
        // The deputy sends up like its leader, and reads what its leader sent up.
        assertEquals("croquis remonté par le chef du groupe 1", w[D].read(sketch))
        assertEquals("de l'adjoint", w[C].read(w[D].frame(ShareKind.WAYPOINT, up, "de l'adjoint")))
        // Neither of them sends to everyone any more, and the leader of another group does not write here.
        for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT, ShareKind.MESSAGE)) {
            assertEquals(ShareRefusal.NOT_ALLOWED, w[C].sealRefusal(kind, Channel.ENSEMBLE, "à tous"), "$kind")
            assertEquals(ShareRefusal.NOT_ALLOWED, w[D].sealRefusal(kind, Channel.ENSEMBLE, "à tous"), "$kind")
            assertEquals(ShareRefusal.NOT_ALLOWED, w[E].sealRefusal(kind, up, "chez le voisin"), "$kind")
        }
        // Each group has its own lane up.
        val other = w[E].frame(ShareKind.SKETCH, Channel.up(G2), "du groupe 2")
        assertEquals(setOf(A, B, E, F, H), w.openers(other))
    }

    @Test
    fun `S2 only the leader of the ensemble, its deputy, the administrators and the creator send to everyone`() {
        val w = led()
        for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT, ShareKind.MESSAGE)) {
            for (author in listOf(A, B, F, H)) {
                val frame = w[author].frame(kind, Channel.ENSEMBLE, "$kind pour tous")
                assertEquals(everyone, w.openers(frame), "$kind $author")
            }
            for (author in listOf(C, D, E)) {
                assertEquals(
                    ShareRefusal.NOT_ALLOWED,
                    w[author].sealRefusal(kind, Channel.ENSEMBLE, "x"),
                    "$kind $author",
                )
            }
        }
    }

    @Test
    fun `Q15 those who command the ensemble send to one precise group, read by that group and by them`() {
        val w = led()
        val down = Channel.down(G1)
        assertEquals(listOf(A, B, C, D, F, H), w[A].state.readerIds(down))
        for (author in listOf(A, B, F, H)) {
            for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT)) {
                val frame = w[author].frame(kind, down, "pour le groupe 1")
                assertEquals(setOf(A, B, C, D, F, H), w.openers(frame), "$kind $author")
                assertEquals(ShareRefusal.NO_KEY, w[E].refusal(frame))
            }
            // Messages keep to their three threads (S3): none goes down to one group.
            assertEquals(ShareRefusal.NOT_ALLOWED, w[author].sealRefusal(ShareKind.MESSAGE, down, "x"), "$author")
        }
        // The group reads that lane and does not write there: what it shares stays in its own channel.
        for (author in listOf(C, D)) {
            assertEquals(ShareRefusal.NOT_ALLOWED, w[author].sealRefusal(ShareKind.SKETCH, down, "x"), "$author")
        }
        // F leads the ensemble and is a member of G2: to its group as a member, down to G2 as the leader.
        val state = w[F].state
        assertEquals(
            listOf(Channel.ENSEMBLE, Channel.down(G1), Channel.unit(G2), Channel.down(G2)),
            state.shareChannels(F, ShareKind.SKETCH),
        )
    }

    @Test
    fun `Q16 what is shared in a group is read by that group only, administrators and leader of the ensemble too`() {
        val w = led()
        for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT, ShareKind.MESSAGE, ShareKind.ALERT)) {
            val frame = w[D].frame(kind, Channel.unit(G1), "entre nous, groupe 1")
            assertEquals(setOf(C, D), w.openers(frame), "$kind")
            // The creator, an administrator, the leader of the ensemble and its deputy: no key, and no way in.
            for (outside in listOf(A, B, F, H)) {
                assertEquals(ShareRefusal.NO_KEY, w[outside].refusal(frame), "$kind $outside")
                assertEquals(ShareRefusal.NOT_ALLOWED, w[outside].sealRefusal(kind, Channel.unit(G1), "x"))
                assertNull(w[outside].ring.issue(w[outside].state, Channel.unit(G1)))
            }
        }
        // The leader of the ensemble is in G2: it reads G2 as one of its members.
        assertEquals("groupe 2", w[F].read(w[E].frame(ShareKind.MESSAGE, Channel.unit(G2), "groupe 2")))
    }

    @Test
    fun `Q6 an alert goes where the role of its sender says`() {
        val state = staffedState()

        fun alerts(member: Long) = state.shareChannels(member, ShareKind.ALERT)
        // A member: its group.
        assertEquals(listOf(Channel.unit(G1)), alerts(I))
        assertEquals(listOf(Channel.unit(G2)), alerts(E))
        // The leader of a group and its deputy: every leader (and their own group, as its members).
        assertEquals(listOf(Channel.LEADERS, Channel.unit(G1)), alerts(C))
        assertEquals(listOf(Channel.LEADERS, Channel.unit(G1)), alerts(D))
        // The leader of the ensemble and its deputy: everyone.
        assertEquals(listOf(Channel.ENSEMBLE, Channel.unit(G2)), alerts(F))
        assertEquals(listOf(Channel.ENSEMBLE), alerts(H))
        // An administrator without a post of leader alerts as a member: its group when it has one.
        assertEquals(listOf(Channel.unit(G2)), alerts(J))
        assertEquals(emptyList(), alerts(B), "an administrator without a group has nobody to alert")
        assertEquals(emptyList(), alerts(A), "nor has the creator without a group")
        // Every leader: the leaders and deputies of the groups and of the ensemble, the administrators, the creator.
        assertEquals(listOf(A, B, C, D, F, H, J), state.readerIds(Channel.LEADERS))

        // By the keys.
        val w = commanded()
        val fromLeader = w[C].frame(ShareKind.ALERT, Channel.LEADERS, "alerte du chef du groupe 1")
        assertEquals(setOf(A, B, C, D, E), w.openers(fromLeader))
        assertEquals(ShareRefusal.NO_KEY, w[F].refusal(fromLeader))
        val fromMember = w[F].frame(ShareKind.ALERT, Channel.unit(G2), "alerte d'un membre")
        assertEquals(setOf(E, F), w.openers(fromMember))
        assertEquals(ShareRefusal.NOT_ALLOWED, w[F].sealRefusal(ShareKind.ALERT, Channel.ENSEMBLE, "à tous"))
        assertEquals(ShareRefusal.NOT_ALLOWED, w[C].sealRefusal(ShareKind.ALERT, Channel.ENSEMBLE, "à tous"))
        val led = led()
        assertEquals(everyone, led.openers(led[F].frame(ShareKind.ALERT, Channel.ENSEMBLE, "alerte du chef")))
        assertEquals(everyone, led.openers(led[H].frame(ShareKind.ALERT, Channel.ENSEMBLE, "alerte de l'adjoint")))
    }

    @Test
    fun `S3 three threads - my group, the one in the middle towards the top, the whole ensemble`() {
        val state = staffedState()

        fun writes(member: Long) = state.shareChannels(member, ShareKind.MESSAGE)
        // A member writes to its group; it reads its group, what comes down to it, and the whole ensemble.
        assertEquals(listOf(Channel.unit(G1)), writes(I))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.unit(G1), Channel.down(G1)), state.channelsReadBy(I))
        // The leader of a group and its deputy also write in the thread of the middle of their group.
        assertEquals(listOf(Channel.unit(G1), Channel.up(G1)), writes(C))
        assertEquals(listOf(Channel.unit(G1), Channel.up(G1)), writes(D))
        // Those who command the ensemble write to everyone, and answer each group in its thread of the middle.
        assertEquals(listOf(Channel.ENSEMBLE, Channel.up(G1)), writes(B))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.up(G1)), writes(H))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.up(G1), Channel.unit(G2)), writes(F))
        // Nobody else writes to everyone; everyone reads it.
        for (member in listOf(C, D, E, I)) assertFalse(state.mayShare(member, ShareKind.MESSAGE, Channel.ENSEMBLE))
        assertEquals(listOf(A, B, C, D, E, F, H, I, J), state.readerIds(Channel.ENSEMBLE))
        // The thread of the middle of a group is not read by its plain members, nor by the other groups.
        assertEquals(listOf(A, B, C, D, F, H, J), state.readerIds(Channel.up(G1)))

        // The other reading (one thread common to every leader) is two cells, not another engine.
        val common =
            listOf(Role.CREATOR, Role.ADMIN, Role.ENSEMBLE_LEADER)
                .fold(ShareTable.DEFAULT.with(ShareKind.MESSAGE, Role.GROUP_LEADER, ShareReach.LEADERS)) { t, role ->
                    t.with(ShareKind.MESSAGE, role, ShareReach.ENSEMBLE, ShareReach.LEADERS)
                }
        val other = staffedState(HierarchyRules(shares = common))
        assertEquals(listOf(Channel.LEADERS, Channel.unit(G1)), other.shareChannels(C, ShareKind.MESSAGE))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.LEADERS), other.shareChannels(B, ShareKind.MESSAGE))

        // By the keys: the thread of the middle of G1, written from both ends, closed to the rest.
        val w = commanded()
        val up = Channel.up(G1)
        val question = w[C].frame(ShareKind.MESSAGE, up, "besoin d'un point de situation")
        val answer = w[B].frame(ShareKind.MESSAGE, up, "reçu, restez en place")
        for (frame in listOf(question, answer)) assertEquals(setOf(A, B, C, D), w.openers(frame))
        assertEquals("reçu, restez en place", w[D].read(answer))
        val toAll = w[B].frame(ShareKind.MESSAGE, Channel.ENSEMBLE, "à toute l'opération")
        assertEquals(setOf(A, B, C, D, E, F), w.openers(toAll))
    }

    @Test
    fun `Q2 who waits learns no name - the operations carry none, the names travel sealed for who has access`() {
        val w = world()
        val state = w[H].state
        assertTrue(state.isWaiting(H))
        // Everything its phone holds of the ensemble, as bytes: no name is in there, not even its own.
        val held =
            HierarchyWire.encodeOpBlocks(w[H].replica.ops()).fold(HierarchyWire.encodeState(state)) { all, block ->
                all + block
            }
        val text = String(held, Charsets.ISO_8859_1)
        for (name in NAMES) assertEquals(-1, text.indexOf(String(name.toByteArray(), Charsets.ISO_8859_1)), name)
        assertEquals(NAMES.size, w[H].names.missing(state).size)
        // The names go to everyone who has access, sealed with the key of the whole ensemble.
        val roster = w[B].frame(ShareKind.ROSTER, Channel.ENSEMBLE, NameBook.encode(NAMES.map(::card)))
        assertEquals(setOf(A, B, C, D, E, F), w.openers(roster))
        assertEquals(ShareRefusal.NO_KEY, w[H].refusal(roster))
        assertEquals(NAMES.size, w[C].names.accept(w[C].share(roster)))
        assertEquals(0, w[C].names.accept(w[C].share(roster)), "already known")
        assertEquals("Delta", w[C].names.nameOf(state, D))
        assertEquals("Hotel", w[C].names.nameOf(state, H))
        assertEquals("Sortie du jour", w[C].names.nameOf(state.name))
        assertEquals("Groupe 2", w[C].names.nameOf(state.groups.getValue(G2).name))
        assertEquals(emptyList(), w[C].names.missing(state))
        // A card that opens no name of the ensemble teaches nothing about it.
        assertNull(w[C].names.nameOf(hidden("Inconnu")))

        // Once placed, it is handed the key of the ensemble, then the names.
        w[B].replica.must(Change.AssignMember(H, G2))
        w.spread()
        w.handOutMissing()
        assertEquals(ShareRefusal.NO_KEY, w[H].refusal(roster), "what was sealed before stays closed")
        val again = w[C].frame(ShareKind.ROSTER, Channel.ENSEMBLE, NameBook.encode(w[C].names.cardsFor(w[C].state)))
        assertEquals(NAMES.size, w[H].names.accept(w[H].share(again)))
        assertEquals("Bravo", w[H].names.nameOf(w[H].state, B))
        assertEquals(emptyList(), w[H].names.missing(w[H].state))
    }

    @Test
    fun `Q10 the position of who waits goes to the administrators only, and nothing comes to it`() {
        val w = world()
        val lane = Channel.waiting(H)
        val state = w[H].state
        assertEquals(listOf(A, B, H), state.readerIds(lane))
        assertEquals(listOf(lane), state.channelsReadBy(H))
        assertEquals(listOf(lane), state.shareChannels(H, ShareKind.POSITION))
        val position = w[H].frame(ShareKind.POSITION, lane, "position de H, en attente")
        assertEquals(setOf(A, B, H), w.openers(position))
        for (member in listOf(C, D, E, F)) {
            assertEquals(ShareRefusal.NO_KEY, w[member].refusal(position), "$member")
            assertFalse(state.canSee(member, H))
        }
        assertTrue(state.canSee(A, H) && state.canSee(B, H))
        for (batch in w[A].ring.batchesFor(w[A].state).filter { it.channel == lane }) {
            assertEquals(listOf(A, B, H), batch.wraps.map { it.recipient })
        }
        // The name it chose goes the same way, so that the administrators know whom they place.
        val own = w[H].frame(ShareKind.ROSTER, lane, NameBook.encode(listOf(card("Hotel"))))
        assertEquals(1, w[B].names.accept(w[B].share(own)))
        assertEquals("Hotel", w[B].names.nameOf(w[B].state, H))
        // Nothing comes back: nobody may write in that lane but the one who waits, and it shares nothing else.
        for (author in listOf(A, B)) {
            for (kind in ShareKind.entries) {
                assertEquals(ShareRefusal.NOT_ALLOWED, w[author].sealRefusal(kind, lane, "x"), "$kind $author")
            }
        }
        for (kind in listOf(ShareKind.SKETCH, ShareKind.WAYPOINT, ShareKind.MESSAGE, ShareKind.ALERT)) {
            assertEquals(ShareRefusal.NOT_ALLOWED, w[H].sealRefusal(kind, lane, "x"), "$kind")
        }
        // It still opens nothing of what the others share (its own lane is the one key it holds).
        assertEquals(
            ShareRefusal.NO_KEY,
            w[H].refusal(w[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, "position de C")),
        )
        // Placed, it has no lane of its own any more: its phone forgets that key, the administrators too.
        w[B].replica.must(Change.AssignMember(H, G1))
        w.spread()
        assertEquals(emptyList(), w[B].state.readersOf(lane))
        assertEquals(ShareRefusal.NOT_ALLOWED, w[H].sealRefusal(ShareKind.POSITION, lane, "x"))
        assertEquals(ShareRefusal.NOT_A_READER, w[B].refusal(position))
        w[B].ring.prune(w[B].state)
        assertEquals(ShareRefusal.NO_KEY, w[B].refusal(position))
    }

    @Test
    fun `Q12 an administrator puts a member back to waiting - every channel it read is keyed again without it`() {
        val before =
            listOf(Channel.ENSEMBLE, Channel.LEADERS, Channel.unit(G1), Channel.up(G1), Channel.down(G1))
        val start = commanded()
        // D, deputy of G1, reads five channels. Only an administrator or the creator takes it out of its group.
        assertEquals(before, start[D].state.channelsReadBy(D))
        for (author in listOf(C, E, F, D)) {
            assertEquals(Rejection.FORBIDDEN, start[author].replica.refused(Change.UnassignMember(D)), "$author")
        }
        for (heard in listOf(true, false)) {
            val case = if (heard) "its phone heard of it" else "its phone did not hear of it"
            val w = commanded()
            val away = if (heard) LongArray(0) else longArrayOf(D)
            val kept = w[D].frame(ShareKind.MESSAGE, Channel.unit(G1), "avant")
            w[B].replica.must(Change.UnassignMember(D))
            w.spread(*away)
            val now = w[B].state
            assertTrue(now.isWaiting(D), case)
            assertEquals(listOf(Channel.waiting(D)), now.channelsReadBy(D), case)
            // C read the same five channels: none has a key to seal with any more.
            for (channel in before) {
                assertNull(w[C].ring.sealingKey(w[C].state, channel), "$case, $channel")
                assertFalse(now.readersOf(channel).any { it.id == D }, "$case, $channel")
            }
            w.handOutMissing(*away)
            val batches = w[A].ring.batchesFor(w[A].state) + w[C].ring.batchesFor(w[C].state)
            for (batch in batches.filter { it.channel in before }) {
                assertTrue(batch.wraps.none { it.recipient == D }, "$case, ${batch.channel}")
            }
            val after =
                listOf(
                    w[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, "après"),
                    w[C].frame(ShareKind.ALERT, Channel.LEADERS, "après"),
                    w[C].frame(ShareKind.MESSAGE, Channel.unit(G1), "après"),
                    w[C].frame(ShareKind.SKETCH, Channel.up(G1), "après"),
                    w[B].frame(ShareKind.SKETCH, Channel.down(G1), "après"),
                )
            for (frame in after) assertEquals(ShareRefusal.NO_KEY, w[D].refusal(frame), case)
            // What it sealed before, or seals with a key it kept, is no longer taken from it.
            assertEquals(ShareRefusal.NOT_ALLOWED, w[C].refusal(kept), case)
            if (heard) {
                assertEquals(ShareRefusal.NOT_ALLOWED, w[D].sealRefusal(ShareKind.MESSAGE, Channel.unit(G1), "x"))
                // Its phone forgets the keys of the channels it left; it keeps its waiting lane only.
                w[D].ring.prune(w[D].state)
                assertEquals(ShareRefusal.NO_KEY, w[D].refusal(kept))
                assertEquals(emptyList(), w[D].ring.missing(w[D].state))
                val position = w[D].frame(ShareKind.POSITION, Channel.waiting(D), "en attente")
                assertEquals(setOf(A, B, D), w.openers(position))
            } else {
                val late = w[D].frame(ShareKind.MESSAGE, Channel.unit(G1), "encore moi")
                assertEquals(ShareRefusal.NOT_ALLOWED, w[C].refusal(late), case)
            }
        }
    }

    @Test
    fun `Q7 deleting a group puts its members back to waiting, by the keys too`() {
        val w = commanded()
        val old = w[D].frame(ShareKind.MESSAGE, Channel.unit(G1), "du temps du groupe 1")
        assertEquals(Rejection.FORBIDDEN, w[C].replica.refused(Change.DeleteGroup(G1)), "not its leader")
        w[B].replica.must(Change.DeleteGroup(G1))
        w.spread()
        val now = w[B].state
        assertEquals(listOf(C, D, H), now.waiting().map { it.id })
        assertEquals(setOf(Role.MEMBER, Role.WAITING), now.rolesOf(C))
        // The group and its lanes are gone; the ensemble and the lane of the leaders lost its two members.
        for (channel in listOf(Channel.unit(G1), Channel.up(G1), Channel.down(G1))) {
            assertFalse(now.isChannel(channel))
            assertEquals(emptyList(), now.readersOf(channel))
        }
        assertEquals(listOf(A, B, E, F), now.readerIds(Channel.ENSEMBLE))
        assertEquals(listOf(A, B, E), now.readerIds(Channel.LEADERS))
        for (member in listOf(C, D)) assertEquals(listOf(Channel.waiting(member)), now.channelsReadBy(member))
        assertNull(w[E].ring.sealingKey(w[E].state, Channel.ENSEMBLE))
        w.handOutMissing()
        val after =
            listOf(
                w[E].frame(ShareKind.POSITION, Channel.ENSEMBLE, "après"),
                w[E].frame(ShareKind.ALERT, Channel.LEADERS, "après"),
                w[B].frame(ShareKind.MESSAGE, Channel.ENSEMBLE, "après"),
            )
        for (member in listOf(C, D)) {
            for (frame in after) assertEquals(ShareRefusal.NO_KEY, w[member].refusal(frame), "$member")
            // The key of the group they kept shows nothing and seals nothing: there is no such place any more.
            assertEquals(ShareRefusal.NOT_A_READER, w[member].refusal(old), "$member")
            assertEquals(ShareRefusal.NOT_ALLOWED, w[member].sealRefusal(ShareKind.MESSAGE, Channel.unit(G1), "x"))
            // Their position now goes to the administrators only.
            val position = w[member].frame(ShareKind.POSITION, Channel.waiting(member), "en attente")
            assertEquals(setOf(A, B, member), w.openers(position))
        }
    }

    @Test
    fun `Q18 S4 when a leader goes its deputy becomes the leader, and the post of deputy is vacant`() {
        fun group(state: HierarchyState) = state.groups.getValue(G1).let { it.leader to it.deputy }
        val ways: List<Pair<String, (HierarchyReplica) -> Unit>> =
            listOf(
                "leaves the ensemble" to
                    { a -> a.copyFor(C).also { it.must(Change.RemoveMember(C)) }.let { sync(a, it) } },
                "is put out by the creator" to { a -> a.must(Change.RemoveMember(C)) },
                "is put back to waiting" to
                    { a -> a.copyFor(B).also { it.must(Change.UnassignMember(C)) }.let { sync(a, it) } },
                "is moved to another group" to { a -> a.must(Change.AssignMember(C, G2)) },
            )
        for ((way, change) in ways) {
            // The leader of a group, with a deputy: the deputy is the leader, nobody is deputy.
            val a =
                deployed().apply {
                    must(leader(G1, C))
                    must(deputy(G1, D))
                }
            change(a)
            assertEquals(D to null, group(a.state), way)
            assertEquals(setOf(Role.GROUP_LEADER, Role.MEMBER), a.state.rolesOf(D), way)
            assertNull(a.state.inconsistency(), way)
            // Without a deputy the post stays vacant, and nothing is blocked.
            val alone = deployed().apply { must(leader(G1, C)) }
            change(alone)
            assertEquals(null to null, group(alone.state), way)
            // The deputy who goes leaves its post vacant, the leader stays.
            val reverse =
                deployed().apply {
                    must(leader(G1, D))
                    must(deputy(G1, C))
                }
            change(reverse)
            assertEquals(D to null, group(reverse.state), way)
        }
        // The same for the leader of the ensemble (S4), who goes by leaving the ensemble or by being put out of it.
        for (byItself in listOf(true, false)) {
            val a =
                deployed().apply {
                    must(leader(ROOT, C))
                    must(deputy(ROOT, D))
                    must(leader(G1, C))
                }
            if (byItself) {
                a.copyFor(C).also {
                    it.must(Change.RemoveMember(C))
                }.let { sync(a, it) }
            } else {
                a.must(Change.RemoveMember(C))
            }
            assertEquals(D, a.state.leader)
            assertNull(a.state.deputy)
            assertEquals(setOf(Role.ENSEMBLE_LEADER, Role.MEMBER), a.state.rolesOf(D))
            // It also led its group, which had no deputy: that post is vacant.
            assertEquals(null to null, group(a.state))
        }
        // Taken out of its group, the leader of the ensemble keeps that post: it did not go from the ensemble.
        val kept =
            deployed().apply {
                must(leader(ROOT, C))
                must(deputy(ROOT, D))
                must(Change.UnassignMember(C))
            }
        assertEquals(C, kept.state.leader)
        assertEquals(D, kept.state.deputy)
        assertFalse(kept.state.isWaiting(C))
        // An administrator who leaves a post vacant, or names someone else to it, promotes nobody.
        val cleared =
            deployed().apply {
                must(leader(G1, C))
                must(deputy(G1, D))
                must(Change.ClearPost(G1, Post.LEADER))
            }
        assertEquals(null to D, group(cleared.state))
    }

    @Test
    fun `Q5 only the creator and the administrators let someone in`() {
        val a =
            deployed().apply {
                must(leader(ROOT, C))
                must(deputy(ROOT, D))
                must(leader(G2, E))
                must(deputy(G2, F))
                must(admit(H))
            }
        // The leader of the ensemble, its deputy, the leader of a group, its deputy, someone who waits: none.
        for (author in listOf(C, D, E, F, H)) {
            assertEquals(Rejection.FORBIDDEN, a.copyFor(author).refused(admit(I)), "$author")
        }
        a.copyFor(B).must(admit(I))
        a.must(admit(J))
        assertTrue(a.state.isWaiting(J))
    }

    @Test
    fun `Q9 the creator chooses its group when it creates the ensemble`() {
        val a = newEnsemble()
        a.must(Change.CreateGroup(G1, hidden("Groupe 1")))
        a.must(Change.AssignMember(A, G1))
        val state = a.state
        assertEquals(G1, state.groupOf(A)?.id)
        assertEquals(listOf(G1, ROOT), state.unitsReadBy(A))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.unit(G1), Channel.down(G1)), state.channelsReadBy(A))
        // Like everyone, it has a thread « my group »; it still does everything the creator does.
        assertEquals(listOf(Channel.ENSEMBLE, Channel.unit(G1)), state.shareChannels(A, ShareKind.MESSAGE))
        assertNull(HierarchyReducer.check(state, A, admit(B)))
        // Nothing forces it: a creator without a group keeps its access.
        assertTrue(newEnsemble().state.hasAccess(A))
    }

    @Test
    fun `Q14 P3 the creator names and removes the administrators, who name the rest and cannot step down`() {
        val a = deployed()
        val b = a.copyFor(B)
        // An administrator does not name another one, does not remove one, does not give up its own role.
        for (change in listOf(Change.AddAdmin(C), Change.RemoveAdmin(B))) {
            assertEquals(Rejection.FORBIDDEN, b.refused(change), "$change")
        }
        // It names to every post, and places the members.
        b.must(leader(ROOT, C))
        b.must(deputy(ROOT, D))
        b.must(leader(G2, E))
        b.must(deputy(G2, F))
        b.must(Change.AssignMember(D, G2))
        sync(a, b)
        // The creator alone decides who is an administrator.
        a.must(Change.AddAdmin(C))
        a.must(Change.RemoveAdmin(B))
        sync(a, b)
        assertEquals(setOf(C), a.state.admins)
        assertEquals(Rejection.FORBIDDEN, b.refused(leader(G1, C)), "its role went with the creator's decision")
    }

    @Test
    fun `P4 one deputy for each leader of a group and one for the leader of the ensemble`() {
        val a = deployed().apply { must(admit(H)) }
        a.must(Change.AssignMember(H, G1))
        for (unit in listOf(ROOT, G1)) {
            a.must(leader(unit, C))
            a.must(deputy(unit, D))
            // A second one named takes the place of the first: never two.
            a.must(deputy(unit, H))
            assertEquals(H, a.state.deputyOf(unit))
            assertEquals(C, a.state.leaderOf(unit))
        }
        assertEquals(setOf(Role.MEMBER), a.state.rolesOf(D))
        assertEquals(setOf(Role.ENSEMBLE_DEPUTY, Role.GROUP_DEPUTY, Role.MEMBER), a.state.rolesOf(H))
        // A deputy without a leader is allowed: it keeps the rights of the post.
        a.must(Change.ClearPost(G1, Post.LEADER))
        assertTrue(a.state.mayShare(H, ShareKind.SKETCH, Channel.up(G1)))
    }

    @Test
    fun `Q11 Q17 the name one chose is written in full for its group and on the leaders, as initials elsewhere`() {
        val state = staffedState()
        // In full: oneself, the members of one's own group, whoever holds a post of leader or deputy.
        assertEquals(NameLabel.FULL, state.nameLabel(I, I))
        assertEquals(NameLabel.FULL, state.nameLabel(I, D))
        assertEquals(NameLabel.FULL, state.nameLabel(E, C), "the leader of another group")
        assertEquals(NameLabel.FULL, state.nameLabel(I, F), "the leader of the ensemble")
        assertEquals(NameLabel.FULL, state.nameLabel(I, H), "its deputy")
        // Initials: a plain member of another group, an administrator or the creator without a post of leader.
        assertEquals(NameLabel.INITIALS, state.nameLabel(E, I))
        assertEquals(NameLabel.INITIALS, state.nameLabel(I, E))
        assertEquals(NameLabel.INITIALS, state.nameLabel(I, B))
        assertEquals(NameLabel.INITIALS, state.nameLabel(I, A))
        assertEquals(NameLabel.INITIALS, state.nameLabel(I, OUTSIDER))
        // A rule of the display: every member who has access knows every name (it reads it by touching the marker).
        assertEquals("AB", Names.initials("Alpha Bravo"))
        assertEquals("KD", Names.initials("  kilo-lima   de la Tour "))
        assertEquals("AL", Names.initials("alpha"))
        assertEquals("É", Names.initials("é"))
        assertEquals("", Names.initials("   "))
    }

    @Test
    fun `S7 the phone of the creator is lost - the administrators go on, what only the creator did stops`() {
        val w = commanded()
        // A never writes again. B, the administrator, still runs the ensemble.
        val b = w[B].replica
        b.must(admit(I))
        b.must(Change.CreateGroup(G3, hidden("Groupe 3")))
        b.must(Change.AssignMember(I, G3))
        b.must(Change.RenameGroup(G3, hidden("Relève")))
        b.must(leader(G3, I))
        b.must(leader(ROOT, C))
        b.must(Change.AssignMember(F, G1))
        // The one way left to cut someone off: back to waiting (Q12).
        b.must(Change.UnassignMember(F))
        b.must(Change.DeleteGroup(G2))
        // What only the creator does is done by nobody any more.
        val lost =
            listOf(
                Change.RemoveMember(F),
                Change.AddAdmin(C),
                Change.RemoveAdmin(B),
                Change.TransferCreator(B),
                Change.Dissolve,
                Change.RemoveMember(A),
            )
        for (author in listOf(B, C, D, I)) {
            val phone = b.copyFor(author)
            for (change in lost) assertEquals(Rejection.FORBIDDEN, phone.refused(change), "$author $change")
        }
        // The lost phone is still the creator: it keeps its access, nobody takes it away. No back door.
        val state = b.state
        assertEquals(A, state.creator)
        assertEquals(Rejection.NO_CHANGE, b.refused(Change.UnassignMember(A)))
        assertTrue(state.hasAccess(A))
        val read = state.channelsReadBy(A)
        assertTrue(read.containsAll(listOf(Channel.ENSEMBLE, Channel.LEADERS, Channel.up(G1), Channel.down(G1))))
        assertTrue(read.containsAll(state.waiting().map { Channel.waiting(it.id) }))
        // So every new key of those channels is still wrapped to it.
        w.spread()
        val batch = w[B].issue(Channel.ENSEMBLE)
        assertTrue(batch.wraps.any { it.recipient == A })
        // Members still leave by themselves; an administrator who leaves is not replaced.
        b.copyFor(D).must(Change.RemoveMember(D))
        val last = b.copyFor(B)
        last.must(Change.RemoveMember(B))
        assertEquals(emptySet(), last.state.admins)
        // Then nobody lets anyone in, places anyone or names anyone: the ensemble only shares until its end.
        for (author in listOf(C, I)) {
            val phone = last.copyFor(author)
            for (change in listOf(
                admit(J),
                Change.AssignMember(F, G1),
                leader(G1, C),
                Change.CreateGroup(0x104, hidden("x")),
            )) {
                assertEquals(Rejection.FORBIDDEN, phone.refused(change), "$author $change")
            }
        }
        assertTrue(
            last.state.mayShare(C, ShareKind.MESSAGE, Channel.ENSEMBLE),
            "the leader of the ensemble still writes",
        )
        assertNull(last.state.inconsistency())
    }
}
