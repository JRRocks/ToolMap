package fr.toolmap.data.altitude

import fr.toolmap.core.elevation.TerrariumTile
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.data.elevation.ElevationTiles
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TerrariumElevationTest {
    /** A 256 px Terrarium tile whose elevation is `base + 2 * x` metres. */
    private fun rampTile(base: Double): ByteArray {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val v = Math.round((base + 2 * x + 32768.0) * 256).toInt()
                image.setRGB(x, y, ((v shr 16) and 0xFF shl 16) or ((v shr 8) and 0xFF shl 8) or (v and 0xFF))
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private class Tiles(
        private val png: ByteArray?,
    ) : ElevationTiles {
        val requested = ArrayList<Triple<Int, Int, Int>>()

        override suspend fun tile(
            z: Int,
            x: Int,
            y: Int,
        ): ByteArray? {
            requested += Triple(z, x, y)
            return png
        }
    }

    @Test
    fun `the elevation is read from the tile that holds the position`() =
        runBlocking {
            val tiles = Tiles(rampTile(1000.0))
            val elevation = TerrariumElevation(tiles, zoom = 13)
            val tile = TileMath.tileAt(6.0, 45.0, 13)
            val (fx, _) = TerrariumTile.fractionIn(6.0, 45.0, 13, tile.x, tile.y)

            val metres = assertNotNull(elevation.at(45.0, 6.0))

            assertEquals(1000.0 + 2 * (fx * 256 - 0.5), metres, 0.5)
            assertEquals(listOf(Triple(13, tile.x, tile.y)), tiles.requested)
        }

    @Test
    fun `a tile is asked for once while the walk stays inside it`() =
        runBlocking {
            val tiles = Tiles(rampTile(0.0))
            val elevation = TerrariumElevation(tiles, zoom = 13)
            repeat(5) { elevation.at(45.0 + it * 0.0001, 6.0) }
            assertEquals(1, tiles.requested.size)
            // Another tile far away is another request, and the first one is still remembered.
            elevation.at(46.0, 7.0)
            elevation.at(45.0, 6.0)
            assertEquals(2, tiles.requested.size)
        }

    @Test
    fun `no tile or an unreadable one gives no elevation and is asked again`() =
        runBlocking {
            val missing = Tiles(null)
            assertNull(TerrariumElevation(missing).at(45.0, 6.0))
            val broken = Tiles(byteArrayOf(1, 2, 3, 4))
            val elevation = TerrariumElevation(broken)
            assertNull(elevation.at(45.0, 6.0))
            assertNull(elevation.at(45.0, 6.0))
            assertEquals(2, broken.requested.size, "a failure is not cached")
        }
}
