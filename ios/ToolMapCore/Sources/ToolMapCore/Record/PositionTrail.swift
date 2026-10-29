// Port of core/src/main/kotlin/fr/toolmap/core/record/PositionTrail.kt.

import Foundation

/// The last positions of the device, drawn as a trail behind the position marker (« Display previous locations »).
/// Bounded in number and in age, and a new point is kept only once the device has moved a few metres, so that a
/// motionless phone does not fill it. Not thread-safe.
public final class PositionTrail {
    public static let defaultMaxPoints = 500
    public static let defaultMaxAgeMillis: Int64 = 30 * 60_000
    public static let defaultMinStepMeters = 3.0

    public struct Sample: Hashable, Sendable {
        public let lat: Double
        public let lon: Double
        public let timeMillis: Int64

        public init(lat: Double, lon: Double, timeMillis: Int64) {
            self.lat = lat
            self.lon = lon
            self.timeMillis = timeMillis
        }
    }

    private let maxPoints: Int
    private let maxAgeMillis: Int64
    private let minStepMeters: Double
    private var samples: [Sample] = []

    public init(
        maxPoints: Int = PositionTrail.defaultMaxPoints,
        maxAgeMillis: Int64 = PositionTrail.defaultMaxAgeMillis,
        minStepMeters: Double = PositionTrail.defaultMinStepMeters
    ) {
        self.maxPoints = maxPoints
        self.maxAgeMillis = maxAgeMillis
        self.minStepMeters = minStepMeters
    }

    public func add(_ lat: Double, _ lon: Double, _ timeMillis: Int64) {
        prune(timeMillis)
        if let last = samples.last, Geodesy.distance(last.lat, last.lon, lat, lon) < minStepMeters { return }
        samples.append(Sample(lat: lat, lon: lon, timeMillis: timeMillis))
        while samples.count > maxPoints {
            samples.removeFirst()
        }
    }

    /// The samples still young enough at `nowMillis`, oldest first.
    public func points(_ nowMillis: Int64) -> [Sample] {
        prune(nowMillis)
        return samples
    }

    public func clear() {
        samples.removeAll()
    }

    private func prune(_ nowMillis: Int64) {
        while let first = samples.first, nowMillis - first.timeMillis > maxAgeMillis {
            samples.removeFirst()
        }
    }
}
