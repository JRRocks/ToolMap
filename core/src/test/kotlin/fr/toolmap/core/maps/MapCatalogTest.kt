package fr.toolmap.core.maps

import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MapRef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapCatalogTest {
    private fun source(
        id: String,
        copyright: String? = null,
        dataSource: String? = null,
        isLayer: Boolean = false,
        type: String? = "topo",
        server: String = "https://example.org/{\$z}/{\$x}/{\$y}.png",
    ) = TileSource(
        id = id,
        name = id,
        type = type,
        isLayer = isLayer,
        copyright = copyright,
        dataSource = dataSource,
        levels = listOf(LevelDef(ZoomSet.range(0, 10), listOf(server))),
    )

    @Test
    fun `the shipped maps fall into the OpenStreetMap and IGN groups and the elevation data is hidden`() {
        val groups = MapCatalog.groups(BuiltinSources.load()) { false }
        assertEquals(
            listOf(GroupKind.OPENSTREETMAP, GroupKind.IGN, GroupKind.MULTI_ZOOM, GroupKind.OTHER),
            groups.map { it.kind },
        )
        // OpenSnowMap is OpenStreetMap data; its pistes overlay comes after the base maps.
        assertEquals(listOf("osm", "opentopomap", "OSMSNOWM", "OSMSKIM"), groups[0].sources.map { it.id })
        // Base maps first, then the overlay.
        assertEquals(
            listOf("ign-maps", "ign-scan25", "ign-plan", "ign-ortho", "ign-pentes"),
            groups[1].sources.map { it.id },
        )
        assertEquals(listOf("ign-topo25k"), groups[2].sources.map { it.id })
        assertNull(groups[2].credit)
        // The Yeti winter tracks (Petzl, ENSG) come from neither provider.
        assertEquals(listOf("TRACESHIVERNALES"), groups[3].sources.map { it.id })
        assertTrue(groups.flatMap { it.sources }.none { it.id == "terrarium" })
    }

    @Test
    fun `a map the user added goes to its own group even when it is an IGN map`() {
        val groups =
            MapCatalog.groups(listOf(source("IGN_MAPS", copyright = "IGN"), source("osm"))) {
                it == "IGN_MAPS"
            }
        assertEquals(listOf(GroupKind.IMPORTED, GroupKind.OTHER), groups.map { it.kind })
    }

    @Test
    fun `a map made of other maps goes to the multi-zoom group even when the user made it`() {
        val mz = source("mz-rando", server = MapRef("osm").toServer())
        assertEquals(GroupKind.MULTI_ZOOM, MapCatalog.kindOf(mz) { true })
    }

    @Test
    fun `a package of tiles with no server is local`() {
        val local = source("pack", server = LevelDef.LOCAL_ONLY_SERVER)
        assertEquals(GroupKind.LOCAL, MapCatalog.kindOf(local) { true })
    }

    @Test
    fun `IGN is recognised as a word and not inside another word`() {
        assertEquals(GroupKind.IGN, MapCatalog.kindOf(source("a", copyright = "(c) IGN 2026")) { false })
        assertEquals(GroupKind.IGN, MapCatalog.kindOf(source("b", dataSource = "https://data.geopf.fr")) { false })
        assertEquals(GroupKind.OTHER, MapCatalog.kindOf(source("c", copyright = "Designed by Virgin")) { false })
    }

    @Test
    fun `the credit line shows the owner and the site of a provider group only`() {
        val ign = MapCatalog.groups(BuiltinSources.load()) { false }.first { it.kind == GroupKind.IGN }
        assertEquals("© IGN · data.geopf.fr", ign.credit)
        val other = MapGroup(GroupKind.OTHER, listOf(source("x", copyright = "Somebody")))
        assertNull(other.credit)
    }

    @Test
    fun `a provided map hidden from the list comes back only when hidden maps are shown`() {
        val sources = BuiltinSources.load()
        val hidden = setOf("ign-plan", "ign-pentes")
        val shown = MapCatalog.groups(sources, hidden, showHidden = false) { false }
        assertEquals(
            listOf("ign-maps", "ign-scan25", "ign-ortho"),
            shown.first { it.kind == GroupKind.IGN }.sources.map { it.id },
        )
        val all = MapCatalog.groups(sources, hidden, showHidden = true) { false }
        assertEquals(MapCatalog.groups(sources) { false }, all)
        assertTrue(MapCatalog.isHidden("ign-plan", hidden) { false })
    }

    @Test
    fun `a map the user added is never hidden`() {
        val mine = source("mine", copyright = "IGN")
        val groups = MapCatalog.groups(listOf(mine), setOf("mine"), showHidden = false) { true }
        assertEquals(listOf("mine"), groups.flatMap { it.sources }.map { it.id })
        assertFalse(MapCatalog.isHidden("mine", setOf("mine")) { true })
    }

    @Test
    fun `the search looks at the name and the section title, without case or accents`() {
        val groups = MapCatalog.groups(BuiltinSources.load()) { false }
        val titles =
            mapOf(
                GroupKind.OPENSTREETMAP to "Cartes OpenStreetMap",
                GroupKind.IGN to "France · Cartes officielles IGN",
                GroupKind.MULTI_ZOOM to "Cartes multi-zoom",
                GroupKind.OTHER to "Autres cartes",
            )

        fun ids(query: String) =
            MapCatalog.search(groups, query) {
                titles.getValue(it)
            }.flatMap { g -> g.sources.map { it.id } }
        // "ign" is in the IGN section title: all its maps, plus the multi-zoom one named after IGN.
        assertEquals(listOf("ign-maps", "ign-scan25", "ign-plan", "ign-ortho", "ign-pentes", "ign-topo25k"), ids("ign"))
        assertEquals(listOf("ign-scan25"), ids("SCAN 25"))
        assertEquals(listOf("opentopomap"), ids("topo openstreetmap"))
        assertEquals(listOf("ign-maps", "ign-scan25", "ign-plan", "ign-ortho", "ign-pentes"), ids("officiélles"))
        assertEquals(groups, MapCatalog.search(groups, "  ") { titles.getValue(it) })
        assertTrue(ids("zzz").isEmpty())
    }

    @Test
    fun `copyright and host cleaning`() {
        assertEquals("IGN", MapCatalog.cleanCopyright(" (c) IGN "))
        assertEquals("OpenStreetMap", MapCatalog.cleanCopyright("© OpenStreetMap"))
        assertNull(MapCatalog.cleanCopyright("(c)"))
        assertEquals("openstreetmap.org", MapCatalog.hostOf("https://www.openstreetmap.org/copyright"))
        assertNull(MapCatalog.hostOf("not an address"))
    }

    @Test
    fun `matches finds every word, whatever the case and the accents, and nothing for an empty query`() {
        assertTrue(MapCatalog.matches("Pistes de ski (Waymarked Trails)", "ski PISTES"))
        assertTrue(MapCatalog.matches("IGN Plan v2", "plan ign"))
        assertTrue(MapCatalog.matches("Relief ombré", "OMBRE"))
        assertFalse(MapCatalog.matches("IGN Plan v2", "plan osm"))
        assertFalse(MapCatalog.matches("IGN Plan v2", "  "))
    }
}
