// Port of core/src/test/kotlin/fr/toolmap/core/pdf/PdfWriterTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct PdfWriterTests {
    private func write(_ block: (PdfWriter) throws -> Void) throws -> Data {
        let out = PdfDataOutput()
        let writer = try PdfWriter(out, info: PdfInfo(title: "Carte \u{E9}", creationMillis: 0))
        try block(writer)
        try writer.close()
        return out.data
    }

    @Test("the file is well formed with exact page boxes and every object at its offset")
    func wellFormed() throws {
        let bytes = try write { w in
            try w.beginPage(297.0, 210.0).rect(10.0, 10.0, 277.0, 190.0)
            try w.beginPage(210.0, 297.0).rect(10.0, 10.0, 190.0, 277.0)
        }
        #expect(String(decoding: bytes.prefix(8), as: UTF8.self).hasPrefix("%PDF-1.4"))
        let probe = try PdfProbe(bytes)
        #expect(try probe.pageNumbers().count == 2)
        // A4 landscape is 841.890 x 595.276 pt: not the rounded 842 x 595.
        #expect(try probe.mediaBox(0) == [0.0, 0.0, 841.89, 595.276])
        #expect(try probe.mediaBox(1) == [0.0, 0.0, 595.276, 841.89])
        for number in probe.offsets.keys {
            _ = try probe.body(number)
        }
        #expect(probe.text.hasSuffix("%%EOF\n"))
        #expect(probe.text.contains("/Title <FEFF00430061007200740065002000E9>"))
    }

    @Test("user space is millimeters from the top-left corner")
    func millimeters() throws {
        let bytes = try write { w in
            let page = try w.beginPage(100.0, 50.0)
            page.rect(1.0, 2.0, 3.0, 4.5)
            page.fill()
        }
        let content = try PdfProbe(bytes).contentOfPage(0)
        // 72 / 25.4 pt per mm, y flipped, origin moved to the top of the page (50 mm = 141.732283 pt).
        #expect(content.hasPrefix("2.834645669 0 0 -2.834645669 0 141.732283465 cm\n"), "\(content.prefix(80))")
        #expect(content.contains("1 2 3 4.5 re\nf\n"))
    }

    @Test("numbers are short and never use an exponent")
    func numbers() {
        #expect(PdfPage.num(0.00001) == "0")
        #expect(PdfPage.num(12.345678) == "12.3457")
        #expect(PdfPage.num(-0.5) == "-0.5")
        #expect(PdfPage.num(100.0) == "100")
        #expect(PdfPage.num(0.00001, 5) == "0.00001")
        #expect(PdfPage.num(Double.nan) == "0")
        #expect(PdfPage.num(123_456_789.123456) == "123456789.1235")
    }

    @Test("text is measured with the Helvetica metrics and encoded in Windows-1252")
    func measureAndEncode() {
        // H 722 + e 556 + l 222 + l 222 + o 556 = 2278 thousandths.
        let widthMm = PdfPage.measure("Hello", .regular, 10.0)
        #expect(abs(widthMm - 2.278 * 10.0 / PdfPage.ptPerMm) <= 1e-9)
        #expect(PdfPage.measure("Hello", .bold, 10.0) > widthMm)
        #expect(PdfPage.encode("\u{E9}\u{A9}\u{B0}") == [UInt8]([0xE9, 0xA9, 0xB0]))
        // Primes become quotes, the narrow no-break space a no-break space, the unknown a question mark.
        #expect(PdfPage.encode("\u{2032}\u{2033}\u{202F}\u{4E2D}") == [UInt8]([0x27, 0x22, 0xA0, 0x3F]))
        #expect(PdfPage.measure("", .regular, 10.0) == 0.0)
    }

    @Test("text is placed by its baseline and aligned by measured width")
    func textPlacement() throws {
        let bytes = try write { w in
            let page = try w.beginPage(100.0, 50.0)
            page.text("Hi", 50.0, 20.0, .bold, 12.0, PdfColor(255, 0, 0), .center)
        }
        let content = try PdfProbe(bytes).contentOfPage(0)
        let width = PdfPage.measure("Hi", .bold, 12.0)
        let expected = PdfPage.num(50.0 - width / 2)
        #expect(content.contains("/F2 4.23333 Tf 1 0 0 -1 \(expected) 20 Tm <4869> Tj ET"), "\(content)")
        #expect(content.contains("1 0 0 rg"))
    }

    @Test("a jpeg goes through untouched and raw pixels come back identical")
    func images() throws {
        let jpeg: [UInt8] = [0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9]
        let pixels: [UInt8] = (0 ..< 12).map { UInt8(truncatingIfNeeded: $0 * 7) }
        let rgb = try PdfImage.rgb(2, 2, Data(pixels))
        let bytes = try write { w in
            let page = try w.beginPage(100.0, 50.0)
            try page.image(PdfImage.jpeg(2, 2, Data(jpeg)), 0.0, 0.0, 10.0, 10.0)
            try page.image(rgb, 10.0, 0.0, 10.0, 10.0)
        }
        let probe = try PdfProbe(bytes)
        var images: [Int] = []
        for number in probe.offsets.keys.sorted() {
            let body = try probe.body(number)
            if body.contains("/Subtype /Image") { images.append(number) }
        }
        #expect(images.count == 2)
        let first = try #require(images.first)
        let second = try #require(images.last)
        #expect(try probe.body(first).contains("/DCTDecode"))
        #expect(try probe.stream(first) == jpeg)
        #expect(try probe.body(second).contains("/FlateDecode"))
        #expect(try probe.stream(second) == pixels)
        let content = try probe.contentOfPage(0)
        // The image is flipped to be upright in the y-down space: bottom edge at y + h.
        #expect(content.contains("10 0 0 -10 0 10 cm\n/Im1 Do"), "\(content)")
        let pageBody = try probe.body(probe.pageNumbers()[0])
        #expect(pageBody.contains("/Im1") && pageBody.contains("/Im2"))
    }

    @Test("opacity levels become named graphics states used by the page")
    func opacity() throws {
        let bytes = try write { w in
            let page = try w.beginPage(100.0, 50.0)
            page.alpha(0.5)
            page.alpha(0.25)
            page.alpha(0.5)
        }
        let probe = try PdfProbe(bytes)
        let content = try probe.contentOfPage(0)
        #expect(content.contains("/GS1 gs") && content.contains("/GS2 gs"))
        #expect(PrintTestText.matches("gs\n", content).count == 3)
        let page = try probe.body(probe.pageNumbers()[0])
        #expect(page.contains("/GS1 << /CA 0.5 /ca 0.5 >>") && page.contains("/GS2 << /CA 0.25 /ca 0.25 >>"))
    }

    @Test("a polyline skips points that are too close but keeps the last one")
    func polyline() throws {
        let xs = [0.0, 0.01, 0.02, 5.0, 5.01]
        let ys = [0.0, 0.0, 0.0, 0.0, 0.0]
        let bytes = try write { w in
            try w.beginPage(100.0, 50.0).polyline(xs, ys, minStepMm: 0.1)
        }
        let content = try PdfProbe(bytes).contentOfPage(0)
        #expect(content.contains("0 0 m\n5 0 l\n5.01 0 l\nS\n"), "\(content)")
    }

    @Test("a page that is never ended is ended on close and objects are never skipped")
    func endedOnClose() throws {
        let bytes = try write { w in
            _ = try w.beginPage(10.0, 10.0)
        }
        let probe = try PdfProbe(bytes)
        #expect(try probe.pageNumbers().count == 1)
        #expect(Set(probe.offsets.keys) == Set(1 ..< probe.offsets.count + 1))
    }

    @Test("sample for visual checks is written to the build directory")
    func sample() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("print-samples", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("writer-sample.pdf")
        let file = try PdfFileOutput(url: url)
        let writer = try PdfWriter(file, info: PdfInfo(title: "Sample"))
        let page = try writer.beginPage(210.0, 297.0)
        // A 100 mm square and diagonals, a ruler from 0 to 100 mm with ticks every 10 mm.
        page.strokeColor(.black)
        page.lineWidth(0.2)
        page.rect(55.0, 20.0, 100.0, 100.0)
        page.stroke()
        for i in 0 ... 10 {
            page.moveTo(55.0 + Double(i) * 10.0, 130.0)
            page.lineTo(55.0 + Double(i) * 10.0, i % 5 == 0 ? 138.0 : 134.0)
            page.stroke()
        }
        page.text("\u{C9}chelle de contr\u{F4}le \u{2014} 100 mm", 105.0, 150.0, .bold, 12.0, .black, .center)
        page.fillColor(PdfColor(200, 30, 30))
        page.circle(105.0, 70.0, 10.0)
        page.fill()
        try writer.close()
        try file.close()
        let size = try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int ?? 0
        #expect(size > 500)
    }
}
