// Port of core/src/test/kotlin/fr/toolmap/core/pdf/PdfProbe.kt, plus the text helpers of the print tests.

import Foundation
@testable import ToolMapCore

/// A small reader of the files `PdfWriter` makes, just enough for tests to look inside them.
struct PdfProbe {
    struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    let bytes: [UInt8]
    /// The whole file as Latin-1 text.
    let text: String
    /// Offsets listed by the cross-reference table, by object number.
    let offsets: [Int: Int]

    init(_ data: Data) throws {
        let all = [UInt8](data)
        bytes = all
        text = PdfProbe.latin1(all[...])
        guard let start = PdfProbe.lastIndex(of: Array("startxref".utf8), in: all) else {
            throw Failure(description: "no startxref")
        }
        let tail = PdfProbe.latin1(all[(start + 9)...]).split(whereSeparator: { $0 == "\n" || $0 == " " })
        guard tail.count == 2, tail[1] == "%%EOF", let startXref = Int(tail[0]) else {
            throw Failure(description: "no startxref")
        }
        guard PdfProbe.startsWith(all, Array("xref\n".utf8), at: startXref) else {
            throw Failure(description: "startxref does not point at the table")
        }
        let lines = PdfProbe.latin1(all[startXref...]).components(separatedBy: "\n")
        guard lines.count > 1, let count = Int(lines[1].substringAfter(" ", missing: lines[1])) else {
            throw Failure(description: "no object count")
        }
        var map: [Int: Int] = [:]
        for number in 1 ..< count {
            let line = lines[2 + number]
            guard let offset = Int(line.prefix(10)) else { throw Failure(description: "bad xref line \(line)") }
            map[number] = offset
        }
        offsets = map
    }

    func body(_ number: Int) throws -> String {
        guard let at = offsets[number] else { throw Failure(description: "object \(number) is not listed") }
        guard PdfProbe.startsWith(bytes, Array("\(number) 0 obj".utf8), at: at) else {
            throw Failure(description: "object \(number) is not at its offset")
        }
        guard let end = PdfProbe.index(of: Array("endobj".utf8), in: bytes, from: at) else {
            throw Failure(description: "object \(number) has no end")
        }
        return PdfProbe.latin1(bytes[at ..< end])
    }

    /// The decompressed (or raw, for a JPEG) bytes of a stream object.
    func stream(_ number: Int) throws -> [UInt8] {
        guard let at = offsets[number] else { throw Failure(description: "object \(number) is not listed") }
        let header = try body(number)
        guard let lengthText = PrintTestText.matches(#"/Length (\d+)"#, header).first?[1], let length = Int(lengthText) else {
            throw Failure(description: "object \(number) has no length")
        }
        guard let streamAt = PdfProbe.index(of: Array("stream\n".utf8), in: bytes, from: at) else {
            throw Failure(description: "object \(number) is not a stream")
        }
        let start = streamAt + 7
        let raw = Array(bytes[start ..< start + length])
        if header.contains("/FlateDecode") { return try PdfProbe.inflate(raw) }
        return raw
    }

    func content(_ number: Int) throws -> String {
        try PdfProbe.latin1(stream(number)[...])
    }

    func pageNumbers() throws -> [Int] {
        let pages = try body(2)
        guard let kids = pages.range(of: "/Kids") else { return [] }
        return PrintTestText.matches(#"(\d+) 0 R"#, String(pages[kids.upperBound...])).compactMap { Int($0[1]) }
    }

    func contentOfPage(_ page: Int) throws -> String {
        let pageBody = try body(pageNumbers()[page])
        guard let contents = PrintTestText.matches(#"/Contents (\d+) 0 R"#, pageBody).first, let number = Int(contents[1]) else {
            throw Failure(description: "page \(page) has no content")
        }
        return try content(number)
    }

    func mediaBox(_ page: Int) throws -> [Double] {
        let pageBody = try body(pageNumbers()[page])
        guard let box = PrintTestText.matches(#"/MediaBox \[([^\]]*)\]"#, pageBody).first else {
            throw Failure(description: "page \(page) has no media box")
        }
        return box[1].split(separator: " ").compactMap { Double(String($0)) }
    }

    /// Flate data as `PdfWriter` writes it: a zlib header, raw deflate, an Adler-32 checksum.
    static func inflate(_ data: [UInt8]) throws -> [UInt8] {
        guard data.count >= 6, data[0] == 0x78 else { throw Failure(description: "not zlib data") }
        let raw = Data(data[2 ..< data.count - 4])
        let out = try (raw as NSData).decompressed(using: .zlib) as Data
        return [UInt8](out)
    }

    static func latin1(_ bytes: ArraySlice<UInt8>) -> String {
        String(String.UnicodeScalarView(bytes.map { Unicode.Scalar($0) }))
    }

    static func startsWith(_ bytes: [UInt8], _ pattern: [UInt8], at: Int) -> Bool {
        guard at >= 0, at + pattern.count <= bytes.count else { return false }
        for i in 0 ..< pattern.count where bytes[at + i] != pattern[i] {
            return false
        }
        return true
    }

    static func index(of pattern: [UInt8], in bytes: [UInt8], from: Int) -> Int? {
        var i = from
        while i + pattern.count <= bytes.count {
            if startsWith(bytes, pattern, at: i) { return i }
            i += 1
        }
        return nil
    }

    static func lastIndex(of pattern: [UInt8], in bytes: [UInt8]) -> Int? {
        var i = bytes.count - pattern.count
        while i >= 0 {
            if startsWith(bytes, pattern, at: i) { return i }
            i -= 1
        }
        return nil
    }
}

/// Regular expressions and texts of the print tests.
enum PrintTestText {
    /// Every match of `pattern` in `text`, each as its groups (group 0 first).
    static func matches(_ pattern: String, _ text: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = text as NSString
        return regex.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { match in
            (0 ..< match.numberOfRanges).map { i in
                let range = match.range(at: i)
                return range.location == NSNotFound ? "" : ns.substring(with: range)
            }
        }
    }

    static func contains(_ pattern: String, _ text: String) -> Bool {
        !matches(pattern, text).isEmpty
    }

    /// How a text is written in a content stream: `<hex>`.
    static func shown(_ text: String) -> String {
        "<" + PdfPage.encode(text).map { String(format: "%02X", UInt32($0)) }.joined() + ">"
    }

    /// Every text of a content stream, one per line, decoded from Windows-1252.
    static func written(_ content: String) -> String {
        matches(#"<([0-9A-F]*)> Tj"#, content).map { match in
            let hex = Array(match[1])
            var bytes: [UInt8] = []
            var i = 0
            while i + 1 < hex.count {
                bytes.append(UInt8(String(hex[i ... i + 1]), radix: 16) ?? 0x3F)
                i += 2
            }
            return windows1252(bytes)
        }.joined(separator: "\n")
    }

    static func windows1252(_ bytes: [UInt8]) -> String {
        String(data: Data(bytes), encoding: .windowsCP1252) ?? ""
    }
}
