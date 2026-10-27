package fr.toolmap.core.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A UTM position: [zone] 1..60, [north] hemisphere, easting and northing in meters (WGS84). */
data class UtmCoordinate(
    val zone: Int,
    val north: Boolean,
    val easting: Double,
    val northing: Double,
)

/**
 * Universal Transverse Mercator on the WGS84 ellipsoid (Krüger series to the 4th order, error below a micrometer
 * within the zone and its overlap). Latitudes outside 84 N .. 80 S are not part of UTM.
 */
object Utm {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING_SOUTH = 10_000_000.0

    private val n = F / (2 - F)
    private val rectifyingRadius = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
    private val e = sqrt(F * (2 - F))

    private val alpha =
        doubleArrayOf(
            n / 2 - 2 * n * n / 3 + 5 * pow(n, 3) / 16 + 41 * pow(n, 4) / 180,
            13 * n * n / 48 - 3 * pow(n, 3) / 5 + 557 * pow(n, 4) / 1440,
            61 * pow(n, 3) / 240 - 103 * pow(n, 4) / 140,
            49561 * pow(n, 4) / 161280,
        )
    private val beta =
        doubleArrayOf(
            n / 2 - 2 * n * n / 3 + 37 * pow(n, 3) / 96 - pow(n, 4) / 360,
            n * n / 48 + pow(n, 3) / 15 - 437 * pow(n, 4) / 1440,
            17 * pow(n, 3) / 480 - 37 * pow(n, 4) / 840,
            4397 * pow(n, 4) / 161280,
        )
    private val delta =
        doubleArrayOf(
            2 * n - 2 * n * n / 3 - 2 * pow(n, 3) + 116 * pow(n, 4) / 45,
            7 * n * n / 3 - 8 * pow(n, 3) / 5 - 227 * pow(n, 4) / 45,
            56 * pow(n, 3) / 15 - 136 * pow(n, 4) / 35,
            4279 * pow(n, 4) / 630,
        )

    private fun pow(
        x: Double,
        k: Int,
    ): Double {
        var r = 1.0
        repeat(k) { r *= x }
        return r
    }

    /** The regular 6° zone of a longitude, without the exceptions of Norway and Svalbard (see the other [zoneOf]). */
    fun zoneOf(lon: Double): Int = (floor((normalizeLon(lon) + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)

    /**
     * The zone of a position: the regular 6° zone, except west of Norway, where 32V reaches west to 3° E between 56
     * and 64 N (31V stops there), and around Svalbard, where only 31X (0–9° E), 33X (9–21° E), 35X (21–33° E) and
     * 37X (33–42° E) exist between 72 and 84 N.
     */
    fun zoneOf(
        lat: Double,
        lon: Double,
    ): Int {
        val l = normalizeLon(lon)
        return when {
            lat >= NORWAY_SOUTH && lat < NORWAY_NORTH && l >= NORWAY_WEST && l < NORWAY_EAST -> {
                NORWAY_ZONE
            }

            lat >= SVALBARD_SOUTH && l >= 0.0 && l < SVALBARD_EDGES.last() -> {
                SVALBARD_ZONES[SVALBARD_EDGES.indexOfFirst { l < it }]
            }

            else -> {
                zoneOf(l)
            }
        }
    }

    fun centralMeridian(zone: Int): Double = (zone - 1) * 6.0 - 180.0 + 3.0

    /**
     * Converts to UTM in [zone] (default: the zone of the position; a neighbouring zone gives its grid carried on).
     * [north] picks the false northing: by default the hemisphere of [lat]; a grid that spans the equator keeps one of
     * them, so its northings run on (negative south of the equator in the northern one, above 10 000 km north of it in
     * the southern one).
     */
    fun toUtm(
        lat: Double,
        lon: Double,
        zone: Int = zoneOf(lat, lon),
        north: Boolean = lat >= 0,
    ): UtmCoordinate {
        val phi = Math.toRadians(lat)
        val dLon = Math.toRadians(normalizeLon(lon - centralMeridian(zone)))
        val sinPhi = sin(phi)
        // Conformal latitude through its tangent.
        val tau = sinh(atanh(sinPhi) - e * atanh(e * sinPhi))
        val xiP = atan2(tau, cos(dLon))
        val etaP = atanh(sin(dLon) / sqrt(1 + tau * tau))
        var xi = xiP
        var eta = etaP
        for (j in 1..alpha.size) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return UtmCoordinate(
            zone = zone,
            north = north,
            easting = FALSE_EASTING + K0 * rectifyingRadius * eta,
            northing = K0 * rectifyingRadius * xi + if (north) 0.0 else FALSE_NORTHING_SOUTH,
        )
    }

    /** Back to WGS84. */
    fun toLonLat(utm: UtmCoordinate): LonLat {
        val xi = (utm.northing - if (utm.north) 0.0 else FALSE_NORTHING_SOUTH) / (K0 * rectifyingRadius)
        val eta = (utm.easting - FALSE_EASTING) / (K0 * rectifyingRadius)
        var xiP = xi
        var etaP = eta
        for (j in 1..beta.size) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))
        var phi = chi
        for (j in 1..delta.size) phi += delta[j - 1] * sin(2 * j * chi)
        val lon = centralMeridian(utm.zone) + Math.toDegrees(atan(sinh(etaP) / cos(xiP)))
        return LonLat(normalizeLon(lon), Math.toDegrees(phi))
    }

    /** Latitude band letter C..X (without I and O); `null` outside 80 S .. 84 N. */
    fun bandLetter(lat: Double): Char? {
        if (lat < -80.0 || lat > 84.0) return null
        val index = floor((lat + 80.0) / 8.0).toInt().coerceAtMost(19)
        return BANDS[index]
    }

    /**
     * The angle from true north to the grid north of [zone] at a position, in degrees, positive when grid north lies
     * east of true north (east of the central meridian in the northern hemisphere). The spherical formula
     * `atan(tan(dLon) sin(lat))` is within 0.0001° of the ellipsoid's inside a zone.
     */
    fun convergenceDegrees(
        lat: Double,
        lon: Double,
        zone: Int = zoneOf(lat, lon),
    ): Double {
        val dLon = Math.toRadians(normalizeLon(lon - centralMeridian(zone)))
        return Math.toDegrees(atan(tan(dLon) * sin(Math.toRadians(lat))))
    }

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    private const val NORWAY_SOUTH = 56.0
    private const val NORWAY_NORTH = 64.0
    private const val NORWAY_WEST = 3.0
    private const val NORWAY_EAST = 12.0
    private const val NORWAY_ZONE = 32
    private const val SVALBARD_SOUTH = 72.0

    /** The east edges of 31X, 33X, 35X and 37X. */
    private val SVALBARD_EDGES = doubleArrayOf(9.0, 21.0, 33.0, 42.0)
    private val SVALBARD_ZONES = intArrayOf(31, 33, 35, 37)

    internal fun normalizeLon(lon: Double): Double {
        var l = lon % 360.0
        if (l >= 180.0) l -= 360.0
        if (l < -180.0) l += 360.0
        return if (abs(l) < 1e-12) 0.0 else l
    }
}
