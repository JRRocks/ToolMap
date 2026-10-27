package fr.toolmap.core.track

import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PointCodecTest {
    @Test
    fun `values with up to nine decimals, six decimals of elevation and milliseconds come back exactly`() {
        val points =
            listOf(
                TrackPoint(45.123456789, 6.987654321, 1234.567891, 1_700_000_000_123L),
                TrackPoint(-33.5, -70.25, null, null),
                TrackPoint(0.0, 0.0, -12.5, -2_147_483_648_000L),
                TrackPoint(89.999999999, -179.999999999, 8848.0, 4_102_444_800_000L),
                TrackPoint(
                    1.0,
                    2.0,
                    3.0,
                    4L,
                    listOf(RawXml("<extensions><a:b xmlns:a=\"u\">é&amp;</a:b></extensions>")),
                ),
            )
        assertEquals(points, PointCodec.decode(PointCodec.encode(points)))
    }

    @Test
    fun `empty list and single point`() {
        assertEquals(emptyList(), PointCodec.decode(PointCodec.encode(emptyList())))
        val one = listOf(TrackPoint(1.5, 2.5))
        assertEquals(one, PointCodec.decode(PointCodec.encode(one)))
    }

    @Test
    fun `finer data is rounded to a tenth of a millimetre, a micrometre and a millisecond`() {
        val back =
            PointCodec.decode(
                PointCodec.encode(listOf(TrackPoint(45.12345678912345, 6.0, 100.0000004, 5L))),
            ).single()
        assertEquals(45.123456789, back.lat)
        assertEquals(100.0, back.ele)
    }

    @Test
    fun `a 50000-point random walk costs about ten bytes a point and round-trips`() {
        val random = Random(42)
        var lat = 45.0
        var lon = 6.0
        var ele = 1500.0
        val points =
            (0 until 50_000).map {
                lat += (random.nextDouble() - 0.5) * 2e-4
                lon += (random.nextDouble() - 0.5) * 2e-4
                ele += (random.nextDouble() - 0.5) * 2
                TrackPoint(
                    Math.round(lat * 1e7) / 1e7,
                    Math.round(lon * 1e7) / 1e7,
                    Math.round(ele * 10) / 10.0,
                    1_700_000_000_000L + it * 1000L,
                )
            }
        val bytes = PointCodec.encode(points)
        assertTrue(bytes.size < points.size * 12, "${bytes.size} bytes for ${points.size} points")
        assertEquals(points, PointCodec.decode(bytes))
    }

    @Test
    fun `real tracks round-trip through the codec`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        // GPSBabel writes 9 decimals of position and 6 of elevation: exact. Garmin Connect writes 14 digits (floats
        // widened to doubles): rounded to the codec's units.
        for (name in listOf("korita-zbevnica.gpx", "Mojstrovka.gpx", "cerknicko-jezero.gpx")) {
            val doc = GpxReader.read(File(dir, "gpx/$name").readBytes())
            for (segment in doc.tracks.flatMap { it.segments }) {
                assertEquals(segment, PointCodec.decode(PointCodec.encode(segment)), name)
            }
        }
        val garmin =
            GpxReader.read(
                File(dir, "gpx/garmin-connect-run.gpx").readBytes(),
            ).tracks.single().segments.single()
        val back = PointCodec.decode(PointCodec.encode(garmin))
        assertEquals(garmin.size, back.size)
        for ((a, b) in garmin.zip(back)) {
            assertEquals(a.lat, b.lat, 1e-9)
            assertEquals(a.lon, b.lon, 1e-9)
            assertEquals(a.ele!!, b.ele!!, 1e-6)
            assertEquals(a.timeMillis, b.timeMillis)
            assertEquals(a.extras, b.extras)
        }
    }

    @Test
    fun `damaged data is refused instead of crashing`() {
        val good = PointCodec.encode(listOf(TrackPoint(1.0, 2.0, 3.0, 4L), TrackPoint(1.1, 2.1, 3.1, 5L)))
        assertFailsWith<IllegalArgumentException> { PointCodec.decode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { PointCodec.decode(good.copyOf(good.size - 3)) }
        assertFailsWith<IllegalArgumentException> { PointCodec.decode(byteArrayOf(9, 0)) }
        assertFailsWith<IllegalArgumentException> { PointCodec.decode(byteArrayOf(1, 100)) }
    }

    @Test
    fun `waypoints, links and extras survive their JSON columns`() {
        val waypoints =
            listOf(
                Waypoint(
                    1.0,
                    2.0,
                    3.0,
                    4L,
                    "n",
                    "c",
                    "d",
                    "s",
                    listOf(GpxLink("h", "t", "ty")),
                    "sym",
                    "type",
                    listOf(RawXml("<x/>")),
                ),
                Waypoint(5.0, 6.0),
            )
        assertEquals(waypoints, TrackJson.decodeWaypoints(TrackJson.encodeWaypoints(waypoints)))
        assertEquals(null, TrackJson.encodeLinks(emptyList()))
        assertEquals(emptyList(), TrackJson.decodeLinks(null))
        assertEquals(listOf(GpxLink("a")), TrackJson.decodeLinks(TrackJson.encodeLinks(listOf(GpxLink("a")))))
        assertEquals(listOf(RawXml("<e/>")), TrackJson.decodeExtras(TrackJson.encodeExtras(listOf(RawXml("<e/>")))))
    }
}
