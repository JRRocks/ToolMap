// The cases of core/src/test/kotlin/fr/toolmap/core/source/BuiltinSourcesTest.kt that need the favorites
// (`Workspaces.sortieSki`, `Workspaces.randoMultiZoom`), ported with the layers.

import Testing
@testable import ToolMapCore

struct BuiltinWorkspacesTests {
    private let byId: [String: TileSource]

    init() throws {
        var byId: [String: TileSource] = [:]
        for source in try BuiltinSources.load() {
            byId[source.id] = source
        }
        self.byId = byId
    }

    @Test("the Sortie Ski favorite shows the snow map above 1550 m and the ski overlays from zoom 12")
    func sortieSki() {
        let ski = Workspaces.sortieSki
        #expect(ski.layers.map(\.sourceId) == ["ign-scan25", "OSMSNOWM", "OSMSKIM", "TRACESHIVERNALES", "ign-pentes"])
        let allKnown = ski.layers.allSatisfy { byId[$0.sourceId] != nil && $0.visible && $0.rangeLogic == .and }
        #expect(allKnown)
        guard ski.layers.count == 5 else { return }
        let base = ski.layers[0]
        let snow = ski.layers[1]
        let pistes = ski.layers[2]
        let tracks = ski.layers[3]
        let slopes = ski.layers[4]
        #expect(base == WorkspaceLayer("ign-scan25"))
        #expect(snow.displayMinElevation == 1550)
        #expect(snow.displayMaxElevation == nil && snow.displayMinZoom == nil && snow.displayMaxZoom == nil)
        for layer in [pistes, tracks, slopes] {
            #expect(layer.displayMinZoom == 12, "\(layer.sourceId)")
            #expect(layer.displayMaxZoom == nil, "\(layer.sourceId)")
            #expect(layer.displayMinElevation == nil, "\(layer.sourceId)")
        }
        #expect([pistes, tracks, slopes].map(\.opacity) == [1, 1, 0.3])
        let stack = ski.toStack { _ in true }.stack
        let at115 = stack.layers.filter { LayerZoom.isActiveAt($0, 11.5) }.map(\.sourceId)
        #expect(at115 == ["ign-scan25", "OSMSNOWM"])
        let at125 = stack.layers.filter { LayerZoom.isActiveAt($0, 12.5) }
        #expect(at125.count == 5)
    }

    @Test("the Rando Multi-Zoom favorite shows exactly one built-in map at each zoom from 1 to 19")
    func randoMultiZoom() {
        let stack = Workspaces.randoMultiZoom.toStack { _ in true }.stack
        let singleZoom = stack.layers.allSatisfy { byId[$0.sourceId].map { !$0.isMultiZoom } ?? false }
        #expect(singleZoom)
        let shown: [[String]] = (1 ... 19).map { (z: Int) -> [String] in
            stack.layers.filter { LayerZoom.isActiveAt($0, Double(z) + 0.5) }.map(\.sourceId)
        }
        #expect(shown.allSatisfy { $0.count == 1 }, "maps per zoom: \(shown)")
        #expect(shown[8 - 1] == ["osm"])
        #expect(shown[10 - 1] == ["opentopomap"])
        #expect(shown[17 - 1] == ["ign-scan25"])
        #expect(shown[18 - 1] == ["osm"])
        #expect(shown[19 - 1] == ["ign-ortho"])
    }
}
