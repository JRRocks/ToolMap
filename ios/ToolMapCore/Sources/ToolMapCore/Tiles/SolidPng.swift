// Port of core/src/main/kotlin/fr/toolmap/core/tiles/SolidPng.kt.

import Compression
import Foundation

/// Minimal PNG encoder for flat-colour tiles (the stored-coverage overlay): an RGBA square filled with one colour and
/// an optional one-colour frame. Written by hand like the Kotlin one (signature, chunks, CRC-32); the pixels are
/// compressed by the zlib encoder of the Compression framework and wrapped in the zlib header and Adler-32 that PNG
/// asks for.
public enum SolidPng {
    private static let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    private static let bytesPerPixel = 4
    private static let maxSize = 2048
    /// Largest block of a "stored" (uncompressed) deflate stream.
    private static let maxStoredBlock = 65_535

    private static let crcTable: [UInt32] = (0 ..< 256).map { index -> UInt32 in
        var c = UInt32(index)
        for _ in 0 ..< 8 {
            c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1
        }
        return c
    }

    /// Colours are `0xAARRGGBB`. `frame` is nil for no frame; `frameWidth` is in pixels. Throws `ToolMapError` for a
    /// size outside 1...2048 or a negative frame width.
    public static func create(_ size: Int, fill: UInt32, frame: UInt32? = nil, frameWidth: Int = 1) throws -> Data {
        try require(size >= 1 && size <= maxSize, "size out of range: \(size)")
        try require(frameWidth >= 0, "negative frame width")
        let rowBytes = size * bytesPerPixel
        var raw = [UInt8](repeating: 0, count: (rowBytes + 1) * size)
        for y in 0 ..< size {
            let rowStart = y * (rowBytes + 1)
            // Filter type 0 (none) is already the zero byte at rowStart.
            for x in 0 ..< size {
                var argb = fill
                if let frameColor = frame,
                   x < frameWidth || y < frameWidth || x >= size - frameWidth || y >= size - frameWidth
                {
                    argb = frameColor
                }
                let offset = rowStart + 1 + x * bytesPerPixel
                raw[offset] = UInt8(truncatingIfNeeded: argb >> 16)
                raw[offset + 1] = UInt8(truncatingIfNeeded: argb >> 8)
                raw[offset + 2] = UInt8(truncatingIfNeeded: argb)
                raw[offset + 3] = UInt8(truncatingIfNeeded: argb >> 24)
            }
        }
        var out = Data(signature)
        appendChunk(&out, "IHDR", header(size))
        appendChunk(&out, "IDAT", zlib(raw))
        appendChunk(&out, "IEND", [])
        return out
    }

    private static func header(_ size: Int) -> [UInt8] {
        var data = bigEndian(UInt32(size))
        data += bigEndian(UInt32(size))
        data.append(8) // bit depth
        data.append(6) // colour type: RGBA
        data += [0, 0, 0] // compression, filter and interlace methods
        return data
    }

    /// The zlib stream (RFC 1950) PNG stores: header of the best compression level, deflate data, Adler-32.
    private static func zlib(_ raw: [UInt8]) -> [UInt8] {
        var out: [UInt8] = [0x78, 0xDA]
        out += deflate(raw)
        out += bigEndian(adler32(raw))
        return out
    }

    /// Raw deflate data (RFC 1951) by the Compression framework; "stored" blocks if it gives nothing.
    private static func deflate(_ raw: [UInt8]) -> [UInt8] {
        let capacity = raw.count + raw.count / 8 + 1024
        var buffer = [UInt8](repeating: 0, count: capacity)
        let written = raw.withUnsafeBufferPointer { source -> Int in
            buffer.withUnsafeMutableBufferPointer { destination -> Int in
                guard let input = source.baseAddress, let output = destination.baseAddress else { return 0 }
                return compression_encode_buffer(output, destination.count, input, source.count, nil, COMPRESSION_ZLIB)
            }
        }
        if written > 0 {
            return Array(buffer[0 ..< written])
        }
        return storedDeflate(raw)
    }

    private static func storedDeflate(_ raw: [UInt8]) -> [UInt8] {
        var out: [UInt8] = []
        var offset = 0
        repeat {
            let length = Swift.min(maxStoredBlock, raw.count - offset)
            let last = offset + length >= raw.count
            out.append(last ? 1 : 0)
            out.append(UInt8(truncatingIfNeeded: length))
            out.append(UInt8(truncatingIfNeeded: length >> 8))
            let complement = ~length & 0xFFFF
            out.append(UInt8(truncatingIfNeeded: complement))
            out.append(UInt8(truncatingIfNeeded: complement >> 8))
            out += raw[offset ..< offset + length]
            offset += length
        } while offset < raw.count
        return out
    }

    private static func adler32(_ bytes: [UInt8]) -> UInt32 {
        var a: UInt32 = 1
        var b: UInt32 = 0
        for byte in bytes {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        return (b << 16) | a
    }

    private static func crc32(_ type: [UInt8], _ data: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for byte in type + data {
            c = crcTable[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8)
        }
        return c ^ 0xFFFF_FFFF
    }

    private static func appendChunk(_ out: inout Data, _ type: String, _ data: [UInt8]) {
        let typeBytes = Array(type.utf8)
        out.append(contentsOf: bigEndian(UInt32(data.count)))
        out.append(contentsOf: typeBytes)
        out.append(contentsOf: data)
        out.append(contentsOf: bigEndian(crc32(typeBytes, data)))
    }

    private static func bigEndian(_ value: UInt32) -> [UInt8] {
        [
            UInt8(truncatingIfNeeded: value >> 24),
            UInt8(truncatingIfNeeded: value >> 16),
            UInt8(truncatingIfNeeded: value >> 8),
            UInt8(truncatingIfNeeded: value),
        ]
    }
}
