package fr.toolmap.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OsmTypesTest {
    @Test
    fun `the known types of OpenStreetMap are said in French`() {
        assertEquals("Château fort", OsmTypes.french("castle"))
        assertEquals("Village", OsmTypes.french("village"))
        assertEquals("Sommet", OsmTypes.french("peak"))
        assertEquals("Refuge", OsmTypes.french("alpine_hut"))
    }

    @Test
    fun `an unknown type is shown as it comes, readable`() {
        assertEquals("Bird hide", OsmTypes.french("bird_hide"))
        assertEquals("Sommet", OsmTypes.french(" Peak "))
        assertNull(OsmTypes.french(""))
        assertNull(OsmTypes.french(null))
    }
}
