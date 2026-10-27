package fr.toolmap.share

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.toolmap.core.p2p.MemberLook
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
 * A PNG sheet of the member tokens in a few colours, for the visual check. Skipped unless the folder is given:
 * `./gradlew :app:testDebugUnitTest --tests '*MemberTokensRenderTest*' -Ptoolmap.renders=<folder>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class MemberTokensRenderTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tokens() {
        val folder = System.getProperty("toolmap.renders").orEmpty()
        assumeTrue("Renders are made with -Ptoolmap.renders=<folder>", folder.isNotEmpty())
        val colors = listOf(0xFFD32F2F, 0xFF1976D2, 0xFF388E3C, 0xFFFFEB3B, 0xFF7B1FA2).map { it.toInt() }
        rule.setContent {
            ToolMapTheme(darkTheme = false) {
                Column(
                    modifier = Modifier.background(Color(0xFFE8E4D8)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (argb in colors) {
                        for (row in MemberLook.ICONS.indices.chunked(8)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (id in row) MemberBadge("Jean Rox", MemberLook.pack(id, argb), size = 44.dp)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(folder).mkdirs()
        FileOutputStream(File(folder, "member-tokens.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
