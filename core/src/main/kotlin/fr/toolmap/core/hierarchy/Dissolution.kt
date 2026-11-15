package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom

/*
 * The trace of a dissolved operation (P39.9, ADR-131, `docs/specs/group-hierarchy.md` § 20). The user's answer R1 of
 * 2026-11-15: « Chaque téléphone garde une semaine une trace fermée de l'opération », which only serves to tell the
 * phones that were away that the operation is dissolved, and holds no position, no name, no message. Pure: no disk,
 * no network, no clock of its own.
 */

/** What a phone makes of a proof of dissolution ([DissolutionProof.check]). */
enum class ProofVerdict {
    /** Signed by the creator of the moment, and nothing this phone holds says otherwise: the operation is over. */
    VALID,

    /** Of another operation: its first operation is not the one this identifier names, or another one is not. */
    WRONG_ENSEMBLE,

    /** An operation not signed by its author, or signed with another key than the one its author came in with. */
    FORGED,

    /** The dissolution, or a handing over before it, is not signed by the creator of that moment. */
    NOT_BY_THE_CREATOR,

    /**
     * It contradicts what this phone holds: a handing over it applied is missing, one of its operations is not the
     * one this phone holds under that number, or it is one this phone refused.
     */
    INCONSISTENT,
}

/**
 * The proof that an operation is dissolved, checked without its log: its first operation ([genesis], whose author and
 * content give the identifier of the operation, so the creator and its key), every handing over of the operation that
 * applied, in their order ([handovers], each signed by the creator of its moment), and the dissolution ([dissolution],
 * signed by the last of them). Nothing else: an operation of this proof names no person and no group in clear (names
 * are hidden, § 8.8), and carries no position, no message, no content key.
 *
 * Only the creator dissolves (the rights of [HierarchyRules.DEFAULT]); a dissolution an ensemble's own rules would let
 * someone else write cannot be proven this way, and is refused.
 */
class DissolutionProof internal constructor(
    val genesis: Op,
    val handovers: List<Op>,
    val dissolution: Op,
) {
    val ensembleId: Long get() = genesis.ensembleId

    private val all: List<Op> get() = listOf(genesis) + handovers + dissolution

    /** One block of operations ([HierarchyWire.encodeOps]): the first, the handings over, the dissolution. */
    fun encode(): ByteArray = HierarchyWire.encodeOps(all)

    /**
     * Whether this proof ends the operation [ensembleId] for a phone that holds [replica] (its copy, `null` for none):
     * every operation of it signed by its author, the first one the one [ensembleId] names, each handing over and the
     * dissolution signed by the creator of its moment, clocks that go forward; then, against what [replica] holds, the
     * same key for every signer it saw come in, the same operations under the same numbers, none it refused, and the
     * handings over it applied as the first ones of the proof.
     */
    fun check(
        ensembleId: Long,
        replica: HierarchyReplica? = null,
    ): ProofVerdict {
        val ops = all
        if (ops.any { it.ensembleId != ensembleId } || !HierarchyReducer.isGenesisOf(ensembleId, genesis)) {
            return ProofVerdict.WRONG_ENSEMBLE
        }
        if (ops.any { it.isMalformed || !it.authentic }) return ProofVerdict.FORGED
        val creation = genesis.change as? Change.CreateEnsemble ?: return ProofVerdict.WRONG_ENSEMBLE
        if (!creation.creatorKeys.signing.contentEquals(genesis.proof?.publicKey)) return ProofVerdict.FORGED
        var creator = genesis.author
        var clock = genesis.clock
        for (handover in handovers) {
            val to = (handover.change as? Change.TransferCreator)?.member ?: return ProofVerdict.NOT_BY_THE_CREATOR
            if (handover.author != creator || handover.clock <= clock || to == creator) {
                return ProofVerdict.NOT_BY_THE_CREATOR
            }
            creator = to
            clock = handover.clock
        }
        if (dissolution.change != Change.Dissolve || dissolution.author != creator || dissolution.clock <= clock) {
            return ProofVerdict.NOT_BY_THE_CREATOR
        }
        return if (replica == null) ProofVerdict.VALID else against(replica)
    }

    private fun against(replica: HierarchyReplica): ProofVerdict {
        // The key that signs is the very key the signer came in with, when this phone saw it come in (§ 8.1).
        for (op in handovers + dissolution) {
            val known = replica.signingKeyOf(op.author) ?: continue
            if (!known.contentEquals(op.proof?.publicKey)) return ProofVerdict.FORGED
        }
        val held = replica.ops().associateBy { it.author to it.seq }
        if (all.any { op -> held[op.author to op.seq]?.let { it != op } == true }) return ProofVerdict.INCONSISTENT
        val history = replica.history()
        val refused = history.filter { it.rejection != null }.map { it.op }
        if ((handovers + dissolution).any { it in refused }) return ProofVerdict.INCONSISTENT
        // A handing over this phone applied and the proof leaves out: the dissolution would not be the creator's.
        val applied = history.filter { it.rejection == null && it.op.change is Change.TransferCreator }.map { it.op }
        if (applied.size > handovers.size || applied != handovers.subList(0, applied.size)) {
            return ProofVerdict.INCONSISTENT
        }
        return ProofVerdict.VALID
    }

    companion object {
        /** More handings over than an operation has in practice; a longer chain would not fit one frame. */
        const val MAX_HANDOVERS = 16

        /** The proof of [encode]; a [WireException] for anything else. */
        fun decode(bytes: ByteArray): DissolutionProof {
            val ops = HierarchyWire.decodeOps(bytes)
            if (ops.size < 2 || ops.size > MAX_HANDOVERS + 2) throw WireException("not a proof of dissolution")
            val middle = ops.subList(1, ops.size - 1)
            if (ops.first().change !is Change.CreateEnsemble ||
                ops.last().change != Change.Dissolve ||
                middle.any { it.change !is Change.TransferCreator }
            ) {
                throw WireException("not a proof of dissolution")
            }
            return DissolutionProof(ops.first(), middle.toList(), ops.last())
        }

        /**
         * The proof [replica] holds, once dissolved: its first operation, the handings over that applied, the
         * dissolution. `null` when it is not dissolved, or after more than [MAX_HANDOVERS] handings over (said).
         */
        fun of(replica: HierarchyReplica): DissolutionProof? {
            if (!replica.state.dissolved) return null
            val applied = replica.history().filter { it.rejection == null }.map { it.op }
            val genesis = applied.firstOrNull { HierarchyReducer.isGenesisOf(replica.ensembleId, it) } ?: return null
            val dissolution = applied.lastOrNull { it.change == Change.Dissolve } ?: return null
            val handovers = applied.filter { it.change is Change.TransferCreator }
            if (handovers.size > MAX_HANDOVERS) {
                Problems.expected("hierarchy.dissolution.handovers", FrameRefused("too many handovers for a trace"))
                return null
            }
            return DissolutionProof(genesis, handovers, dissolution)
        }
    }
}

/**
 * What a phone that keeps the trace of an operation tells a phone still in it ([FrameGenre.DISSOLUTION]): when the
 * operation was dissolved, as the phone that tells it learnt it ([dissolvedAtMs], not signed: it only bounds how long
 * the phone told keeps a trace in turn), and the proof.
 */
class DissolutionNotice(
    val dissolvedAtMs: Long,
    val proof: DissolutionProof,
) {
    fun encode(): ByteArray = ByteWriter().u64(dissolvedAtMs).bytes(proof.encode()).toByteArray()

    companion object {
        fun decode(bytes: ByteArray): DissolutionNotice {
            val r = ByteReader(bytes)
            val at = r.u64()
            return DissolutionNotice(at, DissolutionProof.decode(r.rest()))
        }
    }
}

/** One invitation key a trace keeps: what opens and seals the frames of its [epoch]. */
class TraceKey(
    val epoch: Int,
    val key: ByteArray,
) {
    init {
        require(epoch >= 1 && key.size == OperationInvite.KEY_BYTES) { "bad invitation key" }
    }
}

/**
 * The trace a phone keeps of an operation dissolved while it was a member of it, a week from the dissolution
 * ([KEPT_MS]): what it needs to recognise the operation's room and its frames ([room], [server], the invitation keys of
 * the last epochs, [keys]), the identifier it speaks under there ([sender]: drawn at random when the trace is made,
 * not the member identifier this phone had, so that the trace does not tell which member kept it), and the proof of
 * the dissolution ([proof], which carries the identifier and public key of whoever signed it). No position, no name,
 * no message, no content key, no private key: its frames carry the proof only, and anyone who holds the invitation
 * could already read everything the proof holds.
 */
class DissolutionTrace(
    val ensembleId: Long,
    val dissolvedAtMs: Long,
    val sender: Long,
    val room: ByteArray,
    val server: RelayConfig?,
    val keys: List<TraceKey>,
    val proof: DissolutionProof,
) {
    init {
        require(proof.ensembleId == ensembleId && ensembleId != 0L && sender != 0L) { "trace of another operation" }
        require(room.size == OperationInvite.ROOM_BYTES && keys.size in 1..OperationLinkState.KEPT_EPOCHS) {
            "bad trace"
        }
    }

    /** When it is wiped: a week after the dissolution. */
    val expiresAtMs: Long get() = dissolvedAtMs + KEPT_MS

    /** Over a week after the dissolution, or a dissolution more than a week ahead of this clock (a wrong clock). */
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs || dissolvedAtMs - nowMs > KEPT_MS

    fun keyOf(epoch: Int): ByteArray? = keys.firstOrNull { it.epoch == epoch }?.key

    fun notice(): DissolutionNotice = DissolutionNotice(dissolvedAtMs, proof)

    fun encode(): ByteArray {
        val w = ByteWriter().u8(VERSION).u64(ensembleId).u64(dissolvedAtMs).u64(sender).bytes(room)
        if (server == null) {
            w.u8(0)
        } else {
            w.u8(1).str(server.base, RelayConfig.MAX_URL_BYTES).str(server.apiKey, RelayConfig.MAX_KEY_BYTES)
        }
        w.u8(keys.size)
        for (k in keys) w.u32(k.epoch.toLong()).bytes(k.key)
        val proofBytes = proof.encode()
        return w.u32(proofBytes.size.toLong()).bytes(proofBytes).toByteArray()
    }

    companion object {
        const val VERSION = 1

        /** A week (the user's answer R1). */
        const val KEPT_MS = 7L * 24 * 3600 * 1000

        fun decode(bytes: ByteArray): DissolutionTrace {
            val r = ByteReader(bytes)
            if (r.u8() != VERSION) throw WireException("unknown version of a trace")
            val ensemble = r.u64()
            val at = r.u64()
            val sender = r.u64()
            val room = r.bytes(OperationInvite.ROOM_BYTES)
            val server =
                when (r.u8()) {
                    0 -> null
                    1 -> RelayConfig(r.str(), r.str())
                    else -> throw WireException("bad server flag")
                }
            val n = r.u8()
            if (n !in 1..OperationLinkState.KEPT_EPOCHS) throw WireException("bad number of keys in a trace")
            val keys =
                List(n) {
                    val epoch = r.u32()
                    if (epoch !in 1..Int.MAX_VALUE) throw WireException("bad epoch in a trace")
                    TraceKey(epoch.toInt(), r.bytes(OperationInvite.KEY_BYTES))
                }
            val proofBytes = r.bytes(r.u32().toInt())
            if (r.remaining != 0) throw WireException("trailing data")
            val proof = DissolutionProof.decode(proofBytes)
            if (proof.ensembleId != ensemble || ensemble == 0L || sender == 0L) throw WireException("bad trace")
            return DissolutionTrace(ensemble, at, sender, room, server, keys, proof)
        }

        /**
         * The trace of the operation of [link] that [proof] ends, dissolved at [dissolvedAtMs]; [random] draws the
         * identifier it speaks under in the room.
         */
        fun of(
            proof: DissolutionProof,
            link: OperationLinkState,
            dissolvedAtMs: Long,
            random: SecureRandom = SecureRandom(),
        ): DissolutionTrace =
            DissolutionTrace(
                link.ensembleId,
                dissolvedAtMs,
                generateSequence { random.nextLong() }.first { it != 0L },
                link.room,
                link.server,
                link.epochs.map { TraceKey(it.epoch, it.key) },
                proof,
            )
    }
}

/**
 * The part of a phone that keeps a trace and listens in the room of the dissolved operation (ADR-131): to any frame of
 * a phone still in it, sealed with an invitation key the trace holds, it answers once a minute at most with the proof
 * ([DissolutionNotice]), under the same key, which that phone holds. It answers nothing else: not its own frames, not
 * another trace's, not a frame it cannot open.
 */
class DissolutionAnnouncer(
    private val trace: DissolutionTrace,
    private val clock: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    private val notice = trace.notice().encode()
    private val frameKeys = HashMap<Int, ByteArray>()
    private val answeredAt = LinkedHashMap<Long, Long>()

    /** How many times the proof went out. */
    @get:Synchronized
    var answered: Int = 0
        private set

    init {
        require(notice.size <= OperationFrames.MAX_BODY) { "a notice that does not fit a frame" }
    }

    /** The frame to send back for [frame], received in the room; `null` when there is nothing to answer. */
    @Synchronized
    fun receive(frame: ByteArray): ByteArray? {
        val epoch = OperationFrames.epochOf(frame) ?: return null
        val key = trace.keyOf(epoch) ?: return null
        val frameKey = frameKeys.getOrPut(epoch) { OperationFrames.frameKey(key, trace.ensembleId, epoch) }
        val opened = OperationFrames.open(frameKey, trace.ensembleId, frame) ?: return null
        if (opened.genre == FrameGenre.DISSOLUTION || opened.sender == trace.sender) return null
        val now = clock()
        val last = answeredAt.remove(opened.sender)
        if (last != null && now - last in 0 until ANSWER_EVERY_MS) {
            answeredAt[opened.sender] = last
            return null
        }
        answeredAt[opened.sender] = now
        while (answeredAt.size > MAX_SENDERS) answeredAt.remove(answeredAt.keys.first())
        answered++
        return OperationFrames.seal(
            frameKey,
            trace.ensembleId,
            epoch,
            FrameGenre.DISSOLUTION,
            trace.sender,
            notice,
            false,
            random,
        )
    }

    companion object {
        /** A phone still in the operation speaks at least once a minute (its summary): one answer each time at most. */
        const val ANSWER_EVERY_MS = 60_000L
        private const val MAX_SENDERS = 256
    }
}
