// Port of core/src/main/kotlin/fr/toolmap/core/layers/Workspace.kt (WorkspaceLayer, AppliedWorkspace, Workspace,
// Workspaces, WorkspaceCodec).

import Foundation

/// One layer of a `Workspace`: a `LayerConfig` without the id that only has a meaning inside a live stack.
public struct WorkspaceLayer: Equatable, Hashable, Sendable, Codable {
    public let sourceId: String
    public let visible: Bool
    public let opacity: Float
    public let contrast: Float
    public let brightness: Float
    public let saturation: Float
    public let displayMinZoom: Int?
    public let displayMaxZoom: Int?
    public let inverted: Bool
    public let displayMinElevation: Int?
    public let displayMaxElevation: Int?
    public let rangeLogic: RangeLogic
    /// Hue rotation in degrees (P26.6), see `LayerConfig.hue`.
    public let hue: Float

    public init(
        _ sourceId: String,
        visible: Bool = true,
        opacity: Float = 1,
        contrast: Float = 0,
        brightness: Float = 0,
        saturation: Float = 0,
        displayMinZoom: Int? = nil,
        displayMaxZoom: Int? = nil,
        inverted: Bool = false,
        displayMinElevation: Int? = nil,
        displayMaxElevation: Int? = nil,
        rangeLogic: RangeLogic = .and,
        hue: Float = 0
    ) {
        self.sourceId = sourceId
        self.visible = visible
        self.opacity = opacity
        self.contrast = contrast
        self.brightness = brightness
        self.saturation = saturation
        self.displayMinZoom = displayMinZoom
        self.displayMaxZoom = displayMaxZoom
        self.inverted = inverted
        self.displayMinElevation = displayMinElevation
        self.displayMaxElevation = displayMaxElevation
        self.rangeLogic = rangeLogic
        self.hue = hue
    }

    /// True when both a zoom range and an elevation band are set: only then does `rangeLogic` change the drawing.
    public var combinesRanges: Bool {
        (displayMinZoom != nil || displayMaxZoom != nil) && (displayMinElevation != nil || displayMaxElevation != nil)
    }

    private enum CodingKeys: String, CodingKey {
        case sourceId, visible, opacity, contrast, brightness, saturation, displayMinZoom, displayMaxZoom, inverted
        case displayMinElevation, displayMaxElevation, rangeLogic, hue
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sourceId = try c.decode(String.self, forKey: .sourceId)
        visible = try c.decodeIfPresent(Bool.self, forKey: .visible) ?? true
        opacity = try c.decodeIfPresent(Float.self, forKey: .opacity) ?? 1
        contrast = try c.decodeIfPresent(Float.self, forKey: .contrast) ?? 0
        brightness = try c.decodeIfPresent(Float.self, forKey: .brightness) ?? 0
        saturation = try c.decodeIfPresent(Float.self, forKey: .saturation) ?? 0
        displayMinZoom = try c.decodeIfPresent(Int.self, forKey: .displayMinZoom)
        displayMaxZoom = try c.decodeIfPresent(Int.self, forKey: .displayMaxZoom)
        inverted = try c.decodeIfPresent(Bool.self, forKey: .inverted) ?? false
        displayMinElevation = try c.decodeIfPresent(Int.self, forKey: .displayMinElevation)
        displayMaxElevation = try c.decodeIfPresent(Int.self, forKey: .displayMaxElevation)
        rangeLogic = try c.decodeIfPresent(RangeLogic.self, forKey: .rangeLogic) ?? .and
        hue = try c.decodeIfPresent(Float.self, forKey: .hue) ?? 0
    }

    /// Every key is written, nulls included, like `WorkspaceCodec` on Android (`encodeDefaults = true`).
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(sourceId, forKey: .sourceId)
        try c.encode(visible, forKey: .visible)
        try c.encode(opacity, forKey: .opacity)
        try c.encode(contrast, forKey: .contrast)
        try c.encode(brightness, forKey: .brightness)
        try c.encode(saturation, forKey: .saturation)
        try c.encode(displayMinZoom, forKey: .displayMinZoom)
        try c.encode(displayMaxZoom, forKey: .displayMaxZoom)
        try c.encode(inverted, forKey: .inverted)
        try c.encode(displayMinElevation, forKey: .displayMinElevation)
        try c.encode(displayMaxElevation, forKey: .displayMaxElevation)
        try c.encode(rangeLogic, forKey: .rangeLogic)
        try c.encode(hue, forKey: .hue)
    }
}

/// The result of putting a workspace on the map.
public struct AppliedWorkspace: Equatable, Hashable, Sendable {
    public let stack: LayerStack
    /// Source ids of the workspace that this device does not know (not imported, removed...).
    public let skipped: [String]

    public init(stack: LayerStack, skipped: [String]) {
        self.stack = stack
        self.skipped = skipped
    }
}

/// A named layer stack with its zoom ranges and elevation bands (P20), to switch between setups in one tap. Layers are
/// listed from the bottom of the stack to the top.
public struct Workspace: Equatable, Hashable, Sendable, Codable {
    public static let builtinPrefix = "builtin-"

    private static let tolerance: Float = 0.005

    public let id: String
    public let name: String
    public let layers: [WorkspaceLayer]

    public init(id: String, name: String, layers: [WorkspaceLayer]) {
        self.id = id
        self.name = name
        self.layers = layers
    }

    public var isBuiltin: Bool { id.hasPrefix(Workspace.builtinPrefix) }

    /// Builds the live stack (fresh layer ids), leaving out the layers whose source is not `isKnownSource`.
    public func toStack(_ isKnownSource: (String) -> Bool) -> AppliedWorkspace {
        let known = layers.filter { isKnownSource($0.sourceId) }
        var configs: [LayerConfig] = []
        for (index, layer) in known.enumerated() {
            configs.append(
                LayerConfig(
                    id: "\(LayerStack.idPrefix)\(index + 1)",
                    sourceId: layer.sourceId,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    contrast: layer.contrast,
                    brightness: layer.brightness,
                    saturation: layer.saturation,
                    displayMinZoom: layer.displayMinZoom,
                    displayMaxZoom: layer.displayMaxZoom,
                    inverted: layer.inverted,
                    displayMinElevation: layer.displayMinElevation,
                    displayMaxElevation: layer.displayMaxElevation,
                    rangeLogic: layer.rangeLogic,
                    hue: layer.hue
                )
            )
        }
        let skipped = layers.filter { !isKnownSource($0.sourceId) }.map(\.sourceId)
        return AppliedWorkspace(stack: LayerStack(configs), skipped: skipped)
    }

    /// True when `stack` shows exactly this workspace: same maps in the same order with the same opacity, colours,
    /// zoom ranges and elevation bands (layer ids do not matter). Used to highlight the favorite that is on screen.
    public func matches(_ stack: LayerStack) -> Bool {
        if stack.layers.count != layers.count { return false }
        for (want, have) in zip(layers, stack.layers) {
            let sameRanges = want.displayMinZoom == have.displayMinZoom
                && want.displayMaxZoom == have.displayMaxZoom
                && want.displayMinElevation == have.displayMinElevation
                && want.displayMaxElevation == have.displayMaxElevation
                && (!want.combinesRanges || want.rangeLogic == have.rangeLogic)
            let sameColours = Workspace.near(want.opacity, have.opacity)
                && Workspace.near(want.contrast, have.contrast)
                && Workspace.near(want.brightness, have.brightness)
                && Workspace.near(want.saturation, have.saturation)
                && Workspace.near(want.hue, have.hue)
            let same = want.sourceId == have.sourceId && want.visible == have.visible && want.inverted == have.inverted
            if !(same && sameRanges && sameColours) { return false }
        }
        return true
    }

    public static func of(_ id: String, _ name: String, _ stack: LayerStack) -> Workspace {
        Workspace(
            id: id,
            name: name,
            layers: stack.layers.map { (layer: LayerConfig) -> WorkspaceLayer in
                WorkspaceLayer(
                    layer.sourceId,
                    visible: layer.visible,
                    opacity: layer.opacity,
                    contrast: layer.contrast,
                    brightness: layer.brightness,
                    saturation: layer.saturation,
                    displayMinZoom: layer.displayMinZoom,
                    displayMaxZoom: layer.displayMaxZoom,
                    inverted: layer.inverted,
                    displayMinElevation: layer.displayMinElevation,
                    displayMaxElevation: layer.displayMaxElevation,
                    rangeLogic: layer.rangeLogic,
                    hue: layer.hue
                )
            }
        )
    }

    private static func near(_ a: Float, _ b: Float) -> Bool {
        abs(a - b) <= tolerance
    }
}

/// Workspaces shipped with the app.
public enum Workspaces {
    /// The author's own setup (from USR.PERSONNAL_MULTIZOOM.AQX), the first favorite: one map per zoom range, stacked
    /// as ordinary layers so each range shows in "Displayed maps" and can be changed (ADR-063).
    public static let randoMultiZoom = Workspace(
        id: "\(Workspace.builtinPrefix)rando-multizoom",
        name: "Rando Multi-Zoom",
        layers: [
            WorkspaceLayer("osm", displayMinZoom: 1, displayMaxZoom: 8),
            WorkspaceLayer("opentopomap", displayMinZoom: 9, displayMaxZoom: 11),
            WorkspaceLayer("ign-scan25", displayMinZoom: 12, displayMaxZoom: 17),
            WorkspaceLayer("osm", displayMinZoom: 18, displayMaxZoom: 18),
            WorkspaceLayer("ign-ortho", displayMinZoom: 19, displayMaxZoom: 19),
        ]
    )

    /// The author's ski setup (ADR-069): IGN SCAN 25 everywhere, OpenSnowMap's snow base map above 1,550 m, and from
    /// zoom 12 the ski pistes, the Yeti winter tracks and the IGN slopes at 30 %.
    public static let sortieSki = Workspace(
        id: "\(Workspace.builtinPrefix)sortie-ski",
        name: "Sortie Ski",
        layers: [
            WorkspaceLayer("ign-scan25"),
            WorkspaceLayer("OSMSNOWM", displayMinElevation: 1550),
            WorkspaceLayer("OSMSKIM", displayMinZoom: 12),
            WorkspaceLayer("TRACESHIVERNALES", displayMinZoom: 12),
            WorkspaceLayer("ign-pentes", opacity: 0.3, displayMinZoom: 12),
        ]
    )

    /// Id of the built-in multi-zoom map that `randoMultiZoom` showed until it became a stack of maps (ADR-063).
    public static let retiredRandoSource = "RANDO_ZOOM_GROUP"

    /// Puts the maps of `randoMultiZoom` in place of a layer of the retired built-in map `retiredRandoSource`, so a
    /// stack saved before ADR-063 does not open on an empty map. Left unchanged when the device knows a map with that
    /// id (a copy of the author's file imported by hand).
    public static func replaceRetired(_ stack: LayerStack, _ isKnownSource: (String) -> Bool) -> LayerStack {
        if !stack.layers.contains(where: { $0.sourceId == retiredRandoSource }) || isKnownSource(retiredRandoSource) {
            return stack
        }
        let replacement = randoMultiZoom.toStack { _ in true }.stack.layers
        var expanded: [LayerConfig] = []
        for layer in stack.layers {
            if layer.sourceId == retiredRandoSource {
                expanded.append(contentsOf: replacement)
            } else {
                expanded.append(layer)
            }
        }
        var renumbered: [LayerConfig] = []
        for (index, layer) in expanded.enumerated() {
            var copy = layer
            copy.id = "\(LayerStack.idPrefix)\(index + 1)"
            renumbered.append(copy)
        }
        return LayerStack(renumbered)
    }

    /// Favorites shown first in the "Maps and layers" panel (ADR-069: only these two).
    public static let builtin: [Workspace] = [randoMultiZoom, sortieSki]
}

/// The favorites as JSON files, readable by both apps (the keys of the Kotlin `@Serializable` classes).
public enum WorkspaceCodec {
    public static func encode(_ workspace: Workspace) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(workspace)
        return String(decoding: data, as: UTF8.self)
    }

    public static func decodeOrNull(_ text: String) -> Workspace? {
        try? JSONDecoder().decode(Workspace.self, from: Data(text.utf8))
    }
}
