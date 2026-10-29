// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackPath.kt.

import Foundation

/// The points of a track's segments end to end, with the distance walked at each of them. The gap between two
/// segments (a GPS loss, a pause) is not counted as distance.
public struct TrackPath: Equatable, Sendable {
    public let points: [TrackPoint]

    /// Index in `points` of the first point of each segment.
    public let segmentStarts: [Int]

    /// Meters from the start along the path, one per point.
    public let cumulative: [Double]

    public init(_ segments: [[TrackPoint]]) {
        let points = Array(segments.joined())
        var starts: [Int] = []
        var offset = 0
        for s in segments {
            if !s.isEmpty { starts.append(offset) }
            offset += s.count
        }
        let startSet = Set(starts)
        var cumulative = [Double](repeating: 0, count: points.count)
        if points.count > 1 {
            for i in 1 ..< points.count {
                cumulative[i] = cumulative[i - 1] + (startSet.contains(i) ? 0.0 : Geodesy.distance(points[i - 1], points[i]))
            }
        }
        self.points = points
        segmentStarts = starts
        self.cumulative = cumulative
    }

    public var length: Double { cumulative.last ?? 0.0 }

    /// Position at `distance` meters from the start (clamped to the track), interpolated between points.
    public func interpolate(_ distance: Double) -> TrackPoint? {
        guard let first = points.first, let last = points.last else { return nil }
        if distance <= 0.0 { return first }
        if distance >= length { return last }
        var lo = 0
        var hi = points.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) >> 1
            if cumulative[mid] <= distance { lo = mid } else { hi = mid }
        }
        let a = points[lo]
        let b = points[hi]
        let span = cumulative[hi] - cumulative[lo]
        if span <= 0.0 { return a }
        let f = (distance - cumulative[lo]) / span
        let ele: Double?
        if let ea = a.ele, let eb = b.ele {
            ele = ea + (eb - ea) * f
        } else {
            ele = a.ele ?? b.ele
        }
        return TrackPoint(lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f, ele: ele)
    }

    /// Index of the point closest to a position (flat-earth metric, good enough to pick a point under a finger).
    public func nearestIndex(_ lat: Double, _ lon: Double) -> Int {
        if points.isEmpty { return -1 }
        let cosLat = cos(Angle.radians(lat))
        var best = 0
        var bestD = Double.greatestFiniteMagnitude
        for i in points.indices {
            let dy = points[i].lat - lat
            let dx = (points[i].lon - lon) * cosLat
            let d = dx * dx + dy * dy
            if d < bestD {
                bestD = d
                best = i
            }
        }
        return best
    }

    /// Distance along the path of the point nearest to a position.
    public func distanceOfNearest(_ lat: Double, _ lon: Double) -> Double {
        let index = nearestIndex(lat, lon)
        return index < 0 ? 0.0 : cumulative[index]
    }

    /// Median of `window` (odd) neighbours: removes the one-point spikes of GPS and barometric altitudes. The window
    /// shrinks symmetrically at the ends, so the first and last values are kept as they are.
    public static func medianFilter(_ values: [Double], _ window: Int) -> [Double] {
        if window <= 1 || values.count < 3 { return values }
        var out = [Double](repeating: 0, count: values.count)
        var buffer = [Double](repeating: 0, count: window)
        for i in values.indices {
            let half = Swift.min(window / 2, i, values.count - 1 - i)
            let n = 2 * half + 1
            for k in 0 ..< n {
                buffer[k] = values[i - half + k]
            }
            // `Arrays.sort(double[])` order: -0.0 before 0.0, NaN last.
            buffer[0 ..< n].sort { !$1.isTotallyOrdered(belowOrEqualTo: $0) }
            out[i] = buffer[half]
        }
        return out
    }
}
