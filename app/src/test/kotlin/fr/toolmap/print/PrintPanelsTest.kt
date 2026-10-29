package fr.toolmap.print

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.print.Paper
import fr.toolmap.core.print.PrintArea
import fr.toolmap.core.print.PrintPlanner
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The print panels of P17.8: the sheets of variant E (« Épreuve »), the settings of variant A (« Gabarit ») and the box
 * of a heavy PDF of P17.9 (form Q2), held here with the state the view model would keep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h900dp")
class PrintPanelsTest {
    @get:Rule
    val rule = createComposeRule()

    private val sources = BuiltinSources.load().associateBy { it.id }
    private val stack = listOf(LayerConfig("l1", "ign-maps"))

    private fun plan(template: PrintTemplate) = PrintPlanner.plan(template, PrintArea(45.0, 6.0), stack, sources::get)

    private fun confirming(template: PrintTemplate) =
        Holder(PrintUi(stage = PrintStage.CONFIRM, template = template, plan = plan(template), missingTiles = 0))

    /** The state of the panels, changed by their actions as the view model would change it. */
    private class Holder(
        start: PrintUi,
    ) {
        var ui by mutableStateOf(start)
        val actions =
            PrintActions(
                onUpdate = { change -> ui = ui.copy(template = change(ui.template)) },
                onRotate = {},
                onChooseZone = {},
                onNext = {},
                onBack = {},
                onPage = { page -> ui = ui.copy(sheetPage = page) },
                onClose = {},
                onDownload = {},
                onExport = {},
                onCancelJob = {},
            )
    }

    @Test
    fun `the sheets show their sizes and Autre opens the size of a custom sheet, with no largest`() {
        val holder = Holder(PrintUi(stage = PrintStage.SETUP))
        rule.setContent {
            ToolMapTheme { PrintSetupPanel(ui = holder.ui, actions = holder.actions, maxHeight = 800.dp) }
        }
        rule.onNodeWithText("210 × 297").assertExists()
        rule.onNodeWithText("Paysage").assertExists()
        rule.onNodeWithText("Autre").performClick()
        assertEquals(Paper.CUSTOM, holder.ui.template.page.paper)
        rule.onNodeWithText("50 mm au moins, pas de maximum").assertExists()
        rule.onNodeWithText("Largeur").performTextReplacement("2000")
        assertEquals(2_000.0, holder.ui.template.page.widthMm, 1e-9)
    }

    @Test
    fun `at 300 dpi the settings have no box, the layout elements are all on`() {
        val template = PrintTemplate(dpi = 300)
        val holder = confirming(template)
        rule.setContent {
            ToolMapTheme { PrintConfirmSheet(ui = holder.ui, actions = holder.actions, maxHeight = 900.dp) }
        }
        rule.onAllNodesWithText("PDF très lourd").assertCountEquals(0)
        rule.onNodeWithText("Tous").assertExists()
        rule.onNodeWithText("Éléments de la feuille").performClick()
        assertEquals(PrintSheetPage.ELEMENTS, holder.ui.sheetPage)
        rule.onNodeWithText("Bandeau du bas").assertExists()
    }

    @Test
    fun `above 300 dpi the box of a heavy PDF leads to the resolution, with no highest`() {
        val template = PrintTemplate(dpi = 600)
        val holder = confirming(template)
        rule.setContent {
            ToolMapTheme { PrintConfirmSheet(ui = holder.ui, actions = holder.actions, maxHeight = 900.dp) }
        }
        rule.onNodeWithText("PDF très lourd").assertExists()
        // The action of the box comes first, the line of the settings after it.
        rule.onAllNodesWithText("Qualité et marges")[0].performClick()
        assertEquals(PrintSheetPage.QUALITY, holder.ui.sheetPage)
        rule.onNodeWithText("Résolution élevée").assertExists()
        rule.onNodeWithText("72 dpi au moins, pas de maximum").assertExists()
    }
}
