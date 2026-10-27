package fr.toolmap.share

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * PNG renders of the sharing screen (P29.8, « Cordée »): out of a group, in a group, the invitation and the sheet of a
 * member, by day and by night, for the comparison with the validated preview where no emulator is at hand. The QR code
 * holds a made-up address, never a real invitation. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*ShareRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-xxhdpi")
class ShareRenderTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    @Test
    fun outDay() = renderMain(night = false, "out-day", inGroup = false)

    @Test
    fun outNight() = renderMain(night = true, "out-night", inGroup = false)

    @Test
    fun groupDay() = renderMain(night = false, "group-day", inGroup = true)

    @Test
    fun groupNight() = renderMain(night = true, "group-night", inGroup = true)

    @Test
    fun inviteDay() = renderMain(night = false, "invite-day", inGroup = true, invite = true)

    @Test
    fun memberDay() = renderMember(night = false, "member-day")

    @Test
    fun memberNight() = renderMember(night = true, "member-night")

    private fun folder(): File {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        return File(folder).apply { mkdirs() }
    }

    private fun renderMain(
        night: Boolean,
        name: String,
        inGroup: Boolean,
        invite: Boolean = false,
    ) {
        val out = folder()
        rule.setContent {
            Themed(night) {
                MainPage(
                    ui = ShareSamples.ui(now, inGroup),
                    snackbar = SnackbarHostState(),
                    onBack = {},
                    onToggle = {},
                    onSos = {},
                    onStopSos = {},
                    onInvite = {},
                    onScan = {},
                    onPaste = {},
                    onSettings = {},
                    onHelp = {},
                    onLeave = {},
                    onProfile = {},
                    onMessages = {},
                    onOpenMember = {},
                    onShowOnMap = { _, _ -> },
                    onAlertBack = {},
                )
                if (invite) {
                    CodeSheet(
                        title = "Invitation",
                        hint =
                            "Faites scanner ce code (ou envoyez le lien) : la personne rejoint le groupe aussitôt, " +
                                "sans code de réponse. Valable 7 jours ; tous ceux qui l'ont peuvent entrer, ne le " +
                                "publiez pas.",
                        code = ShareCode("https://example.org/toolmap/exemple-de-code", "https://example.org", 0L),
                        busy = false,
                        primary = null,
                        onClose = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun renderMember(
        night: Boolean,
        name: String,
    ) {
        val out = folder()
        val member = ShareSamples.a54(now)
        rule.setContent {
            Themed(night) {
                Box(Modifier.fillMaxSize().background(MAP))
                MemberSheet(member, PeerState.of(member, now), {}, {}, {}, {}, {})
            }
        }
        rule.waitForIdle()
        save(out, name)
    }

    private fun save(
        out: File,
        name: String,
    ) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private val MAP = Color(0xFFE4EBCF)

@Composable
private fun Themed(
    night: Boolean,
    content: @Composable () -> Unit,
) {
    ToolMapTheme(darkTheme = night) {
        CompositionLocalProvider(LocalFormats provides Formats(), content = content)
    }
}
