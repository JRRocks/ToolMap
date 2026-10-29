// Port of core/src/main/kotlin/fr/toolmap/core/alert/AlertState.kt. The JSON is the one kotlinx.serialization writes on
// Android (`encodeDefaults`, `ignoreUnknownKeys`, `coerceInputValues`): same keys, nulls written, a missing or null value
// taking its default.

import Foundation

public enum FollowKind: String, Hashable, Sendable, Codable {
    case track = "TRACK"
    case route = "ROUTE"
}

/// The line being followed: a stored track or route, walked forwards or backwards.
public struct FollowRef: Hashable, Sendable, Codable {
    public let kind: FollowKind
    public let id: Int64
    public let reversed: Bool

    public init(kind: FollowKind, id: Int64, reversed: Bool = false) {
        self.kind = kind
        self.id = id
        self.reversed = reversed
    }

    private enum CodingKeys: String, CodingKey {
        case kind, id, reversed
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.decode(FollowKind.self, forKey: .kind)
        id = try c.decode(Int64.self, forKey: .id)
        reversed = try c.decodeIfPresent(Bool.self, forKey: .reversed) ?? false
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(kind, forKey: .kind)
        try c.encode(id, forKey: .id)
        try c.encode(reversed, forKey: .reversed)
    }
}

/// A waypoint to be warned about; without `radiusMeters` the default radius of the settings applies.
public struct WatchEntry: Hashable, Sendable, Codable {
    public let waypointId: Int64
    public let radiusMeters: Int?

    public init(waypointId: Int64, radiusMeters: Int? = nil) {
        self.waypointId = waypointId
        self.radiusMeters = radiusMeters
    }

    private enum CodingKeys: String, CodingKey {
        case waypointId, radiusMeters
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        waypointId = try c.decode(Int64.self, forKey: .waypointId)
        radiusMeters = try c.decodeIfPresent(Int.self, forKey: .radiusMeters)
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(waypointId, forKey: .waypointId)
        if let radiusMeters {
            try c.encode(radiusMeters, forKey: .radiusMeters)
        } else {
            try c.encodeNil(forKey: .radiusMeters)
        }
    }
}

/// What the user asked the alerts to watch: kept across restarts, so that a walk can go on after the app is killed.
public struct AlertState: Hashable, Sendable, Codable {
    public let follow: FollowRef?
    public let watches: [WatchEntry]

    public init(follow: FollowRef? = nil, watches: [WatchEntry] = []) {
        self.follow = follow
        self.watches = watches
    }

    public var isIdle: Bool { follow == nil && watches.isEmpty }

    public func watching(_ waypointId: Int64) -> WatchEntry? {
        watches.first { $0.waypointId == waypointId }
    }

    /// Watches the waypoint, or changes its radius when it is already watched.
    public func withWatch(_ waypointId: Int64, _ radiusMeters: Int?) -> AlertState {
        AlertState(
            follow: follow,
            watches: watches.filter { $0.waypointId != waypointId } + [WatchEntry(waypointId: waypointId, radiusMeters: radiusMeters)]
        )
    }

    public func withoutWatch(_ waypointId: Int64) -> AlertState {
        AlertState(follow: follow, watches: watches.filter { $0.waypointId != waypointId })
    }

    /// Forgets what refers to the given items, which were deleted.
    public func removing(trackIds: Set<Int64>, routeIds: Set<Int64>, waypointIds: Set<Int64>) -> AlertState {
        var dropFollow = false
        if let current = follow {
            switch current.kind {
            case .track: dropFollow = trackIds.contains(current.id)
            case .route: dropFollow = routeIds.contains(current.id)
            }
        }
        return AlertState(follow: dropFollow ? nil : follow, watches: watches.filter { !waypointIds.contains($0.waypointId) })
    }

    /// Keeps only what refers to items that exist: `removing` with everything not listed.
    public func pruned(trackIds: Set<Int64>, routeIds: Set<Int64>, waypointIds: Set<Int64>) -> AlertState {
        var keepFollow = false
        if let current = follow {
            switch current.kind {
            case .track: keepFollow = trackIds.contains(current.id)
            case .route: keepFollow = routeIds.contains(current.id)
            }
        }
        return AlertState(follow: keepFollow ? follow : nil, watches: watches.filter { waypointIds.contains($0.waypointId) })
    }

    private enum CodingKeys: String, CodingKey {
        case follow, watches
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        follow = try c.decodeIfPresent(FollowRef.self, forKey: .follow)
        watches = try c.decodeIfPresent([WatchEntry].self, forKey: .watches) ?? []
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        if let follow {
            try c.encode(follow, forKey: .follow)
        } else {
            try c.encodeNil(forKey: .follow)
        }
        try c.encode(watches, forKey: .watches)
    }
}

public enum AlertStateCodec {
    public static func encode(_ state: AlertState) -> String {
        guard let data = try? JSONEncoder().encode(state) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Never throws: a missing or damaged file means nothing is watched.
    public static func decodeOrEmpty(_ text: String?) -> AlertState {
        guard let content = text, !kotlinTrim(content).isEmpty else { return AlertState() }
        return (try? JSONDecoder().decode(AlertState.self, from: Data(content.utf8))) ?? AlertState()
    }
}
