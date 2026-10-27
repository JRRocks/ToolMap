package fr.toolmap

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.toolmap.crash.CrashReporter
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Back on the real app (P14.1): each full screen goes back to the one it was opened from instead of leaving the app,
 * and the map's own states (hidden controls, locked screen) take Back before the app does. MapLibre's natives are
 * no-ops here, as in [AppStartupTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ToolMapApp::class, instrumentedPackages = ["org.maplibre.android"])
class NavigationBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        (Thread.getDefaultUncaughtExceptionHandler() as? CrashReporter.Handler)?.let {
            Thread.setDefaultUncaughtExceptionHandler(it.previous)
        }
        File(RuntimeEnvironment.getApplication().filesDir, "crash").deleteRecursively()
    }

    @Test
    fun `back from the settings and from the backup goes back one screen at a time`() {
        openMenu()
        rule.onNodeWithText("Réglages…").performScrollTo().performClick()
        rule.onNodeWithText("Stockage et sauvegarde").performScrollTo().performClick()
        rule.onNodeWithText("Sauvegarde et restauration…").performScrollTo().performClick()
        rule.onAllNodesWithText("Sauvegarde et restauration").assertCountEquals(1)
        pressBack()
        rule.onAllNodesWithText("Réglages").assertCountEquals(1)
        pressBack()
        assertMapShown()
    }

    @Test
    fun `Back on the help opened from the map shows the map`() {
        openMenu()
        rule.onNodeWithContentDescription("Aide et à propos").performClick()
        pressBack()
        assertMapShown()
    }

    @Test
    fun `Retour on the help opened from the map shows the map, not the settings`() {
        openMenu()
        rule.onNodeWithContentDescription("Aide et à propos").performClick()
        rule.onNodeWithContentDescription("Retour").performClick()
        assertMapShown()
    }

    @Test
    fun `the online catalogue opens from Ajouter une carte, adds a map and goes back to the catalogue`() {
        rule.onNodeWithContentDescription("Cartes").performClick()
        // A tile at the foot of the panel since P29.3: below the layers, scrolled to in a small window.
        rule.onNodeWithText("Cartes disponibles").performScrollTo().performClick()
        rule.onNodeWithText("Ajouter une carte", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Catalogue en ligne…").performClick()
        rule.onAllNodesWithText("OPENSTREETMAP").assertCountEquals(1)
        // « Ajouter » of the first map of OpenStreetMap, not yet in the catalogue.
        rule.onAllNodesWithText("Ajouter")[0].performClick()
        rule.onAllNodesWithText("Ajoutée").assertCountEquals(1)
        pressBack()
        rule.onAllNodesWithText("OPENSTREETMAP").assertCountEquals(0)
        rule.onAllNodesWithText("Cartes disponibles").assertCountEquals(1)
    }

    @Test
    fun `hidden controls come back with a tap on the logo`() {
        openMenu()
        rule.onNodeWithText("Masquer").performClick()
        rule.onAllNodesWithContentDescription("Cartes").assertCountEquals(0)
        rule.onNodeWithContentDescription("Menu").performClick()
        assertMapShown()
        rule.onAllNodesWithText("Verrouiller").assertCountEquals(0)
    }

    @Test
    fun `back in an open settings category folds it before leaving the settings`() {
        openMenu()
        rule.onNodeWithText("Réglages…").performScrollTo().performClick()
        rule.onNodeWithText("Stockage et sauvegarde").performScrollTo().performClick()
        rule.onAllNodesWithText("Sauvegarde et restauration…").assertCountEquals(1)
        pressBack()
        rule.onAllNodesWithText("Sauvegarde et restauration…").assertCountEquals(0)
        rule.onAllNodesWithText("Réglages").assertCountEquals(1)
        pressBack()
        assertMapShown()
    }

    @Test
    fun `hidden controls come back with Back`() {
        openMenu()
        rule.onNodeWithText("Masquer").performClick()
        pressBack()
        assertMapShown()
    }

    @Test
    fun `back on a locked screen keeps the app open and locked`() {
        openMenu()
        rule.onNodeWithText("Verrouiller").performClick()
        pressBack()
        assertFalse(rule.activity.isFinishing, "Back left the app from the locked screen")
        // The hint of the pill, and the same hint in a message for the Back that was refused.
        rule.onAllNodesWithText("Maintenez l'écran pour le déverrouiller").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Menu").assertCountEquals(0)
    }

    @Test
    fun `back while drawing asks before losing the points`() {
        rule.onNodeWithContentDescription("Traces et waypoints").performClick()
        rule.onNodeWithText("Dessiner une trace").performClick()
        rule.onNodeWithText("Ajouter ici").performClick()
        pressBack()
        assertFalse(rule.activity.isFinishing, "Back left the app while drawing")
        rule.onAllNodesWithText("Abandonner les modifications ?").assertCountEquals(1)
        rule.onNodeWithText("Abandonner").performClick()
        rule.onAllNodesWithText("Ajouter ici").assertCountEquals(0)
    }

    @Test
    fun `the map is not rebuilt when a full screen is closed`() {
        val before = rule.activity.window.decorView.findMapView()
        assertNotNull(before, "the map screen was not composed")
        openMenu()
        rule.onNodeWithText("Réglages…").performScrollTo().performClick()
        rule.onAllNodesWithContentDescription("Cartes").assertCountEquals(0)
        assertSame(before, rule.activity.window.decorView.findMapView(), "the map is kept under the screen")
        pressBack()
        assertMapShown()
        assertSame(before, rule.activity.window.decorView.findMapView(), "the same map comes back")
        rule.onAllNodesWithText("Réglages…").assertCountEquals(0)
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).findMapView()?.let { return it }
        }
        return null
    }

    private fun openMenu() {
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.waitForIdle()
    }

    private fun pressBack() {
        // A handler switched on by the last tap only reaches the activity's dispatcher a frame later (activity 1.13
        // goes through navigationevent): without this, Back is sent while it is still off.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** The bar of the map is there and the app was not left. */
    private fun assertMapShown() {
        rule.waitForIdle()
        assertFalse(rule.activity.isFinishing, "Back left the app")
        rule.onAllNodesWithContentDescription("Cartes").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Menu").assertCountEquals(1)
    }
}
