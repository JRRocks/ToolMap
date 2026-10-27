package fr.toolmap.core.aqx

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.ClearColor
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AqxWriterTest {
    private fun roundTrip(sources: List<TileSource>): AqxParseResult = AqxParser.parse(AqxWriter.write(sources))

    private fun fixtureText(): String {
        val dir = File(requireNotNull(System.getProperty("fixtures.dir")))
        return File(dir, "aqx/IGN_Topo_zoom_test.aqx").readText(Charsets.UTF_8)
    }

    @Test
    fun `the built-in catalogue survives a write then parse`() {
        val original = BuiltinSources.load()
        val result = roundTrip(original)
        assertEquals(original, result.file.sources)
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")
    }

    @Test
    fun `the user fixture survives a write then parse`() {
        val original = AqxParser.parse(fixtureText()).file.sources.filter { it.isSupported }
        assertEquals(2, original.size)
        assertEquals(original, roundTrip(original).file.sources)
    }

    @Test
    fun `every model field is written and read back`() {
        val source =
            TileSource(
                id = "full",
                name = "Full & <odd> \"name\"",
                type = "topo",
                isLayer = true,
                opacity = 35,
                copyright = "(c) Someone & co",
                dataSource = "https://example.org/data",
                region = "FR",
                outline = listOf(LonLat(1.0, 2.5), LonLat(3.25, 4.0), LonLat(-5.0, -6.0)),
                previewLocation = LonLatZoom(5.9, 44.8, 12),
                defaultLocation = LonLat(5.5, 45.5),
                params = mapOf("key" to "a&b]]>c", "other" to "x"),
                levels =
                    listOf(
                        LevelDef(
                            zooms = ZoomSet.parse("1-3,5,7-9"),
                            servers =
                                listOf(
                                    "https://a.example/{\$z}/{\$x}/{\$y}.png?k={\$param:key}&v=1",
                                    "https://b.example/{\$q}",
                                ),
                            referer = "https://ref.example/",
                            userAgent = "UA/1.0 (+x)",
                            headers = listOf("X-One" to "1", "Accept" to "image/png: yes"),
                            cookie = "a=b; c=d",
                            updateDelay = "2W",
                            maxThreads = 2,
                            insecure = true,
                            tileSize = 512,
                            clearColor = ClearColor(250..255, 0..10, 7..7),
                            keyUrl = "https://example.org/key",
                            bboxCrs = LevelDef.CRS_WEB_MERCATOR,
                        ),
                        LevelDef(
                            zooms = ZoomSet.range(10, 12),
                            servers = listOf("https://c.example/{\$z}/{\$x}/{\$y}"),
                        ),
                    ),
            )
        val result = roundTrip(listOf(source))
        assertEquals(listOf(source), result.file.sources)
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")
    }

    @Test
    fun `secrets can be redacted when sharing a file`() {
        val source =
            TileSource(
                id = "s",
                name = "S",
                params = mapOf("ign-apikey" to "SECRET", "lang" to "fr"),
                levels = listOf(LevelDef(ZoomSet.range(0, 5), listOf("https://x/{\$z}/{\$x}/{\$y}"))),
            )
        val text = AqxWriter.write(listOf(source), redactedParams = setOf("ign-apikey"))
        assertTrue("SECRET" !in text)
        val back = AqxParser.parse(text).file.sources.single()
        assertEquals("", back.params["ign-apikey"])
        assertEquals("fr", back.params["lang"])
    }

    @Test
    fun `file name and description are kept and the output is well formed XML`() {
        val text = AqxWriter.write(emptyList(), name = "Pack <1>", description = "d & e")
        val file = AqxParser.parse(text).file
        assertEquals("Pack <1>", file.name)
        assertEquals("d & e", file.description)
        assertEquals(AqxWriter.VERSION, file.version)
    }
}
