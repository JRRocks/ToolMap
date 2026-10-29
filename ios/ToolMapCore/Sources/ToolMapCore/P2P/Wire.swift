// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Wire.kt: the messages of the group and their sealed frames,
// byte for byte the same as on Android (docs/specs/p2p-sharing.md §5).

/// A position as it travels: about 34 bytes. Unknown optional values are `nil`.
public struct PeerFix: Equatable, Hashable, Sendable, CustomStringConvertible {
    public let lat: Double
    public let lon: Double
    public let altM: Int?
    public let accuracyM: Int?
    public let speedMs: Double?
    public let bearingDeg: Double?
    /// Epoch seconds of the measure.
    public let timeSec: Int64
    /// Battery of the sender, 0..100.
    public let battery: Int?

    public init(
        _ lat: Double,
        _ lon: Double,
        altM: Int? = nil,
        accuracyM: Int? = nil,
        speedMs: Double? = nil,
        bearingDeg: Double? = nil,
        timeSec: Int64,
        battery: Int? = nil
    ) {
        self.lat = lat
        self.lon = lon
        self.altM = altM
        self.accuracyM = accuracyM
        self.speedMs = speedMs
        self.bearingDeg = bearingDeg
        self.timeSec = timeSec
        self.battery = battery
    }

    public static let bytes = 4 + 4 + 2 + 2 + 2 + 2 + 4 + 1
    private static let coordScale = 1e7
    private static let altUnknown = Int(Int16.min)
    private static let unknown16 = 0xFFFF
    private static let unknown8 = 0xFF

    public var description: String { "PeerFix(\(lat), \(lon) @\(timeSec))" }

    /// Kotlin `Math.round(x).toInt()`: rounded half up, then cut to 32 bits.
    private static func roundToInt(_ x: Double) -> Int {
        Int(Int32(truncatingIfNeeded: kotlinRound(x)))
    }

    /// The values the wire keeps: rounded to 1e-7°, 1 m, 1 cm/s and 0.1°.
    func write(_ w: ByteWriter) {
        w.i32(Int32(truncatingIfNeeded: kotlinRound(lat * PeerFix.coordScale)))
        w.i32(Int32(truncatingIfNeeded: kotlinRound(lon * PeerFix.coordScale)))
        if let alt = altM {
            w.i16(Swift.min(Swift.max(alt, Int(Int16.min) + 1), Int(Int16.max)))
        } else {
            w.i16(PeerFix.altUnknown)
        }
        if let accuracy = accuracyM {
            w.u16(Swift.min(Swift.max(accuracy, 0), PeerFix.unknown16 - 1))
        } else {
            w.u16(PeerFix.unknown16)
        }
        if let speed = speedMs {
            w.u16(Swift.min(Swift.max(PeerFix.roundToInt(speed * 100), 0), PeerFix.unknown16 - 1))
        } else {
            w.u16(PeerFix.unknown16)
        }
        if let bearing = bearingDeg {
            let turned = kotlinRem(kotlinRem(bearing, 360) + 360, 360)
            w.u16(PeerFix.roundToInt(turned * 10) % 3600)
        } else {
            w.u16(PeerFix.unknown16)
        }
        w.u32(timeSec)
        if let level = battery {
            w.u8(Swift.min(Swift.max(level, 0), 100))
        } else {
            w.u8(PeerFix.unknown8)
        }
    }

    static func read(_ r: inout ByteReader) throws -> PeerFix {
        let latRaw = try r.i32()
        let lonRaw = try r.i32()
        let lat = Double(latRaw) / PeerFix.coordScale
        let lon = Double(lonRaw) / PeerFix.coordScale
        if !(lat >= -90.0 && lat <= 90.0) || !(lon >= -180.0 && lon <= 180.0) {
            throw WireError("position out of range")
        }
        let alt = try r.i16()
        let accuracy = try r.u16()
        let speed = try r.u16()
        let bearing = try r.u16()
        let time = try r.u32()
        let battery = try r.u8()
        return PeerFix(
            lat,
            lon,
            altM: alt != PeerFix.altUnknown ? alt : nil,
            accuracyM: accuracy != PeerFix.unknown16 ? accuracy : nil,
            speedMs: speed != PeerFix.unknown16 ? Double(speed) / 100.0 : nil,
            bearingDeg: bearing != PeerFix.unknown16 && bearing < 3600 ? Double(bearing) / 10.0 : nil,
            timeSec: time,
            battery: battery <= 100 ? battery : nil
        )
    }
}

public enum MsgType: Int, CaseIterable, Sendable, CustomStringConvertible {
    case hello = 1
    case peers = 2
    case signal = 3
    case pos = 4
    case trail = 5
    case ping = 6
    case msg = 7
    case sos = 8
    case rekey = 9
    case bye = 10
    case kick = 11
    case avatar = 12
    case diag = 13
    /// The author lifted its alert (P22.2); a version without it drops the frame as an unknown type.
    case sosEnd = 14
    /// Whether the sender shares its position or only listens (P22.3, ADR-090); unknown to older versions too.
    case state = 15

    public var code: Int { rawValue }

    public static func of(_ code: Int) -> MsgType? { MsgType(rawValue: code) }

    /// The Kotlin name, as the event log writes it.
    public var description: String {
        switch self {
        case .hello: "HELLO"
        case .peers: "PEERS"
        case .signal: "SIGNAL"
        case .pos: "POS"
        case .trail: "TRAIL"
        case .ping: "PING"
        case .msg: "MSG"
        case .sos: "SOS"
        case .rekey: "REKEY"
        case .bye: "BYE"
        case .kick: "KICK"
        case .avatar: "AVATAR"
        case .diag: "DIAG"
        case .sosEnd: "SOS_END"
        case .state: "STATE"
        }
    }
}

public struct PeerInfo: Equatable, Hashable, Sendable {
    public let memberId: Int64
    public let name: String
    public let color: Int32
    /// Seconds since this member was last heard of by the sender of the list.
    public let lastSeenAgoSec: Int64

    public init(_ memberId: Int64, _ name: String, _ color: Int32, _ lastSeenAgoSec: Int64) {
        self.memberId = memberId
        self.name = name
        self.color = color
        self.lastSeenAgoSec = lastSeenAgoSec
    }
}

/// A message between members (Kotlin `sealed interface Msg`): one case per type, each with its own values.
public enum Msg: Equatable, Hashable, Sendable {
    case hello(Hello)
    case peers(Peers)
    case signal(Signal)
    case pos(Pos)
    case trail(Trail)
    case ping(Ping)
    case text(Text)
    case sos(Sos)
    case sosEnd(SosEnd)
    case state(State)
    case rekey(Rekey)
    case bye(Bye)
    case kick(Kick)
    case avatar(Avatar)
    case diag(Diag)

    /// 2: the colour became a packed icon and RGB colour (`MemberLook`) on 4 bytes.
    public static let protocolVersion = 2
    public static let maxTextChars = 140

    public struct Hello: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let name: String
        public let color: Int32
        public let protocolVersion: Int

        public init(_ memberId: Int64, _ name: String, _ color: Int32, protocolVersion: Int = Msg.protocolVersion) {
            self.memberId = memberId
            self.name = name
            self.color = color
            self.protocolVersion = protocolVersion
        }
    }

    public struct Peers: Equatable, Hashable, Sendable {
        public let peers: [PeerInfo]

        public init(_ peers: [PeerInfo]) {
            self.peers = peers
        }
    }

    /// Pairing data for `to`, relayed hop by hop through the mesh.
    public struct Signal: Equatable, Hashable, Sendable {
        public let seq: Int64
        public let from: Int64
        public let to: Int64
        public let payload: [UInt8]

        public init(_ seq: Int64, _ from: Int64, _ to: Int64, _ payload: [UInt8]) {
            self.seq = seq
            self.from = from
            self.to = to
            self.payload = payload
        }
    }

    public struct Pos: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let fix: PeerFix

        public init(_ memberId: Int64, _ seq: Int64, _ fix: PeerFix) {
            self.memberId = memberId
            self.seq = seq
            self.fix = fix
        }
    }

    /// Positions kept while the sender had no link, oldest first.
    public struct Trail: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let fixes: [PeerFix]

        public init(_ memberId: Int64, _ fixes: [PeerFix]) {
            self.memberId = memberId
            self.fixes = fixes
        }
    }

    public struct Ping: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let timeSec: Int64

        public init(_ memberId: Int64, _ timeSec: Int64) {
            self.memberId = memberId
            self.timeSec = timeSec
        }
    }

    public struct Text: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let text: String

        public init(_ memberId: Int64, _ seq: Int64, _ text: String) {
            self.memberId = memberId
            self.seq = seq
            self.text = text
        }
    }

    public struct Sos: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let fix: PeerFix?
        public let text: String

        public init(_ memberId: Int64, _ seq: Int64, _ fix: PeerFix?, _ text: String) {
            self.memberId = memberId
            self.seq = seq
            self.fix = fix
            self.text = text
        }
    }

    /// `memberId` lifted its alert: every `Sos` of it numbered below `seq` is over. Repeated like the alert itself
    /// (`MeshNode.sosRepeatForS`), for the members who come back within the two minutes.
    public struct SosEnd: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64

        public init(_ memberId: Int64, _ seq: Int64) {
            self.memberId = memberId
            self.seq = seq
        }
    }

    /// `memberId` shares its position (`sharing`) or only listens to the group (P22.3). Flooded like a text, sent
    /// again unchanged to each new link like `Avatar`; of two, the higher `seq` wins.
    public struct State: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let sharing: Bool

        public init(_ memberId: Int64, _ seq: Int64, sharing: Bool) {
            self.memberId = memberId
            self.seq = seq
            self.sharing = sharing
        }
    }

    /// A new group key, sent when `excluded` (0 for nobody) leaves the group.
    public struct Rekey: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let excluded: Int64
        public let key: [UInt8]

        public init(_ memberId: Int64, _ seq: Int64, _ excluded: Int64, _ key: [UInt8]) {
            self.memberId = memberId
            self.seq = seq
            self.excluded = excluded
            self.key = key
        }
    }

    public struct Bye: Equatable, Hashable, Sendable {
        public let memberId: Int64

        public init(_ memberId: Int64) {
            self.memberId = memberId
        }
    }

    /// Sent to a member that has just been removed from the group, on its own link, before the link closes.
    public struct Kick: Equatable, Hashable, Sendable {
        public let target: Int64

        public init(_ target: Int64) {
            self.target = target
        }
    }

    /// The picture of `memberId` (`MemberLook.picture`); empty when it has none any more. Flooded like a text.
    public struct Avatar: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let image: [UInt8]

        public init(_ memberId: Int64, _ seq: Int64, _ image: [UInt8]) {
            self.memberId = memberId
            self.seq = seq
            self.image = image
        }
    }

    /// A payload of the debug build's test tools (`DiagChannel`); flooded like a text, never shown.
    public struct Diag: Equatable, Hashable, Sendable {
        public let memberId: Int64
        public let seq: Int64
        public let body: [UInt8]

        /// Fits a control frame of the server (6000 characters of Base64, `firebase/database.rules.json`).
        public static let maxBytes = 4096

        public init(_ memberId: Int64, _ seq: Int64, _ body: [UInt8]) {
            self.memberId = memberId
            self.seq = seq
            self.body = body
        }
    }

    public var type: MsgType {
        switch self {
        case .hello: .hello
        case .peers: .peers
        case .signal: .signal
        case .pos: .pos
        case .trail: .trail
        case .ping: .ping
        case .text: .msg
        case .sos: .sos
        case .sosEnd: .sosEnd
        case .state: .state
        case .rekey: .rekey
        case .bye: .bye
        case .kick: .kick
        case .avatar: .avatar
        case .diag: .diag
        }
    }

    /// `true` for the unordered, unreliable channel; everything else goes on the reliable one.
    public var onPositionChannel: Bool {
        switch self {
        case .pos, .ping: true
        default: false
        }
    }

    /// The values of this message when it is a `T` (`Msg.Hello.self`...), else `nil`: Kotlin's `msg as? Msg.Hello`.
    public func value<T>(_ type: T.Type) -> T? {
        switch self {
        case let .hello(m): m as? T
        case let .peers(m): m as? T
        case let .signal(m): m as? T
        case let .pos(m): m as? T
        case let .trail(m): m as? T
        case let .ping(m): m as? T
        case let .text(m): m as? T
        case let .sos(m): m as? T
        case let .sosEnd(m): m as? T
        case let .state(m): m as? T
        case let .rekey(m): m as? T
        case let .bye(m): m as? T
        case let .kick(m): m as? T
        case let .avatar(m): m as? T
        case let .diag(m): m as? T
        }
    }
}

/// A decoded frame: the message and how many more hops it may make.
public struct Frame: Equatable, Sendable {
    public let msg: Msg
    public let ttl: Int

    public init(msg: Msg, ttl: Int) {
        self.msg = msg
        self.ttl = ttl
    }
}

/// Frames of the data channels: `type (1) | ttl (1) | nonce (12) | AES-GCM(payload) | tag (16)`. The type is
/// authenticated, the time to live is not (a relay lowers it without the key's help, and a non-member cannot do more
/// than shorten the life of a frame on a link it already controls).
public enum Wire {
    public static let defaultTtl = 4
    private static let maxFrame = 16 * 1024
    private static let header = 2
    private static let frameIdBytes = 4
    private static let stateSharing = 1
    private static let maxPeers = 32
    private static let maxTrail = 60
    private static let textBytes = Msg.maxTextChars * 4

    /// The sealed frame of `msg`; throws only for a diagnostic payload over `Msg.Diag.maxBytes` (Kotlin `require`).
    public static func encode(_ msg: Msg, _ crypto: GroupCrypto, ttl: Int = defaultTtl) throws -> [UInt8] {
        let aad = [UInt8(msg.type.code)]
        let plain = try payload(msg)
        let sealed = crypto.seal(plain, aad)
        return aad + [UInt8(Swift.min(Swift.max(ttl, 0), 255))] + sealed
    }

    public static func decode(_ frame: [UInt8], _ crypto: GroupCrypto) throws -> Frame {
        if frame.count < header || frame.count > maxFrame { throw WireError("bad frame size") }
        guard let type = MsgType.of(Int(frame[0])) else { throw WireError("unknown message type") }
        let ttl = Int(frame[1])
        let plain = try crypto.open(Array(frame[header...]), [UInt8(type.code)])
        let msg = try parse(type, plain)
        return Frame(msg: msg, ttl: ttl)
    }

    /// The type of `frame`, read without the key; `nil` when it is not one of ours.
    public static func typeOf(_ frame: [UInt8]) -> MsgType? {
        guard let first = frame.first else { return nil }
        return MsgType.of(Int(first))
    }

    /// A short name of `frame` for the event log: the start of its nonce in hexadecimal, the same on every device that
    /// sends, relays or receives it (the relays only change the time to live).
    public static func frameId(_ frame: [UInt8]) -> String {
        if frame.count < header + frameIdBytes { return "-" }
        return P2PHex.encode(Array(frame[header ..< header + frameIdBytes]))
    }

    /// `frame` as a relay passes it on: same bytes, one hop less; `nil` when it has no hop left.
    public static func relayed(_ frame: [UInt8]) -> [UInt8]? {
        guard frame.count >= header else { return nil }
        let ttl = Int(frame[1])
        if ttl <= 1 { return nil }
        var copy = frame
        copy[1] = UInt8(ttl - 1)
        return copy
    }

    private static func payload(_ msg: Msg) throws -> [UInt8] {
        let w = ByteWriter()
        switch msg {
        case let .hello(m):
            w.u64(m.memberId).str(m.name, PairingCodec.maxNameBytes).i32(m.color).u8(m.protocolVersion)
        case let .peers(m):
            w.u8(Swift.min(m.peers.count, maxPeers))
            for p in m.peers.prefix(maxPeers) {
                w.u64(p.memberId).str(p.name, PairingCodec.maxNameBytes).i32(p.color)
                w.u32(Swift.min(Swift.max(p.lastSeenAgoSec, 0), 0xFFFF_FFFF))
            }
        case let .signal(m):
            w.u32(m.seq).u64(m.from).u64(m.to).u16(m.payload.count).bytes(m.payload)
        case let .pos(m):
            w.u64(m.memberId).u32(m.seq)
            m.fix.write(w)
        case let .trail(m):
            w.u64(m.memberId).u8(Swift.min(m.fixes.count, maxTrail))
            for f in m.fixes.suffix(maxTrail) {
                f.write(w)
            }
        case let .ping(m):
            w.u64(m.memberId).u32(m.timeSec)
        case let .text(m):
            w.u64(m.memberId).u32(m.seq)
            w.longStrUtf8(ByteWriter.javaUtf8Prefix(m.text, Msg.maxTextChars), textBytes)
        case let .sos(m):
            w.u64(m.memberId).u32(m.seq).u8(m.fix != nil ? 1 : 0)
            m.fix?.write(w)
            w.longStrUtf8(ByteWriter.javaUtf8Prefix(m.text, Msg.maxTextChars), textBytes)
        case let .sosEnd(m):
            w.u64(m.memberId).u32(m.seq)
        case let .state(m):
            w.u64(m.memberId).u32(m.seq).u8(m.sharing ? stateSharing : 0)
        case let .rekey(m):
            w.u64(m.memberId).u32(m.seq).u64(m.excluded).bytes(m.key)
        case let .bye(m):
            w.u64(m.memberId)
        case let .kick(m):
            w.u64(m.target)
        case let .avatar(m):
            let image = m.image.count <= MemberLook.maxPictureBytes ? m.image : []
            w.u64(m.memberId).u32(m.seq).u16(image.count).bytes(image)
        case let .diag(m):
            try require(m.body.count <= Msg.Diag.maxBytes, "diagnostic payload too large")
            w.u64(m.memberId).u32(m.seq).u16(m.body.count).bytes(m.body)
        }
        return w.toByteArray()
    }

    private static func parse(_ type: MsgType, _ plain: [UInt8]) throws -> Msg {
        var r = ByteReader(plain)
        let msg: Msg
        switch type {
        case .hello:
            let member = try r.u64()
            let name = try r.str()
            let color = try r.i32()
            let proto = try r.u8()
            msg = .hello(Msg.Hello(member, name, color, protocolVersion: proto))
        case .peers:
            let count = try r.u8()
            var peers: [PeerInfo] = []
            for _ in 0 ..< count {
                let member = try r.u64()
                let name = try r.str()
                let color = try r.i32()
                let seen = try r.u32()
                peers.append(PeerInfo(member, name, color, seen))
            }
            msg = .peers(Msg.Peers(peers))
        case .signal:
            let seq = try r.u32()
            let from = try r.u64()
            let to = try r.u64()
            let size = try r.u16()
            let payload = try r.bytes(size)
            msg = .signal(Msg.Signal(seq, from, to, payload))
        case .pos:
            let member = try r.u64()
            let seq = try r.u32()
            let fix = try PeerFix.read(&r)
            msg = .pos(Msg.Pos(member, seq, fix))
        case .trail:
            let member = try r.u64()
            let count = try r.u8()
            var fixes: [PeerFix] = []
            for _ in 0 ..< count {
                let fix = try PeerFix.read(&r)
                fixes.append(fix)
            }
            msg = .trail(Msg.Trail(member, fixes))
        case .ping:
            let member = try r.u64()
            let time = try r.u32()
            msg = .ping(Msg.Ping(member, time))
        case .msg:
            let member = try r.u64()
            let seq = try r.u32()
            let text = try r.longStr()
            msg = .text(Msg.Text(member, seq, text))
        case .sos:
            let member = try r.u64()
            let seq = try r.u32()
            let hasFix = try r.u8()
            var fix: PeerFix?
            if hasFix == 1 {
                fix = try PeerFix.read(&r)
            }
            let text = try r.longStr()
            msg = .sos(Msg.Sos(member, seq, fix, text))
        case .sosEnd:
            let member = try r.u64()
            let seq = try r.u32()
            msg = .sosEnd(Msg.SosEnd(member, seq))
        case .state:
            let member = try r.u64()
            let seq = try r.u32()
            // Unknown bits are left for later versions.
            let flags = try r.u8()
            msg = .state(Msg.State(member, seq, sharing: flags & stateSharing != 0))
        case .rekey:
            let member = try r.u64()
            let seq = try r.u32()
            let excluded = try r.u64()
            let key = try r.bytes(GroupCrypto.keyBytes)
            msg = .rekey(Msg.Rekey(member, seq, excluded, key))
        case .bye:
            let member = try r.u64()
            msg = .bye(Msg.Bye(member))
        case .kick:
            let target = try r.u64()
            msg = .kick(Msg.Kick(target))
        case .avatar:
            let member = try r.u64()
            let seq = try r.u32()
            let size = try r.u16()
            if size > MemberLook.maxPictureBytes { throw WireError("picture too large") }
            let image = try r.bytes(size)
            msg = .avatar(Msg.Avatar(member, seq, image))
        case .diag:
            let member = try r.u64()
            let seq = try r.u32()
            let size = try r.u16()
            if size > Msg.Diag.maxBytes { throw WireError("diagnostic payload too large") }
            let body = try r.bytes(size)
            msg = .diag(Msg.Diag(member, seq, body))
        }
        if r.remaining != 0 { throw WireError("trailing data in \(type)") }
        return msg
    }
}
