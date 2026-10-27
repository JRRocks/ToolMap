package fr.toolmap.data.tiles

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class BitmapElevationMaskerTest {
    private val masker = BitmapElevationMasker()

    private fun png(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): ByteArray = ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()

    private fun solid(
        color: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): ByteArray = png(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, format)

    /** A 256 px DEM rising from 1000 m (west edge) to 2020 m (east edge), 4 m per pixel. */
    private val ramp = DemGrid(256, FloatArray(256 * 256) { i -> 1000f + (i % 256) * 4f })

    private fun alphaAt(
        bitmap: Bitmap,
        x: Int,
        y: Int = 128,
    ): Int = Color.alpha(bitmap.getPixel(x, y))

    @Test
    fun `pixels below the band become transparent, the others keep their colour`() {
        val window = DemWindow.of(13, 0, 0)
        val out = masker.mask(solid(Color.RED), ramp, window, ElevationMask(1500, null))
        val bitmap = BitmapFactory.decodeByteArray(assertNotNull(out), 0, out.size)
        assertEquals(0, alphaAt(bitmap, 10)) // 1040 m
        assertEquals(0, alphaAt(bitmap, 120)) // 1480 m
        assertEquals(255, alphaAt(bitmap, 130)) // 1520 m
        assertEquals(255, alphaAt(bitmap, 250)) // 2000 m
        assertEquals(Color.RED, bitmap.getPixel(200, 128))
    }

    @Test
    fun `a JPEG tile gets an alpha channel`() {
        val out =
            masker.mask(
                solid(Color.BLUE, Bitmap.CompressFormat.JPEG),
                ramp,
                DemWindow.of(13, 0, 0),
                ElevationMask(null, 1500),
            )
        val bitmap = BitmapFactory.decodeByteArray(assertNotNull(out), 0, out.size)
        assertEquals(255, alphaAt(bitmap, 10))
        assertEquals(0, alphaAt(bitmap, 250))
    }

    @Test
    fun `Terrarium tiles are decoded to metres`() {
        // 1500 m = 34268 + 0 / 256 - 32768: red 133, green 220, blue 0.
        val dem =
            masker.dem("13/0/0") {
                png(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(133, 220, 0)) })
            }
        assertEquals(1500f, assertNotNull(dem).elevations[1234])
        // Served from memory the second time.
        assertEquals(dem, masker.dem("13/0/0") { error("loaded twice") })
    }
}
