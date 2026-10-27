package fr.toolmap.data.tracks

import fr.toolmap.core.record.RecordingSession
import fr.toolmap.core.track.GpxReader
import fr.toolmap.core.track.GpxWriter
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStatsCalculator
import fr.toolmap.core.track.Waypoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackCreationTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db, now = { 5_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun walk(
        from: Int,
        count: Int,
    ) = List(count) { i ->
        val k = from + i
        TrackPoint(45.0 + k * 0.0001, 6.0 + k * 0.00005, 1000.0 + k * 0.5, 1_000_000L + k * 10_000L)
    }

    private fun stored(id: Long) = runBlocking { repo.loadTrack(id)!!.segments }

    @Test
    fun `an empty track is created at the root of the library and fills up point by point`() =
        runBlocking {
            val id = repo.createTrack("  Sortie du matin  ", style = PathStyle(colorArgb = 0xFF00FF00.toInt()))
            val lib = repo.library.first()
            val entity = lib.tracks.single()
            assertEquals("Sortie du matin", entity.name)
            assertNull(entity.folderId)
            assertNull(entity.format)
            assertEquals(0, entity.pointCount)

            repo.appendPoints(id, walk(0, 3))
            repo.appendPoints(id, walk(3, 2))
            val segments = stored(id)
            assertEquals(1, segments.size)
            assertEquals(5, segments.single().size)
            walk(0, 5).zip(segments.single()).forEach { (want, got) ->
                assertEquals(want.lat, got.lat, 1e-6)
                assertEquals(want.lon, got.lon, 1e-6)
                assertEquals(want.timeMillis, got.timeMillis)
            }
            val e = repo.library.first().tracks.single()
            assertEquals(5, e.pointCount)
            assertEquals(1, e.segmentCount)
            assertEquals(1_000_000L, e.startTimeMillis)
            assertEquals(1_040_000L, e.endTimeMillis)
            assertEquals(45.0, e.minLat, 1e-6)
            assertEquals(45.0004, e.maxLat, 1e-6)
            assertEquals(TrackStatsCalculator.compute(listOf(walk(0, 5))).distance2dMeters, e.distanceMeters, 0.01)
        }

    @Test
    fun `appending in small steps gives the same track as storing it whole`() =
        runBlocking {
            val whole = repo.createTrack("whole", segments = listOf(walk(0, 4500)))
            val steps = repo.createTrack("steps")
            var k = 0
            while (k < 4500) {
                val n = minOf(37, 4500 - k)
                repo.appendPoints(steps, walk(k, n))
                k += n
            }
            val a = stored(whole).single()
            val b = stored(steps).single()
            assertEquals(a.size, b.size)
            for (i in a.indices step 250) {
                assertEquals(a[i].lat, b[i].lat, 1e-6)
                assertEquals(a[i].timeMillis, b[i].timeMillis)
            }
            val lib = repo.library.first()
            val ea = lib.tracks.first { it.id == whole }
            val eb = lib.tracks.first { it.id == steps }
            assertEquals(ea.pointCount, eb.pointCount)
            assertEquals(ea.distanceMeters, eb.distanceMeters, 0.5)
            // 4500 points in chunks of 2000: three chunks, the last one holds 500.
            assertEquals(3, runBlocking { db.dao().chunks(steps) }.size)
        }

    @Test
    fun `a new segment starts a new chunk series and is not joined to the previous point`() =
        runBlocking {
            val id = repo.createTrack("pause")
            repo.appendPoints(id, walk(0, 3))
            repo.appendPoints(id, walk(100, 3), newSegment = true)
            repo.appendPoints(id, walk(103, 2))
            val segments = stored(id)
            assertEquals(listOf(3, 5), segments.map { it.size })
            val e = repo.library.first().tracks.single()
            assertEquals(2, e.segmentCount)
            assertEquals(8, e.pointCount)
            val expected = TrackStatsCalculator.compute(listOf(walk(0, 3), walk(100, 5))).distance2dMeters
            assertEquals(expected, e.distanceMeters, 0.01)
        }

    @Test
    fun `the first points of a track may start with a new segment flag`() =
        runBlocking {
            val id = repo.createTrack("flagged")
            repo.appendPoints(id, walk(0, 2), newSegment = true)
            assertEquals(listOf(2), stored(id).map { it.size })
            assertEquals(1, repo.library.first().tracks.single().segmentCount)
        }

    @Test
    fun `finishing recomputes the climb`() =
        runBlocking {
            val id = repo.createTrack("climb")
            repo.appendPoints(id, walk(0, 60))
            assertNull(repo.library.first().tracks.single().ascentMeters)
            repo.finishTrack(id)
            val e = repo.library.first().tracks.single()
            assertNotNull(e.ascentMeters)
            assertTrue(e.ascentMeters!! > 20.0, "ascent ${e.ascentMeters}")
        }

    @Test
    fun `appending to a missing track does nothing`() =
        runBlocking {
            repo.appendPoints(999, walk(0, 2))
            repo.finishTrack(999)
            assertTrue(repo.library.first().tracks.isEmpty())
        }

    @Test
    fun `a recorded track exports to GPX and reads back`() =
        runBlocking {
            val id = repo.createTrack("Enregistrée")
            repo.appendPoints(id, walk(0, 10))
            repo.appendPoints(id, walk(50, 10), newSegment = true)
            val doc = repo.document(TrackSelection(trackIds = listOf(id)))
            val back = GpxReader.read(GpxWriter.toBytes(doc, "ToolMap"))
            assertEquals(listOf(10, 10), back.tracks.single().segments.map { it.size })
            assertEquals("Enregistrée", back.tracks.single().name)
        }

    @Test
    fun `routes are created and their points replaced`() =
        runBlocking {
            val id =
                repo.createRoute(
                    "Itinéraire",
                    listOf(Waypoint(45.0, 6.0, name = "Départ"), Waypoint(45.01, 6.0)),
                )
            val before = repo.library.first().routes.single()
            assertNull(before.folderId)
            assertEquals(2, before.pointCount)
            assertEquals(1112.0, before.distanceMeters, 5.0)
            repo.replaceRoutePoints(id, listOf(Waypoint(45.0, 6.0), Waypoint(45.01, 6.0), Waypoint(45.02, 6.0)))
            val after = repo.library.first().routes.single()
            assertEquals(3, after.pointCount)
            assertEquals(2224.0, after.distanceMeters, 10.0)
            assertEquals("Itinéraire", after.name)
            assertEquals(5_000L, after.createdAt)
            assertEquals(3, repo.loadRoute(id)!!.points.size)
            assertEquals(45.02, after.maxLat, 1e-9)
        }

    @Test
    fun `waypoints are created, updated and described`() =
        runBlocking {
            val id = repo.createWaypoint(Waypoint(45.5, 6.5, ele = 1500.0, name = "Col", sym = "Summit"))
            assertEquals("Col", repo.loadWaypoint(id)!!.name)
            repo.setVisible(ItemRef(ItemKind.WAYPOINT, id), false)
            repo.updateWaypoint(id, Waypoint(45.6, 6.6, ele = 1600.0, name = " Refuge ", desc = "Eau", sym = "Lodge"))
            val w = repo.loadWaypoint(id)!!
            assertEquals("Refuge", w.name)
            assertEquals(45.6, w.lat)
            assertEquals("Lodge", w.sym)
            assertEquals("Eau", w.desc)
            // Visibility is not part of what updateWaypoint rewrites.
            assertEquals(false, repo.library.first().waypoints.single().visible)
            repo.describe(ItemRef(ItemKind.WAYPOINT, id), "  Source à 50 m  ")
            assertEquals("Source à 50 m", repo.loadWaypoint(id)!!.desc)
            val track = repo.createTrack("t")
            repo.describe(ItemRef(ItemKind.TRACK, track), "Belle sortie")
            assertEquals("Belle sortie", repo.loadTrack(track)!!.desc)
            repo.describe(ItemRef(ItemKind.TRACK, track), "   ")
            assertNull(repo.loadTrack(track)!!.desc)
        }

    @Test
    fun `the recording session file survives restarts and ignores damage`() {
        val file = folder.newFile("recording.json")
        file.delete()
        val store = RecordingSessionStore(file)
        assertNull(store.read())
        val session =
            RecordingSession(trackId = 7, startedAtMillis = 10, activeMillis = 2_000, paused = true, savedAtMillis = 99)
        store.write(session)
        assertEquals(session, RecordingSessionStore(file).read())
        store.write(session.copy(activeMillis = 5_000))
        assertEquals(5_000L, store.read()!!.activeMillis)
        file.writeText("{ not json")
        assertNull(store.read())
        store.write(session)
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `reversing walks the track the other way`() =
        runBlocking {
            val id = repo.createTrack("aller", segments = listOf(walk(0, 6)))
            repo.reverseTrack(id)
            val back = stored(id).single()
            assertEquals(45.0005, back.first().lat, 1e-6)
            assertEquals(45.0, back.last().lat, 1e-6)
            assertTrue(back.all { it.timeMillis == null })
            assertEquals(6, repo.library.first().tracks.single().pointCount)
        }

    @Test
    fun `simplifying drops points on a straight line and leaves a bare corner alone`() =
        runBlocking {
            val straight =
                repo.createTrack(
                    "droit",
                    segments =
                        listOf(
                            List(100) {
                                TrackPoint(45.0 + it * 0.0001, 6.0)
                            },
                        ),
                )
            val (before, after) = repo.simplifyTrack(straight, 2.0)!!
            assertEquals(100, before)
            assertEquals(2, after)
            assertEquals(2, repo.library.first().tracks.single().pointCount)
            assertEquals(2, repo.simplifyTrack(straight, 2.0)!!.second)
            assertNull(repo.simplifyTrack(999, 2.0))
        }

    @Test
    fun `merging builds a new track and keeps the sources`() =
        runBlocking {
            val a =
                repo.createTrack(
                    "A",
                    segments = listOf(walk(0, 5)),
                    style = PathStyle(colorArgb = 0xFF112233.toInt()),
                )
            val b = repo.createTrack("B", segments = listOf(walk(100, 4)))
            val merged = repo.mergeTracks(listOf(a, b), "A + B")!!
            val segments = stored(merged)
            assertEquals(listOf(5, 4), segments.map { it.size })
            assertEquals(3, repo.library.first().tracks.size)
            assertEquals(0xFF112233.toInt(), repo.loadTrack(merged)!!.style!!.colorArgb)
            assertEquals(9, repo.library.first().tracks.first { it.id == merged }.pointCount)
            assertNull(repo.mergeTracks(listOf(a), "seule"))
            assertNull(repo.mergeTracks(listOf(a, 999), "absente"))
        }
}
