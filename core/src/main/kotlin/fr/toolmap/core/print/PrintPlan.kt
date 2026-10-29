package fr.toolmap.core.print

import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileMath

/** Where the print is taken from: the screen centre, plus the area or the track an atlas covers. */
data class PrintArea(
    val centerLat: Double,
    val centerLon: Double,
    /** The area of a [AtlasMode.GRID] atlas, in degrees. */
    val bounds: Bounds? = null,
    /** The track an [AtlasMode.TRACK] atlas follows. */
    val path: List<LonLat> = emptyList(),
)

/** Reasons that keep a print from being exported; each one has to be fixed by the user. */
enum class PrintIssue {
    PAGE_SETUP,
    SCALE_RANGE,
    NO_AREA,
    NO_TRACK,
    TOO_MANY_PAGES,
    HUGE_PAGE,
    TOO_MANY_TILES,
    NO_LAYER,
}

enum class PageKind { MAP, OVERVIEW, MINIMAP }

/** One page of the PDF: the map frame, its raster and the layers drawn in it. */
class PrintPagePlan(
    val kind: PageKind,
    /** 1-based; the overview of an atlas is number 0. */
    val number: Int,
    val frame: FrameProjection,
    val raster: PrintRaster,
    val dpi: Int,
    val layers: PrintLayerPlan,
    /** The small situation map of this page, when asked for. */
    val miniMap: PrintPagePlan? = null,
    /** Outlines drawn over an overview or a situation map; the page number tells which one is the current page. */
    val footprints: List<PageFootprint> = emptyList(),
) {
    /** The tile zooms of every layer: how many tiles the page reads of each. */
    fun tileCounts(): Map<String, Long> = layers.layers.associate { it.layer.id to raster.tileCount(it.tileZoom) }
}

/** The whole job: its pages, its size, what it needs and what the user should be told. */
class PrintPlan(
    val template: PrintTemplate,
    val pages: List<PrintPagePlan>,
    val atlas: AtlasPlan?,
    val warnings: List<PrintWarning>,
    val issues: List<PrintIssue>,
    /** Tiles read, layer by layer, summed over the pages (neighbouring pages share a few). */
    val tilesByLayer: Map<String, Long>,
    val totalTiles: Long,
    val estimatedBytes: Pair<Long, Long>,
    /** Scale error at the north and south edges of the first map page, in percent. */
    val edgeErrorPercent: Double,
) {
    val canExport: Boolean get() = issues.isEmpty() && pages.isNotEmpty()

    val mapPages: List<PrintPagePlan> get() = pages.filter { it.kind == PageKind.MAP }

    /** Every raster the export reads tiles for: the pages and their situation maps (shared ones count once). */
    val rasters: List<PrintPagePlan> get() = (pages + pages.mapNotNull { it.miniMap }).distinct()

    /**
     * The distinct tiles every page needs, source by source: what to look for in local storage before exporting
     * and what to download for the missing ones. A layer drawn in an elevation band also needs the elevation tiles
     * under its own.
     */
    fun requiredTiles(): Map<String, Set<TileId>> {
        val out = LinkedHashMap<String, MutableSet<TileId>>()
        for (page in rasters) {
            for (layer in page.layers.layers) {
                val tiles = page.raster.tiles(layer.tileZoom)
                out.getOrPut(layer.source.id) { LinkedHashSet() } += tiles
                val band = layer.band ?: continue
                out.getOrPut(band.dem.id) { LinkedHashSet() } +=
                    tiles.map { DemWindow.of(it.z, it.x, it.y).let { w -> TileId(w.demZ, w.demX, w.demY) } }
            }
        }
        return out
    }
}

/** Turns a template, a position and the layer stack into the pages to draw and the figures to show (spec §2, §5, §6). */
object PrintPlanner {
    /** More pixels than this on one page would not fit in a PDF a phone can write or a printer can read. */
    const val MAX_PIXELS_PER_PAGE = 150_000_000L

    const val MAX_TILES = 40_000L

    /** Resolution of the assembly page of an atlas: a map for finding one's way among the pages, not for reading. */
    const val OVERVIEW_DPI = 100

    private const val OVERVIEW_PADDING = 0.06

    /** A single page in its situation map takes about a quarter of the width: lots of surroundings to read. */
    private const val SITUATION_PADDING = 1.0

    const val MINI_MAP_WIDTH_MM = 44.0
    const val MINI_MAP_HEIGHT_MM = 30.0
    const val MINI_MAP_DPI = 150

    fun plan(
        template: PrintTemplate,
        area: PrintArea,
        stack: List<LayerConfig>,
        sources: (String) -> TileSource?,
    ): PrintPlan {
        val t = template.sanitized()
        val issues = LinkedHashSet<PrintIssue>()
        if (template.page.issues().isNotEmpty() || t.page.issues().isNotEmpty()) issues += PrintIssue.PAGE_SETUP
        if (template.scale !in PrintTemplate.MIN_SCALE..PrintTemplate.MAX_SCALE) issues += PrintIssue.SCALE_RANGE
        if (PageIssue.FRAME_TOO_SMALL in t.page.issues() || PageIssue.CUSTOM_SIZE in t.page.issues()) {
            return empty(t, issues)
        }

        val atlas = atlasOf(t, area, issues)
        val centers = centersOf(t, area, atlas)
        val pages = ArrayList<PrintPagePlan>()
        val footprints = atlas?.let { AtlasPlanner.overview(it, t.scale, t.page, 1.0, 1.0).footprints }.orEmpty()
        // The assembly page opens a grid atlas; an atlas along a track is one page per frame (F9): its pages follow
        // each other in the order of the walk.
        if (atlas != null && atlas.pages.size > 1 && t.atlas == AtlasMode.GRID) {
            overviewOf(t, footprints, stack, sources)?.let { pages += it }
        }
        val sharedMini =
            if (t.dressing.miniMap && footprints.size > 1) {
                miniMapOf(t, footprints, OVERVIEW_PADDING, stack, sources)
            } else {
                null
            }
        for (centre in centers) {
            val frame =
                FrameProjection(centre.centerLat, centre.centerLon, t.scale, t.page.frameWidthMm, t.page.frameHeightMm)
            val raster = PrintRaster(frame, t.dpi)
            val layers = layersOf(t, stack, sources, frame.centerLat, t.scale, t.dpi)
            val mini =
                when {
                    !t.dressing.miniMap -> null
                    sharedMini != null -> sharedMini
                    else -> miniMapOf(t, listOf(footprintOf(frame, centre.number)), SITUATION_PADDING, stack, sources)
                }
            pages += PrintPagePlan(PageKind.MAP, centre.number, frame, raster, t.dpi, layers, mini)
        }

        val mapPages = pages.filter { it.kind == PageKind.MAP }
        if (mapPages.any { it.raster.pixels > MAX_PIXELS_PER_PAGE }) issues += PrintIssue.HUGE_PAGE
        if (mapPages.isNotEmpty() && mapPages.all { it.layers.layers.isEmpty() }) issues += PrintIssue.NO_LAYER

        val byLayer = LinkedHashMap<String, Long>()
        var total = 0L
        val all = pages + pages.mapNotNull { it.miniMap }.distinct()
        for (page in all) {
            for ((id, count) in page.tileCounts()) {
                byLayer[id] = (byLayer[id] ?: 0L) + count
                total += count
            }
        }
        if (total > MAX_TILES) issues += PrintIssue.TOO_MANY_TILES

        val pixels = all.sumOf { it.raster.pixels }
        val bytes = PrintMath.estimatedBytes(pixels, 1)
        val edge =
            mapPages.firstOrNull()?.let {
                PrintMath.edgeErrorPercent(t.page.frameHeightMm, t.scale, it.frame.centerLat)
            }
                ?: 0.0
        return PrintPlan(t, pages, atlas, mergeWarnings(all), issues.toList(), byLayer, total, bytes, edge)
    }

    /** The frame of the assembly page: every page footprint inside it, with a little air around. */
    fun overviewFrame(
        footprints: List<PageFootprint>,
        widthMm: Double,
        heightMm: Double,
        padding: Double = OVERVIEW_PADDING,
    ): FrameProjection? {
        val corners = footprints.flatMap { it.corners }
        if (corners.isEmpty()) return null
        val xs = corners.map { TileMath.mercatorX(it.lon) }
        val ys = corners.map { TileMath.mercatorY(it.lat) }
        val centerX = (xs.min() + xs.max()) / 2
        val centerY = (ys.min() + ys.max()) / 2
        val spanX = maxOf(xs.max() - xs.min(), 1.0) * (1 + 2 * padding)
        val spanY = maxOf(ys.max() - ys.min(), 1.0) * (1 + 2 * padding)
        val mercatorPerMm = maxOf(spanX / widthMm, spanY / heightMm)
        val lat = Math.toDegrees(2 * Math.atan(Math.exp(centerY / TileMath.EARTH_RADIUS_M)) - Math.PI / 2)
        val lon = Math.toDegrees(centerX / TileMath.EARTH_RADIUS_M)
        // mercatorPerMm = scale / 1000 / cos(lat): the scale the overview is drawn at.
        val scale = mercatorPerMm * 1000.0 / PrintMath.mercatorFactor(lat)
        return FrameProjection(lat, lon, scale, widthMm, heightMm)
    }

    private fun overviewOf(
        t: PrintTemplate,
        footprints: List<PageFootprint>,
        stack: List<LayerConfig>,
        sources: (String) -> TileSource?,
    ): PrintPagePlan? {
        val frame = overviewFrame(footprints, t.page.frameWidthMm, t.page.frameHeightMm) ?: return null
        val layers = layersOf(t, stack, sources, frame.centerLat, frame.scale, OVERVIEW_DPI)
        return PrintPagePlan(
            PageKind.OVERVIEW,
            0,
            frame,
            PrintRaster(frame, OVERVIEW_DPI),
            OVERVIEW_DPI,
            layers,
            footprints = footprints,
        )
    }

    private fun miniMapOf(
        t: PrintTemplate,
        footprints: List<PageFootprint>,
        padding: Double,
        stack: List<LayerConfig>,
        sources: (String) -> TileSource?,
    ): PrintPagePlan? {
        val frame = overviewFrame(footprints, MINI_MAP_WIDTH_MM, MINI_MAP_HEIGHT_MM, padding) ?: return null
        val layers = layersOf(t, stack, sources, frame.centerLat, frame.scale, MINI_MAP_DPI)
        return PrintPagePlan(
            PageKind.MINIMAP,
            0,
            frame,
            PrintRaster(frame, MINI_MAP_DPI),
            MINI_MAP_DPI,
            layers,
            footprints = footprints,
        )
    }

    private fun footprintOf(
        frame: FrameProjection,
        number: Int,
    ): PageFootprint =
        PageFootprint(
            number,
            listOf(
                frame.toLonLat(0.0, 0.0),
                frame.toLonLat(frame.frameWidthMm, 0.0),
                frame.toLonLat(frame.frameWidthMm, frame.frameHeightMm),
                frame.toLonLat(0.0, frame.frameHeightMm),
            ),
        )

    private fun layersOf(
        t: PrintTemplate,
        stack: List<LayerConfig>,
        sources: (String) -> TileSource?,
        latitude: Double,
        scale: Double,
        dpi: Int,
    ): PrintLayerPlan =
        PrintLayerPlanner.plan(
            stack = stack,
            source = sources,
            scale = scale,
            dpi = dpi,
            latitude = latitude,
            respectRanges = t.respectLayerRanges,
            lighter = t.lighter,
        )

    private fun atlasOf(
        t: PrintTemplate,
        area: PrintArea,
        issues: MutableSet<PrintIssue>,
    ): AtlasPlan? =
        when (t.atlas) {
            AtlasMode.SINGLE -> {
                null
            }

            AtlasMode.GRID -> {
                val bounds = area.bounds
                if (bounds == null) {
                    issues += PrintIssue.NO_AREA
                    null
                } else {
                    AtlasPlanner.grid(bounds, t.scale, t.page, t.overlapPercent).also {
                        if (it.tooMany) issues += PrintIssue.TOO_MANY_PAGES
                    }
                }
            }

            AtlasMode.TRACK -> {
                if (area.path.size < 2) {
                    issues += PrintIssue.NO_TRACK
                    null
                } else {
                    AtlasPlanner.alongTrack(area.path, t.scale, t.page, t.overlapPercent).also {
                        if (it.pages.size >= AtlasPlanner.MAX_PAGES) issues += PrintIssue.TOO_MANY_PAGES
                    }
                }
            }
        }

    private fun centersOf(
        t: PrintTemplate,
        area: PrintArea,
        atlas: AtlasPlan?,
    ): List<AtlasPage> =
        if (t.atlas == AtlasMode.SINGLE) {
            listOf(AtlasPage(1, 0, 0, area.centerLat, area.centerLon))
        } else {
            atlas?.pages.orEmpty()
        }

    /** One entry per problem: pages repeat the same warning, only the worst oversampling of a source is kept. */
    private fun mergeWarnings(pages: List<PrintPagePlan>): List<PrintWarning> {
        val out = LinkedHashMap<Pair<String, String>, PrintWarning>()
        for (warning in pages.flatMap { it.layers.warnings }) {
            val key =
                when (warning) {
                    is PrintWarning.Oversampled -> "over" to warning.sourceId
                    is PrintWarning.OutsideRange -> "range" to warning.sourceId
                    is PrintWarning.Unavailable -> "gone" to warning.sourceId
                }
            val known = out[key]
            if (known !is PrintWarning.Oversampled || warning !is PrintWarning.Oversampled ||
                warning.factor > known.factor
            ) {
                out[key] = warning
            }
        }
        return out.values.toList()
    }

    private fun empty(
        t: PrintTemplate,
        issues: Set<PrintIssue>,
    ): PrintPlan = PrintPlan(t, emptyList(), null, emptyList(), issues.toList(), emptyMap(), 0L, 0L to 0L, 0.0)
}
