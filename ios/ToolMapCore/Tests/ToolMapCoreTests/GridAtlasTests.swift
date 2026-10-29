// Port of core/src/test/kotlin/fr/toolmap/core/print/GridAtlasTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private func near(_ a: Double, _ b: Double, _ tolerance: Double) -> Bool {
    abs(a - b) <= tolerance
}

private extension Array where Element == GridLabel {
    func on(_ side: FrameSide) -> [GridLabel] {
        filter { $0.side == side }
    }
}

struct GridAtlasTests {
    private let france = Locale(identifier: "fr_FR")

    private func frame(_ lat: Double = 45.19, _ lon: Double = 5.72, scale: Double = 25_000.0) throws -> FrameProjection {
        try FrameProjection(lat, lon, scale, 277.0, 190.0)
    }

    @Test("an UTM grid at 1 to 25 000 has a line every kilometer, about 40 mm apart")
    func utmGrid() throws {
        let f = try frame()
        let plan = GridPlanner.plan(f, .utm, locale: france)
        #expect(plan.issue == GridIssue.none)
        #expect(plan.stepText == "1 km")
        #expect(plan.systemText == "UTM 31N · WGS84")
        // 277 mm / 40 mm: six or seven vertical lines, and 190 / 40: four or five horizontal ones.
        let top = plan.labels.on(.top).sorted { $0.positionMm < $1.positionMm }
        let left = plan.labels.on(.left).sorted { $0.positionMm < $1.positionMm }
        #expect(top.count >= 6 && top.count <= 8, "\(top.count) labels on the top edge")
        #expect(left.count >= 4 && left.count <= 6, "\(left.count) labels on the left edge")
        for k in stride(from: 1, to: top.count, by: 1) {
            #expect(near(top[k].positionMm - top[k - 1].positionMm, 40.0, 0.8))
        }
        // The label is the kilometer figure: the easting of the frame centre is 5xx km in zone 31.
        let expected = Utm.toUtm(45.19, 5.72, zone: 31)
        #expect(top.contains { Int($0.text) == Int(expected.easting / 1000) }, "labels \(top.map(\.text)) for \(expected.easting)")
        #expect(left.contains { Int($0.text) == Int(expected.northing / 1000) })
        // Lines stay inside the frame.
        for line in plan.lines {
            for p in line.points {
                #expect(p.xMm >= -1e-6 && p.xMm <= 277.0 + 1e-6 && p.yMm >= -1e-6 && p.yMm <= 190.0 + 1e-6)
            }
        }
    }

    @Test("grid lines are nearly vertical or horizontal near the central meridian and keep their step in millimeters")
    func centralMeridian() throws {
        let f = try frame(45.19, 3.0) // central meridian of zone 31
        let plan = GridPlanner.plan(f, .utm, locale: france)
        let vertical = plan.lines.filter { l in
            guard let first = l.points.first, let last = l.points.last else { return false }
            return abs(first.xMm - last.xMm) < 2.0
        }
        #expect(vertical.count >= 5, "\(vertical.count)")
        // A line of constant easting is 190 mm long in a 190 mm frame, give or take the convergence.
        let full = vertical.allSatisfy { l in
            guard let first = l.points.first, let last = l.points.last else { return false }
            let down = last.yMm - first.yMm
            let up = first.yMm - last.yMm
            return (down >= 185.0 && down <= 191.0) || (up >= 185.0 && up <= 191.0)
        }
        #expect(full)
    }

    @Test("a Lambert 93 grid labels kilometers and a WGS84 graticule labels degrees, minutes and seconds")
    func lambertAndGraticule() throws {
        let lambert = GridPlanner.plan(try frame(48.85, 2.35), .lambert93, locale: france)
        #expect(lambert.systemText == "Lambert-93 · RGF93 (EPSG:2154)")
        let top = lambert.labels.on(.top).compactMap { Int($0.text) }
        #expect(top.count == lambert.labels.on(.top).count)
        #expect(top.allSatisfy { $0 >= 640 && $0 <= 660 }, "\(top)")
        #expect(lambert.labels.on(.left).allSatisfy { label in
            guard let km = Int(label.text) else { return false }
            return km >= 6850 && km <= 6870
        })

        let wgs = GridPlanner.plan(try frame(45.19, 5.72), .wgs84, locale: france)
        #expect(wgs.stepText == "30\u{2033}")
        let pattern = "5°4\\d\u{2032}\\d\\d\u{2033}E|5°4\\d\u{2032}00\u{2033}E|5°4\\d\u{2032}30\u{2033}E"
        let matching = wgs.labels.contains { label in
            label.side == .top && PrintTestText.matches("^(?:\(pattern))$", label.text).count == 1
        }
        #expect(matching, "\(wgs.labels.on(.top).map(\.text))")
        #expect(wgs.labels.contains { $0.side == .left && $0.text.hasSuffix("N") })
        let west = GridPlanner.plan(try frame(40.0, -74.0, scale: 100_000.0), .wgs84)
        #expect(west.labels.on(.top).allSatisfy { $0.text.hasSuffix("W") })
        #expect(west.labels.on(.left).allSatisfy { $0.text.hasSuffix("N") })
    }

    @Test("an MGRS grid labels the two digits of the kilometer inside the 100 km square")
    func mgrsGrid() throws {
        let f = try frame()
        let plan = GridPlanner.plan(f, .mgrs, locale: france)
        #expect(plan.systemText.hasPrefix("MGRS 31T"), "\(plan.systemText)")
        let twoDigits = plan.labels.allSatisfy { label in
            label.text.count == 2 && label.text.allSatisfy { $0.isASCII && $0.isNumber }
        }
        #expect(twoDigits, "\(plan.labels.map(\.text))")
        let square = try #require(GridPlanner.mgrsSquare(f))
        #expect(square.hasPrefix("31T "))
    }

    @Test("the step follows the scale")
    func stepFollowsScale() throws {
        // The spacings of printed maps (P17, Q12): a square of 2 to 5 cm on the paper.
        #expect(GridPlanner.plan(try frame(scale: 50_000.0), .utm).stepText == "1 km")
        #expect(GridPlanner.plan(try frame(scale: 25_000.0), .utm).stepText == "1 km")
        #expect(GridPlanner.plan(try frame(scale: 10_000.0), .utm).stepText == "500 m")
        #expect(GridPlanner.plan(try frame(scale: 100_000.0), .utm).stepText == "2 km")
        #expect(GridPlanner.plan(try frame(scale: 25_000.0), .wgs84).stepText == "30\u{2033}")
        let decimal = GridPlanner.plan(try frame(scale: 25_000.0), .wgs84Decimal, locale: Locale(identifier: "en_US_POSIX"))
        #expect(decimal.stepText == "0.01°")
        #expect(GridPlanner.nearestNice(1_300.0) == 1_000.0)
        #expect(GridPlanner.nearestNice(3_900.0) == 5_000.0)
        #expect(GridPlanner.nearestNice(2_500.0) == 2_000.0)
        // A forced step wins.
        #expect(GridPlanner.plan(try frame(), .utm, step: 500.0).stepText == "500 m")
    }

    @Test("no grid, and grids that do not exist at the place")
    func noGrid() throws {
        #expect(GridPlanner.plan(try frame(), GridKind.none).lines.isEmpty)
        let polar = GridPlanner.plan(try frame(86.0, 10.0), .utm)
        #expect(polar.issue == GridIssue.outsideValidity)
        #expect(polar.lines.isEmpty)
        let abroad = GridPlanner.plan(try frame(35.0, -100.0), .lambert93)
        #expect(abroad.issue == GridIssue.outsideValidity)
    }

    @Test("a page across zones 31 and 32 has each grid on its side of 6 E, the edge, the neighbour in the margin")
    func acrossZones() throws {
        // Pontcharra, the sheet of the P24.0 preview: 6 E crosses it a little east of its middle.
        let f = try frame(45.4274, 6.004)
        let plan = GridPlanner.plan(f, .mgrs, locale: france)
        #expect(plan.zones == ["31T", "32T"])
        #expect(plan.systemText == "MGRS 32T · WGS84")
        #expect(plan.zoneEdges.count == 1)
        let edge = try #require(plan.zoneEdges.first)
        #expect(edge.meridian)
        #expect(edge.degrees == 6.0)
        #expect(edge.sides?.0 == "31" && edge.sides?.1 == "32")
        let xEdge = f.toFrame(6.0, 45.4274).xMm
        let edgeStart = try #require(edge.line.points.first)
        #expect(near(edgeStart.xMm, xEdge, 1e-6))
        #expect(near(edge.line.points.map(\.yMm).min() ?? -1, 0.0, 1e-6))
        #expect(near(edge.line.points.map(\.yMm).max() ?? -1, 190.0, 1e-6))
        // No grid line crosses the edge: each zone's grid stops there.
        for line in plan.lines {
            let west = line.points.allSatisfy { $0.xMm <= xEdge + 1e-6 }
            let east = line.points.allSatisfy { $0.xMm >= xEdge - 1e-6 }
            #expect(west || east, "a line crosses 6 E: \(String(describing: line.points.first)) .. \(String(describing: line.points.last))")
        }

        // Each figure on the top edge is the easting of its own zone there: 31 west of the edge, 32 east of it.
        func easting(_ label: GridLabel, _ zone: Int) -> Double {
            let p = f.toLonLat(label.positionMm, 0.0)
            return Utm.toUtm(p.lat, p.lon, zone: zone).easting
        }
        let top = plan.labels.on(.top)
        let own = top.filter { !$0.neighbour }
        #expect(own.contains { $0.positionMm < xEdge } && own.contains { $0.positionMm > xEdge })
        for label in own {
            let zone = label.positionMm < xEdge ? 31 : 32
            #expect(near(easting(label, zone), label.value, 1.0), "own label \(label.text) at \(label.positionMm)")
        }
        // The neighbour's figures are written where the other zone is: 31 east of the edge, 32 west of it.
        let neighbours = top.filter(\.neighbour)
        #expect(neighbours.contains { $0.positionMm > xEdge } && neighbours.contains { $0.positionMm < xEdge })
        for label in neighbours {
            let zone = label.positionMm > xEdge ? 31 : 32
            #expect(near(easting(label, zone), label.value, 1.0), "neighbour \(label.text) at \(label.positionMm)")
        }

        // On the sides: zone 31 on the left with zone 32 beyond it, the other way round on the right.
        func northing(_ label: GridLabel, _ x: Double, _ zone: Int) -> Double {
            let p = f.toLonLat(x, label.positionMm)
            return Utm.toUtm(p.lat, p.lon, zone: zone).northing
        }
        for label in plan.labels.on(.left) {
            #expect(near(northing(label, 0.0, label.neighbour ? 32 : 31), label.value, 1.0))
        }
        for label in plan.labels.on(.right) {
            #expect(near(northing(label, 277.0, label.neighbour ? 31 : 32), label.value, 1.0))
        }
        #expect(plan.labels.contains { $0.side == .left && $0.neighbour })
        #expect(plan.labels.contains { $0.side == .right && $0.neighbour })
        // The squares with their zone, and their names beside the edge, as in the preview.
        #expect(plan.squares == ["31T GL", "32T KR"])
        #expect(plan.zoneSides == ["31T": CompassSide.west, "32T": CompassSide.east])
        let gl = try #require(plan.squareTags.first { $0.letters == "31T GL" })
        let kr = try #require(plan.squareTags.first { $0.letters == "32T KR" })
        #expect(gl.align == TagAlign.end)
        #expect(kr.align == TagAlign.start)
        #expect(gl.xMm < xEdge && kr.xMm > xEdge)
        #expect(near(gl.yMm, 95.0, 1.0))
        // UTM: the same grid, zones named by hemisphere, no squares.
        let utm = GridPlanner.plan(f, .utm, locale: france)
        #expect(utm.zones == ["31N", "32N"])
        #expect(utm.squares.isEmpty)
        #expect(utm.zoneEdges.count == 1)
    }

    @Test("a page inside one zone has no edge and no neighbour, and a band edge is drawn without neighbours")
    func insideOneZone() throws {
        let inside = GridPlanner.plan(try frame(), .mgrs, locale: france)
        #expect(inside.zones == ["31T"])
        #expect(inside.zoneEdges.isEmpty)
        #expect(!inside.labels.contains(where: \.neighbour))
        #expect(inside.squares.allSatisfy { $0.count == 2 }, "\(inside.squares)")
        // 48 N between bands T and U in zone 32: one grid, the parallel drawn, the squares named with their band.
        let band = GridPlanner.plan(try frame(48.0, 9.0), .mgrs, locale: france)
        #expect(band.zones == ["32T", "32U"])
        #expect(band.zoneEdges.count == 1)
        let edge = try #require(band.zoneEdges.first)
        #expect(!edge.meridian)
        #expect(edge.degrees == 48.0)
        #expect(edge.sides?.0 == "T" && edge.sides?.1 == "U")
        #expect(!band.labels.contains(where: \.neighbour))
        #expect(band.squares.allSatisfy { $0.hasPrefix("32T ") || $0.hasPrefix("32U ") }, "\(band.squares)")
        #expect(band.zoneSides == ["32T": CompassSide.south, "32U": CompassSide.north])
    }

    @Test("the graduated band follows the grid of the sheet and leaves the neighbour's out")
    func graduatedBand() throws {
        let f = try frame(45.4274, 6.004)
        // Only the chosen grid on the sheet (ADR-087): no degrees around a UTM grid, no kilometers around degrees.
        let graduation = GridPlanner.graduation(f, .utm, locale: france)
        #expect(graduation.kind == GridKind.utm)
        let utm = GridPlanner.plan(f, .utm, locale: france)
        let own = utm.labels.filter { $0.side == .top && !$0.neighbour }.count
        #expect(graduation.bars.filter { $0.side == .top }.count == own + 1)
        #expect(GridPlanner.graduation(f, .wgs84, locale: france).kind == GridKind.wgs84)
        #expect(GridPlanner.graduation(f, .lambert93, locale: france).kind == GridKind.lambert93)
        #expect(GridPlanner.graduation(f, GridKind.none, locale: france).bars.isEmpty)
    }

    @Test("MGRS labels are the two figures inside the square, UTM labels keep the hundreds of kilometers")
    func principalDigits() throws {
        let f = try frame(45.4274, 6.004)
        let mgrs = GridPlanner.plan(f, .mgrs, locale: france).labels
        #expect(!mgrs.isEmpty)
        for label in mgrs {
            let parts = try #require(label.parts)
            #expect(parts.prefix == "", "\(label)")
            #expect(parts.major.count == 2, "\(label)")
        }
        let utm = GridPlanner.plan(f, .utm, locale: france).labels
        #expect(utm.allSatisfy { !($0.parts?.prefix.isEmpty ?? true) }, "\(utm)")
    }

    // ---- atlas ---------------------------------------------------------------------------------------------------

    private let a4 = PageSetup(paper: .a4, landscape: true, marginMm: 10.0)

    @Test("a zone smaller than a page is one page, centred")
    func smallZone() {
        let plan = AtlasPlanner.grid(Bounds(minX: 5.70, minY: 45.18, maxX: 5.72, maxY: 45.19), 50_000.0, a4, 10.0)
        #expect(plan.pages.count == 1)
        #expect(near(plan.pages.first?.centerLon ?? 0, 5.71, 1e-9))
    }

    @Test("a grid of pages covers the whole zone with the requested overlap")
    func gridOfPages() throws {
        // About 40 x 25 km at 1:50 000: pages cover 13.85 x 9.5 km.
        let area = Bounds(minX: 5.0, minY: 45.0, maxX: 5.5, maxY: 45.22)
        let plan = AtlasPlanner.grid(area, 50_000.0, a4, 10.0)
        #expect(plan.columns >= 3 && plan.columns <= 5 && plan.rows >= 2 && plan.rows <= 4, "\(plan.columns) x \(plan.rows)")
        #expect(plan.pages.count == plan.columns * plan.rows)
        #expect(plan.pages.map(\.number) == Array(1 ... plan.pages.count))
        // Every corner and the centre of the area fall in at least one page.
        let probes = [
            LonLat(lon: 5.0, lat: 45.0),
            LonLat(lon: 5.5, lat: 45.0),
            LonLat(lon: 5.0, lat: 45.22),
            LonLat(lon: 5.5, lat: 45.22),
            LonLat(lon: 5.25, lat: 45.11),
        ]
        let frames = try plan.pages.map { page in
            try FrameProjection(page.centerLat, page.centerLon, 50_000.0, a4.frameWidthMm, a4.frameHeightMm)
        }
        for p in probes {
            #expect(frames.contains { $0.contains($0.toFrame(p.lon, p.lat)) }, "uncovered \(p)")
        }
        // Neighbouring pages overlap: the next page's left edge falls inside the previous page.
        let first = frames[0]
        let second = frames[1]
        let overlapMm = 277.0 - first.toFrame(second.toLonLat(0.0, 95.0).lon, 45.0).xMm
        #expect(overlapMm > 20.0, "overlap \(overlapMm) mm")
    }

    @Test("more overlap needs at least as many pages")
    func moreOverlap() {
        let area = Bounds(minX: 5.0, minY: 45.0, maxX: 5.5, maxY: 45.22)
        let few = AtlasPlanner.grid(area, 50_000.0, a4, 0.0).pages.count
        let many = AtlasPlanner.grid(area, 50_000.0, a4, 30.0).pages.count
        #expect(many >= few)
    }

    @Test("pages along a track follow it from the start and the track stays inside the useful part of a page")
    func alongTrack() throws {
        // A 30 km track going east along a parallel, at 1:25 000 (pages of 6.9 km).
        let path = (0 ... 300).map { i in LonLat(lon: 5.0 + Double(i) * 0.001, lat: 45.0) }
        let plan = try AtlasPlanner.alongTrack(path, 25_000.0, a4, 10.0)
        #expect(plan.pages.count >= 5 && plan.pages.count <= 10, "\(plan.pages.count) pages")
        #expect(near(plan.pages.first?.centerLon ?? 0, path[0].lon, 1e-9))
        #expect(plan.pages.map(\.number) == Array(1 ... plan.pages.count))
        // Every point of the track is in some page.
        let frames = try plan.pages.map { page in
            try FrameProjection(page.centerLat, page.centerLon, 25_000.0, a4.frameWidthMm, a4.frameHeightMm)
        }
        for p in path {
            #expect(frames.contains { $0.contains($0.toFrame(p.lon, p.lat)) }, "uncovered \(p.lon)")
        }
        // Centres move eastwards only.
        for i in stride(from: 1, to: plan.pages.count, by: 1) {
            #expect(plan.pages[i].centerLon > plan.pages[i - 1].centerLon)
        }
    }

    @Test("a track that ends inside the first page gives one page and an empty track gives none")
    func shortTrack() throws {
        let path = (0 ..< 10).map { i in LonLat(lon: 5.0 + Double(i) * 0.0005, lat: 45.0) }
        #expect(try AtlasPlanner.alongTrack(path, 25_000.0, a4, 10.0).pages.count == 1)
        #expect(try AtlasPlanner.alongTrack([], 25_000.0, a4, 10.0).pages.isEmpty)
    }

    @Test("a wrong scale cannot make an unbounded atlas")
    func boundedAtlas() throws {
        let path = (0 ..< 5_000).map { i in LonLat(lon: Double(i) * 0.01, lat: 45.0) }
        #expect(try AtlasPlanner.alongTrack(path, 1_000.0, a4, 10.0).pages.count <= AtlasPlanner.maxPages)
    }

    @Test("the overview fits every footprint in the sheet")
    func overview() throws {
        let area = Bounds(minX: 5.0, minY: 45.0, maxX: 5.5, maxY: 45.22)
        let plan = AtlasPlanner.grid(area, 50_000.0, a4, 10.0)
        let overview = try AtlasPlanner.overview(plan, 50_000.0, a4, 277.0, 190.0)
        #expect(overview.footprints.count == plan.pages.count)
        for footprint in overview.footprints {
            for corner in footprint.corners {
                let p = overview.toSheet(corner.lon, corner.lat)
                #expect(p.xMm >= -0.01 && p.xMm <= 277.01 && p.yMm >= -0.01 && p.yMm <= 190.01, "\(p) outside the sheet")
            }
        }
    }
}
