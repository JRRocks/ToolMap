// Port of core/src/main/kotlin/fr/toolmap/core/source/ZoomSet.kt.

/// An immutable, sorted set of integer zoom levels.
///
/// Parsed from the AQX syntaxes `"1-17"`, `"1,3,5"`, `"1-3,5,7-9"` (also the `zoom-values` form `"1,2,3,4"`).
/// Zoom levels are tile zooms (Web Mercator, 256 px tiles), the same numbers as in the tile URLs.
public struct ZoomSet: Equatable, Hashable, Sendable, CustomStringConvertible {
    public static let maxZoom = 30

    private let values: [Int]

    private init(sorted values: [Int]) {
        self.values = values
    }

    public var min: Int { values[0] }
    public var max: Int { values[values.count - 1] }
    public var size: Int { values.count }
    public var isContiguous: Bool { max - min + 1 == size }

    public func toList() -> [Int] { values }

    public func contains(_ zoom: Int) -> Bool {
        var low = 0
        var high = values.count - 1
        while low <= high {
            let middle = (low + high) / 2
            if values[middle] == zoom { return true }
            if values[middle] < zoom { low = middle + 1 } else { high = middle - 1 }
        }
        return false
    }

    /// Canonical text form, e.g. `"1-3,5,7-9"`. Round-trips through `parse`.
    public func toSpec() -> String {
        var parts: [String] = []
        var start = values[0]
        var previous = start
        for zoom in values.dropFirst() + [Int.max] {
            if zoom == previous + 1 {
                previous = zoom
                continue
            }
            parts.append(start == previous ? "\(start)" : "\(start)-\(previous)")
            start = zoom
            previous = zoom
        }
        return parts.joined(separator: ",")
    }

    public var description: String { "ZoomSet(\(toSpec()))" }

    public static func of(_ zooms: Int...) throws -> ZoomSet {
        try create(zooms)
    }

    public static func range(_ min: Int, _ max: Int) throws -> ZoomSet {
        try require(min <= max, "Empty zoom range \(min)-\(max)")
        return try create(Array(min ... max))
    }

    /// Throws `ToolMapError.invalidArgument` when `spec` is empty or malformed.
    public static func parse(_ spec: String) throws -> ZoomSet {
        var zooms: [Int] = []
        for rawPart in kotlinSplit(spec, ",", ";", " ") {
            let part = kotlinTrim(rawPart)
            if part.isEmpty { continue }
            let bounds = kotlinSplit(part, "-").map(kotlinTrim)
            let first = kotlinInt(bounds[0])
            let last = bounds.count == 2 ? kotlinInt(bounds[1]) : first
            guard bounds.count <= 2, let from = first, let to = last, from <= to else {
                throw ToolMapError.invalidArgument("Invalid zoom specification '\(part)' in '\(spec)'")
            }
            zooms.append(contentsOf: from ... to)
        }
        return try create(zooms)
    }

    private static func create(_ zooms: [Int]) throws -> ZoomSet {
        let sorted = Array(Set(zooms)).sorted()
        try require(!sorted.isEmpty, "A zoom set cannot be empty")
        try require(
            sorted[0] >= 0 && sorted[sorted.count - 1] <= maxZoom,
            "Zoom levels must be within 0..\(maxZoom): \(sorted[0])..\(sorted[sorted.count - 1])"
        )
        return ZoomSet(sorted: sorted)
    }
}
