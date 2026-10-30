import CoreGraphics
import CoreText
import Foundation
import ToolMapCore

/// Answers MapLibre's glyph requests (`https://glyphs.toolmap.local/{fontstack}/{range}.pbf`, ADR-062) with files made
/// on the device by `GlyphRange` from the system font: names show on the map without a hosted font, offline. Each file
/// is kept in memory once made.
final class GlyphServer: @unchecked Sendable {
    static let host = "glyphs.toolmap.local"
    static let styleURL = "https://\(host)/{fontstack}/{range}.pbf"
    /// Font stacks of the labels: the medium face for names, the regular one for small figures (as on Android).
    static let font = "ToolMap Sans Medium"
    static let regularFont = "ToolMap Sans"

    static let shared = GlyphServer()

    private let lock = NSLock()
    private var made: [String: Data] = [:]
    private let rasterizer = CoreTextRasterizer()
    private let queue = DispatchQueue(label: "fr.toolmap.glyphs", qos: .userInitiated)

    /// The file of `url`, or nil when the address is not a glyph range.
    func load(_ url: URL, completion: @escaping @Sendable (Data?) -> Void) {
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 2 else {
            completion(nil)
            return
        }
        let stack = parts[0]
        var name = parts[1]
        if name.hasSuffix(".pbf") { name.removeLast(4) }
        guard let start = GlyphRange.startOf(name) else {
            completion(nil)
            return
        }
        queue.async { [self] in
            let key = "\(stack)/\(start)"
            lock.lock()
            let cached = made[key]
            lock.unlock()
            if let cached {
                completion(cached)
                return
            }
            let data = try? GlyphRange.encode(stack, start, rasterizer)
            if let data {
                lock.lock()
                made[key] = data
                lock.unlock()
            }
            completion(data)
        }
    }
}

/// Draws a character of the system font at `GlyphRange.fontSize` pixels, white on black in a grey bitmap: the grey is
/// the coverage (Android `SystemFontRasterizer`).
final class CoreTextRasterizer: GlyphRasterizer {
    private let regular: CTFont
    private let bold: CTFont

    init() {
        let size = CGFloat(GlyphRange.fontSize)
        let base = CTFontCreateUIFontForLanguage(.system, size, nil) ?? CTFontCreateWithName("Helvetica" as CFString, size, nil)
        regular = base
        bold = CTFontCreateCopyWithSymbolicTraits(base, size, nil, .traitBold, .traitBold) ?? base
    }

    func draw(_ codePoint: Int, _ bold: Bool) -> RasterGlyph? {
        // Surrogates and control characters have no glyph of their own.
        if (0xD800 ... 0xDFFF).contains(codePoint) || codePoint < 0x20 || (0x7F ... 0x9F).contains(codePoint) {
            return nil
        }
        guard let scalar = Unicode.Scalar(UInt32(codePoint)) else { return nil }
        let font = bold ? self.bold : regular
        var characters = Array(String(Character(scalar)).utf16)
        var glyphs = [CGGlyph](repeating: 0, count: characters.count)
        let found = CTFontGetGlyphsForCharacters(font, &characters, &glyphs, characters.count)
        let blank = scalar.properties.isWhitespace
        if !found, !blank { return nil }
        var glyph = glyphs[0]
        var advanceSize = CGSize.zero
        CTFontGetAdvancesForGlyphs(font, .horizontal, &glyph, &advanceSize, 1)
        let advance = Int(advanceSize.width.rounded())
        var rect = CGRect.zero
        CTFontGetBoundingRectsForGlyphs(font, .horizontal, &glyph, &rect, 1)
        if !found || rect.isEmpty || rect.width < 0.5 || rect.height < 0.5 {
            return RasterGlyph(width: 0, height: 0, left: 0, top: 0, advance: advance, alpha: [])
        }
        let left = Int(rect.minX.rounded(.down))
        let right = Int(rect.maxX.rounded(.up))
        let bottom = Int(rect.minY.rounded(.down))
        let top = Int(rect.maxY.rounded(.up))
        let width = right - left
        let height = top - bottom
        let buffer = SignedDistance.buffer
        let w = width + 2 * buffer
        let h = height + 2 * buffer
        guard let context = CGContext(
            data: nil,
            width: w,
            height: h,
            bitsPerComponent: 8,
            bytesPerRow: w,
            space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.none.rawValue
        ) else { return nil }
        context.setFillColor(gray: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: w, height: h))
        context.setFillColor(gray: 1, alpha: 1)
        var origin = CGPoint(x: CGFloat(buffer - left), y: CGFloat(buffer - bottom))
        CTFontDrawGlyphs(font, &glyph, &origin, 1, context)
        guard let pixels = context.data else { return nil }
        // A bitmap context keeps its top row first, as Android's bitmaps.
        let bytes = pixels.bindMemory(to: UInt8.self, capacity: context.bytesPerRow * h)
        var alpha = [UInt8](repeating: 0, count: w * h)
        for y in 0 ..< h {
            for x in 0 ..< w {
                alpha[y * w + x] = bytes[y * context.bytesPerRow + x]
            }
        }
        return RasterGlyph(width: width, height: height, left: left, top: top, advance: advance, alpha: alpha)
    }
}
