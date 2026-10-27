package fr.toolmap.core.geo

import fr.toolmap.core.geo.ParsedCoordinate.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridReferenceTest {
    private fun position(text: String): Pair<Double, Double> {
        val r = CoordinateParser.parse(text)
        assertIs<ParsedCoordinate.Success>(r, "'$text' -> $r")
        return r.lat to r.lon
    }

    private fun assertWithin(
        text: String,
        lat: Double,
        lon: Double,
        meters: Double,
    ) {
        val (a, b) = position(text)
        val d = fr.toolmap.core.track.Geodesy.distance(lat, lon, a, b)
        assertTrue(d <= meters, "'$text' is ${"%.2f".format(d)} m from ($lat, $lon)")
    }

    // References computed with PROJ and the `mgrs` library (see ProjectionsTest).
    private class Ref(
        val lat: Double,
        val lon: Double,
        val e: Long,
        val n: Long,
        val zone: Int,
        val lx: Long,
        val ly: Long,
        val mgrs: String,
    )

    private val refs =
        listOf(
            Ref(44.57641126591504, -2.6143853259437257, 530615, 4935969, 30, 254620, 6402210, "30TWQ3061535969"),
            Ref(47.68387749387861, -3.5945464166557155, 455379, 5281336, 30, 205822, 6752146, "30TVT5537981336"),
            Ref(46.59087904091355, 0.07111146140731961, 275640, 5163869, 31, 475810, 6614251, "31TBM7564063869"),
            Ref(45.532932296853886, 5.8356515584004764, 721409, 5046066, 31, 921274, 6496579, "31TGL2140946066"),
        )

    @Test
    fun `an MGRS reference of a real position gives it back to the meter`() {
        for (r in refs) {
            assertWithin(r.mgrs, r.lat, r.lon, 1.5)
            // With the separators of a printed map.
            val spaced = "${r.mgrs.take(
                3,
            )} ${r.mgrs.substring(3, 5)} ${r.mgrs.substring(5, 10)} ${r.mgrs.substring(10)}"
            assertWithin(spaced, r.lat, r.lon, 1.5)
            assertWithin(spaced.lowercase(), r.lat, r.lon, 1.5)
        }
    }

    @Test
    fun `fewer digits name a bigger square and give its centre`() {
        // 31T DL 4 4 is a 10 km square: its centre is within 7.1 km of any point inside it.
        assertWithin("31T GL 2 4", 45.5329, 5.8356, 7_200.0)
        val (lat100, _) = position("31T GL")
        assertEquals(45.5, lat100, 1.0)
        // Each digit more halves the error: 5 digits is under 1 m, 3 digits under 71 m.
        assertWithin("31T GL 214 460", 45.532932, 5.835651, 75.0)
    }

    @Test
    fun `UTM references in the usual spellings`() {
        for (r in refs) {
            assertWithin("${r.zone}T ${r.e} E ${r.n} N", r.lat, r.lon, 1.5)
            assertWithin("UTM ${r.zone}T ${r.e} ${r.n}", r.lat, r.lon, 1.5)
            assertWithin("${r.zone}T${r.e} ${r.n}", r.lat, r.lon, 1.5)
            assertWithin("${r.zone}T E${r.e} N${r.n}", r.lat, r.lon, 1.5)
            assertWithin("${r.zone}T ${r.e}mE ${r.n}mN", r.lat, r.lon, 1.5)
            assertWithin("${r.zone}t, ${r.e}, ${r.n}", r.lat, r.lon, 1.5)
        }
    }

    @Test
    fun `Lambert 93 references`() {
        for (r in refs) {
            assertWithin("L93 ${r.lx} E ${r.ly} N", r.lat, r.lon, 1.5)
            assertWithin("Lambert 93 ${r.lx} ${r.ly}", r.lat, r.lon, 1.5)
            assertWithin("RGF93 ${r.lx}, ${r.ly}", r.lat, r.lon, 1.5)
            assertWithin("EPSG:2154 ${r.lx} ${r.ly}", r.lat, r.lon, 1.5)
        }
    }

    @Test
    fun `southern hemisphere goes through the band letter`() {
        // Sydney. The band H is south: the northing carries the false northing of 10 000 000.
        val lat = -33.8568
        val lon = 151.2153
        val text = CoordinateFormat.utm(lat, lon)!!
        assertTrue(text.startsWith("56H "), text)
        assertWithin(text, lat, lon, 1.5)
        val mgrs = Mgrs.of(lat, lon)!!.format()
        assertWithin(mgrs, lat, lon, 1.5)
    }

    @Test
    fun `format then parse is the identity for every system over a spread of places`() {
        val places =
            listOf(
                48.8566 to 2.3522, // Paris
                45.8326 to 6.8652, // Mont Blanc
                -33.8568 to 151.2153, // Sydney
                64.1466 to -21.9426, // Reykjavik
                -54.8019 to -68.3030, // Ushuaia
                0.0 to 0.0,
                1.2 to -179.9,
                -0.5 to 179.9,
                35.6762 to 139.6503, // Tokyo
                -22.9068 to -43.1729, // Rio
            )
        for ((lat, lon) in places) {
            for (system in listOf(CoordinateSystem.UTM, CoordinateSystem.MGRS, CoordinateSystem.DD)) {
                val text = CoordinateFormat.format(system, lat, lon).text
                assertWithin(text, lat, lon, 1.5)
            }
        }
        // Lambert 93 is read back even outside France (the formula has no limit of its own).
        for ((lat, lon) in places.take(3)) {
            val text = CoordinateFormat.format(CoordinateSystem.LAMBERT93, lat, lon).text
            if (Lambert93.toLambert(lat, lon).let { it.easting in 0.0..1_300_000.0 && it.northing in 6e6..7.2e6 }) {
                assertWithin(text, lat, lon, 1.5)
            }
        }
    }

    @Test
    fun `impossible grid references are refused with the grid reason`() {
        val bad =
            listOf(
                "31T AA 12345 12345", // column A is not in zone 31 (set J..R)
                "31T DQ 123 45", // odd digit count
                "61T DQ 12345 12345", // zone 61
                "31T 1500000 5000000", // easting beyond a zone
                "31T 500000 99999999", // northing beyond the pole
                "L93 5 6", // nowhere near France
                "L93 900000", // one number only
            )
        for (text in bad) {
            val r = CoordinateParser.parse(text)
            assertIs<ParsedCoordinate.Failure>(r, "'$text' -> $r")
            assertEquals(Reason.GRID, r.reason, text)
        }
    }

    @Test
    fun `plain degrees are not mistaken for a grid`() {
        assertNull(GridReference.parse("44.8, 5.9"))
        assertNull(GridReference.parse("45 6"))
        assertNull(GridReference.parse("44 48 36 N 5 54 36 E"))
        assertNull(GridReference.parse("N 44° 48.000' E 5° 54.000'"))
        assertNull(GridReference.parse("44°48'00\"N 5°54'00\"E"))
        assertNull(GridReference.parse("12 34567 1234567")) // zone and two numbers, but no band and no 'UTM'
        assertNull(GridReference.parse("   "))
        assertEquals(44.8, position("44.8, 5.9").first, 1e-9)
    }

    @Test
    fun `the formatter writes UTM and Lambert in the shapes the chip shows`() {
        val paris = CoordinateFormat.format(CoordinateSystem.UTM, 48.8566, 2.3522)
        assertNotNull(Regex("^31U \\d{6} E \\d{7} N$").matchEntire(paris.text), paris.text)
        assertEquals(false, paris.outsideValidity)
        val lambert = CoordinateFormat.format(CoordinateSystem.LAMBERT93, 48.8566, 2.3522)
        assertNotNull(Regex("^L93 \\d{6} E \\d{7} N$").matchEntire(lambert.text), lambert.text)
        assertEquals(false, lambert.outsideValidity)
        assertEquals(true, CoordinateFormat.format(CoordinateSystem.LAMBERT93, 40.7, -74.0).outsideValidity)
        // Beyond the UTM latitudes the text falls back to decimal degrees, which can always be written.
        assertEquals("86.00000, 10.00000", CoordinateFormat.format(CoordinateSystem.UTM, 86.0, 10.0).text)
        assertEquals("-85.00000, 10.00000", CoordinateFormat.format(CoordinateSystem.MGRS, -85.0, 10.0).text)
    }
}
