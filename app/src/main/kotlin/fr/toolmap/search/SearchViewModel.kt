package fr.toolmap.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.search.CoordinateQuery
import fr.toolmap.core.search.LibraryItem
import fr.toolmap.core.search.LibraryKind
import fr.toolmap.core.search.LocalSearch
import fr.toolmap.core.search.SearchResult
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.search.Geocoder
import fr.toolmap.data.search.ProviderFailure
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the search screen shows. */
data class SearchUi(
    val query: String = "",
    /** A typed position, then the user's own items: instant, nothing leaves the phone. */
    val here: List<SearchResult> = emptyList(),
    /** What the geocoders found for the text last submitted. */
    val online: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    /** The text was submitted and the answer is in (found or not). */
    val searched: Boolean = false,
    /** The text was submitted in offline mode: nothing was sent. */
    val skippedOffline: Boolean = false,
    val failures: List<ProviderFailure> = emptyList(),
)

/**
 * Search for a place (feature P9.1): a position typed in the box, the user's own tracks, routes and waypoints (live,
 * as the text changes), and the geocoders (only when the user submits: never while typing, never in offline mode).
 */
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: TrackRepository,
        private val geocoder: Geocoder,
        private val offline: OfflineMode,
    ) : ViewModel() {
        private val mutableUi = MutableStateFlow(SearchUi())
        val ui: StateFlow<SearchUi> = mutableUi.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        private val mutableCenter = MutableStateFlow<Pair<Double, Double>?>(null)

        /** The map centre when the screen opened (latitude, longitude), for the distance shown on each result. */
        val center: StateFlow<Pair<Double, Double>?> = mutableCenter.asStateFlow()

        private var items: List<LibraryItem> = emptyList()
        private var near: LonLat? = null
        private var job: Job? = null

        init {
            viewModelScope.launch {
                repository.library.collect { library ->
                    items = library.toItems()
                    refreshHere()
                }
            }
        }

        /** The screen opens: the map centre favours the places close to it. */
        fun open(
            lat: Double,
            lon: Double,
        ) {
            near = LonLat(lon, lat)
            mutableCenter.value = lat to lon
        }

        fun onQueryChange(text: String) {
            job?.cancel()
            mutableUi.update { SearchUi(query = text) }
            refreshHere()
        }

        /** The user asked for the geocoders (keyboard "search"). */
        fun submit() {
            val text = mutableUi.value.query.trim()
            if (text.isEmpty()) return
            job?.cancel()
            if (offline.isOffline()) {
                mutableUi.update {
                    it.copy(online = emptyList(), searching = false, searched = false, skippedOffline = true)
                }
                return
            }
            mutableUi.update {
                it.copy(
                    searching = true,
                    searched = false,
                    skippedOffline = false,
                    failures = emptyList(),
                    online = emptyList(),
                )
            }
            job =
                viewModelScope.launch {
                    val outcome = geocoder.search(text, near, Locale.getDefault().language)
                    mutableUi.update {
                        if (it.query.trim() != text) {
                            it
                        } else {
                            it.copy(
                                online = outcome.results,
                                failures = outcome.failures,
                                searching = false,
                                searched = true,
                            )
                        }
                    }
                }
        }

        /** Keeps a found place as a waypoint of the library. */
        fun saveAsWaypoint(result: SearchResult) {
            viewModelScope.launch {
                repository.createWaypoint(
                    Waypoint(
                        lat = result.lat,
                        lon = result.lon,
                        name = result.title.takeIf { it.isNotBlank() },
                        desc = result.subtitle.takeIf { it.isNotBlank() },
                    ),
                )
                mutableMessages.tryEmit(UiMessage(R.string.waypoint_saved))
            }
        }

        private fun refreshHere() {
            val query = mutableUi.value.query
            val position = CoordinateQuery.resolve(query)
            val found = LocalSearch.search(query, items)
            mutableUi.update { it.copy(here = listOfNotNull(position) + found) }
        }

        private fun TrackLibrary.toItems(): List<LibraryItem> =
            tracks.map {
                LibraryItem(
                    LibraryKind.TRACK,
                    it.id,
                    it.name,
                    listOfNotNull(it.cmt, it.desc).joinToString("\n"),
                    (it.minLat + it.maxLat) / 2,
                    (it.minLon + it.maxLon) / 2,
                    Bounds(it.minLon, it.minLat, it.maxLon, it.maxLat),
                )
            } +
                routes.map {
                    LibraryItem(
                        LibraryKind.ROUTE,
                        it.id,
                        it.name,
                        listOfNotNull(it.cmt, it.desc).joinToString("\n"),
                        (it.minLat + it.maxLat) / 2,
                        (it.minLon + it.maxLon) / 2,
                        Bounds(it.minLon, it.minLat, it.maxLon, it.maxLat),
                    )
                } +
                waypoints.map {
                    LibraryItem(
                        LibraryKind.WAYPOINT,
                        it.id,
                        it.name,
                        listOfNotNull(it.cmt, it.desc).joinToString("\n"),
                        it.lat,
                        it.lon,
                    )
                }
    }
