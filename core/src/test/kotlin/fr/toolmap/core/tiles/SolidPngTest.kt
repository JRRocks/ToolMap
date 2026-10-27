package fr.toolmap.core.tiles

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SolidPngTest {
    private fun decode(bytes: ByteArray) = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

    @Test
    fun `a flat tile decodes to the requested size and colour with alpha`() {
        val png = SolidPng.create(256, 0x8000AA00.toInt())
        val image = decode(png)
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        for ((x, y) in listOf(0 to 0, 255 to 255, 100 to 37)) {
            assertEquals(0x8000AA00.toInt(), image.getRGB(x, y), "pixel $x,$y")
        }
        assertTrue(png.size < 2_000, "a flat tile must stay tiny, was ${png.size} bytes")
    }

    @Test
    fun `the frame is drawn on the edges only`() {
        val image = decode(SolidPng.create(64, 0x4000FF00, frame = 0xFF006400.toInt(), frameWidth = 2))
        assertEquals(0xFF006400.toInt(), image.getRGB(0, 30))
        assertEquals(0xFF006400.toInt(), image.getRGB(1, 30))
        assertEquals(0xFF006400.toInt(), image.getRGB(63, 63))
        assertEquals(0xFF006400.toInt(), image.getRGB(30, 62))
        assertEquals(0x4000FF00, image.getRGB(2, 30))
        assertEquals(0x4000FF00, image.getRGB(30, 30))
    }

    @Test
    fun `invalid sizes are refused`() {
        assertFailsWith<IllegalArgumentException> { SolidPng.create(0, 0) }
        assertFailsWith<IllegalArgumentException> { SolidPng.create(5000, 0) }
    }
}
