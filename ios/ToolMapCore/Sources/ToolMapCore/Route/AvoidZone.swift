// Port of core/src/main/kotlin/fr/toolmap/core/route/AvoidZone.kt.

import Foundation

/// A circle the routed legs must not enter (« Zone à éviter », P19.8, `docs/specs/routing.md` §5.8): one of the `nogos`
/// of BRouter. Legs drawn by hand are not bound by it.
public struct AvoidZone: Hashable, Sendable {
    /// The sizes the handle gives (P30.4): from 50 m to 2 km, in steps of 50 m.
    public static let minResize = 50
    public static let maxResize = 2_000
    public static let resizeStep = 50

    public let lat: Double
    public let lon: Double
    public let radiusMeters: Int

    /// Kotlin's `require(radiusMeters > 0)`: a zone without area is a programming error.
    public init(lat: Double, lon: Double, radiusMeters: Int) {
        precondition(radiusMeters > 0, "radius \(radiusMeters)")
        self.lat = lat
        self.lon = lon
        self.radiusMeters = radiusMeters
    }

    /// Kotlin `copy(...)`.
    public func copy(lat: Double? = nil, lon: Double? = nil, radiusMeters: Int? = nil) -> AvoidZone {
        AvoidZone(lat: lat ?? self.lat, lon: lon ?? self.lon, radiusMeters: radiusMeters ?? self.radiusMeters)
    }

    public func contains(_ lat: Double, _ lon: Double) -> Bool {
        Geodesy.distance(self.lat, self.lon, lat, lon) <= Double(radiusMeters)
    }

    /// The line `points` passes through the circle.
    public func crosses(_ points: [TrackPoint]) -> Bool {
        if points.count == 1 { return contains(points[0].lat, points[0].lon) }
        for j in stride(from: 1, to: points.count, by: 1) {
            if RoutePlan.distanceToSegment(lat, lon, points[j - 1], points[j]) <= Double(radiusMeters) { return true }
        }
        return false
    }

    /// `other` lies wholly inside this circle: a way that kept out of this one kept out of `other` too.
    public func covers(_ other: AvoidZone) -> Bool {
        Geodesy.distance(lat, lon, other.lat, other.lon) + Double(other.radiusMeters) <= Double(radiusMeters)
    }

    /// The point of the circle of `radius` (the zone's own by default) due east of the centre: where the handle that
    /// resizes the zone sits (P30.4, variant C of `docs/previews/p30-itineraires.html`).
    public func east(_ radius: Int? = nil) -> TrackPoint {
        let perDegree = Angle.radians(1.0) * Geodesy.earthRadiusMeters
        let r = Double(radius ?? radiusMeters)
        return TrackPoint(lat: lat, lon: lon + r / perDegree / cos(Angle.radians(lat)))
    }

    /// The radius the handle gives when the finger is `meters` from the centre: rounded to 50 m, 50 m to 2 km.
    public static func resized(_ meters: Double) -> Int {
        let steps = RouteMath.roundToInt(meters / Double(resizeStep))
        return RouteMath.coerce(steps * resizeStep, minResize, maxResize)
    }

    /// The `zones` a leg from `from` to `to` can go round: one that holds an end of the leg is left out, since BRouter
    /// finds no way out of it (« pas de chemin », checked with BRouter 1.7.10) and the leg has to reach its stop.
    public static func around(_ zones: [AvoidZone], _ from: RouteStop, _ to: RouteStop) -> [AvoidZone] {
        zones.filter { !$0.contains(from.lat, from.lon) && !$0.contains(to.lat, to.lon) }
    }

    /// The `nogos` of BRouter: `lon,lat,radius` (radius in whole meters) joined by `|`; empty without zone.
    public static func nogos(_ zones: [AvoidZone]) -> String {
        zones.map { zone in
            "\(TrackNumberText.fixed(zone.lon, 7)),\(TrackNumberText.fixed(zone.lat, 7)),\(zone.radiusMeters)"
        }.joined(separator: "|")
    }
}
