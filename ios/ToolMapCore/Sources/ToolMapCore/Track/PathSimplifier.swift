// Port of core/src/main/kotlin/fr/toolmap/core/track/PathSimplifier.kt.

import Foundation

/// Douglas-Peucker simplification, iterative so that a 100 000-point track cannot overflow the stack.
public enum PathSimplifier {
    private static let metersPerDegree = Double.pi * Geodesy.earthRadiusMeters / 180.0

    /// Keeps the first and last points and every point that sticks out of the line by more than `toleranceMeters`
    /// (measured on a flat projection around the track, accurate at the scale of a track).
    public static func simplify(_ points: [TrackPoint], _ toleranceMeters: Double) -> [TrackPoint] {
        if points.count < 3 || toleranceMeters <= 0.0 { return points }
        let cosLat = cos(Angle.radians(points[points.count / 2].lat))
        let xs = points.map { $0.lon * metersPerDegree * cosLat }
        let ys = points.map { $0.lat * metersPerDegree }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        var stack: [(first: Int, last: Int)] = [(0, points.count - 1)]
        while let range = stack.popLast() {
            let first = range.first
            let last = range.last
            var farthest = -1
            var farthestDistance = toleranceMeters
            if last - first > 1 {
                for i in (first + 1) ..< last {
                    let d = distanceToSegment(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last])
                    if d > farthestDistance {
                        farthestDistance = d
                        farthest = i
                    }
                }
            }
            if farthest >= 0 {
                keep[farthest] = true
                stack.append((first, farthest))
                stack.append((farthest, last))
            }
        }
        var out: [TrackPoint] = []
        for (i, p) in points.enumerated() where keep[i] {
            out.append(p)
        }
        return out
    }

    private static func distanceToSegment(
        _ px: Double,
        _ py: Double,
        _ ax: Double,
        _ ay: Double,
        _ bx: Double,
        _ by: Double
    ) -> Double {
        let dx = bx - ax
        let dy = by - ay
        let lengthSquared = dx * dx + dy * dy
        if lengthSquared == 0.0 { return ((px - ax) * (px - ax) + (py - ay) * (py - ay)).squareRoot() }
        let t = Swift.min(Swift.max(((px - ax) * dx + (py - ay) * dy) / lengthSquared, 0.0), 1.0)
        let cx = ax + t * dx
        let cy = ay + t * dy
        return ((px - cx) * (px - cx) + (py - cy) * (py - cy)).squareRoot()
    }
}
