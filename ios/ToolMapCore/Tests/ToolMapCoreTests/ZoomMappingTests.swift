// Port of core/src/test/kotlin/fr/toolmap/core/geo/ZoomMappingTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct ZoomMappingTests {
    /// samsung SM-S931U of the user (1080 px wide, 450 dpi): 2.8125 physical pixels per dp.
    private let phoneDensity = 2.8125

    /// MapLibre's rule for a raster source: tiles of zoom `round(camera + log2(512 / tileSize))`.
    private func mapLibreTileZoom(_ cameraZoom: Double, _ declaredTileSize: Int) -> Int {
        kotlinRound(cameraZoom + log(512.0 / Double(declaredTileSize)) / log(2.0))
    }

    /// Screen pixels covered by one pixel of a tile of `tilePixels` px and zoom `tileZoom` (a 256 px zoom-z tile spans
    /// the same ground as a 512 px zoom-(z−1) tile) at `cameraZoom`: the world is 512 · 2^camera dp wide.
    private func screenPixelsPerTilePixel(_ cameraZoom: Double, _ tileZoom: Int, _ tilePixels: Int, _ density: Double) -> Double {
        512.0 * pow(2.0, cameraZoom - Double(tileZoom)) * density / Double(tilePixels)
    }

    @Test("MapLibre's own tile size keeps the zoom shown so far")
    func standard() {
        let m = ZoomMapping.standard
        #expect(m.offset == 1.0)
        #expect(m.declaredTileSize(256) == 256)
        #expect(m.declaredTileSize(512) == 512)
        #expect(m.requestedTileZoom(11.6) == 13)
    }

    @Test("the blurry view of the user's screenshot gets AlpineQuest's tiles")
    func screenshot() {
        // ToolMap showed "z 12.6": camera 11.6. MapLibre drew zoom-13 tiles, one tile pixel on 2.1 screen pixels.
        let camera = 11.6
        let before = ZoomMapping.standard
        let oldZoom = mapLibreTileZoom(camera, before.declaredTileSize(256))
        #expect(oldZoom == 13)
        #expect(abs(screenPixelsPerTilePixel(camera, oldZoom, 256, phoneDensity) - 2.13) <= 0.01)

        // AlpineQuest showed the same place with zoom-14 tiles ("14/18"): so does ToolMap now, at ~1:1.
        let sharp = ZoomMapping.forScreen(phoneDensity, TileSharpness.sharp)
        #expect(sharp.declaredTileSize(256) == 91)
        let newZoom = mapLibreTileZoom(camera, sharp.declaredTileSize(256))
        #expect(newZoom == 14)
        #expect(sharp.requestedTileZoom(camera) == newZoom)
        #expect(abs(screenPixelsPerTilePixel(camera, newZoom, 256, phoneDensity) - 1.07) <= 0.01)
        // The zoom shown to the user is the one of the tiles on screen, like AlpineQuest's.
        #expect(abs(sharp.toTileZoom(camera) - 14.09) <= 0.01)
    }

    @Test("a tile pixel covers the chosen number of screen pixels at every zoom and density")
    func everyZoomAndDensity() {
        let densities = [1.0, 1.5, 2.0, 2.625, 2.75, phoneDensity, 3.0, 3.5, 4.0]
        // MapLibre rounds to the nearest zoom: up to half a level either way, ×/÷ √2, plus the integer tile size.
        let low = 1 / 2.0.squareRoot() * 0.99
        let high = 2.0.squareRoot() * 1.01
        var failures: [String] = []
        for density in densities {
            for magnification in TileSharpness.choices(density) {
                let mapping = ZoomMapping.forScreen(density, magnification)
                let target = magnification == TileSharpness.androidSize ? density : magnification
                for pixels in [256, 512] {
                    var camera = 0.0
                    while camera <= 20.0 {
                        let zoom = mapLibreTileZoom(camera, mapping.declaredTileSize(pixels))
                        let ratio = screenPixelsPerTilePixel(camera, zoom, pixels, density) / target
                        if !(ratio >= low && ratio <= high) {
                            failures.append("density \(density), ×\(magnification), \(pixels) px, camera \(camera): ratio \(ratio)")
                        }
                        camera += 0.05
                    }
                }
            }
        }
        #expect(failures.isEmpty, "\(failures.prefix(5))")
    }

    @Test("the zoom shown is the zoom of the 256 px tiles MapLibre requests")
    func zoomShown() {
        var failures: [String] = []
        for mapping in [ZoomMapping.standard, ZoomMapping.forScreen(phoneDensity, 1.0)] {
            var camera = 0.0
            while camera <= 20.0 {
                if mapLibreTileZoom(camera, mapping.declaredTileSize(256)) != mapping.requestedTileZoom(camera) {
                    failures.append("\(mapping) at \(camera)")
                }
                if abs(mapping.toCameraZoom(mapping.toTileZoom(camera)) - camera) > 1e-9 {
                    failures.append("\(mapping) round trip at \(camera)")
                }
                camera += 0.05
            }
        }
        #expect(failures.isEmpty, "\(failures.prefix(5))")
    }

    @Test("unusable values fall back to MapLibre's size")
    func fallback() {
        #expect(ZoomMapping.forScreen(phoneDensity, TileSharpness.androidSize) == ZoomMapping.standard)
        #expect(ZoomMapping.forScreen(phoneDensity, phoneDensity) == ZoomMapping.standard)
        #expect(ZoomMapping.forScreen(phoneDensity, 10.0) == ZoomMapping.standard)
        #expect(ZoomMapping.forScreen(phoneDensity, -1.0) == ZoomMapping.standard)
        #expect(ZoomMapping.forScreen(phoneDensity, Double.nan) == ZoomMapping.standard)
        #expect(ZoomMapping.forScreen(Double.nan, 1.0) == ZoomMapping.standard)
        #expect(ZoomMapping.forScreen(1.0, 1.0) == ZoomMapping.standard)
        // Absurd densities stay within what MapLibre accepts.
        #expect(ZoomMapping.forScreen(1e6, 1.0).declaredTileSize(256) == 16)
    }

    @Test("the settings offer the enlargements below the screen density")
    func settingsChoices() {
        #expect(TileSharpness.choices(phoneDensity) == [1.0, 1.5, 2.0, TileSharpness.androidSize])
        #expect(TileSharpness.choices(1.75) == [1.0, 1.5, TileSharpness.androidSize])
        #expect(TileSharpness.choices(1.5) == [1.0, TileSharpness.androidSize])
        #expect(TileSharpness.choices(1.0) == [1.0])
    }
}
