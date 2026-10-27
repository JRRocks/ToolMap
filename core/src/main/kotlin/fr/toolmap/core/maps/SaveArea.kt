package fr.toolmap.core.maps

import fr.toolmap.core.download.ZoneShape

/** The four handles of the rectangle drawn in "Select and save an area". */
enum class AreaCorner { NORTH_WEST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST }

/**
 * The rectangle of the zone to save, in WGS84 degrees. It is fixed on the ground: moving the map moves it on the
 * screen, dragging a handle changes its bounds. It never gets thinner than [MIN_SPAN] degrees.
 */
data class AreaSelection(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west < east && south < north) { "empty rectangle" }
    }

    fun toShape(): ZoneShape.Rectangle = ZoneShape.Rectangle(west, south, east, north)

    /** Latitude of [corner]. */
    fun latOf(corner: AreaCorner): Double =
        if (corner == AreaCorner.NORTH_WEST || corner == AreaCorner.NORTH_EAST) north else south

    /** Longitude of [corner]. */
    fun lonOf(corner: AreaCorner): Double =
        if (corner == AreaCorner.NORTH_WEST || corner == AreaCorner.SOUTH_WEST) west else east

    /**
     * The rectangle after the handle [corner] was dragged to [lat], [lon]. The opposite corner stays where it is,
     * the dragged one stops before crossing it, and nothing leaves the Web Mercator world.
     */
    fun drag(
        corner: AreaCorner,
        lat: Double,
        lon: Double,
    ): AreaSelection {
        val y = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = lon.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
        return when (corner) {
            AreaCorner.NORTH_WEST -> {
                copy(west = x.coerceAtMost(east - MIN_SPAN), north = y.coerceAtLeast(south + MIN_SPAN))
            }

            AreaCorner.NORTH_EAST -> {
                copy(east = x.coerceAtLeast(west + MIN_SPAN), north = y.coerceAtLeast(south + MIN_SPAN))
            }

            AreaCorner.SOUTH_WEST -> {
                copy(west = x.coerceAtMost(east - MIN_SPAN), south = y.coerceAtMost(north - MIN_SPAN))
            }

            AreaCorner.SOUTH_EAST -> {
                copy(east = x.coerceAtLeast(west + MIN_SPAN), south = y.coerceAtMost(north - MIN_SPAN))
            }
        }
    }

    companion object {
        /** About 10 m; below that the zone would hold a tile or two at any useful zoom. */
        const val MIN_SPAN = 1e-4
        const val MAX_LATITUDE = 85.05112878
        const val MAX_LONGITUDE = 180.0

        /** The rectangle that fills [fraction] of the visible area, around its centre (where the panel opens). */
        fun centered(
            visible: ZoneShape.Rectangle,
            fraction: Double = DEFAULT_FRACTION,
        ): AreaSelection {
            val f = fraction.coerceIn(MIN_FRACTION, 1.0)
            val dx = (visible.east - visible.west) * (1 - f) / 2
            val dy = (visible.north - visible.south) * (1 - f) / 2
            return AreaSelection(visible.west + dx, visible.south + dy, visible.east - dx, visible.north - dy)
        }

        private const val DEFAULT_FRACTION = 0.6
        private const val MIN_FRACTION = 0.05
    }
}

/** What a zoom level of a map becomes when the zone is saved. */
enum class ZoomCell {
    /** Will be saved. */
    SAVED,

    /** The map has this zoom, but it is above the chosen maximum. */
    SKIPPED,

    /** The map has no tile at this zoom. */
    ABSENT,
}

/** The zooms a map contributes to a save: `from`–`to` out of the `available` maximum (AlpineQuest's "1-14/17"). */
data class ZoomSpan(
    val from: Int,
    val to: Int,
    val available: Int,
)

/** The little per-zoom bar of each map row in "Select and save an area". */
object ZoneStrips {
    /** First zoom the bars can show. */
    const val SCALE_MIN = 0

    /** The zooms saved for a map served from [sourceMin] to [sourceMax] when the slider is at [chosenMax]. */
    fun span(
        sourceMin: Int,
        sourceMax: Int,
        chosenMax: Int,
    ): ZoomSpan? = if (chosenMax < sourceMin) null else ZoomSpan(sourceMin, minOf(chosenMax, sourceMax), sourceMax)

    /** One cell per zoom from [SCALE_MIN] to [scaleMax] for a map served from [sourceMin] to [sourceMax]. */
    fun cells(
        sourceMin: Int,
        sourceMax: Int,
        chosenMax: Int,
        scaleMax: Int,
    ): List<ZoomCell> =
        (SCALE_MIN..scaleMax).map { zoom ->
            when {
                zoom < sourceMin || zoom > sourceMax -> ZoomCell.ABSENT
                zoom <= chosenMax -> ZoomCell.SAVED
                else -> ZoomCell.SKIPPED
            }
        }

    /** Where the current [tileZoom] falls along a bar of [scaleMax] + 1 cells (0..1), or `null` off the bar. */
    fun marker(
        tileZoom: Double,
        scaleMax: Int,
    ): Float? {
        val position = (tileZoom - SCALE_MIN) / (scaleMax - SCALE_MIN + 1)
        return position.takeIf { it in 0.0..1.0 }?.toFloat()
    }

    /** Highest value of the "Zoom" slider: the deepest zoom any of the [maxZooms] serves (at least 1). */
    fun sliderMax(maxZooms: List<Int>): Int = (maxZooms.maxOrNull() ?: 1).coerceAtLeast(1)

    /** Where the slider starts: [extra] levels deeper than the current zoom, within `0..sliderMax`. */
    fun defaultMax(
        currentTileZoom: Int,
        extra: Int,
        sliderMax: Int,
    ): Int = (currentTileZoom + extra).coerceIn(0, sliderMax)
}
