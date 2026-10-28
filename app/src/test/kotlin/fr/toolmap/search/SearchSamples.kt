package fr.toolmap.search

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.search.SearchResult
import fr.toolmap.core.search.SearchSource
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.search.GeocodeOutcome
import fr.toolmap.data.search.Geocoder
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.testing.stopAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/**
 * The search of the preview of P29.10 (`docs/previews/design-10-outils.html`): the waypoint « Fort Barraux » on the
 * phone, Barraux and its fort online, the map centred near them; for the tests and the renders of « Recherche ».
 */
class SearchSamples {
    /** A geocoder that answers [outcome] and counts what it was asked. */
    class FakeGeocoder : Geocoder {
        var asked = 0
        var outcome = GeocodeOutcome(ONLINE, emptyList())

        override suspend fun search(
            query: String,
            near: LonLat?,
            languageTag: String,
            limit: Int,
        ): GeocodeOutcome {
            asked++
            return outcome
        }
    }

    private val db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
    val repo = TrackRepository(db)
    val geocoder = FakeGeocoder()
    val vm = SearchViewModel(repo, geocoder, OfflineMode())

    init {
        runBlocking {
            repo.createWaypoint(Waypoint(45.4486, 5.9586, name = "Fort Barraux"))
            repo.library.first { it.waypoints.isNotEmpty() }
        }
        vm.open(45.4520, 5.9550)
    }

    fun close() {
        vm.stopAndJoin()
        db.close()
    }

    companion object {
        val ONLINE =
            listOf(
                SearchResult("Barraux", "38530 Barraux", 45.4417, 5.9789, SearchSource.IGN),
                SearchResult(
                    "Fort Barraux",
                    "38530 Barraux",
                    45.4486,
                    5.9586,
                    SearchSource.IGN,
                    "fort, blockhaus, casemate",
                ),
                SearchResult(
                    "Barraux",
                    "Le Touvet, Grenoble, Isère, France",
                    45.4419,
                    5.9790,
                    SearchSource.OSM,
                    "Village",
                ),
            )
    }
}
