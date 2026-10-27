package fr.toolmap.core.track

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KmlGeoJsonTest {
    private fun kml(name: String): ByteArray {
        val dir = requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir not set by Gradle" }
        return File(dir, "kml/$name").readBytes()
    }

    @Test
    fun `point placemark becomes a waypoint`() {
        val doc = KmlReader.read(kml("point.kml"))
        val w = doc.waypoints.single()
        assertEquals("Simple placemark", w.name)
        assertEquals(37.42228990140251, w.lat)
        assertEquals(-122.0822035425683, w.lon)
        assertEquals(0.0, w.ele)
        assertContains(w.desc.orEmpty(), "Attached to the ground")
        assertEquals(SourceFormat.KML, doc.format)
    }

    @Test
    fun `line strings become tracks and multi-geometry becomes one multi-segment track`() {
        val lines = KmlReader.read(kml("linestring.kml"))
        assertEquals("LineString.kml", lines.name)
        assertEquals(listOf("unextruded", "extruded"), lines.tracks.map { it.name })
        assertEquals(50.0, lines.tracks[1].segments.single().first().ele)

        val multi = KmlReader.read(kml("multigeometry.kml"))
        val track = multi.tracks.single()
        assertEquals("SF Marina Harbor Master", track.name)
        assertEquals(listOf(2, 2), track.segments.map { it.size })
    }

    @Test
    fun `polygon outer ring becomes a closed route and the inner ring is ignored`() {
        val doc = KmlReader.read(kml("polygon.kml"))
        val route = doc.routes.single()
        assertEquals("hollow box", route.name)
        assertEquals(5, route.points.size)
        assertEquals(route.points.first().lat, route.points.last().lat)
        assertContains(doc.issues.map { it.kind }, TrackIssue.Kind.POLYGON_AS_ROUTE)
    }

    @Test
    fun `KML without a namespace is read`() {
        val doc = KmlReader.read(kml("style.kml"))
        assertEquals(2, doc.waypoints.size)
        assertEquals("Google Earth - New Polygon", doc.waypoints[0].name)
    }

    @Test
    fun `folders, styles, style maps, timestamps and gx tracks`() {
        val doc = KmlReader.read(kml("folders-styles-gxtrack.kml"))
        assertEquals("Sortie test", doc.name)

        val refuge = doc.waypoints.single()
        assertEquals("Refuge", refuge.name)
        assertEquals("Jour 1 / Points", refuge.folder)
        assertEquals(TrackTime.parse("2026-07-14T08:30:00Z"), refuge.timeMillis)
        assertEquals(3613.0, refuge.ele)

        val climb = doc.tracks.first { it.name == "Montée" }
        assertEquals("Jour 1", climb.folder)
        assertEquals("<b>Raide</b> & longue", climb.desc)
        // KML colours are aabbggrr: ff0000ff is opaque red.
        assertEquals(0xFFFF0000.toInt(), climb.style?.colorArgb)
        assertEquals(5f, climb.style?.widthDp)
        assertEquals(listOf(1000.0, 1200.0, 1500.0), climb.segments.single().map { it.ele })

        val variant = doc.tracks.first { it.name == "Variante" }
        // Resolved through the StyleMap's "normal" pair: 80ff0000 is half-transparent blue.
        assertEquals(0x800000FF.toInt(), variant.style?.colorArgb)
        assertEquals(2.5f, variant.style?.widthDp)
        assertNull(variant.segments.single().first().ele)

        val gx = doc.tracks.first { it.name == "Enregistrement GPS" }
        val points = gx.segments.single()
        assertEquals(3, points.size)
        assertEquals(TrackTime.parse("2026-07-14T09:00:10Z"), points[1].timeMillis)
        assertEquals(1002.0, points[1].ele)
        assertEquals(6.8601, points[1].lon)
    }

    @Test
    fun `KMZ is a zip holding the KML`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("files/icon.png"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
            it.putNextEntry(ZipEntry("doc.kml"))
            it.write(kml("point.kml"))
            it.closeEntry()
        }
        val doc = TrackImporter.read(zip.toByteArray(), "x.kmz")
        assertEquals(SourceFormat.KMZ, doc.format)
        assertEquals("Simple placemark", doc.waypoints.single().name)
        val empty =
            ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use {
                    it.putNextEntry(ZipEntry("a.txt"))
                    it.closeEntry()
                }
            }
        assertFailsWith<TrackFormatException> { TrackImporter.read(empty.toByteArray()) }
    }

    @Test
    fun `GeoJSON feature collection with every geometry type`() {
        val doc =
            GeoJsonReader.read(
                """
                {"type":"FeatureCollection","name":"Test","features":[
                  {"type":"Feature","properties":{"name":"Col","time":"2026-01-02T03:04:05Z"},
                   "geometry":{"type":"Point","coordinates":[6.5,45.5,2000.5]}},
                  {"type":"Feature","properties":{"title":"Sentier","stroke":"#00ff00","stroke-width":3,
                     "coordTimes":["2026-01-02T03:04:05Z","2026-01-02T03:04:15Z"]},
                   "geometry":{"type":"LineString","coordinates":[[6.0,45.0,100],[6.1,45.1,110]]}},
                  {"type":"Feature","properties":{"name":"Deux parties"},
                   "geometry":{"type":"MultiLineString","coordinates":[[[1,2],[3,4]],[[5,6],[7,8]]]}},
                  {"type":"Feature","properties":{"name":"Lac"},
                   "geometry":{"type":"Polygon","coordinates":[[[1,1],[2,1],[2,2],[1,1]],[[1.2,1.1],[1.3,1.1],[1.2,1.2],[1.2,1.1]]]}},
                  {"type":"Feature","properties":{},
                   "geometry":{"type":"GeometryCollection","geometries":[
                      {"type":"MultiPoint","coordinates":[[10,20],[11,21]]}]}},
                  {"type":"Feature","properties":{"name":"Cassé"},
                   "geometry":{"type":"LineString","coordinates":[[1,2],["x",3],[500,4],[5,6]]}}
                ]}
                """.trimIndent(),
            )
        assertEquals("Test", doc.name)
        assertEquals(listOf("Col", null, null), doc.waypoints.map { it.name })
        assertEquals(2000.5, doc.waypoints[0].ele)
        assertEquals(TrackTime.parse("2026-01-02T03:04:05Z"), doc.waypoints[0].timeMillis)
        val trail = doc.tracks.first { it.name == "Sentier" }
        assertEquals(0xFF00FF00.toInt(), trail.style?.colorArgb)
        assertEquals(3f, trail.style?.widthDp)
        assertEquals(TrackTime.parse("2026-01-02T03:04:15Z"), trail.segments.single()[1].timeMillis)
        assertEquals(listOf(2, 2), doc.tracks.first { it.name == "Deux parties" }.segments.map { it.size })
        assertEquals(4, doc.routes.single().points.size)
        // Two of the four positions of the broken line are unusable.
        assertEquals(2, doc.tracks.first { it.name == "Cassé" }.segments.single().size)
        assertContains(doc.issues.map { it.kind }, TrackIssue.Kind.INVALID_COORDINATE)
    }

    @Test
    fun `bare geometry and bad GeoJSON`() {
        val doc = GeoJsonReader.read("""{"type":"LineString","coordinates":[[0,0],[1,1]]}""")
        assertEquals(1, doc.tracks.size)
        assertFailsWith<TrackFormatException> { GeoJsonReader.read("[1,2,3]") }
        assertFailsWith<TrackFormatException> { GeoJsonReader.read("{\"hello\":1}") }
        assertFailsWith<TrackFormatException> { GeoJsonReader.read("{broken") }
    }

    @Test
    fun `importer detects the format from the content`() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1" creator="x"><wpt lat="1" lon="2"/></gpx>"""
        assertEquals(SourceFormat.GPX, TrackImporter.detect(gpx.toByteArray()))
        assertEquals(SourceFormat.GPX, TrackImporter.detect("  \n$gpx".toByteArray()))
        assertEquals(SourceFormat.GPX, TrackImporter.detect("<topo:gpx xmlns:topo=\"x\"/>".toByteArray()))
        assertEquals(SourceFormat.KML, TrackImporter.detect(kml("point.kml")))
        assertEquals(SourceFormat.GEOJSON, TrackImporter.detect("  {\"type\":\"Point\"}".toByteArray()))
        assertNull(TrackImporter.detect("<html></html>".toByteArray()))
        assertNull(TrackImporter.detect(ByteArray(0)))
        assertFailsWith<TrackFormatException> { TrackImporter.read("hello".toByteArray(), "a.txt") }
        assertNotNull(TrackImporter.read(gpx.toByteArray()).waypoints.single())
        assertTrue(TrackImporter.read(kml("polygon.kml")).routes.isNotEmpty())
    }
}
