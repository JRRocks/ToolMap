package fr.toolmap.core.search

import fr.toolmap.core.geo.Bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchTest {
    private fun item(
        id: Long,
        name: String?,
        note: String? = null,
        kind: LibraryKind = LibraryKind.WAYPOINT,
    ) = LibraryItem(kind, id, name, note, 45.0 + id / 100.0, 6.0)

    private val library =
        listOf(
            item(1, "Refuge de l'Écrin", "Gardé en été\nRéservation obligatoire"),
            item(2, "Col du Lautaret"),
            item(3, "Sommet des Écrins", kind = LibraryKind.TRACK),
            item(4, "Pique-nique", "près du refuge de l'Écrin"),
            item(5, null, "sans nom"),
            item(6, "   "),
            item(7, "écrin", kind = LibraryKind.ROUTE),
        )

    private fun names(query: String) = LocalSearch.search(query, library).map { it.libraryId }

    @Test
    fun `accents and case do not matter in either direction`() {
        assertEquals(listOf(7L, 1L, 3L, 4L), names("ECRIN"), "exact name, then prefix, then word, then note")
        assertEquals(names("ecrin"), names("Écrin"))
        assertEquals(listOf(2L), names("LAUTARET"))
    }

    @Test
    fun `every word typed must be found, in the name or in the note`() {
        assertEquals(listOf(1L, 4L), names("refuge ecrin"), "the name of the first, the note of the second")
        assertEquals(listOf(1L), names("refuge gardé"), "one word from the name and one from the note")
        assertEquals(listOf(1L), names("reservation obligatoire"), "the note is searched too")
        assertEquals(emptyList(), names("refuge lautaret"))
    }

    @Test
    fun `ranking puts the exact name first, then names starting with it, then words, then notes`() {
        val items =
            listOf(
                item(1, "Pont du col"),
                item(2, "Col"),
                item(3, "Col de la Croix"),
                item(4, "Sentier", "mène au col"),
                item(5, "Le col rouge"),
            )
        val order = LocalSearch.search("col", items).map { it.libraryId }
        assertEquals(listOf(2L, 3L, 5L, 1L, 4L), order)
    }

    @Test
    fun `unnamed items and blank queries give nothing`() {
        assertEquals(emptyList(), names(""))
        assertEquals(emptyList(), names("   "))
        assertTrue(LocalSearch.search("sans nom", library).isEmpty(), "an unnamed item cannot be shown")
    }

    @Test
    fun `a result carries what the screen needs to open the item`() {
        val line =
            LibraryItem(
                LibraryKind.TRACK,
                9,
                "Traversée",
                "Trois jours\nEn autonomie",
                45.1,
                6.1,
                Bounds(6.0, 45.0, 6.2, 45.2),
            )
        val result = LocalSearch.search("traversee", listOf(line)).single()
        assertEquals("Traversée", result.title)
        assertEquals("Trois jours", result.subtitle, "only the first line of the note")
        assertEquals(LibraryKind.TRACK, result.libraryKind)
        assertEquals(9L, result.libraryId)
        assertEquals(SearchSource.LIBRARY, result.source)
        assertEquals(Bounds(6.0, 45.0, 6.2, 45.2), result.bounds)
        assertNull(LocalSearch.search("x", listOf(line)).firstOrNull())
    }

    @Test
    fun `the number of results is limited`() {
        val many = List(100) { item(it.toLong(), "Point $it") }
        assertEquals(30, LocalSearch.search("point", many).size)
        assertEquals(5, LocalSearch.search("point", many, limit = 5).size)
    }

    @Test
    fun `fold removes accents, case and runs of spaces`() {
        assertEquals("a la gare", SearchText.fold("  À   La  GARE "))
        assertEquals("ssss", SearchText.fold("SSss"))
        assertEquals(listOf("col", "du", "lautaret"), SearchText.tokens("Col  du Lautaret"))
    }

    @Test
    fun `a position typed in the search box is a result, a place name is not`() {
        val dd = CoordinateQuery.resolve("44.8386, 5.5108")!!
        assertEquals(44.8386, dd.lat, 1e-9)
        assertEquals(5.5108, dd.lon, 1e-9)
        assertEquals(SearchSource.COORDINATES, dd.source)
        val utm = CoordinateQuery.resolve("31T 452482 E 5011717 N")!!
        assertEquals(45.2, utm.lat, 0.1)
        assertNull(CoordinateQuery.resolve("Grand Veymont"))
        assertNull(CoordinateQuery.resolve(""))
        assertNull(CoordinateQuery.resolve("95, 5"), "an impossible latitude is not a place")
    }
}

class SearchMergeTest {
    private fun result(
        title: String,
        lat: Double,
        lon: Double,
        source: SearchSource,
    ) = SearchResult(title, "", lat, lon, source)

    @Test
    fun `the same place from two sources is shown once, the first source wins`() {
        val ign = result("le Grand Veymont", 44.869704, 5.526604, SearchSource.IGN)
        val osm = result("Grand Veymont", 44.8697393, 5.5267039, SearchSource.OSM)
        val merged = SearchMerge.merge(listOf(listOf(ign), listOf(osm)), limit = 10)
        assertEquals(listOf(ign), merged)
    }

    @Test
    fun `same name far apart or near with another name are different places`() {
        val a = result("Col de la Croix", 45.0, 6.0, SearchSource.IGN)
        val farther = result("Col de la Croix", 45.5, 6.0, SearchSource.OSM)
        val other = result("Refuge", 45.0, 6.0, SearchSource.OSM)
        assertEquals(3, SearchMerge.merge(listOf(listOf(a), listOf(farther, other)), limit = 10).size)
    }

    @Test
    fun `articles and accents do not make two names different`() {
        val a = result("L'Écrin", 45.0, 6.0, SearchSource.IGN)
        val b = result("Ecrin", 45.0001, 6.0, SearchSource.OSM)
        assertEquals(listOf(a), SearchMerge.merge(listOf(listOf(a), listOf(b)), limit = 10))
    }

    @Test
    fun `the list is cut at the limit and keeps the order of the groups`() {
        val one = List(5) { result("A$it", 45.0 + it, 6.0, SearchSource.IGN) }
        val two = List(5) { result("B$it", 45.0 + it, 7.0, SearchSource.OSM) }
        val merged = SearchMerge.merge(listOf(one, two), limit = 7)
        assertEquals(listOf("A0", "A1", "A2", "A3", "A4", "B0", "B1"), merged.map { it.title })
    }
}
