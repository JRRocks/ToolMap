package fr.toolmap.core.track

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxTest {
    private fun fixture(name: String): ByteArray {
        val dir = requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir not set by Gradle" }
        return File(dir, "gpx/$name").readBytes()
    }

    private fun read(text: String) = GpxReader.read(text.trimIndent().toByteArray())

    /** What a round trip must keep: everything but who wrote the file and the issues met while reading it. */
    private fun TrackDocument.content() = copy(creator = null, version = null, issues = emptyList())

    @Test
    fun `Garmin Connect GPX 1_1 keeps heart rate extensions and namespaces`() {
        val doc = GpxReader.read(fixture("garmin-connect-run.gpx"))
        assertEquals("1.1", doc.version)
        assertEquals("Garmin Connect", doc.creator)
        assertEquals(listOf(GpxLink("connect.garmin.com", "Garmin Connect")), doc.links)
        assertEquals(TrackTime.parse("2012-10-24T23:22:51.000Z"), doc.timeMillis)
        assertEquals("http://www.garmin.com/xmlschemas/TrackPointExtension/v1", doc.namespaces["gpxtpx"])
        assertEquals("http://www.garmin.com/xmlschemas/GpxExtensions/v3", doc.namespaces["gpxx"])

        assertEquals(1, doc.tracks.size)
        val track = doc.tracks.single()
        assertEquals("Untitled", track.name)
        assertEquals(1, track.segments.size)
        assertEquals(206, track.pointCount)
        val first = track.segments.single().first()
        assertEquals(38.92747367732227, first.lat)
        assertEquals(-77.02016168273985, first.lon)
        assertEquals(25.600000381469727, first.ele)
        assertEquals(TrackTime.parse("2012-10-24T23:29:40.000Z"), first.timeMillis)
        val extension = first.extras.single()
        assertEquals("extensions", extension.name)
        assertContains(extension.xml, "<gpxtpx:hr>130</gpxtpx:hr>")
        assertTrue(doc.issues.isEmpty(), "unexpected issues: ${doc.issues}")
    }

    @Test
    fun `GPSBabel GPX 1_0 with waypoints and several tracks`() {
        val doc = GpxReader.read(fixture("cerknicko-jezero.gpx"))
        assertEquals("1.0", doc.version)
        assertEquals("GPSBabel - http://www.gpsbabel.org", doc.creator)
        assertEquals(TrackTime.parse("2010-08-06T10:36:35Z"), doc.timeMillis)
        assertEquals(7, doc.waypoints.size)
        val flag = doc.waypoints.first()
        assertEquals("001", flag.name)
        assertEquals("05-AUG-10 16:58:37", flag.cmt)
        assertEquals("Flag, Blue", flag.sym)
        assertEquals(45.772163216, flag.lat)
        assertEquals(14.357652292, flag.lon)
        assertEquals(-0.11438, doc.waypoints[1].ele)
        // The file has 8 <trk>, some of them without any point: those are dropped and reported.
        assertEquals(296, doc.tracks.sumOf { it.pointCount })
        assertTrue(doc.tracks.all { it.segments.isNotEmpty() })
        assertTrue(doc.issues.any { it.kind == TrackIssue.Kind.EMPTY_ITEM_DROPPED })
        val numbers = doc.tracks.mapNotNull { it.number }
        assertTrue(numbers.isNotEmpty())
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun `dates written with seven fractional digits and in 1901 are read`() {
        val doc = GpxReader.read(fixture("Mojstrovka.gpx"))
        assertEquals(184, doc.tracks.single().pointCount)
        val first = doc.tracks.single().segments.single().first()
        // 1901-12-13T20:45:52.2073437Z: a GPS that had no date yet; millisecond precision is kept.
        assertEquals(-2_147_483_648_000L + 207, first.timeMillis)
        assertEquals(1614.678, first.ele)
    }

    @Test
    fun `big GPX 1_0 file round-trips`() {
        val doc = GpxReader.read(fixture("korita-zbevnica.gpx"))
        assertEquals(2, doc.waypoints.size)
        assertEquals(871, doc.tracks.sumOf { it.pointCount })
    }

    @Test
    fun `every real fixture survives a write and read without loss`() {
        val names = listOf("garmin-connect-run.gpx", "Mojstrovka.gpx", "cerknicko-jezero.gpx", "korita-zbevnica.gpx")
        for (name in names) {
            val first = GpxReader.read(fixture(name))
            val text = GpxWriter.toString(first)
            val second = GpxReader.read(text.toByteArray())
            assertEquals(first.content(), second.content(), "round trip changed $name")
            // And a second generation is byte-for-byte what the first one wrote.
            assertEquals(text, GpxWriter.toString(second), "writer is not stable on $name")
            assertFalse(Regex("""\d[eE][+-]?\d""").containsMatchIn(text), "scientific notation in $name")
        }
    }

    @Test
    fun `exported file is GPX 1_1 with the vendor namespaces declared on the root`() {
        val text = GpxWriter.toString(GpxReader.read(fixture("garmin-connect-run.gpx")))
        val root = text.lines().first { it.startsWith("<gpx") }
        assertContains(root, "version=\"1.1\"")
        assertContains(root, "xmlns=\"http://www.topografix.com/GPX/1/1\"")
        assertContains(root, "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"")
        assertContains(text, "<gpxtpx:hr>130</gpxtpx:hr>")
        assertContains(text, "<bounds ")
    }

    @Test
    fun `namespace-less GPX and unknown vendor elements are kept`() {
        val doc =
            read(
                """
                <gpx version="1.1" creator="x">
                  <wpt lat="1.5" lon="2.5">
                    <name>A &amp; B &lt;1&gt;</name>
                    <magvar>3.5</magvar>
                    <hdop>1.2</hdop>
                    <extensions><osmand:color xmlns:osmand="https://osmand.net">#ff0000</osmand:color></extensions>
                  </wpt>
                </gpx>
                """,
            )
        val wpt = doc.waypoints.single()
        assertEquals("A & B <1>", wpt.name)
        assertEquals(listOf("magvar", "hdop", "extensions"), wpt.extras.map { it.name })
        assertContains(wpt.extras.last().xml, "xmlns:osmand=\"https://osmand.net\"")
        val written = GpxWriter.toString(doc)
        // Schema order is restored: magvar, then name, then hdop, then extensions.
        assertTrue(written.indexOf("<magvar>") < written.indexOf("<name>"))
        assertTrue(written.indexOf("<name>") < written.indexOf("<hdop>"))
        assertTrue(written.indexOf("<hdop>") < written.indexOf("<extensions>"))
        assertContains(written, "<name>A &amp; B &lt;1&gt;</name>")
        assertEquals(doc.content(), GpxReader.read(written.toByteArray()).content())
    }

    @Test
    fun `routes with named points and links`() {
        val doc =
            read(
                """
                <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
                  <metadata>
                    <name>Trip</name><desc>Two days</desc>
                    <author><name>Jean</name><email id="j" domain="example.org"/></author>
                    <copyright author="Jean"><year>2026</year></copyright>
                    <link href="https://example.org"><text>site</text><type>text/html</type></link>
                    <keywords>alps, hike</keywords>
                  </metadata>
                  <rte>
                    <name>Day 1</name><number>3</number><type>hike</type>
                    <link href="https://example.org/1"/>
                    <rtept lat="45.0" lon="6.0"><ele>1000</ele><name>Start</name><sym>Flag</sym></rtept>
                    <rtept lat="45.1" lon="6.1"><name>Col</name><desc>turn left</desc></rtept>
                  </rte>
                </gpx>
                """,
            )
        assertEquals("Trip", doc.name)
        assertEquals("Two days", doc.description)
        assertEquals("Jean", doc.author)
        assertEquals("alps, hike", doc.keywords)
        assertEquals(listOf(GpxLink("https://example.org", "site", "text/html")), doc.links)
        assertEquals(listOf("copyright"), doc.metadataExtras.map { it.name })
        assertTrue(doc.issues.any { it.kind == TrackIssue.Kind.FIELD_NOT_KEPT && it.detail == "author/email" })
        val route = doc.routes.single()
        assertEquals("Day 1", route.name)
        assertEquals(3, route.number)
        assertEquals("hike", route.type)
        assertEquals(listOf(GpxLink("https://example.org/1")), route.links)
        assertEquals(listOf("Start", "Col"), route.points.map { it.name })
        assertEquals(1000.0, route.points.first().ele)
        assertEquals("turn left", route.points[1].desc)
        assertEquals(doc.content().copy(author = doc.author), GpxReader.read(GpxWriter.toBytes(doc)).content())
    }

    @Test
    fun `GPX 1_0 url fields become links and speed and course are reported as dropped`() {
        val doc =
            read(
                """
                <gpx version="1.0" creator="x" xmlns="http://www.topografix.com/GPX/1/0">
                  <name>Old</name><author>Me</author><email>me@example.org</email>
                  <url>https://x.org</url><urlname>X</urlname>
                  <trk><name>t</name><trkseg>
                    <trkpt lat="1" lon="2"><ele>3</ele><course>90</course><speed>2.5</speed></trkpt>
                  </trkseg></trk>
                </gpx>
                """,
            )
        assertEquals("Old", doc.name)
        assertEquals("Me", doc.author)
        assertEquals(listOf(GpxLink("https://x.org", "X")), doc.links)
        val point = doc.tracks.single().segments.single().single()
        assertEquals(3.0, point.ele)
        assertTrue(point.extras.isEmpty())
        val dropped = doc.issues.filter { it.kind == TrackIssue.Kind.FIELD_NOT_KEPT }.map { it.detail }
        assertTrue("trkpt/speed" in dropped && "trkpt/course" in dropped && "gpx/email" in dropped, "$dropped")
    }

    @Test
    fun `bad points and bad times are skipped and reported`() {
        val doc =
            read(
                """
                <gpx version="1.1" creator="x"><trk><trkseg>
                  <trkpt lat="95" lon="2"><ele>1</ele></trkpt>
                  <trkpt lat="x" lon="2"/>
                  <trkpt lat="10" lon="20"><time>yesterday</time><ele>nan</ele></trkpt>
                  <trkpt lat="11" lon="21"><time>2020-05-01T10:00:00+02:00</time></trkpt>
                </trkseg></trk></gpx>
                """,
            )
        val points = doc.tracks.single().segments.single()
        assertEquals(2, points.size)
        assertNull(points[0].timeMillis)
        assertNull(points[0].ele)
        assertEquals(TrackTime.parse("2020-05-01T08:00:00Z"), points[1].timeMillis)
        val kinds = doc.issues.map { it.kind }
        assertEquals(2, kinds.count { it == TrackIssue.Kind.INVALID_COORDINATE })
        assertContains(kinds, TrackIssue.Kind.INVALID_TIME)
    }

    @Test
    fun `Garmin and OsmAnd colours become the line style`() {
        val doc =
            read(
                """
                <gpx version="1.1" creator="x" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3"
                     xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><name>a</name>
                    <extensions><gpxx:TrackExtension>
                      <gpxx:DisplayColor>Blue</gpxx:DisplayColor>
                    </gpxx:TrackExtension></extensions>
                    <trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
                  <trk><name>b</name>
                    <extensions><color>#80ff8800</color></extensions>
                    <trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
                  <trk><name>c</name><trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
                </gpx>
                """,
            )
        assertEquals(0xFF0000FF.toInt(), doc.tracks[0].style?.colorArgb)
        assertEquals(0x80FF8800.toInt(), doc.tracks[1].style?.colorArgb)
        assertNull(doc.tracks[2].style)
    }

    @Test
    fun `multi-segment tracks keep their segments`() {
        val doc =
            read(
                """
                <gpx version="1.1" creator="x"><trk>
                  <trkseg><trkpt lat="1" lon="1"/><trkpt lat="1" lon="2"/></trkseg>
                  <trkseg><trkpt lat="2" lon="1"/></trkseg>
                </trk></gpx>
                """,
            )
        assertEquals(listOf(2, 1), doc.tracks.single().segments.map { it.size })
        assertEquals(doc.content(), GpxReader.read(GpxWriter.toBytes(doc)).content())
    }

    @Test
    fun `files that are not GPX or are dangerous are refused with a clear error`() {
        assertFailsWith<TrackFormatException> { read("<kml><Document/></kml>") }
        assertFailsWith<TrackFormatException> { read("not xml at all") }
        assertFailsWith<TrackFormatException> { read("") }
        val bomb =
            "<?xml version=\"1.0\"?>\n<!DOCTYPE gpx [<!ENTITY a \"aaaa\">]>\n" +
                "<gpx version=\"1.1\"><name>&a;</name></gpx>"
        assertFailsWith<TrackFormatException> { GpxReader.read(bomb.toByteArray()) }
    }

    @Test
    fun `50000 points are written and read back in a few seconds`() {
        val points =
            (0 until 50_000).map {
                TrackPoint(45.0 + it * 1e-5, 6.0 + it * 1e-5, 1000.0 + it % 50, 1_700_000_000_000L + it * 1000L)
            }
        val doc = TrackDocument(tracks = listOf(Track(name = "big", segments = listOf(points))))
        val started = System.nanoTime()
        val bytes = GpxWriter.toBytes(doc)
        val back = GpxReader.read(bytes)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(points, back.tracks.single().segments.single())
        assertNotNull(back.tracks.single().name)
        assertTrue(seconds < 10, "took $seconds s")
    }
}
