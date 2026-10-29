// Port of core/src/main/kotlin/fr/toolmap/core/geo/LonLat.kt (positions and boxes).

/// A WGS84 position in decimal degrees.
public struct LonLat: Equatable, Hashable, Sendable {
    public let lon: Double
    public let lat: Double

    public init(lon: Double, lat: Double) {
        self.lon = lon
        self.lat = lat
    }
}

/// A WGS84 position plus a (tile) zoom level, as written in the AQX `<preview-location>` element.
public struct LonLatZoom: Equatable, Hashable, Sendable {
    public let lon: Double
    public let lat: Double
    public let zoom: Int

    public init(lon: Double, lat: Double, zoom: Int) {
        self.lon = lon
        self.lat = lat
        self.zoom = zoom
    }
}

/// An axis-aligned box. Units depend on the context (degrees for EPSG:4326, meters for EPSG:3857).
public struct Bounds: Equatable, Hashable, Sendable {
    public let minX: Double
    public let minY: Double
    public let maxX: Double
    public let maxY: Double

    public init(minX: Double, minY: Double, maxX: Double, maxY: Double) {
        self.minX = minX
        self.minY = minY
        self.maxX = maxX
        self.maxY = maxY
    }
}
