package fr.toolmap.core.p2p

import java.net.InetAddress

/** The few fields of a real SDP that a pairing code carries. */
class SdpInfo(
    val iceUfrag: String,
    val icePwd: String,
    val dtlsFingerprint: ByteArray,
    val dtlsSetup: DtlsSetup,
    val candidates: List<IceCandidate>,
)

/**
 * Builds the SDP of a data-channel-only connection from a [PairingPayload] with a fixed template, and reads the
 * handful of fields we keep from the SDP the WebRTC stack produced. Only these fields vary between two such
 * sessions, so the rest of the SDP never travels.
 */
object Sdp {
    const val SCTP_PORT = 5000
    private const val MAX_MESSAGE_SIZE = 262_144

    fun build(
        p: PairingPayload,
        sessionId: Long = 4_611_731_400_430_051_336L,
    ): String {
        val lines = ArrayList<String>()
        lines += "v=0"
        lines += "o=- $sessionId 2 IN IP4 127.0.0.1"
        lines += "s=-"
        lines += "t=0 0"
        lines += "a=group:BUNDLE 0"
        lines += "a=msid-semantic: WMS"
        lines += "m=application 9 UDP/DTLS/SCTP webrtc-datachannel"
        lines += "c=IN IP4 0.0.0.0"
        lines += "a=ice-ufrag:${p.iceUfrag}"
        lines += "a=ice-pwd:${p.icePwd}"
        lines += "a=fingerprint:sha-256 ${fingerprintText(p.dtlsFingerprint)}"
        lines += "a=setup:${p.dtlsSetup.sdp}"
        lines += "a=mid:0"
        lines += "a=sctp-port:$SCTP_PORT"
        lines += "a=max-message-size:$MAX_MESSAGE_SIZE"
        for ((i, c) in p.candidates.withIndex()) lines += candidateLine(c, i)
        lines += "a=end-of-candidates"
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    /** `a=candidate:` line of [c]; the priority is rebuilt from its type and rank as RFC 8445 does. */
    fun candidateLine(
        c: IceCandidate,
        index: Int,
    ): String {
        val localPreference = 65_535 - c.rank * 256
        val priority = (c.type.preference.toLong() shl 24) or (localPreference.toLong() shl 8) or 255L
        val related = if (c.type == CandidateType.HOST) "" else " raddr ${if (c.isV6) "::" else "0.0.0.0"} rport 0"
        return "a=candidate:${index + 1} 1 udp $priority ${c.address} ${c.port} typ ${c.type.sdp}$related generation 0"
    }

    fun fingerprintText(fingerprint: ByteArray): String =
        fingerprint.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** Reads [sdp]; a missing mandatory field is a [WireException]. Unusable candidates are skipped. */
    fun parse(sdp: String): SdpInfo {
        var ufrag: String? = null
        var pwd: String? = null
        var fingerprint: ByteArray? = null
        var setup: DtlsSetup? = null
        val raw = ArrayList<IceCandidate>()
        for (line in sdp.lineSequence().map { it.trim() }) {
            when {
                line.startsWith("a=ice-ufrag:") -> {
                    ufrag = ufrag ?: line.substringAfter(':')
                }

                line.startsWith("a=ice-pwd:") -> {
                    pwd = pwd ?: line.substringAfter(':')
                }

                line.startsWith("a=fingerprint:") -> {
                    val (algorithm, value) =
                        line.substringAfter(':').trim().split(' ', limit = 2).let {
                            it[0] to
                                it.getOrNull(1)
                        }
                    if (fingerprint == null && algorithm.equals("sha-256", ignoreCase = true) && value != null) {
                        fingerprint = parseFingerprint(value)
                    }
                }

                line.startsWith("a=setup:") -> {
                    setup = setup ?: DtlsSetup.ofSdp(line.substringAfter(':').trim())
                }

                line.startsWith("a=candidate:") -> {
                    parseCandidate(line)?.let { raw += it }
                }
            }
        }
        return SdpInfo(
            iceUfrag = ufrag ?: throw WireException("SDP without ice-ufrag"),
            icePwd = pwd ?: throw WireException("SDP without ice-pwd"),
            dtlsFingerprint = fingerprint ?: throw WireException("SDP without a SHA-256 fingerprint"),
            dtlsSetup = setup ?: throw WireException("SDP without setup"),
            candidates = PairingCodec.prune(raw),
        )
    }

    private fun parseFingerprint(text: String): ByteArray {
        val parts = text.trim().split(':')
        if (parts.size != PairingPayload.FINGERPRINT_BYTES) throw WireException("bad fingerprint")
        return ByteArray(parts.size) { parts[it].toIntOrNull(16)?.toByte() ?: throw WireException("bad fingerprint") }
    }

    /** UDP host / srflx / prflx candidates with a literal address; relay, TCP and mDNS `.local` names are skipped. */
    fun parseCandidate(line: String): IceCandidate? {
        val f = line.removePrefix("a=").removePrefix("candidate:").trim().split(' ').filter { it.isNotEmpty() }
        if (f.size < CANDIDATE_MIN_FIELDS || f[2].lowercase() != "udp" || f[6] != "typ") return null
        val type = CandidateType.ofSdp(f[7]) ?: return null
        val host = f[4]
        if (host.endsWith(".local") || !(host.contains(':') || host.all { it.isDigit() || it == '.' })) return null
        val address =
            try {
                InetAddress.getByName(host)
            } catch (e: java.net.UnknownHostException) {
                return null
            }
        if (address.isLinkLocalAddress || address.isMulticastAddress || address.isAnyLocalAddress) return null
        val port = f[5].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        return IceCandidate(type, address.address, port, 0)
    }

    private const val CANDIDATE_MIN_FIELDS = 8
}
