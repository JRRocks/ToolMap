package fr.toolmap.map

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The positioning panel: the barometer row only on a phone that has a barometer, and it reports the switch (P16.2);
 * « La carte suit » offers the four ways to follow the position (R4, P14.10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PositionMenuPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val barometer = mutableListOf<Boolean>()
    private val follow = mutableListOf<FollowChoice>()

    @Test
    fun `the barometer row is hidden when the phone has no barometer`() {
        setContent(PositionMenuState(realTime = true, follow = FollowChoice.FREE, recording = false))
        rule.onAllNodesWithText("Baromètre").assertCountEquals(0)
    }

    @Test
    fun `a tap on the barometer row sets it when it is off`() {
        setContent(
            PositionMenuState(
                realTime = true,
                follow = FollowChoice.FREE,
                recording = false,
                barometerAvailable = true,
            ),
        )
        rule.onNodeWithText("Baromètre").performClick()
        assertEquals(listOf(true), barometer)
    }

    @Test
    fun `a tap on the barometer row clears it when it is on`() {
        setContent(
            PositionMenuState(
                realTime = true,
                follow = FollowChoice.FREE,
                recording = false,
                barometerAvailable = true,
                barometer = true,
            ),
        )
        rule.onNodeWithText("Baromètre").performClick()
        assertEquals(listOf(false), barometer)
    }

    @Test
    fun `the four ways to follow are offered and report the choice`() {
        setContent(PositionMenuState(realTime = true, follow = FollowChoice.FOLLOW, recording = false))
        rule.onNodeWithText("Libre").performClick()
        rule.onNodeWithText("Boussole").performClick()
        rule.onNodeWithText("Explorer").performClick()
        assertEquals(listOf(FollowChoice.FREE, FollowChoice.COMPASS, FollowChoice.EXPLORER), follow)
    }

    @Test
    fun `the recorder row says what it is doing`() {
        setContent(PositionMenuState(realTime = true, follow = null, recording = false))
        rule.onNodeWithText("Arrêté").assertExists()
    }

    private fun setContent(state: PositionMenuState) {
        rule.setContent {
            PositionMenuPanel(
                state = state,
                onFollow = { follow += it },
                onRealTime = {},
                onRecorder = {},
                onBarometer = { barometer += it },
                onShare = {},
                onHelp = {},
                maxHeight = 2000.dp,
            )
        }
    }
}
