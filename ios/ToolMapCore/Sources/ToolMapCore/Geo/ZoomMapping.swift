// Port of core/src/main/kotlin/fr/toolmap/core/geo/ZoomMapping.kt (ZoomMapping and TileSharpness).

import Foundation

/// Relation between the MapLibre camera zoom (512 dp world at zoom 0) and the zoom shown to the user, which is the
/// `z` of the 256 px tiles MapLibre draws at that camera (what AlpineQuest displays, "14/18").
///
/// MapLibre picks the raster tiles of zoom `round(camera + log2(512 / tileSize))`, `tileSize` being the size given to
/// the raster source, and draws each tile on `tileSize` dp. Declared at its real 256 px, a tile pixel covers `density`
/// screen pixels (≈ 2.8 on a recent phone): the map is one or two zoom levels blurrier than AlpineQuest, which draws a
/// tile pixel on about one screen pixel. Declaring `256 / tileScale` instead makes MapLibre take the tiles
/// `log2(tileScale)` levels deeper and draw them that much smaller (ADR-032).
///
/// `tileScale`: how many times smaller than MapLibre's default a tile is drawn; 1 = MapLibre's default.
public struct ZoomMapping: Equatable, Hashable, Sendable, CustomStringConvertible {
    private static let cameraTileSize = 512.0
    private static let standardTilePixels = 256

    /// Smallest size declared to MapLibre (tileScale 16): far beyond any screen density.
    private static let minDeclared = 16

    /// MapLibre's own size: a tile pixel on one dp, displayed zoom = camera + 1 (behaviour before ADR-032).
    public static let standard = ZoomMapping(1.0)

    public let tileScale: Double

    /// Size declared to MapLibre for the tiles of 256 px: the whole mapping derives from this integer.
    private let declared256: Int

    /// Displayed zoom − camera zoom.
    public let offset: Double

    private init(_ tileScale: Double) {
        self.tileScale = tileScale
        let declared = kotlinRound(Double(ZoomMapping.standardTilePixels) / tileScale)
        let clamped = Swift.min(Swift.max(declared, ZoomMapping.minDeclared), ZoomMapping.standardTilePixels)
        declared256 = clamped
        offset = ZoomMapping.log2(ZoomMapping.cameraTileSize / Double(clamped))
    }

    public func toTileZoom(_ cameraZoom: Double) -> Double {
        cameraZoom + offset
    }

    public func toCameraZoom(_ tileZoom: Double) -> Double {
        tileZoom - offset
    }

    /// The tile zoom MapLibre requests at `cameraZoom` (it rounds for raster sources), for 256 px tiles.
    public func requestedTileZoom(_ cameraZoom: Double) -> Int {
        kotlinToInt(Double(kotlinRound(toTileZoom(cameraZoom))))
    }

    /// The `tileSize` to give MapLibre for a raster source whose tiles are `pixels` px wide (256, 512…).
    public func declaredTileSize(_ pixels: Int) -> Int {
        if pixels == ZoomMapping.standardTilePixels { return declared256 }
        return Swift.max(ZoomMapping.minDeclared, kotlinRound(Double(pixels) / tileScale))
    }

    public static func == (lhs: ZoomMapping, rhs: ZoomMapping) -> Bool {
        lhs.tileScale == rhs.tileScale
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(tileScale)
    }

    public var description: String { "ZoomMapping(tileScale=\(tileScale), tileSize=\(declared256))" }

    /// The mapping for a screen of `density` (physical pixels per dp) where a tile pixel should cover `magnification`
    /// screen pixels: 1 = AlpineQuest's sharpness, `density` or more = MapLibre's default size.
    public static func forScreen(_ density: Double, _ magnification: Double) -> ZoomMapping {
        if !(density > 0.0) || !(magnification > 0.0) { return standard }
        return ZoomMapping(Swift.max(1.0, density / magnification))
    }

    private static func log2(_ value: Double) -> Double {
        log(value) / log(2.0)
    }
}

/// The choices of the "map sharpness" setting: screen pixels covered by one tile pixel (ADR-032).
public enum TileSharpness {
    /// One tile pixel on one screen pixel, as AlpineQuest draws its maps.
    public static let sharp = 1.0

    /// MapLibre's default size (one tile pixel per dp), the only behaviour before ADR-032.
    public static let androidSize = 0.0

    private static let magnifications: [Double] = [sharp, 1.5, 2.0]

    /// What the settings offer on a screen of `density`: the enlargements smaller than it, then MapLibre's size (which
    /// is `sharp` itself on a screen of density 1).
    public static func choices(_ density: Double) -> [Double] {
        let enlargements = magnifications.filter { $0 == sharp || $0 < density }
        return density > sharp ? enlargements + [androidSize] : enlargements
    }
}
