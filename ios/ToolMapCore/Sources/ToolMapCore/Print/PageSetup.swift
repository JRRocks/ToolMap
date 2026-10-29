// Port of core/src/main/kotlin/fr/toolmap/core/print/PageSetup.kt.

/// Paper sizes in millimeters, portrait. The raw value is the Kotlin name, as written in `last.json`.
public enum Paper: String, Codable, CaseIterable, Sendable {
    case a5 = "A5"
    case a4 = "A4"
    case a3 = "A3"
    case a2 = "A2"
    case letter = "LETTER"
    case legal = "LEGAL"
    case custom = "CUSTOM"

    public var widthMm: Double {
        switch self {
        case .a5: 148.0
        case .a4: 210.0
        case .a3: 297.0
        case .a2: 420.0
        case .letter: 215.9
        case .legal: 215.9
        case .custom: 210.0
        }
    }

    public var heightMm: Double {
        switch self {
        case .a5: 210.0
        case .a4: 297.0
        case .a3: 420.0
        case .a2: 594.0
        case .letter: 279.4
        case .legal: 355.6
        case .custom: 297.0
        }
    }

    /// The formats offered first; the others are reached through the custom size (P17, Q2).
    public static let offered: [Paper] = [.a5, .a4, .a3, .letter, .custom]
}

/// The sheet, in the IGN TOP 25 style (the NATO style was removed, ADR-088): format, orientation and the printer
/// margin. From the paper edge inwards: the margin, a strip for the grid labels, the graduated band of the double
/// frame, then the map; under the map, the same band and strip, then the footer that holds title, scale, norths, grid
/// and legend (docs/specs/print-pdf.md §6).
///
/// JSON: the keys and defaults of the Kotlin `@Serializable` class (a missing key takes its default value, an unknown
/// paper the default one, unknown keys are ignored).
public struct PageSetup: Equatable, Hashable, Sendable, Codable {
    public static let defaultMarginMm = 8.0
    public static let maxMarginMm = 50.0

    /// The smallest side of a custom sheet; there is no largest (2026-10-29): the plan says when a page is too big.
    public static let minCustomMm = 50.0
    public static let minFrameMm = 20.0

    /// Room for the grid labels between the margin and the frame.
    public static let labelStripMm = 7.0

    /// The band between the two lines of the frame, graduated in the grid of the sheet.
    public static let graduationMm = 1.6

    /// Points per millimeter: 72 / 25.4.
    public static let ptPerMm = 72.0 / 25.4

    /// A side a custom sheet can have: a number, at least `minCustomMm`.
    public static func isCustomSide(_ mm: Double) -> Bool {
        mm.isFinite && mm >= minCustomMm
    }

    public var paper: Paper
    public var landscape: Bool
    /// White border the printer may not reach, from the paper edge to the label strip.
    public var marginMm: Double
    /// Used when `paper` is `.custom` (portrait width and height).
    public var customWidthMm: Double
    public var customHeightMm: Double

    public init(
        paper: Paper = .a4,
        landscape: Bool = true,
        marginMm: Double = PageSetup.defaultMarginMm,
        customWidthMm: Double = Paper.custom.widthMm,
        customHeightMm: Double = Paper.custom.heightMm
    ) {
        self.paper = paper
        self.landscape = landscape
        self.marginMm = marginMm
        self.customWidthMm = customWidthMm
        self.customHeightMm = customHeightMm
    }

    private enum CodingKeys: String, CodingKey {
        case paper, landscape, marginMm, customWidthMm, customHeightMm
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let paperName = try c.decodeIfPresent(String.self, forKey: .paper)
        paper = paperName.flatMap(Paper.init(rawValue:)) ?? .a4
        landscape = try c.decodeIfPresent(Bool.self, forKey: .landscape) ?? true
        marginMm = try c.decodeIfPresent(Double.self, forKey: .marginMm) ?? PageSetup.defaultMarginMm
        customWidthMm = try c.decodeIfPresent(Double.self, forKey: .customWidthMm) ?? Paper.custom.widthMm
        customHeightMm = try c.decodeIfPresent(Double.self, forKey: .customHeightMm) ?? Paper.custom.heightMm
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(paper.rawValue, forKey: .paper)
        try c.encode(landscape, forKey: .landscape)
        try c.encode(marginMm, forKey: .marginMm)
        try c.encode(customWidthMm, forKey: .customWidthMm)
        try c.encode(customHeightMm, forKey: .customHeightMm)
    }

    private var portraitWidth: Double { paper == .custom ? customWidthMm : paper.widthMm }
    private var portraitHeight: Double { paper == .custom ? customHeightMm : paper.heightMm }

    public var widthMm: Double {
        landscape ? Swift.max(portraitWidth, portraitHeight) : Swift.min(portraitWidth, portraitHeight)
    }

    public var heightMm: Double {
        landscape ? Swift.min(portraitWidth, portraitHeight) : Swift.max(portraitWidth, portraitHeight)
    }

    /// From the paper edge to the map, on the left, top and right sides.
    public var borderMm: Double { marginMm + PageSetup.labelStripMm + PageSetup.graduationMm }

    /// The footer under the map: its rows of boxes and the credits line (see `SheetLayout`).
    public var footerHeightMm: Double { SheetLayout.footerHeight(widthMm - 2 * marginMm) }

    /// The map frame: where it starts on the page and its size.
    public var frameLeftMm: Double { borderMm }
    public var frameTopMm: Double { borderMm }
    public var frameWidthMm: Double { widthMm - 2 * borderMm }
    public var frameHeightMm: Double { heightMm - 2 * borderMm - footerHeightMm }

    /// Top edge of the footer, under the bottom label strip.
    public var footerTopMm: Double { heightMm - marginMm - footerHeightMm }

    /// The page in PostScript points (1/72 inch), rounded as PDF page boxes are integers.
    public var widthPt: Int { kotlinRound(widthMm * PageSetup.ptPerMm) }
    public var heightPt: Int { kotlinRound(heightMm * PageSetup.ptPerMm) }

    /// Problems that would make the frame unusable (empty list when fine).
    public func issues() -> [PageIssue] {
        var out: [PageIssue] = []
        if !(marginMm >= 0.0 && marginMm <= PageSetup.maxMarginMm) { out.append(.marginRange) }
        if paper == .custom, !(PageSetup.isCustomSide(customWidthMm) && PageSetup.isCustomSide(customHeightMm)) {
            out.append(.customSize)
        }
        if !(frameWidthMm >= PageSetup.minFrameMm && frameHeightMm >= PageSetup.minFrameMm) {
            out.append(.frameTooSmall)
        }
        return out
    }
}

public enum PageIssue: Sendable {
    case marginRange
    case customSize
    case frameTooSmall
}

/// The boxes of the footer, left to right (spec §6).
public enum FooterBlock: CaseIterable, Sendable {
    case title
    case scale
    case north
    case grid

    public var widthMm: Double {
        switch self {
        case .title: 40.0
        case .scale: 50.0
        case .north: 46.0
        case .grid: 46.0
        }
    }
}

/// A box of the footer, in millimeters from the footer's top-left corner.
public struct PlacedBlock: Equatable, Hashable, Sendable {
    public let block: FooterBlock
    public let xMm: Double
    public let yMm: Double
    public let widthMm: Double
    public let heightMm: Double

    public init(block: FooterBlock, xMm: Double, yMm: Double, widthMm: Double, heightMm: Double) {
        self.block = block
        self.xMm = xMm
        self.yMm = yMm
        self.widthMm = widthMm
        self.heightMm = heightMm
    }
}

/// How the footer is cut into boxes: fixed widths, the title box taking what is left of its row; the boxes go in rows
/// as wide as the sheet allows. Text keeps the same size whatever the paper, as on a printed map, so a narrow sheet
/// gets two rows rather than smaller letters.
public enum SheetLayout {
    public static let blockHeightMm = 22.0
    public static let gapMm = 2.5
    public static let padMm = 1.5

    /// The line of credits (© of the sources, warnings) under the boxes.
    public static let creditsMm = 3.6

    /// The boxes of a footer `widthMm` wide (the sheet minus its margins).
    public static func place(_ widthMm: Double) -> [PlacedBlock] {
        let inner = widthMm - 2 * padMm
        var rows: [[FooterBlock]] = []
        var used = 0.0
        for block in FooterBlock.allCases {
            let w = block.widthMm
            if rows.isEmpty || used + gapMm + w > inner {
                rows.append([block])
                used = w
            } else {
                rows[rows.count - 1].append(block)
                used += gapMm + w
            }
        }
        var out: [PlacedBlock] = []
        for (r, row) in rows.enumerated() {
            let fixed = row.reduce(0.0) { $0 + $1.widthMm } + gapMm * Double(row.count - 1)
            let spare = Swift.max(inner - fixed, 0.0)
            var x = padMm
            let y = padMm + Double(r) * (blockHeightMm + gapMm)
            for block in row {
                let w = block.widthMm + (block == .title ? spare : 0.0)
                out.append(PlacedBlock(block: block, xMm: x, yMm: y, widthMm: w, heightMm: blockHeightMm))
                x += w + gapMm
            }
        }
        return out
    }

    public static func rows(_ widthMm: Double) -> Int {
        var seen: [Double] = []
        for block in place(widthMm) where !seen.contains(block.yMm) {
            seen.append(block.yMm)
        }
        return seen.count
    }

    public static func footerHeight(_ widthMm: Double) -> Double {
        let count = rows(widthMm)
        return 2 * padMm + Double(count) * blockHeightMm + Double(count - 1) * gapMm + creditsMm
    }
}
