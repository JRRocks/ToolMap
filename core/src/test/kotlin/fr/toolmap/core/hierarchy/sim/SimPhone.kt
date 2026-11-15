package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Aead
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.ContentKey
import fr.toolmap.core.hierarchy.ContentKeys
import fr.toolmap.core.hierarchy.DissolutionAnnouncer
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.FrameGenre
import fr.toolmap.core.hierarchy.HierarchyReplica
import fr.toolmap.core.hierarchy.HierarchySnapshot
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.InviteGrant
import fr.toolmap.core.hierarchy.InviteKeys
import fr.toolmap.core.hierarchy.KeyRing
import fr.toolmap.core.hierarchy.LoggedMessage
import fr.toolmap.core.hierarchy.MessageLog
import fr.toolmap.core.hierarchy.NameBook
import fr.toolmap.core.hierarchy.NameCard
import fr.toolmap.core.hierarchy.OpAuth
import fr.toolmap.core.hierarchy.OperationEnding
import fr.toolmap.core.hierarchy.OperationFrames
import fr.toolmap.core.hierarchy.OperationInvite
import fr.toolmap.core.hierarchy.OperationLinkState
import fr.toolmap.core.hierarchy.OperationListener
import fr.toolmap.core.hierarchy.OperationNode
import fr.toolmap.core.hierarchy.OperationText
import fr.toolmap.core.hierarchy.SharedObjects
import fr.toolmap.core.hierarchy.memberId
import fr.toolmap.core.p2p.GroupCrypto
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.WireException

/** What a phone's storage holds of its operation: what `StoredOperation` writes, as bytes. */
data class StoredParts(
    val ensembleId: Long,
    val ops: ByteArray,
    val keys: ByteArray,
    val names: ByteArray,
    val objects: ByteArray,
    val link: ByteArray,
    val messages: ByteArray,
)

/** A share a phone's listener was told of: who, where, what. */
data class Heard(
    val author: Long,
    val channel: Channel,
    val text: String,
    val atMs: Long,
)

/**
 * What a modified phone could keep (P39.10): every invitation key it could unwrap, every content key wrapped to it,
 * from every frame its stream ever brought, never forgotten. The honest phone forgets; this does not, so that the
 * checks can say what a phone taken out or put back to waiting could still read.
 */
class Hoard(
    private val keys: SimKeys,
) {
    val self: Long = keys.memberId
    var ensembleId: Long = 0

    /** By epoch, every invitation key of that epoch it could unwrap (two phones may have made two). */
    val epochKeys = LinkedHashMap<Int, MutableList<ByteArray>>()

    /** Every content key wrapped to it, by channel and identifier. */
    val contentKeys = LinkedHashMap<Pair<Channel, Long>, ContentKey>()

    fun learnEpoch(
        epoch: Int,
        key: ByteArray,
    ) {
        val list = epochKeys.getOrPut(epoch) { ArrayList() }
        if (list.none { it.contentEquals(key) }) list += key
    }

    fun holdsEpochKey(key: ByteArray): Boolean = epochKeys.values.any { list -> list.any { it.contentEquals(key) } }

    /** Takes what [frame] gives this phone: a content key wrapped to it, an invitation key wrapped to it. */
    fun capture(
        world: World,
        frame: ByteArray,
    ) {
        if (ensembleId == 0L || frame.size < 2) return
        val genre = FrameGenre.of(frame[1].toInt() and 0xFF) ?: return
        if (genre != FrameGenre.KEYS && genre != FrameGenre.INVITE_KEY) return
        val epoch = OperationFrames.epochOf(frame) ?: return
        val candidates = epochKeys[epoch]?.toList() ?: return
        val wasMuted = world.muted
        world.muted = true
        try {
            for (key in candidates) {
                val opened =
                    OperationFrames.open(OperationFrames.frameKey(key, ensembleId, epoch), ensembleId, frame)
                        ?: continue
                try {
                    if (genre == FrameGenre.KEYS) {
                        val batch = HierarchyWire.decodeKeyBatch(opened.body)
                        if ((batch.channel to batch.keyId) !in contentKeys &&
                            batch.wraps.any { it.recipient == self }
                        ) {
                            ContentKeys.unwrap(batch, keys)?.let { contentKeys[batch.channel to it.id] = it }
                        }
                    } else {
                        val grant = InviteKeys.decode(opened.body)
                        val a = grant.announcement
                        val known =
                            epochKeys[a.epoch].orEmpty().any {
                                InviteKeys.keyId(a.ensembleId, a.epoch, it) ==
                                    a.keyId
                            }
                        if (!known) unwrapGrant(grant)?.let { learnEpoch(a.epoch, it) }
                    }
                } catch (e: WireException) {
                    // A frame of an honest phone always decodes: the checks see the refusal elsewhere.
                    world.anomalies += "hoard: undecodable ${genre.name}: ${e.message}"
                }
                break
            }
        } finally {
            world.muted = wasMuted
        }
    }

    /** What InviteKeys.open unwraps, without its checks: a modified phone needs none. */
    fun unwrapGrant(grant: InviteGrant): ByteArray? {
        val wrap = grant.wraps.firstOrNull { it.recipient == self } ?: return null
        val shared = keys.agreeQuietly(grant.ephemeral) ?: return null
        val context = grant.announcement.body() + grant.ephemeral + OpAuth.longBytes(self)
        val wrapKey = GroupCrypto.hkdfSha256(shared, ByteArray(0), GRANT_WRAP_INFO + context, Aead.KEY_BYTES)
        val key = Aead.open(wrapKey, ByteArray(Aead.NONCE_BYTES), context, wrap.sealed) ?: return null
        val a = grant.announcement
        return key.takeIf { InviteKeys.keyId(a.ensembleId, a.epoch, it) == a.keyId }
    }

    companion object {
        private val GRANT_WRAP_INFO = "toolmap-operation-invite-wrap-v1".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * A simulated phone (P39.10): the real [OperationNode] with its real parts, a storage written as `StoredOperation`
 * writes it (the same bytes, through [HierarchySnapshot]), an app that can be stopped, killed and started again, a
 * network that comes and goes, and a clock off by [skewMs]. What its listener hears is kept for the checks.
 */
class SimPhone(
    world: World,
    val index: Int,
    val label: String,
    val keys: SimKeys,
    val skewMs: Long,
    seed: Long,
    private val server: RelayConfig,
) : Participant(world, keys.memberId),
    OperationListener {
    val random = SeededRandom(seed)
    var node: OperationNode? = null
        private set

    /** The app runs (not stopped, not killed). */
    var appUp = false
        private set

    /** The phone has a network: when its app runs, its stream connects. */
    var networkUp = true

    /** What its storage holds; `null` before anything is saved and once the operation is wiped. */
    var stored: StoredParts? = null
        private set

    /** The operation is over for this phone, and how. */
    var ended: OperationEnding? = null
        private set
    var endedAtMs: Long? = null
        private set
    var endedByTrace = false
        private set

    /** The trace this phone keeps after a dissolution it was a member at (R1). */
    var trace: DissolutionTrace? = null
        private set

    /** The phone is lost for good: its app never starts again. */
    var lost = false

    /** It created the operation or asked to come in: it never asks again. */
    var joined = false
        private set

    /** Asked to come in: the invitation it used. */
    var invitation: OperationInvite? = null
        private set
    var ownCard: NameCard? = null
        private set

    private var log: MessageLog = MessageLog(0)
    val heardMessages = ArrayList<Heard>()
    val heardAlerts = ArrayList<Heard>()
    val heardPositions = ArrayList<Heard>()
    var positionsHeard = 0L
    val hoard = Hoard(keys)

    /** Seen by the listener while this phone, in its own state, did not read the channel: a break of § 8.5. */
    val badReads = ArrayList<String>()
    private var saveDue = false
    private var messagesDue = false
    private var endDue = false

    /** CPU spent in the node by this phone, in nanoseconds, and its slowest call. */
    var cpuNs = 0L
    var slowestCallNs = 0L

    val n: OperationNode get() = requireNotNull(node) { "$label has no node" }
    val state: HierarchyState? get() = node?.state

    /** In the operation now, as far as this phone knows: let in, not ended. */
    val isMember: Boolean get() = ended == null && node?.isMember == true

    override val running: Boolean get() = appUp && node != null && ended == null && !lost

    fun clock(): Long = world.now + skewMs

    // ---- life ----------------------------------------------------------------------------------------------------

    /** This phone creates the operation. */
    fun create(
        operationName: NameCard,
        myName: NameCard,
    ) {
        val g = OperationNode.genesis(keys, operationName, myName, server, random)
        joined = true
        ownCard = myName
        startNode(OperationNode(keys, g.replica, g.ring, g.names, g.objects, g.link, this, ::clock, random))
    }

    /** This phone asks to come in with [invite], under the name of [myName]. */
    fun join(
        invite: OperationInvite,
        myName: NameCard,
    ) {
        invitation = invite
        joined = true
        ownCard = myName
        val node =
            OperationNode(
                keys,
                HierarchyReplica(invite.ensembleId, id, keys),
                KeyRing(invite.ensembleId, keys),
                NameBook(),
                SharedObjects(invite.ensembleId),
                OperationNode.joining(invite, myName),
                this,
                ::clock,
                random,
            )
        startNode(node)
    }

    private fun startNode(node: OperationNode) {
        this.node = node
        hoard.ensembleId = node.ensembleId
        log = MessageLog(node.ensembleId)
        appUp = true
        world.enter(this)
        call { node.start() }
        save()
        if (networkUp) world.connect(this)
    }

    /** The app stops: cleanly (what was due is saved first) or killed (what was not saved yet is lost). */
    fun stopApp(killed: Boolean) {
        if (!appUp) return
        if (!killed) {
            if (saveDue) save()
            if (messagesDue) saveMessages()
        }
        appUp = false
        node = null
        saveDue = false
        messagesDue = false
        world.stop(this)
    }

    /** The app starts again and reads its storage, as `OperationController.open` does. */
    fun startApp() {
        if (appUp || lost || ended != null) return
        val parts = stored ?: return
        val ops = HierarchySnapshot.decodeOps(parts.ops, parts.ensembleId)
        val replica = HierarchySnapshot.restoreReplica(parts.ensembleId, id, ops, keys)
        val ring = KeyRing(parts.ensembleId, keys).also { HierarchySnapshot.restoreKeys(it, parts.keys) }
        val names = NameBook().also { HierarchySnapshot.restoreNames(it, parts.names) }
        val objects = SharedObjects(parts.ensembleId).also { HierarchySnapshot.restoreObjects(it, parts.objects) }
        val link = OperationLinkState.decode(parts.link)
        log = MessageLog(parts.ensembleId).also { HierarchySnapshot.restoreMessages(it, parts.messages) }
        world.purgeOld(this)
        startNode(OperationNode(keys, replica, ring, names, objects, link, this, ::clock, random))
    }

    fun networkOn() {
        networkUp = true
        if (running) {
            world.connect(this)
        } else if (ended != null && !lost) {
            world.listen(this)
        }
    }

    fun networkOff() {
        networkUp = false
        world.disconnect(this)
    }

    /** Runs [block] on the node as the controller's thread would, then saves what it changed. */
    fun <T> call(block: (OperationNode) -> T): T? {
        val node = node ?: return null
        if (!appUp) return null
        val t0 = System.nanoTime()
        val previous = world.current
        world.current = label
        val result =
            try {
                block(node)
            } finally {
                world.current = previous
            }
        val spent = System.nanoTime() - t0
        cpuNs += spent
        if (spent > slowestCallNs) slowestCallNs = spent
        afterCall()
        return result
    }

    /** Told when this phone's invitation key changes: the trail of a scenario says it. */
    var onEpoch: ((SimPhone, Int, Long) -> Unit)? = null
    private var lastEpoch = 0
    private var lastKeyId = 0L

    private fun afterCall() {
        node?.let { n ->
            val e = n.epoch
            val id = n.linkState().epochs.last().announcement.keyId
            if (e != lastEpoch || id != lastKeyId) {
                lastEpoch = e
                lastKeyId = id
                onEpoch?.invoke(this, e, n.linkState().epochs.last().announcement.announcer)
            }
        }
        node?.let { hoard.ensembleId = it.ensembleId }
        node?.linkState()?.epochs?.forEach { hoard.learnEpoch(it.epoch, it.key) }
        if (endDue) {
            endDue = false
            end()
            return
        }
        if (saveDue) world.after(SAVE_DELAY_MS) { if (saveDue && appUp) save() }
        if (messagesDue) world.after(SAVE_DELAY_MS) { if (messagesDue && appUp) saveMessages() }
    }

    /** What `OperationController.Running.save` then `StoredOperation.save` do. */
    fun save() {
        saveDue = false
        messagesDue = false
        val node = node ?: return
        if (ended != null) return
        val state = node.state
        if (node.self in state.members) {
            HierarchySnapshot.forgetUnread(state, node.ring, node.objects)
            log.retain(state.channelsReadBy(node.self))
        }
        stored =
            StoredParts(
                node.ensembleId,
                HierarchySnapshot.encodeOps(node.replica.ops()),
                HierarchySnapshot.encodeKeys(node.ring),
                HierarchySnapshot.encodeNames(node.names),
                HierarchySnapshot.encodeObjects(node.objects),
                node.linkState().encode(),
                HierarchySnapshot.encodeMessages(log),
            )
    }

    /** What `Running.saveMessages` does: the threads of channels no longer read forgotten, then written alone. */
    private fun saveMessages() {
        messagesDue = false
        val node = node ?: return
        if (ended != null) return
        val parts = stored ?: return
        if (node.self in node.state.members) log.retain(node.state.channelsReadBy(node.self))
        stored = parts.copy(messages = HierarchySnapshot.encodeMessages(log))
    }

    /** Over for this phone: the storage wiped (a trace kept after a dissolution it was a member at), the room left. */
    private fun end() {
        val node = node ?: return
        if (ended == OperationEnding.DISSOLVED) {
            val proof = node.dissolution
            if (proof !=
                null
            ) {
                trace = DissolutionTrace.of(proof, node.linkState(), node.dissolvedAtMs ?: clock(), random)
            }
        }
        endedByTrace = node.dissolvedByTrace
        stored = null
        log.clear()
        // The last frames go out first; then the transport closes (the room deletes this phone's node). A modified
        // phone would go on listening to the room: the hoard keeps what it could still open.
        world.after(CLOSE_DELAY_MS) {
            world.close(this)
            if (networkUp && !lost) world.listen(this)
        }
    }

    // ---- the relay's callbacks -------------------------------------------------------------------------------------

    override fun linkOpened(
        peer: Long,
        link: MeshLink,
    ) {
        call { it.linkOpened(peer, link) }
    }

    override fun linkClosed(peer: Long) {
        call { it.linkClosed(peer) }
    }

    override fun frame(
        peer: Long,
        bytes: ByteArray,
    ) {
        call { it.receive(peer, bytes) }
    }

    override fun captured(
        writer: Long,
        bytes: ByteArray,
    ) = hoard.capture(world, bytes)

    override fun second() {
        call { it.tick() }
    }

    /** Once over, a modified phone would keep listening to the room: the hoard keeps what the stream brings. */
    override val listensOnly: Boolean get() = ended != null

    // ---- the node's listener ---------------------------------------------------------------------------------------

    override fun onChanged() {
        saveDue = true
    }

    private fun readsNow(channel: Channel): Boolean {
        val node = node ?: return false
        return channel in node.state.channelsReadBy(id)
    }

    override fun onPosition(
        member: Long,
        channel: Channel,
        fix: PeerFix,
    ) {
        if (!readsNow(channel)) badReads += "position of $member in $channel"
        positionsHeard++
        if (world.probing) heardPositions += Heard(member, channel, fix.timeSec.toString(), world.now)
    }

    override fun onMessage(
        author: Long,
        channel: Channel,
        message: OperationText,
    ) {
        if (!readsNow(channel)) badReads += "message of $author in $channel"
        heardMessages += Heard(author, channel, message.text, world.now)
        if (log.add(channel, LoggedMessage(author, message.id, message.sentAtMs, clock(), message.text))) {
            messagesDue = true
        }
    }

    override fun onAlert(
        author: Long,
        channel: Channel,
        alert: OperationText,
    ) {
        if (!readsNow(channel)) badReads += "alert of $author in $channel"
        heardAlerts += Heard(author, channel, (if (alert.lifted) "lifted:" else "") + alert.text, world.now)
    }

    override fun onEnded(ending: OperationEnding) {
        ended = ending
        endedAtMs = world.now
        endDue = true
    }

    /** Keeps a message this phone wrote in its own thread, as the controller does (R2: « les siens compris »). */
    fun keepOwn(
        channel: Channel,
        message: OperationText,
    ) {
        if (log.add(channel, LoggedMessage(id, message.id, message.sentAtMs, clock(), message.text))) messagesDue = true
    }

    /** The threads this phone keeps now. */
    fun threads(): Map<Channel, List<LoggedMessage>> = log.threads()

    override fun toString() = label

    companion object {
        /** The controller saves on its thread just after: a few milliseconds where a killed app loses the change. */
        const val SAVE_DELAY_MS = 20L
        const val CLOSE_DELAY_MS = 5_000L
    }
}

/** A phone that keeps the trace of a dissolved operation and is in its room for a moment (ADR-131). */
class Keeper(
    world: World,
    val trace: DissolutionTrace,
    val owner: SimPhone,
) : Participant(world, trace.sender) {
    val announcer = DissolutionAnnouncer(trace, { world.now + owner.skewMs }, SeededRandom(trace.sender))
    private val links = LinkedHashMap<Long, MeshLink>()
    var closesAt = 0L
    var windows = 0

    override val running: Boolean get() = closesAt > world.now

    override fun linkOpened(
        peer: Long,
        link: MeshLink,
    ) {
        links[peer] = link
    }

    override fun linkClosed(peer: Long) {
        links.remove(peer)
    }

    override fun frame(
        peer: Long,
        bytes: ByteArray,
    ) {
        val answer = announcer.receive(bytes) ?: return
        (links[peer] ?: links.values.firstOrNull())?.send(answer, false)
    }

    override fun second() {
        if (world.now >= closesAt) {
            world.close(this)
            links.clear()
        }
    }

    /** A moment of two minutes in the room, as `OperationController.openWindow` gives one. */
    fun open(windowMs: Long = WINDOW_MS) {
        if (trace.isExpired(world.now + owner.skewMs)) return
        val wasRunning = running
        closesAt = world.now + windowMs
        windows++
        if (!wasRunning) {
            world.enter(this)
            world.connect(this)
        }
    }

    companion object {
        const val WINDOW_MS = 2 * 60_000L
    }
}
