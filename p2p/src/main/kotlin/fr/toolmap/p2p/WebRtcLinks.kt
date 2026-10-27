package fr.toolmap.p2p

import android.content.Context
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PairingKind
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.PairingTransport
import fr.toolmap.core.p2p.Sdp
import fr.toolmap.core.p2p.TransportFactory
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.p2p.WireException
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WebRTC side of the sharing: one `PeerConnection` per neighbour with two data channels, `ctrl` (reliable,
 * ordered) and `pos` (unordered, no retransmission), created as pre-negotiated channels so that neither side waits for
 * the other to announce them. There is no audio or video.
 *
 * Offers and answers are not trickled: the SDP is read once the ICE candidates are all gathered (at most
 * [GATHER_TIMEOUT_S] seconds), reduced to a [PairingPayload] and rebuilt on the other side from a fixed template.
 * [MeshTransport] is what the mesh uses to link to members already in the group; [invite] and [join] are the first
 * pairing, through a QR code or a link.
 *
 * Everything that touches libwebrtc runs on one executor thread; the listener is called from WebRTC's threads.
 */
class WebRtcLinks(
    context: Context,
    private val stunServers: () -> List<String>,
    private val listener: LinkListener,
    private val clockSec: () -> Long = { System.currentTimeMillis() / MS_PER_S },
) : PairingTransport {
    private inner class LinkInvitation(
        override val payload: PairingPayload,
        private val link: Link,
    ) : PairingTransport.Invitation {
        override fun accept(answer: PairingPayload) = acceptAnswer(link, answer)

        override fun cancel() = closeLink(link)
    }

    /** Who this device is in the group; set before the first pairing. */
    @Volatile
    override var identity: TransportIdentity? = null

    private val appContext = context.applicationContext
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "toolmap-p2p").apply { isDaemon = true } }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    /** Links being negotiated with a known member (mesh), by member id. */
    private val negotiating = HashMap<Long, Link>()

    /** Links that are open, by member id. */
    private val open = HashMap<Long, Link>()
    private val lock = Any()

    // ---- first pairing ---------------------------------------------------------------------------------------

    /** Prepares an invitation carrying [groupKey]; `done` gets `null` when there is no network to gather from. */
    override fun invite(
        groupKey: ByteArray,
        done: (PairingTransport.Invitation?) -> Unit,
    ) = beginOffer(PairingKind.OFFER, groupKey, peer = 0L) { link, payload ->
        done(if (link != null && payload != null) LinkInvitation(payload, link) else null)
    }

    /** Answers an invitation (or any offer): `done` gets the answer to show or send back, `null` on failure. */
    override fun join(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    ) {
        if (PairingCodec.staleness(offer, clockSec()) != null) return done(null)
        exec.execute {
            val link = newLink()
            if (link == null) return@execute done(null)
            link.peer = offer.memberId
            link.name = offer.name
            link.color = offer.color
            synchronized(lock) { negotiating.put(offer.memberId, link)?.let { if (it !== link) closeLink(it) } }
            val pc = link.pc!!
            pc.setRemoteDescription(
                observer(onSet = {
                    pc.createAnswer(
                        observer(onCreated = { sdp ->
                            pc.setLocalDescription(
                                observer(onSet = {
                                    awaitGathering(link) { done(payload(link, PairingKind.ANSWER, null)) }
                                }, onFail = { fail(link, done) }),
                                sdp,
                            )
                        }, onFail = { fail(link, done) }),
                        MediaConstraints(),
                    )
                }, onFail = { fail(link, done) }),
                SessionDescription(SessionDescription.Type.OFFER, Sdp.build(offer)),
            )
            watchOpening(link)
        }
    }

    // ---- MeshTransport ---------------------------------------------------------------------------------------

    override fun offer(
        peer: Long,
        done: (PairingPayload?) -> Unit,
    ) = beginOffer(PairingKind.MESH_OFFER, null, peer) { link, payload ->
        if (link != null && payload != null) {
            synchronized(lock) { negotiating.put(peer, link)?.let { if (it !== link) closeLink(it) } }
        }
        done(payload)
    }

    override fun answer(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    ) = join(offer, done)

    override fun accept(answer: PairingPayload) {
        val link = synchronized(lock) { negotiating[answer.memberId] } ?: return
        acceptAnswer(link, answer)
    }

    override fun drop(peer: Long) {
        val links =
            synchronized(lock) {
                listOfNotNull(negotiating.remove(peer), open[peer])
            }
        links.forEach { closeLink(it) }
    }

    /** Closes every link and releases libwebrtc. */
    override fun close() {
        val all = synchronized(lock) { (negotiating.values + open.values).toList() }
        all.forEach { closeLink(it) }
        exec.execute {
            runCatching { factory.dispose() }
            exec.shutdown()
        }
    }

    // ---- negotiation -----------------------------------------------------------------------------------------

    private fun beginOffer(
        kind: PairingKind,
        key: ByteArray?,
        peer: Long,
        done: (Link?, PairingPayload?) -> Unit,
    ) {
        exec.execute {
            val link = newLink()
            if (link == null) return@execute done(null, null)
            link.peer = peer
            val pc = link.pc!!
            pc.createOffer(
                observer(onCreated = { sdp ->
                    pc.setLocalDescription(
                        observer(onSet = {
                            awaitGathering(link) {
                                val p = payload(link, kind, key)
                                if (p == null) closeLink(link)
                                done(link.takeIf { p != null }, p)
                            }
                        }, onFail = { closeLink(link).also { done(null, null) } }),
                        sdp,
                    )
                }, onFail = { closeLink(link).also { done(null, null) } }),
                MediaConstraints(),
            )
        }
    }

    private fun acceptAnswer(
        link: Link,
        answer: PairingPayload,
    ) {
        if (PairingCodec.staleness(answer, clockSec()) != null) return closeLink(link)
        exec.execute {
            link.peer = answer.memberId
            link.name = answer.name
            link.color = answer.color
            link.pc?.setRemoteDescription(
                observer(onFail = { closeLink(link) }),
                SessionDescription(SessionDescription.Type.ANSWER, Sdp.build(answer)),
            )
            watchOpening(link)
        }
    }

    /** The payload for the local description of [link], or `null` when no candidate was found (no network). */
    private fun payload(
        link: Link,
        kind: PairingKind,
        key: ByteArray?,
    ): PairingPayload? {
        val id = identity ?: return null
        val description = link.pc?.localDescription?.description ?: return null
        val info =
            try {
                Sdp.parse(description)
            } catch (e: WireException) {
                return null
            }
        if (info.candidates.isEmpty()) return null
        return PairingPayload(
            kind,
            clockSec(),
            id.groupId,
            key,
            id.memberId,
            id.name,
            id.color,
            info.iceUfrag,
            info.icePwd,
            info.dtlsFingerprint,
            info.dtlsSetup,
            info.candidates,
        )
    }

    private fun fail(
        link: Link,
        done: (PairingPayload?) -> Unit,
    ) {
        closeLink(link)
        done(null)
    }

    /** Runs [then] once ICE gathering is complete, or after [GATHER_TIMEOUT_S] seconds with what was found. */
    private fun awaitGathering(
        link: Link,
        then: () -> Unit,
    ) {
        val once = AtomicBoolean(false)
        val run = { if (once.compareAndSet(false, true)) exec.execute(then) }
        if (link.pc?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            run()
            return
        }
        link.onGathered = run
        exec.schedule({ run() }, GATHER_TIMEOUT_S, TimeUnit.SECONDS)
    }

    /** A link that does not open within [OPEN_TIMEOUT_S] seconds of its remote description is given up. */
    private fun watchOpening(link: Link) {
        exec.schedule({ if (!link.opened && !link.closed.get()) closeLink(link) }, OPEN_TIMEOUT_S, TimeUnit.SECONDS)
    }

    // ---- links -----------------------------------------------------------------------------------------------

    inner class Link internal constructor() : MeshLink {
        @Volatile var peer = 0L

        @Volatile var name = ""

        @Volatile var color = 0

        @Volatile var pc: PeerConnection? = null
        var ctrl: DataChannel? = null
        var pos: DataChannel? = null
        var onGathered: (() -> Unit)? = null

        private val openedOnce = AtomicBoolean(false)
        val opened: Boolean get() = openedOnce.get()
        val closed = AtomicBoolean(false)

        override fun send(
            frame: ByteArray,
            onPositionChannel: Boolean,
        ): Boolean {
            val channel = (if (onPositionChannel) pos else ctrl) ?: return false
            if (closed.get() || channel.state() != DataChannel.State.OPEN) return false
            return channel.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))
        }

        override fun close() = closeLink(this)

        fun checkOpen() {
            if (opened || closed.get()) return
            if (ctrl?.state() == DataChannel.State.OPEN && pos?.state() == DataChannel.State.OPEN &&
                openedOnce.compareAndSet(false, true)
            ) {
                val replaced =
                    synchronized(lock) {
                        negotiating.remove(peer)
                        open.put(peer, this)
                    }
                if (replaced != null && replaced !== this) closeLink(replaced, notify = false)
                EventLog.event("rtc.link.open", "peer" to MeshNode.hex(peer), "replaced" to (replaced != null))
                listener.onLinkOpen(peer, name, color, this)
            }
        }
    }

    private fun newLink(): Link? {
        val servers = stunServers().map { PeerConnection.IceServer.builder(it).createIceServer() }
        val config =
            PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            }
        val link = Link()
        val pc =
            try {
                factory.createPeerConnection(config, peerObserver(link))
            } catch (e: Exception) {
                null
            } ?: return null
        link.pc = pc
        link.ctrl =
            pc.createDataChannel(
                "ctrl",
                DataChannel.Init().apply {
                    ordered = true
                    negotiated = true
                    id = CTRL_CHANNEL_ID
                },
            )
        link.pos =
            pc.createDataChannel(
                "pos",
                DataChannel.Init().apply {
                    ordered = false
                    maxRetransmits = 0
                    negotiated = true
                    id = POS_CHANNEL_ID
                },
            )
        link.ctrl?.registerObserver(channelObserver(link, link.ctrl!!))
        link.pos?.registerObserver(channelObserver(link, link.pos!!))
        return link
    }

    private fun closeLink(
        link: Link,
        notify: Boolean = true,
    ) {
        if (!link.closed.compareAndSet(false, true)) return
        EventLog.event("rtc.link.close", "peer" to MeshNode.hex(link.peer), "opened" to link.opened, "notify" to notify)
        val wasRegistered =
            synchronized(lock) {
                if (negotiating[link.peer] === link) negotiating.remove(link.peer)
                if (open[link.peer] === link) {
                    open.remove(link.peer)
                    true
                } else {
                    false
                }
            }
        if (notify && link.opened && wasRegistered) listener.onLinkClosed(link.peer)
        // Disposing from a WebRTC callback thread can deadlock: always do it on our own thread.
        runCatching {
            exec.execute {
                runCatching { link.ctrl?.unregisterObserver() }
                runCatching { link.pos?.unregisterObserver() }
                runCatching { link.ctrl?.close() }
                runCatching { link.pos?.close() }
                runCatching { link.pc?.close() }
                runCatching { link.ctrl?.dispose() }
                runCatching { link.pos?.dispose() }
                runCatching { link.pc?.dispose() }
            }
        }
    }

    // ---- observers -------------------------------------------------------------------------------------------

    private fun channelObserver(
        link: Link,
        channel: DataChannel,
    ) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            when (channel.state()) {
                DataChannel.State.OPEN -> link.checkOpen()
                DataChannel.State.CLOSED -> closeLink(link)
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            if (link.opened && !link.closed.get()) listener.onFrame(link.peer, bytes)
        }
    }

    private fun peerObserver(link: Link) =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                EventLog.event("rtc.ice", "peer" to MeshNode.hex(link.peer), "state" to state)
                // DISCONNECTED often heals by itself; the mesh's own silence timer decides when it is over.
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.CLOSED
                ) {
                    closeLink(link)
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) link.onGathered?.invoke()
            }

            override fun onIceCandidate(candidate: IceCandidate) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit
        }

    private fun observer(
        onCreated: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {},
        onFail: () -> Unit = {},
    ) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreated(sdp)

        override fun onSetSuccess() = onSet()

        override fun onCreateFailure(error: String?) = onFail()

        override fun onSetFailure(error: String?) = onFail()
    }

    private companion object {
        const val GATHER_TIMEOUT_S = 5L
        const val OPEN_TIMEOUT_S = 30L
        const val CTRL_CHANNEL_ID = 0
        const val POS_CHANNEL_ID = 1
        const val MS_PER_S = 1000L
    }
}

/** Builds [WebRtcLinks] for the app. */
class WebRtcTransportFactory(
    private val context: Context,
) : TransportFactory {
    override fun create(
        listener: LinkListener,
        stunServers: () -> List<String>,
    ): PairingTransport = WebRtcLinks(context, stunServers, listener)
}
