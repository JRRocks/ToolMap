package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom
import kotlin.experimental.xor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trace of a dissolved operation (P39.9, ADR-131, `docs/specs/group-hierarchy.md` § 20; the user's answer R1):
 * simulated phones in a simulated room of the coordination server, as in [OperationNodeTest], and phones that keep a
 * trace ([DissolutionAnnouncer]) in the same room. No network, a clock of the test's own.
 */
class DissolutionTraceTest {
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
    private val random = SecureRandom()

    /** Something in the room: a phone with its node, or a phone that keeps a trace. */
    interface Present {
        val id: Long
        val here: Boolean

        fun take(
            from: Long,
            frame: ByteArray,
        )
    }

    inner class Room {
        var now = 1_700_000_000_000L
        val phones = ArrayList<Phone>()
        val keepers = ArrayList<Keeper>()
        private val queue = ArrayDeque<Triple<Long, Present, ByteArray>>()

        fun present(): List<Present> = phones.filter { it.here } + keepers.filter { it.here }

        /** One write reaches every other one present, once. */
        fun write(
            from: Long,
            frame: ByteArray,
        ) {
            for (other in present()) if (other.id != from) queue.addLast(Triple(from, other, frame))
        }

        fun linkTo(from: Long): MeshLink =
            object : MeshLink {
                override val isBroadcastMedium get() = true

                override fun send(
                    frame: ByteArray,
                    onPositionChannel: Boolean,
                ): Boolean {
                    write(from, frame)
                    return true
                }

                override fun close() = Unit
            }

        /** [phone] arrives: it and every phone present get a link to each other; a keeper needs none. */
        fun link(phone: Phone) {
            for (other in phones) {
                if (other === phone || !other.here) continue
                other.n.linkOpened(phone.id, linkTo(other.id))
                phone.n.linkOpened(other.id, linkTo(phone.id))
            }
            for (k in keepers) if (k.here) phone.n.linkOpened(k.id, linkTo(phone.id))
        }

        fun settle(rounds: Int = 200) {
            var quiet = 0
            repeat(rounds) {
                while (queue.isNotEmpty()) {
                    val (from, to, frame) = queue.removeFirst()
                    if (to.here) to.take(from, frame)
                }
                now += 500
                for (p in phones) if (p.here) p.n.tick()
                if (queue.isEmpty()) quiet++ else quiet = 0
                if (quiet > 12) return
            }
        }

        fun creator(
            label: String,
            operation: String = "Opération Vercors",
        ): Phone {
            val phone = Phone(label, this)
            val g = OperationNode.genesis(phone.keys, Names.hide(operation), Names.hide(label), server)
            phone.node = OperationNode(phone.keys, g.replica, g.ring, g.names, g.objects, g.link, phone, { now })
            phones += phone
            phone.n.start()
            link(phone)
            return phone
        }

        fun joiner(
            label: String,
            invite: OperationInvite,
        ): Phone {
            val phone = Phone(label, this)
            phone.node =
                OperationNode(
                    phone.keys,
                    HierarchyReplica(invite.ensembleId, phone.id, phone.keys),
                    KeyRing(invite.ensembleId, phone.keys),
                    NameBook(),
                    SharedObjects(invite.ensembleId),
                    OperationNode.joining(invite, Names.hide(label)),
                    phone,
                    { now },
                )
            phones += phone
            phone.n.start()
            link(phone)
            return phone
        }

        fun letIn(
            admin: Phone,
            label: String,
        ): Phone {
            val phone = joiner(label, assertNotNull(admin.n.invitation()))
            settle()
            assertTrue(admin.n.admit(phone.id) is IssueResult.Issued, "$label let in")
            settle()
            return phone
        }

        fun goOffline(phone: Phone) {
            phone.online = false
            for (other in phones) if (other !== phone && other.here) other.n.linkClosed(phone.id)
        }

        fun comeBack(phone: Phone) {
            phone.online = true
            link(phone)
        }

        /** A phone that keeps [trace] comes into the room: the phones present see it arrive. */
        fun keeper(trace: DissolutionTrace): Keeper {
            val k = Keeper(trace, this)
            keepers += k
            for (p in phones) if (p.here) p.n.linkOpened(k.id, linkTo(p.id))
            return k
        }
    }

    inner class Phone(
        val label: String,
        private val room: Room,
    ) : OperationListener,
        Present {
        val keys = OpAuth.memoryKeys()
        override val id = keys.memberId
        var node: OperationNode? = null
        var online = true
        var ended: OperationEnding? = null
        val messages = ArrayList<String>()

        val n: OperationNode get() = node!!
        val state: HierarchyState get() = n.state
        override val here: Boolean get() = online && node?.ending == null

        override fun take(
            from: Long,
            frame: ByteArray,
        ) = n.receive(from, frame)

        override fun onMessage(
            author: Long,
            channel: Channel,
            message: OperationText,
        ) {
            messages += message.text
        }

        override fun onEnded(ending: OperationEnding) {
            ended = ending
        }

        fun must(change: Change) {
            val r = n.issue(change)
            assertTrue(r is IssueResult.Issued, "$label: $change refused: ${(r as? IssueResult.Refused)?.reason}")
        }

        /** The trace this phone keeps once the operation is dissolved, as the controller makes it. */
        fun trace(): DissolutionTrace =
            DissolutionTrace.of(assertNotNull(n.dissolution, "$label keeps no proof"), n.linkState(), room.now)

        override fun toString() = label
    }

    inner class Keeper(
        trace: DissolutionTrace,
        private val room: Room,
    ) : Present {
        val announcer = DissolutionAnnouncer(trace, { room.now })
        override val id = trace.sender
        override var here = true

        override fun take(
            from: Long,
            frame: ByteArray,
        ) {
            announcer.receive(frame)?.let { room.write(id, it) }
        }
    }

    private fun Phone.newGroup(name: String): Long {
        val card = Names.hide(name)
        val group = random.nextLong().let { if (it == 0L) 1L else it }
        must(Change.CreateGroup(group, card.hidden))
        n.learnAndShare(card)
        return group
    }

    /** [body] sealed as [genre] with [from]'s invitation key of now, in the name of [sender]. */
    private fun sealAs(
        from: Phone,
        genre: FrameGenre,
        body: ByteArray,
        sender: Long,
        epoch: Int = from.n.epoch,
    ): ByteArray {
        val key = OperationFrames.frameKey(from.n.inviteKey(epoch)!!, from.n.ensembleId, epoch)
        return OperationFrames.seal(key, from.n.ensembleId, epoch, genre, sender, body)
    }

    private fun refusals(): List<String> =
        problems.filter { it.where == "operation.dissolution.refused" }.map { it.error.message.orEmpty() }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    // ---- the phone that was away ----------------------------------------------------------------------------------

    @Test
    fun `a phone away when the operation is dissolved learns it from a phone that keeps the trace`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        val g = a.newGroup("Nord")
        a.must(Change.AssignMember(b.id, g))
        a.must(Change.AssignMember(c.id, g))
        room.settle()
        room.goOffline(c)
        a.must(Change.Dissolve)
        room.settle()
        assertEquals(OperationEnding.DISSOLVED, a.ended)
        assertEquals(OperationEnding.DISSOLVED, b.ended)
        assertFalse(a.n.dissolvedByTrace || b.n.dissolvedByTrace, "read from the operations")
        assertNull(c.ended, "away: it does not know")
        val trace = a.trace()
        assertEquals(ProofVerdict.VALID, trace.proof.check(a.n.ensembleId))
        assertEquals(DissolutionTrace.KEPT_MS, trace.expiresAtMs - trace.dissolvedAtMs)

        // A day later, C is back in the room while A's phone keeps the trace there: C learns it, and keeps one too.
        room.now += 24 * 3600_000L
        val keeper = room.keeper(trace)
        room.comeBack(c)
        room.settle()
        assertEquals(OperationEnding.DISSOLVED, c.ended)
        assertTrue(c.n.dissolvedByTrace)
        assertEquals(trace.dissolvedAtMs, c.n.dissolvedAtMs, "the date of the dissolution, not of the news")
        val proof = assertNotNull(c.n.dissolution, "a member when it was dissolved keeps a trace")
        assertEquals(ProofVerdict.VALID, proof.check(c.n.ensembleId))
        assertEquals(1, keeper.announcer.answered)
        assertTrue(c.n.names.cardsFor(c.state).isEmpty() && c.n.ring.batchesFor(c.state).isEmpty(), "all forgotten")
        assertTrue(refusals().isEmpty())
    }

    @Test
    fun `after a handing over, the proof carries it and a phone that missed it accepts it`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        a.must(Change.AddAdmin(b.id))
        room.settle()
        room.goOffline(c)
        a.must(Change.TransferCreator(b.id))
        a.must(Change.RemoveMember(a.id))
        room.settle()
        assertEquals(OperationEnding.OUT, a.ended)
        assertNull(a.n.dissolution, "out before the dissolution: no trace")
        b.must(Change.Dissolve)
        room.settle()
        val trace = b.trace()
        assertEquals(1, trace.proof.handovers.size)
        room.keeper(trace)
        room.comeBack(c)
        room.settle()
        assertEquals(OperationEnding.DISSOLVED, c.ended, "the handing over it missed is in the proof")
        assertTrue(c.n.dissolvedByTrace)
    }

    @Test
    fun `a phone that asks to come in with an invitation given before learns the operation is over, keeps no trace`() {
        val room = Room()
        val a = room.creator("Alice")
        val invite = assertNotNull(a.n.invitation())
        a.must(Change.Dissolve)
        room.settle()
        room.keeper(a.trace())
        val late = room.joiner("Denis", invite)
        room.settle()
        assertEquals(OperationEnding.DISSOLVED, late.ended)
        assertTrue(late.n.dissolvedByTrace)
        assertNull(late.n.dissolution, "never let in: no trace")
    }

    // ---- who keeps nothing ----------------------------------------------------------------------------------------

    @Test
    fun `a member taken out keeps no trace, whether it learns it at once or with the dissolution`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        val c = room.letIn(a, "Chloé")
        a.must(Change.RemoveMember(b.id))
        room.settle()
        assertEquals(OperationEnding.OUT, b.ended)
        assertNull(b.n.dissolution)
        room.goOffline(c)
        a.must(Change.RemoveMember(c.id))
        a.must(Change.Dissolve)
        room.settle()
        // C comes back and gets the operations from a phone that still holds them, under the invitation key it still
        // holds (the creator renewed it as soon as C was taken out): taken out, then dissolved.
        c.online = true
        c.n.receive(a.id, sealAs(a, FrameGenre.OPS, HierarchyWire.encodeOps(a.n.replica.ops()), a.id, c.n.epoch))
        assertEquals(OperationEnding.DISSOLVED, c.ended)
        assertNull(c.n.dissolution, "no longer a member when it was dissolved: no trace")
        assertNotNull(a.n.dissolution)
    }

    // ---- what a trace holds ---------------------------------------------------------------------------------------

    @Test
    fun `a trace holds the proof and the way to the room, no name, no position, no message, no content key`() {
        val room = Room()
        val a = room.creator("Alice", operation = "Opération Belledonne")
        val b = room.letIn(a, "Bruno")
        val g = a.newGroup("Groupe Nord")
        a.must(Change.AssignMember(a.id, g))
        a.must(Change.AssignMember(b.id, g))
        a.must(Change.SetPost(HierarchyState.ROOT, Post.LEADER, a.id))
        room.settle()
        val fix = PeerFix(45.123456, 5.987654, timeSec = 1000)
        assertEquals(ShareOutcome.SENT, a.n.sendPosition(fix))
        assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.unit(g), "Rendez-vous au refuge"))
        val body = "point secret du bivouac".toByteArray()
        assertEquals(ShareOutcome.SENT, a.n.putObject(ShareKind.WAYPOINT, Channel.ENSEMBLE, 9L, body))
        room.settle()
        assertEquals(listOf("Rendez-vous au refuge"), b.messages)
        val secrets = a.n.ring.held.values.flatMap { it.values }.map { it.secret }
        assertTrue(secrets.isNotEmpty())
        val salts = a.n.names.cards.values.map { it.salt }
        a.must(Change.Dissolve)
        room.settle()

        val trace = a.trace()
        val bytes = trace.encode()
        val frame = trace.notice().encode()
        for (where in listOf(bytes, frame)) {
            for (name in listOf("Opération Belledonne", "Alice", "Bruno", "Groupe Nord", "Rendez-vous au refuge")) {
                assertFalse(contains(where, name.toByteArray()), "« $name » in the trace")
            }
            assertFalse(contains(where, body), "an object in the trace")
            val lat = OperationPayloads.position(fix).copyOf(8)
            assertFalse(contains(where, lat), "a position in the trace")
            for (s in secrets) assertFalse(contains(where, s), "a content key in the trace")
            for (s in salts) assertFalse(contains(where, s), "what opens a name in the trace")
        }
        // Nothing but the first operation, the handings over and the dissolution, and the invitation keys.
        val ops = HierarchyWire.decodeOps(trace.proof.encode())
        assertTrue(ops.first().change is Change.CreateEnsemble && ops.last().change == Change.Dissolve && ops.size == 2)
        assertEquals(a.n.linkState().epochs.map { it.epoch }, trace.keys.map { it.epoch })
        assertTrue(trace.sender != a.id && trace.sender !in a.state.members, "no member identifier in the trace")
        assertContentEquals(a.n.room, trace.room)
        // It reads back as written.
        val back = DissolutionTrace.decode(bytes)
        assertContentEquals(bytes, back.encode())
        assertEquals(ProofVerdict.VALID, back.proof.check(a.n.ensembleId))
    }

    // ---- false proofs ---------------------------------------------------------------------------------------------

    @Test
    fun `a proof played again in another operation, or forged, is refused and said`() {
        // X: dissolved, its proof valid.
        val roomX = Room()
        val x = roomX.creator("Xavier", operation = "Opération X")
        x.must(Change.Dissolve)
        roomX.settle()
        val proofX = x.trace().proof

        // Y: alive, with a creator, an administrator and a member.
        val room = Room()
        val a = room.creator("Alice", operation = "Opération Y")
        val b = room.letIn(a, "Bruno")
        val d = room.letIn(a, "Denis")
        a.must(Change.AddAdmin(b.id))
        room.settle()
        val ensemble = a.n.ensembleId
        val genesis = a.n.replica.ops().first { it.author == a.id && it.seq == 1 }

        fun deliver(proof: DissolutionProof) =
            d.n.receive(b.id, sealAs(a, FrameGenre.DISSOLUTION, DissolutionNotice(room.now, proof).encode(), b.id))

        fun signed(
            keys: PhoneKeys,
            seq: Int,
        ) = OpAuth.sign(Op(ensemble, keys.memberId, seq, 1_000, Change.Dissolve), keys)

        fun nextSeq(p: Phone) = p.n.replica.ops().count { it.author == p.id } + 1

        // Played again: X's proof, sealed with Y's invitation key by a phone of both.
        deliver(proofX)
        assertNull(d.ended)
        assertEquals(listOf(ProofVerdict.WRONG_ENSEMBLE.name), refusals())

        // Forged: the administrator signs a dissolution; then the creator's own, one byte of its signature changed.
        deliver(DissolutionProof(genesis, emptyList(), signed(b.keys, nextSeq(b))))
        val real = signed(a.keys, nextSeq(a))
        val sig = real.proof!!.signature.copyOf().also { it[7] = it[7] xor 1 }
        val flipped = Op(ensemble, a.id, real.seq, real.clock, real.change, OpProof(real.proof!!.publicKey, sig))
        deliver(DissolutionProof(genesis, emptyList(), flipped))
        assertNull(d.ended)
        assertEquals(
            listOf(ProofVerdict.WRONG_ENSEMBLE, ProofVerdict.NOT_BY_THE_CREATOR, ProofVerdict.FORGED).map { it.name },
            refusals(),
        )

        // The creator hands over and stays: its own dissolution, signed after, leaves the handing over out.
        a.must(Change.TransferCreator(b.id))
        room.settle()
        assertEquals(b.id, d.state.creator)
        deliver(DissolutionProof(genesis, emptyList(), signed(a.keys, nextSeq(a))))
        assertNull(d.ended, "D saw the handing over")
        assertEquals(ProofVerdict.INCONSISTENT.name, refusals().last())

        // A frame changed on the way does not even open.
        val good = sealAs(a, FrameGenre.DISSOLUTION, DissolutionNotice(room.now, proofX).encode(), b.id)
        good[good.size - 3] = good[good.size - 3] xor 1
        val before = d.n.dropped["frame.sealed"] ?: 0
        d.n.receive(b.id, good)
        assertEquals(before + 1, d.n.dropped["frame.sealed"])
        assertNull(d.ended)
        assertTrue(d.n.dropped.keys.any { it.startsWith("dissolution.") })
    }

    // ---- the phone that keeps the trace ---------------------------------------------------------------------------

    @Test
    fun `a trace answers once a minute, never to itself, another trace or what it cannot open`() {
        val room = Room()
        val a = room.creator("Alice")
        val b = room.letIn(a, "Bruno")
        room.goOffline(b)
        a.must(Change.Dissolve)
        room.settle()
        val trace = a.trace()
        var now = 1_000_000L
        val announcer = DissolutionAnnouncer(trace, { now })
        val summary = sealAs(b, FrameGenre.SUMMARY, ByteArray(0), b.id)
        val answer = assertNotNull(announcer.receive(summary))
        val key = OperationFrames.frameKey(trace.keyOf(b.n.epoch)!!, trace.ensembleId, b.n.epoch)
        assertEquals(FrameGenre.DISSOLUTION, OperationFrames.open(key, trace.ensembleId, answer)?.genre)
        now += 30_000
        assertNull(announcer.receive(summary), "once a minute")
        now += 31_000
        assertNotNull(announcer.receive(summary))
        assertNull(announcer.receive(sealAs(b, FrameGenre.SUMMARY, ByteArray(0), trace.sender)), "its own")
        now += 61_000
        assertNull(announcer.receive(answer), "another trace's answer")
        val other = Room().creator("Zoé")
        assertNull(announcer.receive(sealAs(other, FrameGenre.SUMMARY, ByteArray(0), other.id)), "another room")
        assertEquals(2, announcer.answered)
    }

    // ---- format, size and time ------------------------------------------------------------------------------------

    @Test
    fun `a proof of sixteen handings over fits one frame, and its codecs refuse anything else`() {
        val chain = List(DissolutionProof.MAX_HANDOVERS + 1) { OpAuth.memoryKeys() }
        val first = chain.first()
        val creation =
            Change.CreateEnsemble(42L, Names.hide("Opération").hidden, Names.hide("Alice").hidden, first.public)
        val ensemble = OpAuth.ensembleId(first.memberId, creation)
        val genesis = OpAuth.sign(Op(ensemble, first.memberId, 1, 1, creation), first)
        val handovers =
            (0 until DissolutionProof.MAX_HANDOVERS).map { i ->
                val handover = Change.TransferCreator(chain[i + 1].memberId)
                OpAuth.sign(Op(ensemble, chain[i].memberId, 2, 10L + i, handover), chain[i])
            }
        val last = chain.last()
        val dissolution = OpAuth.sign(Op(ensemble, last.memberId, 2, 100, Change.Dissolve), last)
        val proof = DissolutionProof(genesis, handovers, dissolution)
        assertEquals(ProofVerdict.VALID, proof.check(ensemble))
        val notice = DissolutionNotice(5L, proof).encode()
        assertTrue(notice.size <= OperationFrames.MAX_BODY, "${notice.size} bytes")
        val back = DissolutionNotice.decode(notice)
        assertEquals(5L, back.dissolvedAtMs)
        assertContentEquals(proof.encode(), back.proof.encode())
        // Out of order, cut, or with something else inside: refused.
        val shuffled = DissolutionProof(genesis, handovers.reversed(), proof.dissolution)
        assertEquals(ProofVerdict.NOT_BY_THE_CREATOR, shuffled.check(ensemble))
        assertFailsWith<WireException> { DissolutionNotice.decode(notice.copyOf(notice.size - 1)) }
        assertFailsWith<WireException> { DissolutionProof.decode(HierarchyWire.encodeOps(listOf(genesis))) }
        assertFailsWith<WireException> {
            DissolutionProof.decode(HierarchyWire.encodeOps(listOf(genesis, proof.dissolution, proof.dissolution)))
        }
        assertFailsWith<WireException> { DissolutionTrace.decode(ByteArray(40) { 1 }) }
        assertEquals(ProofVerdict.WRONG_ENSEMBLE, proof.check(ensemble + 1))
    }

    @Test
    fun `a trace is kept a week from the dissolution, not from the news`() {
        val room = Room()
        val a = room.creator("Alice")
        a.must(Change.Dissolve)
        room.settle()
        val day = 24 * 3600_000L
        val trace = a.trace()
        val at = trace.dissolvedAtMs
        assertFalse(trace.isExpired(at + 6 * day))
        assertTrue(trace.isExpired(at + 7 * day))
        assertTrue(trace.isExpired(at + 8 * day))
        assertTrue(trace.isExpired(at - 8 * day), "a clock a week behind: not kept forever")
    }
}
