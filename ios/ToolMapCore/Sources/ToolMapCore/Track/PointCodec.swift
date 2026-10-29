// Port of core/src/main/kotlin/fr/toolmap/core/track/PointCodec.kt.

import Foundation

/// Compact binary form of a list of `TrackPoint`s, for the database: a 50 000-point track is about 10 bytes per point
/// instead of 40, and loads as one blob instead of 50 000 rows.
///
/// Coordinates are stored as integers of 1e-9 degree (0.1 mm), elevations in micrometres, times in milliseconds, each
/// as the difference with the previous point (zig-zag varint), so a point costs 7 to 11 bytes. Unmodelled XML fragments
/// (`RawXml`) are kept as UTF-8. Anything finer than those units is rounded; GPS data is not that precise. The bytes
/// are the same as Android's, so a database blob reads the same on both apps.
public enum PointCodec {
    private static let version: UInt8 = 1
    private static let flagEle = 1
    private static let flagTime = 2
    private static let flagExtras = 4
    private static let coordScale = 1e9
    private static let eleScale = 1e6
    private static let maxExtras = 10000

    public static func encode(_ points: [TrackPoint]) -> Data {
        var out: [UInt8] = []
        out.reserveCapacity(points.count * 10 + 8)
        out.append(version)
        writeVarint(&out, Int64(points.count))
        var lat: Int64 = 0
        var lon: Int64 = 0
        var ele: Int64 = 0
        var time: Int64 = 0
        for p in points {
            var flags = 0
            if p.ele != nil { flags |= flagEle }
            if p.timeMillis != nil { flags |= flagTime }
            if !p.extras.isEmpty { flags |= flagExtras }
            out.append(UInt8(truncatingIfNeeded: flags))
            let la = Int64(kotlinRound(p.lat * coordScale))
            let lo = Int64(kotlinRound(p.lon * coordScale))
            writeVarint(&out, zigzag(la &- lat))
            writeVarint(&out, zigzag(lo &- lon))
            lat = la
            lon = lo
            if let elevation = p.ele {
                let e = Int64(kotlinRound(elevation * eleScale))
                writeVarint(&out, zigzag(e &- ele))
                ele = e
            }
            if let millis = p.timeMillis {
                writeVarint(&out, zigzag(millis &- time))
                time = millis
            }
            if !p.extras.isEmpty {
                writeVarint(&out, Int64(p.extras.count))
                for raw in p.extras {
                    let bytes = Array(raw.xml.utf8)
                    writeVarint(&out, Int64(bytes.count))
                    out.append(contentsOf: bytes)
                }
            }
        }
        return Data(out)
    }

    /// Throws `ToolMapError.invalidArgument` when `data` is not something `encode` wrote (a damaged database).
    public static func decode(_ data: Data) throws -> [TrackPoint] {
        var input = Reader([UInt8](data))
        let versionByte = try input.byte()
        try require(versionByte == Int(version), "Unknown point format version")
        let rawCount = try input.varint()
        let count = kotlinLongToInt(rawCount)
        try require(count >= 0 && count <= data.count, "Corrupt point count")
        var points: [TrackPoint] = []
        points.reserveCapacity(count)
        var lat: Int64 = 0
        var lon: Int64 = 0
        var ele: Int64 = 0
        var time: Int64 = 0
        for _ in 0 ..< count {
            let flags = try input.byte()
            let dLat = try input.varint()
            lat &+= unzigzag(dLat)
            let dLon = try input.varint()
            lon &+= unzigzag(dLon)
            var elevation: Double?
            var millis: Int64?
            if flags & flagEle != 0 {
                let dEle = try input.varint()
                ele &+= unzigzag(dEle)
                elevation = Double(ele) / eleScale
            }
            if flags & flagTime != 0 {
                let dTime = try input.varint()
                time &+= unzigzag(dTime)
                millis = time
            }
            var extras: [RawXml] = []
            if flags & flagExtras != 0 {
                let rawExtras = try input.varint()
                let n = kotlinLongToInt(rawExtras)
                try require(n >= 0 && n <= maxExtras, "Corrupt extras count")
                for _ in 0 ..< n {
                    let rawLength = try input.varint()
                    let length = kotlinLongToInt(rawLength)
                    let bytes = try input.bytes(length)
                    extras.append(RawXml(String(decoding: bytes, as: UTF8.self)))
                }
            }
            points.append(
                TrackPoint(
                    lat: Double(lat) / coordScale,
                    lon: Double(lon) / coordScale,
                    ele: elevation,
                    timeMillis: millis,
                    extras: extras
                )
            )
        }
        return points
    }

    /// Kotlin `Long.toInt()`: the low 32 bits, as a signed number.
    private static func kotlinLongToInt(_ value: Int64) -> Int {
        Int(Int32(truncatingIfNeeded: value))
    }

    private static func zigzag(_ n: Int64) -> Int64 { (n << 1) ^ (n >> 63) }

    private static func unzigzag(_ n: Int64) -> Int64 {
        Int64(bitPattern: UInt64(bitPattern: n) >> 1) ^ (0 &- (n & 1))
    }

    private static func writeVarint(_ out: inout [UInt8], _ value: Int64) {
        var v = UInt64(bitPattern: value)
        while v & ~UInt64(0x7F) != 0 {
            out.append(UInt8(truncatingIfNeeded: (v & 0x7F) | 0x80))
            v >>= 7
        }
        out.append(UInt8(truncatingIfNeeded: v))
    }

    private struct Reader {
        private let data: [UInt8]
        private var pos = 0

        init(_ data: [UInt8]) {
            self.data = data
        }

        mutating func byte() throws -> Int {
            try require(pos < data.count, "Truncated point data")
            let b = Int(data[pos])
            pos += 1
            return b
        }

        mutating func bytes(_ n: Int) throws -> [UInt8] {
            try require(n >= 0 && pos + n <= data.count, "Truncated point data")
            let slice = Array(data[pos ..< pos + n])
            pos += n
            return slice
        }

        mutating func varint() throws -> Int64 {
            var shift: Int64 = 0
            var result: Int64 = 0
            while true {
                let b = try byte()
                result |= Int64(b & 0x7F) << shift
                if b & 0x80 == 0 { return result }
                shift += 7
                try require(shift < 64, "Corrupt varint")
            }
        }
    }
}
