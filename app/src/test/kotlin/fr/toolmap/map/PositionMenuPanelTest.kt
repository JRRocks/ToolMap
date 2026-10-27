package fr.toolmap.map

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The positioning panel: the barometer row only on a phone that has a barometer, and it reports the switch (P16.2);
 * « La carte suit » offers the four ways to follow the position (R4, P14.10). Since P29.5 (« Sentier »): a help line
 * under the choices, the time before the distance, « alt. », the satellites seen, « Caler l'altitude… ».
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

    @Test
    fun `while recording the time comes before the distance, as on the chip`() {
        setContent(
            PositionMenuState(
                realTime = true,
                follow = FollowChoice.FOLLOW,
                recording = true,
                recordedMeters = 4820.0,
                recordedMillis = 72 * 60_000L,
            ),
        )
        rule.onNodeWithText("En cours · 1 h 12 · ", substring = true).assertExists()
    }

    @Test
    fun `a help line says what choosing a way to follow does`() {
        setContent(PositionMenuState(realTime = false, follow = null, recording = false))
        rule.onNodeWithText("Choisir un mode allume la position.").assertExists()
    }

    @Test
    fun `the help line says what the chosen way does, and the altitude is named`() {
        setContent(
            PositionMenuState(
                realTime = true,
                follow = FollowChoice.FOLLOW,
                recording = false,
                accuracyMeters = 4.0,
                altitudeMeters = 297.0,
            ),
        )
        rule.onNodeWithText("La carte vous suit, nord en haut.").assertExists()
        rule.onNodeWithText("alt. 297 m", substring = true).assertExists()
    }

    @Test
    fun `while waiting the panel says how many satellites are seen`() {
        setContent(
            PositionMenuState(realTime = true, follow = FollowChoice.FOLLOW, recording = false, satellitesSeen = 3),
        )
        rule.onNodeWithText("En attente du GPS… · 3 satellites vus").assertExists()
    }

    @Test
    fun `Caler l'altitude sits under the barometer, greyed while the position is off`() {
        setContent(
            PositionMenuState(
                realTime = false,
                follow = null,
                recording = false,
                barometerAvailable = true,
                barometer = true,
            ),
        )
        rule.onNodeWithText("Caler l'altitude…").assertIsNotEnabled()
        rule.onNodeWithText("Allumez la position pour caler").assertExists()
    }

    @Test
    fun `Caler l'altitude opens the calibration, and the barometer says when it is calibrated`() {
        setContent(
            PositionMenuState(
                realTime = true,
                follow = FollowChoice.FOLLOW,
                recording = false,
                barometerAvailable = true,
                barometer = true,
                barometerCalibrated = true,
            ),
        )
        rule.onNodeWithText("Corrige l'altitude du GPS · calée").assertExists()
        rule.onNodeWithText("Caler l'altitude…").performScrollTo().performClick()
        assertEquals(1, calibrations)
    }

    private var calibrations = 0

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
                onCalibrate = { calibrations++ },
            )
        }
    }
}
