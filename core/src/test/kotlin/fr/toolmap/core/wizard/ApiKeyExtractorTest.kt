package fr.toolmap.core.wizard

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.tiles.TileUrlBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiKeyExtractorTest {
    @Test
    fun `key of the reference fixture url is extracted`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        val source =
            AqxParser
                .parse(File(dir, "aqx/IGN_Topo_zoom_test.aqx").readText())
                .file.sources
                .first { it.id == "IGN_SCAN25TOUR" }
        val pasted = source.levels.first().servers.first()
        assertTrue(pasted.contains("apikey=ign_scan_ws"), "the fixture writes the key in the url")

        val result = ApiKeyExtractor.extract(pasted)
        assertTrue(result.found)
        assertEquals(mapOf("apikey" to "ign_scan_ws"), result.params)
        assertFalse(result.template.contains("ign_scan_ws"))
        assertTrue(result.template.contains("apikey={\$param:apikey}"))

        // The rewritten source asks for exactly the same tile as the original one.
        val level = source.levels.first().copy(servers = listOf(result.template))
        val rewritten = source.copy(params = source.params + result.params, levels = listOf(level))
        assertEquals(
            TileUrlBuilder(source).build(14, 8460, 5950),
            TileUrlBuilder(rewritten).build(14, 8460, 5950),
        )
    }

    @Test
    fun `several names and case are handled`() {
        val result = ApiKeyExtractor.extract("https://t.example/{\$z}/{\$x}/{\$y}.png?Key=abc&token=def&size=2")
        assertEquals(mapOf("key" to "abc", "token" to "def"), result.params)
        assertEquals(
            "https://t.example/{\$z}/{\$x}/{\$y}.png?Key={\$param:key}&token={\$param:token}&size=2",
            result.template,
        )
    }

    @Test
    fun `placeholders, empty values and other parameters are left alone`() {
        for (url in listOf(
            "https://t/{\$z}?apikey={\$param:apikey}",
            "https://t/{\$z}?apikey=",
            "https://t/{\$z}?layer=apikey&size=3",
            "https://t/apikey=abc/{\$z}",
        )) {
            val result = ApiKeyExtractor.extract(url)
            assertFalse(result.found, url)
            assertEquals(url, result.template)
        }
    }

    @Test
    fun `a name already used with another value gets a numbered parameter`() {
        val result = ApiKeyExtractor.extract("https://t/{\$z}?apikey=second", mapOf("apikey" to "first"))
        assertEquals(mapOf("apikey-2" to "second"), result.params)
        assertEquals("https://t/{\$z}?apikey={\$param:apikey-2}", result.template)
        // Same value: the existing parameter is reused.
        val same = ApiKeyExtractor.extract("https://t/{\$z}?apikey=first", mapOf("apikey" to "first"))
        assertEquals("https://t/{\$z}?apikey={\$param:apikey}", same.template)
    }

    @Test
    fun `masked shows only the end of the secret`() {
        assertEquals("••••••n_ws", ApiKeyExtractor.masked("ign_scan_ws"))
        assertEquals("•••", ApiKeyExtractor.masked("abc"))
        assertEquals("", ApiKeyExtractor.masked(""))
    }
}
