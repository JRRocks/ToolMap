package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomBarTest {
    private val width = 200f // 10 px per zoom level

    @Test
    fun `a touch position maps to the nearest integer zoom`() {
        assertEquals(0, ZoomBar.zoomAt(-30f, width))
        assertEquals(0, ZoomBar.zoomAt(4f, width))
        assertEquals(1, ZoomBar.zoomAt(6f, width))
        assertEquals(10, ZoomBar.zoomAt(100f, width))
        assertEquals(20, ZoomBar.zoomAt(500f, width))
        assertEquals(0, ZoomBar.zoomAt(50f, 0f))
    }

    @Test
    fun `the closest handle is grabbed and a shared position gives the outer side`() {
        val range = ZoomBar.Range(5, 15)
        assertEquals(ZoomBar.Handle.MIN, ZoomBar.nearestHandle(40f, width, range))
        assertEquals(ZoomBar.Handle.MAX, ZoomBar.nearestHandle(170f, width, range))
        assertEquals(ZoomBar.Handle.MIN, ZoomBar.nearestHandle(90f, width, range))
        val same = ZoomBar.Range(8, 8)
        assertEquals(ZoomBar.Handle.MIN, ZoomBar.nearestHandle(60f, width, same))
        assertEquals(ZoomBar.Handle.MAX, ZoomBar.nearestHandle(100f, width, same))
    }

    @Test
    fun `handles never cross`() {
        val range = ZoomBar.Range(5, 15)
        assertEquals(ZoomBar.Range(15, 15), ZoomBar.moved(range, ZoomBar.Handle.MIN, 19))
        assertEquals(ZoomBar.Range(5, 5), ZoomBar.moved(range, ZoomBar.Handle.MAX, 2))
        assertEquals(ZoomBar.Range(0, 15), ZoomBar.moved(range, ZoomBar.Handle.MIN, -3))
        assertEquals(ZoomBar.Range(5, ZoomBar.MAX), ZoomBar.moved(range, ZoomBar.Handle.MAX, 40))
    }

    @Test
    fun `a handle at the end of the bar means no limit`() {
        assertEquals(null to null, ZoomBar.toBounds(ZoomBar.Range(0, ZoomBar.MAX)))
        assertEquals(8 to 16, ZoomBar.toBounds(ZoomBar.Range(8, 16)))
        assertEquals(null to 7, ZoomBar.toBounds(ZoomBar.Range(0, 7)))
        assertEquals(ZoomBar.Range(0, ZoomBar.MAX), ZoomBar.rangeOf(null, null))
        assertEquals(ZoomBar.Range(8, 16), ZoomBar.rangeOf(8, 16))
    }

    @Test
    fun `the zoom cursor position is a clamped fraction`() {
        assertEquals(0.5f, ZoomBar.fractionOf(10.0))
        assertEquals(0f, ZoomBar.fractionOf(-2.0))
        assertEquals(1f, ZoomBar.fractionOf(27.0))
    }

    @Test
    fun `the same geometry serves a longer bar through its maximum`() {
        // The elevation bar: 96 steps of 50 m on the same 200 px.
        val max = ElevationBar.STEPS
        assertEquals(48, ZoomBar.zoomAt(100f, width, max))
        assertEquals(max, ZoomBar.zoomAt(500f, width, max))
        assertEquals(0.5f, ZoomBar.fractionOf(48.0, max))
        assertEquals(ZoomBar.Range(30, max), ZoomBar.moved(ZoomBar.Range(30, 60), ZoomBar.Handle.MAX, 200, max))
        assertEquals(ZoomBar.Handle.MAX, ZoomBar.nearestHandle(150f, width, ZoomBar.Range(30, 60), max))
    }
}
