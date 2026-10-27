package fr.toolmap.core.route

import fr.toolmap.core.track.TrackStatsCalculator

/**
 * The figures of the route being edited (`docs/specs/routing.md` §5.6): length, climb and descent of the geometry (the
 * same smoothing as the tracks), and a duration. Walking: DIN 33466 on the whole route. Other profiles: the sum of
 * BRouter's times, a « minimum » when some legs have none (drawn by hand or still computing).
 */
data class RouteSummary(
    val lengthMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val durationMillis: Long?,
    val durationIsMinimum: Boolean,
    val pendingLegs: Int,
    /** The route has elevations: climb and descent mean something. */
    val hasElevation: Boolean,
) {
    companion object {
        val EMPTY = RouteSummary(0.0, 0.0, 0.0, null, false, 0, false)

        fun of(
            plan: RoutePlan,
            profile: String,
        ): RouteSummary {
            val geometry = plan.geometry
            if (geometry.size < 2) return EMPTY.copy(pendingLegs = plan.pending.size)
            val stats = TrackStatsCalculator.compute(listOf(geometry))
            val hasElevation = stats.minEleMeters != null
            val pending = plan.pending.size
            var duration: Long? = null
            var minimum = false
            if (RoutingProfiles.isWalking(profile)) {
                duration = stats.estimatedWalkMillis
            } else {
                for (leg in plan.legs) {
                    val t = leg.timeMillis
                    if (t == null || leg.isByHand || leg.state != LegState.OK) {
                        minimum = true
                    } else {
                        duration = (duration ?: 0L) + t
                    }
                }
                if (duration == null) minimum = false
            }
            return RouteSummary(
                lengthMeters = stats.distance2dMeters,
                ascentMeters = stats.ascentMeters,
                descentMeters = stats.descentMeters,
                durationMillis = duration,
                durationIsMinimum = minimum,
                pendingLegs = pending,
                hasElevation = hasElevation,
            )
        }
    }
}
