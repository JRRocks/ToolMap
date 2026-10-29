// Port of core/src/test/kotlin/fr/toolmap/core/tiles/SolidPngTest.kt. The JVM test decodes with ImageIO; here ImageIO
// checks that the image is a readable PNG of the right size, and the pixels are read from the inflated IDAT (a
// CoreGraphics decode may premultiply the alpha and change the colour of a translucent pixel).

import Compression
import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import ToolMapCore

struct SolidPngTests {
    /// The RGBA rows of a PNG written by SolidPng (one filter byte, 0, before each row).
    private struct Pixels {
        let width: Int
        let height: Int
        let raw: [UInt8]

        /// The pixel at (x, y) as `0xAARRGGBB`.
        func argb(_ x: Int, _ y: Int) -> UInt32 {
            let offset = y * (width * 4 + 1) + 1 + x * 4
            let alpha = UInt32(raw[offset + 3]) << 24
            let red = UInt32(raw[offset]) << 16
            let green = UInt32(raw[offset + 1]) << 8
            let blue = UInt32(raw[offset + 2])
            return alpha | red | green | blue
        }
    }

    private func readUInt32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) << 24 | UInt32(bytes[offset + 1]) << 16 | UInt32(bytes[offset + 2]) << 8 |
            UInt32(bytes[offset + 3])
    }

    private func crc32(_ bytes: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in bytes {
            c ^= UInt32(byte)
            for _ in 0 ..< 8 {
                c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1
            }
        }
        return c ^ 0xFFFF_FFFF
    }

    private func adler32(_ bytes: [UInt8]) -> UInt32 {
        var a: UInt32 = 1
        var b: UInt32 = 0
        for byte in bytes {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        return (b << 16) | a
    }

    /// Checks the signature, the chunks and their CRCs, the zlib wrapping, and inflates the pixels.
    private func pixels(_ png: Data) throws -> Pixels {
        let bytes = [UInt8](png)
        try #require(bytes.count > 8)
        #expect(Array(bytes[0 ..< 8]) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        var offset = 8
        var width = 0
        var height = 0
        var idat: [UInt8] = []
        var ended = false
        while offset + 12 <= bytes.count {
            let length = Int(readUInt32(bytes, offset))
            try #require(offset + 12 + length <= bytes.count)
            let typeBytes = Array(bytes[(offset + 4) ..< (offset + 8)])
            let type = String(decoding: typeBytes, as: UTF8.self)
            let data = Array(bytes[(offset + 8) ..< (offset + 8 + length)])
            let crc = readUInt32(bytes, offset + 8 + length)
            #expect(crc == crc32(typeBytes + data), "CRC of \(type)")
            switch type {
            case "IHDR":
                width = Int(readUInt32(data, 0))
                height = Int(readUInt32(data, 4))
                #expect(data[8] == 8)
                #expect(data[9] == 6)
            case "IDAT":
                idat += data
            case "IEND":
                ended = true
            default:
                break
            }
            offset += 12 + length
        }
        #expect(ended)
        // zlib stream (RFC 1950): two header bytes, raw deflate data, Adler-32 of the pixels.
        try #require(idat.count > 6)
        #expect((Int(idat[0]) << 8 | Int(idat[1])) % 31 == 0)
        let deflated = Array(idat[2 ..< (idat.count - 4)])
        let expected = (width * 4 + 1) * height
        var raw = [UInt8](repeating: 0, count: expected)
        let written = deflated.withUnsafeBufferPointer { source -> Int in
            raw.withUnsafeMutableBufferPointer { destination -> Int in
                guard let input = source.baseAddress, let output = destination.baseAddress else { return 0 }
                return compression_decode_buffer(output, destination.count, input, source.count, nil, COMPRESSION_ZLIB)
            }
        }
        #expect(written == expected)
        #expect(readUInt32(idat, idat.count - 4) == adler32(raw))
        return Pixels(width: width, height: height, raw: raw)
    }

    /// The size ImageIO reads, or nil when it cannot read the image.
    private func decodedSize(_ png: Data) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithData(png as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        return (image.width, image.height)
    }

    @Test("a flat tile decodes to the requested size and colour with alpha")
    func flatTile() throws {
        let png = try SolidPng.create(256, fill: 0x8000_AA00)
        let size = try #require(decodedSize(png))
        #expect(size.width == 256)
        #expect(size.height == 256)
        let image = try pixels(png)
        #expect(image.width == 256)
        #expect(image.height == 256)
        for (x, y) in [(0, 0), (255, 255), (100, 37)] {
            #expect(image.argb(x, y) == 0x8000_AA00, "pixel \(x),\(y)")
        }
        #expect(png.count < 2_000, "a flat tile must stay tiny, was \(png.count) bytes")
    }

    @Test("the frame is drawn on the edges only")
    func frame() throws {
        let png = try SolidPng.create(64, fill: 0x4000_FF00, frame: 0xFF00_6400, frameWidth: 2)
        #expect(decodedSize(png) != nil)
        let image = try pixels(png)
        #expect(image.argb(0, 30) == 0xFF00_6400)
        #expect(image.argb(1, 30) == 0xFF00_6400)
        #expect(image.argb(63, 63) == 0xFF00_6400)
        #expect(image.argb(30, 62) == 0xFF00_6400)
        #expect(image.argb(2, 30) == 0x4000_FF00)
        #expect(image.argb(30, 30) == 0x4000_FF00)
    }

    @Test("invalid sizes are refused")
    func invalidSizes() {
        #expect(throws: ToolMapError.self) {
            _ = try SolidPng.create(0, fill: 0)
        }
        #expect(throws: ToolMapError.self) {
            _ = try SolidPng.create(5000, fill: 0)
        }
    }
}
