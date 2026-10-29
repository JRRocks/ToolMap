// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackStats.kt.

/// Numbers describing a track. Elevations, durations and speeds are nil when the points do not carry them.
public struct TrackStats: Equatable, Hashable, Sendable {
    public let pointCount: Int
    public let segmentCount: Int
    public let distance2dMeters: Double
    /// 2D distance combined with the climb between points (smoothed elevations).
    public let distance3dMeters: Double
    public let ascentMeters: Double
    public let descentMeters: Double
    public let minEleMeters: Double?
    public let maxEleMeters: Double?
    public let startTimeMillis: Int64?
    public let endTimeMillis: Int64?
    /// From the first to the last timed point, pauses included.
    public let totalDurationMillis: Int64?
    /// Only the intervals walked at more than `StatsOptions.movingSpeedMps`.
    public let movingDurationMillis: Int64?
    /// Distance over total time.
    public let averageSpeedMps: Double?
    /// Distance over moving time.
    public let averageMovingSpeedMps: Double?
    /// Fastest stretch of at least `StatsOptions.maxSpeedWindowMillis`, from the displacement between its two ends:
    /// a few meters of GPS jitter at every fix does not inflate it.
    public let maxSpeedMps: Double?
    /// Walking time by DIN 33466, from the 2D distance and the smoothed ascent and descent.
    public let estimatedWalkMillis: Int64

    public init(
        pointCount: Int,
        segmentCount: Int,
        distance2dMeters: Double,
        distance3dMeters: Double,
        ascentMeters: Double,
        descentMeters: Double,
        minEleMeters: Double?,
        maxEleMeters: Double?,
        startTimeMillis: Int64?,
        endTimeMillis: Int64?,
        totalDurationMillis: Int64?,
        movingDurationMillis: Int64?,
        averageSpeedMps: Double?,
        averageMovingSpeedMps: Double?,
        maxSpeedMps: Double?,
        estimatedWalkMillis: Int64
    ) {
        self.pointCount = pointCount
        self.segmentCount = segmentCount
        self.distance2dMeters = distance2dMeters
        self.distance3dMeters = distance3dMeters
        self.ascentMeters = ascentMeters
        self.descentMeters = descentMeters
        self.minEleMeters = minEleMeters
        self.maxEleMeters = maxEleMeters
        self.startTimeMillis = startTimeMillis
        self.endTimeMillis = endTimeMillis
        self.totalDurationMillis = totalDurationMillis
        self.movingDurationMillis = movingDurationMillis
        self.averageSpeedMps = averageSpeedMps
        self.averageMovingSpeedMps = averageMovingSpeedMps
        self.maxSpeedMps = maxSpeedMps
        self.estimatedWalkMillis = estimatedWalkMillis
    }
}

/// Tuning of `TrackStatsCalculator`. The defaults suit hiking with a phone's GPS.
public struct StatsOptions: Equatable, Hashable, Sendable {
    /// Odd number of neighbours of the median filter applied to elevations before anything else.
    public let medianWindow: Int
    /// Elevation changes smaller than this are noise, not climb.
    public let hysteresisMeters: Double
    /// Below this speed an interval counts as a stop.
    public let movingSpeedMps: Double
    /// The maximum speed is measured over at least this long, so that GPS jitter does not set the record.
    public let maxSpeedWindowMillis: Int64

    public init(
        medianWindow: Int = 5,
        hysteresisMeters: Double = 5.0,
        movingSpeedMps: Double = 0.3,
        maxSpeedWindowMillis: Int64 = 10000
    ) {
        self.medianWindow = medianWindow
        self.hysteresisMeters = hysteresisMeters
        self.movingSpeedMps = movingSpeedMps
        self.maxSpeedWindowMillis = maxSpeedWindowMillis
    }
}

public enum TrackStatsCalculator {
    /// DIN 33466: 4 km/h on the flat, 300 m/h up, 500 m/h down.
    public static let walkFlatMetersPerHour = 4000.0
    public static let walkUpMetersPerHour = 300.0
    public static let walkDownMetersPerHour = 500.0

    private static let millisPerHour = 3_600_000.0

    public static func compute(_ segments: [[TrackPoint]], options: StatsOptions = StatsOptions()) -> TrackStats {
        let usable = segments.filter { !$0.isEmpty }
        var d2 = 0.0
        var d3 = 0.0
        var ascent = 0.0
        var descent = 0.0
        var minEle: Double?
        var maxEle: Double?
        var moving: Int64 = 0
        var anyMovingData = false
        var maxSpeed: Double?
        for segment in usable {
            let smooth = smoothedElevations(segment, options.medianWindow)
            let swings = Hysteresis(options.hysteresisMeters)
            for i in segment.indices {
                if let e = smooth[i] {
                    swings.add(e)
                    minEle = Swift.min(minEle ?? e, e)
                    maxEle = Swift.max(maxEle ?? e, e)
                }
            }
            ascent += swings.ascent
            descent += swings.descent
            for i in 1 ..< Swift.max(segment.count, 1) {
                let flat = Geodesy.distance(segment[i - 1], segment[i])
                d2 += flat
                if let e0 = smooth[i - 1], let e1 = smooth[i] {
                    d3 += (flat * flat + (e1 - e0) * (e1 - e0)).squareRoot()
                } else {
                    d3 += flat
                }
                if let t0 = segment[i - 1].timeMillis, let t1 = segment[i].timeMillis, t1 > t0 {
                    anyMovingData = true
                    let step = t1 &- t0
                    if flat / (Double(step) / 1000.0) >= options.movingSpeedMps { moving &+= step }
                }
            }
            maxSpeed = Swift.max(maxSpeed ?? 0.0, windowedMaxSpeed(segment, options.maxSpeedWindowMillis) ?? 0.0)
        }
        let times = usable.joined().compactMap { $0.timeMillis }
        let start = times.first
        let end = times.last
        var total: Int64?
        if let first = start, let last = end, last > first { total = last &- first }
        return TrackStats(
            pointCount: usable.reduce(0) { $0 + $1.count },
            segmentCount: usable.count,
            distance2dMeters: d2,
            distance3dMeters: d3,
            ascentMeters: ascent,
            descentMeters: descent,
            minEleMeters: minEle,
            maxEleMeters: maxEle,
            startTimeMillis: start,
            endTimeMillis: end,
            totalDurationMillis: total,
            movingDurationMillis: anyMovingData ? moving : nil,
            averageSpeedMps: total.map { d2 / (Double($0) / 1000.0) },
            averageMovingSpeedMps: anyMovingData && moving > 0 ? d2 / (Double(moving) / 1000.0) : nil,
            maxSpeedMps: maxSpeed.flatMap { $0 > 0.0 ? $0 : nil },
            estimatedWalkMillis: estimateWalkMillis(d2, ascent, descent)
        )
    }

    /// DIN 33466: the longer of the horizontal and vertical times plus half of the shorter one.
    public static func estimateWalkMillis(_ distanceMeters: Double, _ ascentMeters: Double, _ descentMeters: Double) -> Int64 {
        let horizontal = distanceMeters / walkFlatMetersPerHour
        let vertical = ascentMeters / walkUpMetersPerHour + descentMeters / walkDownMetersPerHour
        let hours = Swift.max(horizontal, vertical) + Swift.min(horizontal, vertical) / 2
        return Int64(kotlinRound(hours * millisPerHour))
    }

    /// Elevation of every point after the median filter, nil where the point has none.
    static func smoothedElevations(_ segment: [TrackPoint], _ window: Int) -> [Double?] {
        let withEle = segment.indices.filter { segment[$0].ele != nil }
        let filtered = TrackPath.medianFilter(withEle.map { segment[$0].ele ?? 0 }, window)
        var out = [Double?](repeating: nil, count: segment.count)
        for (k, i) in withEle.enumerated() {
            out[i] = filtered[k]
        }
        return out
    }

    private static func windowedMaxSpeed(_ segment: [TrackPoint], _ windowMillis: Int64) -> Double? {
        var best: Double?
        var j = 0
        for i in segment.indices {
            guard let ti = segment[i].timeMillis else { continue }
            if j < i { j = i }
            // Kotlin `(time ?: Long.MIN_VALUE) - ti` wraps around; so does `&-`.
            while j < segment.count, (segment[j].timeMillis ?? Int64.min) &- ti < windowMillis {
                j += 1
            }
            if j >= segment.count { break }
            guard let tj = segment[j].timeMillis else { continue }
            let speed = Geodesy.distance(segment[i], segment[j]) / (Double(tj &- ti) / 1000.0)
            if best == nil || speed > best! { best = speed }
        }
        return best
    }

    /// Climb and descent that ignore wobbles smaller than a threshold: an up-or-down swing only counts once the
    /// elevation has moved by the threshold from the last turning point, and then counts in full.
    final class Hysteresis {
        private let threshold: Double
        private var settledUp = 0.0
        private var settledDown = 0.0
        private var anchor: Double?
        private var candidate = 0.0
        private var direction = 0

        init(_ threshold: Double) {
            self.threshold = threshold
        }

        /// Climb so far, including the swing in progress.
        var ascent: Double { settledUp + (direction == 1 ? candidate - (anchor ?? candidate) : 0.0) }

        /// Descent so far, including the swing in progress.
        var descent: Double { settledDown + (direction == -1 ? (anchor ?? candidate) - candidate : 0.0) }

        func add(_ value: Double) {
            guard let a = anchor else {
                anchor = value
                candidate = value
                return
            }
            switch direction {
            case 0:
                if value - a >= threshold {
                    direction = 1
                    candidate = value
                } else if a - value >= threshold {
                    direction = -1
                    candidate = value
                }
            case 1:
                if value > candidate {
                    candidate = value
                } else if candidate - value >= threshold {
                    settledUp += candidate - a
                    anchor = candidate
                    direction = -1
                    candidate = value
                }
            default:
                if value < candidate {
                    candidate = value
                } else if value - candidate >= threshold {
                    settledDown += a - candidate
                    anchor = candidate
                    direction = 1
                    candidate = value
                }
            }
        }
    }
}
