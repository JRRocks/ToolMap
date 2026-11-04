package fr.toolmap.core.sketch

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The fill of a zone (P28.4, variant E « Motif × couleur » of docs/previews/p28-formes.html, chosen on 2026-11-04): a
 * pattern drawn in the colour of the zone. [code] is what is stored. The sizes are in screen dp: the pattern keeps its
 * size on the screen at every zoom, so that hatching stays readable.
 */
enum class SketchFill(
    val code: String,
    /** Opacity of the pattern over the map. */
    val opacity: Double,
) {
    NONE("none", 0.0),
    TINT("tint", 0.25),
    HATCH("hatch", 1.0),
    STRIPES("stripes", 0.55),
    DOTS("dots", 1.0),
    GRID("grid", 0.9),
    CROSS("cross", 0.8),
    WAVES("waves", 1.0),
    ;

    /** A pattern drawn with lines or dots; the tint is a plain colour, and NONE draws nothing. */
    val patterned: Boolean get() = this != NONE && this != TINT

    companion object {
        fun of(code: String?): SketchFill = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/**
 * The combinations of a pattern and a colour that have a name (« Hachures rouges : Interdit »), from the catalogue of
 * docs/previews/p28-formes.html; the other combinations stay without a name.
 */
enum class SketchFillPreset(
    val fill: SketchFill,
    val color: Int,
) {
    FORBIDDEN(SketchFill.HATCH, SketchPalette.COLORS[0]),
    DANGER(SketchFill.STRIPES, SketchPalette.COLORS[2]),
    PRESERVE(SketchFill.DOTS, SketchPalette.COLORS[3]),
    TO_SEARCH(SketchFill.GRID, SketchPalette.COLORS[4]),
    SEARCHED(SketchFill.CROSS, SketchPalette.COLORS[6]),
    WET(SketchFill.WAVES, SketchPalette.COLORS[4]),
    ;

    companion object {
        fun of(
            fill: SketchFill,
            color: Int,
        ): SketchFillPreset? = entries.firstOrNull { it.fill == fill && it.color == color }
    }
}

/** What a pattern draws over a box: lines of [lineWidth], dots of [dotRadius], and a light ground. */
class FillMarks(
    val lines: List<List<XY>>,
    val lineWidth: Double,
    val dots: List<XY>,
    val dotRadius: Double,
    /** Opacity of a plain ground under the marks (the dots have one), 0 for none. */
    val groundAlpha: Double,
)

/**
 * The geometry of the patterns, shared by the map (one tile, repeated) and the print (marks over the whole zone,
 * clipped to it). Every pattern repeats over [tile] dp, its marks anchored on the origin, so that a tile drawn from
 * [marks] over `[0, tile]` repeats without a seam.
 */
object SketchFills {
    private const val HATCH_GAP = 9.0
    private const val HATCH_WIDTH = 2.4
    private const val STRIPE_GAP = 18.0
    private const val STRIPE_WIDTH = 9.0
    private const val DOT_GAP = 11.0
    private const val DOT_RADIUS = 1.8
    private const val DOT_GROUND = 0.14
    private const val GRID_GAP = 14.0
    private const val GRID_WIDTH = 1.5
    private const val CROSS_GAP = 10.0
    private const val CROSS_WIDTH = 1.6
    private const val WAVE_LENGTH = 20.0
    private const val WAVE_ROW = 9.0
    private const val WAVE_HEIGHT = 2.0
    private const val WAVE_WIDTH = 1.7
    private const val WAVE_STEPS = 8
    private val SQRT2 = sqrt(2.0)

    /** The size of the tile that repeats, in dp (width, height); `null` for a fill without a pattern. */
    fun tile(fill: SketchFill): XY? =
        when (fill) {
            SketchFill.NONE, SketchFill.TINT -> null
            SketchFill.HATCH -> XY(HATCH_GAP * SQRT2, HATCH_GAP * SQRT2)
            SketchFill.STRIPES -> XY(STRIPE_GAP * SQRT2, STRIPE_GAP * SQRT2)
            SketchFill.DOTS -> XY(DOT_GAP, DOT_GAP)
            SketchFill.GRID -> XY(GRID_GAP, GRID_GAP)
            SketchFill.CROSS -> XY(CROSS_GAP * SQRT2, CROSS_GAP * SQRT2)
            SketchFill.WAVES -> XY(WAVE_LENGTH, WAVE_ROW)
        }

    /**
     * The marks of [fill] covering the box from [min] to [max], one dp being [unit] long in the coordinates of the box
     * (pixels for a tile, millimetres on paper). Lines may run past the box: the caller clips.
     */
    fun marks(
        fill: SketchFill,
        min: XY,
        max: XY,
        unit: Double,
    ): FillMarks =
        when (fill) {
            SketchFill.NONE, SketchFill.TINT -> {
                FillMarks(emptyList(), 0.0, emptyList(), 0.0, 0.0)
            }

            SketchFill.HATCH -> {
                lineMarks(rising(min, max, HATCH_GAP * SQRT2 * unit), HATCH_WIDTH * unit)
            }

            SketchFill.STRIPES -> {
                lineMarks(rising(min, max, STRIPE_GAP * SQRT2 * unit), STRIPE_WIDTH * unit)
            }

            SketchFill.CROSS -> {
                val gap = CROSS_GAP * SQRT2 * unit
                lineMarks(rising(min, max, gap) + falling(min, max, gap), CROSS_WIDTH * unit)
            }

            SketchFill.GRID -> {
                lineMarks(grid(min, max, GRID_GAP * unit), GRID_WIDTH * unit)
            }

            SketchFill.DOTS -> {
                FillMarks(emptyList(), 0.0, dots(min, max, DOT_GAP * unit), DOT_RADIUS * unit, DOT_GROUND)
            }

            SketchFill.WAVES -> {
                lineMarks(waves(min, max, unit), WAVE_WIDTH * unit)
            }
        }

    private fun lineMarks(
        lines: List<List<XY>>,
        width: Double,
    ) = FillMarks(lines, width, emptyList(), 0.0, 0.0)

    private fun steps(
        from: Double,
        to: Double,
        gap: Double,
    ): IntRange = floor(from / gap).toInt() - 1..ceil(to / gap).toInt() + 1

    /** Lines going up to the right (x + y = k·gap), as the hatching of a map. */
    private fun rising(
        min: XY,
        max: XY,
        gap: Double,
    ): List<List<XY>> =
        steps(min.x + min.y, max.x + max.y, gap).map { k ->
            val c = k * gap
            listOf(XY(c - min.y, min.y), XY(c - max.y, max.y))
        }

    /** Lines going down to the right (x − y = k·gap). */
    private fun falling(
        min: XY,
        max: XY,
        gap: Double,
    ): List<List<XY>> =
        steps(min.x - max.y, max.x - min.y, gap).map { k ->
            val c = k * gap
            listOf(XY(c + min.y, min.y), XY(c + max.y, max.y))
        }

    private fun grid(
        min: XY,
        max: XY,
        gap: Double,
    ): List<List<XY>> =
        steps(min.y, max.y, gap).map { k -> listOf(XY(min.x, k * gap), XY(max.x, k * gap)) } +
            steps(min.x, max.x, gap).map { k -> listOf(XY(k * gap, min.y), XY(k * gap, max.y)) }

    /** Two dots per square, on its diagonal, as in the preview. */
    private fun dots(
        min: XY,
        max: XY,
        gap: Double,
    ): List<XY> {
        val out = ArrayList<XY>()
        for (j in steps(min.y, max.y, gap)) {
            for (i in steps(min.x, max.x, gap)) {
                out += XY((i + DOT_FIRST) * gap, (j + DOT_FIRST) * gap)
                out += XY((i + DOT_SECOND) * gap, (j + DOT_SECOND) * gap)
            }
        }
        return out
    }

    private const val DOT_FIRST = 3.0 / 11
    private const val DOT_SECOND = 8.5 / 11

    /** One wave per row, a sine of [WAVE_LENGTH] dp across the box. */
    private fun waves(
        min: XY,
        max: XY,
        unit: Double,
    ): List<List<XY>> {
        val length = WAVE_LENGTH * unit
        val row = WAVE_ROW * unit
        val xs = steps(min.x, max.x, length)
        val first = xs.first * length
        val count = (xs.last - xs.first) * WAVE_STEPS
        return steps(min.y, max.y, row).map { j ->
            val base = j * row + row / 2
            List(count + 1) { s ->
                val x = first + s * length / WAVE_STEPS
                XY(x, base - WAVE_HEIGHT * unit * sin(2 * PI * (x - first) / length))
            }
        }
    }
}
