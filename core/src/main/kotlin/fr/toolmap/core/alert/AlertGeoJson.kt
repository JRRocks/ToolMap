package fr.toolmap.core.alert

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** GeoJSON of what the alerts watch, for the map. */
object AlertGeoJson {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val STEPS = 48

    /** One polygon per watched place: the circle of its radius, as a ring of [STEPS] points. */
    fun circles(places: List<WatchedPlace>): String {
        if (places.isEmpty()) return EMPTY
        val features =
            places.joinToString(",") { place ->
                val ring = (0..STEPS).joinToString(",") { i -> coordinate(place, if (i == STEPS) 0 else i) }
                """{"type":"Feature","properties":{"id":${place.id}},"geometry":{"type":"Polygon",""" +
                    """"coordinates":[[$ring]]}}"""
            }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    /** The point at [step] of [STEPS] around the place, at the place's radius, written `[lon,lat]`. */
    private fun coordinate(
        place: WatchedPlace,
        step: Int,
    ): String {
        val bearing = Math.toRadians(step * 360.0 / STEPS)
        val angular = place.radiusMeters / EARTH_RADIUS_METERS
        val lat1 = Math.toRadians(place.lat)
        val lon1 = Math.toRadians(place.lon)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return String.format(Locale.ROOT, "[%.7f,%.7f]", Math.toDegrees(lon2), Math.toDegrees(lat2))
    }
}
