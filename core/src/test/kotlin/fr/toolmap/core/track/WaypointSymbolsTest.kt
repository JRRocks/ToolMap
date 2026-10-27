package fr.toolmap.core.track

import fr.toolmap.core.record.TrackActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WaypointSymbolsTest {
    private val icons = WaypointCatalog.CATEGORIES.flatMap { it.icons }

    @Test
    fun `unknown or missing names get the default waypoint`() {
        assertEquals(WaypointSymbols.DEFAULT_COLOR, WaypointSymbols.colorOf(null))
        assertEquals(WaypointSymbols.DEFAULT_COLOR, WaypointSymbols.colorOf("Something Else"))
        assertEquals("Waypoint", WaypointSymbols.canonical("Whatever"))
        assertEquals(WaypointSymbols.DEFAULT_ID, WaypointSymbols.iconOf(null).id)
    }

    @Test
    fun `the catalogue has the 16 categories and every icon is unique`() {
        assertEquals(16, WaypointCatalog.CATEGORIES.size)
        assertTrue(icons.size >= 300)
        assertEquals(icons.size, icons.map { it.id }.toSet().size)
        assertEquals(icons.size, icons.map { it.key.lowercase() }.toSet().size)
        assertTrue(icons.all { it.category.isNotEmpty() })
    }

    @Test
    fun `every key resolves to its own icon, case and spaces ignored`() {
        for (icon in icons) {
            assertEquals(icon.id, WaypointSymbols.iconOf(icon.key).id)
            assertEquals(icon.key, WaypointSymbols.canonical("  " + icon.key.uppercase() + " "))
        }
    }

    @Test
    fun `old Garmin names point at the equivalent icon`() {
        assertEquals("peak", WaypointSymbols.iconOf(" summit ").id)
        assertEquals("Peak", WaypointSymbols.canonical("SUMMIT"))
        assertEquals("camping", WaypointSymbols.iconOf("Campground").id)
        for ((alias, id) in WaypointCatalog.ALIASES) {
            assertNotNull(WaypointSymbols.iconById(id), "alias $alias -> $id")
            assertEquals(id, WaypointSymbols.iconOf(alias).id)
        }
    }

    @Test
    fun `an icon has the colour of its category`() {
        assertEquals(0xFF5A7A00.toInt(), WaypointSymbols.colorOf("Peak"))
    }

    @Test
    fun `the activities are the 32 of AlpineQuest plus other`() {
        assertEquals(33, TrackActivity.entries.size)
        assertEquals(TrackActivity.entries.size, TrackActivity.entries.map { it.colorArgb to it.name }.toSet().size)
    }
}
