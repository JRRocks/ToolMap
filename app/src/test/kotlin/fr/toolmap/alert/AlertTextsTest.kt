package fr.toolmap.alert

import android.app.Application
import fr.toolmap.core.alert.AlertEvent
import fr.toolmap.core.alert.WatchedPlace
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.ui.Formats
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlertTextsTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private val metric = Formats(UnitSystem.METRIC)

    @Test
    fun `a proximity alert names the place and the distance`() {
        val place = WatchedPlace(1L, "Refuge de l'Écrin", 45.0, 6.0, 100.0)
        val text = AlertTexts.of(resources, metric, AlertEvent.Proximity(place, 85.0))
        assertTrue("Refuge de l'Écrin" in text.title, text.title)
        assertTrue("85" in text.body, text.body)
    }

    @Test
    fun `a place without a name still has a title`() {
        val place = WatchedPlace(1L, null, 45.0, 6.0, 100.0)
        val text = AlertTexts.of(resources, metric, AlertEvent.Proximity(place, 85.0))
        assertTrue(text.title.isNotBlank() && "null" !in text.title, text.title)
        assertEquals(text, AlertTexts.of(resources, metric, AlertEvent.Proximity(place.copy(name = "  "), 85.0)))
    }

    @Test
    fun `an off-track alert gives the distance and the limit in the user's units`() {
        val event = AlertEvent.OffTrack(120.0, 50.0)
        val m = AlertTexts.of(resources, metric, event)
        assertTrue("120" in m.body && "50" in m.body, m.body)
        val imperial = AlertTexts.of(resources, Formats(UnitSystem.IMPERIAL), event)
        assertTrue("ft" in imperial.body, imperial.body)
    }

    @Test
    fun `every event has a title and a text`() {
        val events = listOf(AlertEvent.BackOnTrack, AlertEvent.Arrived, AlertEvent.OffTrack(60.0, 50.0))
        for (event in events) {
            val text = AlertTexts.of(resources, metric, event)
            assertTrue(text.title.isNotBlank() && text.body.isNotBlank(), "$event")
        }
    }
}
