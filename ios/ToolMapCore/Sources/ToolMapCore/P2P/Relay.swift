// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Relay.kt: the coordination server of a group (ADR-033,
// docs/specs/firebase-relay.md), its invitations and the states shown to the user.

/// The Firebase project that coordinates a group (ADR-033): the address of its Realtime Database and its web API key
/// (an identifier, not a secret: what protects the data is the database rules and the encryption of every frame).
public struct RelayConfig: Equatable, Hashable, Sendable {
    public static let maxUrlBytes = 120
    public static let maxKeyBytes = 60

    public let dbUrl: String
    public let apiKey: String

    public init(_ dbUrl: String, _ apiKey: String) {
        self.dbUrl = dbUrl
        self.apiKey = apiKey
    }

    public enum Problem: Sendable {
        case badUrl
        case badKey
    }

    /// The database address without a trailing slash, in `https`.
    public var base: String {
        var url = kotlinTrim(dbUrl)
        while url.hasSuffix("/") {
            url.removeLast()
        }
        return url
    }

    public var isUsable: Bool { RelayConfig.problem(self) == nil }

    /// What is wrong with `config`, or `nil`.
    public static func problem(_ config: RelayConfig) -> Problem? {
        let url = config.base
        let urlLength = url.utf16.count
        if !url.hasPrefix("https://") || urlLength < "https://x.y".utf16.count || url.contains(" ")
            || url.contains("?") || url.contains("#") || urlLength > maxUrlBytes {
            return .badUrl
        }
        let key = config.apiKey
        let blank = key.unicodeScalars.allSatisfy(KotlinText.isWhitespace)
        if blank || key.utf16.count > maxKeyBytes || key.unicodeScalars.contains(where: KotlinText.isWhitespace) {
            return .badKey
        }
        return nil
    }

    public static func of(_ dbUrl: String, _ apiKey: String) -> RelayConfig {
        var url = kotlinTrim(dbUrl)
        while url.hasSuffix("/") {
            url.removeLast()
        }
        return RelayConfig(url, kotlinTrim(apiKey))
    }
}

/// Where a group lives on the server: a room named after the group, and the paths of its nodes.
public enum RelayPaths {
    private static let memberKeyMax = 17

    /// The room of a group: 32 hexadecimal characters of the group id.
    public static func room(_ groupId: [UInt8]) -> String { P2PHex.encode(groupId) }

    /// The key of a member under `b`: prefixed so that Firebase never mistakes a run of digits for an array index.
    public static func member(_ memberId: Int64) -> String { "m" + P2PHex.long(memberId) }

    public static func memberOf(_ key: String) -> Int64? {
        let length = key.utf16.count
        guard length >= 2, length <= memberKeyMax, key.hasPrefix("m") else { return nil }
        return P2PHex.parseUnsignedLong(String(key.dropFirst()))
    }
}

/// An invitation to a group that lives on a server: the group key, who invites, and where the group is.
public struct RoomInvite: Equatable, Hashable, Sendable {
    public let createdAtSec: Int64
    public let groupId: [UInt8]
    public let groupKey: [UInt8]
    public let inviterId: Int64
    public let inviterName: String
    public let server: RelayConfig

    public init(
        _ createdAtSec: Int64,
        _ groupId: [UInt8],
        _ groupKey: [UInt8],
        _ inviterId: Int64,
        _ inviterName: String,
        _ server: RelayConfig
    ) throws {
        try require(groupId.count == PairingPayload.groupIdBytes, "Failed requirement.")
        try require(groupKey.count == GroupCrypto.keyBytes, "Failed requirement.")
        self.createdAtSec = createdAtSec
        self.groupId = groupId
        self.groupKey = groupKey
        self.inviterId = inviterId
        self.inviterName = inviterName
        self.server = server
    }
}

/// The text of a `RoomInvite`: bytes → Base45 for a QR code, or `toolmap://join?d=…` (URL-safe Base64) for a message.
public enum RoomInviteCodec {
    public static let linkPrefix = "toolmap://join?d="
    public static let maxAgeSec: Int64 = 7 * 24 * 3600
    private static let version = 2

    public static func encodeBytes(_ i: RoomInvite) -> [UInt8] {
        ByteWriter()
            .u16(PairingCodec.magic)
            .u8(version)
            .u32(i.createdAtSec)
            .bytes(i.groupId)
            .bytes(i.groupKey)
            .u64(i.inviterId)
            .str(i.inviterName, PairingCodec.maxNameBytes)
            .str(i.server.base, RelayConfig.maxUrlBytes)
            .str(i.server.apiKey, RelayConfig.maxKeyBytes)
            .toByteArray()
    }

    public static func decodeBytes(_ data: [UInt8]) throws -> RoomInvite {
        var r = ByteReader(data)
        let magic = try r.u16()
        if magic != PairingCodec.magic { throw WireError("not a ToolMap code") }
        let codeVersion = try r.u8()
        if codeVersion != version { throw WireError("not a group invitation") }
        let created = try r.u32()
        let groupId = try r.bytes(PairingPayload.groupIdBytes)
        let key = try r.bytes(GroupCrypto.keyBytes)
        let inviter = try r.u64()
        let name = try r.str()
        let url = try r.str()
        let apiKey = try r.str()
        if r.remaining != 0 { throw WireError("trailing data") }
        let server = RelayConfig(url, apiKey)
        if !server.isUsable { throw WireError("bad server in invitation") }
        return try RoomInvite(created, groupId, key, inviter, name, server)
    }

    public static func toQrText(_ i: RoomInvite) -> String { Base45.encode(encodeBytes(i)) }

    public static func toLink(_ i: RoomInvite) -> String {
        linkPrefix + P2PBase64.encode(encodeBytes(i), url: true, padding: false)
    }

    /// `nil` when `text` is not an invitation to a server group (it may be a pairing code of the direct mode); a
    /// damaged invitation throws, as on Android.
    public static func parseOrNull(_ text: String) throws -> RoomInvite? {
        let t = kotlinTrim(text)
        let bytes: [UInt8]
        if let d = PairingCodec.linkData(t, linkPrefix) {
            guard let decoded = P2PBase64.decode(d, url: true) else { return nil }
            bytes = decoded
        } else if PairingCodec.linkData(t, "toolmap://") != nil {
            return nil
        } else {
            guard let decoded = try? Base45.decode(t) else { return nil }
            bytes = decoded
        }
        if bytes.count < 3 || Int(bytes[2]) != version { return nil }
        return try decodeBytes(bytes)
    }

    public static func staleness(_ i: RoomInvite, _ nowSec: Int64) -> PairingCodec.Staleness? {
        if nowSec - i.createdAtSec > maxAgeSec { return .expired }
        if i.createdAtSec - nowSec > maxAgeSec { return .fromTheFuture }
        return nil
    }
}

/// How the connection to the coordination server is doing, for the whole group.
public enum ServerState: CaseIterable, Sendable, CustomStringConvertible {
    /// The group does not use a server (direct mode, or no group).
    case none
    /// Signing in or opening the room.
    case connecting
    /// The room is open and the heartbeat is accepted.
    case online
    /// The network or the server does not answer; the app keeps trying.
    case unreachable
    /// The server refused us (wrong API key, anonymous sign-in off, rules not published).
    case refused

    public var description: String {
        switch self {
        case .none: "NONE"
        case .connecting: "CONNECTING"
        case .online: "ONLINE"
        case .unreachable: "UNREACHABLE"
        case .refused: "REFUSED"
        }
    }
}

/// The state of a member as the user reads it: one icon and one word per member.
public enum PeerState: CaseIterable, Sendable {
    /// Linked and heard recently.
    case online
    /// In the group but nothing yet received from it (just joined, or never connected since we came).
    case paired
    /// It was online and went silent for a short while: network cut, phone asleep, no GPS fix.
    case lostSync
    /// Silent for a long time.
    case offline
    /// It left the group or was removed, or this device is in no group.
    case unpaired

    /// Silent for longer than this, a member counts as offline rather than out of sync.
    public static let lostSyncWindowMs: Int64 = 10 * 60_000

    /// The state of `m` at `nowMs`; `serverUp` is `false` when this device itself cannot reach the server, in which
    /// case it cannot vouch for anyone (including itself).
    public static func of(_ m: MemberView, _ nowMs: Int64, serverUp: Bool = true) -> PeerState {
        if m.isSelf { return serverUp ? .online : .offline }
        if m.status == .left { return .unpaired }
        if !serverUp { return .lostSync }
        if m.status == .direct || m.status == .relayed { return .online }
        if m.fix == nil { return .paired }
        if nowMs - m.lastHeardMs <= lostSyncWindowMs { return .lostSync }
        return .offline
    }
}

/// The link of a group to its coordination server, as `MeshNode` sees any other transport.
public protocol RelayTransport: MeshTransport {
    var identity: TransportIdentity? { get set }

    /// Told whenever the state of the server connection changes (from any thread).
    var onState: (@Sendable (ServerState) -> Void)? { get set }

    var state: ServerState { get }

    /// Writes to the server not done yet (the test tools wait for none before sending theirs, P21).
    var pendingWrites: Int { get }

    /// Signs in and opens the room; links to the other members then appear through the `LinkListener`.
    func start()

    /// Ignores `peer` from now on (it was removed from the group).
    func ban(_ peer: Int64)

    /// The phone's network changed (or came back): retry at once instead of waiting for the next attempt, and drop a
    /// stream that may still be bound to the network that went away.
    func reconnectNow()

    /// The phone's default network is now `network` (an opaque name): `reconnectNow` then drops a stream opened on
    /// another one, however recent (P21.5 f).
    func onNetwork(_ network: String)

    /// The system cuts the app off the network (`blocked`) or lets it back: no attempt is made meanwhile (P21.5 h).
    func onBlocked(_ blocked: Bool)

    /// The default network is gone and no other took its place: attempts wait for the next `onNetwork` (P21.5 p).
    func onOffline()

    /// The phone's clock was set (by hand or by the network): the offset to the server time is measured again.
    func onClockChanged()

    /// Leaves the room (the member disappears for the others) and releases the connection.
    func close()
}

extension RelayTransport {
    public var pendingWrites: Int { 0 }

    public func reconnectNow() {}

    public func onNetwork(_ network: String) {}

    public func onBlocked(_ blocked: Bool) {}

    public func onOffline() {}

    public func onClockChanged() {}
}

public protocol RelayTransportFactory {
    func create(_ config: RelayConfig, _ groupId: [UInt8], _ listener: any LinkListener) -> any RelayTransport
}
