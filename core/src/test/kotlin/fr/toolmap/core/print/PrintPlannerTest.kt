package fr.toolmap.core.print

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.source.BuiltinSources
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintPlannerTest {
    private val sources = BuiltinSources.load().associateBy { it.id }
    private val stack = listOf(LayerConfig("l1", "ign-maps"), LayerConfig("l2", "ign-pentes", opacity = 0.5f))
    private val here = PrintArea(45.0, 6.0)

    private fun plan(
        template: PrintTemplate = PrintTemplate(scale = 50_000.0),
        area: PrintArea = here,
        layers: List<LayerConfig> = stack,
    ) = PrintPlanner.plan(template, area, layers, sources::get)

    @Test
    fun `a single page at 1 to 50 000 reads about two hundred tiles per layer`() {
        val p = plan()
        assertTrue(p.canExport, p.issues.toString())
        assertEquals(1, p.pages.size)
        val page = p.pages.single()
        assertEquals(PageKind.MAP, page.kind)
        assertEquals(15, page.layers.layers.first().tileZoom)
        val setup = PageSetup()
        assertEquals(PrintMath.pixels(setup.frameWidthMm, 300), page.raster.widthPx)
        assertEquals(PrintMath.pixels(setup.frameHeightMm, 300), page.raster.heightPx)
        val counts = p.tilesByLayer
        assertEquals(setOf("l1", "l2"), counts.keys)
        assertTrue(counts.getValue("l1") in 110L..260L, counts.toString())
        assertEquals(counts.values.sum(), p.totalTiles)
        assertEquals(PrintMath.edgeErrorPercent(setup.frameHeightMm, 50_000.0, 45.0), p.edgeErrorPercent, 0.002)
        val (low, high) = p.estimatedBytes
        assertTrue(low in 1L..high)
    }

    @Test
    fun `the distinct tiles of a page are listed source by source`() {
        val tiles = plan().requiredTiles()
        assertEquals(setOf("ign-maps", "ign-pentes"), tiles.keys)
        assertEquals(plan().tilesByLayer.getValue("l1"), tiles.getValue("ign-maps").size.toLong())
        assertTrue(tiles.getValue("ign-maps").all { it.z == 15 })
    }

    @Test
    fun `an unusable page, scale or layer set blocks the export`() {
        assertTrue(PrintIssue.PAGE_SETUP in plan(PrintTemplate(page = PageSetup(marginMm = 80.0))).issues)
        assertTrue(PrintIssue.SCALE_RANGE in plan(PrintTemplate(scale = 10.0)).issues)
        assertTrue(PrintIssue.SCALE_RANGE in plan(PrintTemplate(scale = Double.NaN)).issues)
        val hidden = plan(layers = listOf(LayerConfig("l1", "ign-maps", visible = false)))
        assertTrue(PrintIssue.NO_LAYER in hidden.issues)
        assertTrue(!hidden.canExport)
        val tooSmall =
            plan(
                PrintTemplate(
                    page = PageSetup(Paper.CUSTOM, customWidthMm = 60.0, customHeightMm = 60.0, marginMm = 25.0),
                ),
            )
        assertTrue(!tooSmall.canExport && tooSmall.pages.isEmpty())
    }

    @Test
    fun `a huge sheet or a huge number of tiles is refused`() {
        val huge =
            plan(
                PrintTemplate(
                    page = PageSetup(Paper.CUSTOM, customWidthMm = 1_500.0, customHeightMm = 1_500.0),
                    scale = 25_000.0,
                    dpi = 600,
                ),
            )
        assertTrue(PrintIssue.HUGE_PAGE in huge.issues)
        val area = Bounds(5.0, 44.0, 7.0, 45.5)
        val manyTiles =
            plan(PrintTemplate(scale = 50_000.0, atlas = AtlasMode.GRID), here.copy(bounds = area))
        assertTrue(PrintIssue.TOO_MANY_PAGES !in manyTiles.issues, manyTiles.issues.toString())
        assertTrue(manyTiles.totalTiles > PrintPlanner.MAX_TILES)
        assertTrue(PrintIssue.TOO_MANY_TILES in manyTiles.issues)
    }

    @Test
    fun `a layer outside its display range does not count and is reported once`() {
        val p = plan(layers = listOf(LayerConfig("l1", "ign-maps", displayMaxZoom = 10), LayerConfig("l2", "osm")))
        assertEquals(listOf("l2"), p.pages.single().layers.layers.map { it.layer.id })
        assertEquals(1, p.warnings.filterIsInstance<PrintWarning.OutsideRange>().size)
        val ignoring =
            plan(
                PrintTemplate(scale = 50_000.0, respectLayerRanges = false),
                layers = listOf(stack[0].copy(displayMaxZoom = 10)),
            )
        assertEquals(1, ignoring.pages.single().layers.layers.size)
    }

    @Test
    fun `a grid atlas covers the area with numbered pages and adds an overview page first`() {
        val area = Bounds(5.9, 44.9, 6.3, 45.15)
        val p =
            plan(
                PrintTemplate(scale = 50_000.0, atlas = AtlasMode.GRID, overlapPercent = 10.0),
                here.copy(bounds = area),
            )
        val atlas = assertNotNull(p.atlas)
        assertTrue(atlas.pages.size > 1)
        assertEquals(PageKind.OVERVIEW, p.pages.first().kind)
        assertEquals(atlas.pages.size, p.mapPages.size)
        assertEquals((1..atlas.pages.size).toList(), p.mapPages.map { it.number })
        // The overview is read at its own, lighter, resolution and scale.
        val overview = p.pages.first()
        assertEquals(PrintPlanner.OVERVIEW_DPI, overview.dpi)
        assertTrue(overview.frame.scale > 50_000.0)
        // The tiles are counted for the overview too, and a tile shared by two pages is listed once.
        val listed = p.requiredTiles().getValue("ign-maps").size
        assertTrue(listed in 1..p.tilesByLayer.getValue("l1").toInt())
    }

    @Test
    fun `the overview frame holds every footprint with some air`() {
        val area = Bounds(5.9, 44.9, 6.3, 45.15)
        val template = PrintTemplate(scale = 50_000.0)
        val atlas = AtlasPlanner.grid(area, template.scale, template.page, 10.0)
        val footprints = AtlasPlanner.overview(atlas, template.scale, template.page, 277.0, 190.0).footprints
        val frame = assertNotNull(PrintPlanner.overviewFrame(footprints, 277.0, 190.0))
        for (footprint in footprints) {
            for (corner in footprint.corners) {
                val p = frame.toFrame(corner.lon, corner.lat)
                assertTrue(p.xMm in 1.0..276.0 && p.yMm in 1.0..189.0, "$p")
            }
        }
        assertNull(PrintPlanner.overviewFrame(emptyList(), 277.0, 190.0))
    }

    @Test
    fun `an atlas without its area or track says so, and a too fine scale is refused`() {
        assertTrue(PrintIssue.NO_AREA in plan(PrintTemplate(atlas = AtlasMode.GRID)).issues)
        assertTrue(PrintIssue.NO_TRACK in plan(PrintTemplate(atlas = AtlasMode.TRACK)).issues)
        val wide = Bounds(-5.0, 41.0, 9.0, 51.0)
        val refused =
            plan(PrintTemplate(scale = 5_000.0, atlas = AtlasMode.GRID), here.copy(bounds = wide))
        assertTrue(PrintIssue.TOO_MANY_PAGES in refused.issues)
        val refusedAtlas = assertNotNull(refused.atlas)
        assertTrue(refusedAtlas.tooMany && refusedAtlas.pages.isEmpty())
    }

    @Test
    fun `an atlas along a track follows it page after page`() {
        val path = List(60) { i -> LonLat(6.0 + i * 0.01, 45.0 + i * 0.002) }
        val p = plan(PrintTemplate(scale = 50_000.0, atlas = AtlasMode.TRACK), here.copy(path = path))
        assertTrue(p.canExport, p.issues.toString())
        assertTrue(p.mapPages.size >= 2)
        assertEquals(path.first().lat, p.mapPages.first().frame.centerLat, 1e-9)
        val longitudes = p.mapPages.map { it.frame.centerLon }
        assertEquals(longitudes.sorted(), longitudes)
        // One page per frame, without the assembly page of a grid atlas (F9).
        assertTrue(p.pages.none { it.kind == PageKind.OVERVIEW })
        assertEquals(p.mapPages.size, p.pages.size)
        assertEquals((1..p.pages.size).toList(), p.pages.map { it.number })
    }

    @Test
    fun `an oversampled source warns once with its worst factor`() {
        val area = Bounds(5.9, 44.9, 5.96, 44.94)
        val p =
            plan(
                PrintTemplate(scale = 6_250.0, atlas = AtlasMode.GRID),
                here.copy(bounds = area),
                listOf(LayerConfig("l1", "ign-scan25")),
            )
        val warnings = p.warnings.filterIsInstance<PrintWarning.Oversampled>()
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().factor >= 3.2)
        assertIs<PrintWarning.Oversampled>(p.warnings.single())
    }

    @Test
    fun `a template survives its JSON, and an old or broken file gives defaults or nothing`() {
        val template =
            PrintTemplate(
                page = PageSetup(Paper.A3, landscape = false, marginMm = 12.0, style = MapStyle.NATO),
                scale = 50_000.0,
                dpi = 200,
                title = "Belledonne",
                subtitle = "Sortie du 12",
                dressing = Dressing(legend = false, waypointNames = false),
                grid = GridKind.MGRS,
                gridStep = 2_000.0,
                atlas = AtlasMode.GRID,
                overlapPercent = 15.0,
            )
        assertEquals(template, PrintTemplateCodec.decode(PrintTemplateCodec.encode(template)))
        val minimal = assertNotNull(PrintTemplateCodec.decode("""{"scale": 10000, "future": true}"""))
        assertEquals(10_000.0, minimal.scale)
        assertEquals(PrintTemplate.DEFAULT_DPI, minimal.dpi)
        assertNull(PrintTemplateCodec.decode("not json"))
        assertNull(PrintTemplateCodec.decode("[1, 2"))
        // A template saved before P17 (a name, a step in meters, a situation map) still reads.
        val oldFile = """{"name": "Rando", "gridStepMeters": 500, "dressing": {"miniMap": true}}"""
        val old = assertNotNull(PrintTemplateCodec.decode(oldFile))
        assertNull(old.gridStep)
        assertEquals(false, old.dressing.miniMap)
    }

    @Test
    fun `sanitizing brings figures back into range`() {
        val s = PrintTemplate(scale = 1.0, dpi = 5_000, overlapPercent = 90.0, gridStep = -3.0).sanitized()
        assertEquals(PrintTemplate.MIN_SCALE, s.scale)
        assertEquals(PrintTemplate.MAX_DPI, s.dpi)
        assertEquals(PrintTemplate.MAX_OVERLAP_PERCENT, s.overlapPercent)
        assertNull(s.gridStep)
        assertTrue(abs(PrintTemplate(scale = Double.NaN).sanitized().scale - PrintTemplate.DEFAULT_SCALE) < 1e-9)
        assertTrue(PrintTemplate.SCALES.all { it >= PrintTemplate.MIN_SCALE })
    }
}
