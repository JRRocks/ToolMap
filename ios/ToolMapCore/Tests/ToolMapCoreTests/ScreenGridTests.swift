// Port of core/src/test/kotlin/fr/toolmap/core/geo/ScreenGridTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

/// Coordinates are written with 7 decimals of a degree (1 cm) and the samples of a line are on it; the ends cut at the
/// edge of the area or of the zone are interpolated between two samples, a few centimeters off on a walker's screen,
/// well under a meter tens of km across.
private let toleranceMeters = 1.0

/// Hundreds of km across, samples 15 to 35 km apart: a cut end a few meters off, a hundredth of a pixel.
private let wideTolerance = 20.0

/// Lambert 93 lines are not cut: every point is a sample on the line.
private let uncutTolerance = 0.1

private struct GridTestLine {
    let points: [LonLat]
    let level: Int

    var isEdge: Bool { level == GridLineLevel.zoneEdge }
    var midLon: Double { ((points.map(\.lon).min() ?? 0) + (points.map(\.lon).max() ?? 0)) / 2 }
    var midLat: Double { ((points.map(\.lat).min() ?? 0) + (points.map(\.lat).max() ?? 0)) / 2 }
}

/// A grid line read in `zone`: does it run north-south (constant easting), and its easting or northing.
private struct GridValue: Hashable {
    let zone: Int
    let vertical: Bool
    let meters: Int
    let level: Int
}

private struct GridLabelFeature {
    let text: String
    let kind: String
    let at: LonLat
}

private func number(_ value: Any?) -> Double {
    (value as? NSNumber)?.doubleValue ?? Double.nan
}

private func features(_ geoJson: String) throws -> [[String: Any]] {
    let root = try #require(try JSONSerialization.jsonObject(with: Data(geoJson.utf8)) as? [String: Any])
    return try #require(root["features"] as? [[String: Any]])
}

private func lines(_ plan: ScreenGridPlan) throws -> [GridTestLine] {
    try features(plan.geoJson).map { feature in
        let geometry = try #require(feature["geometry"] as? [String: Any])
        let coordinates = try #require(geometry["coordinates"] as? [[Any]])
        let properties = try #require(feature["properties"] as? [String: Any])
        let level = try #require(properties["level"] as? NSNumber)
        return GridTestLine(
            points: coordinates.map { LonLat(lon: number($0[0]), lat: number($0[1])) },
            level: level.intValue
        )
    }
}

private func labels(_ plan: ScreenGridPlan) throws -> [GridLabelFeature] {
    try features(plan.labelsGeoJson).map { feature in
        let geometry = try #require(feature["geometry"] as? [String: Any])
        let coordinates = try #require(geometry["coordinates"] as? [Any])
        let properties = try #require(feature["properties"] as? [String: Any])
        let text = try #require(properties["text"] as? String)
        let kind = try #require(properties["kind"] as? String)
        return GridLabelFeature(text: text, kind: kind, at: LonLat(lon: number(coordinates[0]), lat: number(coordinates[1])))
    }
}

private func gridValue(
    _ line: GridTestLine,
    _ zone: Int,
    _ north: Bool,
    tolerance: Double = toleranceMeters
) -> GridValue {
    let utm = line.points.map { Utm.toUtm($0.lat, $0.lon, zone: zone, north: north) }
    let eSpread = (utm.map(\.easting).max() ?? 0) - (utm.map(\.easting).min() ?? 0)
    let nSpread = (utm.map(\.northing).max() ?? 0) - (utm.map(\.northing).min() ?? 0)
    let vertical = eSpread < tolerance
    #expect(
        vertical || nSpread < tolerance,
        "a line that is not straight in zone \(zone) (\(eSpread), \(nSpread)) at \(line.midLat),\(line.midLon)"
    )
    // A sample from the middle: the ends cut at an edge are interpolated.
    let middle = utm[utm.count / 2]
    let value = vertical ? middle.easting : middle.northing
    return GridValue(zone: zone, vertical: vertical, meters: kotlinRound(value), level: line.level)
}

private func distanceToMultiple(_ value: Double, _ step: Double) -> Double {
    abs(value - Double(kotlinRound(value / step)) * step)
}

private func stepMeters(_ plan: ScreenGridPlan) -> Double {
    let text = plan.stepText
    if text.hasSuffix(" km") { return (Double(text.dropLast(3)) ?? .nan) * 1000 }
    if text.hasSuffix(" m") { return Double(text.dropLast(2)) ?? .nan }
    return .nan
}

struct ScreenGridTests {
    // A walker's screen: about 4 km across around Grenoble.
    private let grenoble = Bounds(minX: 5.70, minY: 45.17, maxX: 5.75, maxY: 45.21)

    // The user's screenshots: Pontcharra, on both sides of 6° E (31T / 32T).
    private let pontcharra = Bounds(minX: 5.94, minY: 45.39, maxX: 6.07, maxY: 45.47)

    @Test("no grid is an empty collection")
    func noGrid() {
        let plan = ScreenGrid.plan(GridKind.none, grenoble)
        #expect(plan.isEmpty)
        #expect(plan.geoJson == ScreenGridPlan.emptyGeoJson)
    }

    @Test("UTM lines have a constant easting or northing and a round step")
    func utmLines() throws {
        let plan = ScreenGrid.plan(.utm, grenoble)
        #expect(plan.systemText == "UTM 31T")
        #expect(plan.stepText == "500 m" || plan.stepText == "1 km", "\(plan.stepText)")
        let all = try lines(plan)
        #expect(all.count >= 6 && all.count <= 40, "\(all.count) lines")
        #expect(!all.contains { $0.isEdge }, "no zone edge near Grenoble")
        let step = stepMeters(plan)
        var vertical = 0
        var horizontal = 0
        for line in all {
            let v = gridValue(line, 31, true)
            #expect(distanceToMultiple(Double(v.meters), step) <= 1.0, "line at \(v.meters)")
            if v.vertical { vertical += 1 } else { horizontal += 1 }
        }
        #expect(vertical >= 3 && horizontal >= 3, "\(vertical) vertical, \(horizontal) horizontal")
    }

    @Test("a finer density gives more lines than a coarser one")
    func density() throws {
        let fine = try lines(ScreenGrid.plan(.utm, grenoble, spacing: 0.5)).count
        let coarse = try lines(ScreenGrid.plan(.utm, grenoble, spacing: 2.0)).count
        #expect(fine > coarse, "fine \(fine), coarse \(coarse)")
    }

    @Test("the lines cover the whole region")
    func coverage() throws {
        for kind in [GridKind.utm, .lambert93, .wgs84, .mgrs] {
            let all = try lines(ScreenGrid.plan(kind, grenoble))
            let lons = all.flatMap { $0.points.map(\.lon) }
            let lats = all.flatMap { $0.points.map(\.lat) }
            let minLon = lons.min() ?? .infinity
            let maxLon = lons.max() ?? -.infinity
            let minLat = lats.min() ?? .infinity
            let maxLat = lats.max() ?? -.infinity
            #expect(minLon <= grenoble.minX && maxLon >= grenoble.maxX, "\(kind) lon coverage")
            #expect(minLat <= grenoble.minY && maxLat >= grenoble.maxY, "\(kind) lat coverage")
        }
    }

    @Test("Lambert 93 lines are straight in Lambert coordinates")
    func lambertLines() throws {
        let plan = ScreenGrid.plan(.lambert93, grenoble)
        #expect(plan.systemText == "Lambert 93")
        #expect(plan.issue == ScreenGridIssue.none)
        for line in try lines(plan) {
            #expect(line.level == GridLineLevel.minor || line.level == GridLineLevel.major)
            let p = line.points.map { Lambert93.toLambert($0.lat, $0.lon) }
            let e = (p.map(\.easting).max() ?? 0) - (p.map(\.easting).min() ?? 0)
            let n = (p.map(\.northing).max() ?? 0) - (p.map(\.northing).min() ?? 0)
            #expect(e < uncutTolerance || n < uncutTolerance, "e spread \(e), n spread \(n)")
        }
    }

    @Test("Lambert 93 is drawn but flagged outside France")
    func lambertAbroad() {
        let plan = ScreenGrid.plan(.lambert93, Bounds(minX: -74.05, minY: 40.68, maxX: -74.0, maxY: 40.72))
        #expect(plan.issue == ScreenGridIssue.outsideValidity)
        #expect(plan.geoJson.contains("LineString"))
    }

    @Test("MGRS lines are heavier on 10 and 100 km")
    func mgrsLevels() throws {
        // 60 km across: 10 km lines, two of which are 100 km lines at most.
        let area = Bounds(minX: 5.0, minY: 45.0, maxX: 5.8, maxY: 45.5)
        let plan = ScreenGrid.plan(.mgrs, area)
        #expect(plan.systemText.hasPrefix("MGRS 31T"), "\(plan.systemText)")
        let all = try lines(plan)
        #expect(!all.isEmpty)
        for line in all {
            let v = gridValue(line, 31, true)
            let expected: Int
            if v.meters % 100_000 == 0 {
                expected = GridLineLevel.square
            } else if v.meters % 10_000 == 0 {
                expected = GridLineLevel.tenKm
            } else {
                expected = GridLineLevel.step
            }
            #expect(line.level == expected, "line at \(v.meters)")
        }
        // Finer than 1 km would not be an MGRS square size a map is read with.
        let tiny = ScreenGrid.plan(.mgrs, Bounds(minX: 5.7000, minY: 45.1700, maxX: 5.7010, maxY: 45.1708))
        #expect(tiny.stepText == "1 km")
    }

    @Test("UTM under 1 km keeps a heavier line every fifth step")
    func utmUnderOneKm() throws {
        let plan = ScreenGrid.plan(.utm, Bounds(minX: 5.7000, minY: 45.1700, maxX: 5.7100, maxY: 45.1780))
        let step = stepMeters(plan)
        #expect(step < 1000.0, "\(plan.stepText)")
        for line in try lines(plan) {
            let v = gridValue(line, 31, true)
            let major = distanceToMultiple(Double(v.meters), step * 5) < 1.0
            #expect(line.level == (major ? GridLineLevel.major : GridLineLevel.minor), "line at \(v.meters)")
        }
    }

    @Test("two zones on screen each draw their own grid, cut at the zone edge")
    func twoZones() throws {
        let plan = ScreenGrid.plan(.mgrs, pontcharra)
        // The chip names the zone of the centre, east of 6° E.
        #expect(plan.systemText == "MGRS 32T")
        let all = try lines(plan)
        let edges = all.filter(\.isEdge)
        #expect(edges.count == 1, "one zone edge: the 6° E meridian")
        let edge = try #require(edges.first)
        #expect(edge.points.allSatisfy { $0.lon == 6.0 }, "edge at \(edge.points)")
        #expect(near(edge.points.map(\.lat).min() ?? 0, pontcharra.minY, 1e-7))
        #expect(near(edge.points.map(\.lat).max() ?? 0, pontcharra.maxY, 1e-7))
        let west = all.filter { !$0.isEdge && $0.midLon < 6.0 }
        let east = all.filter { !$0.isEdge && $0.midLon > 6.0 }
        #expect(west.count >= 2 && east.count >= 2, "\(west.count) lines in 31T, \(east.count) in 32T")
        for line in west {
            #expect(line.points.allSatisfy { $0.lon <= 6.0 + 1e-7 }, "a 31T line past 6° E")
            _ = gridValue(line, 31, true)
        }
        for line in east {
            #expect(line.points.allSatisfy { $0.lon >= 6.0 - 1e-7 }, "a 32T line before 6° E")
            _ = gridValue(line, 32, true)
        }
        // The northing lines of each side stop at the edge.
        #expect(west.contains { l in abs((l.points.map(\.lon).max() ?? 0) - 6.0) < 1e-7 })
        #expect(east.contains { l in abs((l.points.map(\.lon).min() ?? 0) - 6.0) < 1e-7 })
    }

    @Test("the zone edge is drawn at every step")
    func edgeAtEveryStep() throws {
        var steps: [String] = []
        for half in [0.002, 0.01, 0.05, 0.2, 0.8, 3.0, 10.0, 25.0, 38.0] {
            let area = Bounds(minX: 6.0 - half, minY: 42.0 - half, maxX: 6.0 + half, maxY: 42.0 + half)
            for kind in [GridKind.mgrs, .utm] {
                let plan = ScreenGrid.plan(kind, area)
                #expect(plan.issue == ScreenGridIssue.none, "\(kind) ±\(half)°")
                let atSixEast = try lines(plan).filter { l in l.isEdge && l.points.allSatisfy { $0.lon == 6.0 } }
                #expect(!atSixEast.isEmpty, "no edge at 6° E for \(kind) ±\(half)° (\(plan.stepText))")
                if kind == .mgrs { steps.append(plan.stepText) }
            }
        }
        // From 1 km to 100 km, then the edges alone; never 200 or 500 km.
        #expect(steps.first == "1 km")
        #expect(steps.contains("100 km"), "\(steps)")
        #expect(steps.last == "")
        #expect(!steps.contains { $0 == "200 km" || $0 == "500 km" }, "\(steps)")
    }

    @Test("zooming out from 5 to 10 km keeps the 10 km lines where they were")
    func zoomingOut() throws {
        // Variant A of the P24.0 preview, the user's images 2 and 3: the line near the centre stays.
        let lat = 45.43098
        let lon = 6.00589
        let close = ScreenGrid.plan(.mgrs, Bounds(minX: lon - 0.15, minY: lat - 0.11, maxX: lon + 0.15, maxY: lat + 0.11))
        let far = ScreenGrid.plan(.mgrs, Bounds(minX: lon - 0.3, minY: lat - 0.22, maxX: lon + 0.3, maxY: lat + 0.22))
        #expect(close.stepText == "5 km")
        #expect(far.stepText == "10 km")

        func values(_ plan: ScreenGridPlan) throws -> [(GridValue, Int)] {
            try lines(plan).filter { !$0.isEdge }.map { l in
                let zone = l.midLon < 6.0 ? 31 : 32
                let v = gridValue(l, zone, true)
                return (GridValue(zone: v.zone, vertical: v.vertical, meters: v.meters, level: 0), v.level)
            }
        }
        var farValues: [GridValue: Int] = [:]
        for (key, level) in try values(far) {
            farValues[key] = level
        }
        let marked = try values(close).filter { $0.1 != GridLineLevel.step }
        #expect(marked.count >= 4, "\(marked.count) marked lines")
        for (key, level) in marked {
            #expect(farValues[key] == level, "the line \(key) changed or went away")
        }
        #expect(!farValues.values.contains(GridLineLevel.step), "at 10 km every line is a 10 km line")
    }

    @Test("a wide view draws the 100 km lines and the band edges")
    func wideView() throws {
        // About 1:5 000 000: the step would have been 200 km.
        let area = Bounds(minX: 2.0, minY: 42.0, maxX: 10.0, maxY: 50.0)
        let plan = ScreenGrid.plan(.mgrs, area)
        #expect(plan.stepText == "100 km")
        let all = try lines(plan)
        #expect(all.filter { !$0.isEdge }.allSatisfy { $0.level == GridLineLevel.square })
        let edges = all.filter(\.isEdge)
        #expect(edges.contains { l in l.points.allSatisfy { $0.lon == 6.0 } }, "the 31 / 32 edge")
        #expect(edges.contains { l in l.points.allSatisfy { $0.lat == 48.0 } }, "the T / U edge")
        // Each 100 km line of zone 31 stops at 6° E, each one of zone 32 starts there.
        for line in all.filter({ !$0.isEdge }) {
            let zone = line.midLon < 6.0 ? 31 : 32
            let v = gridValue(line, zone, true, tolerance: wideTolerance)
            #expect(distanceToMultiple(Double(v.meters), 100_000.0) <= 1.0, "line at \(v.meters)")
        }
    }

    @Test("Norway draws 32V from 3 E")
    func norway() throws {
        let area = Bounds(minX: 1.0, minY: 54.0, maxX: 8.0, maxY: 58.0)
        let all = try lines(ScreenGrid.plan(.utm, area))
        let edges = all.filter(\.isEdge)
        let atThree = edges.filter { l in l.points.allSatisfy { $0.lon == 3.0 } }
        #expect(atThree.count == 1)
        #expect(atThree.first?.points.map(\.lat).min() == 56.0 && atThree.first?.points.map(\.lat).max() == 58.0)
        let atSix = edges.filter { l in l.points.allSatisfy { $0.lon == 6.0 } }
        #expect(atSix.count == 1)
        #expect(atSix.first?.points.map(\.lat).min() == 54.0 && atSix.first?.points.map(\.lat).max() == 56.0)
        // Between 3 and 6° E above 56° N, the lines are those of zone 32.
        let inV = all.filter { !$0.isEdge && $0.midLon > 3.0 && $0.midLon < 6.0 && $0.midLat > 56.0 }
        #expect(!inV.isEmpty)
        for line in inV {
            _ = gridValue(line, 32, true, tolerance: wideTolerance)
        }
    }

    @Test("the grid runs on across the equator")
    func acrossTheEquator() throws {
        let area = Bounds(minX: 6.40, minY: -0.05, maxX: 6.52, maxY: 0.05)
        let plan = ScreenGrid.plan(.mgrs, area)
        let all = try lines(plan)
        #expect(all.contains { l in l.isEdge && l.points.allSatisfy { $0.lat == 0.0 } }, "the M / N edge")
        let south = all.filter { !$0.isEdge && $0.midLat < -0.005 }
        #expect(!south.isEmpty)
        let step = stepMeters(plan)
        for line in south {
            // In the northern hemisphere's grid: just below 0, and on the same lines as the southern one.
            let v = gridValue(line, 32, true)
            #expect(distanceToMultiple(Double(v.meters), step) <= 1.0)
        }
        let points = all.flatMap(\.points)
        #expect(points.allSatisfy { $0.lat >= area.minY - 1e-7 && $0.lat <= area.maxY + 1e-7 })
    }

    @Test("across the antimeridian zone 1 is drawn past 180")
    func antimeridian() throws {
        // Fiji, with the longitudes of a map that runs on east of 180°.
        let area = Bounds(minX: 179.6, minY: -17.0, maxX: 180.4, maxY: -16.2)
        let plan = ScreenGrid.plan(.mgrs, area)
        #expect(plan.systemText == "MGRS 1K")
        let all = try lines(plan)
        #expect(all.contains { l in l.isEdge && l.points.allSatisfy { $0.lon == 180.0 } }, "the 60 / 1 edge")
        let points = all.flatMap(\.points)
        #expect(points.allSatisfy { $0.lon >= area.minX - 1e-7 && $0.lon <= area.maxX + 1e-7 })
        let east = all.filter { !$0.isEdge && $0.midLon > 180.0 }
        let west = all.filter { !$0.isEdge && $0.midLon < 180.0 }
        #expect(!east.isEmpty && !west.isEmpty)
        for line in east {
            _ = gridValue(line, 1, false)
        }
        for line in west {
            _ = gridValue(line, 60, false)
        }
    }

    @Test("a zone with one square in view is named with it, on its side of the edge")
    func namedSquares() throws {
        // Section 1 of the P24.0 preview: « 31T GL » and « 32T KR ».
        let all = try labels(ScreenGrid.plan(.mgrs, pontcharra))
        #expect(Set(all.map(\.text)) == Set(["31T GL", "32T KR"]))
        #expect(all.allSatisfy { $0.kind == GridLabelKind.name })
        let gl = try #require(all.first { $0.text == "31T GL" })
        let kr = try #require(all.first { $0.text == "32T KR" })
        #expect(gl.at.lon < 6.0 && kr.at.lon > 6.0, "\(gl.at) \(kr.at)")
        for label in all {
            let reference = try #require(Mgrs.of(label.at.lat, label.at.lon))
            let written = try reference.format(digits: 0)
            #expect(label.text == written, "\(label.text) at \(label.at)")
        }
        // UTM writes the figures of its lines at the edge of the screen instead of the zone names (2026-10-28).
        #expect(try labels(ScreenGrid.plan(.utm, pontcharra)).isEmpty)
        // On a view too wide for lines, it names the zones only.
        let wide = try labels(ScreenGrid.plan(.utm, Bounds(minX: -20.0, minY: 20.0, maxX: 40.0, maxY: 60.0)))
        #expect(!wide.isEmpty && wide.allSatisfy { ($0.text.first?.isNumber ?? false) })
        #expect(!wide.contains { $0.text.contains(" ") }, "\(wide.map(\.text))")
    }

    @Test("a wide view names the zones and the squares that take enough of the screen")
    func wideNames() throws {
        // Section 3 of the preview, about 1:5 000 000.
        let all = try labels(ScreenGrid.plan(.mgrs, Bounds(minX: 2.0, minY: 42.0, maxX: 10.0, maxY: 50.0)))
        let zones = all.filter { $0.kind == GridLabelKind.zone }
        #expect(Set(zones.map(\.text)) == Set(["31T", "32T", "31U", "32U"]))
        for zone in zones {
            #expect(GridZones.of(zone.at.lat, zone.at.lon)?.name == zone.text)
        }
        let squares = all.filter { $0.kind == GridLabelKind.square }
        #expect(squares.count >= 4, "\(squares.count) squares named")
        for square in squares {
            let reference = try #require(Mgrs.of(square.at.lat, square.at.lon))
            #expect(square.text == "\(reference.columnLetter)\(reference.rowLetter)", "at \(square.at)")
        }
        #expect(!all.contains { $0.kind == GridLabelKind.name })
    }

    @Test("a view too wide for lines keeps the zone names")
    func tooWideKeepsNames() throws {
        let world = ScreenGrid.plan(.mgrs, Bounds(minX: -20.0, minY: 20.0, maxX: 40.0, maxY: 60.0))
        #expect(world.isEmpty)
        #expect(world.convergenceDegrees == nil)
        let names = try labels(world).filter { $0.kind == GridLabelKind.zone }.map(\.text)
        #expect(names.contains("32T") && names.contains("33U"), "\(names)")
    }

    @Test("UTM gives its kilometre lines for the figures at the edge, split before the last two")
    func figureLines() throws {
        let plan = ScreenGrid.plan(.utm, grenoble)
        let figures = plan.figureLines
        #expect(figures.contains { $0.easting } && figures.contains { !$0.easting }, "\(figures.count) lines")
        for line in figures {
            #expect(distanceToMultiple(line.meters, 1000.0) <= 1e-6)
            let value = gridValue(GridTestLine(points: line.points, level: 0), 31, true)
            #expect(line.easting == value.vertical)
            #expect(kotlinRound(line.meters) == value.meters)
            let (small, big) = line.figures
            #expect(big.count == 2)
            #expect(small + big == String(kotlinRound(line.meters / 1000)))
        }
        // Each figure line is a line of the grid.
        #expect(try figures.count <= lines(plan).filter { !$0.isEdge }.count)
        // MGRS keeps its names, without figures.
        #expect(ScreenGrid.plan(.mgrs, grenoble).figureLines.isEmpty)
    }

    @Test("a close view under 1 km gives the kilometre lines alone, often none")
    func closeView() throws {
        // About 1:2 000, lines every 50 m: no kilometre line in view, nothing to write (zoom 18 or 19).
        let close = Bounds(minX: 5.7210, minY: 45.1910, maxX: 5.7230, maxY: 45.1940)
        let plan = ScreenGrid.plan(.utm, close)
        #expect(stepMeters(plan) < 1000.0, "\(plan.stepText)")
        for line in plan.figureLines {
            #expect(distanceToMultiple(line.meters, 1000.0) <= 1e-6)
        }
        // About 1:25 000, lines every 500 m: one in two is written.
        let mid = ScreenGrid.plan(.utm, Bounds(minX: 5.70, minY: 45.17, maxX: 5.73, maxY: 45.19))
        let grid = try lines(mid).filter { !$0.isEdge }.count
        #expect(!mid.figureLines.isEmpty && mid.figureLines.count < grid, "\(mid.figureLines.count) of \(grid)")
    }

    @Test("figures of a short value are not split")
    func shortFigures() {
        let short = GridFigureLine(easting: true, meters: 25_000.0, points: []).figures
        #expect(short.0 == "" && short.1 == "25")
        let easting = GridFigureLine(easting: true, meters: 725_000.0, points: []).figures
        #expect(easting.0 == "7" && easting.1 == "25")
        let northing = GridFigureLine(easting: false, meters: 5_045_000.0, points: []).figures
        #expect(northing.0 == "50" && northing.1 == "45")
    }

    @Test("the convergence at the centre goes with the chip")
    func convergence() throws {
        // East of 6° E, in 32T: grid north about 2°08′ west of true north.
        let mgrs = try #require(ScreenGrid.plan(.mgrs, pontcharra).convergenceDegrees)
        #expect(near(mgrs, Utm.convergenceDegrees(lat: 45.43, lon: 6.005), 1e-9))
        #expect(mgrs < -2.0 && mgrs > -2.3, "\(mgrs)")
        // Lambert 93: n × (λ − 3°) in France, nothing elsewhere.
        let lambert = try #require(ScreenGrid.plan(.lambert93, grenoble).convergenceDegrees)
        #expect(near(lambert, 0.7256077650532670 * (5.725 - 3.0), 1e-9))
        let abroad = ScreenGrid.plan(.lambert93, Bounds(minX: -74.05, minY: 40.68, maxX: -74.0, maxY: 40.72))
        #expect(abroad.convergenceDegrees == nil)
        #expect(ScreenGrid.plan(.wgs84, grenoble).convergenceDegrees == nil)
    }

    @Test("clipping keeps the parts inside the area")
    func clipping() {
        let square = Bounds(minX: 0.0, minY: 0.0, maxX: 1.0, maxY: 1.0)
        let zigzag = [
            LonLat(lon: -1.0, lat: 0.5),
            LonLat(lon: 0.5, lat: 0.5),
            LonLat(lon: 0.5, lat: 2.0),
            LonLat(lon: 0.8, lat: 0.5),
            LonLat(lon: 2.0, lat: 0.5),
        ]
        let parts = ScreenGrid.clip(zigzag, square)
        #expect(parts.count == 2)
        #expect(parts.first == [LonLat(lon: 0.0, lat: 0.5), LonLat(lon: 0.5, lat: 0.5), LonLat(lon: 0.5, lat: 1.0)])
        if parts.count == 2 {
            #expect(parts[1][1] == LonLat(lon: 0.8, lat: 0.5))
            #expect(parts[1].last == LonLat(lon: 1.0, lat: 0.5))
        }
        #expect(ScreenGrid.clip([LonLat(lon: 2.0, lat: 2.0), LonLat(lon: 3.0, lat: 3.0)], square).isEmpty)
    }

    @Test("the graticule uses degrees, minutes or seconds as the region shrinks")
    func graticuleSteps() throws {
        #expect(ScreenGrid.plan(.wgs84, Bounds(minX: 5.0, minY: 44.0, maxX: 11.0, maxY: 48.0)).stepText == "1°")
        #expect(ScreenGrid.plan(.wgs84, Bounds(minX: 5.70, minY: 45.17, maxX: 5.82, maxY: 45.21)).stepText == "1\u{2032}")
        let seconds = ScreenGrid.plan(.wgs84, Bounds(minX: 5.7000, minY: 45.1700, maxX: 5.7040, maxY: 45.1730))
        #expect(seconds.stepText.hasSuffix("\u{2033}"))
        for line in try lines(ScreenGrid.plan(.wgs84, Bounds(minX: 5.70, minY: 45.17, maxX: 5.82, maxY: 45.21))) {
            // 2 points: straight lines in Mercator.
            #expect(line.points.count == 2)
            if line.points.count == 2 {
                #expect(line.points[0].lon == line.points[1].lon || line.points[0].lat == line.points[1].lat)
            }
        }
    }

    @Test("polar regions have no UTM grid and the whole world its zone edges alone")
    func polar() throws {
        let north = ScreenGrid.plan(.utm, Bounds(minX: 10.0, minY: 83.0, maxX: 12.0, maxY: 86.0))
        #expect(north.issue == ScreenGridIssue.polar)
        let south = ScreenGrid.plan(.mgrs, Bounds(minX: 10.0, minY: -85.0, maxX: 12.0, maxY: -82.0))
        #expect(south.issue == ScreenGridIssue.polar)
        let world = ScreenGrid.plan(.utm, Bounds(minX: -20.0, minY: 20.0, maxX: 40.0, maxY: 60.0))
        #expect(world.issue == ScreenGridIssue.none)
        #expect(world.isEmpty, "no chip")
        let all = try lines(world)
        #expect(all.count > 10, "\(all.count) edges")
        #expect(all.allSatisfy(\.isEdge))
        // WGS84 and Lambert 93 still say the region is too wide.
        let wide = ScreenGrid.plan(.lambert93, Bounds(minX: -60.0, minY: -40.0, maxX: 60.0, maxY: 60.0))
        #expect(wide.issue == ScreenGridIssue.tooWide)
    }

    @Test("an empty region gives nothing")
    func emptyRegion() {
        #expect(ScreenGrid.plan(.utm, Bounds(minX: 5.0, minY: 45.0, maxX: 5.0, maxY: 45.0)).isEmpty)
    }

    @Test("nice steps are 1, 2 or 5 times a power of ten and respect the minimum")
    func niceSteps() {
        #expect(ScreenGrid.niceStep(700.0, 10.0) == 1000.0)
        #expect(ScreenGrid.niceStep(1100.0, 10.0) == 2000.0)
        #expect(ScreenGrid.niceStep(2100.0, 10.0) == 5000.0)
        #expect(ScreenGrid.niceStep(5100.0, 10.0) == 10_000.0)
        #expect(ScreenGrid.niceStep(30.0, 1000.0) == 1000.0)
        #expect(ScreenGrid.niceStep(1.0, 10.0) == 10.0)
    }
}
