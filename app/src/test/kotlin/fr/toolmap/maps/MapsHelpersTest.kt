package fr.toolmap.maps

import org.junit.Test
import kotlin.test.assertEquals

/** Since P29.3 a map without a picture shows the map icon, no longer its initials on a pastel. */
class MapsHelpersTest {
    @Test
    fun `percent truncates the fraction`() {
        assertEquals(0, percentOf(0f))
        assertEquals(100, percentOf(1f))
        assertEquals(40, percentOf(0.4f))
        assertEquals(-50, percentOf(-0.5f))
    }
}
