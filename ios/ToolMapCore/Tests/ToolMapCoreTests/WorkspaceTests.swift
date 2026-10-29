// Port of core/src/test/kotlin/fr/toolmap/core/layers/WorkspaceTest.kt.

import Testing
@testable import ToolMapCore

struct WorkspaceTests {
    private let topoPlanSlopes = TestWorkspaces.topoPlanSlopes

    private func withoutIds(_ layers: [LayerConfig]) -> [LayerConfig] {
        layers.map { (layer: LayerConfig) -> LayerConfig in
            var copy = layer
            copy.id = ""
            return copy
        }
    }

    @Test("a workspace builds a stack with distinct ids in the listed order")
    func buildsStack() {
        let applied = topoPlanSlopes.toStack { _ in true }
        #expect(applied.stack.layers.map(\.sourceId) == ["ign-plan", "ign-scan25", "ign-pentes"])
        #expect(Set(applied.stack.layers.map(\.id)).count == 3)
        #expect(applied.skipped.isEmpty)
        #expect(applied.stack.layers.last?.opacity == 0.5)
        #expect(applied.stack.layers[1].displayMinZoom == 8)
        #expect(applied.stack.layers[1].displayMaxZoom == 16)
    }

    @Test("a stack saved with the retired Rando Multi-Zoom map gets its maps back")
    func retiredRando() {
        let saved = LayerStack().add(Workspaces.retiredRandoSource).add("ign-pentes", opacity: 0.5)
        let back = Workspaces.replaceRetired(saved) { $0 != Workspaces.retiredRandoSource }
        #expect(back.layers.map(\.sourceId) == ["osm", "opentopomap", "ign-scan25", "osm", "ign-ortho", "ign-pentes"])
        #expect(back.layers.map(\.id) == (1 ... 6).map { "\(LayerStack.idPrefix)\($0)" })
        #expect(back.layers[2].displayMinZoom == 12)
        #expect(back.layers[2].displayMaxZoom == 17)
        #expect(back.layers.last?.opacity == 0.5)
        #expect(Workspaces.randoMultiZoom.matches(LayerStack(Array(back.layers.dropLast()))))
    }

    @Test("an imported copy of the Rando Multi-Zoom file keeps its layer")
    func importedRando() {
        let saved = LayerStack().add(Workspaces.retiredRandoSource)
        #expect(Workspaces.replaceRetired(saved) { _ in true } == saved)
        let other = LayerStack().add("osm")
        #expect(Workspaces.replaceRetired(other) { _ in false } == other)
    }

    @Test("layers whose source is unknown are skipped and reported")
    func skipped() {
        let applied = topoPlanSlopes.toStack { $0 != "ign-plan" }
        #expect(applied.stack.layers.map(\.sourceId) == ["ign-scan25", "ign-pentes"])
        #expect(applied.skipped == ["ign-plan"])
    }

    @Test("saving a stack and applying it gives the same layers")
    func saveAndApply() {
        let base = LayerStack().add("osm").add("ign-pentes", opacity: 0.4)
        let ranged = base.setZoomRange(base.layers[base.layers.count - 1].id, 6, 14)
        let stack = ranged.setAdjustments(ranged.layers[0].id, 0.2, -0.1, 0.3)
        let workspace = Workspace.of("mine", "Ma pile", stack)
        let back = workspace.toStack { _ in true }.stack
        #expect(withoutIds(back.layers) == withoutIds(stack.layers))
    }

    @Test("workspaces are written as JSON and read back")
    func json() throws {
        let original = topoPlanSlopes
        let text = try WorkspaceCodec.encode(original)
        let decoded = try #require(WorkspaceCodec.decodeOrNull(text))
        #expect(decoded == original)
        #expect(WorkspaceCodec.decodeOrNull("not json") == nil)
        #expect(WorkspaceCodec.decodeOrNull("") == nil)
    }

    @Test("only shipped presets are flagged as built in")
    func builtinFlag() {
        #expect(Workspaces.sortieSki.isBuiltin)
        #expect(!Workspace.of("mine", "x", LayerStack()).isBuiltin)
    }

    @Test("a workspace matches the stack it produced, whatever the layer ids")
    func matchesAnyIds() {
        let stack = topoPlanSlopes.toStack { _ in true }.stack
        #expect(topoPlanSlopes.matches(stack))
        let renamed = stack.layers.map { (layer: LayerConfig) -> LayerConfig in
            var copy = layer
            copy.id = "z\(layer.id)"
            return copy
        }
        #expect(topoPlanSlopes.matches(LayerStack(renamed)))
    }

    @Test("a change of opacity, order, visibility, range or colour breaks the match")
    func changesBreakMatch() {
        let workspace = topoPlanSlopes
        let stack = workspace.toStack { _ in true }.stack
        #expect(!workspace.matches(stack.setOpacity("l3", 0.8)))
        #expect(!workspace.matches(stack.moveUp("l1")))
        #expect(!workspace.matches(stack.setVisible("l2", false)))
        #expect(!workspace.matches(stack.setZoomRange("l2", 9, 16)))
        #expect(!workspace.matches(stack.setInverted("l2", true)))
        #expect(!workspace.matches(stack.setHue("l2", 30)))
        #expect(!workspace.matches(stack.remove("l1")))
        // A hair of difference in a slider is not a change.
        #expect(workspace.matches(stack.setOpacity("l3", 0.502)))
    }

    @Test("the hue survives saving, writing and applying a workspace, and an older favourite has none")
    func hueSurvives() throws {
        let stack = LayerStack().add("osm").setHue("l1", -40)
        let written = try WorkspaceCodec.encode(Workspace.of("mine", "x", stack))
        let decoded = try #require(WorkspaceCodec.decodeOrNull(written))
        let back = decoded.toStack { _ in true }.stack
        #expect(back.layers.count == 1)
        #expect(back.layers.first?.hue == -40)
        let older = #"{"id":"old","name":"Ancien","layers":[{"sourceId":"osm","saturation":0.2}]}"#
        let old = try #require(WorkspaceCodec.decodeOrNull(older))
        #expect(old.layers.count == 1)
        #expect(old.layers.first?.hue == 0)
    }

    @Test("the inverted flag survives saving and applying a workspace")
    func invertedSurvives() {
        let stack = LayerStack().add("osm").setInverted("l1", true)
        let back = Workspace.of("mine", "x", stack).toStack { _ in true }.stack
        #expect(back.layers.count == 1)
        #expect(back.layers.first?.inverted == true)
    }

    @Test("the elevation band and its logic survive saving and applying a workspace")
    func bandSurvives() throws {
        let stack = LayerStack().add("opensnowmap").setZoomRange("l1", 8, 16).setElevationRange("l1", 1500, nil, .or)
        let workspace = Workspace.of("mine", "x", stack)
        let text = try WorkspaceCodec.encode(workspace)
        let decoded = try #require(WorkspaceCodec.decodeOrNull(text))
        let layers = decoded.toStack { _ in true }.stack.layers
        #expect(layers.count == 1)
        let back = try #require(layers.first)
        #expect(back.displayMinElevation == 1500)
        #expect(back.displayMaxElevation == nil)
        #expect(back.rangeLogic == .or)
        #expect(workspace.matches(stack))
        #expect(!workspace.matches(stack.setElevationRange("l1", 1600, nil, .or)))
        #expect(!workspace.matches(stack.setElevationRange("l1", 1500, nil, .and)))
        // Without a zoom range, « ET » and « OU » draw the same thing.
        let bandOnly = Workspace.of("b", "y", LayerStack().add("osm").setElevationRange("l1", 1500, nil, .and))
        #expect(bandOnly.matches(LayerStack().add("osm").setElevationRange("l1", 1500, nil, .or)))
    }

    @Test("an old workspace file without the inverted flag still loads")
    func oldFile() throws {
        let json = #"{"id":"a","name":"Old","layers":[{"sourceId":"osm"}]}"#
        let workspace = try #require(WorkspaceCodec.decodeOrNull(json))
        #expect(workspace.layers.count == 1)
        #expect(workspace.layers.first?.inverted == false)
    }

    @Test("the shipped favorites are Rando Multi-Zoom and Sortie Ski, built in with layers and distinct ids")
    func shippedFavorites() {
        let all = Workspaces.builtin
        #expect(all.allSatisfy { $0.isBuiltin && !$0.layers.isEmpty })
        #expect(Set(all.map(\.id)).count == all.count)
        // ADR-069: only the author's two setups are shipped.
        #expect(all.map(\.name) == ["Rando Multi-Zoom", "Sortie Ski"])
    }
}
