// Port of core/src/main/kotlin/fr/toolmap/core/print/GridPlanner.kt.

import Foundation

/// The grids a print can carry; `wgs84` is the graticule in degrees, minutes, seconds, `wgs84Decimal` in degrees. The
/// raw value is the Kotlin name, as written in `last.json`.
public enum GridKind: String, Codable, CaseIterable, Sendable {
    case none = "NONE"
    case wgs84 = "WGS84"
    case utm = "UTM"
    case lambert93 = "LAMBERT93"
    case mgrs = "MGRS"
    case wgs84Decimal = "WGS84_DECIMAL"
}

public enum FrameSide: CaseIterable, Sendable {
    case top
    case bottom
    case left
    case right
}

/// A grid line, already clipped to the frame; `points` are in frame millimeters. `major`: a 100 km square edge.
public struct GridLine: Equatable, Sendable {
    public let points: [FramePoint]
    public let major: Bool

    public init(points: [FramePoint], major: Bool = false) {
        self.points = points
        self.major = major
    }
}

/// A projected coordinate cut the way printed maps write it: `prefix` in small figures, the two "principal digits"
/// `major` in big ones (the kilometers inside the 100 km square), `rest` in small ones (meters under the kilometer).
/// 645 000 m → `6` `45`; 5 050 500 m → `50` `50` `500`.
public struct LabelParts: Equatable, Hashable, Sendable {
    public let prefix: String
    public let major: String
    public let rest: String

    public init(prefix: String, major: String, rest: String) {
        self.prefix = prefix
        self.major = major
        self.rest = rest
    }
}

/// A label written in the margin where a grid line leaves the frame. `positionMm` runs along the `side`.
public struct GridLabel: Equatable, Sendable {
    public let side: FrameSide
    public let positionMm: Double
    public let text: String
    /// The line's value divided by the spacing: tells neighbours apart and alternates the graduation bars.
    public let index: Int
    /// Principal digits of a projected grid; nil for the graticules.
    public let parts: LabelParts?
    /// The line's value in the grid's unit.
    public let value: Double
    /// A line of a neighbouring zone's grid carried past the zone edge, written in the outer row of the margin on the
    /// part of the sheet that is not its zone (P24.4, the NATO practice).
    public let neighbour: Bool

    public init(
        side: FrameSide,
        positionMm: Double,
        text: String,
        index: Int = 0,
        parts: LabelParts? = nil,
        value: Double = 0.0,
        neighbour: Bool = false
    ) {
        self.side = side
        self.positionMm = positionMm
        self.text = text
        self.index = index
        self.parts = parts
        self.value = value
        self.neighbour = neighbour
    }
}

/// How a name over the map lines up with its point: centred on it, ending on it, or starting from it.
public enum TagAlign: Sendable {
    case center
    case end
    case start
}

/// A 100 km square letter pair written inside the map, at `xMm`, `yMm` of the frame; with its grid zone (`31T GL`) on
/// a sheet across two zones, beside the zone edge.
public struct SquareTag: Equatable, Sendable {
    public let letters: String
    public let xMm: Double
    public let yMm: Double
    public let align: TagAlign

    public init(letters: String, xMm: Double, yMm: Double, align: TagAlign = .center) {
        self.letters = letters
        self.xMm = xMm
        self.yMm = yMm
        self.align = align
    }
}

/// The edge of a grid zone across the sheet (P24.4), drawn thick: a meridian between two zones, or a parallel between
/// two latitude bands.
public struct ZoneEdge: Sendable {
    public let line: GridLine
    public let meridian: Bool
    /// Its longitude or latitude in degrees, east and north positive.
    public let degrees: Double
    /// The zones (`31`, `32`) or the bands (`T`, `U`) on either side, west or south first; nil past 84 N.
    public let sides: (String, String)?

    public init(line: GridLine, meridian: Bool, degrees: Double, sides: (String, String)?) {
        self.line = line
        self.meridian = meridian
        self.degrees = degrees
        self.sides = sides
    }
}

/// Where a zone lies on a sheet across two of them.
public enum CompassSide: Sendable {
    case west
    case east
    case south
    case north
}

public enum GridIssue: Sendable {
    case none
    case outsideValidity
}

public struct GridPlan: Sendable {
    public let kind: GridKind
    public let lines: [GridLine]
    public let labels: [GridLabel]
    /// The spacing of the lines in the unit of the grid, for the legend (`1 km`, `30″`).
    public let stepText: String
    /// The coordinate system, for the legend (`UTM 31N · WGS84`).
    public let systemText: String
    public let issue: GridIssue
    /// The spacing in the grid's unit (meters or degrees).
    public let step: Double
    /// Grid zone of a UTM or MGRS grid (`31T`).
    public let zoneText: String?
    /// The 100 km squares the page covers, west to east then north to south (MGRS); with their grid zone (`31T GL`)
    /// when the page covers more than one.
    public let squares: [String]
    /// Where to write the letters of each square in the map, when the page covers more than one.
    public let squareTags: [SquareTag]
    /// UTM and MGRS: the grid zones the page covers (`31T`, `32T`; `31N`, `32N` in UTM); more than one when it
    /// straddles.
    public let zones: [String]
    /// The edges of the grid zones across the page, drawn thick (P24.4).
    public let zoneEdges: [ZoneEdge]
    /// MGRS on a page across two grid zones: the side each one lies on, for the legend (« 31T GL (ouest) »).
    public let zoneSides: [String: CompassSide]

    public init(
        kind: GridKind,
        lines: [GridLine],
        labels: [GridLabel],
        stepText: String,
        systemText: String,
        issue: GridIssue = .none,
        step: Double = 0.0,
        zoneText: String? = nil,
        squares: [String] = [],
        squareTags: [SquareTag] = [],
        zones: [String] = [],
        zoneEdges: [ZoneEdge] = [],
        zoneSides: [String: CompassSide] = [:]
    ) {
        self.kind = kind
        self.lines = lines
        self.labels = labels
        self.stepText = stepText
        self.systemText = systemText
        self.issue = issue
        self.step = step
        self.zoneText = zoneText
        self.squares = squares
        self.squareTags = squareTags
        self.zones = zones
        self.zoneEdges = zoneEdges
        self.zoneSides = zoneSides
    }

    public static func empty(_ kind: GridKind = GridKind.none) -> GridPlan {
        GridPlan(kind: kind, lines: [], labels: [], stepText: "", systemText: "")
    }
}

/// A bar of the graduated band of the frame: from `startMm` to `endMm` along `side`, black or white.
public struct GraduationBar: Equatable, Sendable {
    public let side: FrameSide
    public let startMm: Double
    public let endMm: Double
    public let black: Bool

    public init(side: FrameSide, startMm: Double, endMm: Double, black: Bool) {
        self.side = side
        self.startMm = startMm
        self.endMm = endMm
        self.black = black
    }
}

/// The graduated band: the grid of the sheet at its spacing, as alternating bars (Q14, ADR-087).
public struct Graduation: Sendable {
    public let kind: GridKind
    public let step: Double
    public let stepText: String
    public let bars: [GraduationBar]

    public init(kind: GridKind, step: Double, stepText: String, bars: [GraduationBar]) {
        self.kind = kind
        self.step = step
        self.stepText = stepText
        self.bars = bars
    }

    public static let empty = Graduation(kind: GridKind.none, step: 0.0, stepText: "", bars: [])
}

// ---- coordinate systems ---------------------------------------------------------------------------------------------

/// A coordinate system the sheet can be gridded in: projected (meters) or a graticule (degrees).
private protocol PrintGridSystem {
    var systemText: String { get }
    var isProjected: Bool { get }
    func toGrid(_ lat: Double, _ lon: Double) -> (Double, Double)
    func fromGrid(_ x: Double, _ y: Double) -> LonLat
    func labelX(_ x: Double, _ step: Double, _ locale: Locale) -> String
    func labelY(_ y: Double, _ step: Double, _ locale: Locale) -> String
    func parts(_ value: Double) -> LabelParts?
    func stepText(_ step: Double, _ locale: Locale) -> String
}

/// The graticules: x is the longitude, y the latitude.
private protocol PrintGraticule: PrintGridSystem {
    func angle(_ value: Double, _ positive: Character, _ negative: Character, _ step: Double, _ locale: Locale) -> String
}

extension PrintGraticule {
    var isProjected: Bool { false }

    func toGrid(_ lat: Double, _ lon: Double) -> (Double, Double) { (lon, lat) }

    func fromGrid(_ x: Double, _ y: Double) -> LonLat { LonLat(lon: x, lat: y) }

    func labelX(_ x: Double, _ step: Double, _ locale: Locale) -> String { angle(x, "E", "W", step, locale) }

    func labelY(_ y: Double, _ step: Double, _ locale: Locale) -> String { angle(y, "N", "S", step, locale) }

    func parts(_ value: Double) -> LabelParts? { nil }
}

private struct DmsSystem: PrintGraticule {
    let systemText = "WGS84 · degrés, minutes, secondes"

    func stepText(_ step: Double, _ locale: Locale) -> String {
        let seconds = kotlinRound(step * 3600.0)
        if seconds % 3600 == 0 { return "\(seconds / 3600)°" }
        if seconds % 60 == 0 { return "\(seconds / 60)\u{2032}" }
        return "\(seconds)\u{2033}"
    }

    func angle(_ value: Double, _ positive: Character, _ negative: Character, _ step: Double, _ locale: Locale) -> String {
        let totalSeconds = kotlinRound(abs(value) * 3600.0)
        let d = totalSeconds / 3600
        let m = (totalSeconds % 3600) / 60
        let s = totalSeconds % 60
        let stepSeconds = kotlinRound(step * 3600.0)
        let hemisphere = value < 0 ? negative : positive
        if stepSeconds % 3600 == 0 { return "\(d)°\(hemisphere)" }
        let minutes = PrintLocaleFormat.padded(m, 2)
        if stepSeconds % 60 == 0 { return "\(d)°\(minutes)\u{2032}\(hemisphere)" }
        return "\(d)°\(minutes)\u{2032}\(PrintLocaleFormat.padded(s, 2))\u{2033}\(hemisphere)"
    }
}

private struct DecimalSystem: PrintGraticule {
    let systemText = "WGS84 · degrés décimaux"

    private func decimals(_ step: Double) -> Int {
        Swift.max(0, kotlinToInt(ceil(-log10(step) - 1e-9)))
    }

    func stepText(_ step: Double, _ locale: Locale) -> String {
        PrintLocaleFormat.fixed(step, decimals(step), locale) + "°"
    }

    func angle(_ value: Double, _ positive: Character, _ negative: Character, _ step: Double, _ locale: Locale) -> String {
        let hemisphere = value < 0 ? negative : positive
        return PrintLocaleFormat.fixed(abs(value), decimals(step), locale) + "°" + String(hemisphere)
    }
}

private struct ProjectedSystem: PrintGridSystem {
    private static let km = 1000
    private static let hecto = 100
    private static let square = 100_000

    let systemText: String
    let forward: (Double, Double) -> (Double, Double)
    let inverse: (Double, Double) -> LonLat
    let mgrs: Bool

    var isProjected: Bool { true }

    func toGrid(_ lat: Double, _ lon: Double) -> (Double, Double) { forward(lat, lon) }

    func fromGrid(_ x: Double, _ y: Double) -> LonLat { inverse(x, y) }

    /// MGRS reads the kilometers inside the 100 km square; the others read the whole figure.
    func labelX(_ x: Double, _ step: Double, _ locale: Locale) -> String { label(x, step, locale) }

    func labelY(_ y: Double, _ step: Double, _ locale: Locale) -> String { label(y, step, locale) }

    func parts(_ value: Double) -> LabelParts? {
        let meters = kotlinRound(value)
        let km = PrintLocaleFormat.floorDiv(meters, ProjectedSystem.km)
        let under = PrintLocaleFormat.floorMod(meters, ProjectedSystem.km)
        let hundreds = PrintLocaleFormat.floorDiv(km, ProjectedSystem.hecto)
        return LabelParts(
            // MGRS reads the kilometers inside the 100 km square: no hundreds of km before them (ADR-087).
            prefix: hundreds > 0 && !mgrs ? String(hundreds) : "",
            major: PrintLocaleFormat.padded(PrintLocaleFormat.floorMod(km, ProjectedSystem.hecto), 2),
            rest: under == 0 ? "" : PrintLocaleFormat.padded(under, 3)
        )
    }

    private func label(_ value: Double, _ step: Double, _ locale: Locale) -> String {
        let meters = kotlinRound(value)
        if mgrs {
            let inSquare = PrintLocaleFormat.floorMod(meters, ProjectedSystem.square)
            if step >= Double(ProjectedSystem.km) {
                return PrintLocaleFormat.padded(inSquare / ProjectedSystem.km, 2)
            }
            return PrintLocaleFormat.padded(inSquare / ProjectedSystem.hecto, 3)
        }
        if step >= Double(ProjectedSystem.km) {
            return String(meters / ProjectedSystem.km)
        }
        return PrintLocaleFormat.format(locale, "%d", meters)
    }

    func stepText(_ step: Double, _ locale: Locale) -> String {
        ScaleBar.format(step)
    }

    /// The grid of `zone`, carried past its edges as far as asked (its part of the sheet is cut later). The frame
    /// centre's hemisphere for the whole page: lines stay continuous across the equator. `shift`: the longitudes of
    /// the sheet less those of the zone, 360° for a zone met past the antimeridian.
    static func utm(_ zone: Int, _ north: Bool, _ mgrs: Bool, _ band: Character, _ shift: Double) -> ProjectedSystem {
        let name = mgrs ? "MGRS \(zone)\(band) · WGS84" : "UTM \(zone)\(north ? "N" : "S") · WGS84"
        let meridian = Utm.centralMeridian(zone)
        return ProjectedSystem(
            systemText: name,
            forward: { lat, lon in
                let u = Utm.toUtm(lat, lon, zone: zone, north: north)
                return (u.easting, u.northing)
            },
            inverse: { x, y in
                // Unwrapped around the central meridian, then into the longitudes of the sheet.
                let p = Utm.toLonLat(UtmCoordinate(zone: zone, north: north, easting: x, northing: y))
                return LonLat(lon: meridian + Utm.normalizeLon(p.lon - meridian) + shift, lat: p.lat)
            },
            mgrs: mgrs
        )
    }

    static func lambert() -> ProjectedSystem {
        ProjectedSystem(
            systemText: "Lambert-93 · RGF93 (EPSG:2154)",
            forward: { lat, lon in
                let p = Lambert93.toLambert(lat, lon)
                return (p.easting, p.northing)
            },
            inverse: { x, y in Lambert93.toLonLat(PlanarCoordinate(easting: x, northing: y)) },
            mgrs: false
        )
    }
}

/// The coordinate grid drawn over the map and labelled in the margin. Grid lines are straight in their own coordinate
/// system and a little curved on the Web Mercator paper: each one is sampled and projected, then clipped to the frame
/// (spec §4).
public enum GridPlanner {
    /// Sample spacing along a grid line, in millimeters of paper.
    private static let sampleMm = 4.0

    private static let maxLinesPerAxis = 120

    private static let edgeToleranceMm = 0.05

    private static let squareMeters = 100_000.0

    private static let franceLat = 41.0 ... 52.0
    private static let franceLon = -6.0 ... 10.0
    private static let utmMaxLat = 84.0
    private static let lambert93ConeConstant = 0.7256077650532670
    private static let lambert93CentralMeridian = 3.0
    private static let utmMinLat = -80.0
    private static let squareSamples = 12

    /// From a zone edge to the name of a square beside it.
    private static let tagGapMm = 5.0

    /// A step into the zone or band on either side of an edge, in degrees.
    private static let sideStep = 1e-6

    /// Plans the grid of `kind` over `frame`. `step` forces a spacing in the grid's own unit (meters, or degrees for
    /// the graticules); nil takes the one of the scale (`GridSpacing`).
    public static func plan(
        _ frame: FrameProjection,
        _ kind: GridKind,
        step: Double? = nil,
        locale: Locale = .current
    ) -> GridPlan {
        if kind == .none { return GridPlan.empty() }
        let forced = step.flatMap { $0 > 0.0 ? $0 : nil }
        if kind == .utm || kind == .mgrs {
            if frame.centerLat > utmMaxLat || frame.centerLat < utmMinLat {
                return GridPlan(kind: kind, lines: [], labels: [], stepText: "", systemText: "", issue: .outsideValidity)
            }
            return zoned(frame, kind, forced ?? GridSpacing.auto(kind, frame.scale), locale)
        }
        guard let system = systemFor(frame, kind) else {
            return GridPlan(kind: kind, lines: [], labels: [], stepText: "", systemText: "", issue: .outsideValidity)
        }
        let spacing = forced ?? GridSpacing.auto(kind, frame.scale)
        let whole = Rect(left: 0.0, top: 0.0, right: frame.frameWidthMm, bottom: frame.frameHeightMm)
        guard let sampled = drawLines(frame, system, spacing) else { return GridPlan.empty(kind) }
        var lines: [GridLine] = []
        var labels: [GridLabel] = []
        for line in sampled {
            let own = clipped(line.raw, whole, frame, line.major)
            lines += own.lines
            for (edge, pos) in own.edges {
                if let label = line.label(edge, pos, locale, neighbour: false) { labels.append(label) }
            }
        }
        return GridPlan(
            kind: kind,
            lines: lines,
            labels: labels,
            stepText: system.stepText(spacing, locale),
            systemText: system.systemText,
            issue: .none,
            step: spacing
        )
    }

    /// UTM and MGRS (P24.4, as the P24.0 preview): each grid zone on the sheet draws its own grid, cut at its edges,
    /// and the edges are drawn thick. Each zone writes its figures in its own part of the margin; where the sheet meets
    /// the neighbouring zone, the lines of that zone carried on are written in an outer row (the NATO practice), so a
    /// reference can be read in either zone. The squares are named with their zone on both sides.
    private static func zoned(_ frame: FrameProjection, _ kind: GridKind, _ spacing: Double, _ locale: Locale) -> GridPlan {
        let mgrs = kind == .mgrs
        // One hemisphere for the whole sheet: its northings run on across the equator.
        let north = frame.centerLat >= 0
        let band = Utm.bandLetter(frame.centerLat) ?? "N"
        let area = frame.lonLatBounds
        let parts = GridZones.pieces(area).compactMap { ZonePart.of(frame, $0) }
        if parts.isEmpty { return GridPlan.empty(kind) }
        var lines: [GridLine] = []
        var labels: [GridLabel] = []
        let whole = Rect(left: 0.0, top: 0.0, right: frame.frameWidthMm, bottom: frame.frameHeightMm)
        for (i, part) in parts.enumerated() {
            let system = ProjectedSystem.utm(part.zone, north, mgrs, band, part.shift)
            guard let sampled = drawLines(frame, system, spacing) else { return GridPlan.empty(kind) }
            for line in sampled {
                let own = clipped(line.raw, part.rect, frame, line.major)
                lines += own.lines
                for (edge, pos) in own.edges {
                    if let label = line.label(edge, pos, locale, neighbour: false) { labels.append(label) }
                }
                if parts.count < 2 { continue }
                // The line carried on: its ends on the margin of an adjacent zone's part.
                let carried = clipped(line.raw, whole, frame, false)
                for (edge, pos) in carried.edges {
                    let p = edgePoint(edge, pos, frame)
                    if part.rect.contains(p) { continue }
                    let adjacent = parts.indices.contains { j in
                        j != i && parts[j].rect.contains(p) && parts[j].rect.touches(part.rect)
                    }
                    if !adjacent { continue }
                    if let label = line.label(edge, pos, locale, neighbour: true) { labels.append(label) }
                }
            }
        }
        let centerZone = Utm.zoneOf(lat: frame.centerLat, lon: frame.centerLon)
        let gridZones = GridZones.meeting(area)
        var zones: [String] = []
        if mgrs {
            zones = gridZones.map(\.name)
        } else {
            for zone in gridZones {
                let name = "\(zone.zone)\(north ? "N" : "S")"
                if !zones.contains(name) { zones.append(name) }
            }
        }
        let edges = GridZones.edges(area).compactMap { zoneEdge(frame, $0) }
        let sides = mgrs && gridZones.count == 2 ? sidesOf(frame, gridZones) : [:]
        var squares: [String] = []
        var tags: [SquareTag] = []
        if mgrs {
            (squares, tags) = squaresOf(frame, gridZones.count > 1, edges)
        }
        let centre = ProjectedSystem.utm(centerZone, north, mgrs, band, 0.0)
        return GridPlan(
            kind: kind,
            lines: lines,
            labels: labels,
            stepText: centre.stepText(spacing, locale),
            systemText: centre.systemText,
            issue: .none,
            step: spacing,
            zoneText: "\(centerZone)\(band)",
            squares: squares,
            squareTags: tags,
            zones: zones,
            zoneEdges: edges,
            zoneSides: sides
        )
    }

    /// A grid line of one value, sampled across the whole sheet, in frame millimeters.
    private struct SampledLine {
        let raw: [FramePoint]
        let major: Bool
        let system: any PrintGridSystem
        let vertical: Bool
        let index: Int
        let value: Double
        let spacing: Double

        /// The label where the line meets `edge`: a line of constant x is written on the top and bottom only.
        func label(_ edge: FrameSide, _ pos: Double, _ locale: Locale, neighbour: Bool) -> GridLabel? {
            let across = edge == .top || edge == .bottom
            if across != vertical { return nil }
            let text = vertical ? system.labelX(value, spacing, locale) : system.labelY(value, spacing, locale)
            return GridLabel(
                side: edge,
                positionMm: pos,
                text: text,
                index: index,
                parts: system.parts(value),
                value: value,
                neighbour: neighbour
            )
        }
    }

    /// Samples every line of `system` at `spacing` over the sheet; nil when there would be too many of them (nothing
    /// drawn).
    private static func drawLines(
        _ frame: FrameProjection,
        _ system: any PrintGridSystem,
        _ spacing: Double
    ) -> [SampledLine]? {
        let corners = perimeter(frame).map { system.toGrid($0.lat, $0.lon) }
        let xs = corners.map { $0.0 }
        let ys = corners.map { $0.1 }
        let minX = xs.min() ?? 0
        let maxX = xs.max() ?? 0
        let minY = ys.min() ?? 0
        let maxY = ys.max() ?? 0
        if Swift.max(maxX - minX, maxY - minY) <= 0.0 { return nil }
        guard let valuesX = multiples(minX, maxX, spacing), let valuesY = multiples(minY, maxY, spacing) else {
            return nil
        }
        let diagonal = (frame.frameWidthMm * frame.frameWidthMm + frame.frameHeightMm * frame.frameHeightMm).squareRoot()
        let sampleCount = Swift.max(2, kotlinToInt(ceil(diagonal / sampleMm))) + 1
        let projected = system.isProjected
        var out: [SampledLine] = []
        for (i, x) in valuesX {
            var raw: [FramePoint] = []
            for s in 0 ..< sampleCount {
                let p = system.fromGrid(x, minY + (maxY - minY) * Double(s) / Double(sampleCount - 1))
                raw.append(frame.toFrame(p.lon, p.lat))
            }
            out.append(
                SampledLine(
                    raw: raw,
                    major: projected && isSquareEdge(x),
                    system: system,
                    vertical: true,
                    index: i,
                    value: x,
                    spacing: spacing
                )
            )
        }
        for (i, y) in valuesY {
            var raw: [FramePoint] = []
            for s in 0 ..< sampleCount {
                let p = system.fromGrid(minX + (maxX - minX) * Double(s) / Double(sampleCount - 1), y)
                raw.append(frame.toFrame(p.lon, p.lat))
            }
            out.append(
                SampledLine(
                    raw: raw,
                    major: projected && isSquareEdge(y),
                    system: system,
                    vertical: false,
                    index: i,
                    value: y,
                    spacing: spacing
                )
            )
        }
        return out
    }

    /// A rectangle of the frame, in millimeters.
    private struct Rect {
        let left: Double
        let top: Double
        let right: Double
        let bottom: Double

        func contains(_ p: FramePoint) -> Bool {
            let t = GridPlanner.edgeToleranceMm
            return p.xMm >= left - t && p.xMm <= right + t && p.yMm >= top - t && p.yMm <= bottom + t
        }

        /// Sharing a piece of side: a zone edge between them.
        func touches(_ other: Rect) -> Bool {
            let t = GridPlanner.edgeToleranceMm
            let overlapX = Swift.min(right, other.right) - Swift.max(left, other.left)
            let overlapY = Swift.min(bottom, other.bottom) - Swift.max(top, other.top)
            let sideBySide = abs(right - other.left) <= t || abs(other.right - left) <= t
            let stacked = abs(bottom - other.top) <= t || abs(other.bottom - top) <= t
            return (sideBySide && overlapY > t) || (stacked && overlapX > t)
        }
    }

    /// The part of the sheet with one zone's grid: the frame is a Web Mercator rectangle, the zone's sides are its.
    private struct ZonePart {
        let zone: Int
        let shift: Double
        let rect: Rect

        static func of(_ frame: FrameProjection, _ piece: ZonePiece) -> ZonePart? {
            let nw = frame.toFrame(piece.area.minX, piece.area.maxY)
            let se = frame.toFrame(piece.area.maxX, piece.area.minY)
            let rect = Rect(
                left: Swift.max(0.0, nw.xMm),
                top: Swift.max(0.0, nw.yMm),
                right: Swift.min(frame.frameWidthMm, se.xMm),
                bottom: Swift.min(frame.frameHeightMm, se.yMm)
            )
            let t = GridPlanner.edgeToleranceMm
            if rect.right - rect.left <= t || rect.bottom - rect.top <= t { return nil }
            return ZonePart(zone: piece.zone, shift: piece.shift, rect: rect)
        }
    }

    private static func edgePoint(_ edge: FrameSide, _ pos: Double, _ frame: FrameProjection) -> FramePoint {
        switch edge {
        case .top: FramePoint(xMm: pos, yMm: 0.0)
        case .bottom: FramePoint(xMm: pos, yMm: frame.frameHeightMm)
        case .left: FramePoint(xMm: 0.0, yMm: pos)
        case .right: FramePoint(xMm: frame.frameWidthMm, yMm: pos)
        }
    }

    /// A zone edge on the sheet, with what lies on either side of it.
    private static func zoneEdge(_ frame: FrameProjection, _ edge: [LonLat]) -> ZoneEdge? {
        guard let a = edge.first, let b = edge.last else { return nil }
        let meridian = a.lon == b.lon
        let pa = frame.toFrame(a.lon, a.lat)
        let pb = frame.toFrame(b.lon, b.lat)
        let whole = Rect(left: 0.0, top: 0.0, right: frame.frameWidthMm, bottom: frame.frameHeightMm)
        guard let run = clip(pa, pb, whole) else { return nil }
        var sides: (String, String)?
        if meridian {
            let lat = (a.lat + b.lat) / 2
            if let west = GridZones.of(lat, a.lon - sideStep)?.zone, let east = GridZones.of(lat, a.lon + sideStep)?.zone {
                sides = (String(west), String(east))
            }
        } else {
            if let south = Utm.bandLetter(a.lat - sideStep), let north = Utm.bandLetter(a.lat + sideStep) {
                sides = (String(south), String(north))
            }
        }
        let degrees = meridian ? Utm.normalizeLon(a.lon) : a.lat
        return ZoneEdge(line: GridLine(points: [run.0, run.1], major: true), meridian: meridian, degrees: degrees, sides: sides)
    }

    /// West and east, or south and north, of the two grid zones of a sheet.
    private static func sidesOf(_ frame: FrameProjection, _ zones: [GridZone]) -> [String: CompassSide] {
        let a = zones[0]
        let b = zones[1]
        let ca = frame.toFrame((a.west + a.east) / 2, (a.south + a.north) / 2)
        let cb = frame.toFrame((b.west + b.east) / 2, (b.south + b.north) / 2)
        // Zones side by side differ in longitude, bands one above the other in latitude.
        if a.zone != b.zone {
            let aWest = ca.xMm < cb.xMm
            return [a.name: aWest ? .west : .east, b.name: aWest ? .east : .west]
        }
        let aNorth = ca.yMm < cb.yMm
        return [a.name: aNorth ? .north : .south, b.name: aNorth ? .south : .north]
    }

    /// The graduated band of the frame, in the grid of the sheet and at its spacing: a bar between two of its lines,
    /// black and white in turn. Nothing without a grid. Until 2026-10-28 the band was in the other system (degrees for a
    /// projected grid, UTM kilometers for a graticule); the user wants the chosen grid alone on the sheet (ADR-087).
    public static func graduation(
        _ frame: FrameProjection,
        _ grid: GridKind,
        step: Double? = nil,
        locale: Locale = .current
    ) -> Graduation {
        let gridPlan = plan(frame, grid, step: step, locale: locale)
        if gridPlan.labels.isEmpty { return Graduation.empty }
        var bars: [GraduationBar] = []
        for side in FrameSide.allCases {
            let length = side == .top || side == .bottom ? frame.frameWidthMm : frame.frameHeightMm
            let ticks = gridPlan.labels.filter { $0.side == side && !$0.neighbour }.sorted { $0.positionMm < $1.positionMm }
            if ticks.isEmpty { continue }
            // Along the left and right sides the values grow upwards, against the frame's y.
            let upwards = side == .left || side == .right
            var start = 0.0
            for (n, tick) in ticks.enumerated() {
                let before = upwards ? tick.index : tick.index - 1
                bars.append(
                    GraduationBar(
                        side: side,
                        startMm: start,
                        endMm: tick.positionMm,
                        black: PrintLocaleFormat.floorMod(before, 2) == 0
                    )
                )
                start = tick.positionMm
                if n == ticks.count - 1 {
                    let after = upwards ? tick.index - 1 : tick.index
                    bars.append(
                        GraduationBar(
                            side: side,
                            startMm: start,
                            endMm: length,
                            black: PrintLocaleFormat.floorMod(after, 2) == 0
                        )
                    )
                }
            }
        }
        return Graduation(
            kind: grid,
            step: gridPlan.step,
            stepText: gridPlan.stepText,
            bars: bars.filter { $0.endMm - $0.startMm > 0.01 }
        )
    }

    /// The angle between true north and the north of the grid at the centre of `frame`, in degrees, positive when grid
    /// north lies east of true north (so the grid north arrow turns clockwise on the page); nil for a grid that has
    /// none or is not valid there. UTM: `Utm.convergenceDegrees`; Lambert-93: `n * dLon`.
    public static func convergenceDegrees(_ frame: FrameProjection, _ kind: GridKind) -> Double? {
        convergenceDegrees(kind, frame.centerLat, frame.centerLon)
    }

    /// The same angle at a position: the centre of a sheet, or of the screen (P24.3).
    public static func convergenceDegrees(_ kind: GridKind, _ lat: Double, _ lon: Double) -> Double? {
        switch kind {
        case .utm, .mgrs:
            if lat > utmMaxLat || lat < utmMinLat { return nil }
            return Utm.convergenceDegrees(lat: lat, lon: lon)
        case .lambert93:
            if franceLat.contains(lat), franceLon.contains(lon) {
                return lambert93ConeConstant * (lon - lambert93CentralMeridian)
            }
            return nil
        case .none, .wgs84, .wgs84Decimal:
            return nil
        }
    }

    /// The 100 km square of the frame centre, for the legend of an MGRS grid (`31T DQ`).
    public static func mgrsSquare(_ frame: FrameProjection) -> String? {
        guard let ref = Mgrs.of(frame.centerLat, frame.centerLon) else { return nil }
        return "\(ref.zone)\(ref.band) \(ref.columnLetter)\(ref.rowLetter)"
    }

    private static func isSquareEdge(_ value: Double) -> Bool {
        let q = value / squareMeters
        return abs(q - q.rounded(.toNearestOrEven)) < 1e-9
    }

    /// The squares under the frame, by sampling it, each in the zone of the place (P24.4: the letters are right on
    /// both sides of a zone edge); and a tag in each when there are several. On a sheet across zones (`qualified`) a
    /// square is named with its zone (`31T GL`), and the tag of a square along a zone edge stands beside it, as in the
    /// P24.0 preview.
    private static func squaresOf(
        _ frame: FrameProjection,
        _ qualified: Bool,
        _ edges: [ZoneEdge]
    ) -> ([String], [SquareTag]) {
        var names: [String] = []
        var samples: [String: [FramePoint]] = [:]
        let columns = squareSamples
        let rows = squareSamples
        for r in 0 ... rows {
            for c in 0 ... columns {
                let p = FramePoint(
                    xMm: frame.frameWidthMm * Double(c) / Double(columns),
                    yMm: frame.frameHeightMm * Double(r) / Double(rows)
                )
                let ll = frame.toLonLat(p.xMm, p.yMm)
                guard let ref = Mgrs.of(ll.lat, Utm.normalizeLon(ll.lon)) else { continue }
                let letters = "\(ref.columnLetter)\(ref.rowLetter)"
                let name = qualified ? "\(ref.zone)\(ref.band) \(letters)" : letters
                if samples[name] == nil { names.append(name) }
                samples[name, default: []].append(p)
            }
        }
        if names.count < 2 { return (names, []) }
        let column = frame.frameWidthMm / Double(columns)
        let meridians = edges.filter(\.meridian).compactMap { $0.line.points.first?.xMm }
        var tags: [SquareTag] = []
        for name in names {
            let points = samples[name] ?? []
            let count = Double(points.count)
            let y = points.reduce(0.0) { $0 + $1.yMm } / count
            let eastmost = points.map(\.xMm).max() ?? 0
            let westmost = points.map(\.xMm).min() ?? 0
            // A sample column at most from the edge: the square runs along it, on one side.
            let before = meridians.first { $0 > eastmost && $0 - eastmost <= column + edgeToleranceMm }
            let after = meridians.first { $0 < westmost && westmost - $0 <= column + edgeToleranceMm }
            if qualified, let before {
                tags.append(SquareTag(letters: name, xMm: before - tagGapMm, yMm: y, align: .end))
            } else if qualified, let after {
                tags.append(SquareTag(letters: name, xMm: after + tagGapMm, yMm: y, align: .start))
            } else {
                tags.append(SquareTag(letters: name, xMm: points.reduce(0.0) { $0 + $1.xMm } / count, yMm: y))
            }
        }
        return (names, tags)
    }

    /// Frame outline sampled every few millimeters, as positions (the frame is curved in the grid's eyes).
    private static func perimeter(_ frame: FrameProjection) -> [LonLat] {
        let n = 24
        var out: [LonLat] = []
        for i in 0 ... n {
            let t = Double(i) / Double(n)
            out.append(frame.toLonLat(frame.frameWidthMm * t, 0.0))
            out.append(frame.toLonLat(frame.frameWidthMm * t, frame.frameHeightMm))
            out.append(frame.toLonLat(0.0, frame.frameHeightMm * t))
            out.append(frame.toLonLat(frame.frameWidthMm, frame.frameHeightMm * t))
        }
        return out
    }

    /// The multiples of `step` between `min` and `max`, with their index (value / step).
    private static func multiples(_ min: Double, _ max: Double, _ step: Double) -> [(Int, Double)]? {
        let first = kotlinToLong(ceil(min / step - 1e-9))
        let last = kotlinToLong(floor(max / step + 1e-9))
        if last &- first > maxLinesPerAxis { return nil }
        if last < first { return [] }
        var out: [(Int, Double)] = []
        for k in first ... last {
            out.append((k, Double(k) * step))
        }
        return out
    }

    /// Clips the polyline `raw` (frame millimeters) to `rect`: the inside runs, and where the ends of the runs sit on a
    /// frame edge (to write the labels there), in the order the Kotlin version calls its `onEdge`.
    private static func clipped(
        _ raw: [FramePoint],
        _ rect: Rect,
        _ frame: FrameProjection,
        _ major: Bool
    ) -> (lines: [GridLine], edges: [(FrameSide, Double)]) {
        var lines: [GridLine] = []
        var edges: [(FrameSide, Double)] = []
        var run: [FramePoint] = []
        func flush() {
            if run.count >= 2, let first = run.first, let last = run.last {
                lines.append(GridLine(points: run, major: major))
                if let edge = edgeLabel(first, frame) { edges.append(edge) }
                if let edge = edgeLabel(last, frame) { edges.append(edge) }
            }
            run = []
        }
        if raw.count >= 2 {
            for i in 0 ..< raw.count - 1 {
                guard let seg = clip(raw[i], raw[i + 1], rect) else {
                    flush()
                    continue
                }
                if let last = run.last, distance(last, seg.0) > edgeToleranceMm { flush() }
                if run.isEmpty { run.append(seg.0) }
                run.append(seg.1)
            }
        }
        flush()
        return (lines, edges)
    }

    private static func edgeLabel(_ p: FramePoint, _ frame: FrameProjection) -> (FrameSide, Double)? {
        if abs(p.yMm) <= edgeToleranceMm { return (.top, p.xMm) }
        if abs(p.yMm - frame.frameHeightMm) <= edgeToleranceMm { return (.bottom, p.xMm) }
        if abs(p.xMm) <= edgeToleranceMm { return (.left, p.yMm) }
        if abs(p.xMm - frame.frameWidthMm) <= edgeToleranceMm { return (.right, p.yMm) }
        return nil
    }

    private static func distance(_ a: FramePoint, _ b: FramePoint) -> Double {
        let dx = a.xMm - b.xMm
        let dy = a.yMm - b.yMm
        return (dx * dx + dy * dy).squareRoot()
    }

    /// Liang–Barsky: the part of the segment inside `rect`, or nil.
    private static func clip(_ a: FramePoint, _ b: FramePoint, _ rect: Rect) -> (FramePoint, FramePoint)? {
        var t0 = 0.0
        var t1 = 1.0
        let dx = b.xMm - a.xMm
        let dy = b.yMm - a.yMm
        let p = [-dx, dx, -dy, dy]
        let q = [a.xMm - rect.left, rect.right - a.xMm, a.yMm - rect.top, rect.bottom - a.yMm]
        for i in 0 ..< 4 {
            if p[i] == 0.0 {
                if q[i] < 0 { return nil }
            } else {
                let t = q[i] / p[i]
                if p[i] < 0 {
                    if t > t1 { return nil }
                    t0 = Swift.max(t0, t)
                } else {
                    if t < t0 { return nil }
                    t1 = Swift.min(t1, t)
                }
            }
        }
        return (
            FramePoint(xMm: a.xMm + t0 * dx, yMm: a.yMm + t0 * dy),
            FramePoint(xMm: a.xMm + t1 * dx, yMm: a.yMm + t1 * dy)
        )
    }

    private static func systemFor(_ frame: FrameProjection, _ kind: GridKind) -> (any PrintGridSystem)? {
        switch kind {
        case .none:
            return nil
        case .wgs84:
            return DmsSystem()
        case .wgs84Decimal:
            return DecimalSystem()
        // Planned zone by zone (`zoned`).
        case .utm, .mgrs:
            return nil
        case .lambert93:
            let inFrance = franceLat.contains(frame.centerLat) && franceLon.contains(frame.centerLon)
            return inFrance ? ProjectedSystem.lambert() : nil
        }
    }

    /// The 1, 2 or 5 × 10^n closest to `target` on a logarithmic scale (a line every 2 or 5 km, never every 3).
    static func nearestNice(_ target: Double) -> Double {
        if !(target > 0.0) { return 0.0 }
        let base = Foundation.pow(10.0, floor(log10(target)))
        let candidates = [1.0, 2.0, 5.0, 10.0].map { $0 * base }
        var best = candidates[0]
        for candidate in candidates.dropFirst() where abs(log(candidate / target)) < abs(log(best / target)) {
            best = candidate
        }
        return best
    }
}
