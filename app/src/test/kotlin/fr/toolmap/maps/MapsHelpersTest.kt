package fr.toolmap.maps

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MapsHelpersTest {
    @Test
    fun `initials come from the first two words`() {
        assertEquals("IT", initialsOf("IGN topo"))
        assertEquals("OS", initialsOf("OpenStreetMap standard"))
        assertEquals("O", initialsOf("OpenTopoMap"))
    }

    @Test
    fun `initials skip separators and punctuation`() {
        assertEquals("PA", initialsOf("Photos - aériennes"))
        assertEquals("CP", initialsOf("  (carte) pentes_ski "))
        assertEquals("ÉM", initialsOf("état-major"))
    }

    @Test
    fun `initials of a name without letters is a question mark`() {
        assertEquals("?", initialsOf(""))
        assertEquals("?", initialsOf(" - _ "))
    }

    @Test
    fun `placeholder colour is stable and differs between maps`() {
        assertEquals(placeholderColor("osm"), placeholderColor("osm"))
        assertNotEquals(placeholderColor("osm"), placeholderColor("ign-maps"))
    }

    @Test
    fun `placeholder colour accepts any id`() {
        placeholderColor("")
        placeholderColor("É".repeat(40))
        placeholderColor("-2147483648")
    }

    @Test
    fun `percent truncates the fraction`() {
        assertEquals(0, percentOf(0f))
        assertEquals(100, percentOf(1f))
        assertEquals(40, percentOf(0.4f))
        assertEquals(-50, percentOf(-0.5f))
    }
}
