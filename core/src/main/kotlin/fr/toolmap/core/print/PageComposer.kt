package fr.toolmap.core.print

import fr.toolmap.core.geo.Lambert93
import fr.toolmap.core.geo.Mgrs
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.Utm
import fr.toolmap.core.pdf.LineCap
import fr.toolmap.core.pdf.LineJoin
import fr.toolmap.core.pdf.PdfColor
import fr.toolmap.core.pdf.PdfFont
import fr.toolmap.core.pdf.PdfPage
import fr.toolmap.core.pdf.TextAlign
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Draws everything of a page but the map tiles, in vector form (docs/specs/print-pdf.md §6). Over the map: grid,
 * 100 km square letters, tracks and waypoints. Around it: the double frame, its band graduated in a second coordinate
 * system, the grid labels with their principal digits and the corner coordinates. Under it: the footer of an IGN
 * TOP 25 sheet — title and legend, scale, the three norths, the grid — plus, for a NATO sheet, angles in mils and the
 * grid reference box. Positions are millimeters from the page's top-left corner; the map starts at
 * ([PageSetup.frameLeftMm], [PageSetup.frameTopMm]), where a point of [FrameProjection] lands where the maths put it.
 */
class PageComposer(
    private val plan: PrintPlan,
    private val content: PrintContent,
) {
    private val template = plan.template
    private val setup = template.page
    private val style = setup.style
    private val dressing = template.dressing
    private val texts = content.texts
    private val locale = content.locale
    private val frameW = setup.frameWidthMm
    private val frameH = setup.frameHeightMm
    private val gridColor = if (style == MapStyle.NATO) PdfColor.BLACK else GRID_BLUE

    /**
     * Draws the overlay of [pp] on [page], whose map is already in place. [pageCount] is the number of map pages of the
     * job and [missingTiles] how many tiles could not be read.
     */
    fun drawOverlay(
        page: PdfPage,
        pp: PrintPagePlan,
        pageCount: Int,
        missingTiles: Int,
    ) {
        val overview = pp.kind == PageKind.OVERVIEW
        val grid =
            if (overview) {
                GridPlan.empty()
            } else {
                GridPlanner.plan(
                    pp.frame,
                    template.grid,
                    template.gridStep,
                    locale,
                    style,
                )
            }
        val graduation =
            if (overview) {
                Graduation.EMPTY
            } else {
                GridPlanner.graduation(pp.frame, template.grid, template.gridStep, locale, style)
            }
        page.save()
        page.translate(setup.frameLeftMm, setup.frameTopMm)
        page.save()
        page.clipRect(0.0, 0.0, frameW, frameH)
        drawGridLines(page, grid)
        drawZoneEdges(page, grid)
        drawSquareTags(page, grid)
        if (overview) drawFootprints(page, pp.frame, pp.footprints)
        val tracks = if (dressing.tracks) drawTracks(page, pp.frame, overview) else emptyList()
        val waypoints =
            if (dressing.waypoints) drawWaypoints(page, pp.frame, !overview && dressing.waypointNames) else 0
        page.restore()
        drawNeatline(page, graduation)
        val taken = ArrayList<Box>()
        // The corners are in degrees: only on a sheet whose grid is in degrees, or without a grid (ADR-087).
        if (dressing.cornerCoordinates && !overview && template.grid in DEGREE_GRIDS) {
            drawCorners(page, pp.frame, taken)
        }
        drawGridLabels(page, grid, taken)
        page.restore()
        drawFooter(page, pp, pageCount, missingTiles, grid, tracks, waypoints)
    }

    // ---- over the map --------------------------------------------------------------------------------------------

    private fun drawGridLines(
        page: PdfPage,
        grid: GridPlan,
    ) {
        if (grid.lines.isEmpty()) return
        page.save()
        page.strokeColor(gridColor)
        page.alpha(GRID_ALPHA)
        for (major in listOf(false, true)) {
            val lines = grid.lines.filter { it.major == major }
            if (lines.isEmpty()) continue
            page.lineWidth(if (major) GRID_MAJOR_MM else GRID_LINE_MM)
            for (line in lines) {
                val xs = DoubleArray(line.points.size) { line.points[it].xMm }
                val ys = DoubleArray(line.points.size) { line.points[it].yMm }
                page.polyline(xs, ys)
            }
        }
        page.restore()
    }

    /**
     * The edges of the grid zones in a thick dark line, each with its meridian (`6° E`) at the top or its parallel
     * (`48° N`) at the left, as in the P24.0 preview (P24.4).
     */
    private fun drawZoneEdges(
        page: PdfPage,
        grid: GridPlan,
    ) {
        if (grid.zoneEdges.isEmpty()) return
        page.save()
        page.strokeColor(ZONE_EDGE_COLOR)
        page.lineWidth(ZONE_EDGE_MM)
        for (edge in grid.zoneEdges) {
            val points = edge.line.points
            page.polyline(DoubleArray(points.size) { points[it].xMm }, DoubleArray(points.size) { points[it].yMm })
        }
        page.restore()
        val cap = capHeight(EDGE_TAG_PT)
        for (edge in grid.zoneEdges) {
            val start = edge.line.points.minBy { if (edge.meridian) it.yMm else it.xMm }
            val name = edgeDegrees(edge)
            if (edge.meridian) {
                haloText(
                    page,
                    name,
                    start.xMm,
                    start.yMm + EDGE_TAG_INSET_MM + cap,
                    PdfFont.REGULAR,
                    EDGE_TAG_PT,
                    ZONE_EDGE_COLOR,
                )
            } else {
                haloText(
                    page,
                    name,
                    start.xMm + EDGE_TAG_INSET_MM,
                    start.yMm - EDGE_TAG_LIFT_MM,
                    PdfFont.REGULAR,
                    EDGE_TAG_PT,
                    ZONE_EDGE_COLOR,
                    TextAlign.LEFT,
                )
            }
        }
    }

    /** `6° E`, `48° N`: the meridian or the parallel of a zone edge. */
    private fun edgeDegrees(edge: ZoneEdge): String {
        val degrees = Math.round(abs(edge.degrees))
        val side =
            if (edge.meridian) {
                if (edge.degrees < 0) texts.west else texts.east
            } else {
                if (edge.degrees < 0) texts.south else texts.north
            }
        return "$degrees° $side"
    }

    /**
     * The letters of each 100 km square, in the middle of its part of the map; on a sheet across two zones, with the
     * zone and beside the zone edge.
     */
    private fun drawSquareTags(
        page: PdfPage,
        grid: GridPlan,
    ) {
        for (tag in grid.squareTags) {
            val align =
                when (tag.align) {
                    TagAlign.CENTER -> TextAlign.CENTER
                    TagAlign.END -> TextAlign.RIGHT
                    TagAlign.START -> TextAlign.LEFT
                }
            val y = tag.yMm + capHeight(SQUARE_PT) / 2
            haloText(page, tag.letters, tag.xMm, y, PdfFont.BOLD, SQUARE_PT, gridColor, align)
        }
    }

    private fun drawTracks(
        page: PdfPage,
        frame: FrameProjection,
        thin: Boolean,
    ): List<PrintTrack> {
        val drawn = ArrayList<PrintTrack>()
        for (track in content.tracks) {
            var any = false
            for (segment in track.segments) {
                if (segment.size < 2) continue
                val xs = DoubleArray(segment.size)
                val ys = DoubleArray(segment.size)
                var minX = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE
                var minY = Double.MAX_VALUE
                var maxY = -Double.MAX_VALUE
                for (i in segment.indices) {
                    val p = frame.toFrame(segment[i].lon, segment[i].lat)
                    xs[i] = p.xMm
                    ys[i] = p.yMm
                    minX = min(minX, p.xMm)
                    maxX = max(maxX, p.xMm)
                    minY = min(minY, p.yMm)
                    maxY = max(maxY, p.yMm)
                }
                if (maxX < 0 || minX > frameW || maxY < 0 || minY > frameH) continue
                if (!any) {
                    page.save()
                    page.strokeColor(PdfColor.fromArgb(track.colorArgb))
                    page.lineWidth(if (thin) OVERVIEW_TRACK_MM else TRACK_MM)
                    page.lineStyle(LineCap.ROUND, LineJoin.ROUND)
                    page.alpha(TRACK_ALPHA)
                }
                any = true
                page.polyline(xs, ys, minStepMm = TRACK_MIN_STEP_MM)
            }
            if (any) {
                page.restore()
                drawn += track
            }
        }
        return drawn
    }

    /** A disc of the waypoint's colour with its white icon, and its name outlined in white (P17, Q16). */
    private fun drawWaypoints(
        page: PdfPage,
        frame: FrameProjection,
        withNames: Boolean,
    ): Int {
        var count = 0
        for (waypoint in content.waypoints) {
            val p = frame.toFrame(waypoint.lon, waypoint.lat)
            val r = WAYPOINT_RADIUS_MM
            if (p.xMm < -r || p.xMm > frameW + r || p.yMm < -r || p.yMm > frameH + r) continue
            count++
            page.save()
            page.fillColor(PdfColor.fromArgb(waypoint.colorArgb))
            page.strokeColor(PdfColor.WHITE)
            page.lineWidth(WAYPOINT_OUTLINE_MM)
            page.circle(p.xMm, p.yMm, r)
            page.fillAndStroke()
            page.restore()
            waypoint.iconId?.let { content.glyphs[it] }?.let {
                page.image(it, p.xMm - GLYPH_MM / 2, p.yMm - GLYPH_MM / 2, GLYPH_MM, GLYPH_MM)
            }
            val name = waypoint.name?.trim()?.takeIf { withNames && it.isNotEmpty() } ?: continue
            val text = fit(page, name, PdfFont.BOLD, WAYPOINT_PT, WAYPOINT_NAME_MAX_MM)
            haloText(
                page,
                text,
                p.xMm + r + WAYPOINT_NAME_GAP_MM,
                p.yMm + capHeight(WAYPOINT_PT) / 2,
                PdfFont.BOLD,
                WAYPOINT_PT,
                PdfColor.BLACK,
                TextAlign.LEFT,
            )
        }
        return count
    }

    /** The outlines of atlas pages on the assembly page, with their numbers. */
    private fun drawFootprints(
        page: PdfPage,
        frame: FrameProjection,
        footprints: List<PageFootprint>,
    ) {
        for (footprint in footprints) {
            val points = footprint.corners.map { frame.toFrame(it.lon, it.lat) }
            page.save()
            page.strokeColor(FOOTPRINT_COLOR)
            page.lineWidth(FOOTPRINT_LINE_MM)
            page.lineStyle(LineCap.BUTT, LineJoin.MITER)
            page.moveTo(points[0].xMm, points[0].yMm)
            for (i in 1 until points.size) page.lineTo(points[i].xMm, points[i].yMm)
            page.closePath()
            page.stroke()
            page.restore()
            val cx = points.sumOf { it.xMm } / points.size
            val cy = points.sumOf { it.yMm } / points.size
            haloText(
                page,
                footprint.number.toString(),
                cx,
                cy + capHeight(FOOTPRINT_NUMBER_PT) / 2,
                PdfFont.BOLD,
                FOOTPRINT_NUMBER_PT,
                FOOTPRINT_COLOR,
            )
        }
    }

    // ---- frame and margins ---------------------------------------------------------------------------------------

    /** The double frame: a thin inner line, the graduated band, a thicker outer line (P17, Q14). */
    private fun drawNeatline(
        page: PdfPage,
        graduation: Graduation,
    ) {
        val g = PageSetup.GRADUATION_MM
        val black = graduation.bars.filter { it.black }
        if (black.isNotEmpty()) {
            page.save()
            page.fillColor(PdfColor.BLACK)
            for (bar in black) {
                val length = bar.endMm - bar.startMm
                when (bar.side) {
                    FrameSide.TOP -> page.rect(bar.startMm, -g, length, g)
                    FrameSide.BOTTOM -> page.rect(bar.startMm, frameH, length, g)
                    FrameSide.LEFT -> page.rect(-g, bar.startMm, g, length)
                    FrameSide.RIGHT -> page.rect(frameW, bar.startMm, g, length)
                }
            }
            page.fill()
            page.restore()
        }
        page.save()
        page.strokeColor(PdfColor.BLACK)
        page.lineStyle(LineCap.BUTT, LineJoin.MITER)
        page.lineWidth(INNER_LINE_MM)
        page.rect(0.0, 0.0, frameW, frameH)
        page.stroke()
        page.lineWidth(OUTER_LINE_MM)
        page.rect(-g, -g, frameW + 2 * g, frameH + 2 * g)
        page.stroke()
        page.restore()
    }

    /**
     * The latitude and longitude of the four corners, in degrees, minutes and seconds (in decimal degrees on a sheet
     * whose grid is): the longitude in the outer row of the strip above or below, the latitude up the strip on the side.
     */
    private fun drawCorners(
        page: PdfPage,
        frame: FrameProjection,
        taken: MutableList<Box>,
    ) {
        val g = PageSetup.GRADUATION_MM
        val cap = capHeight(CORNER_PT)
        val decimal = template.grid == GridKind.WGS84_DECIMAL
        for (cy in listOf(0.0, frameH)) {
            for (cx in listOf(0.0, frameW)) {
                val p = frame.toLonLat(cx, cy)
                val lon = if (decimal) degrees(p.lon, texts.east, texts.west) else dms(p.lon, texts.east, texts.west)
                val lat =
                    if (decimal) {
                        degrees(
                            p.lat,
                            texts.north,
                            texts.south,
                        )
                    } else {
                        dms(p.lat, texts.north, texts.south)
                    }
                val left = cx == 0.0
                val top = cy == 0.0
                // Longitude, horizontal, starting at the outer corner of the frame.
                val lonWidth = page.textWidth(lon, PdfFont.REGULAR, CORNER_PT)
                val baseline = if (top) -g - CORNER_TOP_MM else frameH + g + CORNER_BOTTOM_MM
                val x = if (left) -g else frameW + g
                page.text(
                    lon,
                    x,
                    baseline,
                    PdfFont.REGULAR,
                    CORNER_PT,
                    PdfColor.BLACK,
                    if (left) TextAlign.LEFT else TextAlign.RIGHT,
                )
                val lonLeft = if (left) x else x - lonWidth
                taken += Box(lonLeft, baseline - cap, lonLeft + lonWidth, baseline + DESCENT_MM)
                // Latitude, reading upwards, from the corner along the side.
                val latWidth = page.textWidth(lat, PdfFont.REGULAR, CORNER_PT)
                val bx = if (left) -g - CORNER_SIDE_MM else frameW + g + CORNER_SIDE_MM + cap
                val by = if (top) -g else frameH + g
                page.save()
                page.translate(bx, by)
                page.rotate(-90.0)
                page.text(
                    lat,
                    0.0,
                    0.0,
                    PdfFont.REGULAR,
                    CORNER_PT,
                    PdfColor.BLACK,
                    if (top) TextAlign.RIGHT else TextAlign.LEFT,
                )
                page.restore()
                val y0 = if (top) by else by - latWidth
                taken += Box(bx - cap, y0, bx + DESCENT_MM, y0 + latWidth)
            }
        }
    }

    /**
     * The value of each grid line where it leaves the frame, with a tick across the strip. Projected grids write the
     * kilometers inside the 100 km square in big figures and the rest small; the first line of each side (west or
     * south) is written in full with its unit, the way a TOP 25 sheet does (P17, Q14).
     */
    private fun drawGridLabels(
        page: PdfPage,
        grid: GridPlan,
        taken: MutableList<Box>,
    ) {
        if (grid.labels.isEmpty()) return
        val own = grid.labels.filter { !it.neighbour }
        drawTicks(page, own, gridColor)
        // How far the labels of each side reach from the frame, for the row of the neighbouring zone beyond them.
        val reach = HashMap<FrameSide, Double>()
        for (side in FrameSide.entries) {
            val labels = own.filter { it.side == side }
            // West to east along the top and bottom, south to north along the sides.
            val ordered =
                if (side == FrameSide.LEFT || side == FrameSide.RIGHT) {
                    labels.sortedByDescending { it.positionMm }
                } else {
                    labels.sortedBy { it.positionMm }
                }
            var fullDone = false
            for (label in ordered) {
                if (!fullDone && label.parts != null) {
                    val full = fullPieces(label, side)
                    val box = placeLabel(page, label, full, taken)
                    if (box != null) {
                        reach.merge(side, outward(box, side), ::max)
                        fullDone = true
                        continue
                    }
                }
                placeLabel(page, label, shortPieces(label), taken)?.let { reach.merge(side, outward(it, side), ::max) }
            }
        }
        // The grid of the neighbouring zone carried on: its two figures in blue italics, in the outer row (P24.4).
        val neighbours = grid.labels.filter { it.neighbour }
        if (neighbours.isEmpty()) return
        drawTicks(page, neighbours, NEIGHBOUR_BLUE)
        for (label in neighbours) {
            val text = label.parts?.major ?: label.text
            val inset = (reach[label.side] ?: 0.0) + NEIGHBOUR_ROW_GAP_MM
            placeLabel(
                page,
                label,
                listOf(Piece(text, PdfFont.ITALIC, NEIGHBOUR_PT)),
                taken,
                LabelRow(max(inset, ownRow.inset), NEIGHBOUR_BLUE, outer = true),
            )
        }
    }

    /**
     * How far from the frame a label of [row] may reach on [side]: the strip; an outer row also takes the white margin
     * on the top and the sides as far as a printer reaches (the footer follows the bottom strip).
     */
    private fun stripReach(
        side: FrameSide,
        row: LabelRow,
    ): Double {
        val strip = PageSetup.GRADUATION_MM + PageSetup.LABEL_STRIP_MM
        if (!row.outer || side == FrameSide.BOTTOM) return strip
        return strip + (setup.marginMm - PRINTER_REACH_MM).coerceAtLeast(0.0)
    }

    /** How far [box] reaches out from the frame on [side]. */
    private fun outward(
        box: Box,
        side: FrameSide,
    ): Double =
        when (side) {
            FrameSide.TOP -> -box.top
            FrameSide.BOTTOM -> box.bottom - frameH
            FrameSide.LEFT -> -box.left
            FrameSide.RIGHT -> box.right - frameW
        }

    private fun drawTicks(
        page: PdfPage,
        labels: List<GridLabel>,
        color: PdfColor,
    ) {
        if (labels.isEmpty()) return
        val g = PageSetup.GRADUATION_MM
        page.save()
        page.strokeColor(color)
        page.lineWidth(TICK_LINE_MM)
        for (label in labels) {
            val p = label.positionMm
            when (label.side) {
                FrameSide.TOP -> {
                    page.moveTo(p, -g)
                    page.lineTo(p, -g - TICK_MM)
                }

                FrameSide.BOTTOM -> {
                    page.moveTo(p, frameH + g)
                    page.lineTo(p, frameH + g + TICK_MM)
                }

                FrameSide.LEFT -> {
                    page.moveTo(-g, p)
                    page.lineTo(-g - TICK_MM, p)
                }

                FrameSide.RIGHT -> {
                    page.moveTo(frameW + g, p)
                    page.lineTo(frameW + g + TICK_MM, p)
                }
            }
        }
        page.stroke()
        page.restore()
    }

    /** A run of text in one font and size; [raised] lifts it to align its top with the big figures. */
    private class Piece(
        val text: String,
        val font: PdfFont,
        val sizePt: Double,
        val raised: Boolean = false,
    )

    private fun shortPieces(label: GridLabel): List<Piece> {
        val parts = label.parts ?: return listOf(Piece(label.text, PdfFont.REGULAR, GRATICULE_PT))
        return buildList {
            if (parts.prefix.isNotEmpty()) add(Piece(parts.prefix, PdfFont.REGULAR, SMALL_PT, raised = true))
            add(Piece(parts.major, PdfFont.BOLD, MAJOR_PT))
            if (parts.rest.isNotEmpty()) add(Piece(parts.rest, PdfFont.REGULAR, SMALL_PT, raised = true))
        }
    }

    /** `⁷21⁰⁰⁰ m E`: the whole value with its unit. */
    private fun fullPieces(
        label: GridLabel,
        side: FrameSide,
    ): List<Piece> {
        val parts = label.parts ?: return shortPieces(label)
        // MGRS writes two figures everywhere, never the whole UTM value (ADR-087).
        if (template.grid == GridKind.MGRS) return shortPieces(label)
        val axis = if (side == FrameSide.TOP || side == FrameSide.BOTTOM) texts.east else texts.north
        return buildList {
            if (parts.prefix.isNotEmpty()) add(Piece(parts.prefix, PdfFont.REGULAR, SMALL_PT, raised = true))
            add(Piece(parts.major, PdfFont.BOLD, MAJOR_PT))
            add(Piece(parts.rest.ifEmpty { "000" }, PdfFont.REGULAR, SMALL_PT, raised = true))
            add(Piece(" m $axis", PdfFont.REGULAR, SMALL_PT))
        }
    }

    /**
     * A row of labels in the strip around the frame: [inset] from the frame to the near side of the figures, in
     * [color]; [outer] rows (the neighbouring zone, P24.4) must stay inside the strip.
     */
    private class LabelRow(
        val inset: Double,
        val color: PdfColor,
        val outer: Boolean,
    )

    private val ownRow = LabelRow(PageSetup.GRADUATION_MM + TICK_MM + LABEL_GAP_MM, gridColor, outer = false)

    /**
     * Writes [pieces] at the place of [label] if the room is free; horizontal when it fits the strip, along the side
     * otherwise. Returns where it was written, or `null`.
     */
    private fun placeLabel(
        page: PdfPage,
        label: GridLabel,
        pieces: List<Piece>,
        taken: MutableList<Box>,
        row: LabelRow = ownRow,
    ): Box? {
        val g = PageSetup.GRADUATION_MM
        val width = pieces.sumOf { page.textWidth(it.text, it.font, it.sizePt) }
        val cap = pieces.maxOf { capHeight(it.sizePt) }
        val inset = row.inset
        val pos = label.positionMm
        val room = stripReach(label.side, row) - inset - STRIP_PAD_MM
        val sideways = label.side == FrameSide.LEFT || label.side == FrameSide.RIGHT
        val rotated = sideways && width > room
        val box: Box
        val drawAt: (PdfPage) -> Unit
        if (!rotated) {
            val (left, baseline) =
                when (label.side) {
                    FrameSide.TOP -> (pos - width / 2) to (-inset)
                    FrameSide.BOTTOM -> (pos - width / 2) to (frameH + inset + cap)
                    FrameSide.LEFT -> (-inset - width) to (pos + cap / 2)
                    FrameSide.RIGHT -> (frameW + inset) to (pos + cap / 2)
                }
            box =
                Box(
                    left - LABEL_PAD_MM,
                    baseline - cap - LABEL_PAD_MM,
                    left + width + LABEL_PAD_MM,
                    baseline + DESCENT_MM,
                )
            drawAt = { drawPieces(it, pieces, left, baseline, cap, row.color) }
        } else {
            // Reading upwards; the tops of the figures face away from the frame on the left, towards it on the right.
            val baselineX = if (label.side == FrameSide.LEFT) -inset else frameW + inset + cap
            box =
                Box(
                    baselineX - cap - LABEL_PAD_MM,
                    pos - width / 2 - LABEL_PAD_MM,
                    baselineX + DESCENT_MM,
                    pos + width / 2 + LABEL_PAD_MM,
                )
            drawAt = {
                it.save()
                it.translate(baselineX, pos)
                it.rotate(-90.0)
                drawPieces(it, pieces, -width / 2, 0.0, cap, row.color)
                it.restore()
            }
        }
        val reach = g + LABEL_OVERHANG_MM
        val inside =
            if (sideways) {
                box.top >= -reach && box.bottom <= frameH + reach
            } else {
                box.left >= -reach && box.right <= frameW + reach
            }
        val inStrip = !row.outer || outward(box, label.side) <= stripReach(label.side, row)
        if (!inside || !inStrip || taken.any { it.overlaps(box) }) return null
        taken += box
        drawAt(page)
        return box
    }

    private fun drawPieces(
        page: PdfPage,
        pieces: List<Piece>,
        left: Double,
        baseline: Double,
        cap: Double,
        color: PdfColor = gridColor,
    ) {
        var x = left
        for (piece in pieces) {
            val lift = if (piece.raised) cap - capHeight(piece.sizePt) else 0.0
            page.text(piece.text, x, baseline - lift, piece.font, piece.sizePt, color)
            x += page.textWidth(piece.text, piece.font, piece.sizePt)
        }
    }

    // ---- footer --------------------------------------------------------------------------------------------------

    private fun drawFooter(
        page: PdfPage,
        pp: PrintPagePlan,
        pageCount: Int,
        missingTiles: Int,
        grid: GridPlan,
        tracks: List<PrintTrack>,
        waypoints: Int,
    ) {
        val ox = setup.marginMm
        val oy = setup.footerTopMm
        val width = setup.widthMm - 2 * setup.marginMm
        for (block in SheetLayout.place(width, style)) {
            val box =
                Box(
                    ox + block.xMm,
                    oy + block.yMm,
                    ox + block.xMm + block.widthMm,
                    oy + block.yMm + block.heightMm,
                )
            val drawn =
                when (block.block) {
                    FooterBlock.TITLE -> drawTitleBlock(page, box, pp, pageCount, tracks, waypoints)
                    FooterBlock.SCALE -> drawScaleBlock(page, box, pp.frame.scale)
                    FooterBlock.NORTH -> drawNorthBlock(page, box, pp)
                    FooterBlock.GRID -> drawGridBlock(page, box, grid)
                    FooterBlock.REFERENCE -> drawReferenceBlock(page, box, pp.frame)
                }
            if (drawn) strokeBox(page, box)
        }
        drawCredits(page, ox, oy + setup.footerHeightMm - CREDITS_BASELINE_MM, width, missingTiles)
    }

    /** Title, subtitle, map and legend, and the line of the date: the cartouche of the sheet. */
    private fun drawTitleBlock(
        page: PdfPage,
        box: Box,
        pp: PrintPagePlan,
        pageCount: Int,
        tracks: List<PrintTrack>,
        waypoints: Int,
    ): Boolean {
        if (!dressing.cartouche && !dressing.legend) return false
        val inner = box.inset(BOX_PAD_MM)
        var y = inner.top
        val overview = pp.kind == PageKind.OVERVIEW
        if (dressing.cartouche) {
            val title = if (overview) String.format(locale, texts.overviewTitle, pageCount) else template.title.trim()
            if (title.isNotEmpty()) {
                val lines = wrap(page, title, PdfFont.BOLD, TITLE_PT, inner.width)
                for (line in clamp(page, lines, TITLE_LINES, PdfFont.BOLD, TITLE_PT, inner.width)) {
                    y += lineHeight(TITLE_PT)
                    page.text(line, inner.left, y - lineGap(TITLE_PT), PdfFont.BOLD, TITLE_PT)
                }
            }
            val subtitle = template.subtitle.trim()
            if (subtitle.isNotEmpty() && !overview) {
                y += lineHeight(SUBTITLE_PT)
                page.text(
                    fit(page, subtitle, PdfFont.REGULAR, SUBTITLE_PT, inner.width),
                    inner.left,
                    y - lineGap(SUBTITLE_PT),
                    PdfFont.REGULAR,
                    SUBTITLE_PT,
                )
            }
        }
        val bottomLine = if (dressing.cartouche) inner.bottom - lineHeight(SMALL_TEXT_PT) else inner.bottom
        if (dressing.legend) {
            val layers = pp.layers.layers.map { layerName(it) }.ifEmpty { content.layerNames }
            if (layers.isNotEmpty()) {
                val source = String.format(locale, texts.mapSource, layers.joinToString(" + "))
                for (line in clamp(
                    page,
                    wrap(page, source, PdfFont.REGULAR, TEXT_PT, inner.width),
                    2,
                    PdfFont.REGULAR,
                    TEXT_PT,
                    inner.width,
                )) {
                    if (y + lineHeight(TEXT_PT) > bottomLine) break
                    y += lineHeight(TEXT_PT)
                    page.text(line, inner.left, y - lineGap(TEXT_PT), PdfFont.REGULAR, TEXT_PT)
                }
            }
            drawLegend(page, Box(inner.left, y + LEGEND_GAP_MM, inner.right, bottomLine), tracks, waypoints)
        }
        if (dressing.cartouche) {
            val parts = ArrayList<String>()
            if (content.dateText.isNotBlank()) parts += String.format(locale, texts.printedOn, content.dateText)
            if (!overview && pageCount > 1) parts += String.format(locale, texts.page, pp.number, pageCount)
            parts += texts.producedWith
            val line = fit(page, parts.joinToString(" · "), PdfFont.REGULAR, SMALL_TEXT_PT, inner.width)
            page.text(line, inner.left, inner.bottom - lineGap(SMALL_TEXT_PT), PdfFont.REGULAR, SMALL_TEXT_PT, MUTED)
        }
        return true
    }

    /** The tracks drawn with their colour and the waypoints' count, in one or two columns. */
    private fun drawLegend(
        page: PdfPage,
        area: Box,
        tracks: List<PrintTrack>,
        waypoints: Int,
    ) {
        val rows = ArrayList<LegendRow>()
        tracks.forEach { rows += LegendRow(it.name, LegendSample.LINE, it.colorArgb) }
        if (waypoints > 0) {
            val color = content.waypoints.firstOrNull()?.colorArgb ?: WAYPOINT_SAMPLE_ARGB
            rows += LegendRow(String.format(locale, texts.waypoints, waypoints), LegendSample.DISC, color)
        }
        if (rows.isEmpty()) return
        val rowHeight = lineHeight(TEXT_PT)
        val perColumn = max(0, ((area.bottom - area.top) / rowHeight).toInt())
        if (perColumn == 0) return
        val columns = if (rows.size > perColumn && area.width >= TWO_COLUMNS_MM) 2 else 1
        val columnWidth = (area.width - (columns - 1) * COLUMN_GAP_MM) / columns
        val capacity = perColumn * columns
        val shown =
            if (rows.size <= capacity) {
                rows
            } else {
                rows.take(capacity - 1) + LegendRow("+ ${rows.size - capacity + 1}", LegendSample.NONE, 0)
            }
        shown.forEachIndexed { i, row ->
            val column = i / perColumn
            val x = area.left + column * (columnWidth + COLUMN_GAP_MM)
            val baseline = area.top + (i % perColumn + 1) * rowHeight - lineGap(TEXT_PT)
            val middle = baseline - capHeight(TEXT_PT) / 2
            page.save()
            when (row.sample) {
                LegendSample.LINE -> {
                    page.strokeColor(PdfColor.fromArgb(row.colorArgb))
                    page.lineWidth(TRACK_MM)
                    page.lineStyle(LineCap.ROUND, LineJoin.ROUND)
                    page.moveTo(x, middle)
                    page.lineTo(x + LEGEND_SAMPLE_MM, middle)
                    page.stroke()
                }

                LegendSample.DISC -> {
                    page.fillColor(PdfColor.fromArgb(row.colorArgb))
                    page.strokeColor(PdfColor.WHITE)
                    page.lineWidth(WAYPOINT_OUTLINE_MM)
                    page.circle(x + LEGEND_SAMPLE_MM / 2, middle, LEGEND_DISC_MM)
                    page.fillAndStroke()
                }

                LegendSample.NONE -> {}
            }
            page.restore()
            val textX = x + LEGEND_SAMPLE_MM + LEGEND_TEXT_GAP_MM
            val text = fit(page, row.text, PdfFont.REGULAR, TEXT_PT, columnWidth - (textX - x))
            page.text(text, textX, baseline, PdfFont.REGULAR, TEXT_PT)
        }
    }

    private enum class LegendSample { LINE, DISC, NONE }

    private class LegendRow(
        val text: String,
        val sample: LegendSample,
        val colorArgb: Int,
    )

    /** `1:25 000`, the graphic scale and what a centimeter is on the ground. */
    private fun drawScaleBlock(
        page: PdfPage,
        box: Box,
        scale: Double,
    ): Boolean {
        if (!dressing.scaleText && !dressing.scaleBar) return false
        val inner = box.inset(BOX_PAD_MM)
        val middle = (inner.left + inner.right) / 2
        var y = inner.top
        if (dressing.scaleText) {
            y += capHeight(SCALE_PT) + SCALE_TEXT_GAP_MM
            page.text(PrintFormat.scale(scale, texts), middle, y, PdfFont.BOLD, SCALE_PT, align = TextAlign.CENTER)
        }
        if (dressing.scaleBar) {
            val bar = ScaleBarPlanner.plan(scale, inner.width - SCALE_BAR_OVERHANG_MM, locale)
            if (bar.blocks.isNotEmpty()) {
                val barLeft = middle - bar.widthMm / 2
                val labelBaseline = y + SCALE_LABEL_ROW_MM
                for (tick in bar.labels) {
                    // The number is centred on its tick; the unit of the last one follows it, past the bar's end.
                    val x = barLeft + tick.xMm
                    val number = tick.text.substringBefore(' ')
                    val unit = tick.text.substringAfter(' ', "")
                    page.text(number, x, labelBaseline, PdfFont.REGULAR, SMALL_TEXT_PT, align = TextAlign.CENTER)
                    if (unit.isNotEmpty()) {
                        val after = x + page.textWidth(number, PdfFont.REGULAR, SMALL_TEXT_PT) / 2
                        page.text(" $unit", after, labelBaseline, PdfFont.REGULAR, SMALL_TEXT_PT)
                    }
                }
                val barTop = labelBaseline + SCALE_BAR_GAP_MM
                page.save()
                page.strokeColor(PdfColor.BLACK)
                page.lineWidth(SCALE_BAR_LINE_MM)
                for (block in bar.blocks) {
                    page.fillColor(if (block.black) PdfColor.BLACK else PdfColor.WHITE)
                    page.rect(barLeft + block.startMm, barTop, block.endMm - block.startMm, SCALE_BAR_HEIGHT_MM)
                    page.fillAndStroke()
                }
                page.restore()
                y = barTop + SCALE_BAR_HEIGHT_MM
            }
        }
        val centimeter = String.format(locale, texts.cmOnGround, distance(scale / CM_PER_M))
        y += lineHeight(TEXT_PT)
        page.text(
            fit(page, centimeter, PdfFont.REGULAR, TEXT_PT, inner.width),
            middle,
            y,
            PdfFont.REGULAR,
            TEXT_PT,
            align = TextAlign.CENTER,
        )
        if (plan.edgeErrorPercent >= MIN_EDGE_NOTE_PERCENT) {
            val note = String.format(locale, texts.scaleNote, String.format(locale, "%.1f", plan.edgeErrorPercent))
            for (line in wrap(page, note, PdfFont.ITALIC, SMALL_TEXT_PT, inner.width).take(2)) {
                y += lineHeight(SMALL_TEXT_PT)
                page.text(line, middle, y, PdfFont.ITALIC, SMALL_TEXT_PT, MUTED, TextAlign.CENTER)
            }
        }
        return true
    }

    /**
     * The three norths (P17, Q15): true north with its star, grid north and magnetic north, from the same point. The
     * angles are opened up so the lines can be told apart ("not to scale"); the exact values are written beside.
     */
    private fun drawNorthBlock(
        page: PdfPage,
        box: Box,
        pp: PrintPagePlan,
    ): Boolean {
        if (!dressing.northArrow) return false
        val inner = box.inset(BOX_PAD_MM)
        val overview = pp.kind == PageKind.OVERVIEW
        val declination = content.declinationDegrees?.takeIf { !overview }
        val convergence = if (overview) null else GridPlanner.convergenceDegrees(pp.frame, template.grid)
        val (shownGrid, shownMagnetic) = openAngles(convergence, declination)
        // The diagram, on the left.
        val baseX = inner.left + NORTH_DIAGRAM_MM / 2
        val baseY = inner.bottom - NORTH_BASE_LIFT_MM
        val length = baseY - inner.top - NORTH_LABEL_ROOM_MM
        drawNorthLine(page, baseX, baseY, 0.0, length, NorthTip.STAR, texts.trueNorth)
        shownGrid?.let {
            drawNorthLine(page, baseX, baseY, it, length * GRID_NORTH_SHARE, NorthTip.SQUARE, texts.gridNorth)
        }
        shownMagnetic?.let {
            drawNorthLine(page, baseX, baseY, it, length * MAGNETIC_SHARE, NorthTip.HALF_ARROW, texts.magneticNorth)
        }
        // The values, on the right: one row per angle, the label on the left and the value on the right.
        val textLeft = inner.left + NORTH_DIAGRAM_MM + NORTH_TEXT_GAP_MM
        val textWidth = inner.right - textLeft
        val rows = ArrayList<NorthRow>()
        if (declination != null) rows += NorthRow(texts.declination, withMils(angle(declination), declination))
        if (convergence != null) rows += NorthRow(texts.gridConvergence, withMils(angle(convergence), convergence))
        if (declination != null && convergence != null) {
            val gm = declination - convergence
            rows += NorthRow(texts.gridMagneticAngle, withMils(angle(gm), gm), PdfFont.BOLD)
        }
        val detail =
            content.declinationYear?.takeIf { declination != null }?.let { year ->
                content.declinationAnnualDegrees?.let {
                    String.format(locale, texts.declinationDetail, year, angle(it))
                }
                    ?: year.toString()
            }
        val rowLines = rows.map { northRowLines(page, it, textWidth) }
        val detailLines = detail?.let { wrap(page, it, PdfFont.REGULAR, NOTE_PT, textWidth) }.orEmpty()
        // What fits above the "not to scale" note: the rows always, then the heading, then the yearly detail.
        val room = ((inner.bottom - lineHeight(SMALL_TEXT_PT) - inner.top) / lineHeight(NOTE_PT)).toInt()
        val rowCount = rowLines.sumOf { it.size }
        val heading = rowCount + 1 <= room
        val showDetail = detailLines.isNotEmpty() && rowCount + (if (heading) 1 else 0) + detailLines.size <= room
        val shown = ArrayList<NorthLine>()
        if (heading) shown += NorthLine(texts.northHeading, null, PdfFont.BOLD)
        rowLines.forEachIndexed { i, lines ->
            shown += lines
            if (i == 0 && showDetail) shown += detailLines.map { NorthLine(it, null, PdfFont.REGULAR, MUTED) }
        }
        var y = inner.top
        for (line in shown.take(room)) {
            y += lineHeight(NOTE_PT)
            val baseline = y - lineGap(NOTE_PT)
            line.left?.let { page.text(it, textLeft, baseline, line.font, NOTE_PT, line.color) }
            line.right?.let { page.text(it, inner.right, baseline, line.font, NOTE_PT, line.color, TextAlign.RIGHT) }
        }
        page.text(
            fit(page, texts.notToScale, PdfFont.ITALIC, SMALL_TEXT_PT, textWidth),
            textLeft,
            inner.bottom - lineGap(SMALL_TEXT_PT),
            PdfFont.ITALIC,
            SMALL_TEXT_PT,
            MUTED,
        )
        return true
    }

    private enum class NorthTip { STAR, SQUARE, HALF_ARROW }

    /**
     * [label] wrapped in [width] millimeters; a compound name breaks after its dash when it must break
     * ("Angle quadrillage–" / "magnétique" rather than "Angle" / "quadrillage–magnétique").
     */
    private fun wrapLabel(
        page: PdfPage,
        label: String,
        font: PdfFont,
        width: Double,
    ): List<String> {
        val lines = wrap(page, label, font, NOTE_PT, width)
        val dash = label.indexOf('–')
        if (lines.size < 2 || dash < 0) return lines
        val split = listOf(label.substring(0, dash + 1), label.substring(dash + 1))
        return if (split.all { page.textWidth(it, font, NOTE_PT) <= width }) split else lines
    }

    /** An angle of the norths box: its name and its value. */
    private class NorthRow(
        val label: String,
        val value: String,
        val font: PdfFont = PdfFont.REGULAR,
    )

    /** A line of the norths box: text on the left, text set against the right edge, or both. */
    private class NorthLine(
        val left: String?,
        val right: String?,
        val font: PdfFont,
        val color: PdfColor = PdfColor.BLACK,
    )

    /**
     * The lines of [row] in [width] millimeters: label and value on one line when they fit, otherwise the label
     * wrapped and the value on its last line, or on a line of its own.
     */
    private fun northRowLines(
        page: PdfPage,
        row: NorthRow,
        width: Double,
    ): List<NorthLine> {
        val gap = page.textWidth("  ", row.font, NOTE_PT)
        val value = fit(page, row.value, row.font, NOTE_PT, width)
        val valueWidth = page.textWidth(value, row.font, NOTE_PT)
        val labels = wrapLabel(page, row.label, row.font, width)
        val last = labels.lastOrNull()
        return if (last == null) {
            listOf(NorthLine(null, value, row.font))
        } else if (page.textWidth(last, row.font, NOTE_PT) + gap + valueWidth <= width) {
            labels.dropLast(1).map { NorthLine(it, null, row.font) } + NorthLine(last, value, row.font)
        } else {
            labels.map { NorthLine(it, null, row.font) } + NorthLine(null, value, row.font)
        }
    }

    /** A north line [degrees] clockwise of true north, [length] long, with its tip and its label. */
    private fun drawNorthLine(
        page: PdfPage,
        x: Double,
        y: Double,
        degrees: Double,
        length: Double,
        tip: NorthTip,
        label: String,
    ) {
        page.save()
        page.translate(x, y)
        page.rotate(degrees)
        page.strokeColor(PdfColor.BLACK)
        page.fillColor(PdfColor.BLACK)
        page.lineWidth(NORTH_LINE_MM)
        page.lineStyle(LineCap.BUTT, LineJoin.MITER)
        page.moveTo(0.0, 0.0)
        page.lineTo(0.0, -length)
        page.stroke()
        when (tip) {
            NorthTip.STAR -> {
                star(page, 0.0, -length - STAR_RADIUS_MM, STAR_RADIUS_MM)
                page.fill()
            }

            NorthTip.SQUARE -> {
                page.rect(-NORTH_HEAD_MM / 2, -length - NORTH_HEAD_MM, NORTH_HEAD_MM, NORTH_HEAD_MM)
                page.fill()
            }

            NorthTip.HALF_ARROW -> {
                // Half an arrowhead, on the east side of the line.
                page.moveTo(0.0, -length - NORTH_ARROW_MM)
                page.lineTo(NORTH_HEAD_MM, -length)
                page.lineTo(0.0, -length)
                page.closePath()
                page.fill()
            }
        }
        page.restore()
        val rad = Math.toRadians(degrees)
        val reach = length + NORTH_TIP_ROOM_MM
        val tipX = x + Math.sin(rad) * reach
        val tipY = y - Math.cos(rad) * reach
        page.text(label, tipX, tipY, PdfFont.BOLD, NORTH_LABEL_PT, align = TextAlign.CENTER)
    }

    /** A five-pointed star centred on ([cx], [cy]), as a path to fill. */
    private fun star(
        page: PdfPage,
        cx: Double,
        cy: Double,
        r: Double,
    ) {
        for (i in 0 until STAR_POINTS * 2) {
            val radius = if (i % 2 == 0) r else r * STAR_INNER
            val a = Math.PI * i / STAR_POINTS
            val px = cx + Math.sin(a) * radius
            val py = cy - Math.cos(a) * radius
            if (i == 0) page.moveTo(px, py) else page.lineTo(px, py)
        }
        page.closePath()
    }

    /**
     * The angles the grid and magnetic norths are drawn at: a few degrees at least, a little more for a larger value,
     * the sides kept, and apart from each other when they lie on the same side.
     */
    private fun openAngles(
        grid: Double?,
        magnetic: Double?,
    ): Pair<Double?, Double?> {
        fun open(v: Double): Double =
            if (abs(v) < ZERO_ANGLE) 0.0 else sign(v) * min(OPEN_MAX, OPEN_MIN + OPEN_GAIN * abs(v))

        fun sideOf(v: Double?): Double = v?.let { sign(it) }?.takeIf { it != 0.0 } ?: 1.0
        var g = grid?.let(::open)
        var m = magnetic?.let(::open)
        // A north that matches true north is still drawn apart from it, on the other side of the third one.
        if (g == 0.0) g = -sideOf(m) * OPEN_MIN
        if (m == 0.0) m = -sideOf(g) * OPEN_MIN
        if (g != null && m != null && grid != null && magnetic != null && sign(g) == sign(m) &&
            abs(g - m) < OPEN_SEPARATION
        ) {
            if (abs(grid) <= abs(magnetic)) m = g + sign(g) * OPEN_SEPARATION else g = m + sign(m) * OPEN_SEPARATION
        }
        return g to m
    }

    private fun withMils(
        text: String,
        degrees: Double,
    ): String =
        if (style == MapStyle.NATO) {
            "$text · ${String.format(locale, texts.mils, PrintFormat.mils(degrees).toString())}"
        } else {
            text
        }

    private fun angle(degrees: Double): String = PrintFormat.declination(degrees, texts.east, texts.west)

    /**
     * The grid: its system, its squares and how the labels read. The graduated band follows the grid since ADR-087 and
     * needs no note of its own.
     */
    private fun drawGridBlock(
        page: PdfPage,
        box: Box,
        grid: GridPlan,
    ): Boolean {
        val inner = box.inset(BOX_PAD_MM)
        var y = inner.top
        val lines = ArrayList<Triple<String, PdfFont, Double>>()
        lines += Triple(texts.gridHeading, PdfFont.BOLD, NOTE_PT)
        val projected = grid.kind == GridKind.UTM || grid.kind == GridKind.MGRS || grid.kind == GridKind.LAMBERT93
        if (grid.lines.isEmpty() && grid.labels.isEmpty()) {
            lines += Triple(texts.noGrid, PdfFont.REGULAR, NOTE_PT)
        } else if (grid.zones.size < 2) {
            lines += Triple(grid.systemText, PdfFont.BOLD, NOTE_PT)
            val spacing = String.format(locale, if (projected) texts.gridSquares else texts.gridLines, grid.stepText)
            lines += Triple(spacing, PdfFont.REGULAR, NOTE_PT)
            if (grid.squares.isNotEmpty()) lines += Triple(squaresLine(grid), PdfFont.REGULAR, NOTE_PT)
        } else {
            // A sheet across zones, as the P24.0 preview: the zones and the squares of the grid on one line, then the
            // edges, the squares of each zone and the figures of the neighbour, smaller to keep within the box.
            val spacing =
                String.format(locale, texts.gridSquares, grid.stepText).replaceFirstChar {
                    it.lowercase(locale)
                }
            lines += Triple("${systemLine(grid)} — $spacing", PdfFont.BOLD, REFERENCE_PT)
            for (edge in grid.zoneEdges.mapNotNull(::edgeLine).distinct()) {
                lines += Triple(edge, PdfFont.REGULAR, REFERENCE_PT)
            }
            if (grid.squares.isNotEmpty()) lines += Triple(squaresLine(grid), PdfFont.REGULAR, REFERENCE_PT)
            if (grid.labels.any { it.neighbour }) lines += Triple(texts.neighbourGrid, PdfFont.REGULAR, REFERENCE_PT)
        }
        for ((text, font, size) in lines) {
            for (line in wrap(page, text, font, size, inner.width)) {
                if (y + lineHeight(size) > inner.bottom + EPSILON_MM) break
                y += lineHeight(size)
                page.text(line, inner.left, y - lineGap(size), font, size)
            }
        }
        // A sample of the labels: the big figures and what they are.
        val sample = grid.labels.firstOrNull { it.parts != null && it.side == FrameSide.TOP && !it.neighbour }
        if (sample != null && y + lineHeight(MAJOR_PT) < inner.bottom - lineHeight(NOTE_PT)) {
            val pieces = shortPieces(sample)
            val cap = capHeight(MAJOR_PT)
            y += lineHeight(MAJOR_PT)
            val baseline = y - lineGap(MAJOR_PT)
            drawPieces(page, pieces, inner.left, baseline, cap)
            val w = pieces.sumOf { page.textWidth(it.text, it.font, it.sizePt) }
            // MGRS: the kilometers inside the square, not the whole UTM value (ADR-087).
            val meaning =
                if (grid.kind == GridKind.MGRS) {
                    String.format(locale, texts.squareKilometers, sample.parts?.major ?: sample.text)
                } else {
                    "= ${String.format(locale, "%,d", Math.round(sample.value))} m · ${texts.principalDigits}"
                }
            page.text(
                fit(page, meaning, PdfFont.REGULAR, SMALL_TEXT_PT, inner.width - w - 1.0),
                inner.left + w + 1.0,
                baseline,
                PdfFont.REGULAR,
                SMALL_TEXT_PT,
            )
        }
        return true
    }

    /** `MGRS 31T · WGS84`; across zones, all of them: `MGRS 31T et 32T · WGS84`. */
    private fun systemLine(grid: GridPlan): String {
        if (grid.zones.size < 2) return grid.systemText
        val name = if (grid.kind == GridKind.MGRS) "MGRS" else "UTM"
        val zones = grid.zones.dropLast(1).joinToString(", ") + " ${texts.and} " + grid.zones.last()
        return "$name $zones · WGS84"
    }

    /** `Limite des zones 31 / 32 : méridien 6° E (trait épais)`. */
    private fun edgeLine(edge: ZoneEdge): String? {
        val (first, second) = edge.sides ?: return null
        val format = if (edge.meridian) texts.zoneEdgeMeridian else texts.zoneEdgeParallel
        return String.format(locale, format, first, second, edgeDegrees(edge))
    }

    /** `Carré de 100 km : GL`; across two zones, each named with its side: `Carrés de 100 km : 31T GL (ouest) · …`. */
    private fun squaresLine(grid: GridPlan): String {
        if (grid.zones.size < 2) return String.format(locale, texts.squares, grid.squares.joinToString(" · "))
        val groups = grid.squares.groupBy { it.substringBefore(' ') }
        val text =
            groups.entries.joinToString(" · ") { (zone, squares) ->
                val side =
                    when (grid.zoneSides[zone]) {
                        CompassSide.WEST -> texts.westSide
                        CompassSide.EAST -> texts.eastSide
                        CompassSide.SOUTH -> texts.southSide
                        CompassSide.NORTH -> texts.northSide
                        null -> null
                    }
                squares.joinToString(" · ") + (side?.let { " ($it)" } ?: "")
            }
        return String.format(locale, texts.squaresOfZones, text)
    }

    /**
     * NATO: how to give a grid reference at 100 m, with the centre of the sheet as the worked example, in the grid of
     * the sheet only (ADR-087): MGRS letters and figures, UTM or Lambert 93 meters, or the latitude and longitude.
     */
    private fun drawReferenceBlock(
        page: PdfPage,
        box: Box,
        frame: FrameProjection,
    ): Boolean {
        val inner = box.inset(BOX_PAD_MM)
        var y = inner.top
        val lines = ArrayList<Pair<String, PdfFont>>()
        lines += texts.referenceTitle to PdfFont.BOLD
        val lat = frame.centerLat
        val lon = frame.centerLon
        val example = GridPlanner.exampleReference(frame)
        val square = Mgrs.of(lat, lon)?.let { "${it.columnLetter}${it.rowLetter}" }
        val band = Utm.bandLetter(lat)
        when {
            template.grid == GridKind.MGRS && example != null && square != null -> {
                lines += String.format(locale, texts.referenceStep1, square) to PdfFont.REGULAR
                lines += texts.referenceStep2 to PdfFont.REGULAR
                lines += texts.referenceStep3 to PdfFont.REGULAR
                lines += String.format(locale, texts.referenceExample, example) to PdfFont.BOLD
            }

            template.grid == GridKind.UTM && band != null -> {
                val u = Utm.toUtm(lat, lon)
                lines += String.format(locale, texts.referenceUtmStep1, "${u.zone}$band") to PdfFont.REGULAR
                lines += texts.referenceMetersStep2 to PdfFont.REGULAR
                lines += texts.referenceMetersStep3 to PdfFont.REGULAR
                val meters = "${u.zone}$band ${meters(u.easting, texts.east)} ${meters(u.northing, texts.north)}"
                lines += String.format(locale, texts.referenceExample, meters) to PdfFont.BOLD
            }

            template.grid == GridKind.LAMBERT93 -> {
                val l = Lambert93.toLambert(lat, lon)
                lines += texts.referenceMetersStep2 to PdfFont.REGULAR
                lines += texts.referenceMetersStep3 to PdfFont.REGULAR
                val meters = "${meters(l.easting, texts.east)} ${meters(l.northing, texts.north)}"
                lines += String.format(locale, texts.referenceExample, meters) to PdfFont.BOLD
            }

            else -> {
                val decimal = template.grid == GridKind.WGS84_DECIMAL
                val centre =
                    if (decimal) {
                        degrees(lat, texts.north, texts.south) + " " + degrees(lon, texts.east, texts.west)
                    } else {
                        dms(lat, texts.north, texts.south) + " " + dms(lon, texts.east, texts.west)
                    }
                lines += String.format(locale, texts.referenceExample, centre) to PdfFont.BOLD
            }
        }
        for ((text, font) in lines) {
            val size = if (font == PdfFont.BOLD) NOTE_PT else REFERENCE_PT
            for (line in wrap(page, text, font, size, inner.width)) {
                if (y + lineHeight(size) > inner.bottom + EPSILON_MM) break
                y += lineHeight(size)
                page.text(line, inner.left, y - lineGap(size), font, size)
            }
        }
        return true
    }

    /** The credits of the sources (their licences require them) and what went wrong with the tiles. */
    private fun drawCredits(
        page: PdfPage,
        left: Double,
        baseline: Double,
        width: Double,
        missingTiles: Int,
    ) {
        val parts = ArrayList<String>()
        parts += content.attributions
        plan.warnings.filterIsInstance<PrintWarning.Oversampled>().forEach {
            val factor = String.format(locale, "%.1f", it.factor)
            parts += String.format(locale, texts.oversampled, it.sourceName, factor)
        }
        if (missingTiles > 0) parts += texts.missingTiles
        if (parts.isEmpty()) return
        val text = fit(page, parts.joinToString(" · "), PdfFont.REGULAR, CREDITS_PT, width - 2 * SheetLayout.PAD_MM)
        page.text(text, left + SheetLayout.PAD_MM, baseline, PdfFont.REGULAR, CREDITS_PT, MUTED)
    }

    private fun strokeBox(
        page: PdfPage,
        box: Box,
    ) {
        page.save()
        page.strokeColor(BOX_BORDER)
        page.lineWidth(BOX_LINE_MM)
        page.rect(box.left, box.top, box.width, box.bottom - box.top)
        page.stroke()
        page.restore()
    }

    private fun layerName(layer: PrintLayer): String {
        val percent = Math.round(layer.layer.opacity * PERCENT).toInt()
        return if (percent < PERCENT.toInt()) "${layer.source.name} ($percent %)" else layer.source.name
    }

    // ---- text helpers --------------------------------------------------------------------------------------------

    /** A rectangle of the page, in millimeters. */
    private data class Box(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = right - left

        fun inset(d: Double) = Box(left + d, top + d, right - d, bottom - d)

        fun overlaps(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    /** [text] with a white outline, readable over any map. */
    private fun haloText(
        page: PdfPage,
        text: String,
        x: Double,
        y: Double,
        font: PdfFont,
        sizePt: Double,
        color: PdfColor,
        align: TextAlign = TextAlign.CENTER,
    ) {
        for (i in 0 until HALO_STEPS) {
            val a = 2 * Math.PI * i / HALO_STEPS
            page.text(text, x + Math.cos(a) * HALO_MM, y + Math.sin(a) * HALO_MM, font, sizePt, PdfColor.WHITE, align)
        }
        page.text(text, x, y, font, sizePt, color, align)
    }

    /** `45°12'30"N`: an angle to the second, the way the corners of a sheet are written. */
    private fun dms(
        value: Double,
        positive: String,
        negative: String,
    ): String {
        val total = Math.round(abs(value) * SECONDS_PER_DEGREE)
        val d = total / SECONDS_PER_DEGREE.toLong()
        val m = (total % SECONDS_PER_DEGREE.toLong()) / SECONDS_PER_MINUTE
        val s = total % SECONDS_PER_MINUTE
        return String.format(Locale.ROOT, "%d°%02d′%02d″ %s", d, m, s, if (value >= 0) positive else negative)
    }

    /** `265 900 E`: a projected coordinate to 100 m, the thousands set apart as a French map writes them. */
    private fun meters(
        value: Double,
        axis: String,
    ): String {
        val digits = (Math.round(value / HECTOMETER) * HECTOMETER.toLong()).toString()
        val grouped = digits.reversed().chunked(THOUSANDS).joinToString(" ").reversed()
        return "$grouped $axis"
    }

    /** `45.50335° N`: an angle in decimal degrees to about a meter, for a sheet whose grid is in decimal degrees. */
    private fun degrees(
        value: Double,
        positive: String,
        negative: String,
    ): String = String.format(locale, "%.5f° %s", abs(value), if (value >= 0) positive else negative)

    /** `250 m`, `2,5 km`: a ground distance with the page's decimal separator. */
    private fun distance(meters: Double): String =
        ScaleBar.format(meters).replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)

    private fun lineHeight(sizePt: Double): Double = sizePt * LINE_SPACING / PdfPage.PT_PER_MM

    /** The space under the baseline in a line of [lineHeight]. */
    private fun lineGap(sizePt: Double): Double = lineHeight(sizePt) * BASELINE_SHARE

    private fun capHeight(sizePt: Double): Double = sizePt * CAP_HEIGHT / PdfPage.PT_PER_MM

    /** Greedy word wrap of [text] into lines no wider than [maxWidth] millimeters. */
    private fun wrap(
        page: PdfPage,
        text: String,
        font: PdfFont,
        sizePt: Double,
        maxWidth: Double,
    ): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (page.textWidth(candidate, font, sizePt) <= maxWidth || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
            while (page.textWidth(line, font, sizePt) > maxWidth && line.length > 1) {
                var cut = line.length - 1
                while (cut > 1 && page.textWidth(line.substring(0, cut), font, sizePt) > maxWidth) cut--
                lines += line.substring(0, cut)
                line = line.substring(cut)
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** The first [count] of [lines], the last one ending with an ellipsis when some are left out. */
    private fun clamp(
        page: PdfPage,
        lines: List<String>,
        count: Int,
        font: PdfFont,
        sizePt: Double,
        maxWidth: Double,
    ): List<String> {
        if (lines.size <= count) return lines
        val kept = lines.take(count).toMutableList()
        kept[count - 1] = fit(page, kept[count - 1] + " " + lines[count], font, sizePt, maxWidth)
        if (!kept[count - 1].endsWith("…")) kept[count - 1] = fit(page, kept[count - 1] + "…", font, sizePt, maxWidth)
        return kept
    }

    /** [text] cut to [maxWidth] millimeters with an ellipsis when it is longer. */
    private fun fit(
        page: PdfPage,
        text: String,
        font: PdfFont,
        sizePt: Double,
        maxWidth: Double,
    ): String {
        if (page.textWidth(text, font, sizePt) <= maxWidth) return text
        var end = text.length
        while (end > 1 && page.textWidth(text.substring(0, end) + "…", font, sizePt) > maxWidth) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private companion object {
        const val LINE_SPACING = 1.22
        const val BASELINE_SHARE = 0.22
        const val CAP_HEIGHT = 0.72
        const val PERCENT = 100.0
        const val EPSILON_MM = 0.01
        const val DESCENT_MM = 0.4
        const val SECONDS_PER_DEGREE = 3_600.0
        const val SECONDS_PER_MINUTE = 60L
        const val HECTOMETER = 100.0
        const val THOUSANDS = 3

        /** The grids whose sheet may carry the corners in degrees (ADR-087). */
        val DEGREE_GRIDS = setOf(GridKind.NONE, GridKind.WGS84, GridKind.WGS84_DECIMAL)
        const val CM_PER_M = 100.0

        const val GRID_LINE_MM = 0.13
        const val GRID_MAJOR_MM = 0.35
        const val GRID_ALPHA = 0.85
        const val SQUARE_PT = 10.0

        // The sheet across grid zones (P24.4, as the P24.0 preview): the edge thick and dark, its meridian at the top,
        // the figures of the neighbouring zone in blue italics in the outer row of the strip.
        const val ZONE_EDGE_MM = 0.6
        const val EDGE_TAG_PT = 7.0
        const val EDGE_TAG_INSET_MM = 3.0
        const val EDGE_TAG_LIFT_MM = 1.0
        const val NEIGHBOUR_PT = 6.0
        const val NEIGHBOUR_ROW_GAP_MM = 0.6

        /** From the paper edge, what most printers leave white: an outer row of labels stops there. */
        const val PRINTER_REACH_MM = 4.0

        const val INNER_LINE_MM = 0.15
        const val OUTER_LINE_MM = 0.45

        const val TICK_MM = 1.0
        const val TICK_LINE_MM = 0.15
        const val LABEL_GAP_MM = 0.5
        const val LABEL_PAD_MM = 0.25
        const val STRIP_PAD_MM = 0.3
        const val LABEL_OVERHANG_MM = 2.0
        const val MAJOR_PT = 7.5
        const val SMALL_PT = 5.0
        const val GRATICULE_PT = 6.0

        const val CORNER_PT = 5.0
        const val CORNER_TOP_MM = 4.2
        const val CORNER_BOTTOM_MM = 5.4
        const val CORNER_SIDE_MM = 5.6

        const val TRACK_MM = 0.6
        const val OVERVIEW_TRACK_MM = 0.5
        const val TRACK_ALPHA = 0.9
        const val TRACK_MIN_STEP_MM = 0.05
        const val WAYPOINT_RADIUS_MM = 2.0
        const val WAYPOINT_OUTLINE_MM = 0.3
        const val GLYPH_MM = 2.8
        const val WAYPOINT_PT = 6.5
        const val WAYPOINT_NAME_GAP_MM = 0.6
        const val WAYPOINT_NAME_MAX_MM = 45.0
        const val HALO_MM = 0.25
        const val HALO_STEPS = 8

        const val FOOTPRINT_LINE_MM = 0.5
        const val FOOTPRINT_NUMBER_PT = 14.0

        const val BOX_PAD_MM = 1.6
        const val BOX_LINE_MM = 0.2
        const val CREDITS_BASELINE_MM = 1.1
        const val CREDITS_PT = 5.0

        const val TITLE_PT = 11.0
        const val TITLE_LINES = 2
        const val SUBTITLE_PT = 7.0
        const val TEXT_PT = 6.0
        const val SMALL_TEXT_PT = 4.8
        const val NOTE_PT = 5.4
        const val REFERENCE_PT = 4.9

        const val LEGEND_GAP_MM = 0.6
        const val LEGEND_SAMPLE_MM = 5.0
        const val LEGEND_DISC_MM = 1.1
        const val LEGEND_TEXT_GAP_MM = 1.2
        const val TWO_COLUMNS_MM = 70.0
        const val COLUMN_GAP_MM = 3.0

        const val SCALE_PT = 12.0
        const val SCALE_TEXT_GAP_MM = 0.6
        const val SCALE_BAR_OVERHANG_MM = 7.0
        const val SCALE_LABEL_ROW_MM = 3.2
        const val SCALE_BAR_GAP_MM = 0.6
        const val SCALE_BAR_HEIGHT_MM = 1.6
        const val SCALE_BAR_LINE_MM = 0.18
        const val MIN_EDGE_NOTE_PERCENT = 0.05

        const val NORTH_DIAGRAM_MM = 18.0
        const val NORTH_TEXT_GAP_MM = 1.0
        const val NORTH_BASE_LIFT_MM = 0.6
        const val NORTH_LABEL_ROOM_MM = 4.2
        const val NORTH_TIP_ROOM_MM = 2.6
        const val NORTH_LINE_MM = 0.25
        const val NORTH_HEAD_MM = 0.9
        const val NORTH_ARROW_MM = 2.2
        const val NORTH_LABEL_PT = 5.0
        const val GRID_NORTH_SHARE = 0.7
        const val MAGNETIC_SHARE = 0.86
        const val STAR_RADIUS_MM = 1.2
        const val STAR_INNER = 0.45
        const val STAR_POINTS = 5
        const val ZERO_ANGLE = 0.01
        const val OPEN_MIN = 6.0
        const val OPEN_GAIN = 2.5
        const val OPEN_MAX = 20.0
        const val OPEN_SEPARATION = 7.0

        val GRID_BLUE = PdfColor(20, 50, 140)
        val ZONE_EDGE_COLOR = PdfColor(28, 36, 41)
        val NEIGHBOUR_BLUE = PdfColor(24, 95, 165)
        val BOX_BORDER = PdfColor(90, 90, 90)
        val MUTED = PdfColor(70, 70, 70)
        val FOOTPRINT_COLOR = PdfColor(200, 30, 30)
        const val WAYPOINT_SAMPLE_ARGB = 0xFFC81E1E.toInt()
    }
}
