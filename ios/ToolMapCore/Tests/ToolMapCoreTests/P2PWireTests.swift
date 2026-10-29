// Port of core/src/test/kotlin/fr/toolmap/core/p2p/WireTest.kt: messages, frames, cadence.

import Foundation
import Testing
@testable import ToolMapCore

struct WireTests {
    private let crypto: GroupCrypto
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

    init() throws {
        crypto = try GroupCrypto(GroupCrypto.randomKey())
    }

    private func roundTrip(_ msg: Msg) throws -> Frame {
        let frame = try Wire.encode(msg, crypto)
        return try Wire.decode(frame, crypto)
    }

    /// The values of the message of `frame` when it is a `T`.
    private func message<T>(_ msg: Msg, _ type: T.Type) throws -> T {
        let frame = try roundTrip(msg)
        return try #require(frame.msg.value(type))
    }

    @Test("a position keeps its values to the precision of the wire")
    func positionPrecision() throws {
        let back = try message(.pos(Msg.Pos(7, 42, fix)), Msg.Pos.self)
        #expect(back.memberId == 7)
        #expect(back.seq == 42)
        #expect(abs(back.fix.lat - 45.1234568) <= 1e-7)
        #expect(abs(back.fix.lon - 5.9876543) <= 1e-7)
        #expect(back.fix.altM == 1234)
        #expect(back.fix.accuracyM == 8)
        let speed = try #require(back.fix.speedMs)
        #expect(abs(speed - 1.37) <= 0.006)
        let bearing = try #require(back.fix.bearingDeg)
        #expect(abs(bearing - 271.4) <= 0.06)
        #expect(back.fix.timeSec == 1_790_000_123)
        #expect(back.fix.battery == 64)
    }

    @Test("unknown values stay unknown, negative altitudes and the west and south keep their sign")
    func unknownValues() throws {
        let bare = try message(.pos(Msg.Pos(1, 1, PeerFix(-33.8, -70.6, timeSec: 5))), Msg.Pos.self).fix
        #expect(abs(bare.lat - -33.8) <= 1e-7)
        #expect(abs(bare.lon - -70.6) <= 1e-7)
        #expect(bare.altM == nil)
        #expect(bare.accuracyM == nil)
        #expect(bare.speedMs == nil)
        #expect(bare.bearingDeg == nil)
        #expect(bare.battery == nil)
        let low = try message(.pos(Msg.Pos(1, 1, PeerFix(0.0, 0.0, altM: -420, timeSec: 5))), Msg.Pos.self)
        #expect(low.fix.altM == -420)
        // A bearing of 359.96° rounds to 360.0 and must not be written as an impossible value.
        let north = try message(.pos(Msg.Pos(1, 1, PeerFix(0.0, 0.0, bearingDeg: 359.96, timeSec: 5))), Msg.Pos.self)
        let bearing = try #require(north.fix.bearingDeg)
        #expect(abs(bearing - 0.0) <= 0.01)
    }

    @Test("a position frame is about fifty bytes")
    func positionSize() throws {
        let size = try Wire.encode(.pos(Msg.Pos(7, 42, fix)), crypto).count
        #expect(size >= 50 && size <= 70, "\(size) bytes")
        #expect(PeerFix.bytes == 21)
    }

    @Test("every kind of message round trips")
    func everyKind() throws {
        let hello = try message(.hello(Msg.Hello(9, "Camille", 4)), Msg.Hello.self)
        #expect(hello.name == "Camille")
        #expect(hello.color == 4)
        #expect(hello.protocolVersion == Msg.protocolVersion)

        let peers = try message(.peers(Msg.Peers([PeerInfo(1, "A", 0, 12), PeerInfo(2, "Éva", 5, 3600)])), Msg.Peers.self)
        #expect(peers.peers.map(\.memberId) == [1, 2])
        #expect(peers.peers[1].name == "Éva")
        #expect(peers.peers[1].lastSeenAgoSec == 3600)

        // The whole packed look travels: icon and RGB colour, not just a palette byte.
        let look = MemberLook.pack(3, Int32(bitPattern: 0xFF12_AB34))
        #expect(try message(.hello(Msg.Hello(9, "C", look)), Msg.Hello.self).color == look)
        #expect(try message(.peers(Msg.Peers([PeerInfo(1, "A", look, 1)])), Msg.Peers.self).peers[0].color == look)
        let picture = (0 ..< 3000).map { UInt8(truncatingIfNeeded: $0 * 7) }
        let avatar = try message(.avatar(Msg.Avatar(5, 99, picture)), Msg.Avatar.self)
        #expect(avatar.memberId == 5)
        #expect(avatar.seq == 99)
        #expect(avatar.image == picture)
        #expect(try message(.avatar(Msg.Avatar(5, 100, [])), Msg.Avatar.self).image.isEmpty)
        // A picture over the limit is not sent as is.
        let big = [UInt8](repeating: 0, count: MemberLook.maxPictureBytes + 1)
        #expect(try message(.avatar(Msg.Avatar(5, 101, big)), Msg.Avatar.self).image.isEmpty)
        let legacy = Int32(bitPattern: 0xFFD3_2F2F)
        #expect(try message(.hello(Msg.Hello(9, "C", legacy)), Msg.Hello.self).color == legacy)

        let payload = (0 ..< 300).map { UInt8(truncatingIfNeeded: $0) }
        let signal = try message(.signal(Msg.Signal(77, 1, 2, payload)), Msg.Signal.self)
        #expect(signal.seq == 77)
        #expect(signal.from == 1)
        #expect(signal.to == 2)
        #expect(signal.payload == payload)

        let trail = try message(.trail(Msg.Trail(3, [fix, fix])), Msg.Trail.self)
        #expect(trail.fixes.count == 2)

        let ping = try message(.ping(Msg.Ping(3, 1_790_000_000)), Msg.Ping.self)
        #expect(ping.timeSec == 1_790_000_000)

        let text = try message(.text(Msg.Text(3, 5, "On se retrouve au refuge à 14 h ☀")), Msg.Text.self)
        #expect(text.text == "On se retrouve au refuge à 14 h ☀")

        let sos = try message(.sos(Msg.Sos(3, 6, fix, "Cheville cassée")), Msg.Sos.self)
        #expect(sos.text == "Cheville cassée")
        #expect(sos.fix != nil)
        #expect(try message(.sos(Msg.Sos(3, 7, nil, "")), Msg.Sos.self).fix == nil)

        let end = try message(.sosEnd(Msg.SosEnd(3, 8)), Msg.SosEnd.self)
        #expect(end.memberId == 3)
        #expect(end.seq == 8)

        let listening = try message(.state(Msg.State(3, 9, sharing: false)), Msg.State.self)
        #expect(listening.memberId == 3)
        #expect(listening.seq == 9)
        #expect(listening.sharing == false)
        #expect(try message(.state(Msg.State(3, 10, sharing: true)), Msg.State.self).sharing == true)

        let newKey = GroupCrypto.randomKey()
        let rekey = try message(.rekey(Msg.Rekey(1, 9, 5, newKey)), Msg.Rekey.self)
        #expect(rekey.excluded == 5)
        #expect(rekey.key == newKey)

        #expect(try message(.bye(Msg.Bye(8)), Msg.Bye.self).memberId == 8)
        #expect(try message(.kick(Msg.Kick(6)), Msg.Kick.self).target == 6)
    }

    @Test("a diagnostic payload round trips and its largest frame fits the server limit")
    func diagnostic() throws {
        let body = (0 ..< Msg.Diag.maxBytes).map { UInt8(truncatingIfNeeded: $0 * 31) }
        let diag = try message(.diag(Msg.Diag(0x1234_5678_9ABC, 77, body)), Msg.Diag.self)
        #expect(diag.memberId == 0x1234_5678_9ABC)
        #expect(diag.seq == 77)
        #expect(diag.body == body)
        let frame = try Wire.encode(.diag(Msg.Diag(1, 1, body)), crypto)
        #expect(Wire.typeOf(frame) == .diag)
        #expect(Wire.frameId(frame).count == 8)
        // firebase/database.rules.json: a frame of the reliable channel is at most 6000 Base64 characters.
        #expect(Data(frame).base64EncodedString().count <= 6000)
        #expect(throws: ToolMapError.self) {
            try Wire.encode(.diag(Msg.Diag(1, 2, [UInt8](repeating: 0, count: Msg.Diag.maxBytes + 1))), crypto)
        }
        #expect(!Msg.diag(Msg.Diag(1, 1, body)).onPositionChannel)
    }

    @Test("the lift of an alert has a type of its own after the last one older versions know")
    func liftType() throws {
        // Versions up to 0.4.113 know the types 1 to 13: they drop a frame of type 14 as unknown (MeshNodeTests).
        #expect(MsgType.sosEnd.code == 14)
        #expect(MsgType.state.code == 15)
        #expect(MsgType.of(14) == .sosEnd)
        #expect(MsgType.sos.code == 8)
        let frame = try Wire.encode(.sosEnd(Msg.SosEnd(0x1234_5678_9ABC, 0xFFFF_FFFF)), crypto)
        #expect(Wire.typeOf(frame) == .sosEnd)
        let back = try #require(try Wire.decode(frame, crypto).msg.value(Msg.SosEnd.self))
        #expect(back.memberId == 0x1234_5678_9ABC)
        #expect(back.seq == 0xFFFF_FFFF)
        // A lift and an alert are not interchangeable: the type is authenticated.
        let other = p2pReplacing(frame, 0, UInt8(MsgType.sos.code))
        #expect(throws: WireError.self) { try Wire.decode(other, crypto) }
    }

    @Test("texts are cut at 140 characters and names at 24 bytes")
    func cuts() throws {
        let long = String(repeating: "x", count: 300)
        #expect(try message(.text(Msg.Text(1, 1, long)), Msg.Text.self).text.utf16.count == 140)
        let name = try message(.hello(Msg.Hello(1, String(repeating: "é", count: 40), 0)), Msg.Hello.self).name
        #expect(name.utf8.count <= 24)
    }

    @Test("positions and pings go on the unordered channel, the rest on the reliable one")
    func channels() {
        #expect(Msg.pos(Msg.Pos(1, 1, fix)).onPositionChannel)
        #expect(Msg.ping(Msg.Ping(1, 1)).onPositionChannel)
        #expect(!Msg.text(Msg.Text(1, 1, "")).onPositionChannel)
        #expect(!Msg.sos(Msg.Sos(1, 1, nil, "")).onPositionChannel)
        #expect(!Msg.sosEnd(Msg.SosEnd(1, 1)).onPositionChannel)
        #expect(!Msg.state(Msg.State(1, 1, sharing: true)).onPositionChannel)
        #expect(!Msg.signal(Msg.Signal(1, 1, 2, [])).onPositionChannel)
    }

    @Test("a relay lowers the time to live without touching the ciphertext and stops at the last hop")
    func relayTtl() throws {
        let frame = try Wire.encode(.text(Msg.Text(1, 1, "hi")), crypto, ttl: 3)
        let once = try #require(Wire.relayed(frame))
        let twice = try #require(Wire.relayed(once))
        #expect(try Wire.decode(frame, crypto).ttl == 3)
        #expect(try Wire.decode(once, crypto).ttl == 2)
        #expect(try Wire.decode(twice, crypto).ttl == 1)
        #expect(Wire.relayed(twice) == nil)
        #expect(Array(frame[2...]) == Array(twice[2...]))
        #expect(Wire.defaultTtl == 4)
    }

    @Test("a frame of another group, with another type or damaged is refused")
    func refused() throws {
        let frame = try Wire.encode(.text(Msg.Text(1, 1, "hi")), crypto)
        let stranger = try GroupCrypto(GroupCrypto.randomKey())
        #expect(throws: WireError.self) { try Wire.decode(frame, stranger) }
        let sosType = p2pReplacing(frame, 0, UInt8(MsgType.sos.code))
        #expect(throws: WireError.self) { try Wire.decode(sosType, crypto) }
        let unknown = p2pReplacing(frame, 0, 99)
        #expect(throws: WireError.self) { try Wire.decode(unknown, crypto) }
        let tampered = p2pReplacing(frame, frame.count - 1, frame[frame.count - 1] &+ 1)
        #expect(throws: WireError.self) { try Wire.decode(tampered, crypto) }
        #expect(throws: WireError.self) { try Wire.decode([0], crypto) }
        #expect(throws: WireError.self) { try Wire.decode([UInt8](repeating: 0, count: 20_000), crypto) }
    }

    @Test("a position outside the globe is refused")
    func outsideGlobe() throws {
        let w = ByteWriter().u64(1).u32(1)
        w.i32(Int32(kotlinRound(95.0 * 1e7))).i32(0).i16(0).u16(0).u16(0).u16(0).u32(0).u8(0)
        let sealed = crypto.seal(w.toByteArray(), [UInt8(MsgType.pos.code)])
        let frame = [UInt8(MsgType.pos.code), 4] + sealed
        #expect(throws: WireError.self) { try Wire.decode(frame, crypto) }
    }
}

struct ShareCadenceTests {
    private func fix(_ t: Int64, _ lat: Double = 45.0, _ lon: Double = 6.0) -> PeerFix {
        PeerFix(lat, lon, timeSec: t)
    }

    @Test("the first fix is always sent")
    func firstFix() throws {
        #expect(try ShareCadence(30, 10.0).shouldSend(nil, fix(0)))
    }

    @Test("nothing goes before the interval, a fix up to a second early counts")
    func interval() throws {
        let c = try ShareCadence(30, 0.0)
        let last = SentPosition(100, 45.0, 6.0)
        #expect(!c.shouldSend(last, fix(110, 45.01)))
        #expect(!c.shouldSend(last, fix(128, 45.01)))
        #expect(c.shouldSend(last, fix(129, 45.01)))
        #expect(c.shouldSend(last, fix(131, 45.01)))
    }

    @Test("a device that has not moved by the threshold stays quiet until the keep alive")
    func threshold() throws {
        let c = try ShareCadence(30, 10.0)
        let last = SentPosition(0, 45.0, 6.0)
        #expect(!c.shouldSend(last, fix(30, 45.00003)))
        #expect(c.shouldSend(last, fix(30, 45.0002)))
        #expect(!c.shouldSend(last, fix(149, 45.00003)))
        #expect(c.shouldSend(last, fix(150, 45.00003)))
    }

    @Test("a fix older than the last one sent, after the clock went back, is sent")
    func clockBack() throws {
        let c = try ShareCadence(30, 10.0)
        let last = SentPosition(1000, 45.0, 6.0)
        #expect(c.shouldSend(last, fix(185, 45.0)))
    }

    @Test("an interval under five seconds is refused")
    func minimum() {
        #expect(throws: ToolMapError.self) { try ShareCadence(4, 0.0) }
        #expect(ShareCadence.presetsS.first == 5)
        #expect(ShareCadence.presetsS.last == 1800)
    }

    @Test("initials come from the first two words")
    func initials() {
        #expect(MemberPalette.initials("Jean Dupont") == "JD")
        #expect(MemberPalette.initials("camille") == "CA")
        #expect(MemberPalette.initials("   ") == "?")
        #expect(MemberPalette.argb(13) == MemberPalette.colors[1])
    }
}
