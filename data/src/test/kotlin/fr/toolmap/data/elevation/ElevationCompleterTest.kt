package fr.toolmap.data.elevation

import fr.toolmap.core.elevation.TerrariumTile
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.core.track.Track
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ElevationCompleterTest {
    private lateinit var db: TrackDatabase

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A 256 px Terrarium tile whose elevation is `base + 2 * x` metres. */
    private fun rampTile(base: Double): ByteArray {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val v = Math.round((base + 2 * x + 32768.0) * 256).toInt()
                image.setRGB(x, y, ((v shr 16) and 0xFF shl 16) or ((v shr 8) and 0xFF shl 8) or (v and 0xFF))
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private class CountingTiles(
        private val png: ByteArray?,
    ) : ElevationTiles {
        val requested = ArrayList<Triple<Int, Int, Int>>()

        override suspend fun tile(
            z: Int,
            x: Int,
            y: Int,
        ): ByteArray? {
            requested += Triple(z, x, y)
            return png
        }
    }

    private val line =
        listOf(
            List(5) { i -> TrackPoint(45.0 + i * 0.0002, 6.0 + i * 0.0002) },
        )

    @Test
    fun `points get the interpolated elevation of the tile they fall in`() =
        runBlocking {
            val tiles = CountingTiles(rampTile(300.0))
            val result = ElevationCompleter(tiles).fill(line)
            assertEquals(5, result.filled)
            assertEquals(0, result.missingTiles)
            val first = line[0][0]
            val tile = tiles.requested.first()
            val (fx, _) = TerrariumTile.fractionIn(first.lon, first.lat, 13, tile.second, tile.third)
            // Elevation is 300 + 2 * pixel, interpolated between pixel centres.
            assertEquals(300.0 + 2 * (fx * 256 - 0.5), result.segments[0][0].ele!!, 0.05)
            assertTrue(result.segments[0].zipWithNext().all { (a, b) -> b.ele!! >= a.ele!! })
        }

    @Test
    fun `a tile is fetched once however many points fall in it`() =
        runBlocking {
            val tiles = CountingTiles(rampTile(0.0))
            ElevationCompleter(tiles).fill(line)
            assertEquals(1, tiles.requested.size)
            assertEquals(13, tiles.requested.single().first)
            val expected = TileMath.tileAt(6.0, 45.0, 13)
            assertEquals(expected.x, tiles.requested.single().second)
            assertEquals(expected.y, tiles.requested.single().third)
        }

    @Test
    fun `elevations that exist are never replaced`() =
        runBlocking {
            val mixed = listOf(listOf(TrackPoint(45.0, 6.0, ele = 1234.5), TrackPoint(45.0001, 6.0001)))
            val result = ElevationCompleter(CountingTiles(rampTile(0.0))).fill(mixed)
            assertEquals(1, result.filled)
            assertEquals(1, result.alreadyHadElevation)
            assertEquals(1234.5, result.segments[0][0].ele)
        }

    @Test
    fun `an unavailable or broken tile leaves the points alone and says so`() =
        runBlocking {
            val offline = ElevationCompleter(CountingTiles(null)).fill(line)
            assertEquals(0, offline.filled)
            assertEquals(1, offline.missingTiles)
            assertNull(offline.segments[0][0].ele)
            val broken = ElevationCompleter(CountingTiles(byteArrayOf(1, 2, 3))).fill(line)
            assertEquals(0, broken.filled)
            assertEquals(1, broken.missingTiles)
        }

    @Test
    fun `the library completes a stored track and refreshes its figures`() =
        runBlocking {
            val repo = TrackRepository(db)
            repo.import(TrackDocument(tracks = listOf(Track(name = "flat", segments = line))), "flat.gpx")
            val id = repo.library.first().tracks.single().id
            assertNull(repo.library.first().tracks.single().ascentMeters)

            val result = assertNotNull(repo.completeElevation(id, ElevationCompleter(CountingTiles(rampTile(300.0)))))
            assertEquals(5, result.filled)
            val entity = repo.library.first().tracks.single()
            // Going north-east the ramp climbs: the figures now exist.
            assertNotNull(entity.ascentMeters)
            assertTrue(repo.loadTrack(id)!!.segments.single().all { it.ele != null })

            // A second run has nothing to do and does not rewrite the points.
            val again = assertNotNull(repo.completeElevation(id, ElevationCompleter(CountingTiles(rampTile(900.0)))))
            assertEquals(0, again.filled)
        }
}
