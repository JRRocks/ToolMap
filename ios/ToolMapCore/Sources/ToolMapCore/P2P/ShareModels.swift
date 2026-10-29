// Port of core/src/main/kotlin/fr/toolmap/core/p2p/ShareModels.kt. The saved state is the JSON document of the
// Android app, written with the same text (kotlinx.serialization, `encodeDefaults = true`) and read with the same
// rules (unknown keys ignored, a value of the wrong type spoils the whole document, which then gives the defaults).

/// What the user chose for sharing; kept between launches.
public struct ShareSettings: Equatable, Hashable, Sendable {
    public static let defaultIntervalS = 30
    public static let defaultMinMoveM = 10
    public static let stunServers = ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"]

    public var name: String
    /// The palette colour of version 0.4.0, used until a `color` is chosen.
    public var colorIndex: Int
    public var intervalSec: Int
    public var minMoveMeters: Int
    /// Use public STUN servers to learn the public address (nothing else goes through them).
    public var stun: Bool
    /// The Firebase project used for new groups; `nil` means the one built into the app, or none (direct mode).
    public var server: RelayConfig?
    /// The icon the others see (`MemberLook.icons`).
    public var icon: Int
    /// The colour of the icon's disc (ARGB), chosen on the colour wheel; `nil` keeps `colorIndex`.
    public var color: Int32?
    /// Draw the path of each member on the map; off, only their current position shows.
    public var showTrails: Bool
    /// The member's own picture as Base64 (a small JPEG, `MemberLook.maxPictureBytes` at most).
    public var picture: String?

    public init(
        name: String = "",
        colorIndex: Int = 0,
        intervalSec: Int = ShareSettings.defaultIntervalS,
        minMoveMeters: Int = ShareSettings.defaultMinMoveM,
        stun: Bool = true,
        server: RelayConfig? = nil,
        icon: Int = MemberLook.initials,
        color: Int32? = nil,
        showTrails: Bool = true,
        picture: String? = nil
    ) {
        self.name = name
        self.colorIndex = colorIndex
        self.intervalSec = intervalSec
        self.minMoveMeters = minMoveMeters
        self.stun = stun
        self.server = server
        self.icon = icon
        self.color = color
        self.showTrails = showTrails
        self.picture = picture
    }

    /// The bytes of `picture`; `nil` when there is none or it is damaged.
    public func pictureBytes() -> [UInt8]? {
        guard let text = picture, let b = P2PBase64.decode(text) else { return nil }
        return !b.isEmpty && b.count <= MemberLook.maxPictureBytes ? b : nil
    }

    /// These settings with `bytes` as the picture, chosen as the icon; `nil` removes it (back to the initials).
    public func withPicture(_ bytes: [UInt8]?) -> ShareSettings {
        var copy = self
        if let bytes {
            copy.picture = P2PBase64.encode(bytes)
            copy.icon = MemberLook.picture
        } else {
            copy.picture = nil
            copy.icon = icon == MemberLook.picture ? MemberLook.initials : icon
        }
        return copy
    }

    /// The colour of the disc, from the wheel or else the palette.
    public func colorArgb() -> Int32 {
        if let color { return MemberLook.argb(color) }
        return MemberPalette.argb(colorIndex)
    }

    /// The packed icon and colour sent to the others.
    public func look() -> Int32 { MemberLook.pack(icon, colorArgb()) }

    public func cadence() -> ShareCadence {
        ShareCadence(clamping: intervalSec, Double(minMoveMeters))
    }
}

extension ShareCadence {
    /// `ShareCadence(intervalSec.coerceAtLeast(MIN_INTERVAL_S), …)`, which cannot fail.
    public init(clamping intervalSec: Int, _ minMoveMeters: Double) {
        self.intervalSec = Swift.max(intervalSec, ShareCadence.minIntervalS)
        self.minMoveMeters = minMoveMeters
    }
}

/// The group this device belongs to, enough to come back to it after a restart.
public struct GroupRecord: Equatable, Hashable, Sendable {
    public let groupId: [UInt8]
    public let key: [UInt8]
    public let selfId: Int64
    /// The server this group lives on; `nil` for a group of the direct (QR, WebRTC) mode.
    public let server: RelayConfig?
    /// The icon the others see (`MemberLook.icons`).
    public let icon: Int
    /// The colour of the icon's disc (ARGB), chosen on the colour wheel; `nil` keeps the palette colour.
    public let color: Int32?
    /// Draw the path of each member on the map; off, only their current position shows.
    public let showTrails: Bool

    public init(
        _ groupId: [UInt8],
        _ key: [UInt8],
        _ selfId: Int64,
        server: RelayConfig? = nil,
        icon: Int = MemberLook.initials,
        color: Int32? = nil,
        showTrails: Bool = true
    ) {
        self.groupId = groupId
        self.key = key
        self.selfId = selfId
        self.server = server
        self.icon = icon
        self.color = color
        self.showTrails = showTrails
    }

    /// A group created now by this device: random id, key and member id.
    public static func create(server: RelayConfig? = nil, random: any SecureRandomSource = SystemSecureRandom()) -> GroupRecord {
        let groupId = random.nextBytes(PairingPayload.groupIdBytes)
        let key = GroupCrypto.randomKey(random)
        let member = newMemberId(random)
        return GroupRecord(groupId, key, member, server: server)
    }

    /// A random member id (never 0, which stands for "nobody").
    public static func newMemberId(_ random: any SecureRandomSource = SystemSecureRandom()) -> Int64 {
        var id: Int64
        repeat {
            id = random.nextLong()
        } while id == 0
        return id
    }
}

/// The persisted form: a JSON document with the settings and the current group, if any.
public struct ShareState: Equatable, Hashable, Sendable {
    private static let maxIntervalS = 1800
    private static let maxMoveM = 1000

    public let settings: ShareSettings
    public let group: GroupRecord?

    public init(settings: ShareSettings = ShareSettings(), group: GroupRecord? = nil) {
        self.settings = settings
        self.group = group
    }

    /// The document as kotlinx.serialization writes it on Android, key for key.
    public func toJson() -> String {
        let s = settings
        var out = "{\"settings\":{"
        out += "\"name\":" + P2PJsonText.quote(s.name)
        out += ",\"colorIndex\":\(s.colorIndex)"
        out += ",\"intervalSec\":\(s.intervalSec)"
        out += ",\"minMoveMeters\":\(s.minMoveMeters)"
        out += ",\"stun\":" + (s.stun ? "true" : "false")
        out += ",\"server\":" + ShareState.serverJson(s.server)
        out += ",\"icon\":\(s.icon)"
        out += ",\"color\":" + (s.color.map { String($0) } ?? "null")
        out += ",\"showTrails\":" + (s.showTrails ? "true" : "false")
        out += ",\"picture\":" + (s.picture.map { P2PJsonText.quote($0) } ?? "null")
        out += "},\"group\":"
        if let g = group {
            out += "{\"id\":" + P2PJsonText.quote(P2PHex.encode(g.groupId))
            out += ",\"key\":" + P2PJsonText.quote(P2PHex.encode(g.key))
            out += ",\"self\":" + P2PJsonText.quote(P2PHex.long(g.selfId))
            out += ",\"server\":" + ShareState.serverJson(g.server) + "}"
        } else {
            out += "null"
        }
        return out + "}"
    }

    private static func serverJson(_ server: RelayConfig?) -> String {
        guard let server else { return "null" }
        return "{\"dbUrl\":" + P2PJsonText.quote(server.dbUrl) + ",\"apiKey\":" + P2PJsonText.quote(server.apiKey) + "}"
    }

    /// The state in `json`; a damaged or empty document gives the defaults (and no group).
    public static func fromJson(_ json: String) -> ShareState {
        do {
            let root = try P2PJson.parse(json)
            guard let object = root.objectValue else { return ShareState() }
            var settings = ShareSettings()
            if let value = object["settings"] {
                settings = try ShareStateDecoding.settings(value)
            }
            var group: GroupRecord?
            if let value = object["group"], let raw = try ShareStateDecoding.group(value) {
                guard let id = P2PHex.decode(raw.id), let key = P2PHex.decode(raw.key) else { return ShareState() }
                if id.count == PairingPayload.groupIdBytes, key.count == GroupCrypto.keyBytes {
                    guard let selfId = P2PHex.parseUnsignedLong(raw.selfHex) else { return ShareState() }
                    let server = raw.server.flatMap { $0.isUsable ? $0 : nil }
                    group = GroupRecord(id, key, selfId, server: server)
                }
            }
            return ShareState(settings: sanitized(settings), group: group)
        } catch {
            return ShareState()
        }
    }

    private static func sanitized(_ s: ShareSettings) -> ShareSettings {
        let pictureOk = s.pictureBytes() != nil
        var out = s
        out.name = String(decoding: Array(s.name.utf16.prefix(PairingCodec.maxNameBytes)), as: UTF16.self)
        let n = MemberPalette.colors.count
        out.colorIndex = ((s.colorIndex % n) + n) % n
        if s.icon >= 0, s.icon < MemberLook.icons.count {
            out.icon = s.icon
        } else if s.icon == MemberLook.picture, pictureOk {
            out.icon = s.icon
        } else {
            out.icon = MemberLook.initials
        }
        out.picture = pictureOk ? s.picture : nil
        out.color = s.color.map(MemberLook.argb)
        out.intervalSec = Swift.min(Swift.max(s.intervalSec, ShareCadence.minIntervalS), maxIntervalS)
        out.minMoveMeters = Swift.min(Swift.max(s.minMoveMeters, 0), maxMoveM)
        out.server = s.server.map { RelayConfig.of($0.dbUrl, $0.apiKey) }.flatMap { $0.isUsable ? $0 : nil }
        return out
    }
}

/// The decoding rules of kotlinx.serialization for the saved state: a missing key takes its default, `null` is only
/// accepted for a nullable value, numbers may be quoted, booleans may be quoted and in any case.
private enum ShareStateDecoding {
    struct Failure: Error {}

    struct GroupJson {
        let id: String
        let key: String
        let selfHex: String
        let server: RelayConfig?
    }

    static func object(_ v: P2PJson) throws -> P2PJsonObject {
        guard let o = v.objectValue else { throw Failure() }
        return o
    }

    static func string(_ v: P2PJson) throws -> String {
        guard case let .string(text) = v else { throw Failure() }
        return text
    }

    static func int(_ v: P2PJson) throws -> Int {
        let text: String
        switch v {
        case let .literal(t), let .string(t): text = t
        default: throw Failure()
        }
        guard let long = P2PJsonNumber.parse(text, mode: .field), let value = Int32(exactly: long) else { throw Failure() }
        return Int(value)
    }

    static func bool(_ v: P2PJson) throws -> Bool {
        switch v {
        case let .literal(t), let .string(t):
            guard let b = P2PJsonBool.parse(t) else { throw Failure() }
            return b
        default:
            throw Failure()
        }
    }

    static func relayConfig(_ v: P2PJson) throws -> RelayConfig {
        let o = try object(v)
        guard let url = o["dbUrl"], let key = o["apiKey"] else { throw Failure() }
        let dbUrl = try string(url)
        let apiKey = try string(key)
        return RelayConfig(dbUrl, apiKey)
    }

    static func settings(_ v: P2PJson) throws -> ShareSettings {
        let o = try object(v)
        var s = ShareSettings()
        if let x = o["name"] { s.name = try string(x) }
        if let x = o["colorIndex"] { s.colorIndex = try int(x) }
        if let x = o["intervalSec"] { s.intervalSec = try int(x) }
        if let x = o["minMoveMeters"] { s.minMoveMeters = try int(x) }
        if let x = o["stun"] { s.stun = try bool(x) }
        if let x = o["server"] {
            if x == .null {
                s.server = nil
            } else {
                let config = try relayConfig(x)
                s.server = config
            }
        }
        if let x = o["icon"] { s.icon = try int(x) }
        if let x = o["color"] {
            if x == .null {
                s.color = nil
            } else {
                let c = try int(x)
                s.color = Int32(c)
            }
        }
        if let x = o["showTrails"] { s.showTrails = try bool(x) }
        if let x = o["picture"] {
            if x == .null {
                s.picture = nil
            } else {
                let text = try string(x)
                s.picture = text
            }
        }
        return s
    }

    /// `nil` for a `null` group.
    static func group(_ v: P2PJson) throws -> GroupJson? {
        if v == .null { return nil }
        let o = try object(v)
        guard let id = o["id"], let key = o["key"], let selfValue = o["self"] else { throw Failure() }
        var server: RelayConfig?
        if let x = o["server"], x != .null {
            server = try relayConfig(x)
        }
        let idText = try string(id)
        let keyText = try string(key)
        let selfText = try string(selfValue)
        return GroupJson(id: idText, key: keyText, selfHex: selfText, server: server)
    }
}
