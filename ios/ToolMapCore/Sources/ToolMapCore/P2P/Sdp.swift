// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Sdp.kt.

/// The few fields of a real SDP that a pairing code carries.
public struct SdpInfo: Equatable, Sendable {
    public let iceUfrag: String
    public let icePwd: String
    public let dtlsFingerprint: [UInt8]
    public let dtlsSetup: DtlsSetup
    public let candidates: [IceCandidate]

    public init(iceUfrag: String, icePwd: String, dtlsFingerprint: [UInt8], dtlsSetup: DtlsSetup, candidates: [IceCandidate]) {
        self.iceUfrag = iceUfrag
        self.icePwd = icePwd
        self.dtlsFingerprint = dtlsFingerprint
        self.dtlsSetup = dtlsSetup
        self.candidates = candidates
    }
}

/// Builds the SDP of a data-channel-only connection from a `PairingPayload` with a fixed template, and reads the
/// handful of fields we keep from the SDP the WebRTC stack produced. Only these fields vary between two such
/// sessions, so the rest of the SDP never travels.
public enum Sdp {
    public static let sctpPort = 5000
    private static let maxMessageSize = 262_144
    private static let candidateMinFields = 8

    public static func build(_ p: PairingPayload, sessionId: Int64 = 4_611_731_400_430_051_336) -> String {
        var lines: [String] = []
        lines.append("v=0")
        lines.append("o=- \(sessionId) 2 IN IP4 127.0.0.1")
        lines.append("s=-")
        lines.append("t=0 0")
        lines.append("a=group:BUNDLE 0")
        lines.append("a=msid-semantic: WMS")
        lines.append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel")
        lines.append("c=IN IP4 0.0.0.0")
        lines.append("a=ice-ufrag:\(p.iceUfrag)")
        lines.append("a=ice-pwd:\(p.icePwd)")
        lines.append("a=fingerprint:sha-256 \(fingerprintText(p.dtlsFingerprint))")
        lines.append("a=setup:\(p.dtlsSetup.sdp)")
        lines.append("a=mid:0")
        lines.append("a=sctp-port:\(sctpPort)")
        lines.append("a=max-message-size:\(maxMessageSize)")
        for (i, c) in p.candidates.enumerated() {
            lines.append(candidateLine(c, i))
        }
        lines.append("a=end-of-candidates")
        return lines.joined(separator: "\r\n") + "\r\n"
    }

    /// `a=candidate:` line of `c`; the priority is rebuilt from its type and rank as RFC 8445 does.
    public static func candidateLine(_ c: IceCandidate, _ index: Int) -> String {
        let localPreference = 65_535 - c.rank * 256
        let priority = (Int64(c.type.preference) << 24) | (Int64(localPreference) << 8) | 255
        let related = c.type == .host ? "" : " raddr \(c.isV6 ? "::" : "0.0.0.0") rport 0"
        return "a=candidate:\(index + 1) 1 udp \(priority) \(c.address) \(c.port) typ \(c.type.sdp)\(related) generation 0"
    }

    public static func fingerprintText(_ fingerprint: [UInt8]) -> String {
        fingerprint.map { P2PHex.encodeUpper($0) }.joined(separator: ":")
    }

    /// Reads `sdp`; a missing mandatory field is a `WireError`. Unusable candidates are skipped.
    public static func parse(_ sdp: String) throws -> SdpInfo {
        var ufrag: String?
        var pwd: String?
        var fingerprint: [UInt8]?
        var setup: DtlsSetup?
        var raw: [IceCandidate] = []
        for rawLine in lines(sdp) {
            let line = kotlinTrim(rawLine)
            if line.hasPrefix("a=ice-ufrag:") {
                if ufrag == nil { ufrag = after(line, ":") }
            } else if line.hasPrefix("a=ice-pwd:") {
                if pwd == nil { pwd = after(line, ":") }
            } else if line.hasPrefix("a=fingerprint:") {
                let value = kotlinTrim(after(line, ":"))
                let algorithm: String
                var text: String?
                if let space = value.firstIndex(of: " ") {
                    algorithm = String(value[..<space])
                    text = String(value[value.index(after: space)...])
                } else {
                    algorithm = value
                }
                if fingerprint == nil, equalsIgnoringCase(algorithm, "sha-256"), let t = text {
                    fingerprint = try parseFingerprint(t)
                }
            } else if line.hasPrefix("a=setup:") {
                if setup == nil { setup = DtlsSetup.ofSdp(kotlinTrim(after(line, ":"))) }
            } else if line.hasPrefix("a=candidate:") {
                if let c = parseCandidate(line) { raw.append(c) }
            }
        }
        guard let iceUfrag = ufrag else { throw WireError("SDP without ice-ufrag") }
        guard let icePwd = pwd else { throw WireError("SDP without ice-pwd") }
        guard let dtlsFingerprint = fingerprint else { throw WireError("SDP without a SHA-256 fingerprint") }
        guard let dtlsSetup = setup else { throw WireError("SDP without setup") }
        return SdpInfo(
            iceUfrag: iceUfrag,
            icePwd: icePwd,
            dtlsFingerprint: dtlsFingerprint,
            dtlsSetup: dtlsSetup,
            candidates: PairingCodec.prune(raw)
        )
    }

    private static func parseFingerprint(_ text: String) throws -> [UInt8] {
        let parts = kotlinSplit(kotlinTrim(text), ":")
        if parts.count != PairingPayload.fingerprintBytes { throw WireError("bad fingerprint") }
        var out: [UInt8] = []
        for part in parts {
            // Kotlin toIntOrNull(16) then toByte(): a sign is accepted and the value cut to 8 bits.
            guard let v = Int32(part, radix: 16) else { throw WireError("bad fingerprint") }
            out.append(UInt8(truncatingIfNeeded: v))
        }
        return out
    }

    /// UDP host / srflx / prflx candidates with a literal address; relay, TCP and mDNS `.local` names are skipped.
    public static func parseCandidate(_ line: String) -> IceCandidate? {
        var body = Substring(line)
        if body.hasPrefix("a=") { body = body.dropFirst(2) }
        if body.hasPrefix("candidate:") { body = body.dropFirst("candidate:".count) }
        let f = kotlinSplit(kotlinTrim(String(body)), " ").filter { !$0.isEmpty }
        if f.count < candidateMinFields || f[2].lowercased() != "udp" || f[6] != "typ" { return nil }
        guard let type = CandidateType.ofSdp(f[7]) else { return nil }
        let host = f[4]
        let literal = host.contains(":") || host.unicodeScalars.allSatisfy {
            $0 == "." || $0.properties.generalCategory == .decimalNumber
        }
        if host.hasSuffix(".local") || !literal { return nil }
        guard let address = IpAddress.parse(host) else { return nil }
        if IpAddress.isLinkLocal(address) || IpAddress.isMulticast(address) || IpAddress.isAnyLocal(address) {
            return nil
        }
        guard let port = kotlinInt(f[5]), port >= 1, port <= 65_535 else { return nil }
        return IceCandidate(type, address, port, 0)
    }

    /// Kotlin `lineSequence()`: lines cut at `\r\n`, `\n` or `\r` (a Swift `Character` holds `\r\n` as one).
    private static func lines(_ text: String) -> [String] {
        var out: [String] = []
        var current = String.UnicodeScalarView()
        var previousCR = false
        for scalar in text.unicodeScalars {
            if scalar == "\n" {
                if previousCR {
                    previousCR = false
                    continue
                }
                out.append(String(current))
                current = String.UnicodeScalarView()
            } else if scalar == "\r" {
                out.append(String(current))
                current = String.UnicodeScalarView()
                previousCR = true
                continue
            } else {
                current.append(scalar)
            }
            previousCR = false
        }
        out.append(String(current))
        return out
    }

    /// Kotlin `substringAfter(delimiter)`: the whole text when the delimiter is missing.
    private static func after(_ text: String, _ delimiter: Character) -> String {
        guard let index = text.firstIndex(of: delimiter) else { return text }
        return String(text[text.index(after: index)...])
    }
}
