package fr.toolmap.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.BearingUnit
import fr.toolmap.core.settings.ThemeMode
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * « Réglages » since P29.9 (« Loupe »): the search over the nine categories, whose results are the settings themselves;
 * the pages of the categories with their tabs; switches, segmented choices and sliders in the rows; « Rétablir » with
 * « Annuler »; Back closing the page, then the search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2400dp")
class SettingsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val vm = SettingsViewModel(SettingsStore(File.createTempFile("settings", ".json").apply { delete() }))

    private fun setContent() {
        rule.setContent {
            val settings: AppSettings by vm.settings.collectAsState()
            ToolMapTheme(darkTheme = false, gloves = settings.gloves) {
                CompositionLocalProvider(
                    LocalAppSettings provides settings,
                    LocalFormats provides Formats(settings),
                ) {
                    SettingsScreen(vm = vm, onBack = {}, onOpenBackup = {}, onOpenMultiZoom = {})
                }
            }
        }
    }

    private fun search(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun `the list shows the search field and the nine categories, closed`() {
        setContent()
        rule.onAllNodesWithText("Rechercher un réglage").assertCountEquals(1)
        CATEGORIES.forEach { rule.onAllNodesWithText(it).assertCountEquals(1) }
        rule.onAllNodesWithText("Thème").assertCountEquals(0)
    }

    @Test
    fun `a category opens on its page, whose tabs lead to the others`() {
        setContent()
        rule.onNodeWithText("Affichage").performClick()
        rule.onAllNodesWithText("Thème").assertCountEquals(1)
        rule.onAllNodesWithText("Sombre").assertCountEquals(1)
        rule.onAllNodesWithText("Fond opaque des menus").assertCountEquals(1)
        rule.onNodeWithText("Carte").performClick()
        rule.onAllNodesWithText("Thème").assertCountEquals(0)
        rule.onAllNodesWithText("Zooms manquants").assertCountEquals(1)
        rule.onAllNodesWithText("Boutons + et − à gauche").assertCountEquals(1)
        rule.onAllNodesWithText("Complétés sur 3 niveaux").assertCountEquals(1)
        assertEquals(SettingsCategory.CANVAS, vm.category)
    }

    @Test
    fun `cap finds its category and four settings of three categories, with the category on their second line`() {
        setContent()
        search("cap")
        rule.onAllNodesWithText("Position et cap").assertCountEquals(1)
        rule.onAllNodesWithText("Catégorie").assertCountEquals(1)
        rule.onAllNodesWithText("Unité de cap").assertCountEquals(1)
        rule.onAllNodesWithText("Unités et coordonnées · Degrés (360° par tour)").assertCountEquals(1)
        rule.onAllNodesWithText("Affichage du cap GNSS").assertCountEquals(1)
        rule.onAllNodesWithText("Position et cap · Flèche de direction du déplacement").assertCountEquals(1)
        rule.onAllNodesWithText("Affichage du cap actuel").assertCountEquals(1)
        rule.onAllNodesWithText("Mode explorateur automatique").assertCountEquals(1)
        rule.onAllNodesWithText("Thème").assertCountEquals(0)
        // The names that hold the word first, then the setting found in its explanation.
        val top = { text: String -> rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Affichage du cap actuel") < top("Mode explorateur automatique"))
    }

    @Test
    fun `a result is the setting itself, usable in place`() {
        setContent()
        search("unite de cap")
        rule.onNodeWithText("Grades").performClick()
        rule.waitForIdle()
        assertEquals(BearingUnit.GRADIANS, vm.settings.value.bearing)
        rule.onAllNodesWithText("Unités et coordonnées · Grades (400 par tour)").assertCountEquals(1)
    }

    @Test
    fun `the search reads the explanations and the options, without accents`() {
        setContent()
        search("SOMBRE")
        rule.onAllNodesWithText("Thème").assertCountEquals(1)
        rule.onNodeWithContentDescription("Effacer la recherche").performClick()
        search("ecart tolere")
        rule.onAllNodesWithText("Écart toléré").assertCountEquals(1)
    }

    @Test
    fun `no result says so and offers to clear the search`() {
        setContent()
        search("zzzz")
        rule.onAllNodesWithText("Aucun réglage ne contient ce mot.").assertCountEquals(1)
        rule.onNodeWithText("Effacer la recherche").performClick()
        rule.waitForIdle()
        assertEquals("", vm.query)
        rule.onAllNodesWithText("Unités et coordonnées").assertCountEquals(1)
    }

    @Test
    fun `Back closes the page, then clears the search`() {
        setContent()
        search("cap")
        rule.onNodeWithText("Position et cap").performClick()
        rule.onAllNodesWithText("Recentrage automatique", substring = true).assertCountEquals(1)
        pressBack()
        assertNull(vm.category)
        assertEquals("cap", vm.query)
        pressBack()
        assertEquals("", vm.query)
    }

    @Test
    fun `the loupe of a page goes back to the search`() {
        setContent()
        rule.onNodeWithText("Alertes").performClick()
        rule.onNodeWithContentDescription("Rechercher un réglage").performClick()
        rule.waitForIdle()
        assertNull(vm.category)
        rule.onAllNodesWithText("Rechercher un réglage").assertCountEquals(1)
    }

    @Test
    fun `a switch toggles from anywhere on its row`() {
        setContent()
        rule.onNodeWithText("Affichage").performClick()
        rule.onNodeWithText("Mode gants").performClick()
        rule.waitForIdle()
        assertTrue(vm.settings.value.gloves)
    }

    @Test
    fun `the radius only shows while the proximity alert is on`() {
        setContent()
        rule.onNodeWithText("Alertes").performClick()
        rule.onAllNodesWithText("Rayon par défaut").assertCountEquals(1)
        rule.onNodeWithText("Alerte de proximité").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Rayon par défaut").assertCountEquals(0)
    }

    @Test
    fun `Rétablir applies the defaults and Annuler brings the settings back`() {
        vm.update { it.copy(theme = ThemeMode.DARK) }
        setContent()
        rule.onNodeWithContentDescription("Plus d'options").performClick()
        rule.onNodeWithText("Rétablir les réglages par défaut").performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.SYSTEM, vm.settings.value.theme)
        rule.onAllNodesWithText("Réglages par défaut rétablis").assertCountEquals(1)
        rule.onNodeWithText("Annuler").performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.DARK, vm.settings.value.theme)
    }

    @Test
    fun `the backup opens from the page of Stockage`() {
        var opened = 0
        rule.setContent {
            ToolMapTheme(darkTheme = false) {
                CompositionLocalProvider(LocalFormats provides Formats(AppSettings())) {
                    SettingsScreen(vm = vm, onBack = {}, onOpenBackup = { opened++ })
                }
            }
        }
        rule.onNodeWithText("Stockage et sauvegarde").performScrollTo().performClick()
        rule.onNodeWithText("Sauvegarde et restauration…").performClick()
        assertEquals(1, opened)
    }

    private companion object {
        val CATEGORIES =
            listOf(
                "Unités et coordonnées",
                "Affichage",
                "Carte",
                "Grille et informations",
                "Repères et traces",
                "Position et cap",
                "Enregistreur de trace",
                "Alertes",
                "Stockage et sauvegarde",
            )
    }
}
