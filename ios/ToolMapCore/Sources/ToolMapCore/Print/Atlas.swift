// Port of core/src/main/kotlin/fr/toolmap/core/print/Atlas.kt.

import Foundation

/// The raw value is the Kotlin name, as written in `last.json`.
public enum AtlasMode: String, Codable, CaseIterable, Sendable {
    case single = "SINGLE"
    case grid = "GRID"
    case track = "TRACK"
}

/// One page of an atlas: its number and where its frame is centred.
public struct AtlasPage: Equatable, Hashable, Sendable {
    public let number: Int
    public let row: Int
    public let column: Int
    public let centerLat: Double
    public let centerLon: Double

    public init(number: Int, row: Int, column: Int, centerLat: Double, centerLon: Double) {
        self.number = number
        self.row = row
        self.column = column
        self.centerLat = centerLat
        self.centerLon = centerLon
    }
}

/// The pages of an atlas and how they tile the area.
public struct AtlasPlan: Equatable, Hashable, Sendable {
    public let pages: [AtlasPage]
    public let columns: Int
    public let rows: Int
    public let overlapPercent: Double
    /// True when the area needs more than `AtlasPlanner.maxPages` pages: none is listed, the scale is wrong.
    public let tooMany: Bool

    public init(pages: [AtlasPage], columns: Int, rows: Int, overlapPercent: Double, tooMany: Bool = false) {
        self.pages = pages
        self.columns = columns
        self.rows = rows
        self.overlapPercent = overlapPercent
        self.tooMany = tooMany
    }
}

/// A page's footprint on the ground, as the four corners (clockwise from the top left).
public struct PageFootprint: Equatable, Hashable, Sendable {
    public let number: Int
    public let corners: [LonLat]

    public init(number: Int, corners: [LonLat]) {
        self.number = number
        self.corners = corners
    }
}

/// The overview of an atlas: the footprints, fitted into a sheet at a scale that suits it.
public struct AtlasOverview: Sendable {
    public let footprints: [PageFootprint]
    public let bounds: Bounds
    private let widthMm: Double
    private let heightMm: Double

    /// Millimeters of the overview sheet per Mercator meter, the same on both axes.
    private let mmPerMeter: Double
    private let originX: Double
    private let originY: Double

    public init(footprints: [PageFootprint], bounds: Bounds, widthMm: Double, heightMm: Double) {
        self.footprints = footprints
        self.bounds = bounds
        self.widthMm = widthMm
        self.heightMm = heightMm
        let minX = TileMath.mercatorX(bounds.minX)
        let maxX = TileMath.mercatorX(bounds.maxX)
        let minY = TileMath.mercatorY(bounds.minY)
        let maxY = TileMath.mercatorY(bounds.maxY)
        let w = Swift.max(maxX - minX, 1.0)
        let h = Swift.max(maxY - minY, 1.0)
        let perMeter = Swift.min(widthMm / w, heightMm / h)
        mmPerMeter = perMeter
        originX = (minX + maxX) / 2 - widthMm / perMeter / 2
        originY = (minY + maxY) / 2 + heightMm / perMeter / 2
    }

    public func toSheet(_ lon: Double, _ lat: Double) -> FramePoint {
        FramePoint(
            xMm: (TileMath.mercatorX(lon) - originX) * mmPerMeter,
            yMm: (originY - TileMath.mercatorY(lat)) * mmPerMeter
        )
    }
}

/// Cuts an area, or the neighbourhood of a track, into pages (spec §5).
public enum AtlasPlanner {
    /// A safety limit: more pages than this is almost certainly a wrong scale.
    public static let maxPages = 400

    private static let maxOverlap = 50.0

    /// Pages tiling `area` (degrees), each overlapping its neighbours by `overlapPercent` of the frame.
    public static func grid(_ area: Bounds, _ scale: Double, _ setup: PageSetup, _ overlapPercent: Double) -> AtlasPlan {
        let overlap = Swift.min(Swift.max(overlapPercent, 0.0), maxOverlap) / 100.0
        // Each page is exact at its own latitude, so its frame spans a little more Mercator meters the further it is
        // from the equator: step with the smallest span the area gives, and no page leaves a gap.
        let nearestToEquator = area.minY <= 0.0 && area.maxY >= 0.0 ? 0.0 : Swift.min(abs(area.minY), abs(area.maxY))
        let k0 = PrintMath.mercatorFactor(nearestToEquator)
        let frameW = PrintMath.groundMeters(setup.frameWidthMm, scale) * k0
        let frameH = PrintMath.groundMeters(setup.frameHeightMm, scale) * k0
        let minX = TileMath.mercatorX(area.minX)
        let maxX = TileMath.mercatorX(area.maxX)
        let minY = TileMath.mercatorY(area.minY)
        let maxY = TileMath.mercatorY(area.maxY)
        let columns = countFor(maxX - minX, frameW, overlap)
        let rows = countFor(maxY - minY, frameH, overlap)
        if columns * rows > maxPages {
            return AtlasPlan(pages: [], columns: columns, rows: rows, overlapPercent: overlap * 100.0, tooMany: true)
        }
        var pages: [AtlasPage] = []
        let stepX = columns > 1 ? (maxX - minX - frameW) / Double(columns - 1) : 0.0
        let stepY = rows > 1 ? (maxY - minY - frameH) / Double(rows - 1) : 0.0
        var number = 1
        for row in stride(from: 0, to: rows, by: 1) {
            for column in stride(from: 0, to: columns, by: 1) {
                let cx = columns > 1 ? minX + frameW / 2 + Double(column) * stepX : (minX + maxX) / 2
                let cy = rows > 1 ? maxY - frameH / 2 - Double(row) * stepY : (minY + maxY) / 2
                pages.append(AtlasPage(number: number, row: row, column: column, centerLat: latOf(cy), centerLon: lonOf(cx)))
                number += 1
            }
        }
        return AtlasPlan(pages: pages, columns: columns, rows: rows, overlapPercent: overlap * 100.0)
    }

    /// Pages along `path`: the first is centred on the start; the next on the last point still inside the useful part
    /// of the page (its frame minus the overlap on every side), and so on to the end. Pages keep the order of the
    /// track. Throws for a scale that is not positive.
    public static func alongTrack(
        _ path: [LonLat],
        _ scale: Double,
        _ setup: PageSetup,
        _ overlapPercent: Double
    ) throws -> AtlasPlan {
        guard let start = path.first else {
            return AtlasPlan(pages: [], columns: 0, rows: 0, overlapPercent: overlapPercent)
        }
        let overlap = Swift.min(Swift.max(overlapPercent, 0.0), maxOverlap) / 100.0
        var pages: [AtlasPage] = []
        var center = start
        pages.append(AtlasPage(number: 1, row: 0, column: 0, centerLat: center.lat, centerLon: center.lon))
        var i = 1
        while i < path.count, pages.count < maxPages {
            let frame = try FrameProjection(center.lat, center.lon, scale, setup.frameWidthMm, setup.frameHeightMm)
            let halfW = setup.frameWidthMm / 2 * (1 - overlap)
            let halfH = setup.frameHeightMm / 2 * (1 - overlap)
            var lastInside = -1
            while i < path.count {
                let p = frame.toFrame(path[i].lon, path[i].lat)
                let dx = abs(p.xMm - setup.frameWidthMm / 2)
                let dy = abs(p.yMm - setup.frameHeightMm / 2)
                if dx > halfW || dy > halfH { break }
                lastInside = i
                i += 1
            }
            if i >= path.count { break }
            // The next page starts where this one stopped being useful; at least move on past the center.
            center = path[lastInside >= 0 ? lastInside : i]
            if lastInside < 0 { i += 1 }
            pages.append(
                AtlasPage(number: pages.count + 1, row: 0, column: pages.count, centerLat: center.lat, centerLon: center.lon)
            )
        }
        return AtlasPlan(pages: pages, columns: pages.count, rows: 1, overlapPercent: overlap * 100.0)
    }

    /// Footprints and overview mapping of `plan`, fitted into a `widthMm` x `heightMm` sheet.
    public static func overview(
        _ plan: AtlasPlan,
        _ scale: Double,
        _ setup: PageSetup,
        _ widthMm: Double,
        _ heightMm: Double
    ) throws -> AtlasOverview {
        var footprints: [PageFootprint] = []
        for page in plan.pages {
            let frame = try FrameProjection(
                page.centerLat,
                page.centerLon,
                scale,
                setup.frameWidthMm,
                setup.frameHeightMm
            )
            footprints.append(
                PageFootprint(
                    number: page.number,
                    corners: [
                        frame.toLonLat(0.0, 0.0),
                        frame.toLonLat(setup.frameWidthMm, 0.0),
                        frame.toLonLat(setup.frameWidthMm, setup.frameHeightMm),
                        frame.toLonLat(0.0, setup.frameHeightMm),
                    ]
                )
            )
        }
        let all = footprints.flatMap(\.corners)
        let bounds: Bounds
        if all.isEmpty {
            bounds = Bounds(minX: 0.0, minY: 0.0, maxX: 0.0, maxY: 0.0)
        } else {
            bounds = Bounds(
                minX: all.map(\.lon).min() ?? 0.0,
                minY: all.map(\.lat).min() ?? 0.0,
                maxX: all.map(\.lon).max() ?? 0.0,
                maxY: all.map(\.lat).max() ?? 0.0
            )
        }
        return AtlasOverview(footprints: footprints, bounds: bounds, widthMm: widthMm, heightMm: heightMm)
    }

    /// Pages needed along one axis: `frame` covers `extent` with a step of `frame * (1 - overlap)`.
    private static func countFor(_ extent: Double, _ frame: Double, _ overlap: Double) -> Int {
        if extent <= frame { return 1 }
        let step = frame * (1 - overlap)
        return kotlinToInt(Swift.min(ceil((extent - frame) / step - 1e-9) + 1, Double(Int32.max)))
    }

    private static func lonOf(_ mx: Double) -> Double {
        Angle.degrees(mx / TileMath.earthRadiusM)
    }

    private static func latOf(_ my: Double) -> Double {
        Angle.degrees(2 * atan(exp(my / TileMath.earthRadiusM)) - Double.pi / 2)
    }
}
