package fr.toolmap.core.aqx

import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.ClearColor
import fr.toolmap.core.source.LevelDef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AqxParserTest {
    private fun fixture(name: String): String {
        val dir = requireNotNull(System.getProperty("fixtures.dir")) { "fixtures.dir not set by Gradle" }
        return File(dir, name).readText()
    }

    private fun warningsOf(text: String) = AqxParser.parse(text).warnings.map { it.message }

    @Test
    fun `reference fixture gives two sources`() {
        val result = AqxParser.parse(fixture("aqx/IGN_Topo_zoom_test.aqx"))
        val file = result.file
        assertEquals(11, file.version)
        assertEquals("IGN Cartes Privées (ign_scan_ws)", file.name)
        assertEquals(listOf("IGN_MAPS", "IGN_SCAN25TOUR"), file.sources.map { it.id })
        assertTrue(result.warnings.isEmpty(), "unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `IGN_MAPS covers zooms 1 to 18`() {
        val source = AqxParser.parse(fixture("aqx/IGN_Topo_zoom_test.aqx")).file.sources.first { it.id == "IGN_MAPS" }
        assertEquals("IGN Cartes Topographiques", source.name)
        assertEquals("topo", source.type)
        assertEquals("IGN", source.copyright)
        assertEquals("WRLD", source.region)
        assertEquals("https://data.geopf.fr", source.dataSource)
        assertEquals(LonLatZoom(5.90, 44.80, 12), source.previewLocation)
        assertTrue(source.isSupported)
        assertFalse(source.isLayer)
        assertEquals(1, source.minZoom)
        assertEquals(18, source.maxZoom)
        val level = source.levels.single()
        assertTrue(level.zooms.isContiguous)
        assertEquals("https://data.geopf.fr/", level.referer)
        assertEquals(
            "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&layer=GEOGRAPHICALGRIDSYSTEMS.MAPS&style=normal" +
                "&tilematrixset=PM&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg" +
                "&TileMatrix={\$z}&TileCol={\$x}&TileRow={\$y}",
            level.servers.single(),
        )
    }

    @Test
    fun `IGN_SCAN25TOUR covers zooms 6 to 14`() {
        val source =
            AqxParser.parse(fixture("aqx/IGN_Topo_zoom_test.aqx")).file.sources
                .first { it.id == "IGN_SCAN25TOUR" }
        assertEquals(6, source.minZoom)
        assertEquals(14, source.maxZoom)
        assertNull(source.levelFor(5))
        assertNotNull(source.levelFor(14))
        assertEquals("https://data.geopf.fr/", source.levels.single().referer)
        assertTrue("GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR" in source.levels.single().servers.single())
    }

    @Test
    fun `second syntax with zoom-levels, several servers, headers and params`() {
        val text =
            """
            <aqx version="11">
              <param name="key">abc</param>
              <source id="A" layer="true" opacity="60" type="Hillshade">
                <name>Layer A</name>
                <outline>5.0,44.0 6.0,44.0 6.0,45.0</outline>
                <default-location>5.5,44.5</default-location>
                <zoom-levels z="1-3,5">
                  <server><![CDATA[https://a.example/{${'$'}z}/{${'$'}x}/{${'$'}y}?k={${'$'}param:key}]]></server>
                  <server>https://b.example/{${'$'}z}/{${'$'}x}/{${'$'}y}</server>
                  <user-agent>UA/1</user-agent>
                  <header>X-Test: 1</header>
                  <header>Accept: image/png</header>
                  <cookie>a=b</cookie>
                  <update-delay>2W</update-delay>
                  <max-threads>9</max-threads>
                  <tiles-size>512</tiles-size>
                  <clear-color r="240-255" g="240-255" b="250"/>
                </zoom-levels>
                <zoom-levels z="4"><server>https://c.example/{${'$'}q}</server></zoom-levels>
              </source>
            </aqx>
            """.trimIndent()
        val result = AqxParser.parse(text)
        assertTrue(result.warnings.isEmpty(), "unexpected warnings: ${result.warnings}")
        val source = result.file.sources.single()
        assertTrue(source.isLayer)
        assertEquals(60, source.opacity)
        assertEquals("hillshade", source.type)
        assertEquals(3, source.outline.size)
        assertEquals(5.5, source.defaultLocation?.lon)
        assertEquals("abc", source.params["key"])
        assertEquals(2, source.levels.size)
        val first = source.levels[0]
        assertEquals(listOf(1, 2, 3, 5), first.zooms.toList())
        assertEquals(2, first.servers.size)
        assertEquals("UA/1", first.userAgent)
        assertEquals(listOf("X-Test" to "1", "Accept" to "image/png"), first.headers)
        assertEquals("a=b", first.cookie)
        assertEquals("2W", first.updateDelay)
        assertEquals(9, first.maxThreads, "max-threads is kept up to the cap")
        assertEquals(
            16,
            AqxParser.parse(
                text.replace("<max-threads>9<", "<max-threads>40<"),
            ).file.sources.single().levels[0].maxThreads,
            "max-threads is capped at ${AqxParser.MAX_THREADS}",
        )
        assertEquals(512, first.tileSize)
        assertEquals(ClearColor(240..255, 240..255, 250..250), first.clearColor)
        assertEquals(first, source.levelFor(5))
        assertEquals(source.levels[1], source.levelFor(4))
        assertEquals(1, source.minZoom)
        assertEquals(5, source.maxZoom)
    }

    @Test
    fun `legacy zoom-level element and non contiguous zoom values`() {
        val text =
            """
            <aqx version="8">
              <source id="L"><name>Legacy</name>
                <zoom-level z="5"><server>https://x.example/{${'$'}z}</server></zoom-level>
                <level><zoom-values>8,10,12</zoom-values><server>https://y.example/{${'$'}z}</server></level>
              </source>
            </aqx>
            """.trimIndent()
        val source = AqxParser.parse(text).file.sources.single()
        assertEquals(2, source.levels.size)
        assertEquals(listOf(5), source.levels[0].zooms.toList())
        assertFalse(source.levels[1].zooms.isContiguous)
    }

    @Test
    fun `custom projection marks the source unsupported`() {
        val text =
            """
            <aqx version="11">
              <source id="P"><name>Lambert</name>
                <level><zoom-values>1-5</zoom-values><server>https://x.example/{${'$'}z}</server>
                  <projection-code>EPSG:2154</projection-code>
                  <projection-true-origin-x>700000</projection-true-origin-x>
                </level>
              </source>
              <source id="M"><name>Mercator</name>
                <level><zoom-values>1-5</zoom-values><server>https://x.example/{${'$'}z}</server>
                  <projection-code>EPSG:3857</projection-code>
                  <bbox-crs-code>3857</bbox-crs-code>
                </level>
              </source>
            </aqx>
            """.trimIndent()
        val (lambert, mercator) = AqxParser.parse(text).file.sources
        assertFalse(lambert.isSupported)
        assertTrue("EPSG:2154" in lambert.unsupportedReason.orEmpty())
        assertTrue(mercator.isSupported)
        assertEquals("EPSG:3857", mercator.levels.single().bboxCrs)
    }

    @Test
    fun `unknown elements and variables produce warnings but not failures`() {
        val text =
            """
            <aqx version="13">
              <mystery/>
              <source id="W"><name>W</name><whatever/>
                <level><zoom-values>1-2</zoom-values>
                  <server>https://x.example/{${'$'}z}/{${'$'}x}/{${'$'}y}</server>
                  <variable set="v" type="single"/>
                  <odd-tag/>
                </level>
              </source>
            </aqx>
            """.trimIndent()
        val result = AqxParser.parse(text)
        assertEquals(1, result.file.sources.size)
        assertTrue(result.file.sources.single().isSupported)
        val messages = result.warnings.map { it.message }
        assertTrue(messages.any { "<mystery>" in it })
        assertTrue(messages.any { "<whatever>" in it })
        assertTrue(messages.any { "<variable>" in it })
        assertTrue(messages.any { "<odd-tag>" in it })
    }

    @Test
    fun `broken levels and sources are skipped with a warning`() {
        val text =
            """
            <aqx version="11">
              <source><name>No id</name></source>
              <source id="S"><name>S</name>
                <level><zoom-values>1-3</zoom-values></level>
                <level><zoom-values>x</zoom-values><server>https://x.example/</server></level>
              </source>
              <source id="S"><name>Duplicate</name>
                <level><zoom-values>1</zoom-values><server>https://x.example/</server></level>
              </source>
            </aqx>
            """.trimIndent()
        val result = AqxParser.parse(text)
        val source = result.file.sources.single()
        assertEquals("S", source.id)
        assertFalse(source.isSupported, "a source without any usable level is unsupported")
        val messages = result.warnings.map { it.message }
        assertTrue(messages.any { "without an id" in it })
        assertTrue(messages.any { "no <server>" in it })
        assertTrue(messages.any { "level ignored" in it })
        assertTrue(messages.any { "duplicate source id" in it })
    }

    @Test
    fun `source without a name falls back to its id`() {
        val level = "<level><zoom-values>1</zoom-values><server>https://x/</server></level>"
        val text = """<aqx><source id="Z">$level</source></aqx>"""
        assertEquals("Z", AqxParser.parse(text).file.sources.single().name)
    }

    @Test
    fun `byte order mark is tolerated`() {
        val text = 0xFEFF.toChar() + "<?xml version=\"1.0\"?><aqx version=\"11\"></aqx>"
        assertEquals(0xFEFF, text.first().code, "the test input must really start with a BOM")
        assertEquals(0, AqxParser.parse(text).file.sources.size)
    }

    @Test
    fun `DOCTYPE and entities are rejected`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE aqx [<!ENTITY x SYSTEM "file:///etc/passwd">]><aqx>&x;</aqx>"""
        assertFailsWith<AqxParseException> { AqxParser.parse(xxe) }
        assertFailsWith<AqxParseException> { AqxParser.parse("<!doctype aqx><aqx/>") }
    }

    @Test
    fun `invalid input gives a clear exception`() {
        assertFailsWith<AqxParseException> { AqxParser.parse("") }
        assertFailsWith<AqxParseException> { AqxParser.parse("not xml at all") }
        assertFailsWith<AqxParseException> { AqxParser.parse("<aqx><source>") }
        val wrongRoot = assertFailsWith<AqxParseException> { AqxParser.parse("<gpx/>") }
        assertTrue("gpx" in wrongRoot.message.orEmpty())
    }

    @Test
    fun `oversized input is rejected`() {
        val huge = "<aqx>" + " ".repeat(AqxParser.MAX_FILE_CHARS) + "</aqx>"
        assertFailsWith<AqxParseException> { AqxParser.parse(huge) }
    }

    @Test
    fun `warnings are reported even for an empty file`() {
        assertEquals(emptyList(), warningsOf("""<aqx version="11"/>"""))
    }

    @Test
    fun `servers wrapped in a servers element are read`() {
        // OpenSnowMap (COM.109): <level><servers><referer/><server/></servers></level>.
        val result = AqxParser.parse(fixture("aqx/user/COM.109.AQX"))
        assertEquals(listOf("OSMSNOWM", "OSMSKIM"), result.file.sources.map { it.id })
        val base = result.file.sources.first()
        val level = base.levels.single()
        assertEquals(listOf("http://tiles.opensnowmap.org/base_snow_map/{\$z}/{\$x}/{\$y}.png"), level.servers)
        assertEquals("http://www.opensnowmap.org/", level.referer)
        assertEquals("3,5,7-17", level.zooms.toSpec())
        assertTrue(result.file.sources[1].isLayer)
        assertTrue(result.warnings.isEmpty(), "unexpected warnings: ${result.warnings}")
    }

    @Test
    fun `a WMS asking for Web Mercator gets its bbox in metres`() {
        val sources =
            AqxParser.parse(
                fixture("aqx/user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX"),
            ).file.sources
        val tracks = sources.first { it.id == "TRACESHIVERNALES" }
        assertEquals(LevelDef.CRS_WEB_MERCATOR, tracks.levels.single().bboxCrs)
        // An explicit <bbox-crs-code> always wins.
        val explicit =
            """<aqx version="11"><source id="w"><name>w</name><level><zoom-values>1-5</zoom-values>
              <bbox-crs-code>4326</bbox-crs-code>
              <server><![CDATA[https://h.example.org/wms?SRS=EPSG:3857&BBOX={${'$'}bbox}]]></server></level></source></aqx>"""
        assertEquals(LevelDef.CRS_WGS84, AqxParser.parse(explicit).file.sources.single().levels.single().bboxCrs)
    }
}
