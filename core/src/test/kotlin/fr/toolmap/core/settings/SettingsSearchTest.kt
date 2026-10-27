package fr.toolmap.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test
    fun `the words typed are folded and lose their plural mark`() {
        assertEquals(listOf("repere", "trace"), SettingsSearch.terms("  Repères   TRACES "))
        assertEquals(listOf("cap"), SettingsSearch.terms("cap"))
        assertEquals(listOf("gps"), SettingsSearch.terms("GPS"))
        assertEquals(listOf("mil"), SettingsSearch.terms("mils"))
        assertEquals(listOf("niveau"), SettingsSearch.terms("niveaux"))
        assertEquals(emptyList(), SettingsSearch.terms("   "))
    }

    @Test
    fun `every word must be in the title, the explanation or an option`() {
        val texts = listOf("Unité de cap", "Degrés (360° par tour)", "Mils OTAN (6 400 par tour)")
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("cap"), texts))
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("unite otan"), texts))
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("DEGRES"), texts))
        assertFalse(SettingsSearch.matches(SettingsSearch.terms("cap pente"), texts))
        assertFalse(SettingsSearch.matches(emptyList(), texts))
    }

    @Test
    fun `a plural typed finds the singular, a singular finds the plural`() {
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("gants"), listOf("Mode gants")))
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("gant"), listOf("Mode gants")))
        assertTrue(SettingsSearch.matches(SettingsSearch.terms("traces"), listOf("Style de la trace")))
    }

    @Test
    fun `the bold goes on the word found, in the text with its accents`() {
        val text = "Affichage du cap GNSS"
        assertEquals(listOf(13..15), SettingsSearch.highlights(text, SettingsSearch.terms("cap")))
        assertEquals("cap", text.substring(13..15))
        val accents = "Écart toléré"
        assertEquals(listOf(0..4, 6..11), SettingsSearch.highlights(accents, SettingsSearch.terms("ecart TOLERE")))
    }

    @Test
    fun `each occurrence is in bold, and ranges that touch are merged`() {
        assertEquals(listOf(0..2, 7..9), SettingsSearch.highlights("cap et cap", SettingsSearch.terms("cap")))
        assertEquals(listOf(0..5), SettingsSearch.highlights("nordet", listOf("nord", "rdet")))
        assertEquals(emptyList(), SettingsSearch.highlights("Thème", SettingsSearch.terms("cap")))
    }

    @Test
    fun `the folded text keeps the length of the text`() {
        val text = "Élévation, œil, Ça"
        assertEquals(text.length, SettingsSearch.fold(text).length)
        assertEquals("elevation, œil, ca", SettingsSearch.fold(text))
    }
}
