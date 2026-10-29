// Port of core/src/main/kotlin/fr/toolmap/core/route/GoTo.kt.

import Foundation

/// The settings of « Aller vers un point » (P30.3, variant C of `docs/previews/p30-itineraires.html`): BRouter is asked
/// again every `intervalMillis`, and at once when the walker is more than `offRouteMeters` from the way.
public struct GoToRules: Hashable, Sendable {
    public static let defaultIntervalMillis: Int64 = 120_000
    public static let defaultOffRouteMeters = 50.0

    /// The intervals offered, in minutes (Kotlin `INTERVAL_MINUTES`).
    public static let intervalMinutesOffered = [1, 2, 5, 10]

    /// The distances off the way offered, in meters (Kotlin `OFF_ROUTE_METERS`).
    public static let offRouteMetersOffered = [25, 50, 100]

    /// The BRouter profiles of the start sheet: Randonnée, Vélo, VTT, Voiture.
    public static let profiles = [RoutingProfiles.hiking, RoutingProfiles.bike, RoutingProfiles.mtb, RoutingProfiles.car]

    private static let millisPerMinute: Int64 = 60_000

    public let intervalMillis: Int64
    public let offRouteMeters: Double

    public init(intervalMillis: Int64 = GoToRules.defaultIntervalMillis, offRouteMeters: Double = GoToRules.defaultOffRouteMeters) {
        self.intervalMillis = intervalMillis
        self.offRouteMeters = offRouteMeters
    }

    /// The rules of the settings, an unknown value taken as the nearest one offered.
    public static func of(_ intervalMinutes: Int, _ offRouteMeters: Int) -> GoToRules {
        let minutes = intervalMinutesOffered.min { abs($0 - intervalMinutes) < abs($1 - intervalMinutes) } ?? 2
        let meters = offRouteMetersOffered.min { abs($0 - offRouteMeters) < abs($1 - offRouteMeters) } ?? 50
        return GoToRules(intervalMillis: Int64(minutes) * millisPerMinute, offRouteMeters: Double(meters))
    }
}

/// A way to the target, as BRouter gave it from where the walker stood (`from`) at `computedAtMillis`.
public final class GoToWay: Sendable {
    public let points: [TrackPoint]
    public let profile: String
    /// BRouter's time for the whole way; `nil` for a walking profile (DIN 33466 is used) or when it gave none.
    public let timeMillis: Int64?
    public let computedAtMillis: Int64
    public let follower: TrackFollower

    /// How far the walker stood from the way when it was computed: BRouter starts it on the nearest path, which may be
    /// far from a walker in a field; being off the way is counted beyond that distance.
    public let startOffMeters: Double

    /// Distance along the way at each of its points, to cut what remains.
    private let cumulative: [Double]

    public init(points: [TrackPoint], profile: String, timeMillis: Int64?, computedAtMillis: Int64, from: TrackPoint? = nil) {
        self.points = points
        self.profile = profile
        self.timeMillis = timeMillis
        self.computedAtMillis = computedAtMillis
        let line = TrackFollower(points.map { LonLat(lon: $0.lon, lat: $0.lat) })
        follower = line
        if let start = from, let projection = line.project(start.lat, start.lon) {
            startOffMeters = projection.offMeters
        } else {
            startOffMeters = 0.0
        }
        var out = [Double](repeating: 0, count: points.count)
        for i in stride(from: 1, to: points.count, by: 1) {
            out[i] = out[i - 1] + Geodesy.distance(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        cumulative = out
    }

    public var totalMeters: Double { follower.totalMeters }

    /// The points of the way from `alongMeters` to its end.
    public func after(_ alongMeters: Double) -> [TrackPoint] {
        guard let first = cumulative.firstIndex(where: { $0 > alongMeters }) else {
            if let last = points.last { return [last] }
            return []
        }
        return Array(points[first...])
    }

    /// The point of the way `alongMeters` from its start.
    public func pointAt(_ alongMeters: Double) -> TrackPoint? {
        guard let only = points.first, let total = cumulative.last else { return nil }
        // Kotlin indexes before the first point of a one-point way; here that point is the answer.
        if points.count < 2 { return TrackPoint(lat: only.lat, lon: only.lon, ele: only.ele) }
        let d = RouteMath.coerce(alongMeters, 0.0, total)
        let found = cumulative.firstIndex(where: { $0 >= d }) ?? -1
        let i = Swift.min(Swift.max(found, 1), points.count - 1)
        let a = points[i - 1]
        let b = points[i]
        let span = cumulative[i] - cumulative[i - 1]
        let t = span <= 0.0 ? 0.0 : (d - cumulative[i - 1]) / span
        var ele: Double?
        if let ea = a.ele, let eb = b.ele {
            ele = ea + (eb - ea) * t
        } else {
            ele = a.ele ?? b.ele
        }
        return TrackPoint(lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t, ele: ele)
    }

    /// What is left of the way from `alongMeters`: drawn on the map from the walker's place on it.
    public func remaining(_ alongMeters: Double) -> [TrackPoint] {
        var out: [TrackPoint] = []
        if let here = pointAt(alongMeters) { out.append(here) }
        out.append(contentsOf: after(alongMeters))
        return out
    }
}

/// Where the walker stands on the way, and what remains of it.
public struct GoToProgress: Hashable, Sendable {
    /// Distance from the position to the way.
    public let offMeters: Double
    public let alongMeters: Double
    public let remainingMeters: Double
    /// Smoothed climb left; `nil` when the way has no elevation.
    public let remainingAscentMeters: Double?
    /// Time left; `nil` when it cannot be told (no BRouter time for a profile that is not walking).
    public let remainingMillis: Int64?
    /// The direction to walk: towards the way a little ahead, in degrees from the north.
    public let headingDegrees: Double
    public let offRoute: Bool
    public let arrived: Bool

    public init(
        offMeters: Double,
        alongMeters: Double,
        remainingMeters: Double,
        remainingAscentMeters: Double?,
        remainingMillis: Int64?,
        headingDegrees: Double,
        offRoute: Bool,
        arrived: Bool
    ) {
        self.offMeters = offMeters
        self.alongMeters = alongMeters
        self.remainingMeters = remainingMeters
        self.remainingAscentMeters = remainingAscentMeters
        self.remainingMillis = remainingMillis
        self.headingDegrees = headingDegrees
        self.offRoute = offRoute
        self.arrived = arrived
    }
}

/// The rules of « Aller vers un point »: what remains of the way, and when to ask BRouter again (P30.3).
public enum GoTo {
    /// Closer than this to the target is « Arrivé » (as the straight line, `Navigation`).
    public static let arrivedMeters = 15.0

    /// The arrow points at the way this far ahead of the walker.
    public static let lookAheadMeters = 40.0

    /// Off the way, BRouter is asked again at most this often: a way it cannot find is not asked for each second.
    public static let minRetryMillis: Int64 = 15_000

    private static let fullTurn = 360.0
    private static let millisPerSecond: Int64 = 1_000
    private static let secondsPerMinute: Int64 = 60

    public static func progress(
        _ way: GoToWay,
        _ lat: Double,
        _ lon: Double,
        _ rules: GoToRules,
        hintAlongMeters: Double? = nil
    ) -> GoToProgress? {
        guard let p = way.follower.project(lat, lon, hintAlongMeters: hintAlongMeters), let end = way.points.last else {
            return nil
        }
        let remaining = Swift.max(way.totalMeters - p.alongMeters, 0.0)
        let stats = TrackStatsCalculator.compute([way.remaining(p.alongMeters)])
        let ascent: Double? = stats.minEleMeters != nil ? stats.ascentMeters : nil
        var millis: Int64?
        if RoutingProfiles.isWalking(way.profile) {
            millis = Int64(stats.estimatedWalkMillis)
        } else if let time = way.timeMillis, way.totalMeters > 0 {
            millis = Int64(kotlinRound(Double(time) * remaining / way.totalMeters))
        }
        let arrived = Geodesy.distance(lat, lon, end.lat, end.lon) <= arrivedMeters
        let ahead = way.pointAt(p.alongMeters + lookAheadMeters) ?? end
        // Off the way, the arrow leads back to it; on it, along it.
        let off = p.offMeters - way.startOffMeters
        let towards = off > rules.offRouteMeters / 2 ? TrackPoint(lat: p.nearestLat, lon: p.nearestLon) : ahead
        return GoToProgress(
            offMeters: p.offMeters,
            alongMeters: p.alongMeters,
            remainingMeters: remaining,
            remainingAscentMeters: ascent,
            remainingMillis: millis,
            headingDegrees: bearing(lat, lon, towards.lat, towards.lon),
            offRoute: off > rules.offRouteMeters,
            arrived: arrived
        )
    }

    /// Whether BRouter is asked again now: never asked yet, the interval of `rules` elapsed since the last question
    /// (`askedAtMillis`; also after a question that gave no way), or the walker left the way (`minRetryMillis` at least
    /// after the last question); never once arrived.
    public static func needsWay(
        _ way: GoToWay?,
        _ progress: GoToProgress?,
        _ askedAtMillis: Int64?,
        _ nowMillis: Int64,
        _ rules: GoToRules
    ) -> Bool {
        if progress?.arrived == true { return false }
        guard let asked = askedAtMillis else { return true }
        let since = nowMillis - asked
        if since >= rules.intervalMillis { return true }
        if way != nil && progress?.offRoute == true { return since >= minRetryMillis }
        return false
    }

    /// `1 min 40`, `40 s`: the time left before the next computation of the way.
    public static func countdown(_ millis: Int64) -> String {
        let seconds = Swift.max((millis &+ (millisPerSecond - 1)) / millisPerSecond, 0)
        let m = seconds / secondsPerMinute
        let s = seconds % secondsPerMinute
        if m == 0 { return "\(s) s" }
        if s == 0 { return "\(m) min" }
        return s < 10 ? "\(m) min 0\(s)" : "\(m) min \(s)"
    }

    /// Initial bearing from a position to another, in degrees from the north (0 to 360).
    public static func bearing(_ fromLat: Double, _ fromLon: Double, _ toLat: Double, _ toLon: Double) -> Double {
        let dx = (toLon - fromLon) * cos(Angle.radians((fromLat + toLat) / 2))
        let dy = toLat - fromLat
        let degrees = Angle.degrees(atan2(dx, dy))
        return kotlinRem(degrees + fullTurn, fullTurn)
    }
}
