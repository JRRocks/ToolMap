// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Pairing.kt: the pairing codes of the direct mode (binary → raw
// DEFLATE when shorter → Base45 or URL-safe Base64), docs/specs/p2p-sharing.md §3.2.

import Compression
import Foundation

public enum PairingKind: Int, CaseIterable, Sendable {
    /// The invitation shown by the first member: carries the group key.
    case offer = 1
    /// The reply to any offer.
    case answer = 2
    /// An offer relayed through the mesh to a member already in the group: no key, it has it.
    case meshOffer = 3

    public var code: Int { rawValue }

    public static func of(_ code: Int) -> PairingKind? { PairingKind(rawValue: code) }
}

public enum DtlsSetup: Int, CaseIterable, Sendable {
    case actpass = 0
    case active = 1
    case passive = 2

    public var code: Int { rawValue }

    public var sdp: String {
        switch self {
        case .actpass: "actpass"
        case .active: "active"
        case .passive: "passive"
        }
    }

    public static func of(_ code: Int) -> DtlsSetup? { DtlsSetup(rawValue: code) }

    public static func ofSdp(_ text: String) -> DtlsSetup? { allCases.first { $0.sdp == text } }
}

public enum CandidateType: Int, CaseIterable, Sendable, CustomStringConvertible {
    case host = 0
    case srflx = 1
    case prflx = 2

    public var code: Int { rawValue }

    public var sdp: String {
        switch self {
        case .host: "host"
        case .srflx: "srflx"
        case .prflx: "prflx"
        }
    }

    /// RFC 8445 type preference, used to rebuild a priority.
    public var preference: Int {
        switch self {
        case .host: 126
        case .srflx: 100
        case .prflx: 110
        }
    }

    public var description: String { sdp.uppercased() }

    public static func of(_ code: Int) -> CandidateType? { CandidateType(rawValue: code) }

    public static func ofSdp(_ text: String) -> CandidateType? { allCases.first { $0.sdp == text } }
}

/// A UDP ICE candidate reduced to what a remote needs; `rank` 0 is the best of its list.
public struct IceCandidate: Equatable, Hashable, Sendable, CustomStringConvertible {
    public static let ipv4Bytes = 4
    public static let ipv6Bytes = 16

    public let type: CandidateType
    /// 4 bytes (IPv4) or 16 bytes (IPv6).
    public let ip: [UInt8]
    public let port: Int
    public let rank: Int

    public init(_ type: CandidateType, _ ip: [UInt8], _ port: Int, _ rank: Int) {
        self.type = type
        self.ip = ip
        self.port = port
        self.rank = rank
    }

    public var isV6: Bool { ip.count == IceCandidate.ipv6Bytes }

    /// The address as the JVM writes it (`InetAddress.getHostAddress`): dotted for IPv4 and IPv4-mapped IPv6, eight
    /// groups without leading zeros nor `::` for IPv6.
    public var address: String { IpAddress.text(ip) }

    public var description: String { "\(type) \(address):\(port) #\(rank)" }
}

/// Everything a remote needs to reach this device, in place of a whole SDP (which would not fit in a QR code).
/// `groupKey` is present for `PairingKind.offer` only.
public struct PairingPayload: Equatable, Hashable, Sendable {
    public static let groupIdBytes = 16
    public static let fingerprintBytes = 32
    public static let maxCandidates = 8

    public let kind: PairingKind
    public let createdAtSec: Int64
    public let groupId: [UInt8]
    public let groupKey: [UInt8]?
    public let memberId: Int64
    public let name: String
    public let color: Int32
    public let iceUfrag: String
    public let icePwd: String
    public let dtlsFingerprint: [UInt8]
    public let dtlsSetup: DtlsSetup
    public let candidates: [IceCandidate]

    public init(
        kind: PairingKind,
        createdAtSec: Int64,
        groupId: [UInt8],
        groupKey: [UInt8]?,
        memberId: Int64,
        name: String,
        color: Int32,
        iceUfrag: String,
        icePwd: String,
        dtlsFingerprint: [UInt8],
        dtlsSetup: DtlsSetup,
        candidates: [IceCandidate]
    ) throws {
        try require(groupId.count == PairingPayload.groupIdBytes, "group id must be \(PairingPayload.groupIdBytes) bytes")
        try require(dtlsFingerprint.count == PairingPayload.fingerprintBytes, "DTLS fingerprint must be SHA-256")
        try require((kind == .offer) == (groupKey != nil), "only an invitation carries the group key")
        try require(groupKey == nil || groupKey?.count == GroupCrypto.keyBytes, "bad group key size")
        try require(candidates.count <= PairingPayload.maxCandidates, "too many candidates")
        self.kind = kind
        self.createdAtSec = createdAtSec
        self.groupId = groupId
        self.groupKey = groupKey
        self.memberId = memberId
        self.name = name
        self.color = color
        self.iceUfrag = iceUfrag
        self.icePwd = icePwd
        self.dtlsFingerprint = dtlsFingerprint
        self.dtlsSetup = dtlsSetup
        self.candidates = candidates
    }
}

/// The text of a pairing code (binary → deflate when shorter → Base45) and its link variant. The QR code holds the
/// Base45 text in alphanumeric mode; the link carries the same bytes as URL-safe Base64 so that any messenger keeps
/// it intact (Base45 has spaces and `%` in it).
public enum PairingCodec {
    public static let magic = 0x544D

    /// 3: the colour is a packed icon and RGB colour (`MemberLook`) on 4 bytes instead of a palette index (version 1).
    /// Version 2 is taken by the server invitations (`RoomInviteCodec`).
    private static let version = 3
    private static let deflated = 0x80
    public static let maxAgeSec: Int64 = 600
    public static let maxNameBytes = 24
    public static let linkPrefix = "toolmap://pair?d="
    private static let inflateLimit = 4096

    public enum Staleness: Sendable {
        case expired
        case fromTheFuture
    }

    /// The binary body before compression (the bytes after the magic and the version).
    static func bodyBytes(_ p: PairingPayload) -> [UInt8] {
        let body = ByteWriter().u8(p.kind.code).u32(p.createdAtSec).bytes(p.groupId)
        if let key = p.groupKey { body.bytes(key) }
        body.u64(p.memberId).str(p.name, maxNameBytes).i32(p.color).str(p.iceUfrag).str(p.icePwd)
        body.bytes(p.dtlsFingerprint).u8(p.dtlsSetup.code).u8(p.candidates.count)
        for c in p.candidates {
            let family = c.isV6 ? 1 : 0
            body.u8((c.type.code << 4) | family).bytes(c.ip).u16(c.port).u8(c.rank)
        }
        return body.toByteArray()
    }

    public static func encodeBytes(_ p: PairingPayload) -> [UInt8] {
        let plain = bodyBytes(p)
        let out = ByteWriter().u16(magic)
        if let packed = deflate(plain), packed.count < plain.count {
            return out.u8(version | deflated).bytes(packed).toByteArray()
        }
        return out.u8(version).bytes(plain).toByteArray()
    }

    public static func decodeBytes(_ data: [UInt8]) throws -> PairingPayload {
        var r = ByteReader(data)
        let codeMagic = try r.u16()
        if codeMagic != magic { throw WireError("not a ToolMap code") }
        let codeVersion = try r.u8()
        if codeVersion & ~deflated != version { throw WireError("unsupported code version") }
        let rest = try r.rest()
        let plain: [UInt8]
        if codeVersion & deflated != 0 {
            plain = try inflate(rest)
        } else {
            plain = rest
        }
        var b = ByteReader(plain)
        let kindCode = try b.u8()
        guard let kind = PairingKind.of(kindCode) else { throw WireError("unknown code kind") }
        let createdAt = try b.u32()
        let groupId = try b.bytes(PairingPayload.groupIdBytes)
        var key: [UInt8]?
        if kind == .offer {
            key = try b.bytes(GroupCrypto.keyBytes)
        }
        let memberId = try b.u64()
        let name = try b.str()
        let color = try b.i32()
        let ufrag = try b.str()
        let pwd = try b.str()
        let fingerprint = try b.bytes(PairingPayload.fingerprintBytes)
        let setupCode = try b.u8()
        guard let setup = DtlsSetup.of(setupCode) else { throw WireError("unknown DTLS setup") }
        let count = try b.u8()
        if count > PairingPayload.maxCandidates { throw WireError("too many candidates") }
        var candidates: [IceCandidate] = []
        for _ in 0 ..< count {
            let head = try b.u8()
            guard let type = CandidateType.of(head >> 4) else { throw WireError("unknown candidate type") }
            let size = head & 1 == 1 ? IceCandidate.ipv6Bytes : IceCandidate.ipv4Bytes
            let ip = try b.bytes(size)
            let port = try b.u16()
            let rank = try b.u8()
            candidates.append(IceCandidate(type, ip, port, rank))
        }
        if b.remaining != 0 { throw WireError("trailing data") }
        return try PairingPayload(
            kind: kind,
            createdAtSec: createdAt,
            groupId: groupId,
            groupKey: key,
            memberId: memberId,
            name: name,
            color: color,
            iceUfrag: ufrag,
            icePwd: pwd,
            dtlsFingerprint: fingerprint,
            dtlsSetup: setup,
            candidates: candidates
        )
    }

    /// The text a QR code holds.
    public static func toQrText(_ p: PairingPayload) -> String { Base45.encode(encodeBytes(p)) }

    /// The link to send by SMS or any messenger.
    public static func toLink(_ p: PairingPayload) -> String {
        linkPrefix + P2PBase64.encode(encodeBytes(p), url: true, padding: false)
    }

    /// Reads what was scanned or pasted: the QR text or the link, with the stray spaces a messenger adds.
    public static func parse(_ text: String) throws -> PairingPayload {
        let trimmed = kotlinTrim(text)
        let bytes: [UInt8]
        if let d = linkData(trimmed, linkPrefix) {
            guard let decoded = P2PBase64.decode(d, url: true) else { throw WireError("bad link: Illegal base64 character") }
            bytes = decoded
        } else {
            bytes = try Base45.decode(trimmed)
        }
        return try decodeBytes(bytes)
    }

    /// The data of a link `prefix…d=<data>&…` (the prefix compared without case), or `nil` when `text` is not one.
    static func linkData(_ text: String, _ prefix: String) -> String? {
        let units = Array(text.utf16)
        let prefixUnits = Array(prefix.utf16)
        guard units.count >= prefixUnits.count else { return nil }
        let head = String(decoding: units[0 ..< prefixUnits.count], as: UTF16.self)
        guard head.lowercased() == prefix.lowercased() else { return nil }
        let rest = String(decoding: units[prefixUnits.count...], as: UTF16.self)
        return rest.substringBefore("&")
    }

    /// `nil` when `p` can still be used at `nowSec`; otherwise why not.
    public static func staleness(_ p: PairingPayload, _ nowSec: Int64) -> Staleness? {
        if nowSec - p.createdAtSec > maxAgeSec { return .expired }
        if p.createdAtSec - nowSec > maxAgeSec { return .fromTheFuture }
        return nil
    }

    /// Keeps the candidates a remote can use and that fit a QR code: IPv4 first, host before server-reflexive, at most
    /// `limit`. The kept ones are ranked 0.. in that order.
    public static func prune(_ candidates: [IceCandidate], limit: Int = 5) -> [IceCandidate] {
        struct Key: Hashable {
            let address: String
            let port: Int
            let type: CandidateType
        }
        var seen = Set<Key>()
        var distinct: [IceCandidate] = []
        for c in candidates where seen.insert(Key(address: c.address, port: c.port, type: c.type)).inserted {
            distinct.append(c)
        }
        func typeOrder(_ c: IceCandidate) -> Int {
            switch c.type.code {
            case 0: 0
            case 2: 1
            default: 2
            }
        }
        // A stable sort, as Kotlin's sortedWith.
        let sorted = distinct.enumerated().sorted { a, b in
            let ka = (a.element.isV6 ? 1 : 0, typeOrder(a.element), a.offset)
            let kb = (b.element.isV6 ? 1 : 0, typeOrder(b.element), b.offset)
            return ka < kb
        }
        return sorted.prefix(Swift.max(limit, 0)).enumerated().map { i, c in
            IceCandidate(c.element.type, c.element.ip, c.element.port, i)
        }
    }

    /// Raw DEFLATE (`Deflater(…, nowrap = true)`); `nil` when the result does not fit `data.count + 64` bytes. The
    /// bytes differ from the JVM's (another compressor), but both sides read each other's.
    static func deflate(_ data: [UInt8]) -> [UInt8]? {
        if data.isEmpty { return nil }
        var out = [UInt8](repeating: 0, count: data.count + 64)
        let capacity = out.count
        let written = out.withUnsafeMutableBufferPointer { dst -> Int in
            data.withUnsafeBufferPointer { src -> Int in
                guard let d = dst.baseAddress, let s = src.baseAddress else { return 0 }
                return compression_encode_buffer(d, capacity, s, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        if written <= 0 { return nil }
        return Array(out[0 ..< written])
    }

    /// Raw DEFLATE back to bytes, at most `inflateLimit`; a truncated or corrupt stream is a `WireError`.
    static func inflate(_ data: [UInt8]) throws -> [UInt8] {
        let limit = inflateLimit
        // A raw deflate stream wants one extra byte after its end on some zlib builds (as the Kotlin code feeds it).
        let input = data + [0]
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        if compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_ERROR {
            throw WireError("corrupt code")
        }
        defer { compression_stream_destroy(stream) }
        let chunk = 512
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        var out: [UInt8] = []
        try input.withUnsafeBufferPointer { (src: UnsafeBufferPointer<UInt8>) throws -> Void in
            guard let base = src.baseAddress else { throw WireError("truncated code") }
            stream.pointee.src_ptr = base
            stream.pointee.src_size = src.count
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let before = stream.pointee.src_size
                let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                if status == COMPRESSION_STATUS_ERROR { throw WireError("corrupt code") }
                let produced = chunk - stream.pointee.dst_size
                out.append(contentsOf: UnsafeBufferPointer(start: buffer, count: produced))
                if out.count > limit { throw WireError("code too large") }
                if status == COMPRESSION_STATUS_END { return }
                if produced == 0, stream.pointee.src_size == before || stream.pointee.src_size == 0 {
                    throw WireError("truncated code")
                }
            }
        }
        return out
    }
}

/// IP addresses as the JVM reads and writes them (`java.net.InetAddress`), for the ICE candidates.
enum IpAddress {
    /// `InetAddress.getByAddress(ip).hostAddress`.
    static func text(_ ip: [UInt8]) -> String {
        if ip.count == IceCandidate.ipv4Bytes {
            return ip.map { String($0) }.joined(separator: ".")
        }
        if ip.count == IceCandidate.ipv6Bytes {
            if let v4 = mappedV4(ip) { return text(v4) }
            var groups: [String] = []
            for i in 0 ..< 8 {
                let v = (Int(ip[2 * i]) << 8) | Int(ip[2 * i + 1])
                groups.append(String(v, radix: 16))
            }
            return groups.joined(separator: ":")
        }
        return ""
    }

    /// The IPv4 address inside an IPv4-mapped IPv6 one (`::ffff:a.b.c.d`), which the JVM turns into an IPv4 address.
    static func mappedV4(_ ip: [UInt8]) -> [UInt8]? {
        guard ip.count == IceCandidate.ipv6Bytes else { return nil }
        for i in 0 ..< 10 where ip[i] != 0 { return nil }
        guard ip[10] == 0xFF, ip[11] == 0xFF else { return nil }
        return Array(ip[12 ..< 16])
    }

    /// The bytes of a literal address (`InetAddress.getByName` without a name lookup), `nil` when `host` is not one.
    static func parse(_ host: String) -> [UInt8]? {
        if host.contains(":") {
            var s = Substring(host)
            if s.hasPrefix("["), s.hasSuffix("]") { s = s.dropFirst().dropLast() }
            if let percent = s.firstIndex(of: "%") { s = s[..<percent] }
            guard let v6 = parseV6(String(s)) else { return nil }
            return mappedV4(v6) ?? v6
        }
        return parseV4(host)
    }

    /// Four decimal numbers of 0 to 255, as `inet_pton` reads them.
    static func parseV4(_ text: String) -> [UInt8]? {
        let parts = kotlinSplit(text, ".")
        guard parts.count == 4 else { return nil }
        var out: [UInt8] = []
        for part in parts {
            guard !part.isEmpty, part.utf8.count <= 3, part.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else {
                return nil
            }
            if part.utf8.count > 1, part.hasPrefix("0") { return nil }
            guard let v = Int(part), v <= 255 else { return nil }
            out.append(UInt8(v))
        }
        return out
    }

    /// Eight groups of 1 to 4 hexadecimal digits, `::` for a run of zero groups, an IPv4 tail allowed.
    static func parseV6(_ text: String) -> [UInt8]? {
        let halves = text.components(separatedBy: "::")
        guard halves.count <= 2 else { return nil }
        guard let head = groups(halves[0], allowV4Tail: halves.count == 1) else { return nil }
        var all: [UInt16]
        if halves.count == 2 {
            guard let tail = groups(halves[1], allowV4Tail: true) else { return nil }
            guard head.count + tail.count <= 7 else { return nil }
            all = head + [UInt16](repeating: 0, count: 8 - head.count - tail.count) + tail
        } else {
            guard head.count == 8 else { return nil }
            all = head
        }
        var out: [UInt8] = []
        for g in all {
            out.append(UInt8(g >> 8))
            out.append(UInt8(g & 0xFF))
        }
        return out
    }

    private static func groups(_ text: String, allowV4Tail: Bool) -> [UInt16]? {
        if text.isEmpty { return [] }
        let parts = kotlinSplit(text, ":")
        var out: [UInt16] = []
        for (i, part) in parts.enumerated() {
            if i == parts.count - 1, allowV4Tail, part.contains(".") {
                guard let v4 = parseV4(part) else { return nil }
                out.append(UInt16(v4[0]) << 8 | UInt16(v4[1]))
                out.append(UInt16(v4[2]) << 8 | UInt16(v4[3]))
                continue
            }
            guard !part.isEmpty, part.utf8.count <= 4, let v = UInt16(part, radix: 16),
                  part.utf8.allSatisfy(isHexDigit)
            else { return nil }
            out.append(v)
        }
        return out
    }

    private static func isHexDigit(_ c: UInt8) -> Bool {
        (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x46) || (c >= 0x61 && c <= 0x66)
    }

    /// `InetAddress.isLinkLocalAddress`.
    static func isLinkLocal(_ ip: [UInt8]) -> Bool {
        if ip.count == 4 { return ip[0] == 169 && ip[1] == 254 }
        return ip.count == 16 && ip[0] == 0xFE && ip[1] & 0xC0 == 0x80
    }

    /// `InetAddress.isMulticastAddress`.
    static func isMulticast(_ ip: [UInt8]) -> Bool {
        if ip.count == 4 { return ip[0] & 0xF0 == 0xE0 }
        return ip.count == 16 && ip[0] == 0xFF
    }

    /// `InetAddress.isAnyLocalAddress`.
    static func isAnyLocal(_ ip: [UInt8]) -> Bool {
        !ip.isEmpty && ip.allSatisfy { $0 == 0 }
    }
}
