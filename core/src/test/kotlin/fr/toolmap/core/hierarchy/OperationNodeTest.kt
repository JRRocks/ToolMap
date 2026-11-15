package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exchange between phones of an operation (P39.7, `docs/specs/group-hierarchy.md` § 17), with simulated phones on
 * a simulated room of the coordination server: every frame one phone writes reaches every phone present, in order,
 * nothing else. No network, a clock of the test's own.
 */
class OperationNodeTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        assertTrue(problems.none { !it.expected }, "bugs reported: $problems")
    }

    private val server = RelayConfig.of("https://example.invalid", "key")

    /** The room: who is present, and the frames written, delivered in order. */
    inner class Room {
        var now = 1_700_000_000_000L
        val phones = ArrayList<Phone>()
        val queue = ArrayDeque<Triple<Phone, Phone, ByteArray>>()

        /** Every frame written, by whom: what an eavesdropper of the room sees, and what a replay uses. */
        val written = ArrayList<Pair<Long, ByteArray>>()

        fun present(): List<Phone> = phones.filter { it.online && it.node != null && it.node?.ending == null }

        fun link(phone: Phone) {
            for (other in present()) {
                if (other === phone) continue
                other.node!!.linkOpened(phone.id, linkTo(other, phone))
                phone.node!!.linkOpened(other.id, linkTo(phone, other))
            }
        }

        private fun linkTo(
            from: Phone,
            to: Phone,
        ): MeshLink =
            object : MeshLink {
                override val isBroadcastMedium get() = true

                override fun send(
                    frame: ByteArray,
                    onPositionChannel: Boolean,
                ): Boolean {
                    if (!from.online) return false
                    // One write reaches the whole room: the frame is delivered once to each phone present.
                    if (written.lastOrNull()?.second === frame) return true
                    written += from.id to frame
                    for (other in present()) if (other !== from) queue.addLast(Triple(from, other, frame))
                    return true
                }

                override fun close() = Unit
            }

        /** Delivers, ticks and moves the clock until nothing is left to say. */
        fun settle(rounds: Int = 200) {
            var quiet = 0
            repeat(rounds) {
                while (queue.isNotEmpty()) {
                    val (from, to, frame) = queue.removeFirst()
                    if (to.online) to.node?.receive(from.id, frame)
                }
                now += 500
                for (p in present()) p.node!!.tick()
                if (queue.isEmpty()) quiet++ else quiet = 0
                if (quiet > 12) return
            }
        }

        fun creator(
            name: String,
            operation: String = "Opération Vercors",
        ): Phone {
            val phone = Phone(name)
            val g = OperationNode.genesis(phone.keys, Names.hide(operation), Names.hide(name), server)
            phone.node = OperationNode(phone.keys, g.replica, g.ring, g.names, g.objects, g.link, phone, { now })
            phones += phone
            phone.node!!.start()
            link(phone)
            return phone
        }

        fun joiner(
            name: String,
            invite: OperationInvite,
        ): Phone {
            val phone = Phone(name)
            val replica = HierarchyReplica(invite.ensembleId, phone.id, phone.keys)
            val node =
                OperationNode(
                    phone.keys,
                    replica,
                    KeyRing(invite.ensembleId, phone.keys),
                    NameBook(),
                    SharedObjects(invite.ensembleId),
                    OperationNode.joining(invite, Names.hide(name)),
                    phone,
                    { now },
                )
            phone.node = node
            phones += phone
            node.start()
            link(phone)
            return phone
        }

        fun goOffline(phone: Phone) {
            phone.online = false
            for (other in present()) other.node!!.linkClosed(phone.id)
            phone.node!!.let { n -> present().forEach { n.linkClosed(it.id) } }
        }

        fun comeBack(phone: Phone) {
            phone.online = true
            link(phone)
        }
    }

    inner class Phone(
        val label: String,
    ) : OperationListener {
        val keys = OpAuth.memoryKeys()
        val id = keys.memberId
        var node: OperationNode? = null
        var online = true
        val positions = HashMap<Long, PeerFix>()
        val messages = ArrayList<Triple<Long, Channel, String>>()
        val alerts = ArrayList<Triple<Long, Channel, OperationText>>()
        var ended: OperationEnding? = null

        val n: OperationNode get() = node!!
        val state: HierarchyState get() = n.state

        override fun onPosition(
            member: Long,
            channel: Channel,
            fix: PeerFix,
        ) {
            positions[member] = fix
        }

        override fun onMessage(
            author: Long,
            channel: Channel,
            message: OperationText,
        ) {
            messages += Triple(author, channel, message.text)
        }

        override fun onAlert(
            author: Long,
            channel: Channel,
            alert: OperationText,
        ) {
            alerts += Triple(author, channel, alert)
        }

        override fun onEnded(ending: OperationEnding) {
            ended = ending
        }

        fun must(change: Change) {
            val r = n.issue(change)
            assertTrue(r is IssueResult.Issued, "$label: $change refused: ${(r as? IssueResult.Refused)?.reason}")
        }

        fun nameOf(member: Long): String? = n.names.nameOf(state, member)

        override fun toString() = label
    }

    private fun fix(
        t: Long,
        lat: Double = 45.1,
    ) = PeerFix(lat, 5.5, timeSec = t)

    private val random = SecureRandom()

    private fun Phone.newGroup(name: String): Long {
        val card = Names.hide(name)
        val group = random.nextLong().let { if (it == 0L) 1L else it }
        must(Change.CreateGroup(group, card.hidden))
        n.learnAndShare(card)
        return group
    }

    /** A phone that comes in with [creator]'s invitation and is let in by it. */
    private fun Room.letIn(
        creator: Phone,
        name: String,
    ): Phone {
        val invite = assertNotNull(creator.n.invitation())
        val parsed = assertNotNull(OperationInviteCodec.parseOrNull(OperationInviteCodec.toLink(invite)))
        val phone = joiner(name, parsed)
        settle()
        assertTrue(creator.n.pendingJoins.any { it.member == phone.id }, "$name asks to come in")
        assertTrue(creator.n.admit(phone.id) is IssueResult.Issued)
        settle()
        return phone
    }

    @Test
    fun `creation, invitation, request, waiting, then placed`() {
        val room = Room()
        val a = room.creator("Alice")
        val invite = assertNotNull(a.n.invitation())
        assertEquals("Opération Vercors", invite.ensembleCard.name)
        val b = room.joiner("Bruno", OperationInviteCodec.parseOrNull(OperationInviteCodec.toQrText(invite))!!)
        assertEquals("Opération Vercors", b.n.operationName(), "the name the invitation carries, before anything")
        room.settle()
        // The request reached the creator with its name, wrapped to it; Bruno is not let in yet.
        val pending = a.n.pendingJoins.single()
        assertEquals(b.id, pending.member)
        assertEquals("Bruno", pending.card?.name)
        assertFalse(b.n.isMember)
        assertTrue(b.n.inviteNameChecked)
        assertEquals("Opération Vercors", b.n.operationName(), "checked against the first operation")
        // Let in: Bruno waits, sends its position to the administrators, its name is known to them only.
        assertTrue(a.n.admit(b.id) is IssueResult.Issued)
        room.settle()
        assertTrue(b.n.isMember && b.state.isWaiting(b.id))
        assertTrue(a.n.pendingJoins.isEmpty())
        assertEquals(ShareOutcome.SENT, b.n.sendPosition(fix(100)))
        room.settle()
        assertEquals(45.1, a.positions[b.id]?.lat)
        assertEquals("Bruno", a.nameOf(b.id))
        assertNull(b.nameOf(a.id), "who waits reads no name but the operation's and its own")
        assertEquals("Opération Vercors", b.n.operationName())
        // Placed in a group: it reads the names of everyone, and the creator's position.
        val g = a.newGroup("Groupe Nord")
        a.must(Change.AssignMember(b.id, g))
        room.settle()
        assertFalse(b.state.isWaiting(b.id))
        assertEquals("Alice", b.nameOf(a.id))
        assertEquals("Groupe Nord", b.n.names.nameOf(b.state.groups.getValue(g).name))
        assertEquals(ShareOutcome.SENT, a.n.sendPosition(fix(200, lat = 44.9)))
        room.settle()
        assertEquals(44.9, b.positions[a.id]?.lat)
        assertEquals(a.state.ensembleId, b.state.ensembleId)
        assertEquals(a.n.replica.summary(), b.n.replica.summary())
    }

    @Test
    fun `a late phone catches up on what happened while it was away`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val g = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, g))
        a.must(Change.AssignMember(c.id, g))
        room.settle()
        room.goOffline(c)
        val g2 = a.newGroup("Sud")
        a.must(Change.AssignMember(b.id, g2))
        a.must(Change.SetPost(g2, Post.LEADER, b.id))
        a.must(Change.AddAdmin(b.id))
        assertEquals(ShareOutcome.SENT, a.n.putObject(ShareKind.WAYPOINT, Channel.ENSEMBLE, 7L, byteArrayOf(1, 2, 3)))
        room.settle()
        assertTrue(c.state.groups[g2] == null, "away: it knows nothing of it")
        room.comeBack(c)
        room.settle()
        assertEquals(a.n.replica.summary(), c.n.replica.summary())
        assertEquals(b.id, c.state.leaderOf(g2))
        assertEquals("Sud", c.n.names.nameOf(c.state.groups.getValue(g2).name))
        assertEquals(listOf(7L), c.n.objects.shown(Channel.ENSEMBLE).map { it.objectId })
        assertNotNull(c.n.ring.sealingKey(c.state, Channel.ENSEMBLE), "the keys made while it was away came")
    }

    @Test
    fun `the deputy becomes leader when the leader leaves, and the leaders' alert follows the new posts`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val d = room.letIn(a, "David")
        val g = a.newGroup("Nord")
        for (p in listOf(b, c, d)) a.must(Change.AssignMember(p.id, g))
        a.must(Change.SetPost(g, Post.LEADER, b.id))
        a.must(Change.SetPost(g, Post.DEPUTY, c.id))
        room.settle()
        assertTrue(b.n.issue(Change.RemoveMember(b.id)) is IssueResult.Issued)
        room.settle()
        assertEquals(OperationEnding.OUT, b.ended)
        for (p in listOf(a, c, d)) assertEquals(c.id, p.state.leaderOf(g), "$p")
        // C now alerts every leader, not its group: D (a plain member) does not get it, A (the creator) does.
        assertEquals(Channel.LEADERS, c.state.alertChannel(c.id))
        assertEquals(ShareOutcome.SENT, c.n.sendAlert("Besoin d'aide", fix(300)))
        room.settle()
        assertEquals(listOf("Besoin d'aide"), a.alerts.map { it.third.text })
        assertTrue(d.alerts.isEmpty())
        // D, a plain member, alerts its group: C reads it, A (outside the group) does not.
        assertEquals(ShareOutcome.SENT, d.n.sendAlert("Chute", null))
        room.settle()
        assertEquals(listOf("Chute"), c.alerts.map { it.third.text })
        assertEquals(listOf("Besoin d'aide"), a.alerts.map { it.third.text })
    }

    @Test
    fun `a member taken out reads nothing of what follows, and the invitation key is renewed`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val g = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, g))
        a.must(Change.AssignMember(c.id, g))
        room.settle()
        assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.ENSEMBLE, "avant"))
        room.settle()
        assertEquals(listOf("avant"), c.messages.map { it.third })
        val oldEpoch = a.n.epoch
        // What C's phone holds when it is taken out, as a modified phone would keep it.
        val stolenRing =
            KeyRing(a.state.ensembleId, c.keys).also {
                HierarchySnapshot.restoreKeys(it, HierarchySnapshot.encodeKeys(c.n.ring))
            }
        val stolenState = c.state
        a.must(Change.RemoveMember(c.id))
        room.settle()
        assertEquals(OperationEnding.OUT, c.ended)
        assertEquals(oldEpoch + 1, a.n.epoch, "the creator renewed the invitation key")
        assertEquals(a.n.epoch, b.n.epoch, "and B got it")
        val mark = room.written.size
        assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.ENSEMBLE, "après"))
        room.settle()
        assertEquals(listOf("avant", "après"), b.messages.map { it.third })
        // Every frame written since: sealed with the new invitation key, which C never had.
        val after = room.written.drop(mark)
        assertTrue(after.isNotEmpty())
        val oldKey = OperationFrames.frameKey(assertNotNull(c.n.inviteKey(oldEpoch)), a.state.ensembleId, oldEpoch)
        for ((_, frame) in after) {
            assertTrue(OperationFrames.epochOf(frame)!! > oldEpoch)
            assertNull(OperationFrames.open(oldKey, a.state.ensembleId, frame))
        }
        // Even given the share inside, the keys it kept do not open it: the content key was made again without it.
        val share =
            after.firstNotNullOf { (_, f) ->
                OperationFrames.open(
                    OperationFrames.frameKey(a.n.inviteKey(a.n.epoch)!!, a.state.ensembleId, a.n.epoch),
                    a.state.ensembleId,
                    f,
                )
                    ?.takeIf { it.genre == FrameGenre.SHARE }
            }
        val opened = ShareSeal.open(stolenState, stolenRing, share.body)
        assertTrue(opened is OpenResult.Refused)
    }

    @Test
    fun `a member away while the invitation key is renewed gets it back, the creator hands over and leaves`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val d = room.letIn(a, "David")
        val g = a.newGroup("Nord")
        for (p in listOf(b, c, d)) a.must(Change.AssignMember(p.id, g))
        a.must(Change.AddAdmin(b.id))
        room.settle()
        room.goOffline(c)
        a.must(Change.RemoveMember(d.id))
        room.settle()
        assertEquals(2, a.n.epoch)
        assertEquals(1, c.n.epoch)
        room.comeBack(c)
        room.settle()
        assertEquals(2, c.n.epoch, "granted when it spoke under the old key")
        assertEquals(a.n.replica.summary(), c.n.replica.summary())
        assertFalse(d.id in c.state.members)
        // The creator hands the operation over to B and leaves: B is the creator for everyone, and invites.
        a.must(Change.TransferCreator(b.id))
        a.must(Change.RemoveMember(a.id))
        room.settle()
        assertEquals(OperationEnding.OUT, a.ended)
        for (p in listOf(b, c)) assertEquals(b.id, p.state.creator, "$p")
        assertNotNull(b.n.invitation())
        assertNull(c.n.invitation(), "a plain member does not invite")
        assertEquals(3, c.n.epoch, "renewed again once A left")
    }

    @Test
    fun `dissolution ends the operation on every phone, the one away when it comes back`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        room.goOffline(c)
        a.must(Change.Dissolve)
        room.settle()
        assertEquals(OperationEnding.DISSOLVED, a.ended)
        assertEquals(OperationEnding.DISSOLVED, b.ended)
        assertNull(c.ended, "away: it does not know yet")
        assertTrue(a.n.names.cardsFor(a.state).isEmpty() && a.n.ring.batchesFor(a.state).isEmpty())
        // Every phone that holds the dissolution wipes the operation: C learns it only from a phone that still holds
        // it (here, the operations A's phone had, handed over as a phone would: § 17, limits).
        c.n.receive(a.id, sealAs(a, FrameGenre.OPS, HierarchyWire.encodeOps(a.n.replica.ops()), a.id))
        assertEquals(OperationEnding.DISSOLVED, c.ended)
    }

    /** [body] sealed as [genre] with [from]'s invitation key of now, in the name of [sender]. */
    private fun sealAs(
        from: Phone,
        genre: FrameGenre,
        body: ByteArray,
        sender: Long,
    ): ByteArray {
        val epoch = from.n.epoch
        val key = OperationFrames.frameKey(from.n.inviteKey(epoch)!!, from.n.ensembleId, epoch)
        return OperationFrames.seal(key, from.n.ensembleId, epoch, genre, sender, body)
    }

    @Test
    fun `what goes up, down and between leaders reaches only its readers`() {
        val room = Room()
        val a = room.creator("Alice")
        val l = room.letIn(a, "Léa")
        val m = room.letIn(a, "Marc")
        val o = room.letIn(a, "Olga")
        val p = room.letIn(a, "Paul")
        val g1 = a.newGroup("Nord")
        val g2 = a.newGroup("Sud")
        a.must(Change.AssignMember(l.id, g1))
        a.must(Change.AssignMember(m.id, g1))
        a.must(Change.AssignMember(o.id, g2))
        a.must(Change.AssignMember(p.id, g2))
        a.must(Change.SetPost(g1, Post.LEADER, l.id))
        a.must(Change.SetPost(g2, Post.LEADER, o.id))
        room.settle()
        // In its group: M writes to G1; L reads, A (creator, outside the group) and the other group do not.
        assertEquals(ShareOutcome.SENT, m.n.sendMessage(Channel.unit(g1), "dans le groupe"))
        // Up: L writes to the middle thread of G1; A reads, M (plain member) and O (another leader) do not.
        assertEquals(ShareOutcome.SENT, l.n.sendMessage(Channel.up(g1), "vers le commandement"))
        room.settle()
        assertEquals(listOf("dans le groupe"), l.messages.map { it.third })
        assertEquals(listOf("vers le commandement"), a.messages.map { it.third })
        assertTrue(m.messages.isEmpty() && o.messages.isEmpty() && p.messages.isEmpty())
        // Down: A sends a waypoint to G2 only; O and P see it, G1 does not.
        assertEquals(ShareOutcome.SENT, a.n.putObject(ShareKind.WAYPOINT, Channel.down(g2), 11L, byteArrayOf(9)))
        room.settle()
        for (x in listOf(o, p)) assertEquals(listOf(11L), x.n.objects.shown(x.state, x.id).map { it.objectId }, "$x")
        for (x in listOf(l, m)) assertTrue(x.n.objects.shown(x.state, x.id).isEmpty(), "$x")
        // Between leaders: L alerts every leader; O and A read it, M and P do not.
        assertEquals(ShareOutcome.SENT, l.n.sendAlert("Renfort", null))
        room.settle()
        assertEquals(listOf("Renfort"), o.alerts.map { it.third.text })
        assertEquals(listOf("Renfort"), a.alerts.map { it.third.text })
        assertTrue(m.alerts.isEmpty() && p.alerts.isEmpty())
        // A plain member may not write in the leaders' lane at all.
        assertEquals(ShareOutcome.NOT_ALLOWED, m.n.sendMessage(Channel.LEADERS, "non"))
        // The leader of the ensemble alerts everyone placed.
        a.must(Change.SetPost(HierarchyState.ROOT, Post.LEADER, a.id))
        room.settle()
        assertEquals(ShareOutcome.SENT, a.n.sendAlert("Évacuation", fix(400)))
        room.settle()
        for (x in listOf(l, m, o, p)) assertTrue(x.alerts.any { it.third.text == "Évacuation" }, "$x")
    }

    @Test
    fun `a phone that comes back after a cut gets the objects changed and taken away meanwhile`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val g = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, g))
        a.must(Change.AssignMember(c.id, g))
        room.settle()
        assertEquals(ShareOutcome.SENT, a.n.putObject(ShareKind.SKETCH, Channel.ENSEMBLE, 1L, byteArrayOf(1)))
        assertEquals(ShareOutcome.SENT, a.n.putObject(ShareKind.WAYPOINT, Channel.ENSEMBLE, 2L, byteArrayOf(2)))
        room.settle()
        assertEquals(2, b.n.objects.shown(Channel.ENSEMBLE).size)
        room.goOffline(b)
        assertEquals(ShareOutcome.SENT, a.n.putObject(ShareKind.SKETCH, Channel.ENSEMBLE, 1L, byteArrayOf(1, 1)))
        assertEquals(ShareOutcome.SENT, a.n.removeObject(ObjectRef(Channel.ENSEMBLE, a.id, 2L)))
        assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.ENSEMBLE, "pendant la coupure"))
        room.settle()
        room.comeBack(b)
        room.now += OperationNode.ANSWER_EVERY_MS
        room.settle()
        val shown = b.n.objects.shown(Channel.ENSEMBLE)
        assertEquals(listOf(1L), shown.map { it.objectId })
        assertEquals(2L, shown.single().revision)
        assertEquals(listOf("pendant la coupure"), c.messages.map { it.third })
        assertTrue(b.messages.isEmpty(), "old messages are never sent again (S6)")
        assertEquals(c.n.objects.digest(Channel.ENSEMBLE), b.n.objects.digest(Channel.ENSEMBLE))
    }

    @Test
    fun `frames played again, changed, or sealed with another invitation are refused`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val g = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, g))
        room.settle()
        val mark = room.written.size
        assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.ENSEMBLE, "une fois"))
        assertEquals(ShareOutcome.SENT, a.n.sendPosition(fix(500)))
        room.settle()
        val frames = room.written.drop(mark).map { it.second }
        assertEquals(1, b.messages.size)
        // Played again: the message counts once, the position does not go back.
        for (f in frames) b.n.receive(a.id, f)
        assertEquals(1, b.messages.size)
        assertEquals(500L, b.positions[a.id]?.timeSec)
        // Changed on the way: one byte anywhere and the envelope does not open.
        for (f in frames) {
            for (i in listOf(0, 1, 5, 10, 20, f.size - 1)) {
                val changed = f.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
                b.n.receive(a.id, changed)
            }
        }
        assertEquals(1, b.messages.size)
        // Sealed with the invitation key of another operation.
        val other = Room().creator("Zoé", "Autre")
        val foreign =
            OperationFrames.seal(
                OperationFrames.frameKey(other.n.inviteKey(1)!!, a.n.ensembleId, 1),
                a.n.ensembleId,
                1,
                FrameGenre.SHARE,
                a.id,
                OperationFrames.open(
                    OperationFrames.frameKey(a.n.inviteKey(a.n.epoch)!!, a.n.ensembleId, a.n.epoch),
                    a.n.ensembleId,
                    frames.first(),
                )!!.body,
            )
        b.n.receive(a.id, foreign)
        assertEquals(1, b.messages.size)
        // A forged request to come in (signed by another key) is not shown to the creator.
        val intruder = OpAuth.memoryKeys()
        val honest = JoinRequests.make(intruder, a.n.ensembleId, Names.hide("Intrus"), emptyList(), room.now / 1000)
        val forged =
            JoinRequest(
                honest.ensembleId,
                OpAuth.memoryKeys().public,
                honest.name,
                honest.timeSec,
                honest.ephemeral,
                honest.wraps,
                honest.signature,
            )
        a.n.receive(forged.member, sealAs(b, FrameGenre.JOIN, forged.encode(), forged.member))
        assertTrue(a.n.pendingJoins.isEmpty())
        assertTrue(b.n.dropped.isNotEmpty())
    }

    @Test
    fun `the invitation, the request and the grant read back what they wrote`() {
        val keys = OpAuth.memoryKeys()
        val card = Names.hide("Opération Belledonne")
        val invite =
            OperationInvite(1_700_000_000, 42L, ByteArray(16) { it.toByte() }, 3, ByteArray(32) { 7 }, card, server)
        val back = OperationInviteCodec.decodeBytes(OperationInviteCodec.encodeBytes(invite))
        assertEquals(42L, back.ensembleId)
        assertEquals(3, back.epoch)
        assertEquals(card.name, back.ensembleCard.name)
        assertNotNull(OperationInviteCodec.parseOrNull(OperationInviteCodec.toLink(invite)))
        assertNull(OperationInviteCodec.parseOrNull("toolmap://join?d=AAAA"), "an « Entre amis » link is not one")
        assertEquals(
            fr.toolmap.core.p2p.PairingCodec.Staleness.EXPIRED,
            OperationInviteCodec.staleness(
                invite,
                invite.createdAtSec + 8 * 24 * 3600,
            ),
        )
        for (n in 0 until OperationInviteCodec.encodeBytes(invite).size) {
            assertNull(
                OperationInviteCodec.parseOrNull(
                    OperationInviteCodec.LINK_PREFIX +
                        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                            OperationInviteCodec.encodeBytes(invite).copyOf(n),
                        ),
                ),
            )
        }
        val admin = OpAuth.memoryKeys()
        val adminMember = Member(admin.memberId, Names.hide("Admin").hidden, admin.public)
        val request = JoinRequests.make(keys, 42L, Names.hide("Bruno"), listOf(adminMember), 1_700_000_000)
        val read = JoinRequests.decode(request.encode())
        assertTrue(JoinRequests.verify(read))
        assertEquals("Bruno", JoinRequests.openCard(read, admin)?.name)
        assertNull(JoinRequests.openCard(read, OpAuth.memoryKeys()))
        val link =
            OperationLinkState(
                42L,
                ByteArray(16),
                server,
                listOf(EpochKey(ByteArray(32), InviteKeys.announce(admin, 42L, 1, ByteArray(32)))),
                card,
                null,
            )
        val linkBack = OperationLinkState.decode(link.encode())
        assertEquals(link.encode().toList(), linkBack.encode().toList())
    }
}
