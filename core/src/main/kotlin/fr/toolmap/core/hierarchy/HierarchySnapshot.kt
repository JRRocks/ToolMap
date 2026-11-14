package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException

/**
 * What one phone keeps of one ensemble between two runs, as bytes (`docs/specs/group-hierarchy.md` § 9, « Stockage »):
 * the operations, the content keys it holds with the key batches it checked, the name cards, the records of the
 * objects still shown. Pure: no disk, no Keystore. The caller seals these bytes (they carry content keys and names)
 * with an authenticated cipher and writes them; it hands back only bytes that it authenticated, so nothing here
 * checks a signature again. Anything else read here throws a [WireException], and the target is left untouched.
 *
 * Nothing of this travels between phones: it is not part of [HierarchyWire], and its version is its own.
 */
object HierarchySnapshot {
    const val VERSION = 1
    private const val OPS = 1
    private const val KEYS = 2
    private const val NAMES = 3
    private const val OBJECTS = 4

    /** Far more than an ensemble can hold: a count above it is damage, not data. */
    private const val MAX_COUNT = 1 shl 20

    /** [ops] (what [HierarchyReplica.ops] gives), in the blocks of [HierarchyWire.encodeOpBlocks]. */
    fun encodeOps(ops: List<Op>): ByteArray {
        val blocks = HierarchyWire.encodeOpBlocks(ops)
        val w = header(OPS).u32(blocks.size.toLong())
        for (block in blocks) w.u32(block.size.toLong()).bytes(block)
        return w.toByteArray()
    }

    /** The operations of [encodeOps]; every one must be of [ensembleId]. */
    fun decodeOps(
        bytes: ByteArray,
        ensembleId: Long,
    ): List<Op> {
        val r = open(bytes, OPS)
        val ops = ArrayList<Op>()
        repeat(count(r.u32())) {
            ops += HierarchyWire.decodeOps(r.bytes(count(r.u32())))
        }
        end(r)
        if (ops.any { it.ensembleId != ensembleId }) throw WireException("operation of another ensemble")
        return ops
    }

    /**
     * The copy of [self] rebuilt from stored operations. They are handed over in the order every phone applies them
     * ([Op.ORDER]): in the order of [HierarchyReplica.ops] (by author) almost every one would come « in the past »
     * and replay all the others. Same state, same history either way: the state is a function of the operations held.
     */
    fun restoreReplica(
        ensembleId: Long,
        self: Long,
        ops: List<Op>,
        keys: PhoneKeys?,
    ): HierarchyReplica = HierarchyReplica.restore(ensembleId, self, ops.sortedWith(Op.ORDER), keys)

    /** The keys [ring] holds, channel by channel and oldest first, then its checked and its waiting batches. */
    fun encodeKeys(ring: KeyRing): ByteArray =
        synchronized(ring) {
            val w = header(KEYS).u64(ring.ensembleId)
            val held = ring.held.values.flatMap { it.values }
            w.u32(held.size.toLong())
            for (key in held) channel(w, key.channel).bytes(key.readersTag).bytes(key.secret)
            batches(w, ring.checked.values.toList())
            batches(w, ring.pending.toList())
            w.toByteArray()
        }

    /** Puts back into [ring] what [encodeKeys] gave; all of it or, on a [WireException], nothing. */
    fun restoreKeys(
        ring: KeyRing,
        bytes: ByteArray,
    ) {
        val r = open(bytes, KEYS)
        if (r.u64() != ring.ensembleId) throw WireException("keys of another ensemble")
        val held =
            List(count(r.u32())) {
                val channel = readChannel(r)
                val tag = r.bytes(Readers.TAG_BYTES)
                ContentKey(ring.ensembleId, channel, tag, r.bytes(Aead.KEY_BYTES))
            }
        val checked = readBatches(r, ring.ensembleId)
        val pending = readBatches(r, ring.ensembleId)
        end(r)
        synchronized(ring) {
            held.forEach(ring::hold)
            checked.forEach(ring::keep)
            for (batch in pending) ring.pending.addLast(batch)
            while (ring.pending.size > KeyRing.MAX_PENDING) ring.pending.removeFirst()
        }
    }

    /** Every card [book] holds, the ones whose operation has not arrived yet included. */
    fun encodeNames(book: NameBook): ByteArray =
        synchronized(book) {
            val w = header(NAMES).u32(book.cards.size.toLong())
            for (card in book.cards.values) w.bytes(card.salt).str(card.name, Names.MAX_BYTES)
            w.toByteArray()
        }

    /** Puts back into [book] what [encodeNames] gave; all of it or, on a [WireException], nothing. */
    fun restoreNames(
        book: NameBook,
        bytes: ByteArray,
    ) {
        val r = open(bytes, NAMES)
        val cards =
            List(count(r.u32())) {
                val salt = r.bytes(NameCard.SALT_BYTES)
                NameCard(salt, r.str()).also { if (!it.valid) throw WireException("bad name card") }
            }
        end(r)
        cards.forEach(book::learn)
    }

    /** The newest record of every object [objects] holds, channel by channel, removals included. */
    fun encodeObjects(objects: SharedObjects): ByteArray =
        synchronized(objects) {
            val w = header(OBJECTS).u64(objects.ensembleId)
            val channels = objects.held.filterValues { it.isNotEmpty() }
            w.u32(channels.size.toLong())
            for ((channel, records) in channels) {
                channel(w, channel).u32(records.size.toLong())
                for (record in records.values) {
                    val payload = SharedRecords.encode(record)
                    w.u8(record.kind.code).u32(payload.size.toLong()).bytes(payload)
                }
            }
            w.toByteArray()
        }

    /** Puts back into [objects] what [encodeObjects] gave; all of it or, on a [WireException], nothing. */
    fun restoreObjects(
        objects: SharedObjects,
        bytes: ByteArray,
    ) {
        val r = open(bytes, OBJECTS)
        if (r.u64() != objects.ensembleId) throw WireException("objects of another ensemble")
        val read =
            List(count(r.u32())) {
                val channel = readChannel(r)
                channel to
                    List(count(r.u32())) {
                        val kind = ShareKind.of(r.u8())?.takeIf { it.standing } ?: throw WireException("not an object")
                        SharedRecords.decode(kind, channel, r.bytes(count(r.u32())))
                    }
            }
        end(r)
        synchronized(objects) {
            for ((channel, records) in read) {
                val ofChannel = objects.held.getOrPut(channel) { LinkedHashMap() }
                for (record in records) {
                    ofChannel.remove(record.ref)
                    ofChannel[record.ref] = record
                }
            }
        }
    }

    /**
     * Forgets what ties this phone to the channels it no longer reads in [state] (it left a group, lost a post, was
     * put back to waiting): their content keys ([KeyRing.prune]), their objects ([SharedObjects.prune]), and the
     * batches of those channels that carry a copy for this phone, which would give their keys back. A batch of such a
     * channel is never handed out any more: it is not made for the readers of now, since this phone is no longer one
     * of them ([KeyRing.batchesFor]). Names are not tied to a channel: they stay ([NameBook.prune] is the caller's).
     */
    fun forgetUnread(
        state: HierarchyState,
        ring: KeyRing,
        objects: SharedObjects,
    ) {
        val read = state.channelsReadBy(ring.self).toSet()
        synchronized(ring) {
            ring.prune(state)
            ring.checked.values.removeIf { batch ->
                batch.channel !in read && batch.wraps.any { it.recipient == ring.self }
            }
        }
        objects.prune(state, ring.self)
    }

    private fun batches(
        w: ByteWriter,
        batches: List<KeyBatch>,
    ) {
        w.u32(batches.size.toLong())
        for (batch in batches) {
            val bytes = HierarchyWire.encodeKeyBatch(batch)
            w.u32(bytes.size.toLong()).bytes(bytes)
        }
    }

    private fun readBatches(
        r: ByteReader,
        ensembleId: Long,
    ): List<KeyBatch> =
        List(count(r.u32())) {
            HierarchyWire.decodeKeyBatch(r.bytes(count(r.u32()))).also {
                if (it.ensembleId != ensembleId) throw WireException("key batch of another ensemble")
            }
        }

    private fun channel(
        w: ByteWriter,
        channel: Channel,
    ): ByteWriter = w.bytes(channel.bytes())

    private fun readChannel(r: ByteReader): Channel {
        val lane = Lane.of(r.u8()) ?: throw WireException("unknown lane")
        return Channel(lane, r.u64())
    }

    private fun count(value: Long): Int {
        if (value < 0 || value > MAX_COUNT) throw WireException("count out of bounds")
        return value.toInt()
    }

    private fun header(part: Int) = ByteWriter().u8(VERSION).u8(part)

    private fun open(
        bytes: ByteArray,
        part: Int,
    ): ByteReader {
        val r = ByteReader(bytes)
        if (r.u8() != VERSION) throw WireException("unknown version of a stored ensemble")
        if (r.u8() != part) throw WireException("not this part of a stored ensemble")
        return r
    }

    private fun end(r: ByteReader) {
        if (r.remaining != 0) throw WireException("trailing data")
    }
}
