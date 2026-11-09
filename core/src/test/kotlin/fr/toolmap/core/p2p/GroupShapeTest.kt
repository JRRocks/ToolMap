package fr.toolmap.core.p2p

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.FavoritesFile
import fr.toolmap.core.layers.FavoritesFileCodec
import fr.toolmap.core.layers.Workspaces
import fr.toolmap.core.sketch.Cubic
import fr.toolmap.core.sketch.RawStroke
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchShape
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
    fun `the lines and zones of a sketch keep their kind and fill, and a sketch without one is packed as before`() {
        val origin = XY.of(LonLat(6.4, 45.1))
        val style = SketchStyle(0xFFE53935.toInt(), widthMeters = 6.0, smoothingMeters = 2.5)
        val corners = listOf(origin, origin + XY(300.0, 0.0), origin + XY(150.0, 260.0))
        val free = RawStroke(style, List(40) { i -> origin + XY(i * 7.0, 30 * sin(i / 5.0)) }).smoothed()
        val zone = RawStroke(style, corners, SketchShape(closed = true, fill = SketchFill.WAVES)).smoothed()
        val line = RawStroke(style, corners, SketchShape(closed = false)).smoothed()
        val back = GroupShape.unpackSketch(assertNotNullShape(GroupShape.packSketch(listOf(free, zone, line))))
        assertEquals(listOf(null, zone.shape, line.shape), back.map { it.shape })
        assertEquals(3, back[1].path.vertices(closed = true).size)
        // Without a shape, nothing follows the strokes: what an older app sends and reads.
        val plain = assertNotNullShape(GroupShape.packSketch(listOf(free)))
        assertNull(GroupShape.unpackSketch(plain).single().shape)
        val withZone = assertNotNullShape(GroupShape.packSketch(listOf(free, zone)))
        assertTrue(GroupShape.unpackSketch(withZone).size == 2)
    }

    @Test
    fun `damaged bytes are refused, never a huge list`() {
        assertFailsWith<WireException> { GroupShape.unpackTrack(byteArrayOf(1, 2, 3)) }
        val packed = assertNotNullShape(GroupShape.packTrack(walk(50)))
        assertFailsWith<WireException> { GroupShape.unpackTrack(packed.copyOf(packed.size / 2)) }
    }

    @Test
    fun `a sketch made up to cost millions of points to draw is refused, a drawing by hand is not`() {
        val large = 1 shl 20
        val thin = SketchStyle(color = 0xFF0000, widthMeters = 0.01, smoothingMeters = 0.01)

        // Wiggles of 50 km, a centimeter thin: 65,000 points each once cut into lines finer than their width.
        fun wiggle(i: Int) = Cubic(XY(i * 10.0, 50_000.0), XY(i * 10.0 + 5, -50_000.0), XY(i * 10.0 + 10, 0.0))
        val heavy = List(40) { s -> SketchStroke(thin, SketchPath(XY(0.0, 0.0), List(50) { wiggle(s * 50 + it) })) }
        val packed = assertNotNullShape(GroupShape.packSketch(heavy, large))
        assertFailsWith<WireException> { GroupShape.unpackSketch(packed) }
        // One stroke alone is cut short, wherever it is cut into lines.
        assertTrue(heavy[0].path.sample(0.001).size <= SketchPath.MAX_SAMPLE_POINTS + 20)
        assertTrue(heavy[0].drawn(100).size <= 120)
        // More strokes than any drawing has.
        val dot = SketchStroke(thin.copy(widthMeters = 1.0), SketchPath(XY(1.0, 1.0)))
        val dots = assertNotNullShape(GroupShape.packSketch(List(5_001) { dot }, large))
        assertFailsWith<WireException> { GroupShape.unpackSketch(dots) }
        // A drawing by hand: sixty strokes of forty curves of ten meters, two meters wide.
        val hand = SketchStyle(color = 0x00FF00, widthMeters = 2.0, smoothingMeters = 1.0)

        fun bump(
            x: Double,
            i: Int,
        ) = Cubic(XY(x + i * 10 + 3, 4.0), XY(x + i * 10 + 6, -4.0), XY(x + i * 10 + 10, 0.0))
        val drawing = List(60) { s -> SketchStroke(hand, SketchPath(XY(s * 3.0, 0.0), List(40) { bump(s * 3.0, it) })) }
        assertEquals(60, GroupShape.unpackSketch(assertNotNullShape(GroupShape.packSketch(drawing, large))).size)
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

    @Test
    fun `a favorites file is deflated whole, comes back to the character and is refused when it does not fit`() {
        val text = favoritesText(8)
        assertTrue(text.toByteArray(Charsets.UTF_8).size > 4096, "${text.length}")
        val packed = assertNotNullShape(GroupShape.packText(text))
        assertTrue(packed.size < 4096, "${packed.size}")
        assertEquals(text, GroupShape.unpackText(packed))
        val item =
            GroupPoint(
                id = 7,
                author = 8,
                rev = 1,
                lat = 45.1,
                lon = 6.4,
                name = "Rando été",
                createdSec = 1_790_000_000,
                kind = GroupItemKind.FAVORITES,
                shape = Base64.getEncoder().encodeToString(packed),
            )
        assertEquals(text, item.favoritesText())
        assertEquals(8, FavoritesFileCodec.decode(item.favoritesText()!!).favorites.size)
        // Another kind, or damaged bytes, has no file.
        assertNull(item.copy(kind = GroupItemKind.SKETCH).favoritesText())
        assertNull(item.copy(shape = Base64.getEncoder().encodeToString(packed.copyOf(10))).favoritesText())
        // Text that does not shrink (keys, long added maps) past the limit is refused, not cut.
        val noise = Random(3).let { r -> String(CharArray(30_000) { ('a' + r.nextInt(26)) }) }
        assertNull(GroupShape.packText(noise))
        assertNull(GroupShape.packText(text, maxBytes = packed.size - 1))
    }

    private fun assertNotNullShape(bytes: ByteArray?): ByteArray = assertNotNull(bytes, "no shape")

    companion object {
        /** A `.toolmap` file of [count] favorites of six maps each, as « Partager des favoris » writes it. */
        fun favoritesText(count: Int): String =
            FavoritesFileCodec.encode(
                FavoritesFile(
                    appVersion = "0.4.196",
                    favorites = List(count) { Workspaces.randoEte.copy(id = "ws-$it", name = "Sortie $it") },
                ),
            )
    }
}
