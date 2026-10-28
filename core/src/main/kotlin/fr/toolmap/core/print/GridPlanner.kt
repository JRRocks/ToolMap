package fr.toolmap.core.print

import fr.toolmap.core.geo.GridZone
import fr.toolmap.core.geo.GridZones
import fr.toolmap.core.geo.Lambert93
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.Mgrs
import fr.toolmap.core.geo.PlanarCoordinate
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.Utm
import fr.toolmap.core.geo.UtmCoordinate
import fr.toolmap.core.geo.ZonePiece
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The grids a print can carry; [WGS84] is the graticule in degrees, minutes, seconds, [WGS84_DECIMAL] in degrees. */
@Serializable
enum class GridKind { NONE, WGS84, UTM, LAMBERT93, MGRS, WGS84_DECIMAL }

enum class FrameSide { TOP, BOTTOM, LEFT, RIGHT }

/** A grid line, already clipped to the frame; [points] are in frame millimeters. [major]: a 100 km square edge. */
class GridLine(
    val points: List<FramePoint>,
    val major: Boolean = false,
)

/**
 * A projected coordinate cut the way printed maps write it: [prefix] in small figures, the two "principal digits"
 * [major] in big ones (the kilometers inside the 100 km square), [rest] in small ones (meters under the kilometer).
 * 645 000 m → `6` `45`; 5 050 500 m → `50` `50` `500`.
 */
data class LabelParts(
    val prefix: String,
    val major: String,
    val rest: String,
)

/** A label written in the margin where a grid line leaves the frame. [positionMm] runs along the [side]. */
data class GridLabel(
    val side: FrameSide,
    val positionMm: Double,
    val text: String,
    /** The line's value divided by the spacing: tells neighbours apart and alternates the graduation bars. */
    val index: Long = 0,
    /** Principal digits of a projected grid; `null` for the graticules. */
    val parts: LabelParts? = null,
    /** The line's value in the grid's unit. */
    val value: Double = 0.0,
    /**
     * A line of a neighbouring zone's grid carried past the zone edge, written in the outer row of the margin on the
     * part of the sheet that is not its zone (P24.4, the NATO practice).
     */
    val neighbour: Boolean = false,
)

/** How a name over the map lines up with its point: centred on it, ending on it, or starting from it. */
enum class TagAlign { CENTER, END, START }

/**
 * A 100 km square letter pair written inside the map, at [xMm], [yMm] of the frame; with its grid zone (`31T GL`) on a
 * sheet across two zones, beside the zone edge.
 */
data class SquareTag(
    val letters: String,
    val xMm: Double,
    val yMm: Double,
    val align: TagAlign = TagAlign.CENTER,
)

/**
 * The edge of a grid zone across the sheet (P24.4), drawn thick: a meridian between two zones, or a parallel between
 * two latitude bands.
 */
class ZoneEdge(
    val line: GridLine,
    val meridian: Boolean,
    /** Its longitude or latitude in degrees, east and north positive. */
    val degrees: Double,
    /** The zones (`31`, `32`) or the bands (`T`, `U`) on either side, west or south first; `null` past 84 N. */
    val sides: Pair<String, String>?,
)

/** Where a zone lies on a sheet across two of them. */
enum class CompassSide { WEST, EAST, SOUTH, NORTH }

enum class GridIssue { NONE, OUTSIDE_VALIDITY }

class GridPlan(
    val kind: GridKind,
    val lines: List<GridLine>,
    val labels: List<GridLabel>,
    /** The spacing of the lines in the unit of the grid, for the legend (`1 km`, `30″`). */
    val stepText: String,
    /** The coordinate system, for the legend (`UTM 31N · WGS84`). */
    val systemText: String,
    val issue: GridIssue = GridIssue.NONE,
    /** The spacing in the grid's unit (meters or degrees). */
    val step: Double = 0.0,
    /** Grid zone of a UTM or MGRS grid (`31T`). */
    val zoneText: String? = null,
    /**
     * The 100 km squares the page covers, west to east then north to south (MGRS); with their grid zone (`31T GL`) when
     * the page covers more than one.
     */
    val squares: List<String> = emptyList(),
    /** Where to write the letters of each square in the map, when the page covers more than one. */
    val squareTags: List<SquareTag> = emptyList(),
    /** UTM and MGRS: the grid zones the page covers (`31T`, `32T`; `31N`, `32N` in UTM); more than one when it straddles. */
    val zones: List<String> = emptyList(),
    /** The edges of the grid zones across the page, drawn thick (P24.4). */
    val zoneEdges: List<ZoneEdge> = emptyList(),
    /** MGRS on a page across two grid zones: the side each one lies on, for the legend (« 31T GL (ouest) »). */
    val zoneSides: Map<String, CompassSide> = emptyMap(),
) {
    companion object {
        fun empty(kind: GridKind = GridKind.NONE) = GridPlan(kind, emptyList(), emptyList(), "", "")
    }
}

/** A bar of the graduated band of the frame: from [startMm] to [endMm] along [side], black or white. */
data class GraduationBar(
    val side: FrameSide,
    val startMm: Double,
    val endMm: Double,
    val black: Boolean,
)

/** The graduated band: the grid of the sheet at its spacing, as alternating bars (Q14, ADR-087). */
class Graduation(
    val kind: GridKind,
    val step: Double,
    val stepText: String,
    val bars: List<GraduationBar>,
) {
    companion object {
        val EMPTY = Graduation(GridKind.NONE, 0.0, "", emptyList())
    }
}

/**
 * The coordinate grid drawn over the map and labelled in the margin. Grid lines are straight in their own coordinate
 * system and a little curved on the Web Mercator paper: each one is sampled and projected, then clipped to the
 * frame (spec §4).
 */
object GridPlanner {
    /** Sample spacing along a grid line, in millimeters of paper. */
    private const val SAMPLE_MM = 4.0

    private const val MAX_LINES_PER_AXIS = 120

    private const val EDGE_TOLERANCE_MM = 0.05

    private const val SQUARE = 100_000.0

    /**
     * Plans the grid of [kind] over [frame]. [step] forces a spacing in the grid's own unit (meters, or degrees for
     * the graticules); `null` takes the one of the scale ([GridSpacing]).
     */
    fun plan(
        frame: FrameProjection,
        kind: GridKind,
        step: Double? = null,
        locale: Locale = Locale.getDefault(),
        style: MapStyle = MapStyle.TOP25,
    ): GridPlan {
        if (kind == GridKind.NONE) return GridPlan.empty()
        if (kind == GridKind.UTM || kind == GridKind.MGRS) {
            if (frame.centerLat > UTM_MAX_LAT || frame.centerLat < UTM_MIN_LAT) {
                return GridPlan(kind, emptyList(), emptyList(), "", "", GridIssue.OUTSIDE_VALIDITY)
            }
            return zoned(frame, kind, step?.takeIf { it > 0.0 } ?: GridSpacing.auto(kind, frame.scale, style), locale)
        }
        val system =
            systemFor(frame, kind)
                ?: return GridPlan(kind, emptyList(), emptyList(), "", "", GridIssue.OUTSIDE_VALIDITY)
        val spacing = step?.takeIf { it > 0.0 } ?: GridSpacing.auto(kind, frame.scale, style)
        val whole = Rect(0.0, 0.0, frame.frameWidthMm, frame.frameHeightMm)
        val lines = ArrayList<GridLine>()
        val labels = ArrayList<GridLabel>()
        val drawn =
            drawLines(frame, system, spacing, lines) { line ->
                addClipped(line.raw, whole, frame, lines, line.major) { edge, pos ->
                    labels += line.label(edge, pos, locale, neighbour = false) ?: return@addClipped
                }
            }
        if (!drawn) return GridPlan.empty(kind)
        return GridPlan(
            kind,
            lines,
            labels,
            system.stepText(spacing, locale),
            system.systemText,
            GridIssue.NONE,
            spacing,
        )
    }

    /**
     * UTM and MGRS (P24.4, as the P24.0 preview): each grid zone on the sheet draws its own grid, cut at its edges, and
     * the edges are drawn thick. Each zone writes its figures in its own part of the margin; where the sheet meets the
     * neighbouring zone, the lines of that zone carried on are written in an outer row (the NATO practice), so a
     * reference can be read in either zone. The squares are named with their zone on both sides.
     */
    private fun zoned(
        frame: FrameProjection,
        kind: GridKind,
        spacing: Double,
        locale: Locale,
    ): GridPlan {
        val mgrs = kind == GridKind.MGRS
        // One hemisphere for the whole sheet: its northings run on across the equator.
        val north = frame.centerLat >= 0
        val band = Utm.bandLetter(frame.centerLat) ?: 'N'
        val area = frame.lonLatBounds
        val parts = GridZones.pieces(area).mapNotNull { ZonePart.of(frame, it) }
        if (parts.isEmpty()) return GridPlan.empty(kind)
        val lines = ArrayList<GridLine>()
        val labels = ArrayList<GridLabel>()
        val whole = Rect(0.0, 0.0, frame.frameWidthMm, frame.frameHeightMm)
        for (part in parts) {
            val system = ProjectedSystem.utm(part.zone, north, mgrs, band, part.shift)
            val drawn =
                drawLines(frame, system, spacing, lines) { line ->
                    addClipped(line.raw, part.rect, frame, lines, line.major) { edge, pos ->
                        labels += line.label(edge, pos, locale, neighbour = false) ?: return@addClipped
                    }
                    if (parts.size < 2) return@drawLines
                    // The line carried on: its ends on the margin of an adjacent zone's part.
                    addClipped(line.raw, whole, frame, ArrayList(), false) { edge, pos ->
                        val p = edgePoint(edge, pos, frame)
                        if (part.rect.contains(p)) return@addClipped
                        if (parts.none { it !== part && it.rect.contains(p) && it.rect.touches(part.rect) }) {
                            return@addClipped
                        }
                        labels += line.label(edge, pos, locale, neighbour = true) ?: return@addClipped
                    }
                }
            if (!drawn) return GridPlan.empty(kind)
        }
        val centerZone = Utm.zoneOf(frame.centerLat, frame.centerLon)
        val gridZones = GridZones.meeting(area)
        val zones =
            if (mgrs) {
                gridZones.map { it.name }
            } else {
                gridZones.map { "${it.zone}${if (north) 'N' else 'S'}" }.distinct()
            }
        val edges = GridZones.edges(area).mapNotNull { zoneEdge(frame, it) }
        val sides = if (mgrs && gridZones.size == 2) sidesOf(frame, gridZones) else emptyMap()
        val (squares, tags) =
            if (mgrs) {
                squaresOf(frame, gridZones.size > 1, edges)
            } else {
                emptyList<String>() to
                    emptyList()
            }
        val centre = ProjectedSystem.utm(centerZone, north, mgrs, band, 0.0)
        return GridPlan(
            kind,
            lines,
            labels,
            centre.stepText(spacing, locale),
            centre.systemText,
            GridIssue.NONE,
            spacing,
            "$centerZone$band",
            squares,
            tags,
            zones,
            edges,
            sides,
        )
    }

    /** A grid line of one value, sampled across the whole sheet, in frame millimeters. */
    private class SampledLine(
        val raw: List<FramePoint>,
        val major: Boolean,
        private val system: GridSystem,
        private val vertical: Boolean,
        private val index: Long,
        private val value: Double,
        private val spacing: Double,
    ) {
        /** The label where the line meets [edge]: a line of constant x is written on the top and bottom only. */
        fun label(
            edge: FrameSide,
            pos: Double,
            locale: Locale,
            neighbour: Boolean,
        ): GridLabel? {
            val across = edge == FrameSide.TOP || edge == FrameSide.BOTTOM
            if (across != vertical) return null
            val text = if (vertical) system.labelX(value, spacing, locale) else system.labelY(value, spacing, locale)
            return GridLabel(edge, pos, text, index, system.parts(value), value, neighbour)
        }
    }

    /**
     * Samples every line of [system] at [spacing] over the sheet and hands each to [draw]; `false` when there would be
     * too many of them (nothing drawn).
     */
    private fun drawLines(
        frame: FrameProjection,
        system: GridSystem,
        spacing: Double,
        lines: MutableList<GridLine>,
        draw: (SampledLine) -> Unit,
    ): Boolean {
        val corners = perimeter(frame).map { system.toGrid(it.lat, it.lon) }
        val minX = corners.minOf { it.first }
        val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }
        val maxY = corners.maxOf { it.second }
        if (max(maxX - minX, maxY - minY) <= 0.0) return false
        val valuesX = multiples(minX, maxX, spacing) ?: return false
        val valuesY = multiples(minY, maxY, spacing) ?: return false
        val sampleCount = max(2, ceil(Math.hypot(frame.frameWidthMm, frame.frameHeightMm) / SAMPLE_MM).toInt()) + 1
        val projected = system is ProjectedSystem
        for ((i, x) in valuesX) {
            val raw =
                List(sampleCount) { s ->
                    frame.toFrameOf(
                        system.fromGrid(
                            x,
                            minY + (maxY - minY) * s / (sampleCount - 1),
                        ),
                    )
                }
            draw(SampledLine(raw, projected && isSquareEdge(x), system, true, i, x, spacing))
        }
        for ((i, y) in valuesY) {
            val raw =
                List(sampleCount) { s ->
                    frame.toFrameOf(
                        system.fromGrid(minX + (maxX - minX) * s / (sampleCount - 1), y),
                    )
                }
            draw(SampledLine(raw, projected && isSquareEdge(y), system, false, i, y, spacing))
        }
        return true
    }

    /** A rectangle of the frame, in millimeters. */
    private class Rect(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        fun contains(p: FramePoint): Boolean =
            p.xMm >= left - EDGE_TOLERANCE_MM && p.xMm <= right + EDGE_TOLERANCE_MM &&
                p.yMm >= top - EDGE_TOLERANCE_MM && p.yMm <= bottom + EDGE_TOLERANCE_MM

        /** Sharing a piece of side: a zone edge between them. */
        fun touches(other: Rect): Boolean {
            val overlapX = min(right, other.right) - max(left, other.left)
            val overlapY = min(bottom, other.bottom) - max(top, other.top)
            val sideBySide = (
                abs(right - other.left) <= EDGE_TOLERANCE_MM ||
                    abs(other.right - left) <= EDGE_TOLERANCE_MM
            )
            val stacked = (abs(bottom - other.top) <= EDGE_TOLERANCE_MM || abs(other.bottom - top) <= EDGE_TOLERANCE_MM)
            return (sideBySide && overlapY > EDGE_TOLERANCE_MM) || (stacked && overlapX > EDGE_TOLERANCE_MM)
        }
    }

    /** The part of the sheet with one zone's grid: the frame is a Web Mercator rectangle, the zone's sides are its. */
    private class ZonePart(
        val zone: Int,
        val shift: Double,
        val rect: Rect,
    ) {
        companion object {
            fun of(
                frame: FrameProjection,
                piece: ZonePiece,
            ): ZonePart? {
                val nw = frame.toFrame(piece.area.minX, piece.area.maxY)
                val se = frame.toFrame(piece.area.maxX, piece.area.minY)
                val rect =
                    Rect(
                        max(0.0, nw.xMm),
                        max(0.0, nw.yMm),
                        min(frame.frameWidthMm, se.xMm),
                        min(frame.frameHeightMm, se.yMm),
                    )
                if (rect.right - rect.left <= EDGE_TOLERANCE_MM || rect.bottom - rect.top <= EDGE_TOLERANCE_MM) {
                    return null
                }
                return ZonePart(piece.zone, piece.shift, rect)
            }
        }
    }

    private fun edgePoint(
        edge: FrameSide,
        pos: Double,
        frame: FrameProjection,
    ): FramePoint =
        when (edge) {
            FrameSide.TOP -> FramePoint(pos, 0.0)
            FrameSide.BOTTOM -> FramePoint(pos, frame.frameHeightMm)
            FrameSide.LEFT -> FramePoint(0.0, pos)
            FrameSide.RIGHT -> FramePoint(frame.frameWidthMm, pos)
        }

    /** A zone edge on the sheet, with what lies on either side of it. */
    private fun zoneEdge(
        frame: FrameProjection,
        edge: List<LonLat>,
    ): ZoneEdge? {
        val a = edge.first()
        val b = edge.last()
        val meridian = a.lon == b.lon
        val pa = frame.toFrame(a.lon, a.lat)
        val pb = frame.toFrame(b.lon, b.lat)
        val run =
            clip(pa, pb, Rect(0.0, 0.0, frame.frameWidthMm, frame.frameHeightMm)) ?: return null
        val sides =
            if (meridian) {
                val lat = (a.lat + b.lat) / 2
                val west = GridZones.of(lat, a.lon - SIDE_STEP)?.zone
                val east = GridZones.of(lat, a.lon + SIDE_STEP)?.zone
                if (west != null && east != null) west.toString() to east.toString() else null
            } else {
                val south = Utm.bandLetter(a.lat - SIDE_STEP)
                val north = Utm.bandLetter(a.lat + SIDE_STEP)
                if (south != null && north != null) south.toString() to north.toString() else null
            }
        val degrees = if (meridian) Utm.normalizeLon(a.lon) else a.lat
        return ZoneEdge(GridLine(listOf(run.first, run.second), major = true), meridian, degrees, sides)
    }

    /** West and east, or south and north, of the two grid zones of a sheet. */
    private fun sidesOf(
        frame: FrameProjection,
        zones: List<GridZone>,
    ): Map<String, CompassSide> {
        val (a, b) = zones
        val ca = frame.toFrame((a.west + a.east) / 2, (a.south + a.north) / 2)
        val cb = frame.toFrame((b.west + b.east) / 2, (b.south + b.north) / 2)
        // Zones side by side differ in longitude, bands one above the other in latitude.
        return if (a.zone != b.zone) {
            val aWest = ca.xMm < cb.xMm
            mapOf(
                a.name to if (aWest) CompassSide.WEST else CompassSide.EAST,
                b.name to if (aWest) CompassSide.EAST else CompassSide.WEST,
            )
        } else {
            val aNorth = ca.yMm < cb.yMm
            mapOf(
                a.name to if (aNorth) CompassSide.NORTH else CompassSide.SOUTH,
                b.name to if (aNorth) CompassSide.SOUTH else CompassSide.NORTH,
            )
        }
    }

    /**
     * The graduated band of the frame, in the grid of the sheet and at its spacing: a bar between two of its lines,
     * black and white in turn. Nothing without a grid. Until 2026-10-28 the band was in the other system (degrees for
     * a projected grid, UTM kilometers for a graticule); the user wants the chosen grid alone on the sheet (ADR-087).
     */
    fun graduation(
        frame: FrameProjection,
        grid: GridKind,
        step: Double? = null,
        locale: Locale = Locale.getDefault(),
        style: MapStyle = MapStyle.TOP25,
    ): Graduation {
        val plan = plan(frame, grid, step, locale, style)
        if (plan.labels.isEmpty()) return Graduation.EMPTY
        val bars = ArrayList<GraduationBar>()
        for (side in FrameSide.entries) {
            val length =
                if (side == FrameSide.TOP ||
                    side == FrameSide.BOTTOM
                ) {
                    frame.frameWidthMm
                } else {
                    frame.frameHeightMm
                }
            val ticks = plan.labels.filter { it.side == side && !it.neighbour }.sortedBy { it.positionMm }
            if (ticks.isEmpty()) continue
            // Along the left and right sides the values grow upwards, against the frame's y.
            val upwards = side == FrameSide.LEFT || side == FrameSide.RIGHT
            var start = 0.0
            for ((n, tick) in ticks.withIndex()) {
                val before = if (upwards) tick.index else tick.index - 1
                bars += GraduationBar(side, start, tick.positionMm, Math.floorMod(before, 2L) == 0L)
                start = tick.positionMm
                if (n == ticks.lastIndex) {
                    val after = if (upwards) tick.index - 1 else tick.index
                    bars += GraduationBar(side, start, length, Math.floorMod(after, 2L) == 0L)
                }
            }
        }
        return Graduation(grid, plan.step, plan.stepText, bars.filter { it.endMm - it.startMm > 0.01 })
    }

    /**
     * The angle between true north and the north of the grid at the centre of [frame], in degrees, positive when grid
     * north lies east of true north (so the grid north arrow turns clockwise on the page); `null` for a grid that has
     * none or is not valid there. UTM: [Utm.convergenceDegrees]; Lambert-93: `n * dLon`.
     */
    fun convergenceDegrees(
        frame: FrameProjection,
        kind: GridKind,
    ): Double? = convergenceDegrees(kind, frame.centerLat, frame.centerLon)

    /** The same angle at a position: the centre of a sheet, or of the screen (P24.3). */
    fun convergenceDegrees(
        kind: GridKind,
        lat: Double,
        lon: Double,
    ): Double? =
        when (kind) {
            GridKind.UTM, GridKind.MGRS -> {
                if (lat > UTM_MAX_LAT || lat < UTM_MIN_LAT) {
                    null
                } else {
                    Utm.convergenceDegrees(lat, lon)
                }
            }

            GridKind.LAMBERT93 -> {
                if (lat in FRANCE_LAT && lon in FRANCE_LON) {
                    LAMBERT93_CONE_CONSTANT * (lon - LAMBERT93_CENTRAL_MERIDIAN)
                } else {
                    null
                }
            }

            GridKind.NONE, GridKind.WGS84, GridKind.WGS84_DECIMAL -> {
                null
            }
        }

    /** The 100 km square of the frame centre, for the legend of an MGRS grid (`31T DQ`). */
    fun mgrsSquare(frame: FrameProjection): String? =
        Mgrs.of(frame.centerLat, frame.centerLon)?.let { "${it.zone}${it.band} ${it.columnLetter}${it.rowLetter}" }

    /**
     * A full MGRS reference of the frame centre at 100 m (`31T FK 453 507`), in the frame's own zone: the worked
     * example of the grid reference box of a NATO sheet.
     */
    fun exampleReference(frame: FrameProjection): String? {
        val band = Utm.bandLetter(frame.centerLat) ?: return null
        return Mgrs.of(Utm.toUtm(frame.centerLat, frame.centerLon), band).format(digits = 3)
    }

    private fun isSquareEdge(value: Double): Boolean = abs(value / SQUARE - Math.rint(value / SQUARE)) < 1e-9

    /**
     * The squares under the frame, by sampling it, each in the zone of the place (P24.4: the letters are right on both
     * sides of a zone edge); and a tag in each when there are several. On a sheet across zones ([qualified]) a square
     * is named with its zone (`31T GL`), and the tag of a square along a zone edge stands beside it, as in the P24.0
     * preview.
     */
    private fun squaresOf(
        frame: FrameProjection,
        qualified: Boolean,
        edges: List<ZoneEdge>,
    ): Pair<List<String>, List<SquareTag>> {
        val samples = LinkedHashMap<String, MutableList<FramePoint>>()
        val columns = SQUARE_SAMPLES
        val rows = SQUARE_SAMPLES
        for (r in 0..rows) {
            for (c in 0..columns) {
                val p = FramePoint(frame.frameWidthMm * c / columns, frame.frameHeightMm * r / rows)
                val ll = frame.toLonLat(p.xMm, p.yMm)
                val ref = Mgrs.of(ll.lat, Utm.normalizeLon(ll.lon)) ?: continue
                val letters = "${ref.columnLetter}${ref.rowLetter}"
                samples.getOrPut(if (qualified) "${ref.zone}${ref.band} $letters" else letters) { ArrayList() } += p
            }
        }
        val names = samples.keys.toList()
        if (names.size < 2) return names to emptyList()
        val column = frame.frameWidthMm / columns
        val meridians = edges.filter { it.meridian }.map { it.line.points.first().xMm }
        val tags =
            samples.map { (name, points) ->
                val y = points.sumOf { it.yMm } / points.size
                val eastmost = points.maxOf { it.xMm }
                val westmost = points.minOf { it.xMm }
                // A sample column at most from the edge: the square runs along it, on one side.
                val before = meridians.firstOrNull { it > eastmost && it - eastmost <= column + EDGE_TOLERANCE_MM }
                val after = meridians.firstOrNull { it < westmost && westmost - it <= column + EDGE_TOLERANCE_MM }
                when {
                    qualified && before != null -> SquareTag(name, before - TAG_GAP_MM, y, TagAlign.END)
                    qualified && after != null -> SquareTag(name, after + TAG_GAP_MM, y, TagAlign.START)
                    else -> SquareTag(name, points.sumOf { it.xMm } / points.size, y)
                }
            }
        return names to tags
    }

    private fun FrameProjection.toFrameOf(p: LonLat): FramePoint = toFrame(p.lon, p.lat)

    /** Frame outline sampled every few millimeters, as positions (the frame is curved in the grid's eyes). */
    private fun perimeter(frame: FrameProjection): List<LonLat> {
        val n = 24
        val out = ArrayList<LonLat>()
        for (i in 0..n) {
            val t = i.toDouble() / n
            out += frame.toLonLat(frame.frameWidthMm * t, 0.0)
            out += frame.toLonLat(frame.frameWidthMm * t, frame.frameHeightMm)
            out += frame.toLonLat(0.0, frame.frameHeightMm * t)
            out += frame.toLonLat(frame.frameWidthMm, frame.frameHeightMm * t)
        }
        return out
    }

    /** The multiples of [step] between [min] and [max], with their index (value / step). */
    private fun multiples(
        min: Double,
        max: Double,
        step: Double,
    ): List<Pair<Long, Double>>? {
        val first = ceil(min / step - 1e-9).toLong()
        val last = floor(max / step + 1e-9).toLong()
        if (last - first > MAX_LINES_PER_AXIS) return null
        if (last < first) return emptyList()
        return (first..last).map { it to it * step }
    }

    /**
     * Clips the polyline [raw] (frame millimeters) to [rect] and appends each inside run to [lines]; [onEdge] is
     * called for the end of a run that sits on a frame edge, to write the label there.
     */
    private fun addClipped(
        raw: List<FramePoint>,
        rect: Rect,
        frame: FrameProjection,
        lines: MutableList<GridLine>,
        major: Boolean,
        onEdge: (FrameSide, Double) -> Unit,
    ) {
        var run = ArrayList<FramePoint>()

        fun flush() {
            if (run.size >= 2) {
                lines += GridLine(run.toList(), major)
                edgeLabel(run.first(), frame, onEdge)
                edgeLabel(run.last(), frame, onEdge)
            }
            run = ArrayList()
        }
        for (i in 0 until raw.size - 1) {
            val seg = clip(raw[i], raw[i + 1], rect)
            if (seg == null) {
                flush()
                continue
            }
            if (run.isNotEmpty() && distance(run.last(), seg.first) > EDGE_TOLERANCE_MM) flush()
            if (run.isEmpty()) run.add(seg.first)
            run.add(seg.second)
        }
        flush()
    }

    private fun edgeLabel(
        p: FramePoint,
        frame: FrameProjection,
        onEdge: (FrameSide, Double) -> Unit,
    ) {
        when {
            abs(p.yMm) <= EDGE_TOLERANCE_MM -> onEdge(FrameSide.TOP, p.xMm)
            abs(p.yMm - frame.frameHeightMm) <= EDGE_TOLERANCE_MM -> onEdge(FrameSide.BOTTOM, p.xMm)
            abs(p.xMm) <= EDGE_TOLERANCE_MM -> onEdge(FrameSide.LEFT, p.yMm)
            abs(p.xMm - frame.frameWidthMm) <= EDGE_TOLERANCE_MM -> onEdge(FrameSide.RIGHT, p.yMm)
        }
    }

    private fun distance(
        a: FramePoint,
        b: FramePoint,
    ): Double = Math.hypot(a.xMm - b.xMm, a.yMm - b.yMm)

    /** Liang–Barsky: the part of the segment inside [rect], or `null`. */
    private fun clip(
        a: FramePoint,
        b: FramePoint,
        rect: Rect,
    ): Pair<FramePoint, FramePoint>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.xMm - a.xMm
        val dy = b.yMm - a.yMm
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(a.xMm - rect.left, rect.right - a.xMm, a.yMm - rect.top, rect.bottom - a.yMm)
        for (i in 0 until 4) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return null
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) {
                    if (t > t1) return null
                    t0 = max(t0, t)
                } else {
                    if (t < t0) return null
                    t1 = min(t1, t)
                }
            }
        }
        return FramePoint(a.xMm + t0 * dx, a.yMm + t0 * dy) to FramePoint(a.xMm + t1 * dx, a.yMm + t1 * dy)
    }

    // ---- coordinate systems -------------------------------------------------------------------------------------

    private interface GridSystem {
        val systemText: String

        fun toGrid(
            lat: Double,
            lon: Double,
        ): Pair<Double, Double>

        fun fromGrid(
            x: Double,
            y: Double,
        ): LonLat

        fun labelX(
            x: Double,
            step: Double,
            locale: Locale,
        ): String

        fun labelY(
            y: Double,
            step: Double,
            locale: Locale,
        ): String

        fun parts(value: Double): LabelParts? = null

        fun stepText(
            step: Double,
            locale: Locale,
        ): String
    }

    private fun systemFor(
        frame: FrameProjection,
        kind: GridKind,
    ): GridSystem? =
        when (kind) {
            GridKind.NONE -> {
                null
            }

            GridKind.WGS84 -> {
                DmsSystem
            }

            GridKind.WGS84_DECIMAL -> {
                DecimalSystem
            }

            // Planned zone by zone ([zoned]).
            GridKind.UTM, GridKind.MGRS -> {
                null
            }

            GridKind.LAMBERT93 -> {
                val inFrance = frame.centerLat in FRANCE_LAT && frame.centerLon in FRANCE_LON
                if (inFrance) ProjectedSystem.lambert() else null
            }
        }

    private val FRANCE_LAT = 41.0..52.0
    private val FRANCE_LON = -6.0..10.0
    private const val UTM_MAX_LAT = 84.0
    private const val LAMBERT93_CONE_CONSTANT = 0.7256077650532670
    private const val LAMBERT93_CENTRAL_MERIDIAN = 3.0
    private const val UTM_MIN_LAT = -80.0
    private const val SQUARE_SAMPLES = 12

    /** From a zone edge to the name of a square beside it. */
    private const val TAG_GAP_MM = 5.0

    /** A step into the zone or band on either side of an edge, in degrees. */
    private const val SIDE_STEP = 1e-6

    private abstract class Graticule : GridSystem {
        override fun toGrid(
            lat: Double,
            lon: Double,
        ) = lon to lat

        override fun fromGrid(
            x: Double,
            y: Double,
        ) = LonLat(x, y)

        override fun labelX(
            x: Double,
            step: Double,
            locale: Locale,
        ) = angle(x, 'E', 'W', step, locale)

        override fun labelY(
            y: Double,
            step: Double,
            locale: Locale,
        ) = angle(y, 'N', 'S', step, locale)

        abstract fun angle(
            value: Double,
            positive: Char,
            negative: Char,
            step: Double,
            locale: Locale,
        ): String
    }

    private object DmsSystem : Graticule() {
        override val systemText = "WGS84 · degrés, minutes, secondes"

        override fun stepText(
            step: Double,
            locale: Locale,
        ): String {
            val seconds = Math.round(step * 3600.0)
            return when {
                seconds % 3600 == 0L -> "${seconds / 3600}°"
                seconds % 60 == 0L -> "${seconds / 60}′"
                else -> "$seconds″"
            }
        }

        override fun angle(
            value: Double,
            positive: Char,
            negative: Char,
            step: Double,
            locale: Locale,
        ): String {
            val totalSeconds = Math.round(abs(value) * 3600.0)
            val d = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            val stepSeconds = Math.round(step * 3600.0)
            val hemisphere = if (value < 0) negative else positive
            return when {
                stepSeconds % 3600 == 0L -> "$d°$hemisphere"
                stepSeconds % 60 == 0L -> "$d°${m.toString().padStart(2, '0')}′$hemisphere"
                else -> "$d°${m.toString().padStart(2, '0')}′${s.toString().padStart(2, '0')}″$hemisphere"
            }
        }
    }

    private object DecimalSystem : Graticule() {
        override val systemText = "WGS84 · degrés décimaux"

        private fun decimals(step: Double): Int = max(0, ceil(-Math.log10(step) - 1e-9).toInt())

        override fun stepText(
            step: Double,
            locale: Locale,
        ): String = String.format(locale, "%.${decimals(step)}f°", step)

        override fun angle(
            value: Double,
            positive: Char,
            negative: Char,
            step: Double,
            locale: Locale,
        ): String {
            val hemisphere = if (value < 0) negative else positive
            return String.format(locale, "%.${decimals(step)}f°%s", abs(value), hemisphere)
        }
    }

    private class ProjectedSystem(
        override val systemText: String,
        private val forward: (lat: Double, lon: Double) -> Pair<Double, Double>,
        private val inverse: (x: Double, y: Double) -> LonLat,
        private val mgrs: Boolean,
    ) : GridSystem {
        override fun toGrid(
            lat: Double,
            lon: Double,
        ) = forward(lat, lon)

        override fun fromGrid(
            x: Double,
            y: Double,
        ) = inverse(x, y)

        /** MGRS reads the kilometers inside the 100 km square; the others read the whole figure. */
        override fun labelX(
            x: Double,
            step: Double,
            locale: Locale,
        ) = label(x, step, locale)

        override fun labelY(
            y: Double,
            step: Double,
            locale: Locale,
        ) = label(y, step, locale)

        override fun parts(value: Double): LabelParts {
            val meters = Math.round(value)
            val km = Math.floorDiv(meters, KM.toLong())
            val under = Math.floorMod(meters, KM.toLong())
            val hundreds = Math.floorDiv(km, HECTO)
            return LabelParts(
                // MGRS reads the kilometers inside the 100 km square: no hundreds of km before them (ADR-087).
                prefix = if (hundreds > 0 && !mgrs) hundreds.toString() else "",
                major = Math.floorMod(km, HECTO).toString().padStart(2, '0'),
                rest = if (under == 0L) "" else under.toString().padStart(3, '0'),
            )
        }

        private fun label(
            value: Double,
            step: Double,
            locale: Locale,
        ): String {
            val meters = Math.round(value)
            if (mgrs) {
                val inSquare = Math.floorMod(meters, SQUARE.toLong())
                return if (step >= KM) {
                    (inSquare / KM.toLong()).toString().padStart(2, '0')
                } else {
                    (inSquare / HECTO).toString().padStart(3, '0')
                }
            }
            return if (step >= KM) {
                (meters / KM.toLong()).toString()
            } else {
                String.format(locale, "%d", meters)
            }
        }

        override fun stepText(
            step: Double,
            locale: Locale,
        ): String = ScaleBar.format(step)

        companion object {
            /**
             * The grid of [zone], carried past its edges as far as asked (its part of the sheet is cut later). The
             * frame centre's hemisphere for the whole page: lines stay continuous across the equator. [shift]: the
             * longitudes of the sheet less those of the zone, 360° for a zone met past the antimeridian.
             */
            fun utm(
                zone: Int,
                north: Boolean,
                mgrs: Boolean,
                band: Char,
                shift: Double,
            ): ProjectedSystem {
                val name = if (mgrs) "MGRS $zone$band · WGS84" else "UTM $zone${if (north) "N" else "S"} · WGS84"
                val meridian = Utm.centralMeridian(zone)
                return ProjectedSystem(
                    systemText = name,
                    forward = { lat, lon ->
                        val u = Utm.toUtm(lat, lon, zone, north)
                        u.easting to u.northing
                    },
                    inverse = { x, y ->
                        // Unwrapped around the central meridian, then into the longitudes of the sheet.
                        val p = Utm.toLonLat(UtmCoordinate(zone, north, x, y))
                        LonLat(meridian + Utm.normalizeLon(p.lon - meridian) + shift, p.lat)
                    },
                    mgrs = mgrs,
                )
            }

            fun lambert(): ProjectedSystem =
                ProjectedSystem(
                    systemText = "Lambert-93 · RGF93 (EPSG:2154)",
                    forward = { lat, lon -> Lambert93.toLambert(lat, lon).let { it.easting to it.northing } },
                    inverse = { x, y -> Lambert93.toLonLat(PlanarCoordinate(x, y)) },
                    mgrs = false,
                )

            private const val KM = 1000.0
            private const val HECTO = 100L
        }
    }

    /** The 1, 2 or 5 × 10^n closest to [target] on a logarithmic scale (a line every 2 or 5 km, never every 3). */
    internal fun nearestNice(target: Double): Double {
        if (!(target > 0.0)) return 0.0
        val base = Math.pow(10.0, Math.floor(Math.log10(target)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * base }.minBy { abs(Math.log(it / target)) }
    }
}
