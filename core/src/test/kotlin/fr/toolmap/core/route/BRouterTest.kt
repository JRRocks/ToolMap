package fr.toolmap.core.route

import fr.toolmap.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BRouterTest {
    /** The shape of BRouter's `FormatJson` output (abridged). */
    private val sample =
        """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {
                "creator": "BRouter-1.7.8",
                "name": "brouter_hiking-mountain_0",
                "track-length": "1503",
                "filtered ascend": "212",
                "plain-ascend": "198",
                "total-time": "2710",
                "total-energy": "0",
                "cost": "4123",
                "messages": [["Longitude", "Latitude", "Elevation"], ["6800000", "45900000", "1200"]]
              },
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [6.8, 45.9, 1200.25],
                  [6.801, 45.901],
                  [6.802, 45.902, 1250.0]
                ]
              }
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `reads the way, the elevations and the time`() {
        val ok = BRouterJson.parse(sample)!!
        assertEquals(3, ok.points.size)
        assertEquals(TrackPoint(45.9, 6.8, 1200.25), ok.points[0])
        assertNull(ok.points[1].ele)
        assertEquals(2_710_000L, ok.timeMillis)
    }

    @Test
    fun `anything else is not a way`() {
        assertNull(BRouterJson.parse("datafile E5_N45.rd5 not found"))
        assertNull(BRouterJson.parse("""{"type":"FeatureCollection","features":[]}"""))
        assertNull(BRouterJson.parse("""{"features":[{"geometry":{"coordinates":[]}}]}"""))
    }

    @Test
    fun `sorts the messages of BRouter`() {
        assertEquals(RoutingError.NO_DATA, BRouterErrors.classify("datafile E5_N45.rd5 not found"))
        assertEquals(
            RoutingError.NO_DATA,
            BRouterErrors.classify("segment directory /storage/emulated/0/brouter/segments4 does not exist"),
        )
        assertEquals(RoutingError.UNKNOWN_PROFILE, BRouterErrors.classify("Profile mtb-perso does not exists"))
        assertEquals(RoutingError.NO_PATH, BRouterErrors.classify("no track found at pass=0"))
        assertEquals(RoutingError.NO_PATH, BRouterErrors.classify("start island detected for section 0"))
        assertEquals(RoutingError.NO_PATH, BRouterErrors.classify("target island detected for section 0"))
        assertEquals(RoutingError.NO_PATH, BRouterErrors.classify("a wpt in restricted area"))
        assertEquals(
            RoutingError.TIMEOUT,
            BRouterErrors.classify("operation killed by thread-priority-watchdog after 30 seconds"),
        )
        assertEquals(RoutingError.OTHER, BRouterErrors.classify("memory limit reached"))
    }

    @Test
    fun `a way that starts on a road away from the stop is joined to it`() {
        val from = RouteStop(45.9, 6.8)
        val to = RouteStop(45.91, 6.81)
        val way = LegResult.Ok(listOf(TrackPoint(45.9005, 6.8, 1000.0), TrackPoint(45.91, 6.81, 1100.0)), 1000)
        val leg = RoutePlanEditor.legOf(from, to, RoutingProfiles.HIKING, way)
        assertEquals(listOf(from.point, TrackPoint(45.9005, 6.8, 1000.0), TrackPoint(45.91, 6.81, 1100.0)), leg.points)
        assertEquals(LegState.OK, leg.state)
        assertEquals(1000L, leg.timeMillis)
    }
}
