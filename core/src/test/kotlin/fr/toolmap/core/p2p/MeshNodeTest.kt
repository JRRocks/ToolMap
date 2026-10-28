package fr.toolmap.core.p2p

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tiny network: links deliver frames through a queue pumped by the test, so everything is deterministic. */
private class Net {
    var nowMs = 1_790_000_000_000L
    val peers = HashMap<Long, Peer>()
    private val queue = ArrayDeque<() -> Unit>()
    val cut = HashSet<Pair<Long, Long>>()

    fun pump() {
        var guard = 0
        while (queue.isNotEmpty()) {
            check(++guard < 10_000) { "the network does not settle" }
            queue.removeFirst()()
        }
    }

    fun deliver(
        from: Long,
        to: Long,
        frame: ByteArray,
    ) {
        queue.addLast {
            if (Pair(from, to) !in cut) peers[to]?.node?.onFrame(from, frame)
        }
    }

    /** Opens a link between [a] and [b] the way a successful pairing would. */
    fun connect(
        a: Long,
        b: Long,
    ) {
        val pa = peers.getValue(a)
        val pb = peers.getValue(b)
        val toB = NetLink(this, a, b)
        val toA = NetLink(this, b, a)
        pa.links[b] = toB
        pb.links[a] = toA
        pa.node.onLinkOpen(b, pb.name, pb.color, toB)
        pb.node.onLinkOpen(a, pa.name, pa.color, toA)
        pump()
    }

    /** Both ends see the link close, as a lost connection would show. */
    fun disconnect(
        a: Long,
        b: Long,
    ) {
        peers.getValue(a).links.remove(b)
        peers.getValue(b).links.remove(a)
        peers.getValue(a).node.onLinkClosed(b)
        peers.getValue(b).node.onLinkClosed(a)
        pump()
    }

    fun tickAll(
        seconds: Int,
        every: Int = 1,
    ) {
        var t = 0
        while (t < seconds) {
            nowMs += every * 1000L
            for (p in peers.values.toList()) p.node.tick()
            pump()
            t += every
        }
    }
}

private class NetLink(
    private val net: Net,
    private val from: Long,
    private val to: Long,
) : MeshLink {
    var closed = false
    var sent = 0

    override fun send(
        frame: ByteArray,
        onPositionChannel: Boolean,
    ): Boolean {
        if (closed) return false
        sent++
        net.deliver(from, to, frame)
        return true
    }

    override fun close() {
        closed = true
    }
}

private class Peer(
    val net: Net,
    val id: Long,
    val name: String,
    groupKey: ByteArray,
    val groupId: ByteArray,
    var allowOffers: Boolean = true,
    seqFloor: Long = 0L,
) : MeshTransport,
    MeshListener {
    val color = (id % 12).toInt()
    val links = HashMap<Long, NetLink>()
    val positions = ArrayList<Pair<Long, PeerFix>>()
    val texts = ArrayList<Pair<Long, String>>()
    val sos = ArrayList<Pair<Long, String>>()
    val lifted = ArrayList<Long>()
    val diags = ArrayList<Pair<Long, ByteArray>>()
    var rekeyed: ByteArray? = null
    var excluded = false
    var changes = 0
    var offersMade = 0
    var answersMade = 0
    private val pendingOffers = HashMap<String, Long>()
    private var token = 0

    /** How far this device's clock is from the network's, to move one clock alone. */
    var skewMs = 0L

    val node =
        MeshNode(groupId, groupKey, id, name, color, this, this, { net.nowMs + skewMs }, SecureRandom(), seqFloor)

    init {
        net.peers[id] = this
    }

    private fun payload(
        kind: PairingKind,
        ufrag: String,
    ) = PairingPayload(
        kind,
        net.nowMs / 1000,
        groupId,
        null,
        id,
        name,
        color,
        ufrag,
        "p".repeat(24),
        ByteArray(32),
        DtlsSetup.ACTPASS,
        emptyList(),
    )

    override fun offer(
        peer: Long,
        done: (PairingPayload?) -> Unit,
    ) {
        if (!allowOffers) return done(null)
        offersMade++
        val ufrag = "o$id-${token++}"
        pendingOffers[ufrag] = peer
        done(payload(PairingKind.MESH_OFFER, ufrag))
    }

    override fun answer(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    ) {
        answersMade++
        // The answer carries the ufrag of the offer so the offerer knows which link it is for.
        done(payload(PairingKind.ANSWER, offer.iceUfrag))
    }

    override fun accept(answer: PairingPayload) {
        val target = pendingOffers.remove(answer.iceUfrag) ?: return
        assertEquals(target, answer.memberId)
        net.connect(id, answer.memberId)
    }

    override fun drop(peer: Long) {
        links.remove(peer)?.close()
    }

    override fun onChanged() {
        changes++
    }

    override fun onPosition(
        member: Long,
        fix: PeerFix,
    ) {
        positions += member to fix
    }

    override fun onText(
        member: Long,
        text: String,
    ) {
        texts += member to text
    }

    override fun onSos(
        member: Long,
        fix: PeerFix?,
        text: String,
    ) {
        sos += member to text
    }

    override fun onSosLifted(member: Long) {
        lifted += member
    }

    override fun onDiag(
        member: Long,
        body: ByteArray,
    ) {
        diags += member to body
    }

    override fun onRekeyed(key: ByteArray) {
        rekeyed = key
    }

    override fun onExcluded() {
        excluded = true
    }

    fun status(other: Long) = node.members().first { it.id == other }.status

    fun view(other: Long) = node.members().first { it.id == other }
}

class MeshNodeTest {
    private val key = GroupCrypto.randomKey()
    private val groupId = ByteArray(16) { 9 }
    private val net = Net()

    private fun peer(
        id: Long,
        name: String,
    ) = Peer(net, id, name, key, groupId)

    private fun fix(
        lat: Double = 45.0,
        lon: Double = 6.0,
        t: Long = net.nowMs / 1000,
    ) = PeerFix(lat, lon, timeSec = t, battery = 80)

    /** Three members, A (id 1) invited B (2) and C (3), who then find each other through the mesh. */
    private fun threeMembers(): Triple<Peer, Peer, Peer> {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        val c = peer(3, "Chloé")
        net.connect(1, 2)
        net.connect(1, 3)
        return Triple(a, b, c)
    }

    @Test
    fun `a joiner receives the member list and the inviter learns the joiner`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        assertEquals(listOf("Alice", "Bruno"), a.node.members().map { it.name }.sorted())
        assertEquals(listOf("Alice", "Bruno"), b.node.members().map { it.name }.sorted())
        assertEquals(MemberStatus.DIRECT, a.status(2))
        assertEquals(setOf(2L), a.node.linkedIds())
        assertTrue(a.node.members().first { it.isSelf }.id == 1L)
    }

    @Test
    fun `a third member pairs once and the two others link up through the relayed signal`() {
        val (a, b, c) = threeMembers()
        assertEquals(setOf(1L, 3L), b.node.linkedIds())
        assertEquals(setOf(1L, 2L), c.node.linkedIds())
        assertEquals(setOf(2L, 3L), a.node.linkedIds())
        // Of B and C the smaller id made the offer, once; A (id 1) did not need to call anyone.
        assertEquals(1, b.offersMade)
        assertEquals(0, c.offersMade)
        assertEquals(1, c.answersMade)
        assertEquals(0, a.offersMade)
        assertEquals(MemberStatus.DIRECT, c.status(2))
    }

    @Test
    fun `a position reaches everyone exactly once even when two paths lead to it`() {
        val (a, b, c) = threeMembers()
        b.node.publish(fix(45.1, 6.1))
        net.pump()
        assertEquals(1, a.positions.count { it.first == 2L })
        assertEquals(1, c.positions.count { it.first == 2L })
        assertEquals(0, b.positions.size)
        assertEquals(45.1, c.view(2).fix!!.lat, 1e-6)
        assertEquals(1, c.view(2).trail.size)
    }

    @Test
    fun `when the direct link is down the position is relayed by the third member`() {
        val (a, b, c) = threeMembers()
        net.disconnect(2, 3)
        b.node.publish(fix(45.2, 6.2))
        net.pump()
        assertEquals(1, c.positions.count { it.first == 2L })
        assertEquals(MemberStatus.RELAYED, c.status(2))
        assertEquals(45.2, c.view(2).fix!!.lat, 1e-6)
        assertEquals(1, a.positions.count { it.first == 2L })
    }

    @Test
    fun `a picture reaches everyone, relayed when needed, then reaches a late link, and can be removed`() {
        val (a, b, c) = threeMembers()
        net.disconnect(2, 3)
        val picture = ByteArray(2000) { it.toByte() }
        b.node.setPicture(picture)
        net.pump()
        assertContentEquals(picture, a.view(2).picture)
        assertContentEquals(picture, c.view(2).picture)
        // The same picture again sends nothing.
        val sent = b.links.values.sumOf { it.sent }
        b.node.setPicture(picture.copyOf())
        net.pump()
        assertEquals(sent, b.links.values.sumOf { it.sent })
        b.node.setPicture(null)
        net.pump()
        assertNull(a.view(2).picture)
        assertNull(c.view(2).picture)
        // Too large: refused, nothing changes.
        b.node.setPicture(ByteArray(MemberLook.MAX_PICTURE_BYTES + 1))
        net.pump()
        assertNull(a.view(2).picture)
    }

    @Test
    fun `a diagnostic payload reaches every other member once, relayed when needed`() {
        val (a, b, c) = threeMembers()
        net.disconnect(2, 3)
        val body = ByteArray(1000) { it.toByte() }
        assertTrue(b.node.sendDiag(body))
        net.pump()
        assertEquals(listOf(2L), a.diags.map { it.first })
        assertEquals(listOf(2L), c.diags.map { it.first })
        assertContentEquals(body, c.diags.single().second)
        assertTrue(b.diags.isEmpty())
        val alone = peer(9, "Seul")
        assertTrue(!alone.node.sendDiag(body))
    }

    @Test
    fun `the time to live stops a flood after four hops`() {
        val peers = (1L..6L).map { Peer(net, it, "P$it", key, groupId, allowOffers = false) }
        for (i in 1L until 6L) net.connect(i, i + 1)
        val sentBefore = peers.sumOf { p -> p.links.values.sumOf { it.sent } }
        peers[0].node.publish(fix(46.0, 7.0))
        net.pump()
        for (i in 1..4) assertEquals(1, peers[i].positions.size, "P${i + 1} is $i hops away")
        assertEquals(0, peers[5].positions.size, "P6 is five hops away")
        // One position, one frame per hop: four frames in all, no echo storm.
        assertEquals(4, peers.sumOf { p -> p.links.values.sumOf { it.sent } } - sentBefore)
    }

    @Test
    fun `a frame seen twice is handled once`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        a.node.publish(fix())
        net.pump()
        assertEquals(1, b.positions.size)
        val crypto = GroupCrypto(key)
        val frame = Wire.encode(Msg.Pos(1, 777, fix(46.0, 7.0)), crypto)
        b.node.onFrame(1, frame)
        b.node.onFrame(1, frame)
        assertEquals(2, b.positions.size)
    }

    @Test
    fun `frames of another group or garbage are dropped without effect`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        val stranger = GroupCrypto(GroupCrypto.randomKey())
        b.node.onFrame(1, Wire.encode(Msg.Pos(1, 5, fix()), stranger))
        b.node.onFrame(1, ByteArray(40))
        b.node.onFrame(1, ByteArray(0))
        assertEquals(0, b.positions.size)
        assertNull(b.view(1).fix)
    }

    @Test
    fun `texts and SOS are flooded, the SOS repeated until lifted`() {
        val (a, b, c) = threeMembers()
        b.node.sendText("On y va")
        net.pump()
        assertEquals(listOf(2L to "On y va"), a.texts)
        assertEquals(listOf(2L to "On y va"), c.texts)

        b.node.startSos(fix(45.3, 6.3), "Blessé")
        net.pump()
        assertEquals(1, a.sos.size)
        assertEquals(1, c.sos.size)
        net.tickAll(16)
        assertEquals(2, a.sos.size)
        b.node.cancelSos()
        net.pump()
        assertEquals(listOf(2L), a.lifted)
        assertEquals(listOf(2L), c.lifted)
        net.tickAll(40)
        assertEquals(2, a.sos.size)
        assertEquals(45.3, a.view(2).fix!!.lat, 1e-6)
    }

    @Test
    fun `an alert is repeated for two minutes, stays on after them and is lifted once however often the lift comes`() {
        val (a, b, _) = threeMembers()
        b.node.startSos(fix(), "Cheville")
        net.tickAll(300)
        // The first send, then one every 15 s for 120 s.
        assertEquals(MeshNode.SOS_SENDS.toInt(), a.sos.size)
        assertEquals(9, a.sos.size)
        assertTrue(b.node.sosActive)

        b.node.cancelSos()
        net.tickAll(300)
        assertFalse(b.node.sosActive)
        assertEquals(listOf(2L), a.lifted)
        assertEquals(9, a.sos.size)
        // Nothing to lift any more: a second cancel sends nothing.
        b.node.cancelSos()
        net.tickAll(30)
        assertEquals(listOf(2L), a.lifted)
    }

    @Test
    fun `a member cut off when the alert was lifted hears the lift when it comes back within two minutes`() {
        val (a, b, c) = threeMembers()
        net.connect(2, 3)
        b.node.startSos(fix(), "Perdu")
        net.pump()
        assertEquals(1, c.sos.size)
        net.disconnect(1, 3)
        net.disconnect(2, 3)
        b.node.cancelSos()
        net.tickAll(30)
        assertEquals(listOf(2L), a.lifted)
        assertTrue(c.lifted.isEmpty())
        net.connect(1, 3)
        net.tickAll(16)
        assertEquals(listOf(2L), c.lifted)
    }

    @Test
    fun `an alert sent with no link goes as soon as a link opens, and its two minutes start then`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        b.node.startSos(fix(), "Aide")
        net.tickAll(60)
        net.connect(1, 2)
        net.tickAll(1)
        assertEquals(1, a.sos.size)
        net.tickAll(300)
        assertEquals(9, a.sos.size)
    }

    @Test
    fun `a repeat overtaken by the lift, and a lift older than a new alert, are ignored`() {
        val (a, _, _) = threeMembers()
        val crypto = GroupCrypto(key)

        fun from2(msg: Msg) {
            a.node.onFrame(2, Wire.encode(msg, crypto))
            net.pump()
        }
        // The lift of an alert this node never heard: nothing to lift.
        from2(Msg.SosEnd(2, 105))
        assertTrue(a.lifted.isEmpty())
        // A repeat of that alert arriving after its lift is over already.
        from2(Msg.Sos(2, 103, null, "ancienne"))
        assertTrue(a.sos.isEmpty())
        // A new alert, then a late repeat of the previous lift: the alert stays.
        from2(Msg.Sos(2, 110, null, "nouvelle"))
        from2(Msg.SosEnd(2, 107))
        assertEquals(listOf(2L to "nouvelle"), a.sos)
        assertTrue(a.lifted.isEmpty())
        from2(Msg.SosEnd(2, 112))
        assertEquals(listOf(2L), a.lifted)
    }

    @Test
    fun `whether a member shares or only listens reaches everyone, new links included, the latest winning`() {
        val (a, b, c) = threeMembers()
        // A member of a version without STATE is unknown, neither sharing nor listening.
        assertNull(a.view(2).sharing)
        b.node.setSharing(false)
        net.pump()
        assertEquals(false, a.view(2).sharing)
        assertEquals(false, c.view(2).sharing)
        assertEquals(false, b.node.members().first { it.isSelf }.sharing)
        b.node.setSharing(true)
        net.pump()
        assertEquals(true, a.view(2).sharing)
        // A late copy of the earlier state changes nothing.
        a.node.onFrame(2, Wire.encode(Msg.State(2, 1, sharing = false), GroupCrypto(key)))
        net.pump()
        assertEquals(true, a.view(2).sharing)
        // Someone who joins later hears it on its first link.
        val d = peer(4, "Denis")
        net.connect(2, 4)
        assertEquals(true, d.view(2).sharing)
    }

    @Test
    fun `a frame of a type this version does not know is dropped and the group goes on`() {
        // What a version without SOS_END (up to 0.4.113, types 1 to 13) does with a lift: its type is unknown to it.
        // Here an unassigned type plays that part for this version.
        val (a, b, _) = threeMembers()
        val lift = Wire.encode(Msg.SosEnd(2, 1), GroupCrypto(key))
        a.node.onFrame(2, lift.copyOf().also { it[0] = 0x7F })
        net.pump()
        assertTrue(a.lifted.isEmpty())
        b.node.sendText("Toujours là")
        net.pump()
        assertEquals(listOf(2L to "Toujours là"), a.texts)
        assertEquals(MemberStatus.DIRECT, a.status(2))
    }

    @Test
    fun `pings keep links alive and a silent neighbour is dropped after 45 seconds`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        net.tickAll(120, every = 5)
        assertEquals(MemberStatus.DIRECT, a.status(2))
        assertEquals(setOf(2L), a.node.linkedIds())
        net.cut += (2L to 1L)
        net.cut += (1L to 2L)
        // The last ping was at most 15 s ago: 20 s of silence is not yet 45.
        net.tickAll(20, every = 5)
        assertEquals(MemberStatus.DIRECT, a.status(2))
        net.tickAll(35, every = 5)
        assertEquals(setOf<Long>(), a.node.linkedIds())
        assertEquals(MemberStatus.LOST, a.status(2))
        assertTrue(a.links.isEmpty() || a.links.values.all { it.closed })
    }

    @Test
    fun `a member that stops speaking is lost and a lost link is called again by the smaller id`() {
        val (a, b, c) = threeMembers()
        net.disconnect(2, 3)
        net.tickAll(25, every = 5)
        // B (id 2) calls C (id 3) again through A, whose links are intact.
        assertEquals(setOf(1L, 3L), b.node.linkedIds())
        assertEquals(2, b.offersMade)
        assertEquals(MemberStatus.DIRECT, c.status(2))
    }

    @Test
    fun `removing a member rekeys the others, closes its links and ignores what it still sends`() {
        val (a, b, c) = threeMembers()
        a.node.remove(3)
        net.pump()
        assertTrue(c.excluded)
        assertTrue(c.node.isClosed)
        assertContentEquals(a.rekeyed, b.rekeyed)
        assertNotEquals(null, a.rekeyed)
        assertEquals(setOf(2L), a.node.linkedIds())
        assertEquals(setOf(1L), b.node.linkedIds())
        assertFalse(a.node.members().any { it.id == 3L })
        assertFalse(b.node.members().any { it.id == 3L })
        // What A and B say next is sealed with the new key: C could not read it and sends nothing back.
        a.node.sendText("secret")
        net.pump()
        assertEquals(listOf(1L to "secret"), b.texts)
        assertTrue(c.texts.none { it.second == "secret" })
        // A frame C sealed with the old key is dropped by the author check.
        val old = GroupCrypto(key)
        b.node.onFrame(1, Wire.encode(Msg.Text(3, 99, "intrus"), old))
        assertTrue(b.texts.none { it.second == "intrus" })
        // A key can be changed twice in a row: a member of two keys ago can no longer be heard.
        assertContentEquals(a.rekeyed, b.rekeyed)
    }

    @Test
    fun `leaving tells the neighbours and closes the links`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        b.node.leave()
        net.pump()
        assertTrue(b.node.isClosed)
        assertEquals(MemberStatus.LEFT, a.status(2))
        assertEquals(setOf<Long>(), a.node.linkedIds())
        // A closed node does nothing more.
        b.node.publish(fix())
        net.pump()
        assertTrue(a.positions.isEmpty())
    }

    @Test
    fun `positions taken without any link are sent as a trail to the first neighbour`() {
        val a = peer(1, "Alice")
        a.node.publish(fix(45.0, 6.0, t = 1))
        a.node.publish(fix(45.001, 6.0, t = 2))
        a.node.publish(fix(45.002, 6.0, t = 3))
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        assertEquals(3, b.view(1).trail.size)
        assertEquals(listOf(1L, 2L, 3L), b.view(1).trail.map { it.timeSec })
        // The newest fix of the trail is the position shown (P21.5 l).
        assertEquals(3L, b.view(1).fix?.timeSec)
        assertEquals(listOf(3L), b.positions.map { it.second.timeSec })
    }

    @Test
    fun `a trail older than the known position does not move it`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        a.node.publish(fix(45.0, 6.0, t = 10))
        net.pump()
        b.node.onFrame(1, Wire.encode(Msg.Trail(1, listOf(fix(44.0, 6.0, t = 5))), GroupCrypto(key)))
        assertEquals(10L, b.view(1).fix?.timeSec)
        assertEquals(listOf(10L), b.positions.map { it.second.timeSec })
    }

    @Test
    fun `a node started again above its last message number is heard at once`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        // A burst of messages takes the numbers ahead of the clock, as the test mode does.
        repeat(50) { a.node.sendText("t$it") }
        net.pump()
        val last = a.node.lastSeq
        // A new process in the same second, numbered from the clock alone: B knows these numbers and drops them.
        val fresh = Peer(net, 1, "Alice", key, groupId)
        net.connect(1, 2)
        fresh.node.sendText("perdu")
        net.pump()
        assertTrue(b.texts.none { it.second == "perdu" })
        // Started above the kept number (P21.5 m), it is heard.
        val again = Peer(net, 1, "Alice", key, groupId, seqFloor = last + 1000)
        net.connect(1, 2)
        again.node.sendText("reçu")
        net.pump()
        assertEquals("reçu", b.texts.last().second)
    }

    @Test
    fun `pings go on when the clock goes back`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        net.tickAll(30, every = 5)
        // A's clock goes back by 815 s, as the emulator's did: B must keep hearing its pings (P21.5 n).
        a.skewMs = -815_000
        net.tickAll(120, every = 5)
        assertEquals(setOf(1L), b.node.linkedIds())
        assertEquals(setOf(2L), a.node.linkedIds())
    }

    @Test
    fun `a signal for another group, from another member or too old is ignored`() {
        val a = peer(1, "Alice")
        val b = peer(2, "Bruno")
        net.connect(1, 2)
        val crypto = GroupCrypto(key)
        var seq = 5_000L

        fun signal(payload: PairingPayload) =
            Wire.encode(Msg.Signal(seq++, 1, 2, PairingCodec.encodeBytes(payload)), crypto)

        fun offer(
            group: ByteArray = groupId,
            member: Long = 1,
            created: Long = net.nowMs / 1000,
        ) = PairingPayload(
            PairingKind.MESH_OFFER,
            created,
            group,
            null,
            member,
            "A",
            0,
            "u",
            "p",
            ByteArray(32),
            DtlsSetup.ACTPASS,
            emptyList(),
        )
        b.node.onFrame(1, signal(offer(group = ByteArray(16))))
        b.node.onFrame(1, signal(offer(member = 7)))
        b.node.onFrame(1, signal(offer(created = net.nowMs / 1000 - 3_600)))
        assertEquals(0, b.answersMade)
        b.node.onFrame(1, signal(offer()))
        assertEquals(1, b.answersMade)
    }

    @Test
    fun `an unreachable member is called again after a back off, not every twenty seconds`() {
        val a = peer(1, "Alice")
        val b = Peer(net, 2, "Bruno", key, groupId, allowOffers = false)
        val c = peer(3, "Chloé")
        net.connect(1, 3)
        net.connect(1, 2)
        // B has to call C and cannot (its transport refuses): it retries, but spaced out.
        net.tickAll(300, every = 5)
        assertTrue(b.offersMade == 0)
        b.allowOffers = true
        net.tickAll(120, every = 5)
        assertTrue(b.offersMade >= 1)
        assertEquals(setOf(1L, 3L), b.node.linkedIds())
    }

    @Test
    fun `renaming is announced`() {
        val (a, b, c) = threeMembers()
        a.node.setSelf("Alice M.", 5)
        net.pump()
        assertEquals("Alice M.", b.view(1).name)
        assertEquals(5, c.view(1).color)
    }
}
