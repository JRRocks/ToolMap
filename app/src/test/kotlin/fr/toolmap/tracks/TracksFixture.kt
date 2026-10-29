package fr.toolmap.tracks

import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.ElevationTiles
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackDisplay
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.testing.stopTurningLooper
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.createTempDirectory

/**
 * A library like the one of the preview of P29.4 (`docs/previews/design-04-traces.html`): a folder « Chartreuse » with
 * two tracks (one hidden) and two waypoints, then a route, the track « Fort Barraux – Les Trois Têtes » (8,64 km,
 * 742 m up and down) and three waypoints; for the tests and renders of the tracks screens.
 */
class TracksFixture {
    private val dir: File = createTempDirectory("tracks-ui").toFile()
    val db: TrackDatabase = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
    val repository = TrackRepository(db)
    val vm =
        TracksViewModel(
            repository,
            TrackDisplay(repository),
            ElevationCompleter(ElevationTiles { _, _, _ -> null }),
            SettingsStore(File(dir, "settings.json")),
        )

    var fbt = 0L
        private set
    var tetes = 0L
        private set
    var route = 0L
        private set

    /** Fills the library; [empty] leaves it empty. */
    fun fill(empty: Boolean = false) =
        runBlocking {
            if (empty) return@runBlocking
            val folder = requireNotNull(repository.createFolder(null, "Chartreuse"))
            val granier = repository.createTrack("Col du Granier", listOf(line(45.40, 5.90, 11.9, 905.0)), style(BLUE))
            val boucle =
                repository.createTrack(
                    "Boucle du Pont du Furet",
                    listOf(line(45.41, 5.95, 5.2, 310.0)),
                    style(GREEN),
                )
            val parking =
                repository.createWaypoint(
                    Waypoint(45.43, 5.96, 330.0, at(18), "Parking de Chapareillan", sym = "Parking"),
                )
            val cascade =
                repository.createWaypoint(
                    Waypoint(45.44, 5.97, 520.0, at(12), "Cascade du Furet", sym = "Waterfall"),
                )
            repository.moveItem(ItemRef(ItemKind.TRACK, granier), folder)
            repository.moveItem(ItemRef(ItemKind.TRACK, boucle), folder)
            repository.moveItem(ItemRef(ItemKind.WAYPOINT, parking), folder)
            repository.moveItem(ItemRef(ItemKind.WAYPOINT, cascade), folder)
            repository.setVisible(ItemRef(ItemKind.TRACK, boucle), false)
            route =
                repository.createRoute(
                    "Chapareillan → Col du Granier",
                    listOf(Waypoint(45.43, 5.96, 300.0), Waypoint(45.50, 5.90, 1100.0)),
                    PathStyle(PURPLE, dashed = true),
                )
            fbt = repository.createTrack("Fort Barraux – Les Trois Têtes", listOf(fortBarraux()), style(ORANGE))
            repository.createWaypoint(Waypoint(45.45, 5.98, 291.0, at(21), "Fort Barraux", sym = "Castle"))
            tetes = repository.createWaypoint(Waypoint(45.49, 5.99, 1020.0, at(21), "Les Trois Têtes", sym = "Peak"))
            repository.createWaypoint(Waypoint(45.42, 5.97, 410.0, at(12), "Pont du Furet"))
        }

    /**
     * Whether the view model's library holds all of [fill]: it joins four Room flows that catch up one by one, so five
     * waypoints can be read before the track « Fort Barraux » (a sheet of a track not in the library draws nothing).
     */
    fun isFilled(library: TrackLibrary?) =
        library != null &&
            library.folders.size == 1 &&
            library.tracks.size == 3 &&
            library.routes.size == 1 &&
            library.waypoints.size == 5

    /**
     * Waits until [done], letting the main thread run with [idle] (the view model reads the database there): the
     * compose rule's own `waitUntil` does not turn the main looper before anything is shown.
     */
    fun await(
        idle: () -> Unit,
        done: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the library" }
            idle()
            Thread.sleep(POLL_MS)
        }
    }

    /** Stops the view model (whose coroutines run on the main looper) before the database closes. */
    fun close() {
        vm.stopTurningLooper()
        db.close()
        dir.deleteRecursively()
    }

    private fun style(color: Int) = PathStyle(color)

    /** A straight line north of [km], climbing [up] metres then coming down as much. */
    private fun line(
        lat: Double,
        lon: Double,
        km: Double,
        up: Double,
    ): List<TrackPoint> =
        (0..20).map { i ->
            val f = i / 20.0
            TrackPoint(lat + km * f / KM_PER_DEGREE, lon, 300.0 + up * (1 - Math.abs(2 * f - 1)), at(18) + i * 60_000L)
        }

    /** 278 m → 1 020 m → 278 m over 8,64 km, walked on 21 October from 09:12. */
    private fun fortBarraux(): List<TrackPoint> =
        PROFILE.mapIndexed { i, (km, ele) ->
            TrackPoint(45.45 + km / KM_PER_DEGREE, 5.98, ele, start + i * 11 * 60_000L)
        }

    private fun at(day: Int): Long =
        LocalDateTime.of(2026, 10, day, 9, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val start = at(21)

    private companion object {
        const val KM_PER_DEGREE = 111.2
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val BLUE = 0xFF1E88E5.toInt()
        const val GREEN = 0xFF43A047.toInt()
        const val PURPLE = 0xFF8E24AA.toInt()
        const val ORANGE = 0xFFFB8C00.toInt()
        val PROFILE =
            listOf(
                0.0 to 278.0,
                0.5 to 286.0,
                1.0 to 300.0,
                1.5 to 340.0,
                2.0 to 400.0,
                2.5 to 470.0,
                2.8 to 540.0,
                3.2 to 612.0,
                3.6 to 684.0,
                3.9 to 770.0,
                4.2 to 860.0,
                4.5 to 950.0,
                4.8 to 1020.0,
                5.1 to 960.0,
                5.5 to 860.0,
                6.0 to 740.0,
                6.5 to 600.0,
                7.0 to 480.0,
                7.5 to 380.0,
                8.0 to 310.0,
                8.3 to 290.0,
                8.64 to 278.0,
            )
    }
}
