// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintViewport.kt.

import Foundation

/// Where to put the camera: a centre and a zoom.
public struct CameraFit: Equatable, Hashable, Sendable {
    public let lat: Double
    public let lon: Double
    public let zoom: Double

    public init(lat: Double, lon: Double, zoom: Double) {
        self.lat = lat
        self.lon = lon
        self.zoom = zoom
    }
}

/// How big the page frame looks on the screen: the frame is drawn over the map at its true ground size.
public enum PrintViewport {
    private static let mmPerM = 1000.0

    /// Ground meters under the page frame at `scale`: millimeters on the paper times the scale denominator.
    public static func groundMeters(_ paperMm: Double, _ scale: Double) -> Double {
        paperMm / mmPerM * scale
    }

    /// The frame's size in dp for a map at `cameraZoom` centred on `latitude`, as (width, height).
    public static func frameDp(
        _ setup: PageSetup,
        _ scale: Double,
        _ latitude: Double,
        _ cameraZoom: Double
    ) -> (Double, Double) {
        let metersPerDp = ScaleBar.metersPerDp(latitude, cameraZoom)
        return (
            groundMeters(setup.frameWidthMm, scale) / metersPerDp,
            groundMeters(setup.frameHeightMm, scale) / metersPerDp
        )
    }

    /// The camera zoom at which the whole frame fits in `availableWidthDp` × `availableHeightDp` (the larger the
    /// better).
    public static func zoomToFit(
        _ setup: PageSetup,
        _ scale: Double,
        _ latitude: Double,
        _ availableWidthDp: Double,
        _ availableHeightDp: Double
    ) -> Double {
        let metersPerDp = Swift.max(
            groundMeters(setup.frameWidthMm, scale) / availableWidthDp,
            groundMeters(setup.frameHeightMm, scale) / availableHeightDp
        )
        return ScaleBar.cameraZoomFor(latitude, metersPerDp)
    }

    /// The camera that shows every point of `corners` (the pages of an atlas along a track, F9) in
    /// `availableWidthDp` × `availableHeightDp`, north up; nil without points.
    public static func fit(_ corners: [LonLat], _ availableWidthDp: Double, _ availableHeightDp: Double) -> CameraFit? {
        if corners.isEmpty { return nil }
        let xs = corners.map { TileMath.mercatorX($0.lon) }
        let ys = corners.map { TileMath.mercatorY($0.lat) }
        let minX = xs.min() ?? 0
        let maxX = xs.max() ?? 0
        let minY = ys.min() ?? 0
        let maxY = ys.max() ?? 0
        let centerX = (minX + maxX) / 2
        let centerY = (minY + maxY) / 2
        let lat = Angle.degrees(2 * atan(exp(centerY / TileMath.earthRadiusM)) - Double.pi / 2)
        let lon = Angle.degrees(centerX / TileMath.earthRadiusM)
        // Mercator meters times the cosine of the latitude are ground meters there, the unit of metersPerDp.
        let cosine = cos(Angle.radians(lat))
        let metersPerDp = Swift.max(
            Swift.max(maxX - minX, 1.0) * cosine / availableWidthDp,
            Swift.max(maxY - minY, 1.0) * cosine / availableHeightDp
        )
        return CameraFit(lat: lat, lon: lon, zoom: ScaleBar.cameraZoomFor(lat, metersPerDp))
    }
}
