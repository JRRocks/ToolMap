// Port of core/src/test/kotlin/fr/toolmap/core/p2p/CodecsTest.kt: Base45, pairing codes, SDP, group encryption.

import Compression
import Foundation
import Testing
@testable import ToolMapCore

private func ipv4Bytes(_ b: Int...) -> [UInt8] { b.map { UInt8(truncatingIfNeeded: $0) } }

private func defaultCandidates() -> [IceCandidate] {
    [
        IceCandidate(.host, ipv4Bytes(192, 168, 1, 23), 51_234, 0),
        IceCandidate(.srflx, ipv4Bytes(203, 0, 113, 7), 40_001, 1),
        IceCandidate(.host, ipv4Bytes(10, 0, 0, 5), 52_000, 2),
    ]
}

private func samplePayload(
    _ kind: PairingKind = .offer,
    createdAtSec: Int64 = 1_790_000_000,
    name: String = "Jean",
    candidates: [IceCandidate]? = nil,
    memberId: Int64 = 0x1122_3344_5566_7788
) throws -> PairingPayload {
    try PairingPayload(
        kind: kind,
        createdAtSec: createdAtSec,
        groupId: (0 ..< 16).map { UInt8($0 + 1) },
        groupKey: kind == .offer ? (0 ..< 32).map { UInt8(100 + $0) } : nil,
        memberId: memberId,
        name: name,
        color: 3,
        iceUfrag: "aB3d",
        icePwd: "x7Yq0pLm9Rt2VnB4cD6eF8gH",
        dtlsFingerprint: (0 ..< 32).map { UInt8(truncatingIfNeeded: $0 * 7) },
        dtlsSetup: kind == .answer ? .active : .actpass,
        candidates: candidates ?? defaultCandidates()
    )
}

/// A small deterministic generator (the Kotlin tests use `java.util.Random` with a seed).
private struct SplitMix64: RandomNumberGenerator {
    var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// `bytes` with the byte at `index` replaced by `value`.
func p2pReplacing(_ bytes: [UInt8], _ index: Int, _ value: UInt8) -> [UInt8] {
    var copy = bytes
    copy[index] = value
    return copy
}

private func randomBytes(_ count: Int, _ generator: inout SplitMix64) -> [UInt8] {
    (0 ..< count).map { _ in UInt8.random(in: 0 ... 255, using: &generator) }
}

struct Base45Tests {
    @Test("the vectors of RFC 9285")
    func rfcVectors() throws {
        #expect(Base45.encode(Array("AB".utf8)) == "BB8")
        #expect(Base45.encode(Array("Hello!!".utf8)) == "%69 VD92EX0")
        #expect(Base45.encode(Array("base-45".utf8)) == "UJCLQE7W581")
        #expect(Base45.encode(Array("ietf!".utf8)) == "QED8WEX0")
        #expect(try Base45.decode("QED8WEX0") == Array("ietf!".utf8))
        #expect(try Base45.decode("%69 VD92EX0") == Array("Hello!!".utf8))
    }

    @Test("any bytes come back, whatever the length")
    func anyLength() throws {
        var generator = SplitMix64(seed: 7)
        for n in 0 ... 40 {
            let data = randomBytes(n, &generator)
            #expect(try Base45.decode(Base45.encode(data)) == data, "length \(n)")
        }
    }

    @Test("text that is not base45 is refused without a raw exception")
    func refused() {
        #expect(throws: WireError.self) { try Base45.decode("abc") }
        #expect(throws: WireError.self) { try Base45.decode("A") }
        // 16 + 16·45 + 16·2025 > 65535
        #expect(throws: WireError.self) { try Base45.decode("GGW") }
        #expect(throws: WireError.self) { try Base45.decode(":::") }
    }
}

struct PairingCodecTests {
    @Test("the three kinds survive the binary form")
    func threeKinds() throws {
        for kind in PairingKind.allCases {
            let p = try samplePayload(kind)
            #expect(try PairingCodec.decodeBytes(PairingCodec.encodeBytes(p)) == p)
        }
    }

    @Test("only the invitation carries the key")
    func onlyOfferHasKey() throws {
        let offerPayload = try samplePayload(.offer)
        let answerPayload = try samplePayload(.answer)
        let offer = PairingCodec.encodeBytes(offerPayload)
        let answer = PairingCodec.encodeBytes(answerPayload)
        #expect(offer.count >= answer.count + 32 - 8, "offer \(offer.count) B, answer \(answer.count) B")
        #expect(throws: ToolMapError.self) {
            try PairingPayload(
                kind: .answer,
                createdAtSec: 0,
                groupId: [UInt8](repeating: 0, count: 16),
                groupKey: [UInt8](repeating: 0, count: 32),
                memberId: 1,
                name: "x",
                color: 0,
                iceUfrag: "u",
                icePwd: "p",
                dtlsFingerprint: [UInt8](repeating: 0, count: 32),
                dtlsSetup: .active,
                candidates: []
            )
        }
    }

    @Test("an IPv6 candidate and a long name round trip, the name cut at 24 bytes on a character boundary")
    func ipv6AndLongName() throws {
        let v6 = IceCandidate(.host, (0 ..< 16).map { UInt8(0x20 + $0) }, 9, 0)
        let p = try samplePayload(name: "Élodie-Anne de la Croix-Rousse", candidates: [v6])
        let back = try PairingCodec.decodeBytes(PairingCodec.encodeBytes(p))
        #expect(back.candidates == [v6])
        #expect(back.name.utf8.count <= 24)
        #expect(back.name.hasPrefix("Élodie-Anne"))
    }

    @Test("text and link read back the same payload, the link surviving a messenger")
    func textAndLink() throws {
        let p = try samplePayload()
        #expect(try PairingCodec.parse(PairingCodec.toQrText(p)) == p)
        let link = PairingCodec.toLink(p)
        #expect(link.hasPrefix("toolmap://pair?d="))
        #expect(link.allSatisfy { ($0.isASCII && ($0.isLetter || $0.isNumber)) || ":/?=-_.".contains($0) }, "\(link)")
        #expect(try PairingCodec.parse("  \(link)\n") == p)
        #expect(try PairingCodec.parse(link + "&utm=1") == p)
    }

    /// ZXing is not on iOS: the QR version is checked by the capacity of version 15 at level M in alphanumeric mode,
    /// 600 characters (601 need version 16: measured with ZXing 3.5.4 by the vector program, P2PVectors.swift).
    @Test("the QR text uses only the alphanumeric mode and fits version 15 at level M")
    func fitsQrVersion15() throws {
        let candidates = (0 ..< 5).map { IceCandidate(.host, ipv4Bytes(192, 168, 1, 20 + $0), 50_000 + $0, $0) }
        let p = try samplePayload(candidates: candidates)
        let text = PairingCodec.toQrText(p)
        #expect(text.allSatisfy { Base45.alphabet.contains($0) })
        #expect(text.count <= 600, "\(text.count) characters")
        let size = PairingCodec.encodeBytes(p).count
        #expect(size >= 100 && size <= 330, "\(size) bytes")
    }

    @Test("a code older than ten minutes or from the future is refused")
    func staleness() throws {
        let p = try samplePayload(createdAtSec: 1_000)
        #expect(PairingCodec.staleness(p, 1_000) == nil)
        #expect(PairingCodec.staleness(p, 1_600) == nil)
        #expect(PairingCodec.staleness(p, 1_601) == .expired)
        #expect(PairingCodec.staleness(p, 399) == .fromTheFuture)
    }

    @Test("damaged or foreign codes are refused cleanly")
    func damaged() throws {
        let payload = try samplePayload()
        let good = PairingCodec.encodeBytes(payload)
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes([]) }
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes(Array(good.prefix(good.count / 2))) }
        let badMagic = p2pReplacing(good, 0, 0)
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes(badMagic) }
        let badVersion = p2pReplacing(good, 2, 9)
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes(badVersion) }
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes(good + [1]) }
        #expect(throws: WireError.self) { try PairingCodec.parse("https://example.org") }
        #expect(throws: WireError.self) { try PairingCodec.parse("toolmap://pair?d=@@@") }
        // Random garbage never throws anything but a WireError (nor crashes).
        var generator = SplitMix64(seed: 3)
        for _ in 0 ..< 300 {
            let count = Int.random(in: 0 ..< 120, using: &generator)
            var noise = randomBytes(count, &generator)
            if noise.count > 3 {
                noise[0] = 0x54
                noise[1] = 0x4D
            }
            do {
                _ = try PairingCodec.decodeBytes(noise)
            } catch is WireError {
                // expected
            } catch is ToolMapError {
                // a payload that parses but breaks its own invariants (more than 8 candidates is caught earlier)
            } catch {
                Issue.record("unexpected error \(error)")
            }
        }
        // The same with the deflate flag and a body: the inflater must refuse cleanly too.
        for _ in 0 ..< 300 {
            let count = Int.random(in: 0 ..< 120, using: &generator)
            let noise: [UInt8] = [0x54, 0x4D, 0x83] + randomBytes(count, &generator)
            do {
                _ = try PairingCodec.decodeBytes(noise)
            } catch is WireError {
                // expected
            } catch is ToolMapError {
                // as above
            } catch {
                Issue.record("unexpected error \(error)")
            }
        }
    }

    @Test("a bomb of repeated bytes is not inflated without limit")
    func bomb() {
        let zeros = [UInt8](repeating: 0, count: 2_000_000)
        var buffer = [UInt8](repeating: 0, count: 10_000)
        let capacity = buffer.count
        let n = buffer.withUnsafeMutableBufferPointer { dst -> Int in
            zeros.withUnsafeBufferPointer { src -> Int in
                guard let d = dst.baseAddress, let s = src.baseAddress else { return 0 }
                return compression_encode_buffer(d, capacity, s, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        #expect(n > 0)
        let bomb: [UInt8] = [0x54, 0x4D, UInt8(1 | 0x80)] + Array(buffer.prefix(n))
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes(bomb) }
        // With this version's own number the inflater stops at its limit.
        let current: [UInt8] = [0x54, 0x4D, UInt8(3 | 0x80)] + Array(buffer.prefix(n))
        #expect(throws: WireError.self) { try PairingCodec.decodeBytes(current) }
    }

    @Test("pruning keeps IPv4 first, host before reflexive, and renumbers the ranks")
    func pruning() {
        let v6 = IceCandidate(.host, [UInt8](repeating: 1, count: 16), 1, 0)
        let reflexive = IceCandidate(.srflx, ipv4Bytes(1, 2, 3, 4), 2, 0)
        let host = IceCandidate(.host, ipv4Bytes(10, 0, 0, 1), 3, 0)
        let extras = (0 ..< 8).map { IceCandidate(.host, ipv4Bytes(10, 0, 1, $0), 4, 0) }
        let pruned = PairingCodec.prune([v6, reflexive, host] + extras, limit: 4)
        #expect(pruned.count == 4)
        #expect(pruned.map(\.rank) == [0, 1, 2, 3])
        #expect(pruned[0].port == host.port)
        #expect(pruned.allSatisfy { !$0.isV6 })
        let again = IceCandidate(host.type, host.ip, host.port, 9)
        #expect(PairingCodec.prune([host, host, again, v6, reflexive], limit: 5).count == 3)
    }
}

struct SdpTests {
    @Test("an SDP built from a payload reads back to the same fields")
    func buildAndParse() throws {
        let p = try samplePayload(.answer)
        let sdp = Sdp.build(p)
        #expect(sdp.contains("m=application 9 UDP/DTLS/SCTP webrtc-datachannel"))
        #expect(sdp.contains("a=setup:active"))
        #expect(sdp.contains("a=sctp-port:5000"))
        #expect(sdp.hasSuffix("\r\n"))
        let info = try Sdp.parse(sdp)
        #expect(info.iceUfrag == p.iceUfrag)
        #expect(info.icePwd == p.icePwd)
        #expect(info.dtlsFingerprint == p.dtlsFingerprint)
        #expect(info.dtlsSetup == p.dtlsSetup)
        // The best candidates come first: hosts before the server reflexive one.
        let found = info.candidates.map { "\($0.type.sdp) \($0.address) \($0.port)" }
        #expect(found == ["host 192.168.1.23 51234", "host 10.0.0.5 52000", "srflx 203.0.113.7 40001"])
    }

    @Test("priorities rebuilt from the rank keep the order of the candidates")
    func priorities() throws {
        let lines = try samplePayload().candidates.enumerated().map { Sdp.candidateLine($0.element, $0.offset) }
        let priorities = lines.map { Int64(kotlinSplit($0, " ")[3]) ?? -1 }
        #expect(priorities[0] > priorities[2], "two hosts: the better rank first")
        #expect(priorities[0] > priorities[1], "host before server reflexive")
        #expect(lines[1].contains("typ srflx raddr 0.0.0.0 rport 0"))
    }

    @Test("the SDP of a real data channel offer gives only what we can use")
    func realOffer() throws {
        let fingerprint = (0 ..< 32).map { String(format: "%02X", $0 * 3) }.joined(separator: ":")
        let sdp = """
        v=0
        o=- 1234567890 2 IN IP4 127.0.0.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        a=extmap-allow-mixed
        a=msid-semantic: WMS
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        c=IN IP4 0.0.0.0
        a=candidate:1 1 udp 2122260223 192.168.43.1 40000 typ host generation 0 network-id 1
        a=candidate:2 1 udp 2122194687 10.20.0.5 40001 typ host generation 0 network-id 2
        a=candidate:3 1 tcp 1518280447 192.168.43.1 9 typ host tcptype active generation 0
        a=candidate:4 1 udp 2122129151 fe80::1 40002 typ host generation 0
        a=candidate:5 1 udp 1686052607 90.12.34.56 40003 typ srflx raddr 192.168.43.1 rport 40000 generation 0
        a=candidate:6 1 udp 41885439 51.0.0.1 3478 typ relay raddr 90.12.34.56 rport 40003 generation 0
        a=candidate:7 1 udp 2122260223 5d7f2a9c-1b3e-4c8d-9f10-aabbccddeeff.local 40004 typ host generation 0
        a=candidate:8 1 udp 2122262783 2a01:cb00:1234::5 40005 typ host generation 0
        a=ice-ufrag:Zq1r
        a=ice-pwd:0123456789abcdefghijklmn
        a=ice-options:trickle
        a=fingerprint:sha-256 \(fingerprint)
        a=setup:actpass
        a=mid:0
        a=sctp-port:5000
        a=max-message-size:262144
        """.replacingOccurrences(of: "\n", with: "\r\n")
        let info = try Sdp.parse(sdp)
        #expect(info.iceUfrag == "Zq1r")
        #expect(info.dtlsSetup == .actpass)
        #expect(info.dtlsFingerprint.count == 32)
        #expect(info.dtlsFingerprint[0] == 0)
        #expect(info.dtlsFingerprint[1] == 3)
        let found = info.candidates.map { "\($0.address) \($0.type.sdp)" }
        #expect(found == ["192.168.43.1 host", "10.20.0.5 host", "90.12.34.56 srflx", "2a01:cb00:1234:0:0:0:0:5 host"])
        #expect(info.candidates.map(\.rank) == [0, 1, 2, 3])
    }

    @Test("an SDP missing a mandatory field is refused")
    func missingField() {
        #expect(throws: WireError.self) { try Sdp.parse("v=0\r\na=ice-ufrag:x\r\n") }
        #expect(throws: WireError.self) { try Sdp.parse("") }
        #expect(Sdp.parseCandidate("a=candidate:1 1 udp 1 not-an-ip 5 typ host") == nil)
        #expect(Sdp.parseCandidate("candidate:1 1 UDP 1 1.2.3.4 5 typ host") != nil)
        #expect(Sdp.parseCandidate("a=candidate:1 1 udp 1 0.0.0.0 5 typ host") == nil)
    }
}

struct GroupCryptoTests {
    private func hex(_ s: String) -> [UInt8] {
        P2PHex.decode(s) ?? []
    }

    @Test("HKDF-SHA256 gives the vector 1 of RFC 5869")
    func hkdfVector1() throws {
        let okm = try GroupCrypto.hkdfSha256(
            hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42
        )
        #expect(okm == hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"))
    }

    @Test("HKDF with no salt gives the vector 3 of RFC 5869")
    func hkdfVector3() throws {
        let okm = try GroupCrypto.hkdfSha256(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), [], [], 42)
        #expect(okm == hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"))
    }

    @Test("sealed bytes open with the same key and aad only")
    func sealAndOpen() throws {
        let key = GroupCrypto.randomKey()
        let a = try GroupCrypto(key)
        let b = try GroupCrypto(key)
        let sealed = a.seal(Array("position".utf8), [4])
        #expect(sealed.count == 12 + 8 + 16)
        #expect(try b.open(sealed, [4]) == Array("position".utf8))
        #expect(throws: WireError.self) { try b.open(sealed, [5]) }
        let tampered = p2pReplacing(sealed, 20, sealed[20] &+ 1)
        #expect(throws: WireError.self) { try b.open(tampered, [4]) }
        let stranger = try GroupCrypto(GroupCrypto.randomKey())
        #expect(throws: WireError.self) { try stranger.open(sealed, [4]) }
        #expect(throws: WireError.self) { try b.open([UInt8](repeating: 0, count: 10), [4]) }
    }

    @Test("two seals of the same text differ and a re-key keeps the previous key for what is in flight")
    func rekeyKeepsPrevious() throws {
        let old = GroupCrypto.randomKey()
        let crypto = try GroupCrypto(old)
        let one = crypto.seal([0, 0, 0, 0], [1])
        #expect(one != crypto.seal([0, 0, 0, 0], [1]))
        let replacement = GroupCrypto.randomKey()
        try crypto.rekey(replacement)
        _ = try crypto.open(one, [1])
        let other = try GroupCrypto(replacement)
        #expect(try other.open(crypto.seal([0, 0, 0, 0], [1]), [1]) == [0, 0, 0, 0])
        try crypto.rekey(GroupCrypto.randomKey())
        #expect(throws: WireError.self) { try crypto.open(one, [1]) }
    }
}
