package fr.toolmap.core.sketch

import fr.toolmap.core.tiles.TileMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * How a stroke looks, fixed when it is drawn (P28). Lengths are in map meters (EPSG:3857, the unit of [XY]), so a
 * stroke covers the same piece of map at every zoom and thins out when zooming out, like ink on a paper map:
 * [widthMeters] is the width seen on screen at that moment, [smoothingMeters] the smoothing tolerance chosen before
 * drawing, kept so that a reopened sketch is smoothed the same way when it is saved again.
 */
data class SketchStyle(
    val color: Int,
    val widthMeters: Double,
    val smoothingMeters: Double,
)

/** A stroke being edited: the raw points, which the eraser cuts and which are shown smoothed on the fly. */
data class RawStroke(
    val style: SketchStyle,
    val points: List<XY>,
) {
    fun smoothed(): SketchStroke = SketchStroke(style, SketchSmoother.fit(points, style.smoothingMeters))
}

/** A saved stroke: only its smoothed SVG path is kept. */
data class SketchStroke(
    val style: SketchStyle,
    val path: SketchPath,
) {
    /** Back to points for editing (a reopened sketch); smoothing it again may move it very slightly. */
    fun toRaw(): RawStroke = RawStroke(style, path.sample(style.smoothingMeters / 4))
}

/** A named drawing session (« Croquis du 3 nov. »): shown or hidden as a whole. */
data class Sketch(
    val name: String,
    val strokes: List<SketchStroke>,
) {
    /**
     * The camera zoom below which the whole sketch is hidden: there, even its widest stroke would be thinner than
     * one pixel. Null for an empty sketch.
     */
    fun minVisibleZoom(): Double? =
        strokes.maxOfOrNull { it.style.widthMeters }?.let { SketchScale.zoomForWidth(it, pixels = 1.0) }
}

/** The smoothing levels offered before drawing, as a tolerance in screen pixels (dp). */
enum class SketchSmoothing(
    val tolerancePx: Double,
) {
    LOW(1.0),
    MEDIUM(2.5),
    HIGH(5.0),
}

/** The eight fixed colors of the first lot (red, orange, yellow, green, blue, violet, black, white). */
object SketchPalette {
    val COLORS: List<Int> =
        listOf(
            0xFFE53935,
            0xFFFB8C00,
            0xFFFDD835,
            0xFF43A047,
            0xFF1E88E5,
            0xFF8E24AA,
            0xFF111111,
            0xFFFFFFFF,
        ).map { it.toInt() }
}

/** Screen pixels (dp) and map meters at a camera zoom of MapLibre (512 dp per world tile). */
object SketchScale {
    const val MIN_WIDTH_PX = 1
    const val MAX_WIDTH_PX = 40
    private const val WORLD_TILE_DP = 512.0

    /** Map meters (EPSG:3857) under one pixel; the same at every latitude. */
    fun metersPerPixel(cameraZoom: Double): Double =
        2 * PI * TileMath.EARTH_RADIUS_M / (WORLD_TILE_DP * 2.0.pow(cameraZoom))

    /** The camera zoom at which [widthMeters] covers [pixels] on screen. */
    fun zoomForWidth(
        widthMeters: Double,
        pixels: Double,
    ): Double = ln(metersPerPixel(0.0) * pixels / widthMeters) / ln(2.0)

    /** The style of a stroke drawn now: width and smoothing chosen in pixels, kept in map meters. */
    fun style(
        color: Int,
        widthPx: Int,
        smoothing: SketchSmoothing,
        cameraZoom: Double,
    ): SketchStyle {
        val mpp = metersPerPixel(cameraZoom)
        return SketchStyle(color, widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX) * mpp, smoothing.tolerancePx * mpp)
    }

    /** Meters on the ground for map meters at [latitude] (EPSG:3857 stretches by 1 / cos(latitude)). */
    fun groundMeters(
        mapMeters: Double,
        latitude: Double,
    ): Double = mapMeters * cos(Math.toRadians(latitude))
}
