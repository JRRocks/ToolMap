// Port of core/src/main/kotlin/fr/toolmap/core/route/RouteSummary.kt and SavedRouteInfo.kt.

import Foundation

/// The figures of the route being edited (`docs/specs/routing.md` §5.6): length, climb and descent of the geometry (the
/// same smoothing as the tracks), and a duration. Walking: DIN 33466 on the whole route. Other profiles: the sum of
/// BRouter's times, a « minimum » when some legs have none (drawn by hand or still computing).
public struct RouteSummary: Hashable, Sendable {
    public static let empty = RouteSummary(
        lengthMeters: 0,
        ascentMeters: 0,
        descentMeters: 0,
        durationMillis: nil,
        durationIsMinimum: false,
        pendingLegs: 0,
        hasElevation: false
    )

    public let lengthMeters: Double
    public let ascentMeters: Double
    public let descentMeters: Double
    public let durationMillis: Int64?
    public let durationIsMinimum: Bool
    public let pendingLegs: Int
    /// The route has elevations: climb and descent mean something.
    public let hasElevation: Bool

    public init(
        lengthMeters: Double,
        ascentMeters: Double,
        descentMeters: Double,
        durationMillis: Int64?,
        durationIsMinimum: Bool,
        pendingLegs: Int,
        hasElevation: Bool
    ) {
        self.lengthMeters = lengthMeters
        self.ascentMeters = ascentMeters
        self.descentMeters = descentMeters
        self.durationMillis = durationMillis
        self.durationIsMinimum = durationIsMinimum
        self.pendingLegs = pendingLegs
        self.hasElevation = hasElevation
    }

    public static func of(_ plan: RoutePlan, _ profile: String) -> RouteSummary {
        let geometry = plan.geometry
        if geometry.count < 2 {
            return RouteSummary(
                lengthMeters: 0,
                ascentMeters: 0,
                descentMeters: 0,
                durationMillis: nil,
                durationIsMinimum: false,
                pendingLegs: plan.pending.count,
                hasElevation: false
            )
        }
        let stats = TrackStatsCalculator.compute([geometry])
        let hasElevation = stats.minEleMeters != nil
        let pending = plan.pending.count
        var duration: Int64?
        var minimum = false
        if RoutingProfiles.isWalking(profile) {
            duration = Int64(stats.estimatedWalkMillis)
        } else {
            for leg in plan.legs {
                if let t = leg.timeMillis, !leg.isByHand, leg.state == .ok {
                    duration = (duration ?? 0) + t
                } else {
                    minimum = true
                }
            }
            if duration == nil { minimum = false }
        }
        return RouteSummary(
            lengthMeters: stats.distance2dMeters,
            ascentMeters: stats.ascentMeters,
            descentMeters: stats.descentMeters,
            durationMillis: duration,
            durationIsMinimum: minimum,
            pendingLegs: pending,
            hasElevation: hasElevation
        )
    }
}

/// The figures of a saved route, for its sheet (P19, screen 6 of `docs/previews/p19-routing.html`): those of its plan
/// when it follows the ways, with the profile of its last computed leg; else the length and climb of its points.
public struct SavedRouteInfo: Hashable, Sendable {
    public let summary: RouteSummary
    /// `nil` for a route drawn by hand: no profile, no time.
    public let profile: String?

    public init(summary: RouteSummary, profile: String?) {
        self.summary = summary
        self.profile = profile
    }

    public static func of(_ points: [TrackPoint], _ extras: [RawXml]) -> SavedRouteInfo {
        if let plan = RoutePlanGpx.planOf(points, extras), let profile = plan.legs.last(where: { $0.mode == .routed })?.profile {
            return SavedRouteInfo(summary: RouteSummary.of(plan, profile), profile: profile)
        }
        if points.count < 2 { return SavedRouteInfo(summary: RouteSummary.empty, profile: nil) }
        let stats = TrackStatsCalculator.compute([points])
        let summary = RouteSummary(
            lengthMeters: stats.distance2dMeters,
            ascentMeters: stats.ascentMeters,
            descentMeters: stats.descentMeters,
            durationMillis: nil,
            durationIsMinimum: false,
            pendingLegs: 0,
            hasElevation: stats.minEleMeters != nil
        )
        return SavedRouteInfo(summary: summary, profile: nil)
    }
}
