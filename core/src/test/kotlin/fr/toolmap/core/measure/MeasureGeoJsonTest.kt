package fr.toolmap.core.measure

import fr.toolmap.core.track.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeasureGeoJsonTest {
    private val square =
        listOf(TrackPoint(45.0, 5.0), TrackPoint(45.0, 5.01), TrackPoint(45.01, 5.01), TrackPoint(45.01, 5.0))

    private fun coordinates(
        geoJson: String,
        geometry: String,
    ): List<List<Double>> {
        val f = Json.parseToJsonElement(geoJson).jsonObject["features"]!!.jsonArray.single().jsonObject
        val g = f["geometry"]!!.jsonObject
        assertEquals(geometry, g["type"]!!.jsonPrimitive.content)
        val c = g["coordinates"]!!.jsonArray
        val line = if (geometry == "Polygon") c[0].jsonArray else c
        return line.map { p -> p.jsonArray.map { it.jsonPrimitive.content.toDouble() } }
    }

    @Test
    fun `a path is an open line and has no fill`() {
        assertEquals(4, coordinates(MeasureGeoJson.line(square, MeasureMode.PATH), "LineString").size)
        assertTrue(MeasureGeoJson.fill(square, MeasureMode.PATH).contains("\"features\":[]"))
    }

    @Test
    fun `an area is a closed line and a filled ring`() {
        val line = coordinates(MeasureGeoJson.line(square, MeasureMode.AREA), "LineString")
        assertEquals(5, line.size)
        assertEquals(line.first(), line.last())
        val ring = coordinates(MeasureGeoJson.fill(square, MeasureMode.AREA), "Polygon")
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        // [lon, lat] order.
        assertEquals(5.0, ring[0][0], 1e-9)
        assertEquals(45.0, ring[0][1], 1e-9)
    }

    @Test
    fun `two points are never closed or filled, even in area mode`() {
        val two = square.take(2)
        assertEquals(2, coordinates(MeasureGeoJson.line(two, MeasureMode.AREA), "LineString").size)
        assertTrue(MeasureGeoJson.fill(two, MeasureMode.AREA).contains("\"features\":[]"))
    }

    @Test
    fun `vertices have no midpoint handles`() {
        val v = Json.parseToJsonElement(MeasureGeoJson.vertices(square)).jsonObject["features"]!!.jsonArray
        assertEquals(4, v.size)
        val kinds = v.map { it.jsonObject["properties"]!!.jsonObject["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("a", "v", "v", "z"), kinds)
        assertTrue(MeasureGeoJson.vertices(emptyList()).contains("\"features\":[]"))
    }
}
