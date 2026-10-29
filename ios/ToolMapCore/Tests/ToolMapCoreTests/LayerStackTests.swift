// Port of core/src/test/kotlin/fr/toolmap/core/layers/LayerStackTest.kt.

import Testing
@testable import ToolMapCore

struct LayerStackTests {
    private static let bases: Set<String> = ["osm", "scan25", "ortho"]

    private func isBase(_ id: String) -> Bool {
        LayerStackTests.bases.contains(id)
    }

    private func stack() -> LayerStack {
        LayerStack().add("osm").add("pentes", opacity: 0.5)
    }

    @Test("layers get deterministic increasing ids and the last one is on top")
    func ids() {
        let s = stack()
        #expect(s.layers.map(\.id) == ["l1", "l2"])
        #expect(s.topToBottom().map(\.sourceId) == ["pentes", "osm"])
        #expect(s.nextId() == "l3")
    }

    @Test("an id is not reused after a removal in the middle")
    func idNotReused() {
        let s = stack().add("scan25").remove("l2")
        #expect(s.layers.map(\.id) == ["l1", "l3"])
        #expect(s.nextId() == "l4")
    }

    @Test("move up and down swap neighbours and clamp at the ends")
    func moves() {
        let s = stack().add("scan25") // l1 osm, l2 pentes, l3 scan25
        #expect(s.moveDown("l3").layers.map(\.id) == ["l1", "l3", "l2"])
        #expect(s.moveUp("l3").layers.map(\.id) == ["l1", "l2", "l3"])
        #expect(s.moveDown("l1").layers.map(\.id) == ["l1", "l2", "l3"])
        #expect(s.moveUp("l1").layers.map(\.id) == ["l2", "l1", "l3"])
        #expect(s.move("l1", 99).layers.map(\.id) == ["l2", "l3", "l1"])
    }

    @Test("unknown ids leave the stack unchanged")
    func unknownIds() {
        let s = stack()
        #expect(s.remove("nope") == s)
        #expect(s.moveUp("nope") == s)
        #expect(s.setOpacity("nope", 0.1) == s)
        #expect(s["nope"] == nil)
    }

    @Test("opacity and adjustments are clamped")
    func clamped() throws {
        let s = stack().setOpacity("l1", 3).setAdjustments("l2", -4, 9, 0.25)
        let l1 = try #require(s["l1"])
        let l2 = try #require(s["l2"])
        #expect(l1.opacity == 1)
        #expect(l2.contrast == -1)
        #expect(l2.brightness == 1)
        #expect(l2.saturation == 0.25)
        #expect(l2.hasAdjustments == true)
        #expect(l1.hasAdjustments == false)
    }

    @Test("the hue is clamped to half a turn and the colours reset all together")
    func hueAndReset() throws {
        let s = stack()
            .setHue("l1", -270)
            .setAdjustments("l1", 0.2, -0.3, 0.4)
            .setInverted("l1", true)
            .setOpacity("l1", 0.6)
        #expect(s["l1"]?.hue == -180)
        let reset = try #require(s.resetColors("l1")["l1"])
        #expect(reset == LayerConfig(id: "l1", sourceId: "osm", opacity: 0.6))
        #expect(reset.hasAdjustments == false)
        #expect(s.resetColors("l1")["l2"] == s["l2"])
    }

    @Test("replacing the base keeps position and settings and leaves overlays alone")
    func replaceBase() {
        let s = stack().setOpacity("l1", 0.8).replaceBase("scan25", isBase)
        #expect(s.layers.map(\.sourceId) == ["scan25", "pentes"])
        #expect(s["l1"]?.opacity == 0.8)
        #expect(s.baseLayer(isBase)?.id == "l1")
    }

    @Test("replacing the base of a stack without one adds it at the bottom")
    func replaceMissingBase() {
        let s = LayerStack().add("pentes").replaceBase("osm", isBase)
        #expect(s.layers.map(\.sourceId) == ["osm", "pentes"])
    }

    @Test("replacing with the same source is a no-op")
    func sameSource() {
        let s = stack()
        #expect(s.replaceBase("osm", isBase) == s)
    }

    @Test("brightness maps to a min max range")
    func brightness() {
        #expect(RasterAdjust.brightnessRange(0) == BrightnessRange(0, 1))
        #expect(RasterAdjust.brightnessRange(0.5) == BrightnessRange(0.5, 1))
        #expect(RasterAdjust.brightnessRange(-0.75) == BrightnessRange(0, 0.25))
        #expect(RasterAdjust.brightnessRange(7) == BrightnessRange(1, 1))
        #expect(RasterAdjust.brightnessRange(-7) == BrightnessRange(0, 0))
    }

    @Test("undisplay others keeps only the chosen layer")
    func removeOthers() {
        let s = stack().add("scan25")
        #expect(s.removeOthers("l2").layers.map(\.id) == ["l2"])
        #expect(s.removeOthers("nope") == s)
    }

    @Test("inverting a layer touches that layer only")
    func inverting() {
        let s = stack().setInverted("l2", true)
        #expect(s.layers.map(\.inverted) == [false, true])
        #expect(s.setInverted("l2", false)["l2"]?.inverted == false)
    }
}
