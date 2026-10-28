package fr.toolmap.share

import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.p2p.DiagChannel
import fr.toolmap.core.p2p.GroupRecord
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.MeshListener
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.MeshTransport
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PairingKind
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.PairingTransport
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.RoomInvite
import fr.toolmap.core.p2p.RoomInviteCodec
import fr.toolmap.core.p2p.SentPosition
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.ShareSettings
import fr.toolmap.core.p2p.ShareState
import fr.toolmap.core.p2p.TransportFactory
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.record.Fix
import fr.toolmap.p2p.ShareStore
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the user can be told went wrong with a code or a group. */
enum class ShareProblem {
    /** The code is not one of ours, or damaged (a QR code half read, a link cut by a messenger). */
    BAD_CODE,

    /** The code is older than [PairingCodec.MAX_AGE_SEC]. */
    EXPIRED,

    /** The phone clock and the one that made the code differ by more than the validity of a code. */
    CLOCK_OFF,

    /** The code belongs to another group than the one this device is in. */
    OTHER_GROUP,

    /** This kind of code is not what is expected now (an answer without an invitation, a mesh code). */
    NOT_EXPECTED,

    /** The code could not be prepared: no network interface to gather addresses from. */
    NO_NETWORK,

    /** The link did not open in time after the answer was given. */
    PAIRING_TIMEOUT,
}

/** A pairing code ready to be shown as a QR code or sent as a link. */
class ShareCode(
    val qrText: String,
    val link: String,
    val expiresAtSec: Long,
)

class SosAlert(
    val memberId: Long,
    val name: String,
    val fix: PeerFix?,
    val text: String,
    val receivedAtMs: Long,
    /**
     * The user saw it: the dialog does not come back for the repeats, the banner stays while they last. Until then the
     * alert stays, however long ago it came (P22.2).
     */
    val acknowledged: Boolean,
    /** When the first send of this alert came, for « son alerte de 10:41 » once it is lifted. */
    val firstAtMs: Long = receivedAtMs,
)

/**
 * An alert of the group that its author lifted (P22.2): it takes the place of the alert for a minute, and for as long
 * as its [dialog] waits for « OK » (the alert was lifted before it was seen).
 */
class SosLifted(
    val memberId: Long,
    val name: String,
    val atMs: Long,
    /** When the lifted alert was first received. */
    val alertAtMs: Long = atMs,
    val dialog: Boolean = false,
)

class ShareMessage(
    val memberId: Long,
    val name: String,
    val text: String,
    val timeMs: Long,
    val mine: Boolean,
)

/** What the sharing screen, the map layer and the service notification show. */
data class ShareUi(
    val settings: ShareSettings = ShareSettings(),
    val inGroup: Boolean = false,
    /** This device sends its position. */
    val sharing: Boolean = false,
    val gpsUnavailable: Boolean = false,
    val selfId: Long = 0L,
    /** Every member of the group, this device included. */
    val members: List<MemberView> = emptyList(),
    /** An invitation being shown, waiting for the answer of the person who scans it. */
    val invitation: ShareCode? = null,
    /** The answer to an invitation or an offer, to show to the one who made it. */
    val answer: ShareCode? = null,
    /** A code is being prepared (gathering the addresses takes a few seconds). */
    val preparing: Boolean = false,
    /** The answer was given and the link is opening. */
    val pairing: Boolean = false,
    val problem: ShareProblem? = null,
    val sos: List<SosAlert> = emptyList(),
    /** The alerts lifted by their authors a moment ago (P22.2). */
    val sosLifted: List<SosLifted> = emptyList(),
    /** This device's alert is on, from « Envoyer » to « Annuler l'alerte », past its two minutes of repeats. */
    val ownSos: Boolean = false,
    /** When this device's alert was sent, for the time its repeats still run. */
    val ownSosAtMs: Long = 0L,
    val messages: List<ShareMessage> = emptyList(),
    /** The others removed this device from the group. */
    val excluded: Boolean = false,
    /** The name of the member whose link just opened, for a short confirmation. */
    val justJoined: String? = null,
    /** The group lives on the coordination server (and not on direct WebRTC links). */
    val onServer: Boolean = false,
    /** The phone settings that stop the sharing or may stop it, in order of gravity (P21.5 o, c). */
    val alerts: List<ShareAlert> = emptyList(),
    /** How the connection to the coordination server is doing; [ServerState.NONE] without a server group. */
    val server: ServerState = ServerState.NONE,
    /** The server new groups are made on: the one chosen in the settings, else the one built into the app. */
    val serverForNewGroups: RelayConfig? = null,
    /** [serverForNewGroups] comes from the build, not from the user. */
    val serverIsBuiltIn: Boolean = false,
    /** The name the others see: the one chosen, else the phone's (P29.8: the line « vous » shows it). */
    val myName: String = "",
    /** Messages of the others received since the page of the messages was last open (P29.8). */
    val unread: Int = 0,
) {
    val self: MemberView? get() = members.firstOrNull { it.isSelf }
    val others: List<MemberView> get() = members.filter { !it.isSelf }
}

/**
 * The sharing of positions for the whole process (ADR-004, ADR-021): owns the group (a [MeshNode] over a
 * [PairingTransport]), the persisted settings, the pairing codes being shown, and sends this device's position when
 * sharing is on. The foreground service only keeps the process alive; the map layer and the screens read [state].
 *
 * It is also the [DiagChannel] of the debug build's test tools (P21) and writes what it does in the event log.
 */
class ShareController(
    private val store: ShareStore,
    private val transports: TransportFactory,
    private val location: LocationSource,
    /** Makes the transport of a group that lives on a server (ADR-033); `null` keeps the direct mode only. */
    private val relays: RelayTransportFactory? = null,
    /** The server built into the app (from the build configuration), used when the user chose none. */
    private val builtInServer: RelayConfig? = null,
    private val deviceName: () -> String = { "ToolMap" },
    private val batteryPercent: () -> Int? = { null },
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Follows the default network for the server connection (P21.5 f, h); `null` in the tests that need none. */
    private val network: NetworkWatch? = null,
    /** Reads the phone settings that stop the sharing (P21.5 o, c); `null` in the tests that need none. */
    private val phone: PhoneChecks? = null,
) : DiagChannel {
    private val lock = Any()
    private var settings = ShareSettings()
    private var group: GroupRecord? = null
    private var node: MeshNode? = null
    private var transport: PairingTransport? = null
    private var relay: RelayTransport? = null
    private var networkWatch: AutoCloseable? = null
    private var invitation: PairingTransport.Invitation? = null
    private var ticker: Job? = null
    private var gps: Job? = null
    private var lastSent: SentPosition? = null
    private var lastFix: PeerFix? = null
    private var pairingSince = 0L
    private var loaded = false
    private val sosAlerts = LinkedHashMap<Long, SosAlert>()

    /** Guarded by [sosAlerts], like it. */
    private val liftedAlerts = LinkedHashMap<Long, SosLifted>()
    private val messages = ArrayList<ShareMessage>()

    /** Messages of the others received since their page was last open (P29.8). */
    private var unreadCount = 0

    /** The status of each member at the last [refresh], to log the changes. */
    private val lastStatus = HashMap<Long, MemberStatus>()
    private var lastFixAtMs = 0L
    private var lastSentAtMs = 0L

    /** The message number last kept in the store, and when (P21.5 m). */
    private var keptSeq = 0L
    private var keptSeqAtMs = 0L
    private var phoneCheckedAtMs = 0L

    private val mutableState = MutableStateFlow(ShareUi())
    val state: StateFlow<ShareUi> = mutableState.asStateFlow()

    @Volatile
    override var diagReceiver: ((member: Long, body: ByteArray) -> Unit)? = null

    override val selfId: Long get() = group?.selfId ?: 0L

    override val canSend: Boolean
        get() {
            val linked = node?.linkedIds()?.isNotEmpty() == true
            return linked && relay.let { it == null || it.state == ServerState.ONLINE }
        }

    override val busy: Boolean get() = (relay?.pendingWrites ?: 0) > 0

    override fun sendDiag(body: ByteArray): Boolean = node?.sendDiag(body) ?: false

    init {
        EventLog.gauge("share", ::gaugeValues)
    }

    private val links =
        object : LinkListener {
            override fun onLinkOpen(
                peer: Long,
                name: String,
                color: Int,
                link: MeshLink,
            ) {
                val current = node
                if (current == null) {
                    link.close()
                    return
                }
                current.onLinkOpen(peer, name, color, link)
                mutableState.update {
                    it.copy(
                        invitation = null,
                        answer = null,
                        pairing = false,
                        preparing = false,
                        justJoined = name,
                    )
                }
                invitation = null
            }

            override fun onLinkClosed(peer: Long) {
                node?.onLinkClosed(peer)
            }

            override fun onFrame(
                peer: Long,
                bytes: ByteArray,
            ) {
                node?.onFrame(peer, bytes)
            }
        }

    /** The default network, told to the server connection while a group on a server exists (P21.5 f, h). */
    private val networkEvents =
        object : NetworkWatch.Listener {
            override fun onNetwork(
                name: String,
                retry: Boolean,
            ) {
                relay?.onNetwork(name)
                if (retry) reconnectServer()
            }

            override fun onBlocked(blocked: Boolean) {
                relay?.onBlocked(blocked)
            }

            override fun onOffline() {
                relay?.onOffline()
            }

            override fun onClockChanged() {
                relay?.onClockChanged()
            }
        }

    private val mesh =
        object : MeshListener {
            override fun onChanged() = refresh()

            override fun onPosition(
                member: Long,
                fix: PeerFix,
            ) = Unit

            override fun onText(
                member: Long,
                text: String,
            ) {
                val name = node?.members()?.firstOrNull { it.id == member }?.name ?: "?"
                EventLog.event("share.text.in", "member" to MeshNode.hex(member), "chars" to text.length)
                synchronized(messages) {
                    messages += ShareMessage(member, name, text, clockMs(), mine = false)
                    unreadCount++
                    while (messages.size > MAX_MESSAGES) messages.removeAt(0)
                }
                refresh()
            }

            override fun onSos(
                member: Long,
                fix: PeerFix?,
                text: String,
            ) {
                val name = node?.members()?.firstOrNull { it.id == member }?.name ?: "?"
                EventLog.event("share.sos.in", "member" to MeshNode.hex(member), "fix" to (fix != null))
                synchronized(sosAlerts) {
                    val before = sosAlerts[member]
                    val now = clockMs()
                    sosAlerts[member] =
                        SosAlert(member, name, fix, text, now, before?.acknowledged ?: false, before?.firstAtMs ?: now)
                    liftedAlerts.remove(member)
                }
                refresh()
            }

            override fun onSosLifted(member: Long) {
                val name = node?.members()?.firstOrNull { it.id == member }?.name ?: "?"
                EventLog.event("share.sos.lifted", "member" to MeshNode.hex(member))
                synchronized(sosAlerts) {
                    val alert = sosAlerts.remove(member)
                    val now = clockMs()
                    liftedAlerts[member] =
                        SosLifted(member, name, now, alert?.firstAtMs ?: now, dialog = alert?.acknowledged == false)
                }
                refresh()
            }

            override fun onRekeyed(key: ByteArray) {
                EventLog.event("share.rekey")
                synchronized(lock) {
                    val g = group ?: return
                    group = GroupRecord(g.groupId, key, g.selfId, g.server)
                    save()
                }
            }

            override fun onExcluded() {
                EventLog.event("share.excluded")
                synchronized(lock) { discardGroup() }
                mutableState.update { it.copy(excluded = true) }
            }

            override fun onDiag(
                member: Long,
                body: ByteArray,
            ) {
                diagReceiver?.invoke(member, body)
            }
        }

    /** Reads the saved settings and group (once); a saved group is joined again with no link open. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val saved = store.read()
            settings = saved.settings
            group = saved.group
            EventLog.event(
                "share.load",
                "group" to (saved.group != null),
                "server" to (saved.group?.server != null),
                "intervalS" to settings.intervalSec,
            )
            saved.group?.let { startNode(it) }
            mutableState.update { it.copy(settings = settings, inGroup = group != null, selfId = group?.selfId ?: 0L) }
            refresh()
        }
    }

    // ---- group ------------------------------------------------------------------------------------------------

    /** Makes a new group with this device as its only member. */
    fun createGroup() {
        synchronized(lock) {
            if (group != null) return
            val g = GroupRecord.create(server = serverForNewGroups())
            EventLog.event("share.group.create", "server" to (g.server != null))
            group = g
            save()
            startNode(g)
            mutableState.update { it.copy(inGroup = true, selfId = g.selfId, excluded = false, problem = null) }
            refresh()
        }
    }

    /** Leaves the group: the others are told and every link closes. */
    fun leaveGroup() {
        EventLog.event("share.group.leave")
        synchronized(lock) {
            node?.leave()
            discardGroup()
        }
    }

    /** Takes the member out of the group; the group key changes so that it cannot read anything from now on. */
    fun removeMember(memberId: Long) {
        EventLog.event("share.member.remove", "member" to MeshNode.hex(memberId))
        relay?.ban(memberId)
        node?.remove(memberId)
        synchronized(sosAlerts) {
            sosAlerts.remove(memberId)
            liftedAlerts.remove(memberId)
        }
        refresh()
    }

    // ---- pairing ----------------------------------------------------------------------------------------------

    /** Prepares an invitation (a new group is made if there is none): it appears in [ShareUi.invitation]. */
    fun invite() {
        synchronized(lock) {
            if (group == null) createGroup()
            val g = group ?: return
            EventLog.event("share.invite", "server" to (g.server != null))
            g.server?.let { return showRoomInvite(g, it) }
            val t = transport ?: return
            cancelInvitation()
            t.identity = identity(g)
            mutableState.update { it.copy(preparing = true, problem = null, justJoined = null) }
            t.invite(g.key) { made ->
                if (made == null) {
                    mutableState.update { it.copy(preparing = false, problem = ShareProblem.NO_NETWORK) }
                    return@invite
                }
                synchronized(lock) {
                    if (transport !== t) {
                        made.cancel()
                        return@invite
                    }
                    invitation = made
                }
                mutableState.update { it.copy(preparing = false, invitation = codeOf(made.payload)) }
            }
        }
    }

    /** An invitation to a server group needs no answer: the code is ready at once and anyone who scans it is in. */
    private fun showRoomInvite(
        g: GroupRecord,
        server: RelayConfig,
    ) {
        val invite = RoomInvite(clockMs() / MS_PER_S, g.groupId, g.key, g.selfId, displayName(), server)
        val code =
            ShareCode(
                RoomInviteCodec.toQrText(invite),
                RoomInviteCodec.toLink(invite),
                invite.createdAtSec + RoomInviteCodec.MAX_AGE_SEC,
            )
        mutableState.update { it.copy(invitation = code, answer = null, preparing = false, problem = null) }
    }

    fun cancelInvitation() {
        invitation?.cancel()
        invitation = null
        mutableState.update { it.copy(invitation = null, answer = null, preparing = false, pairing = false) }
    }

    /**
     * Something was scanned or pasted: an invitation to join (a group is joined or made and an answer appears in
     * [ShareUi.answer]) or the answer to the invitation being shown (the link opens by itself).
     */
    fun handleCode(text: String) {
        val room =
            try {
                RoomInviteCodec.parseOrNull(text)
            } catch (e: WireException) {
                return problem(ShareProblem.BAD_CODE)
            }
        EventLog.event("share.code", "room" to (room != null), "chars" to text.length)
        if (room != null) return joinRoom(room)
        val payload =
            try {
                PairingCodec.parse(text)
            } catch (e: WireException) {
                return problem(ShareProblem.BAD_CODE)
            }
        val nowSec = clockMs() / MS_PER_S
        PairingCodec.staleness(payload, nowSec)?.let {
            return problem(if (it == PairingCodec.Staleness.EXPIRED) ShareProblem.EXPIRED else ShareProblem.CLOCK_OFF)
        }
        when (payload.kind) {
            PairingKind.OFFER -> join(payload)
            PairingKind.ANSWER -> acceptAnswer(payload)
            PairingKind.MESH_OFFER -> problem(ShareProblem.NOT_EXPECTED)
        }
    }

    /** An invitation to a group on a server: the group is joined at once, the others appear when they are present. */
    private fun joinRoom(invite: RoomInvite) {
        RoomInviteCodec.staleness(invite, clockMs() / MS_PER_S)?.let {
            return problem(if (it == PairingCodec.Staleness.EXPIRED) ShareProblem.EXPIRED else ShareProblem.CLOCK_OFF)
        }
        if (relays == null) return problem(ShareProblem.NOT_EXPECTED)
        synchronized(lock) {
            val existing = group
            if (existing != null) {
                if (!existing.groupId.contentEquals(invite.groupId)) return problem(ShareProblem.OTHER_GROUP)
                mutableState.update { it.copy(justJoined = invite.inviterName, problem = null) }
                return
            }
            val g = GroupRecord(invite.groupId, invite.groupKey, GroupRecord.newMemberId(), invite.server)
            EventLog.event(
                "share.group.join",
                "inviter" to MeshNode.hex(invite.inviterId),
                "me" to MeshNode.hex(g.selfId),
            )
            group = g
            save()
            startNode(g)
            mutableState.update {
                it.copy(
                    inGroup = true,
                    selfId = g.selfId,
                    excluded = false,
                    problem = null,
                    justJoined = invite.inviterName,
                )
            }
            refresh()
        }
    }

    private fun join(offer: PairingPayload) {
        synchronized(lock) {
            val existing = group
            if (existing != null && !existing.groupId.contentEquals(offer.groupId)) {
                return problem(ShareProblem.OTHER_GROUP)
            }
            val madeHere = existing == null
            val g = existing ?: GroupRecord(offer.groupId, offer.groupKey!!, GroupRecord.newMemberId())
            if (madeHere) {
                group = g
                save()
                startNode(g)
                mutableState.update { it.copy(inGroup = true, selfId = g.selfId, excluded = false) }
            }
            val t = transport ?: return
            cancelInvitation()
            t.identity = identity(g)
            mutableState.update { it.copy(preparing = true, problem = null, justJoined = null) }
            t.join(offer) { answer ->
                if (answer == null) {
                    synchronized(lock) {
                        if (madeHere && transport === t &&
                            node?.linkedIds()?.isEmpty() == true
                        ) {
                            discardGroup()
                        }
                    }
                    mutableState.update { it.copy(preparing = false, problem = ShareProblem.NO_NETWORK) }
                    return@join
                }
                pairingSince = clockMs()
                mutableState.update { it.copy(preparing = false, answer = codeOf(answer), pairing = true) }
            }
        }
    }

    private fun acceptAnswer(answer: PairingPayload) {
        val pending = invitation ?: return problem(ShareProblem.NOT_EXPECTED)
        val g = group
        if (g == null || !g.groupId.contentEquals(answer.groupId)) return problem(ShareProblem.OTHER_GROUP)
        pairingSince = clockMs()
        mutableState.update { it.copy(invitation = null, pairing = true, problem = null) }
        pending.accept(answer)
    }

    fun clearProblem() = mutableState.update { it.copy(problem = null) }

    fun clearJoined() = mutableState.update { it.copy(justJoined = null) }

    /** The page of the messages is open: every message received so far is read. */
    fun markMessagesRead() {
        synchronized(messages) { unreadCount = 0 }
        refresh()
    }

    fun clearExcluded() = mutableState.update { it.copy(excluded = false) }

    private fun problem(p: ShareProblem) {
        EventLog.event("share.problem", "problem" to p)
        mutableState.update { it.copy(problem = p, preparing = false) }
    }

    // ---- sending ------------------------------------------------------------------------------------------------

    /** Starts or stops sending this device's position. The caller holds the location permission. */
    fun setSharing(on: Boolean) {
        synchronized(lock) {
            EventLog.event("share.sharing", "on" to on, "was" to (gps != null))
            if (!on) {
                gps?.cancel()
                gps = null
                mutableState.update { it.copy(sharing = false) }
                return
            }
            if (group == null) createGroup()
            if (gps != null) return
            lastSent = null
            mutableState.update { it.copy(sharing = true, gpsUnavailable = false) }
            startFixes()
        }
    }

    /** Asks the GPS for fixes at the current interval; called under [lock], with no request running. */
    private fun startFixes() {
        gps =
            scope.launch {
                location
                    .fixes(settings.intervalSec * MS_PER_S, 0f)
                    .catch { e ->
                        EventLog.event("share.gps.error", "error" to e.javaClass.simpleName, "why" to e.message)
                        gps = null
                        mutableState.update { s -> s.copy(sharing = false, gpsUnavailable = true) }
                    }.collect { fix -> onFix(fix) }
            }
    }

    /** The network changed or came back: reconnect to the server at once (no-op in direct mode). */
    fun reconnectServer() {
        EventLog.event("share.reconnect", "server" to (relay != null))
        relay?.reconnectNow()
    }

    private fun onFix(fix: Fix) {
        val peerFix = toPeerFix(fix)
        lastFix = peerFix
        val now = clockMs()
        lastFixAtMs = now
        val cadence = settings.cadence()
        val send = cadence.shouldSend(lastSent, peerFix)
        // Coordinates only in the test mode: the everyday log keeps no trace of where the user was.
        EventLog.event(
            "share.fix",
            "send" to send,
            "ageMs" to now - fix.timeMillis,
            "acc" to peerFix.accuracyM,
            "speed" to peerFix.speedMs,
            "lat" to peerFix.lat.takeIf { EventLog.verbose },
            "lon" to peerFix.lon.takeIf { EventLog.verbose },
            "sinceSentS" to lastSent?.let { peerFix.timeSec - it.timeSec },
        )
        if (!send) return
        lastSent = SentPosition(peerFix.timeSec, peerFix.lat, peerFix.lon)
        lastSentAtMs = now
        node?.publish(peerFix)
    }

    private fun toPeerFix(fix: Fix) =
        PeerFix(
            lat = fix.lat,
            lon = fix.lon,
            altM = fix.ele?.let { Math.round(it).toInt() },
            accuracyM = fix.accuracyMeters?.let { Math.round(it).toInt() },
            speedMs = fix.speedMps,
            timeSec = (if (fix.timeMillis > 0) fix.timeMillis else clockMs()) / MS_PER_S,
            battery = batteryPercent(),
        )

    fun sendText(text: String) {
        val clean = text.trim()
        val n = node ?: return
        if (clean.isEmpty()) return
        EventLog.event("share.text.out", "chars" to clean.length)
        n.sendText(clean)
        synchronized(messages) {
            messages += ShareMessage(group?.selfId ?: 0L, settings.name, clean, clockMs(), mine = true)
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        }
        refresh()
    }

    /** Sends an alert with the last known position, repeated for two minutes; it stays on until [cancelSos] (P22.2). */
    fun startSos(text: String) {
        EventLog.event("share.sos.start", "fix" to (lastFix != null))
        node?.startSos(lastFix, text.trim())
        mutableState.update { it.copy(ownSos = true, ownSosAtMs = clockMs()) }
    }

    /** « Annuler l'alerte »: the others are told it is lifted (P22.2). */
    fun cancelSos() {
        EventLog.event("share.sos.cancel")
        node?.cancelSos()
        mutableState.update { it.copy(ownSos = false) }
    }

    fun acknowledgeSos(memberId: Long) {
        synchronized(sosAlerts) {
            sosAlerts[memberId]?.let {
                sosAlerts[memberId] =
                    SosAlert(it.memberId, it.name, it.fix, it.text, it.receivedAtMs, true, it.firstAtMs)
            }
        }
        refresh()
    }

    /** « OK » on « Alerte levée »: its banner then goes with the minute (P22). */
    fun acknowledgeLift(memberId: Long) {
        synchronized(sosAlerts) {
            liftedAlerts[memberId]?.let {
                liftedAlerts[memberId] = SosLifted(it.memberId, it.name, it.atMs, it.alertAtMs, dialog = false)
            }
        }
        refresh()
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    /** Sets what the others see of this device and how often it sends. */
    fun updateSettings(update: (ShareSettings) -> ShareSettings) {
        synchronized(lock) {
            val next = ShareState(update(settings), group).let { ShareState.fromJson(it.toJson()).settings }
            val rhythmChanged = next.intervalSec != settings.intervalSec
            EventLog.event(
                "share.settings",
                "intervalS" to next.intervalSec,
                "minMoveM" to next.minMoveMeters,
                "stun" to next.stun,
                "server" to (next.server != null),
            )
            settings = next
            save()
            group?.let { g ->
                transport?.identity = identity(g)
                relay?.identity = identity(g)
                node?.setSelf(displayName(), next.look())
                node?.setPicture(next.pictureBytes())
            }
            mutableState.update { it.copy(settings = next) }
            // Only the GPS request changes: the sharing never goes "off", which would stop its service (P21.5 d).
            if (rhythmChanged && gps != null) {
                EventLog.event("share.gps.restart", "intervalS" to next.intervalSec)
                gps?.cancel()
                startFixes()
            }
            refresh()
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    private fun displayName() = settings.name.ifBlank { deviceName() }

    private fun identity(g: GroupRecord) = TransportIdentity(g.groupId, g.selfId, displayName(), settings.look())

    private fun codeOf(p: PairingPayload) =
        ShareCode(PairingCodec.toQrText(p), PairingCodec.toLink(p), p.createdAtSec + PairingCodec.MAX_AGE_SEC)

    private fun save() = runCatching { store.write(ShareState(settings, group)) }

    private fun startNode(g: GroupRecord) {
        val server = g.server
        EventLog.event(
            "share.node.start",
            "server" to (server != null && relays != null),
            "me" to MeshNode.hex(g.selfId),
        )
        val medium: MeshTransport
        if (server != null && relays != null) {
            val r = relays.create(server, g.groupId, links)
            r.identity = identity(g)
            r.onState = { state -> mutableState.update { it.copy(server = state) } }
            relay = r
            transport = null
            medium = r
        } else {
            val t = transports.create(links) { if (settings.stun) ShareSettings.STUN_SERVERS else emptyList() }
            t.identity = identity(g)
            transport = t
            relay = null
            medium = t
        }
        node =
            MeshNode(
                groupId = g.groupId,
                groupKey = g.key,
                selfId = g.selfId,
                selfName = displayName(),
                selfColor = settings.look(),
                transport = medium,
                listener = mesh,
                clock = clockMs,
                seqFloor = store.readSeq()?.let { it + SEQ_MARGIN } ?: 0L,
            )
        node?.setPicture(settings.pictureBytes())
        networkWatch?.close()
        // Before the connection starts: it then knows the network it opens its stream on, and whether it may.
        networkWatch = relay?.let { network?.start(networkEvents) }
        relay?.start()
        ticker?.cancel()
        ticker =
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    tick()
                }
            }
    }

    /** The server a group made now lives on: the user's choice, else the built-in one, else none (direct mode). */
    private fun serverForNewGroups(): RelayConfig? =
        if (relays == null) null else (settings.server ?: builtInServer)?.takeIf(RelayConfig::isUsable)

    private fun tick() {
        node?.tick()
        val now = clockMs()
        keepSeq(now)
        if (now < phoneCheckedAtMs || now - phoneCheckedAtMs >= PHONE_CHECK_MS) {
            phoneCheckedAtMs = now
            recheckPhone()
        }
        synchronized(sosAlerts) {
            // An alert not seen yet stays (P22.2); a seen one goes two minutes after its last repeat.
            sosAlerts.entries.removeAll { it.value.acknowledged && now - it.value.receivedAtMs > SOS_STALE_MS }
            liftedAlerts.entries.removeAll { !it.value.dialog && now - it.value.atMs > LIFTED_SHOW_MS }
        }
        val expired = state.value.invitation?.let { it.expiresAtSec <= now / MS_PER_S } == true
        if (expired) cancelInvitation()
        val ui = state.value
        if (ui.pairing && pairingSince > 0 && now - pairingSince > PAIRING_TIMEOUT_MS) {
            invitation = null
            mutableState.update { it.copy(pairing = false, answer = null, problem = ShareProblem.PAIRING_TIMEOUT) }
        }
        refresh()
    }

    /**
     * Reads the phone settings that stop the sharing again: every [PHONE_CHECK_MS] in a group, and at once when the
     * screen comes back or the sharing starts (P21.5 o, c).
     */
    fun recheckPhone(): List<ShareAlert> {
        val alerts = phone?.alerts().orEmpty()
        if (alerts != state.value.alerts) {
            EventLog.event("share.alerts", "alerts" to alerts.joinToString(",").ifEmpty { "none" })
            mutableState.update { it.copy(alerts = alerts) }
        }
        return alerts
    }

    /** Keeps the last message number at most every [SEQ_KEEP_MS], so that the next process starts above it (P21.5 m). */
    private fun keepSeq(now: Long) {
        val seq = node?.lastSeq ?: return
        if (seq == keptSeq || (now >= keptSeqAtMs && now - keptSeqAtMs < SEQ_KEEP_MS)) return
        keptSeq = seq
        keptSeqAtMs = now
        store.writeSeq(seq)
    }

    /** Forgets the group (after leaving, being removed, or a failed first pairing). Call under [lock]. */
    private fun discardGroup() {
        EventLog.event("share.group.discard")
        gps?.cancel()
        gps = null
        ticker?.cancel()
        ticker = null
        invitation?.cancel()
        invitation = null
        transport?.close()
        transport = null
        networkWatch?.close()
        networkWatch = null
        relay?.close()
        relay = null
        node = null
        group = null
        lastSent = null
        synchronized(sosAlerts) {
            sosAlerts.clear()
            liftedAlerts.clear()
        }
        synchronized(messages) {
            messages.clear()
            unreadCount = 0
        }
        save()
        mutableState.update {
            ShareUi(settings = settings, excluded = it.excluded)
        }
        refresh()
    }

    private fun refresh() {
        val n = node
        val members = n?.members().orEmpty()
        logStatusChanges(members)
        val alerts = synchronized(sosAlerts) { sosAlerts.values.toList() }
        val lifted = synchronized(sosAlerts) { liftedAlerts.values.toList() }
        val texts = synchronized(messages) { messages.toList() }
        mutableState.update {
            it.copy(
                members = members.sortedWith(compareBy({ m -> !m.isSelf }, { m -> m.name.lowercase() })),
                inGroup = n != null,
                selfId = group?.selfId ?: 0L,
                sos = alerts,
                sosLifted = lifted,
                messages = texts,
                settings = settings,
                onServer = relay != null,
                server = relay?.state ?: ServerState.NONE,
                serverForNewGroups = serverForNewGroups(),
                serverIsBuiltIn = settings.server == null && serverForNewGroups() != null,
                myName = displayName(),
                unread = synchronized(messages) { unreadCount },
            )
        }
    }

    /** Logs each member whose status (linked, relayed, lost, left) changed since the last call. */
    private fun logStatusChanges(members: List<MemberView>) {
        val now = clockMs()
        synchronized(lastStatus) {
            for (m in members) {
                if (m.isSelf) continue
                val before = lastStatus.put(m.id, m.status)
                if (before == m.status) continue
                EventLog.event(
                    "share.peer",
                    "member" to MeshNode.hex(m.id),
                    "name" to m.name,
                    "from" to before,
                    "to" to m.status,
                    "heardAgoS" to (now - m.lastHeardMs) / MS_PER_S,
                    "fixAgoS" to m.fix?.let { now / MS_PER_S - it.timeSec },
                )
            }
            val gone = lastStatus.keys - members.map { it.id }.toSet()
            for (id in gone) {
                lastStatus.remove(id)
                EventLog.event("share.peer", "member" to MeshNode.hex(id), "to" to "GONE")
            }
        }
    }

    /** What the snapshots of the event log read of the sharing (P21). */
    private fun gaugeValues(): Map<String, Any?> {
        val now = clockMs()
        val ui = state.value
        return mapOf(
            "group" to ui.inGroup,
            "server" to ui.server,
            "sharing" to ui.sharing,
            "gps" to (gps?.isActive == true),
            "gpsUnavailable" to ui.gpsUnavailable,
            "alerts" to ui.alerts.joinToString(","),
            "intervalS" to settings.intervalSec,
            "lastFixAgoS" to lastFixAtMs.takeIf { it > 0 }?.let { (now - it) / MS_PER_S },
            "lastSentAgoS" to lastSentAtMs.takeIf { it > 0 }?.let { (now - it) / MS_PER_S },
            "links" to node?.linkedIds()?.size,
            "members" to
                ui.others.joinToString(",") { m ->
                    MeshNode.hex(m.id) + ":" + m.status + ":" + (now - m.lastHeardMs) / MS_PER_S + "s:" +
                        (m.fix?.let { "fix" + (now / MS_PER_S - it.timeSec) + "s" } ?: "nofix")
                },
        )
    }

    private companion object {
        const val TICK_MS = 1_000L

        /** Messages sent after the last kept number: far more than 10 s of the test mode (P21.5 m). */
        const val SEQ_MARGIN = 1_000L
        const val SEQ_KEEP_MS = 10_000L
        const val PHONE_CHECK_MS = 5_000L
        const val MS_PER_S = 1_000L
        const val MAX_MESSAGES = 50
        const val SOS_STALE_MS = 120_000L

        /** How long « Alerte levée par … » stays (P22.2). */
        const val LIFTED_SHOW_MS = 60_000L
        const val PAIRING_TIMEOUT_MS = 45_000L
    }
}
