package fr.toolmap.maps

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P26.6, variant A: while a slider of « Cartes et calques » is held, the rest of the panel fades away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdjustSlidersTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `holding a slider fades the panel away, and letting go brings it back while the slider keeps its pill`() {
        lateinit var fade: PanelFade
        rule.setContent { fade = rememberPanelFade() }
        assertEquals(1f, fade.shown)
        assertNull(fade.held)
        rule.runOnIdle { fade.onHold("hue", true) }
        rule.waitForIdle()
        assertEquals("hue", fade.held)
        assertEquals(0f, fade.shown)
        // Another slider letting go changes nothing.
        rule.runOnIdle { fade.onHold("contrast", false) }
        rule.waitForIdle()
        assertEquals("hue", fade.held)
        rule.runOnIdle { fade.onHold("hue", false) }
        rule.waitForIdle()
        assertNull(fade.held)
        assertEquals("hue", fade.last)
        assertEquals(1f, fade.shown)
    }

    @Test
    fun `a finger on a slider holds it until it lets go`() {
        lateinit var fade: PanelFade
        rule.setContent {
            fade = rememberPanelFade()
            AdjustSlider(
                key = "hue",
                label = "Teinte : 0°",
                value = 0f,
                range = HUE_RANGE,
                fade = fade,
                onChange = {},
                hueTrack = true,
            )
        }
        val slider = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        slider.performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals("hue", fade.held)
        assertEquals(0f, fade.shown)
        slider.performTouchInput { up() }
        rule.waitForIdle()
        assertNull(fade.held)
        assertEquals(1f, fade.shown)
    }
}
