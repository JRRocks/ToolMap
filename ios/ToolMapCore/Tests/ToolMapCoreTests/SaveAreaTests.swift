// Port of core/src/test/kotlin/fr/toolmap/core/maps/SaveAreaTest.kt.

import Testing
@testable import ToolMapCore

struct SaveAreaTests {
    private let box: AreaSelection

    init() throws {
        box = try AreaSelection(west: 5.0, south: 44.0, east: 6.0, north: 45.0)
    }

    private func near(_ expected: Double, _ actual: Double, _ tolerance: Double) -> Bool {
        abs(expected - actual) <= tolerance
    }

    @Test("dragging a corner moves it and keeps the opposite one")
    func dragCorner() throws {
        let moved = try box.drag(.northEast, lat: 45.5, lon: 6.5)
        let wanted = try AreaSelection(west: 5.0, south: 44.0, east: 6.5, north: 45.5)
        #expect(moved == wanted)
        let other = try box.drag(.southWest, lat: 43.5, lon: 4.5)
        let expected = try AreaSelection(west: 4.5, south: 43.5, east: 6.0, north: 45.0)
        #expect(other == expected)
    }

    @Test("a corner cannot cross the opposite one")
    func noCrossing() throws {
        let squeezed = try box.drag(.northEast, lat: 40.0, lon: 1.0)
        #expect(squeezed.west == 5.0)
        #expect(squeezed.south == 44.0)
        #expect(near(5.0 + AreaSelection.minSpan, squeezed.east, 1e-12))
        #expect(near(44.0 + AreaSelection.minSpan, squeezed.north, 1e-12))
        let west = try box.drag(.northWest, lat: 46.0, lon: 9.0)
        #expect(near(6.0 - AreaSelection.minSpan, west.west, 1e-12))
        #expect(west.north == 46.0)
    }

    @Test("nothing leaves the Web Mercator world")
    func staysInWorld() throws {
        let far = try box.drag(.northEast, lat: 89.0, lon: 400.0)
        #expect(far.north == AreaSelection.maxLatitude)
        #expect(far.east == 180.0)
        let low = try box.drag(.southWest, lat: -90.0, lon: -200.0)
        #expect(low.south == -AreaSelection.maxLatitude)
        #expect(low.west == -180.0)
    }

    @Test("the corner accessors agree with the bounds")
    func cornerAccessors() {
        #expect(box.latOf(.northWest) == 45.0)
        #expect(box.latOf(.southEast) == 44.0)
        #expect(box.lonOf(.southWest) == 5.0)
        #expect(box.lonOf(.northEast) == 6.0)
        #expect(box.toShape() == ZoneShape.Rectangle(west: 5.0, south: 44.0, east: 6.0, north: 45.0))
    }

    @Test("an empty rectangle is refused")
    func emptyRefused() {
        #expect(throws: ToolMapError.self) {
            _ = try AreaSelection(west: 5.0, south: 44.0, east: 5.0, north: 45.0)
        }
    }

    @Test("the first rectangle fills 60 percent of what is visible, around its centre")
    func centered() throws {
        let visible = ZoneShape.Rectangle(west: 0.0, south: 0.0, east: 10.0, north: 20.0)
        let area = try AreaSelection.centered(visible)
        #expect(near(2.0, area.west, 1e-9))
        #expect(near(8.0, area.east, 1e-9))
        #expect(near(4.0, area.south, 1e-9))
        #expect(near(16.0, area.north, 1e-9))
    }

    @Test("a map saves the zooms it has up to the chosen maximum")
    func spans() {
        #expect(ZoneStrips.span(sourceMin: 1, sourceMax: 17, chosenMax: 14) == ZoomSpan(1, 14, 17))
        #expect(ZoneStrips.span(sourceMin: 1, sourceMax: 17, chosenMax: 20) == ZoomSpan(1, 17, 17))
        #expect(ZoneStrips.span(sourceMin: 6, sourceMax: 14, chosenMax: 6) == ZoomSpan(6, 6, 14))
        #expect(ZoneStrips.span(sourceMin: 6, sourceMax: 14, chosenMax: 5) == nil)
    }

    @Test("the bar has one cell per zoom and tells saved, skipped and absent apart")
    func cells() {
        let cells = ZoneStrips.cells(sourceMin: 2, sourceMax: 6, chosenMax: 4, scaleMax: 8)
        #expect(cells.count == 9)
        let expected: [ZoomCell] = [.absent, .absent, .saved, .saved, .saved, .skipped, .skipped, .absent, .absent]
        #expect(cells == expected)
    }

    @Test("the red marker sits at the current zoom along the bar")
    func marker() throws {
        // 19 cells (zoom 0..18): zoom 9.5 is exactly half way.
        let half = try #require(ZoneStrips.marker(9.5, scaleMax: 18))
        #expect(abs(half - 0.5) <= 1e-6)
        let start = try #require(ZoneStrips.marker(0.0, scaleMax: 18))
        #expect(start == 0)
        #expect(ZoneStrips.marker(25.0, scaleMax: 18) == nil)
        #expect(ZoneStrips.marker(-1.0, scaleMax: 18) == nil)
    }

    @Test("slider bounds")
    func sliderBounds() {
        #expect(ZoneStrips.sliderMax([14, 18, 17]) == 18)
        #expect(ZoneStrips.sliderMax([]) == 1)
        #expect(ZoneStrips.defaultMax(currentTileZoom: 13, extra: 2, sliderMax: 18) == 15)
        #expect(ZoneStrips.defaultMax(currentTileZoom: 17, extra: 2, sliderMax: 18) == 18)
        #expect(ZoneStrips.defaultMax(currentTileZoom: -3, extra: 0, sliderMax: 18) >= 0)
    }
}
