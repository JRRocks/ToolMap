package fr.toolmap.core.p2p

import fr.toolmap.core.eventlog.EventLog
import java.security.SecureRandom

/** One open connection to a neighbour; the WebRTC one in the app, a simulated one in the tests. */
interface MeshLink {
    /** Sends [frame] on the unordered channel when [onPositionChannel], else on the reliable one. */
    fun send(
        frame: ByteArray,
        onPositionChannel: Boolean,
    ): Boolean

    fun close()

    /**
     * `true` when everything sent on this link is heard by every member at once (a shared server room): the node then
     * never passes on what it received, since the author already reached everyone directly.
     */
    val isBroadcastMedium: Boolean get() = false
}

/** What the node needs to open connections; the offers and answers travel through the node's own SIGNAL messages. */
interface MeshTransport {
    /** Prepares a link towards [peer] and hands over the offer to relay; `null` when it could not be made. */
    fun offer(
        peer: Long,
        done: (PairingPayload?) -> Unit,
    )

    /** Accepts a relayed [offer] and hands over the answer to send back; `null` when it failed. */
    fun answer(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    )

    /** Applies the [answer] to the link prepared by [offer]. */
    fun accept(answer: PairingPayload)

    /** Gives up the link being negotiated with [peer], or closes it. */
    fun drop(peer: Long)
}

interface MeshListener {
    /** The member table or a member's state changed. */
    fun onChanged() {}

    fun onPosition(
        member: Long,
        fix: PeerFix,
    ) {}

    fun onText(
        member: Long,
        text: String,
    ) {}

    fun onSos(
        member: Long,
        fix: PeerFix?,
        text: String,
    ) {}

    /** [member] lifted the alert last given to [onSos] (P22.2): called once per alert, however often it is repeated. */
    fun onSosLifted(member: Long) {}

    /** The group key changed: persist it. */
    fun onRekeyed(key: ByteArray) {}

    /** The others removed this device from the group. */
    fun onExcluded() {}

    /** A payload of the debug build's test tools ([Msg.Diag]) came from [member]. */
    fun onDiag(
        member: Long,
        body: ByteArray,
    ) {}
}

enum class MemberStatus {
    /** A link is open to it. */
    DIRECT,

    /** No link, but its messages still reach us through other members. */
    RELAYED,

    /** Nothing heard for a while. */
    LOST,

    /** It said goodbye. */
    LEFT,
}

class MemberView(
    val id: Long,
    val name: String,
    /** The packed icon and colour ([MemberLook]). */
    val color: Int,
    val status: MemberStatus,
    val isSelf: Boolean,
    /** Epoch millis of the last time anything was heard from or about it. */
    val lastHeardMs: Long,
    val fix: PeerFix?,
    val trail: List<PeerFix>,
    /** The member's own picture (a small JPEG), once received; `null` when it has none. */
    val picture: ByteArray? = null,
    /**
     * Whether it shares its position (`true`) or only listens to the group (`false`, P22.3); `null` until it said so,
     * as a version without the STATE message never does.
     */
    val sharing: Boolean? = null,
) {
    /** The opaque colour of the member. */
    val argb: Int get() = MemberLook.argb(color)

    /** The icon id of the member ([MemberLook.ICONS]). */
    val icon: Int get() = MemberLook.icon(color)
}

/**
 * The protocol of a group, independent of any network: who is in the group, which links are open, what to relay, when
 * to open the missing links and when a member counts as lost. Calls may come from any thread; the listener is called
 * outside the node's lock.
 *
 * Rules (`docs/specs/p2p-sharing.md` §4, §5): every member should end up linked to every other; of two members the
 * one with the smaller id makes the offer; offers and answers are relayed as SIGNAL messages along the links that
 * exist; positions, texts and SOS are flooded with a time to live and are never handled twice. An alert and its lift
 * are repeated for [SOS_REPEAT_FOR_S] seconds (P22.2); of the two, the one with the higher number wins.
 */
class MeshNode(
    val groupId: ByteArray,
    groupKey: ByteArray,
    val selfId: Long,
    selfName: String,
    selfColor: Int,
    private val transport: MeshTransport,
    private val listener: MeshListener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /**
     * The lowest message number to start from: a new process must not reuse the numbers of the previous one, which
     * the others still remember and would drop as duplicates (P21.5 m). `0` starts from the clock alone.
     */
    seqFloor: Long = 0L,
) {
    private class Member(
        val id: Long,
        var name: String,
        var color: Int,
    ) {
        var lastHeardMs = 0L
        var fix: PeerFix? = null
        var left = false
        val trail = ArrayDeque<PeerFix>()
        var picture: ByteArray? = null
        var sharing: Boolean? = null
        var stateSeq = -1L
    }

    private val crypto = GroupCrypto(groupKey, random)
    private val members = LinkedHashMap<Long, Member>()
    private val links = HashMap<Long, MeshLink>()
    private val lastFrameMs = HashMap<Long, Long>()
    private val negotiating = HashMap<Long, Long>()
    private val retryAt = HashMap<Long, Long>()
    private val attempts = HashMap<Long, Int>()
    private val removed = HashSet<Long>()
    private val seen = LinkedHashMap<Pair<Long, Long>, Long>()
    private val missed = ArrayDeque<PeerFix>()
    private val events = ArrayList<() -> Unit>()
    private var seq = maxOf(clock() / MS_PER_S, seqFloor)
    private var lastPingMs = 0L
    private var lastEnsureMs = 0L

    /** This device's alert, on until [cancelSos] even once its repeats are over. */
    private var sos: Pair<PeerFix?, String>? = null
    private var sosSendsLeft = 0L
    private var lastSosMs = 0L

    /** Sends of the lift of this device's alert still to make. */
    private var liftSendsLeft = 0L
    private var lastLiftMs = 0L

    /** The highest numbers of the alerts and lifts heard of each member: a frame overtaken by the other is over. */
    private val alertSeq = HashMap<Long, Long>()
    private val liftSeq = HashMap<Long, Long>()

    /** The members whose alert was handed to the listener and not lifted since. */
    private val alerting = HashSet<Long>()
    private var closed = false

    private val me = Member(selfId, selfName, selfColor).also { members[selfId] = it }

    /** The sequence number of the last AVATAR this node sent, sent again unchanged to each new link. */
    private var pictureSeq = 0L

    /** The sequence number of the last STATE this node sent, sent again unchanged to each new link (P22.3). */
    private var stateSeq = 0L

    /** The last message number used, kept by the caller so that the next process starts above it (P21.5 m). */
    val lastSeq: Long get() = synchronized(this) { seq }

    /** `true` once this node has left the group or been removed from it. */
    val isClosed: Boolean get() = synchronized(this) { closed }

    private fun <T> locked(block: () -> T): T {
        val result: T
        val toRun: List<() -> Unit>
        synchronized(this) {
            result = block()
            toRun = events.toList()
            events.clear()
        }
        for (e in toRun) e()
        return result
    }

    private fun now() = clock()

    // ---- links ------------------------------------------------------------------------------------------------

    /** A link to [peer] (known by [name] and [color] from its pairing code) is open. */
    fun onLinkOpen(
        peer: Long,
        name: String,
        color: Int,
        link: MeshLink,
    ) = locked {
        if (closed || peer == selfId) {
            link.close()
            return@locked
        }
        links.remove(peer)?.let { if (it !== link) it.close() }
        links[peer] = link
        EventLog.event("mesh.link.open", "peer" to hex(peer), "name" to name, "links" to links.size)
        negotiating.remove(peer)
        attempts.remove(peer)
        retryAt.remove(peer)
        removed.remove(peer)
        val m = members.getOrPut(peer) { Member(peer, name, color) }
        m.name = name
        m.color = color
        m.left = false
        m.lastHeardMs = now()
        lastFrameMs[peer] = now()
        sendTo(peer, Msg.Hello(selfId, me.name, me.color))
        me.picture?.let { sendTo(peer, Msg.Avatar(selfId, pictureSeq, it)) }
        me.sharing?.let { sendTo(peer, Msg.State(selfId, stateSeq, it)) }
        sendTo(peer, peersMessage())
        if (missed.isNotEmpty()) sendTo(peer, Msg.Trail(selfId, missed.toList()))
        broadcast(peersMessage(), except = peer)
        changed()
    }

    fun onLinkClosed(peer: Long) =
        locked {
            if (links.remove(peer) != null) {
                EventLog.event("mesh.link.closed", "peer" to hex(peer), "links" to links.size)
                changed()
            }
            lastFrameMs.remove(peer)
            negotiating.remove(peer)
        }

    /** A frame came in from the neighbour [peer]. Frames that do not decrypt or parse are dropped silently. */
    fun onFrame(
        peer: Long,
        bytes: ByteArray,
    ) = locked {
        if (closed) return@locked
        val frame =
            try {
                Wire.decode(bytes, crypto)
            } catch (e: WireException) {
                EventLog.event(
                    "mesh.frame.bad",
                    "peer" to hex(peer),
                    "type" to Wire.typeOf(bytes),
                    "bytes" to bytes.size,
                    "fid" to Wire.frameId(bytes),
                    "why" to e.message,
                )
                return@locked
            }
        EventLog.detail(
            "mesh.frame.in",
            "peer" to hex(peer),
            "type" to frame.msg.type,
            "ttl" to frame.ttl,
            "bytes" to bytes.size,
            "fid" to Wire.frameId(bytes),
        )
        // A removed member can still have frames in flight, sealed with the key it knew: they count for nothing.
        if (authorOf(frame.msg) in removed) return@locked
        lastFrameMs[peer] = now()
        members[peer]?.let {
            it.lastHeardMs = now()
            it.left = false
        }
        handle(peer, frame, bytes)
    }

    private fun handle(
        from: Long,
        frame: Frame,
        raw: ByteArray,
    ) {
        when (val msg = frame.msg) {
            is Msg.Hello -> {
                if (msg.memberId != from) return
                members[from]?.let {
                    it.name = msg.name
                    it.color = msg.color
                }
                changed()
            }

            is Msg.Peers -> {
                onPeers(msg)
            }

            is Msg.Signal -> {
                onSignal(from, msg, frame, raw)
            }

            is Msg.Pos -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                EventLog.event(
                    "mesh.pos.in",
                    "member" to hex(msg.memberId),
                    "via" to hex(from),
                    "seq" to msg.seq,
                    "fid" to Wire.frameId(raw),
                    "fixT" to msg.fix.timeSec,
                    "ageS" to now() / MS_PER_S - msg.fix.timeSec,
                    "acc" to msg.fix.accuracyM,
                    "bat" to msg.fix.battery,
                )
                recordFix(msg.memberId, msg.fix)
                events += { listener.onPosition(msg.memberId, msg.fix) }
                relay(raw, from)
            }

            is Msg.Trail -> {
                EventLog.event("mesh.trail.in", "member" to hex(msg.memberId), "fixes" to msg.fixes.size)
                val m = members[msg.memberId] ?: return
                for (f in msg.fixes) m.trail.addLast(f)
                while (m.trail.size > MAX_TRAIL) m.trail.removeFirst()
                // The newest fix of a trail is where the member is now when no POS brought a newer one, as for the
                // start position taken before the first link opened (P21.5 l).
                val newest = msg.fixes.maxByOrNull { it.timeSec }
                if (newest != null && newest.timeSec > (m.fix?.timeSec ?: Long.MIN_VALUE)) {
                    m.fix = newest
                    events += { listener.onPosition(msg.memberId, newest) }
                }
                changed()
            }

            is Msg.Ping -> {
                Unit
            }

            is Msg.Text -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                EventLog.event(
                    "mesh.text.in",
                    "member" to hex(msg.memberId),
                    "seq" to msg.seq,
                    "chars" to msg.text.length,
                )
                touch(msg.memberId)
                events += { listener.onText(msg.memberId, msg.text) }
                relay(raw, from)
            }

            is Msg.Sos -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                val lifted = msg.seq < (liftSeq[msg.memberId] ?: -1L)
                EventLog.event(
                    "mesh.sos.in",
                    "member" to hex(msg.memberId),
                    "seq" to msg.seq,
                    "fix" to (msg.fix != null),
                    "lifted" to lifted,
                )
                touch(msg.memberId)
                relay(raw, from)
                // A repeat that a lift of the same member overtook on the way (P22.2).
                if (lifted) return
                msg.fix?.let { recordFix(msg.memberId, it) }
                alertSeq[msg.memberId] = maxOf(msg.seq, alertSeq[msg.memberId] ?: 0L)
                alerting.add(msg.memberId)
                events += { listener.onSos(msg.memberId, msg.fix, msg.text) }
            }

            is Msg.SosEnd -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                // A lift older than an alert already heard belongs to a previous alert.
                val stale = msg.seq < (alertSeq[msg.memberId] ?: -1L)
                EventLog.event(
                    "mesh.sos.end.in",
                    "member" to hex(msg.memberId),
                    "seq" to msg.seq,
                    "stale" to stale,
                )
                touch(msg.memberId)
                relay(raw, from)
                if (stale) return
                liftSeq[msg.memberId] = maxOf(msg.seq, liftSeq[msg.memberId] ?: 0L)
                if (alerting.remove(msg.memberId)) events += { listener.onSosLifted(msg.memberId) }
            }

            is Msg.State -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                EventLog.event(
                    "mesh.state.in",
                    "member" to hex(msg.memberId),
                    "seq" to msg.seq,
                    "sharing" to msg.sharing,
                )
                relay(raw, from)
                val m = memberOrNew(msg.memberId) ?: return
                m.lastHeardMs = now()
                m.left = false
                if (msg.seq > m.stateSeq) {
                    m.stateSeq = msg.seq
                    m.sharing = msg.sharing
                }
                changed()
            }

            is Msg.Rekey -> {
                onRekey(from, msg, raw)
            }

            is Msg.Avatar -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                members[msg.memberId]?.let {
                    it.picture = msg.image.takeIf { image -> image.isNotEmpty() }
                    changed()
                }
                relay(raw, from)
            }

            is Msg.Kick -> {
                EventLog.event("mesh.kick.in", "target" to hex(msg.target), "me" to (msg.target == selfId))
                if (msg.target == selfId) {
                    close(sayBye = false)
                    events += { listener.onExcluded() }
                }
            }

            is Msg.Diag -> {
                if (msg.memberId == selfId || !firstTime(msg.memberId, msg.seq)) return
                events += { listener.onDiag(msg.memberId, msg.body) }
                relay(raw, from)
            }

            is Msg.Bye -> {
                EventLog.event("mesh.bye.in", "member" to hex(msg.memberId), "via" to hex(from))
                members[msg.memberId]?.left = true
                if (msg.memberId == from) {
                    links.remove(from)?.close()
                    transport.drop(from)
                }
                changed()
            }
        }
    }

    private fun authorOf(msg: Msg): Long? =
        when (msg) {
            is Msg.Hello -> msg.memberId
            is Msg.Signal -> msg.from
            is Msg.Pos -> msg.memberId
            is Msg.Trail -> msg.memberId
            is Msg.Ping -> msg.memberId
            is Msg.Text -> msg.memberId
            is Msg.Sos -> msg.memberId
            is Msg.SosEnd -> msg.memberId
            is Msg.State -> msg.memberId
            is Msg.Rekey -> msg.memberId
            is Msg.Bye -> msg.memberId
            is Msg.Avatar -> msg.memberId
            is Msg.Diag -> msg.memberId
            is Msg.Peers -> null
            is Msg.Kick -> null
        }

    private fun onPeers(msg: Msg.Peers) {
        var grew = false
        for (p in msg.peers) {
            if (p.memberId == selfId || p.memberId in removed) continue
            val known = members[p.memberId]
            if (known == null) {
                members[p.memberId] =
                    Member(p.memberId, p.name, p.color).also { it.lastHeardMs = now() - p.lastSeenAgoSec * MS_PER_S }
                grew = true
            } else if (known.left && p.lastSeenAgoSec < LOST_AFTER_S) {
                known.left = false
            }
        }
        if (grew) changed()
        ensureLinks()
    }

    // ---- positions and texts -----------------------------------------------------------------------------------

    /** Sends the position of this device to every member, and keeps it for those not linked yet. */
    fun publish(fix: PeerFix) =
        locked {
            if (closed) return@locked
            me.fix = fix
            me.lastHeardMs = now()
            val msg = Msg.Pos(selfId, nextSeq(), fix)
            if (links.isEmpty()) {
                missed.addLast(fix)
                while (missed.size > MAX_MISSED) missed.removeFirst()
                EventLog.event("mesh.pos.held", "seq" to msg.seq, "fixT" to fix.timeSec, "held" to missed.size)
            }
            val frame = broadcast(msg)
            if (frame != null) {
                EventLog.event(
                    "mesh.pos.out",
                    "seq" to msg.seq,
                    "fid" to Wire.frameId(frame),
                    "fixT" to fix.timeSec,
                    "acc" to fix.accuracyM,
                    "links" to links.size,
                    "bytes" to frame.size,
                )
            }
            changed()
        }

    fun sendText(text: String) =
        locked {
            if (!closed) broadcast(Msg.Text(selfId, nextSeq(), text.take(Msg.MAX_TEXT_CHARS)))
        }

    /** Sends a payload of the debug build's test tools to every member; `false` when no link is open. */
    fun sendDiag(body: ByteArray): Boolean =
        locked {
            if (closed || links.isEmpty()) return@locked false
            val msg = Msg.Diag(selfId, nextSeq(), body)
            firstTime(selfId, msg.seq)
            broadcast(msg) != null
        }

    /**
     * Starts an alert: sent now, then every [SOS_REPEAT_S] seconds for [SOS_REPEAT_FOR_S] seconds (P22.2). Only the
     * sends that reach a neighbour count: with no link, it goes as soon as one opens. It stays on until [cancelSos].
     */
    fun startSos(
        fix: PeerFix?,
        text: String,
    ) = locked {
        if (closed) return@locked
        sos = fix to text
        sosSendsLeft = SOS_SENDS
        liftSendsLeft = 0L
        lastSosMs = 0L
        sendSosNow()
    }

    /**
     * Lifts this device's alert, even once its repeats are over: the others are told now and again for
     * [SOS_REPEAT_FOR_S] seconds, like the alert. Nothing is sent when no alert is on.
     */
    fun cancelSos() =
        locked {
            if (closed || sos == null) return@locked
            sos = null
            sosSendsLeft = 0L
            liftSendsLeft = SOS_SENDS
            lastLiftMs = 0L
            sendLiftNow()
        }

    /** `true` from [startSos] to [cancelSos]. */
    val sosActive: Boolean get() = synchronized(this) { sos != null }

    private fun sendSosNow() {
        val (fix, text) = sos ?: return
        if (links.isEmpty()) return
        lastSosMs = now()
        sosSendsLeft--
        val msg = Msg.Sos(selfId, nextSeq(), fix ?: me.fix, text)
        EventLog.event("mesh.sos.out", "seq" to msg.seq, "left" to sosSendsLeft, "links" to links.size)
        broadcast(msg)
    }

    private fun sendLiftNow() {
        if (links.isEmpty()) return
        lastLiftMs = now()
        liftSendsLeft--
        val msg = Msg.SosEnd(selfId, nextSeq())
        EventLog.event("mesh.sos.end.out", "seq" to msg.seq, "left" to liftSendsLeft, "links" to links.size)
        broadcast(msg)
    }

    // ---- signalling ---------------------------------------------------------------------------------------------

    /** Sends [payload] to member [to] by the links that exist. */
    private fun sendSignal(
        to: Long,
        payload: PairingPayload,
    ) {
        val msg = Msg.Signal(nextSeq(), selfId, to, PairingCodec.encodeBytes(payload))
        firstTime(selfId, msg.seq)
        if (links.containsKey(to)) sendTo(to, msg) else broadcast(msg)
    }

    private fun onSignal(
        from: Long,
        msg: Msg.Signal,
        frame: Frame,
        raw: ByteArray,
    ) {
        if (!firstTime(msg.from, msg.seq)) return
        if (msg.to != selfId) {
            val direct = links[msg.to]
            if (direct != null) {
                if (!direct.isBroadcastMedium) Wire.relayed(raw)?.let { direct.send(it, false) }
            } else {
                relay(raw, from)
            }
            return
        }
        val payload =
            try {
                PairingCodec.decodeBytes(msg.payload)
            } catch (e: WireException) {
                return
            }
        if (!payload.groupId.contentEquals(groupId) || payload.memberId != msg.from) return
        if (PairingCodec.staleness(payload, now() / MS_PER_S) != null) return
        when (payload.kind) {
            PairingKind.MESH_OFFER -> {
                val m = members[msg.from] ?: return
                if (m.left) return
                transport.answer(payload) { answer -> if (answer != null) locked { sendSignal(msg.from, answer) } }
            }

            PairingKind.ANSWER -> {
                transport.accept(payload)
            }

            PairingKind.OFFER -> {
                Unit
            }
        }
    }

    /** Opens the links to members this node should call: those with a larger id, not yet linked or being linked. */
    private fun ensureLinks() {
        if (closed || links.isEmpty()) return
        val t = now()
        lastEnsureMs = t
        for (m in members.values.toList()) {
            if (m.id == selfId || m.left || links.containsKey(m.id) || negotiating.containsKey(m.id)) continue
            if (selfId >= m.id || t < (retryAt[m.id] ?: 0L)) continue
            negotiating[m.id] = t
            transport.offer(m.id) { offer ->
                locked {
                    if (offer == null) negotiating.remove(m.id) else sendSignal(m.id, offer)
                }
            }
        }
    }

    // ---- keys and leaving ---------------------------------------------------------------------------------------

    /** Removes [memberId]: a new key is sent to everyone else, so the member cannot read what comes next. */
    fun remove(memberId: Long) =
        locked {
            if (closed || memberId == selfId) return@locked
            val newKey = GroupCrypto.randomKey(random)
            val msg = Msg.Rekey(selfId, nextSeq(), memberId, newKey)
            val frame = Wire.encode(msg, crypto)
            for ((id, link) in links) if (id != memberId) link.send(frame, false)
            // The member is told it is out with the old key it knows, before the new one becomes the sealing key.
            dropMember(memberId)
            crypto.rekey(newKey)
            events += { listener.onRekeyed(newKey) }
            changed()
        }

    private fun onRekey(
        from: Long,
        msg: Msg.Rekey,
        raw: ByteArray,
    ) {
        if (!firstTime(msg.memberId, msg.seq)) return
        if (msg.excluded == selfId) {
            // Only reachable through a relay that did not know it had to keep the key from us: leave at once.
            close(sayBye = false)
            events += { listener.onExcluded() }
            return
        }
        if (msg.excluded != 0L) dropMember(msg.excluded)
        crypto.rekey(msg.key)
        events += { listener.onRekeyed(msg.key) }
        // The frame is passed on as it came, still sealed with the previous key, to everyone but the excluded.
        Wire.relayed(raw)?.let { relayed ->
            for ((id, link) in links) {
                if (id != from && id != msg.excluded && !link.isBroadcastMedium) link.send(relayed, false)
            }
        }
        changed()
    }

    private fun dropMember(id: Long) {
        // Tell it on its own link, with the old key, that it is out; the new key is not in that message.
        links[id]?.send(Wire.encode(Msg.Kick(id), crypto), false)
        removed.add(id)
        retryAt.remove(id)
        attempts.remove(id)
        links.remove(id)?.close()
        lastFrameMs.remove(id)
        negotiating.remove(id)
        transport.drop(id)
        members.remove(id)
        alertSeq.remove(id)
        liftSeq.remove(id)
        alerting.remove(id)
    }

    /** Leaves the group: tells the neighbours and closes every link. */
    fun leave() = locked { close(sayBye = true) }

    private fun close(sayBye: Boolean) {
        if (closed) return
        if (sayBye) broadcast(Msg.Bye(selfId))
        closed = true
        for ((id, link) in links) {
            link.close()
            transport.drop(id)
        }
        for (id in negotiating.keys) transport.drop(id)
        links.clear()
        negotiating.clear()
        changed()
    }

    // ---- clock --------------------------------------------------------------------------------------------------

    /** Call about once a second: pings, dead links, missing links, SOS repeats. */
    fun tick() =
        locked {
            if (closed) return@locked
            val t = now()
            if (due(lastPingMs, t, PING_EVERY_S)) {
                lastPingMs = t
                val frame = broadcast(Msg.Ping(selfId, t / MS_PER_S))
                if (frame != null) EventLog.detail("mesh.ping.out", "fid" to Wire.frameId(frame), "links" to links.size)
            }
            for ((id, last) in lastFrameMs.toMap()) {
                if (t - last > LOST_AFTER_S * MS_PER_S) {
                    EventLog.event("mesh.link.lost", "peer" to hex(id), "silentS" to (t - last) / MS_PER_S)
                    links.remove(id)?.close()
                    transport.drop(id)
                    lastFrameMs.remove(id)
                    changed()
                }
            }
            for ((id, started) in negotiating.toMap()) {
                if (t - started > NEGOTIATION_TIMEOUT_S * MS_PER_S) {
                    negotiating.remove(id)
                    transport.drop(id)
                    val n = (attempts[id] ?: 0) + 1
                    attempts[id] = n
                    retryAt[id] = t + (NEGOTIATION_TIMEOUT_S shl (n - 1).coerceAtMost(MAX_BACKOFF_SHIFT)) * MS_PER_S
                }
            }
            if (due(lastEnsureMs, t, ENSURE_EVERY_S)) ensureLinks()
            if (sos != null && sosSendsLeft > 0 && due(lastSosMs, t, SOS_REPEAT_S)) sendSosNow()
            if (liftSendsLeft > 0 && due(lastLiftMs, t, SOS_REPEAT_S)) sendLiftNow()
        }

    /** `true` when [everyS] seconds passed since [last], or when the clock went back past it (P21.5 n). */
    private fun due(
        last: Long,
        t: Long,
        everyS: Long,
    ) = t < last || t - last >= everyS * MS_PER_S

    // ---- views --------------------------------------------------------------------------------------------------

    fun members(): List<MemberView> =
        synchronized(this) {
            val t = now()
            members.values.map { m ->
                val status =
                    when {
                        m.id == selfId -> MemberStatus.DIRECT
                        m.left -> MemberStatus.LEFT
                        links.containsKey(m.id) -> MemberStatus.DIRECT
                        t - m.lastHeardMs <= LOST_AFTER_S * MS_PER_S -> MemberStatus.RELAYED
                        else -> MemberStatus.LOST
                    }
                MemberView(
                    m.id,
                    m.name,
                    m.color,
                    status,
                    m.id == selfId,
                    m.lastHeardMs,
                    m.fix,
                    m.trail.toList(),
                    m.picture,
                    m.sharing,
                )
            }
        }

    /** Ids of the neighbours linked right now. */
    fun linkedIds(): Set<Long> = synchronized(this) { links.keys.toSet() }

    fun setSelf(
        name: String,
        color: Int,
    ) = locked {
        me.name = name
        me.color = color
        broadcast(Msg.Hello(selfId, name, color))
        changed()
    }

    /**
     * Tells the group whether this device shares its position or only listens (P22.3): sent at once when it changes,
     * and to each link that opens.
     */
    fun setSharing(on: Boolean) =
        locked {
            if (closed || me.sharing == on) return@locked
            me.sharing = on
            stateSeq = nextSeq()
            firstTime(selfId, stateSeq)
            EventLog.event("mesh.state.out", "seq" to stateSeq, "sharing" to on, "links" to links.size)
            broadcast(Msg.State(selfId, stateSeq, on))
            changed()
        }

    /** Shares [picture] (a small JPEG, at most [MemberLook.MAX_PICTURE_BYTES]) with the group; `null` removes it. */
    fun setPicture(picture: ByteArray?) =
        locked {
            val next = picture?.takeIf { it.isNotEmpty() && it.size <= MemberLook.MAX_PICTURE_BYTES }
            if (next.contentEquals(me.picture)) return@locked
            me.picture = next
            pictureSeq = nextSeq()
            firstTime(selfId, pictureSeq)
            broadcast(Msg.Avatar(selfId, pictureSeq, next ?: ByteArray(0)))
            changed()
        }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private fun nextSeq(): Long = ++seq and 0xFFFFFFFFL

    private fun changed() {
        events += { listener.onChanged() }
    }

    private fun memberOrNew(memberId: Long): Member? {
        if (memberId in removed) return null
        // A member can be heard of through a relay before its name reaches us in a PEERS list.
        return members.getOrPut(memberId) { Member(memberId, "?", 0) }
    }

    private fun touch(memberId: Long) {
        memberOrNew(memberId)?.let {
            it.lastHeardMs = now()
            it.left = false
        }
    }

    private fun recordFix(
        memberId: Long,
        fix: PeerFix,
    ) {
        val m = memberOrNew(memberId) ?: return
        m.lastHeardMs = now()
        m.left = false
        m.fix = fix
        m.trail.addLast(fix)
        while (m.trail.size > MAX_TRAIL) m.trail.removeFirst()
        changed()
    }

    /** `true` the first time a `(member, seq)` pair is seen; remembers the last [SEEN_CAP] ones. */
    private fun firstTime(
        member: Long,
        seq: Long,
    ): Boolean {
        val key = member to seq
        if (seen.containsKey(key)) return false
        seen[key] = now()
        if (seen.size > SEEN_CAP) {
            val it = seen.entries.iterator()
            it.next()
            it.remove()
        }
        return true
    }

    private fun peersMessage(): Msg.Peers {
        val t = now()
        return Msg.Peers(
            members.values.filter { !it.left }.map {
                PeerInfo(
                    it.id,
                    it.name,
                    it.color,
                    if (it.id ==
                        selfId
                    ) {
                        0
                    } else {
                        ((t - it.lastHeardMs) / MS_PER_S).coerceAtLeast(0)
                    },
                )
            },
        )
    }

    private fun sendTo(
        peer: Long,
        msg: Msg,
    ) {
        val link = links[peer] ?: return
        link.send(Wire.encode(msg, crypto), msg.onPositionChannel)
    }

    /** Sends [msg] to every neighbour but [except]; the frame sent, `null` when there is no neighbour. */
    private fun broadcast(
        msg: Msg,
        except: Long? = null,
    ): ByteArray? {
        if (links.isEmpty()) return null
        val frame = Wire.encode(msg, crypto)
        for ((id, link) in links) {
            if (id != except && !link.send(frame, msg.onPositionChannel)) {
                EventLog.event("mesh.send.fail", "peer" to hex(id), "type" to msg.type, "fid" to Wire.frameId(frame))
            }
        }
        return frame
    }

    /** Passes [raw] to every neighbour but [from], one hop shorter. */
    private fun relay(
        raw: ByteArray,
        from: Long,
    ) {
        val relayed = Wire.relayed(raw) ?: return
        val onPos = (raw[0].toInt() and 0xFF) == MsgType.POS.code
        for ((id, link) in links) if (id != from && !link.isBroadcastMedium) link.send(relayed, onPos)
    }

    companion object {
        const val PING_EVERY_S = 15L
        const val LOST_AFTER_S = 45L
        const val ENSURE_EVERY_S = 20L
        const val NEGOTIATION_TIMEOUT_S = 30L
        const val SOS_REPEAT_S = 15L

        /** How long an alert, and then its lift, is repeated (P22.2): two minutes. */
        const val SOS_REPEAT_FOR_S = 120L

        /** Sends of an alert or of its lift: the first one, then one every [SOS_REPEAT_S] for [SOS_REPEAT_FOR_S]. */
        const val SOS_SENDS = 1 + SOS_REPEAT_FOR_S / SOS_REPEAT_S
        private const val MS_PER_S = 1000L
        private const val SEEN_CAP = 4096
        private const val MAX_TRAIL = 200
        private const val MAX_MISSED = 60
        private const val MAX_BACKOFF_SHIFT = 4

        /** A member id as the event log and the server write it. */
        fun hex(id: Long): String = java.lang.Long.toHexString(id)
    }
}
