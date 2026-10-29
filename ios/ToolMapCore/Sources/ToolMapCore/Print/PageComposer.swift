// Port of core/src/main/kotlin/fr/toolmap/core/print/PageComposer.kt.

import Foundation

/// Draws everything of a page but the map tiles, in vector form (docs/specs/print-pdf.md §6). Over the map: grid,
/// 100 km square letters, tracks and waypoints. Around it: the double frame, its band graduated at the step of the
/// grid, the grid labels with their principal digits and, for a grid in degrees, the corner coordinates. Under it: the
/// footer of an IGN TOP 25 sheet: title and legend, scale, the three norths, the grid. Positions are millimeters from
/// the page's top-left corner; the map starts at (`PageSetup.frameLeftMm`, `PageSetup.frameTopMm`), where a point of
/// `FrameProjection` lands where the maths put it.
public struct PageComposer {
    private let plan: PrintPlan
    private let content: PrintContent
    private let template: PrintTemplate
    private let setup: PageSetup
    private let dressing: Dressing
    private let texts: PrintTexts
    private let locale: Locale
    private let frameW: Double
    private let frameH: Double
    private let gridColor = Style.gridBlue
    private let ownRow: LabelRow

    public init(_ plan: PrintPlan, _ content: PrintContent) {
        self.plan = plan
        self.content = content
        template = plan.template
        setup = plan.template.page
        dressing = plan.template.dressing
        texts = content.texts
        locale = content.locale
        frameW = plan.template.page.frameWidthMm
        frameH = plan.template.page.frameHeightMm
        ownRow = LabelRow(
            inset: PageSetup.graduationMm + Style.tickMm + Style.labelGapMm,
            color: Style.gridBlue,
            outer: false
        )
    }

    /// Draws the overlay of `pp` on `page`, whose map is already in place. `pageCount` is the number of map pages of
    /// the job and `missingTiles` how many tiles could not be read. Throws when a waypoint icon cannot be written.
    public func drawOverlay(_ page: PdfPage, _ pp: PrintPagePlan, _ pageCount: Int, _ missingTiles: Int) throws {
        let overview = pp.kind == .overview
        let grid = overview
            ? GridPlan.empty()
            : GridPlanner.plan(pp.frame, template.grid, step: template.gridStep, locale: locale)
        let graduation = overview
            ? Graduation.empty
            : GridPlanner.graduation(pp.frame, template.grid, step: template.gridStep, locale: locale)
        page.save()
        page.translate(setup.frameLeftMm, setup.frameTopMm)
        page.save()
        page.clipRect(0.0, 0.0, frameW, frameH)
        drawGridLines(page, grid)
        drawZoneEdges(page, grid)
        drawSquareTags(page, grid)
        if overview { drawFootprints(page, pp.frame, pp.footprints) }
        let tracks = dressing.tracks ? drawTracks(page, pp.frame, overview) : []
        var waypoints = 0
        if dressing.waypoints {
            waypoints = try drawWaypoints(page, pp.frame, !overview && dressing.waypointNames)
        }
        page.restore()
        drawNeatline(page, graduation)
        var taken: [Box] = []
        // The corners are in degrees: only on a sheet whose grid is in degrees, or without a grid (ADR-087).
        if dressing.cornerCoordinates, !overview, Style.degreeGrids.contains(template.grid) {
            drawCorners(page, pp.frame, &taken)
        }
        drawGridLabels(page, grid, &taken)
        page.restore()
        drawFooter(page, pp, pageCount, missingTiles, grid, tracks, waypoints)
    }

    // ---- over the map ------------------------------------------------------------------------------------------------

    private func drawGridLines(_ page: PdfPage, _ grid: GridPlan) {
        if grid.lines.isEmpty { return }
        page.save()
        page.strokeColor(gridColor)
        page.alpha(Style.gridAlpha)
        for major in [false, true] {
            let lines = grid.lines.filter { $0.major == major }
            if lines.isEmpty { continue }
            page.lineWidth(major ? Style.gridMajorMm : Style.gridLineMm)
            for line in lines {
                page.polyline(line.points.map(\.xMm), line.points.map(\.yMm))
            }
        }
        page.restore()
    }

    /// The edges of the grid zones in a thick dark line, each with its meridian (`6° E`) at the top or its parallel
    /// (`48° N`) at the left, as in the P24.0 preview (P24.4).
    private func drawZoneEdges(_ page: PdfPage, _ grid: GridPlan) {
        if grid.zoneEdges.isEmpty { return }
        page.save()
        page.strokeColor(Style.zoneEdgeColor)
        page.lineWidth(Style.zoneEdgeMm)
        for edge in grid.zoneEdges {
            page.polyline(edge.line.points.map(\.xMm), edge.line.points.map(\.yMm))
        }
        page.restore()
        let cap = capHeight(Style.edgeTagPt)
        for edge in grid.zoneEdges {
            let points = edge.line.points
            guard let start = points.min(by: { edge.meridian ? $0.yMm < $1.yMm : $0.xMm < $1.xMm }) else { continue }
            let name = edgeDegrees(edge)
            if edge.meridian {
                haloText(
                    page,
                    name,
                    start.xMm,
                    start.yMm + Style.edgeTagInsetMm + cap,
                    .regular,
                    Style.edgeTagPt,
                    Style.zoneEdgeColor
                )
            } else {
                haloText(
                    page,
                    name,
                    start.xMm + Style.edgeTagInsetMm,
                    start.yMm - Style.edgeTagLiftMm,
                    .regular,
                    Style.edgeTagPt,
                    Style.zoneEdgeColor,
                    .left
                )
            }
        }
    }

    /// `6° E`, `48° N`: the meridian or the parallel of a zone edge.
    private func edgeDegrees(_ edge: ZoneEdge) -> String {
        let degrees = kotlinRound(abs(edge.degrees))
        let side: String
        if edge.meridian {
            side = edge.degrees < 0 ? texts.west : texts.east
        } else {
            side = edge.degrees < 0 ? texts.south : texts.north
        }
        return "\(degrees)° \(side)"
    }

    /// The letters of each 100 km square, in the middle of its part of the map; on a sheet across two zones, with the
    /// zone and beside the zone edge.
    private func drawSquareTags(_ page: PdfPage, _ grid: GridPlan) {
        for tag in grid.squareTags {
            let align: TextAlign
            switch tag.align {
            case .center: align = .center
            case .end: align = .right
            case .start: align = .left
            }
            let y = tag.yMm + capHeight(Style.squarePt) / 2
            haloText(page, tag.letters, tag.xMm, y, .bold, Style.squarePt, gridColor, align)
        }
    }

    private func drawTracks(_ page: PdfPage, _ frame: FrameProjection, _ thin: Bool) -> [PrintTrack] {
        var drawn: [PrintTrack] = []
        for track in content.tracks {
            var any = false
            for segment in track.segments {
                if segment.count < 2 { continue }
                var xs: [Double] = []
                var ys: [Double] = []
                xs.reserveCapacity(segment.count)
                ys.reserveCapacity(segment.count)
                var minX = Double.greatestFiniteMagnitude
                var maxX = -Double.greatestFiniteMagnitude
                var minY = Double.greatestFiniteMagnitude
                var maxY = -Double.greatestFiniteMagnitude
                for point in segment {
                    let p = frame.toFrame(point.lon, point.lat)
                    xs.append(p.xMm)
                    ys.append(p.yMm)
                    minX = Swift.min(minX, p.xMm)
                    maxX = Swift.max(maxX, p.xMm)
                    minY = Swift.min(minY, p.yMm)
                    maxY = Swift.max(maxY, p.yMm)
                }
                if maxX < 0 || minX > frameW || maxY < 0 || minY > frameH { continue }
                if !any {
                    page.save()
                    page.strokeColor(PdfColor.fromArgb(track.colorArgb))
                    page.lineWidth(thin ? Style.overviewTrackMm : Style.trackMm)
                    page.lineStyle(.round, .round)
                    page.alpha(Style.trackAlpha)
                }
                any = true
                page.polyline(xs, ys, minStepMm: Style.trackMinStepMm)
            }
            if any {
                page.restore()
                drawn.append(track)
            }
        }
        return drawn
    }

    /// A disc of the waypoint's colour with its white icon, and its name outlined in white (P17, Q16).
    private func drawWaypoints(_ page: PdfPage, _ frame: FrameProjection, _ withNames: Bool) throws -> Int {
        var count = 0
        for waypoint in content.waypoints {
            let p = frame.toFrame(waypoint.lon, waypoint.lat)
            let r = Style.waypointRadiusMm
            if p.xMm < -r || p.xMm > frameW + r || p.yMm < -r || p.yMm > frameH + r { continue }
            count += 1
            page.save()
            page.fillColor(PdfColor.fromArgb(waypoint.colorArgb))
            page.strokeColor(.white)
            page.lineWidth(Style.waypointOutlineMm)
            page.circle(p.xMm, p.yMm, r)
            page.fillAndStroke()
            page.restore()
            if let iconId = waypoint.iconId, let glyph = content.glyphs[iconId] {
                let g = Style.glyphMm
                try page.image(glyph, p.xMm - g / 2, p.yMm - g / 2, g, g)
            }
            guard withNames, let rawName = waypoint.name else { continue }
            let name = kotlinTrim(rawName)
            if name.isEmpty { continue }
            let text = fit(page, name, .bold, Style.waypointPt, Style.waypointNameMaxMm)
            haloText(
                page,
                text,
                p.xMm + r + Style.waypointNameGapMm,
                p.yMm + capHeight(Style.waypointPt) / 2,
                .bold,
                Style.waypointPt,
                .black,
                .left
            )
        }
        return count
    }

    /// The outlines of atlas pages on the assembly page, with their numbers.
    private func drawFootprints(_ page: PdfPage, _ frame: FrameProjection, _ footprints: [PageFootprint]) {
        for footprint in footprints {
            let points = footprint.corners.map { frame.toFrame($0.lon, $0.lat) }
            guard let first = points.first else { continue }
            page.save()
            page.strokeColor(Style.footprintColor)
            page.lineWidth(Style.footprintLineMm)
            page.lineStyle(.butt, .miter)
            page.moveTo(first.xMm, first.yMm)
            for point in points.dropFirst() {
                page.lineTo(point.xMm, point.yMm)
            }
            page.closePath()
            page.stroke()
            page.restore()
            let cx = points.reduce(0.0) { $0 + $1.xMm } / Double(points.count)
            let cy = points.reduce(0.0) { $0 + $1.yMm } / Double(points.count)
            haloText(
                page,
                String(footprint.number),
                cx,
                cy + capHeight(Style.footprintNumberPt) / 2,
                .bold,
                Style.footprintNumberPt,
                Style.footprintColor
            )
        }
    }

    // ---- frame and margins -------------------------------------------------------------------------------------------

    /// The double frame: a thin inner line, the graduated band, a thicker outer line (P17, Q14).
    private func drawNeatline(_ page: PdfPage, _ graduation: Graduation) {
        let g = PageSetup.graduationMm
        let black = graduation.bars.filter(\.black)
        if !black.isEmpty {
            page.save()
            page.fillColor(.black)
            for bar in black {
                let length = bar.endMm - bar.startMm
                switch bar.side {
                case .top: page.rect(bar.startMm, -g, length, g)
                case .bottom: page.rect(bar.startMm, frameH, length, g)
                case .left: page.rect(-g, bar.startMm, g, length)
                case .right: page.rect(frameW, bar.startMm, g, length)
                }
            }
            page.fill()
            page.restore()
        }
        page.save()
        page.strokeColor(.black)
        page.lineStyle(.butt, .miter)
        page.lineWidth(Style.innerLineMm)
        page.rect(0.0, 0.0, frameW, frameH)
        page.stroke()
        page.lineWidth(Style.outerLineMm)
        page.rect(-g, -g, frameW + 2 * g, frameH + 2 * g)
        page.stroke()
        page.restore()
    }

    /// The latitude and longitude of the four corners, in degrees, minutes and seconds (in decimal degrees on a sheet
    /// whose grid is): the longitude in the outer row of the strip above or below, the latitude up the strip on the
    /// side.
    private func drawCorners(_ page: PdfPage, _ frame: FrameProjection, _ taken: inout [Box]) {
        let g = PageSetup.graduationMm
        let cap = capHeight(Style.cornerPt)
        let decimal = template.grid == .wgs84Decimal
        for cy in [0.0, frameH] {
            for cx in [0.0, frameW] {
                let p = frame.toLonLat(cx, cy)
                let lon = decimal ? degrees(p.lon, texts.east, texts.west) : dms(p.lon, texts.east, texts.west)
                let lat = decimal ? degrees(p.lat, texts.north, texts.south) : dms(p.lat, texts.north, texts.south)
                let left = cx == 0.0
                let top = cy == 0.0
                // Longitude, horizontal, starting at the outer corner of the frame.
                let lonWidth = page.textWidth(lon, .regular, Style.cornerPt)
                let baseline = top ? -g - Style.cornerTopMm : frameH + g + Style.cornerBottomMm
                let x = left ? -g : frameW + g
                page.text(lon, x, baseline, .regular, Style.cornerPt, .black, left ? .left : .right)
                let lonLeft = left ? x : x - lonWidth
                taken.append(Box(left: lonLeft, top: baseline - cap, right: lonLeft + lonWidth, bottom: baseline + Style.descentMm))
                // Latitude, reading upwards, from the corner along the side.
                let latWidth = page.textWidth(lat, .regular, Style.cornerPt)
                let bx = left ? -g - Style.cornerSideMm : frameW + g + Style.cornerSideMm + cap
                let by = top ? -g : frameH + g
                page.save()
                page.translate(bx, by)
                page.rotate(-90.0)
                page.text(lat, 0.0, 0.0, .regular, Style.cornerPt, .black, top ? .right : .left)
                page.restore()
                let y0 = top ? by : by - latWidth
                taken.append(Box(left: bx - cap, top: y0, right: bx + Style.descentMm, bottom: y0 + latWidth))
            }
        }
    }

    /// The value of each grid line where it leaves the frame, with a tick across the strip. Projected grids write the
    /// kilometers inside the 100 km square in big figures and the rest small; the first line of each side (west or
    /// south) is written in full with its unit, the way a TOP 25 sheet does (P17, Q14).
    private func drawGridLabels(_ page: PdfPage, _ grid: GridPlan, _ taken: inout [Box]) {
        if grid.labels.isEmpty { return }
        let own = grid.labels.filter { !$0.neighbour }
        drawTicks(page, own, gridColor)
        // How far the labels of each side reach from the frame, for the row of the neighbouring zone beyond them.
        var reach: [FrameSide: Double] = [:]
        func extend(_ side: FrameSide, _ box: Box) {
            let value = outward(box, side)
            reach[side] = Swift.max(reach[side] ?? value, value)
        }
        for side in FrameSide.allCases {
            let labels = own.filter { $0.side == side }
            // West to east along the top and bottom, south to north along the sides.
            let ordered: [GridLabel]
            if side == .left || side == .right {
                ordered = labels.sorted { $0.positionMm > $1.positionMm }
            } else {
                ordered = labels.sorted { $0.positionMm < $1.positionMm }
            }
            var fullDone = false
            for label in ordered {
                if !fullDone, label.parts != nil {
                    let full = fullPieces(label, side)
                    if let box = placeLabel(page, label, full, &taken, ownRow) {
                        extend(side, box)
                        fullDone = true
                        continue
                    }
                }
                if let box = placeLabel(page, label, shortPieces(label), &taken, ownRow) {
                    extend(side, box)
                }
            }
        }
        // The grid of the neighbouring zone carried on: its two figures in blue italics, in the outer row (P24.4).
        let neighbours = grid.labels.filter(\.neighbour)
        if neighbours.isEmpty { return }
        drawTicks(page, neighbours, Style.neighbourBlue)
        for label in neighbours {
            let text = label.parts?.major ?? label.text
            let inset = (reach[label.side] ?? 0.0) + Style.neighbourRowGapMm
            _ = placeLabel(
                page,
                label,
                [Piece(text: text, font: .italic, sizePt: Style.neighbourPt)],
                &taken,
                LabelRow(inset: Swift.max(inset, ownRow.inset), color: Style.neighbourBlue, outer: true)
            )
        }
    }

    /// How far from the frame a label of `row` may reach on `side`: the strip; an outer row also takes the white
    /// margin on the top and the sides as far as a printer reaches (the footer follows the bottom strip).
    private func stripReach(_ side: FrameSide, _ row: LabelRow) -> Double {
        let strip = PageSetup.graduationMm + PageSetup.labelStripMm
        if !row.outer || side == .bottom { return strip }
        return strip + Swift.max(setup.marginMm - Style.printerReachMm, 0.0)
    }

    /// How far `box` reaches out from the frame on `side`.
    private func outward(_ box: Box, _ side: FrameSide) -> Double {
        switch side {
        case .top: -box.top
        case .bottom: box.bottom - frameH
        case .left: -box.left
        case .right: box.right - frameW
        }
    }

    private func drawTicks(_ page: PdfPage, _ labels: [GridLabel], _ color: PdfColor) {
        if labels.isEmpty { return }
        let g = PageSetup.graduationMm
        let tick = Style.tickMm
        page.save()
        page.strokeColor(color)
        page.lineWidth(Style.tickLineMm)
        for label in labels {
            let p = label.positionMm
            switch label.side {
            case .top:
                page.moveTo(p, -g)
                page.lineTo(p, -g - tick)
            case .bottom:
                page.moveTo(p, frameH + g)
                page.lineTo(p, frameH + g + tick)
            case .left:
                page.moveTo(-g, p)
                page.lineTo(-g - tick, p)
            case .right:
                page.moveTo(frameW + g, p)
                page.lineTo(frameW + g + tick, p)
            }
        }
        page.stroke()
        page.restore()
    }

    /// A run of text in one font and size; `raised` lifts it to align its top with the big figures.
    private struct Piece {
        let text: String
        let font: PdfFont
        let sizePt: Double
        var raised = false
    }

    private func shortPieces(_ label: GridLabel) -> [Piece] {
        guard let parts = label.parts else {
            return [Piece(text: label.text, font: .regular, sizePt: Style.graticulePt)]
        }
        var out: [Piece] = []
        if !parts.prefix.isEmpty { out.append(Piece(text: parts.prefix, font: .regular, sizePt: Style.smallPt, raised: true)) }
        out.append(Piece(text: parts.major, font: .bold, sizePt: Style.majorPt))
        if !parts.rest.isEmpty { out.append(Piece(text: parts.rest, font: .regular, sizePt: Style.smallPt, raised: true)) }
        return out
    }

    /// `⁷21⁰⁰⁰ m E`: the whole value with its unit.
    private func fullPieces(_ label: GridLabel, _ side: FrameSide) -> [Piece] {
        guard let parts = label.parts else { return shortPieces(label) }
        // MGRS writes two figures everywhere, never the whole UTM value (ADR-087).
        if template.grid == .mgrs { return shortPieces(label) }
        let axis = side == .top || side == .bottom ? texts.east : texts.north
        var out: [Piece] = []
        if !parts.prefix.isEmpty { out.append(Piece(text: parts.prefix, font: .regular, sizePt: Style.smallPt, raised: true)) }
        out.append(Piece(text: parts.major, font: .bold, sizePt: Style.majorPt))
        out.append(
            Piece(text: parts.rest.isEmpty ? "000" : parts.rest, font: .regular, sizePt: Style.smallPt, raised: true)
        )
        out.append(Piece(text: " m \(axis)", font: .regular, sizePt: Style.smallPt))
        return out
    }

    /// A row of labels in the strip around the frame: `inset` from the frame to the near side of the figures, in
    /// `color`; `outer` rows (the neighbouring zone, P24.4) must stay inside the strip.
    private struct LabelRow {
        let inset: Double
        let color: PdfColor
        let outer: Bool
    }

    /// Writes `pieces` at the place of `label` if the room is free; horizontal when it fits the strip, along the side
    /// otherwise. Returns where it was written, or nil.
    private func placeLabel(
        _ page: PdfPage,
        _ label: GridLabel,
        _ pieces: [Piece],
        _ taken: inout [Box],
        _ row: LabelRow
    ) -> Box? {
        let g = PageSetup.graduationMm
        let width = pieces.reduce(0.0) { $0 + page.textWidth($1.text, $1.font, $1.sizePt) }
        let cap = pieces.map { capHeight($0.sizePt) }.max() ?? 0.0
        let inset = row.inset
        let pos = label.positionMm
        let room = stripReach(label.side, row) - inset - Style.stripPadMm
        let sideways = label.side == .left || label.side == .right
        let rotated = sideways && width > room
        let box: Box
        var left = 0.0
        var baseline = 0.0
        var baselineX = 0.0
        if !rotated {
            switch label.side {
            case .top:
                left = pos - width / 2
                baseline = -inset
            case .bottom:
                left = pos - width / 2
                baseline = frameH + inset + cap
            case .left:
                left = -inset - width
                baseline = pos + cap / 2
            case .right:
                left = frameW + inset
                baseline = pos + cap / 2
            }
            box = Box(
                left: left - Style.labelPadMm,
                top: baseline - cap - Style.labelPadMm,
                right: left + width + Style.labelPadMm,
                bottom: baseline + Style.descentMm
            )
        } else {
            // Reading upwards; the tops of the figures face away from the frame on the left, towards it on the right.
            baselineX = label.side == .left ? -inset : frameW + inset + cap
            box = Box(
                left: baselineX - cap - Style.labelPadMm,
                top: pos - width / 2 - Style.labelPadMm,
                right: baselineX + Style.descentMm,
                bottom: pos + width / 2 + Style.labelPadMm
            )
        }
        let reach = g + Style.labelOverhangMm
        let inside = sideways
            ? box.top >= -reach && box.bottom <= frameH + reach
            : box.left >= -reach && box.right <= frameW + reach
        let inStrip = !row.outer || outward(box, label.side) <= stripReach(label.side, row)
        if !inside || !inStrip || taken.contains(where: { $0.overlaps(box) }) { return nil }
        taken.append(box)
        if !rotated {
            drawPieces(page, pieces, left, baseline, cap, row.color)
        } else {
            page.save()
            page.translate(baselineX, pos)
            page.rotate(-90.0)
            drawPieces(page, pieces, -width / 2, 0.0, cap, row.color)
            page.restore()
        }
        return box
    }

    private func drawPieces(
        _ page: PdfPage,
        _ pieces: [Piece],
        _ left: Double,
        _ baseline: Double,
        _ cap: Double,
        _ color: PdfColor
    ) {
        var x = left
        for piece in pieces {
            let lift = piece.raised ? cap - capHeight(piece.sizePt) : 0.0
            page.text(piece.text, x, baseline - lift, piece.font, piece.sizePt, color)
            x += page.textWidth(piece.text, piece.font, piece.sizePt)
        }
    }

    // ---- footer ------------------------------------------------------------------------------------------------------

    private func drawFooter(
        _ page: PdfPage,
        _ pp: PrintPagePlan,
        _ pageCount: Int,
        _ missingTiles: Int,
        _ grid: GridPlan,
        _ tracks: [PrintTrack],
        _ waypoints: Int
    ) {
        let ox = setup.marginMm
        let oy = setup.footerTopMm
        let width = setup.widthMm - 2 * setup.marginMm
        for block in SheetLayout.place(width) {
            let box = Box(
                left: ox + block.xMm,
                top: oy + block.yMm,
                right: ox + block.xMm + block.widthMm,
                bottom: oy + block.yMm + block.heightMm
            )
            let drawn: Bool
            switch block.block {
            case .title: drawn = drawTitleBlock(page, box, pp, pageCount, tracks, waypoints)
            case .scale: drawn = drawScaleBlock(page, box, pp.frame.scale)
            case .north: drawn = drawNorthBlock(page, box, pp)
            case .grid: drawn = drawGridBlock(page, box, grid)
            }
            if drawn { strokeBox(page, box) }
        }
        drawCredits(page, ox, oy + setup.footerHeightMm - Style.creditsBaselineMm, width, missingTiles)
    }

    /// Title, subtitle, map and legend, and the line of the date: the cartouche of the sheet.
    private func drawTitleBlock(
        _ page: PdfPage,
        _ box: Box,
        _ pp: PrintPagePlan,
        _ pageCount: Int,
        _ tracks: [PrintTrack],
        _ waypoints: Int
    ) -> Bool {
        if !dressing.cartouche && !dressing.legend { return false }
        let inner = box.inset(Style.boxPadMm)
        var y = inner.top
        let overview = pp.kind == .overview
        if dressing.cartouche {
            let title = overview
                ? PrintLocaleFormat.format(locale, texts.overviewTitle, pageCount)
                : kotlinTrim(template.title)
            if !title.isEmpty {
                let lines = wrap(page, title, .bold, Style.titlePt, inner.width)
                for line in clamp(page, lines, Style.titleLines, .bold, Style.titlePt, inner.width) {
                    y += lineHeight(Style.titlePt)
                    page.text(line, inner.left, y - lineGap(Style.titlePt), .bold, Style.titlePt)
                }
            }
            let subtitle = kotlinTrim(template.subtitle)
            if !subtitle.isEmpty, !overview {
                y += lineHeight(Style.subtitlePt)
                page.text(
                    fit(page, subtitle, .regular, Style.subtitlePt, inner.width),
                    inner.left,
                    y - lineGap(Style.subtitlePt),
                    .regular,
                    Style.subtitlePt
                )
            }
        }
        let bottomLine = dressing.cartouche ? inner.bottom - lineHeight(Style.smallTextPt) : inner.bottom
        if dressing.legend {
            var layers = pp.layers.layers.map { layerName($0) }
            if layers.isEmpty { layers = content.layerNames }
            if !layers.isEmpty {
                let source = PrintLocaleFormat.format(locale, texts.mapSource, layers.joined(separator: " + "))
                let wrapped = wrap(page, source, .regular, Style.textPt, inner.width)
                for line in clamp(page, wrapped, 2, .regular, Style.textPt, inner.width) {
                    if y + lineHeight(Style.textPt) > bottomLine { break }
                    y += lineHeight(Style.textPt)
                    page.text(line, inner.left, y - lineGap(Style.textPt), .regular, Style.textPt)
                }
            }
            drawLegend(
                page,
                Box(left: inner.left, top: y + Style.legendGapMm, right: inner.right, bottom: bottomLine),
                tracks,
                waypoints
            )
        }
        if dressing.cartouche {
            var parts: [String] = []
            if PrintLocaleFormat.isNotBlank(content.dateText) {
                parts.append(PrintLocaleFormat.format(locale, texts.printedOn, content.dateText))
            }
            if !overview, pageCount > 1 {
                parts.append(PrintLocaleFormat.format(locale, texts.page, pp.number, pageCount))
            }
            parts.append(texts.producedWith)
            let line = fit(page, parts.joined(separator: " · "), .regular, Style.smallTextPt, inner.width)
            page.text(line, inner.left, inner.bottom - lineGap(Style.smallTextPt), .regular, Style.smallTextPt, Style.muted)
        }
        return true
    }

    private enum LegendSample {
        case line
        case disc
        case none
    }

    private struct LegendRow {
        let text: String
        let sample: LegendSample
        let colorArgb: Int
    }

    /// The tracks drawn with their colour and the waypoints' count, in one or two columns.
    private func drawLegend(_ page: PdfPage, _ area: Box, _ tracks: [PrintTrack], _ waypoints: Int) {
        var rows: [LegendRow] = tracks.map { LegendRow(text: $0.name, sample: .line, colorArgb: $0.colorArgb) }
        if waypoints > 0 {
            let color = content.waypoints.first?.colorArgb ?? Style.waypointSampleArgb
            rows.append(
                LegendRow(text: PrintLocaleFormat.format(locale, texts.waypoints, waypoints), sample: .disc, colorArgb: color)
            )
        }
        if rows.isEmpty { return }
        let rowHeight = lineHeight(Style.textPt)
        let perColumn = Swift.max(0, kotlinToInt((area.bottom - area.top) / rowHeight))
        if perColumn == 0 { return }
        let columns = rows.count > perColumn && area.width >= Style.twoColumnsMm ? 2 : 1
        let columnWidth = (area.width - Double(columns - 1) * Style.columnGapMm) / Double(columns)
        let capacity = perColumn * columns
        let shown: [LegendRow]
        if rows.count <= capacity {
            shown = rows
        } else {
            shown = Array(rows.prefix(capacity - 1))
                + [LegendRow(text: "+ \(rows.count - capacity + 1)", sample: LegendSample.none, colorArgb: 0)]
        }
        for (i, row) in shown.enumerated() {
            let column = i / perColumn
            let x = area.left + Double(column) * (columnWidth + Style.columnGapMm)
            let baseline = area.top + Double(i % perColumn + 1) * rowHeight - lineGap(Style.textPt)
            let middle = baseline - capHeight(Style.textPt) / 2
            page.save()
            switch row.sample {
            case .line:
                page.strokeColor(PdfColor.fromArgb(row.colorArgb))
                page.lineWidth(Style.trackMm)
                page.lineStyle(.round, .round)
                page.moveTo(x, middle)
                page.lineTo(x + Style.legendSampleMm, middle)
                page.stroke()
            case .disc:
                page.fillColor(PdfColor.fromArgb(row.colorArgb))
                page.strokeColor(.white)
                page.lineWidth(Style.waypointOutlineMm)
                page.circle(x + Style.legendSampleMm / 2, middle, Style.legendDiscMm)
                page.fillAndStroke()
            case .none:
                break
            }
            page.restore()
            let textX = x + Style.legendSampleMm + Style.legendTextGapMm
            let text = fit(page, row.text, .regular, Style.textPt, columnWidth - (textX - x))
            page.text(text, textX, baseline, .regular, Style.textPt)
        }
    }

    /// `1:25 000`, the graphic scale and what a centimeter is on the ground.
    private func drawScaleBlock(_ page: PdfPage, _ box: Box, _ scale: Double) -> Bool {
        if !dressing.scaleText && !dressing.scaleBar { return false }
        let inner = box.inset(Style.boxPadMm)
        let middle = (inner.left + inner.right) / 2
        var y = inner.top
        if dressing.scaleText {
            y += capHeight(Style.scalePt) + Style.scaleTextGapMm
            page.text(PrintFormat.scale(scale, texts), middle, y, .bold, Style.scalePt, .black, .center)
        }
        if dressing.scaleBar {
            let bar = ScaleBarPlanner.plan(scale, maxWidthMm: inner.width - Style.scaleBarOverhangMm, locale: locale)
            if !bar.blocks.isEmpty {
                let barLeft = middle - bar.widthMm / 2
                let labelBaseline = y + Style.scaleLabelRowMm
                for tick in bar.labels {
                    // The number is centred on its tick; the unit of the last one follows it, past the bar's end.
                    let x = barLeft + tick.xMm
                    let number = tick.text.substringBefore(" ")
                    let unit = tick.text.substringAfter(" ", missing: "")
                    page.text(number, x, labelBaseline, .regular, Style.smallTextPt, .black, .center)
                    if !unit.isEmpty {
                        let after = x + page.textWidth(number, .regular, Style.smallTextPt) / 2
                        page.text(" \(unit)", after, labelBaseline, .regular, Style.smallTextPt)
                    }
                }
                let barTop = labelBaseline + Style.scaleBarGapMm
                page.save()
                page.strokeColor(.black)
                page.lineWidth(Style.scaleBarLineMm)
                for block in bar.blocks {
                    page.fillColor(block.black ? .black : .white)
                    page.rect(barLeft + block.startMm, barTop, block.endMm - block.startMm, Style.scaleBarHeightMm)
                    page.fillAndStroke()
                }
                page.restore()
                y = barTop + Style.scaleBarHeightMm
            }
        }
        let centimeter = PrintLocaleFormat.format(locale, texts.cmOnGround, distance(scale / Style.cmPerM))
        y += lineHeight(Style.textPt)
        page.text(fit(page, centimeter, .regular, Style.textPt, inner.width), middle, y, .regular, Style.textPt, .black, .center)
        if plan.edgeErrorPercent >= Style.minEdgeNotePercent {
            let percent = PrintLocaleFormat.fixed(plan.edgeErrorPercent, 1, locale)
            let note = PrintLocaleFormat.format(locale, texts.scaleNote, percent)
            for line in wrap(page, note, .italic, Style.smallTextPt, inner.width).prefix(2) {
                y += lineHeight(Style.smallTextPt)
                page.text(line, middle, y, .italic, Style.smallTextPt, Style.muted, .center)
            }
        }
        return true
    }

    /// The three norths (P17, Q15): true north with its star, grid north and magnetic north, from the same point. The
    /// angles are opened up so the lines can be told apart ("not to scale"); the exact values are written beside.
    private func drawNorthBlock(_ page: PdfPage, _ box: Box, _ pp: PrintPagePlan) -> Bool {
        if !dressing.northArrow { return false }
        let inner = box.inset(Style.boxPadMm)
        let overview = pp.kind == .overview
        let declination = overview ? nil : content.declinationDegrees
        let convergence = overview ? nil : GridPlanner.convergenceDegrees(pp.frame, template.grid)
        let (shownGrid, shownMagnetic) = openAngles(convergence, declination)
        // The diagram, on the left.
        let baseX = inner.left + Style.northDiagramMm / 2
        let baseY = inner.bottom - Style.northBaseLiftMm
        let length = baseY - inner.top - Style.northLabelRoomMm
        drawNorthLine(page, baseX, baseY, 0.0, length, .star, texts.trueNorth)
        if let shownGrid {
            drawNorthLine(page, baseX, baseY, shownGrid, length * Style.gridNorthShare, .square, texts.gridNorth)
        }
        if let shownMagnetic {
            drawNorthLine(
                page,
                baseX,
                baseY,
                shownMagnetic,
                length * Style.magneticShare,
                .halfArrow,
                texts.magneticNorth
            )
        }
        // The values, on the right: one row per angle, the label on the left and the value on the right.
        let textLeft = inner.left + Style.northDiagramMm + Style.northTextGapMm
        let textWidth = inner.right - textLeft
        var rows: [NorthRow] = []
        if let declination { rows.append(NorthRow(label: texts.declination, value: angle(declination))) }
        if let convergence { rows.append(NorthRow(label: texts.gridConvergence, value: angle(convergence))) }
        if let declination, let convergence {
            let gm = declination - convergence
            rows.append(NorthRow(label: texts.gridMagneticAngle, value: angle(gm), font: .bold))
        }
        var detail: String?
        if declination != nil, let year = content.declinationYear {
            if let annual = content.declinationAnnualDegrees {
                detail = PrintLocaleFormat.format(locale, texts.declinationDetail, year, angle(annual))
            } else {
                detail = String(year)
            }
        }
        let rowLines = rows.map { northRowLines(page, $0, textWidth) }
        let detailLines = detail.map { wrap(page, $0, .regular, Style.notePt, textWidth) } ?? []
        // What fits above the "not to scale" note: the rows always, then the heading, then the yearly detail.
        let room = kotlinToInt((inner.bottom - lineHeight(Style.smallTextPt) - inner.top) / lineHeight(Style.notePt))
        let rowCount = rowLines.reduce(0) { $0 + $1.count }
        let heading = rowCount + 1 <= room
        let showDetail = !detailLines.isEmpty && rowCount + (heading ? 1 : 0) + detailLines.count <= room
        var shown: [NorthLine] = []
        if heading { shown.append(NorthLine(left: texts.northHeading, right: nil, font: .bold)) }
        for (i, lines) in rowLines.enumerated() {
            shown += lines
            if i == 0, showDetail {
                shown += detailLines.map { NorthLine(left: $0, right: nil, font: .regular, color: Style.muted) }
            }
        }
        var y = inner.top
        for line in shown.prefix(Swift.max(room, 0)) {
            y += lineHeight(Style.notePt)
            let baseline = y - lineGap(Style.notePt)
            if let left = line.left { page.text(left, textLeft, baseline, line.font, Style.notePt, line.color) }
            if let right = line.right {
                page.text(right, inner.right, baseline, line.font, Style.notePt, line.color, .right)
            }
        }
        page.text(
            fit(page, texts.notToScale, .italic, Style.smallTextPt, textWidth),
            textLeft,
            inner.bottom - lineGap(Style.smallTextPt),
            .italic,
            Style.smallTextPt,
            Style.muted
        )
        return true
    }

    private enum NorthTip {
        case star
        case square
        case halfArrow
    }

    /// `label` wrapped in `width` millimeters; a compound name breaks after its dash when it must break
    /// ("Angle quadrillage–" / "magnétique" rather than "Angle" / "quadrillage–magnétique").
    private func wrapLabel(_ page: PdfPage, _ label: String, _ font: PdfFont, _ width: Double) -> [String] {
        let lines = wrap(page, label, font, Style.notePt, width)
        let scalars = Array(label.unicodeScalars)
        guard lines.count >= 2, let dash = scalars.firstIndex(of: "\u{2013}") else { return lines }
        let split = [
            String(String.UnicodeScalarView(scalars[0 ... dash])),
            String(String.UnicodeScalarView(scalars[(dash + 1)...])),
        ]
        return split.allSatisfy({ page.textWidth($0, font, Style.notePt) <= width }) ? split : lines
    }

    /// An angle of the norths box: its name and its value.
    private struct NorthRow {
        let label: String
        let value: String
        var font: PdfFont = .regular
    }

    /// A line of the norths box: text on the left, text set against the right edge, or both.
    private struct NorthLine {
        let left: String?
        let right: String?
        let font: PdfFont
        var color: PdfColor = .black
    }

    /// The lines of `row` in `width` millimeters: label and value on one line when they fit, otherwise the label
    /// wrapped and the value on its last line, or on a line of its own.
    private func northRowLines(_ page: PdfPage, _ row: NorthRow, _ width: Double) -> [NorthLine] {
        let gap = page.textWidth("  ", row.font, Style.notePt)
        let value = fit(page, row.value, row.font, Style.notePt, width)
        let valueWidth = page.textWidth(value, row.font, Style.notePt)
        let labels = wrapLabel(page, row.label, row.font, width)
        guard let last = labels.last else {
            return [NorthLine(left: nil, right: value, font: row.font)]
        }
        if page.textWidth(last, row.font, Style.notePt) + gap + valueWidth <= width {
            return labels.dropLast().map { NorthLine(left: $0, right: nil, font: row.font) }
                + [NorthLine(left: last, right: value, font: row.font)]
        }
        return labels.map { NorthLine(left: $0, right: nil, font: row.font) }
            + [NorthLine(left: nil, right: value, font: row.font)]
    }

    /// A north line `degrees` clockwise of true north, `length` long, with its tip and its label.
    private func drawNorthLine(
        _ page: PdfPage,
        _ x: Double,
        _ y: Double,
        _ degrees: Double,
        _ length: Double,
        _ tip: NorthTip,
        _ label: String
    ) {
        page.save()
        page.translate(x, y)
        page.rotate(degrees)
        page.strokeColor(.black)
        page.fillColor(.black)
        page.lineWidth(Style.northLineMm)
        page.lineStyle(.butt, .miter)
        page.moveTo(0.0, 0.0)
        page.lineTo(0.0, -length)
        page.stroke()
        switch tip {
        case .star:
            star(page, 0.0, -length - Style.starRadiusMm, Style.starRadiusMm)
            page.fill()
        case .square:
            page.rect(-Style.northHeadMm / 2, -length - Style.northHeadMm, Style.northHeadMm, Style.northHeadMm)
            page.fill()
        case .halfArrow:
            // Half an arrowhead, on the east side of the line.
            page.moveTo(0.0, -length - Style.northArrowMm)
            page.lineTo(Style.northHeadMm, -length)
            page.lineTo(0.0, -length)
            page.closePath()
            page.fill()
        }
        page.restore()
        let rad = Angle.radians(degrees)
        let reach = length + Style.northTipRoomMm
        let tipX = x + sin(rad) * reach
        let tipY = y - cos(rad) * reach
        page.text(label, tipX, tipY, .bold, Style.northLabelPt, .black, .center)
    }

    /// A five-pointed star centred on (`cx`, `cy`), as a path to fill.
    private func star(_ page: PdfPage, _ cx: Double, _ cy: Double, _ r: Double) {
        for i in 0 ..< Style.starPoints * 2 {
            let radius = i % 2 == 0 ? r : r * Style.starInner
            let a = Double.pi * Double(i) / Double(Style.starPoints)
            let px = cx + sin(a) * radius
            let py = cy - cos(a) * radius
            if i == 0 { page.moveTo(px, py) } else { page.lineTo(px, py) }
        }
        page.closePath()
    }

    /// The angles the grid and magnetic norths are drawn at: a few degrees at least, a little more for a larger value,
    /// the sides kept, and apart from each other when they lie on the same side.
    private func openAngles(_ grid: Double?, _ magnetic: Double?) -> (Double?, Double?) {
        func opened(_ v: Double) -> Double {
            if abs(v) < Style.zeroAngle { return 0.0 }
            return PrintLocaleFormat.sign(v) * Swift.min(Style.openMax, Style.openMin + Style.openGain * abs(v))
        }
        func sideOf(_ v: Double?) -> Double {
            guard let v else { return 1.0 }
            let s = PrintLocaleFormat.sign(v)
            return s != 0.0 ? s : 1.0
        }
        var g = grid.map(opened)
        var m = magnetic.map(opened)
        // A north that matches true north is still drawn apart from it, on the other side of the third one.
        if g == 0.0 { g = -sideOf(m) * Style.openMin }
        if m == 0.0 { m = -sideOf(g) * Style.openMin }
        if let gv = g, let mv = m, let gridValue = grid, let magneticValue = magnetic,
           PrintLocaleFormat.sign(gv) == PrintLocaleFormat.sign(mv), abs(gv - mv) < Style.openSeparation
        {
            if abs(gridValue) <= abs(magneticValue) {
                m = gv + PrintLocaleFormat.sign(gv) * Style.openSeparation
            } else {
                g = mv + PrintLocaleFormat.sign(mv) * Style.openSeparation
            }
        }
        return (g, m)
    }

    private func angle(_ degrees: Double) -> String {
        PrintFormat.declination(degrees, texts.east, texts.west)
    }

    /// The grid: its system, its squares and how the labels read. The graduated band follows the grid since ADR-087
    /// and needs no note of its own.
    private func drawGridBlock(_ page: PdfPage, _ box: Box, _ grid: GridPlan) -> Bool {
        let inner = box.inset(Style.boxPadMm)
        var y = inner.top
        var lines: [(String, PdfFont, Double)] = []
        lines.append((texts.gridHeading, .bold, Style.notePt))
        let projected = grid.kind == .utm || grid.kind == .mgrs || grid.kind == .lambert93
        if grid.lines.isEmpty && grid.labels.isEmpty {
            lines.append((texts.noGrid, .regular, Style.notePt))
        } else if grid.zones.count < 2 {
            lines.append((grid.systemText, .bold, Style.notePt))
            let spacing = PrintLocaleFormat.format(locale, projected ? texts.gridSquares : texts.gridLines, grid.stepText)
            lines.append((spacing, .regular, Style.notePt))
            if !grid.squares.isEmpty { lines.append((squaresLine(grid), .regular, Style.notePt)) }
        } else {
            // A sheet across zones, as the P24.0 preview: the zones and the squares of the grid on one line, then the
            // edges, the squares of each zone and the figures of the neighbour, smaller to keep within the box.
            let spacing = lowercasedFirst(PrintLocaleFormat.format(locale, texts.gridSquares, grid.stepText))
            lines.append(("\(systemLine(grid)) \u{2014} \(spacing)", .bold, Style.zonesNotePt))
            var edgeLines: [String] = []
            for edge in grid.zoneEdges {
                if let text = edgeLine(edge), !edgeLines.contains(text) { edgeLines.append(text) }
            }
            for edge in edgeLines {
                lines.append((edge, .regular, Style.zonesNotePt))
            }
            if !grid.squares.isEmpty { lines.append((squaresLine(grid), .regular, Style.zonesNotePt)) }
            if grid.labels.contains(where: \.neighbour) {
                lines.append((texts.neighbourGrid, .regular, Style.zonesNotePt))
            }
        }
        for (text, font, size) in lines {
            for line in wrap(page, text, font, size, inner.width) {
                if y + lineHeight(size) > inner.bottom + Style.epsilonMm { break }
                y += lineHeight(size)
                page.text(line, inner.left, y - lineGap(size), font, size)
            }
        }
        // A sample of the labels: the big figures and what they are.
        let sample = grid.labels.first { $0.parts != nil && $0.side == .top && !$0.neighbour }
        if let sample, y + lineHeight(Style.majorPt) < inner.bottom - lineHeight(Style.notePt) {
            let pieces = shortPieces(sample)
            let cap = capHeight(Style.majorPt)
            y += lineHeight(Style.majorPt)
            let baseline = y - lineGap(Style.majorPt)
            drawPieces(page, pieces, inner.left, baseline, cap, gridColor)
            let w = pieces.reduce(0.0) { $0 + page.textWidth($1.text, $1.font, $1.sizePt) }
            // MGRS: the kilometers inside the square, not the whole UTM value (ADR-087).
            let meaning: String
            if grid.kind == .mgrs {
                meaning = PrintLocaleFormat.format(locale, texts.squareKilometers, sample.parts?.major ?? sample.text)
            } else {
                let meters = PrintLocaleFormat.grouped(kotlinRound(sample.value), locale)
                meaning = "= \(meters) m · \(texts.principalDigits)"
            }
            page.text(
                fit(page, meaning, .regular, Style.smallTextPt, inner.width - w - 1.0),
                inner.left + w + 1.0,
                baseline,
                .regular,
                Style.smallTextPt
            )
        }
        return true
    }

    /// Kotlin `replaceFirstChar { it.lowercase(locale) }`.
    private func lowercasedFirst(_ text: String) -> String {
        guard let first = text.first else { return text }
        return String(first).lowercased(with: locale) + text.dropFirst()
    }

    /// `MGRS 31T · WGS84`; across zones, all of them: `MGRS 31T et 32T · WGS84`.
    private func systemLine(_ grid: GridPlan) -> String {
        if grid.zones.count < 2 { return grid.systemText }
        let name = grid.kind == .mgrs ? "MGRS" : "UTM"
        let zones = grid.zones.dropLast().joined(separator: ", ") + " \(texts.and) " + (grid.zones.last ?? "")
        return "\(name) \(zones) · WGS84"
    }

    /// `Limite des zones 31 / 32 : méridien 6° E (trait épais)`.
    private func edgeLine(_ edge: ZoneEdge) -> String? {
        guard let sides = edge.sides else { return nil }
        let format = edge.meridian ? texts.zoneEdgeMeridian : texts.zoneEdgeParallel
        return PrintLocaleFormat.format(locale, format, sides.0, sides.1, edgeDegrees(edge))
    }

    /// `Carré de 100 km : GL`; across two zones, each named with its side: `Carrés de 100 km : 31T GL (ouest) · …`.
    private func squaresLine(_ grid: GridPlan) -> String {
        if grid.zones.count < 2 {
            return PrintLocaleFormat.format(locale, texts.squares, grid.squares.joined(separator: " · "))
        }
        var order: [String] = []
        var groups: [String: [String]] = [:]
        for square in grid.squares {
            let zone = square.substringBefore(" ")
            if groups[zone] == nil { order.append(zone) }
            groups[zone, default: []].append(square)
        }
        let text = order.map { zone -> String in
            let squares = groups[zone] ?? []
            var side: String?
            if let compass = grid.zoneSides[zone] {
                switch compass {
                case .west: side = texts.westSide
                case .east: side = texts.eastSide
                case .south: side = texts.southSide
                case .north: side = texts.northSide
                }
            }
            return squares.joined(separator: " · ") + (side.map { " (\($0))" } ?? "")
        }.joined(separator: " · ")
        return PrintLocaleFormat.format(locale, texts.squaresOfZones, text)
    }

    /// The credits of the sources (their licences require them) and what went wrong with the tiles.
    private func drawCredits(_ page: PdfPage, _ left: Double, _ baseline: Double, _ width: Double, _ missingTiles: Int) {
        var parts = content.attributions
        for warning in plan.warnings {
            guard case let .oversampled(_, sourceName, factor, _, _) = warning else { continue }
            let text = PrintLocaleFormat.fixed(factor, 1, locale)
            parts.append(PrintLocaleFormat.format(locale, texts.oversampled, sourceName, text))
        }
        if missingTiles > 0 { parts.append(texts.missingTiles) }
        if parts.isEmpty { return }
        let text = fit(page, parts.joined(separator: " · "), .regular, Style.creditsPt, width - 2 * SheetLayout.padMm)
        page.text(text, left + SheetLayout.padMm, baseline, .regular, Style.creditsPt, Style.muted)
    }

    private func strokeBox(_ page: PdfPage, _ box: Box) {
        page.save()
        page.strokeColor(Style.boxBorder)
        page.lineWidth(Style.boxLineMm)
        page.rect(box.left, box.top, box.width, box.bottom - box.top)
        page.stroke()
        page.restore()
    }

    private func layerName(_ layer: PrintLayer) -> String {
        let percent = kotlinRound(Double(layer.layer.opacity) * Style.percent)
        return percent < Int(Style.percent) ? "\(layer.source.name) (\(percent) %)" : layer.source.name
    }

    // ---- text helpers ------------------------------------------------------------------------------------------------

    /// A rectangle of the page, in millimeters.
    private struct Box {
        let left: Double
        let top: Double
        let right: Double
        let bottom: Double

        var width: Double { right - left }

        func inset(_ d: Double) -> Box {
            Box(left: left + d, top: top + d, right: right - d, bottom: bottom - d)
        }

        func overlaps(_ other: Box) -> Bool {
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
        }
    }

    /// `text` with a white outline, readable over any map.
    private func haloText(
        _ page: PdfPage,
        _ text: String,
        _ x: Double,
        _ y: Double,
        _ font: PdfFont,
        _ sizePt: Double,
        _ color: PdfColor,
        _ align: TextAlign = .center
    ) {
        for i in 0 ..< Style.haloSteps {
            let a = 2 * Double.pi * Double(i) / Double(Style.haloSteps)
            page.text(text, x + cos(a) * Style.haloMm, y + sin(a) * Style.haloMm, font, sizePt, .white, align)
        }
        page.text(text, x, y, font, sizePt, color, align)
    }

    /// `45°12′30″ N`: an angle to the second, the way the corners of a sheet are written.
    private func dms(_ value: Double, _ positive: String, _ negative: String) -> String {
        let total = kotlinRound(abs(value) * Style.secondsPerDegree)
        let d = total / 3600
        let m = (total % 3600) / Style.secondsPerMinute
        let s = total % Style.secondsPerMinute
        let side = value >= 0 ? positive : negative
        return "\(d)°\(PrintLocaleFormat.padded(m, 2))\u{2032}\(PrintLocaleFormat.padded(s, 2))\u{2033} \(side)"
    }

    /// `45.50335° N`: an angle in decimal degrees to about a meter, for a sheet whose grid is in decimal degrees.
    private func degrees(_ value: Double, _ positive: String, _ negative: String) -> String {
        PrintLocaleFormat.fixed(abs(value), 5, locale) + "° " + (value >= 0 ? positive : negative)
    }

    /// `250 m`, `2,5 km`: a ground distance with the page's decimal separator.
    private func distance(_ meters: Double) -> String {
        ScaleBar.format(meters).replacingOccurrences(of: ".", with: PrintLocaleFormat.decimalSeparator(locale))
    }

    private func lineHeight(_ sizePt: Double) -> Double {
        sizePt * Style.lineSpacing / PdfPage.ptPerMm
    }

    /// The space under the baseline in a line of `lineHeight`.
    private func lineGap(_ sizePt: Double) -> Double {
        lineHeight(sizePt) * Style.baselineShare
    }

    private func capHeight(_ sizePt: Double) -> Double {
        sizePt * Style.capHeight / PdfPage.ptPerMm
    }

    /// Greedy word wrap of `text` into lines no wider than `maxWidth` millimeters.
    private func wrap(_ page: PdfPage, _ text: String, _ font: PdfFont, _ sizePt: Double, _ maxWidth: Double) -> [String] {
        var lines: [String] = []
        var line = ""
        for word in text.split(separator: " ").map(String.init) {
            let candidate = line.isEmpty ? word : "\(line) \(word)"
            if page.textWidth(candidate, font, sizePt) <= maxWidth || line.isEmpty {
                line = candidate
            } else {
                lines.append(line)
                line = word
            }
            var scalars = Array(line.unicodeScalars)
            while page.textWidth(line, font, sizePt) > maxWidth, scalars.count > 1 {
                var cut = scalars.count - 1
                while cut > 1, page.textWidth(Self.string(scalars[0 ..< cut]), font, sizePt) > maxWidth {
                    cut -= 1
                }
                lines.append(Self.string(scalars[0 ..< cut]))
                scalars = Array(scalars[cut...])
                line = Self.string(scalars[...])
            }
        }
        if !line.isEmpty { lines.append(line) }
        return lines
    }

    private static func string(_ scalars: ArraySlice<Unicode.Scalar>) -> String {
        String(String.UnicodeScalarView(scalars))
    }

    /// The first `count` of `lines`, the last one ending with an ellipsis when some are left out.
    private func clamp(
        _ page: PdfPage,
        _ lines: [String],
        _ count: Int,
        _ font: PdfFont,
        _ sizePt: Double,
        _ maxWidth: Double
    ) -> [String] {
        if lines.count <= count { return lines }
        var kept = Array(lines.prefix(count))
        kept[count - 1] = fit(page, kept[count - 1] + " " + lines[count], font, sizePt, maxWidth)
        if !kept[count - 1].hasSuffix("\u{2026}") {
            kept[count - 1] = fit(page, kept[count - 1] + "\u{2026}", font, sizePt, maxWidth)
        }
        return kept
    }

    /// `text` cut to `maxWidth` millimeters with an ellipsis when it is longer.
    private func fit(_ page: PdfPage, _ text: String, _ font: PdfFont, _ sizePt: Double, _ maxWidth: Double) -> String {
        if page.textWidth(text, font, sizePt) <= maxWidth { return text }
        let scalars = Array(text.unicodeScalars)
        var end = scalars.count
        while end > 1, page.textWidth(Self.string(scalars[0 ..< end]) + "\u{2026}", font, sizePt) > maxWidth {
            end -= 1
        }
        return PrintLocaleFormat.trimEnd(Self.string(scalars[0 ..< end])) + "\u{2026}"
    }

    /// The sizes, colours and spacings of the sheet.
    private enum Style {
        static let lineSpacing = 1.22
        static let baselineShare = 0.22
        static let capHeight = 0.72
        static let percent = 100.0
        static let epsilonMm = 0.01
        static let descentMm = 0.4
        static let secondsPerDegree = 3_600.0
        static let secondsPerMinute = 60

        /// The grids whose sheet may carry the corners in degrees (ADR-087).
        static let degreeGrids: Set<GridKind> = [GridKind.none, .wgs84, .wgs84Decimal]
        static let cmPerM = 100.0

        static let gridLineMm = 0.13
        static let gridMajorMm = 0.35
        static let gridAlpha = 0.85
        static let squarePt = 10.0

        // The sheet across grid zones (P24.4, as the P24.0 preview): the edge thick and dark, its meridian at the top,
        // the figures of the neighbouring zone in blue italics in the outer row of the strip.
        static let zoneEdgeMm = 0.6
        static let edgeTagPt = 7.0
        static let edgeTagInsetMm = 3.0
        static let edgeTagLiftMm = 1.0
        static let neighbourPt = 6.0
        static let neighbourRowGapMm = 0.6

        /// From the paper edge, what most printers leave white: an outer row of labels stops there.
        static let printerReachMm = 4.0

        static let innerLineMm = 0.15
        static let outerLineMm = 0.45

        static let tickMm = 1.0
        static let tickLineMm = 0.15
        static let labelGapMm = 0.5
        static let labelPadMm = 0.25
        static let stripPadMm = 0.3
        static let labelOverhangMm = 2.0
        static let majorPt = 7.5
        static let smallPt = 5.0
        static let graticulePt = 6.0

        static let cornerPt = 5.0
        static let cornerTopMm = 4.2
        static let cornerBottomMm = 5.4
        static let cornerSideMm = 5.6

        static let trackMm = 0.6
        static let overviewTrackMm = 0.5
        static let trackAlpha = 0.9
        static let trackMinStepMm = 0.05
        static let waypointRadiusMm = 2.0
        static let waypointOutlineMm = 0.3
        static let glyphMm = 2.8
        static let waypointPt = 6.5
        static let waypointNameGapMm = 0.6
        static let waypointNameMaxMm = 45.0
        static let haloMm = 0.25
        static let haloSteps = 8

        static let footprintLineMm = 0.5
        static let footprintNumberPt = 14.0

        static let boxPadMm = 1.6
        static let boxLineMm = 0.2
        static let creditsBaselineMm = 1.1
        static let creditsPt = 5.0

        static let titlePt = 11.0
        static let titleLines = 2
        static let subtitlePt = 7.0
        static let textPt = 6.0
        static let smallTextPt = 4.8
        static let notePt = 5.4
        static let zonesNotePt = 4.9

        static let legendGapMm = 0.6
        static let legendSampleMm = 5.0
        static let legendDiscMm = 1.1
        static let legendTextGapMm = 1.2
        static let twoColumnsMm = 70.0
        static let columnGapMm = 3.0

        static let scalePt = 12.0
        static let scaleTextGapMm = 0.6
        static let scaleBarOverhangMm = 7.0
        static let scaleLabelRowMm = 3.2
        static let scaleBarGapMm = 0.6
        static let scaleBarHeightMm = 1.6
        static let scaleBarLineMm = 0.18
        static let minEdgeNotePercent = 0.05

        static let northDiagramMm = 18.0
        static let northTextGapMm = 1.0
        static let northBaseLiftMm = 0.6
        static let northLabelRoomMm = 4.2
        static let northTipRoomMm = 2.6
        static let northLineMm = 0.25
        static let northHeadMm = 0.9
        static let northArrowMm = 2.2
        static let northLabelPt = 5.0
        static let gridNorthShare = 0.7
        static let magneticShare = 0.86
        static let starRadiusMm = 1.2
        static let starInner = 0.45
        static let starPoints = 5
        static let zeroAngle = 0.01
        static let openMin = 6.0
        static let openGain = 2.5
        static let openMax = 20.0
        static let openSeparation = 7.0

        static let gridBlue = PdfColor(20, 50, 140)
        static let zoneEdgeColor = PdfColor(28, 36, 41)
        static let neighbourBlue = PdfColor(24, 95, 165)
        static let boxBorder = PdfColor(90, 90, 90)
        static let muted = PdfColor(70, 70, 70)
        static let footprintColor = PdfColor(200, 30, 30)
        static let waypointSampleArgb = 0xFFC8_1E1E
    }
}
