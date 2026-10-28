package fr.toolmap.core.maps

import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.track.Geodesy
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The band to save around a track or a route (P30.2, variant B of `docs/previews/p30-itineraires.html`): its [path],
 * thinned for the planner, and the width kept on each side. The download takes the tiles of the band only
 * ([ZoneShape.Corridor]), not those of the rectangle around it.
 */
data class AreaCorridor(
    /** The name of the track or the route, given to the download. */
    val name: String,
    val path: List<ZoneShape.Point>,
    val radiusMeters: Int,
) {
    init {
        require(path.size >= 2) { "a band needs a line" }
        require(radiusMeters > 0) { "radius $radiusMeters" }
    }

    fun toShape(): ZoneShape.Corridor = ZoneShape.Corridor(path, radiusMeters.toDouble())

    /** The rectangle around the band (the line and [radius] on every side): what the map is fitted to. */
    fun bounds(radius: Int = radiusMeters): AreaSelection {
        val perDegree = Math.toRadians(1.0) * Geodesy.EARTH_RADIUS_METERS
        val south = path.minOf { it.lat }
        val north = path.maxOf { it.lat }
        val dLat = radius / perDegree
        // The widest degree of longitude of the band is at its latitude nearest the equator.
        val nearest =
            if (south > 0) {
                south
            } else if (north < 0) {
                -north
            } else {
                0.0
            }
        val dLon = dLat / cos(Math.toRadians(nearest.coerceAtMost(AreaSelection.MAX_LATITUDE)))
        return AreaSelection(
            (path.minOf { it.lon } - dLon).coerceAtLeast(-AreaSelection.MAX_LONGITUDE),
            (south - dLat).coerceAtLeast(-AreaSelection.MAX_LATITUDE),
            (path.maxOf { it.lon } + dLon).coerceAtMost(AreaSelection.MAX_LONGITUDE),
            (north + dLat).coerceAtMost(AreaSelection.MAX_LATITUDE),
        )
    }
}

/**
 * The width of the band on each side, as its slider gives it: from 50 m to 5 km on a logarithmic scale, in steps of
 * 50 m up to 1 km and of 250 m above (variant B of `docs/previews/p30-itineraires.html`).
 */
object CorridorWidth {
    const val MIN = 50
    const val MAX = 5_000
    const val DEFAULT = 500
    private const val FINE_UP_TO = 1_000
    private const val FINE_STEP = 50
    private const val COARSE_STEP = 250

    /** The width at [fraction] (0 to 1) of the slider. */
    fun fromFraction(fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        return round(MIN * (MAX.toDouble() / MIN).pow(f))
    }

    /** Where the slider stands for [meters]. */
    fun toFraction(meters: Int): Double = ln(clamp(meters).toDouble() / MIN) / ln(MAX.toDouble() / MIN)

    /** [meters] in the bounds, at its step. */
    fun round(meters: Double): Int {
        val step = if (meters <= FINE_UP_TO) FINE_STEP else COARSE_STEP
        return clamp((meters / step).roundToInt() * step)
    }

    /** A width typed by the user: kept to the meter, within the bounds. */
    fun clamp(meters: Int): Int = meters.coerceIn(MIN, MAX)
}
