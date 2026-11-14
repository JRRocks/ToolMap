package fr.toolmap.core.hierarchy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where an alert goes (`docs/specs/group-hierarchy.md` § 4.2; the user's answers Q6, then T4, T5, T13 and T16 of
 * 2026-11-14): one destination per person, with no choice when sending. The leader of the ensemble or its deputy:
 * everyone who is placed. Else the leader or the deputy of a group, an administrator, the creator: every leader, and
 * not their own group. Else a member: its group. Who waits: nobody. One test per combination of roles; what closes
 * something is checked with the keys.
 */
class HierarchyAlertTest {
    /**
     * [deployed] with its posts and every role: A the creator and B an administrator, both without a group; C leads
     * G1 with D as deputy and I as a plain member; E leads G2, where F is a plain member and J an administrator
     * without a post; H waits. Nobody leads the ensemble yet.
     */
    private fun base(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
        deployed(rules).apply {
            must(admit(H, "Hotel"))
            must(admit(I, "India"))
            must(admit(J, "Juliett"))
            must(Change.AssignMember(I, G1))
            must(Change.AssignMember(J, G2))
            must(Change.AddAdmin(J))
            must(leader(G1, C))
            must(deputy(G1, D))
            must(leader(G2, E))
        }

    /** The phones of [base], every channel that has readers with its key. */
    private fun phones(): World = World(base(), listOf(A, B, C, D, E, F, H, I, J)).apply { handOutMissing() }

    /** Everyone who has a group or a post: all of [base] but H, who waits. */
    private val placed = listOf(A, B, C, D, E, F, I, J)

    /** Every leader of [base]: the creator, the administrators, the leaders of the groups and the deputy of G1. */
    private val leaders = listOf(A, B, C, D, E, J)

    /**
     * [member] alerts in [channel] and nowhere else, as [role]; [readers] read it. With no channel: it alerts
     * nobody.
     */
    private fun HierarchyState.assertAlert(
        member: Long,
        channel: Channel?,
        role: Role? = null,
        readers: List<Long> = emptyList(),
    ) {
        assertEquals(listOfNotNull(channel), shareChannels(member, ShareKind.ALERT), "the one destination")
        assertEquals(channel, alertChannel(member))
        for (other in channels()) assertEquals(other == channel, mayShare(member, ShareKind.ALERT, other), "$other")
        assertEquals(role, destinationRole(member, ShareKind.ALERT))
        if (channel != null) assertEquals(readers, readerIds(channel), "who reads $channel")
    }

    /**
     * What a modified phone would do: seal an alert where it may not, with a key it holds, signed with its own key
     * the way an honest phone signs.
     */
    private fun forge(
        forger: Device,
        channel: Channel,
        text: String,
    ): ByteArray {
        val key = assertNotNull(forger.ring.sealingKey(forger.state, channel), "the forger holds the key of $channel")
        val header = HierarchyWire.shareHeader(forger.state.ensembleId, channel, key.id, ShareKind.ALERT, forger.id)
        val nonce = Random.nextBytes(Aead.NONCE_BYTES)
        val payload = text.toByteArray()
        val signature = keysOf(forger.id).sign(ShareSeal.SHARE_TAG + header + nonce + payload)
        return header + nonce + Aead.seal(key.sealingKey(forger.id), nonce, header, payload + signature)
    }

    @Test
    fun `Q6 a plain member alerts its group, and nobody else`() {
        val state = base().state
        state.assertAlert(I, Channel.unit(G1), Role.MEMBER, listOf(C, D, I))
        state.assertAlert(F, Channel.unit(G2), Role.MEMBER, listOf(E, F, J))
    }

    @Test
    fun `T4 the leader of a group alerts every leader, and not its own group`() {
        val state = base().state
        state.assertAlert(C, Channel.LEADERS, Role.GROUP_LEADER, leaders)
        state.assertAlert(E, Channel.LEADERS, Role.GROUP_LEADER, leaders)
        // I and F, plain members of their groups, are not among the readers; H, who waits, neither.
        for (member in listOf(F, H, I)) assertFalse(member in state.readerIds(Channel.LEADERS))
    }

    @Test
    fun `T4 the deputy of a group alerts like its leader, the post of leader held or vacant`() {
        val a = base()
        a.state.assertAlert(D, Channel.LEADERS, Role.GROUP_DEPUTY, leaders)
        a.must(Change.ClearPost(G1, Post.LEADER))
        a.state.assertAlert(D, Channel.LEADERS, Role.GROUP_DEPUTY, listOf(A, B, D, E, J))
        // C leads nothing any more: a plain member again, it alerts its group.
        a.state.assertAlert(C, Channel.unit(G1), Role.MEMBER, listOf(C, D, I))
    }

    @Test
    fun `T13 an administrator who leads nothing alerts every leader, with or without a group`() {
        val state = base().state
        // J is in G2 and does not alert it; B has no group.
        state.assertAlert(J, Channel.LEADERS, Role.ADMIN, leaders)
        state.assertAlert(B, Channel.LEADERS, Role.ADMIN, leaders)
    }

    @Test
    fun `T13 the creator who leads nothing alerts every leader, with or without a group`() {
        val a = base()
        a.state.assertAlert(A, Channel.LEADERS, Role.CREATOR, leaders)
        a.must(Change.AssignMember(A, G2))
        a.state.assertAlert(A, Channel.LEADERS, Role.CREATOR, leaders)
    }

    @Test
    fun `T13 the creator alone alerts a lane it alone reads`() {
        // Nobody else leads anything yet: the alert has a destination, and no reader but its sender.
        newEnsemble().state.assertAlert(A, Channel.LEADERS, Role.CREATOR, listOf(A))
    }

    @Test
    fun `Q6 T16 the leader of the ensemble and its deputy alert everyone who is placed, not who waits`() {
        val a = base()
        a.must(leader(ROOT, F))
        a.must(deputy(ROOT, I))
        // Both are plain members of a group, which they no longer alert.
        a.state.assertAlert(F, Channel.ENSEMBLE, Role.ENSEMBLE_LEADER, placed)
        a.state.assertAlert(I, Channel.ENSEMBLE, Role.ENSEMBLE_DEPUTY, placed)
        assertFalse(H in a.state.readerIds(Channel.ENSEMBLE))
        // The deputy has the cell of its leader, the post of leader held or vacant.
        a.must(Change.ClearPost(ROOT, Post.LEADER))
        a.state.assertAlert(I, Channel.ENSEMBLE, Role.ENSEMBLE_DEPUTY, placed)
        a.state.assertAlert(F, Channel.unit(G2), Role.MEMBER, listOf(E, F, J))
    }

    @Test
    fun `Q6 T4 the leader or the deputy of a group who also leads the ensemble alerts everyone, not the leaders`() {
        val a = base()
        a.must(leader(ROOT, C))
        a.must(deputy(ROOT, D))
        a.state.assertAlert(C, Channel.ENSEMBLE, Role.ENSEMBLE_LEADER, placed)
        a.state.assertAlert(D, Channel.ENSEMBLE, Role.ENSEMBLE_DEPUTY, placed)
        // The other leader of a group still alerts the leaders.
        a.state.assertAlert(E, Channel.LEADERS, Role.GROUP_LEADER, leaders)
    }

    @Test
    fun `Q6 T13 an administrator or the creator who also leads the ensemble alerts everyone`() {
        val a = base()
        a.must(leader(ROOT, A))
        a.must(deputy(ROOT, B))
        a.state.assertAlert(A, Channel.ENSEMBLE, Role.ENSEMBLE_LEADER, placed)
        a.state.assertAlert(B, Channel.ENSEMBLE, Role.ENSEMBLE_DEPUTY, placed)
        // Another administrator takes the post of deputy: B alerts the leaders again.
        a.must(deputy(ROOT, J))
        a.state.assertAlert(J, Channel.ENSEMBLE, Role.ENSEMBLE_DEPUTY, placed)
        a.state.assertAlert(B, Channel.LEADERS, Role.ADMIN, leaders)
    }

    @Test
    fun `T4 T13 an administrator or the creator who leads a group still has one destination, every leader`() {
        val a = base()
        a.must(Change.AddAdmin(C))
        a.state.assertAlert(C, Channel.LEADERS, Role.ADMIN, leaders)
        a.must(deputy(G2, J))
        a.state.assertAlert(J, Channel.LEADERS, Role.ADMIN, leaders)
        // The creator takes the place of E at the head of G2: E is a plain member again.
        a.must(Change.AssignMember(A, G2))
        a.must(leader(G2, A))
        a.state.assertAlert(A, Channel.LEADERS, Role.CREATOR, listOf(A, B, C, D, J))
        a.state.assertAlert(E, Channel.unit(G2), Role.MEMBER, listOf(A, E, F, J))
    }

    @Test
    fun `Q6 who waits alerts nobody`() {
        val a = base()
        a.state.assertAlert(H, null)
        // An administrator without a group who loses its role waits; so does a member put back to waiting.
        a.must(Change.RemoveAdmin(B))
        a.must(Change.UnassignMember(I))
        a.state.assertAlert(B, null)
        a.state.assertAlert(I, null)
        // The leader of G1 put back to waiting alerts nobody; its deputy leads now, and alerts the leaders.
        a.must(Change.UnassignMember(C))
        a.state.assertAlert(C, null)
        a.state.assertAlert(D, Channel.LEADERS, Role.GROUP_LEADER, listOf(A, D, E, J))
    }

    @Test
    fun `sub-groups - the leader of a sub-group alerts every leader, a member of a sub-group its sub-group`() {
        val state =
            staffed(TWO_LEVELS)
                .apply {
                    must(admit(E, "Echo"))
                    must(Change.CreateGroup(G3, hidden("Sous-groupe"), parent = G1))
                    must(Change.AssignMember(C, G1))
                    must(Change.AssignMember(D, G3))
                    must(Change.AssignMember(E, G3))
                    must(leader(G1, C))
                    must(leader(G3, D))
                }.state
        state.assertAlert(D, Channel.LEADERS, Role.GROUP_LEADER, listOf(A, B, C, D))
        state.assertAlert(C, Channel.LEADERS, Role.GROUP_LEADER, listOf(A, B, C, D))
        state.assertAlert(E, Channel.unit(G3), Role.MEMBER, listOf(D, E))
    }

    @Test
    fun `T4 T13 by the keys - the alert of a leader, an administrator or the creator is opened by the leaders only`() {
        val w = phones()
        for (author in listOf(C, D, E, J, B, A)) {
            val alert = w[author].frame(ShareKind.ALERT, Channel.LEADERS, "alerte de $author")
            assertEquals(leaders.toSet(), w.openers(alert), "$author")
            // The plain members, of the sender's own group too, and who waits: no copy of the key was made for them.
            for (member in listOf(F, H, I)) assertEquals(ShareRefusal.NO_KEY, w[member].refusal(alert), "$member")
        }
        for (batch in w[A].ring.batchesFor(w[A].state).filter { it.channel == Channel.LEADERS }) {
            assertEquals(leaders, batch.wraps.map { it.recipient })
        }
        // A plain member: its group, its leader and its deputy included, and nobody above.
        val fromMember = w[I].frame(ShareKind.ALERT, Channel.unit(G1), "alerte d'un membre")
        assertEquals(setOf(C, D, I), w.openers(fromMember))
        for (member in listOf(A, B, E, J)) assertEquals(ShareRefusal.NO_KEY, w[member].refusal(fromMember))
    }

    @Test
    fun `T16 by the keys - the alert of the leader of the ensemble is opened by everyone placed, not by who waits`() {
        val w = phones()
        w[B].replica.must(leader(ROOT, F))
        w[B].replica.must(deputy(ROOT, I))
        w.spread()
        w.handOutMissing()
        for (author in listOf(F, I)) {
            val alert = w[author].frame(ShareKind.ALERT, Channel.ENSEMBLE, "alerte de $author")
            assertEquals(placed.toSet(), w.openers(alert), "$author")
            assertEquals(ShareRefusal.NO_KEY, w[H].refusal(alert))
        }
    }

    @Test
    fun `an alert sealed for another place is refused, by the phone of its sender and by every other`() {
        val w = phones()
        // The phone of the sender seals an alert nowhere but in its one destination.
        for (author in placed) {
            val state = w[author].state
            val destination = assertNotNull(state.alertChannel(author))
            for (channel in state.channels().filter { it != destination }) {
                assertEquals(
                    ShareRefusal.NOT_ALLOWED,
                    w[author].sealRefusal(ShareKind.ALERT, channel, "ailleurs"),
                    "$author in $channel",
                )
            }
        }
        for (channel in w[H].state.channels()) {
            assertEquals(ShareRefusal.NOT_ALLOWED, w[H].sealRefusal(ShareKind.ALERT, channel, "en attente"))
        }
        // The forged frame itself is well made: in the destination of its sender, every reader opens it.
        assertEquals(leaders.toSet(), w.openers(forge(w[C], Channel.LEADERS, "au bon endroit")))
        // A modified phone seals where it reads and may not alert: nobody opens it, the readers say why.
        val cases =
            listOf(
                // The leader of a group and its deputy, to their own group (T4).
                Triple(C, Channel.unit(G1), listOf(C, D, I)),
                Triple(D, Channel.unit(G1), listOf(C, D, I)),
                // An administrator in a group, to its group (T13); to everyone.
                Triple(J, Channel.unit(G2), listOf(E, F, J)),
                Triple(J, Channel.ENSEMBLE, placed),
                // The creator and the leader of a group, to everyone; a plain member, to everyone.
                Triple(A, Channel.ENSEMBLE, placed),
                Triple(E, Channel.ENSEMBLE, placed),
                Triple(I, Channel.ENSEMBLE, placed),
                // The leader of a group, up to those who command the ensemble only.
                Triple(C, Channel.up(G1), listOf(A, B, C, D, J)),
            )
        for ((forger, channel, readers) in cases) {
            val forged = forge(w[forger], channel, "ailleurs")
            assertEquals(emptySet(), w.openers(forged), "$forger in $channel")
            for (reader in readers) {
                assertEquals(
                    ShareRefusal.NOT_ALLOWED,
                    w[reader].refusal(forged),
                    "$forger in $channel, read by $reader",
                )
            }
        }
        // The leader of the ensemble reads the lane of the leaders and its own group, and alerts neither.
        w[B].replica.must(leader(ROOT, F))
        w.spread()
        w.handOutMissing()
        for (channel in listOf(Channel.LEADERS, Channel.unit(G2))) {
            val forged = forge(w[F], channel, "ailleurs")
            assertEquals(emptySet(), w.openers(forged), "$channel")
            assertEquals(ShareRefusal.NOT_ALLOWED, w[E].refusal(forged), "$channel")
        }
    }

    @Test
    fun `the destination follows the role - an alert sealed before a nomination is refused once it is known`() {
        val w = phones()
        val before = w[I].frame(ShareKind.ALERT, Channel.unit(G1), "avant d'être chef")
        assertEquals(setOf(C, D, I), w.openers(before))
        // I takes the place of C at the head of G1; D has not heard of it yet.
        w[B].replica.must(leader(G1, I))
        w.spread(D)
        assertEquals("avant d'être chef", w[D].read(before))
        assertEquals(ShareRefusal.NOT_ALLOWED, w[C].refusal(before))
        w.spread()
        w.handOutMissing()
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].refusal(before))
        // I alerts the leaders now, C its group.
        assertEquals(ShareRefusal.NOT_ALLOWED, w[I].sealRefusal(ShareKind.ALERT, Channel.unit(G1), "encore"))
        val after = w[I].frame(ShareKind.ALERT, Channel.LEADERS, "une fois chef")
        assertEquals(setOf(A, B, D, E, I, J), w.openers(after))
        assertEquals(ShareRefusal.NO_KEY, w[C].refusal(after))
        assertEquals(setOf(C, D, I), w.openers(w[C].frame(ShareKind.ALERT, Channel.unit(G1), "simple membre")))
    }

    @Test
    fun `the same destination on every phone, whatever the order the changes arrive in`() {
        val a = base()
        val b = a.copyFor(B)
        // The creator moves C, leader of G1, to G2 while an administrator names it leader of the ensemble.
        val fromA = a.must(Change.AssignMember(C, G2))
        val fromB = b.must(leader(ROOT, C))
        assertEquals(fromA.clock, fromB.clock, "neither had seen the other")
        // Before they talk: C alerts its new group on one phone, everyone on the other.
        assertEquals(Channel.unit(G2), a.state.alertChannel(C))
        assertEquals(Channel.ENSEMBLE, b.state.alertChannel(C))
        sync(a, b)
        assertSame(a, b)
        val ops = a.ops()
        for (order in listOf(ops, ops.reversed(), ops.sortedWith(Op.ORDER))) {
            val phone = HierarchyReplica(a.ensembleId, OUTSIDER).apply { order.forEach(::add) }
            assertSame(a, phone)
            for (member in a.state.members.keys) {
                assertEquals(a.state.alertChannel(member), phone.state.alertChannel(member), "$member")
            }
        }
        // C leads the ensemble from G2; D, its deputy in G1, leads G1 now; I stays a plain member of G1.
        a.state.assertAlert(C, Channel.ENSEMBLE, Role.ENSEMBLE_LEADER, placed)
        a.state.assertAlert(D, Channel.LEADERS, Role.GROUP_LEADER, leaders)
        a.state.assertAlert(I, Channel.unit(G1), Role.MEMBER, listOf(D, I))
    }

    @Test
    fun `in data - T13 is one cell per role, T4 one cell, and the single destination a rule of the ensemble`() {
        // Without the two cells of T13, an administrator and the creator alert as members: their group, or nobody.
        val asMembers =
            ShareTable.DEFAULT.with(ShareKind.ALERT, Role.ADMIN).with(ShareKind.ALERT, Role.CREATOR)
        val before = base(HierarchyRules(shares = asMembers)).state
        before.assertAlert(J, Channel.unit(G2), Role.MEMBER, listOf(E, F, J))
        before.assertAlert(B, null, Role.MEMBER)
        before.assertAlert(A, null, Role.MEMBER)
        before.assertAlert(C, Channel.LEADERS, Role.GROUP_LEADER, leaders)
        // The other answer to T4 (every leader and its own group) is one cell: the alert goes to both, no choice.
        val both = ShareTable.DEFAULT.with(ShareKind.ALERT, Role.GROUP_LEADER, ShareReach.LEADERS, ShareReach.OWN_UNIT)
        val wide = base(HierarchyRules(shares = both)).state
        for (member in listOf(C, D)) {
            assertEquals(listOf(Channel.LEADERS, Channel.unit(G1)), wide.shareChannels(member, ShareKind.ALERT))
        }
        wide.assertAlert(I, Channel.unit(G1), Role.MEMBER, listOf(C, D, I))
        wide.assertAlert(J, Channel.LEADERS, Role.ADMIN, leaders)
        // An ensemble whose alert has no single destination: the roles add up, as for the other kinds.
        val open = base(HierarchyRules(singleDestination = emptySet())).apply { must(leader(ROOT, F)) }.state
        assertEquals(listOf(Channel.LEADERS, Channel.unit(G1)), open.shareChannels(C, ShareKind.ALERT))
        assertEquals(listOf(Channel.LEADERS, Channel.unit(G2)), open.shareChannels(J, ShareKind.ALERT))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.unit(G2)), open.shareChannels(F, ShareKind.ALERT))
        assertNull(open.destinationRole(C, ShareKind.ALERT))
        // The rule is not made for the alert alone: messages with one destination follow the same order of roles.
        val messages = setOf(ShareKind.ALERT, ShareKind.MESSAGE)
        val narrow = base(HierarchyRules(singleDestination = messages)).state
        assertEquals(listOf(Channel.up(G1)), narrow.shareChannels(C, ShareKind.MESSAGE))
        assertEquals(listOf(Channel.unit(G1)), narrow.shareChannels(I, ShareKind.MESSAGE))
        // The other kinds keep adding up the roles of their author.
        val state = base().state
        assertEquals(listOf(Channel.unit(G1), Channel.up(G1)), state.shareChannels(C, ShareKind.MESSAGE))
        assertNull(state.destinationRole(C, ShareKind.MESSAGE))
    }
}
