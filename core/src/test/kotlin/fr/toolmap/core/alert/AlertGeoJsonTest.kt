package fr.toolmap.core.alert

import fr.toolmap.core.track.Geodesy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AlertGeoJsonTest {
    private val place = WatchedPlace(7L, "Refuge", 45.0, 6.0, 250.0)

    private fun rings(text: String): List<List<Pair<Double, Double>>> =
        Json
            .parseToJsonElement(text)
            .jsonObject["features"]!!
            .jsonArray
            .map { feature ->
                val ring =
                    feature.jsonObject["geometry"]!!
                        .jsonObject["coordinates"]!!
                        .jsonArray[0]
                        .jsonArray
                ring.map {
                    it.jsonArray[0].jsonPrimitive.content.toDouble() to
                        it.jsonArray[1].jsonPrimitive.content.toDouble()
                }
            }

    @Test
    fun `no place gives an empty collection`() {
        val parsed = Json.parseToJsonElement(AlertGeoJson.circles(emptyList())).jsonObject
        assertEquals("FeatureCollection", parsed["type"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), parsed["features"])
    }

    @Test
    fun `a circle is a closed ring of points at the radius from the centre`() {
        val ring = rings(AlertGeoJson.circles(listOf(place))).single()
        assertEquals(ring.first(), ring.last(), "ring closed")
        assertEquals(49, ring.size)
        for ((lon, lat) in ring) {
            assertEquals(250.0, Geodesy.distance(place.lat, place.lon, lat, lon), 0.5)
        }
    }

    @Test
    fun `each place has its own ring with its own radius and id`() {
        val big = WatchedPlace(8L, null, 46.0, 7.0, 1000.0)
        val text = AlertGeoJson.circles(listOf(place, big))
        val found = rings(text)
        assertEquals(2, found.size)
        assertEquals(1000.0, Geodesy.distance(46.0, 7.0, found[1][5].second, found[1][5].first), 1.0)
        val ids =
            Json
                .parseToJsonElement(text)
                .jsonObject["features"]!!
                .jsonArray
                .map { (it as JsonObject)["properties"]!!.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("7", "8"), ids)
    }
}
