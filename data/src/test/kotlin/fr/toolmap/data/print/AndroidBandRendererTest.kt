package fr.toolmap.data.print

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.print.PrintArea
import fr.toolmap.core.print.PrintPlanner
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.TileId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class AndroidBandRendererTest {
    private val sources = BuiltinSources.load().associateBy { it.id }

    private fun solidTile(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val plan =
        PrintPlanner.plan(
            PrintTemplate(scale = 50_000.0, dpi = PrintTemplate.MIN_DPI),
            PrintArea(45.0, 6.0),
            listOf(LayerConfig("l1", "ign-maps")),
            sources::get,
        )

    /** Tiles by column: red, green, blue in turn; every fourth column is not available; every fifth is empty. */
    private val fetcher =
        PrintTileFetcher { _: TileSource, tile: TileId ->
            when {
                tile.x % 4 == 0 -> TileFetch.Unavailable
                tile.x % 5 == 0 -> TileFetch.Empty
                else -> TileFetch.Found(solidTile(listOf(Color.RED, Color.GREEN, Color.BLUE)[tile.x % 3]))
            }
        }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun near(
        actual: Int,
        expected: Int,
        tolerance: Int = 40,
    ) = abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
        abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
        abs(Color.blue(actual) - Color.blue(expected)) <= tolerance

    @Test
    fun `every tile lands where the plan puts it, missing ones are hatched and counted`() {
        assertTrue(plan.canExport, plan.issues.toString())
        val page = plan.pages.single()
        AndroidBandRenderer(fetcher).use { renderer ->
            val band = page.raster.band(0)
            val rendered = renderer.render(page, band)
            assertEquals(page.raster.widthPx, rendered.image.widthPx)
            assertEquals(band.heightPx, rendered.image.heightPx)
            assertTrue(rendered.image.jpeg)
            val image = decode(rendered.image.data)
            assertEquals(page.raster.widthPx, image.width)
            assertEquals(band.heightPx, image.height)

            val placements = page.raster.placements(page.layers.layers.single().tileZoom, band)
            var unavailable = 0
            for (p in placements) {
                // The middle of the part of the tile that is inside the image.
                val cx = ((p.left + p.right) / 2).coerceIn(0, image.width - 1)
                val cy = ((p.top + p.bottom) / 2).coerceIn(0, image.height - 1)
                val pixel = image.getPixel(cx, cy)
                when {
                    p.tile.x % 4 == 0 -> {
                        unavailable++
                        val grey = abs(Color.red(pixel) - Color.green(pixel)) < 12 && Color.red(pixel) in 150..255
                        assertTrue(grey, "hatch expected at ${p.tile}, got #${Integer.toHexString(pixel)}")
                    }

                    p.tile.x % 5 == 0 -> {
                        assertTrue(near(pixel, Color.WHITE), "empty tile ${p.tile} should stay white")
                    }

                    else -> {
                        val expected = listOf(Color.RED, Color.GREEN, Color.BLUE)[p.tile.x % 3]
                        assertTrue(near(pixel, expected), "tile ${p.tile}: #${Integer.toHexString(pixel)}")
                    }
                }
            }
            assertTrue(unavailable > 0, "the test needs at least one unavailable tile")
            assertEquals(unavailable, rendered.missingTiles)
        }
    }

    @Test
    fun `an undecodable tile counts as missing`() {
        val page = plan.pages.single()
        val broken = PrintTileFetcher { _, _ -> TileFetch.Found(byteArrayOf(1, 2, 3)) }
        AndroidBandRenderer(broken).use { renderer ->
            val band = page.raster.band(0)
            val rendered = renderer.render(page, band)
            assertEquals(page.raster.placements(page.layers.layers.single().tileZoom, band).size, rendered.missingTiles)
        }
    }
}
