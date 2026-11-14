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

    /** Not signed by its author: not kept. Every operation of an ensemble carries the proof of its author. */
    UNAUTHENTICATED,

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
 * @param keys sign what [issue] writes; without them the copy only reads (a phone that relays, a test).
 */
class HierarchyReplica(
    val ensembleId: Long,
    val self: Long,
    private val keys: PhoneKeys? = null,
) {
    init {
        require(keys == null || keys.memberId == self) { "the keys are not the ones of this member" }
    }

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

    /**
     * For each author, the clock of its last operation that counts. An author's operations count in the order it
     * numbered them, with a clock that only goes forward: one whose clock does not pass the one before it is
     * refused ([Rejection.OUT_OF_ORDER]) wherever the order would put it. Nobody can write « before » what it
     * already wrote, so nobody can undo its own handing over of the ensemble, nor its dissolution.
     */
    private val lastClock = HashMap<Long, Long>()

    /** The operations refused for that reason, by author and number. */
    private val outOfOrder = HashSet<Pair<Long, Int>>()
    private var current = HierarchyState.empty(ensembleId)
    private var digest: Long? = null

    /**
     * Every list of readers a channel had at some point of the operations held, by channel and tag, the most recent
     * last: what a key batch is checked against ([KeyRing.offer]). A list that only existed before an operation
     * arrived « in the past » stays: a key made for it is still wrapped to that list and to nobody else.
     */
    private val readersSeen = LinkedHashMap<ReadersRef, Readers>(16, 0.75f, true)

    /** The readers each channel has in [current]: a list that did not change is not fingerprinted again. */
    private var readersNow = HashMap<Channel, Readers>()

    /**
     * The signing key of every member the applied operations ever let in, gone since or not: what the signature of
     * something a member shared before it left is checked against ([SharedObjects]).
     */
    private val signers = HashMap<Long, ByteArray>()

    val state: HierarchyState
        @Synchronized get() = current

    /** Takes in an operation received from another phone, or read back from the disk. */
    @Synchronized
    fun add(op: Op): AddResult {
        if (op.ensembleId != ensembleId) return AddResult.WRONG_ENSEMBLE
        if (op.isMalformed) return AddResult.MALFORMED
        val known = byAuthor[op.author]?.get(op.seq)
        // Already there, proven when it came in: a copy received again is not checked again.
        if (known != null && known == op) return AddResult.DUPLICATE
        if (!op.authentic) return AddResult.UNAUTHENTICATED
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
        for (seq in op.seq..last) mark(ops.getValue(seq))
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
        val upToDate = (byAuthor[self]?.lastKey() ?: 0) == seq - 1
        if (!upToDate || maxClock >= Op.MAX_CLOCK || stored >= MAX_OPS) return IssueResult.Refused(Rejection.LOG_FULL)
        val draft = Op(ensembleId, self, seq, maxClock + 1, change)
        if (draft.isMalformed) return IssueResult.Refused(Rejection.MALFORMED)
        val op = keys?.let { OpAuth.sign(draft, it) } ?: return IssueResult.Refused(Rejection.UNAUTHENTICATED)
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

    /**
     * The readers [channel] had under the tag [tag] at some point of the operations held; `null` when this phone
     * never saw such a list (it may lack operations: a catch-up, then [KeyRing.retry]).
     */
    @Synchronized
    fun readers(
        channel: Channel,
        tag: ByteArray,
    ): Readers? = readersSeen[ReadersRef(channel, tag.asList())]

    /**
     * The signing key [member] was let in with, whether it is still in the ensemble or not; `null` for someone the
     * operations held never let in.
     */
    @Synchronized
    fun signingKeyOf(member: Long): ByteArray? = signers[member]

    /** Same author, same number: one operation at most is kept, the same on every phone that saw both. */
    private fun settle(
        known: Op,
        op: Op,
    ): AddResult {
        if (!wins(op, known)) return AddResult.CONFLICT_KEPT
        val ops = byAuthor.getValue(op.author)
        ops[op.seq] = op
        maxClock = maxOf(maxClock, op.clock)
        digest = null
        // Which operations of this author count is read again from its first one.
        lastClock.remove(op.author)
        outOfOrder.removeAll { it.first == op.author }
        for (one in ops.headMap(contiguous[op.author] ?: 0, true).values) mark(one)
        replay()
        return AddResult.CONFLICT_REPLACED
    }

    /**
     * The first operation of the ensemble wins over anything else under its number, then the one whose bytes come
     * first. Both are signed by their author: two operations under one number are its doing, not a forgery.
     */
    private fun wins(
        op: Op,
        known: Op,
    ): Boolean {
        val genesis = HierarchyReducer.isGenesisOf(ensembleId, op)
        if (genesis != HierarchyReducer.isGenesisOf(ensembleId, known)) return genesis
        return compareBytes(op.content, known.content) < 0
    }

    /** Says whether [op], the next one of its author, counts: its clock passes the one before it. */
    private fun mark(op: Op) {
        if (op.clock >
            (lastClock[op.author] ?: 0L)
        ) {
            lastClock[op.author] = op.clock
        } else {
            outOfOrder.add(op.author to op.seq)
        }
    }

    private fun step(op: Op) {
        if ((op.author to op.seq) in outOfOrder) {
            applied.add(Entry(op, Rejection.OUT_OF_ORDER))
            return
        }
        val reduced = HierarchyReducer.reduce(current, op)
        current = reduced.state
        applied.add(Entry(op, reduced.rejection))
        if (reduced.rejection != null) return
        when (val change = op.change) {
            is Change.CreateEnsemble -> signers[op.author] = change.creatorKeys.signing
            is Change.AdmitMember -> signers[change.member] = change.keys.signing
            else -> Unit
        }
        if (op.change.movesReaders) rememberReaders()
    }

    private fun rememberReaders() {
        val now = HashMap<Channel, Readers>()
        for (channel in current.channels()) {
            val members = current.readersOf(channel)
            if (members.isEmpty()) continue
            val known = readersNow[channel]
            val readers =
                if (known != null &&
                    known.members == members
                ) {
                    known
                } else {
                    Readers(ensembleId, channel, members)
                }
            now[channel] = readers
            if (readers !== known) readersSeen[ReadersRef(channel, readers.tag.asList())] = readers
        }
        readersNow = now
        val eldest = readersSeen.keys.iterator()
        while (readersSeen.size > MAX_READERS_SEEN) {
            eldest.next()
            eldest.remove()
        }
    }

    private fun replay() {
        applied.clear()
        signers.clear()
        readersNow = HashMap()
        current = HierarchyState.empty(ensembleId)
        val ops = byAuthor.flatMap { (author, ops) -> ops.headMap(contiguous[author] ?: 0, true).values }
        for (op in ops.sortedWith(Op.ORDER)) step(op)
    }

    /** 8 bytes of the SHA-256 of the applied operations, by author then number. */
    private fun computeDigest(): Long {
        val sha = MessageDigest.getInstance("SHA-256")
        for ((author, ops) in byAuthor.entries.sortedWith(BY_AUTHOR)) {
            for (op in ops.headMap(contiguous[author] ?: 0, true).values) {
                sha.update(OpAuth.longBytes(op.content.size.toLong()))
                sha.update(op.content)
            }
        }
        return sha.digest().take(Long.SIZE_BYTES).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    }

    private data class ReadersRef(
        val channel: Channel,
        val tag: List<Byte>,
    )

    companion object {
        /** The most operations a phone keeps for one ensemble. */
        const val MAX_OPS = 20_000

        /** The most lists of readers remembered: the ones of now, and enough of the past for late key batches. */
        private const val MAX_READERS_SEEN = 4096

        /**
         * Whether a change may alter who reads a channel: who is in the ensemble, in which group, at which post
         * (the posts of a group decide who reads its lane up and the lane of the leaders).
         */
        private val Change.movesReaders: Boolean
            get() = this !is Change.RenameGroup && this !is Change.CreateGroup

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
         * A new ensemble created by the phone that holds [keys]: the copy of its creator, first operation written.
         * [nonce] is a random number that makes the identifier of the ensemble unique. The names are hidden
         * ([Names.hide]): the caller keeps their cards ([NameBook]) and shares them once it has a key.
         */
        fun create(
            keys: PhoneKeys,
            creatorName: HiddenName,
            name: HiddenName,
            nonce: Long,
            rules: HierarchyRules = HierarchyRules.DEFAULT,
        ): HierarchyReplica {
            val creator = keys.memberId
            val genesis = Change.CreateEnsemble(nonce, name, creatorName, keys.public, rules)
            val replica = HierarchyReplica(OpAuth.ensembleId(creator, genesis), creator, keys)
            val result = replica.issue(genesis)
            require(result is IssueResult.Issued) { "ensemble not created: ${(result as IssueResult.Refused).reason}" }
            return replica
        }

        /** The copy of this phone rebuilt from the operations it stored ([ops]). */
        fun restore(
            ensembleId: Long,
            self: Long,
            ops: List<Op>,
            keys: PhoneKeys? = null,
        ): HierarchyReplica = HierarchyReplica(ensembleId, self, keys).apply { addAll(ops) }
    }
}
