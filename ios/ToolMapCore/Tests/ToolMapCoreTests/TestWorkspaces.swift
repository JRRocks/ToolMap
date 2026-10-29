// Port of core/src/test/kotlin/fr/toolmap/core/layers/TestWorkspaces.kt.

@testable import ToolMapCore

enum TestWorkspaces {
    /// A three-layer favorite with zoom ranges and an overlay, shipped until ADR-069 ("IGN Topo 8–16, Plan 1–7, Pentes")
    /// and kept as a sample for the tests: IGN Plan v2 for zooms 1–7, IGN SCAN 25 for 8–16, the slopes at 50 % over both.
    static let topoPlanSlopes = Workspace(
        id: "\(Workspace.builtinPrefix)ign-topo-plan-slopes",
        name: "IGN Topo 8–16, Plan 1–7, Pentes",
        layers: [
            WorkspaceLayer("ign-plan", displayMinZoom: 1, displayMaxZoom: 7),
            WorkspaceLayer("ign-scan25", displayMinZoom: 8, displayMaxZoom: 16),
            WorkspaceLayer("ign-pentes", opacity: 0.5, displayMinZoom: 1, displayMaxZoom: 16),
        ]
    )
}
