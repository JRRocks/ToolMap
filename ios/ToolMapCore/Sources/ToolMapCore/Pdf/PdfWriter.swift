// Port of core/src/main/kotlin/fr/toolmap/core/pdf/PdfWriter.kt: the PDF 1.4 writer of the scale prints (ADR-020).
// The objects, their numbers and the operators of the content streams are the same as on Android, so the tests read
// the same text in both files.

import Foundation

/// Where a PDF goes, byte after byte (Kotlin `OutputStream`).
public protocol PdfOutput: AnyObject {
    func write(_ data: Data) throws
    func flush() throws
}

/// A PDF kept in memory (Kotlin `ByteArrayOutputStream`).
public final class PdfDataOutput: PdfOutput {
    public private(set) var data = Data()

    public init() {}

    public func write(_ data: Data) throws {
        self.data.append(data)
    }

    public func flush() throws {}
}

/// A PDF written to a file, created or emptied when opened.
public final class PdfFileOutput: PdfOutput {
    private let handle: FileHandle

    public init(url: URL) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        handle = try FileHandle(forWritingTo: url)
    }

    public func write(_ data: Data) throws {
        try handle.write(contentsOf: data)
    }

    public func flush() throws {
        try handle.synchronize()
    }

    public func close() throws {
        try handle.close()
    }
}

/// The three standard fonts every PDF reader has; their glyph widths are known, so text can be measured here.
public enum PdfFont: CaseIterable, Sendable {
    case regular
    case bold
    case italic

    var resource: String {
        switch self {
        case .regular: "F1"
        case .bold: "F2"
        case .italic: "F3"
        }
    }

    var baseFont: String {
        switch self {
        case .regular: "Helvetica"
        case .bold: "Helvetica-Bold"
        case .italic: "Helvetica-Oblique"
        }
    }

    var widths: [Int] {
        switch self {
        case .regular, .italic: HelveticaMetrics.regular
        case .bold: HelveticaMetrics.bold
        }
    }
}

public struct PdfColor: Equatable, Hashable, Sendable {
    public let red: Int
    public let green: Int
    public let blue: Int

    public init(_ red: Int, _ green: Int, _ blue: Int) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    public static let black = PdfColor(0, 0, 0)
    public static let white = PdfColor(255, 255, 255)

    /// The colour of an `0xAARRGGBB` integer (the alpha is ignored: see `PdfPage.alpha`).
    public static func fromArgb(_ argb: Int) -> PdfColor {
        PdfColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)
    }
}

public enum TextAlign: Sendable {
    case left
    case center
    case right
}

/// Line ends; the raw value is the operand of the PDF operator `J`.
public enum LineCap: Int, Sendable {
    case butt = 0
    case round = 1
    case square = 2
}

/// Line joins; the raw value is the operand of the PDF operator `j`.
public enum LineJoin: Int, Sendable {
    case miter = 0
    case round = 1
    case bevel = 2
}

/// Document properties shown by PDF readers.
public struct PdfInfo: Equatable, Sendable {
    public let title: String
    public let subject: String
    public let creator: String
    public let creationMillis: Int64

    public init(
        title: String = "",
        subject: String = "",
        creator: String = "ToolMap",
        creationMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.title = title
        self.subject = subject
        self.creator = creator
        self.creationMillis = creationMillis
    }
}

/// An image to place on a page: a JPEG as it comes from an encoder, or raw RGB pixels compressed on writing. A class:
/// an image with transparency is written once per document and recognised by its identity.
public final class PdfImage: Sendable {
    public let widthPx: Int
    public let heightPx: Int
    public let data: Data
    public let jpeg: Bool
    /// Opacity of each pixel (0 transparent, 255 opaque), row after row; nil for an opaque image.
    public let alpha: Data?

    private init(_ widthPx: Int, _ heightPx: Int, _ data: Data, jpeg: Bool, alpha: Data? = nil) {
        self.widthPx = widthPx
        self.heightPx = heightPx
        self.data = data
        self.jpeg = jpeg
        self.alpha = alpha
    }

    /// A baseline JPEG with three colour components.
    public static func jpeg(_ widthPx: Int, _ heightPx: Int, _ bytes: Data) -> PdfImage {
        PdfImage(widthPx, heightPx, bytes, jpeg: true)
    }

    /// Pixels as red, green, blue bytes, row after row, `widthPx` × `heightPx` × 3 of them.
    public static func rgb(_ widthPx: Int, _ heightPx: Int, _ pixels: Data) throws -> PdfImage {
        try require(pixels.count == widthPx * heightPx * 3, "pixel data does not match \(widthPx) x \(heightPx)")
        return PdfImage(widthPx, heightPx, pixels, jpeg: false)
    }

    /// Like `rgb`, with an opacity byte per pixel: an icon drawn over the map.
    public static func rgba(_ widthPx: Int, _ heightPx: Int, _ pixels: Data, _ alpha: Data) throws -> PdfImage {
        try require(pixels.count == widthPx * heightPx * 3, "pixel data does not match \(widthPx) x \(heightPx)")
        try require(alpha.count == widthPx * heightPx, "alpha data does not match \(widthPx) x \(heightPx)")
        return PdfImage(widthPx, heightPx, pixels, jpeg: false, alpha: alpha)
    }
}

/// Writes a PDF 1.4 file page by page, to an output, without a graphics stack: images go out as soon as they are added
/// (memory stays bounded whatever the paper), the vector content of a page is kept until it ends. All the page API is
/// in millimeters from the top-left corner, y downwards, so a layout in millimeters is a layout on the paper to the
/// thousandth: what the print is about (docs/specs/print-pdf.md).
public final class PdfWriter {
    static let catalogObject = 1
    static let pagesObject = 2
    static let infoObject = 3
    static let fontObject = 4
    private static let firstFreeObject = 7
    private static let offsetDigits = 10

    private let out: any PdfOutput
    private let info: PdfInfo
    private var count: Int64 = 0
    private var offsets: [Int: Int64] = [:]
    private var pages: [Int] = []
    private var nextObject = PdfWriter.firstFreeObject
    private var current: PdfPage?
    private var closed = false

    /// Images with transparency already written, by identity (the image is kept so that its identity stays its own):
    /// see `PdfPage.image`.
    var icons: [ObjectIdentifier: (image: PdfImage, number: Int)] = [:]

    public init(_ output: any PdfOutput, info: PdfInfo = PdfInfo()) throws {
        out = output
        self.info = info
        try write(PdfWriter.latin1("%PDF-1.4\n%"))
        try write(Data([0xE2, 0xE3, 0xCF, 0xD3, 0x0A]))
    }

    public var pageCount: Int { pages.count }

    /// Starts a page of `widthMm` × `heightMm` (the previous one, if still open, ends first).
    public func beginPage(_ widthMm: Double, _ heightMm: Double) throws -> PdfPage {
        if closed { throw ToolMapError.invalidArgument("document already closed") }
        try endPage()
        try require(widthMm > 0 && heightMm > 0, "empty page")
        let page = PdfPage(self, widthMm, heightMm)
        current = page
        return page
    }

    public func endPage() throws {
        let page = current
        current = nil
        try page?.finish()
    }

    /// Ends the open page and writes the catalog, the fonts, the cross-reference table and the trailer. The output is
    /// flushed, not closed.
    public func close() throws {
        if closed { return }
        try endPage()
        closed = true
        var kids = ""
        for page in pages {
            kids += "\(page) 0 R "
        }
        try writeObject(PdfWriter.pagesObject, "<< /Type /Pages /Count \(pages.count) /Kids [\(kids)] >>")
        try writeObject(PdfWriter.catalogObject, "<< /Type /Catalog /Pages \(PdfWriter.pagesObject) 0 R >>")
        let infoBody = "<< /Title \(PdfWriter.hexString(info.title)) /Subject \(PdfWriter.hexString(info.subject))"
            + " /Creator \(PdfWriter.hexString(info.creator)) /Producer \(PdfWriter.hexString(info.creator))"
            + " /CreationDate (\(PdfWriter.pdfDate(info.creationMillis))) >>"
        try writeObject(PdfWriter.infoObject, infoBody)
        for (index, font) in PdfFont.allCases.enumerated() {
            try writeObject(
                PdfWriter.fontObject + index,
                "<< /Type /Font /Subtype /Type1 /BaseFont /\(font.baseFont) /Encoding /WinAnsiEncoding >>"
            )
        }
        let xrefAt = count
        let total = nextObject
        try write(PdfWriter.latin1("xref\n0 \(total)\n"))
        try write(PdfWriter.latin1("0000000000 65535 f \n"))
        var table = ""
        for number in 1 ..< total {
            guard let offset = offsets[number] else {
                throw ToolMapError.invalidArgument("object \(number) was never written")
            }
            let digits = String(offset)
            table += String(repeating: "0", count: Swift.max(PdfWriter.offsetDigits - digits.count, 0)) + digits
            table += " 00000 n \n"
        }
        try write(PdfWriter.latin1(table))
        let infoNumber = PdfWriter.infoObject
        let catalog = PdfWriter.catalogObject
        try write(
            PdfWriter.latin1(
                "trailer\n<< /Size \(total) /Root \(catalog) 0 R /Info \(infoNumber) 0 R >>\nstartxref\n\(xrefAt)\n%%EOF\n"
            )
        )
        try out.flush()
    }

    func allocate() -> Int {
        let number = nextObject
        nextObject += 1
        return number
    }

    func addPage(_ number: Int) {
        pages.append(number)
    }

    /// Writes the dictionary `body` as object `number`.
    func writeObject(_ number: Int, _ body: String) throws {
        offsets[number] = count
        try write(PdfWriter.latin1("\(number) 0 obj\n\(body)\nendobj\n"))
    }

    /// Writes a stream object: `dictionary` entries (without the length) and the `data` as they are.
    func writeStream(_ number: Int, _ dictionary: String, _ data: Data) throws {
        offsets[number] = count
        try write(PdfWriter.latin1("\(number) 0 obj\n<< \(dictionary) /Length \(data.count) >>\nstream\n"))
        try write(data)
        try write(PdfWriter.latin1("\nendstream\nendobj\n"))
    }

    private func write(_ data: Data) throws {
        try out.write(data)
        count += Int64(data.count)
    }

    /// `D:20261002143000Z`: the creation date in UTC.
    private static func pdfDate(_ millis: Int64) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? TimeZone(secondsFromGMT: 0) ?? calendar.timeZone
        let seconds = (Double(millis) / 1000).rounded(.down)
        let date = Date(timeIntervalSince1970: seconds)
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return String(
            format: "D:%04ld%02ld%02ld%02ld%02ld%02ldZ",
            c.year ?? 1970,
            c.month ?? 1,
            c.day ?? 1,
            c.hour ?? 0,
            c.minute ?? 0,
            c.second ?? 0
        )
    }

    /// A text string as UTF-16 hex with its byte order mark: any character, in every reader.
    static func hexString(_ text: String) -> String {
        let digits: [Character] = Array("0123456789ABCDEF")
        var out = "<FEFF"
        for unit in text.utf16 {
            out.append(digits[Int((unit >> 12) & 0xF)])
            out.append(digits[Int((unit >> 8) & 0xF)])
            out.append(digits[Int((unit >> 4) & 0xF)])
            out.append(digits[Int(unit & 0xF)])
        }
        out += ">"
        return out
    }

    /// The bytes of an ASCII or Latin-1 text (`toByteArray(ISO_8859_1)`: anything else becomes `?`).
    static func latin1(_ text: String) -> Data {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(text.utf8.count)
        for scalar in text.unicodeScalars {
            bytes.append(scalar.value < 256 ? UInt8(scalar.value) : 0x3F)
        }
        return Data(bytes)
    }
}

/// One page being written. Coordinates are millimeters from the top-left corner (y down); font sizes are in points,
/// as everywhere in typography. Methods draw straight into the page's content stream.
public final class PdfPage {
    /// Points per millimeter: 72 / 25.4.
    public static let ptPerMm = 72.0 / 25.4

    private static let initialContent = 8192
    private static let kappa = 0.5522847498
    private static let textDecimals = 5
    private static let mediaboxDecimals = 3
    private static let exactDecimals = 9
    private static let hex: [Character] = Array("0123456789ABCDEF")

    private static let substitutes: [Unicode.Scalar: Unicode.Scalar] = [
        "\u{202F}": "\u{00A0}",
        "\u{2009}": "\u{00A0}",
        "\u{2007}": "\u{00A0}",
        "\u{2032}": "'",
        "\u{2033}": "\"",
        "\u{2212}": "-",
        "\u{2011}": "-",
    ]

    /// The characters of Windows-1252 between 0x80 and 0x9F (the others are Latin-1).
    private static let winAnsiHigh: [Unicode.Scalar: UInt8] = [
        "\u{20AC}": 0x80, "\u{201A}": 0x82, "\u{0192}": 0x83, "\u{201E}": 0x84, "\u{2026}": 0x85,
        "\u{2020}": 0x86, "\u{2021}": 0x87, "\u{02C6}": 0x88, "\u{2030}": 0x89, "\u{0160}": 0x8A,
        "\u{2039}": 0x8B, "\u{0152}": 0x8C, "\u{017D}": 0x8E, "\u{2018}": 0x91, "\u{2019}": 0x92,
        "\u{201C}": 0x93, "\u{201D}": 0x94, "\u{2022}": 0x95, "\u{2013}": 0x96, "\u{2014}": 0x97,
        "\u{02DC}": 0x98, "\u{2122}": 0x99, "\u{0161}": 0x9A, "\u{203A}": 0x9B, "\u{0153}": 0x9C,
        "\u{017E}": 0x9E, "\u{0178}": 0x9F,
    ]

    private let writer: PdfWriter
    public let widthMm: Double
    public let heightMm: Double
    private var ops = ""
    /// Image names and object numbers, in the order they were first drawn.
    private var images: [(name: String, number: Int)] = []
    /// Opacity values (as written) and the names of their graphics states, in the order they were first used.
    private var alphas: [(key: String, name: String)] = []
    private var finished = false

    init(_ writer: PdfWriter, _ widthMm: Double, _ heightMm: Double) {
        self.writer = writer
        self.widthMm = widthMm
        self.heightMm = heightMm
        ops.reserveCapacity(PdfPage.initialContent)
        // User space becomes millimeters, y downwards.
        let k = PdfPage.exact(PdfPage.ptPerMm)
        ops += "\(k) 0 0 -\(k) 0 \(PdfPage.exact(heightMm * PdfPage.ptPerMm)) cm\n"
    }

    public func save() {
        ops += "q\n"
    }

    public func restore() {
        ops += "Q\n"
    }

    /// Moves the origin.
    public func translate(_ xMm: Double, _ yMm: Double) {
        ops += "1 0 0 1 \(PdfPage.num(xMm)) \(PdfPage.num(yMm)) cm\n"
    }

    /// Turns what follows by `degrees`, clockwise on the paper, around the origin.
    public func rotate(_ degrees: Double) {
        let r = Angle.radians(degrees)
        let c = cos(r)
        let s = sin(r)
        ops += "\(PdfPage.exact(c)) \(PdfPage.exact(s)) \(PdfPage.exact(-s)) \(PdfPage.exact(c)) 0 0 cm\n"
    }

    public func clipRect(_ x: Double, _ y: Double, _ w: Double, _ h: Double) {
        rect(x, y, w, h)
        ops += "W n\n"
    }

    public func fillColor(_ color: PdfColor) {
        ops += "\(PdfPage.component(color.red)) \(PdfPage.component(color.green)) \(PdfPage.component(color.blue)) rg\n"
    }

    public func strokeColor(_ color: PdfColor) {
        ops += "\(PdfPage.component(color.red)) \(PdfPage.component(color.green)) \(PdfPage.component(color.blue)) RG\n"
    }

    public func lineWidth(_ mm: Double) {
        ops += "\(PdfPage.num(mm)) w\n"
    }

    public func lineStyle(_ cap: LineCap = .butt, _ join: LineJoin = .miter) {
        ops += "\(cap.rawValue) J \(join.rawValue) j\n"
    }

    /// A dash pattern in millimeters; empty for a solid line.
    public func dash(_ pattern: Double...) {
        ops += "[" + pattern.map { PdfPage.num($0) }.joined(separator: " ") + "] 0 d\n"
    }

    /// Opacity of what is stroked and filled from now on, 0 (clear) to 1 (opaque).
    public func alpha(_ opacity: Double) {
        let value = Swift.min(Swift.max(opacity, 0.0), 1.0)
        let key = PdfPage.num(value)
        let name: String
        if let known = alphas.first(where: { $0.key == key }) {
            name = known.name
        } else {
            name = "GS\(alphas.count + 1)"
            alphas.append((key: key, name: name))
        }
        ops += "/\(name) gs\n"
    }

    public func moveTo(_ x: Double, _ y: Double) {
        ops += "\(PdfPage.num(x)) \(PdfPage.num(y)) m\n"
    }

    public func lineTo(_ x: Double, _ y: Double) {
        ops += "\(PdfPage.num(x)) \(PdfPage.num(y)) l\n"
    }

    public func curveTo(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ x3: Double, _ y3: Double) {
        let first = "\(PdfPage.num(x1)) \(PdfPage.num(y1)) \(PdfPage.num(x2)) \(PdfPage.num(y2))"
        ops += "\(first) \(PdfPage.num(x3)) \(PdfPage.num(y3)) c\n"
    }

    public func rect(_ x: Double, _ y: Double, _ w: Double, _ h: Double) {
        ops += "\(PdfPage.num(x)) \(PdfPage.num(y)) \(PdfPage.num(w)) \(PdfPage.num(h)) re\n"
    }

    /// A circle as four Bézier arcs.
    public func circle(_ cx: Double, _ cy: Double, _ r: Double) {
        let k = r * PdfPage.kappa
        moveTo(cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        closePath()
    }

    public func closePath() {
        ops += "h\n"
    }

    public func stroke() {
        ops += "S\n"
    }

    public func fill(evenOdd: Bool = false) {
        ops += evenOdd ? "f*\n" : "f\n"
    }

    public func fillAndStroke() {
        ops += "B\n"
    }

    /// Strokes a polyline through `xs` / `ys` (parallel arrays), skipping points closer than `minStepMm` to the last.
    public func polyline(_ xs: [Double], _ ys: [Double], count: Int? = nil, minStepMm: Double = 0.0) {
        let n = count ?? Swift.min(xs.count, ys.count)
        if n < 2 { return }
        moveTo(xs[0], ys[0])
        var lastX = xs[0]
        var lastY = ys[0]
        var drawn = 1
        for i in 1 ..< n {
            let last = i == n - 1
            if !last, abs(xs[i] - lastX) < minStepMm, abs(ys[i] - lastY) < minStepMm { continue }
            lineTo(xs[i], ys[i])
            lastX = xs[i]
            lastY = ys[i]
            drawn += 1
        }
        if drawn > 1 { stroke() } else { ops += "n\n" }
    }

    /// The width of `text` on the paper, in millimeters.
    public func textWidth(_ text: String, _ font: PdfFont, _ sizePt: Double) -> Double {
        PdfPage.measure(text, font, sizePt)
    }

    /// Writes `text` with its baseline at `y`; `x` is the left end, the middle or the right end according to `align`.
    /// Characters outside Windows-1252 become `?`.
    public func text(
        _ text: String,
        _ x: Double,
        _ y: Double,
        _ font: PdfFont,
        _ sizePt: Double,
        _ color: PdfColor = .black,
        _ align: TextAlign = .left
    ) {
        if text.isEmpty || sizePt <= 0 { return }
        let width = PdfPage.measure(text, font, sizePt)
        let left: Double
        switch align {
        case .left: left = x
        case .center: left = x - width / 2
        case .right: left = x - width
        }
        save()
        fillColor(color)
        let size = PdfPage.num(sizePt / PdfPage.ptPerMm, PdfPage.textDecimals)
        var line = "BT /\(font.resource) \(size) Tf 1 0 0 -1 \(PdfPage.num(left)) \(PdfPage.num(y)) Tm <"
        for byte in PdfPage.encode(text) {
            line.append(PdfPage.hex[Int(byte >> 4)])
            line.append(PdfPage.hex[Int(byte & 0x0F)])
        }
        line += "> Tj ET\n"
        ops += line
        restore()
    }

    /// Draws `image` in the rectangle at (`x`, `y`) of `w` × `h` millimeters.
    public func image(_ image: PdfImage, _ x: Double, _ y: Double, _ w: Double, _ h: Double) throws {
        let number: Int
        if let known = writer.icons[ObjectIdentifier(image)] {
            number = known.number
        } else {
            number = try writeImage(image)
        }
        let name: String
        if let drawn = images.first(where: { $0.number == number }) {
            name = drawn.name
        } else {
            name = "Im\(images.count + 1)"
            images.append((name: name, number: number))
        }
        save()
        // The unit square of an image is y-up: flip it into our y-down space.
        ops += "\(PdfPage.num(w)) 0 0 \(PdfPage.num(-h)) \(PdfPage.num(x)) \(PdfPage.num(y + h)) cm\n/\(name) Do\n"
        restore()
    }

    /// Writes `image` (and its mask) and returns its object number. An image with transparency is an icon: small and
    /// drawn many times, it is written once for the whole document.
    private func writeImage(_ image: PdfImage) throws -> Int {
        var mask: Int?
        if let alpha = image.alpha {
            let maskNumber = writer.allocate()
            try writer.writeStream(
                maskNumber,
                "/Type /XObject /Subtype /Image /Width \(image.widthPx) /Height \(image.heightPx) "
                    + "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
                PdfPage.deflate(alpha)
            )
            mask = maskNumber
        }
        let number = writer.allocate()
        var dictionary = "/Type /XObject /Subtype /Image /Width \(image.widthPx) /Height \(image.heightPx) "
            + "/ColorSpace /DeviceRGB /BitsPerComponent 8 "
            + (image.jpeg ? "/Filter /DCTDecode" : "/Filter /FlateDecode")
        if let mask {
            dictionary += " /SMask \(mask) 0 R"
        }
        let data: Data
        if image.jpeg {
            data = image.data
        } else {
            data = try PdfPage.deflate(image.data)
        }
        try writer.writeStream(number, dictionary, data)
        if image.alpha != nil {
            writer.icons[ObjectIdentifier(image)] = (image: image, number: number)
        }
        return number
    }

    func finish() throws {
        if finished { return }
        finished = true
        let contentNumber = writer.allocate()
        let pageNumber = writer.allocate()
        let content = try PdfPage.deflate(PdfWriter.latin1(ops))
        try writer.writeStream(contentNumber, "/Filter /FlateDecode", content)
        var body = "<< /Type /Page /Parent \(PdfWriter.pagesObject) 0 R"
        body += " /MediaBox [0 0 \(PdfPage.num(widthMm * PdfPage.ptPerMm, PdfPage.mediaboxDecimals)) "
        body += "\(PdfPage.num(heightMm * PdfPage.ptPerMm, PdfPage.mediaboxDecimals))]"
        body += " /Contents \(contentNumber) 0 R /Resources << /Font <<"
        for (i, font) in PdfFont.allCases.enumerated() {
            body += " /\(font.resource) \(PdfWriter.fontObject + i) 0 R"
        }
        body += " >>"
        if !images.isEmpty {
            body += " /XObject <<"
            for entry in images {
                body += " /\(entry.name) \(entry.number) 0 R"
            }
            body += " >>"
        }
        if !alphas.isEmpty {
            body += " /ExtGState <<"
            for entry in alphas {
                body += " /\(entry.name) << /CA \(entry.key) /ca \(entry.key) >>"
            }
            body += " >>"
        }
        body += " >> >>"
        try writer.writeObject(pageNumber, body)
        writer.addPage(pageNumber)
    }

    /// The bytes of `text` in Windows-1252 (a few typographic characters are replaced by their nearest one first).
    static func encode(_ text: String) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(text.unicodeScalars.count)
        for scalar in text.unicodeScalars {
            let s = substitutes[scalar] ?? scalar
            if s.value < 0x80 || (s.value >= 0xA0 && s.value <= 0xFF) {
                out.append(UInt8(s.value))
            } else if let byte = winAnsiHigh[s] {
                out.append(byte)
            } else {
                out.append(0x3F)
            }
        }
        return out
    }

    static func measure(_ text: String, _ font: PdfFont, _ sizePt: Double) -> Double {
        let widths = font.widths
        var units = 0
        for byte in encode(text) {
            units += widths[Int(byte)]
        }
        return Double(units) / 1000.0 * sizePt / ptPerMm
    }

    /// A decimal with at most `decimals` digits, no exponent and no trailing zeros.
    static func num(_ value: Double, _ decimals: Int = 4) -> String {
        if !value.isFinite { return "0" }
        var scale = 1
        for _ in 0 ..< decimals {
            scale *= 10
        }
        let rounded = kotlinRound(value * Double(scale))
        if rounded == 0 { return "0" }
        let negative = rounded < 0
        let magnitude = rounded.magnitude
        let whole = magnitude / UInt(scale)
        let digits = String(magnitude % UInt(scale))
        var fraction = String(repeating: "0", count: Swift.max(decimals - digits.count, 0)) + digits
        while fraction.hasSuffix("0") {
            fraction.removeLast()
        }
        var out = negative ? "-" : ""
        out += String(whole)
        if !fraction.isEmpty {
            out += "." + fraction
        }
        return out
    }

    private static func exact(_ value: Double) -> String {
        num(value, exactDecimals)
    }

    private static func component(_ value: Int) -> String {
        num(Double(Swift.min(Swift.max(value, 0), 255)) / 255.0, 3)
    }

    /// Flate (zlib) data, as `DeflaterOutputStream` writes it: a zlib header, the raw deflate stream and the Adler-32
    /// checksum of the data.
    static func deflate(_ data: Data) throws -> Data {
        var out = Data([0x78, 0x9C])
        if data.isEmpty {
            // An empty final block of fixed codes.
            out.append(contentsOf: [0x03, 0x00])
        } else {
            let raw = try (data as NSData).compressed(using: .zlib) as Data
            out.append(raw)
        }
        let checksum = adler32(data)
        out.append(contentsOf: [
            UInt8((checksum >> 24) & 0xFF),
            UInt8((checksum >> 16) & 0xFF),
            UInt8((checksum >> 8) & 0xFF),
            UInt8(checksum & 0xFF),
        ])
        return out
    }

    private static func adler32(_ data: Data) -> UInt32 {
        let modulus: UInt32 = 65521
        // The most bytes that can be summed before b overflows 32 bits.
        let chunk = 5552
        var a: UInt32 = 1
        var b: UInt32 = 0
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            var index = 0
            let count = buffer.count
            while index < count {
                let end = Swift.min(index + chunk, count)
                while index < end {
                    a &+= UInt32(buffer[index])
                    b &+= a
                    index += 1
                }
                a %= modulus
                b %= modulus
            }
        }
        return (b << 16) | a
    }
}
