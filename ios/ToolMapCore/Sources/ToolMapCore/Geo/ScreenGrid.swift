// Port of core/src/main/kotlin/fr/toolmap/core/geo/ScreenGrid.kt (the grid drawn over the map, P9.3, P24.2, P24.3).

import Foundation

/// The grid drawn over the map for the visible region: lines as GeoJSON, and what to say about it in a chip.
public struct ScreenGridPlan: Sendable {
    public static let emptyGeoJson = #"{"type":"FeatureCollection","features":[]}"#

    public let kind: GridKind
    /// A FeatureCollection of LineStrings; `level` (`GridLineLevel`) tells how each one is drawn.
    public let geoJson: String
    /// The spacing of the lines in the unit of the grid (`1 km`, `30′`), empty when there are no grid lines (a UTM
    /// view too wide for them has its zone edges alone).
    public let stepText: String
    /// The coordinate system (`UTM 31T`, `Lambert 93`), empty when there are no grid lines.
    public let systemText: String
    /// Why there is no grid, or a caution about it.
    public let issue: ScreenGridIssue
    /// UTM and MGRS: a FeatureCollection of Points with the names of the zones and squares in view (P24.3).
    public let labelsGeoJson: String
    /// The angle from true north to grid north at the centre, east positive (UTM, MGRS, Lambert 93 in France); nil
    /// when the chip is not shown or the grid has none there.
    public let convergenceDegrees: Double?
    /// UTM: the kilometre lines in view, whose values are written where they leave the screen (variant A of
    /// `docs/previews/p24-utm-reperes.html`).
    public let figureLines: [GridFigureLine]

    public init(
        kind: GridKind,
        geoJson: String,
        stepText: String,
        systemText: String,
        issue: ScreenGridIssue = .none,
        labelsGeoJson: String = ScreenGridPlan.emptyGeoJson,
        convergenceDegrees: Double? = nil,
        figureLines: [GridFigureLine] = []
    ) {
        self.kind = kind
        self.geoJson = geoJson
        self.stepText = stepText
        self.systemText = systemText
        self.issue = issue
        self.labelsGeoJson = labelsGeoJson
        self.convergenceDegrees = convergenceDegrees
        self.figureLines = figureLines
    }

    public var isEmpty: Bool { stepText.isEmpty }

    public static func none(_ kind: GridKind = GridKind.none, _ issue: ScreenGridIssue = ScreenGridIssue.none) -> ScreenGridPlan {
        ScreenGridPlan(kind: kind, geoJson: emptyGeoJson, stepText: "", systemText: "", issue: issue)
    }
}

/// A kilometre line of the UTM grid, cut at the edges of its zone and of the region: its value is written where it
/// leaves the screen, the eastings at the bottom and the northings on the left, as on a paper map.
public struct GridFigureLine: Equatable, Sendable {
    private static let km = 1000.0

    /// A line of constant easting, running north-south.
    public let easting: Bool
    public let meters: Double
    public let points: [LonLat]

    public init(easting: Bool, meters: Double, points: [LonLat]) {
        self.easting = easting
        self.meters = meters
        self.points = points
    }

    /// « 50 » and « 45 » for 5045 km: the figures before the last two, written smaller, and the last two.
    public var figures: (String, String) {
        let km = String(kotlinRound(meters / GridFigureLine.km))
        if km.count <= 2 { return ("", km) }
        return (String(km.dropLast(2)), String(km.suffix(2)))
    }
}

public enum ScreenGridIssue: Sendable {
    case none

    /// The region is beyond the latitudes UTM and MGRS are defined for (80 S .. 84 N).
    case polar

    /// Lambert 93 is drawn, but the region is outside the area it was made for (France).
    case outsideValidity

    /// The region is too wide for a useful grid at any step.
    case tooWide
}

/// How a grid line is drawn: the `level` property of its feature in `ScreenGridPlan.geoJson`.
public enum GridLineLevel {
    /// WGS84, Lambert 93, and UTM under 1 km: a line of the step.
    public static let minor = 0

    /// WGS84, Lambert 93, and UTM under 1 km: every fifth line.
    public static let major = 1

    /// UTM and MGRS from 1 km: a line of the step (P24.2, variant A of the P24.0 preview).
    public static let step = 2

    /// UTM and MGRS from 1 km: a 10 km line.
    public static let tenKm = 3

    /// UTM and MGRS from 1 km: a 100 km line, the edge of an MGRS square.
    public static let square = 4

    /// UTM and MGRS: the edge of a grid zone, a 6° meridian or the parallel between two latitude bands.
    public static let zoneEdge = 5
}

/// How a name of the grid is written: the `kind` property of its feature in `ScreenGridPlan.labelsGeoJson`.
public enum GridLabelKind {
    /// « 31T GL »: a zone with one square in view (« 31T » in UTM), just above the middle of the square.
    public static let name = "name"

    /// « 31T »: a zone with several squares in view, larger, in the middle of its part of the screen.
    public static let zone = "zone"

    /// « GL »: the letters of a square of such a zone, just below the middle of its part of the screen.
    public static let square = "square"
}

/// Plans the grid of the visible region (feature P9.3). The lines are straight in their own system and slightly
/// curved on the Web Mercator map: each one is sampled and projected back to longitude and latitude. UTM and MGRS draw
/// each grid zone in view with its own grid, cut at the zone's edges, and the edges themselves (P24.2), with the names
/// of the zones and of the MGRS squares (P24.3, ADR-073). The chip tells the system, the spacing and the convergence;
/// the coordinates of the centre are in the box of the screen.
public enum ScreenGrid {
    /// Roughly this many lines across the longest side of the region.
    private static let targetLines = 6.0
    private static let maxLines = 40.0
    private static let samples = 24
    private static let firstStepMeters = 10.0
    private static let maxStepMeters = 1_000_000.0
    private static let majorEvery = 5
    private static let squareMeters = 100_000.0
    private static let utmMaxLat = 84.0
    private static let utmMinLat = -80.0
    private static let mgrsMinStep = 1000.0
    private static let tenKmMeters = 10_000.0

    /// Samples of the screen for the names: across its narrower side, and at most along the other.
    private static let labelSamples = 13
    private static let maxLabelSamples = 40

    /// A square is named when it takes this share of the screen: 6 of the 13 × 25 samples of the preview.
    private static let squareShare = 6.0 / 325
    private static let eps = 1e-9

    /// Angular steps of the WGS84 graticule in degrees: 5″ 10″ 30″ 1′ 2′ 5′ 10′ 30′ 1° 2° 5° 10° 20°.
    private static let degreeSteps: [Double] = [
        5.0 / 3600,
        10.0 / 3600,
        30.0 / 3600,
        1.0 / 60,
        2.0 / 60,
        5.0 / 60,
        10.0 / 60,
        30.0 / 60,
        1.0,
        2.0,
        5.0,
        10.0,
        20.0,
    ]

    /// The grid of `kind` over `area` (west, south, east, north in degrees). `spacing` scales the step between two
    /// lines: 2 gives half as many lines, 0.5 twice as many (« Densité des grilles », P11.8).
    public static func plan(_ kind: GridKind, _ area: Bounds, spacing: Double = 1.0) -> ScreenGridPlan {
        if kind == .none { return ScreenGridPlan.none() }
        if area.maxX <= area.minX || area.maxY <= area.minY { return ScreenGridPlan.none(kind) }
        let centerLat = (area.minY + area.maxY) / 2
        let centerLon = (area.minX + area.maxX) / 2
        switch kind {
        case .none:
            return ScreenGridPlan.none()
        // The screen draws one graticule; the decimal one only changes how a print writes its labels.
        case .wgs84, .wgs84Decimal:
            return graticule(area, spacing)
        case .utm, .mgrs:
            if area.maxY > utmMaxLat || area.minY < utmMinLat {
                return ScreenGridPlan.none(kind, .polar)
            }
            return zoned(kind, area, centerLat, centerLon, spacing)
        case .lambert93:
            return lambert(area, !Lambert93.covers(centerLat, centerLon), spacing)
        }
    }

    private static func graticule(_ area: Bounds, _ spacing: Double) -> ScreenGridPlan {
        let span = Swift.max(area.maxX - area.minX, area.maxY - area.minY)
        let step = degreeSteps.first { span / $0 <= targetLines * 1.5 / spacing } ?? degreeSteps[degreeSteps.count - 1]
        if span / step > maxLines { return ScreenGridPlan.none(.wgs84, .tooWide) }
        var features: [String] = []
        let firstLon = kotlinToLong(ceil(area.minX / step))
        let lastLon = kotlinToLong(floor(area.maxX / step))
        for k in stride(from: firstLon, through: lastLon, by: 1) {
            let lon = Double(k) * step
            features.append(
                line([LonLat(lon: lon, lat: area.minY), LonLat(lon: lon, lat: area.maxY)], graticuleLevel(k))
            )
        }
        let firstLat = kotlinToLong(ceil(area.minY / step))
        let lastLat = kotlinToLong(floor(area.maxY / step))
        for k in stride(from: firstLat, through: lastLat, by: 1) {
            let lat = Double(k) * step
            features.append(
                line([LonLat(lon: area.minX, lat: lat), LonLat(lon: area.maxX, lat: lat)], graticuleLevel(k))
            )
        }
        return ScreenGridPlan(kind: .wgs84, geoJson: collection(features), stepText: degreesText(step), systemText: "WGS84")
    }

    private static func graticuleLevel(_ k: Int) -> Int {
        k % majorEvery == 0 ? GridLineLevel.major : GridLineLevel.minor
    }

    /// UTM and MGRS: each grid zone in view draws its own grid, cut at its edges, and the edges are drawn too (P24.2).
    /// The chip names the zone of the centre.
    private static func zoned(
        _ kind: GridKind,
        _ area: Bounds,
        _ centerLat: Double,
        _ centerLon: Double,
        _ spacing: Double
    ) -> ScreenGridPlan {
        let zone = Utm.zoneOf(lat: centerLat, lon: centerLon)
        // One hemisphere for the whole region: its northings run on across the equator.
        let north = centerLat >= 0
        let band = Utm.bandLetter(centerLat) ?? "N"
        let name = kind == .mgrs ? "MGRS \(zone)\(band)" : "UTM \(zone)\(band)"
        let edges = GridZones.edges(area).map { line($0, GridLineLevel.zoneEdge) }
        let labels = zoneLabels(kind, area)
        // The step comes from the region measured in the grid of the centre zone, as before P24.2. Past 100 km, one
        // line of the MGRS squares in two would be missing: the step stops there (variant A of the P24.0 preview).
        let extent = extentOf(area, utmForward(zone, north))
        let minStep = kind == .mgrs ? mgrsMinStep : firstStepMeters
        let step = Swift.min(niceStep(extent.span / targetLines * spacing, minStep), squareMeters)
        // Too wide for grid lines: the zone edges and the names alone, without a chip or a note.
        if extent.span / step > maxLines {
            return ScreenGridPlan(kind: kind, geoJson: collection(edges), stepText: "", systemText: "", labelsGeoJson: labels)
        }

        // Variant A: the 10 and 100 km lines are heavier at every step, so they stay as they were when it grows.
        func level(_ value: Double) -> Int {
            // Under 1 km (UTM only, not in the preview): every fifth line, as before P24.2.
            if step < mgrsMinStep {
                return isMultiple(value, step * Double(majorEvery)) ? GridLineLevel.major : GridLineLevel.minor
            }
            if isMultiple(value, squareMeters) { return GridLineLevel.square }
            if isMultiple(value, tenKmMeters) { return GridLineLevel.tenKm }
            return GridLineLevel.step
        }
        var features: [String] = []
        var figures: [GridFigureLine] = []
        for piece in GridZones.pieces(area) {
            // The piece in the longitudes of its zone, those of the screen less the shift of its world copy.
            let own = Bounds(
                minX: piece.area.minX - piece.shift,
                minY: piece.area.minY,
                maxX: piece.area.maxX - piece.shift,
                maxY: piece.area.maxY
            )
            let meridian = Utm.centralMeridian(piece.zone)
            let pieceZone = piece.zone
            let inverse: (Double, Double) -> LonLat = { x, y in
                // Unwrapped around the central meridian: zone 1 carried past 180° W stays below -180.
                let p = Utm.toLonLat(UtmCoordinate(zone: pieceZone, north: north, easting: x, northing: y))
                return LonLat(lon: meridian + Utm.normalizeLon(p.lon - meridian), lat: p.lat)
            }
            let straight = straightLines(extentOf(own, utmForward(piece.zone, north)), step, inverse)
            for line in straight {
                for part in clip(line.points, own) {
                    let shown = part.map { LonLat(lon: $0.lon + piece.shift, lat: $0.lat) }
                    features.append(Self.line(shown, level(line.value)))
                    // Variant A of the UTM preview: the kilometre lines only, so none is left on a very close view.
                    if kind == .utm, isMultiple(line.value, mgrsMinStep) {
                        figures.append(GridFigureLine(easting: line.constantX, meters: line.value, points: shown))
                    }
                }
            }
        }
        features += edges
        return ScreenGridPlan(
            kind: kind,
            geoJson: collection(features),
            stepText: ScaleBar.format(step),
            systemText: name,
            // UTM: the figures at the edge take the place of the zone names (2026-10-28).
            labelsGeoJson: kind == .utm ? ScreenGridPlan.emptyGeoJson : labels,
            convergenceDegrees: GridPlanner.convergenceDegrees(kind, centerLat, centerLon),
            figureLines: figures
        )
    }

    /// A place on the screen where a name goes: the middle of the samples that fell in its zone or square.
    private struct Spot {
        var lon = 0.0
        var y = 0.0
        var count = 0

        mutating func add(_ lon: Double, _ y: Double) {
            self.lon += lon
            self.y += y
            count += 1
        }

        func at() -> LonLat {
            LonLat(lon: lon / Double(count), lat: ScreenGrid.latitudeOf(y / Double(count)))
        }
    }

    /// Spots in the order they are first met (Kotlin `LinkedHashMap`).
    private struct Spots {
        var keys: [String] = []
        var spots: [String: Spot] = [:]

        mutating func add(_ key: String, _ lon: Double, _ y: Double) {
            if spots[key] == nil { keys.append(key) }
            spots[key, default: Spot()].add(lon, y)
        }
    }

    /// The names of the zones and of the MGRS squares in view, placed as in the P24.0 preview: the screen is sampled
    /// (13 points across its narrower side); a zone with one square in view is named with it (« 31T GL ») above the
    /// middle of the square, otherwise the zone is named in the middle of its part of the screen (« 31T ») and each
    /// square that takes enough of it is named below its middle (« GL »). UTM names the zones only, and keeps them
    /// only on a view too wide for its lines: closer, the figures at the edge of the screen take their place.
    private static func zoneLabels(_ kind: GridKind, _ area: Bounds) -> String {
        let south = mercatorY(area.minY)
        let north = mercatorY(area.maxY)
        let width = Angle.radians(area.maxX - area.minX)
        let height = north - south
        let ratio = Swift.max(width, height) / Swift.min(width, height)
        let across = Swift.min(Swift.max(kotlinRound(Double(labelSamples) * ratio), 1), maxLabelSamples)
        let columns = width <= height ? labelSamples : across
        let rows = width <= height ? across : labelSamples
        var zones = Spots()
        var squares = Spots()
        var squaresOfZone: [String: [String]] = [:]
        for i in 0 ..< columns {
            let lon = area.minX + (area.maxX - area.minX) * (Double(i) + 0.5) / Double(columns)
            for j in 0 ..< rows {
                let y = south + height * (Double(j) + 0.5) / Double(rows)
                guard let ref = Mgrs.of(latitudeOf(y), lon) else { continue }
                let zone = "\(ref.zone)\(ref.band)"
                let square = "\(zone) \(ref.columnLetter)\(ref.rowLetter)"
                zones.add(zone, lon, y)
                squares.add(square, lon, y)
                var inZone = squaresOfZone[zone] ?? []
                if !inZone.contains(square) { inZone.append(square) }
                squaresOfZone[zone] = inZone
            }
        }
        let enough = Double(columns * rows) * squareShare
        var features: [String] = []
        for zone in zones.keys {
            guard let spot = zones.spots[zone] else { continue }
            let inZone = squaresOfZone[zone] ?? []
            if kind != .mgrs {
                features.append(label(spot.at(), zone, GridLabelKind.name))
            } else if inZone.count == 1, let only = inZone.first, let square = squares.spots[only] {
                features.append(label(square.at(), only, GridLabelKind.name))
            } else {
                features.append(label(spot.at(), zone, GridLabelKind.zone))
                for square in inZone {
                    guard let s = squares.spots[square] else { continue }
                    if Double(s.count) > enough {
                        features.append(label(s.at(), square.substringAfter(" ", missing: square), GridLabelKind.square))
                    }
                }
            }
        }
        return collection(features)
    }

    private static func mercatorY(_ lat: Double) -> Double {
        log(tan(Double.pi / 4 + Angle.radians(lat) / 2))
    }

    fileprivate static func latitudeOf(_ y: Double) -> Double {
        Angle.degrees(2 * atan(exp(y)) - Double.pi / 2)
    }

    private static func label(_ at: LonLat, _ text: String, _ kind: String) -> String {
        let lon = formatFixed(at.lon, 7)
        let lat = formatFixed(at.lat, 7)
        var out = #"{"type":"Feature","properties":{"text":""#
        out += text
        out += #"","kind":""#
        out += kind
        out += #""},"geometry":{"type":"Point","coordinates":["#
        out += lon
        out += ","
        out += lat
        out += "]}}"
        return out
    }

    private static func utmForward(_ zone: Int, _ north: Bool) -> (Double, Double) -> (Double, Double) {
        { lat, lon in
            let u = Utm.toUtm(lat, lon, zone: zone, north: north)
            return (u.easting, u.northing)
        }
    }

    private static func lambert(_ area: Bounds, _ outside: Bool, _ spacing: Double) -> ScreenGridPlan {
        let extent = extentOf(area) { lat, lon in
            let p = Lambert93.toLambert(lat, lon)
            return (p.easting, p.northing)
        }
        let step = niceStep(extent.span / targetLines * spacing, firstStepMeters)
        if step > maxStepMeters || extent.span / step > maxLines {
            return ScreenGridPlan.none(.lambert93, .tooWide)
        }
        let straight = straightLines(extent, step) { x, y in Lambert93.toLonLat(PlanarCoordinate(easting: x, northing: y)) }
        let features = straight.map { s in
            line(s.points, isMultiple(s.value, step * Double(majorEvery)) ? GridLineLevel.major : GridLineLevel.minor)
        }
        return ScreenGridPlan(
            kind: .lambert93,
            geoJson: collection(features),
            stepText: ScaleBar.format(step),
            systemText: "Lambert 93",
            issue: outside ? .outsideValidity : .none,
            convergenceDegrees: GridPlanner.convergenceDegrees(
                .lambert93,
                (area.minY + area.maxY) / 2,
                (area.minX + area.maxX) / 2
            )
        )
    }

    private struct Extent {
        let minX: Double
        let maxX: Double
        let minY: Double
        let maxY: Double

        var span: Double { Swift.max(maxX - minX, maxY - minY) }
    }

    /// The region's extent in grid coordinates: its outline is sampled, a curved side may bulge past the corners.
    private static func extentOf(_ area: Bounds, _ forward: (Double, Double) -> (Double, Double)) -> Extent {
        var xs: [Double] = []
        var ys: [Double] = []
        for i in 0 ... samples {
            let t = Double(i) / Double(samples)
            let lon = area.minX + (area.maxX - area.minX) * t
            let lat = area.minY + (area.maxY - area.minY) * t
            let corners: [(Double, Double)] = [(area.minY, lon), (area.maxY, lon), (lat, area.minX), (lat, area.maxX)]
            for (la, lo) in corners {
                let (x, y) = forward(la, lo)
                xs.append(x)
                ys.append(y)
            }
        }
        return Extent(minX: xs.min() ?? 0, maxX: xs.max() ?? 0, minY: ys.min() ?? 0, maxY: ys.max() ?? 0)
    }

    /// A straight line of the grid in longitude and latitude, with its value; `constantX` for an easting.
    private struct Straight {
        let points: [LonLat]
        let value: Double
        let constantX: Bool
    }

    /// Lines of constant x and of constant y at `step` over `extent`, in longitude and latitude, with their value.
    private static func straightLines(
        _ extent: Extent,
        _ step: Double,
        _ inverse: (Double, Double) -> LonLat
    ) -> [Straight] {
        var lines: [Straight] = []
        for x in multiples(extent.minX, extent.maxX, step) {
            var points: [LonLat] = []
            for i in 0 ... samples {
                points.append(inverse(x, extent.minY + (extent.maxY - extent.minY) * Double(i) / Double(samples)))
            }
            lines.append(Straight(points: points, value: x, constantX: true))
        }
        for y in multiples(extent.minY, extent.maxY, step) {
            var points: [LonLat] = []
            for i in 0 ... samples {
                points.append(inverse(extent.minX + (extent.maxX - extent.minX) * Double(i) / Double(samples), y))
            }
            lines.append(Straight(points: points, value: y, constantX: false))
        }
        return lines
    }

    /// The parts of `points` inside `area`, longitude and latitude taken as plane coordinates: the samples of a line
    /// are close enough for its curve between two of them not to show.
    static func clip(_ points: [LonLat], _ area: Bounds) -> [[LonLat]] {
        var parts: [[LonLat]] = []
        var part: [LonLat] = []
        func close() {
            if part.count >= 2 { parts.append(part) }
            part = []
        }
        if points.count >= 2 {
            for i in 0 ..< points.count - 1 {
                let a = points[i]
                let b = points[i + 1]
                guard let t = clipSegment(a, b, area) else {
                    close()
                    continue
                }
                let t0 = t.0
                let t1 = t.1
                // Entering the area starts a new part, leaving it ends the part.
                if t0 > 0.0 { close() }
                if part.isEmpty { part.append(pointAt(a, b, t0)) }
                part.append(pointAt(a, b, t1))
                if t1 < 1.0 { close() }
            }
        }
        close()
        return parts
    }

    /// Liang–Barsky: the fractions of the segment `a` `b` where it enters and leaves `area`, nil when outside.
    private static func clipSegment(_ a: LonLat, _ b: LonLat, _ area: Bounds) -> (Double, Double)? {
        let dx = b.lon - a.lon
        let dy = b.lat - a.lat
        var t0 = 0.0
        var t1 = 1.0
        let sides: [(Double, Double)] = [
            (-dx, a.lon - area.minX),
            (dx, area.maxX - a.lon),
            (-dy, a.lat - area.minY),
            (dy, area.maxY - a.lat),
        ]
        for (p, q) in sides {
            if p == 0.0 {
                if q < 0.0 { return nil }
            } else if p < 0.0 {
                t0 = Swift.max(t0, q / p)
            } else {
                t1 = Swift.min(t1, q / p)
            }
        }
        return t0 < t1 ? (t0, t1) : nil
    }

    private static func pointAt(_ a: LonLat, _ b: LonLat, _ t: Double) -> LonLat {
        if t == 0.0 { return a }
        if t == 1.0 { return b }
        return LonLat(lon: a.lon + (b.lon - a.lon) * t, lat: a.lat + (b.lat - a.lat) * t)
    }

    /// The 1, 2 or 5 × 10^n not smaller than `target` and at least `min`.
    static func niceStep(_ target: Double, _ min: Double) -> Double {
        let wanted = Swift.max(target, min)
        let base = Foundation.pow(10.0, floor(log(wanted) / log(10.0)))
        for m in [1.0, 2.0, 5.0, 10.0] where m * base >= wanted * (1 - eps) {
            return m * base
        }
        return 10 * base
    }

    private static func multiples(_ from: Double, _ to: Double, _ step: Double) -> [Double] {
        let first = kotlinToLong(ceil(from / step))
        let last = kotlinToLong(floor(to / step))
        var out: [Double] = []
        for k in stride(from: first, through: last, by: 1) {
            out.append(Double(k) * step)
        }
        return out
    }

    private static func isMultiple(_ value: Double, _ of: Double) -> Bool {
        let q = value / of
        return abs(q - Double(kotlinRound(q))) < eps
    }

    private static func degreesText(_ step: Double) -> String {
        let seconds = kotlinRound(step * 3600.0)
        if seconds < 60 { return "\(seconds)\u{2033}" }
        if seconds < 3600 { return "\(seconds / 60)\u{2032}" }
        return "\(seconds / 3600)°"
    }

    private static func line(_ points: [LonLat], _ level: Int) -> String {
        let coordinates = points.map { p -> String in
            let lon = formatFixed(p.lon, 7)
            let lat = formatFixed(p.lat, 7)
            return "[\(lon),\(lat)]"
        }.joined(separator: ",")
        var out = #"{"type":"Feature","properties":{"level":"#
        out += String(level)
        out += #"},"geometry":{"type":"LineString","coordinates":["#
        out += coordinates
        out += "]}}"
        return out
    }

    private static func collection(_ features: [String]) -> String {
        #"{"type":"FeatureCollection","features":["# + features.joined(separator: ",") + "]}"
    }
}
