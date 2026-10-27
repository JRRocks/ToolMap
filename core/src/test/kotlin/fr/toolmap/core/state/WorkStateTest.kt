package fr.toolmap.core.state

import fr.toolmap.core.layers.LayerStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkStateTest {
    @Test
    fun `the default state shows OpenStreetMap over France`() {
        val state = WorkState()
        assertEquals(listOf("osm"), state.stack.layers.map { it.sourceId })
        assertEquals(5.0, state.camera.zoom)
    }

    @Test
    fun `a state survives a JSON round trip`() {
        val state =
            WorkState(
                stack =
                    LayerStack().add(
                        "ign-scan25",
                    ).add("ign-pentes", opacity = 0.5f).setAdjustments("l2", 0.1f, 0.2f, 0.3f),
                camera = CameraState(lat = 45.19, lon = 5.72, zoom = 11.5, bearing = 30.0),
                recentBases = listOf("ign-scan25", "osm"),
                offline = true,
            )
        assertEquals(state, WorkStateCodec.decodeOrDefault(WorkStateCodec.encode(state)))
    }

    @Test
    fun `damaged or missing content gives the default state`() {
        assertEquals(WorkState(), WorkStateCodec.decodeOrDefault(null))
        assertEquals(WorkState(), WorkStateCodec.decodeOrDefault(""))
        assertEquals(WorkState(), WorkStateCodec.decodeOrDefault("{ not json"))
        assertEquals(WorkState(), WorkStateCodec.decodeOrDefault("""{"camera": "oops"}"""))
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val text = """{"camera":{"lat":1.0,"lon":2.0,"zoom":3.0,"bearing":0.0,"pitch":45.0},"future":true}"""
        assertEquals(1.0, WorkStateCodec.decodeOrDefault(text).camera.lat)
    }

    @Test
    fun `recent bases keep the two latest distinct maps`() {
        var state = WorkState(recentBases = listOf("osm"))
        assertNull(state.swapTarget())
        state = state.withBaseUsed("scan25")
        assertEquals(listOf("scan25", "osm"), state.recentBases)
        assertEquals("osm", state.swapTarget())
        state = state.withBaseUsed("scan25")
        assertEquals(listOf("scan25", "osm"), state.recentBases)
        state = state.withBaseUsed("ortho")
        assertEquals(listOf("ortho", "scan25"), state.recentBases)
        state = state.withBaseUsed("scan25")
        assertEquals(listOf("scan25", "ortho"), state.recentBases)
    }
}
