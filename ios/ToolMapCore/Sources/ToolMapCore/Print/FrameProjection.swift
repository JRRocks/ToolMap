// Port of core/src/main/kotlin/fr/toolmap/core/print/FrameProjection.kt.

import Foundation

/// A point of the map frame, in millimeters from its top-left corner (x to the right, y downwards).
public struct FramePoint: Equatable, Hashable, Sendable {
    public let xMm: Double
    public let yMm: Double

    public init(xMm: Double, yMm: Double) {
        self.xMm = xMm
        self.yMm = yMm
    }
}

/// Where things fall in the printed map frame: a `frameWidthMm` × `frameHeightMm` rectangle centred on (`centerLat`,
/// `centerLon`), north up, drawn at 1:`scale`, exact at the centre latitude. The frame is a plain rectangle of the Web
/// Mercator plane, which is what the tiles are, so a tile maps to the paper without distortion.
public struct FrameProjection: Equatable, Sendable {
    public let centerLat: Double
    public let centerLon: Double
    public let scale: Double
    public let frameWidthMm: Double
    public let frameHeightMm: Double

    /// Mercator meters of the plane under one millimeter of paper.
    public let mercatorPerMm: Double
    public let centerX: Double
    public let centerY: Double

    /// The frame in Mercator meters (EPSG:3857).
    public let mercatorBounds: Bounds

    /// Throws `ToolMapError.invalidArgument` for an empty frame or a scale that is not positive.
    public init(
        _ centerLat: Double,
        _ centerLon: Double,
        _ scale: Double,
        _ frameWidthMm: Double,
        _ frameHeightMm: Double
    ) throws {
        try require(scale > 0 && frameWidthMm > 0 && frameHeightMm > 0, "empty frame")
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.scale = scale
        self.frameWidthMm = frameWidthMm
        self.frameHeightMm = frameHeightMm
        let perMm = PrintMath.groundMeters(1.0, scale) * PrintMath.mercatorFactor(centerLat)
        let x = TileMath.mercatorX(centerLon)
        let y = TileMath.mercatorY(centerLat)
        mercatorPerMm = perMm
        centerX = x
        centerY = y
        mercatorBounds = Bounds(
            minX: x - frameWidthMm / 2 * perMm,
            minY: y - frameHeightMm / 2 * perMm,
            maxX: x + frameWidthMm / 2 * perMm,
            maxY: y + frameHeightMm / 2 * perMm
        )
    }

    /// The frame as a box of degrees (the box that contains the frame; the frame is a bit narrower at its top).
    public var lonLatBounds: Bounds {
        let topLeft = FrameProjection.unproject(mercatorBounds.minX, mercatorBounds.maxY)
        let bottomRight = FrameProjection.unproject(mercatorBounds.maxX, mercatorBounds.minY)
        return Bounds(minX: topLeft.lon, minY: bottomRight.lat, maxX: bottomRight.lon, maxY: topLeft.lat)
    }

    public func toFrame(_ lon: Double, _ lat: Double) -> FramePoint {
        FramePoint(
            xMm: frameWidthMm / 2 + (TileMath.mercatorX(lon) - centerX) / mercatorPerMm,
            yMm: frameHeightMm / 2 - (TileMath.mercatorY(lat) - centerY) / mercatorPerMm
        )
    }

    public func toLonLat(_ xMm: Double, _ yMm: Double) -> LonLat {
        FrameProjection.unproject(
            centerX + (xMm - frameWidthMm / 2) * mercatorPerMm,
            centerY - (yMm - frameHeightMm / 2) * mercatorPerMm
        )
    }

    /// True when the point falls inside the frame.
    public func contains(_ point: FramePoint) -> Bool {
        point.xMm >= 0 && point.xMm <= frameWidthMm && point.yMm >= 0 && point.yMm <= frameHeightMm
    }

    static func unproject(_ mx: Double, _ my: Double) -> LonLat {
        let lon = Angle.degrees(mx / TileMath.earthRadiusM)
        let lat = Angle.degrees(2 * atan(exp(my / TileMath.earthRadiusM)) - Double.pi / 2)
        return LonLat(lon: lon, lat: lat)
    }
}
