package fr.toolmap.core.track

import kotlinx.serialization.Serializable
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** The order of the explorer of the tracks and waypoints (F7): the most recent first, by name, or the longest first. */
@Serializable
enum class LibrarySort { DATE, NAME, DISTANCE }

/** The kinds of item the explorer shows (F7). */
@Serializable
enum class LibraryFilter { ALL, TRACKS, ROUTES, WAYPOINTS }

enum class LibraryKind { TRACK, ROUTE, WAYPOINT }

/** What the explorer sorts and searches one item by. */
data class LibraryKey(
    val kind: LibraryKind,
    val name: String?,
    /** When the item was recorded, or else imported. */
    val timeMillis: Long,
    /** `null` for a waypoint. */
    val distanceMeters: Double?,
)

/** Sort, filter and search of the explorer (F7); the folders are not sorted here, they stay on top. */
object LibraryOrder {
    fun accepts(
        filter: LibraryFilter,
        kind: LibraryKind,
    ): Boolean =
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.TRACKS -> kind == LibraryKind.TRACK
            LibraryFilter.ROUTES -> kind == LibraryKind.ROUTE
            LibraryFilter.WAYPOINTS -> kind == LibraryKind.WAYPOINT
        }

    /**
     * Recent first; or by name, case and accents ignored, unnamed items last; or longest first, waypoints last. Ties
     * keep the most recent first.
     */
    fun comparator(
        sort: LibrarySort,
        locale: Locale = Locale.FRENCH,
    ): Comparator<LibraryKey> {
        val recent = compareByDescending<LibraryKey> { it.timeMillis }
        return when (sort) {
            LibrarySort.DATE -> {
                recent
            }

            LibrarySort.NAME -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
                compareBy<LibraryKey> { it.name.isNullOrBlank() }
                    .then { a, b -> collator.compare(a.name.orEmpty().trim(), b.name.orEmpty().trim()) }
                    .then(recent)
            }

            LibrarySort.DISTANCE -> {
                compareBy<LibraryKey> { it.distanceMeters == null }
                    .thenByDescending { it.distanceMeters ?: 0.0 }
                    .then(recent)
            }
        }
    }

    /** True when [name] holds every word of [query], case and accents ignored; a blank query matches everything. */
    fun matches(
        name: String?,
        query: String,
    ): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val text = fold(name.orEmpty())
        return words.all { it in text }
    }

    /** Lower case without accents: « Écrins » and « ecrins » match. */
    private fun fold(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}
