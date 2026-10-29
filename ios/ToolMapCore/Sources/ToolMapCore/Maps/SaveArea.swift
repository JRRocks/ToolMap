// Port of core/src/main/kotlin/fr/toolmap/core/maps/SaveArea.kt.

/// The four handles of the rectangle drawn in "Select and save an area".
public enum AreaCorner: String, CaseIterable, Sendable {
    case northWest = "NORTH_WEST"
    case northEast = "NORTH_EAST"
    case southWest = "SOUTH_WEST"
    case southEast = "SOUTH_EAST"
}

/// The rectangle of the zone to save, in WGS84 degrees. It is fixed on the ground: moving the map moves it on the
/// screen, dragging a handle changes its bounds. It never gets thinner than `minSpan` degrees.
public struct AreaSelection: Equatable, Hashable, Sendable {
    /// About 10 m; below that the zone would hold a tile or two at any useful zoom.
    public static let minSpan = 1e-4
    public static let maxLatitude = 85.05112878
    public static let maxLongitude = 180.0
    public static let defaultFraction = 0.6
    private static let minFraction = 0.05

    public let west: Double
    public let south: Double
    public let east: Double
    public let north: Double

    /// Throws `ToolMapError` for an empty rectangle.
    public init(west: Double, south: Double, east: Double, north: Double) throws {
        try require(west < east && south < north, "empty rectangle")
        self.west = west
        self.south = south
        self.east = east
        self.north = north
    }

    public func toShape() -> ZoneShape.Rectangle {
        ZoneShape.Rectangle(west: west, south: south, east: east, north: north)
    }

    /// Latitude of `corner`.
    public func latOf(_ corner: AreaCorner) -> Double {
        corner == .northWest || corner == .northEast ? north : south
    }

    /// Longitude of `corner`.
    public func lonOf(_ corner: AreaCorner) -> Double {
        corner == .northWest || corner == .southWest ? west : east
    }

    /// The rectangle after the handle `corner` was dragged to `lat`, `lon`. The opposite corner stays where it is, the
    /// dragged one stops before crossing it, and nothing leaves the Web Mercator world.
    public func drag(_ corner: AreaCorner, lat: Double, lon: Double) throws -> AreaSelection {
        let y = AreaSelection.coerceIn(lat, -AreaSelection.maxLatitude, AreaSelection.maxLatitude)
        let x = AreaSelection.coerceIn(lon, -AreaSelection.maxLongitude, AreaSelection.maxLongitude)
        let span = AreaSelection.minSpan
        switch corner {
        case .northWest:
            return try AreaSelection(
                west: AreaSelection.atMost(x, east - span),
                south: south,
                east: east,
                north: AreaSelection.atLeast(y, south + span)
            )
        case .northEast:
            return try AreaSelection(
                west: west,
                south: south,
                east: AreaSelection.atLeast(x, west + span),
                north: AreaSelection.atLeast(y, south + span)
            )
        case .southWest:
            return try AreaSelection(
                west: AreaSelection.atMost(x, east - span),
                south: AreaSelection.atMost(y, north - span),
                east: east,
                north: north
            )
        case .southEast:
            return try AreaSelection(
                west: west,
                south: AreaSelection.atMost(y, north - span),
                east: AreaSelection.atLeast(x, west + span),
                north: north
            )
        }
    }

    /// The rectangle that fills `fraction` of the visible area, around its centre (where the panel opens).
    public static func centered(_ visible: ZoneShape.Rectangle, fraction: Double = defaultFraction) throws -> AreaSelection {
        let f = coerceIn(fraction, minFraction, 1.0)
        let dx = (visible.east - visible.west) * (1 - f) / 2
        let dy = (visible.north - visible.south) * (1 - f) / 2
        return try AreaSelection(
            west: visible.west + dx,
            south: visible.south + dy,
            east: visible.east - dx,
            north: visible.north - dy
        )
    }

    /// Kotlin `coerceIn`.
    private static func coerceIn(_ value: Double, _ low: Double, _ high: Double) -> Double {
        if value < low { return low }
        if value > high { return high }
        return value
    }

    /// Kotlin `coerceAtMost`.
    private static func atMost(_ value: Double, _ high: Double) -> Double {
        value > high ? high : value
    }

    /// Kotlin `coerceAtLeast`.
    private static func atLeast(_ value: Double, _ low: Double) -> Double {
        value < low ? low : value
    }
}

/// What a zoom level of a map becomes when the zone is saved.
public enum ZoomCell: String, CaseIterable, Sendable {
    /// Will be saved.
    case saved = "SAVED"
    /// The map has this zoom, but it is above the chosen maximum.
    case skipped = "SKIPPED"
    /// The map has no tile at this zoom.
    case absent = "ABSENT"
}

/// The zooms a map contributes to a save: `from`–`to` out of the `available` maximum (AlpineQuest's "1-14/17").
public struct ZoomSpan: Equatable, Hashable, Sendable {
    public let from: Int
    public let to: Int
    public let available: Int

    public init(_ from: Int, _ to: Int, _ available: Int) {
        self.from = from
        self.to = to
        self.available = available
    }
}

/// The little per-zoom bar of each map row in "Select and save an area".
public enum ZoneStrips {
    /// First zoom the bars can show.
    public static let scaleMin = 0

    /// The zooms saved for a map served from `sourceMin` to `sourceMax` when the slider is at `chosenMax`.
    public static func span(sourceMin: Int, sourceMax: Int, chosenMax: Int) -> ZoomSpan? {
        chosenMax < sourceMin ? nil : ZoomSpan(sourceMin, Swift.min(chosenMax, sourceMax), sourceMax)
    }

    /// One cell per zoom from `scaleMin` to `scaleMax` for a map served from `sourceMin` to `sourceMax`.
    public static func cells(sourceMin: Int, sourceMax: Int, chosenMax: Int, scaleMax: Int) -> [ZoomCell] {
        stride(from: scaleMin, through: scaleMax, by: 1).map { zoom -> ZoomCell in
            if zoom < sourceMin || zoom > sourceMax { return .absent }
            return zoom <= chosenMax ? .saved : .skipped
        }
    }

    /// Where the current `tileZoom` falls along a bar of `scaleMax` + 1 cells (0...1), or nil off the bar.
    public static func marker(_ tileZoom: Double, scaleMax: Int) -> Float? {
        let position = (tileZoom - Double(scaleMin)) / Double(scaleMax - scaleMin + 1)
        return position >= 0.0 && position <= 1.0 ? Float(position) : nil
    }

    /// Highest value of the "Zoom" slider: the deepest zoom any of the `maxZooms` serves (at least 1).
    public static func sliderMax(_ maxZooms: [Int]) -> Int {
        Swift.max(maxZooms.max() ?? 1, 1)
    }

    /// Where the slider starts: `extra` levels deeper than the current zoom, within `0...sliderMax`.
    public static func defaultMax(currentTileZoom: Int, extra: Int, sliderMax: Int) -> Int {
        Swift.min(Swift.max(currentTileZoom + extra, 0), sliderMax)
    }
}
