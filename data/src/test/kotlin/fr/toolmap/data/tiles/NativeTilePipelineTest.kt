package fr.toolmap.data.tiles

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import fr.toolmap.core.compute.Compute
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.elevation.PngDecoder
import fr.toolmap.core.elevation.PngImage
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileRecipe
import fr.toolmap.engine.NativeEngine
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tiles drawn here (blended, cut out along a contour, made of other tiles) with the native engine in charge
 * (ADR-119): the same pixels as the Kotlin code gives, in a PNG written by the engine. Runs on the library built for
 * this machine; skipped where Rust is not installed, unless the engine is required (CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class NativeTilePipelineTest {
    @Before
    fun install() {
        val state = NativeEngine.install()
        if (System.getProperty("toolmap.engine.required") == "true") {
            assertEquals(NativeEngine.State.ACTIVE, state, "the native engine is required here")
        }
        assumeTrue("no native engine on this machine (Rust not installed)", state == NativeEngine.State.ACTIVE)
    }

    @After
    fun uninstall() {
        NativeEngine.uninstall()
    }

    private val random = Random(7)

    private fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** A 256 px tile that looks like a map: flat areas, lines and a photo-like noise, opaque. */
    private fun mapTile(seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(230 - seed * 9, 236, 215 + seed * 4))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val local = Random(seed.toLong())
        repeat(40) {
            paint.color = Color.rgb(local.nextInt(256), local.nextInt(256), local.nextInt(256))
            paint.strokeWidth = 1f + local.nextInt(6)
            canvas.drawLine(
                local.nextInt(256).toFloat(),
                local.nextInt(256).toFloat(),
                local.nextInt(256).toFloat(),
                local.nextInt(256).toFloat(),
                paint,
            )
        }
        return png(bitmap)
    }

    /** A 256 px overlay with transparent, partly transparent and opaque areas (slopes, a ski map). */
    private fun overlayTile(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(30) {
            paint.color = Color.argb(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(
                random.nextInt(256).toFloat(),
                random.nextInt(256).toFloat(),
                8f + random.nextInt(40),
                paint,
            )
        }
        return png(bitmap)
    }

    private fun pixelsOf(bytes: ByteArray): IntArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    /** The pixels a PNG file holds, as ARGB ints (not through Android: no premultiplication in between). */
    private fun held(png: ByteArray): IntArray {
        val image: PngImage = PngDecoder.decode(png)
        return IntArray(image.width * image.height) { i ->
            val x = i % image.width
            val y = i / image.width
            val alpha = if (image.channels == 4) image.sample(x, y, 3) else 0xFF
            (alpha shl 24) or (image.sample(x, y, 0) shl 16) or (image.sample(x, y, 1) shl 8) or image.sample(x, y, 2)
        }
    }

    /** What the engine's PNG writer keeps of [pixels]: a fully transparent pixel loses its hidden colour. */
    private fun written(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            if (pixels[i] ushr 24 ==
                0
            ) {
                0
            } else {
                pixels[i]
            }
        }

    @Test
    fun `a blended tile holds the pixels of the Kotlin code`() {
        val layers =
            listOf(
                LayerConfig("a", "base") to mapTile(1),
                LayerConfig("b", "slopes", opacity = 0.6f, tint = 0x3366CC, tintStrength = 0.4f) to overlayTile(),
                LayerConfig("c", "plan", blend = BlendMode.MULTIPLY, contrast = 0.2f) to mapTile(2),
            )
        val out = assertNotNull(BitmapTileBlender().blend(layers))
        // The same steps with the Kotlin code alone.
        val expected = IntArray(256 * 256)
        for ((part, bytes) in layers) {
            val pixels = pixelsOf(bytes)
            fr.toolmap.core.layers.RasterAdjust.colorMatrix(part)?.let { Blend.adjustReference(pixels, it) }
            part.tint?.let { Blend.tintReference(pixels, it, part.tintStrength) }
            Blend.compositeReference(expected, pixels, part.blend, part.opacity)
        }
        assertContentEquals(written(expected), held(out))
        // And Android reads the engine's PNG as it reads its own: the tile of the Kotlin path, pixel for pixel.
        NativeEngine.uninstall()
        val plain = assertNotNull(BitmapTileBlender().blend(layers))
        assertContentEquals(pixelsOf(plain), pixelsOf(out))
    }

    @Test
    fun `a tile cut out along a contour holds the pixels of the Kotlin code`() {
        val ramp = DemGrid(256, FloatArray(256 * 256) { i -> 1000f + (i % 256) * 4f + (i / 256) * 0.5f })
        val window = DemWindow.of(14, 1, 0)
        val mask = ElevationMask(1500, 1700)
        for (tile in listOf(mapTile(3), overlayTile())) {
            val out = assertNotNull(BitmapElevationMasker().mask(tile, ramp, window, mask))
            val expected = pixelsOf(tile)
            val alphas = MaskRaster.alphas(MaskRaster.elevations(ramp.elevations, 256, window, 256), 256, mask)
            MaskRaster.applyAlphasReference(expected, alphas)
            assertContentEquals(written(expected), held(out))
            assertTrue(held(out).any { it ushr 24 == 0 } && held(out).any { it ushr 24 == 0xFF })
        }
    }

    @Test
    fun `a JPEG tile cut out by the engine gets an alpha channel`() {
        val jpeg =
            ByteArrayOutputStream().also {
                Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                    .compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
        val ramp = DemGrid(256, FloatArray(256 * 256) { i -> 1000f + (i % 256) * 4f })
        val out =
            assertNotNull(BitmapElevationMasker().mask(jpeg, ramp, DemWindow.of(13, 0, 0), ElevationMask(null, 1500)))
        val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(255, Color.alpha(bitmap.getPixel(10, 128)))
        assertEquals(0, Color.alpha(bitmap.getPixel(250, 128)))
    }

    @Test
    fun `a tile made of other tiles is the one Android would have written`() {
        val level = LevelDef(ZoomSet.range(0, 18), listOf("https://t/{\$z}/{\$x}/{\$y}.png"))
        val source = TileSource(id = "s", name = "s", levels = listOf(level))
        val coarse = TileRecipe.Direct(source, level, TileId(10, 5, 5))
        val enlarged = TileRecipe.Enlarge(coarse, depth = 1, col = 1, row = 0)
        val tile = mapTile(4)
        val composer = BitmapTileComposer()
        val native = assertNotNull(composer.compose(enlarged) { tile })
        NativeEngine.uninstall()
        assertEquals(null, Compute.accelerator)
        val plain = assertNotNull(composer.compose(enlarged) { tile })
        assertContentEquals(pixelsOf(plain), pixelsOf(native))
    }
}
