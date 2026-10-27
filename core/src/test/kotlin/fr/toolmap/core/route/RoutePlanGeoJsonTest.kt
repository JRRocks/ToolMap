package fr.toolmap.core.route

import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.WaypointSymbols
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePlanGeoJsonTest {
    private val a = RouteStop(45.0, 6.0)
    private val b = RouteStop(45.01, 6.0, StopKind.NAMED, "Refuge \"Nord\"", "Lodge")
    private val c = RouteStop(45.02, 6.01)
    private val d = RouteStop(45.03, 6.0)

    private fun plan(): RoutePlan =
        RoutePlan(
            listOf(a, b, c, d),
            listOf(
                RouteLeg(
                    RoutingProfiles.HIKING,
                    LegMode.ROUTED,
                    LegState.OK,
                    listOf(a.point, TrackPoint(45.005, 6.002), b.point),
                    1,
                ),
                RouteLeg.straight(b, c, RoutingProfiles.HIKING),
                RouteLeg.straight(c, d, RoutingProfiles.HIKING, LegMode.ROUTED, LegState.PENDING),
            ),
        )

    private fun count(
        text: String,
        part: String,
    ): Int = text.windowed(part.length).count { it == part }

    @Test
    fun `the ends get letters and the named stops their icon`() {
        val icons = RoutePlanGeoJson.icons(plan())
        assertEquals(
            setOf(
                RouteMarkers.LETTER_PREFIX + "A",
                RouteMarkers.LETTER_PREFIX + "D",
                WaypointSymbols.iconOf("Lodge").id,
            ),
            icons,
        )
    }

    @Test
    fun `each leg says how it is drawn, with a label in the middle of the legs not routed`() {
        val text = RoutePlanGeoJson.lines(plan(), "calcul…", "à la main")
        assertEquals(3, count(text, "\"LineString\""))
        assertTrue("\"st\":\"r\"" in text)
        assertTrue("\"st\":\"h\"" in text)
        assertTrue("\"st\":\"p\"" in text)
        assertTrue("\"label\":\"à la main\"" in text)
        assertTrue("\"label\":\"calcul…\"" in text)
        assertEquals(2, count(text, "\"label\""))
    }

    @Test
    fun `an empty label puts no label`() {
        val text = RoutePlanGeoJson.lines(plan(), "calcul…", "")
        assertEquals(1, count(text, "\"label\""))
        assertFalse("à la main" in text)
    }

    @Test
    fun `an empty plan has no features`() {
        val empty = """{"type":"FeatureCollection","features":[]}"""
        assertEquals(empty, RoutePlanGeoJson.lines(RoutePlan(), "", ""))
        assertEquals(empty, RoutePlanGeoJson.handles(RoutePlan(), null))
    }

    @Test
    fun `handles carry kind, selection, colour, icon and name`() {
        val text = RoutePlanGeoJson.handles(plan(), selected = 2)
        assertTrue("\"kind\":\"start\"" in text)
        assertTrue("\"kind\":\"end\"" in text)
        assertTrue("\"kind\":\"named\"" in text)
        assertTrue("\"kind\":\"pass-sel\",\"i\":2,\"sel\":true" in text)
        assertTrue("\"color\":\"#43a047\"" in text)
        assertTrue("\"color\":\"#e53935\"" in text)
        assertTrue("\"name\":\"Refuge \\\"Nord\\\"\"" in text)
        assertFalse("\"kind\":\"m\"" in text)
        // The start is drawn last, above the end of a loop.
        assertTrue(text.lastIndexOf("\"kind\":\"start\"") > text.lastIndexOf("\"kind\":\"end\""))
    }

    @Test
    fun `a loop shows « A » alone on its start`() {
        val loop =
            RoutePlan(
                listOf(a, c, d, a.discreet()),
                listOf(
                    RouteLeg.straight(a, c, RoutingProfiles.HIKING),
                    RouteLeg.straight(c, d, RoutingProfiles.HIKING),
                    RouteLeg.straight(d, a, RoutingProfiles.HIKING),
                ),
            )
        assertEquals(setOf(RouteMarkers.LETTER_PREFIX + "A"), RoutePlanGeoJson.icons(loop))
        val text = RoutePlanGeoJson.handles(loop, null)
        assertEquals(1, count(text, "\"icon\""))
        assertTrue("\"kind\":\"end\"" in text)
    }

    @Test
    fun `drawing by hand shows the middle of the straight legs only`() {
        val text = RoutePlanGeoJson.handles(plan(), null, midpoints = true)
        assertEquals(1, count(text, "\"kind\":\"m\""))
        assertTrue("\"kind\":\"m\",\"leg\":1" in text)
        val m = RoutePlanGeoJson.midpoint(plan(), 1)
        assertEquals((b.lat + c.lat) / 2, m.lat, 1e-4)
    }
}
