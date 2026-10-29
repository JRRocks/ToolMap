// Port of core/src/test/kotlin/fr/toolmap/core/layers/BandSwapTest.kt.

import Testing
@testable import ToolMapCore

struct BandSwapTests {
    private func layer(_ id: String, _ source: String, visible: Bool = true) -> AppliedLayer {
        AppliedLayer(id: id, sourceId: source, props: LayerProps(visible: visible))
    }

    private var topo: AppliedLayer { layer("l1", "opentopomap") }
    private var snow1500: AppliedLayer { layer("l2", "opensnowmap?alt=1500,") }
    private var snow1300: AppliedLayer { layer("l2", "opensnowmap?alt=1300,") }

    @Test("a layer moved to another band of the same map keeps its old drawing below")
    func ghostKept() {
        #expect(BandSwap.newGhosts([topo, snow1500], [topo, snow1300], []) == [snow1500])
        // From plain to banded and back: still the same map.
        let plain = layer("l2", "opensnowmap")
        #expect(BandSwap.newGhosts([plain], [snow1500], []) == [plain])
        #expect(BandSwap.newGhosts([snow1500], [plain], []) == [snow1500])
    }

    @Test("no ghost for another map, a hidden or removed layer, or one that has a ghost already")
    func noGhost() {
        let other = layer("l2", "osm?alt=1500,")
        #expect(BandSwap.newGhosts([snow1500], [other], []).isEmpty)
        let hidden = layer("l2", "opensnowmap?alt=1500,", visible: false)
        #expect(BandSwap.newGhosts([hidden], [snow1300], []).isEmpty)
        #expect(BandSwap.newGhosts([topo, snow1500], [topo], []).isEmpty)
        #expect(BandSwap.newGhosts([snow1500], [snow1300], ["l2"]).isEmpty)
        #expect(BandSwap.newGhosts([snow1500], [snow1500], []).isEmpty)
    }

    @Test("a ghost goes at once when its layer is gone or back on the ghost's source")
    func staleGhosts() {
        let ghosts = ["l2": snow1500]
        #expect(BandSwap.stale(ghosts, [topo, snow1300]).isEmpty)
        #expect(BandSwap.stale(ghosts, [topo, snow1500]) == ["l2"])
        #expect(BandSwap.stale(ghosts, [topo]) == ["l2"])
    }
}
