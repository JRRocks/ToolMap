package fr.toolmap.core.track

import fr.toolmap.core.track.EditorGeoJson.TapAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorGeoJsonTest {
    private fun line(count: Int) = List(count) { TrackPoint(45.0 + it * 0.001, 6.0) }

    private fun kinds(text: String): List<Pair<String, Int>> =
        Json
            .parseToJsonElement(text)
            .jsonObject["features"]!!
            .jsonArray
            .map {
                val p = it.jsonObject["properties"]!!.jsonObject
                p["kind"]!!.jsonPrimitive.content to p["i"]!!.jsonPrimitive.int
            }

    @Test
    fun `a line needs two points and is a valid linestring`() {
        assertEquals(0, Json.parseToJsonElement(EditorGeoJson.line(line(1))).jsonObject["features"]!!.jsonArray.size)
        val f = Json.parseToJsonElement(EditorGeoJson.line(line(3))).jsonObject["features"]!!.jsonArray.single()
        val g = f.jsonObject["geometry"]!!.jsonObject
        assertEquals("LineString", g["type"]!!.jsonPrimitive.content)
        assertEquals(3, g["coordinates"]!!.jsonArray.size)
    }

    @Test
    fun `handles tell the ends, the selection and the midpoints apart`() {
        val got = kinds(EditorGeoJson.handles(line(4), selected = 2))
        assertEquals(listOf("m" to 0, "m" to 1, "m" to 2, "a" to 0, "v" to 1, "s" to 2, "z" to 3), got)
        assertEquals(
            listOf("a" to 0, "z" to 1),
            kinds(EditorGeoJson.handles(line(2), selected = null, midpoints = false)),
        )
        assertEquals(0, kinds(EditorGeoJson.handles(emptyList(), null)).size)
        // A single point is both ends: it shows as the start.
        assertEquals(listOf("a" to 0), kinds(EditorGeoJson.handles(line(1), null)))
    }

    @Test
    fun `a very long line only shows its ends and the selected vertex`() {
        val long = line(EditorGeoJson.MAX_HANDLES + 1)
        val got = kinds(EditorGeoJson.handles(long, selected = 700))
        assertEquals(listOf("a" to 0, "s" to 700, "z" to EditorGeoJson.MAX_HANDLES), got.sortedBy { it.second })
    }

    @Test
    fun `a tap selects a vertex, inserts at a midpoint or adds`() {
        val pts = line(3) // vertices 111 m apart
        assertEquals(TapAction.Select(1), EditorGeoJson.interpret(pts, 45.001, 6.0002, reachMeters = 30.0))
        // 55 m from both neighbours: the midpoint handle.
        assertEquals(TapAction.InsertMidpoint(0), EditorGeoJson.interpret(pts, 45.0005, 6.0, reachMeters = 30.0))
        assertEquals(TapAction.Add, EditorGeoJson.interpret(pts, 45.0005, 6.01, reachMeters = 30.0))
        assertEquals(TapAction.Add, EditorGeoJson.interpret(emptyList(), 1.0, 1.0, 30.0))
        // Two handles within reach: the vertex wins over the midpoint.
        assertEquals(TapAction.Select(0), EditorGeoJson.interpret(pts, 45.00001, 6.0, reachMeters = 100.0))
    }

    @Test
    fun `point features carry the symbol colour and the icon`() {
        val text =
            TrackGeoJson.points(
                listOf(PointFeature(1, "Sommet", 45.0, 6.0, "Summit"), PointFeature(2, null, 0.0, 0.0)),
            )
        val f = Json.parseToJsonElement(text).jsonObject["features"]!!.jsonArray
        val summit = f[0].jsonObject["properties"]!!.jsonObject
        assertEquals("#5a7a00", summit["color"]!!.jsonPrimitive.content)
        assertEquals("peak", summit["icon"]!!.jsonPrimitive.content)
        val plain = f[1].jsonObject["properties"]!!.jsonObject
        assertEquals("#e53935", plain["color"]!!.jsonPrimitive.content)
        assertEquals("waypoint", plain["icon"]!!.jsonPrimitive.content)
    }
}
