// Port of core/src/main/kotlin/fr/toolmap/core/tiles/TileUrlBuilder.kt.

import Foundation

/// A URL template uses a placeholder we do not know (e.g. `{$serverpart}`) or a missing `{$param:key}`.
public struct TileUrlError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Turns the URL templates of a `TileSource` into real tile URLs.
///
/// Supported placeholders: `{$x} {$y} {$!y} {$z} {$q} {$bbox} {$ibbox} {$param:key}`.
/// When a level lists several servers they are used in turn (round-robin, thread-safe).
public final class TileUrlBuilder: @unchecked Sendable {
    private static let degreeDecimals = 10
    private static let mercatorDecimals = 4

    private let source: TileSource
    private let lock = NSLock()
    private var counter = 0

    public init(_ source: TileSource) {
        self.source = source
    }

    /// URL for tile `z`/`x`/`y` using the next server in rotation, or nil when no level serves `z`.
    /// Throws `TileUrlError` when the template cannot be expanded.
    public func build(_ z: Int, _ x: Int, _ y: Int) throws -> String? {
        guard let level = source.levelFor(z) else { return nil }
        lock.lock()
        let index = counter
        counter &+= 1
        lock.unlock()
        return try build(level, z, x, y, serverIndex: index)
    }

    /// Deterministic variant: `serverIndex` picks the server modulo the number of servers.
    public func build(_ level: LevelDef, _ z: Int, _ x: Int, _ y: Int, serverIndex: Int) throws -> String {
        let count = level.servers.count
        let template = level.servers[((serverIndex % count) + count) % count]
        return try expand(template, level, z, x, y)
    }

    /// Replaces every `{$name}` (a name of at least one character, without `}`), left to right, like the Kotlin
    /// regular expression `\{\$([^\}]+)\}`.
    private func expand(_ template: String, _ level: LevelDef, _ z: Int, _ x: Int, _ y: Int) throws -> String {
        let scalars = Array(template.unicodeScalars)
        var out = String.UnicodeScalarView()
        var i = 0
        while i < scalars.count {
            if scalars[i] == "{", i + 1 < scalars.count, scalars[i + 1] == "$",
               let close = scalars[(i + 2)...].firstIndex(of: "}"), close > i + 2
            {
                let name = String(String.UnicodeScalarView(scalars[(i + 2) ..< close]))
                let replacement = try value(of: name, level, z, x, y)
                out.append(contentsOf: replacement.unicodeScalars)
                i = close + 1
            } else {
                out.append(scalars[i])
                i += 1
            }
        }
        return String(out)
    }

    private func value(of name: String, _ level: LevelDef, _ z: Int, _ x: Int, _ y: Int) throws -> String {
        switch name {
        case "x": return "\(x)"
        case "y": return "\(y)"
        case "!y":
            let row = try TileMath.tmsY(y, z)
            return "\(row)"
        case "z": return "\(z)"
        case "q": return try TileMath.quadKey(x, y, z)
        case "bbox": return try bbox(level, z, x, y, integer: false)
        case "ibbox": return try bbox(level, z, x, y, integer: true)
        default:
            if name.hasPrefix("param:") {
                let key = String(name.dropFirst("param:".count))
                guard let value = source.params[key] else {
                    throw TileUrlError("Missing <param name=\"\(key)\"> for source \(source.id)")
                }
                return value
            }
            throw TileUrlError("Unknown placeholder {$\(name)} in source \(source.id)")
        }
    }

    private func bbox(_ level: LevelDef, _ z: Int, _ x: Int, _ y: Int, integer: Bool) throws -> String {
        let bounds: Bounds
        let decimals: Int
        if level.bboxCrs == LevelDef.crsWebMercator {
            bounds = try TileMath.boundsMercator(x, y, z)
            decimals = TileUrlBuilder.mercatorDecimals
        } else {
            bounds = try TileMath.boundsLonLat(x, y, z)
            decimals = TileUrlBuilder.degreeDecimals
        }
        return [bounds.minX, bounds.minY, bounds.maxX, bounds.maxY]
            .map { DecimalText.halfUp($0, decimals: integer ? 0 : decimals) }
            .joined(separator: ",")
    }
}
