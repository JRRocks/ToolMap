package fr.toolmap.map

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The centre point (F1, ADR-043): a tap opens its quick menu, and each row of the menu runs its command. The point is off
 * while a tool is on, as the position button is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CenterPointTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `each row of the quick menu runs its command`() {
        val commands = mutableListOf<String>()
        setContent(menuEnabled = true, menuOpen = true, onTap = { _, _ -> }, commands = commands)
        rule.onNodeWithText("Définir comme cible").performClick()
        rule.onNodeWithText("Créer un waypoint…").performClick()
        rule.onNodeWithText("Mesurer depuis ici").performClick()
        rule.onNodeWithText("Copier").performClick()
        rule.onNodeWithText("Partager").performClick()
        rule.onNodeWithText("Ouvrir avec").performClick()
        assertEquals(listOf("target", "waypoint", "measure", "copy", "share", "open"), commands)
    }

    @Test
    fun `the menu shows the coordinates and the altitude of the relief, and copies them`() {
        val commands = mutableListOf<String>()
        val copied = mutableListOf<String>()
        setContent(menuEnabled = true, menuOpen = true, onTap = { _, _ -> }, commands = commands, copied = copied)
        rule.onNodeWithText("282 m · relief", substring = true).assertExists()
        rule.onNodeWithText("45", substring = true).performClick()
        assertEquals(listOf("copy"), commands)
        assertTrue(copied.single().startsWith("45"))
    }

    @Test
    fun `a tap on the point opens its menu`() {
        var taps = 0
        setContent(menuEnabled = true, menuOpen = false, onTap = { _, _ -> taps++ }, commands = mutableListOf())
        rule.onNodeWithContentDescription(POINT_LABEL).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a tap gives its offset from the middle, so that what is drawn under the finger can open`() {
        val taps = mutableListOf<Pair<Float, Float>>()
        setContent(menuEnabled = true, menuOpen = false, onTap = {
            dx,
            dy,
            ->
            taps += dx to dy
        }, commands = mutableListOf())
        rule.onNodeWithContentDescription(POINT_LABEL).performTouchInput { click(topLeft) }
        val (dx, dy) = taps.single()
        assertTrue("$dx, $dy", dx < 0f && dy < 0f)
    }

    @Test
    fun `a long press always opens the menu`() {
        var menus = 0
        var taps = 0
        setContent(
            menuEnabled = true,
            menuOpen = false,
            onTap = { _, _ -> taps++ },
            onMenu = { menus++ },
            commands = mutableListOf(),
        )
        rule.onNodeWithContentDescription(POINT_LABEL).performTouchInput { longClick() }
        assertEquals(1, menus)
        assertEquals(0, taps)
    }

    @Test
    fun `the point is off while a tool is on, and a tap does nothing`() {
        var taps = 0
        setContent(menuEnabled = false, menuOpen = false, onTap = { _, _ -> taps++ }, commands = mutableListOf())
        rule.onNodeWithContentDescription(POINT_LABEL).assertIsNotEnabled()
        rule.onNodeWithContentDescription(POINT_LABEL).performClick()
        assertEquals(0, taps)
    }

    private fun setContent(
        menuEnabled: Boolean,
        menuOpen: Boolean,
        onTap: (Float, Float) -> Unit,
        onMenu: () -> Unit = {},
        commands: MutableList<String>,
        copied: MutableList<String> = mutableListOf(),
    ) {
        rule.setContent {
            CenterPoint(
                menuEnabled = menuEnabled,
                menuOpen = menuOpen,
                onTap = { dx, dy -> onTap(dx, dy) },
                onMenu = onMenu,
                onDismiss = {},
                menu = {
                    CenterQuickMenu(
                        lat = 45.0,
                        lon = 6.0,
                        terrainElevation = { _, _ -> 282.0 },
                        onTarget = { commands += "target" },
                        onWaypoint = { commands += "waypoint" },
                        onMeasure = { commands += "measure" },
                        onCopy = {
                            commands += "copy"
                            copied += it
                        },
                        onShare = { commands += "share" },
                        onOpenWith = { commands += "open" },
                    )
                },
            )
        }
    }

    private companion object {
        const val POINT_LABEL = "Point central de la carte : menu rapide"
    }
}
