// Port of core/src/main/kotlin/fr/toolmap/core/layers/StyleDiff.kt (LayerProps, AppliedLayer, StyleOp, StyleDiff).

/// Everything MapLibre needs to draw one raster layer; compared to decide what to change in the style.
public struct LayerProps: Equatable, Hashable, Sendable {
    public var visible: Bool
    public var opacity: Float
    public var contrast: Float
    public var brightnessMin: Float
    public var brightnessMax: Float
    public var saturation: Float
    /// MapLibre `raster-hue-rotate`, in degrees.
    public var hueRotate: Float
    /// MapLibre `minzoom` / `maxzoom` of the layer (camera zoom); nil = not set.
    public var minZoom: Float?
    public var maxZoom: Float?

    public init(
        visible: Bool = true,
        opacity: Float = 1,
        contrast: Float = 0,
        brightnessMin: Float = 0,
        brightnessMax: Float = 1,
        saturation: Float = 0,
        hueRotate: Float = 0,
        minZoom: Float? = nil,
        maxZoom: Float? = nil
    ) {
        self.visible = visible
        self.opacity = opacity
        self.contrast = contrast
        self.brightnessMin = brightnessMin
        self.brightnessMax = brightnessMax
        self.saturation = saturation
        self.hueRotate = hueRotate
        self.minZoom = minZoom
        self.maxZoom = maxZoom
    }
}

/// A raster layer as it exists (or should exist) in the MapLibre style.
public struct AppliedLayer: Equatable, Hashable, Sendable {
    public var id: String
    public var sourceId: String
    public var props: LayerProps

    public init(id: String, sourceId: String, props: LayerProps) {
        self.id = id
        self.sourceId = sourceId
        self.props = props
    }
}

/// One change of the MapLibre style (Kotlin `sealed interface StyleOp`).
public enum StyleOp: Equatable, Hashable, Sendable {
    case addSource(sourceId: String)
    case removeSource(sourceId: String)
    /// Insert `layer` right below the layer `belowId`; nil means on top of every raster layer.
    case addLayer(layer: AppliedLayer, belowId: String?)
    case removeLayer(id: String)
    case updateLayer(id: String, props: LayerProps)
}

/// Pure difference between the layers currently in the style and the desired ones (bottom to top), expressed as the
/// minimum sensible list of operations. `apply` replays operations on a plain list, which lets tests check that any
/// reordering ends exactly in the desired state.
public enum StyleDiff {
    public static func compute(_ current: [AppliedLayer], _ desired: [AppliedLayer]) -> [StyleOp] {
        var ops: [StyleOp] = []
        var desiredById: [String: AppliedLayer] = [:]
        for layer in desired {
            desiredById[layer.id] = layer
        }

        // 1. Layers that disappear, or whose source changed (the layer is rebuilt).
        var sim: [AppliedLayer] = []
        for layer in current {
            if let wanted = desiredById[layer.id], wanted.sourceId == layer.sourceId {
                sim.append(layer)
            } else {
                ops.append(.removeLayer(id: layer.id))
            }
        }
        // 2. Sources nobody will use any more (Kotlin sets keep the order of first appearance).
        let neededSources = orderedUnique(desired.map(\.sourceId))
        let presentSources = orderedUnique(current.map(\.sourceId))
        let needed = Set(neededSources)
        let present = Set(presentSources)
        for sourceId in presentSources where !needed.contains(sourceId) {
            ops.append(.removeSource(sourceId: sourceId))
        }
        // 3. New sources.
        for sourceId in neededSources where !present.contains(sourceId) {
            ops.append(.addSource(sourceId: sourceId))
        }

        // 4. Walk the desired order, fixing the position of each layer.
        for (index, want) in desired.enumerated() {
            if index < sim.count, sim[index].id == want.id {
                let here = sim[index]
                if here.props != want.props {
                    ops.append(.updateLayer(id: want.id, props: want.props))
                    var updated = here
                    updated.props = want.props
                    sim[index] = updated
                }
                continue
            }
            if let existing = sim.firstIndex(where: { $0.id == want.id }) {
                ops.append(.removeLayer(id: want.id))
                sim.remove(at: existing)
            }
            let below = index < sim.count ? sim[index].id : nil
            ops.append(.addLayer(layer: want, belowId: below))
            sim.insert(want, at: Swift.min(index, sim.count))
        }
        return ops
    }

    /// Replays `ops` on `current`; used by tests and as documentation of the operations' meaning.
    public static func apply(_ current: [AppliedLayer], _ ops: [StyleOp]) -> [AppliedLayer] {
        var result = current
        for op in ops {
            switch op {
            case .addSource, .removeSource:
                break
            case let .removeLayer(id):
                result.removeAll { $0.id == id }
            case let .updateLayer(id, props):
                if let i = result.firstIndex(where: { $0.id == id }) {
                    var updated = result[i]
                    updated.props = props
                    result[i] = updated
                }
            case let .addLayer(layer, belowId):
                let at = belowId.flatMap { below in result.firstIndex { $0.id == below } } ?? -1
                if at >= 0 {
                    result.insert(layer, at: at)
                } else {
                    result.append(layer)
                }
            }
        }
        return result
    }

    private static func orderedUnique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for value in values {
            if seen.insert(value).inserted { out.append(value) }
        }
        return out
    }
}
