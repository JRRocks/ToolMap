package fr.toolmap.update

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fr.toolmap.core.update.GitHubReleases
import fr.toolmap.p2p.QrCodes
import fr.toolmap.share.QR_LOGO_MODULES
import fr.toolmap.share.qrLogoRange
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * « Partager l'app » (P40.1, ADR-137, `docs/previews/partager-app/img/03-A-page.png`): the page, its share button, and
 * the QR code of the fixed link, which still reads once the launcher icon covers its middle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareAppScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val link = GitHubReleases.LATEST_APK_LINK

    @Test
    fun `the page shows the hint, the QR code and the link, and the round button shares the message`() {
        var back = 0
        rule.setContent { ToolMapTheme { ShareAppScreen(onBack = { back++ }) } }
        rule.onNodeWithText("Partager l'app").assertExists()
        rule.onNodeWithText("À scanner avec l'appareil photo\nde l'autre téléphone").assertExists()
        rule.onNodeWithTag(SHARE_APP_QR_TAG).assertExists()
        rule.onNodeWithContentDescription(link).assertExists()
        // Under the fold of the small screen of Robolectric: scrolled to first.
        rule.onNodeWithContentDescription("Envoyer le lien").performScrollTo().performClick()
        val chooser = assertNotNull(shadowOf(rule.activity).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("ToolMap pour Android : $link", send.getStringExtra(Intent.EXTRA_TEXT))
        rule.onNodeWithContentDescription("Retour").performClick()
        assertEquals(1, back)
    }

    @Test
    fun `the link is cut only after its slashes, as in the maquette`() {
        assertEquals(
            listOf(
                "https://github.com/",
                "JRRocks/",
                "ToolMap/",
                "releases/",
                "latest/",
                "download/",
                "ToolMap.apk",
            ),
            linkPieces(link),
        )
    }

    @Test
    fun `the QR code of the page, level H, its logo square emptied, reads as the link`() {
        val matrix = QrCodes.encode(link, ErrorCorrectionLevel.H)
        // The maquette: 49 modules, the white square of the logo 13 of them, from the 19th to the 31st.
        assertEquals(49, matrix.size)
        assertEquals(18 until 18 + QR_LOGO_MODULES, qrLogoRange(matrix.size))
        val logo = qrLogoRange(matrix.size)
        val side = (matrix.size + 2 * QUIET) * PIXELS
        val image = ByteArray(side * side) { WHITE }
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y] || (x in logo && y in logo)) continue
                for (dy in 0 until PIXELS) {
                    for (dx in 0 until PIXELS) {
                        image[((y + QUIET) * PIXELS + dy) * side + (x + QUIET) * PIXELS + dx] = 0
                    }
                }
            }
        }
        assertEquals(link, QrCodes.decode(image, side, side, side))
    }

    private companion object {
        const val QUIET = 4
        const val PIXELS = 4
        const val WHITE = 0xFF.toByte()
    }
}
