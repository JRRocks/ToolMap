package fr.toolmap.core.p2p

import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

enum class PairingKind(
    val code: Int,
) {
    /** The invitation shown by the first member: carries the group key. */
    OFFER(1),

    /** The reply to any offer. */
    ANSWER(2),

    /** An offer relayed through the mesh to a member already in the group: no key, it has it. */
    MESH_OFFER(3),
    ;

    companion object {
        fun of(code: Int): PairingKind? = entries.firstOrNull { it.code == code }
    }
}

enum class DtlsSetup(
    val code: Int,
    val sdp: String,
) {
    ACTPASS(0, "actpass"),
    ACTIVE(1, "active"),
    PASSIVE(2, "passive"),
    ;

    companion object {
        fun of(code: Int): DtlsSetup? = entries.firstOrNull { it.code == code }

        fun ofSdp(text: String): DtlsSetup? = entries.firstOrNull { it.sdp == text }
    }
}

enum class CandidateType(
    val code: Int,
    val sdp: String,
    /** RFC 8445 type preference, used to rebuild a priority. */
    val preference: Int,
) {
    HOST(0, "host", 126),
    SRFLX(1, "srflx", 100),
    PRFLX(2, "prflx", 110),
    ;

    companion object {
        fun of(code: Int): CandidateType? = entries.firstOrNull { it.code == code }

        fun ofSdp(text: String): CandidateType? = entries.firstOrNull { it.sdp == text }
    }
}

/** A UDP ICE candidate reduced to what a remote needs; [rank] 0 is the best of its list. */
class IceCandidate(
    val type: CandidateType,
    /** 4 bytes (IPv4) or 16 bytes (IPv6). */
    val ip: ByteArray,
    val port: Int,
    val rank: Int,
) {
    val isV6: Boolean get() = ip.size == IPV6_BYTES

    val address: String
        get() = java.net.InetAddress.getByAddress(ip).hostAddress.orEmpty().substringBefore('%')

    override fun equals(other: Any?): Boolean =
        other is IceCandidate && type == other.type && ip.contentEquals(other.ip) && port == other.port &&
            rank == other.rank

    override fun hashCode(): Int = ((type.hashCode() * 31 + ip.contentHashCode()) * 31 + port) * 31 + rank

    override fun toString(): String = "$type $address:$port #$rank"

    companion object {
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
    }
}

/**
 * Everything a remote needs to reach this device, in place of a whole SDP (which would not fit in a QR code).
 * [groupKey] is present for [PairingKind.OFFER] only.
 */
class PairingPayload(
    val kind: PairingKind,
    val createdAtSec: Long,
    val groupId: ByteArray,
    val groupKey: ByteArray?,
    val memberId: Long,
    val name: String,
    val color: Int,
    val iceUfrag: String,
    val icePwd: String,
    val dtlsFingerprint: ByteArray,
    val dtlsSetup: DtlsSetup,
    val candidates: List<IceCandidate>,
) {
    init {
        require(groupId.size == GROUP_ID_BYTES) { "group id must be $GROUP_ID_BYTES bytes" }
        require(dtlsFingerprint.size == FINGERPRINT_BYTES) { "DTLS fingerprint must be SHA-256" }
        require((kind == PairingKind.OFFER) == (groupKey != null)) { "only an invitation carries the group key" }
        require(groupKey == null || groupKey.size == GroupCrypto.KEY_BYTES) { "bad group key size" }
        require(candidates.size <= MAX_CANDIDATES) { "too many candidates" }
    }

    override fun equals(other: Any?): Boolean =
        other is PairingPayload && kind == other.kind && createdAtSec == other.createdAtSec &&
            groupId.contentEquals(other.groupId) && (groupKey ?: EMPTY).contentEquals(other.groupKey ?: EMPTY) &&
            memberId == other.memberId && name == other.name && color == other.color && iceUfrag == other.iceUfrag &&
            icePwd == other.icePwd && dtlsFingerprint.contentEquals(other.dtlsFingerprint) &&
            dtlsSetup == other.dtlsSetup && candidates == other.candidates

    override fun hashCode(): Int = (memberId.hashCode() * 31 + iceUfrag.hashCode()) * 31 + candidates.hashCode()

    companion object {
        const val GROUP_ID_BYTES = 16
        const val FINGERPRINT_BYTES = 32
        const val MAX_CANDIDATES = 8
        private val EMPTY = ByteArray(0)
    }
}

/**
 * The text of a pairing code (binary → deflate when shorter → Base45) and its link variant. The QR code holds the
 * Base45 text in alphanumeric mode; the link carries the same bytes as URL-safe Base64 so that any messenger keeps
 * it intact (Base45 has spaces and `%` in it).
 */
object PairingCodec {
    const val MAGIC = 0x544D

    /**
     * 3: the colour is a packed icon and RGB colour ([MemberLook]) on 4 bytes instead of a palette index (version 1).
     * Version 2 is taken by the server invitations ([RoomInviteCodec]).
     */
    private const val VERSION = 3
    private const val DEFLATED = 0x80
    const val MAX_AGE_SEC = 600L
    const val MAX_NAME_BYTES = 24
    const val LINK_PREFIX = "toolmap://pair?d="
    private const val INFLATE_LIMIT = 4096

    fun encodeBytes(p: PairingPayload): ByteArray {
        val body = ByteWriter().u8(p.kind.code).u32(p.createdAtSec).bytes(p.groupId)
        p.groupKey?.let { body.bytes(it) }
        body.u64(p.memberId).str(p.name, MAX_NAME_BYTES).i32(p.color).str(p.iceUfrag).str(p.icePwd)
        body.bytes(p.dtlsFingerprint).u8(p.dtlsSetup.code).u8(p.candidates.size)
        for (c in p.candidates) {
            val family = if (c.isV6) 1 else 0
            body.u8((c.type.code shl 4) or family).bytes(c.ip).u16(c.port).u8(c.rank)
        }
        val plain = body.toByteArray()
        val packed = deflate(plain)
        val useDeflate = packed.size < plain.size
        val out = ByteWriter().u16(MAGIC).u8(if (useDeflate) VERSION or DEFLATED else VERSION)
        return out.bytes(if (useDeflate) packed else plain).toByteArray()
    }

    fun decodeBytes(data: ByteArray): PairingPayload {
        val r = ByteReader(data)
        if (r.u16() != MAGIC) throw WireException("not a ToolMap code")
        val version = r.u8()
        if (version and DEFLATED.inv() != VERSION) throw WireException("unsupported code version")
        val rest = r.rest()
        val plain = if (version and DEFLATED != 0) inflate(rest) else rest
        val b = ByteReader(plain)
        val kind = PairingKind.of(b.u8()) ?: throw WireException("unknown code kind")
        val createdAt = b.u32()
        val groupId = b.bytes(PairingPayload.GROUP_ID_BYTES)
        val key = if (kind == PairingKind.OFFER) b.bytes(GroupCrypto.KEY_BYTES) else null
        val memberId = b.u64()
        val name = b.str()
        val color = b.i32()
        val ufrag = b.str()
        val pwd = b.str()
        val fingerprint = b.bytes(PairingPayload.FINGERPRINT_BYTES)
        val setup = DtlsSetup.of(b.u8()) ?: throw WireException("unknown DTLS setup")
        val count = b.u8()
        if (count > PairingPayload.MAX_CANDIDATES) throw WireException("too many candidates")
        val candidates =
            List(count) {
                val head = b.u8()
                val type = CandidateType.of(head shr 4) ?: throw WireException("unknown candidate type")
                val size = if (head and 1 == 1) IceCandidate.IPV6_BYTES else IceCandidate.IPV4_BYTES
                IceCandidate(type, b.bytes(size), b.u16(), b.u8())
            }
        if (b.remaining != 0) throw WireException("trailing data")
        return PairingPayload(
            kind,
            createdAt,
            groupId,
            key,
            memberId,
            name,
            color,
            ufrag,
            pwd,
            fingerprint,
            setup,
            candidates,
        )
    }

    /** The text a QR code holds. */
    fun toQrText(p: PairingPayload): String = Base45.encode(encodeBytes(p))

    /** The link to send by SMS or any messenger. */
    fun toLink(p: PairingPayload): String =
        LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encodeBytes(p))

    /** Reads what was scanned or pasted: the QR text or the link, with the stray spaces a messenger adds. */
    fun parse(text: String): PairingPayload {
        val trimmed = text.trim()
        val bytes =
            if (trimmed.startsWith(LINK_PREFIX, ignoreCase = true)) {
                val d = trimmed.substring(LINK_PREFIX.length).substringBefore('&')
                try {
                    Base64.getUrlDecoder().decode(d)
                } catch (e: IllegalArgumentException) {
                    throw WireException("bad link: ${e.message}")
                }
            } else {
                Base45.decode(trimmed)
            }
        return decodeBytes(bytes)
    }

    /** `null` when [p] can still be used at [nowSec]; otherwise why not. */
    fun staleness(
        p: PairingPayload,
        nowSec: Long,
    ): Staleness? =
        when {
            nowSec - p.createdAtSec > MAX_AGE_SEC -> Staleness.EXPIRED
            p.createdAtSec - nowSec > MAX_AGE_SEC -> Staleness.FROM_THE_FUTURE
            else -> null
        }

    enum class Staleness { EXPIRED, FROM_THE_FUTURE }

    /**
     * Keeps the candidates a remote can use and that fit a QR code: IPv4 first, host before server-reflexive, at most
     * [limit]. The kept ones are ranked 0.. in that order.
     */
    fun prune(
        candidates: List<IceCandidate>,
        limit: Int = 5,
    ): List<IceCandidate> =
        candidates
            .distinctBy { Triple(it.address, it.port, it.type) }
            .sortedWith(
                compareBy({ it.isV6 }, {
                    it.type.code.let { c ->
                        if (c == 0) {
                            0
                        } else if (c == 2) {
                            1
                        } else {
                            2
                        }
                    }
                }),
            )
            .take(limit)
            .mapIndexed { i, c -> IceCandidate(c.type, c.ip, c.port, i) }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(data)
            d.finish()
            val buf = ByteArray(data.size + 64)
            var n = 0
            while (!d.finished() && n < buf.size) n += d.deflate(buf, n, buf.size - n)
            return buf.copyOf(n)
        } finally {
            d.end()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater(true)
        try {
            // A raw deflate stream wants one extra byte after its end on some zlib builds.
            inf.setInput(data.copyOf(data.size + 1))
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(512)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw WireException("truncated code")
                out.write(buf, 0, n)
                if (out.size() > INFLATE_LIMIT) throw WireException("code too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw WireException("corrupt code")
        } finally {
            inf.end()
        }
    }
}
