package fr.toolmap.core.tiles

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The ground to download: always WGS84 degrees. */
sealed interface DownloadArea {
    /** A box; [bounds] uses x = longitude, y = latitude, with west <= east (no antimeridian crossing). */
    data class Rectangle(
        val bounds: Bounds,
    ) : DownloadArea

    /** A simple polygon (the closing edge is implicit). */
    data class Polygon(
        val ring: List<LonLat>,
    ) : DownloadArea

    /** Every place within [radiusMeters] (on the ground) of the polyline [path], e.g. a GPX track. */
    data class Corridor(
        val path: List<LonLat>,
        val radiusMeters: Double,
    ) : DownloadArea
}

/** A tile to fetch. */
data class TileId(
    val z: Int,
    val x: Int,
    val y: Int,
)

/** The zone asks for more tiles than the safety limit allows. */
class TooManyTilesException(
    val limit: Long,
) : IllegalArgumentException("more than $limit tiles: reduce the zone or the zoom levels")

/** Which tiles cover a [DownloadArea]; shared by the zone download and the PDF composer. */
object TilePlanner {
    /** Safety limit for one plan: beyond it the zone is refused rather than enumerated. */
    const val MAX_TILES = 3_000_000L

    /**
     * Tiles of [area] at each zoom of [zooms], as a lazy sequence ordered by zoom then row then column.
     * Polygons and corridors are exact enough to never miss a tile that touches the area (corridors err on the
     * side of a few extra tiles). Throws [TooManyTilesException] when the plan exceeds [limit].
     */
    fun tiles(
        area: DownloadArea,
        zooms: IntRange,
        limit: Long = MAX_TILES,
    ): Sequence<TileId> =
        sequence {
            var produced = 0L
            for (z in zooms) {
                for (tile in tilesAt(area, z, limit - produced)) {
                    if (++produced > limit) throw TooManyTilesException(limit)
                    yield(TileId(z, tile.x, tile.y))
                }
            }
        }

    /** Number of tiles at each zoom (exact for the box, enumerated for polygons and corridors). */
    fun count(
        area: DownloadArea,
        zooms: IntRange,
        limit: Long = MAX_TILES,
    ): Map<Int, Long> {
        val result = sortedMapOf<Int, Long>()
        var total = 0L
        for (z in zooms) {
            val n =
                if (area is DownloadArea.Rectangle) {
                    val r = rectangleRange(area.bounds, z)
                    (r.xs.last - r.xs.first + 1L) * (r.ys.last - r.ys.first + 1L)
                } else {
                    tilesAt(area, z, limit - total).count().toLong()
                }
            total += n
            if (total > limit) throw TooManyTilesException(limit)
            result[z] = n
        }
        return result
    }

    private class Range(
        val xs: IntRange,
        val ys: IntRange,
    )

    private fun rectangleRange(
        bounds: Bounds,
        z: Int,
    ): Range {
        require(bounds.minX <= bounds.maxX && bounds.minY <= bounds.maxY) { "empty rectangle" }
        val topLeft = TileMath.tileAt(bounds.minX, bounds.maxY, z)
        val bottomRight = TileMath.tileAt(bounds.maxX, bounds.minY, z)
        return Range(topLeft.x..bottomRight.x, topLeft.y..bottomRight.y)
    }

    private fun tilesAt(
        area: DownloadArea,
        z: Int,
        remaining: Long,
    ): Sequence<TileCoord> =
        when (area) {
            is DownloadArea.Rectangle -> {
                val r = rectangleRange(area.bounds, z)
                val total = (r.xs.last - r.xs.first + 1L) * (r.ys.last - r.ys.first + 1L)
                if (total > remaining) throw TooManyTilesException(MAX_TILES)
                r.ys.asSequence().flatMap { y -> r.xs.asSequence().map { x -> TileCoord(x, y) } }
            }

            is DownloadArea.Polygon -> {
                polygonTiles(area.ring, z, remaining)
            }

            is DownloadArea.Corridor -> {
                corridorTiles(area, z).sortedWith(compareBy({ it.y }, { it.x })).asSequence()
            }
        }

    // ---- polygon -------------------------------------------------------------------------------------------

    private fun polygonTiles(
        ring: List<LonLat>,
        z: Int,
        remaining: Long,
    ): Sequence<TileCoord> {
        require(ring.size >= 3) { "a polygon needs at least 3 points" }
        val px = ring.map { TileMath.mercatorX(it.lon) }
        val py = ring.map { TileMath.mercatorY(it.lat) }
        val topLeft = TileMath.tileAt(ring.minOf { it.lon }, ring.maxOf { it.lat }, z)
        val bottomRight = TileMath.tileAt(ring.maxOf { it.lon }, ring.minOf { it.lat }, z)
        val candidates = (bottomRight.x - topLeft.x + 1L) * (bottomRight.y - topLeft.y + 1L)
        if (candidates > remaining * CANDIDATE_FACTOR) throw TooManyTilesException(MAX_TILES)
        val size = 2 * TileMath.ORIGIN_SHIFT_M / TileMath.tilesPerAxis(z)
        return (topLeft.y..bottomRight.y).asSequence().flatMap { y ->
            (topLeft.x..bottomRight.x).asSequence().mapNotNull { x ->
                val x0 = -TileMath.ORIGIN_SHIFT_M + x * size
                val y1 = TileMath.ORIGIN_SHIFT_M - y * size
                if (polygonTouchesSquare(px, py, x0, y1 - size, x0 + size, y1)) TileCoord(x, y) else null
            }
        }
    }

    /** True when the polygon (px, py) and the box share at least one point. */
    private fun polygonTouchesSquare(
        px: List<Double>,
        py: List<Double>,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): Boolean {
        val n = px.size
        // A vertex inside the box.
        for (i in 0 until n) if (px[i] in x0..x1 && py[i] in y0..y1) return true
        // A box corner inside the polygon.
        if (containsPoint(px, py, x0, y0) || containsPoint(px, py, x1, y0) ||
            containsPoint(px, py, x1, y1) || containsPoint(px, py, x0, y1)
        ) {
            return true
        }
        // An edge crossing the box (clip test).
        for (i in 0 until n) {
            val j = (i + 1) % n
            if (segmentTouchesBox(px[i], py[i], px[j], py[j], x0, y0, x1, y1)) return true
        }
        return false
    }

    private fun containsPoint(
        px: List<Double>,
        py: List<Double>,
        x: Double,
        y: Double,
    ): Boolean {
        var inside = false
        var j = px.size - 1
        for (i in px.indices) {
            if ((py[i] > y) != (py[j] > y) && x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i]) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Liang-Barsky: does the segment (ax, ay)-(bx, by) intersect the closed box? */
    private fun segmentTouchesBox(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ): Boolean {
        var t0 = 0.0
        var t1 = 1.0
        val dx = bx - ax
        val dy = by - ay
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(ax - x0, x1 - ax, ay - y0, y1 - ay)
        for (i in 0..3) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0.0) {
                    if (t > t1) return false
                    t0 = max(t0, t)
                } else {
                    if (t < t0) return false
                    t1 = min(t1, t)
                }
            }
        }
        return true
    }

    // ---- corridor ------------------------------------------------------------------------------------------

    /**
     * The path is walked in steps of at most half a tile; around each step point every tile within the radius
     * (plus half a step, so nothing between two points is missed) is kept.
     */
    private fun corridorTiles(
        area: DownloadArea.Corridor,
        z: Int,
    ): Set<TileCoord> {
        require(area.path.isNotEmpty()) { "empty path" }
        require(area.radiusMeters >= 0) { "negative radius" }
        val n = TileMath.tilesPerAxis(z)
        val size = 2 * TileMath.ORIGIN_SHIFT_M / n
        val step = size / 2
        val found = HashSet<TileCoord>()

        fun addAround(
            mx: Double,
            my: Double,
            lat: Double,
        ) {
            // Mercator stretches distances by 1/cos(lat): convert the ground radius into Mercator meters.
            val reach =
                area.radiusMeters / cos(Math.toRadians(lat.coerceIn(-TileMath.MAX_LATITUDE, TileMath.MAX_LATITUDE))) +
                    step / 2
            val colMin = floor((mx - reach + TileMath.ORIGIN_SHIFT_M) / size).toLong().coerceIn(0, n - 1).toInt()
            val colMax = floor((mx + reach + TileMath.ORIGIN_SHIFT_M) / size).toLong().coerceIn(0, n - 1).toInt()
            val rowMin = floor((TileMath.ORIGIN_SHIFT_M - (my + reach)) / size).toLong().coerceIn(0, n - 1).toInt()
            val rowMax = floor((TileMath.ORIGIN_SHIFT_M - (my - reach)) / size).toLong().coerceIn(0, n - 1).toInt()
            if ((colMax - colMin + 1L) * (rowMax - rowMin + 1L) > MAX_TILES) throw TooManyTilesException(MAX_TILES)
            for (row in rowMin..rowMax) {
                val top = TileMath.ORIGIN_SHIFT_M - row * size
                for (col in colMin..colMax) {
                    if (found.size > MAX_TILES) throw TooManyTilesException(MAX_TILES)
                    val left = -TileMath.ORIGIN_SHIFT_M + col * size
                    val dx = max(max(left - mx, 0.0), mx - (left + size))
                    val dy = max(max((top - size) - my, 0.0), my - top)
                    if (hypot(dx, dy) <= reach) found.add(TileCoord(col, row))
                }
            }
        }

        val first = area.path.first()
        addAround(TileMath.mercatorX(first.lon), TileMath.mercatorY(first.lat), first.lat)
        for (i in 1 until area.path.size) {
            val a = area.path[i - 1]
            val b = area.path[i]
            val ax = TileMath.mercatorX(a.lon)
            val ay = TileMath.mercatorY(a.lat)
            val bx = TileMath.mercatorX(b.lon)
            val by = TileMath.mercatorY(b.lat)
            val length = hypot(bx - ax, by - ay)
            val steps = max(1, floor(length / step).toInt() + 1)
            for (k in 1..steps) {
                val t = k.toDouble() / steps
                val lat = a.lat + (b.lat - a.lat) * t
                addAround(ax + (bx - ax) * t, ay + (by - ay) * t, lat)
            }
        }
        return found
    }

    /**
     * How many tiles of [area] at zoom [z] are already stored, from the store's answers about a box of tiles:
     * [countIn] counts the stored tiles of the box, [storedIn] gives a membership test over them. A rectangle is its
     * own box. A polygon or a corridor checks its tiles against its bounding box, unless the box holds more than
     * [sampleLimit] stored tiles: the box count is then scaled by the share of the box the area covers.
     */
    fun countStored(
        area: DownloadArea,
        z: Int,
        countIn: (xs: IntRange, ys: IntRange) -> Long,
        storedIn: (xs: IntRange, ys: IntRange) -> (x: Int, y: Int) -> Boolean,
        sampleLimit: Long = STORED_SAMPLE_LIMIT,
    ): Long {
        if (area is DownloadArea.Rectangle) {
            val r = rectangleRange(area.bounds, z)
            return countIn(r.xs, r.ys)
        }
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var tiles = 0L
        for (tile in tilesAt(area, z, MAX_TILES)) {
            minX = min(minX, tile.x)
            maxX = max(maxX, tile.x)
            minY = min(minY, tile.y)
            maxY = max(maxY, tile.y)
            tiles++
        }
        if (tiles == 0L) return 0L
        val xs = minX..maxX
        val ys = minY..maxY
        val inBox = countIn(xs, ys)
        if (inBox == 0L) return 0L
        if (inBox > sampleLimit) {
            val box = (maxX - minX + 1L) * (maxY - minY + 1L)
            return inBox * tiles / box
        }
        val isStored = storedIn(xs, ys)
        return tilesAt(area, z, MAX_TILES).count { isStored(it.x, it.y) }.toLong()
    }

    /** Beyond this many stored tiles in the box of a polygon, [countStored] estimates instead of listing them. */
    const val STORED_SAMPLE_LIMIT = 500_000L

    /** Rough download size: [count] tiles of [averageTileBytes] each (sampled from the source when possible). */
    fun estimateBytes(
        count: Long,
        averageTileBytes: Long = DEFAULT_TILE_BYTES,
    ): Long = count * averageTileBytes

    const val DEFAULT_TILE_BYTES = 20_000L

    /** Polygon candidates are cheap to reject, so the box may be this many times larger than the limit. */
    private const val CANDIDATE_FACTOR = 8L
}
