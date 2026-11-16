package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * The test bench of the attacks against the « Opération » mode (P39.11, `docs/specs/group-hierarchy.md` § 22): a
 * simulated room of the coordination server whose server may lie, simulated phones, and what a modified phone does
 * that the application never would (sign a change it may not make, seal where it may not share, speak in the name of
 * another member). No network, a clock of the test's own; the choices of the tests come from a seeded generator, the
 * keys from the platform.
 */

/** What the [Problems] sink received during a test: the expected failures counted by place, the bugs kept whole. */
internal class ProblemLog : Problems.Sink {
    val expected = LinkedHashMap<String, Int>()
    val bugs = ArrayList<Problem>()

    @Synchronized
    override fun take(problem: Problem) {
        if (problem.expected) expected[problem.where] = (expected[problem.where] ?: 0) + 1 else bugs += problem
    }

    fun count(where: String): Int = synchronized(this) { expected[where] ?: 0 }

    /** Installs this log as the sink for [block], then checks that nothing was a bug. */
    fun <T> around(block: () -> T): T {
        val previous = Problems.sink
        Problems.sink = this
        try {
            return block()
        } finally {
            Problems.sink = previous
            assertTrue(bugs.isEmpty(), "bugs reported: ${bugs.map { it.where + ": " + it.error }}")
        }
    }
}

/** One phone of the room: an honest one, or a modified one that the attack tests drive by hand. */
internal class AttackPhone(
    val label: String,
    val room: AttackRoom,
) : OperationListener {
    val keys: PhoneKeys = OpAuth.memoryKeys()
    val id: Long = keys.memberId
    var node: OperationNode? = null
    var online = true
    val positions = HashMap<Long, PeerFix>()
    val messages = ArrayList<Triple<Long, Channel, String>>()
    val alerts = ArrayList<Triple<Long, Channel, OperationText>>()
    var ended: OperationEnding? = null
    var changes = 0

    val n: OperationNode get() = assertNotNull(node, "$label has no node")
    val state: HierarchyState get() = n.state

    override fun onChanged() {
        changes++
    }

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

    /** [change] asked for the honest way: it must be written. */
    fun must(change: Change) {
        val r = n.issue(change)
        assertTrue(r is IssueResult.Issued, "$label: $change refused: ${(r as? IssueResult.Refused)?.reason}")
    }

    fun newGroup(name: String): Long {
        val card = Names.hide(name)
        val group = room.nonZero()
        must(Change.CreateGroup(group, card.hidden))
        n.learnAndShare(card)
        return group
    }

    fun nameOf(member: Long): String? = n.names.nameOf(state, member)

    // ---- what a modified phone does ------------------------------------------------------------------------------

    /** The next number of this phone's operations, as its own copy counts them. */
    fun nextSeq(): Int = (n.replica.ops().filter { it.author == id }.maxOfOrNull { it.seq } ?: 0) + 1

    /** One more than every clock this phone's copy holds. */
    fun nextClock(): Long = (n.replica.ops().maxOfOrNull { it.clock } ?: 0L) + 1

    /**
     * [change] signed by this phone without asking its copy: what a modified phone writes, whatever its rights. The
     * number and the clock are the next ones unless given.
     */
    fun forge(
        change: Change,
        seq: Int = nextSeq(),
        clock: Long = nextClock(),
        signer: PhoneKeys = keys,
        ensembleId: Long = n.ensembleId,
    ): Op = OpAuth.sign(Op(ensembleId, id, seq, clock, change), signer)

    /** An envelope sealed with this phone's invitation key of [epoch], in the name of [sender]. */
    fun frame(
        genre: FrameGenre,
        body: ByteArray,
        sender: Long = id,
        epoch: Int = n.epoch,
        position: Boolean = false,
    ): ByteArray {
        val key =
            OperationFrames.frameKey(
                assertNotNull(n.inviteKey(epoch), "no key of epoch $epoch"),
                n.ensembleId,
                epoch,
            )
        return OperationFrames.seal(key, n.ensembleId, epoch, genre, sender, body, position)
    }

    /** Writes [frame] in the room as this phone does: one write, delivered by the server to everyone present. */
    fun write(
        frame: ByteArray,
        position: Boolean = false,
    ) = room.write(this, frame, position)

    /** Writes [ops] in one frame of operations. */
    fun sendOps(vararg ops: Op) = write(frame(FrameGenre.OPS, HierarchyWire.encodeOps(ops.toList())))

    /**
     * [payload] sealed as a share of [kind] in [channel] in the name of [author], with the content key [key] of that
     * channel, signed by [signer] when the rules sign that kind: what [ShareSeal.seal] makes, without asking whether
     * [author] may share there.
     */
    fun sealRaw(
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
        key: ContentKey,
        author: Long = id,
        signer: PhoneKeys = keys,
    ): ByteArray {
        val header = HierarchyWire.shareHeader(n.ensembleId, channel, key.id, kind, author)
        val nonce = ByteArray(Aead.NONCE_BYTES).also(room.secure::nextBytes)
        val plain =
            if (kind in state.rules.signedShares) {
                payload + signer.sign(ShareSeal.SHARE_TAG + header + nonce + payload)
            } else {
                payload
            }
        return header + nonce + Aead.seal(key.sealingKey(author), nonce, header, plain)
    }

    /** A message as its payload travels: identifier, time, text. */
    fun messagePayload(text: String): ByteArray =
        OperationPayloads.message(OperationText(room.nonZero(), room.now, text))

    override fun toString() = label
}

/**
 * The room. Every frame a phone writes is one write for the whole room ([written]: what the server sees); the server
 * hands it to every phone present, through [relay], which decides what each delivery becomes. Honest by default: as
 * written, in order. A lying server drops it (empty list), changes it, doubles it, holds it back (keeps it and hands
 * it later with [deliver]) or adds frames of its own ([deliver]).
 */
internal class AttackRoom(
    seed: Long = 1L,
) {
    val server: RelayConfig = RelayConfig.of("https://example.invalid", "key")
    var now = 1_700_000_000_000L
    val phones = ArrayList<AttackPhone>()

    /** The choices of the test: seeded, so that a failure plays again the same way. */
    val random = java.util.Random(seed)

    /** The keys and nonces: from the platform, as on a phone. */
    val secure = SecureRandom()

    class Delivery(
        val from: AttackPhone,
        val to: AttackPhone,
        val frame: ByteArray,
        val position: Boolean,
    )

    /** One write as the server sees it: when, by which transport identifier, what. */
    class Write(
        val at: Long,
        val writer: Long,
        val frame: ByteArray,
        val position: Boolean,
    )

    val written = ArrayList<Write>()
    var relay: (Delivery) -> List<Delivery> = { listOf(it) }
    private val queue = ArrayDeque<Delivery>()
    private var lastWrite: ByteArray? = null

    fun nonZero(): Long = generateSequence { random.nextLong() }.first { it != 0L }

    fun present(): List<AttackPhone> = phones.filter { it.online && it.node != null && it.node?.ending == null }

    /** One write of [from]; the same array sent on every link of a broadcast is one write (the server's way). */
    fun write(
        from: AttackPhone,
        frame: ByteArray,
        position: Boolean,
    ) {
        if (!from.online || lastWrite === frame) return
        lastWrite = frame
        written += Write(now, from.id, frame, position)
        for (other in present()) {
            if (other !==
                from
            ) {
                for (d in relay(Delivery(from, other, frame, position))) queue.addLast(d)
            }
        }
    }

    /** Hands [delivery] to its phone at the next round, as the server would. */
    fun deliver(delivery: Delivery) = queue.addLast(delivery)

    fun link(phone: AttackPhone) {
        for (other in present()) {
            if (other === phone) continue
            other.n.linkOpened(phone.id, linkOf(other))
            phone.n.linkOpened(other.id, linkOf(phone))
        }
    }

    private fun linkOf(from: AttackPhone): MeshLink =
        object : MeshLink {
            override val isBroadcastMedium get() = true

            override fun send(
                frame: ByteArray,
                onPositionChannel: Boolean,
            ): Boolean {
                if (!from.online) return false
                write(from, frame, onPositionChannel)
                return true
            }

            override fun close() = Unit
        }

    /** Delivers, ticks and moves the clock until nothing is left to say, or [rounds] times. */
    fun settle(
        rounds: Int = 200,
        stepMs: Long = 500,
    ) {
        var quiet = 0
        repeat(rounds) {
            while (queue.isNotEmpty()) {
                val d = queue.removeFirst()
                if (d.to.online) d.to.node?.receive(d.from.id, d.frame)
            }
            now += stepMs
            for (p in present()) p.n.tick()
            if (queue.isEmpty()) quiet++ else quiet = 0
            if (quiet > 12) return
        }
    }

    fun creator(
        name: String,
        operation: String = "Opération Vercors",
    ): AttackPhone {
        val phone = AttackPhone(name, this)
        val g = OperationNode.genesis(phone.keys, Names.hide(operation), Names.hide(name), server)
        phone.node = OperationNode(phone.keys, g.replica, g.ring, g.names, g.objects, g.link, phone, { now })
        phones += phone
        phone.n.start()
        link(phone)
        return phone
    }

    fun joiner(
        name: String,
        invite: OperationInvite,
        phone: AttackPhone = AttackPhone(name, this),
    ): AttackPhone {
        phone.node =
            OperationNode(
                phone.keys,
                HierarchyReplica(invite.ensembleId, phone.id, phone.keys),
                KeyRing(invite.ensembleId, phone.keys),
                NameBook(),
                SharedObjects(invite.ensembleId),
                OperationNode.joining(invite, Names.hide(name)),
                phone,
                { now },
            )
        phones += phone
        phone.n.start()
        link(phone)
        return phone
    }

    /** A phone that comes in with [inviter]'s invitation and is let in by it. */
    fun letIn(
        inviter: AttackPhone,
        name: String,
    ): AttackPhone {
        val invite = assertNotNull(inviter.n.invitation())
        val phone = joiner(name, assertNotNull(OperationInviteCodec.parseOrNull(OperationInviteCodec.toLink(invite))))
        settle()
        assertTrue(inviter.n.pendingJoins.any { it.member == phone.id }, "$name asks to come in")
        assertTrue(inviter.n.admit(phone.id) is IssueResult.Issued)
        settle()
        return phone
    }

    fun goOffline(phone: AttackPhone) {
        phone.online = false
        for (other in present()) other.n.linkClosed(phone.id)
        for (other in present()) phone.n.linkClosed(other.id)
    }

    fun comeBack(phone: AttackPhone) {
        phone.online = true
        link(phone)
    }

    /**
     * A small operation: the creator A, an administrator B, two groups (Nord: L leader, M member; Sud: O leader, P
     * member), all placed, names and keys settled.
     */
    fun standard(): Standard {
        val a = creator("Alice")
        val b = letIn(a, "Bruno")
        val l = letIn(a, "Léa")
        val m = letIn(a, "Marc")
        val o = letIn(a, "Olga")
        val p = letIn(a, "Paul")
        val nord = a.newGroup("Nord")
        val sud = a.newGroup("Sud")
        a.must(Change.AddAdmin(b.id))
        for (x in listOf(b, l, m)) a.must(Change.AssignMember(x.id, nord))
        for (x in listOf(o, p)) a.must(Change.AssignMember(x.id, sud))
        a.must(Change.SetPost(nord, Post.LEADER, l.id))
        a.must(Change.SetPost(sud, Post.LEADER, o.id))
        settle()
        val all = listOf(a, b, l, m, o, p)
        assertConverged(all)
        for (x in all) assertTrue(x.n.ring.missing(x.state).none { it.lane == Lane.UNIT }, "$x holds its unit keys")
        return Standard(a, b, l, m, o, p, nord, sud)
    }

    class Standard(
        val a: AttackPhone,
        val b: AttackPhone,
        val l: AttackPhone,
        val m: AttackPhone,
        val o: AttackPhone,
        val p: AttackPhone,
        val nord: Long,
        val sud: Long,
    ) {
        val all get() = listOf(a, b, l, m, o, p)
    }

    /** Every frame written since [mark], opened with [by]'s invitation keys: what a holder of the invitation reads. */
    fun openedSince(
        mark: Int,
        by: AttackPhone,
    ): List<OpenedFrame> =
        written.drop(mark).mapNotNull { w ->
            val epoch = OperationFrames.epochOf(w.frame) ?: return@mapNotNull null
            val key = by.n.inviteKey(epoch) ?: return@mapNotNull null
            OperationFrames.open(OperationFrames.frameKey(key, by.n.ensembleId, epoch), by.n.ensembleId, w.frame)
        }
}

/** Same operations, same state, on every phone of [phones]. */
internal fun assertConverged(phones: List<AttackPhone>) {
    val first = phones.first()
    for (p in phones.drop(1)) {
        assertEquals(first.n.replica.summary(), p.n.replica.summary(), "$p holds the operations of $first")
        assertEquals(
            HierarchyWire.encodeState(first.state).toList(),
            HierarchyWire.encodeState(p.state).toList(),
            "$p has the state of $first",
        )
    }
}

/** The state of [phone] as bytes: compared before and after an attack. */
internal fun stateBytes(phone: AttackPhone): List<Byte> = HierarchyWire.encodeState(phone.state).toList()

internal fun fixAt(
    t: Long,
    lat: Double = 45.1,
) = PeerFix(lat, 5.5, timeSec = t)

/** Every content key [this] phone holds now, added to [into]: what a modified phone that forgets nothing keeps. */
internal fun AttackPhone.hoardKeys(into: MutableMap<Pair<Channel, Long>, ContentKey>) {
    val ring = n.ring
    synchronized(ring) { for ((channel, keys) in ring.held) for ((id, key) in keys) into[channel to id] = key }
}

/** Every invitation key [this] phone holds now, by epoch, added to [into]. */
internal fun AttackPhone.hoardInviteKeys(into: MutableMap<Int, ByteArray>) {
    for (epoch in 1..n.epoch) n.inviteKey(epoch)?.let { into[epoch] = it }
}

/**
 * The share [body] opened with any key of [keys], whatever the rights of its author and of the phone that opens it
 * (what a modified phone does): its header and its plain bytes; `null` when none of the keys opens it.
 */
internal fun openRaw(
    body: ByteArray,
    keys: Map<Pair<Channel, Long>, ContentKey>,
): Pair<HierarchyWire.ShareHeader, ByteArray>? {
    val headerBytes = HierarchyWire.SHARE_HEADER_BYTES
    if (body.size < headerBytes + Aead.NONCE_BYTES + Aead.TAG_BYTES) return null
    val header =
        try {
            HierarchyWire.readShareHeader(body.copyOf(headerBytes))
        } catch (e: fr.toolmap.core.p2p.WireException) {
            return null
        }
    val key = keys[header.channel to header.keyId] ?: return null
    val nonce = body.copyOfRange(headerBytes, headerBytes + Aead.NONCE_BYTES)
    val sealed = body.copyOfRange(headerBytes + Aead.NONCE_BYTES, body.size)
    val plain = Aead.open(key.sealingKey(header.author), nonce, body.copyOf(headerBytes), sealed) ?: return null
    return header to plain
}

/** Every envelope of [written] that one of [inviteKeys] opens, for the operation [ensembleId]. */
internal fun openEnvelopes(
    written: List<AttackRoom.Write>,
    inviteKeys: Map<Int, ByteArray>,
    ensembleId: Long,
): List<OpenedFrame> =
    written.mapNotNull { w ->
        val epoch = OperationFrames.epochOf(w.frame) ?: return@mapNotNull null
        val key = inviteKeys[epoch] ?: return@mapNotNull null
        OperationFrames.open(OperationFrames.frameKey(key, ensembleId, epoch), ensembleId, w.frame)
    }

/** Whether [needle] appears in [haystack]. */
internal fun ByteArray.holds(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}
