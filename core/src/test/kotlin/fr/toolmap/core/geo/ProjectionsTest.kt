package fr.toolmap.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reference values computed with PROJ (EPSG:326xx / 327xx, EPSG:2154) and the `mgrs` library. */
class ProjectionsTest {
    private class Ref(
        val lat: Double,
        val lon: Double,
        val zone: Int,
        val north: Boolean,
        val e: Double,
        val n: Double,
        val lx: Double?,
        val ly: Double?,
        val mgrs: String?,
    )

    private val refs =
        listOf(
            Ref(
                44.57641126591504,
                -2.6143853259437257,
                30,
                true,
                530615.4369868224,
                4935969.122838298,
                254620.158826229,
                6402210.31516703,
                "30TWQ3061535969",
            ),
            Ref(
                47.68387749387861,
                -3.5945464166557155,
                30,
                true,
                455379.3188569226,
                5281336.683753321,
                205822.91391553095,
                6752146.39294708,
                "30TVT5537981336",
            ),
            Ref(
                46.59087904091355,
                0.07111146140731961,
                31,
                true,
                275640.8961094668,
                5163869.287719299,
                475810.0405752151,
                6614251.0303879315,
                "31TBM7564063869",
            ),
            Ref(
                42.05098978535972,
                1.8429466648677533,
                31,
                true,
                404251.4513891748,
                4656085.183202932,
                604024.9565818071,
                6106316.872536584,
                "31TDG0425156085",
            ),
            Ref(
                41.85620875519886,
                0.9205710457798233,
                31,
                true,
                327395.26014702994,
                4635901.912799291,
                526958.789332901,
                6086211.263679601,
                "31TCG2739535901",
            ),
            Ref(
                42.16362652395888,
                -3.366087333201687,
                30,
                true,
                469759.2929602551,
                4668008.600434748,
                173509.48983554344,
                6139383.837439148,
                "30TVM6975968008",
            ),
            Ref(
                45.532932296853886,
                5.8356515584004764,
                31,
                true,
                721409.6272025686,
                5046066.853321635,
                921274.5336638116,
                6496579.03902305,
                "31TGL2140946066",
            ),
            Ref(
                42.676118630921636,
                -1.709512942412318,
                30,
                true,
                605736.9953005228,
                4725656.581152934,
                313719.56915726786,
                6186688.254454249,
                "30TXN0573625656",
            ),
            Ref(
                47.4606156128531,
                7.34636178071257,
                32,
                true,
                375367.18758763274,
                5257678.085108612,
                1027283.2046186705,
                6715705.10363569,
                "32TLT7536757678",
            ),
            Ref(
                46.982478011866235,
                0.4585059331347523,
                31,
                true,
                306722.60119526053,
                5206352.1156490855,
                506851.48572097754,
                6656693.328993984,
                "31TCN0672206352",
            ),
            Ref(
                50.77442350313274,
                -3.9177164922780463,
                30,
                true,
                435293.00817233406,
                5625141.871390565,
                211748.73975231516,
                7096719.934598374,
                "30UVB3529325141",
            ),
            Ref(
                49.655450360962455,
                -0.8798839208540468,
                30,
                true,
                653020.0176101903,
                5502481.463747837,
                419797.53958057775,
                6957588.811496689,
                "30UXA5302002481",
            ),
            Ref(
                -55.63067649609512,
                -136.83037876794413,
                8,
                false,
                384764.141810646,
                3833504.346678555,
                null,
                null,
                "08FLD8476433504",
            ),
            Ref(
                -29.025944495486634,
                113.17323656497126,
                49,
                false,
                711647.2653620611,
                6787191.784302976,
                null,
                null,
                "49JGH1164787191",
            ),
            Ref(
                -49.72232645232213,
                29.212858591162927,
                35,
                false,
                659494.1573907305,
                4489891.261901166,
                null,
                null,
                "35FPE5949489891",
            ),
            Ref(
                24.50398196604182,
                -45.68167970418821,
                23,
                true,
                430937.2367500436,
                2710196.6019575745,
                null,
                null,
                "23RMH3093710196",
            ),
            Ref(
                9.734603444948362,
                -156.5215469595503,
                4,
                true,
                771925.3395823163,
                1077064.4561773653,
                null,
                null,
                "04PGR7192577064",
            ),
            Ref(
                -69.34461046547031,
                -105.2667808106811,
                13,
                false,
                489497.56530176825,
                2307188.583943354,
                null,
                null,
                "13DDD8949707188",
            ),
            Ref(
                31.224795655449313,
                -25.921954570353762,
                26,
                true,
                602677.3587005178,
                3455016.1607920085,
                null,
                null,
                "26RPV0267755016",
            ),
            Ref(
                -28.108158398959773,
                30.631147135734665,
                36,
                false,
                267284.2492543245,
                6888548.923602009,
                null,
                null,
                "36JTP6728488548",
            ),
            Ref(
                -5.58413102793439,
                -71.68341512280172,
                19,
                false,
                202703.3230642698,
                9382088.3378922,
                null,
                null,
                "19MBP0270382088",
            ),
            Ref(
                49.68947600664356,
                71.24000727518651,
                42,
                true,
                661559.7172433021,
                5506514.950862923,
                null,
                null,
                "42UXA6155906514",
            ),
            Ref(
                -39.456365263011236,
                26.64368827260421,
                35,
                false,
                469345.48388897465,
                5632517.510745985,
                null,
                null,
                "35HMS6934532517",
            ),
            Ref(
                6.081833617455132,
                134.29922341528754,
                53,
                true,
                422455.1926101782,
                672300.8918448151,
                null,
                null,
                "53NMG2245572300",
            ),
            Ref(
                48.8566,
                2.3522,
                31,
                true,
                452482.5327026278,
                5411717.1768689,
                652469.0227091359,
                6862035.259420079,
                "31UDQ5248211717",
            ),
            Ref(
                45.0,
                3.0,
                31,
                true,
                500000.0000000007,
                4982950.400226552,
                700000.0,
                6433418.985424661,
                "31TEK0000082950",
            ),
            Ref(
                0.0,
                0.0,
                31,
                true,
                166021.44308054057,
                0.0,
                null,
                null,
                "31NAA6602100000",
            ),
            Ref(
                -33.8688,
                151.2093,
                56,
                false,
                334368.633648097,
                6250948.345385009,
                null,
                null,
                "56HLH3436850948",
            ),
            Ref(
                45.188,
                5.724,
                31,
                true,
                713989.1938340532,
                5007445.233328477,
                913886.7726731828,
                6457988.30676883,
                "31TGL1398907445",
            ),
            Ref(
                78.2,
                15.6,
                33,
                true,
                513696.94541704346,
                8680760.053195585,
                null,
                null,
                "33XWG1369680760",
            ),
        )

    @Test
    fun `UTM matches PROJ to a millimeter`() {
        for (r in refs) {
            val u = Utm.toUtm(r.lat, r.lon)
            assertEquals(r.zone, u.zone)
            assertEquals(r.north, u.north)
            assertEquals(r.e, u.easting, 0.001, "easting at ${r.lat},${r.lon}")
            assertEquals(r.n, u.northing, 0.001, "northing at ${r.lat},${r.lon}")
        }
    }

    @Test
    fun `UTM inverse brings the position back`() {
        for (r in refs) {
            val back = Utm.toLonLat(UtmCoordinate(r.zone, r.north, r.e, r.n))
            assertEquals(r.lat, back.lat, 1e-8, "lat at ${r.lat},${r.lon}")
            assertEquals(r.lon, back.lon, 1e-8, "lon at ${r.lat},${r.lon}")
        }
    }

    @Test
    fun `an extended zone keeps working past the zone edge`() {
        // Paris is at 2.35 E: zone 31 covers it, zone 30 (the neighbour to the west) extends its grid eastwards.
        val extended = Utm.toUtm(48.8566, 2.3522, zone = 30)
        assertTrue(
            extended.easting in 850_000.0..950_000.0,
            "east of the neighbour's central meridian: ${extended.easting}",
        )
        val back = Utm.toLonLat(extended)
        assertEquals(2.3522, back.lon, 1e-8)
        assertEquals(48.8566, back.lat, 1e-8)
    }

    @Test
    fun `Lambert 93 matches PROJ to a millimeter and round trips`() {
        var checked = 0
        for (r in refs) {
            val lx = r.lx ?: continue
            val ly = r.ly ?: continue
            val p = Lambert93.toLambert(r.lat, r.lon)
            assertEquals(lx, p.easting, 0.001, "easting at ${r.lat},${r.lon}")
            assertEquals(ly, p.northing, 0.001, "northing at ${r.lat},${r.lon}")
            val back = Lambert93.toLonLat(p)
            assertEquals(r.lat, back.lat, 1e-8)
            assertEquals(r.lon, back.lon, 1e-8)
            checked++
        }
        assertTrue(checked >= 10, "only $checked French points")
    }

    @Test
    fun `MGRS matches the reference library`() {
        var checked = 0
        for (r in refs) {
            val expected = r.mgrs ?: continue
            val ref = Mgrs.of(r.lat, r.lon)
            assertNotNull(ref)
            // Norway and Svalbard follow their exceptions since P24.1 (MgrsJunctionsTest): nothing is skipped. The
            // library writes zones 1 to 9 with a leading zero (`08F`), ToolMap without.
            assertEquals(expected.trimStart('0'), ref.format(5, separators = false), "at ${r.lat},${r.lon}")
            checked++
        }
        assertTrue(checked >= 20, "only $checked compared")
    }

    @Test
    fun `MGRS is truncated like a printed map and has no value near the poles`() {
        val ref = Mgrs.of(48.8566, 2.3522)!!
        assertEquals("31U DQ 52482 11717", ref.format())
        assertEquals("31U DQ 524 117", ref.format(3))
        assertEquals("31U DQ", ref.format(0))
        assertNull(Mgrs.of(89.0, 0.0))
        assertNull(Mgrs.of(-85.0, 0.0))
    }

    @Test
    fun `zones and bands`() {
        assertEquals(31, Utm.zoneOf(2.35))
        assertEquals(1, Utm.zoneOf(-179.9))
        assertEquals(60, Utm.zoneOf(179.9))
        assertEquals(60, Utm.zoneOf(180.0).let { if (it == 1) 60 else it }.coerceAtMost(60))
        assertEquals('U', Utm.bandLetter(48.8566))
        assertEquals('C', Utm.bandLetter(-79.0))
        assertEquals('X', Utm.bandLetter(84.0))
        assertNull(Utm.bandLetter(85.0))
    }
}
