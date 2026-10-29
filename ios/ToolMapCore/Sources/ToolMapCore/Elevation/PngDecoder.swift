// Port of core/src/main/kotlin/fr/toolmap/core/elevation/PngDecoder.kt.

import Compression
import Foundation

/// A decoded 8-bit image: `channels` bytes per pixel, rows top to bottom.
public struct PngImage: Equatable, Sendable {
    public let width: Int
    public let height: Int
    public let channels: Int
    public let data: [UInt8]

    public init(width: Int, height: Int, channels: Int, data: [UInt8]) {
        self.width = width
        self.height = height
        self.channels = channels
        self.data = data
    }

    /// Unsigned value of `channel` of the pixel at (`x`, `y`).
    public func sample(_ x: Int, _ y: Int, _ channel: Int) -> Int {
        Int(data[(y * width + x) * channels + channel])
    }
}

/// A PNG this decoder cannot read (Kotlin `PngException`).
public struct PngError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Small PNG reader for elevation tiles (Terrarium is 8-bit RGB), without ImageIO (which may convert the colours), so
/// that the values of every pixel are exactly those of the file. Supports 8-bit grey, grey+alpha, RGB, RGBA and
/// palette images, all five scanline filters, not interlaced (Adam7).
public enum PngDecoder {
    private static let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    private static let maxPixels = 4096 * 4096
    private static let maxChunk = 64 * 1024 * 1024

    private static let ihdr: [UInt8] = Array("IHDR".utf8)
    private static let plte: [UInt8] = Array("PLTE".utf8)
    private static let idatType: [UInt8] = Array("IDAT".utf8)
    private static let iend: [UInt8] = Array("IEND".utf8)

    public static func decode(_ png: Data) throws -> PngImage {
        let bytes = [UInt8](png)
        if bytes.count < signature.count || Array(bytes[0 ..< signature.count]) != signature {
            throw PngError("Not a PNG")
        }
        var pos = signature.count
        var width = 0
        var height = 0
        var colorType = -1
        var palette: [UInt8]?
        var idat: [UInt8] = []
        var seenHeader = false
        chunks: while pos + 8 <= bytes.count {
            let length = Int(readUInt32(bytes, pos))
            let type = Array(bytes[pos + 4 ..< pos + 8])
            let start = pos + 8
            if length > maxChunk || start + length + 4 > bytes.count {
                throw PngError("Truncated PNG chunk \(latin1(type))")
            }
            if type == ihdr {
                if length < 13 { throw PngError("Bad IHDR") }
                width = Int(Int32(bitPattern: readUInt32(bytes, start)))
                height = Int(Int32(bitPattern: readUInt32(bytes, start + 4)))
                let depth = Int(Int8(bitPattern: bytes[start + 8]))
                colorType = Int(Int8(bitPattern: bytes[start + 9]))
                let interlace = Int(Int8(bitPattern: bytes[start + 12]))
                if depth != 8 { throw PngError("Only 8-bit PNG is supported (depth \(depth))") }
                if interlace != 0 { throw PngError("Interlaced PNG is not supported") }
                if width <= 0 || height <= 0 || width * height > maxPixels { throw PngError("Bad size") }
                seenHeader = true
            } else if type == plte {
                palette = Array(bytes[start ..< start + length])
            } else if type == idatType {
                idat.append(contentsOf: bytes[start ..< start + length])
            } else if type == iend {
                break chunks
            }
            pos = start + length + 4
        }
        if !seenHeader { throw PngError("Missing IHDR") }
        let inputChannels: Int
        switch colorType {
        case 0, 3: inputChannels = 1
        case 2: inputChannels = 3
        case 4: inputChannels = 2
        case 6: inputChannels = 4
        default: throw PngError("Unsupported colour type \(colorType)")
        }
        let inflated = try inflate(idat, (width * inputChannels + 1) * height)
        let raw = try unfilter(inflated, width, height, inputChannels)
        if colorType != 3 { return PngImage(width: width, height: height, channels: inputChannels, data: raw) }
        guard let table = palette else { throw PngError("Palette image without PLTE") }
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        for i in 0 ..< width * height {
            let index = Int(raw[i])
            if index * 3 + 2 >= table.count { throw PngError("Palette index out of range") }
            rgb[i * 3] = table[index * 3]
            rgb[i * 3 + 1] = table[index * 3 + 1]
            rgb[i * 3 + 2] = table[index * 3 + 2]
        }
        return PngImage(width: width, height: height, channels: 3, data: rgb)
    }

    /// The `expected` first bytes of the zlib stream `compressed`. The framework Compression reads raw DEFLATE
    /// (`COMPRESSION_ZLIB`): the 2-byte zlib header is skipped and the final Adler-32 is not checked.
    private static func inflate(_ compressed: [UInt8], _ expected: Int) throws -> [UInt8] {
        if compressed.count <= 2 { throw PngError("PNG pixel data is too short") }
        var out = [UInt8](repeating: 0, count: expected)
        let written = out.withUnsafeMutableBufferPointer { (dst: inout UnsafeMutableBufferPointer<UInt8>) -> Int in
            compressed.withUnsafeBufferPointer { (src: UnsafeBufferPointer<UInt8>) -> Int in
                guard let dstBase = dst.baseAddress, let srcBase = src.baseAddress else { return 0 }
                return compression_decode_buffer(dstBase, expected, srcBase + 2, src.count - 2, nil, COMPRESSION_ZLIB)
            }
        }
        if written <= 0 { throw PngError("Corrupt PNG data") }
        if written < expected { throw PngError("PNG pixel data is too short") }
        return out
    }

    private static func unfilter(_ raw: [UInt8], _ width: Int, _ height: Int, _ bpp: Int) throws -> [UInt8] {
        let rowBytes = width * bpp
        var out = [UInt8](repeating: 0, count: rowBytes * height)
        for y in 0 ..< height {
            let filter = Int(Int8(bitPattern: raw[y * (rowBytes + 1)]))
            let src = y * (rowBytes + 1) + 1
            let dst = y * rowBytes
            for i in 0 ..< rowBytes {
                let x = Int(raw[src + i])
                let a = i >= bpp ? Int(out[dst + i - bpp]) : 0
                let b = y > 0 ? Int(out[dst - rowBytes + i]) : 0
                let c = (y > 0 && i >= bpp) ? Int(out[dst - rowBytes + i - bpp]) : 0
                let value: Int
                switch filter {
                case 0: value = x
                case 1: value = x + a
                case 2: value = x + b
                case 3: value = x + (a + b) / 2
                case 4: value = x + paeth(a, b, c)
                default: throw PngError("Bad PNG filter \(filter)")
                }
                out[dst + i] = UInt8(truncatingIfNeeded: value)
            }
        }
        return out
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

    private static func readUInt32(_ b: [UInt8], _ at: Int) -> UInt32 {
        (UInt32(b[at]) << 24) | (UInt32(b[at + 1]) << 16) | (UInt32(b[at + 2]) << 8) | UInt32(b[at + 3])
    }

    /// The chunk type as ISO-8859-1 text, for the messages.
    private static func latin1(_ bytes: [UInt8]) -> String {
        String(String.UnicodeScalarView(bytes.map { Unicode.Scalar($0) }))
    }
}
