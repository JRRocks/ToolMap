// Port of core/src/main/kotlin/fr/toolmap/core/track/ElevationProfile.kt.

/// One point of the profile graph. `index` is the point of `TrackPath.points` it stands for.
public struct ProfileSample: Equatable, Hashable, Sendable {
    public let distanceMeters: Double
    public let elevationMeters: Double
    /// Slope in percent over `ProfileOptions.gradeWindowMeters` around the sample; positive going up.
    public let gradePercent: Double
    public let index: Int

    public init(distanceMeters: Double, elevationMeters: Double, gradePercent: Double, index: Int) {
        self.distanceMeters = distanceMeters
        self.elevationMeters = elevationMeters
        self.gradePercent = gradePercent
        self.index = index
    }
}

/// Colour bands of the slope, from the steepness only (the sign is shown by the profile itself).
public enum GradeBand: String, CaseIterable, Sendable {
    case flat = "FLAT"
    case gentle = "GENTLE"
    case moderate = "MODERATE"
    case steep = "STEEP"
    case verySteep = "VERY_STEEP"

    private static let flatBelow = 3.0
    private static let gentleBelow = 8.0
    private static let moderateBelow = 15.0
    private static let steepBelow = 25.0

    public static func of(_ gradePercent: Double) -> GradeBand {
        let g = Swift.abs(gradePercent)
        if g < flatBelow { return .flat }
        if g < gentleBelow { return .gentle }
        if g < moderateBelow { return .moderate }
        if g < steepBelow { return .steep }
        return .verySteep
    }
}

public struct ProfileOptions: Equatable, Hashable, Sendable {
    /// At most this many samples are produced; a 50 000-point track is drawn from a few hundred.
    public let maxSamples: Int
    public let medianWindow: Int
    /// Slopes are measured over about this length: shorter and GPS noise makes them meaningless.
    public let gradeWindowMeters: Double

    public init(maxSamples: Int = 600, medianWindow: Int = 5, gradeWindowMeters: Double = 60.0) {
        self.maxSamples = maxSamples
        self.medianWindow = medianWindow
        self.gradeWindowMeters = gradeWindowMeters
    }
}

/// What the altitude graph needs, built from the points that have an elevation.
public struct ElevationProfile: Equatable, Sendable {
    public let samples: [ProfileSample]
    public let totalDistanceMeters: Double

    public init(samples: [ProfileSample], totalDistanceMeters: Double) {
        self.samples = samples
        self.totalDistanceMeters = totalDistanceMeters
    }

    public var minElevation: Double? { samples.map(\.elevationMeters).min() }
    public var maxElevation: Double? { samples.map(\.elevationMeters).max() }

    /// The sample whose distance is the closest to `distanceMeters`; nil for an empty profile.
    public func sampleAt(_ distanceMeters: Double) -> ProfileSample? {
        if samples.isEmpty { return nil }
        var lo = 0
        var hi = samples.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) >> 1
            if samples[mid].distanceMeters <= distanceMeters { lo = mid } else { hi = mid }
        }
        let a = samples[lo]
        let b = samples[hi]
        return Swift.abs(distanceMeters - a.distanceMeters) <= Swift.abs(b.distanceMeters - distanceMeters) ? a : b
    }

    /// nil when fewer than two points have an elevation: there is nothing to draw.
    public static func build(_ path: TrackPath, options: ProfileOptions = ProfileOptions()) -> ElevationProfile? {
        let indices = path.points.indices.filter { path.points[$0].ele != nil }
        if indices.count < 2 { return nil }
        let ele = TrackPath.medianFilter(indices.map { path.points[$0].ele ?? 0 }, options.medianWindow)
        let dist = indices.map { path.cumulative[$0] }
        let slopes = grades(dist, ele, options.gradeWindowMeters)
        let keep = pick(indices.count, options.maxSamples)
        let samples = keep.map {
            ProfileSample(distanceMeters: dist[$0], elevationMeters: ele[$0], gradePercent: slopes[$0], index: indices[$0])
        }
        return ElevationProfile(samples: samples, totalDistanceMeters: path.length)
    }

    /// Slope at each sample: climb over the distance between the samples `window` meters apart around it.
    static func grades(_ dist: [Double], _ ele: [Double], _ window: Double) -> [Double] {
        var out = [Double](repeating: 0, count: dist.count)
        var from = 0
        var to = 0
        for i in dist.indices {
            while from < i, dist[i] - dist[from] > window / 2 {
                from += 1
            }
            if to < i { to = i }
            while to < dist.count - 1, dist[to] - dist[i] < window / 2 {
                to += 1
            }
            let run = dist[to] - dist[from]
            out[i] = run > 0.0 ? (ele[to] - ele[from]) / run * 100.0 : 0.0
        }
        return out
    }

    /// Evenly spread indices, first and last included.
    static func pick(_ count: Int, _ max: Int) -> [Int] {
        if count <= max || max < 2 { return Array(0 ..< count) }
        var out: [Int] = []
        var seen = Set<Int>()
        for k in 0 ..< max {
            let index = k * (count - 1) / (max - 1)
            if seen.insert(index).inserted { out.append(index) }
        }
        return out
    }
}
