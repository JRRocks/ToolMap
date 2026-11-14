package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems

/**
 * One state of something that stays on the map of the others until someone takes it away: a sketch or a waypoint
 * ([ShareKind.standing]). It is the payload of the share that carries it, and it carries its own signature, which
 * does not depend on the content key: the same record can be sealed again, later, with another key, for a member who
 * arrives or comes back (`docs/specs/group-hierarchy.md` § 8.7).
 *
 * An object is named by its [author] and the [objectId] the author gave it, in its [channel]. Each change of it is a
 * new [revision]; the highest one counts. A record that is [deleted] says the object was taken away, by its author or
 * by someone else ([editor]).
 */
class SharedRecord(
    val kind: ShareKind,
    val channel: Channel,
    /** Who made the object. */
    val author: Long,
    val objectId: Long,
    /** From 1; a change or a removal is one more than the revision it replaces. */
    val revision: Long,
    /** Who wrote this revision and signed it: the author for a content, anyone for a removal. */
    val editor: Long,
    val deleted: Boolean,
    /** The caller's: what the sketch or the waypoint is. Empty when [deleted]. */
    val body: ByteArray,
    val signature: ByteArray,
) {
    val ref: ObjectRef get() = ObjectRef(channel, author, objectId)

    override fun toString(): String = "SharedRecord($kind $ref r$revision${if (deleted) " deleted" else ""})"
}

/** What names a shared object. */
data class ObjectRef(
    val channel: Channel,
    val author: Long,
    val objectId: Long,
)

/** What became of a record handed to [SharedObjects.accept]. */
enum class ObjectVerdict {
    /** New, or newer than what was held: kept. */
    STORED,

    /** Already held. */
    DUPLICATE,

    /** Older than what is held: an object that changed or was taken away does not come back. */
    OLDER,

    /** Not a share of a kind that stays, or not a record. */
    MALFORMED,

    /** Not about the channel or the kind of the share that carries it. */
    MISPLACED,

    /** A content not written by the author of the object, or a signature that is not the editor's. */
    FORGED,

    /** Its editor was never let into the ensemble, as far as this phone knows. */
    UNKNOWN_EDITOR,

    /** Too many objects in this channel. */
    FULL,
}

/** How records are made, signed, written and read. Pure. */
object SharedRecords {
    private val RECORD_TAG = "toolmap-hierarchy-object-v3".toByteArray(Charsets.US_ASCII)
    private const val DELETED = 1

    /** `author (8) | object (8) | revision (4) | editor (8) | flags (1) | signature (64)`, then the body. */
    const val OVERHEAD_BYTES = 29 + P256.SIGNATURE_BYTES

    /** A body fits one share with its record around it. */
    const val MAX_BODY = ShareSeal.MAX_PAYLOAD - OVERHEAD_BYTES
    const val MAX_REVISION = 0xFFFFFFFFL

    /**
     * The first state of a new object ([previous] `null`), or the next state of one this phone is the author of.
     * [objectId] is drawn by the author, never 0.
     */
    fun write(
        keys: PhoneKeys,
        ensembleId: Long,
        kind: ShareKind,
        channel: Channel,
        objectId: Long,
        body: ByteArray,
        previous: SharedRecord? = null,
    ): SharedRecord {
        val self = keys.memberId
        require(kind.standing) { "not a kind that stays" }
        require(objectId != 0L && body.size <= MAX_BODY) { "bad object" }
        require(previous == null || (previous.ref == ObjectRef(channel, self, objectId) && previous.kind == kind)) {
            "not the next state of this object"
        }
        val revision = (previous?.revision ?: 0L) + 1
        require(revision <= MAX_REVISION) { "too many revisions" }
        return signed(keys, ensembleId, SharedRecord(kind, channel, self, objectId, revision, self, false, body, EMPTY))
    }

    /** The record that takes the object of [previous] away, written by the phone that holds [keys]. */
    fun remove(
        keys: PhoneKeys,
        ensembleId: Long,
        previous: SharedRecord,
    ): SharedRecord {
        val revision = previous.revision + 1
        require(revision <= MAX_REVISION) { "too many revisions" }
        val gone =
            SharedRecord(
                previous.kind,
                previous.channel,
                previous.author,
                previous.objectId,
                revision,
                keys.memberId,
                true,
                EMPTY,
                EMPTY,
            )
        return signed(keys, ensembleId, gone)
    }

    private fun signed(
        keys: PhoneKeys,
        ensembleId: Long,
        record: SharedRecord,
    ): SharedRecord =
        SharedRecord(
            record.kind,
            record.channel,
            record.author,
            record.objectId,
            record.revision,
            record.editor,
            record.deleted,
            record.body,
            keys.sign(signedBytes(ensembleId, record)),
        )

    /** Whether [record] carries the signature of its editor, whose signing key is [signingKey]. */
    fun verify(
        ensembleId: Long,
        record: SharedRecord,
        signingKey: ByteArray,
    ): Boolean = P256.verify(signingKey, signedBytes(ensembleId, record), record.signature)

    /** Everything a record says, with the ensemble, the channel and the kind it is for; not the content key. */
    private fun signedBytes(
        ensembleId: Long,
        record: SharedRecord,
    ): ByteArray {
        val w = ByteWriter().bytes(RECORD_TAG).u64(ensembleId).bytes(record.channel.bytes()).u8(record.kind.code)
        return head(w, record).bytes(record.body).toByteArray()
    }

    private fun head(
        w: ByteWriter,
        record: SharedRecord,
    ): ByteWriter =
        w.u64(record.author).u64(record.objectId).u32(record.revision).u64(record.editor).u8(
            if (record.deleted) DELETED else 0,
        )

    /** The payload of the share that carries [record]. */
    fun encode(record: SharedRecord): ByteArray =
        head(ByteWriter(), record).bytes(record.signature).bytes(record.body).toByteArray()

    /** The record a share of [kind] in [channel] carries; a [WireException] when [payload] is not one. */
    fun decode(
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
    ): SharedRecord {
        val r = ByteReader(payload)
        val author = r.u64()
        val objectId = r.u64()
        val revision = r.u32()
        val editor = r.u64()
        val flags = r.u8()
        val signature = r.bytes(P256.SIGNATURE_BYTES)
        val body = r.rest()
        if (author == 0L || objectId == 0L || editor == 0L || revision < 1) throw WireException("bad shared object")
        if (flags and DELETED.inv() != 0) throw WireException("unknown flag of a shared object")
        val deleted = flags and DELETED != 0
        if (deleted && body.isNotEmpty()) throw WireException("removed object with a body")
        return SharedRecord(kind, channel, author, objectId, revision, editor, deleted, body, signature)
    }

    private val EMPTY = ByteArray(0)
}

/**
 * The sketches and waypoints one phone holds for one ensemble: for each object the newest record it received, taken
 * away or not. What is « still shown » is every object whose newest record is not a removal, in a channel this phone
 * reads. Knows nothing of the network: the caller hands it the shares it opened ([accept]) and seals again, with the
 * key of now, the records of [resend] for a member who arrives or comes back.
 *
 * Who may do what is not decided here: a record only reaches [accept] in a share that [ShareSeal.open] let through,
 * so sealed by someone who may share that kind in that channel now. That member answers for the record: it wrote it,
 * or it sends again a record it held. The signature inside the record says who made the object, and that nobody
 * changed it on the way.
 */
class SharedObjects(
    val ensembleId: Long,
) {
    private val held = HashMap<Channel, LinkedHashMap<ObjectRef, SharedRecord>>()

    /**
     * Takes in a share of a kind that stays, once opened. [signingKeyOf] gives the signing key a member was let in
     * with, gone since or not ([HierarchyReplica.signingKeyOf]).
     */
    @Synchronized
    fun accept(
        share: Share,
        signingKeyOf: (Long) -> ByteArray?,
    ): ObjectVerdict {
        if (!share.kind.standing) return ObjectVerdict.MALFORMED
        val record =
            try {
                SharedRecords.decode(share.kind, share.channel, share.payload)
            } catch (e: WireException) {
                Problems.expected("hierarchy.objects.decode", e)
                return ObjectVerdict.MALFORMED
            }
        return accept(record, signingKeyOf)
    }

    /** Takes in [record], already known to come from someone who may share its kind in its channel. */
    @Synchronized
    fun accept(
        record: SharedRecord,
        signingKeyOf: (Long) -> ByteArray?,
    ): ObjectVerdict {
        if (!record.kind.standing) return ObjectVerdict.MALFORMED
        // Only its author changes an object; anyone the share let through may take it away.
        if (!record.deleted && record.editor != record.author) return ObjectVerdict.FORGED
        val key = signingKeyOf(record.editor) ?: return ObjectVerdict.UNKNOWN_EDITOR
        if (!SharedRecords.verify(ensembleId, record, key)) return ObjectVerdict.FORGED
        val ofChannel = held.getOrPut(record.channel) { LinkedHashMap() }
        val known = ofChannel[record.ref]
        if (known != null) {
            if (known.kind != record.kind) return ObjectVerdict.MISPLACED
            val order = ORDER.compare(record, known)
            if (order == 0) return ObjectVerdict.DUPLICATE
            if (order < 0) return ObjectVerdict.OLDER
        } else if (ofChannel.size >= MAX_PER_CHANNEL && !makeRoom(ofChannel)) {
            return ObjectVerdict.FULL
        }
        // Newest last: the removals forgotten first are the oldest ones.
        ofChannel.remove(record.ref)
        ofChannel[record.ref] = record
        return ObjectVerdict.STORED
    }

    /** The newest record held of [ref], a removal or not; `null` when this phone never held the object. */
    @Synchronized
    fun find(ref: ObjectRef): SharedRecord? = held[ref.channel]?.get(ref)

    /** The objects of [channel] still shown, by author then identifier. */
    @Synchronized
    fun shown(channel: Channel): List<SharedRecord> =
        held[channel]?.values?.filter { !it.deleted }?.sortedWith(BY_REF) ?: emptyList()

    /** Every object still shown on the phone of [self] in [state]: the ones of the channels it reads. */
    @Synchronized
    fun shown(
        state: HierarchyState,
        self: Long,
    ): List<SharedRecord> = state.channelsReadBy(self).flatMap(::shown)

    /**
     * What to seal again for someone who arrives in [channel] or comes back to it: every object still shown, and
     * the removals (a phone that was away must learn what was taken away meanwhile). By author then identifier.
     */
    @Synchronized
    fun resend(channel: Channel): List<SharedRecord> = held[channel]?.values?.sortedWith(BY_REF) ?: emptyList()

    /**
     * A fingerprint of what this phone holds for [channel]: two phones that hold the same records give the same
     * one, and then have nothing to send each other. It says nothing of the objects.
     */
    @Synchronized
    fun digest(channel: Channel): Long {
        var data = OpAuth.longBytes(ensembleId) + channel.bytes()
        for (record in resend(channel)) data += record.signature
        return OpAuth.fingerprint(DIGEST_TAG, data)
    }

    /**
     * Forgets the objects of the channels the phone of [self] no longer reads in [state]: it left the group, lost
     * its post, was put back to waiting. They are no longer shown there.
     */
    @Synchronized
    fun prune(
        state: HierarchyState,
        self: Long,
    ) {
        held.keys.retainAll(state.channelsReadBy(self).toSet())
    }

    /** Forgets everything: the phone left the ensemble, was put out of it, or the ensemble is dissolved. */
    @Synchronized
    fun clear() = held.clear()

    /** Forgets the oldest removal of [ofChannel]; `false` when it holds none. */
    private fun makeRoom(ofChannel: LinkedHashMap<ObjectRef, SharedRecord>): Boolean {
        val oldest = ofChannel.values.firstOrNull { it.deleted } ?: return false
        ofChannel.remove(oldest.ref)
        return true
    }

    companion object {
        /** The most objects kept per channel, removals included. */
        const val MAX_PER_CHANNEL = 1024
        private val DIGEST_TAG = "toolmap-hierarchy-objects-v3".toByteArray(Charsets.US_ASCII)

        private val BY_REF: Comparator<SharedRecord> =
            Comparator { a, b ->
                val byAuthor = java.lang.Long.compareUnsigned(a.author, b.author)
                if (byAuthor != 0) byAuthor else java.lang.Long.compareUnsigned(a.objectId, b.objectId)
            }

        /**
         * Which of two records of one object counts: the higher revision; at the same revision a removal, then the
         * one whose signature comes first. The same on every phone that saw both.
         */
        private val ORDER: Comparator<SharedRecord> =
            Comparator { a, b ->
                when {
                    a.revision != b.revision -> a.revision.compareTo(b.revision)
                    a.deleted != b.deleted -> if (a.deleted) 1 else -1
                    else -> -compareBytes(SharedRecords.encode(a), SharedRecords.encode(b))
                }
            }

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
    }
}
