package fr.toolmap.core.measure

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.Utm
import fr.toolmap.core.geo.UtmCoordinate
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.core.track.TrackFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureTest {
    private val a = 6_378_137.0
    private val e2 = (1 / 298.257223563) * (2 - 1 / 298.257223563)

    /**
     * Area of the lat/lon rectangle by integrating the surface element of the ellipsoid, M·N·cosφ dφ dλ (Simpson):
     * a different route from the equal-area projection under test.
     */
    private fun rectangleArea(
        lat1: Double,
        lat2: Double,
        dLonDegrees: Double,
    ): Double {
        val steps = 2000
        val h = Math.toRadians(lat2 - lat1) / steps

        fun f(phi: Double): Double {
            val s = sin(phi)
            val w = 1 - e2 * s * s
            // M = a(1-e²)/w^1.5, N = a/w^0.5, element = M N cosφ
            return a * a * (1 - e2) / (w * w) * cos(phi)
        }
        var sum = f(Math.toRadians(lat1)) + f(Math.toRadians(lat2))
        for (i in 1 until steps) sum += f(Math.toRadians(lat1) + i * h) * if (i % 2 == 1) 4 else 2
        return sum * h / 3 * Math.toRadians(dLonDegrees)
    }

    private fun rectangle(
        lon1: Double,
        lat1: Double,
        lon2: Double,
        lat2: Double,
    ) = listOf(LonLat(lon1, lat1), LonLat(lon2, lat1), LonLat(lon2, lat2), LonLat(lon1, lat2))

    private fun assertRelative(
        expected: Double,
        actual: Double,
        relative: Double,
        message: String = "",
    ) = assertEquals(expected, actual, expected * relative, message)

    @Test
    fun `a lat-lon rectangle has the area of the ellipsoid surface integral`() {
        for ((lat1, lat2, dLon) in listOf(
            Triple(0.0, 1.0, 1.0),
            Triple(44.0, 46.0, 3.0),
            Triple(59.9, 60.1, 0.2),
            Triple(-46.0, -44.0, 2.0),
            Triple(10.0, 50.0, 40.0),
            Triple(-1.0, 1.0, 1.0),
        )) {
            val expected = rectangleArea(lat1, lat2, dLon)
            val actual = Measure.areaSquareMeters(rectangle(5.0, lat1, 5.0 + dLon, lat2))
            assertRelative(expected, actual, 1e-9, "($lat1..$lat2) × $dLon")
        }
        // 1° × 1° at the equator: about 12 308 km² (a spherical Earth would say 12 364).
        assertEquals(12_308.5, Measure.areaSquareMeters(rectangle(0.0, 0.0, 1.0, 1.0)) / 1e6, 0.1)
    }

    @Test
    fun `a square of the UTM grid has its grid area corrected by the scale factor`() {
        // On the central meridian the scale factor is exactly 0.9996: the ground is 1/0.9996² larger than the grid.
        fun square(
            east: Double,
            north: Double,
            side: Double,
        ) = listOf(
            Utm.toLonLat(UtmCoordinate(31, true, east, north)),
            Utm.toLonLat(UtmCoordinate(31, true, east + side, north)),
            Utm.toLonLat(UtmCoordinate(31, true, east + side, north + side)),
            Utm.toLonLat(UtmCoordinate(31, true, east, north + side)),
        )
        val centre = Measure.areaSquareMeters(square(500_000.0, 5_000_000.0, 1000.0))
        // The polygon here has edges that are straight in UTM, and geodesic-ish in the projection: 1e-6 is plenty.
        assertRelative(1_000_000.0 / (0.9996 * 0.9996), centre, 2e-5)
        // 300 km east of the meridian the scale factor is about 0.9996 (1 + x² / 2R²).
        val edge = Measure.areaSquareMeters(square(800_000.0, 5_000_000.0, 1000.0))
        val k = 0.9996 * (1 + 300_000.0 * 300_000.0 / (2 * 6_375_000.0 * 6_375_000.0))
        assertRelative(1_000_000.0 / (k * k), edge, 1e-4)
        // A 10 km square.
        val big = Measure.areaSquareMeters(square(500_000.0, 5_000_000.0, 10_000.0))
        assertRelative(100_000_000.0 / (0.9996 * 0.9996), big, 5e-5)
    }

    @Test
    fun `orientation, start point and the antimeridian do not change the area`() {
        val r = rectangle(5.0, 44.0, 6.0, 45.0)
        val area = Measure.areaSquareMeters(r)
        assertEquals(area, Measure.areaSquareMeters(r.reversed()), 1e-3)
        assertEquals(area, Measure.areaSquareMeters(r.drop(2) + r.take(2)), 1e-3)
        // The same cell either side of the antimeridian.
        val across = Measure.areaSquareMeters(rectangle(179.5, 44.0, -179.5, 45.0))
        assertRelative(area, across, 1e-9)
    }

    @Test
    fun `fewer than three points or points in a line enclose nothing`() {
        assertEquals(0.0, Measure.areaSquareMeters(emptyList()))
        assertEquals(0.0, Measure.areaSquareMeters(listOf(LonLat(5.0, 45.0), LonLat(5.1, 45.0))))
        assertEquals(
            0.0,
            Measure.areaSquareMeters(listOf(LonLat(5.0, 45.0), LonLat(5.1, 45.0), LonLat(5.2, 45.0))),
            1e-3,
        )
        // A bow-tie (self-crossing) polygon has two lobes that cancel: the figure is the signed sum, here zero.
        val bowTie = listOf(LonLat(5.0, 45.0), LonLat(5.1, 45.1), LonLat(5.1, 45.0), LonLat(5.0, 45.1))
        assertTrue(Measure.areaSquareMeters(bowTie) < 1.0)
    }

    @Test
    fun `measure of nothing, one point, two points and a polygon`() {
        val none = Measure.measure(emptyList())
        assertEquals(0, none.pointCount)
        assertEquals(0.0, none.lengthMeters)
        assertNull(none.areaSquareMeters)
        assertNull(none.lastBearingDegrees)
        assertNull(none.directMeters)

        val one = Measure.measure(listOf(LonLat(5.0, 45.0)))
        assertEquals(0.0, one.lengthMeters)
        assertNull(one.lastBearingDegrees)

        // 0.01° of longitude along a parallel at 45 N, due east.
        val two = Measure.measure(listOf(LonLat(5.0, 45.0), LonLat(5.01, 45.0)))
        assertEquals(786.0, two.lengthMeters, 1.5) // 111 195 m × 0.01 × cos 45° ≈ 786 m
        assertEquals(90.0, two.lastBearingDegrees!!, 0.1)
        assertEquals(two.lengthMeters, two.lastSegmentMeters!!, 1e-9)
        assertEquals(two.lengthMeters, two.directMeters!!, 1e-9)
        assertNull(two.areaSquareMeters)
        assertNull(two.perimeterMeters)

        val square = Measure.measure(rectangle(5.0, 45.0, 5.01, 45.01))
        assertEquals(4, square.pointCount)
        assertNotNull(square.areaSquareMeters)
        // Three sides drawn, the closing one is in the perimeter only.
        assertEquals(
            square.perimeterMeters!!,
            square.lengthMeters + Measure.distance(LonLat(5.0, 45.01), LonLat(5.0, 45.0)),
            1e-6,
        )
        // Last segment goes west along the top edge.
        assertEquals(270.0, square.lastBearingDegrees!!, 0.1)
        // "As the crow flies": first corner to the last one is due north.
        assertEquals(0.0, square.directBearingDegrees!!, 0.1)
    }

    @Test
    fun `an out-and-back path is twice the way and a polygon is not its length`() {
        val way = listOf(LonLat(5.0, 45.0), LonLat(5.0, 45.1), LonLat(5.1, 45.1))
        val back = way + way.reversed().drop(1)
        assertEquals(2 * Measure.pathLength(way), Measure.pathLength(back), 1e-6)
        assertEquals(
            sqrt(2.0),
            Measure.distance(LonLat(0.0, 0.0), LonLat(1.0, 1.0)) / Measure.distance(LonLat(0.0, 0.0), LonLat(0.0, 1.0)),
            0.01,
        )
    }

    @Test
    fun `areas are written in square metres, hectares and square kilometres, or feet, acres and square miles`() {
        val l = Locale.US
        assertEquals("0 m²", TrackFormat.area(0.0, l))
        assertEquals("9500 m²", TrackFormat.area(9_500.0, l))
        assertEquals("1.00 ha", TrackFormat.area(10_000.0, l))
        assertEquals("12.35 ha", TrackFormat.area(123_456.0, l))
        assertEquals("1.000 km²", TrackFormat.area(1_000_000.0, l))
        assertEquals("12.346 km²", TrackFormat.area(12_345_678.0, l))
        val i = UnitSystem.IMPERIAL
        assertEquals("1076 ft²", TrackFormat.area(100.0, l, i))
        assertEquals("1.00 ac", TrackFormat.area(4_046.9, l, i))
        assertEquals("247.11 ac", TrackFormat.area(1_000_000.0, l, i))
        assertEquals("1.000 mi²", TrackFormat.area(2_590_000.0, l, i))
    }
}
