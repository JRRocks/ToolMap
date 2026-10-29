// Port of core/src/main/kotlin/fr/toolmap/core/route/Routing.kt.

import Foundation

/// Why BRouter gave no way.
public enum RoutingError: String, Hashable, Sendable {
    /// No routing data (`.rd5`) for the area: they are downloaded in BRouter.
    case noData = "NO_DATA"
    /// The profile is not among those of BRouter.
    case unknownProfile = "UNKNOWN_PROFILE"
    /// No way between the two points (island, forbidden area).
    case noPath = "NO_PATH"
    case timeout = "TIMEOUT"
    /// The BRouter app is not installed (Android only).
    case notInstalled = "NOT_INSTALLED"
    case other = "OTHER"
}

/// The answer for one leg.
public enum LegResult: Hashable, Sendable {
    case ok(Ok)
    case failed(Failed)

    /// Kotlin `LegResult.Ok`.
    public struct Ok: Hashable, Sendable {
        public let points: [TrackPoint]
        public let timeMillis: Int64?

        public init(_ points: [TrackPoint], _ timeMillis: Int64?) {
            self.points = points
            self.timeMillis = timeMillis
        }
    }

    /// Kotlin `LegResult.Failed`.
    public struct Failed: Hashable, Sendable {
        public let error: RoutingError
        public let message: String

        public init(_ error: RoutingError, _ message: String) {
            self.error = error
            self.message = message
        }
    }
}

/// Computes the way between two points (BRouter: the AIDL service on Android, the web server on iPhone; a fake in the
/// tests), going round `zones` (P19.8).
public protocol RoutingEngine: Sendable {
    func route(from: RouteStop, to: RouteStop, profile: String, zones: [AvoidZone]) async -> LegResult
}

extension RoutingEngine {
    /// Without zones to avoid (Kotlin's default `zones = emptyList()`).
    public func route(from: RouteStop, to: RouteStop, profile: String) async -> LegResult {
        await route(from: from, to: to, profile: profile, zones: [])
    }
}

/// The BRouter profiles offered by name; others can be typed (personal profiles of BRouter).
public enum RoutingProfiles {
    public static let hiking = "hiking-mountain"
    public static let bike = "trekking"
    public static let roadBike = "fastbike"
    public static let mtb = "mtb"
    public static let car = "car-fast"
    public static let shortest = "shortest"

    public static let builtIn: [String] = [hiking, bike, roadBike, mtb, car, shortest]

    /// Walking profiles, whose time is estimated by DIN 33466 rather than taken from BRouter.
    public static func isWalking(_ profile: String) -> Bool { profile == hiking }
}

/// Results of the legs computed during an edit, the most recently used kept: undo, redo, reversing twice or going back
/// over a way already computed do not ask BRouter again.
public final class LegCache: @unchecked Sendable {
    public static let defaultCapacity = 200

    private let lock = NSLock()
    private var map: RouteLru<LegKey, LegResult.Ok>

    public init(capacity: Int = LegCache.defaultCapacity) {
        map = RouteLru(capacity: capacity)
    }

    public func get(_ key: LegKey) -> LegResult.Ok? {
        lock.lock()
        defer { lock.unlock() }
        return map.get(key)
    }

    public func put(_ key: LegKey, _ result: LegResult.Ok) {
        lock.lock()
        defer { lock.unlock() }
        map.put(key, result)
    }

    public func size() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return map.count
    }
}
