package fr.toolmap.record

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.TrackActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * « Enregistrer une trace » in one dialog (P29.5, « Sentier »): the activity among the last ones, the final name, the
 * precision of the points; « Démarrer » starts. A text field in a dialog never lets Robolectric go idle on a screen
 * wider than 320 dp (an artefact of the test environment), hence the small screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h900dp")
class RecordStartDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val started = ArrayList<Triple<String, RecorderProfile, TrackActivity?>>()

    private fun show(askActivity: Boolean) {
        rule.setContent {
            RecordStartDialog(
                initialProfile = RecorderProfile.WALK,
                onStart = { name, profile, activity -> started += Triple(name, profile, activity) },
                onDismiss = {},
                askActivity = askActivity,
                recentActivities =
                    listOf(
                        TrackActivity.HIKE,
                        TrackActivity.WALK,
                        TrackActivity.TRAIL_RUN,
                        TrackActivity.BICYCLE,
                    ),
                initialActivity = TrackActivity.HIKE,
            )
        }
    }

    @Test
    fun `the last activities are chips, the field shows the final name, Demarrer starts`() {
        show(askActivity = true)
        listOf("Randonnée", "Marche", "Trail", "Vélo", "Toutes…").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Randonnée · Sortie du ", substring = true).assertExists()
        rule.onNodeWithText("Démarrer").performClick()
        assertEquals(TrackActivity.HIKE, started.single().third)
        assertEquals(RecorderProfile.WALK, started.single().second)
    }

    @Test
    @Config(qualifiers = "w320dp-h1800dp")
    fun `Toutes unfolds the activities of AlpineQuest, and a pick replaces the first chip`() {
        show(askActivity = true)
        rule.onAllNodesWithText("Pousse-pousse").assertCountEquals(0)
        rule.onNodeWithText("Toutes…").performClick()
        rule.onNodeWithText("Pousse-pousse").performClick()
        rule.onNodeWithText("Pousse-pousse · Sortie du ", substring = true).assertExists()
        rule.onNodeWithText("Démarrer").performClick()
        assertEquals(TrackActivity.RICKSHAW, started.single().third)
    }

    @Test
    fun `the precision of the points is named, densest first`() {
        show(askActivity = false)
        rule.onAllNodesWithText("Activité").assertCountEquals(0)
        listOf("Précise", "Normale", "Espacée", "Économie").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("toutes les 10 s et 25 m").performClick()
        rule.onNodeWithText("Démarrer").performClick()
        assertEquals(RecorderProfile.SAVER, started.single().second)
        assertEquals(null, started.single().third)
    }
}
