// Port of core/src/test/kotlin/fr/toolmap/core/layers/LayerElevationTest.kt.

import Testing
@testable import ToolMapCore

struct LayerElevationTests {
    private func layer(
        zooms: (Int?, Int?) = (nil, nil),
        band: (Int?, Int?) = (nil, nil),
        logic: RangeLogic = .and
    ) -> LayerConfig {
        LayerConfig(
            id: "l1",
            sourceId: "opensnowmap",
            displayMinZoom: zooms.0,
            displayMaxZoom: zooms.1,
            displayMinElevation: band.0,
            displayMaxElevation: band.1,
            rangeLogic: logic
        )
    }

    @Test("a layer without a band is drawn whole")
    func noBand() {
        #expect(LayerElevation.maskOf(layer()) == nil)
        #expect(LayerElevation.maskOf(layer(zooms: (8, 16), logic: .or)) == nil)
    }

    @Test("with ET the band holds at every zoom of the range")
    func andLogic() {
        let mask = LayerElevation.maskOf(layer(zooms: (8, 16), band: (1500, nil)))
        #expect(mask == ElevationMask(1500, nil))
        #expect(LayerElevation.limitsZoom(layer(zooms: (8, 16), band: (1500, nil))))
    }

    @Test("with OU the zoom range is drawn whole and the band elsewhere")
    func orLogic() {
        let or = layer(zooms: (8, 16), band: (1500, nil), logic: .or)
        #expect(LayerElevation.maskOf(or) == ElevationMask(1500, nil, IntRange(8, 16)))
        #expect(!LayerElevation.limitsZoom(or))
        // An open end of the range stands for every deeper zoom.
        let from14 = layer(zooms: (14, nil), band: (nil, 1200), logic: .or)
        #expect(LayerElevation.maskOf(from14)?.whole == IntRange(14, 30))
        // Without a zoom range, OU is the band alone.
        #expect(LayerElevation.maskOf(layer(band: (1500, nil), logic: .or)) == ElevationMask(1500, nil))
    }

    @Test("a layer is drawn outside its zoom range only with OU and a band")
    func drawnOutside() {
        let and = layer(zooms: (8, 16), band: (1500, nil))
        #expect(!LayerElevation.isDrawnAt(and, 5.0))
        #expect(LayerElevation.isDrawnAt(and, 10.5))
        var or = and
        or.rangeLogic = .or
        #expect(LayerElevation.isDrawnAt(or, 5.0))
        let zoomOnly = layer(zooms: (8, 16), logic: .or)
        #expect(LayerElevation.limitsZoom(zoomOnly))
        #expect(!LayerElevation.isDrawnAt(zoomOnly, 17.0))
    }

    @Test("the elevation bar works in 50 m steps and its ends mean no limit")
    func elevationBar() {
        let all = ZoomBar.Range(0, ElevationBar.steps)
        #expect(ElevationBar.steps == 96)
        #expect(ElevationBar.rangeOf(nil, nil) == all)
        let none = ElevationBar.toBounds(all)
        #expect(none.0 == nil && none.1 == nil)
        #expect(ElevationBar.rangeOf(1500, nil) == ZoomBar.Range(30, ElevationBar.steps))
        let from1500 = ElevationBar.toBounds(ZoomBar.Range(30, ElevationBar.steps))
        #expect(from1500.0 == 1500 && from1500.1 == nil)
        let below2000 = ElevationBar.toBounds(ZoomBar.Range(0, 40))
        #expect(below2000.0 == nil && below2000.1 == 2000)
        // A bound set elsewhere snaps to the nearest step; beyond the bar it stays on the bar.
        #expect(ElevationBar.rangeOf(1520, 2480) == ZoomBar.Range(30, 50))
        #expect(ElevationBar.rangeOf(-200, 9000) == ZoomBar.Range(0, ElevationBar.steps))
    }

    @Test("the centre mark sits on the bar")
    func centreMark() {
        #expect(abs(ElevationBar.positionOf(1234.0) - 24.68) <= 1e-9)
        #expect(ElevationBar.positionOf(-20.0) == 0.0)
        #expect(ElevationBar.positionOf(8848.0) == 96.0)
    }

    @Test("a banded layer has its own style source named after the band")
    func styleSource() {
        let mask = ElevationMask(1500, nil)
        #expect(StyleSources.id("osm", nil) == "osm")
        #expect(StyleSources.id("osm", mask) == "osm?alt=1500,")
        #expect(StyleSources.sourceOf("osm?alt=1500,") == "osm")
        #expect(StyleSources.sourceOf("osm") == "osm")
        #expect(StyleSources.queryOf("osm?alt=1500,") == "alt=1500,")
        #expect(StyleSources.queryOf("osm") == nil)
        #expect(ElevationMask.parse(StyleSources.queryOf(StyleSources.id("osm", mask))) == mask)
    }

    @Test("setting a band keeps its bounds in order and resets with two nulls")
    func settingBand() throws {
        let stack = LayerStack().add("osm")
        let raised = try #require(stack.setElevationRange("l1", 2000, 1500, .or).layers.first)
        #expect(raised.displayMinElevation == 2000)
        #expect(raised.displayMaxElevation == 2000)
        #expect(raised.rangeLogic == .or)
        let reset = stack.setElevationRange("l1", 1500, nil, .and).setElevationRange("l1", nil, nil, .and)
        #expect(reset.layers.count == 1)
        #expect(reset.layers.first?.hasElevationRange == false)
        #expect(stack.setElevationRange("l9", 1500, nil, .and) == stack)
    }

    @Test("the style of a banded layer uses its band source and drops the camera range with OU")
    func bandedStyle() throws {
        let stack = LayerStack()
            .add("opentopomap")
            .add("opensnowmap")
            .setZoomRange("l2", 8, 16)
            .setElevationRange("l2", 1500, nil, .and)
        let and = StackToStyle.desired(stack) { _ in true }
        #expect(and.map(\.sourceId) == ["opentopomap", "opensnowmap?alt=1500,"])
        #expect(and.last?.props.minZoom == LayerZoom.cameraRange(8, 16).minZoom)
        let orStack = stack.setElevationRange("l2", 1500, nil, .or)
        let orLayers = StackToStyle.desired(orStack) { _ in true }
        let or = try #require(orLayers.last)
        #expect(or.sourceId == "opensnowmap?alt=1500,&whole=8,16")
        #expect(or.props.minZoom == nil)
        #expect(or.props.maxZoom == nil)
        // Unknown maps are left out by their own id, not by the band's.
        let known = StackToStyle.desired(stack) { $0 == "opentopomap" }
        #expect(known.map(\.id) == ["l1"])
    }
}
