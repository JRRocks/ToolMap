package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.GroupCrypto
import fr.toolmap.core.problem.catchExpected
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The members who read what is shared in one unit (the ensemble or a group) at one moment
 * ([HierarchyState.readersOf]), with the keys they have. A content key is made for exactly one such list and wrapped
 * to each of its members, to nobody else: whoever waits for a group is in no list, so no key ever reaches its phone
 * (`docs/specs/group-hierarchy.md` § 8).
 *
 * [tag] names the list: the unit, who is in it and with which agreement key. Any change of the list gives another
 * tag, so another key.
 */
class Readers internal constructor(
    val ensembleId: Long,
    val unit: Long,
    /** Ordered by identifier; never empty. */
    val members: List<Member>,
) {
    private val byId = members.associateBy { it.id }

    val tag: ByteArray by lazy {
        var data = OpAuth.longBytes(ensembleId) + OpAuth.longBytes(unit) + OpAuth.longBytes(members.size.toLong())
        for (member in members) data += OpAuth.longBytes(member.id) + member.keys.agreement
        OpAuth.sha256(READERS_TAG, data).copyOf(TAG_BYTES)
    }

    operator fun contains(member: Long): Boolean = member in byId

    fun member(id: Long): Member? = byId[id]

    override fun equals(other: Any?): Boolean =
        other is Readers && ensembleId == other.ensembleId && unit == other.unit && tag.contentEquals(other.tag)

    override fun hashCode(): Int = tag.contentHashCode()

    override fun toString(): String =
        "Readers(unit ${java.lang.Long.toUnsignedString(unit, 16)}, ${members.size} members)"

    companion object {
        const val TAG_BYTES = 16
        private val READERS_TAG = "toolmap-hierarchy-readers-v2".toByteArray(Charsets.US_ASCII)
    }
}

/** The readers of [unit] now; `null` when nobody reads it (no such unit, nobody in it, ensemble dissolved). */
fun HierarchyState.readers(unit: Long): Readers? =
    readersOf(unit).takeIf { it.isNotEmpty() }?.let { Readers(ensembleId, unit, it) }

/** The readers of every unit that has some: the ensemble first, then the groups by identifier. */
fun HierarchyState.allReaders(): List<Readers> =
    (listOf(HierarchyState.ROOT) + groups.keys.sortedWith(HierarchyWire.UNSIGNED)).mapNotNull(::readers)

/**
 * The readers of [unit] in the order they should hand out its content key when it has none: its leader, its deputy,
 * (for the ensemble) the creator and the administrators, then the others by identifier. Any reader may do it; the
 * order only avoids that all do it at once (the caller waits a little longer the further down it is).
 */
fun HierarchyState.keyIssuers(unit: Long): List<Long> {
    val readers = readersOf(unit).map { it.id }
    val first = ArrayList<Long>()
    leaderOf(unit)?.let(first::add)
    deputyOf(unit)?.let(first::add)
    if (unit == HierarchyState.ROOT) {
        creator?.let(first::add)
        first.addAll(admins.sortedWith(HierarchyWire.UNSIGNED))
    }
    return (first.filter { it in readers } + readers).distinct()
}

/**
 * A key that seals what is shared in [unit] for one list of readers ([readersTag]). [id] names it in what it seals:
 * a fingerprint of the key and of what it is for, so that a phone that unwraps another key than the one announced
 * knows it.
 */
class ContentKey internal constructor(
    val ensembleId: Long,
    val unit: Long,
    val readersTag: ByteArray,
    private val secret: ByteArray,
) {
    val id: Long = OpAuth.fingerprint(KEY_TAG, context() + secret)

    /** Whether this key was made for [readers]. */
    fun isFor(readers: Readers?): Boolean =
        readers != null && ensembleId == readers.ensembleId && unit == readers.unit &&
            readersTag.contentEquals(readers.tag)

    /** One sealing key per author: two authors never draw their random nonces under the same key. */
    internal fun sealingKey(author: Long): ByteArray =
        GroupCrypto.hkdfSha256(secret, ByteArray(0), SHARE_INFO + OpAuth.longBytes(author), Aead.KEY_BYTES)

    private fun context(): ByteArray = OpAuth.longBytes(ensembleId) + OpAuth.longBytes(unit) + readersTag

    companion object {
        private val KEY_TAG = "toolmap-hierarchy-key-v2".toByteArray(Charsets.US_ASCII)
        private val SHARE_INFO = "toolmap-hierarchy-share-key-v2".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * One content key wrapped to each of its readers, signed by the reader who made it ([issuer]). It travels in clear
 * (under the key of the invitation like the operations): only a reader can unwrap its own copy, and every phone,
 * reader or not, can check it and pass it on to a phone that was away.
 *
 * The key is wrapped with ECDH P-256 between a key pair made for this batch ([ephemeral] is its public half) and the
 * agreement key of each reader, HKDF-SHA256, then AES-256-GCM.
 */
class KeyBatch(
    val ensembleId: Long,
    val unit: Long,
    val readersTag: ByteArray,
    val keyId: Long,
    val issuer: Long,
    val ephemeral: ByteArray,
    /** Ordered by recipient. */
    val wraps: List<Wrap>,
    val signature: ByteArray,
) {
    class Wrap(
        val recipient: Long,
        val sealed: ByteArray,
    )

    /** What the issuer signs. */
    internal val body: ByteArray by lazy { HierarchyWire.keyBatchBody(this) }

    companion object {
        /** A key of 32 bytes and its authentication tag. */
        const val WRAP_BYTES = Aead.KEY_BYTES + Aead.TAG_BYTES
    }
}

/** What a phone makes of a key batch ([ContentKeys.check], [KeyRing.offer]). */
enum class KeyOffer {
    /** Checked, and its key is now held by this phone. */
    ACCEPTED,

    /** Checked, already held. */
    DUPLICATE,

    /** Checked, but this phone is not one of its readers: kept only to pass it on. */
    NOT_FOR_ME,

    /** For readers this phone does not know (yet): kept aside, tried again when operations arrive. */
    UNKNOWN_READERS,

    /** For another ensemble. */
    WRONG_ENSEMBLE,

    /** Its issuer is not one of its readers: nobody hands out the key of a unit it does not read. */
    ISSUER_NOT_A_READER,

    /** Not signed by its issuer. */
    FORGED,

    /** Not wrapped to exactly its readers: someone is missing or someone is added. */
    WRONG_RECIPIENTS,

    /** The copy of this phone does not unwrap, or is not the key announced. */
    DAMAGED,
}

/** How content keys are made, wrapped, checked and unwrapped. Pure: no network, no disk. */
object ContentKeys {
    private val KEYS_TAG = "toolmap-hierarchy-keys-v2".toByteArray(Charsets.US_ASCII)
    private val WRAP_INFO = "toolmap-hierarchy-wrap-v2".toByteArray(Charsets.US_ASCII)

    /** The key is used once: a fixed nonce is safe, and the same on every phone. */
    private val WRAP_NONCE = ByteArray(Aead.NONCE_BYTES)

    class Issued(
        val key: ContentKey,
        val batch: KeyBatch,
    )

    /** A new key for [readers], wrapped to each of them by the phone that holds [keys], itself one of the readers. */
    fun issue(
        readers: Readers,
        keys: PhoneKeys,
        random: SecureRandom = SecureRandom(),
    ): Issued {
        val issuer = keys.memberId
        require(issuer in readers) { "only a reader hands out the key of a unit" }
        val secret = ByteArray(Aead.KEY_BYTES).also(random::nextBytes)
        val key = ContentKey(readers.ensembleId, readers.unit, readers.tag, secret)
        val pair = P256.generate()
        val ephemeral = P256.encode(pair.public)
        val wraps =
            readers.members.map { member ->
                val shared = requireNotNull(P256.agree(pair.private, member.keys.agreement)) { "reader without a key" }
                val context = wrapContext(readers.ensembleId, readers.unit, readers.tag, key.id, ephemeral, member.id)
                KeyBatch.Wrap(member.id, Aead.seal(wrapKey(shared, context), WRAP_NONCE, context, secret))
            }
        val unsigned =
            KeyBatch(readers.ensembleId, readers.unit, readers.tag, key.id, issuer, ephemeral, wraps, ByteArray(0))
        return Issued(key, signed(unsigned, keys))
    }

    /** [batch] with the signature of the phone that holds [keys] over everything it says. */
    internal fun signed(
        batch: KeyBatch,
        keys: PhoneKeys,
    ): KeyBatch =
        KeyBatch(
            batch.ensembleId,
            batch.unit,
            batch.readersTag,
            batch.keyId,
            batch.issuer,
            batch.ephemeral,
            batch.wraps,
            keys.sign(KEYS_TAG + batch.body),
        )

    /**
     * Whether [batch] is a key for [readers], made by one of them and wrapped to all of them and nobody else:
     * [KeyOffer.ACCEPTED] when so. Every phone can tell, reader or not.
     */
    fun check(
        batch: KeyBatch,
        readers: Readers,
    ): KeyOffer {
        if (batch.ensembleId != readers.ensembleId) return KeyOffer.WRONG_ENSEMBLE
        if (batch.unit != readers.unit || !batch.readersTag.contentEquals(readers.tag)) return KeyOffer.UNKNOWN_READERS
        val issuer = readers.member(batch.issuer) ?: return KeyOffer.ISSUER_NOT_A_READER
        if (!P256.verify(issuer.keys.signing, KEYS_TAG + batch.body, batch.signature)) return KeyOffer.FORGED
        if (batch.wraps.map { it.recipient } != readers.members.map { it.id }) return KeyOffer.WRONG_RECIPIENTS
        return KeyOffer.ACCEPTED
    }

    /**
     * The key of [batch] for the phone that holds [keys]; `null` when its copy does not unwrap or is not the key
     * announced. To call once [check] accepted the batch for readers this phone is one of.
     */
    fun unwrap(
        batch: KeyBatch,
        keys: PhoneKeys,
    ): ContentKey? {
        val self = keys.memberId
        val wrap = batch.wraps.firstOrNull { it.recipient == self } ?: return null
        val shared = keys.agree(batch.ephemeral) ?: return null
        val context = wrapContext(batch.ensembleId, batch.unit, batch.readersTag, batch.keyId, batch.ephemeral, self)
        val secret = Aead.open(wrapKey(shared, context), WRAP_NONCE, context, wrap.sealed) ?: return null
        return ContentKey(batch.ensembleId, batch.unit, batch.readersTag, secret).takeIf { it.id == batch.keyId }
    }

    private fun wrapKey(
        shared: ByteArray,
        context: ByteArray,
    ): ByteArray = GroupCrypto.hkdfSha256(shared, ByteArray(0), WRAP_INFO + context, Aead.KEY_BYTES)

    /** Everything a wrapped copy is tied to: it opens for this reader of this batch and for nothing else. */
    private fun wrapContext(
        ensembleId: Long,
        unit: Long,
        readersTag: ByteArray,
        keyId: Long,
        ephemeral: ByteArray,
        recipient: Long,
    ): ByteArray =
        OpAuth.longBytes(ensembleId) + OpAuth.longBytes(unit) + readersTag + OpAuth.longBytes(keyId) + ephemeral +
            OpAuth.longBytes(recipient)
}

/**
 * The content keys one phone holds for one ensemble, and the batches it keeps to pass on. Knows nothing of the
 * network nor of the disk: the caller hands it the batches it receives ([offer]) and sends the ones it makes
 * ([issue]) or keeps for others ([batchesFor]).
 *
 * What it guarantees: a key is held only if it came in a batch made by a reader of the unit, for a list of readers
 * that existed at some point of the operations this phone holds, and wrapped to exactly that list.
 */
class KeyRing(
    val ensembleId: Long,
    private val keys: PhoneKeys,
) {
    val self: Long = keys.memberId

    /** Per unit, the keys held, oldest first. */
    private val held = HashMap<Long, LinkedHashMap<Long, ContentKey>>()

    /** The checked batches, oldest first: what a phone that was away is given. */
    private val checked = LinkedHashMap<BatchRef, KeyBatch>()

    /** Batches for readers this phone does not know yet. */
    private val pending = ArrayDeque<KeyBatch>()

    /**
     * Takes in a batch received from another phone. [replica] says which lists of readers existed: a batch for a
     * list it never saw waits ([KeyOffer.UNKNOWN_READERS]) until [retry].
     */
    @Synchronized
    fun offer(
        batch: KeyBatch,
        replica: HierarchyReplica,
    ): KeyOffer {
        if (batch.ensembleId != ensembleId || replica.ensembleId != ensembleId) return KeyOffer.WRONG_ENSEMBLE
        if (BatchRef(batch) in checked) return KeyOffer.DUPLICATE
        val readers = replica.readers(batch.unit, batch.readersTag)
        if (readers == null) {
            if (pending.none { BatchRef(it) == BatchRef(batch) }) {
                pending.addLast(batch)
                while (pending.size > MAX_PENDING) pending.removeFirst()
            }
            return KeyOffer.UNKNOWN_READERS
        }
        val verdict = ContentKeys.check(batch, readers)
        if (verdict != KeyOffer.ACCEPTED) return verdict
        if (self !in readers) {
            keep(batch)
            return KeyOffer.NOT_FOR_ME
        }
        val key = ContentKeys.unwrap(batch, keys) ?: return KeyOffer.DAMAGED
        keep(batch)
        hold(key)
        return KeyOffer.ACCEPTED
    }

    /** Tries again the batches that waited for operations; how many are now held or kept. */
    @Synchronized
    fun retry(replica: HierarchyReplica): Int {
        val waiting = pending.toList()
        pending.clear()
        return waiting.count {
            offer(it, replica).let { verdict ->
                verdict == KeyOffer.ACCEPTED ||
                    verdict == KeyOffer.NOT_FOR_ME
            }
        }
    }

    /**
     * A new key for the readers of [unit] in [state], held at once; the batch is to send to the others. `null` when
     * this phone does not read [unit]: it cannot hand out its key.
     */
    @Synchronized
    fun issue(
        state: HierarchyState,
        unit: Long,
        random: SecureRandom = SecureRandom(),
    ): KeyBatch? {
        val readers = state.readers(unit)?.takeIf { self in it } ?: return null
        val issued = ContentKeys.issue(readers, keys, random)
        keep(issued.batch)
        hold(issued.key)
        return issued.batch
    }

    /**
     * The key this phone seals with in [unit]: one made for exactly the readers [unit] has in [state]. When several
     * were handed out at once, the one with the smallest identifier: every phone that holds them picks the same.
     * `null` when it holds none: it waits for a batch, or hands out a key itself ([issue]).
     */
    @Synchronized
    fun sealingKey(
        state: HierarchyState,
        unit: Long,
    ): ContentKey? {
        val readers = state.readers(unit) ?: return null
        return held[unit]?.values?.filter { it.isFor(readers) }?.minWithOrNull {
            a,
            b,
            ->
            java.lang.Long.compareUnsigned(a.id, b.id)
        }
    }

    /** The key [keyId] of [unit], current or older; `null` when this phone does not hold it. */
    @Synchronized
    fun find(
        unit: Long,
        keyId: Long,
    ): ContentKey? = held[unit]?.get(keyId)

    /** The units this phone reads in [state] and has no key to seal with, nearest first. */
    @Synchronized
    fun missing(state: HierarchyState): List<Long> = state.unitsReadBy(self).filter { sealingKey(state, it) == null }

    /**
     * The batches to give a phone that catches up: for every unit of [state], the ones made for its readers of now.
     * Any phone can pass them on, reader or not; only the readers can open them.
     */
    @Synchronized
    fun batchesFor(state: HierarchyState): List<KeyBatch> {
        val now = state.allReaders()
        return checked.values.filter { batch ->
            now.any {
                it.unit == batch.unit &&
                    it.tag.contentEquals(batch.readersTag)
            }
        }
    }

    /** Forgets everything: the phone left the ensemble, was put out of it, or the ensemble is dissolved. */
    @Synchronized
    fun clear() {
        held.clear()
        checked.clear()
        pending.clear()
    }

    private fun keep(batch: KeyBatch) {
        checked[BatchRef(batch)] = batch
        val eldest = checked.keys.iterator()
        while (checked.size > MAX_BATCHES) {
            eldest.next()
            eldest.remove()
        }
    }

    private fun hold(key: ContentKey) {
        val ofUnit = held.getOrPut(key.unit) { LinkedHashMap() }
        ofUnit[key.id] = key
        // Older keys still open what a phone that had not heard of a change sealed; not for ever.
        val eldest = ofUnit.keys.iterator()
        while (ofUnit.size > KEPT_KEYS) {
            eldest.next()
            eldest.remove()
        }
    }

    /** What names a batch: a key identifier alone is chosen by whoever makes the batch. */
    private data class BatchRef(
        val unit: Long,
        val keyId: Long,
        val readers: List<Byte>,
    ) {
        constructor(batch: KeyBatch) : this(batch.unit, batch.keyId, batch.readersTag.asList())
    }

    companion object {
        /** The most keys kept per unit: the one of now and older ones. */
        const val KEPT_KEYS = 16
        private const val MAX_BATCHES = 256
        private const val MAX_PENDING = 64
    }
}

/** AES-256-GCM, as the engine uses it: wrapped keys and shares. */
internal object Aead {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** The plain bytes, or `null` when [key] does not authenticate [sealed] and [aad]. */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray? =
        // Sealed with another key, or changed on the way: it comes from outside, refused and said to whoever debugs.
        catchExpected("hierarchy.crypto.open", GeneralSecurityException::class) {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        }.getOrNull()
}
