import Foundation

// Port of core/src/main/kotlin/fr/toolmap/core/text/GlyphRange.kt.

/// A glyph drawn by the device's font at `GlyphRange.fontSize` pixels: its coverage `alpha` (0 = empty, 255 = ink),
/// `width` × `height` pixels of ink with a `SignedDistance.buffer` margin on each side in `alpha`, placed `left` pixels
/// right of the pen and `top` pixels above the baseline, and the `advance` of the pen. A blank glyph (a space) has no
/// pixel and only an advance.
public struct RasterGlyph: Equatable, Hashable, Sendable {
    public let width: Int
    public let height: Int
    public let left: Int
    public let top: Int
    public let advance: Int
    public let alpha: [UInt8]

    public init(width: Int, height: Int, left: Int, top: Int, advance: Int, alpha: [UInt8]) {
        self.width = width
        self.height = height
        self.left = left
        self.top = top
        self.advance = advance
        self.alpha = alpha
    }
}

/// Draws one character of a font stack, or answers nil when the font has nothing for it.
public protocol GlyphRasterizer {
    func draw(_ codePoint: Int, _ bold: Bool) -> RasterGlyph?
}

/// The glyph file MapLibre asks for (`{fontstack}/{start}-{end}.pbf`, ADR-062): the 256 characters of a range, each as
/// a signed distance field with a `SignedDistance.buffer` margin, in the protocol buffer format of the `glyphs` schema
/// (`glyphs > stacks > glyphs`). The map's base style has no hosted font: this file is made on the device, from the
/// system font, so that names show on the map offline.
public enum GlyphRange {
    /// The size MapLibre's glyph metrics are expressed at.
    public static let fontSize = 24

    /// Characters per range file.
    public static let range = 256

    /// The highest character a range may hold (end of the Unicode planes MapLibre asks for).
    private static let maxCodePoint = 0xFFFF

    /// Distance from the top of MapLibre's line box to the baseline at `fontSize` (hosted fonts give `top` relative to
    /// that point; 24 px text has its baseline about 24 px down, MapLibre adds its own −17 px shaping offset).
    public static let ascent = 24

    private static let wireVarint: UInt64 = 0
    private static let wireLength: UInt64 = 2
    private static let fieldStacks = 1
    private static let fieldStackName = 1
    private static let fieldStackRange = 2
    private static let fieldStackGlyphs = 3
    private static let fieldId = 1
    private static let fieldBitmap = 2
    private static let fieldWidth = 3
    private static let fieldHeight = 4
    private static let fieldLeft = 5
    private static let fieldTop = 6
    private static let fieldAdvance = 7

    /// Reads `0-255` (the `{range}` of the URL, without `.pbf`): the first character, or nil when it is not a range.
    public static func startOf(_ range: String) -> Int? {
        let parts = kotlinSplit(range, "-")
        if parts.count != 2 { return nil }
        guard let start = kotlinInt(parts[0]), let end = kotlinInt(parts[1]) else { return nil }
        if start < 0 || start % GlyphRange.range != 0 || end != start + GlyphRange.range - 1 || end > maxCodePoint {
            return nil
        }
        return start
    }

    /// Whether the font stack asks for the bold face (`… Bold`, `… Medium`, `… Semibold`).
    public static func isBold(_ fontStack: String) -> Bool {
        let name = fontStack.lowercased()
        return name.contains("bold") || name.contains("medium")
    }

    /// The glyph file of the 256 characters from `start`, drawn by `rasterizer`.
    public static func encode(_ fontStack: String, _ start: Int, _ rasterizer: some GlyphRasterizer) throws -> Data {
        try encode(fontStack, start) { code, bold in rasterizer.draw(code, bold) }
    }

    /// The glyph file of the 256 characters from `start`, drawn by `draw` (Kotlin's `fun interface` as a closure).
    public static func encode(_ fontStack: String, _ start: Int, draw: (Int, Bool) -> RasterGlyph?) throws -> Data {
        let bold = isBold(fontStack)
        var stack: [UInt8] = []
        string(&stack, fieldStackName, fontStack)
        string(&stack, fieldStackRange, "\(start)-\(start + GlyphRange.range - 1)")
        for code in start ..< start + GlyphRange.range {
            guard let glyph = draw(code, bold) else { continue }
            let message = try glyphMessage(code, glyph)
            bytes(&stack, fieldStackGlyphs, message)
        }
        var file: [UInt8] = []
        bytes(&file, fieldStacks, stack)
        return Data(file)
    }

    private static func glyphMessage(_ code: Int, _ glyph: RasterGlyph) throws -> [UInt8] {
        var out: [UInt8] = []
        uint(&out, fieldId, code)
        if glyph.width > 0, glyph.height > 0 {
            let w = glyph.width + 2 * SignedDistance.buffer
            let h = glyph.height + 2 * SignedDistance.buffer
            try require(glyph.alpha.count == w * h, "glyph \(code): \(glyph.alpha.count) bytes, expected \(w) × \(h)")
            let field = try SignedDistance.of(glyph.alpha, w, h)
            bytes(&out, fieldBitmap, field)
        }
        uint(&out, fieldWidth, glyph.width)
        uint(&out, fieldHeight, glyph.height)
        sint(&out, fieldLeft, glyph.left)
        sint(&out, fieldTop, glyph.top - ascent)
        uint(&out, fieldAdvance, glyph.advance)
        return out
    }

    // Protocol buffer writing: varints, zigzag for sint32, length-delimited strings and messages.

    private static func varint(_ out: inout [UInt8], _ value: UInt64) {
        var v = value
        while v & ~UInt64(0x7F) != 0 {
            out.append(UInt8(truncatingIfNeeded: (v & 0x7F) | 0x80))
            v >>= 7
        }
        out.append(UInt8(truncatingIfNeeded: v))
    }

    private static func tag(_ out: inout [UInt8], _ field: Int, _ wireType: UInt64) {
        varint(&out, (UInt64(field) << 3) | wireType)
    }

    /// Kotlin writes `value.toLong()`: a negative value takes ten bytes, as in the JVM.
    private static func uint(_ out: inout [UInt8], _ field: Int, _ value: Int) {
        tag(&out, field, wireVarint)
        varint(&out, UInt64(bitPattern: Int64(value)))
    }

    private static func sint(_ out: inout [UInt8], _ field: Int, _ value: Int) {
        tag(&out, field, wireVarint)
        let v = Int32(truncatingIfNeeded: value)
        let zigzag = UInt32(bitPattern: (v << 1) ^ (v >> 31))
        varint(&out, UInt64(zigzag))
    }

    private static func bytes(_ out: inout [UInt8], _ field: Int, _ value: [UInt8]) {
        tag(&out, field, wireLength)
        varint(&out, UInt64(value.count))
        out += value
    }

    private static func string(_ out: inout [UInt8], _ field: Int, _ value: String) {
        bytes(&out, field, Array(value.utf8))
    }
}
