package fr.toolmap.core.search

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.CoordinateParser
import fr.toolmap.core.geo.ParsedCoordinate
import java.text.Normalizer
import java.util.Locale

/** What the search sees of one of the user's own tracks, routes or waypoints. */
data class LibraryItem(
    val kind: LibraryKind,
    val id: Long,
    val name: String?,
    /** Comment and description, searched too. */
    val note: String?,
    /** A point to show: the waypoint itself, the middle of a line's extent. */
    val lat: Double,
    val lon: Double,
    val bounds: Bounds? = null,
)

/** Case- and accent-insensitive comparison of what the user typed with what is stored. */
object SearchText {
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** `Écrins` and `ecrins` fold to the same text. */
    fun fold(text: String): String =
        Normalizer
            .normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(SPACES, " ")
            .trim()

    fun tokens(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /** The words of a folded text: `l'ecrin` is `l` and `ecrin`, so that "ecrin" finds a name where it starts a word. */
    fun words(folded: String): List<String> = folded.split(NOT_WORD).filter { it.isNotEmpty() }
}

/** Search in the user's own library (feature P9.1): offline, instant, nothing leaves the phone. */
object LocalSearch {
    private const val NOTE_EXCERPT = 80
    private const val EXACT = 0
    private const val PREFIX = 1
    private const val WORD_PREFIX = 2
    private const val IN_NAME = 3
    private const val IN_NOTE = 4

    /** Items whose name or note contain every word typed, the closest names first. */
    fun search(
        query: String,
        items: List<LibraryItem>,
        limit: Int = DEFAULT_LIMIT,
    ): List<SearchResult> {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val whole = tokens.joinToString(" ")
        return items
            .mapNotNull { item ->
                val name = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val foldedName = SearchText.fold(name)
                val foldedNote = SearchText.fold(item.note.orEmpty())
                val inName = tokens.all { foldedName.contains(it) }
                val inEither = tokens.all { foldedName.contains(it) || foldedNote.contains(it) }
                if (!inEither) return@mapNotNull null
                val score =
                    when {
                        foldedName == whole -> EXACT
                        foldedName.startsWith(whole) -> PREFIX
                        inName && SearchText.words(foldedName).any { it.startsWith(tokens.first()) } -> WORD_PREFIX
                        inName -> IN_NAME
                        else -> IN_NOTE
                    }
                Ranked(score, foldedName, item, name)
            }.sortedWith(compareBy<Ranked>({ it.score }, { it.foldedName }, { it.item.id }))
            .take(limit)
            .map { (_, _, item, name) ->
                SearchResult(
                    title = name,
                    subtitle = item.note.orEmpty().lineSequence().firstOrNull().orEmpty().take(NOTE_EXCERPT),
                    lat = item.lat,
                    lon = item.lon,
                    source = SearchSource.LIBRARY,
                    bounds = item.bounds,
                    libraryKind = item.kind,
                    libraryId = item.id,
                )
            }
    }

    private data class Ranked(
        val score: Int,
        val foldedName: String,
        val item: LibraryItem,
        val name: String,
    )

    const val DEFAULT_LIMIT = 30
}

/** A search text that is a position (`44.8386, 5.5108`, `31T DQ 52482 11717`): go there. */
object CoordinateQuery {
    fun resolve(text: String): SearchResult? {
        val parsed = CoordinateParser.parse(text) as? ParsedCoordinate.Success ?: return null
        return SearchResult("", "", parsed.lat, parsed.lon, SearchSource.COORDINATES)
    }
}

/** Puts the answers of several sources in one list without showing the same place twice. */
object SearchMerge {
    private const val SAME_PLACE_METERS = 300.0
    private val ARTICLES = setOf("le", "la", "les", "l", "un", "une", "du", "de", "des", "d", "the")

    /** [groups] in order of preference: a later result is dropped when an earlier one has the same name nearby. */
    fun merge(
        groups: List<List<SearchResult>>,
        limit: Int,
    ): List<SearchResult> {
        val kept = ArrayList<SearchResult>()
        for (group in groups) {
            for (result in group) {
                if (kept.size >= limit) return kept
                if (kept.none { sameAs(it, result) }) kept += result
            }
        }
        return kept
    }

    private fun sameAs(
        a: SearchResult,
        b: SearchResult,
    ): Boolean =
        key(a.title) == key(b.title) &&
            fr.toolmap.core.track.Geodesy.distance(a.lat, a.lon, b.lat, b.lon) <= SAME_PLACE_METERS

    private fun key(title: String): String =
        SearchText.words(SearchText.fold(title)).filterNot { it in ARTICLES }.joinToString(" ")
}
