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

        /** Phones cut off from the others, together (a second room): what they write reaches only each other. */
        val apart = HashSet<Phone>()

        private fun together(
            a: Phone,
            b: Phone,
        ) = (a in apart) == (b in apart)

        fun link(phone: Phone) {
            for (other in present()) {
                if (other === phone || !together(other, phone)) continue
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
                    deliver(from, frame)
                    return true
                }

                override fun close() = Unit
            }

        /** [frame] written by [from] (or in its name), to every phone that hears it. */
        fun deliver(
            from: Phone,
            frame: ByteArray,
        ) {
            for (other in present()) {
                if (other !== from &&
                    together(from, other)
                ) {
                    queue.addLast(Triple(from, other, frame))
                }
            }
        }

        /** [frame] written in [from]'s name by the test: what a phone of an older version, or an attacker, sends. */
        fun inject(
            from: Phone,
            frame: ByteArray,
        ) {
            written += from.id to frame
            deliver(from, frame)
        }

        /** [cut] leave the room together: they hear each other, nobody else. */
        fun split(vararg cut: Phone) {
            for (p in cut) {
                for (o in present()) {
                    if (o in cut) continue
                    o.node!!.linkClosed(p.id)
                    p.node!!.linkClosed(o.id)
                }
            }
            apart += cut
        }

        /** The phones apart come back to the room. */
        fun mend() {
            val back = apart.toList()
            apart.clear()
            for (p in back) {
                for (o in present()) {
                    if (o in back) continue
                    o.node!!.linkOpened(p.id, linkTo(o, p))
                    p.node!!.linkOpened(o.id, linkTo(p, o))
                }
            }
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
            cutOff: Boolean = false,
        ): Phone {
            val phone = Phone(name)
            if (cutOff) apart += phone
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

        /** Called when the node asks for everything to be written down before a change goes out (M3). */
        var onBeforeSend: (() -> Unit)? = null

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

        override fun beforeSend() {
            onBeforeSend?.invoke()
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
        assertEquals(OperationEnding.LEFT, b.ended, "its departure got by the others (M4 A)")
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
        assertEquals(OperationEnding.LEFT, a.ended, "its departure got by the others (M4 A)")
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

    @Test
    fun `a change and a new invitation key are written down before they go out (M3)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val seen = ArrayList<Int>()
        a.onBeforeSend = { seen += room.written.size }
        val before = room.written.size
        a.must(Change.CreateGroup(0x55L, Names.hide("Nord").hidden))
        assertEquals(listOf(before), seen, "written down once, before the operation went out")
        assertTrue(room.written.size > before, "then sent")
        seen.clear()
        val renewing = room.written.size
        assertTrue(a.n.renewInvitation())
        assertEquals(listOf(renewing), seen, "the new key too, before its grant goes out")
        assertTrue(room.written.size > renewing)
        // Its own departure too (M4 A): kept until another phone got it.
        val left = ArrayList<Int>()
        b.onBeforeSend = { left += room.written.size }
        val departing = room.written.size
        b.must(Change.RemoveMember(b.id))
        assertEquals(listOf(departing), left, "its own departure, written down before it goes")
        assertTrue(b.n.leaving)
        room.settle()
        assertEquals(OperationEnding.LEFT, b.ended)
    }

    @Test
    fun `a burst of summaries of one phone is answered at once, then once more, the latest (M7)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val g = a.newGroup("Groupe")
        repeat(12) { a.must(Change.SetGroupColor(g, 0x101010 * (it + 1))) }
        room.settle()
        val ensemble = a.n.ensembleId
        val epoch = a.n.epoch
        assertEquals(epoch, b.n.epoch)
        val key = OperationFrames.frameKey(assertNotNull(a.n.inviteKey(epoch)), ensemble, epoch)

        fun opened(
            from: Long,
            since: Int,
        ) = room.written.drop(since).filter {
            it.first == from
        }.mapNotNull { OperationFrames.open(key, ensemble, it.second) }

        // A summary B wrote, then two minutes of its summaries handed over in one go by the server, as after a
        // reconnection (P39.12, § 23): each says B holds one more of A's operations.
        val real = OperationSummary.decode(opened(b.id, 0).last { it.genre == FrameGenre.SUMMARY }.body)
        val authored = a.n.replica.summary().counts.getValue(a.id)
        assertTrue(authored > 11, "$authored operations of A")
        val burst =
            (1..10).map { k ->
                val counts = real.summary.counts + (a.id to k)
                val body =
                    OperationSummary(
                        Summary(ensemble, counts, 0L),
                        real.keysDigest,
                        0,
                        real.objects,
                        a.id,
                    ).encode()
                OperationFrames.seal(key, ensemble, epoch, FrameGenre.SUMMARY, b.id, body)
            }

        fun opsOfA(since: Int) =
            opened(a.id, since).filter { it.genre == FrameGenre.OPS }.flatMap { HierarchyWire.decodeOps(it.body) }

        val mark = room.written.size
        for (frame in burst) a.n.receive(b.id, frame)
        val first = opsOfA(mark)
        assertEquals((2..authored).toList(), first.filter { it.author == a.id }.map { it.seq }, "the first, at once")
        val later = room.written.size
        room.now += 1_000
        a.n.tick()
        assertTrue(opsOfA(later).isEmpty(), "nothing more within two seconds")
        room.now += 1_000
        a.n.tick()
        assertEquals((11..authored).toList(), opsOfA(later).filter { it.author == a.id }.map { it.seq }, "the latest")
        room.now += 5_000
        a.n.tick()
        assertEquals(authored - 10, opsOfA(later).size, "then nothing: the other eight were never answered")
    }

    // ---- P39.14.2: M6, M2, M1, M5 (the user's answers of 2026-11-16, ADR-140) ------------------------------------

    @Test
    fun `only the creator renews the invitation key, at once, or when it is back (M6)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val d = room.letIn(a, "David")
        a.must(Change.AddAdmin(b.id))
        room.settle()
        assertFalse(b.n.renewInvitation(), "an administrator no longer renews it")
        assertEquals(1, b.n.epoch)
        // The creator away, a member leaves: nobody renews it in its stead.
        room.goOffline(a)
        d.must(Change.RemoveMember(d.id))
        room.settle()
        assertEquals(OperationEnding.LEFT, d.ended, "got by B and C (M4 A)")
        for (p in listOf(b, c)) assertEquals(1, p.n.epoch, "$p: still the key D held")
        // Back, the creator learns the departure and renews it, once.
        room.comeBack(a)
        room.settle()
        for (p in listOf(a, b, c)) assertEquals(2, p.n.epoch, "$p")
        assertTrue(a.n.inviteKey(2).contentEquals(b.n.inviteKey(2)))
        assertTrue(a.n.inviteKey(2).contentEquals(c.n.inviteKey(2)))
        assertNull(d.n.inviteKey(2), "D does not hold the key of now")
    }

    @Test
    fun `two keys of one epoch, one from a phone of an older version, still end on one key (ADR-132)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        a.must(Change.AddAdmin(b.id))
        a.must(Change.AssignMember(c.id, a.newGroup("Nord")))
        room.settle()
        // B renews as a phone of 0.4.276 would, in the same moment as the creator.
        val key = ByteArray(OperationInvite.KEY_BYTES).also(random::nextBytes)
        val announcement = InviteKeys.announce(b.keys, a.n.ensembleId, 2, key)
        val grant = InviteKeys.grant(b.keys, announcement, key, b.state.members.values.filter { it.id != b.id })
        assertTrue(a.n.renewInvitation())
        val creators = a.n.linkState().epochs.last().announcement.keyId
        val frameKey = OperationFrames.frameKey(assertNotNull(b.n.inviteKey(1)), a.n.ensembleId, 1)
        room.inject(
            b,
            OperationFrames.seal(
                frameKey,
                a.n.ensembleId,
                1,
                FrameGenre.INVITE_KEY,
                b.id,
                grant.encode(),
                false,
                random,
            ),
        )
        room.settle()
        val ids =
            listOf(a, b, c).map { p ->
                assertEquals(2, p.n.epoch, "$p")
                InviteKeys.keyId(a.n.ensembleId, 2, assertNotNull(p.n.inviteKey(2)))
            }
        assertEquals(1, ids.toSet().size, "one key of epoch 2 everywhere")
        val smaller =
            if (java.lang.Long.compareUnsigned(announcement.keyId, creators) <
                0
            ) {
                announcement.keyId
            } else {
                creators
            }
        assertEquals(smaller, ids.first(), "the one with the smaller identifier")
        assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.ENSEMBLE, "une seule clé"))
        room.settle()
        for (p in listOf(b, c)) assertTrue(p.messages.any { it.third == "une seule clé" }, "$p hears the creator")
    }

    @Test
    fun `a member away for many renewals comes back by itself (M2, case B)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val g = a.newGroup("Nord")
        for (p in listOf(b, c)) a.must(Change.AssignMember(p.id, g))
        room.settle()
        room.goOffline(c)
        // Six members come and go while C is away: six renewals (four keys were kept until P39.14).
        repeat(6) { i ->
            val passer = room.letIn(a, "Passant $i")
            passer.must(Change.RemoveMember(passer.id))
            room.settle()
        }
        assertEquals(7, a.n.epoch)
        assertEquals(1, c.n.epoch)
        room.comeBack(c)
        room.settle()
        assertEquals(a.n.epoch, c.n.epoch, "C holds the key of now")
        assertEquals(a.n.replica.summary(), c.n.replica.summary(), "and what it missed")
        assertEquals(ShareOutcome.SENT, c.n.sendMessage(Channel.unit(g), "de retour"))
        assertEquals(ShareOutcome.SENT, b.n.sendMessage(Channel.unit(g), "bienvenue"))
        room.settle()
        assertTrue(b.messages.any { it.third == "de retour" }, "B hears C")
        assertTrue(c.messages.any { it.third == "bienvenue" }, "C hears B")
    }

    @Test
    fun `a member away while the operation was handed over takes the key of the new creator (M2, case C)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        a.must(Change.AddAdmin(b.id))
        room.settle()
        room.goOffline(c)
        // A hands over to B and leaves; B, creator in an epoch C does not hold, renews the invitation.
        a.must(Change.TransferCreator(b.id))
        a.must(Change.RemoveMember(a.id))
        room.settle()
        assertEquals(2, b.n.epoch)
        assertEquals(b.id, b.n.linkState().epochs.last().announcement.announcer)
        room.comeBack(c)
        room.settle()
        assertEquals(b.id, c.state.creator, "C learnt the handing over, under the key it held")
        assertEquals(2, c.n.epoch, "then took the key B announced")
        assertTrue(b.n.inviteKey(2).contentEquals(c.n.inviteKey(2)))
        // The key alone first (ADR-142): C cannot take it yet, it does not know B as the creator. It asks again under
        // its own key and gets the operations, then the key.
        val refused = problems.count { it.where == "operation.grant.refused" }
        assertTrue(refused <= 2, "refused once or twice, not more: $problems")
    }

    @Test
    fun `a member taken out while it was away is told so under the key it holds, and nothing else (M1, case A)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val x = room.letIn(a, "Xavier")
        a.must(Change.AddAdmin(x.id))
        room.settle()
        room.goOffline(x)
        val g = a.newGroup("Sud")
        a.must(Change.RemoveMember(x.id))
        room.settle()
        assertEquals(2, a.n.epoch)
        room.comeBack(x)
        room.settle()
        assertEquals(OperationEnding.OUT, x.ended, "X learns it was taken out")
        assertTrue(g !in x.state.groups, "and nothing of what happened meanwhile")
        assertEquals(1, x.n.epoch)
        for (p in listOf(a, b)) assertTrue(x.id !in p.state.members && p.ended == null, "$p")
    }

    @Test
    fun `who was let in by an administrator taken out before learns it too (M1, case A)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val x = room.letIn(a, "Xavier")
        a.must(Change.AddAdmin(x.id))
        room.settle()
        // X is cut off; A takes it out, and the invitation is renewed without it.
        room.split(x)
        a.must(Change.RemoveMember(x.id))
        room.settle()
        assertEquals(2, a.n.epoch)
        // Not knowing it, X makes groups and lets I in with its invitation of epoch 1, both cut off from the room.
        x.newGroup("Est")
        x.newGroup("Ouest")
        val invite = assertNotNull(OperationInviteCodec.parseOrNull(OperationInviteCodec.toLink(x.n.invitation()!!)))
        val i = room.joiner("Iris", invite, cutOff = true)
        room.settle()
        assertTrue(x.n.admit(i.id) is IssueResult.Issued)
        room.settle()
        assertTrue(i.id in i.state.members, "I believes it is in")
        room.mend()
        room.settle()
        assertEquals(OperationEnding.OUT, x.ended, "X learns it was taken out")
        assertEquals(OperationEnding.OUT, i.ended, "I learns that whoever let it in was out before")
        for (p in listOf(a, b)) {
            assertTrue(i.id !in p.state.members && x.id !in p.state.members, "$p")
            assertNull(p.ended, "$p")
        }
    }

    @Test
    fun `the key of now handed to a phone gone makes the creator renew it once more (M5, case G)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val d = room.letIn(a, "David")
        room.settle()
        val gone = a.state.members.getValue(d.id)
        d.must(Change.RemoveMember(d.id))
        room.settle()
        assertEquals(2, a.n.epoch)
        // B, as a phone that had not learnt the departure yet, hands D the key of now under the key D holds.
        val now = b.n.linkState().epochs.last()
        val grant = InviteKeys.grant(b.keys, now.announcement, now.key, listOf(gone))
        val frameKey = OperationFrames.frameKey(assertNotNull(b.n.inviteKey(1)), a.n.ensembleId, 1)
        val frame =
            OperationFrames.seal(
                frameKey,
                a.n.ensembleId,
                1,
                FrameGenre.INVITE_KEY,
                b.id,
                grant.encode(),
                false,
                random,
            )
        room.inject(b, frame)
        room.settle()
        for (p in listOf(a, b, c)) assertEquals(3, p.n.epoch, "$p: renewed once more, without D")
        // Played again: the key of epoch 2 is no longer the one of now, nothing more.
        room.inject(b, frame)
        room.settle()
        assertEquals(3, a.n.epoch)
    }

    // ---- ADR-142: what the long series found in delivery 2 (seeds 0x50ac001c, 0x50ac0023) ----------------------

    @Test
    fun `back as the creator after a renewal it missed, a phone renews from the key of now (ADR-142)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val d = room.letIn(a, "Denis")
        a.must(Change.AddAdmin(b.id))
        room.settle()
        room.goOffline(b)
        // While B is away: D is taken out (A renews, epoch 2), then A hands the operation over to B and leaves.
        a.must(Change.RemoveMember(d.id))
        room.settle()
        assertEquals(2, a.n.epoch)
        a.must(Change.TransferCreator(b.id))
        a.must(Change.RemoveMember(a.id))
        room.settle()
        assertEquals(2, c.n.epoch, "nobody renews while the creator is away")
        room.comeBack(b)
        room.settle()
        assertEquals(b.id, b.state.creator)
        assertEquals(3, b.n.epoch, "B took the key of epoch 2 first, then renewed from it")
        assertEquals(3, c.n.epoch)
        for (e in 2..3) assertTrue(b.n.inviteKey(e).contentEquals(c.n.inviteKey(e)), "one key for epoch $e")
    }

    @Test
    fun `back after a renewal, a member gets the objects of its channels, the key handed by one who waits`() {
        val room = Room()
        val a = room.creator("Alice")
        val w = room.letIn(a, "Wanda")
        val m = room.letIn(a, "Marc")
        val r = room.letIn(a, "Rémi")
        val x = room.letIn(a, "Xavier")
        val nord = a.newGroup("Nord")
        for (p in listOf(m, r, x)) a.must(Change.AssignMember(p.id, nord))
        room.settle()
        val unit = Channel.unit(nord)
        assertEquals(ShareOutcome.SENT, r.n.putObject(ShareKind.WAYPOINT, unit, 1L, byteArrayOf(1)))
        room.settle()
        assertTrue(a.state.isWaiting(w.id))
        room.goOffline(m)
        a.must(Change.RemoveMember(x.id))
        room.settle()
        assertEquals(ShareOutcome.SENT, r.n.putObject(ShareKind.WAYPOINT, unit, 2L, byteArrayOf(2)))
        room.settle()
        assertEquals(2, r.n.epoch)
        // A is gone for good: M comes back to W, who waits (it hands the key first), and R.
        room.goOffline(a)
        room.comeBack(m)
        room.settle()
        assertEquals(2, m.n.epoch)
        assertEquals(listOf(1L, 2L), m.n.objects.shown(unit).map { it.objectId }.sorted())
    }

    /**
     * A known limit, verified so it is not forgotten (seed 0x50ac002c of the long series; spec § 27, P39.14.3): the
     * creator, away, takes the role of administrator from B; B, not knowing it yet, puts a sketch down to its group.
     * The phones that received it keep it, but none of them may share down to the group any more: the creator never
     * gets it. Already so in deliveries 1 and 2; what should happen is the user's to decide.
     */
    @Test
    fun `known limit - a sketch put down by an administrator demoted meanwhile stays with who got it`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val m = room.letIn(a, "Marc")
        val nord = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, nord))
        a.must(Change.AssignMember(m.id, nord))
        a.must(Change.AddAdmin(b.id))
        room.settle()
        room.goOffline(a)
        a.must(Change.RemoveAdmin(b.id))
        val down = Channel.down(nord)
        assertEquals(ShareOutcome.SENT, b.n.putObject(ShareKind.SKETCH, down, 21L, byteArrayOf(1)))
        room.settle()
        room.comeBack(a)
        room.settle()
        room.now += 600_000
        room.settle()
        for (x in listOf(a, b, m)) assertTrue(down in x.state.channelsReadBy(x.id), "$x reads $down")
        assertTrue(b.id !in a.state.admins, "B is no longer an administrator")
        assertEquals(listOf(21L), m.n.objects.shown(down).map { it.objectId })
        assertEquals(listOf(21L), b.n.objects.shown(down).map { it.objectId })
        assertTrue(a.n.objects.shown(down).isEmpty(), "the creator never gets it")
    }

    /**
     * A group renamed by an administrator taken out before its card reached anyone (seed 0x50ac003e of the long
     * series, P39.14.6): the rename stands on every phone, so its name must too. A card proves itself (its commitment
     * is in an operation signed by who could write it), so it is taken from who sealed it, even out since.
     */
    @Test
    fun `a group renamed by an administrator taken out meanwhile keeps its name`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val m = room.letIn(a, "Marc")
        val nord = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, nord))
        a.must(Change.AssignMember(m.id, nord))
        a.must(Change.AddAdmin(b.id))
        room.settle()
        room.goOffline(m)
        val card = Names.hide("Ouest")
        b.must(Change.RenameGroup(nord, card.hidden))
        // A gets the rename alone, then takes B out before the card that follows it: the rename comes first.
        val (from, to, frame) = room.queue.removeFirst()
        assertEquals(b to a, from to to)
        a.n.receive(b.id, frame)
        assertEquals(card.hidden, a.state.groups.getValue(nord).name)
        b.n.learnAndShare(card)
        a.must(Change.RemoveMember(b.id))
        room.settle()
        room.comeBack(m)
        room.settle()
        room.now += 600_000
        room.settle()
        assertTrue(b.id !in a.state.members, "B is out")
        for (x in listOf(a, m)) {
            assertEquals(card.hidden, x.state.groups.getValue(nord).name, "$x: the rename stands")
            assertEquals("Ouest", x.n.names.nameOf(x.state.groups.getValue(nord).name), "$x knows the new name")
        }
    }

    // ---- P39.14.3: L2 (the user's answer of 2026-11-16, cases E and H of § 21) -----------------------------------

    /** A phone that asks to come in with [creator]'s invitation; not let in. */
    private fun Room.asks(
        creator: Phone,
        name: String,
    ): Phone {
        val invite =
            assertNotNull(OperationInviteCodec.parseOrNull(OperationInviteCodec.toLink(creator.n.invitation()!!)))
        val phone = joiner(name, invite)
        settle()
        assertTrue(creator.n.pendingJoins.any { it.member == phone.id && it.card != null }, "$name asks to come in")
        return phone
    }

    @Test
    fun `an administrator named after the phone of who waits is lost learns its name from the others (L2, case H)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        a.must(Change.AssignMember(b.id, a.newGroup("Nord")))
        a.must(Change.AssignMember(c.id, a.newGroup("Sud")))
        val w = room.letIn(a, "Wanda")
        room.settle()
        assertTrue(a.state.isWaiting(w.id))
        assertEquals("Wanda", a.nameOf(w.id))
        assertNull(c.nameOf(w.id), "a plain member does not know the name of who waits")
        // Its phone is lost for good; C is named administrator afterwards.
        room.goOffline(w)
        a.must(Change.AddAdmin(c.id))
        room.settle()
        assertEquals("Wanda", c.nameOf(w.id), "the creator told it in the lane of who waits")
        assertNull(b.nameOf(w.id), "nobody else learns it")
    }

    @Test
    fun `an administrator named after a request, its phone no longer hearing, learns it and its name (L2, case E)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        a.must(Change.AssignMember(b.id, a.newGroup("Nord")))
        room.settle()
        val r = room.asks(a, "Romain")
        assertTrue(b.n.pendingJoins.isEmpty(), "a plain member holds no request")
        // The phone that asks hears nothing any more; B is named administrator.
        room.goOffline(r)
        a.must(Change.AddAdmin(b.id))
        room.settle()
        val held = assertNotNull(b.n.pendingJoins.firstOrNull { it.member == r.id }, "B holds the request")
        assertEquals("Romain", held.card?.name, "and its name")
        // B lets it in; back, it learns it is in, with its name.
        assertTrue(b.n.admit(r.id) is IssueResult.Issued)
        room.settle()
        room.comeBack(r)
        room.settle()
        assertTrue(r.id in r.state.members, "let in by B")
        assertEquals("Romain", a.nameOf(r.id))
    }

    @Test
    fun `a card wrapped again opens only for its recipient, and only as the name the request signed (L2)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val r = room.asks(a, "Romain")
        val pending = a.n.pendingJoins.single { it.member == r.id }
        val bMember = a.state.members.getValue(b.id)
        val relayed = JoinRequests.relay(pending.request, assertNotNull(pending.card), listOf(bMember))
        val back = JoinRequests.decodeRelayed(relayed.encode())
        assertEquals("Romain", JoinRequests.openRelayed(back, b.keys)?.name)
        assertNull(JoinRequests.openRelayed(back, a.keys), "not wrapped to A")
        // Another card wrapped in its place: not the name the request signed.
        val other = JoinRequests.relay(pending.request, Names.hide("Robert"), listOf(bMember))
        assertNull(JoinRequests.openRelayed(JoinRequests.decodeRelayed(other.encode()), b.keys))
        // Cut or with a byte too many: refused.
        val bytes = relayed.encode()
        kotlin.test.assertFailsWith<fr.toolmap.core.p2p.WireException> {
            JoinRequests.decodeRelayed(
                bytes.copyOf(bytes.size - 1),
            )
        }
        kotlin.test.assertFailsWith<fr.toolmap.core.p2p.WireException> { JoinRequests.decodeRelayed(bytes + 0) }
    }

    @Test
    fun `a phone that leaves out of network keeps its departure until another phone got it (M4 A)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val g = a.newGroup("Nord")
        for (p in listOf(b, c)) a.must(Change.AssignMember(p.id, g))
        room.settle()
        room.goOffline(b)
        b.must(Change.RemoveMember(b.id))
        room.settle()
        assertNull(b.ended, "nobody got the departure yet")
        assertTrue(b.n.leaving)
        assertNotNull(b.n.linkState().leftAtMs, "kept with the link, for a restart")
        assertEquals(ShareOutcome.NOT_ALLOWED, b.n.sendPosition(PeerFix(45.0, 6.0, timeSec = 1)))
        assertTrue(b.id in a.state.members && b.id in c.state.members, "for the others it is still there")
        // Back in the room: the others get the departure, and say so.
        room.comeBack(b)
        room.settle()
        assertEquals(OperationEnding.LEFT, b.ended)
        for (p in listOf(a, c)) assertFalse(b.id in p.state.members, "$p")
    }

    @Test
    fun `a week without any other phone, the departure is given up (M4 A)`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        room.settle()
        room.goOffline(b)
        b.must(Change.RemoveMember(b.id))
        room.now += OperationNode.LEAVING_MS - 1_000
        b.n.tick()
        assertNull(b.ended)
        room.now += 1_000
        b.n.tick()
        assertEquals(OperationEnding.LEFT_UNHEARD, b.ended)
        assertTrue(b.id in a.state.members, "the others never learnt it")
    }
}
