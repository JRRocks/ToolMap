package fr.toolmap.core.track

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackGeoJsonTest {
    private fun features(text: String): JsonArray = Json.parseToJsonElement(text).jsonObject["features"]!!.jsonArray

    @Test
    fun `simplification keeps the ends and the corners and drops collinear points`() {
        val straight = List(1000) { TrackPoint(45.0, 6.0 + it * 1e-4) }
        assertEquals(listOf(straight.first(), straight.last()), PathSimplifier.simplify(straight, 1.0))

        val corner =
            List(50) { TrackPoint(45.0, 6.0 + it * 1e-4) } + List(50) { TrackPoint(45.0 + (it + 1) * 1e-4, 6.0049) }
        val simplified = PathSimplifier.simplify(corner, 1.0)
        assertEquals(3, simplified.size)
        assertEquals(corner[49], simplified[1])

        assertEquals(corner, PathSimplifier.simplify(corner, 0.0))
        assertEquals(2, PathSimplifier.simplify(corner.take(2), 5.0).size)
    }

    @Test
    fun `a wiggle larger than the tolerance is kept, a smaller one is not`() {
        val wiggle = listOf(TrackPoint(45.0, 6.0), TrackPoint(45.00001, 6.0005), TrackPoint(45.0, 6.001))
        assertEquals(3, PathSimplifier.simplify(wiggle, 0.5).size)
        assertEquals(2, PathSimplifier.simplify(wiggle, 5.0).size)
    }

    @Test
    fun `lines become features with their style`() {
        val line =
            LineFeature(
                id = 42,
                colorArgb = 0x80FF8800.toInt(),
                widthDp = 4f,
                dashed = true,
                segments =
                    listOf(
                        listOf(TrackPoint(45.5, 6.25), TrackPoint(45.75, 6.5)),
                        listOf(TrackPoint(1.0, 2.0), TrackPoint(3.0, 4.0)),
                    ),
                arrows = true,
            )
        val f = features(TrackGeoJson.lines(listOf(line))).single().jsonObject
        val props = f["properties"]!!.jsonObject
        assertEquals(42, props["id"]!!.jsonPrimitive.int)
        assertEquals("#ff8800", props["color"]!!.jsonPrimitive.content)
        assertEquals(0.5, props["opacity"]!!.jsonPrimitive.double, 0.01)
        assertEquals(4.0, props["width"]!!.jsonPrimitive.double)
        assertEquals("true", props["dashed"]!!.jsonPrimitive.content)
        assertEquals("true", props["arrows"]!!.jsonPrimitive.content)
        val geometry = f["geometry"]!!.jsonObject
        assertEquals("MultiLineString", geometry["type"]!!.jsonPrimitive.content)
        val first = geometry["coordinates"]!!.jsonArray[0].jsonArray[0].jsonArray
        assertEquals(6.25, first[0].jsonPrimitive.double)
        assertEquals(45.5, first[1].jsonPrimitive.double)
    }

    @Test
    fun `single segment is a LineString, empty or one-point lines are skipped`() {
        val items =
            listOf(
                LineFeature(
                    1,
                    0xFFFF0000.toInt(),
                    3f,
                    false,
                    listOf(listOf(TrackPoint(0.0, 0.0), TrackPoint(0.001, 0.001))),
                ),
                LineFeature(2, 0xFFFF0000.toInt(), 3f, false, listOf(listOf(TrackPoint(0.0, 0.0)))),
                LineFeature(3, 0xFFFF0000.toInt(), 3f, false, emptyList()),
            )
        val result = features(TrackGeoJson.lines(items))
        assertEquals(1, result.size)
        assertEquals("LineString", result[0].jsonObject["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1", result[0].jsonObject["properties"]!!.jsonObject["opacity"]!!.jsonPrimitive.content)
        assertEquals(0, features(TrackGeoJson.lines(emptyList())).size)
    }

    @Test
    fun `waypoints keep awkward names and coordinates have at most six decimals`() {
        val text =
            TrackGeoJson.points(
                listOf(
                    PointFeature(7, "Col \"du\" Géant\n1", -33.1234567891, -70.5),
                    PointFeature(8, null, 0.0000004, 179.9999996),
                ),
            )
        val result = features(text)
        assertEquals(
            "Col \"du\" Géant\n1",
            result[0].jsonObject["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        val coords = result[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(-70.5, coords[0].jsonPrimitive.double)
        assertEquals(-33.123457, coords[1].jsonPrimitive.double)
        val second = result[1].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(180.0, second[0].jsonPrimitive.double)
        assertEquals(0.0, second[1].jsonPrimitive.double)
        assertTrue(!text.contains("E-"), "no scientific notation")
    }

    @Test
    fun `a 50000-point track is simplified and written quickly`() {
        val points =
            List(50_000) {
                TrackPoint(45.0 + Math.sin(it / 300.0) * 0.01, 6.0 + it * 2e-6 + Math.cos(it / 40.0) * 1e-5)
            }
        val started = System.nanoTime()
        val text = TrackGeoJson.lines(listOf(LineFeature(1, 0xFFFF0000.toInt(), 4f, false, listOf(points))))
        val millis = (System.nanoTime() - started) / 1_000_000
        val count = features(text)[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.size
        assertTrue(count in 2 until points.size, "$count points kept")
        assertTrue(millis < 2_000, "took $millis ms")
        println("50000 points -> $count kept, ${text.length} chars, $millis ms")
    }

    @Test
    fun `JsonObject type is what the parser returns`() {
        assertTrue(Json.parseToJsonElement(TrackGeoJson.point(1.0, 2.0)) is JsonObject)
    }
}
