// Port of core/src/test/kotlin/fr/toolmap/core/layers/ZoomBarTest.kt.

import Testing
@testable import ToolMapCore

struct ZoomBarTests {
    private let width: Float = 200 // 10 px per zoom level

    @Test("a touch position maps to the nearest integer zoom")
    func touch() {
        #expect(ZoomBar.zoomAt(-30, width) == 0)
        #expect(ZoomBar.zoomAt(4, width) == 0)
        #expect(ZoomBar.zoomAt(6, width) == 1)
        #expect(ZoomBar.zoomAt(100, width) == 10)
        #expect(ZoomBar.zoomAt(500, width) == 20)
        #expect(ZoomBar.zoomAt(50, 0) == 0)
    }

    @Test("the closest handle is grabbed and a shared position gives the outer side")
    func closestHandle() {
        let range = ZoomBar.Range(5, 15)
        #expect(ZoomBar.nearestHandle(40, width, range) == .min)
        #expect(ZoomBar.nearestHandle(170, width, range) == .max)
        #expect(ZoomBar.nearestHandle(90, width, range) == .min)
        let same = ZoomBar.Range(8, 8)
        #expect(ZoomBar.nearestHandle(60, width, same) == .min)
        #expect(ZoomBar.nearestHandle(100, width, same) == .max)
    }

    @Test("handles never cross")
    func neverCross() {
        let range = ZoomBar.Range(5, 15)
        #expect(ZoomBar.moved(range, .min, 19) == ZoomBar.Range(15, 15))
        #expect(ZoomBar.moved(range, .max, 2) == ZoomBar.Range(5, 5))
        #expect(ZoomBar.moved(range, .min, -3) == ZoomBar.Range(0, 15))
        #expect(ZoomBar.moved(range, .max, 40) == ZoomBar.Range(5, ZoomBar.max))
    }

    @Test("a handle at the end of the bar means no limit")
    func endMeansNoLimit() {
        let all = ZoomBar.toBounds(ZoomBar.Range(0, ZoomBar.max))
        #expect(all.0 == nil && all.1 == nil)
        let both = ZoomBar.toBounds(ZoomBar.Range(8, 16))
        #expect(both.0 == 8 && both.1 == 16)
        let upTo7 = ZoomBar.toBounds(ZoomBar.Range(0, 7))
        #expect(upTo7.0 == nil && upTo7.1 == 7)
        #expect(ZoomBar.rangeOf(nil, nil) == ZoomBar.Range(0, ZoomBar.max))
        #expect(ZoomBar.rangeOf(8, 16) == ZoomBar.Range(8, 16))
    }

    @Test("the zoom cursor position is a clamped fraction")
    func fraction() {
        #expect(ZoomBar.fractionOf(10.0) == 0.5)
        #expect(ZoomBar.fractionOf(-2.0) == 0)
        #expect(ZoomBar.fractionOf(27.0) == 1)
    }

    @Test("the same geometry serves a longer bar through its maximum")
    func longerBar() {
        // The elevation bar: 96 steps of 50 m on the same 200 px.
        let max = ElevationBar.steps
        #expect(ZoomBar.zoomAt(100, width, max) == 48)
        #expect(ZoomBar.zoomAt(500, width, max) == max)
        #expect(ZoomBar.fractionOf(48.0, max) == 0.5)
        #expect(ZoomBar.moved(ZoomBar.Range(30, 60), .max, 200, max) == ZoomBar.Range(30, max))
        #expect(ZoomBar.nearestHandle(150, width, ZoomBar.Range(30, 60), max) == .max)
    }
}
