// Port of core/src/main/kotlin/fr/toolmap/core/record/RecorderCore.kt.

import Foundation

/// A position as the phone's GNSS reports it (CoreLocation's `CLLocation` on iPhone).
public struct Fix: Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let ele: Double?
    public let timeMillis: Int64
    /// Horizontal accuracy (68 % radius) in meters, when known.
    public let accuracyMeters: Double?
    public let speedMps: Double?
    /// Accuracy of `ele` in meters, when the receiver says.
    public let verticalAccuracyMeters: Double?
    /// Whether `ele` is known to be above mean sea level; `false` means the receiver may report the ellipsoid height.
    public let eleIsMsl: Bool

    public init(
        lat: Double,
        lon: Double,
        ele: Double? = nil,
        timeMillis: Int64,
        accuracyMeters: Double? = nil,
        speedMps: Double? = nil,
        verticalAccuracyMeters: Double? = nil,
        eleIsMsl: Bool = false
    ) {
        self.lat = lat
        self.lon = lon
        self.ele = ele
        self.timeMillis = timeMillis
        self.accuracyMeters = accuracyMeters
        self.speedMps = speedMps
        self.verticalAccuracyMeters = verticalAccuracyMeters
        self.eleIsMsl = eleIsMsl
    }
}

/// What the recorder keeps and what it throws away. The defaults suit walking; a bike wants a larger distance.
/// Kotlin `fr.toolmap.core.record.RecorderSettings`, renamed: `RecorderSettings` is the recorder section of the app
/// settings (`settings/AppSettings.kt`), and Swift has one namespace for both.
public struct RecorderCoreSettings: Hashable, Sendable {
    /// Fixes closer in time than this to the last kept point are skipped.
    public let minIntervalMillis: Int64
    /// Fixes closer in space than this to the last kept point are skipped (a stop does not draw a knot).
    public let minDistanceMeters: Double
    /// Fixes less precise than this are skipped.
    public let maxAccuracyMeters: Double
    /// A silence this long (tunnel, device sleeping) starts a new segment instead of a straight line across it.
    public let gapNewSegmentMillis: Int64
    /// A jump faster than this is a bad fix, not movement.
    public let maxSpeedMps: Double
    /// Below this speed an interval counts as a stop.
    public let movingSpeedMps: Double

    public init(
        minIntervalMillis: Int64 = 2_000,
        minDistanceMeters: Double = 5.0,
        maxAccuracyMeters: Double = 50.0,
        gapNewSegmentMillis: Int64 = 300_000,
        maxSpeedMps: Double = 100.0,
        movingSpeedMps: Double = 0.3
    ) {
        self.minIntervalMillis = minIntervalMillis
        self.minDistanceMeters = minDistanceMeters
        self.maxAccuracyMeters = maxAccuracyMeters
        self.gapNewSegmentMillis = gapNewSegmentMillis
        self.maxSpeedMps = maxSpeedMps
        self.movingSpeedMps = movingSpeedMps
    }
}

public enum RecorderState: String, Hashable, Sendable {
    case idle = "IDLE"
    case recording = "RECORDING"
    case paused = "PAUSED"
}

public enum RejectReason: String, Hashable, Sendable {
    case notRecording = "NOT_RECORDING"
    case accuracy = "ACCURACY"
    case timeBackwards = "TIME_BACKWARDS"
    case tooSoon = "TOO_SOON"
    case tooClose = "TOO_CLOSE"
    case jump = "JUMP"
}

/// What `RecorderCore.onFix` decided.
public enum FixDecision: Hashable, Sendable {
    /// `newSegment`: the point starts a segment (first of the recording, after a pause, after a gap or a bad run).
    case accepted(point: TrackPoint, newSegment: Bool)
    case rejected(RejectReason)
}

/// Figures shown while recording.
public struct LiveStats: Hashable, Sendable {
    public let pointCount: Int
    public let segmentCount: Int
    public let distanceMeters: Double
    /// Time spent recording: pauses are not counted.
    public let elapsedMillis: Int64
    public let movingMillis: Int64
    public let ascentMeters: Double
    public let descentMeters: Double
    public let currentSpeedMps: Double?
    public let minEleMeters: Double?
    public let maxEleMeters: Double?
    public let lastFix: Fix?

    public init(
        pointCount: Int = 0,
        segmentCount: Int = 0,
        distanceMeters: Double = 0.0,
        elapsedMillis: Int64 = 0,
        movingMillis: Int64 = 0,
        ascentMeters: Double = 0.0,
        descentMeters: Double = 0.0,
        currentSpeedMps: Double? = nil,
        minEleMeters: Double? = nil,
        maxEleMeters: Double? = nil,
        lastFix: Fix? = nil
    ) {
        self.pointCount = pointCount
        self.segmentCount = segmentCount
        self.distanceMeters = distanceMeters
        self.elapsedMillis = elapsedMillis
        self.movingMillis = movingMillis
        self.ascentMeters = ascentMeters
        self.descentMeters = descentMeters
        self.currentSpeedMps = currentSpeedMps
        self.minEleMeters = minEleMeters
        self.maxEleMeters = maxEleMeters
        self.lastFix = lastFix
    }
}

/// A call that the recorder's state does not allow (Kotlin's `check(...)`, an `IllegalStateException`).
public struct RecorderStateError: Error, Hashable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// The decisions of the GPS recorder, free of the platform: which fixes become track points, where segments start, and
/// the live figures. The app feeds it fixes and stores what it accepts; this class does not touch storage or clocks.
/// Not thread-safe: fed from one place.
public final class RecorderCore {
    public static let hysteresisMeters = 5.0
    private static let maxBadRun = 4
    private static let speedWindowMillis: Int64 = 10_000
    private static let recentPoints = 20
    private static let elevationWindow = 5

    public let settings: RecorderCoreSettings
    public private(set) var state: RecorderState = .idle

    private var activeSince: Int64?
    private var activeBefore: Int64 = 0
    private var last: TrackPoint?
    private var awaitingSegment = true
    private var badRun = 0
    private var points = 0
    private var segments = 0
    private var distance = 0.0
    private var moving: Int64 = 0
    private var minEle: Double?
    private var maxEle: Double?
    private var lastFix: Fix?
    private var reportedSpeed: Double?
    private var recent: [TrackPoint] = []
    private var eleWindow: [Double] = []
    private let swings = TrackStatsCalculator.Hysteresis(RecorderCore.hysteresisMeters)

    public init(_ settings: RecorderCoreSettings = RecorderCoreSettings()) {
        self.settings = settings
    }

    /// Throws `RecorderStateError` when already started.
    public func start(_ nowMillis: Int64) throws {
        guard state == .idle else { throw RecorderStateError("already started") }
        state = .recording
        activeSince = nowMillis
    }

    public func pause(_ nowMillis: Int64) {
        if state != .recording { return }
        activeBefore += nowMillis - (activeSince ?? nowMillis)
        activeSince = nil
        state = .paused
    }

    /// After a pause the next kept fix opens a new segment: the way between is not part of the track.
    public func resume(_ nowMillis: Int64) {
        if state != .paused { return }
        activeSince = nowMillis
        awaitingSegment = true
        state = .recording
    }

    public func stop(_ nowMillis: Int64) {
        pause(nowMillis)
        state = .idle
    }

    /// Rebuilds the figures from a track already stored (recovery after the app was killed). The recorder ends up
    /// paused, so that the user chooses whether to carry on; the next fix after `resume` opens a new segment. Throws
    /// `RecorderStateError` on a running recorder.
    public func replay(_ stored: [[TrackPoint]], activeMillis: Int64) throws {
        guard state == .idle else { throw RecorderStateError("replay on a running recorder") }
        for segment in stored {
            var first = true
            for point in segment {
                keep(point, newSegment: first, speed: nil)
                first = false
            }
        }
        activeBefore = activeMillis
        state = .paused
    }

    public func onFix(_ fix: Fix) -> FixDecision {
        if state != .recording { return .rejected(.notRecording) }
        if let accuracy = fix.accuracyMeters, accuracy > settings.maxAccuracyMeters {
            return .rejected(.accuracy)
        }
        let candidate = TrackPoint(lat: fix.lat, lon: fix.lon, ele: fix.ele, timeMillis: fix.timeMillis)
        guard let previous = last, !awaitingSegment else {
            keep(candidate, newSegment: true, speed: fix.speedMps)
            lastFix = fix
            return .accepted(point: candidate, newSegment: true)
        }
        let dt = fix.timeMillis - (previous.timeMillis ?? fix.timeMillis)
        if dt < 0 { return .rejected(.timeBackwards) }
        if dt < settings.minIntervalMillis { return .rejected(.tooSoon) }
        let d = Geodesy.distance(previous, candidate)
        if dt > 0 && d / (Double(dt) / 1000.0) > settings.maxSpeedMps {
            // A bad fix is dropped, but a run of them means the earlier points were the wrong ones: follow the new place.
            badRun += 1
            if badRun < RecorderCore.maxBadRun { return .rejected(.jump) }
            keep(candidate, newSegment: true, speed: fix.speedMps)
            lastFix = fix
            return .accepted(point: candidate, newSegment: true)
        }
        if d < settings.minDistanceMeters { return .rejected(.tooClose) }
        let newSegment = dt > settings.gapNewSegmentMillis
        keep(candidate, newSegment: newSegment, speed: fix.speedMps)
        lastFix = fix
        return .accepted(point: candidate, newSegment: newSegment)
    }

    public func stats(_ nowMillis: Int64) -> LiveStats {
        var elapsed = activeBefore
        if let since = activeSince { elapsed += nowMillis - since }
        return LiveStats(
            pointCount: points,
            segmentCount: segments,
            distanceMeters: distance,
            elapsedMillis: elapsed,
            movingMillis: moving,
            ascentMeters: swings.ascent,
            descentMeters: swings.descent,
            currentSpeedMps: currentSpeed(),
            minEleMeters: minEle,
            maxEleMeters: maxEle,
            lastFix: lastFix
        )
    }

    private func currentSpeed() -> Double? {
        if let speed = reportedSpeed { return speed }
        guard let newest = recent.last, let tNew = newest.timeMillis else { return nil }
        let window = RecorderCore.speedWindowMillis
        guard let older = recent.first(where: { point in
            let gap = tNew - (point.timeMillis ?? tNew)
            return gap >= 1 && gap <= window
        }) else { return nil }
        let dt = tNew - (older.timeMillis ?? tNew)
        return Geodesy.distance(older, newest) / (Double(dt) / 1000.0)
    }

    private func keep(_ point: TrackPoint, newSegment: Bool, speed: Double?) {
        badRun = 0
        awaitingSegment = false
        if newSegment {
            segments += 1
            recent.removeAll()
            eleWindow.removeAll()
        } else if let previous = last {
            let d = Geodesy.distance(previous, point)
            distance += d
            if let t0 = previous.timeMillis, let t1 = point.timeMillis, t1 > t0,
               d / (Double(t1 - t0) / 1000.0) >= settings.movingSpeedMps {
                moving += t1 - t0
            }
        }
        points += 1
        if let ele = point.ele { addElevation(ele) }
        last = point
        reportedSpeed = speed
        recent.append(point)
        while recent.count > RecorderCore.recentPoints {
            recent.removeFirst()
        }
    }

    /// Median of the last few altitudes, so that one wild reading does not count as climb.
    private func addElevation(_ ele: Double) {
        eleWindow.append(ele)
        while eleWindow.count > RecorderCore.elevationWindow {
            eleWindow.removeFirst()
        }
        let sorted = eleWindow.sorted()
        let median = sorted[(sorted.count - 1) / 2]
        swings.add(median)
        minEle = Swift.min(minEle ?? median, median)
        maxEle = Swift.max(maxEle ?? median, median)
    }
}
