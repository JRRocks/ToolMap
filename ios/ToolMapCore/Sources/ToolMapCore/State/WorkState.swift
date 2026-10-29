// Port of core/src/main/kotlin/fr/toolmap/core/state/WorkState.kt (CameraState, WorkState, WorkStateCodec).

import Foundation

public struct CameraState: Equatable, Hashable, Sendable, Codable {
    public let lat: Double
    public let lon: Double
    /// MapLibre camera zoom.
    public let zoom: Double
    public let bearing: Double

    public init(lat: Double = 46.5, lon: Double = 2.5, zoom: Double = 5.0, bearing: Double = 0.0) {
        self.lat = lat
        self.lon = lon
        self.zoom = zoom
        self.bearing = bearing
    }

    private enum CodingKeys: String, CodingKey {
        case lat, lon, zoom, bearing
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lat = try c.decodeIfPresent(Double.self, forKey: .lat) ?? 46.5
        lon = try c.decodeIfPresent(Double.self, forKey: .lon) ?? 2.5
        zoom = try c.decodeIfPresent(Double.self, forKey: .zoom) ?? 5.0
        bearing = try c.decodeIfPresent(Double.self, forKey: .bearing) ?? 0.0
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(lat, forKey: .lat)
        try c.encode(lon, forKey: .lon)
        try c.encode(zoom, forKey: .zoom)
        try c.encode(bearing, forKey: .bearing)
    }
}

/// What the app restores at startup: layers, camera and the two last base maps for the quick swap.
public struct WorkState: Equatable, Hashable, Sendable, Codable {
    /// OpenStreetMap alone: the stack of a first start.
    public static let defaultStack = LayerStack().add("osm")

    public let stack: LayerStack
    public let camera: CameraState
    /// Most recent first, at most two entries.
    public let recentBases: [String]
    public let offline: Bool

    public init(
        stack: LayerStack = WorkState.defaultStack,
        camera: CameraState = CameraState(),
        recentBases: [String] = ["osm"],
        offline: Bool = false
    ) {
        self.stack = stack
        self.camera = camera
        self.recentBases = recentBases
        self.offline = offline
    }

    /// Remembers `sourceId` as the base map in use (keeps the two latest distinct ones).
    public func withBaseUsed(_ sourceId: String) -> WorkState {
        var bases: [String] = []
        for id in [sourceId] + recentBases where !bases.contains(id) {
            bases.append(id)
        }
        return WorkState(stack: stack, camera: camera, recentBases: Array(bases.prefix(2)), offline: offline)
    }

    /// Base map to switch to with the quick-swap button: the previous one, if there is one.
    public func swapTarget() -> String? {
        recentBases.count > 1 ? recentBases[1] : nil
    }

    public func withStack(_ stack: LayerStack) -> WorkState {
        WorkState(stack: stack, camera: camera, recentBases: recentBases, offline: offline)
    }

    public func withCamera(_ camera: CameraState) -> WorkState {
        WorkState(stack: stack, camera: camera, recentBases: recentBases, offline: offline)
    }

    private enum CodingKeys: String, CodingKey {
        case stack, camera, recentBases, offline
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        stack = try c.decodeIfPresent(LayerStack.self, forKey: .stack) ?? WorkState.defaultStack
        camera = try c.decodeIfPresent(CameraState.self, forKey: .camera) ?? CameraState()
        recentBases = try c.decodeIfPresent([String].self, forKey: .recentBases) ?? ["osm"]
        offline = try c.decodeIfPresent(Bool.self, forKey: .offline) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(stack, forKey: .stack)
        try c.encode(camera, forKey: .camera)
        try c.encode(recentBases, forKey: .recentBases)
        try c.encode(offline, forKey: .offline)
    }
}

/// The saved work state as JSON (the keys and defaults of the Kotlin classes, unknown keys ignored).
public enum WorkStateCodec {
    public static func encode(_ state: WorkState) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(state)
        return String(decoding: data, as: UTF8.self)
    }

    /// Never throws: a missing or damaged file gives the default state.
    public static func decodeOrDefault(_ text: String?) -> WorkState {
        guard let json = text, !kotlinTrim(json).isEmpty else { return WorkState() }
        return (try? JSONDecoder().decode(WorkState.self, from: Data(json.utf8))) ?? WorkState()
    }
}
