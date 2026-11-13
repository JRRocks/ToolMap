package fr.toolmap.core.hierarchy

import java.security.MessageDigest
import java.util.TreeMap

/** What became of an operation handed to [HierarchyReplica.add]. */
enum class AddResult {
    /** New, and applied (which may be as a refusal: [HierarchyReplica.history]). */
    ADDED,

    /** New, kept aside: operations of its author before it are missing. A catch-up will bring them. */
    HELD,

    /** Already there. */
    DUPLICATE,

    /** Its author wrote two different operations under one number; this one takes the place of the other. */
    CONFLICT_REPLACED,

    /** Its author wrote two different operations under one number; the one already there stays. */
    CONFLICT_KEPT,

    /** For another ensemble: not kept. */
    WRONG_ENSEMBLE,

    /** No phone would accept it: not kept. */
    MALFORMED,

    /** This phone keeps no more operations ([HierarchyReplica.MAX_OPS]): not kept. */
    FULL,
}

/** What became of a change this phone asked for ([HierarchyReplica.issue]). */
sealed interface IssueResult {
    /** Applied here; [op] is to send to the others. */
    class Issued(
        val op: Op,
    ) : IssueResult

    /** Refused here, for [reason]: nothing was written, nothing is to send. */
    class Refused(
        val reason: Rejection,
    ) : IssueResult
}

/**
 * The copy of one ensemble on one phone: every operation it received, and the state they give. There is no central
 * authority: each phone keeps the operations of every author and applies them in the same order ([Op.ORDER]), each
 * checked against the rights of its author at that point. Two phones that hold the same operations, received in any
 * order, hold the same state (`docs/specs/group-hierarchy.md` § 6).
 *
 * Only the operations an author wrote without a gap are applied (1, 2, 3…); a later one waits for the missing ones.
 * A phone that was away catches up by exchanging a [summary] for the operations it lacks ([missingFor]).
 *
 * Knows nothing of the network nor of the disk: the caller sends the bytes of [HierarchyWire] and stores [ops].
 *
 * @param self the member identifier of this phone, author of what [issue] writes.
 * @param signer signs what [issue] writes; needed in an ensemble whose rules ask for signed operations.
 */
class HierarchyReplica(
    val ensembleId: Long,
    val self: Long,
    private val signer: OpSigner? = null,
) {
    /** An operation in the order it was applied, and why it changed nothing when it did not. */
    class Entry(
        val op: Op,
        val rejection: Rejection?,
    )

    private val byAuthor = HashMap<Long, TreeMap<Int, Op>>()

    /** For each author, how many operations are there without a gap: the ones that are applied. */
    private val contiguous = HashMap<Long, Int>()
    private var stored = 0
    private var maxClock = 0L
    private val applied = ArrayList<Entry>()

    /** While applying in order: the number of the last operation of each author that was let through. */
    private val reached = HashMap<Long, Int>()
    private var current = HierarchyState.empty(ensembleId)
    private var digest: Long? = null

    val state: HierarchyState
        @Synchronized get() = current

    /** Takes in an operation received from another phone, or read back from the disk. */
    @Synchronized
    fun add(op: Op): AddResult {
        if (op.ensembleId != ensembleId) return AddResult.WRONG_ENSEMBLE
        if (op.isMalformed) return AddResult.MALFORMED
        val known = byAuthor[op.author]?.get(op.seq)
        if (known != null) return settle(known, op)
        if (stored >= MAX_OPS) return AddResult.FULL
        val ops = byAuthor.getOrPut(op.author) { TreeMap() }
        ops[op.seq] = op
        stored++
        maxClock = maxOf(maxClock, op.clock)
        val before = contiguous[op.author] ?: 0
        if (op.seq != before + 1) return AddResult.HELD
        var last = op.seq
        while (ops.containsKey(last + 1)) last++
        contiguous[op.author] = last
        digest = null
        val isLatest = applied.isEmpty() || Op.ORDER.compare(applied.last().op, op) < 0
        if (last == op.seq && isLatest) step(op) else replay()
        return AddResult.ADDED
    }

    /** Takes in [ops], in the order given; the number that were new. */
    @Synchronized
    fun addAll(ops: List<Op>): Int =
        ops.count { op ->
            when (add(op)) {
                AddResult.ADDED, AddResult.HELD, AddResult.CONFLICT_REPLACED -> true
                else -> false
            }
        }

    /**
     * Asks for [change] in the name of this phone. Checked here first: a change this phone knows it may not make is
     * not written at all. Once written it may still lose on the other phones against an operation this one had not
     * received yet; [history] tells.
     */
    @Synchronized
    fun issue(change: Change): IssueResult {
        val seq = (contiguous[self] ?: 0) + 1
        val upToDate = (byAuthor[self]?.lastKey() ?: 0) == seq - 1 && (reached[self] ?: 0) == seq - 1
        if (!upToDate || maxClock >= Op.MAX_CLOCK || stored >= MAX_OPS) return IssueResult.Refused(Rejection.LOG_FULL)
        val draft = Op(ensembleId, self, seq, maxClock + 1, change)
        if (draft.isMalformed) return IssueResult.Refused(Rejection.MALFORMED)
        val op = signer?.let { OpAuth.sign(draft, it) } ?: draft
        HierarchyReducer.reduce(current, op).rejection?.let { return IssueResult.Refused(it) }
        check(add(op) == AddResult.ADDED) { "an operation checked here was not added" }
        return IssueResult.Issued(op)
    }

    /** Every operation kept, by author then number: what to store, and to hand to [restore]. */
    @Synchronized
    fun ops(): List<Op> = byAuthor.entries.sortedWith(BY_AUTHOR).flatMap { it.value.values }

    /** The operations applied, in their order, each with the reason it changed nothing if so. */
    @Synchronized
    fun history(): List<Entry> = applied.toList()

    /** What this phone holds, to send to another one. */
    @Synchronized
    fun summary(): Summary = Summary(ensembleId, contiguous.toMap(), digest ?: computeDigest().also { digest = it })

    /**
     * The operations to send to the phone that gave [theirs]: the ones after what it holds of each author, at most
     * [max] (the rest goes with its next summary). When both hold as many operations of every author and the
     * fingerprints differ, an author wrote two operations under one number: everything is sent, whatever [max]
     * ([HierarchyWire.encodeOpBlocks] cuts it), and both phones keep the same one of the two ([add]).
     */
    @Synchronized
    fun missingFor(
        theirs: Summary,
        max: Int = HierarchyWire.MAX_BATCH,
    ): List<Op> {
        if (theirs.ensembleId != ensembleId) return emptyList()
        val mine = summary()
        val sameCounts = mine.counts == theirs.counts
        if (sameCounts && mine.digest == theirs.digest) return emptyList()
        val authors = byAuthor.entries.sortedWith(BY_AUTHOR)
        if (sameCounts) return authors.flatMap { it.value.values }
        return authors.flatMap { (author, ops) -> ops.tailMap(theirs.counts[author] ?: 0, false).values }.take(max)
    }

    /** Same author, same number: one operation at most is kept, the same on every phone that saw both. */
    private fun settle(
        known: Op,
        op: Op,
    ): AddResult {
        val same = known == op
        // A copy with its proof replaces a copy without: stripping the proof must not hide the operation.
        if (same && (known.authentic || !op.authentic)) return AddResult.DUPLICATE
        if (!same && !wins(op, known)) return AddResult.CONFLICT_KEPT
        byAuthor.getValue(op.author)[op.seq] = op
        maxClock = maxOf(maxClock, op.clock)
        digest = null
        replay()
        return if (same) AddResult.DUPLICATE else AddResult.CONFLICT_REPLACED
    }

    /**
     * The first operation of the ensemble wins over anything else under its number, then a proven operation over one
     * that is not, then the one whose bytes come first.
     */
    private fun wins(
        op: Op,
        known: Op,
    ): Boolean {
        val genesis = HierarchyReducer.isGenesisOf(ensembleId, op)
        if (genesis != HierarchyReducer.isGenesisOf(ensembleId, known)) return genesis
        if (op.authentic != known.authentic) return op.authentic
        return compareBytes(op.content, known.content) < 0
    }

    private fun step(op: Op) {
        // An author's operations count in the order it wrote them: one that the order puts after a later one of the
        // same author (its clock went backwards) is refused, and the author goes on from the later one.
        if (op.seq <= (reached[op.author] ?: 0)) {
            applied.add(Entry(op, Rejection.OUT_OF_ORDER))
            return
        }
        reached[op.author] = op.seq
        val reduced = HierarchyReducer.reduce(current, op)
        current = reduced.state
        applied.add(Entry(op, reduced.rejection))
    }

    private fun replay() {
        applied.clear()
        reached.clear()
        current = HierarchyState.empty(ensembleId)
        val ops = byAuthor.flatMap { (author, ops) -> ops.headMap(contiguous[author] ?: 0, true).values }
        for (op in ops.sortedWith(Op.ORDER)) step(op)
    }

    /** 8 bytes of the SHA-256 of the applied operations, by author then number, each with whether it is proven. */
    private fun computeDigest(): Long {
        val sha = MessageDigest.getInstance("SHA-256")
        for ((author, ops) in byAuthor.entries.sortedWith(BY_AUTHOR)) {
            for (op in ops.headMap(contiguous[author] ?: 0, true).values) {
                sha.update(OpAuth.longBytes(op.content.size.toLong()))
                sha.update(op.content)
                sha.update(if (op.authentic) 1 else 0)
            }
        }
        return sha.digest().take(Long.SIZE_BYTES).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    }

    companion object {
        /** The most operations a phone keeps for one ensemble. */
        const val MAX_OPS = 20_000

        private val BY_AUTHOR: Comparator<Map.Entry<Long, *>> =
            Comparator { a, b -> java.lang.Long.compareUnsigned(a.key, b.key) }

        private fun compareBytes(
            a: ByteArray,
            b: ByteArray,
        ): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        /**
         * A new ensemble created by [creator]: the copy of its creator, first operation written. [nonce] is a random
         * number that makes the identifier of the ensemble unique. Names must be [Names.valid].
         */
        fun create(
            creator: Long,
            creatorName: String,
            name: String,
            nonce: Long,
            rules: HierarchyRules = HierarchyRules.DEFAULT,
            signer: OpSigner? = null,
        ): HierarchyReplica {
            val genesis = Change.CreateEnsemble(nonce, name, creatorName, rules)
            val replica = HierarchyReplica(OpAuth.ensembleId(creator, genesis), creator, signer)
            val result = replica.issue(genesis)
            require(result is IssueResult.Issued) { "ensemble not created: ${(result as IssueResult.Refused).reason}" }
            return replica
        }

        /** The copy of this phone rebuilt from the operations it stored ([ops]). */
        fun restore(
            ensembleId: Long,
            self: Long,
            ops: List<Op>,
            signer: OpSigner? = null,
        ): HierarchyReplica = HierarchyReplica(ensembleId, self, signer).apply { addAll(ops) }
    }
}
