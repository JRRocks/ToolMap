import Compression
import CryptoKit
import Foundation

// The ZIP files of the backup (docs/specs/backup-format.md). iOS has no public ZIP API: `ZipWriter` writes "stored"
// entries (no compression) with their CRC-32 and sizes in the local header, which java.util.zip.ZipInputStream needs
// to read a stored entry, so the Android app restores what the iPhone saves; ZIP64 fields above 4 GB.
// `BackupZipReader` reads both stored and deflated entries (the Android app deflates all but the offline maps) from a
// memory-mapped file, entry by entry, without loading the archive.

/// CRC-32 of the ZIP format (polynomial 0xEDB88320).
struct ZipCrc32 {
    private static let table: [UInt32] = (0 ..< 256).map { index -> UInt32 in
        var c = UInt32(index)
        for _ in 0 ..< 8 {
            c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1
        }
        return c
    }

    private var running: UInt32 = 0xFFFF_FFFF

    var value: UInt32 { running ^ 0xFFFF_FFFF }

    mutating func update(_ data: Data) {
        var c = running
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            ZipCrc32.table.withUnsafeBufferPointer { table in
                for byte in raw {
                    c = table[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8)
                }
            }
        }
        running = c
    }
}

/// Little-endian fields of a ZIP header.
struct ZipBytes {
    private(set) var data = Data()

    mutating func u16(_ value: UInt16) {
        data.append(UInt8(value & 0xFF))
        data.append(UInt8(value >> 8))
    }

    mutating func u32(_ value: UInt32) {
        u16(UInt16(value & 0xFFFF))
        u16(UInt16(value >> 16))
    }

    mutating func u64(_ value: UInt64) {
        u32(UInt32(value & 0xFFFF_FFFF))
        u32(UInt32(value >> 32))
    }

    mutating func bytes(_ more: Data) {
        data.append(more)
    }
}

/// Writes a ZIP archive of stored entries, one after the other: `begin` an entry whose size and CRC-32 are known, `write`
/// its bytes, then `finish` the archive (central directory).
final class ZipWriter {
    struct WriteError: Error, CustomStringConvertible {
        let message: String
        var description: String { message }
    }

    private struct Record {
        let name: Data
        let crc: UInt32
        let size: UInt64
        let offset: UInt64
    }

    private static let max32: UInt64 = 0xFFFF_FFFF
    /// Bit 11: the names are UTF-8.
    private static let utf8Flag: UInt16 = 0x0800

    private let handle: FileHandle
    private var offset: UInt64 = 0
    private var records: [Record] = []
    private var expected: UInt64 = 0
    private var written: UInt64 = 0
    private let time: UInt16
    private let date: UInt16

    /// Creates (or empties) the file at `url`.
    init(url: URL, date stamp: Date = Date()) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil),
              let handle = FileHandle(forWritingAtPath: url.path)
        else { throw WriteError(message: "Cannot create \(url.lastPathComponent)") }
        self.handle = handle
        let parts = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day, .hour, .minute, .second], from: stamp)
        // MS-DOS date and time, the only ones every reader knows.
        let year: Int = max((parts.year ?? 1980) - 1980, 0)
        let month: Int = parts.month ?? 1
        let dayOfMonth: Int = parts.day ?? 1
        let hour: Int = parts.hour ?? 0
        let minute: Int = parts.minute ?? 0
        let second: Int = parts.second ?? 0
        let dosDate: Int = (year << 9) | (month << 5) | dayOfMonth
        let dosTime: Int = (hour << 11) | (minute << 5) | (second / 2)
        date = UInt16(truncatingIfNeeded: dosDate)
        time = UInt16(truncatingIfNeeded: dosTime)
    }

    deinit {
        try? handle.close()
    }

    /// Starts a stored entry of `size` bytes whose CRC-32 is `crc`.
    func begin(_ name: String, size: UInt64, crc: UInt32) throws {
        if written != expected { throw WriteError(message: "Entry shorter than announced") }
        let nameData = Data(name.utf8)
        let large = size >= ZipWriter.max32
        var header = ZipBytes()
        header.u32(0x0403_4B50)
        header.u16(large ? 45 : 20)
        header.u16(ZipWriter.utf8Flag)
        header.u16(0) // stored
        header.u16(time)
        header.u16(date)
        header.u32(crc)
        let sizeField = large ? UInt32(0xFFFF_FFFF) : UInt32(size)
        header.u32(sizeField) // compressed size
        header.u32(sizeField) // uncompressed size
        header.u16(UInt16(nameData.count))
        header.u16(large ? 20 : 0)
        header.bytes(nameData)
        if large {
            header.u16(0x0001)
            header.u16(16)
            header.u64(size)
            header.u64(size)
        }
        records.append(Record(name: nameData, crc: crc, size: size, offset: offset))
        try append(header.data)
        expected = size
        written = 0
    }

    /// The next bytes of the current entry.
    func write(_ data: Data) throws {
        written += UInt64(data.count)
        if written > expected { throw WriteError(message: "Entry longer than announced") }
        try append(data)
    }

    /// Writes a whole entry from memory.
    func add(_ name: String, _ data: Data) throws {
        var crc = ZipCrc32()
        crc.update(data)
        try begin(name, size: UInt64(data.count), crc: crc.value)
        try write(data)
    }

    /// Writes the central directory and closes the file.
    func finish() throws {
        if written != expected { throw WriteError(message: "Entry shorter than announced") }
        let start = offset
        for record in records {
            try append(central(record))
        }
        let size = offset - start
        let count = UInt64(records.count)
        var end = ZipBytes()
        let needs64 = count >= 0xFFFF || start >= ZipWriter.max32 || size >= ZipWriter.max32
        if needs64 {
            let record64 = offset
            end.u32(0x0606_4B50)
            end.u64(44)
            end.u16(45)
            end.u16(45)
            end.u32(0)
            end.u32(0)
            end.u64(count)
            end.u64(count)
            end.u64(size)
            end.u64(start)
            end.u32(0x0706_4B50)
            end.u32(0)
            end.u64(record64)
            end.u32(1)
        }
        end.u32(0x0605_4B50)
        end.u16(0)
        end.u16(0)
        end.u16(needs64 ? 0xFFFF : UInt16(count))
        end.u16(needs64 ? 0xFFFF : UInt16(count))
        end.u32(needs64 ? 0xFFFF_FFFF : UInt32(size))
        end.u32(needs64 ? 0xFFFF_FFFF : UInt32(start))
        end.u16(0)
        try append(end.data)
        try handle.synchronize()
        try handle.close()
    }

    private func central(_ record: Record) -> Data {
        let bigSize = record.size >= ZipWriter.max32
        let bigOffset = record.offset >= ZipWriter.max32
        var extra = ZipBytes()
        if bigSize || bigOffset {
            extra.u16(0x0001)
            var length: UInt16 = 0
            if bigSize { length += 16 }
            if bigOffset { length += 8 }
            extra.u16(length)
            if bigSize {
                extra.u64(record.size)
                extra.u64(record.size)
            }
            if bigOffset {
                extra.u64(record.offset)
            }
        }
        let version: UInt16 = (bigSize || bigOffset) ? 45 : 20
        var header = ZipBytes()
        header.u32(0x0201_4B50)
        header.u16(version) // made by
        header.u16(version) // needed
        header.u16(ZipWriter.utf8Flag)
        header.u16(0) // stored
        header.u16(time)
        header.u16(date)
        header.u32(record.crc)
        let sizeField = bigSize ? UInt32(0xFFFF_FFFF) : UInt32(record.size)
        header.u32(sizeField)
        header.u32(sizeField)
        header.u16(UInt16(record.name.count))
        header.u16(UInt16(extra.data.count))
        header.u16(0) // comment
        header.u16(0) // disk
        header.u16(0) // internal attributes
        header.u32(0) // external attributes
        header.u32(bigOffset ? UInt32(0xFFFF_FFFF) : UInt32(record.offset))
        header.bytes(record.name)
        header.bytes(extra.data)
        return header.data
    }

    private func append(_ data: Data) throws {
        try handle.write(contentsOf: data)
        offset += UInt64(data.count)
    }
}

/// Reads a ZIP archive from a file mapped in memory: its entries from the central directory, then each entry streamed
/// to a file or read into memory.
final class BackupZipReader {
    struct ReadError: Error, CustomStringConvertible {
        let message: String
        var description: String { message }
    }

    struct Entry {
        let name: String
        let method: Int
        let flags: Int
        let compressedSize: Int
        let size: Int
        let localOffset: Int

        var isDirectory: Bool { name.hasSuffix("/") }
    }

    private let data: Data
    /// The entries in the order of the archive (by the place of their local header).
    let entries: [Entry]

    init(url: URL) throws {
        let mapped = try Data(contentsOf: url, options: .alwaysMapped)
        let listed = try BackupZipReader.centralDirectory(mapped)
        data = mapped
        entries = listed.sorted { $0.localOffset < $1.localOffset }
    }

    // MARK: Fields

    private static func bytes(_ data: Data, _ at: Int, _ count: Int) throws -> [UInt8] {
        guard at >= 0, count >= 0, at + count <= data.count else { throw ReadError(message: "Truncated ZIP archive") }
        let start = data.startIndex + at
        return [UInt8](data[start ..< start + count])
    }

    private static func u16(_ b: [UInt8], _ at: Int) throws -> Int {
        guard at >= 0, at + 2 <= b.count else { throw ReadError(message: "Truncated ZIP archive") }
        return Int(b[at]) | (Int(b[at + 1]) << 8)
    }

    private static func u32(_ b: [UInt8], _ at: Int) throws -> Int {
        guard at >= 0, at + 4 <= b.count else { throw ReadError(message: "Truncated ZIP archive") }
        let low = Int(b[at]) | (Int(b[at + 1]) << 8)
        let high = Int(b[at + 2]) | (Int(b[at + 3]) << 8)
        return low | (high << 16)
    }

    private static func u64(_ b: [UInt8], _ at: Int) throws -> Int {
        let low = try u32(b, at)
        let high = try u32(b, at + 4)
        guard high < 0x8000_0000 else { throw ReadError(message: "ZIP field out of range") }
        return (high << 32) | low
    }

    // MARK: Central directory

    private static func centralDirectory(_ data: Data) throws -> [Entry] {
        guard data.count >= 22 else { throw ReadError(message: "Not a ZIP archive") }
        // The end record is at least 22 bytes, followed by a comment of at most 65 535 bytes.
        let tailStart = max(0, data.count - 22 - 65535)
        let tail = try bytes(data, tailStart, data.count - tailStart)
        var end = -1
        var i = tail.count - 22
        while i >= 0 {
            if tail[i] == 0x50, tail[i + 1] == 0x4B, tail[i + 2] == 0x05, tail[i + 3] == 0x06 {
                end = i
                break
            }
            i -= 1
        }
        guard end >= 0 else { throw ReadError(message: "Not a ZIP archive") }
        var count = try u16(tail, end + 10)
        var size = try u32(tail, end + 12)
        var offset = try u32(tail, end + 16)
        if count == 0xFFFF || size == 0xFFFF_FFFF || offset == 0xFFFF_FFFF {
            // ZIP64: the locator is just before the end record and points at the ZIP64 end record.
            let locatorAt = tailStart + end - 20
            if locatorAt >= 0 {
                let locator = try bytes(data, locatorAt, 20)
                if try u32(locator, 0) == 0x0706_4B50 {
                    let recordAt = try u64(locator, 8)
                    let record = try bytes(data, recordAt, 56)
                    guard try u32(record, 0) == 0x0606_4B50 else { throw ReadError(message: "Damaged ZIP64 archive") }
                    count = try u64(record, 32)
                    size = try u64(record, 40)
                    offset = try u64(record, 48)
                }
            }
        }
        let directory = try bytes(data, offset, size)
        var entries: [Entry] = []
        var at = 0
        for _ in 0 ..< count {
            guard try u32(directory, at) == 0x0201_4B50 else { throw ReadError(message: "Damaged ZIP central directory") }
            let flags = try u16(directory, at + 8)
            let method = try u16(directory, at + 10)
            var compressed = try u32(directory, at + 20)
            var uncompressed = try u32(directory, at + 24)
            let nameLength = try u16(directory, at + 28)
            let extraLength = try u16(directory, at + 30)
            let commentLength = try u16(directory, at + 32)
            var local = try u32(directory, at + 42)
            let nameStart = at + 46
            guard nameStart + nameLength + extraLength <= directory.count else {
                throw ReadError(message: "Truncated ZIP archive")
            }
            let name = String(decoding: directory[nameStart ..< nameStart + nameLength], as: UTF8.self)
            // ZIP64 sizes and offset, in this order, for the fields that are 0xFFFFFFFF.
            var extra = nameStart + nameLength
            let extraEnd = extra + extraLength
            while extra + 4 <= extraEnd {
                let id = try u16(directory, extra)
                let length = try u16(directory, extra + 2)
                if id == 0x0001 {
                    var field = extra + 4
                    let fieldEnd = extra + 4 + length
                    if uncompressed == 0xFFFF_FFFF, field + 8 <= fieldEnd {
                        uncompressed = try u64(directory, field)
                        field += 8
                    }
                    if compressed == 0xFFFF_FFFF, field + 8 <= fieldEnd {
                        compressed = try u64(directory, field)
                        field += 8
                    }
                    if local == 0xFFFF_FFFF, field + 8 <= fieldEnd {
                        local = try u64(directory, field)
                    }
                }
                extra += 4 + length
            }
            entries.append(
                Entry(
                    name: name,
                    method: method,
                    flags: flags,
                    compressedSize: compressed,
                    size: uncompressed,
                    localOffset: local
                )
            )
            at = nameStart + nameLength + extraLength + commentLength
        }
        return entries
    }

    // MARK: Contents

    /// Where the bytes of `entry` start in the archive.
    private func payloadStart(_ entry: Entry) throws -> Int {
        if entry.flags & 1 != 0 { throw ReadError(message: "Encrypted ZIP entry \(entry.name)") }
        let header = try BackupZipReader.bytes(data, entry.localOffset, 30)
        guard try BackupZipReader.u32(header, 0) == 0x0403_4B50 else {
            throw ReadError(message: "Damaged ZIP entry \(entry.name)")
        }
        let nameLength = try BackupZipReader.u16(header, 26)
        let extraLength = try BackupZipReader.u16(header, 28)
        let start = entry.localOffset + 30 + nameLength + extraLength
        guard entry.compressedSize >= 0, start + entry.compressedSize <= data.count else {
            throw ReadError(message: "Truncated ZIP entry \(entry.name)")
        }
        return start
    }

    /// The bytes of `entry`, refused beyond `limit` bytes once uncompressed.
    func contents(_ entry: Entry, limit: Int) throws -> Data {
        var out = Data()
        try stream(entry, limit: Int64(limit)) { chunk in
            out.append(chunk)
        }
        return out
    }

    /// Hands the bytes of `entry` to `sink` in chunks, uncompressed; throws when they go beyond `limit` bytes.
    func stream(_ entry: Entry, limit: Int64, sink: (Data) throws -> Void) throws {
        let start = try payloadStart(entry)
        switch entry.method {
        case 0:
            if Int64(entry.compressedSize) > limit { throw ReadError(message: "ZIP entry \(entry.name) too large") }
            let chunk = 1 << 20
            var at = start
            let end = start + entry.compressedSize
            while at < end {
                let next = min(at + chunk, end)
                try sink(data.subdata(in: data.startIndex + at ..< data.startIndex + next))
                at = next
            }
        case 8:
            try inflate(start, entry.compressedSize, limit: limit, sink: sink)
        default:
            throw ReadError(message: "Compression method \(entry.method) of \(entry.name) not supported")
        }
    }

    /// Raw DEFLATE (`COMPRESSION_ZLIB` is RFC 1951), inflated chunk by chunk.
    private func inflate(_ start: Int, _ count: Int, limit: Int64, sink: (Data) throws -> Void) throws {
        if count == 0 { throw ReadError(message: "Unexpected end of ZLIB input stream") }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw ReadError(message: "Cannot start inflating")
        }
        defer { compression_stream_destroy(stream) }
        let chunk = 256 * 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        let finalize = Int32(COMPRESSION_STREAM_FINALIZE.rawValue)
        var produced: Int64 = 0
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) throws -> Void in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                throw ReadError(message: "Unexpected end of ZLIB input stream")
            }
            stream.pointee.src_ptr = base + start
            stream.pointee.src_size = count
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let status = compression_stream_process(stream, finalize)
                let made = chunk - stream.pointee.dst_size
                if made > 0 {
                    produced += Int64(made)
                    if produced > limit { throw ReadError(message: "ZIP entry too large") }
                    try sink(Data(bytes: buffer, count: made))
                }
                if status == COMPRESSION_STATUS_END { return }
                if status != COMPRESSION_STATUS_OK { throw ReadError(message: "Invalid deflate data") }
                if made == 0, stream.pointee.src_size == 0 {
                    throw ReadError(message: "Unexpected end of ZLIB input stream")
                }
            }
        }
    }
}

/// SHA-256 in lower-case hexadecimal, as the manifest of a backup writes it.
enum BackupDigest {
    static func hex(_ digest: SHA256.Digest) -> String {
        digest.map { String(format: "%02x", $0) }.joined()
    }
}
