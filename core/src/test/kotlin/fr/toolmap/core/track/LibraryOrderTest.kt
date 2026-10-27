package fr.toolmap.core.track

import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.AppSettingsCodec
import fr.toolmap.core.settings.LibraryViewSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryOrderTest {
    private val lauvitel = LibraryKey(LibraryKind.TRACK, "Tour du lac Lauvitel", 1_800, 11_900.0)
    private val muzelle = LibraryKey(LibraryKind.ROUTE, "Col de la Muzelle", 1_500, 14_200.0)
    private val refuge = LibraryKey(LibraryKind.WAYPOINT, "Refuge de la Muzelle", 1_500, null)
    private val etang = LibraryKey(LibraryKind.TRACK, "Étang de Berre", 1_200, 7_400.0)
    private val unnamed = LibraryKey(LibraryKind.TRACK, null, 2_000, 3_000.0)
    private val all = listOf(refuge, etang, unnamed, lauvitel, muzelle)

    @Test
    fun `by date the most recent comes first`() {
        assertEquals(
            listOf(unnamed, lauvitel, refuge, muzelle, etang),
            all.sortedWith(LibraryOrder.comparator(LibrarySort.DATE)),
        )
    }

    @Test
    fun `by name accents and case are ignored and unnamed items go last`() {
        // « Étang » sorts with the E, before « Refuge », not after every unaccented name.
        assertEquals(
            listOf(muzelle, etang, refuge, lauvitel, unnamed),
            all.sortedWith(LibraryOrder.comparator(LibrarySort.NAME)),
        )
    }

    @Test
    fun `by distance the longest comes first and waypoints go last`() {
        assertEquals(
            listOf(muzelle, lauvitel, etang, unnamed, refuge),
            all.sortedWith(LibraryOrder.comparator(LibrarySort.DISTANCE)),
        )
    }

    @Test
    fun `the filter keeps one kind of item, or all of them`() {
        LibraryKind.entries.forEach { assertTrue(LibraryOrder.accepts(LibraryFilter.ALL, it)) }
        assertTrue(LibraryOrder.accepts(LibraryFilter.TRACKS, LibraryKind.TRACK))
        assertFalse(LibraryOrder.accepts(LibraryFilter.TRACKS, LibraryKind.ROUTE))
        assertTrue(LibraryOrder.accepts(LibraryFilter.ROUTES, LibraryKind.ROUTE))
        assertFalse(LibraryOrder.accepts(LibraryFilter.ROUTES, LibraryKind.WAYPOINT))
        assertTrue(LibraryOrder.accepts(LibraryFilter.WAYPOINTS, LibraryKind.WAYPOINT))
        assertFalse(LibraryOrder.accepts(LibraryFilter.WAYPOINTS, LibraryKind.TRACK))
    }

    @Test
    fun `the search wants every word, case and accents ignored`() {
        assertTrue(LibraryOrder.matches("Étang de Berre", "etang"))
        assertTrue(LibraryOrder.matches("Col de la Muzelle", "MUZELLE col"))
        assertFalse(LibraryOrder.matches("Col de la Muzelle", "muzelle refuge"))
        assertFalse(LibraryOrder.matches(null, "col"))
        assertTrue(LibraryOrder.matches(null, "  "))
    }

    @Test
    fun `the sort and the filter are kept with the settings`() {
        assertEquals(LibraryViewSettings(LibrarySort.DATE, LibraryFilter.ALL), AppSettings().library)
        val s = AppSettings(library = LibraryViewSettings(LibrarySort.DISTANCE, LibraryFilter.WAYPOINTS))
        assertEquals(s, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)))
    }
}
