package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileSetIdentityTest {
    private val builtins = BuiltinSources.load()
    private val keys = StorageKeys(builtins)

    private fun fixture(name: String): List<TileSource> {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        return AqxParser.parse(File(dir, "aqx/$name").readText(Charsets.UTF_8)).file.sources
    }

    private fun builtin(id: String) = builtins.first { it.id == id }

    private fun storageOf(
        source: TileSource,
        zoom: Int,
    ) = keys.storageIdFor(source, zoom) { null }

    @Test
    fun `the same IGN map described by four files is stored once`() {
        // IGN Cartes (GEOGRAPHICALGRIDSYSTEMS.MAPS): built in, the reference fixture, AQ_TEST_4 and the ski touring file.
        val fixture = fixture("IGN_Topo_zoom_test.aqx").first { it.id == "IGN_MAPS" }
        val test4 = fixture("user/USR.AQ_TEST_4.AQX").first { it.id == "IGN_MAPS" }
        val skiTouring =
            fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").first {
                it.id == "IGN_TOP25"
            }
        for (source in listOf(fixture, test4, skiTouring)) {
            assertEquals("ign-maps", storageOf(source, 12), source.id)
        }
        // The built-in one writes its key as {$param:ign-apikey}, the files inline: the key is not part of the identity.
        assertEquals("ign-maps", storageOf(builtin("ign-maps"), 12))
    }

    @Test
    fun `SCAN 25 shares its storage whatever the zoom list of the file`() {
        val fixture = fixture("IGN_Topo_zoom_test.aqx").first { it.id == "IGN_SCAN25TOUR" } // zooms 6-14
        val test4 = fixture("user/USR.AQ_TEST_4.AQX").first { it.id == "IGN_SCAN25TOUR" } // zooms 6-16
        assertEquals("ign-scan25", storageOf(fixture, 10))
        assertEquals("ign-scan25", storageOf(test4, 16))
        assertNotEquals(storageOf(test4, 16), storageOf(fixture("IGN_Topo_zoom_test.aqx").first(), 16))
    }

    @Test
    fun `the personal multi-zoom file reuses the storage of five built-in maps`() {
        val multi = fixture("user/USR.PERSONNAL_MULTIZOOM.AQX").single()
        assertEquals("osm", storageOf(multi, 5))
        assertEquals("opentopomap", storageOf(multi, 10))
        assertEquals("ign-scan25", storageOf(multi, 14))
        assertEquals("osm", storageOf(multi, 18))
        assertEquals("ign-ortho", storageOf(multi, 19))
        // Zoom 17 is in no <zoom-levels> of the file: nothing is stored for it.
        assertNull(storageOf(multi, 17))
        assertEquals(
            setOf("osm", "opentopomap", "ign-scan25", "ign-ortho"),
            keys.storageIdsOf(multi) { null },
        )
    }

    @Test
    fun `slopes written with other parameter case and order is the built-in slopes layer`() {
        val pentes = fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").first { it.id == "PENTES" }
        assertEquals("ign-pentes", storageOf(pentes, 12))
    }

    @Test
    fun `normalization keeps what changes the image`() {
        val a = TileSetIdentity.normalizeServer("https://a.tile.example.org/{\$z}/{\$x}/{\$y}.png")
        val b = TileSetIdentity.normalizeServer("http://C.tile.example.org/{\$z}/{\$x}/{\$y}.png")
        assertEquals(a, b)
        // TMS rows are other tiles.
        assertNotEquals(a, TileSetIdentity.normalizeServer("https://a.tile.example.org/{\$z}/{\$x}/{\$!y}.png"))
        // Paths are case-sensitive.
        assertNotEquals(a, TileSetIdentity.normalizeServer("https://a.tile.example.org/{\$z}/{\$x}/{\$Y}.PNG"))
        // Different layers stay apart, keys never count.
        val wmts = "https://data.geopf.fr/wmts?layer=A&Format=image/png&TileMatrix={\$z}&apikey=one"
        assertEquals(
            TileSetIdentity.normalizeServer(wmts),
            TileSetIdentity.normalizeServer(
                "https://data.geopf.fr/wmts?FORMAT=image/png&LAYER=a&TILEMATRIX={\$z}&key=2",
            ),
        )
        assertNotEquals(
            TileSetIdentity.normalizeServer(wmts),
            TileSetIdentity.normalizeServer(wmts.replace("layer=A", "layer=B")),
        )
        // A two-label host has no rotating label to drop.
        assertEquals("example.org/t", TileSetIdentity.normalizeServer("https://example.org/t"))
    }

    @Test
    fun `parameters are substituted before comparing`() {
        val viaParam =
            TileSource(
                id = "p",
                name = "p",
                params = mapOf("layer" to "ORTHO"),
                levels = listOf(LevelDef(ZoomSet.range(1, 5), listOf("https://h.example.org/w?l={\$param:layer}"))),
            )
        val inline =
            viaParam.copy(
                id = "i",
                levels = listOf(LevelDef(ZoomSet.range(3, 9), listOf("https://h.example.org/w?l=ortho"))),
            )
        assertEquals(TileSetIdentity.allOf(viaParam), TileSetIdentity.allOf(inline))
        assertTrue(storageOf(viaParam, 3)!!.startsWith("t-"))
        assertEquals(storageOf(viaParam, 3), storageOf(inline, 3))
    }

    @Test
    fun `a local package and a reference have no identity`() {
        val local =
            TileSource(
                "pack",
                "Pack",
                levels = listOf(LevelDef(ZoomSet.range(1, 5), listOf(LevelDef.LOCAL_ONLY_SERVER))),
            )
        assertNull(TileSetIdentity.of(local, local.levels.single()))
        assertEquals("pack", storageOf(local, 3))
        val ref = LevelDef(ZoomSet.range(1, 5), listOf(MapRef("osm").toServer()))
        assertFalse(ref.hasServer)
        assertNull(TileSetIdentity.of(local, ref))
    }

    @Test
    fun `a multi-zoom map built in the app stores nothing of its own`() {
        val byId = builtins.associateBy { it.id }
        val topo25 =
            TileSource(
                id = "mz-topo25",
                name = "IGN Topo 25k",
                levels =
                    listOf(
                        LevelDef(ZoomSet.range(1, 12), listOf(MapRef("ign-maps").toServer())),
                        LevelDef(ZoomSet.range(13, 14), listOf(MapRef("ign-maps", 15).toServer())),
                        LevelDef(ZoomSet.range(15, 16), listOf(MapRef("ign-maps").toServer())),
                    ),
            )
        assertEquals("ign-maps", keys.storageIdFor(topo25, 12, byId::get))
        // A forced zoom is composed from the zoom-15 tiles: no zoom-13 tile is ever stored.
        assertNull(keys.storageIdFor(topo25, 13, byId::get))
        assertEquals(setOf("ign-maps"), keys.storageIdsOf(topo25, byId::get))
        // A deleted member leaves the level without storage.
        assertNull(keys.storageIdFor(topo25, 12) { null })
        val loop =
            TileSource(
                "loop",
                "loop",
                levels = listOf(LevelDef(ZoomSet.range(1, 5), listOf(MapRef("loop").toServer()))),
            )
        assertNull(keys.storageIdFor(loop, 3) { loop })
        assertEquals(emptySet(), keys.storageIdsOf(loop) { loop })
    }

    @Test
    fun `the author's ski files share the tiles of the built-in ski maps, whose old storage is taken over`() {
        // COM.109 (http) and the Yeti tracks of the ski touring file are built in since ADR-069 (https).
        val files =
            fixture("user/COM.109.AQX") +
                fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").filter {
                    it.id == "TRACESHIVERNALES"
                }
        assertEquals(listOf("OSMSNOWM", "OSMSKIM", "TRACESHIVERNALES"), files.map { it.id })
        for (file in files) {
            assertEquals(TileSetIdentity.allOf(builtin(file.id)), TileSetIdentity.allOf(file), file.id)
            assertEquals(file.id, storageOf(file, 13), file.id)
            // Tiles downloaded before, when the map was imported, were kept under the hashed name.
            val hashed = TileSetIdentity.storageIdOf(TileSetIdentity.allOf(file).single())
            assertEquals(file.id, keys.takenOverByBuiltins[hashed])
        }
    }

    @Test
    fun `storage ids are stable and short`() {
        val id = TileSetIdentity.storageIdOf("example.org/{\$z}/{\$x}/{\$y}#256")
        assertEquals(id, TileSetIdentity.storageIdOf("example.org/{\$z}/{\$x}/{\$y}#256"))
        assertEquals(18, id.length)
        assertTrue(id.matches(Regex("t-[0-9a-f]{16}")))
    }
}
