package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiZoomDraftTest {
    private val builtins = BuiltinSources.load()
    private val byId = builtins.associateBy { it.id }

    private fun fixture(name: String): List<TileSource> {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        return AqxParser.parse(File(dir, "aqx/$name").readText(Charsets.UTF_8)).file.sources
    }

    private fun kinds(
        draft: MultiZoomDraft,
        selfId: String? = null,
        lookup: (String) -> TileSource? = byId::get,
    ) = draft.issues(selfId, lookup).map { it.kind }

    @Test
    fun `the personal multi-zoom file becomes bands over the built-in maps`() {
        val file = fixture("user/USR.PERSONNAL_MULTIZOOM.AQX").single()
        val draft = MultiZoomDraft.derivedFrom(file, builtins + file, "Rando")
        assertEquals(
            listOf(
                ZoomBand(1, 8, "osm"),
                ZoomBand(9, 11, "opentopomap"),
                ZoomBand(12, 16, "ign-scan25"),
                ZoomBand(18, 18, "osm"),
                ZoomBand(19, 19, "ign-ortho"),
            ),
            draft.bands,
        )
        // Zoom 17 is in no level of the file: it stays a gap, filled from the nearest band.
        assertEquals(listOf(DraftIssue.Kind.GAP), kinds(draft))
        assertTrue(draft.canSave(null, byId::get))
    }

    @Test
    fun `holes in a zoom list become forced zooms, as AlpineQuest shows them`() {
        // Ski touring file: zoom values 3,5,7-16 of IGN Cartes.
        val top25 = fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").first { it.id == "IGN_TOP25" }
        val draft = MultiZoomDraft.derivedFrom(top25, builtins + top25, "Top 25")
        assertEquals(
            listOf(
                ZoomBand(3, 3, "ign-maps"),
                ZoomBand(4, 4, "ign-maps", 3),
                ZoomBand(5, 5, "ign-maps"),
                ZoomBand(6, 6, "ign-maps", 5),
                ZoomBand(7, 16, "ign-maps"),
            ),
            draft.bands,
        )
        assertEquals(emptyList(), kinds(draft))
    }

    @Test
    fun `a level no catalogue map serves points to the imported map itself`() {
        // OpenSnowMap is built in since ADR-069: the same file on another server is served by no catalogue map.
        val snow = fixture("user/COM.109.AQX").first()
        val elsewhere =
            snow.copy(
                id = "NEIGE",
                levels =
                    snow.levels.map {
                        it.copy(servers = listOf("https://snow.example.org/{\$z}/{\$x}/{\$y}.png"))
                    },
            )
        val draft = MultiZoomDraft.derivedFrom(elsewhere, builtins + elsewhere, "Neige")
        assertTrue(draft.bands.all { it.mapId == "NEIGE" })
        // The file itself now points to the built-in map that serves its tiles.
        assertTrue(MultiZoomDraft.derivedFrom(snow, builtins + snow, "Neige").bands.all { it.mapId == "OSMSNOWM" })
    }

    @Test
    fun `a multi-zoom map round-trips through its draft`() {
        val topo25 = byId.getValue("ign-topo25k")
        val draft = requireNotNull(MultiZoomDraft.of(topo25))
        assertEquals(
            listOf(
                ZoomBand(1, 12, "ign-maps"),
                ZoomBand(13, 14, "ign-maps", 15),
                ZoomBand(15, 16, "ign-maps"),
                ZoomBand(17, 18, "ign-maps", 16),
            ),
            draft.bands,
        )
        val saved = draft.toSource("ign-topo25k", byId::get)
        assertEquals(topo25.levels, saved.levels)
        assertEquals("IGN", saved.copyright)
        assertEquals("topo", saved.type)
        assertNull(MultiZoomDraft.of(byId.getValue("osm")))
    }

    @Test
    fun `blocking problems prevent saving`() {
        val ok = MultiZoomDraft("Ma carte", listOf(ZoomBand(1, 10, "osm"), ZoomBand(11, 16, "ign-scan25")))
        assertTrue(ok.canSave("mz-ma-carte", byId::get))
        assertEquals(listOf(DraftIssue.Kind.NO_NAME), kinds(ok.copy(name = " ")))
        assertEquals(listOf(DraftIssue.Kind.NO_BAND), kinds(ok.copy(bands = emptyList())))
        assertEquals(
            listOf(DraftIssue.Kind.OVERLAP),
            kinds(ok.copy(bands = listOf(ZoomBand(1, 12, "osm"), ZoomBand(11, 16, "ign-scan25")))),
        )
        assertEquals(listOf(DraftIssue.Kind.BAD_RANGE), kinds(ok.copy(bands = listOf(ZoomBand(9, 3, "osm")))))
        assertEquals(listOf(DraftIssue.Kind.UNKNOWN_MAP), kinds(ok.copy(bands = listOf(ZoomBand(1, 3, "gone")))))
        assertEquals(
            listOf(DraftIssue.Kind.FORCED_TOO_FAR),
            kinds(ok.copy(bands = listOf(ZoomBand(1, 10, "ign-maps", 15)))),
        )
        val far = ok.copy(bands = listOf(ZoomBand(11, 14, "ign-maps", 15)))
        assertFalse(far.issues(null, byId::get).any { it.kind.blocking })
    }

    @Test
    fun `a band showing a map that shows this one is a loop`() {
        val mine = MultiZoomDraft("A", listOf(ZoomBand(1, 18, "ign-maps"))).toSource("mz-a", byId::get)
        val other = MultiZoomDraft("B", listOf(ZoomBand(1, 18, "mz-a"))).toSource("mz-b", byId::get)
        val lookup = (byId + mapOf(mine.id to mine, other.id to other))::get
        val edited = MultiZoomDraft("A", listOf(ZoomBand(1, 18, "mz-b")))
        assertEquals(listOf(DraftIssue.Kind.LOOP), kinds(edited, "mz-a", lookup))
        assertEquals(
            listOf(DraftIssue.Kind.LOOP),
            kinds(MultiZoomDraft("A", listOf(ZoomBand(1, 18, "mz-a"))), "mz-a", lookup),
        )
        // A new map (no id yet) cannot be referenced by anything.
        assertEquals(emptyList(), kinds(edited, null, lookup))
    }

    @Test
    fun `informative problems do not block`() {
        val draft = MultiZoomDraft("Info", listOf(ZoomBand(1, 3, "ign-scan25"), ZoomBand(17, 19, "osm", 20)))
        assertEquals(
            listOf(DraftIssue.Kind.NOT_SERVED, DraftIssue.Kind.GAP, DraftIssue.Kind.FORCED_NOT_SERVED),
            kinds(draft),
        )
        assertTrue(draft.canSave(null, byId::get))
    }

    @Test
    fun `new ids are readable and free`() {
        assertEquals("mz-rando-ete", MultiZoomDraft.newId("Rando été") { false })
        assertEquals("mz-rando-ete-2", MultiZoomDraft.newId("Rando été") { it == "mz-rando-ete" })
        assertEquals("mz-carte", MultiZoomDraft.newId("!!!") { false })
    }
}
