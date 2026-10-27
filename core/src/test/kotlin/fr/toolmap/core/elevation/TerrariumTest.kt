package fr.toolmap.core.elevation

import fr.toolmap.core.tiles.TileMath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerrariumTest {
    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    /** A 256 px tile whose elevation is `100 + 2 * x + 0.5 * y` metres, Terrarium-encoded. */
    private fun rampTile(type: Int = BufferedImage.TYPE_INT_RGB): ByteArray {
        val image = BufferedImage(256, 256, type)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val v = Math.round((100.0 + 2 * x + 0.5 * y + 32768.0) * 256).toInt()
                val rgb = ((v shr 16) and 0xFF shl 16) or ((v shr 8) and 0xFF shl 8) or (v and 0xFF)
                image.setRGB(x, y, 0xFF000000.toInt() or rgb)
            }
        }
        return png(image)
    }

    @Test
    fun `Terrarium formula`() {
        assertEquals(0.0, TerrariumTile.decodeElevation(128, 0, 0))
        assertEquals(-32768.0, TerrariumTile.decodeElevation(0, 0, 0))
        assertEquals(1000.5, TerrariumTile.decodeElevation(131, 232, 128), 1e-9)
        assertEquals(-10.0, TerrariumTile.decodeElevation(127, 246, 0), 1e-9)
    }

    @Test
    fun `RGB and RGBA PNG tiles decode to the same elevations`() {
        for (type in listOf(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR)) {
            val tile = TerrariumTile.fromPng(rampTile(type))
            assertEquals(256, tile.size)
            assertEquals(100.0, tile.pixel(0, 0), 0.01)
            assertEquals(100.0 + 2 * 255 + 0.5 * 255, tile.pixel(255, 255), 0.01)
            assertEquals(100.0 + 2 * 10 + 0.5 * 20, tile.pixel(10, 20), 0.01)
        }
    }

    @Test
    fun `palette and grey images are read`() {
        val indexed = BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED)
        indexed.setRGB(3, 4, 0xFF123456.toInt())
        val image = PngDecoder.decode(png(indexed))
        assertEquals(3, image.channels)
        assertEquals(8, image.width)
        val grey = BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY)
        grey.raster.setSample(1, 2, 0, 77)
        val g = PngDecoder.decode(png(grey))
        assertEquals(1, g.channels)
        assertEquals(77, g.sample(1, 2, 0))
    }

    @Test
    fun `bilinear sampling between pixel centres`() {
        val tile = TerrariumTile.fromPng(rampTile())
        // Centre of pixel (10, 20) is at fraction ((10 + .5) / 256, (20 + .5) / 256).
        assertEquals(130.0, tile.elevationAt(10.5 / 256, 20.5 / 256), 0.01)
        // Halfway between pixels 10 and 11 along x: one more metre.
        assertEquals(131.0, tile.elevationAt(11.0 / 256, 20.5 / 256), 0.01)
        // Beyond the edge the border pixel is repeated.
        assertEquals(tile.pixel(0, 0), tile.elevationAt(0.0, 0.0), 0.6)
    }

    @Test
    fun `position to tile fraction agrees with the tile maths`() {
        val zoom = 12
        val tile = TileMath.tileAt(6.865, 45.8326, zoom)
        val (fx, fy) = TerrariumTile.fractionIn(6.865, 45.8326, zoom, tile.x, tile.y)
        assertEquals(true, fx in 0.0..1.0 && fy in 0.0..1.0, "$fx,$fy")
        // The tile's own north-west corner is fraction (0, 0).
        val b = TileMath.boundsLonLat(tile.x, tile.y, zoom)
        val (cx, cy) = TerrariumTile.fractionIn(b.minX, b.maxY, zoom, tile.x, tile.y)
        assertEquals(0.0, cx, 1e-9)
        assertEquals(0.0, cy, 1e-9)
    }

    @Test
    fun `bad images are refused`() {
        assertFailsWith<PngException> { PngDecoder.decode(ByteArray(0)) }
        assertFailsWith<PngException> { PngDecoder.decode("GIF89a".toByteArray()) }
        val good = rampTile()
        assertFailsWith<PngException> { PngDecoder.decode(good.copyOf(good.size / 2)) }
        val tall =
            ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(4, 8, BufferedImage.TYPE_INT_RGB), "png", it)
            }
        assertFailsWith<PngException> { TerrariumTile.fromPng(tall.toByteArray()) }
    }
}
