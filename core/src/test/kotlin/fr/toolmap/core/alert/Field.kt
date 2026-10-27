package fr.toolmap.core.alert

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.record.Fix
import kotlin.math.cos

/** A flat test field around 45°N 6°E: x meters east and y meters north of the origin. */
object Field {
    private const val LAT0 = 45.0
    private const val LON0 = 6.0
    private const val METERS_PER_DEGREE = 111_194.9266
    private val kx = METERS_PER_DEGREE * cos(Math.toRadians(LAT0))

    fun lat(y: Double) = LAT0 + y / METERS_PER_DEGREE

    fun lon(x: Double) = LON0 + x / kx

    fun point(
        x: Double,
        y: Double,
    ) = LonLat(lon(x), lat(y))

    fun fix(
        x: Double,
        y: Double,
        timeMillis: Long = 0L,
        accuracy: Double? = 5.0,
    ) = Fix(lat(y), lon(x), timeMillis = timeMillis, accuracyMeters = accuracy)

    /** A straight line from [fromX] to [toX] at height [y], with a vertex every [step] meters. */
    fun line(
        fromX: Double,
        toX: Double,
        y: Double = 0.0,
        step: Double = 100.0,
    ): List<LonLat> {
        val out = ArrayList<LonLat>()
        var x = fromX
        while (x < toX) {
            out += point(x, y)
            x += step
        }
        out += point(toX, y)
        return out
    }
}
