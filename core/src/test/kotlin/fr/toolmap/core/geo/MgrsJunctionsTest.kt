package fr.toolmap.core.geo

import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Positions at grid zone junctions checked against GeoConvert (GeographicLib), an implementation independent of ToolMap
 * (`fixtures/mgrs/geoconvert-junctions.txt`, P24.1): the 6° E junction of the user's screenshots, Norway, Svalbard,
 * the equator, band edges, 80 S, 84 N and the antimeridian.
 */
class MgrsJunctionsTest {
    private class Ref(
        val lat: Double,
        val lon: Double,
        /** GeoConvert writes zones 1 to 9 with a leading zero (`01N`); ToolMap writes `1N`. */
        val mgrs: String,
        val zone: Int,
        val north: Boolean,
        val easting: Double,
        val northing: Double,
    )

    private val refs: List<Ref> by lazy {
        val dir = requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir not set by Gradle" }
        File(dir, "mgrs/geoconvert-junctions.txt")
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (position, mgrs, utm) = line.split("|").map { it.trim() }
                val (lat, lon) = position.split(" ").map { it.toDouble() }
                val (zone, e, n) = utm.split(" ")
                Ref(
                    lat = lat,
                    lon = lon,
                    mgrs = mgrs.trimStart('0'),
                    zone = zone.dropLast(1).toInt(),
                    north = zone.last() == 'n',
                    easting = e.toDouble(),
                    northing = n.toDouble(),
                )
            }
    }

    private val reference = Regex("""^(\d{1,2})([C-X])([A-Z])([A-Z])(\d{5})(\d{5})$""")

    @Test
    fun `the fixture covers the junctions`() {
        assertTrue(refs.size >= 40, "only ${refs.size} references")
        val zones = refs.map { it.mgrs.take(3) }.toSet()
        for (z in listOf("31T", "32T", "31V", "32V", "33V", "31X", "33X", "35X", "37X", "38X", "32N", "32M")) {
            assertTrue(z in zones, "no reference in $z")
        }
    }

    @Test
    fun `MGRS references match GeographicLib, Norway and Svalbard included`() {
        for (r in refs) {
            val ref = assertNotNull(Mgrs.of(r.lat, r.lon), "at ${r.lat},${r.lon}")
            assertEquals(r.mgrs, ref.format(5, separators = false), "at ${r.lat},${r.lon}")
        }
    }

    @Test
    fun `UTM zone, hemisphere and coordinates match GeographicLib to a meter`() {
        for (r in refs) {
            val u = Utm.toUtm(r.lat, r.lon)
            assertEquals(r.zone, u.zone, "zone at ${r.lat},${r.lon}")
            assertEquals(r.north, u.north, "hemisphere at ${r.lat},${r.lon}")
            // GeoConvert rounds the UTM figures to the meter.
            assertEquals(r.easting, u.easting, 0.51, "easting at ${r.lat},${r.lon}")
            assertEquals(r.northing, u.northing, 0.51, "northing at ${r.lat},${r.lon}")
        }
    }

    @Test
    fun `the grid zone of each reference is the one GeographicLib names`() {
        for (r in refs) {
            val zone = assertNotNull(GridZones.of(r.lat, r.lon), "at ${r.lat},${r.lon}")
            assertEquals(r.mgrs.takeWhile { it.isDigit() } + r.mgrs.dropWhile { it.isDigit() }.first(), zone.name)
            assertTrue(zone.contains(r.lat, r.lon), "${zone.name} does not contain ${r.lat},${r.lon}")
        }
    }

    @Test
    fun `each reference reads back to its position`() {
        for (r in refs) {
            val m = assertNotNull(reference.matchEntire(r.mgrs), r.mgrs).groupValues
            val back =
                assertNotNull(
                    Mgrs.toLonLat(m[1].toInt(), m[2][0], m[3][0], m[4][0], m[5].toInt(), m[6].toInt()),
                    r.mgrs,
                )
            // The reference names a 1 m square (truncated): its centre is within 1.5 m of the position.
            val dNorth = (back.lat - r.lat) * METERS_PER_DEGREE
            val dEast = Utm.normalizeLon(back.lon - r.lon) * METERS_PER_DEGREE * cos(Math.toRadians(r.lat))
            assertTrue(Math.hypot(dNorth, dEast) < 1.5, "${r.mgrs} reads back ${Math.hypot(dNorth, dEast)} m away")
        }
    }

    @Test
    fun `Svalbard has no 32X, 34X or 36X`() {
        assertNull(Mgrs.toLonLat(32, 'X', 'N', 'G', 50_000, 50_000))
        assertNull(Mgrs.toLonLat(34, 'X', 'E', 'G', 50_000, 50_000))
        assertNull(Mgrs.toLonLat(36, 'X', 'W', 'G', 50_000, 50_000))
        assertEquals(31, Utm.zoneOf(78.0, 8.99))
        assertEquals(33, Utm.zoneOf(78.0, 9.01))
        assertEquals(37, Utm.zoneOf(78.0, 41.99))
        assertEquals(38, Utm.zoneOf(78.0, 42.01))
        // Below 72 N the zones are regular again.
        assertEquals(32, Utm.zoneOf(71.99, 10.0))
    }

    @Test
    fun `32V reaches 3 E between 56 and 64 N only`() {
        assertEquals(31, Utm.zoneOf(60.0, 2.999))
        assertEquals(32, Utm.zoneOf(60.0, 3.001))
        assertEquals(31, Utm.zoneOf(55.999, 4.0))
        assertEquals(32, Utm.zoneOf(56.001, 4.0))
        assertEquals(31, Utm.zoneOf(64.001, 4.0))
        // The regular zone of a longitude alone does not know the exceptions.
        assertEquals(31, Utm.zoneOf(4.0))
    }

    @Test
    fun `a grid keeps one hemisphere across the equator`() {
        val south = Utm.toUtm(-0.0001, 6.5)
        assertEquals(false, south.north)
        // In the northern hemisphere's grid, the same place is just below 0.
        val carried = Utm.toUtm(-0.0001, 6.5, north = true)
        assertEquals(south.northing - 10_000_000.0, carried.northing, 1e-6)
        assertTrue(carried.northing < 0.0)
        // And its 100 km square letters do not change: 10 000 km is five cycles of the row letters.
        assertEquals(Mgrs.of(south, 'M').format(), Mgrs.of(carried, 'M').format())
    }

    @Test
    fun `convergence is the angle the projection gives to the meridian`() {
        // At the centres of the user's screenshots on both sides of 6 E: about 2°08' east in 31T, west in 32T.
        val places = listOf(45.42711 to 5.99937, 45.42742 to 6.00573, 60.0 to 3.001, 78.0 to 41.99, -33.86 to 151.21)
        for ((lat, lon) in places) {
            val gamma = Utm.convergenceDegrees(lat, lon)
            // Grid north as the projection draws it: the direction of a short step due north, measured in the grid.
            val a = Utm.toUtm(lat, lon)
            val b = Utm.toUtm(lat + 1e-5, lon, a.zone, a.north)
            val fromProjection = -Math.toDegrees(atan2(b.easting - a.easting, b.northing - a.northing))
            assertEquals(fromProjection, gamma, 1e-3, "at $lat,$lon")
        }
        assertEquals(2.136, Utm.convergenceDegrees(45.42711, 5.99937), 0.01)
        assertEquals(-2.132, Utm.convergenceDegrees(45.42742, 6.00573), 0.01)
        assertTrue(abs(Utm.convergenceDegrees(45.0, 3.0)) < 1e-12)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
