// Port of core/src/main/kotlin/fr/toolmap/core/route/RoutePlan.kt.

import Foundation

/// A point placed by the user: a discreet passage that forces the way, or a named stop (name and waypoint icon).
public enum StopKind: String, Hashable, Sendable {
    case passage = "PASSAGE"
    case named = "NAMED"
}

/// A point of interest of a route (P30.1, variant B of `docs/previews/p30-itineraires.html`): a name and a waypoint icon
/// at a place along the route or beside it (a spring 80 m off the path). Unlike a named stop it does not change the way.
public struct RoutePoi: Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let name: String?
    /// GPX symbol: its waypoint icon.
    public let sym: String?

    public init(lat: Double, lon: Double, name: String? = nil, sym: String? = nil) {
        self.lat = lat
        self.lon = lon
        self.name = name
        self.sym = sym
    }

    public var point: TrackPoint { TrackPoint(lat: lat, lon: lon) }
}

/// Where a point of interest stands along a route: `alongMeters` from the start, `offMeters` beside the way.
public struct PoiPlace: Hashable, Sendable {
    public let alongMeters: Double
    public let offMeters: Double

    public init(alongMeters: Double, offMeters: Double) {
        self.alongMeters = alongMeters
        self.offMeters = offMeters
    }
}

public struct RouteStop: Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let kind: StopKind
    public let name: String?
    /// GPX symbol of a named stop: its waypoint icon.
    public let sym: String?

    public init(lat: Double, lon: Double, kind: StopKind = .passage, name: String? = nil, sym: String? = nil) {
        self.lat = lat
        self.lon = lon
        self.kind = kind
        self.name = name
        self.sym = sym
    }

    public var point: TrackPoint { TrackPoint(lat: lat, lon: lon) }

    /// The same stop as a passage: no name, no icon.
    public func discreet() -> RouteStop { RouteStop(lat: lat, lon: lon) }

    /// Kotlin `copy(lat = …, lon = …)`: the same stop somewhere else.
    public func copy(lat: Double? = nil, lon: Double? = nil) -> RouteStop {
        RouteStop(lat: lat ?? self.lat, lon: lon ?? self.lon, kind: kind, name: name, sym: sym)
    }
}

/// How a leg is drawn: by BRouter along the ways, or by hand as a straight line.
public enum LegMode: String, Hashable, Sendable {
    case routed = "ROUTED"
    case straight = "STRAIGHT"
}

/// Where a leg stands. `pending`: BRouter is asked (a straight line is shown meanwhile). `noData` and `failed`: BRouter
/// could not give a way (no data for the area, no path, time out), the leg is a straight line until recomputed.
public enum LegState: String, Hashable, Sendable {
    case ok = "OK"
    case pending = "PENDING"
    case noData = "NO_DATA"
    case failed = "FAILED"
}

/// The way between two consecutive stops. `points` start exactly on the first stop and end exactly on the second.
public struct RouteLeg: Hashable, Sendable {
    public let profile: String
    public let mode: LegMode
    public let state: LegState
    public let points: [TrackPoint]
    /// Travel time given by BRouter; `nil` for a leg drawn by hand.
    public let timeMillis: Int64?

    public init(profile: String, mode: LegMode, state: LegState, points: [TrackPoint], timeMillis: Int64? = nil) {
        self.profile = profile
        self.mode = mode
        self.state = state
        self.points = points
        self.timeMillis = timeMillis
    }

    /// Drawn as a straight line: by choice, or because BRouter gave no way.
    public var isByHand: Bool { mode == .straight || state == .noData || state == .failed }

    /// Kotlin `copy(points = …)` / `copy(state = …)`.
    public func copy(points: [TrackPoint]? = nil, state: LegState? = nil) -> RouteLeg {
        RouteLeg(profile: profile, mode: mode, state: state ?? self.state, points: points ?? self.points, timeMillis: timeMillis)
    }

    public static func straight(
        _ from: RouteStop,
        _ to: RouteStop,
        _ profile: String,
        mode: LegMode = .straight,
        state: LegState = .ok
    ) -> RouteLeg {
        RouteLeg(profile: profile, mode: mode, state: state, points: [from.point, to.point])
    }
}

/// What identifies the computation of a leg: its ends to a millionth of a degree (about 10 cm), the profile and the zones
/// it goes round (the `nogos` sent to BRouter, empty without zone; `AvoidZone.around`).
public struct LegKey: Hashable, Sendable {
    private static let scale = 1e6

    public let fromLat: Int
    public let fromLon: Int
    public let toLat: Int
    public let toLon: Int
    public let profile: String
    public let nogos: String

    public init(fromLat: Int, fromLon: Int, toLat: Int, toLon: Int, profile: String, nogos: String = "") {
        self.fromLat = fromLat
        self.fromLon = fromLon
        self.toLat = toLat
        self.toLon = toLon
        self.profile = profile
        self.nogos = nogos
    }

    public static func of(_ from: RouteStop, _ to: RouteStop, _ profile: String, zones: [AvoidZone] = []) -> LegKey {
        LegKey(
            fromLat: kotlinRound(from.lat * scale),
            fromLon: kotlinRound(from.lon * scale),
            toLat: kotlinRound(to.lat * scale),
            toLon: kotlinRound(to.lon * scale),
            profile: profile,
            nogos: AvoidZone.nogos(AvoidZone.around(zones, from, to))
        )
    }
}

/// The stops the user placed and, between two consecutive ones, a leg: what a computed route is made from, with the
/// `zones` its routed legs go round. Its `geometry` (the legs put end to end) is what is drawn, followed, printed and
/// exported.
public struct RoutePlan: Hashable, Sendable {
    /// Ends closer than this make a loop.
    public static let loopMeters = 1.0

    public let stops: [RouteStop]
    public let legs: [RouteLeg]
    public let zones: [AvoidZone]
    /// The points of interest of the route (P30.1): never sent to BRouter, they do not change the way.
    public let pois: [RoutePoi]

    /// The legs end to end; the point shared by two legs is kept once. One stop alone is a single point. Computed once,
    /// as Kotlin's `by lazy`.
    public let geometry: [TrackPoint]

    /// Index in `geometry` of every stop.
    public let stopIndices: [Int]

    /// Kotlin's `require(legs.size == maxOf(0, stops.size - 1))`: a plan that breaks it is a programming error.
    public init(stops: [RouteStop] = [], legs: [RouteLeg] = [], zones: [AvoidZone] = [], pois: [RoutePoi] = []) {
        precondition(legs.count == Swift.max(0, stops.count - 1), "\(stops.count) stops need \(stops.count - 1) legs")
        self.stops = stops
        self.legs = legs
        self.zones = zones
        self.pois = pois
        var line: [TrackPoint] = []
        if let first = stops.first {
            if legs.isEmpty {
                line = [first.point]
            } else {
                line.reserveCapacity(legs.reduce(0) { $0 + $1.points.count })
                for (i, leg) in legs.enumerated() {
                    if i == 0 {
                        line.append(contentsOf: leg.points)
                    } else {
                        line.append(contentsOf: leg.points.dropFirst())
                    }
                }
            }
        }
        geometry = line
        var indices: [Int] = []
        indices.reserveCapacity(stops.count)
        var at = 0
        for i in stops.indices {
            indices.append(at)
            if i < legs.count { at += legs[i].points.count - 1 }
        }
        stopIndices = indices
    }

    /// Equality of the data class: stops, legs, zones and points of interest (the rest follows from them).
    public static func == (a: RoutePlan, b: RoutePlan) -> Bool {
        a.stops == b.stops && a.legs == b.legs && a.zones == b.zones && a.pois == b.pois
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(stops)
        hasher.combine(legs)
        hasher.combine(zones)
        hasher.combine(pois)
    }

    /// Kotlin `copy(...)`.
    public func copy(
        stops: [RouteStop]? = nil,
        legs: [RouteLeg]? = nil,
        zones: [AvoidZone]? = nil,
        pois: [RoutePoi]? = nil
    ) -> RoutePlan {
        RoutePlan(stops: stops ?? self.stops, legs: legs ?? self.legs, zones: zones ?? self.zones, pois: pois ?? self.pois)
    }

    /// The same plan with other stops, legs and zones: its points of interest stay.
    public func with(_ stops: [RouteStop], _ legs: [RouteLeg], zones: [AvoidZone]? = nil) -> RoutePlan {
        RoutePlan(stops: stops, legs: legs, zones: zones ?? self.zones, pois: pois)
    }

    public func key(_ leg: Int) -> LegKey {
        LegKey.of(stops[leg], stops[leg + 1], legs[leg].profile, zones: zones)
    }

    /// The zones leg `leg` goes round: those that hold neither of its stops (P19.8).
    public func legZones(_ leg: Int) -> [AvoidZone] {
        AvoidZone.around(zones, stops[leg], stops[leg + 1])
    }

    /// Legs BRouter still has to compute, in order.
    public var pending: [Int] { legs.indices.filter { legs[$0].state == .pending } }

    /// Every leg drawn by hand by choice: a route drawn as before, with no routing in it.
    public var isManual: Bool { legs.allSatisfy { $0.mode == .straight } }

    /// Distance from the start to each stop, along the geometry.
    public func stopDistances() -> [Double] {
        var total = 0.0
        var out: [Double] = []
        out.reserveCapacity(stops.count)
        for i in stops.indices {
            out.append(total)
            if i < legs.count {
                let p = legs[i].points
                for j in stride(from: 1, to: p.count, by: 1) {
                    total += Geodesy.distance(p[j - 1], p[j])
                }
            }
        }
        return out
    }

    /// The leg whose line passes nearest to a position, with that distance in meters; `nil` without legs.
    public func nearestLeg(_ lat: Double, _ lon: Double) -> (leg: Int, meters: Double)? {
        var bestLeg = -1
        var bestDistance = Double.greatestFiniteMagnitude
        for (i, leg) in legs.enumerated() {
            let p = leg.points
            for j in stride(from: 1, to: p.count, by: 1) {
                let d = RoutePlan.distanceToSegment(lat, lon, p[j - 1], p[j])
                if d < bestDistance {
                    bestDistance = d
                    bestLeg = i
                }
            }
        }
        if bestLeg < 0 { return nil }
        return (leg: bestLeg, meters: bestDistance)
    }

    /// Index of the stop within `maxMeters` of a position, nearest first; `nil` when none is that close.
    public func nearestStop(_ lat: Double, _ lon: Double, _ maxMeters: Double) -> Int? {
        var best: Int?
        var bestDistance = maxMeters
        for (i, s) in stops.enumerated() {
            let d = Geodesy.distance(lat, lon, s.lat, s.lon)
            if d <= bestDistance {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /// Index of the point of interest within `maxMeters` of a position, nearest first; `nil` when none is that close.
    public func nearestPoi(_ lat: Double, _ lon: Double, _ maxMeters: Double) -> Int? {
        var best: Int?
        var bestDistance = maxMeters
        for (i, p) in pois.enumerated() {
            let d = Geodesy.distance(lat, lon, p.lat, p.lon)
            if d <= bestDistance {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /// Where point of interest `index` stands along the geometry; `nil` without a line (fewer than two points).
    public func placeOf(_ index: Int) -> PoiPlace? {
        guard pois.indices.contains(index) else { return nil }
        let poi = pois[index]
        let g = geometry
        if g.count < 2 { return nil }
        var along = 0.0
        var bestAlong = 0.0
        var bestOff = Double.greatestFiniteMagnitude
        for j in 1 ..< g.count {
            let a = g[j - 1]
            let b = g[j]
            let length = Geodesy.distance(a.lat, a.lon, b.lat, b.lon)
            let t = RoutePlan.fractionOnSegment(poi.lat, poi.lon, a, b)
            let off = RoutePlan.distanceToSegment(poi.lat, poi.lon, a, b)
            if off < bestOff {
                bestOff = off
                bestAlong = along + t * length
            }
            along += length
        }
        return PoiPlace(alongMeters: bestAlong, offMeters: bestOff)
    }

    /// The zone a position lies in, the one whose centre is nearest when several overlap; `nil` outside them all.
    public func zoneAt(_ lat: Double, _ lon: Double) -> Int? {
        zones.indices
            .filter { zones[$0].contains(lat, lon) }
            .min { Geodesy.distance(lat, lon, zones[$0].lat, zones[$0].lon) < Geodesy.distance(lat, lon, zones[$1].lat, zones[$1].lon) }
    }

    /// A loop: the last stop is back on the first.
    public var isLoop: Bool {
        guard stops.count >= 3, let first = stops.first, let last = stops.last else { return false }
        return Geodesy.distance(first.lat, first.lon, last.lat, last.lon) < RoutePlan.loopMeters
    }

    /// Distance from a position to a short segment, on a local flat projection (exact enough within a few km).
    static func distanceToSegment(_ lat: Double, _ lon: Double, _ a: TrackPoint, _ b: TrackPoint) -> Double {
        let k = Angle.radians(1.0) * Geodesy.earthRadiusMeters
        let c = cos(Angle.radians(lat))
        let ax = (a.lon - lon) * c * k
        let ay = (a.lat - lat) * k
        let bx = (b.lon - lon) * c * k
        let by = (b.lat - lat) * k
        let dx = bx - ax
        let dy = by - ay
        let len2 = dx * dx + dy * dy
        let t = len2 == 0.0 ? 0.0 : RouteMath.coerce(-(ax * dx + ay * dy) / len2, 0.0, 1.0)
        let x = ax + t * dx
        let y = ay + t * dy
        return (x * x + y * y).squareRoot()
    }

    /// Where the point of segment `a`–`b` nearest a position falls, from 0 at `a` to 1 at `b` (same projection).
    static func fractionOnSegment(_ lat: Double, _ lon: Double, _ a: TrackPoint, _ b: TrackPoint) -> Double {
        let c = cos(Angle.radians(lat))
        let ax = (a.lon - lon) * c
        let ay = a.lat - lat
        let dx = (b.lon - a.lon) * c
        let dy = b.lat - a.lat
        let len2 = dx * dx + dy * dy
        return len2 == 0.0 ? 0.0 : RouteMath.coerce(-(ax * dx + ay * dy) / len2, 0.0, 1.0)
    }
}
