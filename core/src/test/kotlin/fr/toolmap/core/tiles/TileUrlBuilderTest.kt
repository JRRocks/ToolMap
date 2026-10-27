package fr.toolmap.core.tiles

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileUrlBuilderTest {
    private fun source(
        vararg servers: String,
        params: Map<String, String> = emptyMap(),
        bboxCrs: String = LevelDef.CRS_WGS84,
    ) = TileSource(
        id = "T",
        name = "T",
        params = params,
        levels = listOf(LevelDef(ZoomSet.range(0, 20), servers.toList(), bboxCrs = bboxCrs)),
    )

    private fun url(
        source: TileSource,
        z: Int,
        x: Int,
        y: Int,
    ) = TileUrlBuilder(source).build(z, x, y)

    @Test
    fun `xyz placeholders`() {
        assertEquals("https://t.example/3/3/5.png", url(source("https://t.example/{\$z}/{\$x}/{\$y}.png"), 3, 3, 5))
    }

    @Test
    fun `tms row`() {
        assertEquals("https://t.example/3/3/2", url(source("https://t.example/{\$z}/{\$x}/{\$!y}"), 3, 3, 5))
    }

    @Test
    fun `quadkey`() {
        assertEquals("https://t.example/213.jpg", url(source("https://t.example/{\$q}.jpg"), 3, 3, 5))
    }

    @Test
    fun `param placeholder and missing param`() {
        val withKey = source("https://t.example/{\$z}?apikey={\$param:apikey}", params = mapOf("apikey" to "k1"))
        assertEquals("https://t.example/4?apikey=k1", url(withKey, 4, 1, 1))
        val withoutKey = source("https://t.example/{\$z}?apikey={\$param:apikey}")
        val error = assertFailsWith<TileUrlException> { url(withoutKey, 4, 1, 1) }
        assertTrue("apikey" in error.message.orEmpty())
    }

    @Test
    fun `bbox in WGS84 is the default`() {
        // World tile at zoom 1, top-left quadrant.
        assertEquals("bbox=-180,0,0,85.0511287798", url(source("bbox={\$bbox}"), 1, 0, 0))
    }

    @Test
    fun `bbox in Web Mercator`() {
        val mercator = source("bbox={\$bbox}", bboxCrs = LevelDef.CRS_WEB_MERCATOR)
        assertEquals("bbox=-20037508.3428,0,0,20037508.3428", url(mercator, 1, 0, 0))
    }

    @Test
    fun `integer bbox`() {
        val mercator = source("{\$ibbox}", bboxCrs = LevelDef.CRS_WEB_MERCATOR)
        assertEquals("-20037508,0,0,20037508", url(mercator, 1, 0, 0))
    }

    @Test
    fun `bbox uses a dot as decimal separator whatever the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val bbox = url(source("{\$bbox}"), 5, 10, 10)!!
            assertEquals(3, bbox.count { it == ',' }, "only the 4 values are comma separated: $bbox")
            assertTrue('.' in bbox, "decimal separator must be a dot: $bbox")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `unknown placeholder is an error`() {
        val error = assertFailsWith<TileUrlException> { url(source("https://{\$serverpart}.example/{\$z}"), 1, 0, 0) }
        assertTrue("serverpart" in error.message.orEmpty())
    }

    @Test
    fun `servers rotate round robin`() {
        val builder = TileUrlBuilder(source("https://a/{\$z}", "https://b/{\$z}", "https://c/{\$z}"))
        val urls = (0 until 7).map { builder.build(2, 0, 0) }
        assertEquals(
            listOf(
                "https://a/2",
                "https://b/2",
                "https://c/2",
                "https://a/2",
                "https://b/2",
                "https://c/2",
                "https://a/2",
            ),
            urls,
        )
    }

    @Test
    fun `explicit server index is deterministic and wraps`() {
        val source = source("https://a/{\$z}", "https://b/{\$z}")
        val builder = TileUrlBuilder(source)
        val level = source.levels.single()
        assertEquals("https://b/1", builder.build(level, 1, 0, 0, 1))
        assertEquals("https://b/1", builder.build(level, 1, 0, 0, 3))
        assertEquals("https://b/1", builder.build(level, 1, 0, 0, -1))
    }

    @Test
    fun `zoom outside every level gives null`() {
        val limited =
            TileSource(
                id = "L",
                name = "L",
                levels = listOf(LevelDef(ZoomSet.range(6, 14), listOf("https://t/{\$z}"))),
            )
        assertNull(TileUrlBuilder(limited).build(5, 0, 0))
        assertEquals("https://t/6", TileUrlBuilder(limited).build(6, 0, 0))
    }

    @Test
    fun `reference IGN url at zoom 14`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        val file = AqxParser.parse(File(dir, "aqx/IGN_Topo_zoom_test.aqx").readText()).file
        val scan25 = file.sources.first { it.id == "IGN_SCAN25TOUR" }
        // Same tile as the curl check of session 1 (HTTP 200, image/jpeg).
        assertEquals(
            "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&layer=GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR" +
                "&style=normal&tilematrixset=PM&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg" +
                "&TileMatrix=14&TileCol=8460&TileRow=5950",
            TileUrlBuilder(scan25).build(14, 8460, 5950),
        )
        assertNull(TileUrlBuilder(scan25).build(15, 0, 0), "SCAN 25 has no tile above zoom 14")
    }
}
