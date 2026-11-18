package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.DissolutionAnnouncer
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.HiddenName
import fr.toolmap.core.hierarchy.HierarchySnapshot
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.LoggedMessage
import fr.toolmap.core.hierarchy.MessageLog
import fr.toolmap.core.hierarchy.NameLabel
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.ObjectRef
import fr.toolmap.core.hierarchy.OperationEnding
import fr.toolmap.core.hierarchy.OperationInvite
import fr.toolmap.core.hierarchy.OperationInviteCodec
import fr.toolmap.core.hierarchy.OperationLinkState
import fr.toolmap.core.hierarchy.OperationListener
import fr.toolmap.core.hierarchy.OperationNode
import fr.toolmap.core.hierarchy.OperationText
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.Rejection
import fr.toolmap.core.hierarchy.Role
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.hierarchy.SharedRecord
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPresence
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.problem.catchReported
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Where this phone stands in the operation it runs. */
enum class OperationPhase {
    /** No operation open. */
    NONE,

    /** It holds an invitation and asks to come in; nobody let it in yet. */
    ASKING,

    /** Let in, without a group: only the administrators see it (Q10). */
    WAITING,

    /** In a group, or holding a post that gives the access without one. */
    PLACED,

    /** Over for this phone ([OperationView.ending]): everything it held was wiped. */
    ENDED,
}

/** A member as the screens show it. [name] is `null` while this phone does not know it (write something else then). */
data class MemberView(
    val id: Long,
    val name: String?,
    val roles: Set<Role>,
    val group: Long?,
    val waiting: Boolean,
    val online: Boolean,
    /** How its name is written on its marker of this phone's map (Q17): whole, or initials. */
    val label: NameLabel,
)

data class GroupView(
    val id: Long,
    val name: String?,
    val parent: Long,
    val leader: Long?,
    val deputy: Long?,
    val members: List<Long>,
)

/** A phone that asks to come in, as the creator and the administrators see it. */
data class JoinRequestView(
    val member: Long,
    /** The name it chose, `null` when its card did not reach this phone (it asked before it knew this administrator). */
    val name: String?,
    val receivedAtMs: Long,
)

data class ReceivedPosition(
    val fix: PeerFix,
    val channel: Channel,
    val receivedAtMs: Long,
)

data class ThreadMessage(
    val author: Long,
    val text: String,
    val sentAtMs: Long,
    val receivedAtMs: Long,
)

data class ReceivedAlert(
    val author: Long,
    val channel: Channel,
    val text: String,
    val fix: PeerFix?,
    val sentAtMs: Long,
    val receivedAtMs: Long,
)

/**
 * Where this phone may share each kind now (`docs/specs/group-hierarchy.md` § 4.2, § 10 quinquies and sexies): the
 * screens offer these lines and no other. [alert] is the one place its alert goes (`null`: it waits), [alertReaders]
 * who reads it (itself left out: empty for a creator alone, the screen must say the alert reaches nobody).
 */
data class ShareTargets(
    val position: Channel?,
    val messages: List<Channel>,
    val sketches: List<Channel>,
    val waypoints: List<Channel>,
    val alert: Channel?,
    val alertReaders: List<Long>,
)

/**
 * A request of a new name nobody answered yet (2026-11-16), as the creator, the administrators and the one who asked
 * see it: [requested] is `null` while this phone does not hold its card; [seenAtMs] is when this phone first saw it.
 */
data class NameRequestView(
    val member: Long,
    val requested: String?,
    val seenAtMs: Long,
)

/**
 * The last answer to the request of a new name of [member]: who answered ([by]), which name ([name], `null` while
 * this phone does not hold its card), accepted or refused. Kept until the member asks again.
 */
data class NameAnswerView(
    val member: Long,
    val by: Long,
    val name: String?,
    val accepted: Boolean,
)

/** A change this phone wrote that the others' operations made lose afterwards ([fr.toolmap.core.hierarchy.HierarchyReplica.history]). */
data class RefusedChange(
    val seq: Int,
    val change: Change,
    val reason: Rejection,
)

/** Everything the screens of the « Opération » mode read, in one immutable value ([OperationController.view]). */
data class OperationView(
    val phase: OperationPhase = OperationPhase.NONE,
    /** The stored operation ([OperationStorage.ids]) this view is of. */
    val storageId: String? = null,
    val ensembleId: Long? = null,
    val self: Long? = null,
    /** The name of the operation: what the invitation said until its first operation arrives, then checked. */
    val operationName: String? = null,
    /** The engine's state, for what the lists below do not say (`rolesOf`, `canSee`, `readersOf`…). */
    val hierarchy: HierarchyState? = null,
    val members: List<MemberView> = emptyList(),
    val groups: List<GroupView> = emptyList(),
    val joinRequests: List<JoinRequestView> = emptyList(),
    /**
     * The requests of a new name nobody answered yet, oldest first: all of them for the creator and the
     * administrators (who answer them), this phone's own for anyone else.
     */
    val nameRequests: List<NameRequestView> = emptyList(),
    /**
     * The last answer to each member's request of a new name, by member: all of them for the creator and the
     * administrators (an answer that came too late shows who answered first), this phone's own for anyone else.
     */
    val nameAnswers: Map<Long, NameAnswerView> = emptyMap(),
    /** The last position of each member this phone may see, by member. */
    val positions: Map<Long, ReceivedPosition> = emptyMap(),
    /** The messages of each thread this phone reads, oldest first; its own included. */
    val threads: Map<Channel, List<ThreadMessage>> = emptyMap(),
    /** The alerts going on, oldest first (an alert lifted by its author is no longer here). */
    val alerts: List<ReceivedAlert> = emptyList(),
    /** The sketches and waypoints still shown, in the channels this phone reads. */
    val objects: List<SharedRecord> = emptyList(),
    val targets: ShareTargets? = null,
    /** This phone may give an invitation ([OperationController.invitationLink]). */
    val mayInvite: Boolean = false,
    /** The epoch of the invitation key: an invitation given before it changed no longer works. */
    val inviteEpoch: Int = 0,
    val server: ServerState = ServerState.NONE,
    val online: Set<Long> = emptySet(),
    val refused: List<RefusedChange> = emptyList(),
    val ending: Ending? = null,
    /**
     * Ended as dissolved, said by a phone that keeps the operation's trace (this phone was away when it was
     * dissolved: P39.9, R1), not read from the operations.
     */
    val endedByTrace: Boolean = false,
    /** Dissolved while this phone was a member: until when it keeps the trace that tells it to who was away (R1). */
    val traceKeptUntilMs: Long? = null,
)

/**
 * A trace this phone keeps of an operation dissolved while it was a member (R1): when it was dissolved, and when the
 * trace goes by itself. It names nothing: a trace holds no name.
 */
data class DissolutionTraceView(
    val dissolvedAtMs: Long,
    val keptUntilMs: Long,
)

/** What came of [OperationController.create], [OperationController.join] or [OperationController.open]. */
enum class StartResult {
    STARTED,

    /** A name the format refuses (empty, a control character, more than 64 bytes). */
    BAD_NAME,

    /** Not an invitation to an operation. */
    BAD_INVITATION,

    /** An invitation older than a week, or from the future. */
    EXPIRED,

    /** The key store or the disk refused (said to whoever debugs). */
    STORAGE_FAILED,

    /** No such stored operation, or one never created nor joined. */
    NOT_FOUND,

    /** The stored operation is over: it was wiped on reading. */
    ENDED,
}

/** What an invitation says before one joins: the name of the operation (T14) and whether it is still valid. */
data class InvitationPreview(
    val operationName: String,
    val ensembleId: Long,
    val staleness: PairingCodec.Staleness?,
)

/**
 * The « Opération » mode for the screens (P39.7, ADR-129, `docs/specs/group-hierarchy.md` § 17): one operation at a
 * time, its engine ([OperationNode]), its storage ([StoredOperation]) and its room on the coordination server. Every
 * method runs on a thread of its own, in order; [view] says everything to show. No screen calls it yet.
 *
 * - Start: [create], [join] (with an invitation), [open] (a stored one, [ids]).
 * - Let in and place: [view] `.joinRequests`, [admit], [decline], [createGroup], [renameGroup], [deleteGroup],
 *   [place], [unassign], [setPost], [clearPost], [addAdmin], [removeAdmin], [removeMember], [check] to grey a button.
 * - Names (2026-11-16): [renameMember] and [renameOperation] (the creator and the administrators), [requestName] and
 *   [withdrawNameRequest] (the others), [answerName]; [view] `.nameRequests` and `.nameAnswers`.
 * - Invite: [invitationLink], [invitationQr], [renewInvitation] (also done by itself when someone leaves).
 * - Leave: [leave] (refused for the creator: [handOverAndLeave] or [dissolve]).
 * - Share: [sendPosition], [sendMessage], [sendAlert], [liftAlert], [putObject], [removeObject], where
 *   [view] `.targets` allows.
 *
 * When the operation ends for this phone (it left, was taken out, or the operation is dissolved) everything is wiped
 * and [view] says [OperationPhase.ENDED] with the reason.
 *
 * P39.9 (ADR-131, `docs/specs/group-hierarchy.md` § 20): the messages of the threads are kept, the last 500 of each,
 * sealed with the operation and wiped with it (R2). A dissolution leaves, on every phone that was a member, a trace
 * kept a week ([traces]) which tells a phone that was away; to do so this phone listens in the room of each trace for
 * a moment ([TRACE_WINDOW_MS]) when an operation starts on it, then every [TRACE_EVERY_MS] while it runs, and when the
 * screens ask ([announceDissolutions], the app in front): never otherwise, never with the screen off and no operation.
 */
class OperationController(
    private val storage: OperationStorage,
    /** `null`: no coordination server at all (the debug check): the operation lives on this phone only. */
    private val relays: RelayTransportFactory?,
    /** The server written into a new operation's invitations; `null` when none is set. */
    private val serverForNew: () -> RelayConfig?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ScheduledExecutorService = newExecutor(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mutableView = MutableStateFlow(OperationView())

    val view: StateFlow<OperationView> = mutableView.asStateFlow()

    private var running: Running? = null

    private inner class Running(
        val stored: StoredOperation,
        val contents: OperationContents,
        link: OperationLinkState,
    ) : OperationListener {
        val node =
            OperationNode(
                stored.keys,
                contents.replica,
                contents.ring,
                contents.names,
                contents.objects,
                link,
                this,
                clock,
                random,
            )
        var transport: RelayTransport? = null
        var serverState = ServerState.NONE
        val positions = HashMap<Long, ReceivedPosition>()

        /** The messages of the threads: stored, read back at [open] (R2). */
        val messages = MessageLog(contents.ensembleId)
        val alerts = LinkedHashMap<Long, ReceivedAlert>()

        /**
         * When this phone first saw each request of a new name pending now, by member ([NameRequestView.seenAtMs]).
         * In memory: after a restart, a request still pending counts as seen when the operation opened again.
         */
        val nameRequestsSeen = HashMap<Long, Pair<HiddenName, Long>>()
        var saveDue = false
        var messagesDue = false
        var ended: Ending? = null
        var ticker: ScheduledFuture<*>? = null

        /** Until when the trace this phone kept at the dissolution goes ([OperationView.traceKeptUntilMs]). */
        var traceKeptUntil: Long? = null

        init {
            stored.loadMessages()?.let { bytes ->
                catchExpected("operation.open.messages", WireException::class) {
                    HierarchySnapshot.restoreMessages(messages, bytes)
                }
            }
        }

        override fun onChanged() {
            if (!saveDue) {
                saveDue = true
                executor.execute { if (running === this) save() }
            }
        }

        override fun onPosition(
            member: Long,
            channel: Channel,
            fix: PeerFix,
        ) {
            positions[member] = ReceivedPosition(fix, channel, clock())
            publish()
        }

        override fun onMessage(
            author: Long,
            channel: Channel,
            message: OperationText,
        ) {
            addMessage(channel, LoggedMessage(author, message.id, message.sentAtMs, clock(), message.text))
            publish()
        }

        override fun onAlert(
            author: Long,
            channel: Channel,
            alert: OperationText,
        ) {
            if (alert.lifted) {
                alerts.remove(author)
            } else {
                alerts[author] = ReceivedAlert(author, channel, alert.text, alert.fix, alert.sentAtMs, clock())
            }
            publish()
        }

        override fun onEnded(ending: OperationEnding) {
            ended = if (ending == OperationEnding.DISSOLVED) Ending.DISSOLVED else Ending.OUT
            executor.execute { if (running === this) end() }
        }

        /**
         * Keeps [message] in its thread, and writes the threads a moment later (only them: a message changes nothing
         * else of the operation); a message played again (same author and identifier) is not kept twice.
         */
        fun addMessage(
            channel: Channel,
            message: LoggedMessage,
        ) {
            if (!messages.add(channel, message) || messagesDue) return
            messagesDue = true
            executor.execute { if (running === this) saveMessages() }
        }

        /** Writes the threads, those of the channels this phone no longer reads forgotten first, as their keys (R2). */
        fun saveMessages() {
            messagesDue = false
            if (ended != null) return
            val state = node.state
            if (node.self in state.members) messages.retain(state.channelsReadBy(node.self))
            stored.saveMessages(HierarchySnapshot.encodeMessages(messages))
        }

        fun save() {
            saveDue = false
            when (val result = stored.save(contents)) {
                is SaveResult.Ended -> {
                    ended = ended ?: result.ending
                    traceKeptUntil = result.traceKeptUntilMs
                    end()
                    return
                }

                // Said to whoever debugs by the storage; the previous files stay, the next change saves again.
                SaveResult.Failed -> {
                    return
                }

                SaveResult.Saved -> {
                    stored.saveLink(node.linkState().encode())
                    saveMessages()
                }
            }
            publish()
        }

        /**
         * Over: the storage is wiped (but the trace of a dissolution, when this phone was a member then: R1), the room
         * left a moment later (the last frames go out first).
         */
        fun end() {
            if (ended == Ending.DISSOLVED) {
                traceKeptUntil = stored.endDissolved(node.dissolution, node.dissolvedAtMs ?: clock(), node.linkState())
            } else {
                stored.wipe()
            }
            messages.clear()
            ticker?.cancel(false)
            val t = transport
            transport = null
            if (t !=
                null
            ) {
                executor.schedule({
                    catchReported("operation.transport.close") { t.close() }
                }, CLOSE_DELAY_S, TimeUnit.SECONDS)
            }
            publish()
        }

        fun startTransport() {
            ticker =
                executor.scheduleWithFixedDelay({
                    catchReported("operation.tick") {
                        if (running ===
                            this
                        ) {
                            node.tick()
                            if (clock() - lastWindowAt >= TRACE_EVERY_MS) openWindow()
                        }
                    }
                }, 1, 1, TimeUnit.SECONDS)
            val factory = relays ?: return
            val server = node.server ?: return
            val listener =
                object : LinkListener {
                    override fun onLinkOpen(
                        peer: Long,
                        name: String,
                        color: Int,
                        link: MeshLink,
                    ) = post {
                        node.linkOpened(peer, link)
                        // Who is online shows at once, not at the next change of the operation (P39.10).
                        publish()
                    }

                    override fun onLinkClosed(peer: Long) =
                        post {
                            node.linkClosed(peer)
                            publish()
                        }

                    override fun onFrame(
                        peer: Long,
                        bytes: ByteArray,
                    ) = post { node.receive(peer, bytes) }
                }
            val t = factory.create(server, node.room, listener, RelayPresence.OPERATION)
            // No name in clear on the server: the room only sees who writes (the member identifier) and ciphertext.
            t.identity = TransportIdentity(node.room, node.self, "", 0)
            t.onState = { state ->
                post {
                    serverState = state
                    publish()
                }
            }
            transport = t
            serverState = t.state
            t.start()
        }

        private fun post(block: () -> Unit) {
            executor.execute {
                if (running === this &&
                    ended == null
                ) {
                    catchExpected("operation.link", KeyVaultException::class) { block() }
                }
            }
        }
    }

    /**
     * A moment in the room of an operation dissolved while this phone was a member, whose trace it keeps (R1,
     * ADR-131): to every phone still in it that speaks, the proof once a minute at most ([DissolutionAnnouncer]).
     */
    private inner class Announcing(
        val trace: DissolutionTrace,
        var closesAt: Long,
    ) : LinkListener {
        val announcer = DissolutionAnnouncer(trace, clock, random)
        val links = HashMap<Long, MeshLink>()
        var transport: RelayTransport? = null

        override fun onLinkOpen(
            peer: Long,
            name: String,
            color: Int,
            link: MeshLink,
        ) = post { links[peer] = link }

        override fun onLinkClosed(peer: Long) = post { links.remove(peer) }

        override fun onFrame(
            peer: Long,
            bytes: ByteArray,
        ) = post {
            val answer = announcer.receive(bytes) ?: return@post
            // The room carries one write to everyone in it: any link of this room will do.
            (links[peer] ?: links.values.firstOrNull())?.send(answer, false)
        }

        fun close() {
            val t = transport ?: return
            transport = null
            catchReported("operation.trace.close") { t.close() }
        }

        private fun post(block: () -> Unit) {
            executor.execute {
                if (announcing[trace.ensembleId] === this) catchReported("operation.trace.frame") { block() }
            }
        }
    }

    /** The rooms of dissolved operations this phone is in now, by operation. */
    private val announcing = LinkedHashMap<Long, Announcing>()
    private var lastWindowAt = Long.MIN_VALUE / 2
    private var traceTicker: ScheduledFuture<*>? = null

    // ---- start -------------------------------------------------------------------------------------------------

    /** The stored operations of this phone. */
    fun ids(): List<String> = storage.ids()

    /** A new operation named [operationName], created by this phone as [myName]. */
    suspend fun create(
        operationName: String,
        myName: String,
    ): StartResult =
        onThread {
            if (!Names.valid(operationName) || !Names.valid(myName)) return@onThread StartResult.BAD_NAME
            stop()
            val stored = storage.create() ?: return@onThread StartResult.STORAGE_FAILED
            val genesis =
                catchExpected("operation.create", KeyVaultException::class) {
                    OperationNode.genesis(
                        stored.keys,
                        Names.hide(operationName, random),
                        Names.hide(myName, random),
                        serverForNew(),
                        random,
                    )
                }.getOrNull() ?: return@onThread failed(stored)
            val contents = OperationContents(genesis.replica, genesis.ring, genesis.names, genesis.objects)
            if (stored.save(contents) != SaveResult.Saved ||
                !stored.saveLink(genesis.link.encode())
            ) {
                return@onThread failed(stored)
            }
            run(stored, contents, genesis.link)
        }

    /** What [invitation] says, without joining. `null` when it is not an invitation to an operation. */
    fun preview(invitation: String): InvitationPreview? {
        val invite = OperationInviteCodec.parseOrNull(invitation) ?: return null
        return InvitationPreview(
            invite.ensembleCard.name,
            invite.ensembleId,
            OperationInviteCodec.staleness(
                invite,
                clock() / MS,
            ),
        )
    }

    /** Asks to come into the operation of [invitation] as [myName]; the creator or an administrator lets it in. */
    suspend fun join(
        invitation: String,
        myName: String,
    ): StartResult =
        onThread {
            if (!Names.valid(myName)) return@onThread StartResult.BAD_NAME
            val invite: OperationInvite =
                OperationInviteCodec.parseOrNull(invitation) ?: return@onThread StartResult.BAD_INVITATION
            if (OperationInviteCodec.staleness(invite, clock() / MS) != null) return@onThread StartResult.EXPIRED
            stop()
            val stored = storage.create() ?: return@onThread StartResult.STORAGE_FAILED
            val contents = stored.join(invite.ensembleId)
            val link = OperationNode.joining(invite, Names.hide(myName, random))
            if (stored.save(contents) != SaveResult.Saved ||
                !stored.saveLink(link.encode())
            ) {
                return@onThread failed(stored)
            }
            run(stored, contents, link)
        }

    /** Opens the stored operation [id] and joins its room. */
    suspend fun open(id: String): StartResult =
        onThread {
            stop()
            val stored = storage.open(id) ?: return@onThread StartResult.NOT_FOUND
            when (val loaded = stored.load()) {
                is LoadResult.Ended -> {
                    mutableView.value =
                        OperationView(
                            OperationPhase.ENDED,
                            storageId = id,
                            ending = loaded.ending,
                            traceKeptUntilMs = loaded.traceKeptUntilMs,
                        )
                    StartResult.ENDED
                }

                LoadResult.Empty -> {
                    StartResult.NOT_FOUND
                }

                LoadResult.Unavailable -> {
                    StartResult.STORAGE_FAILED
                }

                is LoadResult.Loaded -> {
                    val bytes = stored.loadLink() ?: return@onThread StartResult.STORAGE_FAILED
                    val link =
                        catchExpected("operation.open.link", WireException::class) { OperationLinkState.decode(bytes) }
                            .getOrNull() ?: return@onThread StartResult.STORAGE_FAILED
                    run(stored, loaded.contents, link)
                }
            }
        }

    /** Leaves the room and closes the operation on this phone; it stays stored. */
    suspend fun close() = onThread { stop() }

    private fun run(
        stored: StoredOperation,
        contents: OperationContents,
        link: OperationLinkState,
    ): StartResult {
        val r = Running(stored, contents, link)
        running = r
        catchExpected("operation.start", KeyVaultException::class) { r.node.start() }
        if (r.ended != null) {
            r.end()
            return StartResult.ENDED
        }
        r.startTransport()
        r.save()
        // The « Opération » sharing starts: a moment in the rooms of the operations dissolved this week (R1).
        openWindow()
        return StartResult.STARTED
    }

    private fun failed(stored: StoredOperation): StartResult {
        stored.wipe()
        return StartResult.STORAGE_FAILED
    }

    private fun stop() {
        val r = running ?: return
        running = null
        r.ticker?.cancel(false)
        r.transport?.let { t -> catchReported("operation.transport.close") { t.close() } }
        r.transport = null
        if (r.ended == null && r.saveDue) r.save()
        if (r.ended == null && r.messagesDue) r.saveMessages()
        mutableView.value = OperationView()
    }

    // ---- invite and let in -------------------------------------------------------------------------------------

    /** A link `toolmap://operation?d=…` for a new phone; `null` when this phone may not invite or has no server. */
    suspend fun invitationLink(): String? = onNode(null) { n -> n.invitation()?.let(OperationInviteCodec::toLink) }

    /** The same invitation as the text of a QR code. */
    suspend fun invitationQr(): String? = onNode(null) { n -> n.invitation()?.let(OperationInviteCodec::toQrText) }

    /** A new invitation key: invitations given before no longer work, and who was taken out reads nothing more. */
    suspend fun renewInvitation(): Boolean = onNode(false) { it.renewInvitation() }

    suspend fun admit(member: Long): Rejection? = onNode(Rejection.NO_ENSEMBLE) { it.admit(member).rejection() }

    suspend fun decline(member: Long): Boolean = onNode(false) { it.decline(member) }

    /** Why [change] would be refused now; `null` when it would go through. */
    fun check(change: Change): Rejection? = running?.node?.check(change) ?: Rejection.NO_ENSEMBLE

    /** Asks for any [change] of the engine; `null` when it is written (it may still lose later: [OperationView.refused]). */
    suspend fun change(change: Change): Rejection? = onNode(Rejection.NO_ENSEMBLE) { it.issue(change).rejection() }

    /** A new group named [name] under [parent]; its identifier, or why it was refused. */
    suspend fun createGroup(
        name: String,
        parent: Long = HierarchyState.ROOT,
    ): Pair<Long?, Rejection?> =
        onNode(null to Rejection.NO_ENSEMBLE) { n ->
            if (!Names.valid(name)) return@onNode null to Rejection.MALFORMED
            val card = Names.hide(name, random)
            var group = 0L
            while (group == 0L || group in n.state.groups || group in n.state.deletedGroups) group = random.nextLong()
            when (val r = n.issue(Change.CreateGroup(group, card.hidden, parent))) {
                is IssueResult.Issued -> {
                    n.learnAndShare(card)
                    group to null
                }

                is IssueResult.Refused -> {
                    null to r.reason
                }
            }
        }

    suspend fun renameGroup(
        group: Long,
        name: String,
    ): Rejection? =
        onNode(Rejection.NO_ENSEMBLE) { n ->
            if (!Names.valid(name)) return@onNode Rejection.MALFORMED
            val card = Names.hide(name, random)
            n.issue(Change.RenameGroup(group, card.hidden)).also {
                if (it is IssueResult.Issued) n.learnAndShare(card)
            }.rejection()
        }

    /**
     * Gives [member] the name [name] at once (the creator and the administrators, for anyone and for themselves:
     * 2026-11-16); a request of that member nobody answered goes. [Rejection.MALFORMED] for a name the format
     * refuses (it is trimmed first).
     */
    suspend fun renameMember(
        member: Long,
        name: String,
    ): Rejection? = issueNamed(name) { _, hidden -> Change.RenameMember(member, hidden) }

    /**
     * Asks for the new name [name] for this phone (everyone but the creator and the administrators, who rename
     * themselves: [renameMember]); it replaces a request of before nobody answered. The name does not change until
     * the creator or an administrator accepts it ([OperationView.nameAnswers] says what became of it).
     */
    suspend fun requestName(name: String): Rejection? =
        issueNamed(name) { n, hidden -> Change.RequestName(n.self, hidden) }

    /** Takes back this phone's request of a new name while nobody answered it ([Rejection.NOT_PENDING] if none). */
    suspend fun withdrawNameRequest(): Rejection? =
        onNode(Rejection.NO_ENSEMBLE) { n -> n.issue(Change.WithdrawName(n.self)).rejection() }

    /**
     * Accepts or refuses the request of a new name [member] has pending now. [Rejection.NOT_PENDING] when there is
     * none any more (someone answered first: [OperationView.nameAnswers] says who and what, or it was taken back).
     * An answer written here may still lose against another one written at the same moment elsewhere: then it shows
     * in [OperationView.refused] with [Rejection.NOT_PENDING].
     */
    suspend fun answerName(
        member: Long,
        accept: Boolean,
    ): Rejection? =
        onNode(Rejection.NO_ENSEMBLE) { n ->
            val pending = n.state.nameRequests[member] ?: return@onNode Rejection.NOT_PENDING
            n.issue(Change.AnswerName(member, pending, accept)).rejection()
        }

    /**
     * Gives the operation itself the name [name] (the creator and the administrators). Invitations given before
     * stay valid and show the former name until the phone is let in; who waits for a group learns the new one.
     */
    suspend fun renameOperation(name: String): Rejection? =
        issueNamed(name) { _, hidden -> Change.RenameEnsemble(hidden) }

    /** Hides [name] (trimmed), writes the change [build] makes with it, then keeps and shares its card. */
    private suspend fun issueNamed(
        name: String,
        build: (OperationNode, HiddenName) -> Change,
    ): Rejection? =
        onNode(Rejection.NO_ENSEMBLE) { n ->
            val trimmed = name.trim()
            if (!Names.valid(trimmed)) return@onNode Rejection.MALFORMED
            val card = Names.hide(trimmed, random)
            n.issue(build(n, card.hidden)).also {
                if (it is IssueResult.Issued) n.learnAndShare(card)
            }.rejection()
        }

    suspend fun deleteGroup(group: Long) = change(Change.DeleteGroup(group))

    suspend fun place(
        member: Long,
        group: Long,
    ) = change(Change.AssignMember(member, group))

    suspend fun unassign(member: Long) = change(Change.UnassignMember(member))

    /** Names [member] leader or deputy of [unit] (a group, or [HierarchyState.ROOT]: the operation). */
    suspend fun setPost(
        unit: Long,
        post: Post,
        member: Long,
    ) = change(Change.SetPost(unit, post, member))

    suspend fun clearPost(
        unit: Long,
        post: Post,
    ) = change(Change.ClearPost(unit, post))

    suspend fun addAdmin(member: Long) = change(Change.AddAdmin(member))

    suspend fun removeAdmin(member: Long) = change(Change.RemoveAdmin(member))

    suspend fun removeMember(member: Long) = change(Change.RemoveMember(member))

    /** Leaves the operation; refused for the creator ([Rejection.IS_CREATOR]): [handOverAndLeave] or [dissolve]. */
    suspend fun leave(): Rejection? =
        onNode(Rejection.NO_ENSEMBLE) { n ->
            n.issue(Change.RemoveMember(n.self)).rejection()
        }

    /** The creator hands the operation over to [successor] (an administrator), then leaves. */
    suspend fun handOverAndLeave(successor: Long): Rejection? =
        onNode(Rejection.NO_ENSEMBLE) { n ->
            n.issue(Change.TransferCreator(successor)).rejection()
                ?: n.issue(Change.RemoveMember(n.self)).rejection()
        }

    /** Ends the operation for everyone (the creator only). */
    suspend fun dissolve(): Rejection? = change(Change.Dissolve)

    // ---- share -------------------------------------------------------------------------------------------------

    suspend fun sendPosition(fix: PeerFix): ShareOutcome = onNode(ShareOutcome.NOT_ALLOWED) { it.sendPosition(fix) }

    /** A message in [channel], one of [OperationView.targets] `.messages`; it shows in this phone's thread too. */
    suspend fun sendMessage(
        channel: Channel,
        text: String,
    ): ShareOutcome =
        onNode(ShareOutcome.NOT_ALLOWED) { n ->
            val m = OperationText(nonZero(), clock(), text)
            val outcome = n.sendMessage(channel, m)
            if (outcome == ShareOutcome.SENT || outcome == ShareOutcome.QUEUED) {
                running?.addMessage(channel, LoggedMessage(n.self, m.id, m.sentAtMs, m.sentAtMs, text))
            }
            outcome
        }

    suspend fun sendAlert(
        text: String,
        fix: PeerFix?,
    ): ShareOutcome = onNode(ShareOutcome.NOT_ALLOWED) { it.sendAlert(text, fix) }

    suspend fun liftAlert(): ShareOutcome = onNode(ShareOutcome.NOT_ALLOWED) { it.liftAlert() }

    /**
     * Shares a sketch or a waypoint ([kind]) in [channel]: a new one when [objectId] is `null` (its identifier comes
     * back), else the next state of this phone's object [objectId].
     */
    suspend fun putObject(
        kind: ShareKind,
        channel: Channel,
        body: ByteArray,
        objectId: Long? = null,
    ): Pair<ShareOutcome, Long> =
        onNode(ShareOutcome.NOT_ALLOWED to 0L) { n ->
            val id = objectId ?: generateSequence { random.nextLong() }.first { it != 0L }
            n.putObject(kind, channel, id, body) to id
        }

    suspend fun removeObject(ref: ObjectRef): ShareOutcome = onNode(ShareOutcome.NOT_ALLOWED) { it.removeObject(ref) }

    // ---- traces of dissolved operations (R1) --------------------------------------------------------------------

    /**
     * The screens say the app is in front: a moment ([TRACE_WINDOW_MS]) in the room of each operation dissolved this
     * week while this phone was a member, to tell a phone still in it (R1); not more than once every
     * [ANNOUNCE_EVERY_MS]. The number of traces being told now (0: none kept, no network used).
     */
    suspend fun announceDissolutions(): Int =
        onThread {
            if (clock() - lastWindowAt >= ANNOUNCE_EVERY_MS) openWindow()
            announcing.size
        }

    /** The traces this phone keeps (those over a week old are wiped first). */
    suspend fun traces(): List<DissolutionTraceView> =
        onThread { storage.traces.list(clock()).map { DissolutionTraceView(it.dissolvedAtMs, it.expiresAtMs) } }

    /** Joins, for [TRACE_WINDOW_MS], the room of each trace kept (one already open stays that much longer). */
    private fun openWindow() {
        val factory = relays ?: return
        val now = clock()
        lastWindowAt = now
        for (trace in storage.traces.list(now)) {
            val server = trace.server ?: continue
            val open = announcing[trace.ensembleId]
            if (open != null) {
                open.closesAt = now + TRACE_WINDOW_MS
                continue
            }
            val a = Announcing(trace, now + TRACE_WINDOW_MS)
            val t = factory.create(server, trace.room, a, RelayPresence.OPERATION)
            // As in the operation, no name on the server; and an identifier drawn for the trace, not the member's.
            t.identity = TransportIdentity(trace.room, trace.sender, "", 0)
            a.transport = t
            announcing[trace.ensembleId] = a
            t.start()
        }
        if (announcing.isNotEmpty() && traceTicker == null) {
            traceTicker =
                executor.scheduleWithFixedDelay({
                    catchReported("operation.trace.tick") { tickTraces() }
                }, 1, 1, TimeUnit.SECONDS)
        }
    }

    /** Leaves the rooms whose moment is over; the last one stops the clock of the traces. */
    private fun tickTraces() {
        val now = clock()
        val over = announcing.values.filter { now >= it.closesAt }
        for (a in over) {
            announcing.remove(a.trace.ensembleId)
            a.close()
        }
        if (announcing.isEmpty()) {
            traceTicker?.cancel(false)
            traceTicker = null
        }
    }

    // ---- inside ------------------------------------------------------------------------------------------------

    /** Runs what is due now, without waiting for the next second (tests): the operation's, then the traces'. */
    internal suspend fun tickNow() {
        onNode(Unit) { n ->
            n.tick()
            if (clock() - lastWindowAt >= TRACE_EVERY_MS) openWindow()
        }
        onThread { tickTraces() }
    }

    /** The node of the operation open now (tests and the debug check). */
    internal suspend fun <T> withNode(block: (OperationNode) -> T): T? = onThread { running?.node?.let(block) }

    private suspend fun <T> onThread(block: () -> T): T = withContext(dispatcher) { block() }

    private suspend fun <T> onNode(
        fallback: T,
        block: (OperationNode) -> T,
    ): T =
        onThread {
            val r = running
            if (r == null || r.ended != null) return@onThread fallback
            val result =
                catchExpected("operation.call", KeyVaultException::class) {
                    block(r.node)
                }.getOrElse { fallback }
            publish()
            result
        }

    private fun publish() {
        val r = running ?: return
        val n = r.node
        val state = n.state
        val self = n.self
        val ended = r.ended
        if (ended != null) {
            mutableView.value =
                OperationView(
                    OperationPhase.ENDED,
                    r.stored.id,
                    n.ensembleId,
                    self,
                    ending = ended,
                    endedByTrace = n.dissolvedByTrace,
                    traceKeptUntilMs = r.traceKeptUntil,
                )
            return
        }
        val online = n.online
        val members =
            state.members.values.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }.map { m ->
                MemberView(
                    m.id,
                    n.names.nameOf(m.name),
                    state.rolesOf(m.id),
                    m.group,
                    state.isWaiting(m.id),
                    m.id == self || m.id in online,
                    state.nameLabel(self, m.id),
                )
            }
        val groups =
            state.groups.values.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }.map { g ->
                GroupView(
                    g.id,
                    n.names.nameOf(g.name),
                    g.parent,
                    g.leader,
                    g.deputy,
                    state.membersOf(g.id).map {
                        it.id
                    },
                )
            }
        val read = if (self in state.members) state.channelsReadBy(self).toSet() else emptySet()
        r.positions.keys.retainAll { it in state.members && state.canSee(self, it) }
        // Only once this phone knows where it stands (as at a save): while it catches up it reads no channel.
        if (self in state.members) r.messages.retain(read)
        r.alerts.keys.retainAll(state.members.keys)
        val alertChannel = state.alertChannel(self)
        val targets =
            if (self in state.members) {
                ShareTargets(
                    state.shareChannels(self, ShareKind.POSITION).firstOrNull(),
                    state.shareChannels(self, ShareKind.MESSAGE),
                    state.shareChannels(self, ShareKind.SKETCH),
                    state.shareChannels(self, ShareKind.WAYPOINT),
                    alertChannel,
                    alertChannel?.let { c -> state.readersOf(c).map { it.id }.filter { it != self } }.orEmpty(),
                )
            } else {
                null
            }
        val refused =
            n.replica.history().filter { it.op.author == self && it.rejection != null }.map {
                RefusedChange(it.op.seq, it.op.change, requireNotNull(it.rejection))
            }
        val phase =
            when {
                self !in state.members -> OperationPhase.ASKING
                state.isWaiting(self) -> OperationPhase.WAITING
                else -> OperationPhase.PLACED
            }
        // The requests of a new name: when this phone first saw each one; the creator and the administrators see
        // all of them and the answers, anyone else its own (2026-11-16).
        val now = clock()
        r.nameRequestsSeen.keys.retainAll(state.nameRequests.keys)
        for ((member, requested) in state.nameRequests) {
            if (r.nameRequestsSeen[member]?.first != requested) r.nameRequestsSeen[member] = requested to now
        }
        val answers = self == state.creator || self in state.admins
        val nameRequests =
            state.nameRequests
                .filterKeys { answers || it == self }
                .map { (member, requested) ->
                    NameRequestView(member, n.names.nameOf(requested), r.nameRequestsSeen[member]?.second ?: now)
                }.sortedWith { a, b ->
                    if (a.seenAtMs != b.seenAtMs) {
                        a.seenAtMs.compareTo(b.seenAtMs)
                    } else {
                        java.lang.Long.compareUnsigned(a.member, b.member)
                    }
                }
        val nameAnswers =
            state.nameAnswers
                .filterKeys { answers || it == self }
                .mapValues { (member, a) -> NameAnswerView(member, a.by, n.names.nameOf(a.name), a.accepted) }
        mutableView.value =
            OperationView(
                phase = phase,
                storageId = r.stored.id,
                ensembleId = n.ensembleId,
                self = self,
                operationName = n.operationName(),
                hierarchy = state,
                members = members,
                groups = groups,
                joinRequests = n.pendingJoins.map { JoinRequestView(it.member, it.card?.name, it.receivedAtMs) },
                nameRequests = nameRequests,
                nameAnswers = nameAnswers,
                positions = r.positions.toMap(),
                threads =
                    r.messages.threads().mapValues { (_, list) ->
                        list.map { ThreadMessage(it.author, it.text, it.sentAtMs, it.receivedAtMs) }
                    },
                alerts = r.alerts.values.sortedBy { it.receivedAtMs },
                objects = n.objects.shown(state, self).takeIf { self in state.members }.orEmpty(),
                targets = targets,
                mayInvite = n.mayInvite() && n.server != null,
                inviteEpoch = n.epoch,
                server = r.serverState,
                online = online,
                refused = refused,
            )
    }

    private fun IssueResult.rejection(): Rejection? = (this as? IssueResult.Refused)?.reason

    private fun nonZero(): Long {
        var id = 0L
        while (id == 0L) id = random.nextLong()
        return id
    }

    companion object {
        private const val MS = 1000L
        private const val CLOSE_DELAY_S = 5L

        /** A moment in the room of a dissolved operation: a phone still in it speaks within a minute (ADR-131). */
        const val TRACE_WINDOW_MS = 2 * 60_000L

        /** While an operation runs on this phone: a moment in the rooms of the traces every half hour. */
        const val TRACE_EVERY_MS = 30 * 60_000L

        /** When the screens ask ([announceDissolutions]): not more than one moment every ten minutes. */
        const val ANNOUNCE_EVERY_MS = 10 * 60_000L

        fun newExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "toolmap-operation").apply { isDaemon = true } }
    }
}
