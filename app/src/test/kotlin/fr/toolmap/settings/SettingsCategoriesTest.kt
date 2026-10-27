package fr.toolmap.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.settings.AppSettings
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

/**
 * The settings are in nine categories, all closed at first (P11.10, P11.11): each title is shown once, and a tap on a
 * title opens the settings under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsCategoriesTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `each of the nine categories is shown once and closed`() {
        setContent()
        CATEGORIES.forEach { title ->
            rule.onAllNodesWithText(title).assertCountEquals(1)
        }
        rule.onAllNodesWithText("Thème").assertCountEquals(0)
    }

    @Test
    fun `a tap on a category opens the settings under it`() {
        setContent()
        rule.onAllNodesWithText("Thème").assertCountEquals(0)
        rule.onNodeWithText("Affichage").performClick()
        rule.onAllNodesWithText("Thème").assertCountEquals(1)
    }

    private fun setContent() {
        val file = File.createTempFile("settings", ".json").apply { delete() }
        val vm = SettingsViewModel(SettingsStore(file))
        val settings = AppSettings()
        rule.setContent {
            ToolMapTheme(darkTheme = false, gloves = false) {
                CompositionLocalProvider(
                    LocalAppSettings provides settings,
                    LocalFormats provides Formats(settings),
                ) {
                    SettingsScreen(vm = vm, onBack = {}, onOpenBackup = {})
                }
            }
        }
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
