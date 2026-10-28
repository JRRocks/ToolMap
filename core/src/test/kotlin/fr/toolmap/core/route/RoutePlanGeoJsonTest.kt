package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
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
    fun `a stop being moved pales its legs, follows the crosshair and is joined to it by elastic lines`() {
        val to = TrackPoint(45.015, 6.02)
        val lines = RoutePlanGeoJson.lines(plan(), "calcul…", "à la main", taken = 2)
        assertEquals(2, count(lines, "\"st\":\"g\""))
        assertTrue("\"st\":\"r\"" in lines)
        assertEquals(0, count(lines, "\"label\""))
        val handles = RoutePlanGeoJson.handles(plan(), selected = 2, moved = to)
        assertTrue("\"i\":2,\"sel\":true},\"geometry\":{\"type\":\"Point\",\"coordinates\":[6.02,45.015]" in handles)
        val elastic = RoutePlanGeoJson.elastic(plan(), 2, to)
        assertTrue("[[6,45.01],[6.02,45.015],[6,45.03]]" in elastic)
        // The start has one neighbour: one stretch only.
        assertEquals(1, count(RoutePlanGeoJson.elastic(plan(), 0, to), "],["))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", RoutePlanGeoJson.elastic(plan(), 9, to))
    }

    @Test
    fun `drawing by hand shows the middle of the straight legs only`() {
        val text = RoutePlanGeoJson.handles(plan(), null, midpoints = true)
        assertEquals(1, count(text, "\"kind\":\"m\""))
        assertTrue("\"kind\":\"m\",\"leg\":1" in text)
        val m = RoutePlanGeoJson.midpoint(plan(), 1)
        assertEquals((b.lat + c.lat) / 2, m.lat, 1e-4)
    }

    @Test
    fun `zones are circles with their sign, the one being placed a ring`() {
        val zone = AvoidZone(45.0, 6.0, 250)
        assertEquals("""{"type":"FeatureCollection","features":[]}""", RoutePlanGeoJson.zones(RoutePlan()))
        val text = RoutePlanGeoJson.zones(RoutePlan(zones = listOf(zone)), placing = zone.copy(lat = 45.01))
        assertEquals(1, count(text, "\"z\":\"zone\""))
        assertEquals(1, count(text, "\"z\":\"sign\""))
        assertEquals(1, count(text, "\"z\":\"ring\""))
        assertEquals(2, count(text, "\"Polygon\""))
        val outline = RoutePlanGeoJson.circle(zone)
        assertEquals(outline.first(), outline.last())
        for (p in outline) assertEquals(250.0, Geodesy.distance(zone.lat, zone.lon, p.lat, p.lon), 1.0)
    }

    @Test
    fun `the zone under the crosshair has a solid edge, drawn at the size its handle gives`() {
        val zones = listOf(AvoidZone(45.0, 6.0, 250), AvoidZone(45.02, 6.0, 250))
        val text = RoutePlanGeoJson.zones(RoutePlan(zones = zones), hovered = 1, hoverRadius = 400)
        assertEquals(1, count(text, "\"z\":\"zone\""))
        assertEquals(1, count(text, "\"z\":\"hover\""))
        assertEquals(2, count(text, "\"z\":\"sign\""), "the zone under the crosshair keeps its sign")
        // The east point of the hovered circle is 400 m from its centre.
        val hovered = text.substringAfter("\"z\":\"hover\"").substringAfter("[[[").substringBefore("]")
        val (lon, lat) = hovered.split(',').map { it.toDouble() }
        assertEquals(400.0, Geodesy.distance(45.02, 6.0, lat, lon), 1.0)
    }

    @Test
    fun `while a zone is resized every leg is pale`() {
        val text = RoutePlanGeoJson.lines(plan(), "calcul", "à la main", pale = true)
        assertEquals(0, count(text, "\"st\":\"r\""))
        assertEquals(0, count(text, "\"st\":\"h\""))
        assertTrue(count(text, "\"st\":\"g\"") >= 2)
    }
}
