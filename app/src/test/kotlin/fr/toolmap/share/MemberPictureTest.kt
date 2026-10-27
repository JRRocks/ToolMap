package fr.toolmap.share

import android.app.Application
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import fr.toolmap.core.p2p.MemberLook
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class MemberPictureTest {
    /** A worst case for JPEG: random noise, which does not compress. */
    private fun noise(
        width: Int,
        height: Int,
    ): Bitmap {
        val random = Random(1)
        val bitmap = createBitmap(width, height)
        val pixels = IntArray(width * height) { random.nextInt() or 0xFF000000.toInt() }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    @Test
    fun `any picture becomes a small square under the size limit`() {
        val square = MemberPicture.square(noise(1200, 800))
        assertEquals(MemberLook.PICTURE_PX, square.width)
        assertEquals(MemberLook.PICTURE_PX, square.height)
        val bytes = assertNotNull(MemberPicture.encode(square))
        assertTrue(bytes.size <= MemberLook.MAX_PICTURE_BYTES, "${bytes.size} bytes")
        val back = assertNotNull(MemberPicture.decode(bytes))
        assertEquals(MemberLook.PICTURE_PX, back.width)
    }

    @Test
    fun `a rotated photo stays square, and bytes that are not an image decode to nothing`() {
        val square = MemberPicture.square(noise(300, 500), rotation = 90)
        assertEquals(square.width, square.height)
        assertEquals(null, MemberPicture.decode(byteArrayOf(1, 2, 3)))
    }
}
