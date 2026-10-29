// Port of core/src/test/kotlin/fr/toolmap/core/geo/ScaleRatioTest.kt.

import Testing
@testable import ToolMapCore

struct ScaleRatioTests {
    @Test("at the equator and zoom 0 the ratio is the world over one printed dp")
    func equator() {
        // 40 075 016.69 m over 256 px, over the 0.158 75 mm of a dp: 156 543.03 m / 0.000 158 75 m.
        #expect(abs(ScaleBar.denominator(0.0, 0.0, 1.0) - 986_097_851.5) <= 1.0)
    }

    @Test("each displayed zoom level halves the ratio")
    func halving() {
        let at16 = ScaleBar.denominator(46.13, 16.0, 2.625)
        #expect(abs(ScaleBar.denominator(46.13, 17.0, 2.625) - at16 / 2) <= 0.5)
    }

    @Test("near 46 degrees at zoom 16 on a 2_625 density screen the ratio is about 1 to 27_400")
    func near46() {
        let denominator = ScaleBar.denominator(46.13, 16.0, 2.625)
        #expect(abs(denominator - 27_410) < 100, "\(denominator)")
    }

    @Test("the ratio is written with a comma between thousands and no decimals")
    func ratioText() {
        #expect(ScaleBar.ratioText(27_372.7) == "1:27,373")
        #expect(ScaleBar.ratioText(986_097_851.5) == "1:986,097,852")
        #expect(ScaleBar.ratioText(500.0) == "1:500")
    }

    @Test("the bar at zoom 16 near 46 degrees is 500 m long and about 115 dp wide")
    func bar500() {
        let bar = ScaleBar.forDisplayedZoom(46.13, 16.0, 2.625, maxWidthDp: 120.0)
        #expect(bar.meters == 500.0)
        #expect(bar.label == "500 m")
        #expect(abs(bar.widthDp - 115) < 1.5, "\(bar.widthDp)")
    }

    @Test("the bar and the ratio agree, its printed length times the ratio is its ground length")
    func barAndRatio() {
        let denominator = ScaleBar.denominator(46.13, 16.0, 2.625)
        let bar = ScaleBar.forDisplayedZoom(46.13, 16.0, 2.625, maxWidthDp: 120.0)
        let ground = bar.widthDp * (0.0254 / 160.0) * denominator
        #expect(abs(ground - bar.meters) <= 1e-6)
    }
}
