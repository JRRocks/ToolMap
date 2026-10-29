// A small ZIP reader for KMZ files (TrackImporter.kt reads them with java.util.zip.ZipInputStream): the entries are
// listed from the central directory, "stored" entries are copied and "deflate" ones are inflated with the
// Compression framework (`COMPRESSION_ZLIB` is raw DEFLATE, RFC 1951). Encrypted entries and other methods are refused.

import Compression
import Foundation

struct ZipReader {
    /// The archive is damaged, or holds what this reader does not read.
    struct ReadError: Error, Equatable, Sendable, CustomStringConvertible {
        let message: String

        var description: String { message }
    }

    struct Entry: Equatable, Sendable {
        let name: String
        let method: Int
        let flags: Int
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int

        /// `java.util.zip.ZipEntry.isDirectory`: the name ends with a slash.
        var isDirectory: Bool { name.hasSuffix("/") }
    }

    private let bytes: [UInt8]
    let entries: [Entry]

    init(_ data: Data) throws {
        let bytes = [UInt8](data)
        self.bytes = bytes
        entries = try ZipReader.centralDirectory(bytes)
    }

    // MARK: - Little-endian fields

    private static func u16(_ b: [UInt8], _ at: Int) throws -> Int {
        guard at >= 0, at + 2 <= b.count else { throw ReadError(message: "Truncated ZIP archive") }
        let low = Int(b[at])
        let high = Int(b[at + 1])
        return low | (high << 8)
    }

    private static func u32(_ b: [UInt8], _ at: Int) throws -> Int {
        guard at >= 0, at + 4 <= b.count else { throw ReadError(message: "Truncated ZIP archive") }
        let b0 = Int(b[at])
        let b1 = Int(b[at + 1])
        let b2 = Int(b[at + 2])
        let b3 = Int(b[at + 3])
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
    }

    private static func u64(_ b: [UInt8], _ at: Int) throws -> Int {
        let low = try u32(b, at)
        let high = try u32(b, at + 4)
        guard high < 0x8000_0000 else { throw ReadError(message: "ZIP field out of range") }
        return (high << 32) | low
    }

    // MARK: - Central directory

    private static let zip64LocatorSignature = 0x0706_4B50
    private static let zip64EndSignature = 0x0606_4B50
    private static let centralSignature = 0x0201_4B50
    private static let localSignature = 0x0403_4B50

    private static func centralDirectory(_ b: [UInt8]) throws -> [Entry] {
        // The end record is at least 22 bytes, followed by a comment of at most 65 535 bytes.
        guard b.count >= 22 else { throw ReadError(message: "Not a ZIP archive: no end of central directory") }
        var end = -1
        var i = b.count - 22
        let lowest = Swift.max(0, b.count - 22 - 65535)
        while i >= lowest {
            if b[i] == 0x50, b[i + 1] == 0x4B, b[i + 2] == 0x05, b[i + 3] == 0x06 {
                end = i
                break
            }
            i -= 1
        }
        guard end >= 0 else { throw ReadError(message: "Not a ZIP archive: no end of central directory") }
        var count = try u16(b, end + 10)
        var offset = try u32(b, end + 16)
        if count == 0xFFFF || offset == 0xFFFF_FFFF {
            // ZIP64: the locator sits just before the end record and points at the ZIP64 end record.
            let locator = end - 20
            if locator >= 0, try u32(b, locator) == zip64LocatorSignature {
                let record = try u64(b, locator + 8)
                guard try u32(b, record) == zip64EndSignature else {
                    throw ReadError(message: "Damaged ZIP64 archive")
                }
                count = try u64(b, record + 32)
                offset = try u64(b, record + 48)
            }
        }
        var entries: [Entry] = []
        var at = offset
        for _ in 0 ..< count {
            guard try u32(b, at) == centralSignature else { throw ReadError(message: "Damaged ZIP central directory") }
            let flags = try u16(b, at + 8)
            let method = try u16(b, at + 10)
            var compressed = try u32(b, at + 20)
            var uncompressed = try u32(b, at + 24)
            let nameLength = try u16(b, at + 28)
            let extraLength = try u16(b, at + 30)
            let commentLength = try u16(b, at + 32)
            var local = try u32(b, at + 42)
            let nameStart = at + 46
            guard nameStart + nameLength + extraLength <= b.count else { throw ReadError(message: "Truncated ZIP archive") }
            // ZipInputStream reads the names as UTF-8.
            let name = String(decoding: b[nameStart ..< nameStart + nameLength], as: UTF8.self)
            // ZIP64 sizes and offset, in this order, for the fields that are 0xFFFFFFFF.
            var extra = nameStart + nameLength
            let extraEnd = extra + extraLength
            while extra + 4 <= extraEnd {
                let id = try u16(b, extra)
                let size = try u16(b, extra + 2)
                if id == 0x0001 {
                    var field = extra + 4
                    if uncompressed == 0xFFFF_FFFF, field + 8 <= extra + 4 + size {
                        uncompressed = try u64(b, field)
                        field += 8
                    }
                    if compressed == 0xFFFF_FFFF, field + 8 <= extra + 4 + size {
                        compressed = try u64(b, field)
                        field += 8
                    }
                    if local == 0xFFFF_FFFF, field + 8 <= extra + 4 + size {
                        local = try u64(b, field)
                    }
                }
                extra += 4 + size
            }
            entries.append(
                Entry(
                    name: name,
                    method: method,
                    flags: flags,
                    compressedSize: compressed,
                    uncompressedSize: uncompressed,
                    localHeaderOffset: local
                )
            )
            at = nameStart + nameLength + extraLength + commentLength
        }
        return entries
    }

    // MARK: - Contents

    /// The bytes of `entry`, uncompressed. Throws `ReadError` when it is damaged, encrypted, compressed with another
    /// method than stored or deflate, or larger than `limit` once uncompressed.
    func contents(of entry: Entry, limit: Int) throws -> [UInt8] {
        if entry.flags & 1 != 0 { throw ReadError(message: "Encrypted ZIP entry not supported") }
        let header = entry.localHeaderOffset
        guard try ZipReader.u32(bytes, header) == ZipReader.localSignature else {
            throw ReadError(message: "Damaged ZIP entry \(entry.name)")
        }
        let nameLength = try ZipReader.u16(bytes, header + 26)
        let extraLength = try ZipReader.u16(bytes, header + 28)
        let start = header + 30 + nameLength + extraLength
        guard entry.compressedSize >= 0, start + entry.compressedSize <= bytes.count else {
            throw ReadError(message: "Truncated ZIP entry \(entry.name)")
        }
        let stored = Array(bytes[start ..< start + entry.compressedSize])
        switch entry.method {
        case 0:
            if stored.count > limit { throw ReadError(message: "ZIP entry too large") }
            return stored
        case 8:
            return try ZipReader.inflate(stored, limit: limit)
        default:
            throw ReadError(message: "Invalid compression method \(entry.method) for \(entry.name)")
        }
    }

    /// Raw DEFLATE data inflated by the Compression framework, refused beyond `limit` bytes.
    static func inflate(_ input: [UInt8], limit: Int) throws -> [UInt8] {
        if input.isEmpty { throw ReadError(message: "Unexpected end of ZLIB input stream") }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ReadError(message: "Cannot start inflating")
        }
        defer { compression_stream_destroy(stream) }
        let chunk = 64 * 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        var output: [UInt8] = []
        let finalize = Int32(COMPRESSION_STREAM_FINALIZE.rawValue)
        try input.withUnsafeBufferPointer { source in
            guard let base = source.baseAddress else { throw ReadError(message: "Unexpected end of ZLIB input stream") }
            stream.pointee.src_ptr = base
            stream.pointee.src_size = source.count
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let status = compression_stream_process(stream, finalize)
                let produced = chunk - stream.pointee.dst_size
                if produced > 0 {
                    if output.count + produced > limit { throw ReadError(message: "ZIP entry too large") }
                    output.append(contentsOf: UnsafeBufferPointer(start: buffer, count: produced))
                }
                if status == COMPRESSION_STATUS_END { return }
                if status != COMPRESSION_STATUS_OK { throw ReadError(message: "Invalid deflate data") }
                if produced == 0, stream.pointee.src_size == 0 {
                    throw ReadError(message: "Unexpected end of ZLIB input stream")
                }
            }
        }
        return output
    }
}
