package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.Base45
import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.GroupCrypto
import fr.toolmap.core.p2p.MsgType
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom
import java.util.Base64

/*
 * The transport of the « Opération » mode (P39.7, ADR-129, `docs/specs/group-hierarchy.md` § 17): the invitation, the
 * envelope every frame travels in, and what the engine did not define yet (the request to come in, the renewal of the
 * invitation key, the payloads of positions, messages and alerts). Pure: no network, no disk, no clock of its own.
 */

/**
 * An invitation to an operation: the only thing a new phone needs. [key] is the invitation key of [epoch]: it seals
 * the envelopes ([OperationFrames]), opens no share and gives no name. [ensembleCard] opens the name of the operation,
 * and that name only (T14): it is read by whoever holds the invitation. [room] names the room of the coordination
 * server ([server]), chosen at random by the creator and the same for the whole life of the operation.
 */
class OperationInvite(
    val createdAtSec: Long,
    val ensembleId: Long,
    val room: ByteArray,
    val epoch: Int,
    val key: ByteArray,
    val ensembleCard: NameCard,
    val server: RelayConfig,
) {
    init {
        require(ensembleId != 0L && room.size == ROOM_BYTES && key.size == KEY_BYTES && epoch >= 1)
        require(ensembleCard.valid) { "bad card" }
    }

    companion object {
        const val ROOM_BYTES = 16
        const val KEY_BYTES = 32
    }
}

/**
 * The text of an [OperationInvite]: bytes → Base45 for a QR code, or `toolmap://operation?d=…` (URL-safe Base64) for a
 * message. The bytes start like every ToolMap code (`MAGIC`) with a version of their own (4): older versions of the
 * app read neither a server invitation of « Entre amis » (version 2) nor a pairing code (3) in it, and refuse it.
 */
object OperationInviteCodec {
    const val LINK_PREFIX = "toolmap://operation?d="
    const val VERSION = 4
    const val MAX_AGE_SEC = 7L * 24 * 3600

    fun encodeBytes(i: OperationInvite): ByteArray {
        val w =
            ByteWriter()
                .u16(PairingCodec.MAGIC)
                .u8(VERSION)
                .u32(i.createdAtSec)
                .u64(i.ensembleId)
                .bytes(i.room)
                .u32(i.epoch.toLong())
                .bytes(i.key)
        OperationWire.writeCard(w, i.ensembleCard)
        return w
            .str(i.server.base, RelayConfig.MAX_URL_BYTES)
            .str(i.server.apiKey, RelayConfig.MAX_KEY_BYTES)
            .toByteArray()
    }

    fun decodeBytes(data: ByteArray): OperationInvite {
        val r = ByteReader(data)
        if (r.u16() != PairingCodec.MAGIC) throw WireException("not a ToolMap code")
        if (r.u8() != VERSION) throw WireException("not an operation invitation")
        val created = r.u32()
        val ensemble = r.u64()
        val room = r.bytes(OperationInvite.ROOM_BYTES)
        val epoch = r.u32()
        val key = r.bytes(OperationInvite.KEY_BYTES)
        val card = OperationWire.readCard(r)
        val server = RelayConfig(r.str(), r.str())
        if (r.remaining != 0) throw WireException("trailing data")
        if (ensemble == 0L || epoch !in 1..Int.MAX_VALUE) throw WireException("bad invitation")
        if (!server.isUsable) throw WireException("bad server in invitation")
        return OperationInvite(created, ensemble, room, epoch.toInt(), key, card, server)
    }

    fun toQrText(i: OperationInvite): String = Base45.encode(encodeBytes(i))

    fun toLink(i: OperationInvite): String =
        LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encodeBytes(i))

    /** The invitation [text] carries (a link or the text of a QR code); `null` when it is not one, said to whoever debugs. */
    fun parseOrNull(text: String): OperationInvite? {
        val t = text.trim()
        // Never in the context: the text holds an invitation key.
        return try {
            val bytes =
                if (t.startsWith(LINK_PREFIX, ignoreCase = true)) {
                    Base64.getUrlDecoder().decode(t.substring(LINK_PREFIX.length).substringBefore('&'))
                } else if (t.startsWith("toolmap://", ignoreCase = true)) {
                    return null
                } else {
                    Base45.decode(t)
                }
            if (bytes.size < 3 || (bytes[2].toInt() and 0xFF) != VERSION) return null
            decodeBytes(bytes)
        } catch (e: IllegalArgumentException) {
            Problems.expected("operation.invite.decode", e, "chars" to t.length)
            null
        } catch (e: WireException) {
            Problems.expected("operation.invite.decode", e, "chars" to t.length)
            null
        }
    }

    fun staleness(
        i: OperationInvite,
        nowSec: Long,
    ): PairingCodec.Staleness? =
        when {
            nowSec - i.createdAtSec > MAX_AGE_SEC -> PairingCodec.Staleness.EXPIRED
            i.createdAtSec - nowSec > MAX_AGE_SEC -> PairingCodec.Staleness.FROM_THE_FUTURE
            else -> null
        }
}

/** What the envelope of a frame says it carries. */
enum class FrameGenre(
    val code: Int,
) {
    /** Operations ([HierarchyWire.encodeOps]). */
    OPS(1),

    /** What this phone holds ([OperationSummary]): the other answers with what is missing. */
    SUMMARY(2),

    /** A content key wrapped to its readers ([HierarchyWire.encodeKeyBatch]). */
    KEYS(3),

    /** Something shared, sealed with a content key ([ShareSeal]). */
    SHARE(4),

    /** A phone that holds the invitation asks to come in ([JoinRequest]). */
    JOIN(5),

    /** The invitation key of an epoch, wrapped to members ([InviteGrant]). */
    INVITE_KEY(6),

    /**
     * The proof that the operation is dissolved ([DissolutionNotice]), from a phone that keeps its trace to a phone
     * still in it (P39.9, ADR-131). Versions before it do not know this genre and drop the frame.
     */
    DISSOLUTION(7),
    ;

    companion object {
        fun of(code: Int): FrameGenre? = entries.firstOrNull { it.code == code }
    }
}

/** An envelope opened: who sent it, under which epoch of the invitation key, and what it carries. */
class OpenedFrame(
    val genre: FrameGenre,
    val epoch: Int,
    val sender: Long,
    val body: ByteArray,
)

/**
 * The envelope of every frame of an operation: `marker (1) | genre (1) | epoch (4) | sender (8) | nonce (12) |
 * AES-256-GCM(body)`, sealed with a key derived from the invitation key of `epoch` and the operation
 * (HKDF-SHA256), the clear part authenticated. Who does not hold the invitation key of that epoch neither reads nor
 * writes a frame: the server only sees ciphertext and who writes.
 *
 * The marker is [MARKER], or [POSITION_MARKER] for a position: the coordination server of « Entre amis » keeps in a
 * member's position slot the last frame whose first byte is the one of a position there ([MsgType.POS]), and pushes
 * every other frame as a control frame. Choosing the first byte this way lets the operations use that server as it
 * is, without a line of it changed (ADR-129).
 */
object OperationFrames {
    const val MARKER = 0xC7
    val POSITION_MARKER: Int = MsgType.POS.code
    private const val HEADER_BYTES = 1 + 1 + 4 + 8

    /** Envelope bytes around a body. */
    const val OVERHEAD = HEADER_BYTES + Aead.NONCE_BYTES + Aead.TAG_BYTES

    /** A control frame of the coordination server holds 6 000 characters of Base64: 4 500 bytes, kept under. */
    const val MAX_FRAME = 4096

    /** The position slot of the server holds 400 characters of Base64: 300 bytes. */
    const val MAX_POSITION_FRAME = 300
    const val MAX_BODY = MAX_FRAME - OVERHEAD
    private val INFO = "toolmap-operation-frame-v1".toByteArray(Charsets.US_ASCII)

    /** The key that seals the envelopes of [epoch], from its invitation key. */
    fun frameKey(
        inviteKey: ByteArray,
        ensembleId: Long,
        epoch: Int,
    ): ByteArray =
        GroupCrypto.hkdfSha256(
            inviteKey,
            ByteArray(0),
            INFO + OpAuth.longBytes(ensembleId) + OpAuth.longBytes(epoch.toLong()),
            Aead.KEY_BYTES,
        )

    fun seal(
        frameKey: ByteArray,
        ensembleId: Long,
        epoch: Int,
        genre: FrameGenre,
        sender: Long,
        body: ByteArray,
        position: Boolean = false,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(!position || genre == FrameGenre.SHARE) { "only a share is a position" }
        require(body.size <= MAX_BODY) { "frame too large" }
        val header =
            ByteWriter()
                .u8(if (position) POSITION_MARKER else MARKER)
                .u8(genre.code)
                .u32(epoch.toLong())
                .u64(sender)
                .toByteArray()
        val nonce = ByteArray(Aead.NONCE_BYTES).also(random::nextBytes)
        return header + nonce + Aead.seal(frameKey, nonce, aad(header, ensembleId), body)
    }

    /** The epoch [frame] says it is sealed for; `null` when it is not an envelope of this format. */
    fun epochOf(frame: ByteArray): Int? {
        if (frame.size < OVERHEAD || frame.size > MAX_FRAME) return null
        val marker = frame[0].toInt() and 0xFF
        if (marker != MARKER && marker != POSITION_MARKER) return null
        if (FrameGenre.of(frame[1].toInt() and 0xFF) == null) return null
        val epoch = ByteReader(frame.copyOfRange(2, 6)).u32()
        return if (epoch in 1..Int.MAX_VALUE) epoch.toInt() else null
    }

    /**
     * The envelope opened with [frameKey]; `null` when it is not one, or when the key does not open it (another epoch,
     * another operation, changed on the way): said to whoever debugs by [Aead.open].
     */
    fun open(
        frameKey: ByteArray,
        ensembleId: Long,
        frame: ByteArray,
    ): OpenedFrame? {
        val epoch = epochOf(frame) ?: return null
        val genre = FrameGenre.of(frame[1].toInt() and 0xFF) ?: return null
        val marker = frame[0].toInt() and 0xFF
        if (marker == POSITION_MARKER && genre != FrameGenre.SHARE) return null
        val header = frame.copyOf(HEADER_BYTES)
        val sender = ByteReader(frame.copyOfRange(6, HEADER_BYTES)).u64()
        if (sender == 0L) return null
        val nonce = frame.copyOfRange(HEADER_BYTES, HEADER_BYTES + Aead.NONCE_BYTES)
        val body =
            Aead.open(
                frameKey,
                nonce,
                aad(header, ensembleId),
                frame.copyOfRange(HEADER_BYTES + Aead.NONCE_BYTES, frame.size),
            )
                ?: return null
        return OpenedFrame(genre, epoch, sender, body)
    }

    private fun aad(
        header: ByteArray,
        ensembleId: Long,
    ) = header + OpAuth.longBytes(ensembleId)
}

/**
 * What a phone tells the others it holds: its [summary] of the operations, a fingerprint of the key batches it holds
 * for the readers of now ([keysDigest]), the fingerprint of the objects of each channel it reads ([objects],
 * [SharedObjects.digest]) and how many names it lacks ([namesMissing]). The fingerprints say nothing of what they
 * stand for: they travel under the invitation key only.
 */
class OperationSummary(
    val summary: Summary,
    val keysDigest: Long,
    val namesMissing: Int,
    val objects: Map<Channel, Long>,
) {
    fun encode(): ByteArray {
        val ops = HierarchyWire.encodeSummary(summary)
        val w = ByteWriter().u16(ops.size).bytes(ops).u64(keysDigest).u16(namesMissing.coerceIn(0, 0xFFFF))
        w.u16(objects.size)
        for ((channel, digest) in objects) OperationWire.writeChannel(w, channel).u64(digest)
        return w.toByteArray()
    }

    companion object {
        /** The most channels a summary lists: more than a phone reads. */
        const val MAX_CHANNELS = 64

        fun decode(bytes: ByteArray): OperationSummary {
            val r = ByteReader(bytes)
            val summary = HierarchyWire.decodeSummary(r.bytes(r.u16()))
            val keys = r.u64()
            val names = r.u16()
            val n = r.u16()
            if (n > MAX_CHANNELS) throw WireException("too many channels")
            val objects = LinkedHashMap<Channel, Long>()
            repeat(n) {
                val channel = OperationWire.readChannel(r)
                if (objects.put(channel, r.u64()) != null) throw WireException("channel twice")
            }
            if (r.remaining != 0) throw WireException("trailing data")
            return OperationSummary(summary, keys, names, objects)
        }
    }
}

/**
 * A phone that holds the invitation asks to come in: its two public keys and the hidden name it chose, signed by its
 * signing key (its member identifier is the fingerprint of that key). The name itself is not in clear, since the
 * request travels under the invitation key, which every waiting phone holds; its card is wrapped to each of
 * [JoinRequest.Wrap.recipient] (the creator and the administrators the phone knows of), who may let it in: they read
 * who asks before they decide (the user's answer Q11), nobody else does.
 */
class JoinRequest(
    val ensembleId: Long,
    val keys: MemberKeys,
    val name: HiddenName,
    val timeSec: Long,
    val ephemeral: ByteArray,
    val wraps: List<Wrap>,
    val signature: ByteArray,
) {
    class Wrap(
        val recipient: Long,
        val sealed: ByteArray,
    )

    val member: Long get() = OpAuth.memberId(keys.signing)

    internal fun body(): ByteArray {
        val w = ByteWriter().u64(ensembleId).bytes(keys.signing).bytes(keys.agreement).bytes(name.commitment)
        w.u32(timeSec).bytes(ephemeral).u8(wraps.size)
        for (wrap in wraps) w.u64(wrap.recipient).u8(wrap.sealed.size).bytes(wrap.sealed)
        return w.toByteArray()
    }

    fun encode(): ByteArray = body() + signature
}

/** How join requests are made, read, checked and opened. */
object JoinRequests {
    private val TAG = "toolmap-operation-join-v1".toByteArray(Charsets.US_ASCII)
    private val WRAP_INFO = "toolmap-operation-join-wrap-v1".toByteArray(Charsets.US_ASCII)
    private val NONCE = ByteArray(Aead.NONCE_BYTES)

    /** The most recipients of the card: the creator and administrators an operation can have, and some margin. */
    const val MAX_WRAPS = 32

    /** A request of the phone that holds [keys] to come into [ensembleId] as [card], its card wrapped to [recipients]. */
    fun make(
        keys: PhoneKeys,
        ensembleId: Long,
        card: NameCard,
        recipients: List<Member>,
        timeSec: Long,
    ): JoinRequest {
        val pair = P256.generate()
        val ephemeral = P256.encode(pair.public)
        val self = keys.memberId
        val plain = OperationWire.writeCard(ByteWriter(), card).toByteArray()
        val wraps =
            recipients.take(MAX_WRAPS).mapNotNull { r ->
                val shared = P256.agree(pair.private, r.keys.agreement) ?: return@mapNotNull null
                val context = context(ensembleId, self, r.id, ephemeral)
                JoinRequest.Wrap(r.id, Aead.seal(wrapKey(shared, context), NONCE, context, plain))
            }
        val unsigned = JoinRequest(ensembleId, keys.public, card.hidden, timeSec, ephemeral, wraps, ByteArray(0))
        return JoinRequest(
            ensembleId,
            keys.public,
            card.hidden,
            timeSec,
            ephemeral,
            wraps,
            keys.sign(TAG + unsigned.body()),
        )
    }

    fun decode(bytes: ByteArray): JoinRequest {
        val r = ByteReader(bytes)
        val ensemble = r.u64()
        val keys = MemberKeys(r.bytes(P256.POINT_BYTES), r.bytes(P256.POINT_BYTES))
        val name = HiddenName(r.bytes(HiddenName.BYTES))
        val time = r.u32()
        val ephemeral = r.bytes(P256.POINT_BYTES)
        val n = r.u8()
        if (n > MAX_WRAPS) throw WireException("too many recipients")
        val wraps = List(n) { JoinRequest.Wrap(r.u64(), r.bytes(r.u8())) }
        val signature = r.bytes(P256.SIGNATURE_BYTES)
        if (r.remaining != 0) throw WireException("trailing data")
        if (!keys.valid || !P256.isPoint(ephemeral)) throw WireException("bad keys in a join request")
        return JoinRequest(ensemble, keys, name, time, ephemeral, wraps, signature)
    }

    /** Whether [request] is signed by the key it shows. */
    fun verify(request: JoinRequest): Boolean =
        P256.verify(request.keys.signing, TAG + request.body(), request.signature)

    /** The card of the name [request] asks to come in with, for the phone that holds [keys]; `null` if not for it. */
    fun openCard(
        request: JoinRequest,
        keys: PhoneKeys,
    ): NameCard? {
        val self = keys.memberId
        val wrap = request.wraps.firstOrNull { it.recipient == self } ?: return null
        val shared = keys.agree(request.ephemeral) ?: return null
        val context = context(request.ensembleId, request.member, self, request.ephemeral)
        val plain = Aead.open(wrapKey(shared, context), NONCE, context, wrap.sealed) ?: return null
        val card =
            try {
                OperationWire.readCard(ByteReader(plain))
            } catch (e: WireException) {
                Problems.expected("operation.join.card", e)
                return null
            }
        return card.takeIf { it.valid && it.hidden == request.name }
    }

    private fun context(
        ensembleId: Long,
        member: Long,
        recipient: Long,
        ephemeral: ByteArray,
    ) = OpAuth.longBytes(ensembleId) + OpAuth.longBytes(member) + OpAuth.longBytes(recipient) + ephemeral

    private fun wrapKey(
        shared: ByteArray,
        context: ByteArray,
    ) = GroupCrypto.hkdfSha256(shared, ByteArray(0), WRAP_INFO + context, Aead.KEY_BYTES)
}

/**
 * The invitation key of an [epoch], as its announcer made it: the creator or an administrator, the ones who may let
 * someone in. The announcement signs the identifier of the key; any member that holds the key can then wrap it to
 * other members ([InviteGrant]), and every phone checks that the key it unwraps is the one announced.
 */
class InviteAnnouncement(
    val ensembleId: Long,
    val epoch: Int,
    val keyId: Long,
    val announcer: Long,
    val signature: ByteArray,
) {
    internal fun body(): ByteArray =
        ByteWriter().u64(ensembleId).u32(epoch.toLong()).u64(keyId).u64(announcer).toByteArray()
}

/** An invitation key wrapped by [granter] to [wraps] (members only), with the announcement it is for. */
class InviteGrant(
    val announcement: InviteAnnouncement,
    val granter: Long,
    val ephemeral: ByteArray,
    val wraps: List<KeyBatch.Wrap>,
    val signature: ByteArray,
) {
    internal fun body(): ByteArray {
        val a = announcement
        val w = ByteWriter().bytes(a.body()).bytes(a.signature).u64(granter).bytes(ephemeral).u16(wraps.size)
        for (wrap in wraps) w.u64(wrap.recipient).bytes(wrap.sealed)
        return w.toByteArray()
    }

    fun encode(): ByteArray = body() + signature
}

/** What a phone makes of an [InviteGrant]. */
enum class GrantVerdict {
    /** Checked, and its key opened for this phone. */
    ACCEPTED,

    /** Checked; this phone is not one of its recipients. */
    NOT_FOR_ME,

    /** For another operation. */
    WRONG_ENSEMBLE,

    /** Its announcer may not let anyone in, or its granter or one of its recipients is not a member. */
    NOT_ALLOWED,

    /** A signature that is not the announcer's or the granter's. */
    FORGED,

    /** The copy of this phone does not open, or is not the key announced. */
    DAMAGED,
}

/** How invitation keys are announced, wrapped, checked and opened. */
object InviteKeys {
    private val ANNOUNCE_TAG = "toolmap-operation-invite-announce-v1".toByteArray(Charsets.US_ASCII)
    private val GRANT_TAG = "toolmap-operation-invite-grant-v1".toByteArray(Charsets.US_ASCII)
    private val KEY_TAG = "toolmap-operation-invite-key-v1".toByteArray(Charsets.US_ASCII)
    private val WRAP_INFO = "toolmap-operation-invite-wrap-v1".toByteArray(Charsets.US_ASCII)
    private val NONCE = ByteArray(Aead.NONCE_BYTES)
    const val MAX_WRAPS = HierarchyLimits.FORMAT_MAX_MEMBERS

    fun keyId(
        ensembleId: Long,
        epoch: Int,
        key: ByteArray,
    ): Long = OpAuth.fingerprint(KEY_TAG, OpAuth.longBytes(ensembleId) + OpAuth.longBytes(epoch.toLong()) + key)

    fun announce(
        keys: PhoneKeys,
        ensembleId: Long,
        epoch: Int,
        key: ByteArray,
    ): InviteAnnouncement {
        val unsigned = InviteAnnouncement(ensembleId, epoch, keyId(ensembleId, epoch, key), keys.memberId, ByteArray(0))
        return InviteAnnouncement(
            ensembleId,
            epoch,
            unsigned.keyId,
            unsigned.announcer,
            keys.sign(ANNOUNCE_TAG + unsigned.body()),
        )
    }

    /** [key] of [announcement] wrapped by the phone that holds [keys] to [recipients]. */
    fun grant(
        keys: PhoneKeys,
        announcement: InviteAnnouncement,
        key: ByteArray,
        recipients: List<Member>,
    ): InviteGrant {
        val pair = P256.generate()
        val ephemeral = P256.encode(pair.public)
        val wraps =
            recipients.sortedWith {
                a,
                b,
                ->
                java.lang.Long.compareUnsigned(a.id, b.id)
            }.take(MAX_WRAPS).mapNotNull { r ->
                val shared = P256.agree(pair.private, r.keys.agreement) ?: return@mapNotNull null
                val context = context(announcement, ephemeral, r.id)
                KeyBatch.Wrap(r.id, Aead.seal(wrapKey(shared, context), NONCE, context, key))
            }
        val unsigned = InviteGrant(announcement, keys.memberId, ephemeral, wraps, ByteArray(0))
        return InviteGrant(announcement, keys.memberId, ephemeral, wraps, keys.sign(GRANT_TAG + unsigned.body()))
    }

    fun decode(bytes: ByteArray): InviteGrant {
        val r = ByteReader(bytes)
        val ensemble = r.u64()
        val epoch = r.u32()
        val keyId = r.u64()
        val announcer = r.u64()
        val announceSignature = r.bytes(P256.SIGNATURE_BYTES)
        val granter = r.u64()
        val ephemeral = r.bytes(P256.POINT_BYTES)
        val n = r.u16()
        if (n > MAX_WRAPS) throw WireException("too many recipients")
        val wraps = List(n) { KeyBatch.Wrap(r.u64(), r.bytes(KeyBatch.WRAP_BYTES)) }
        val signature = r.bytes(P256.SIGNATURE_BYTES)
        if (r.remaining != 0) throw WireException("trailing data")
        if (epoch !in 1..Int.MAX_VALUE || !P256.isPoint(ephemeral)) throw WireException("bad grant")
        val announcement = InviteAnnouncement(ensemble, epoch.toInt(), keyId, announcer, announceSignature)
        return InviteGrant(announcement, granter, ephemeral, wraps, signature)
    }

    /** Whether [announcement] is signed by its announcer, who may let someone in, in [state]. */
    fun checkAnnouncement(
        announcement: InviteAnnouncement,
        state: HierarchyState,
    ): GrantVerdict {
        if (announcement.ensembleId != state.ensembleId) return GrantVerdict.WRONG_ENSEMBLE
        val announcer = state.members[announcement.announcer] ?: return GrantVerdict.NOT_ALLOWED
        if (announcer.id != state.creator && announcer.id !in state.admins) return GrantVerdict.NOT_ALLOWED
        val signed = ANNOUNCE_TAG + announcement.body()
        if (!P256.verify(announcer.keys.signing, signed, announcement.signature)) return GrantVerdict.FORGED
        return GrantVerdict.ACCEPTED
    }

    /** [grant] checked in [state], and its key for the phone that holds [keys] when it is one of the recipients. */
    fun open(
        grant: InviteGrant,
        state: HierarchyState,
        keys: PhoneKeys,
    ): Pair<GrantVerdict, ByteArray?> {
        val verdict = checkAnnouncement(grant.announcement, state)
        if (verdict != GrantVerdict.ACCEPTED) return verdict to null
        val granter = state.members[grant.granter] ?: return GrantVerdict.NOT_ALLOWED to null
        if (grant.wraps.any { it.recipient !in state.members }) return GrantVerdict.NOT_ALLOWED to null
        if (!P256.verify(granter.keys.signing, GRANT_TAG + grant.body(), grant.signature)) {
            return GrantVerdict.FORGED to null
        }
        val self = keys.memberId
        val wrap = grant.wraps.firstOrNull { it.recipient == self } ?: return GrantVerdict.NOT_FOR_ME to null
        val shared = keys.agree(grant.ephemeral) ?: return GrantVerdict.DAMAGED to null
        val context = context(grant.announcement, grant.ephemeral, self)
        val key =
            Aead.open(wrapKey(shared, context), NONCE, context, wrap.sealed) ?: return GrantVerdict.DAMAGED to null
        val a = grant.announcement
        if (keyId(a.ensembleId, a.epoch, key) != a.keyId) return GrantVerdict.DAMAGED to null
        return GrantVerdict.ACCEPTED to key
    }

    private fun context(
        a: InviteAnnouncement,
        ephemeral: ByteArray,
        recipient: Long,
    ) = a.body() + ephemeral + OpAuth.longBytes(recipient)

    private fun wrapKey(
        shared: ByteArray,
        context: ByteArray,
    ) = GroupCrypto.hkdfSha256(shared, ByteArray(0), WRAP_INFO + context, Aead.KEY_BYTES)
}

/** A message of a thread, or an alert, as it travels inside a share. */
class OperationText(
    /** Drawn by the author; with the author, it names the message on every phone (a frame received twice counts once). */
    val id: Long,
    val sentAtMs: Long,
    val text: String,
    /** An alert only: where the author was. */
    val fix: PeerFix? = null,
    /** An alert only: the author lifted it. */
    val lifted: Boolean = false,
)

/** The payloads the engine leaves to the caller: positions, messages, alerts (`docs/specs/group-hierarchy.md` § 17). */
object OperationPayloads {
    const val MAX_TEXT_BYTES = 1024
    private const val HAS_FIX = 1
    private const val LIFTED = 2

    fun position(fix: PeerFix): ByteArray = ByteWriter().also { fix.write(it) }.toByteArray()

    fun readPosition(payload: ByteArray): PeerFix {
        val r = ByteReader(payload)
        val fix = PeerFix.read(r)
        if (r.remaining != 0) throw WireException("trailing data")
        return fix
    }

    fun message(m: OperationText): ByteArray =
        ByteWriter().u64(m.id).u64(m.sentAtMs).longStr(m.text, MAX_TEXT_BYTES).toByteArray()

    fun readMessage(payload: ByteArray): OperationText {
        val r = ByteReader(payload)
        val m = OperationText(r.u64(), r.u64(), r.longStr())
        if (r.remaining != 0 || m.id == 0L) throw WireException("bad message")
        return m
    }

    fun alert(a: OperationText): ByteArray {
        val flags = (if (a.fix != null) HAS_FIX else 0) or (if (a.lifted) LIFTED else 0)
        val w = ByteWriter().u64(a.id).u64(a.sentAtMs).u8(flags)
        a.fix?.write(w)
        return w.longStr(a.text, MAX_TEXT_BYTES).toByteArray()
    }

    fun readAlert(payload: ByteArray): OperationText {
        val r = ByteReader(payload)
        val id = r.u64()
        val sent = r.u64()
        val flags = r.u8()
        if (flags and (HAS_FIX or LIFTED).inv() != 0) throw WireException("unknown flag of an alert")
        val fix = if (flags and HAS_FIX != 0) PeerFix.read(r) else null
        val a = OperationText(id, sent, r.longStr(), fix, flags and LIFTED != 0)
        if (r.remaining != 0 || id == 0L) throw WireException("bad alert")
        return a
    }
}

/** One invitation key this phone holds, with the announcement that makes it the key of its epoch. */
class EpochKey(
    val key: ByteArray,
    val announcement: InviteAnnouncement,
) {
    val epoch: Int get() = announcement.epoch
}

/**
 * What a phone keeps of how it reaches the others in an operation, beside what the engine holds: the room and the
 * server, the invitation keys of the last epochs, the card of the name of the operation its invitation carried, and,
 * while it is not let in, the card of its own name. Sealed on the disk with the rest of the operation.
 */
class OperationLinkState(
    val ensembleId: Long,
    val room: ByteArray,
    val server: RelayConfig?,
    val epochs: List<EpochKey>,
    val inviteCard: NameCard?,
    val ownCard: NameCard?,
) {
    fun encode(): ByteArray {
        val w = ByteWriter().u8(VERSION).u64(ensembleId).bytes(room)
        if (server == null) {
            w.u8(0)
        } else {
            w.u8(1).str(server.base, RelayConfig.MAX_URL_BYTES).str(server.apiKey, RelayConfig.MAX_KEY_BYTES)
        }
        w.u8(epochs.size)
        for (e in epochs) {
            w.bytes(e.key).bytes(e.announcement.body()).bytes(e.announcement.signature)
        }
        for (card in listOf(inviteCard, ownCard)) {
            if (card == null) w.u8(0) else OperationWire.writeCard(w.u8(1), card)
        }
        return w.toByteArray()
    }

    companion object {
        private const val VERSION = 1
        const val KEPT_EPOCHS = 4

        fun decode(bytes: ByteArray): OperationLinkState {
            val r = ByteReader(bytes)
            if (r.u8() != VERSION) throw WireException("unknown version of the link state")
            val ensemble = r.u64()
            val room = r.bytes(OperationInvite.ROOM_BYTES)
            val server =
                when (r.u8()) {
                    0 -> null
                    1 -> RelayConfig(r.str(), r.str())
                    else -> throw WireException("bad server flag")
                }
            val n = r.u8()
            if (n > KEPT_EPOCHS) throw WireException("too many epochs")
            val epochs =
                List(n) {
                    val key = r.bytes(OperationInvite.KEY_BYTES)
                    val a =
                        InviteAnnouncement(r.u64(), r.u32().toInt(), r.u64(), r.u64(), r.bytes(P256.SIGNATURE_BYTES))
                    EpochKey(key, a)
                }
            val cards =
                List(2) {
                    when (r.u8()) {
                        0 -> null
                        1 -> OperationWire.readCard(r)
                        else -> throw WireException("bad card flag")
                    }
                }
            if (r.remaining != 0) throw WireException("trailing data")
            return OperationLinkState(ensemble, room, server, epochs, cards[0], cards[1])
        }
    }
}

internal object OperationWire {
    fun writeCard(
        w: ByteWriter,
        card: NameCard,
    ): ByteWriter = w.bytes(card.salt).str(card.name, Names.MAX_BYTES)

    fun readCard(r: ByteReader): NameCard {
        val card = NameCard(r.bytes(NameCard.SALT_BYTES), r.str())
        if (!card.valid) throw WireException("bad name card")
        return card
    }

    fun writeChannel(
        w: ByteWriter,
        channel: Channel,
    ): ByteWriter = w.u8(channel.lane.code).u64(channel.scope)

    fun readChannel(r: ByteReader): Channel {
        val lane = Lane.of(r.u8()) ?: throw WireException("unknown lane")
        return Channel(lane, r.u64())
    }
}
