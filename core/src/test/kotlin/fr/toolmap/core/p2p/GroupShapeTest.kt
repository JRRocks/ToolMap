package fr.toolmap.core.p2p

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.sketch.RawStroke
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.SketchStyle
import fr.toolmap.core.sketch.XY
import java.util.Base64
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupShapeTest {
    private val crypto = GroupCrypto(GroupCrypto.randomKey())

    /** A walk of [n] points of about 10 m from the Pont du Fau area, in [segments] pieces. */
    private fun walk(
        n: Int,
        segments: Int = 1,
        seed: Int = 1,
    ): List<List<LonLat>> {
        val random = Random(seed)
        var lat = 45.1
        var lon = 6.4
        var heading = 0.0
        val points =
            List(n) {
                heading += random.nextDouble(-0.6, 0.6)
                lat += 9e-5 * cos(heading)
                lon += 1.3e-4 * sin(heading)
                LonLat(lon, lat)
            }
        return points.chunked((n + segments - 1) / segments)
    }

    @Test
    fun `a track comes back to the meter, its segments kept`() {
        val track = walk(600, segments = 3)
        val packed = assertNotNullShape(GroupShape.packTrack(track))
        val back = GroupShape.unpackTrack(packed)
        assertEquals(track.map { it.size }, back.map { it.size })
        for ((a, b) in track.flatten().zip(back.flatten())) {
            assertTrue(abs(a.lat - b.lat) <= 0.6e-5 && abs(a.lon - b.lon) <= 0.6e-5, "$a $b")
        }
        // About 3 bytes a point once deflated: far from the limit.
        assertTrue(packed.size < 600 * 4, "${packed.size} bytes")
    }

    @Test
    fun `a track too long for a frame is simplified until it fits, and still starts and ends in place`() {
        val track = walk(40_000)
        val packed = assertNotNullShape(GroupShape.packTrack(track))
        assertTrue(packed.size <= GroupShape.MAX_BYTES)
        val back = GroupShape.unpackTrack(packed).single()
        assertTrue(back.size < 40_000)
        assertEquals(track[0].first().lat, back.first().lat, 1e-5)
        assertEquals(track[0].last().lon, back.last().lon, 1e-5)
    }

    @Test
    fun `a sketch keeps its curves to the decimeter, with the colour, width and smoothing of each stroke`() {
        val origin = XY.of(LonLat(6.4, 45.1))
        val style = SketchStyle(0x80E53935.toInt(), widthMeters = 12.34, smoothingMeters = 2.5)
        val stroke = RawStroke(style, List(120) { i -> origin + XY(i * 7.0, 90 * sin(i / 15.0)) }).smoothed()
        val dot = SketchStroke(style.copy(color = 0xFF1E88E5.toInt()), SketchPath(origin + XY(-50.0, 3.0)))
        val packed = assertNotNullShape(GroupShape.packSketch(listOf(stroke, dot)))
        val back = GroupShape.unpackSketch(packed)
        assertEquals(2, back.size)
        assertEquals(style, back[0].style)
        assertEquals(0xFF1E88E5.toInt(), back[1].style.color)
        assertEquals(stroke.path.curves.size, back[0].path.curves.size)
        val before = listOf(stroke.path.start) + stroke.path.curves.flatMap { listOf(it.c1, it.c2, it.end) }
        val after = listOf(back[0].path.start) + back[0].path.curves.flatMap { listOf(it.c1, it.c2, it.end) }
        for ((a, b) in before.zip(after)) assertTrue(abs(a.x - b.x) <= 0.05 && abs(a.y - b.y) <= 0.05, "$a $b")
        assertTrue(back[1].path.curves.isEmpty())
        assertNull(GroupShape.packSketch(listOf(stroke, dot), maxBytes = 10))
    }

    @Test
    fun `damaged bytes are refused, never a huge list`() {
        assertFailsWith<WireException> { GroupShape.unpackTrack(byteArrayOf(1, 2, 3)) }
        val packed = assertNotNullShape(GroupShape.packTrack(walk(50)))
        assertFailsWith<WireException> { GroupShape.unpackTrack(packed.copyOf(packed.size / 2)) }
    }

    @Test
    fun `a track rides in a point frame that fits the server slot, and an older version reads a waypoint`() {
        val packed = assertNotNullShape(GroupShape.packTrack(walk(40_000, seed = 7)))
        val item =
            GroupPoint(
                id = 5,
                author = 6,
                rev = 1,
                // Seven decimals, as on the wire.
                lat = 45.1000001,
                lon = 6.4000001,
                name = "Retour par la crête",
                createdSec = 1_790_000_000,
                kind = GroupItemKind.TRACK,
                shape = Base64.getEncoder().encodeToString(packed),
            )
        val frame = Wire.encode(Msg.Point(6, 1, item), crypto)
        val back = (Wire.decode(frame, crypto).msg as Msg.Point).point
        assertEquals(item, back)
        assertEquals(GroupItemKind.TRACK, back.kind)
        assertTrue(back.track().single().size > 2)
        assertTrue(back.sketch().isEmpty())
        // 16 KB of frame at most for the mesh, 17 000 characters of Base64 for the server (`database.rules.json`).
        assertTrue(frame.size <= 16 * 1024, "${frame.size}")
        assertTrue((frame.size + 2) / 3 * 4 <= 17_000, "${frame.size}")
        // A version of P27.1 wrote no kind nor shape: what it sends reads as a waypoint.
        val w = ByteWriter()
        item.copy(kind = GroupItemKind.WAYPOINT, shape = "").write(w)
        val old = w.toByteArray().let { it.copyOf(it.size - 3) }
        val read = GroupPoint.read(ByteReader(old))
        assertEquals(GroupItemKind.WAYPOINT, read.kind)
        assertEquals("", read.shape)
        assertEquals(item.name, read.name)
    }

    private fun assertNotNullShape(bytes: ByteArray?): ByteArray = assertNotNull(bytes, "no shape")
}
