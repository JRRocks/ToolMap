package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom

/**
 * Something a member shares in a channel: a position, a sketch, a waypoint, a message, an alert, names. [payload] is
 * the caller's (for sketches and waypoints, a [SharedRecord]; for names, [NameBook.encode]).
 */
class Share(
    val kind: ShareKind,
    val channel: Channel,
    val author: Long,
    val payload: ByteArray,
)

/** Why a share was not sealed, or not opened. */
enum class ShareRefusal {
    /** The author may not share this kind of thing in this channel ([HierarchyState.mayShare]). */
    NOT_ALLOWED,

    /** This phone does not hold the key: it waits for it, is not a reader of the channel, or waits for a group. */
    NO_KEY,

    /** Not a share of this format. */
    MALFORMED,

    /** For another ensemble. */
    WRONG_ENSEMBLE,

    /** This phone does not read the channel (any more). */
    NOT_A_READER,

    /** The key does not open it: changed on the way, or not sealed by a holder of the key. */
    UNDECIPHERABLE,

    /** A kind that must carry the signature of its author, and does not carry a valid one. */
    FORGED,

    /** A kind that is not signed, sealed with the key of readers who are no longer the readers of the channel. */
    STALE_KEY,

    /** More than [ShareSeal.MAX_PAYLOAD] bytes. */
    TOO_LARGE,
}

sealed interface SealResult {
    /** [frame] is to send to the readers of the channel. */
    class Sealed(
        val frame: ByteArray,
    ) : SealResult

    class Refused(
        val reason: ShareRefusal,
    ) : SealResult
}

sealed interface OpenResult {
    class Opened(
        val share: Share,
    ) : OpenResult

    class Refused(
        val reason: ShareRefusal,
    ) : OpenResult
}

/**
 * Sealing and opening what is shared (`docs/specs/group-hierarchy.md` § 8.5). A share is sealed with the content key
 * of its channel for the readers of now (AES-256-GCM); a phone that does not hold that key cannot open it, and a
 * phone that waits for a group holds none but the one of its own waiting lane. The kinds the rules name
 * ([HierarchyRules.signedShares]: all of them, positions too) also carry the signature of their author, inside the
 * sealed part: a reader cannot share in the name of another.
 *
 * Frame: `version | kind | ensemble (8) | lane (1) | scope (8) | key (8) | kind of share (1) | author (8) |
 * nonce (12) | sealed`, where `sealed` is the payload, then the signature (64) for a signed kind, then the tag (16).
 */
object ShareSeal {
    /** A share fits one frame of the mesh. */
    const val MAX_PAYLOAD = 12 * 1024
    private val SHARE_TAG = "toolmap-hierarchy-share-v3".toByteArray(Charsets.US_ASCII)

    /**
     * Seals [payload] as a share of [kind] in [channel] by the phone that holds [keys]. Checked first: what this
     * phone may not share ([HierarchyState.mayShare]) is not sealed at all.
     */
    fun seal(
        state: HierarchyState,
        ring: KeyRing,
        keys: PhoneKeys,
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): SealResult {
        val author = keys.memberId
        if (state.ensembleId != ring.ensembleId) return SealResult.Refused(ShareRefusal.WRONG_ENSEMBLE)
        if (!state.mayShare(author, kind, channel)) return SealResult.Refused(ShareRefusal.NOT_ALLOWED)
        if (payload.size > MAX_PAYLOAD) return SealResult.Refused(ShareRefusal.TOO_LARGE)
        val key = ring.sealingKey(state, channel) ?: return SealResult.Refused(ShareRefusal.NO_KEY)
        val header = HierarchyWire.shareHeader(state.ensembleId, channel, key.id, kind, author)
        val nonce = ByteArray(Aead.NONCE_BYTES).also(random::nextBytes)
        val plain =
            if (kind in state.rules.signedShares) payload + keys.sign(SHARE_TAG + header + nonce + payload) else payload
        return SealResult.Sealed(header + nonce + Aead.seal(key.sealingKey(author), nonce, header, plain))
    }

    /** Seals [payload] in the own channel of [unit]. */
    fun seal(
        state: HierarchyState,
        ring: KeyRing,
        keys: PhoneKeys,
        kind: ShareKind,
        unit: Long,
        payload: ByteArray,
    ): SealResult = seal(state, ring, keys, kind, Channel.unit(unit), payload)

    /**
     * Opens [frame] on the phone of [ring]. Refused unless: this phone holds the key and reads the channel, the author
     * may share this kind there in [state], a signed kind carries the author's signature, and an unsigned kind is
     * sealed with the key of the readers of now (an older key is also held by whoever was put out since).
     */
    fun open(
        state: HierarchyState,
        ring: KeyRing,
        frame: ByteArray,
    ): OpenResult {
        fun no(reason: ShareRefusal) = OpenResult.Refused(reason)
        val header =
            try {
                HierarchyWire.readShareHeader(frame.copyOf(minOf(frame.size, HierarchyWire.SHARE_HEADER_BYTES)))
            } catch (e: WireException) {
                Problems.expected("hierarchy.share.header", e)
                return no(ShareRefusal.MALFORMED)
            }
        val sealedAt = HierarchyWire.SHARE_HEADER_BYTES + Aead.NONCE_BYTES
        if (frame.size < sealedAt + Aead.TAG_BYTES) return no(ShareRefusal.MALFORMED)
        if (header.ensembleId != state.ensembleId ||
            ring.ensembleId != state.ensembleId
        ) {
            return no(ShareRefusal.WRONG_ENSEMBLE)
        }
        val key = ring.find(header.channel, header.keyId) ?: return no(ShareRefusal.NO_KEY)
        if (state.readersOf(header.channel).none { it.id == ring.self }) return no(ShareRefusal.NOT_A_READER)
        val clear = frame.copyOf(HierarchyWire.SHARE_HEADER_BYTES)
        val nonce = frame.copyOfRange(HierarchyWire.SHARE_HEADER_BYTES, sealedAt)
        val plain =
            Aead.open(key.sealingKey(header.author), nonce, clear, frame.copyOfRange(sealedAt, frame.size))
                ?: return no(ShareRefusal.UNDECIPHERABLE)
        // The rights are the ones of now: who was put out, or lost its post, no longer shares, even with a key it kept.
        if (!state.mayShare(header.author, header.kind, header.channel)) return no(ShareRefusal.NOT_ALLOWED)
        if (header.kind !in state.rules.signedShares) {
            if (!key.isFor(state.readers(header.channel))) return no(ShareRefusal.STALE_KEY)
            return OpenResult.Opened(Share(header.kind, header.channel, header.author, plain))
        }
        if (plain.size < P256.SIGNATURE_BYTES) return no(ShareRefusal.FORGED)
        val payload = plain.copyOf(plain.size - P256.SIGNATURE_BYTES)
        val signature = plain.copyOfRange(payload.size, plain.size)
        val signing = state.members.getValue(header.author).keys.signing
        if (!P256.verify(signing, SHARE_TAG + clear + nonce + payload, signature)) return no(ShareRefusal.FORGED)
        return OpenResult.Opened(Share(header.kind, header.channel, header.author, payload))
    }
}
