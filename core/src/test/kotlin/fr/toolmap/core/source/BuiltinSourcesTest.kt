package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.layers.LayerZoom
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.core.layers.WorkspaceLayer
import fr.toolmap.core.layers.Workspaces
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.core.tiles.TileUrlBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuiltinSourcesTest {
    private val sources = BuiltinSources.load()

    @Test
    fun `the resource parses without any warning`() {
        val text =
            BuiltinSources::class.java.getResourceAsStream(BuiltinSources.RESOURCE)!!
                .bufferedReader(Charsets.UTF_8)
                .readText()
        val result = AqxParser.parse(text)
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")
    }

    @Test
    fun `ids are unique and urls have no unresolved placeholder`() {
        assertEquals(sources.size, sources.map { it.id }.toSet().size)
        for (source in sources) {
            assertTrue(source.isSupported, "${source.id}: ${source.unsupportedReason}")
            val z = requireNotNull(source.previewLocation).zoom
            val preview = requireNotNull(source.previewLocation)
            assertNotNull(source.levelFor(z), "${source.id} has no level at its preview zoom $z")
            assertTrue(source.copyright != null, "${source.id} needs a copyright (attribution is mandatory)")
            if (source.isMultiZoom) {
                for (level in source.levels) {
                    val ref = assertNotNull(level.mapRef, "${source.id}: a multi-zoom map only shows other maps")
                    assertTrue(
                        sources.any {
                            it.id == ref.sourceId && !it.isMultiZoom
                        },
                        "${source.id}: ${ref.sourceId}",
                    )
                }
                continue
            }
            val tile = TileMath.tileAt(preview.lon, preview.lat, z)
            val url = requireNotNull(TileUrlBuilder(source).build(z, tile.x, tile.y)) { source.id }
            assertTrue("{" !in url && "}" !in url, "${source.id}: unresolved placeholder in $url")
            assertTrue(url.startsWith("https://"), "${source.id}: $url")
        }
    }

    @Test
    fun `IGN scan 25 is served from zoom 6 to 16 with the public key`() {
        // Checked on the server on 2026-10-03: TileMatrix 6 to 16 answer, 17 and 18 are 404.
        val scan25 = sources.first { it.id == "ign-scan25" }
        assertEquals(6, scan25.minZoom)
        assertEquals(16, scan25.maxZoom)
        val url = requireNotNull(TileUrlBuilder(scan25).build(14, 8460, 5906))
        assertTrue("apikey=ign_scan_ws" in url && "SCAN25TOUR" in url && url.endsWith("TileRow=5906"), url)
        assertEquals("https://data.geopf.fr/", scan25.levels.single().referer)
    }

    @Test
    fun `slopes is a half transparent overlay`() {
        val slopes = sources.first { it.id == "ign-pentes" }
        assertTrue(slopes.isLayer)
        assertEquals(50, slopes.opacity)
        assertEquals(1, slopes.minZoom)
        assertEquals(16, slopes.maxZoom)
    }

    @Test
    fun `OSM declares an identifying user agent and at most two connections`() {
        val level = sources.first { it.id == "osm" }.levels.single()
        assertTrue(level.userAgent.orEmpty().startsWith("ToolMap/"))
        assertEquals(2, level.maxThreads)
    }

    @Test
    fun `the Geoplateforme maps ask for six connections and the volunteer servers for two`() {
        // data.geopf.fr WMTS has no per-second limit (cartes.gouv.fr terms, art. 3.2); OSM and OpenTopoMap are volunteer
        // servers (ADR-058).
        val geopf =
            sources.filter { s ->
                s.levels.any { l -> l.servers.any { it.startsWith("https://data.geopf.fr/") } }
            }
        assertEquals(
            setOf("ign-maps", "ign-scan25", "ign-pentes", "ign-plan", "ign-ortho"),
            geopf.map { it.id }.toSet(),
        )
        for (source in geopf) {
            assertTrue(source.levels.all { it.maxThreads == 6 }, "${source.id}: ${source.levels.map { it.maxThreads }}")
        }
        for (id in listOf("osm", "opentopomap", "OSMSNOWM", "OSMSKIM", "TRACESHIVERNALES")) {
            assertEquals(2, sources.first { it.id == id }.levels.single().maxThreads, id)
        }
    }

    @Test
    fun `the catalogue holds every planned map plus the elevation source`() {
        val expected =
            setOf(
                "osm",
                "opentopomap",
                "ign-maps",
                "ign-plan",
                "ign-ortho",
                "ign-scan25",
                "ign-pentes",
                "ign-topo25k",
                "OSMSNOWM",
                "OSMSKIM",
                "TRACESHIVERNALES",
                "terrarium",
            )
        assertEquals(expected, sources.map { it.id }.toSet())
        assertEquals("dem", sources.first { it.id == "terrarium" }.type)
        assertEquals(
            listOf("ign-pentes", "OSMSKIM", "TRACESHIVERNALES"),
            sources.filter { it.isLayer }.map { it.id },
        )
    }

    @Test
    fun `the ski maps keep the ids of the author's files and ask OpenSnowMap with its referer`() {
        val snow = sources.first { it.id == "OSMSNOWM" }
        val pistes = sources.first { it.id == "OSMSKIM" }
        val tracks = sources.first { it.id == "TRACESHIVERNALES" }
        assertEquals(
            listOf("SnowBase Map", "Pistes Layer", "Traces_ski_yeti_api"),
            listOf(snow, pistes, tracks).map { it.name },
        )
        assertFalse(snow.isLayer)
        for (map in listOf(snow, pistes)) {
            val level = map.levels.single()
            assertEquals("3,5,7-17", level.zooms.toSpec(), map.id)
            assertEquals("http://www.opensnowmap.org/", level.referer, map.id)
            assertTrue(level.userAgent.orEmpty().startsWith("ToolMap/"), map.id)
        }
        val wms = tracks.levels.single()
        assertEquals("3,5,7-16", wms.zooms.toSpec())
        assertEquals(LevelDef.CRS_WEB_MERCATOR, wms.bboxCrs)
        val url = requireNotNull(TileUrlBuilder(tracks).build(13, 4250, 2918))
        assertTrue(url.startsWith("https://api.ensg.eu/geoserver/yeti/wms?") && "BBOX=7" in url, url)
    }

    @Test
    fun `the Sortie Ski favorite shows the snow map above 1550 m and the ski overlays from zoom 12`() {
        val ski = Workspaces.sortieSki
        val byId = sources.associateBy { it.id }
        assertEquals(
            listOf("ign-scan25", "OSMSNOWM", "OSMSKIM", "TRACESHIVERNALES", "ign-pentes"),
            ski.layers.map { it.sourceId },
        )
        assertTrue(ski.layers.all { byId[it.sourceId] != null && it.visible && it.rangeLogic == RangeLogic.AND })
        val (base, snow, pistes, tracks, slopes) = ski.layers
        assertEquals(WorkspaceLayer("ign-scan25"), base)
        assertEquals(1550, snow.displayMinElevation)
        assertEquals(
            listOf(null, null, null),
            listOf(snow.displayMaxElevation, snow.displayMinZoom, snow.displayMaxZoom),
        )
        for (layer in listOf(pistes, tracks, slopes)) {
            assertEquals(12, layer.displayMinZoom, layer.sourceId)
            assertNull(layer.displayMaxZoom, layer.sourceId)
            assertNull(layer.displayMinElevation, layer.sourceId)
        }
        assertEquals(listOf(1f, 1f, 0.3f), listOf(pistes, tracks, slopes).map { it.opacity })
        val stack = ski.toStack { true }.stack
        assertEquals(
            listOf("ign-scan25", "OSMSNOWM"),
            stack.layers.filter { LayerZoom.isActiveAt(it, 11.5) }.map { it.sourceId },
        )
        assertEquals(5, stack.layers.count { LayerZoom.isActiveAt(it, 12.5) })
    }

    @Test
    fun `OpenTopoMap rotates its three servers and identifies ToolMap`() {
        val topo = sources.first { it.id == "opentopomap" }
        val level = topo.levels.single()
        assertEquals(3, level.servers.size)
        assertTrue(level.userAgent.orEmpty().startsWith("ToolMap/"))
        val builder = TileUrlBuilder(topo)
        val hosts =
            List(3) { requireNotNull(builder.build(14, 8460, 5906)).substringAfter("https://").substringBefore('.') }
        assertEquals(listOf("a", "b", "c"), hosts)
    }

    @Test
    fun `IGN public layers need neither key nor referer`() {
        for (id in listOf("ign-plan", "ign-ortho", "ign-pentes")) {
            val level = sources.first { it.id == id }.levels.single()
            assertTrue(level.referer == null, "$id should not declare a referer")
            assertTrue(level.servers.none { "apikey" in it }, "$id should not need an API key")
        }
    }

    @Test
    fun `IGN Topo 25k keeps the 1 to 25,000 map from zoom 13 to 18 with the tiles of IGN Cartes`() {
        val topo25 = sources.first { it.id == "ign-topo25k" }
        assertTrue(topo25.isMultiZoom)
        assertEquals(1, topo25.minZoom)
        assertEquals(18, topo25.maxZoom)
        assertEquals(MapRef("ign-maps"), topo25.levelFor(12)?.mapRef)
        assertEquals(MapRef("ign-maps", 15), topo25.levelFor(13)?.mapRef)
        assertEquals(MapRef("ign-maps", 15), topo25.levelFor(14)?.mapRef)
        assertEquals(MapRef("ign-maps"), topo25.levelFor(16)?.mapRef)
        assertEquals(MapRef("ign-maps", 16), topo25.levelFor(18)?.mapRef)
        val byId = sources.associateBy { it.id }
        assertEquals(setOf("ign-maps"), StorageKeys(sources).storageIdsOf(topo25, byId::get))
    }

    @Test
    fun `the Rando Multi-Zoom favorite shows exactly one built-in map at each zoom from 1 to 19`() {
        val stack = Workspaces.randoMultiZoom.toStack { true }.stack
        val byId = sources.associateBy { it.id }
        assertTrue(stack.layers.all { byId[it.sourceId]?.isMultiZoom == false })
        val shown =
            (1..19).map { z ->
                stack.layers.filter { LayerZoom.isActiveAt(it, z + 0.5) }.map { it.sourceId }
            }
        assertTrue(shown.all { it.size == 1 }, "maps per zoom: $shown")
        assertEquals("osm", shown[8 - 1].single())
        assertEquals("opentopomap", shown[10 - 1].single())
        assertEquals("ign-scan25", shown[17 - 1].single())
        assertEquals("osm", shown[18 - 1].single())
        assertEquals("ign-ortho", shown[19 - 1].single())
    }
}
