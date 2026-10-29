// Port of core/src/main/kotlin/fr/toolmap/core/maps/SaveCorridor.kt.

import Foundation

/// The band to save around a track or a route (P30.2, variant B of `docs/previews/p30-itineraires.html`): its `path`,
/// thinned for the planner, and the width kept on each side. The download takes the tiles of the band only
/// (`ZoneShape.Corridor`), not those of the rectangle around it.
public struct AreaCorridor: Equatable, Hashable, Sendable {
    /// The name of the track or the route, given to the download.
    public let name: String
    public let path: [ZoneShape.Point]
    public let radiusMeters: Int

    /// Throws `ToolMapError` for a path of less than two points or a radius that is not positive.
    public init(name: String, path: [ZoneShape.Point], radiusMeters: Int) throws {
        try require(path.count >= 2, "a band needs a line")
        try require(radiusMeters > 0, "radius \(radiusMeters)")
        self.name = name
        self.path = path
        self.radiusMeters = radiusMeters
    }

    public func toShape() -> ZoneShape.Corridor {
        ZoneShape.Corridor(points: path, radiusMeters: Double(radiusMeters))
    }

    /// The rectangle around the band (the line and its radius on every side): what the map is fitted to.
    public func bounds() throws -> AreaSelection {
        try bounds(radius: radiusMeters)
    }

    /// The rectangle around the line and `radius` meters on every side.
    public func bounds(radius: Int) throws -> AreaSelection {
        let perDegree = Angle.radians(1.0) * Geodesy.earthRadiusMeters
        let lats = path.map(\.lat)
        let lons = path.map(\.lon)
        let south = lats.min() ?? 0
        let north = lats.max() ?? 0
        let dLat = Double(radius) / perDegree
        // The widest degree of longitude of the band is at its latitude nearest the equator.
        let nearest: Double
        if south > 0 {
            nearest = south
        } else if north < 0 {
            nearest = -north
        } else {
            nearest = 0.0
        }
        let limited = nearest > AreaSelection.maxLatitude ? AreaSelection.maxLatitude : nearest
        let dLon = dLat / cos(Angle.radians(limited))
        return try AreaSelection(
            west: Swift.max((lons.min() ?? 0) - dLon, -AreaSelection.maxLongitude),
            south: Swift.max(south - dLat, -AreaSelection.maxLatitude),
            east: Swift.min((lons.max() ?? 0) + dLon, AreaSelection.maxLongitude),
            north: Swift.min(north + dLat, AreaSelection.maxLatitude)
        )
    }
}

/// The width of the band on each side, as its slider gives it: from 50 m to 5 km on a logarithmic scale, in steps of
/// 50 m up to 1 km and of 250 m above (variant B of `docs/previews/p30-itineraires.html`).
public enum CorridorWidth {
    public static let min = 50
    public static let max = 5_000
    /// Kotlin `DEFAULT`.
    public static let defaultWidth = 500
    private static let fineUpTo = 1_000
    private static let fineStep = 50
    private static let coarseStep = 250

    /// The width at `fraction` (0 to 1) of the slider.
    public static func fromFraction(_ fraction: Double) -> Int {
        let f = fraction < 0.0 ? 0.0 : (fraction > 1.0 ? 1.0 : fraction)
        return round(Double(min) * pow(Double(max) / Double(min), f))
    }

    /// Where the slider stands for `meters`.
    public static func toFraction(_ meters: Int) -> Double {
        log(Double(clamp(meters)) / Double(min)) / log(Double(max) / Double(min))
    }

    /// `meters` in the bounds, at its step.
    public static func round(_ meters: Double) -> Int {
        let step = meters <= Double(fineUpTo) ? fineStep : coarseStep
        return clamp(kotlinRound(meters / Double(step)) * step)
    }

    /// A width typed by the user: kept to the meter, within the bounds.
    public static func clamp(_ meters: Int) -> Int {
        Swift.min(Swift.max(meters, min), max)
    }
}
