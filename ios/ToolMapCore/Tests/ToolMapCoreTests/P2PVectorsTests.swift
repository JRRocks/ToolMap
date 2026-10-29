// Interoperability with Android (docs/plan-ios.md §3, point 4): the values of P2PVectors.swift were produced by the
// Kotlin code of :core (scratch build, Vectors.kt) from the inputs rebuilt here. The Swift code must read what Android
// wrote and, with the same nonces, write the very same bytes: frames, invitations, saved state, event lines.

import Foundation
import Testing
@testable import ToolMapCore

/// The nonces of the vectors: call k fills byte j with (k * 31 + j * 7 + 1) mod 256, as Vectors.kt does.
private final class FixedRandom: SecureRandomSource, @unchecked Sendable {
    private let lock = NSLock()
    private var call = 0

    func nextBytes(_ count: Int) -> [UInt8] {
        lock.lock()
        defer { lock.unlock() }
        let k = call
        call += 1
        return (0 ..< count).map { UInt8(truncatingIfNeeded: k * 31 + $0 * 7 + 1) }
    }
}

/// Always the same bytes: to seal again a decoded frame with its own nonce.
private struct NonceRandom: SecureRandomSource {
    let nonce: [UInt8]

    func nextBytes(_ count: Int) -> [UInt8] { Array(nonce.prefix(count)) }
}

private func hexBytes(_ hex: String) -> [UInt8] { P2PHex.decode(hex) ?? [] }

private func ipv4Bytes(_ b: Int...) -> [UInt8] { b.map { UInt8(truncatingIfNeeded: $0) } }

private func samplePayload(_ kind: PairingKind, name: String = "Jean", candidates: [IceCandidate]? = nil) throws -> PairingPayload {
    try PairingPayload(
        kind: kind,
        createdAtSec: 1_790_000_000,
        groupId: (0 ..< 16).map { UInt8($0 + 1) },
        groupKey: kind == .offer ? (0 ..< 32).map { UInt8(100 + $0) } : nil,
        memberId: 0x1122_3344_5566_7788,
        name: name,
        color: 3,
        iceUfrag: "aB3d",
        icePwd: "x7Yq0pLm9Rt2VnB4cD6eF8gH",
        dtlsFingerprint: (0 ..< 32).map { UInt8(truncatingIfNeeded: $0 * 7) },
        dtlsSetup: kind == .answer ? .active : .actpass,
        candidates: candidates ?? [
            IceCandidate(.host, ipv4Bytes(192, 168, 1, 23), 51_234, 0),
            IceCandidate(.srflx, ipv4Bytes(203, 0, 113, 7), 40_001, 1),
            IceCandidate(.host, ipv4Bytes(10, 0, 0, 5), 52_000, 2),
        ]
    )
}

struct P2PVectorsTests {
    private let fix = PeerFix(
        45.123456789,
        5.987654321,
        altM: 1234,
        accuracyM: 8,
        speedMs: 1.37,
        bearingDeg: 271.4,
        timeSec: 1_790_000_123,
        battery: 64
    )
    private let bare = PeerFix(-33.8, -70.6, timeSec: 5)
    private let edges = PeerFix(
        0.00000005,
        -0.00000005,
        altM: -420,
        accuracyM: 70_000,
        speedMs: 0.005,
        bearingDeg: 359.96,
        timeSec: 0,
        battery: 150
    )
    private let clamps = PeerFix(
        -90.0,
        180.0,
        altM: 40_000,
        accuracyM: -3,
        speedMs: 655.36,
        bearingDeg: -90.05,
        timeSec: 0xFFFF_FFFF,
        battery: -1
    )
    private let low = PeerFix(12.3456789, -0.000000149999, altM: -40_000, speedMs: -1.0, bearingDeg: 720.04, timeSec: 7)

    /// The messages of Vectors.kt, in the same order (each one takes the next nonce).
    private func messages() throws -> [(Msg, Int)] {
        // The pairing code inside the SIGNAL is Android's own (both sides may compress it differently).
        let answerCode = hexBytes(P2PVectors.pairing[1].1)
        return [
            (.hello(Msg.Hello(0x1122_3344_5566_7788, "Jean", MemberLook.pack(3, Int32(bitPattern: 0xFF12_AB34)))), 4),
            (.hello(Msg.Hello(-2, "Élodie-Anne de la Croix-Rousse", Int32(bitPattern: 0xFFD3_2F2F))), 4),
            (.peers(Msg.Peers([PeerInfo(1, "A", 0, 12), PeerInfo(Int64.min, "Éva", -1, 3600)])), 4),
            (.pos(Msg.Pos(7, 42, fix)), 4),
            (.pos(Msg.Pos(-1, 0xFFFF_FFFF, bare)), 4),
            (.pos(Msg.Pos(3, 1, edges)), 4),
            (.pos(Msg.Pos(3, 2, clamps)), 4),
            (.pos(Msg.Pos(3, 3, low)), 4),
            (.trail(Msg.Trail(3, [fix, bare, edges])), 4),
            (.ping(Msg.Ping(3, 1_790_000_000)), 4),
            (.text(Msg.Text(3, 5, "On se retrouve au refuge à 14 h ☀")), 4),
            (.text(Msg.Text(4, 6, String(repeating: "x", count: 300))), 4),
            (.text(Msg.Text(4, 7, "a" + String(repeating: "\u{1F600}", count: 80))), 4),
            (.sos(Msg.Sos(3, 6, fix, "Cheville cassée")), 4),
            (.sos(Msg.Sos(3, 7, nil, "")), 4),
            (.sosEnd(Msg.SosEnd(0x1234_5678_9ABC, 0xFFFF_FFFF)), 4),
            (.state(Msg.State(3, 9, sharing: false)), 4),
            (.state(Msg.State(3, 10, sharing: true)), 4),
            (.rekey(Msg.Rekey(1, 9, 5, (0 ..< 32).map { UInt8(200 + $0) })), 4),
            (.bye(Msg.Bye(8)), 4),
            (.kick(Msg.Kick(6)), 4),
            (.avatar(Msg.Avatar(5, 99, (0 ..< 300).map { UInt8(truncatingIfNeeded: $0 * 7) })), 4),
            (.avatar(Msg.Avatar(5, 100, [])), 4),
            (.avatar(Msg.Avatar(5, 101, [UInt8](repeating: 0, count: MemberLook.maxPictureBytes + 1))), 4),
            (.diag(Msg.Diag(0x1234_5678_9ABC, 77, (0 ..< 100).map { UInt8(truncatingIfNeeded: $0 * 31) })), 4),
            (.signal(Msg.Signal(77, 1, 2, answerCode)), 4),
            (.text(Msg.Text(1, 1, "hi")), 3),
            (.text(Msg.Text(1, 2, "hi")), 300),
        ]
    }

    @Test("the key of the group is derived as on Android")
    func derivedKey() throws {
        let derived = try GroupCrypto.hkdfSha256(hexBytes(P2PVectors.groupKey), [], Array("toolmap-p2p-v1".utf8), 32)
        #expect(P2PHex.encode(derived) == P2PVectors.derivedKey)
    }

    @Test("with the same nonces, every frame is byte for byte the one Android seals")
    func framesAsAndroid() throws {
        let crypto = try GroupCrypto(hexBytes(P2PVectors.groupKey), random: FixedRandom())
        let list = try messages()
        #expect(list.count == P2PVectors.frames.count)
        for ((msg, ttl), (name, hex)) in zip(list, P2PVectors.frames) {
            let frame = try Wire.encode(msg, crypto, ttl: ttl)
            #expect(P2PHex.encode(frame) == hex, "\(name)")
        }
        // After a re-key, sealed with the new key.
        try crypto.rekey((0 ..< 32).map { UInt8($0 + 50) })
        let rekeyed = try Wire.encode(.text(Msg.Text(1, 3, "nouvelle clé")), crypto)
        #expect(P2PHex.encode(rekeyed) == P2PVectors.rekeyedText)
        #expect(P2PVectors.frames.prefix(3).map { Wire.frameId(hexBytes($0.1)) } == P2PVectors.frameIds)
    }

    @Test("every frame of Android opens, reads as sent and seals back to the same bytes")
    func framesFromAndroid() throws {
        let key = hexBytes(P2PVectors.groupKey)
        let reader = try GroupCrypto(key)
        let list = try messages()
        for ((sent, _), (name, hex)) in zip(list, P2PVectors.frames) {
            let frame = hexBytes(hex)
            let decoded = try Wire.decode(frame, reader)
            let again = try GroupCrypto(key, random: NonceRandom(nonce: Array(frame[2 ..< 14])))
            let resealed = try Wire.encode(decoded.msg, again, ttl: decoded.ttl)
            #expect(P2PHex.encode(resealed) == hex, "\(name)")
            #expect(decoded.msg.type == sent.type, "\(name)")
        }
        let pos = try #require(try Wire.decode(hexBytes(P2PVectors.frames[3].1), reader).msg.value(Msg.Pos.self))
        #expect(pos.memberId == 7)
        #expect(pos.seq == 42)
        #expect(pos.fix.lat == 45.1234568)
        #expect(pos.fix.lon == 5.9876543)
        #expect(pos.fix.altM == 1234)
        #expect(pos.fix.speedMs == 1.37)
        #expect(pos.fix.bearingDeg == 271.4)
        #expect(pos.fix.battery == 64)
        let split = try #require(try Wire.decode(hexBytes(P2PVectors.frames[12].1), reader).msg.value(Msg.Text.self))
        // Android's take(140) cut the last emoji in two; the JVM wrote its first half as "?".
        #expect(split.text == "a" + String(repeating: "\u{1F600}", count: 69) + "?")
        let peers = try #require(try Wire.decode(hexBytes(P2PVectors.frames[2].1), reader).msg.value(Msg.Peers.self))
        #expect(peers.peers.map(\.memberId) == [1, Int64.min])
        #expect(peers.peers[1].color == -1)
        // The frame sealed after the re-key opens with both keys, the old crypto knowing the new one.
        let renewed = try GroupCrypto(key)
        try renewed.rekey((0 ..< 32).map { UInt8($0 + 50) })
        let text = try #require(try Wire.decode(hexBytes(P2PVectors.rekeyedText), renewed).msg.value(Msg.Text.self))
        #expect(text.text == "nouvelle clé")
        #expect(throws: WireError.self) { try Wire.decode(hexBytes(P2PVectors.rekeyedText), reader) }
    }

    @Test("pairing codes of Android read back, compressed or not, and the binary body is the same")
    func pairingCodes() throws {
        let v6 = IceCandidate(.host, (0 ..< 16).map { UInt8(0x20 + $0) }, 9, 0)
        let packed = try PairingPayload(
            kind: .meshOffer,
            createdAtSec: 1_790_000_000,
            groupId: [UInt8](repeating: 0, count: 16),
            groupKey: nil,
            memberId: 7,
            name: String(repeating: "a", count: 24),
            color: 0,
            iceUfrag: String(repeating: "u", count: 60),
            icePwd: String(repeating: "p", count: 60),
            dtlsFingerprint: [UInt8](repeating: 0, count: 32),
            dtlsSetup: .passive,
            candidates: (0 ..< 8).map { IceCandidate(.host, ipv4Bytes(10, 0, 0, 1), 50_000, $0) }
        )
        let offer = try samplePayload(.offer)
        let answer = try samplePayload(.answer)
        let mesh = try samplePayload(.meshOffer)
        let long = try samplePayload(.offer, name: "Élodie-Anne de la Croix-Rousse", candidates: [v6])
        let none = try samplePayload(.answer, candidates: [])
        let payloads: [String: PairingPayload] = [
            "offer": offer,
            "answer": answer,
            "mesh": mesh,
            "v6": long,
            "none": none,
            "packed": packed,
        ]
        #expect(P2PVectors.pairing.count == payloads.count)
        for (name, code, body, qr, link) in P2PVectors.pairing {
            let p = try #require(payloads[name])
            #expect(P2PHex.encode(PairingCodec.bodyBytes(p)) == body, "\(name)")
            let fromAndroid = try PairingCodec.decodeBytes(hexBytes(code))
            #expect(try PairingPayload.decodedName(p) == fromAndroid, "\(name)")
            #expect(try PairingCodec.parse(qr) == fromAndroid, "\(name)")
            #expect(try PairingCodec.parse(link) == fromAndroid, "\(name)")
            let mine = PairingCodec.encodeBytes(p)
            #expect(try PairingCodec.decodeBytes(mine) == fromAndroid, "\(name)")
            if mine[2] & 0x80 == 0, hexBytes(code)[2] & 0x80 == 0 {
                #expect(P2PHex.encode(mine) == code, "\(name)")
                #expect(PairingCodec.toQrText(p) == qr, "\(name)")
                #expect(PairingCodec.toLink(p) == link, "\(name)")
            }
        }
        #expect(hexBytes(P2PVectors.pairing[5].1)[2] == 0x83, "the packed code is deflated by Android")
        let inflated = try PairingCodec.inflate(hexBytes(P2PVectors.deflatedSample))
        #expect(String(decoding: inflated, as: UTF8.self) == P2PVectors.deflatedText)
        // And what this side deflates comes back the same.
        let sample = Array(P2PVectors.deflatedText.utf8)
        let deflated = try #require(PairingCodec.deflate(sample))
        #expect(try PairingCodec.inflate(deflated) == sample)
    }

    @Test("the SDP rebuilt from a code is the one Android builds")
    func sdp() throws {
        let answer = try samplePayload(.answer)
        #expect(Sdp.build(answer) == P2PVectors.sdpAnswer)
        let v6 = IceCandidate(.host, (0 ..< 16).map { UInt8(0x20 + $0) }, 9, 0)
        let p = try samplePayload(.offer, name: "Élodie-Anne de la Croix-Rousse", candidates: [v6])
        #expect(Sdp.build(p, sessionId: 42) == P2PVectors.sdpV6)
    }

    @Test("server invitations are written and read as on Android")
    func invitations() throws {
        let server = RelayConfig.of("https://toolmap-1234-default-rtdb.europe-west1.firebasedatabase.app/", " fake-key ")
        let first = try RoomInvite(1_800_000_000, (0 ..< 16).map { UInt8($0) }, (0 ..< 32).map { UInt8($0 * 3) }, 0x1234_ABCD, "Jean", server)
        let second = try RoomInvite(
            1_800_000_001,
            (0 ..< 16).map { UInt8(255 - $0) },
            (0 ..< 32).map { UInt8($0 * 5 + 1) },
            -0x1234_ABCD,
            "Élodie-Anne de la Croix-Rousse",
            server
        )
        let invites = [first, second]
        for (invite, vector) in zip(invites, P2PVectors.invites) {
            #expect(P2PHex.encode(RoomInviteCodec.encodeBytes(invite)) == vector.0)
            #expect(RoomInviteCodec.toQrText(invite) == vector.1)
            #expect(RoomInviteCodec.toLink(invite) == vector.2)
            let back = try #require(try RoomInviteCodec.parseOrNull(vector.2))
            #expect(back.inviterId == invite.inviterId)
            #expect(back.groupKey == invite.groupKey)
            #expect(back.server == RelayConfig(server.base, server.apiKey))
        }
        for (id, key) in P2PVectors.memberKeys {
            #expect(RelayPaths.member(id) == key)
            #expect(RelayPaths.memberOf(key) == id)
        }
    }

    @Test("the saved state is the same JSON text as on Android, and is read with the same rules")
    func savedState() throws {
        let group = GroupRecord(
            (0 ..< 16).map { UInt8(truncatingIfNeeded: $0 * 9) },
            (0 ..< 32).map { UInt8(truncatingIfNeeded: $0 * 11) },
            -0x5EAD_BEEF,
            server: RelayConfig.of("https://a.firebaseio.com", "key-1")
        )
        let states = [
            ShareState(),
            ShareState(settings: ShareSettings(name: "Jean", colorIndex: 3, intervalSec: 60, minMoveMeters: 25, stun: false), group: group),
            ShareState(
                settings: ShareSettings(
                    name: "Jo \"le\" \\ Fou\n\u{1}\u{8}\u{C}",
                    server: RelayConfig.of("https://b.firebaseio.com/", "k"),
                    icon: 4,
                    color: Int32(bitPattern: 0xFF12_3456),
                    showTrails: false
                ),
                group: GroupRecord([UInt8](repeating: 0, count: 16), [UInt8](repeating: 0, count: 32), Int64.min)
            ),
            ShareState(settings: ShareSettings(icon: 3).withPicture([9, 8, 7, 6])),
        ]
        #expect(states.count == P2PVectors.states.count)
        for (state, json) in zip(states, P2PVectors.states) {
            #expect(state.toJson() == json)
            #expect(ShareState.fromJson(json).toJson() == json)
        }
        for (input, kept) in P2PVectors.readStates {
            #expect(ShareState.fromJson(input).toJson() == kept, "\(input)")
        }
    }

    @Test("event lines are written as on Android, in any time zone")
    func eventLines() throws {
        let sum: Double = 0.1 + 0.2
        let events = [
            ToolMapCore.Event(1_790_000_000_123, "relay.write", [("ch", "p"), ("ms", 85), ("why", "socket closed"), ("lat", 45.123456789), ("n", nil)]),
            ToolMapCore.Event(1_700_000_000_000, "test.values", [
                ("a", sum), ("b", 1.0), ("c", 100.0), ("d", 1e-8), ("e", Float(1.1)), ("f", -0.0), ("g", 1e12),
                ("h", Double.nan), ("i", -12.50000004), ("j", 123_456.7), ("k", 0.00000015), ("l", -Double.infinity),
                ("m", 99_999_999_999.99), ("n", 5e-8), ("o", -5e-8), ("p", 2.5e-7),
            ]),
            ToolMapCore.Event(-1, "test.text", [("t", "a \"b\" c\\d=e\nf\rg"), ("e", ""), ("u", "é☀"), ("l", Int64.min), ("b", true)]),
            ToolMapCore.Event(1_711_846_800_000, "dst.start", []),
        ]
        var lines: [String] = []
        for zoneName in ["UTC", "Europe/Paris", "America/St_Johns"] {
            let zone = try #require(TimeZone(identifier: zoneName))
            for (i, e) in events.enumerated() {
                lines.append(EventLine.format(Int64(i + 1), e, zone))
            }
        }
        #expect(lines.count == P2PVectors.eventLines.count)
        for (mine, android) in zip(lines, P2PVectors.eventLines) {
            #expect(mine == android)
        }
    }

    @Test("the map features, image names, initials, colours and Base45 texts are those of Android")
    func mapAndLooks() throws {
        let now: Int64 = 1_790_000_000_000
        let f1 = PeerFix(45.1234567, 6.7654321, timeSec: now / 1000)
        let f2 = PeerFix(-12.00000005, -0.5, timeSec: now / 1000 - 700)
        let members = [
            MemberView(1, "Moi", 0, .direct, true, now, f1, [f1, f2]),
            MemberView(2, "Alice \"A\"", MemberLook.pack(2, Int32(bitPattern: 0xFFAA_0000)), .direct, false, now, f1, [f2, f1]),
            MemberView(
                Int64.max - 5,
                "Bob",
                MemberLook.pack(MemberLook.picture, Int32(bitPattern: 0xFF00_FF00)),
                .lost,
                false,
                now,
                f2,
                [f1],
                picture: [1, 2, 3, 0xFC]
            ),
            MemberView(-7, "Zoé Martin", Int32(bitPattern: 0xFFD3_2F2F), .relayed, false, now, f2, [f1, f2, f1], sharing: false),
            MemberView(9, "Écoute", 0, .left, false, now, f1, [f1, f2], sharing: false),
        ]
        #expect(ShareGeoJson.members(members, [-7], now) == P2PVectors.geoMembers)
        #expect(ShareGeoJson.trails(members) == P2PVectors.geoTrails)
        let pictures: [[UInt8]] = [[], [1], (0 ..< 3000).map { UInt8(truncatingIfNeeded: $0 * 13) }]
        let look = MemberLook.pack(MemberLook.picture, 0x123456)
        #expect(pictures.map { MemberLook.imageKey(look, "x", $0) } == P2PVectors.imageKeys)
        for (name, letters) in P2PVectors.initials {
            #expect(MemberPalette.initials(name) == letters, "\(name)")
        }
        for (argb, hue, saturation, value, turned) in P2PVectors.hsv {
            let hsv = ColorWheel.toHsv(argb)
            #expect(hsv == [hue, saturation, value])
            #expect(ColorWheel.fromHsv(hue + 17.3, saturation * 0.7, value) == turned)
        }
        for (hex, text) in P2PVectors.base45 {
            #expect(Base45.encode(hexBytes(hex)) == text)
            #expect(try Base45.decode(text) == hexBytes(hex))
        }
    }
}

private extension PairingPayload {
    /// `p` as a code gives it back: the name cut at 24 bytes like the binary form does.
    static func decodedName(_ p: PairingPayload) throws -> PairingPayload {
        try PairingCodec.decodeBytes(PairingCodec.encodeBytes(p))
    }
}
