package fr.toolmap.data.tracks

import fr.toolmap.core.route.LegMode
import fr.toolmap.core.route.LegState
import fr.toolmap.core.route.RouteLeg
import fr.toolmap.core.route.RoutePlan
import fr.toolmap.core.route.RoutePlanGpx
import fr.toolmap.core.route.RoutePoi
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.track.GpxReader
import fr.toolmap.core.track.GpxWriter
import fr.toolmap.core.track.KmlReader
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.Route
import fr.toolmap.core.track.Track
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStatsCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackRepositoryTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db, now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fixture(path: String): ByteArray {
        val dir = requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir not set by Gradle" }
        return File(dir, path).readBytes()
    }

    private fun synthetic(
        points: Int,
        name: String = "synthetic",
        withTime: Boolean = true,
    ) = Track(
        name = name,
        segments =
            listOf(
                List(points) { i ->
                    TrackPoint(
                        lat = 45.0 + i * 0.0001,
                        lon = 6.0 + i * 0.0001,
                        ele = 1000.0 + i % 50,
                        timeMillis = if (withTime) 1_700_000_000_000L + i * 1000L else null,
                    )
                },
            ),
    )

    private fun runDb(block: suspend () -> Unit) = runBlocking { block() }

    private fun assertSamePoints(
        expected: List<TrackPoint>,
        actual: List<TrackPoint>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertTrue(abs(a.lat - b.lat) < 1e-6 && abs(a.lon - b.lon) < 1e-6, "position $a vs $b")
            assertEquals(a.ele == null, b.ele == null)
            if (a.ele != null && b.ele != null) assertTrue(abs(a.ele!! - b.ele!!) < 1e-2, "elevation $a vs $b")
            assertEquals(a.timeMillis, b.timeMillis)
            assertEquals(a.extras, b.extras)
        }
    }

    @Test
    fun `real GPX files survive import, storage and export`() =
        runDb {
            for (file in listOf("Mojstrovka", "cerknicko-jezero", "korita-zbevnica", "garmin-connect-run")) {
                val source = GpxReader.read(fixture("gpx/$file.gpx"))
                val summary = repo.import(source, "$file.gpx")
                assertEquals(source.tracks.size, summary.tracks, file)
                assertEquals(source.waypoints.size, summary.waypoints, file)
                assertEquals(source.routes.size, summary.routes, file)

                val exported = repo.document(TrackSelection(folderIds = listOf(summary.folderId)))
                // The export is a real GPX file: read it back to prove it.
                val again = GpxReader.read(GpxWriter.toBytes(exported, "ToolMap"))
                assertEquals(source.tracks.size, again.tracks.size, file)
                assertEquals(
                    source.waypoints.map {
                        it.name
                    },
                    again.waypoints.map { it.name },
                    file,
                )
                source.tracks.zip(again.tracks).forEach { (a, b) ->
                    assertEquals(a.name, b.name, file)
                    assertEquals(a.segments.size, b.segments.size, file)
                    a.segments.zip(b.segments).forEach { (x, y) -> assertSamePoints(x, y) }
                    assertEquals(a.extras, b.extras, file)
                }
            }
        }

    @Test
    fun `statistics are computed once and cached on the track`() =
        runDb {
            val source = GpxReader.read(fixture("gpx/Mojstrovka.gpx"))
            repo.import(source, "Mojstrovka.gpx")
            val entity = repo.library.first().tracks.single()
            val expected = TrackStatsCalculator.compute(source.tracks.single().segments)
            assertEquals(expected.pointCount, entity.pointCount)
            assertEquals(expected.distance2dMeters, entity.distanceMeters, 1.0)
            assertEquals(expected.ascentMeters, entity.ascentMeters!!, 1.0)
            assertTrue(entity.minLat < entity.maxLat && entity.minLon < entity.maxLon)
            assertEquals(expected.startTimeMillis, entity.startTimeMillis)
        }

    @Test
    fun `long tracks are split in chunks and read back whole`() =
        runDb {
            val track = synthetic(2 * TrackRepository.CHUNK_POINTS + 1)
            val summary = repo.import(TrackDocument(tracks = listOf(track)), "long.gpx")
            val id = repo.library.first().tracks.single().id
            assertEquals(3, db.dao().chunks(id).size)
            assertEquals(track.pointCount, summary.points)
            assertSamePoints(track.segments.single(), repo.loadTrack(id)!!.segments.single())
        }

    @Test
    fun `a track without elevation has no climb figures`() =
        runDb {
            val flat =
                synthetic(10).let { t ->
                    t.copy(segments = t.segments.map { s -> s.map { it.copy(ele = null) } })
                }
            repo.import(TrackDocument(tracks = listOf(flat)), "flat.gpx")
            val entity = repo.library.first().tracks.single()
            assertEquals(null, entity.ascentMeters)
            assertEquals(null, entity.descentMeters)
        }

    @Test
    fun `an import makes one folder named after the document or the file`() =
        runDb {
            repo.import(TrackDocument(name = "Tour du Mont Blanc", tracks = listOf(synthetic(5))), "x.gpx")
            repo.import(TrackDocument(tracks = listOf(synthetic(5))), "week-end.gpx")
            repo.import(TrackDocument(tracks = listOf(synthetic(5))), null)
            val names = repo.library.first().folders.map { it.name }
            assertEquals(setOf("Tour du Mont Blanc", "week-end", TrackRepository.DEFAULT_FOLDER_NAME), names.toSet())
        }

    @Test
    fun `tracks of one import get different colours`() =
        runDb {
            val doc = TrackDocument(tracks = listOf(synthetic(5, "a"), synthetic(5, "b"), synthetic(5, "c")))
            repo.import(doc, "colours.gpx")
            val colours = repo.library.first().tracks.map { it.colorArgb }
            assertEquals(3, colours.toSet().size)
            assertTrue(PathStyle.PALETTE.containsAll(colours))
        }

    @Test
    fun `KML folders become nested folders and export to one flat file`() =
        runDb {
            val source = KmlReader.read(fixture("kml/folders-styles-gxtrack.kml"))
            val summary = repo.import(source, "sortie.kml")
            val lib = repo.library.first()
            val root = lib.folders.single { it.id == summary.folderId }
            assertEquals("Sortie test", root.name)
            val day = lib.folders.single { it.name == "Jour 1" }
            val points = lib.folders.single { it.name == "Points" }
            assertEquals(root.id, day.parentId)
            assertEquals(day.id, points.parentId)
            assertEquals("Sortie test / Jour 1 / Points", lib.pathOf(points.id))
            assertEquals(points.id, lib.waypoints.single().folderId)
            assertEquals(day.id, lib.tracks.single { it.name == "Montée" }.folderId)
            assertEquals(root.id, lib.tracks.single { it.name == "Enregistrement GPS" }.folderId)
            // The style the file gave is kept.
            assertEquals(0xFFFF0000.toInt(), lib.tracks.single { it.name == "Montée" }.colorArgb)

            val exported = repo.document(TrackSelection(folderIds = listOf(summary.folderId)))
            assertEquals(source.tracks.size, exported.tracks.size)
            assertEquals(source.waypoints.size, exported.waypoints.size)
            assertEquals("Sortie test", exported.name)
        }

    @Test
    fun `hiding a folder hides what is in it at any depth`() =
        runDb {
            val source = KmlReader.read(fixture("kml/folders-styles-gxtrack.kml"))
            repo.import(source, "sortie.kml")
            var lib = repo.library.first()
            val day = lib.folders.single { it.name == "Jour 1" }
            val refuge = lib.waypoints.single()
            assertTrue(lib.isShown(refuge.folderId, refuge.visible))
            repo.setFolderVisible(day.id, false)
            lib = repo.library.first()
            assertFalse(lib.isShown(refuge.folderId, refuge.visible))
            val recording = lib.tracks.single { it.name == "Enregistrement GPS" }
            assertTrue(lib.isShown(recording.folderId, recording.visible))
            // An item hidden on its own stays hidden when the folder comes back.
            repo.setVisible(ItemRef(ItemKind.WAYPOINT, refuge.id), false)
            repo.setFolderVisible(day.id, true)
            lib = repo.library.first()
            assertFalse(lib.isShown(refuge.folderId, lib.waypoints.single().visible))
        }

    @Test
    fun `deleting a folder deletes its contents and their points`() =
        runDb {
            val source = KmlReader.read(fixture("kml/folders-styles-gxtrack.kml"))
            repo.import(source, "sortie.kml")
            val other = repo.import(TrackDocument(tracks = listOf(synthetic(5, "kept"))), "other.gpx")
            val lib = repo.library.first()
            repo.deleteFolder(lib.folders.single { it.name == "Jour 1" }.id)
            val after = repo.library.first()
            assertEquals(setOf<String?>("Enregistrement GPS", "kept"), after.tracks.map { it.name }.toSet())
            assertTrue(after.waypoints.isEmpty())
            assertEquals(1, after.tracks.count { it.folderId == other.folderId })
            // Chunks of the deleted tracks went with them.
            val deleted = lib.tracks.single { it.name == "Montée" }
            assertTrue(db.dao().chunks(deleted.id).isEmpty())
        }

    @Test
    fun `rename, restyle and delete an item`() =
        runDb {
            repo.import(TrackDocument(tracks = listOf(synthetic(5, "old"))), "t.gpx")
            val ref = ItemRef(ItemKind.TRACK, repo.library.first().tracks.single().id)
            repo.rename(ref, "  new name ")
            repo.setStyle(ref, PathStyle(0xFF123456.toInt(), 7f, dashed = true, arrows = true))
            val e = repo.library.first().tracks.single()
            assertEquals("new name", e.name)
            assertEquals(0xFF123456.toInt(), e.colorArgb)
            assertEquals(7f, e.widthDp)
            assertTrue(e.dashed && e.arrows)
            repo.delete(ref)
            assertTrue(repo.library.first().tracks.isEmpty())
        }

    @Test
    fun `routes and waypoints are stored and exported`() =
        runDb {
            val source = GpxReader.read(fixture("gpx/cerknicko-jezero.gpx"))
            val summary = repo.import(source, "c.gpx")
            val lib = repo.library.first()
            assertEquals(source.waypoints.size, lib.waypoints.size)
            val w = lib.waypoints.first()
            val loaded = assertNotNull(repo.loadWaypoint(w.id))
            assertEquals(w.name, loaded.name)
            assertEquals(source.waypoints.size, summary.waypoints)
        }

    @Test
    fun `exporting imports that use one prefix for two namespaces keeps both valid`() =
        runDb {
            fun doc(uri: String): TrackDocument {
                val ext = RawXml("<x:hr>90</x:hr>")
                val p = TrackPoint(45.0, 6.0, extras = listOf(ext))
                val q = TrackPoint(45.001, 6.001, extras = listOf(ext))
                return TrackDocument(
                    tracks = listOf(Track(name = uri, segments = listOf(listOf(p, q)))),
                    namespaces =
                        mapOf("x" to uri),
                )
            }
            val a = repo.import(doc("urn:a"), "a.gpx")
            val b = repo.import(doc("urn:b"), "b.gpx")
            val merged = repo.document(TrackSelection(folderIds = listOf(a.folderId, b.folderId)))
            val text = GpxWriter.toString(merged, "ToolMap")
            val again = GpxReader.read(text.toByteArray())
            assertEquals(2, again.tracks.size)
            assertContains(text, "urn:a")
            assertContains(text, "urn:b")
        }

    @Test
    fun `the map layer shows visible items only and keeps its work between changes`() =
        runDb {
            val source = KmlReader.read(fixture("kml/folders-styles-gxtrack.kml"))
            repo.import(source, "sortie.kml")
            repo.import(TrackDocument(tracks = listOf(synthetic(300, "long"))), "long.gpx")
            val display = TrackDisplay(repo)
            val all = display.data.first()
            assertEquals(source.tracks.size + 1, all.lineCount)
            assertEquals(1, all.pointCount)
            assertContains(all.linesGeoJson, "\"type\":\"FeatureCollection\"")

            val lib = repo.library.first()
            repo.setVisible(ItemRef(ItemKind.TRACK, lib.tracks.single { it.name == "long" }.id), false)
            repo.setFolderVisible(lib.folders.single { it.name == "Points" }.id, false)
            val some = display.data.first()
            assertEquals(source.tracks.size, some.lineCount)
            assertEquals(0, some.pointCount)
            assertTrue(some.linesGeoJson.length < all.linesGeoJson.length)
        }

    @Test
    fun `the displayed line follows a track that grows or changes`() =
        runDb {
            repo.import(TrackDocument(tracks = listOf(synthetic(50, "live"))), "live.gpx")
            val id = repo.library.first().tracks.single().id
            val display = TrackDisplay(repo)
            val before = display.data.first().linesGeoJson

            // A recording adds points at the end.
            repo.appendPoints(id, List(50) { i -> TrackPoint(lat = 46.0 + i * 0.0001, lon = 7.0 + i * 0.0001) })
            val grown = display.data.first().linesGeoJson
            assertTrue(grown != before, "the line did not grow")

            // An edit rewrites the points.
            repo.reverseTrack(id)
            val reversed = display.data.first().linesGeoJson
            assertTrue(reversed != grown, "the reversed line is still the old one")
        }

    @Test
    fun `the points of interest of a route go out as waypoints too, and come back in the route only`() =
        runDb {
            val a = RouteStop(45.0, 6.0)
            val b = RouteStop(45.01, 6.0)
            val plan =
                RoutePlan(
                    listOf(a, b),
                    listOf(RouteLeg.straight(a, b, RoutingProfiles.HIKING, LegMode.ROUTED, LegState.OK)),
                    pois = listOf(RoutePoi(45.005, 6.001, "Source", "Spring")),
                )
            val route =
                Route(
                    name = "Tour",
                    points = RoutePlanGpx.routePoints(plan),
                    extras = RoutePlanGpx.attach(emptyList(), plan),
                )
            val first = repo.import(TrackDocument(routes = listOf(route)), "tour.gpx")
            val exported = repo.document(TrackSelection(folderIds = listOf(first.folderId)))
            assertEquals(listOf("Source"), exported.waypoints.map { it.name })
            val bytes = GpxWriter.toBytes(exported, "ToolMap")
            // Imported again: the route keeps its point of interest, which is not a waypoint of the library as well.
            val again = repo.import(GpxReader.read(bytes), "tour.gpx")
            assertEquals(0, again.waypoints)
            assertTrue(repo.library.first().waypoints.isEmpty())
        }
}
