package fr.toolmap.core.geo

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A projected position in meters. */
data class PlanarCoordinate(
    val easting: Double,
    val northing: Double,
)

/**
 * Lambert-93 (RGF93, EPSG:2154), the legal projection of metropolitan France: Lambert conformal conic with
 * standard parallels 44 N and 49 N on the GRS80 ellipsoid. RGF93 and WGS84 differ by far less than a meter, so the
 * WGS84 coordinates of a GPS are used as they are.
 */
object Lambert93 {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257222101
    private const val LON0 = 3.0
    private const val E0 = 700_000.0
    private const val N0 = 6_600_000.0
    private val e = sqrt(F * (2 - F))

    private val phi1 = Math.toRadians(44.0)
    private val phi2 = Math.toRadians(49.0)
    private val phi0 = Math.toRadians(46.5)

    private val n = (ln(m(phi1)) - ln(m(phi2))) / (ln(t(phi1)) - ln(t(phi2)))
    private val bigF = m(phi1) / (n * t(phi1).pow(n))
    private val rho0 = A * bigF * t(phi0).pow(n)

    private fun m(phi: Double): Double = cos(phi) / sqrt(1 - e * e * sin(phi) * sin(phi))

    private fun t(phi: Double): Double {
        val s = e * sin(phi)
        return tan(Math.PI / 4 - phi / 2) / ((1 - s) / (1 + s)).pow(e / 2)
    }

    fun toLambert(
        lat: Double,
        lon: Double,
    ): PlanarCoordinate {
        val rho = A * bigF * t(Math.toRadians(lat)).pow(n)
        val theta = n * Math.toRadians(Utm.normalizeLon(lon - LON0))
        return PlanarCoordinate(E0 + rho * sin(theta), N0 + rho0 - rho * cos(theta))
    }

    fun toLonLat(point: PlanarCoordinate): LonLat {
        val dx = point.easting - E0
        val dy = rho0 - (point.northing - N0)
        val rho = sign(n) * sqrt(dx * dx + dy * dy)
        val tt = (rho / (A * bigF)).pow(1 / n)
        val theta = atan2(sign(n) * dx, sign(n) * dy)
        var phi = Math.PI / 2 - 2 * atan(tt)
        repeat(ITERATIONS) {
            val s = e * sin(phi)
            phi = Math.PI / 2 - 2 * atan(tt * ((1 - s) / (1 + s)).pow(e / 2))
        }
        return LonLat(LON0 + Math.toDegrees(theta / n), Math.toDegrees(phi))
    }

    /** Whether (lat, lon) is within the area EPSG:2154 is defined for (France and its seas, with the margin of 1°). */
    fun covers(
        lat: Double,
        lon: Double,
    ): Boolean = lat in 41.15..51.56 && lon in -9.86..10.38

    private const val ITERATIONS = 8
}
