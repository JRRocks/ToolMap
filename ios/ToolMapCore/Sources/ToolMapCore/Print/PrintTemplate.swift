// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintTemplate.kt.

import Foundation

/// The elements drawn around and over the map, each one switchable (spec §4).
///
/// JSON: the keys and defaults of the Kotlin `@Serializable` class.
public struct Dressing: Equatable, Hashable, Sendable, Codable {
    public var scaleText: Bool
    public var scaleBar: Bool
    public var northArrow: Bool
    public var legend: Bool
    public var cartouche: Bool
    /// Situation map of an atlas page; the single-page print of P17 does not draw it.
    public var miniMap: Bool
    public var cornerCoordinates: Bool
    public var tracks: Bool
    public var waypoints: Bool
    /// The name next to each waypoint; off leaves the icons alone (P17, Q16).
    public var waypointNames: Bool

    public init(
        scaleText: Bool = true,
        scaleBar: Bool = true,
        northArrow: Bool = true,
        legend: Bool = true,
        cartouche: Bool = true,
        miniMap: Bool = false,
        cornerCoordinates: Bool = true,
        tracks: Bool = true,
        waypoints: Bool = true,
        waypointNames: Bool = true
    ) {
        self.scaleText = scaleText
        self.scaleBar = scaleBar
        self.northArrow = northArrow
        self.legend = legend
        self.cartouche = cartouche
        self.miniMap = miniMap
        self.cornerCoordinates = cornerCoordinates
        self.tracks = tracks
        self.waypoints = waypoints
        self.waypointNames = waypointNames
    }

    private enum CodingKeys: String, CodingKey {
        case scaleText, scaleBar, northArrow, legend, cartouche, miniMap, cornerCoordinates, tracks, waypoints
        case waypointNames
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        scaleText = try c.decodeIfPresent(Bool.self, forKey: .scaleText) ?? true
        scaleBar = try c.decodeIfPresent(Bool.self, forKey: .scaleBar) ?? true
        northArrow = try c.decodeIfPresent(Bool.self, forKey: .northArrow) ?? true
        legend = try c.decodeIfPresent(Bool.self, forKey: .legend) ?? true
        cartouche = try c.decodeIfPresent(Bool.self, forKey: .cartouche) ?? true
        miniMap = try c.decodeIfPresent(Bool.self, forKey: .miniMap) ?? false
        cornerCoordinates = try c.decodeIfPresent(Bool.self, forKey: .cornerCoordinates) ?? true
        tracks = try c.decodeIfPresent(Bool.self, forKey: .tracks) ?? true
        waypoints = try c.decodeIfPresent(Bool.self, forKey: .waypoints) ?? true
        waypointNames = try c.decodeIfPresent(Bool.self, forKey: .waypointNames) ?? true
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(scaleText, forKey: .scaleText)
        try c.encode(scaleBar, forKey: .scaleBar)
        try c.encode(northArrow, forKey: .northArrow)
        try c.encode(legend, forKey: .legend)
        try c.encode(cartouche, forKey: .cartouche)
        try c.encode(miniMap, forKey: .miniMap)
        try c.encode(cornerCoordinates, forKey: .cornerCoordinates)
        try c.encode(tracks, forKey: .tracks)
        try c.encode(waypoints, forKey: .waypoints)
        try c.encode(waypointNames, forKey: .waypointNames)
    }
}

/// Everything the user chooses for a print except where the frame is and what is on the map: the frame is placed on
/// the map, the content comes from what the map shows. The last one used is remembered (P17, Q22).
///
/// JSON (`last.json`): the keys and defaults of the Kotlin `@Serializable` class; an unknown enum value takes the
/// default, as Kotlin's `coerceInputValues` does.
public struct PrintTemplate: Equatable, Hashable, Sendable, Codable {
    public static let defaultScale = 25_000.0
    public static let defaultDpi = 300
    public static let defaultOverlapPercent = 10.0
    public static let minScale = 500.0
    public static let maxScale = 10_000_000.0

    /// The lowest resolution; there is no highest since 2026-10-29 (P17.9, ADR-097).
    public static let minDpi = 72

    /// Above this the PDF gets the warning of a heavy print: printers and maps rarely gain beyond it (P17.9).
    public static let highDpi = 300
    public static let maxOverlapPercent = 50.0

    /// The scales offered as presets (P17, Q3); any other value can be typed.
    public static let scales: [Int] = [10_000, 20_000, 25_000, 50_000, 100_000, 250_000]

    public static let dpis: [Int] = [150, 200, 300]

    public var page: PageSetup
    /// The scale denominator: 25 000 for 1:25 000.
    public var scale: Double
    public var dpi: Int
    /// One zoom level less than the sharp one: a lighter file, a touch softer.
    public var lighter: Bool
    /// Leave out the layers whose display range (N3) does not reach this scale.
    public var respectLayerRanges: Bool
    public var title: String
    public var subtitle: String
    public var dressing: Dressing
    public var grid: GridKind
    /// Spacing of the grid lines in the grid's own unit (meters, or degrees); nil follows the scale (`GridSpacing`).
    public var gridStep: Double?
    public var atlas: AtlasMode
    public var overlapPercent: Double

    public init(
        page: PageSetup = PageSetup(),
        scale: Double = PrintTemplate.defaultScale,
        dpi: Int = PrintTemplate.defaultDpi,
        lighter: Bool = false,
        respectLayerRanges: Bool = true,
        title: String = "",
        subtitle: String = "",
        dressing: Dressing = Dressing(),
        grid: GridKind = .utm,
        gridStep: Double? = nil,
        atlas: AtlasMode = .single,
        overlapPercent: Double = PrintTemplate.defaultOverlapPercent
    ) {
        self.page = page
        self.scale = scale
        self.dpi = dpi
        self.lighter = lighter
        self.respectLayerRanges = respectLayerRanges
        self.title = title
        self.subtitle = subtitle
        self.dressing = dressing
        self.grid = grid
        self.gridStep = gridStep
        self.atlas = atlas
        self.overlapPercent = overlapPercent
    }

    private enum CodingKeys: String, CodingKey {
        case page, scale, dpi, lighter, respectLayerRanges, title, subtitle, dressing, grid, gridStep, atlas
        case overlapPercent
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        page = try c.decodeIfPresent(PageSetup.self, forKey: .page) ?? PageSetup()
        scale = try c.decodeIfPresent(Double.self, forKey: .scale) ?? PrintTemplate.defaultScale
        dpi = try c.decodeIfPresent(Int.self, forKey: .dpi) ?? PrintTemplate.defaultDpi
        lighter = try c.decodeIfPresent(Bool.self, forKey: .lighter) ?? false
        respectLayerRanges = try c.decodeIfPresent(Bool.self, forKey: .respectLayerRanges) ?? true
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle) ?? ""
        dressing = try c.decodeIfPresent(Dressing.self, forKey: .dressing) ?? Dressing()
        let gridName = try c.decodeIfPresent(String.self, forKey: .grid)
        grid = gridName.flatMap(GridKind.init(rawValue:)) ?? .utm
        gridStep = try c.decodeIfPresent(Double.self, forKey: .gridStep)
        let atlasName = try c.decodeIfPresent(String.self, forKey: .atlas)
        atlas = atlasName.flatMap(AtlasMode.init(rawValue:)) ?? .single
        overlapPercent = try c.decodeIfPresent(Double.self, forKey: .overlapPercent)
            ?? PrintTemplate.defaultOverlapPercent
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(page, forKey: .page)
        try c.encode(scale, forKey: .scale)
        try c.encode(dpi, forKey: .dpi)
        try c.encode(lighter, forKey: .lighter)
        try c.encode(respectLayerRanges, forKey: .respectLayerRanges)
        try c.encode(title, forKey: .title)
        try c.encode(subtitle, forKey: .subtitle)
        try c.encode(dressing, forKey: .dressing)
        try c.encode(grid.rawValue, forKey: .grid)
        // Kotlin writes the defaults, `null` included (`encodeDefaults`).
        if let gridStep {
            try c.encode(gridStep, forKey: .gridStep)
        } else {
            try c.encodeNil(forKey: .gridStep)
        }
        try c.encode(atlas.rawValue, forKey: .atlas)
        try c.encode(overlapPercent, forKey: .overlapPercent)
    }

    /// The same template with every figure brought back into its valid range (a hand-edited or old file).
    public func sanitized() -> PrintTemplate {
        var t = self
        t.scale = scale.isFinite
            ? Swift.min(Swift.max(scale, PrintTemplate.minScale), PrintTemplate.maxScale)
            : PrintTemplate.defaultScale
        t.dpi = Swift.max(dpi, PrintTemplate.minDpi)
        if let step = gridStep, step.isFinite, step > 0.0 {
            t.gridStep = step
        } else {
            t.gridStep = nil
        }
        // The situation map is no longer drawn (P17): its tiles are not fetched either.
        t.dressing.miniMap = false
        t.overlapPercent = overlapPercent.isFinite
            ? Swift.min(Swift.max(overlapPercent, 0.0), PrintTemplate.maxOverlapPercent)
            : PrintTemplate.defaultOverlapPercent
        t.page.marginMm = page.marginMm.isFinite
            ? Swift.min(Swift.max(page.marginMm, 0.0), PageSetup.maxMarginMm)
            : PageSetup.defaultMarginMm
        return t
    }

    /// The grid spacing actually drawn: the one chosen, or the one of the scale.
    public var effectiveGridStep: Double { gridStep ?? GridSpacing.auto(grid, scale) }
}

/// Reads and writes templates as JSON, tolerating files from other versions (unknown fields, missing ones).
public enum PrintTemplateCodec {
    public static func encode(_ template: PrintTemplate) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        let data = try encoder.encode(template)
        return String(decoding: data, as: UTF8.self)
    }

    /// The template in `text`, sanitized; nil when it is not a template at all.
    public static func decode(_ text: String) -> PrintTemplate? {
        guard let template = try? JSONDecoder().decode(PrintTemplate.self, from: Data(text.utf8)) else { return nil }
        return template.sanitized()
    }
}
