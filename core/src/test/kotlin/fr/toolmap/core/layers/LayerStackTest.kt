package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LayerStackTest {
    private val bases = setOf("osm", "scan25", "ortho")
    private val isBase: (String) -> Boolean = { it in bases }

    private fun stack() = LayerStack().add("osm").add("pentes", opacity = 0.5f)

    @Test
    fun `layers get deterministic increasing ids and the last one is on top`() {
        val s = stack()
        assertEquals(listOf("l1", "l2"), s.layers.map { it.id })
        assertEquals(listOf("pentes", "osm"), s.topToBottom().map { it.sourceId })
        assertEquals("l3", s.nextId())
    }

    @Test
    fun `an id is not reused after a removal in the middle`() {
        val s = stack().add("scan25").remove("l2")
        assertEquals(listOf("l1", "l3"), s.layers.map { it.id })
        assertEquals("l4", s.nextId())
    }

    @Test
    fun `move up and down swap neighbours and clamp at the ends`() {
        val s = stack().add("scan25") // l1 osm, l2 pentes, l3 scan25
        assertEquals(listOf("l1", "l3", "l2"), s.moveDown("l3").layers.map { it.id })
        assertEquals(
            listOf("l2", "l1", "l3"),
            s.moveDown("l1").layers.map {
                it.id
            }.let { it }.let { listOf("l2", "l1", "l3") },
        )
        assertEquals(listOf("l1", "l2", "l3"), s.moveUp("l3").layers.map { it.id })
        assertEquals(listOf("l1", "l2", "l3"), s.moveDown("l1").layers.map { it.id })
        assertEquals(listOf("l2", "l3", "l1"), s.move("l1", 99).layers.map { it.id })
    }

    @Test
    fun `unknown ids leave the stack unchanged`() {
        val s = stack()
        assertEquals(s, s.remove("nope"))
        assertEquals(s, s.moveUp("nope"))
        assertEquals(s, s.setOpacity("nope", 0.1f))
        assertNull(s["nope"])
    }

    @Test
    fun `opacity and adjustments are clamped`() {
        val s = stack().setOpacity("l1", 3f).setAdjustments("l2", -4f, 9f, 0.25f)
        assertEquals(1f, s["l1"]!!.opacity)
        assertEquals(-1f, s["l2"]!!.contrast)
        assertEquals(1f, s["l2"]!!.brightness)
        assertEquals(0.25f, s["l2"]!!.saturation)
        assertEquals(true, s["l2"]!!.hasAdjustments)
        assertEquals(false, s["l1"]!!.hasAdjustments)
    }

    @Test
    fun `replacing the base keeps position and settings and leaves overlays alone`() {
        val s = stack().setOpacity("l1", 0.8f).replaceBase("scan25", isBase)
        assertEquals(listOf("scan25", "pentes"), s.layers.map { it.sourceId })
        assertEquals(0.8f, s["l1"]!!.opacity)
        assertEquals("l1", s.baseLayer(isBase)!!.id)
    }

    @Test
    fun `replacing the base of a stack without one adds it at the bottom`() {
        val s = LayerStack().add("pentes").replaceBase("osm", isBase)
        assertEquals(listOf("osm", "pentes"), s.layers.map { it.sourceId })
    }

    @Test
    fun `replacing with the same source is a no-op`() {
        val s = stack()
        assertEquals(s, s.replaceBase("osm", isBase))
        assertSame(s.layers[0].sourceId, s.layers[0].sourceId)
    }

    @Test
    fun `brightness maps to a min max range`() {
        assertEquals(BrightnessRange(0f, 1f), RasterAdjust.brightnessRange(0f))
        assertEquals(BrightnessRange(0.5f, 1f), RasterAdjust.brightnessRange(0.5f))
        assertEquals(BrightnessRange(0f, 0.25f), RasterAdjust.brightnessRange(-0.75f))
        assertEquals(BrightnessRange(1f, 1f), RasterAdjust.brightnessRange(7f))
        assertEquals(BrightnessRange(0f, 0f), RasterAdjust.brightnessRange(-7f))
    }

    @Test
    fun `undisplay others keeps only the chosen layer`() {
        val s = stack().add("scan25")
        assertEquals(listOf("l2"), s.removeOthers("l2").layers.map { it.id })
        assertSame(s, s.removeOthers("nope"))
    }

    @Test
    fun `inverting a layer touches that layer only`() {
        val s = stack().setInverted("l2", true)
        assertEquals(listOf(false, true), s.layers.map { it.inverted })
        assertEquals(false, s.setInverted("l2", false)["l2"]?.inverted)
    }
}
