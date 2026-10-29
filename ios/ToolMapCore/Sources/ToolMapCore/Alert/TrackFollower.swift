// Port of core/src/main/kotlin/fr/toolmap/core/alert/TrackFollower.kt.

import Foundation

/// Where a position stands against a followed line.
public struct Projection: Hashable, Sendable {
    /// Distance from the position to the nearest point of the line, in meters.
    public let offMeters: Double
    /// Distance walked along the line up to that nearest point, in meters.
    public let alongMeters: Double
    /// Index of the segment the nearest point lies on.
    public let segment: Int
    public let nearestLat: Double
    public let nearestLon: Double

    public init(offMeters: Double, alongMeters: Double, segment: Int, nearestLat: Double, nearestLon: Double) {
        self.offMeters = offMeters
        self.alongMeters = alongMeters
        self.segment = segment
        self.nearestLat = nearestLat
        self.nearestLon = nearestLon
    }
}

/// A line to follow: how far a position is from it and how far along it the position is. Distances to the line use a
/// flat projection centred on the position (accurate to well under a meter within a few kilometers, which is all that
/// matters for an alert at tens of meters); distances along the line are geodesic.
///
/// A line that comes back near itself (an out-and-back, a loop) is ambiguous: the nearest point may be on the way out or
/// on the way back. `project` takes the progress made so far as a hint and, among the candidates about as near as the
/// best one (and on another stretch of the line, not on the next segment), prefers the one closest to the hint.
public struct TrackFollower: Sendable {
    private static let metersPerDegree = 111_194.9266
    private static let ambiguityMeters = 15.0
    private static let branchMarginMeters = 20.0

    /// Follow the line from its last point to its first.
    public let reversed: Bool

    private let line: [LonLat]

    /// Distance along the line at each vertex.
    private let cumulative: [Double]

    public init(_ points: [LonLat], reversed: Bool = false) {
        self.reversed = reversed
        let vertices = TrackFollower.dedupe(reversed ? Array(points.reversed()) : points)
        var distances = [Double](repeating: 0, count: vertices.count)
        for i in stride(from: 1, to: vertices.count, by: 1) {
            let a = vertices[i - 1]
            let b = vertices[i]
            distances[i] = distances[i - 1] + Geodesy.distance(a.lat, a.lon, b.lat, b.lon)
        }
        line = vertices
        cumulative = distances
    }

    public var totalMeters: Double { cumulative.last ?? 0.0 }

    /// Whether there is a line at all: at least two distinct points.
    public var usable: Bool { line.count >= 2 }

    public var start: LonLat? { line.first }
    public var end: LonLat? { line.last }

    /// The same line, followed the other way (as in Kotlin, from the line as this follower walks it).
    public func reversedCopy() -> TrackFollower {
        TrackFollower(line, reversed: !reversed)
    }

    /// `nil` when the line is unusable. `hintAlongMeters` is the progress known so far, to settle ambiguities.
    public func project(_ lat: Double, _ lon: Double, hintAlongMeters: Double? = nil) -> Projection? {
        if !usable { return nil }
        var nearest = candidate(0, lat, lon)
        for i in 1 ..< line.count - 1 {
            let c = candidate(i, lat, lon)
            if c.off < nearest.off { nearest = c }
        }
        var chosen = nearest
        if let hint = hintAlongMeters {
            // Another branch of the line (not the next segment of the same stretch) about as near as the best one.
            let branchGap = 2 * (nearest.off + TrackFollower.ambiguityMeters) + TrackFollower.branchMarginMeters
            for i in 0 ..< line.count - 1 {
                let c = candidate(i, lat, lon)
                let nearEnough = c.off <= nearest.off + TrackFollower.ambiguityMeters
                let otherBranch = abs(c.along - nearest.along) > branchGap
                if nearEnough && otherBranch && abs(c.along - hint) < abs(chosen.along - hint) {
                    chosen = c
                }
            }
        }
        return Projection(
            offMeters: chosen.off,
            alongMeters: chosen.along,
            segment: chosen.segment,
            nearestLat: chosen.lat,
            nearestLon: chosen.lon
        )
    }

    private struct Candidate {
        let off: Double
        let along: Double
        let segment: Int
        let lat: Double
        let lon: Double
    }

    private func candidate(_ index: Int, _ lat: Double, _ lon: Double) -> Candidate {
        let a = line[index]
        let b = line[index + 1]
        let kx = cos(Angle.radians(lat)) * TrackFollower.metersPerDegree
        let ax = (a.lon - lon) * kx
        let ay = (a.lat - lat) * TrackFollower.metersPerDegree
        let bx = (b.lon - lon) * kx
        let by = (b.lat - lat) * TrackFollower.metersPerDegree
        let dx = bx - ax
        let dy = by - ay
        let lengthSquared = dx * dx + dy * dy
        let t = lengthSquared <= 0.0 ? 0.0 : RouteMath.coerce((-ax * dx - ay * dy) / lengthSquared, 0.0, 1.0)
        let off = hypot(ax + t * dx, ay + t * dy)
        let along = cumulative[index] + t * (cumulative[index + 1] - cumulative[index])
        return Candidate(off: off, along: along, segment: index, lat: a.lat + t * (b.lat - a.lat), lon: a.lon + t * (b.lon - a.lon))
    }

    /// Consecutive identical points make zero-length segments that carry no direction.
    private static func dedupe(_ points: [LonLat]) -> [LonLat] {
        var out: [LonLat] = []
        out.reserveCapacity(points.count)
        for p in points where out.last != p {
            out.append(p)
        }
        return out
    }
}
