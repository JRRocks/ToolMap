package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceDuplicatesTest {
    private val builtins = BuiltinSources.load()

    private fun fixture(name: String): List<TileSource> {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        return AqxParser.parse(File(dir, "aqx/$name").readText(Charsets.UTF_8)).file.sources
    }

    @Test
    fun `the user's IGN files are exact duplicates of built-in maps`() {
        val test4 = fixture("user/USR.AQ_TEST_4.AQX")
        assertEquals("ign-maps", SourceDuplicates.of(test4.first { it.id == "IGN_MAPS" }, builtins).duplicateOf?.id)
        // SCAN 25 up to zoom 16, like the built-in one since the server was checked.
        assertEquals(
            "ign-scan25",
            SourceDuplicates.of(
                test4.first {
                    it.id == "IGN_SCAN25TOUR"
                },
                builtins,
            ).duplicateOf?.id,
        )
        val pentes = fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").first { it.id == "PENTES" }
        assertEquals("ign-pentes", SourceDuplicates.of(pentes, builtins).duplicateOf?.id)
    }

    @Test
    fun `a map that goes further than the catalogue one only shares its tiles`() {
        val wider =
            builtins.first { it.id == "ign-scan25" }.let { scan ->
                scan.copy(id = "SCAN_WIDE", levels = listOf(scan.levels.single().copy(zooms = ZoomSet.range(6, 17))))
            }
        val info = SourceDuplicates.of(wider, builtins)
        assertNull(info.duplicateOf)
        assertEquals(listOf("ign-scan25"), info.sharesWith.map { it.id })
    }

    @Test
    fun `the multi-zoom file shares with four maps without duplicating any`() {
        val multi = fixture("user/USR.PERSONNAL_MULTIZOOM.AQX").single()
        val info = SourceDuplicates.of(multi, builtins)
        assertNull(info.duplicateOf)
        assertEquals(setOf("osm", "opentopomap", "ign-scan25", "ign-ortho"), info.sharesWith.map { it.id }.toSet())
    }

    @Test
    fun `new maps, the map itself and multi-zoom maps are not duplicates`() {
        val snow = fixture("user/COM.109.AQX")
        assertTrue(SourceDuplicates.of(snow.first(), builtins).isEmpty)
        val osm = builtins.first { it.id == "osm" }
        assertTrue(SourceDuplicates.of(osm, builtins).isEmpty)
        assertTrue(SourceDuplicates.of(builtins.first { it.id == "ign-topo25k" }, builtins).isEmpty)
        // A base map and a layer with the same tiles are kept apart.
        val asLayer = osm.copy(id = "osm-layer", isLayer = true)
        val info = SourceDuplicates.of(asLayer, builtins)
        assertNull(info.duplicateOf)
        assertEquals(listOf("osm"), info.sharesWith.map { it.id })
    }
}
