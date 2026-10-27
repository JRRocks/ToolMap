package fr.toolmap.core.geo

import fr.toolmap.core.track.Geodesy

/** A place the user chose to walk to. */
data class NavTarget(
    val lat: Double,
    val lon: Double,
    val name: String? = null,
)

/** How to get from here to a [NavTarget]. [relativeDegrees] is `null` while the device heading is unknown. */
data class Guidance(
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val relativeDegrees: Double?,
)

object Navigation {
    /** Arrival radius: closer than this and the target counts as reached. */
    const val ARRIVED_METERS = 15.0

    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun guide(
        fromLat: Double,
        fromLon: Double,
        target: NavTarget,
        headingDegrees: Double? = null,
    ): Guidance {
        val bearing = Geodesy.bearing(fromLat, fromLon, target.lat, target.lon)
        return Guidance(
            distanceMeters = Geodesy.distance(fromLat, fromLon, target.lat, target.lon),
            bearingDegrees = bearing,
            relativeDegrees = headingDegrees?.let { Geodesy.relativeBearing(it, bearing) },
        )
    }

    fun arrived(guidance: Guidance): Boolean = guidance.distanceMeters <= ARRIVED_METERS

    /** Eight-wind compass name of a bearing: `NE`. */
    fun compassPoint(bearingDegrees: Double): String {
        val normalised = (bearingDegrees % 360.0 + 360.0) % 360.0
        return POINTS[((normalised + 22.5) / 45.0).toInt() % 8]
    }
}
