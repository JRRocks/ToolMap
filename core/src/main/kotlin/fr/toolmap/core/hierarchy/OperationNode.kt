package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom
import java.util.TreeMap

/** Why an operation is over for this phone. */
enum class OperationEnding {
    /** It left, or was taken out by the creator. */
    OUT,

    /** The operation is dissolved. */
    DISSOLVED,
}

/** A request to come in, as an administrator's phone holds it until someone lets the phone in. */
class PendingJoin(
    val request: JoinRequest,
    /** The name it chose, when its card was wrapped to this phone. */
    val card: NameCard?,
    val receivedAtMs: Long,
) {
    val member: Long get() = request.member
}

/** What became of something this phone wanted to share. */
enum class ShareOutcome {
    /** Sealed and sent to the phones linked now. */
    SENT,

    /** Kept until the key of its channel arrives, or until a phone is linked: sent then. */
    QUEUED,

    /** A position without a key or without a link: skipped, the next one goes. */
    SKIPPED,

    /** This phone may not share that there ([HierarchyState.mayShare]), or no longer belongs to the operation. */
    NOT_ALLOWED,

    /** Larger than a frame. */
    TOO_LARGE,
}

/** What the node tells the app. Called with the node's lock held: post the work elsewhere, do not block. */
interface OperationListener {
    /** Something to save or to show changed: the hierarchy, keys, names, objects, requests, invitation keys. */
    fun onChanged() {}

    fun onPosition(
        member: Long,
        channel: Channel,
        fix: PeerFix,
    ) {}

    fun onMessage(
        author: Long,
        channel: Channel,
        message: OperationText,
    ) {}

    /** An alert, or its end ([OperationText.lifted]). */
    fun onAlert(
        author: Long,
        channel: Channel,
        alert: OperationText,
    ) {}

    /** The operation is over for this phone: everything it held of it is forgotten; wipe the storage. */
    fun onEnded(ending: OperationEnding) {}

    /**
     * A change this phone just wrote, or a new invitation key it just drew, is about to go out: write everything down
     * now, before returning (the user's answer M3 of 2026-11-16, spec § 21 case D). The one call that may block,
     * a few milliseconds: a change sent before it is on the disk, the app killed in between, came back after a restart
     * as a second change under the same number, and one of the two was lost on every phone. Not called for a change
     * that ends the operation for this phone (dissolution, its own departure): nothing of it is kept anyway.
     */
    fun beforeSend() {}
}

/** A frame refused for a reason that is not an exception: what [Problems.expected] is given. */
class FrameRefused(
    what: String,
) : Exception(what)

/**
 * The exchange between the phones of one operation (P39.7, ADR-129, `docs/specs/group-hierarchy.md` § 17): what the
 * engine leaves to the transport. Pure: it is given links ([MeshLink], one per phone linked now, the coordination
 * server's included) and frames, a clock and a random source; it hands back frames to send and tells [listener].
 *
 * On a link that opens it sends its [OperationSummary]; the other answers with the operations it lacks, the key
 * batches if their fingerprints differ, the objects of each channel whose fingerprint differs (if it may share there),
 * the names if it lacks some; the one that is behind asks again. After each change of the hierarchy it forgets what
 * it no longer reads, makes the keys of the units it reads (at once if it is the first issuer linked, later
 * otherwise), sends again what is still shown to who arrives, and, for the creator and the administrators, renews the
 * invitation key when someone left. A phone not let in yet asks to come in ([JoinRequest]) until it is.
 *
 * A phone that was away when the operation was dissolved learns it from a phone that keeps its trace (P39.9,
 * ADR-131): the proof that comes in a [FrameGenre.DISSOLUTION] frame is checked ([DissolutionProof.check]), then ends
 * the operation here as a dissolution read from the operations would; a false one is refused and said.
 *
 * Every method takes the node's lock: the app calls it from any thread.
 */
class OperationNode(
    private val keys: PhoneKeys,
    val replica: HierarchyReplica,
    val ring: KeyRing,
    val names: NameBook,
    val objects: SharedObjects,
    link: OperationLinkState,
    private val listener: OperationListener,
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    /** Between two summaries to the linked phones; a link that opens gets one at once (P39.15: five minutes). */
    private val summaryEveryMs: Long = SUMMARY_EVERY_MS,
) {
    val self: Long = keys.memberId
    val ensembleId: Long = replica.ensembleId
    val room: ByteArray = link.room
    val server: RelayConfig? = link.server

    init {
        require(link.ensembleId == ensembleId && replica.self == self && ring.self == self) { "parts of two phones" }
        require(link.epochs.isNotEmpty()) { "no invitation key" }
    }

    private val epochs = TreeMap<Int, EpochKey>().apply { link.epochs.forEach { put(it.epoch, it) } }
    private val frameKeys = HashMap<Int, ByteArray>()
    private val inviteCard: NameCard? = link.inviteCard
    private var ownCard: NameCard? = link.ownCard
    private val links = LinkedHashMap<Long, MeshLink>()
    private val joins = LinkedHashMap<Long, PendingJoin>()
    private val duties = LinkedHashMap<Pair<Duty, Channel?>, Scheduled>()
    private val outbox = ArrayDeque<Pending>()
    private val lastFix = HashMap<Long, Long>()
    private val seenTexts = LinkedHashSet<Pair<Long, Long>>()
    private val pulledAt = HashMap<Long, Long>()

    /**
     * Asks [pull] put off by [PULL_EVERY_MS]: they go at the next tick once it has passed. Lost, they waited for the
     * next summary, five minutes away since P39.15.
     */
    private val pullLater = LinkedHashSet<Long>()
    private val answeredAt = HashMap<Long, Pair<Long, Int>>()

    /**
     * When this phone last answered a summary of each phone, and the most recent one heard from it since, not answered
     * yet (M7): after a reconnection the server hands over two minutes of a phone's summaries in one go; the first
     * is answered at once, then one every [ANSWER_LATEST_EVERY_MS] at most, the latest.
     */
    private val summaryAnsweredAt = HashMap<Long, Long>()
    private val summaryLater = LinkedHashMap<Long, Heard>()
    private val grantedAt = HashMap<Long, Long>()

    /** When this phone last told each phone gone from the operation why it is out ([tellWhyOut], M1). */
    private val toldOutAt = HashMap<Long, Long>()

    /** When the creator last renewed the invitation because the key of now reached a phone gone (M5). */
    private var renewedAgainAt = Long.MIN_VALUE / 2

    /** When this phone last answered each member still on an older invitation key ([answerBehind], M2). */
    private val behindAnsweredAt = HashMap<Long, Long>()

    /**
     * The newest epoch of the invitation key this phone knows of without holding its key (a frame sealed with it, a
     * key announced for it that it could not take yet): the creator renews from there only ([renewInvitation]).
     */
    private var newerEpochSeen = 0

    /** A renewal of the invitation owed after a departure, until the key of [newerEpochSeen] reaches this phone. */
    private var renewOwed = false
    private val offeredAt = HashMap<Int, Long>()
    private val resendSeenAt = HashMap<Channel, Long>()

    /** When another reader last told the names in each waiting lane: one administrator does it, not all of them. */
    private val namesSeenAt = HashMap<Channel, Long>()
    private var previous: HierarchyState = replica.state
    private var lastJoinAt = Long.MIN_VALUE / 2
    private var lastSummaryAt = Long.MIN_VALUE / 2
    private var askedNewerKeyAt = Long.MIN_VALUE / 2
    private var alert: Pair<OperationText, Channel>? = null

    /** Whether the name of the operation its invitation carried is the one its first operation signed. */
    var inviteNameChecked: Boolean = inviteCard == null
        private set

    /**
     * Whether the name its invitation carried is one the ensemble had, the one of now or a former one (an
     * invitation given before a rename stays valid): shown while this phone does not know the name of now.
     */
    private var inviteNameKnown = false

    var ending: OperationEnding? = null
        private set

    /**
     * Once dissolved, the proof this phone keeps in its trace (ADR-131): `null` when it was not a member when the
     * operation was dissolved (taken out before, never let in), so keeps no trace, or when it is not dissolved.
     */
    @get:Synchronized
    var dissolution: DissolutionProof? = null
        private set

    /** Whether the dissolution came from a phone that keeps the operation's trace, not from the operations. */
    @get:Synchronized
    var dissolvedByTrace: Boolean = false
        private set

    /** When the operation was dissolved: when this phone learnt it, or what the phone that told it said, if earlier. */
    @get:Synchronized
    var dissolvedAtMs: Long? = null
        private set

    /** Counters, for the debug check and the event log: frames refused or dropped, by reason. */
    val dropped: Map<String, Int>
        @Synchronized get() = drops.toMap()
    private val drops = HashMap<String, Int>()

    /** Summaries heard that were meant for another phone, left to it (P39.17): not a refusal, so not in [dropped]. */
    @get:Synchronized
    var heardForOthers = 0L
        private set

    private enum class Duty { KEY, RESEND, ROSTER, NAMES, RENEW }

    private class Scheduled(
        val dueAt: Long,
        val scheduledAt: Long,
    )

    /** A summary heard and put off ([summaryLater]): what it says, the frame it came in, the link it came by. */
    private class Heard(
        val summary: OperationSummary,
        val frame: OpenedFrame,
        val peer: Long,
    )

    private class Pending(
        val kind: ShareKind,
        val channel: Channel,
        val payload: ByteArray,
        val queuedAt: Long,
    )

    // ---- reading -------------------------------------------------------------------------------------------------

    val state: HierarchyState get() = replica.state

    @get:Synchronized
    val epoch: Int get() = epochs.lastKey()

    /** The phones linked now. */
    @get:Synchronized
    val online: Set<Long> get() = links.keys.toSet()

    /** The requests to come in this phone may answer (it is the creator or an administrator), oldest first. */
    @get:Synchronized
    val pendingJoins: List<PendingJoin> get() = joins.values.toList()

    /** Whether this phone is let in (waiting for a group or placed). */
    val isMember: Boolean get() = self in state.members

    /**
     * The name of the operation: verified against its operations, or what the invitation says until then. After a
     * rename, an invitation given before still shows the former name, until this phone learns the one of now.
     */
    @Synchronized
    fun operationName(): String? =
        names.nameOf(state.name) ?: inviteCard?.name?.takeIf { !state.created || inviteNameKnown }

    /** What to keep beside the engine's parts ([OperationLinkState]). */
    @Synchronized
    fun linkState(): OperationLinkState =
        OperationLinkState(ensembleId, room, server, epochs.values.toList(), inviteCard, ownCard)

    /** Whether this phone may let someone in, so give an invitation and renew its key. */
    fun mayInvite(): Boolean {
        val s = state
        return !s.dissolved && (self == s.creator || self in s.admins)
    }

    /** An invitation for a new phone, made now; `null` when this phone may not invite or has no server. */
    @Synchronized
    fun invitation(nowSec: Long = clock() / MS): OperationInvite? {
        if (!mayInvite()) return null
        val server = server ?: return null
        val card = names.ensembleCard(state) ?: return null
        val current = epochs.lastEntry().value
        return OperationInvite(nowSec, ensembleId, room, current.epoch, current.key, card, server)
    }

    /** Why [change] would be refused now, `null` when it would go through: to grey a button. */
    fun check(change: Change): Rejection? = HierarchyReducer.check(state, self, change)

    // ---- links ---------------------------------------------------------------------------------------------------

    /** Called once the node is built: makes the keys it lacks, sends what waited. */
    @Synchronized
    fun start() {
        previous = state
        if (ending == null) afterChange(start = true)
    }

    @Synchronized
    fun linkOpened(
        peer: Long,
        link: MeshLink,
    ) {
        if (peer == self || ending != null) return
        links[peer] = link
        sendTo(link, FrameGenre.SUMMARY, ourSummary(to = peer).encode())
        if (!isMember) sendJoin()
        flushOutbox()
    }

    @Synchronized
    fun linkClosed(peer: Long) {
        links.remove(peer)
    }

    /** Runs what is due; to call about every second. */
    @Synchronized
    fun tick() {
        if (ending != null) return
        val now = clock()
        val due = duties.entries.filter { it.value.dueAt <= now }.sortedBy { it.value.dueAt }
        for ((key, scheduled) in due) {
            duties.remove(key)
            run(key.first, key.second, scheduled)
        }
        if (!isMember && now - lastJoinAt >= JOIN_EVERY_MS) sendJoin()
        for (peer in pullLater.toList()) if (peer in links) pull(peer) else pullLater -= peer
        for ((sender, heard) in summaryLater.entries.toList()) {
            if (now - (summaryAnsweredAt[sender] ?: Long.MIN_VALUE / 2) < ANSWER_LATEST_EVERY_MS) continue
            summaryLater.remove(sender)
            answer(heard.summary, heard.frame, heard.peer)
        }
        if (links.isNotEmpty() && now - lastSummaryAt >= summaryEveryMs) {
            lastSummaryAt = now
            broadcast(FrameGenre.SUMMARY, ourSummary().encode())
        }
        while (outbox.isNotEmpty() && now - outbox.first().queuedAt > OUTBOX_MAX_AGE_MS) {
            outbox.removeFirst()
            drop("outbox.expired")
        }
        flushOutbox()
    }

    // ---- receiving -----------------------------------------------------------------------------------------------

    /** A frame from [peer], as a link handed it over. */
    @Synchronized
    fun receive(
        peer: Long,
        bytes: ByteArray,
    ) {
        if (ending != null) return
        val epoch = OperationFrames.epochOf(bytes) ?: return drop("frame.format")
        if (epochs[epoch] == null) {
            if (epoch > this.epoch) {
                noteNewerEpoch(epoch)
                askNewerKey(OperationFrames.senderOf(bytes) ?: 0L)
            }
            return drop("frame.epoch")
        }
        val frame = OperationFrames.open(frameKey(epoch), ensembleId, bytes)
        if (frame == null) {
            // Maybe sealed with another key of this epoch (two renewals at once, P39.10): this phone hands its own
            // again under the epoch before, and the smaller key wins on every phone ([onGrant]).
            offerKeyAgain(epoch)
            return drop("frame.sealed")
        }
        if (frame.sender == self) return
        var stray = false
        if (epoch < this.epoch && frame.genre != FrameGenre.DISSOLUTION) {
            // A phone still on an older invitation key (not one that keeps a trace: the operation is over for it, it
            // speaks only to say so). A member gets the one of now; its summary first gets what it missed, under its
            // own key ([answerBehind], M2). A phone gone from the operation is told why, and nothing else (M1).
            // Nothing new is let in that way.
            if (frame.sender in state.members) {
                if (frame.genre != FrameGenre.SUMMARY) grantTo(frame.sender, epoch)
            } else {
                tellWhyOut(frame.sender, epoch)
                stray = true
            }
            if (frame.genre == FrameGenre.JOIN) return drop("join.old_key")
        }
        try {
            when (frame.genre) {
                FrameGenre.OPS -> onOps(frame)
                FrameGenre.SUMMARY -> if (stray) heardFromStray(frame) else onSummary(frame, peer)
                FrameGenre.KEYS -> onKeys(frame)
                FrameGenre.SHARE -> onShare(frame)
                FrameGenre.JOIN -> onJoin(frame)
                FrameGenre.INVITE_KEY -> onGrant(frame)
                FrameGenre.DISSOLUTION -> onDissolution(frame)
                FrameGenre.JOIN_CARD -> onJoinCard(frame)
            }
        } catch (e: WireException) {
            // Opened with the invitation key, so written by a phone that holds it: its bytes, not a bug here.
            Problems.expected("operation.frame.decode", e, "genre" to frame.genre.name)
            drop("frame.decode")
        }
    }

    private fun onOps(frame: OpenedFrame) {
        var changed = false
        var held = false
        var out = false
        var heldOut = false
        for (op in HierarchyWire.decodeOps(frame.body)) {
            val outing = takesThisPhoneOut(op)
            val result = replica.add(op)
            if (outing && result == AddResult.ADDED) out = true
            if (outing && result == AddResult.HELD) heldOut = true
            when (result) {
                AddResult.ADDED, AddResult.CONFLICT_REPLACED -> {
                    changed = true
                }

                AddResult.HELD -> {
                    held = true
                }

                AddResult.DUPLICATE, AddResult.CONFLICT_KEPT -> {
                    Unit
                }

                else -> {
                    Problems.expected("operation.ops.refused", FrameRefused(result.name))
                    drop("ops.${result.name.lowercase()}")
                }
            }
            if (result == AddResult.CONFLICT_REPLACED || result == AddResult.CONFLICT_KEPT) {
                Problems.expected("operation.ops.conflict", FrameRefused(result.name))
            }
        }
        // Taken out, or let in by a phone taken out before it did, told so ahead of what happened between (M1). Let
        // in that way, its admission no longer counts: no signing key of its own then, [afterChange] would not end it.
        if (heldOut) return finish(OperationEnding.OUT)
        val state = state
        if (out && !state.dissolved && self !in state.members && replica.signingKeyOf(self) == null) {
            return finish(OperationEnding.OUT)
        }
        if (held) pull(frame.sender)
        if (changed) afterChange()
    }

    private fun onSummary(
        frame: OpenedFrame,
        peer: Long,
    ) {
        val theirs = OperationSummary.decode(frame.body)
        if (theirs.summary.ensembleId != ensembleId) return drop("summary.ensemble")
        // Meant for another phone (P39.17, ADR-138): the room carries it to everyone, the one it is meant for answers.
        // Answered by every phone that heard it, one arrival in a room of 22 pushed 7 360 frames to each phone, 1 037
        // with this rule alone (`OperationArrivalCostTest`; run « CI » n° 243). What the answer carries is still taken
        // by all.
        if (theirs.to != 0L && theirs.to != self) {
            heardForOthers++
            return
        }
        // One answer every two seconds at most to the summaries of one phone, to the latest (the user's answer M7 of
        // 2026-11-16, spec § 23): what the others were missing two seconds ago, the latest summary says too.
        if (clock() - (summaryAnsweredAt[frame.sender] ?: Long.MIN_VALUE / 2) < ANSWER_LATEST_EVERY_MS) {
            summaryLater[frame.sender] = Heard(theirs, frame, peer)
            return
        }
        summaryLater.remove(frame.sender)
        answer(theirs, frame, peer)
    }

    /** Answers [theirs], a summary of [frame]'s sender meant for this phone or for the whole room. */
    private fun answer(
        theirs: OperationSummary,
        frame: OpenedFrame,
        peer: Long,
    ) {
        summaryAnsweredAt[frame.sender] = clock()
        if (frame.epoch < epoch) return answerBehind(theirs, frame, peer)
        val to = links[peer] ?: links[frame.sender]
        val missing = replica.missingFor(theirs.summary)
        for (body in opsBodies(missing)) sendTo(to, FrameGenre.OPS, body)
        val mine = replica.summary()
        if (isBehind(mine, theirs.summary)) pull(frame.sender)
        // The same summary heard twice in a moment (the room carries it to everyone) is answered once.
        val now = clock()
        val heard = frame.body.contentHashCode()
        val last = answeredAt[frame.sender]
        if (last != null && last.second == heard && now - last.first < ANSWER_EVERY_MS) return
        answeredAt[frame.sender] = now to heard
        val state = state
        if (theirs.keysDigest != keysDigest(state)) {
            for (batch in ring.batchesFor(state)) sendTo(to, FrameGenre.KEYS, HierarchyWire.encodeKeyBatch(batch))
        }
        for ((channel, digest) in theirs.objects) {
            if (channel in state.channelsReadBy(self) && digest != objects.digest(channel)) resend(channel)
        }
        // A phone behind on the operations also lacks the names they carry: they go with them.
        val lacksNames = theirs.namesMissing > 0 || missing.isNotEmpty()
        if (lacksNames && state.hasAccess(frame.sender) && state.hasAccess(self)) shareRoster()
        // Who waits tells its name in its own lane only: a reader of that lane who lacks names gets it again (P39.10).
        if (theirs.namesMissing > 0 && state.isWaiting(self) &&
            state.readersOf(Channel.waiting(self)).any { it.id == frame.sender }
        ) {
            shareCards(Channel.waiting(self), ownCards(state))
        }
        // A reader of waiting lanes lacks names: the names of who waits and cannot tell them itself (its phone not
        // linked, maybe lost: L2, case H) are told there again, by the first reader linked that holds them, once
        // every [NAMES_AGAIN_EVERY_MS] at most.
        if (theirs.namesMissing > 0 && frame.sender != self && state.hasAccess(frame.sender)) {
            for (w in state.waiting()) {
                if (w.id == self || w.id == frame.sender || w.id in links || !holdsNameOf(state, w.id)) continue
                val lane = Channel.waiting(w.id)
                val readers = state.readersOf(lane)
                if (readers.none { it.id == frame.sender } || readers.none { it.id == self }) continue
                if (clock() - (namesSeenAt[lane] ?: Long.MIN_VALUE / 2) < NAMES_AGAIN_EVERY_MS) continue
                tellNamesLater(state, lane, renamedOnly = false)
            }
        }
        // Who waits and lacks a name it may know (the ensemble renamed, or itself, while it was away) gets it in its
        // lane from an administrator or the creator (2026-11-16); nothing else goes there.
        if (theirs.namesMissing > 0 && frame.sender != self && state.isWaiting(frame.sender)) {
            tellNames(Channel.waiting(frame.sender))
        } else if (frame.sender != self && state.isWaiting(frame.sender) &&
            missing.any { namesChangeFor(it.change, frame.sender) }
        ) {
            // Behind on a rename (away when it was written): its summary cannot say it lacks a name it does not know
            // of yet; the names follow the operations, once, from the first reader of its lane linked.
            tellNamesLater(state, Channel.waiting(frame.sender), renamedOnly = false)
        }
    }

    /**
     * A member still on an older invitation key, away while it was renewed (the user's answer M2 of 2026-11-16, ADR-140
     * and ADR-142): the key of now, under the key it holds; what it lacks it then asks for under the key of now
     * ([onGrant]). Behind again within [BEHIND_AGAIN_MS] (it could not take the key: named creator or administrator
     * while it was away, case C): the operations it missed under its own key, then the key of now, in that order.
     */
    private fun answerBehind(
        theirs: OperationSummary,
        frame: OpenedFrame,
        peer: Long,
    ) {
        val now = clock()
        val again = now - (behindAnsweredAt[frame.sender] ?: Long.MIN_VALUE / 2) < BEHIND_AGAIN_MS
        behindAnsweredAt[frame.sender] = now
        if (behindAnsweredAt.size > MAX_JOINS) behindAnsweredAt.keys.retainAll(state.members.keys)
        // The key of now first, alone: what the phone missed then comes under that key, it asks at once ([onGrant]).
        // Sent with the operations, these could reach it first: back as the creator (the role handed over while it was
        // away), it would renew from its old key, two keys of one epoch (long series, seed 0x50ac001c).
        if (!again) return grantTo(frame.sender, frame.epoch)
        // Behind again a moment later: it could not take that key (it does not know yet who announced it or who
        // handed it over, case C), or the key was lost. What it missed, under its own key, then the key of now.
        val to = links[peer] ?: links[frame.sender]
        for (body in opsBodies(replica.missingFor(theirs.summary))) sendTo(to, FrameGenre.OPS, body, frame.epoch)
        grantTo(frame.sender, frame.epoch, force = true)
    }

    /**
     * The summary of a phone that is no longer in the operation, or never was, under an older invitation key: nothing
     * of now is told to it. When it holds operations this phone lacks (the admission of the phone that wrote, by an
     * administrator taken out meanwhile, M1), they are asked for under its key, so that it can be told why it is out.
     */
    private fun heardFromStray(frame: OpenedFrame) {
        val theirs = OperationSummary.decode(frame.body)
        if (theirs.summary.ensembleId != ensembleId) return drop("summary.ensemble")
        if (theirs.to != 0L && theirs.to != self) return
        if (self in state.members && isBehind(replica.summary(), theirs.summary)) pull(frame.sender, frame.epoch)
        drop("summary.stray")
    }

    /**
     * A phone gone from the operation still speaks under an older invitation key: taken out while it was away (case A,
     * the user's answer M1 of 2026-11-16), or let in by an administrator taken out before it did. It is sent, under its
     * key, the operation that took it (or that administrator) out, and nothing else; its app ends the operation then
     * ([takesThisPhoneOut]). Once every [GRANT_EVERY_MS] at most, by every member that hears it.
     */
    private fun tellWhyOut(
        phone: Long,
        theirEpoch: Int,
    ) {
        if (self !in state.members) return
        val now = clock()
        if (now - (toldOutAt[phone] ?: Long.MIN_VALUE / 2) < GRANT_EVERY_MS) return
        val notice = removalNoticeFor(phone) ?: return
        if (toldOutAt.size >= MAX_JOINS) toldOutAt.clear()
        toldOutAt[phone] = now
        broadcast(FrameGenre.OPS, HierarchyWire.encodeOps(listOf(notice)), epoch = theirEpoch)
    }

    /** The operation that took [phone] out, or that took out, before it did, the phone that let [phone] in. */
    private fun removalNoticeFor(phone: Long): Op? {
        val history = replica.history()

        fun removalOf(member: Long): Op? =
            history.lastOrNull { e ->
                e.rejection == null && (e.op.change as? Change.RemoveMember)?.member == member
            }?.op
        if (replica.signingKeyOf(phone) != null) return removalOf(phone)
        val admission = history.lastOrNull { (it.op.change as? Change.AdmitMember)?.member == phone }?.op ?: return null
        return removalOf(admission.author)?.takeIf { Op.ORDER.compare(it, admission) < 0 }
    }

    /**
     * Whether [op] takes this phone out, read in what this phone holds before it, even when it arrives ahead of the
     * operations between ([AddResult.HELD]; M1): the removal of this phone after it was let in, or the removal, before
     * it let this phone in, of the phone that did; made by a phone that may make it.
     */
    private fun takesThisPhoneOut(op: Op): Boolean {
        val change = op.change as? Change.RemoveMember ?: return false
        val state = state
        if (self !in state.members || HierarchyReducer.check(state, op.author, change) != null) return false
        val admission =
            replica.history().lastOrNull { e ->
                e.rejection == null && (e.op.change as? Change.AdmitMember)?.member == self
            }?.op ?: return false
        return when (change.member) {
            self -> Op.ORDER.compare(admission, op) < 0
            admission.author -> Op.ORDER.compare(op, admission) < 0
            else -> false
        }
    }

    /**
     * The invitation key of now handed to a phone gone from the operation, by a phone that had not learnt the
     * departure yet (case G, the user's answer M5 of 2026-11-16): the creator renews the invitation once more, one
     * epoch up (since M6 the creator alone renews it, ADR-140). Once every [RENEW_AGAIN_EVERY_MS] at most: the phone
     * that does it learns the departure meanwhile.
     */
    private fun renewIfHandedToGone(grant: InviteGrant) {
        val state = state
        if (self != state.creator || grant.announcement.epoch != epoch) return
        if (grant.wraps.none { it.recipient !in state.members && replica.signingKeyOf(it.recipient) != null }) return
        val granter = state.members[grant.granter] ?: return
        if (!InviteKeys.signedBy(grant, granter)) return
        val now = clock()
        if (now - renewedAgainAt < RENEW_AGAIN_EVERY_MS) return
        renewedAgainAt = now
        renewInvitation()
    }

    private fun onKeys(frame: OpenedFrame) {
        val batch = HierarchyWire.decodeKeyBatch(frame.body)
        when (val verdict = ring.offer(batch, replica)) {
            KeyOffer.ACCEPTED -> {
                afterChange()
            }

            KeyOffer.NOT_FOR_ME -> {
                listener.onChanged()
            }

            KeyOffer.DUPLICATE -> {
                Unit
            }

            KeyOffer.UNKNOWN_READERS -> {
                pull(frame.sender)
            }

            else -> {
                Problems.expected("operation.keys.refused", FrameRefused(verdict.name))
                drop("keys.${verdict.name.lowercase()}")
            }
        }
    }

    private fun onShare(frame: OpenedFrame) {
        val state = state
        val opened =
            when (val result = ShareSeal.open(state, ring, frame.body, replica::signingKeyOf)) {
                is OpenResult.Opened -> {
                    result.share
                }

                is OpenResult.Refused -> {
                    when (result.reason) {
                        // Every frame of the room reaches every phone: one it may not read is the normal case.
                        ShareRefusal.NOT_A_READER -> {
                            drop("share.not_a_reader")
                        }

                        ShareRefusal.NO_KEY -> {
                            drop("share.no_key")
                            // Behind only if it reads that channel: who may not read it (a member waiting for its
                            // place) has no key to ask for. Its asking, answered with more it could not open, was
                            // half of the 240 frames a minute each phone of a room of 22 received for good once one
                            // member waited (P39.17, ADR-138; the other half: [ourSummary]).
                            val channel = result.channel
                            if (isMember && channel != null && state.readersOf(channel).any { it.id == self }) {
                                pull(frame.sender)
                            }
                        }

                        else -> {
                            Problems.expected("operation.share.refused", FrameRefused(result.reason.name))
                            drop("share.${result.reason.name.lowercase()}")
                        }
                    }
                    return
                }
            }
        val now = clock()
        when (opened.kind) {
            ShareKind.POSITION -> {
                val fix = OperationPayloads.readPosition(opened.payload)
                if (fix.timeSec <= (lastFix[opened.author] ?: Long.MIN_VALUE)) return drop("position.old")
                lastFix[opened.author] = fix.timeSec
                listener.onPosition(opened.author, opened.channel, fix)
            }

            ShareKind.MESSAGE -> {
                val m = OperationPayloads.readMessage(opened.payload)
                if (!fresh(opened.author, m, now)) return
                listener.onMessage(opened.author, opened.channel, m)
            }

            ShareKind.ALERT -> {
                val a = OperationPayloads.readAlert(opened.payload)
                if (!fresh(opened.author, a, now)) return
                listener.onAlert(opened.author, opened.channel, a)
            }

            ShareKind.SKETCH, ShareKind.WAYPOINT -> {
                when (val verdict = objects.accept(opened, replica::signingKeyOf)) {
                    ObjectVerdict.STORED -> {
                        resendSeenAt[opened.channel] = now
                        listener.onChanged()
                    }

                    ObjectVerdict.DUPLICATE, ObjectVerdict.OLDER -> {
                        resendSeenAt[opened.channel] = now
                    }

                    else -> {
                        Problems.expected("operation.object.refused", FrameRefused(verdict.name))
                        drop("object.${verdict.name.lowercase()}")
                    }
                }
            }

            ShareKind.ROSTER -> {
                // Another reader told the names of this waiting lane: this phone need not do it too.
                if (opened.channel.lane == Lane.WAITING && opened.author != opened.channel.scope && !opened.late) {
                    namesSeenAt[opened.channel] = now
                }
                val learnt = if (opened.late) names.accept(opened, lateNames(opened.channel)) else names.accept(opened)
                if (learnt > 0) {
                    // Who waits keeps the ensemble's name and its own ones, nothing else, whoever wrote in its lane.
                    if (state.isWaiting(self)) names.prune(state, self)
                    refreshOwnCard()
                    listener.onChanged()
                }
            }
        }
    }

    /**
     * The names that names from someone who may no longer share in [channel] still give this phone ([Share.late],
     * P39.14.6, ADR-143): only the ones it lacks, that the operations held carry, that an honest phone shares there
     * (in the whole operation, every name but the ones of who waits; in a waiting lane, the ones its member may know)
     * and that this phone may know. The operation that one wrote may stand on every phone: its name must too.
     */
    private fun lateNames(channel: Channel): Set<HiddenName> {
        val state = state
        val there =
            when {
                channel == Channel.ENSEMBLE -> {
                    state.hiddenNames() - state.waiting().flatMapTo(HashSet()) { state.namesOf(it.id) }
                }

                channel.lane == Lane.WAITING -> {
                    state.namesFor(channel.scope)
                }

                else -> {
                    emptySet()
                }
            }
        val mine = state.namesFor(self)
        return there.filterTo(HashSet()) { it in mine && names.cardOf(it) == null }
    }

    private fun onJoin(frame: OpenedFrame) {
        val request = JoinRequests.decode(frame.body)
        val state = state
        when {
            request.ensembleId != ensembleId -> {
                return drop("join.ensemble")
            }

            request.member != frame.sender -> {
                return drop("join.sender")
            }

            !JoinRequests.verify(request) -> {
                Problems.expected("operation.join.forged", FrameRefused("signature"))
                return drop("join.forged")
            }

            request.member in state.members -> {
                return
            }

            !mayInvite() -> {
                return
            }

            kotlin.math.abs(clock() / MS - request.timeSec) > OperationInviteCodec.MAX_AGE_SEC -> {
                return drop("join.old")
            }
        }
        val known = joins[request.member]
        val card = JoinRequests.openCard(request, keys) ?: known?.card
        if (known != null && known.card != null) return
        if (joins.size >= MAX_JOINS && known == null) return drop("join.full")
        joins[request.member] = PendingJoin(request, card, known?.receivedAtMs ?: clock())
        listener.onChanged()
    }

    /**
     * The requests this phone holds the card of, wrapped again to the creator and the administrators they left out
     * (named after the request was sent: its phone may no longer hear the operation, it would never send them its
     * card; the user's answer L2 of 2026-11-16, case E of § 21). Done by each phone that holds the card, when the
     * ones who may let someone in change.
     */
    private fun relayJoinCards(state: HierarchyState) {
        val inviters =
            (listOfNotNull(state.creator) + state.admins).filter {
                it != self
            }.mapNotNull { state.members[it] }
        for (pending in joins.values) {
            val card = pending.card ?: continue
            val left = inviters.filter { m -> pending.request.wraps.none { it.recipient == m.id } }
            if (left.isEmpty()) continue
            broadcast(FrameGenre.JOIN_CARD, JoinRequests.relay(pending.request, card, left).encode())
        }
    }

    private fun onJoinCard(frame: OpenedFrame) {
        val relay = JoinRequests.decodeRelayed(frame.body)
        val request = relay.request
        val state = state
        when {
            request.ensembleId != ensembleId -> {
                return drop("join_card.ensemble")
            }

            frame.sender !in state.members -> {
                return drop("join_card.sender")
            }

            !JoinRequests.verify(request) -> {
                Problems.expected("operation.join_card.forged", FrameRefused("signature"))
                return drop("join_card.forged")
            }

            request.member in state.members || !mayInvite() -> {
                return
            }

            kotlin.math.abs(clock() / MS - request.timeSec) > OperationInviteCodec.MAX_AGE_SEC -> {
                return drop("join_card.old")
            }
        }
        val known = joins[request.member]
        if (known?.card != null) return
        val card = JoinRequests.openRelayed(relay, keys) ?: return
        if (joins.size >= MAX_JOINS && known == null) return drop("join.full")
        joins[request.member] = PendingJoin(request, card, known?.receivedAtMs ?: clock())
        listener.onChanged()
    }

    private fun onGrant(frame: OpenedFrame) {
        val grant = InviteKeys.decode(frame.body)
        val epoch = grant.announcement.epoch
        val known = epochs[epoch]
        if (known != null && InviteKeys.keyId(ensembleId, epoch, known.key) == grant.announcement.keyId) {
            return renewIfHandedToGone(grant)
        }
        val (verdict, key) = InviteKeys.open(grant, state, keys)
        when (verdict) {
            GrantVerdict.ACCEPTED -> {
                if (known == null) {
                    adopt(EpochKey(requireNotNull(key), grant.announcement))
                    // Handed to this phone alone: it was behind ([answerBehind], M2). What it missed is asked for at
                    // once, under the key of now, of every phone linked, as when a link opens: its summaries under
                    // its old key got the key alone. Asked of the phone that handed it alone, a member waiting for its
                    // place, the objects of the channels it reads never came (long series, seed 0x50ac0023).
                    if (grant.wraps.size == 1 && epoch == this.epoch) {
                        for (peer in links.keys.toList()) pull(peer)
                        if (frame.sender !in links) pull(frame.sender)
                    }
                    // A renewal owed meanwhile now starts from the key of now.
                    if (renewOwed && newerEpochSeen <= this.epoch) {
                        renewOwed = false
                        duties.remove(Duty.RENEW to null)
                        renewInvitation()
                    }
                } else {
                    // Two keys of one epoch: two phones renewed at once (P39.10). The one with the smaller identifier
                    // wins, the same on every phone, as for two content keys (§ 8.3); the other side learns it.
                    val ours = InviteKeys.keyId(ensembleId, epoch, known.key)
                    if (UNSIGNED.compare(grant.announcement.keyId, ours) < 0) {
                        epochs[epoch] = EpochKey(requireNotNull(key), grant.announcement)
                        frameKeys.remove(epoch)
                    }
                    offerKeyAgain(epoch)
                }
                listener.onChanged()
            }

            GrantVerdict.NOT_FOR_ME -> {
                Unit
            }

            else -> {
                Problems.expected("operation.grant.refused", FrameRefused(verdict.name))
                // The key of a newer epoch, for this phone, that it cannot take: it lacks the operations that say who
                // announced or handed it (case C). It asks for them under its own key ([answerBehind]).
                if (verdict == GrantVerdict.NOT_ALLOWED && epoch > this.epoch &&
                    grant.wraps.any { it.recipient == self }
                ) {
                    noteNewerEpoch(epoch)
                    pull(frame.sender)
                }
                drop("grant.${verdict.name.lowercase()}")
            }
        }
    }

    /**
     * A phone that keeps the trace of this operation says it is dissolved: true when the proof is the creator's and
     * nothing this phone holds says otherwise; then everything is forgotten, as for a dissolution read from the
     * operations. A member when it was dissolved (as far as this phone knows) keeps the proof for its own trace.
     */
    private fun onDissolution(frame: OpenedFrame) {
        val notice = DissolutionNotice.decode(frame.body)
        when (val verdict = notice.proof.check(ensembleId, replica)) {
            ProofVerdict.VALID -> {
                dissolvedByTrace = true
                dissolvedAtMs = minOf(notice.dissolvedAtMs, clock())
                if (self in state.members) dissolution = notice.proof
                finish(OperationEnding.DISSOLVED)
            }

            else -> {
                Problems.expected("operation.dissolution.refused", FrameRefused(verdict.name))
                drop("dissolution.${verdict.name.lowercase()}")
            }
        }
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    /** Asks for [change]; sent to the others when this phone may make it. */
    @Synchronized
    fun issue(change: Change): IssueResult {
        if (ending != null) return IssueResult.Refused(Rejection.DISSOLVED)
        val result = replica.issue(change)
        if (result is IssueResult.Issued) {
            val after = state
            if (!after.dissolved && self in after.members) listener.beforeSend()
            broadcast(FrameGenre.OPS, HierarchyWire.encodeOps(listOf(result.op)))
            afterChange()
        }
        return result
    }

    /** Lets in the phone that asked with [member]'s request; its name, when its card came, is learnt here. */
    @Synchronized
    fun admit(member: Long): IssueResult {
        val pending = joins[member] ?: return IssueResult.Refused(Rejection.NO_SUCH_MEMBER)
        pending.card?.let(names::learn)
        val result = issue(Change.AdmitMember(member, pending.request.name, pending.request.keys))
        if (result is IssueResult.Issued) joins.remove(member)
        return result
    }

    /** Forgets [member]'s request without letting it in. */
    @Synchronized
    fun decline(member: Long): Boolean = (joins.remove(member) != null).also { if (it) listener.onChanged() }

    /**
     * A name this phone just wrote into an operation (a new group, a rename, a request of a new name): its card is
     * kept and shared where that name goes. With those who have the access, but the names of a member who waits for
     * a group (its own, the one it asks for), which go to its waiting lane only: its own phone writes there, and so
     * do the administrators and the creator. The name of the ensemble goes to every waiting lane too, so that who
     * waits learns it after a rename (2026-11-16).
     */
    @Synchronized
    fun learnAndShare(card: NameCard) {
        names.learn(card)
        refreshOwnCard()
        val state = state
        val hidden = card.hidden
        val about = state.members.keys.firstOrNull { hidden in state.namesOf(it) }
        if (about != null && state.isWaiting(about)) {
            shareCards(Channel.waiting(about), listOf(card))
            return
        }
        if (state.hasAccess(self)) queueRoster(listOf(card))
        if (hidden == state.name) {
            for (waiting in state.waiting()) {
                val lane = Channel.waiting(waiting.id)
                if (state.mayShare(self, ShareKind.ROSTER, lane)) shareCards(lane, listOf(card))
            }
        }
    }

    @Synchronized
    fun sendPosition(fix: PeerFix): ShareOutcome {
        val channel = state.shareChannels(self, ShareKind.POSITION).firstOrNull() ?: return ShareOutcome.NOT_ALLOWED
        if (links.isEmpty()) return ShareOutcome.SKIPPED
        return when (val sealed = seal(ShareKind.POSITION, channel, OperationPayloads.position(fix))) {
            is SealResult.Sealed -> {
                if (sealed.frame.size + OperationFrames.OVERHEAD > OperationFrames.MAX_POSITION_FRAME) {
                    Problems.unexpected(
                        "operation.position.size",
                        "position frame too large",
                        "bytes" to sealed.frame.size,
                    )
                    return ShareOutcome.TOO_LARGE
                }
                broadcast(FrameGenre.SHARE, sealed.frame, position = true)
                ShareOutcome.SENT
            }

            is SealResult.Refused -> {
                if (sealed.reason == ShareRefusal.NO_KEY) {
                    wantKey(channel)
                    ShareOutcome.SKIPPED
                } else {
                    ShareOutcome.NOT_ALLOWED
                }
            }
        }
    }

    @Synchronized
    fun sendMessage(
        channel: Channel,
        text: String,
    ): ShareOutcome = sendMessage(channel, OperationText(nonZero(), clock(), text))

    /** [message] in [channel], with the identifier and the time the caller drew (it keeps the message: R2). */
    @Synchronized
    fun sendMessage(
        channel: Channel,
        message: OperationText,
    ): ShareOutcome {
        require(message.id != 0L && message.fix == null && !message.lifted) { "not a message" }
        return share(ShareKind.MESSAGE, channel, OperationPayloads.message(message))
    }

    /** An alert to the one place this phone's role sends it ([HierarchyState.alertChannel]). */
    @Synchronized
    fun sendAlert(
        text: String,
        fix: PeerFix?,
    ): ShareOutcome {
        val channel = state.alertChannel(self) ?: return ShareOutcome.NOT_ALLOWED
        val a = OperationText(nonZero(), clock(), text, fix)
        alert = a to channel
        return share(ShareKind.ALERT, channel, OperationPayloads.alert(a))
    }

    /** Ends the alert this phone sent last. */
    @Synchronized
    fun liftAlert(): ShareOutcome {
        val (a, _) = alert ?: return ShareOutcome.NOT_ALLOWED
        val channel = state.alertChannel(self) ?: return ShareOutcome.NOT_ALLOWED
        alert = null
        val lifted = OperationText(nonZero(), clock(), a.text, a.fix, lifted = true)
        return share(ShareKind.ALERT, channel, OperationPayloads.alert(lifted))
    }

    /**
     * Shares a new state of the sketch or waypoint [objectId] of this phone in [channel] (a new one when it has none
     * yet): kept here, and sent again later to who arrives or comes back.
     */
    @Synchronized
    fun putObject(
        kind: ShareKind,
        channel: Channel,
        objectId: Long,
        body: ByteArray,
    ): ShareOutcome {
        if (!kind.standing || objectId == 0L) return ShareOutcome.NOT_ALLOWED
        if (!state.mayShare(self, kind, channel)) return ShareOutcome.NOT_ALLOWED
        if (body.size > MAX_OBJECT_BODY) return ShareOutcome.TOO_LARGE
        val previous = objects.find(ObjectRef(channel, self, objectId))?.takeIf { it.kind == kind }
        val record = SharedRecords.write(keys, ensembleId, kind, channel, objectId, body, previous)
        objects.accept(record, replica::signingKeyOf)
        listener.onChanged()
        return share(kind, channel, SharedRecords.encode(record))
    }

    /** Takes away the object [ref] (this phone's, or one it may share in the same place). */
    @Synchronized
    fun removeObject(ref: ObjectRef): ShareOutcome {
        val previous = objects.find(ref)?.takeIf { !it.deleted } ?: return ShareOutcome.NOT_ALLOWED
        if (!state.mayShare(self, previous.kind, ref.channel)) return ShareOutcome.NOT_ALLOWED
        val record = SharedRecords.remove(keys, ensembleId, previous)
        objects.accept(record, replica::signingKeyOf)
        listener.onChanged()
        return share(previous.kind, ref.channel, SharedRecords.encode(record))
    }

    /**
     * A new invitation key: the phones that hold the old one only no longer read what follows. The creator's only
     * (M6, ADR-140): the administrators no longer renew it in its stead; `false` on any other phone, and while the key
     * of a newer epoch has not reached this phone yet (renewed once it has, ADR-142).
     */
    @Synchronized
    fun renewInvitation(): Boolean = renew(waitForNewer = true)

    private fun renew(waitForNewer: Boolean): Boolean {
        if (ending != null || self != state.creator || self !in state.members) return false
        // The key of a newer epoch exists and has not reached this phone yet (back as the creator, the role handed
        // over while it was away): renewing from its own key would make two keys of one epoch. It renews once that
        // key is here ([onGrant]), or after [RENEW_OWED_MS] at worst, from its own (ADR-132 then settles it).
        if (waitForNewer && newerEpochSeen > epoch) {
            renewOwed = true
            schedule(Duty.RENEW, null, RENEW_OWED_MS)
            return false
        }
        renewOwed = false
        val old = epochs.lastEntry().value
        val key = ByteArray(OperationInvite.KEY_BYTES).also(random::nextBytes)
        val announcement = InviteKeys.announce(keys, ensembleId, old.epoch + 1, key)
        val grant = InviteKeys.grant(keys, announcement, key, state.members.values.filter { it.id != self })
        adopt(EpochKey(key, announcement))
        // Written down before it goes (M3): a key handed out and then lost here would leave this phone behind its own.
        listener.beforeSend()
        // Under the old key, so that the members still on it open the envelope; only members unwrap the key.
        broadcast(FrameGenre.INVITE_KEY, grant.encode(), epoch = old.epoch)
        listener.onChanged()
        return true
    }

    // ---- after a change ------------------------------------------------------------------------------------------

    private fun afterChange(start: Boolean = false) {
        val state = state
        val before = previous
        previous = state
        ring.retry(replica)
        val over =
            when {
                state.dissolved -> OperationEnding.DISSOLVED
                replica.signingKeyOf(self) != null && self !in state.members -> OperationEnding.OUT
                else -> null
            }
        if (over != null) {
            if (over == OperationEnding.DISSOLVED) {
                dissolvedAtMs = clock()
                // Taken out before the dissolution (no longer a member then): no trace (R1).
                if (self in state.members) dissolution = DissolutionProof.of(replica)
            }
            finish(over)
            return
        }
        val member = self in state.members
        if (member) {
            ring.prune(state)
            ring.restore(state)
            objects.prune(state, self)
            names.prune(state, self)
        }
        if (!inviteNameChecked && inviteCard != null && state.created) checkInviteName(state, inviteCard, member)
        // Its own card while it is its name (a member renamed keeps the card of its new name: [refreshOwnCard]).
        ownCard?.let { card ->
            if (state.created && (!member || card.hidden in state.namesFor(self))) names.learn(card)
        }
        refreshOwnCard()
        namesSeenAt.keys.retainAll { it.lane == Lane.WAITING && state.isWaiting(it.scope) }
        joins.keys.removeAll { it in state.members }
        if (!mayInvite()) joins.clear()
        // The ones who may let someone in changed: the cards of the requests held go to those they left out (L2).
        if (before.created && mayInvite() && (state.admins != before.admins || state.creator != before.creator)) {
            relayJoinCards(state)
        }
        if (!member && state.created && before.creator == null) sendJoin()
        if (!member && (state.admins != before.admins || state.creator != before.creator)) sendJoin()
        if (member && self !in before.members && !start) arrivedHere(state)
        if (member) {
            for (channel in ring.missing(state)) if (channel.lane == Lane.UNIT) wantKey(channel)
            // Names it lacks (a group made while it was away): its summary says so to a phone that has them.
            val lacking = names.missing(state)
            if (state.hasAccess(self) && lacking.isNotEmpty()) {
                links.keys.firstOrNull()?.let(::pull)
                // The name of who waits is told in its own lane, by its phone only: that phone is asked too, at once,
                // not at the next summary (five minutes since P39.15).
                for (m in state.members.values) {
                    if (m.name in lacking && m.id in links && state.isWaiting(m.id)) pull(m.id)
                }
            }
            // Who waits and lacks the ensemble's name (renamed) or its own says so: a reader of its lane tells it.
            if (state.isWaiting(self) && names.missingOf(state.namesFor(self)).isNotEmpty()) {
                links.keys.firstOrNull()?.let(::pull)
            }
        }
        if (!start && before.created) {
            for ((channel, who) in state.arrivals(before)) {
                // A new waiting lane (someone let in, or put back to waiting) of a renamed ensemble: its member may
                // only know a former name of it, from its invitation (2026-11-16).
                if (channel.lane == Lane.WAITING && channel.scope != self && channel.scope in who) {
                    tellNamesLater(state, channel)
                }
                // A new reader of the waiting lane of a phone that is not linked (an administrator named, a new
                // creator; that phone off, maybe lost for good): the readers who hold the name of who waits tell it
                // there, the first linked of them (the user's answer L2 of 2026-11-16, case H of § 21).
                if (channel.lane == Lane.WAITING && channel.scope != self && channel.scope !in who && self !in who &&
                    channel.scope !in links && holdsNameOf(state, channel.scope)
                ) {
                    tellNamesLater(state, channel, renamedOnly = false)
                }
                if (self in who) {
                    if (channel == Channel.ENSEMBLE) queueRoster(ownCards(state))
                    continue
                }
                // A new reader of this phone's waiting lane (an administrator named, a new creator) learns its name
                // there too: whoever may place it knows whom it places (Q10, Q11, § 8.8; P39.10).
                if (channel == Channel.waiting(self)) {
                    shareCards(channel, ownCards(state))
                    continue
                }
                if (objects.resend(channel).isNotEmpty() &&
                    mayResend(channel)
                ) {
                    schedule(Duty.RESEND, channel, rank(state.keyIssuers(channel)))
                }
                if (channel == Channel.ENSEMBLE && state.hasAccess(self)) {
                    schedule(Duty.ROSTER, channel, rank(state.keyIssuers(Channel.ENSEMBLE)))
                }
            }
            // After every departure the creator renews the invitation, at once, and nobody else: one key per epoch
            // (the user's answer M6 of 2026-11-16, ADR-140). Away, it learns the departure when it is back, and renews
            // then. At once, in this very call: a duty in memory was lost when the app stopped before its next second
            // (P39.10); the new key is written down with the departure ([renewInvitation], M3). Only when the key of a
            // newer epoch has not reached it yet does it wait for it (ADR-142).
            val gone = before.members.keys - state.members.keys
            if (gone.isNotEmpty() && self == state.creator) renewInvitation()
            alert?.let { (a, channel) ->
                val now = state.alertChannel(self)
                if (now != null && now != channel) {
                    alert = a to now
                    share(ShareKind.ALERT, now, OperationPayloads.alert(a))
                }
            }
        }
        flushOutbox()
        listener.onChanged()
    }

    /** Over for this phone: everything it held of the operation is forgotten, and the app is told. */
    private fun finish(over: OperationEnding) {
        ending = over
        ring.clear()
        names.clear()
        objects.clear()
        joins.clear()
        duties.clear()
        outbox.clear()
        summaryLater.clear()
        listener.onEnded(over)
    }

    /** This phone was just let in: its own waiting lane gets a key, and its name goes to the administrators there. */
    private fun arrivedHere(state: HierarchyState) {
        val card = ownCard ?: return
        names.learn(card)
        if (state.isWaiting(self)) {
            wantKey(Channel.waiting(self))
            outbox.addLast(Pending(ShareKind.ROSTER, Channel.waiting(self), NameBook.encode(listOf(card)), clock()))
        }
    }

    private fun run(
        duty: Duty,
        channel: Channel?,
        scheduled: Scheduled,
    ) {
        val state = state
        when (duty) {
            Duty.KEY -> {
                val c = channel ?: return
                if (ring.sealingKey(state, c) != null || c !in state.channelsReadBy(self)) return
                val batch = ring.issue(state, c, random) ?: return
                broadcast(FrameGenre.KEYS, HierarchyWire.encodeKeyBatch(batch))
                listener.onChanged()
                flushOutbox()
            }

            Duty.RESEND -> {
                val c = channel ?: return
                if ((resendSeenAt[c] ?: Long.MIN_VALUE) > scheduled.scheduledAt) return
                resend(c)
            }

            Duty.ROSTER -> {
                shareRoster()
            }

            Duty.NAMES -> {
                val c = channel ?: return
                if ((namesSeenAt[c] ?: Long.MIN_VALUE) > scheduled.scheduledAt) return
                tellNames(c)
            }

            Duty.RENEW -> {
                if (renewOwed) renew(waitForNewer = false)
            }
        }
    }

    /**
     * Tells the member who waits in [lane] the names it may know ([HierarchyState.namesFor]: the ensemble's, its
     * own ones) that this phone holds, when it may write there (an administrator or the creator).
     */
    private fun tellNames(lane: Channel) {
        val state = state
        if (lane.lane != Lane.WAITING || !state.isWaiting(lane.scope) || lane.scope == self) return
        if (!state.mayShare(self, ShareKind.ROSTER, lane)) return
        shareCards(lane, state.namesFor(lane.scope).mapNotNull(names::cardOf))
        namesSeenAt[lane] = clock()
    }

    /** Whether this phone holds the card of a name of [member] (L2). */
    private fun holdsNameOf(
        state: HierarchyState,
        member: Long,
    ): Boolean = state.namesOf(member).any { names.cardOf(it) != null }

    /**
     * Once a new waiting lane appears in an ensemble that was renamed: the first of its readers linked tells the
     * names there a moment later (its member makes the lane's key first), the others only if it did not.
     */
    private fun tellNamesLater(
        state: HierarchyState,
        lane: Channel,
        renamedOnly: Boolean = true,
    ) {
        if (!state.mayShare(self, ShareKind.ROSTER, lane)) return
        if (renamedOnly && replica.ensembleNames().size < 2) return
        val order = state.resenders(ShareKind.ROSTER, lane).filter { it != lane.scope }
        schedule(Duty.NAMES, lane, rank(order)?.plus(RANK_STEP_MS))
    }

    /** Whether [change] gives [member], while it waits, a name it may know: the ensemble's, or its own. */
    private fun namesChangeFor(
        change: Change,
        member: Long,
    ): Boolean =
        when (change) {
            is Change.RenameEnsemble -> true
            is Change.RenameMember -> change.member == member
            is Change.AnswerName -> change.member == member
            else -> false
        }

    /** The cards of this phone's own names ([HierarchyState.namesOf]) it holds; its own card before it is let in. */
    private fun ownCards(state: HierarchyState): List<NameCard> =
        state.namesOf(self).mapNotNull(names::cardOf).ifEmpty { listOfNotNull(ownCard) }

    /** Its own card follows its name: renamed (directly, or its request accepted), the card of the new one. */
    private fun refreshOwnCard() {
        val current = state.members[self]?.name ?: return
        if (ownCard?.hidden == current) return
        names.cardOf(current)?.let { ownCard = it }
    }

    /**
     * The card of its invitation, checked against the operations once they are there: the name of the ensemble now
     * (kept), or a former one (an invitation given before a rename: shown until the one of now is known, not a
     * problem). A card that opens neither is said once this phone is let in; before, its copy of the operations
     * may still lack the rename the card comes from.
     */
    private fun checkInviteName(
        state: HierarchyState,
        card: NameCard,
        member: Boolean,
    ) {
        when {
            names.learnEnsembleName(state, card) || (card.valid && card.hidden in replica.ensembleNames()) -> {
                inviteNameChecked = true
                inviteNameKnown = true
            }

            member -> {
                inviteNameChecked = true
                Problems.expected("operation.invite.name", FrameRefused("card of another name"))
            }
        }
    }

    private fun shareCards(
        channel: Channel,
        cards: List<NameCard>,
    ) {
        for (chunk in cards.distinct().chunked(ROSTER_CHUNK)) share(ShareKind.ROSTER, channel, NameBook.encode(chunk))
    }

    private fun resend(channel: Channel) {
        for (record in objects.resend(channel)) {
            if (state.mayShare(self, record.kind, channel)) share(record.kind, channel, SharedRecords.encode(record))
        }
    }

    private fun mayResend(channel: Channel): Boolean =
        state.mayShare(self, ShareKind.WAYPOINT, channel) || state.mayShare(self, ShareKind.SKETCH, channel)

    /**
     * The names of those who have the access, for everyone who has it; not the names of who waits (Q10, Q11), nor
     * the one it asks for, nor the last one answered to it: those stay in its waiting lane.
     */
    private fun shareRoster() {
        val state = state
        val waiting = state.waiting().flatMapTo(HashSet()) { state.namesOf(it.id) }
        queueRoster(names.cardsFor(state).filter { it.hidden !in waiting })
    }

    private fun queueRoster(cards: List<NameCard>) {
        for (chunk in cards.chunked(ROSTER_CHUNK)) share(ShareKind.ROSTER, Channel.ENSEMBLE, NameBook.encode(chunk))
    }

    // ---- sending -------------------------------------------------------------------------------------------------

    private fun share(
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
    ): ShareOutcome {
        if (ending != null) return ShareOutcome.NOT_ALLOWED
        if (!state.mayShare(self, kind, channel)) return ShareOutcome.NOT_ALLOWED
        if (payload.size > MAX_SHARE_PAYLOAD) return ShareOutcome.TOO_LARGE
        val sealed = seal(kind, channel, payload)
        if (sealed is SealResult.Sealed && links.isNotEmpty()) {
            broadcast(FrameGenre.SHARE, sealed.frame)
            return ShareOutcome.SENT
        }
        if (sealed is SealResult.Refused && sealed.reason != ShareRefusal.NO_KEY) return ShareOutcome.NOT_ALLOWED
        if (outbox.size >= MAX_OUTBOX) {
            outbox.removeFirst()
            drop("outbox.full")
        }
        val pending = Pending(kind, channel, payload, clock())
        outbox.addLast(pending)
        if (sealed is SealResult.Refused) wantKey(channel)
        return if (outbox.any { it === pending }) ShareOutcome.QUEUED else ShareOutcome.SENT
    }

    private fun flushOutbox() {
        if (outbox.isEmpty() || links.isEmpty()) return
        val kept = ArrayDeque<Pending>()
        while (outbox.isNotEmpty()) {
            val p = outbox.removeFirst()
            if (!state.mayShare(self, p.kind, p.channel)) {
                drop("outbox.not_allowed")
                continue
            }
            when (val sealed = seal(p.kind, p.channel, p.payload)) {
                is SealResult.Sealed -> {
                    broadcast(FrameGenre.SHARE, sealed.frame)
                }

                is SealResult.Refused -> {
                    if (sealed.reason == ShareRefusal.NO_KEY) kept.addLast(p) else drop("outbox.refused")
                }
            }
        }
        outbox.addAll(kept)
    }

    private fun seal(
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
    ): SealResult = ShareSeal.seal(state, ring, keys, kind, channel, payload, random)

    /** The key of [channel] is wanted: made at once by the first issuer linked, a little later by the others. */
    private fun wantKey(channel: Channel) {
        if (ring.sealingKey(state, channel) != null) return
        // A lane gets its key only when someone has something to share there (§ 9): that one makes it at once.
        val delay = if (channel.lane == Lane.UNIT) rank(state.keyIssuers(channel)) ?: return else 0L
        if (channel !in state.channelsReadBy(self)) return
        schedule(Duty.KEY, channel, delay)
        if (delay == 0L) tickDuty(Duty.KEY, channel)
    }

    private fun tickDuty(
        duty: Duty,
        channel: Channel?,
    ) {
        val scheduled = duties.remove(duty to channel) ?: return
        run(duty, channel, scheduled)
    }

    private fun schedule(
        duty: Duty,
        channel: Channel?,
        delay: Long?,
    ) {
        if (delay == null) return
        val now = clock()
        val known = duties[duty to channel]
        if (known != null && known.dueAt <= now + delay) return
        duties[duty to channel] = Scheduled(now + delay, now)
    }

    /**
     * How long this phone waits before doing what any of [order] may do: its rank among those of [order] linked now
     * (itself included) times a step, so that the first one linked does it and the others see it done. `null` when
     * this phone is not one of them.
     */
    private fun rank(order: List<Long>): Long? {
        val here = order.filter { it == self || it in links }
        val i = here.indexOf(self)
        return if (i < 0) null else i * RANK_STEP_MS
    }

    private fun sendJoin() {
        val state = state
        val card = ownCard ?: return
        if (isMember || links.isEmpty()) return
        lastJoinAt = clock()
        val recipients = state.members.values.filter { it.id == state.creator || it.id in state.admins }
        val request = JoinRequests.make(keys, ensembleId, card, recipients, clock() / MS)
        broadcast(FrameGenre.JOIN, request.encode())
    }

    private fun pull(
        peer: Long,
        epoch: Int = this.epoch,
    ) {
        val now = clock()
        if (now - (pulledAt[peer] ?: Long.MIN_VALUE / 2) < PULL_EVERY_MS) {
            pullLater += peer
            return
        }
        pullLater -= peer
        pulledAt[peer] = now
        sendTo(links[peer], FrameGenre.SUMMARY, ourSummary(to = peer).encode(), epoch)
    }

    /**
     * [epoch], newer than any key this phone holds, exists: the creator waits for its key before renewing. Bounded by
     * [OperationLinkState.KEPT_EPOCHS]: the epoch in the clear part of an envelope is not signed.
     */
    private fun noteNewerEpoch(epoch: Int) {
        if (epoch > this.epoch && epoch - this.epoch <= OperationLinkState.KEPT_EPOCHS) {
            newerEpochSeen = maxOf(newerEpochSeen, epoch)
        }
    }

    /**
     * A frame sealed with a newer invitation key than any this phone holds: away while it was renewed. Its summary goes
     * to the phone that wrote that frame, which holds the key of now: that phone alone answers (P39.17), with the key of
     * now, under the key this one holds (M2, ADR-142). It was the whole room until P39.14.
     */
    private fun askNewerKey(from: Long) {
        val now = clock()
        if (now - askedNewerKeyAt < PULL_EVERY_MS) return
        askedNewerKeyAt = now
        broadcast(FrameGenre.SUMMARY, ourSummary(to = from).encode())
    }

    private fun grantTo(
        member: Long,
        theirEpoch: Int,
        force: Boolean = false,
    ) {
        val now = clock()
        if (!force && now - (grantedAt[member] ?: Long.MIN_VALUE / 2) < GRANT_EVERY_MS) return
        grantedAt[member] = now
        val target = state.members[member] ?: return
        val current = epochs.lastEntry().value
        // A key this phone only has from its invitation: its announcement was never seen, it cannot vouch for it.
        if (current.announcement.announcer == 0L) return
        val grant = InviteKeys.grant(keys, current.announcement, current.key, listOf(target))
        broadcast(FrameGenre.INVITE_KEY, grant.encode(), epoch = theirEpoch)
    }

    /**
     * Hands this phone's key of [epoch] to every member again, under the epoch before (which both sides of two
     * renewals made at once still share), once every [GRANT_EVERY_MS] at most: whoever holds the other key of that
     * epoch then keeps the smaller one ([onGrant]).
     */
    private fun offerKeyAgain(epoch: Int) {
        val mine = epochs[epoch] ?: return
        val before = epochs.lowerKey(epoch) ?: return
        if (mine.announcement.announcer == 0L || self !in state.members) return
        val now = clock()
        if (now - (offeredAt[epoch] ?: Long.MIN_VALUE / 2) < GRANT_EVERY_MS) return
        offeredAt[epoch] = now
        offeredAt.keys.retainAll(epochs.keys)
        val grant = InviteKeys.grant(keys, mine.announcement, mine.key, state.members.values.filter { it.id != self })
        broadcast(FrameGenre.INVITE_KEY, grant.encode(), epoch = before)
    }

    private fun adopt(key: EpochKey) {
        epochs[key.epoch] = key
        while (epochs.size > OperationLinkState.KEPT_EPOCHS) epochs.remove(epochs.firstKey())
    }

    /** What this phone holds, for the one phone [to] (0: for the whole room). */
    private fun ourSummary(to: Long = 0L): OperationSummary {
        val state = state
        val read = if (self in state.members) state.channelsReadBy(self) else emptyList()
        val digests = read.take(OperationSummary.MAX_CHANNELS).associateWith { objects.digest(it) }
        val missing =
            when {
                // Not the names of who waits in a lane this phone does not read: none would ever come (Q10, Q11), and
                // every phone of the room answered each summary of the room with its roster, for good (P39.17, ADR-138:
                // 120 frames a minute to each phone of a room of 22 with one member waiting).
                state.hasAccess(self) -> {
                    val closed =
                        state.waiting().filter { w -> state.readersOf(Channel.waiting(w.id)).none { it.id == self } }
                    val unreadable = closed.flatMapTo(HashSet()) { state.namesOf(it.id) }
                    names.missing(state).count { it !in unreadable }
                }

                // Who waits counts only what it may know: the ensemble's name, its own ones.
                state.isWaiting(self) -> {
                    names.missingOf(state.namesFor(self)).size
                }

                else -> {
                    0
                }
            }
        return OperationSummary(replica.summary(), keysDigest(state), missing, digests, to)
    }

    private fun keysDigest(state: HierarchyState): Long {
        var data = OpAuth.longBytes(ensembleId)
        val batches =
            ring.batchesFor(state).sortedWith(
                compareBy({
                    it.channel.lane.code
                }, { it.channel.scope }, { it.keyId }),
            )
        for (b in batches) data += b.channel.bytes() + OpAuth.longBytes(b.keyId) + b.readersTag
        return OpAuth.fingerprint(KEYS_DIGEST_TAG, data)
    }

    private fun opsBodies(ops: List<Op>): List<ByteArray> {
        val bodies = ArrayList<ByteArray>()
        var chunk = ArrayList<Op>()
        var size = OPS_HEADER
        for (op in ops) {
            val one = HierarchyWire.encodeOp(op).size
            if (chunk.isNotEmpty() &&
                (size + one > OperationFrames.MAX_BODY || chunk.size >= HierarchyWire.MAX_BATCH)
            ) {
                bodies += HierarchyWire.encodeOps(chunk)
                chunk = ArrayList()
                size = OPS_HEADER
            }
            chunk += op
            size += one
        }
        if (chunk.isNotEmpty()) bodies += HierarchyWire.encodeOps(chunk)
        return bodies
    }

    private fun broadcast(
        genre: FrameGenre,
        body: ByteArray,
        position: Boolean = false,
        epoch: Int = this.epoch,
    ) {
        if (links.isEmpty()) return
        val frame = OperationFrames.seal(frameKey(epoch), ensembleId, epoch, genre, self, body, position, random)
        // One array for every link: the coordination server writes it once for the whole room.
        for (link in links.values.toList()) link.send(frame, position)
    }

    private fun sendTo(
        link: MeshLink?,
        genre: FrameGenre,
        body: ByteArray,
        epoch: Int = this.epoch,
    ) {
        if (link == null) return
        if (link.isBroadcastMedium) return broadcast(genre, body, epoch = epoch)
        link.send(OperationFrames.seal(frameKey(epoch), ensembleId, epoch, genre, self, body, false, random), false)
    }

    /** The invitation key of [epoch] this phone holds (tests and the debug check). */
    @Synchronized
    internal fun inviteKey(epoch: Int): ByteArray? = epochs[epoch]?.key

    private fun frameKey(epoch: Int): ByteArray =
        frameKeys.getOrPut(epoch) { OperationFrames.frameKey(epochs.getValue(epoch).key, ensembleId, epoch) }

    private fun fresh(
        author: Long,
        text: OperationText,
        now: Long,
    ): Boolean {
        if (now - text.sentAtMs > TEXT_MAX_AGE_MS) return false.also { drop("text.old") }
        if (!seenTexts.add(author to text.id)) return false.also { drop("text.again") }
        val eldest = seenTexts.iterator()
        while (seenTexts.size > MAX_SEEN) {
            eldest.next()
            eldest.remove()
        }
        return true
    }

    private fun nonZero(): Long {
        var id = 0L
        while (id == 0L) id = random.nextLong()
        return id
    }

    private fun drop(why: String) {
        drops[why] = (drops[why] ?: 0) + 1
    }

    private fun isBehind(
        mine: Summary,
        theirs: Summary,
    ): Boolean =
        theirs.counts.any { (author, count) -> count > (mine.counts[author] ?: 0) } ||
            (mine.counts == theirs.counts && mine.digest != theirs.digest)

    companion object {
        private const val MS = 1000L
        const val RANK_STEP_MS = 1_500L
        const val JOIN_EVERY_MS = 20_000L

        /**
         * Five minutes (the user's answer 2 a of 2026-11-18; a minute until 0.4.261): what a phone missed comes back
         * within five minutes at worst, at once when a link opens.
         */
        const val SUMMARY_EVERY_MS = 300_000L
        const val PULL_EVERY_MS = 2_000L
        const val ANSWER_EVERY_MS = 5_000L

        /** Between two answers to the summaries of one phone (M7). */
        const val ANSWER_LATEST_EVERY_MS = 2_000L
        const val GRANT_EVERY_MS = 10_000L

        /** Between two renewals of the invitation made because the key of now reached a phone gone (M5). */
        const val RENEW_AGAIN_EVERY_MS = 60_000L

        /** The longest a creator waits for the key of a newer epoch before it renews from its own ([renew]). */
        const val RENEW_OWED_MS = 30_000L

        /** A member behind again within this time gets what it missed under its own key ([answerBehind]). */
        const val BEHIND_AGAIN_MS = 60_000L

        /** Between two times this phone tells again the names of who waits and is not linked (L2, case H). */
        const val NAMES_AGAIN_EVERY_MS = 600_000L
        const val OUTBOX_MAX_AGE_MS = 3_600_000L
        const val TEXT_MAX_AGE_MS = 6 * 3_600_000L
        const val MAX_OUTBOX = 64
        const val MAX_JOINS = 64
        private const val MAX_SEEN = 4096
        private const val ROSTER_CHUNK = 32
        private const val OPS_HEADER = 4

        /** A share fits a control frame of the coordination server with its envelope. */
        const val MAX_SHARE_PAYLOAD = OperationFrames.MAX_BODY - HierarchyWire.SHARE_HEADER_BYTES - 12 - 16 - 64

        /** A sketch or waypoint body fits one share with its record around it. */
        const val MAX_OBJECT_BODY = MAX_SHARE_PAYLOAD - SharedRecords.OVERHEAD_BYTES
        private val KEYS_DIGEST_TAG = "toolmap-operation-keys-digest-v1".toByteArray(Charsets.US_ASCII)
        private val UNSIGNED = Comparator<Long> { a, b -> java.lang.Long.compareUnsigned(a, b) }

        /**
         * A new operation made by the phone that holds [keys]: the creator's copy (first operation written), its
         * name cards learnt, the first invitation key announced, a room drawn at random.
         */
        fun genesis(
            keys: PhoneKeys,
            operationName: NameCard,
            creatorName: NameCard,
            server: RelayConfig?,
            random: SecureRandom = SecureRandom(),
        ): Genesis {
            val replica = HierarchyReplica.create(keys, creatorName.hidden, operationName.hidden, random.nextLong())
            val names =
                NameBook().apply {
                    learn(operationName)
                    learn(creatorName)
                }
            val key = ByteArray(OperationInvite.KEY_BYTES).also(random::nextBytes)
            val room = ByteArray(OperationInvite.ROOM_BYTES).also(random::nextBytes)
            val epoch = EpochKey(key, InviteKeys.announce(keys, replica.ensembleId, 1, key))
            val link = OperationLinkState(replica.ensembleId, room, server, listOf(epoch), null, creatorName)
            return Genesis(replica, KeyRing(replica.ensembleId, keys), names, SharedObjects(replica.ensembleId), link)
        }

        /** What a phone that joins with [invite] keeps beside the engine's parts, before it is let in. */
        fun joining(
            invite: OperationInvite,
            ownName: NameCard,
        ): OperationLinkState {
            // The announcement of the invitation's key is checked when the operations arrive; until then the key is
            // the one the invitation gives (it opens what the room carries, nothing else).
            val announcement =
                InviteAnnouncement(invite.ensembleId, invite.epoch, 0L, 0L, ByteArray(P256.SIGNATURE_BYTES))
            return OperationLinkState(
                invite.ensembleId,
                invite.room,
                invite.server,
                listOf(EpochKey(invite.key, announcement)),
                invite.ensembleCard,
                ownName,
            )
        }
    }

    class Genesis(
        val replica: HierarchyReplica,
        val ring: KeyRing,
        val names: NameBook,
        val objects: SharedObjects,
        val link: OperationLinkState,
    )
}
