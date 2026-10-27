package fr.toolmap.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomSetTest {
    @Test
    fun `parses a range`() {
        val zooms = ZoomSet.parse("1-17")
        assertEquals(1, zooms.min)
        assertEquals(17, zooms.max)
        assertEquals(17, zooms.size)
        assertTrue(zooms.isContiguous)
    }

    @Test
    fun `parses lists and mixed forms`() {
        assertEquals(listOf(1, 3, 5), ZoomSet.parse("1,3,5").toList())
        assertEquals(listOf(1, 2, 3, 5, 7, 8, 9), ZoomSet.parse("1-3,5,7-9").toList())
        assertEquals(listOf(6, 7, 8), ZoomSet.parse(" 6, 7 ,8 ").toList())
    }

    @Test
    fun `non contiguous sets are detected`() {
        val zooms = ZoomSet.parse("2,4,6")
        assertFalse(zooms.isContiguous)
        assertTrue(4 in zooms)
        assertFalse(5 in zooms)
    }

    @Test
    fun `canonical spec round trips`() {
        for (spec in listOf("1-18", "1-3,5,7-9", "4", "2,4,6")) {
            assertEquals(spec, ZoomSet.parse(spec).toSpec())
        }
        assertEquals("1-3", ZoomSet.parse("3,1,2,2").toSpec())
    }

    @Test
    fun `rejects invalid specs`() {
        for (spec in listOf("", "  ", "a", "5-3", "1-2-3", "-1", "31", "1-40")) {
            assertFailsWith<IllegalArgumentException>("spec '$spec'") { ZoomSet.parse(spec) }
        }
    }
}
