package fr.toolmap.core.route

import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStatsCalculator

/**
 * The figures of a saved route, for its sheet (P19, screen 6 of `docs/previews/p19-routing.html`): those of its plan
 * when it follows the ways, with the profile of its last computed leg; else the length and climb of its points.
 */
data class SavedRouteInfo(
    val summary: RouteSummary,
    /** `null` for a route drawn by hand: no profile, no time. */
    val profile: String?,
) {
    companion object {
        fun of(
            points: List<TrackPoint>,
            extras: List<RawXml>,
        ): SavedRouteInfo {
            val plan = RoutePlanGpx.planOf(points, extras)
            val profile = plan?.legs?.lastOrNull { it.mode == LegMode.ROUTED }?.profile
            if (plan != null && profile != null) return SavedRouteInfo(RouteSummary.of(plan, profile), profile)
            if (points.size < 2) return SavedRouteInfo(RouteSummary.EMPTY, null)
            val stats = TrackStatsCalculator.compute(listOf(points))
            val summary =
                RouteSummary(
                    lengthMeters = stats.distance2dMeters,
                    ascentMeters = stats.ascentMeters,
                    descentMeters = stats.descentMeters,
                    durationMillis = null,
                    durationIsMinimum = false,
                    pendingLegs = 0,
                    hasElevation = stats.minEleMeters != null,
                )
            return SavedRouteInfo(summary, null)
        }
    }
}
