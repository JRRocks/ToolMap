// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackOps.kt.

/// Whole-track operations of the editor (reverse, split, merge, trim, thin out). Pure functions on lists of points:
/// nothing is stored here, the caller decides what to do with the results.
public enum TrackOps {
    /// The track walked the other way: segments and points in reverse order. Times are dropped: they would run
    /// backwards, which breaks speeds and durations, and a reversed track was never recorded in that direction.
    public static func reverse(_ segments: [[TrackPoint]]) -> [[TrackPoint]] {
        segments.reversed().map { segment in
            segment.reversed().map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele, timeMillis: nil, extras: $0.extras) }
        }
    }

    /// Every point on one segment, in order: closes the gaps between segments.
    public static func join(_ segments: [[TrackPoint]]) -> [TrackPoint] {
        Array(segments.joined())
    }

    /// Cuts `points` at `index`: the first part ends with that point and the second starts with it, so that the two
    /// tracks touch. Both parts have at least two points, so `index` must be strictly inside the line.
    public static func split(_ points: [TrackPoint], _ index: Int) throws -> (first: [TrackPoint], second: [TrackPoint]) {
        try require(index >= 1 && index < points.count - 1, "cannot split at \(index) of \(points.count) points")
        return (Array(points[0 ... index]), Array(points[index...]))
    }

    /// `second` after `first`. The two stay separate segments (a gap between them is not walked) unless the end of the
    /// first and the start of the second are within `joinWithinMeters`, in which case they become one segment and the
    /// duplicated point is kept once.
    public static func merge(
        _ first: [[TrackPoint]],
        _ second: [[TrackPoint]],
        joinWithinMeters: Double = 0.0
    ) -> [[TrackPoint]] {
        let a = first.filter { !$0.isEmpty }
        let b = second.filter { !$0.isEmpty }
        guard let lastSegment = a.last, let end = lastSegment.last else { return b }
        guard let firstSegment = b.first, let start = firstSegment.first else { return a }
        if joinWithinMeters > 0.0, Geodesy.distance(end, start) <= joinWithinMeters {
            let drop = end.lat == start.lat && end.lon == start.lon ? 1 : 0
            let joined = lastSegment + firstSegment.dropFirst(drop)
            return Array(a.dropLast()) + [joined] + Array(b.dropFirst())
        }
        return a + b
    }

    /// The points from `from` to `to`, both included (the ends are cropped away).
    public static func trim(_ points: [TrackPoint], _ from: Int, _ to: Int) throws -> [TrackPoint] {
        try require(
            from >= 0 && from < points.count && to >= 0 && to < points.count && from <= to,
            "bad range \(from)..\(to) of \(points.count)"
        )
        return Array(points[from ... to])
    }

    /// Douglas-Peucker thinning, ends kept; points that carry times or extensions are not special.
    public static func simplify(_ points: [TrackPoint], _ toleranceMeters: Double) -> [TrackPoint] {
        PathSimplifier.simplify(points, toleranceMeters)
    }

    /// Point halfway between two others: positions, elevations and times averaged when both sides have them.
    public static func midpoint(_ a: TrackPoint, _ b: TrackPoint) -> TrackPoint {
        var ele: Double?
        if let ea = a.ele, let eb = b.ele { ele = (ea + eb) / 2 }
        var time: Int64?
        if let ta = a.timeMillis, let tb = b.timeMillis { time = (ta &+ tb) / 2 }
        return TrackPoint(lat: (a.lat + b.lat) / 2, lon: (a.lon + b.lon) / 2, ele: ele, timeMillis: time)
    }

    /// Length of a polyline in meters.
    public static func length(_ points: [TrackPoint]) -> Double {
        var total = 0.0
        if points.count > 1 {
            for i in 1 ..< points.count {
                total += Geodesy.distance(points[i - 1], points[i])
            }
        }
        return total
    }
}
