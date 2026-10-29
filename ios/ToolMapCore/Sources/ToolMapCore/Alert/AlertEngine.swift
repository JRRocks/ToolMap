// Port of core/src/main/kotlin/fr/toolmap/core/alert/AlertEngine.kt.

import Foundation

/// A waypoint the user wants to be warned about when getting close to it.
public struct WatchedPlace: Hashable, Sendable {
    public let id: Int64
    public let name: String?
    public let lat: Double
    public let lon: Double
    public let radiusMeters: Double

    public init(id: Int64, name: String?, lat: Double, lon: Double, radiusMeters: Double) {
        self.id = id
        self.name = name
        self.lat = lat
        self.lon = lon
        self.radiusMeters = radiusMeters
    }
}

/// What the engine tells the app to raise.
public enum AlertEvent: Hashable, Sendable {
    /// A watched place was entered.
    case proximity(place: WatchedPlace, distanceMeters: Double)
    /// The position has been further than the limit from the followed line for a while; repeats while it lasts.
    case offTrack(distanceMeters: Double, limitMeters: Double)
    /// The position came back within reach of the line after an `offTrack`.
    case backOnTrack
    /// The end of the followed line was reached.
    case arrived
}

/// Where the walker stands on the followed line.
public struct FollowStatus: Hashable, Sendable {
    public let offMeters: Double
    public let alongMeters: Double
    public let remainingMeters: Double
    public let totalMeters: Double
    public let offTrack: Bool
    public let arrived: Bool

    public init(offMeters: Double, alongMeters: Double, remainingMeters: Double, totalMeters: Double, offTrack: Bool, arrived: Bool) {
        self.offMeters = offMeters
        self.alongMeters = alongMeters
        self.remainingMeters = remainingMeters
        self.totalMeters = totalMeters
        self.offTrack = offTrack
        self.arrived = arrived
    }

    public var fraction: Double {
        totalMeters <= 0.0 ? 0.0 : RouteMath.coerce(alongMeters / totalMeters, 0.0, 1.0)
    }
}

/// What one position changed.
public struct AlertUpdate: Hashable, Sendable {
    public let events: [AlertEvent]
    /// `nil` while no line is followed or no usable position has come in yet.
    public let status: FollowStatus?

    public init(events: [AlertEvent] = [], status: FollowStatus? = nil) {
        self.events = events
        self.status = status
    }
}

/// The rules of the alerts (feature P9.4), free of the platform: positions go in, events come out.
///
/// - Fixes less precise than `maxAccuracyMeters` are ignored: they would make alerts jump in and out.
/// - **Proximity**: one event when a watched place is entered; it fires again only after the position went beyond
///   `exitFactor` times the radius (hysteresis against GPS jitter at the edge). A place that is already entered when
///   it is first seen is entered silently: switching a watch on next to it is not a surprise to announce.
/// - **Off the line**: the limit must be exceeded, and be more than the fix's own accuracy, by `confirmFixes` fixes in
///   a row before the event fires; it repeats every `repeatMillis` while it lasts and a `.backOnTrack` follows once the
///   position is within `backFactor` times the limit.
/// - **Arrival**: once, when the end of the line is within `arriveMeters` after more than half of it was covered (so
///   that a loop does not "arrive" at its own start).
///
/// Not thread-safe: fed from one place (the location updates).
public final class AlertEngine {
    public static let maxAccuracyMeters = 150.0
    public static let exitFactor = 1.25
    public static let backFactor = 0.8
    public static let confirmFixes = 3
    public static let repeatMillis: Int64 = 60_000
    public static let arriveMeters = 30.0

    private var settings: AlertSettings
    private var places: [WatchedPlace] = []
    private var inside = Set<Int64>()
    private var seen = Set<Int64>()

    private var follower: TrackFollower?
    private var along: Double?
    private var furthest = 0.0
    private var beyondInARow = 0
    private var offTrack = false
    private var lastOffEventMillis: Int64 = 0
    private var arrivedSent = false
    private var lastStatus: FollowStatus?

    public init(_ settings: AlertSettings = AlertSettings()) {
        self.settings = settings
    }

    public func updateSettings(_ settings: AlertSettings) {
        self.settings = settings
    }

    /// The places to watch now; those no longer listed are forgotten, the others keep their state.
    public func setWatched(_ list: [WatchedPlace]) {
        places = list
        let ids = Set(list.map { $0.id })
        inside.formIntersection(ids)
        seen.formIntersection(ids)
    }

    /// The line to follow, or `nil` to stop following. Progress starts again.
    public func setFollow(_ line: TrackFollower?) {
        if let usable = line, usable.usable {
            follower = usable
        } else {
            follower = nil
        }
        along = nil
        furthest = 0.0
        beyondInARow = 0
        offTrack = false
        lastOffEventMillis = 0
        arrivedSent = false
        lastStatus = nil
    }

    @discardableResult
    public func onFix(_ fix: Fix) -> AlertUpdate {
        if let accuracy = fix.accuracyMeters, accuracy > AlertEngine.maxAccuracyMeters {
            return AlertUpdate(status: lastStatus)
        }
        var events: [AlertEvent] = []
        if settings.proximityEnabled {
            checkProximity(fix, &events)
        } else {
            inside.removeAll()
        }
        let status = follow(fix, &events)
        return AlertUpdate(events: events, status: status)
    }

    private func checkProximity(_ fix: Fix, _ events: inout [AlertEvent]) {
        for place in places {
            let d = Geodesy.distance(fix.lat, fix.lon, place.lat, place.lon)
            let first = seen.insert(place.id).inserted
            if !inside.contains(place.id) {
                if d <= place.radiusMeters {
                    inside.insert(place.id)
                    if !first { events.append(.proximity(place: place, distanceMeters: d)) }
                }
            } else if d > place.radiusMeters * AlertEngine.exitFactor {
                inside.remove(place.id)
            }
        }
    }

    private func follow(_ fix: Fix, _ events: inout [AlertEvent]) -> FollowStatus? {
        guard let line = follower else { return nil }
        // Without progress yet the walk is taken to begin at the start, which settles a loop's start against its end.
        guard let projection = line.project(fix.lat, fix.lon, hintAlongMeters: along ?? 0.0) else { return nil }
        along = projection.alongMeters
        furthest = Swift.max(furthest, projection.alongMeters)
        let remaining = Swift.max(line.totalMeters - projection.alongMeters, 0.0)
        let limit = Double(settings.offTrackMeters)

        if settings.offTrackEnabled {
            let accuracy = fix.accuracyMeters ?? 0.0
            let beyond = projection.offMeters > limit && projection.offMeters > accuracy
            if beyond {
                beyondInARow += 1
                if !offTrack && beyondInARow >= AlertEngine.confirmFixes {
                    offTrack = true
                    lastOffEventMillis = fix.timeMillis
                    events.append(.offTrack(distanceMeters: projection.offMeters, limitMeters: limit))
                } else if offTrack && fix.timeMillis - lastOffEventMillis >= AlertEngine.repeatMillis {
                    lastOffEventMillis = fix.timeMillis
                    events.append(.offTrack(distanceMeters: projection.offMeters, limitMeters: limit))
                }
            } else {
                if projection.offMeters <= limit { beyondInARow = 0 }
                if offTrack && projection.offMeters <= limit * AlertEngine.backFactor {
                    offTrack = false
                    beyondInARow = 0
                    events.append(.backOnTrack)
                }
            }
        } else {
            beyondInARow = 0
            offTrack = false
        }

        let reached = !arrivedSent
            && furthest > line.totalMeters / 2
            && remaining <= AlertEngine.arriveMeters
            && projection.offMeters <= Swift.max(limit, AlertEngine.arriveMeters)
        if reached {
            arrivedSent = true
            events.append(.arrived)
        }

        let status = FollowStatus(
            offMeters: projection.offMeters,
            alongMeters: projection.alongMeters,
            remainingMeters: remaining,
            totalMeters: line.totalMeters,
            offTrack: offTrack,
            arrived: arrivedSent
        )
        lastStatus = status
        return status
    }
}
