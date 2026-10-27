package fr.toolmap.data.tracks

import fr.toolmap.core.track.Track
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackDisplayTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository
    private lateinit var display: TrackDisplay

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db, now = { 1_000L })
        display = TrackDisplay(repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A winding path, so that the simplification really drops points but keeps the bends. */
    private fun pt(i: Int) =
        TrackPoint(
            lat = 46.0 + i * 0.00002 + sin(i / 30.0) * 0.0003,
            lon = 7.0 + i * 0.00003 + cos(i / 45.0) * 0.0003,
            timeMillis = i * 1000L,
        )

    private suspend fun importTrack(points: Int): Long {
        repo.import(
            TrackDocument(
                tracks =
                    listOf(
                        Track(
                            name = "live",
                            segments =
                                listOf(
                                    List(points) {
                                        pt(it)
                                    },
                                ),
                        ),
                    ),
            ),
            "l.gpx",
        )
        return repo.library.first().tracks.single().id
    }

    private suspend fun lines(id: Long): List<List<TrackPoint>> {
        display.data.first()
        // The simplified geometry through the same entry point as the print.
        return display.printItems().tracks.single().segments.map { seg -> seg.map { TrackPoint(it.lat, it.lon) } }
    }

    @Test
    fun `a batch appended to a shown track does not simplify the whole track again`() =
        runBlocking {
            val id = importTrack(2_000)
            display.data.first()
            assertEquals(1, display.fullLoads.get())
            var next = 2_000
            repeat(40) {
                repo.appendPoints(id, List(10) { pt(next++) })
                display.data.first()
            }
            assertEquals(1, display.fullLoads.get())
        }

    @Test
    fun `the extended line follows the raw points within the tolerance`() =
        runBlocking {
            val id = importTrack(1_500)
            display.data.first()
            var next = 1_500
            repeat(60) {
                repo.appendPoints(id, List(10) { pt(next++) })
                display.data.first()
            }
            val segments = lines(id)
            assertEquals(1, segments.size)
            val line = segments.single()
            val raw = List(next) { pt(it) }
            assertEquals(raw.first().lat, line.first().lat, 1e-9)
            assertEquals(raw.last().lat, line.last().lat, 1e-9)
            assertTrue(line.size < raw.size / 3, "the line is thinned: ${line.size} of ${raw.size}")
            val worst = raw.maxOf { distanceToLine(it, line) }
            // The tolerance is 1 m; the tail is simplified in pieces, which can add up to twice that.
            assertTrue(worst <= 2.5, "worst gap to the line: $worst m")
            assertEquals(1, display.fullLoads.get())
        }

    @Test
    fun `a new segment is a new line`() =
        runBlocking {
            val id = importTrack(500)
            display.data.first()
            repo.appendPoints(id, List(100) { pt(500 + it) }, newSegment = true)
            display.data.first()
            val segments = lines(id)
            assertEquals(2, segments.size)
            assertEquals(pt(500).lat, segments[1].first().lat, 1e-9)
            assertEquals(pt(599).lat, segments[1].last().lat, 1e-9)
            assertEquals(1, display.fullLoads.get())
        }

    @Test
    fun `a track that is rewritten is read again`() =
        runBlocking {
            val id = importTrack(800)
            display.data.first()
            repo.appendPoints(id, List(10) { pt(800 + it) })
            display.data.first()
            assertEquals(1, display.fullLoads.get())
            repo.reverseTrack(id)
            val segments = lines(id)
            assertEquals(2, display.fullLoads.get())
            assertEquals(pt(809).lat, segments.single().first().lat, 1e-9)
            assertEquals(pt(0).lat, segments.single().last().lat, 1e-9)
        }

    @Test
    fun `appended batches are found again only while they are contiguous`() =
        runBlocking {
            val id = importTrack(100)
            val before = repo.pointsRevision(id)
            repo.appendPoints(id, List(5) { pt(100 + it) })
            repo.appendPoints(id, List(5) { pt(105 + it) })
            val since = assertNotNull(repo.appendedSince(id, before))
            assertEquals(2, since.batches.size)
            assertEquals(110, since.batches.last().endCount)
            assertEquals(repo.pointsRevision(id), since.revision)
            assertTrue(repo.appendedSince(id, repo.pointsRevision(id))!!.batches.isEmpty())
            repo.reverseTrack(id)
            assertNull(repo.appendedSince(id, before))
        }

    /** Distance in meters from [p] to the polyline [line], on a flat projection (enough at this scale). */
    private fun distanceToLine(
        p: TrackPoint,
        line: List<TrackPoint>,
    ): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = (line[i].lon - p.lon) * kx
            val ay = (line[i].lat - p.lat) * ky
            val bx = (line[i + 1].lon - p.lon) * kx
            val by = (line[i + 1].lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }
}
