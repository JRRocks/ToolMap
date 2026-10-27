package fr.toolmap.data.tiles

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileRecipe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class BitmapTileComposerTest {
    private val level = LevelDef(ZoomSet.range(0, 18), listOf("https://t/{\$z}/{\$x}/{\$y}.png"))
    private val source = TileSource(id = "s", name = "s", levels = listOf(level))
    private val composer = BitmapTileComposer()

    private fun direct(
        z: Int,
        x: Int,
        y: Int,
    ) = TileRecipe.Direct(source, level, TileId(z, x, y))

    private fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun solid(color: Int): ByteArray =
        png(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })

    /** A tile whose quarters are red, green (top) and blue, yellow (bottom). */
    private fun quarters(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, color ->
            paint.color = color
            val left = (i % 2) * 128f
            val top = (i / 2) * 128f
            canvas.drawRect(left, top, left + 128f, top + 128f, paint)
        }
        return png(bitmap)
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun assertColor(
        expected: Int,
        actual: Int,
    ) {
        val close =
            abs(Color.red(actual) - Color.red(expected)) <= TOLERANCE &&
                abs(Color.green(actual) - Color.green(expected)) <= TOLERANCE &&
                abs(Color.blue(actual) - Color.blue(expected)) <= TOLERANCE &&
                abs(Color.alpha(actual) - Color.alpha(expected)) <= TOLERANCE
        assertTrue(close, "expected #${Integer.toHexString(expected)}, got #${Integer.toHexString(actual)}")
    }

    @Test
    fun `an enlarged tile shows the right part of the coarser tile`() {
        val coarse = quarters()
        // Top right quarter of the coarse tile: green everywhere.
        val bytes = assertNotNull(composer.compose(TileRecipe.Enlarge(direct(4, 1, 1), 1, 1, 0)) { coarse })
        val image = decode(bytes)
        assertEquals(256, image.width)
        assertEquals(256, image.height)
        for ((x, y) in listOf(10 to 10, 128 to 128, 245 to 245)) assertColor(Color.GREEN, image.getPixel(x, y))
        // Bottom left quarter: blue.
        val blue = decode(assertNotNull(composer.compose(TileRecipe.Enlarge(direct(4, 1, 1), 1, 0, 1)) { coarse }))
        assertColor(Color.BLUE, blue.getPixel(128, 128))
    }

    @Test
    fun `shrunk tiles are drawn in their cell and a missing one stays transparent`() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val parts = listOf(direct(5, 2, 2), direct(5, 3, 2), direct(5, 2, 3), direct(5, 3, 3))
        val bytes =
            assertNotNull(
                composer.compose(TileRecipe.Shrink(parts, 1)) { leaf ->
                    colors.getOrNull(parts.indexOf(leaf))?.let(::solid)
                },
            )
        val image = decode(bytes)
        assertColor(Color.RED, image.getPixel(64, 64))
        assertColor(Color.GREEN, image.getPixel(192, 64))
        assertColor(Color.BLUE, image.getPixel(64, 192))
        assertEquals(0, Color.alpha(image.getPixel(192, 192)))
    }

    @Test
    fun `two levels of shrinking keep each tile in its place`() {
        val parts = (0 until 16).map { direct(6, 8 + it % 4, 8 + it / 4) }
        val bytes =
            assertNotNull(
                composer.compose(TileRecipe.Shrink(parts, 2)) { leaf ->
                    solid(if (parts.indexOf(leaf) == 5) Color.RED else Color.WHITE)
                },
            )
        val image = decode(bytes)
        // Index 5 is column 1, row 1 of the 4 × 4 grid: pixels 64..127.
        assertColor(Color.RED, image.getPixel(96, 96))
        assertColor(Color.WHITE, image.getPixel(32, 32))
        assertColor(Color.WHITE, image.getPixel(160, 96))
    }

    @Test
    fun `nothing to draw gives no tile`() {
        assertNull(composer.compose(TileRecipe.None) { solid(Color.RED) })
        val parts = listOf(direct(5, 0, 0), direct(5, 1, 0), direct(5, 0, 1), direct(5, 1, 1))
        assertNull(composer.compose(TileRecipe.Shrink(parts, 1)) { null })
        assertNull(composer.compose(TileRecipe.Enlarge(direct(4, 0, 0), 1, 0, 0)) { byteArrayOf(1, 2, 3) })
    }

    private companion object {
        const val TOLERANCE = 24
    }
}
