package fr.toolmap.core.print

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.tiles.TileMath
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil

@Serializable
enum class AtlasMode { SINGLE, GRID, TRACK }

/** One page of an atlas: its number and where its frame is centred. */
data class AtlasPage(
    val number: Int,
    val row: Int,
    val column: Int,
    val centerLat: Double,
    val centerLon: Double,
)

/** The pages of an atlas and how they tile the area. */
data class AtlasPlan(
    val pages: List<AtlasPage>,
    val columns: Int,
    val rows: Int,
    val overlapPercent: Double,
    /** True when the area needs more than [AtlasPlanner.MAX_PAGES] pages: none is listed, the scale is wrong. */
    val tooMany: Boolean = false,
)

/** A page's footprint on the ground, as the four corners (clockwise from the top left). */
data class PageFootprint(
    val number: Int,
    val corners: List<LonLat>,
)

/** The overview of an atlas: the footprints, fitted into a sheet at a scale that suits it. */
class AtlasOverview(
    val footprints: List<PageFootprint>,
    val bounds: Bounds,
    private val widthMm: Double,
    private val heightMm: Double,
) {
    /** Millimeters of the overview sheet per Mercator meter, the same on both axes. */
    private val mmPerMeter: Double
    private val originX: Double
    private val originY: Double

    init {
        val minX = TileMath.mercatorX(bounds.minX)
        val maxX = TileMath.mercatorX(bounds.maxX)
        val minY = TileMath.mercatorY(bounds.minY)
        val maxY = TileMath.mercatorY(bounds.maxY)
        val w = maxOf(maxX - minX, 1.0)
        val h = maxOf(maxY - minY, 1.0)
        mmPerMeter = minOf(widthMm / w, heightMm / h)
        originX = (minX + maxX) / 2 - widthMm / mmPerMeter / 2
        originY = (minY + maxY) / 2 + heightMm / mmPerMeter / 2
    }

    fun toSheet(
        lon: Double,
        lat: Double,
    ): FramePoint =
        FramePoint(
            (TileMath.mercatorX(lon) - originX) * mmPerMeter,
            (originY - TileMath.mercatorY(lat)) * mmPerMeter,
        )
}

/** Cuts an area, or the neighbourhood of a track, into pages (spec §5). */
object AtlasPlanner {
    /** A safety limit: more pages than this is almost certainly a wrong scale. */
    const val MAX_PAGES = 400

    /** Pages tiling [area] (degrees), each overlapping its neighbours by [overlapPercent] of the frame. */
    fun grid(
        area: Bounds,
        scale: Double,
        setup: PageSetup,
        overlapPercent: Double,
    ): AtlasPlan {
        val overlap = overlapPercent.coerceIn(0.0, MAX_OVERLAP) / 100.0
        // Each page is exact at its own latitude, so its frame spans a little more Mercator meters the further it is
        // from the equator: step with the smallest span the area gives, and no page leaves a gap.
        val nearestToEquator = if (area.minY <= 0.0 && area.maxY >= 0.0) 0.0 else minOf(abs(area.minY), abs(area.maxY))
        val k0 = PrintMath.mercatorFactor(nearestToEquator)
        val frameW = PrintMath.groundMeters(setup.frameWidthMm, scale) * k0
        val frameH = PrintMath.groundMeters(setup.frameHeightMm, scale) * k0
        val minX = TileMath.mercatorX(area.minX)
        val maxX = TileMath.mercatorX(area.maxX)
        val minY = TileMath.mercatorY(area.minY)
        val maxY = TileMath.mercatorY(area.maxY)
        val columns = countFor(maxX - minX, frameW, overlap)
        val rows = countFor(maxY - minY, frameH, overlap)
        if (columns.toLong() * rows > MAX_PAGES) return AtlasPlan(emptyList(), columns, rows, overlap * 100.0, true)
        if (columns.toLong() * rows > MAX_PAGES) return AtlasPlan(emptyList(), columns, rows, overlap * 100.0, true)
        val pages = ArrayList<AtlasPage>(columns * rows)
        val stepX = if (columns > 1) (maxX - minX - frameW) / (columns - 1) else 0.0
        val stepY = if (rows > 1) (maxY - minY - frameH) / (rows - 1) else 0.0
        var number = 1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cx = if (columns > 1) minX + frameW / 2 + column * stepX else (minX + maxX) / 2
                val cy = if (rows > 1) maxY - frameH / 2 - row * stepY else (minY + maxY) / 2
                pages += AtlasPage(number++, row, column, latOf(cy), lonOf(cx))
            }
        }
        return AtlasPlan(pages, columns, rows, overlap * 100.0)
    }

    /**
     * Pages along [path]: the first is centred on the start; the next on the last point still inside the useful part
     * of the page (its frame minus the overlap on every side), and so on to the end. Pages keep the order of the track.
     */
    fun alongTrack(
        path: List<LonLat>,
        scale: Double,
        setup: PageSetup,
        overlapPercent: Double,
    ): AtlasPlan {
        if (path.isEmpty()) return AtlasPlan(emptyList(), 0, 0, overlapPercent)
        val overlap = overlapPercent.coerceIn(0.0, MAX_OVERLAP) / 100.0
        val pages = ArrayList<AtlasPage>()
        var center = path.first()
        pages += AtlasPage(1, 0, 0, center.lat, center.lon)
        var i = 1
        while (i < path.size && pages.size < MAX_PAGES) {
            val frame = FrameProjection(center.lat, center.lon, scale, setup.frameWidthMm, setup.frameHeightMm)
            val halfW = setup.frameWidthMm / 2 * (1 - overlap)
            val halfH = setup.frameHeightMm / 2 * (1 - overlap)
            var lastInside = -1
            while (i < path.size) {
                val p = frame.toFrame(path[i].lon, path[i].lat)
                val dx = abs(p.xMm - setup.frameWidthMm / 2)
                val dy = abs(p.yMm - setup.frameHeightMm / 2)
                if (dx > halfW || dy > halfH) break
                lastInside = i
                i++
            }
            if (i >= path.size) break
            // The next page starts where this one stopped being useful; at least move on past the center.
            center = path[if (lastInside >= 0) lastInside else i]
            if (lastInside < 0) i++
            pages += AtlasPage(pages.size + 1, 0, pages.size, center.lat, center.lon)
        }
        return AtlasPlan(pages, pages.size, 1, overlap * 100.0)
    }

    /** Footprints and overview mapping of [plan], fitted into an [widthMm] x [heightMm] sheet. */
    fun overview(
        plan: AtlasPlan,
        scale: Double,
        setup: PageSetup,
        widthMm: Double,
        heightMm: Double,
    ): AtlasOverview {
        val footprints =
            plan.pages.map { page ->
                val frame =
                    FrameProjection(page.centerLat, page.centerLon, scale, setup.frameWidthMm, setup.frameHeightMm)
                PageFootprint(
                    page.number,
                    listOf(
                        frame.toLonLat(0.0, 0.0),
                        frame.toLonLat(setup.frameWidthMm, 0.0),
                        frame.toLonLat(setup.frameWidthMm, setup.frameHeightMm),
                        frame.toLonLat(0.0, setup.frameHeightMm),
                    ),
                )
            }
        val all = footprints.flatMap { it.corners }
        val bounds =
            if (all.isEmpty()) {
                Bounds(0.0, 0.0, 0.0, 0.0)
            } else {
                Bounds(all.minOf { it.lon }, all.minOf { it.lat }, all.maxOf { it.lon }, all.maxOf { it.lat })
            }
        return AtlasOverview(footprints, bounds, widthMm, heightMm)
    }

    /** Pages needed along one axis: [frame] covers [extent] with a step of `frame * (1 - overlap)`. */
    private fun countFor(
        extent: Double,
        frame: Double,
        overlap: Double,
    ): Int {
        if (extent <= frame) return 1
        val step = frame * (1 - overlap)
        return (ceil((extent - frame) / step - 1e-9) + 1).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun lonOf(mx: Double) = Math.toDegrees(mx / TileMath.EARTH_RADIUS_M)

    private fun latOf(my: Double) = Math.toDegrees(2 * Math.atan(Math.exp(my / TileMath.EARTH_RADIUS_M)) - Math.PI / 2)

    private const val MAX_OVERLAP = 50.0
}
