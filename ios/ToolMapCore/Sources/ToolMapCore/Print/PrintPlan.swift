// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintPlan.kt.

import Foundation

/// Where the print is taken from: the screen centre, plus the area or the track an atlas covers.
public struct PrintArea: Equatable, Sendable {
    public var centerLat: Double
    public var centerLon: Double
    /// The area of a `.grid` atlas, in degrees.
    public var bounds: Bounds?
    /// The track a `.track` atlas follows.
    public var path: [LonLat]

    public init(centerLat: Double, centerLon: Double, bounds: Bounds? = nil, path: [LonLat] = []) {
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.bounds = bounds
        self.path = path
    }
}

/// Reasons that keep a print from being exported; each one has to be fixed by the user.
public enum PrintIssue: Sendable {
    case pageSetup
    case scaleRange
    case noArea
    case noTrack
    case tooManyPages
    case tooManyTiles
    case noLayer
}

public enum PageKind: Sendable {
    case map
    case overview
    case minimap
}

/// One page of the PDF: the map frame, its raster and the layers drawn in it. A class: a situation map shared by the
/// pages of an atlas is one object, counted once.
public final class PrintPagePlan: Sendable {
    public let kind: PageKind
    /// 1-based; the overview of an atlas is number 0.
    public let number: Int
    public let frame: FrameProjection
    public let raster: PrintRaster
    public let dpi: Int
    public let layers: PrintLayerPlan
    /// The small situation map of this page, when asked for.
    public let miniMap: PrintPagePlan?
    /// Outlines drawn over an overview or a situation map; the page number tells which one is the current page.
    public let footprints: [PageFootprint]

    public init(
        kind: PageKind,
        number: Int,
        frame: FrameProjection,
        raster: PrintRaster,
        dpi: Int,
        layers: PrintLayerPlan,
        miniMap: PrintPagePlan? = nil,
        footprints: [PageFootprint] = []
    ) {
        self.kind = kind
        self.number = number
        self.frame = frame
        self.raster = raster
        self.dpi = dpi
        self.layers = layers
        self.miniMap = miniMap
        self.footprints = footprints
    }

    /// The tile zooms of every layer: how many tiles the page reads of each, by layer id.
    public func tileCounts() throws -> [String: Int] {
        var out: [String: Int] = [:]
        for layer in layers.layers {
            out[layer.layer.id] = try raster.tileCount(layer.tileZoom)
        }
        return out
    }
}

/// The whole job: its pages, its size, what it needs and what the user should be told.
public struct PrintPlan: Sendable {
    public let template: PrintTemplate
    public let pages: [PrintPagePlan]
    public let atlas: AtlasPlan?
    public let warnings: [PrintWarning]
    public let issues: [PrintIssue]
    /// Tiles read, layer by layer, summed over the pages (neighbouring pages share a few).
    public let tilesByLayer: [String: Int]
    public let totalTiles: Int
    /// (low, high) in bytes.
    public let estimatedBytes: (Int, Int)
    /// Scale error at the north and south edges of the first map page, in percent.
    public let edgeErrorPercent: Double

    public init(
        template: PrintTemplate,
        pages: [PrintPagePlan],
        atlas: AtlasPlan?,
        warnings: [PrintWarning],
        issues: [PrintIssue],
        tilesByLayer: [String: Int],
        totalTiles: Int,
        estimatedBytes: (Int, Int),
        edgeErrorPercent: Double
    ) {
        self.template = template
        self.pages = pages
        self.atlas = atlas
        self.warnings = warnings
        self.issues = issues
        self.tilesByLayer = tilesByLayer
        self.totalTiles = totalTiles
        self.estimatedBytes = estimatedBytes
        self.edgeErrorPercent = edgeErrorPercent
    }

    public var canExport: Bool { issues.isEmpty && !pages.isEmpty }

    public var mapPages: [PrintPagePlan] { pages.filter { $0.kind == .map } }

    /// Every raster the export reads tiles for: the pages and their situation maps (shared ones count once).
    public var rasters: [PrintPagePlan] {
        var seen = Set<ObjectIdentifier>()
        var out: [PrintPagePlan] = []
        for page in pages + pages.compactMap(\.miniMap) where seen.insert(ObjectIdentifier(page)).inserted {
            out.append(page)
        }
        return out
    }

    /// The distinct tiles every page needs, source by source: what to look for in local storage before exporting and
    /// what to download for the missing ones. A layer drawn in an elevation band also needs the elevation tiles under
    /// its own.
    public func requiredTiles() throws -> [String: Set<TileId>] {
        var out: [String: Set<TileId>] = [:]
        for page in rasters {
            for layer in page.layers.layers {
                let tiles = try page.raster.tiles(layer.tileZoom)
                out[layer.source.id, default: []].formUnion(tiles)
                guard let band = layer.band else { continue }
                let dem = tiles.map { tile -> TileId in
                    let window = DemWindow.of(tile.z, tile.x, tile.y)
                    return TileId(window.demZ, window.demX, window.demY)
                }
                out[band.dem.id, default: []].formUnion(dem)
            }
        }
        return out
    }
}

/// Turns a template, a position and the layer stack into the pages to draw and the figures to show (spec §2, §5, §6).
public enum PrintPlanner {
    /// Above this a page is heavy (P17.9): the PDF is still made, with a warning; it was the largest page allowed until
    /// the user asked for no limit on 2026-10-29 (ADR-097).
    public static let heavyPixels = 150_000_000

    public static let maxTiles = 40_000

    /// Resolution of the assembly page of an atlas: a map for finding one's way among the pages, not for reading.
    public static let overviewDpi = 100

    private static let overviewPadding = 0.06

    /// A single page in its situation map takes about a quarter of the width: lots of surroundings to read.
    private static let situationPadding = 1.0

    public static let miniMapWidthMm = 44.0
    public static let miniMapHeightMm = 30.0
    public static let miniMapDpi = 150

    /// Throws only for an impossible frame or tile zoom, which the template, once sanitized, cannot give.
    public static func plan(
        _ template: PrintTemplate,
        _ area: PrintArea,
        _ stack: [LayerConfig],
        _ sources: (String) -> TileSource?
    ) throws -> PrintPlan {
        let t = template.sanitized()
        var issues: [PrintIssue] = []
        func add(_ issue: PrintIssue) {
            if !issues.contains(issue) { issues.append(issue) }
        }
        if !template.page.issues().isEmpty || !t.page.issues().isEmpty { add(.pageSetup) }
        if !(template.scale >= PrintTemplate.minScale && template.scale <= PrintTemplate.maxScale) { add(.scaleRange) }
        let pageIssues = t.page.issues()
        if pageIssues.contains(.frameTooSmall) || pageIssues.contains(.customSize) {
            return empty(t, issues)
        }

        var atlasIssues: [PrintIssue] = []
        let atlas = try atlasOf(t, area, &atlasIssues)
        for issue in atlasIssues {
            add(issue)
        }
        let centers = centersOf(t, area, atlas)
        var pages: [PrintPagePlan] = []
        var footprints: [PageFootprint] = []
        if let atlas {
            footprints = try AtlasPlanner.overview(atlas, t.scale, t.page, 1.0, 1.0).footprints
        }
        // The assembly page opens a grid atlas; an atlas along a track is one page per frame (F9): its pages follow
        // each other in the order of the walk.
        if let atlas, atlas.pages.count > 1, t.atlas == .grid {
            if let overview = try overviewOf(t, footprints, stack, sources) {
                pages.append(overview)
            }
        }
        var sharedMini: PrintPagePlan?
        if t.dressing.miniMap, footprints.count > 1 {
            sharedMini = try miniMapOf(t, footprints, overviewPadding, stack, sources)
        }
        for centre in centers {
            let frame = try FrameProjection(
                centre.centerLat,
                centre.centerLon,
                t.scale,
                t.page.frameWidthMm,
                t.page.frameHeightMm
            )
            let raster = try PrintRaster(frame, t.dpi)
            let layers = layersOf(t, stack, sources, frame.centerLat, t.scale, t.dpi)
            var mini: PrintPagePlan?
            if !t.dressing.miniMap {
                mini = nil
            } else if let sharedMini {
                mini = sharedMini
            } else {
                mini = try miniMapOf(t, [footprintOf(frame, centre.number)], situationPadding, stack, sources)
            }
            pages.append(
                PrintPagePlan(
                    kind: .map,
                    number: centre.number,
                    frame: frame,
                    raster: raster,
                    dpi: t.dpi,
                    layers: layers,
                    miniMap: mini
                )
            )
        }

        let mapPages = pages.filter { $0.kind == .map }
        if !mapPages.isEmpty, mapPages.allSatisfy({ $0.layers.layers.isEmpty }) { add(.noLayer) }

        var byLayer: [String: Int] = [:]
        var total = 0
        var all = pages
        var seenMinis = Set<ObjectIdentifier>()
        for mini in pages.compactMap(\.miniMap) where seenMinis.insert(ObjectIdentifier(mini)).inserted {
            all.append(mini)
        }
        for page in all {
            for (id, count) in try page.tileCounts() {
                byLayer[id] = (byLayer[id] ?? 0) &+ count
                total = total &+ count
            }
        }
        if total > maxTiles { add(.tooManyTiles) }

        let pixels = all.reduce(0) { $0 &+ $1.raster.pixels }
        let bytes = PrintMath.estimatedBytes(pixels, 1)
        var edge = 0.0
        if let first = mapPages.first {
            edge = PrintMath.edgeErrorPercent(t.page.frameHeightMm, t.scale, first.frame.centerLat)
        }
        let largest = mapPages.map(\.raster.pixels).max() ?? 0
        var heavy: [PrintWarning] = []
        if !mapPages.isEmpty, t.dpi > PrintTemplate.highDpi || largest > heavyPixels {
            heavy = [.heavy(dpi: t.dpi, pixelsPerPage: largest)]
        }
        return PrintPlan(
            template: t,
            pages: pages,
            atlas: atlas,
            warnings: mergeWarnings(all) + heavy,
            issues: issues,
            tilesByLayer: byLayer,
            totalTiles: total,
            estimatedBytes: bytes,
            edgeErrorPercent: edge
        )
    }

    /// The frame of the assembly page: every page footprint inside it, with a little air around.
    public static func overviewFrame(
        _ footprints: [PageFootprint],
        _ widthMm: Double,
        _ heightMm: Double,
        padding: Double = 0.06
    ) throws -> FrameProjection? {
        let corners = footprints.flatMap(\.corners)
        if corners.isEmpty { return nil }
        let xs = corners.map { TileMath.mercatorX($0.lon) }
        let ys = corners.map { TileMath.mercatorY($0.lat) }
        let minX = xs.min() ?? 0
        let maxX = xs.max() ?? 0
        let minY = ys.min() ?? 0
        let maxY = ys.max() ?? 0
        let centerX = (minX + maxX) / 2
        let centerY = (minY + maxY) / 2
        let spanX = Swift.max(maxX - minX, 1.0) * (1 + 2 * padding)
        let spanY = Swift.max(maxY - minY, 1.0) * (1 + 2 * padding)
        let mercatorPerMm = Swift.max(spanX / widthMm, spanY / heightMm)
        let lat = Angle.degrees(2 * atan(exp(centerY / TileMath.earthRadiusM)) - Double.pi / 2)
        let lon = Angle.degrees(centerX / TileMath.earthRadiusM)
        // mercatorPerMm = scale / 1000 / cos(lat): the scale the overview is drawn at.
        let scale = mercatorPerMm * 1000.0 / PrintMath.mercatorFactor(lat)
        return try FrameProjection(lat, lon, scale, widthMm, heightMm)
    }

    private static func overviewOf(
        _ t: PrintTemplate,
        _ footprints: [PageFootprint],
        _ stack: [LayerConfig],
        _ sources: (String) -> TileSource?
    ) throws -> PrintPagePlan? {
        guard let frame = try overviewFrame(footprints, t.page.frameWidthMm, t.page.frameHeightMm) else { return nil }
        let layers = layersOf(t, stack, sources, frame.centerLat, frame.scale, overviewDpi)
        let raster = try PrintRaster(frame, overviewDpi)
        return PrintPagePlan(
            kind: .overview,
            number: 0,
            frame: frame,
            raster: raster,
            dpi: overviewDpi,
            layers: layers,
            footprints: footprints
        )
    }

    private static func miniMapOf(
        _ t: PrintTemplate,
        _ footprints: [PageFootprint],
        _ padding: Double,
        _ stack: [LayerConfig],
        _ sources: (String) -> TileSource?
    ) throws -> PrintPagePlan? {
        guard let frame = try overviewFrame(footprints, miniMapWidthMm, miniMapHeightMm, padding: padding) else {
            return nil
        }
        let layers = layersOf(t, stack, sources, frame.centerLat, frame.scale, miniMapDpi)
        let raster = try PrintRaster(frame, miniMapDpi)
        return PrintPagePlan(
            kind: .minimap,
            number: 0,
            frame: frame,
            raster: raster,
            dpi: miniMapDpi,
            layers: layers,
            footprints: footprints
        )
    }

    private static func footprintOf(_ frame: FrameProjection, _ number: Int) -> PageFootprint {
        PageFootprint(
            number: number,
            corners: [
                frame.toLonLat(0.0, 0.0),
                frame.toLonLat(frame.frameWidthMm, 0.0),
                frame.toLonLat(frame.frameWidthMm, frame.frameHeightMm),
                frame.toLonLat(0.0, frame.frameHeightMm),
            ]
        )
    }

    private static func layersOf(
        _ t: PrintTemplate,
        _ stack: [LayerConfig],
        _ sources: (String) -> TileSource?,
        _ latitude: Double,
        _ scale: Double,
        _ dpi: Int
    ) -> PrintLayerPlan {
        PrintLayerPlanner.plan(
            stack: stack,
            source: sources,
            scale: scale,
            dpi: dpi,
            latitude: latitude,
            respectRanges: t.respectLayerRanges,
            lighter: t.lighter
        )
    }

    private static func atlasOf(_ t: PrintTemplate, _ area: PrintArea, _ issues: inout [PrintIssue]) throws -> AtlasPlan? {
        switch t.atlas {
        case .single:
            return nil
        case .grid:
            guard let bounds = area.bounds else {
                issues.append(.noArea)
                return nil
            }
            let plan = AtlasPlanner.grid(bounds, t.scale, t.page, t.overlapPercent)
            if plan.tooMany { issues.append(.tooManyPages) }
            return plan
        case .track:
            if area.path.count < 2 {
                issues.append(.noTrack)
                return nil
            }
            let plan = try AtlasPlanner.alongTrack(area.path, t.scale, t.page, t.overlapPercent)
            if plan.pages.count >= AtlasPlanner.maxPages { issues.append(.tooManyPages) }
            return plan
        }
    }

    private static func centersOf(_ t: PrintTemplate, _ area: PrintArea, _ atlas: AtlasPlan?) -> [AtlasPage] {
        if t.atlas == .single {
            return [AtlasPage(number: 1, row: 0, column: 0, centerLat: area.centerLat, centerLon: area.centerLon)]
        }
        return atlas?.pages ?? []
    }

    private enum WarningKind: Hashable {
        case over(String)
        case range(String)
        case gone(String)
        case heavy
    }

    /// One entry per problem: pages repeat the same warning, only the worst oversampling of a source is kept.
    private static func mergeWarnings(_ pages: [PrintPagePlan]) -> [PrintWarning] {
        var order: [WarningKind] = []
        var out: [WarningKind: PrintWarning] = [:]
        for warning in pages.flatMap(\.layers.warnings) {
            let key: WarningKind
            switch warning {
            case let .oversampled(sourceId, _, _, _, _): key = .over(sourceId)
            case let .outsideRange(sourceId, _): key = .range(sourceId)
            case let .unavailable(sourceId): key = .gone(sourceId)
            // Only the plan as a whole is heavy: never among the warnings of a page's layers.
            case .heavy: key = .heavy
            }
            guard let known = out[key] else {
                order.append(key)
                out[key] = warning
                continue
            }
            if case let .oversampled(_, _, knownFactor, _, _) = known,
               case let .oversampled(_, _, factor, _, _) = warning
            {
                if factor > knownFactor { out[key] = warning }
            } else {
                out[key] = warning
            }
        }
        return order.compactMap { out[$0] }
    }

    private static func empty(_ t: PrintTemplate, _ issues: [PrintIssue]) -> PrintPlan {
        PrintPlan(
            template: t,
            pages: [],
            atlas: nil,
            warnings: [],
            issues: issues,
            tilesByLayer: [:],
            totalTiles: 0,
            estimatedBytes: (0, 0),
            edgeErrorPercent: 0.0
        )
    }
}
