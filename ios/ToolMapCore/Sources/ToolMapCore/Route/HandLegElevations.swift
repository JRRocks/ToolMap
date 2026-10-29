// Port of core/src/main/kotlin/fr/toolmap/core/route/HandLegElevations.kt.

import Foundation

/// Elevations for the legs drawn by hand (`docs/specs/routing.md` §5.6). BRouter's legs carry the elevations of its own
/// data; a straight leg has none, so its points are given those of the terrain tiles, like the points of a track (P5.5).
///
/// In a route that follows the ways, a straight leg is sampled every `spacingMeters` along its line (straight on the
/// map), so that a ridge or a valley between its two stops counts in the climb. A route wholly drawn by hand is stored as
/// its stops (ADR-065): only its stops take an elevation, and the figures of the editor stay those of the saved route.
///
/// A leg whose points cannot all have an elevation (a tile missing offline) is left as it is: none rather than half.
/// What was found is kept by the ends of the leg, so that undo, redo or a leg turned round do not ask again. Not
/// thread-safe: used from the main thread.
public final class HandLegElevations {
    /// A straight line from one stop to the next; `dense` when it is sampled along, not just at its ends.
    public struct Key: Hashable, Sendable {
        public let ends: LegKey
        public let dense: Bool

        public init(ends: LegKey, dense: Bool) {
            self.ends = ends
            self.dense = dense
        }

        public var reversed: Key {
            Key(
                ends: LegKey(fromLat: ends.toLat, fromLon: ends.toLon, toLat: ends.fromLat, toLon: ends.fromLon, profile: ends.profile),
                dense: dense
            )
        }
    }

    /// Terrarium is a 30 m model (SRTM): closer samples would add nothing.
    public static let spacingMeters = 30.0

    /// A very long leg is sampled more loosely, so that it stays a few hundred points.
    public static let maxPoints = 500

    private var known: RouteLru<Key, [TrackPoint]>
    private var failed = Set<Key>()

    public init(capacity: Int = 256) {
        known = RouteLru(capacity: capacity)
    }

    /// The legs of `plan` that need elevations and are not known yet, in order, each with the points to give one to (no
    /// elevation yet); a leg that failed before is not asked again until `forgetFailures`. Kotlin returns an ordered map.
    public func wanted(_ plan: RoutePlan) -> [(key: Key, points: [TrackPoint])] {
        var out: [(key: Key, points: [TrackPoint])] = []
        var listed = Set<Key>()
        let dense = !plan.isManual
        for (i, leg) in plan.legs.enumerated() {
            if !HandLegElevations.needs(leg, dense: dense) { continue }
            let key = keyOf(plan, i, dense)
            if listed.contains(key) || failed.contains(key) || lookup(key) != nil { continue }
            listed.insert(key)
            let points = dense ? HandLegElevations.samples(plan.stops[i].point, plan.stops[i + 1].point) : ends(plan, i)
            out.append((key: key, points: points))
        }
        return out
    }

    /// The answer for `key`: kept when every point has an elevation, else the leg is given up.
    public func put(_ key: Key, _ points: [TrackPoint]) {
        if points.count >= 2 && points.allSatisfy({ $0.ele != nil }) {
            failed.remove(key)
            known.put(key, points)
        } else {
            failed.insert(key)
        }
    }

    /// Tiles may come later (online again, area downloaded): the legs given up may be asked again.
    public func forgetFailures() {
        failed.removeAll()
    }

    /// `plan` with the elevations known for its legs drawn by hand; the same plan when nothing changes.
    public func apply(_ plan: RoutePlan) -> RoutePlan {
        applied(plan) ?? plan
    }

    /// `plan` with the elevations known for its legs drawn by hand, or `nil` when nothing changes (Kotlin compares the
    /// instance `apply` gives back with the plan).
    func applied(_ plan: RoutePlan) -> RoutePlan? {
        let dense = !plan.isManual
        var changed = false
        var legs: [RouteLeg] = []
        legs.reserveCapacity(plan.legs.count)
        for (i, leg) in plan.legs.enumerated() {
            if HandLegElevations.needs(leg, dense: dense), let points = lookup(keyOf(plan, i, dense)) {
                changed = true
                legs.append(leg.copy(points: points))
            } else {
                legs.append(leg)
            }
        }
        return changed ? plan.copy(legs: legs) : nil
    }

    private func lookup(_ key: Key) -> [TrackPoint]? {
        if let points = known.get(key) { return points }
        if let points = known.get(key.reversed) { return Array(points.reversed()) }
        return nil
    }

    private func keyOf(_ plan: RoutePlan, _ leg: Int, _ dense: Bool) -> Key {
        Key(ends: LegKey.of(plan.stops[leg], plan.stops[leg + 1], ""), dense: dense)
    }

    private func ends(_ plan: RoutePlan, _ leg: Int) -> [TrackPoint] {
        [plan.stops[leg].point, plan.stops[leg + 1].point]
    }

    /// A leg drawn by hand still lacking elevations: one of its points has none, or (when `dense`) it is still its two
    /// ends only while its line is long enough to be sampled.
    public static func needs(_ leg: RouteLeg, dense: Bool) -> Bool {
        if !leg.isByHand || leg.points.count < 2 { return false }
        if leg.points.contains(where: { $0.ele == nil }) { return true }
        return dense && leg.points.count == 2 && sampleCount(leg.points[0], leg.points[1]) > 2
    }

    /// How many points `samples` gives between `from` and `to`, both included.
    public static func sampleCount(_ from: TrackPoint, _ to: TrackPoint) -> Int {
        let steps = kotlinToInt(ceil(Geodesy.distance(from, to) / spacingMeters))
        return RouteMath.coerce(steps, 1, maxPoints - 1) + 1
    }

    /// Points along the straight line from `from` to `to` as the map draws it (linear in Web Mercator), at most
    /// `spacingMeters` apart; the ends are `from` and `to` themselves, without elevation.
    public static func samples(_ from: TrackPoint, _ to: TrackPoint) -> [TrackPoint] {
        let n = sampleCount(from, to) - 1
        let y0 = mercatorY(from.lat)
        let y1 = mercatorY(to.lat)
        var out: [TrackPoint] = []
        out.reserveCapacity(n + 1)
        for k in 0 ... n {
            if k == 0 {
                out.append(TrackPoint(lat: from.lat, lon: from.lon))
            } else if k == n {
                out.append(TrackPoint(lat: to.lat, lon: to.lon))
            } else {
                let t = Double(k) / Double(n)
                out.append(TrackPoint(lat: latitudeOf(y0 + (y1 - y0) * t), lon: from.lon + (to.lon - from.lon) * t))
            }
        }
        return out
    }

    /// Web Mercator northing on the unit sphere: only ratios matter here.
    private static func mercatorY(_ lat: Double) -> Double {
        log(tan(Double.pi / 4 + Angle.radians(lat) / 2))
    }

    private static func latitudeOf(_ y: Double) -> Double {
        Angle.degrees(2 * atan(exp(y)) - Double.pi / 2)
    }
}
