// Port of core/src/main/kotlin/fr/toolmap/core/geo/GridZones.kt.

/// A grid zone of UTM and MGRS (« 31T »): the part of the earth one zone and one latitude band cover, a rectangle in
/// longitude and latitude.
public struct GridZone: Equatable, Hashable, Sendable {
    public let zone: Int
    public let band: Character
    public let west: Double
    public let east: Double
    public let south: Double
    public let north: Double

    public var name: String { "\(zone)\(band)" }

    /// Inside, the west and south edges included; the north edge too for band X, which ends the grid at 84 N.
    public func contains(_ lat: Double, _ lon: Double) -> Bool {
        let l = Utm.normalizeLon(lon)
        let northOk = band == "X" ? lat <= north : lat < north
        return l >= west && l < east && lat >= south && northOk
    }
}

/// The 1 197 grid zones between 80 S and 84 N, with the Norway and Svalbard exceptions.
public enum GridZones {
    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")
    private static let southEdge = -80.0
    private static let northEdge = 84.0
    private static let bandHeight = 8.0
    private static let zoneWidth = 6.0
    private static let bandV: [Int: (Double, Double)] = [31: (0.0, 3.0), 32: (3.0, 12.0)]
    private static let bandX: [Int: (Double, Double)] = [31: (0.0, 9.0), 33: (9.0, 21.0), 35: (21.0, 33.0), 37: (33.0, 42.0)]
    private static let missingInX: Set<Int> = [32, 34, 36]

    public static let all: [GridZone] = bands.flatMap { band in (1 ... 60).compactMap { make($0, band) } }

    /// The grid zone `zone` `band`, or nil when it does not exist (32X, 34X, 36X, a zone or band out of range).
    public static func get(_ zone: Int, _ band: Character) -> GridZone? {
        (1 ... 60).contains(zone) && bands.contains(band) ? make(zone, band) : nil
    }

    /// The grid zone of a position; nil outside 80 S ... 84 N.
    public static func of(_ lat: Double, _ lon: Double) -> GridZone? {
        guard let band = Utm.bandLetter(lat) else { return nil }
        return get(Utm.zoneOf(lat: lat, lon: lon), band)
    }

    /// The grid zones that meet `area` (west, south, east, north in degrees).
    public static func covering(_ area: Bounds) -> [GridZone] {
        if area.maxY < southEdge || area.minY > northEdge { return [] }
        return all.filter { $0.east > area.minX && $0.west < area.maxX && $0.north > area.minY && $0.south < area.maxY }
    }

    private static func make(_ zone: Int, _ band: Character) -> GridZone? {
        guard let index = bands.firstIndex(of: band) else { return nil }
        let south = southEdge + bandHeight * Double(index)
        let north = band == "X" ? northEdge : south + bandHeight
        let regular = (-180.0 + zoneWidth * Double(zone - 1), -180.0 + zoneWidth * Double(zone))
        let edges: (Double, Double)
        switch band {
        case "V": edges = bandV[zone] ?? regular
        case "X":
            if missingInX.contains(zone) { return nil }
            edges = bandX[zone] ?? regular
        default: edges = regular
        }
        return GridZone(zone: zone, band: band, west: edges.0, east: edges.1, south: south, north: north)
    }
}
