// Port of core/src/main/kotlin/fr/toolmap/core/layers/LayerStack.kt.

/// The ordered layers shown on the map. Index 0 is the bottom layer, the last one is drawn on top.
/// Immutable: every operation returns a new stack (an unknown id leaves the stack unchanged).
///
/// JSON: `{"layers": [...]}`, like the Kotlin `@Serializable` class.
public struct LayerStack: Equatable, Hashable, Sendable, Codable {
    public static let idPrefix = "l"

    public let layers: [LayerConfig]

    public init(_ layers: [LayerConfig] = []) {
        self.layers = layers
    }

    public var isEmpty: Bool { layers.isEmpty }

    public subscript(_ id: String) -> LayerConfig? {
        layers.first { $0.id == id }
    }

    public func indexOf(_ id: String) -> Int {
        layers.firstIndex { $0.id == id } ?? -1
    }

    /// Layers from the top of the screen down: the order users see in the list.
    public func topToBottom() -> [LayerConfig] {
        Array(layers.reversed())
    }

    /// Adds `sourceId` on top (or at `index`) with a fresh id.
    public func add(_ sourceId: String, opacity: Float = 1, index: Int? = nil) -> LayerStack {
        let config = LayerConfig(id: nextId(), sourceId: sourceId, opacity: opacity)
        let at = LayerStack.clamp(index ?? layers.count, 0, layers.count)
        var list = layers
        list.insert(config, at: at)
        return LayerStack(list)
    }

    public func remove(_ id: String) -> LayerStack {
        LayerStack(layers.filter { $0.id != id })
    }

    /// Keeps only `id` ("Undisplay others"); an unknown id leaves the stack unchanged.
    public func removeOthers(_ id: String) -> LayerStack {
        indexOf(id) < 0 ? self : LayerStack(layers.filter { $0.id == id })
    }

    /// Moves `id` to position `toIndex` (clamped).
    public func move(_ id: String, _ toIndex: Int) -> LayerStack {
        let from = indexOf(id)
        if from < 0 { return self }
        var list = layers
        let item = list.remove(at: from)
        list.insert(item, at: LayerStack.clamp(toIndex, 0, list.count))
        return LayerStack(list)
    }

    /// One step towards the top of the screen.
    public func moveUp(_ id: String) -> LayerStack {
        let index = indexOf(id)
        return index < 0 ? self : move(id, index + 1)
    }

    /// One step towards the bottom of the screen.
    public func moveDown(_ id: String) -> LayerStack {
        let index = indexOf(id)
        return index < 0 ? self : move(id, index - 1)
    }

    public func update(_ id: String, _ transform: (LayerConfig) -> LayerConfig) -> LayerStack {
        LayerStack(layers.map { $0.id == id ? transform($0) : $0 })
    }

    public func setVisible(_ id: String, _ visible: Bool) -> LayerStack {
        update(id) { layer in
            var copy = layer
            copy.visible = visible
            return copy
        }
    }

    public func setOpacity(_ id: String, _ opacity: Float) -> LayerStack {
        update(id) { layer in
            var copy = layer
            copy.opacity = LayerStack.clamp(opacity, 0, 1)
            return copy
        }
    }

    /// Sets the display range of `id` (displayed zoom, inclusive; nil = unbounded). Out-of-range values are clamped to
    /// 0...`ZoomBar.max` and a minimum above the maximum raises the maximum to it.
    public func setZoomRange(_ id: String, _ min: Int?, _ max: Int?) -> LayerStack {
        let low = min.map { LayerStack.clamp($0, 0, ZoomBar.max) }
        var high = max.map { LayerStack.clamp($0, 0, ZoomBar.max) }
        if let lowValue = low, let highValue = high, highValue < lowValue {
            high = lowValue
        }
        let newHigh = high
        return update(id) { layer in
            var copy = layer
            copy.displayMinZoom = low
            copy.displayMaxZoom = newHigh
            return copy
        }
    }

    /// Sets the elevation band of layer `id` (metres, inclusive, nil = unbounded, P20) and how it combines with the
    /// zoom range; a minimum above the maximum raises the maximum to it.
    public func setElevationRange(_ id: String, _ min: Int?, _ max: Int?, _ logic: RangeLogic) -> LayerStack {
        var high = max
        if let lowValue = min, let highValue = max, highValue < lowValue {
            high = lowValue
        }
        let newHigh = high
        return update(id) { layer in
            var copy = layer
            copy.displayMinElevation = min
            copy.displayMaxElevation = newHigh
            copy.rangeLogic = logic
            return copy
        }
    }

    public func setAdjustments(_ id: String, _ contrast: Float, _ brightness: Float, _ saturation: Float) -> LayerStack {
        update(id) { layer in
            var copy = layer
            copy.contrast = LayerStack.clamp(contrast, -1, 1)
            copy.brightness = LayerStack.clamp(brightness, -1, 1)
            copy.saturation = LayerStack.clamp(saturation, -1, 1)
            return copy
        }
    }

    public func setInverted(_ id: String, _ inverted: Bool) -> LayerStack {
        update(id) { layer in
            var copy = layer
            copy.inverted = inverted
            return copy
        }
    }

    /// Turns a layer's colours round the colour wheel by `hue` degrees (P26.6).
    public func setHue(_ id: String, _ hue: Float) -> LayerStack {
        update(id) { layer in
            var copy = layer
            copy.hue = LayerStack.clamp(hue, -RasterAdjust.maxHue, RasterAdjust.maxHue)
            return copy
        }
    }

    /// « Réinitialiser les couleurs »: contrast, brightness, saturation and hue back to neutral, colours not inverted.
    public func resetColors(_ id: String) -> LayerStack {
        update(id) { layer in
            var copy = layer
            copy.contrast = 0
            copy.brightness = 0
            copy.saturation = 0
            copy.hue = 0
            copy.inverted = false
            return copy
        }
    }

    /// Swaps the source of the lowest base-map layer (a layer for which `isBase` holds) for `sourceId`, keeping its
    /// position, opacity and adjustments. Adds a new bottom layer when the stack has no base map.
    public func replaceBase(_ sourceId: String, _ isBase: (String) -> Bool) -> LayerStack {
        guard let target = layers.first(where: { isBase($0.sourceId) }) else { return add(sourceId, index: 0) }
        return update(target.id) { layer in
            var copy = layer
            copy.sourceId = sourceId
            return copy
        }
    }

    /// Lowest layer showing a base map, if any.
    public func baseLayer(_ isBase: (String) -> Bool) -> LayerConfig? {
        layers.first { isBase($0.sourceId) }
    }

    /// Deterministic next id: `l<max number in use + 1>`.
    public func nextId() -> String {
        let numbers = layers.compactMap { (layer: LayerConfig) -> Int? in
            guard layer.id.hasPrefix(LayerStack.idPrefix) else { return nil }
            return kotlinInt(String(layer.id.dropFirst(LayerStack.idPrefix.count)))
        }
        let highest = numbers.max() ?? 0
        return "\(LayerStack.idPrefix)\(highest + 1)"
    }

    private enum CodingKeys: String, CodingKey {
        case layers
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        layers = try c.decodeIfPresent([LayerConfig].self, forKey: .layers) ?? []
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(layers, forKey: .layers)
    }

    private static func clamp(_ value: Int, _ low: Int, _ high: Int) -> Int {
        Swift.min(Swift.max(value, low), high)
    }

    /// Kotlin `coerceIn`: a NaN stays NaN.
    private static func clamp(_ value: Float, _ low: Float, _ high: Float) -> Float {
        if value < low { return low }
        if value > high { return high }
        return value
    }
}
