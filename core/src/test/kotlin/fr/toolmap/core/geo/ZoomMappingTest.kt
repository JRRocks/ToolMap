package fr.toolmap.core.geo

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoomMappingTest {
    /** samsung SM-S931U of the user (1080 px wide, 450 dpi): 2.8125 physical pixels per dp. */
    private val phoneDensity = 2.8125

    /** MapLibre's rule for a raster source: tiles of zoom `round(camera + log2(512 / tileSize))`. */
    private fun mapLibreTileZoom(
        cameraZoom: Double,
        declaredTileSize: Int,
    ): Int = Math.round(cameraZoom + ln(512.0 / declaredTileSize) / ln(2.0)).toInt()

    /**
     * Screen pixels covered by one pixel of a tile of [tilePixels] px and zoom [tileZoom] (a 256 px zoom-z tile
     * spans the same ground as a 512 px zoom-(z−1) tile) at [cameraZoom]: the world is 512 · 2^camera dp wide.
     */
    private fun screenPixelsPerTilePixel(
        cameraZoom: Double,
        tileZoom: Int,
        tilePixels: Int,
        density: Double,
    ): Double = 512.0 * 2.0.pow(cameraZoom - tileZoom) * density / tilePixels

    @Test
    fun `MapLibre's own tile size keeps the zoom shown so far`() {
        val m = ZoomMapping.STANDARD
        assertEquals(1.0, m.offset)
        assertEquals(256, m.declaredTileSize(256))
        assertEquals(512, m.declaredTileSize(512))
        assertEquals(13, m.requestedTileZoom(11.6))
    }

    @Test
    fun `the blurry view of the user's screenshot gets AlpineQuest's tiles`() {
        // ToolMap showed "z 12.6": camera 11.6. MapLibre drew zoom-13 tiles, one tile pixel on 2.1 screen pixels.
        val camera = 11.6
        val before = ZoomMapping.STANDARD
        val oldZoom = mapLibreTileZoom(camera, before.declaredTileSize(256))
        assertEquals(13, oldZoom)
        assertEquals(2.13, screenPixelsPerTilePixel(camera, oldZoom, 256, phoneDensity), 0.01)

        // AlpineQuest showed the same place with zoom-14 tiles ("14/18"): so does ToolMap now, at ~1:1.
        val sharp = ZoomMapping.forScreen(phoneDensity, TileSharpness.SHARP)
        assertEquals(91, sharp.declaredTileSize(256))
        val newZoom = mapLibreTileZoom(camera, sharp.declaredTileSize(256))
        assertEquals(14, newZoom)
        assertEquals(newZoom, sharp.requestedTileZoom(camera))
        assertEquals(1.07, screenPixelsPerTilePixel(camera, newZoom, 256, phoneDensity), 0.01)
        // The zoom shown to the user is the one of the tiles on screen, like AlpineQuest's.
        assertEquals(14.09, sharp.toTileZoom(camera), 0.01)
    }

    @Test
    fun `a tile pixel covers the chosen number of screen pixels at every zoom and density`() {
        val densities = listOf(1.0, 1.5, 2.0, 2.625, 2.75, phoneDensity, 3.0, 3.5, 4.0)
        // MapLibre rounds to the nearest zoom: up to half a level either way, ×/÷ √2, plus the integer tile size.
        val low = 1 / sqrt(2.0) * 0.99
        val high = sqrt(2.0) * 1.01
        for (density in densities) {
            for (magnification in TileSharpness.choices(density)) {
                val mapping = ZoomMapping.forScreen(density, magnification)
                val target = if (magnification == TileSharpness.ANDROID_SIZE) density else magnification
                for (pixels in listOf(256, 512)) {
                    var camera = 0.0
                    while (camera <= 20.0) {
                        val zoom = mapLibreTileZoom(camera, mapping.declaredTileSize(pixels))
                        val ratio = screenPixelsPerTilePixel(camera, zoom, pixels, density) / target
                        assertTrue(
                            ratio in low..high,
                            "density $density, ×$magnification, $pixels px, camera $camera: ratio $ratio",
                        )
                        camera += 0.05
                    }
                }
            }
        }
    }

    @Test
    fun `the zoom shown is the zoom of the 256 px tiles MapLibre requests`() {
        for (mapping in listOf(ZoomMapping.STANDARD, ZoomMapping.forScreen(phoneDensity, 1.0))) {
            var camera = 0.0
            while (camera <= 20.0) {
                assertEquals(mapLibreTileZoom(camera, mapping.declaredTileSize(256)), mapping.requestedTileZoom(camera))
                assertEquals(camera, mapping.toCameraZoom(mapping.toTileZoom(camera)), 1e-9)
                camera += 0.05
            }
        }
    }

    @Test
    fun `unusable values fall back to MapLibre's size`() {
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(phoneDensity, TileSharpness.ANDROID_SIZE))
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(phoneDensity, phoneDensity))
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(phoneDensity, 10.0))
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(phoneDensity, -1.0))
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(phoneDensity, Double.NaN))
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(Double.NaN, 1.0))
        assertEquals(ZoomMapping.STANDARD, ZoomMapping.forScreen(1.0, 1.0))
        // Absurd densities stay within what MapLibre accepts.
        assertEquals(16, ZoomMapping.forScreen(1e6, 1.0).declaredTileSize(256))
    }

    @Test
    fun `the settings offer the enlargements below the screen density`() {
        assertEquals(listOf(1.0, 1.5, 2.0, TileSharpness.ANDROID_SIZE), TileSharpness.choices(phoneDensity))
        assertEquals(listOf(1.0, 1.5, TileSharpness.ANDROID_SIZE), TileSharpness.choices(1.75))
        assertEquals(listOf(1.0, TileSharpness.ANDROID_SIZE), TileSharpness.choices(1.5))
        assertEquals(listOf(1.0), TileSharpness.choices(1.0))
    }
}
