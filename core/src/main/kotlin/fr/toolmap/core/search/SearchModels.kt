package fr.toolmap.core.search

import fr.toolmap.core.geo.Bounds

/** Where a search result comes from. */
enum class SearchSource {
    /** The text was a position: the result is that position. */
    COORDINATES,

    /** One of the user's own tracks, routes or waypoints. */
    LIBRARY,

    /** IGN Géoplateforme geocoder (France: places, addresses). */
    IGN,

    /** OpenStreetMap Nominatim (worldwide). */
    OSM,
}

enum class LibraryKind { TRACK, ROUTE, WAYPOINT }

/** A place the user can go to, whatever found it. */
data class SearchResult(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val source: SearchSource,
    /** What it is, in the words of the source (`sommet`, `peak`); `null` when unknown. */
    val category: String? = null,
    /** The extent to frame when the place is an area or a line; `null` for a point. */
    val bounds: Bounds? = null,
    val libraryKind: LibraryKind? = null,
    val libraryId: Long? = null,
)
