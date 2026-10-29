// Port of core/src/test/kotlin/fr/toolmap/core/elevation/TerrariumTest.kt. The Kotlin test writes its PNG files with
// javax.imageio; here a small writer (stored DEFLATE blocks, the five scanline filters in turn) builds them byte by
// byte, so that the decoder reads exactly the values written.

import Foundation
import Testing
@testable import ToolMapCore

/// A minimal PNG writer for the tests: 8-bit images of colour type 0 (grey), 2 (RGB), 3 (palette), 4 (grey + alpha)
/// or 6 (RGBA). Row `y` uses filter `y % 5`, so every filter of the decoder is exercised.
private enum TestPng {
    static func encode(width: Int, height: Int, colorType: UInt8, pixels: [UInt8], palette: [UInt8]? = nil) -> Data {
        let bpp: Int
        switch colorType {
        case 2: bpp = 3
        case 4: bpp = 2
        case 6: bpp = 4
        default: bpp = 1
        }
        let rowBytes = width * bpp
        var raw: [UInt8] = []
        raw.reserveCapacity((rowBytes + 1) * height)
        for y in 0 ..< height {
            let filter = y % 5
            raw.append(UInt8(filter))
            for i in 0 ..< rowBytes {
                let x = Int(pixels[y * rowBytes + i])
                let a = i >= bpp ? Int(pixels[y * rowBytes + i - bpp]) : 0
                let b = y > 0 ? Int(pixels[(y - 1) * rowBytes + i]) : 0
                let c = (y > 0 && i >= bpp) ? Int(pixels[(y - 1) * rowBytes + i - bpp]) : 0
                let predicted: Int
                switch filter {
                case 1: predicted = a
                case 2: predicted = b
                case 3: predicted = (a + b) / 2
                case 4: predicted = paeth(a, b, c)
                default: predicted = 0
                }
                raw.append(UInt8(truncatingIfNeeded: x - predicted))
            }
        }
        // zlib stream: header, stored DEFLATE blocks of at most 65 535 bytes, Adler-32.
        var zlib: [UInt8] = [0x78, 0x01]
        var offset = 0
        repeat {
            let n = Swift.min(65535, raw.count - offset)
            let last = offset + n == raw.count
            zlib.append(last ? 1 : 0)
            zlib.append(UInt8(n & 0xFF))
            zlib.append(UInt8((n >> 8) & 0xFF))
            zlib.append(UInt8(~n & 0xFF))
            zlib.append(UInt8((~n >> 8) & 0xFF))
            zlib.append(contentsOf: raw[offset ..< offset + n])
            offset += n
        } while offset < raw.count
        zlib.append(contentsOf: bigEndian(adler32(raw)))

        var png: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        var header = bigEndian(UInt32(width)) + bigEndian(UInt32(height))
        header.append(contentsOf: [8, colorType, 0, 0, 0])
        png.append(contentsOf: chunk("IHDR", header))
        if let palette {
            png.append(contentsOf: chunk("PLTE", palette))
        }
        png.append(contentsOf: chunk("IDAT", zlib))
        png.append(contentsOf: chunk("IEND", []))
        return Data(png)
    }

    private static func paeth(_ a: Int, _ b: Int, _ c: Int) -> Int {
        let p = a + b - c
        let pa = abs(p - a)
        let pb = abs(p - b)
        let pc = abs(p - c)
        if pa <= pb, pa <= pc { return a }
        if pb <= pc { return b }
        return c
    }

    private static func chunk(_ type: String, _ data: [UInt8]) -> [UInt8] {
        let typeBytes = Array(type.utf8)
        var out = bigEndian(UInt32(data.count))
        out.append(contentsOf: typeBytes)
        out.append(contentsOf: data)
        out.append(contentsOf: bigEndian(crc32(typeBytes + data)))
        return out
    }

    private static func bigEndian(_ value: UInt32) -> [UInt8] {
        [UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]
    }

    private static func adler32(_ data: [UInt8]) -> UInt32 {
        var a: UInt32 = 1
        var b: UInt32 = 0
        for byte in data {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        return (b << 16) | a
    }

    private static func crc32(_ data: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0 ..< 8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

struct TerrariumTests {
    /// A tile of `size` px whose elevation is `100 + 2 * x + 0.5 * y` metres, Terrarium-encoded, as RGB (colour type 2)
    /// or RGBA (colour type 6).
    private func rampTile(colorType: UInt8 = 2, size: Int = 256) -> Data {
        let channels = colorType == 6 ? 4 : 3
        var pixels = [UInt8](repeating: 0, count: size * size * channels)
        for y in 0 ..< size {
            for x in 0 ..< size {
                let v = kotlinRound((100.0 + 2 * Double(x) + 0.5 * Double(y) + 32768.0) * 256)
                let at = (y * size + x) * channels
                pixels[at] = UInt8((v >> 16) & 0xFF)
                pixels[at + 1] = UInt8((v >> 8) & 0xFF)
                pixels[at + 2] = UInt8(v & 0xFF)
                if channels == 4 { pixels[at + 3] = 0xFF }
            }
        }
        return TestPng.encode(width: size, height: size, colorType: colorType, pixels: pixels)
    }

    @Test("Terrarium formula")
    func formula() {
        #expect(TerrariumTile.decodeElevation(128, 0, 0) == 0.0)
        #expect(TerrariumTile.decodeElevation(0, 0, 0) == -32768.0)
        #expect(abs(TerrariumTile.decodeElevation(131, 232, 128) - 1000.5) <= 1e-9)
        #expect(abs(TerrariumTile.decodeElevation(127, 246, 0) - -10.0) <= 1e-9)
    }

    @Test("RGB and RGBA PNG tiles decode to the same elevations")
    func rgbAndRgba() throws {
        for colorType: UInt8 in [2, 6] {
            let tile = try TerrariumTile.fromPng(rampTile(colorType: colorType))
            #expect(tile.size == 256)
            #expect(abs(tile.pixel(0, 0) - 100.0) <= 0.01)
            #expect(abs(tile.pixel(255, 255) - (100.0 + 2 * 255 + 0.5 * 255)) <= 0.01)
            #expect(abs(tile.pixel(10, 20) - (100.0 + 2 * 10 + 0.5 * 20)) <= 0.01)
        }
    }

    @Test("palette and grey images are read")
    func paletteAndGrey() throws {
        var indices = [UInt8](repeating: 0, count: 64)
        indices[4 * 8 + 3] = 1
        let indexed = TestPng.encode(width: 8, height: 8, colorType: 3, pixels: indices, palette: [0, 0, 0, 0x12, 0x34, 0x56])
        let image = try PngDecoder.decode(indexed)
        #expect(image.channels == 3)
        #expect(image.width == 8)
        #expect(image.sample(3, 4, 0) == 0x12)
        #expect(image.sample(3, 4, 1) == 0x34)
        #expect(image.sample(3, 4, 2) == 0x56)
        var grey = [UInt8](repeating: 0, count: 16)
        grey[2 * 4 + 1] = 77
        let g = try PngDecoder.decode(TestPng.encode(width: 4, height: 4, colorType: 0, pixels: grey))
        #expect(g.channels == 1)
        #expect(g.sample(1, 2, 0) == 77)
    }

    @Test("grey with alpha and every filter keep the exact values")
    func greyAlphaFilters() throws {
        var pixels = [UInt8](repeating: 0, count: 9 * 7 * 2)
        for i in pixels.indices {
            pixels[i] = UInt8((i * 37 + 11) % 256)
        }
        let image = try PngDecoder.decode(TestPng.encode(width: 9, height: 7, colorType: 4, pixels: pixels))
        #expect(image.channels == 2)
        #expect(image.data == pixels)
    }

    @Test("bilinear sampling between pixel centres")
    func bilinear() throws {
        let tile = try TerrariumTile.fromPng(rampTile())
        // Centre of pixel (10, 20) is at fraction ((10 + .5) / 256, (20 + .5) / 256).
        #expect(abs(tile.elevationAt(10.5 / 256, 20.5 / 256) - 130.0) <= 0.01)
        // Halfway between pixels 10 and 11 along x: one more metre.
        #expect(abs(tile.elevationAt(11.0 / 256, 20.5 / 256) - 131.0) <= 0.01)
        // Beyond the edge the border pixel is repeated.
        #expect(abs(tile.elevationAt(0.0, 0.0) - tile.pixel(0, 0)) <= 0.6)
    }

    @Test("position to tile fraction agrees with the tile maths")
    func fractionIn() throws {
        let zoom = 12
        let tile = try TileMath.tileAt(6.865, 45.8326, zoom)
        let fraction = try TerrariumTile.fractionIn(6.865, 45.8326, zoom, tile.x, tile.y)
        #expect((0.0 ... 1.0).contains(fraction.0) && (0.0 ... 1.0).contains(fraction.1), "\(fraction)")
        // The tile's own north-west corner is fraction (0, 0).
        let b = try TileMath.boundsLonLat(tile.x, tile.y, zoom)
        let corner = try TerrariumTile.fractionIn(b.minX, b.maxY, zoom, tile.x, tile.y)
        #expect(abs(corner.0) <= 1e-9)
        #expect(abs(corner.1) <= 1e-9)
    }

    @Test("bad images are refused")
    func badImages() {
        #expect(throws: PngError.self) { try PngDecoder.decode(Data()) }
        #expect(throws: PngError.self) { try PngDecoder.decode(Data("GIF89a".utf8)) }
        let good = rampTile()
        let half = good.prefix(good.count / 2)
        #expect(throws: PngError.self) { try PngDecoder.decode(Data(half)) }
        let tall = TestPng.encode(width: 4, height: 8, colorType: 2, pixels: [UInt8](repeating: 0, count: 4 * 8 * 3))
        #expect(throws: PngError.self) { try TerrariumTile.fromPng(tall) }
    }
}
