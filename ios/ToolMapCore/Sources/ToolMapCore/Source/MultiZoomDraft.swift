// Port of core/src/main/kotlin/fr/toolmap/core/source/MultiZoomDraft.kt.

import Foundation

/// One zoom range of a multi-zoom map: from `from` to `to` (displayed tile zooms, inclusive) the map shows `mapId`, at
/// the displayed zoom or, with `forcedZoom`, always with the tiles of that zoom (shrunk below it, enlarged above).
public struct ZoomBand: Equatable, Hashable, Sendable {
    public let from: Int
    public let to: Int
    public let mapId: String
    public let forcedZoom: Int?

    public init(_ from: Int, _ to: Int, _ mapId: String, _ forcedZoom: Int? = nil) {
        self.from = from
        self.to = to
        self.mapId = mapId
        self.forcedZoom = forcedZoom
    }

    /// Kotlin `from..to` (empty when `from > to`).
    public var zooms: IntRange { IntRange(from, to) }
}

/// What is wrong (or worth knowing) in a `MultiZoomDraft`; `band` is the index of the band concerned, if any.
public struct DraftIssue: Equatable, Hashable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case noName = "NO_NAME"
        case noBand = "NO_BAND"
        case badRange = "BAD_RANGE"
        case overlap = "OVERLAP"
        case unknownMap = "UNKNOWN_MAP"
        /// The band shows a map that shows this one (directly or not): it would loop.
        case loop = "LOOP"
        /// The forced zoom is more than `ScalingPolicy.maxLevels` above the band: too many tiles for one.
        case forcedTooFar = "FORCED_TOO_FAR"
        /// The map has no tile at the forced zoom (its nearest zoom is used).
        case forcedNotServed = "FORCED_NOT_SERVED"
        /// The map serves none of the zooms of the band (its nearest zoom is used).
        case notServed = "NOT_SERVED"
        /// No band covers the zooms between two bands (they are filled from the nearest band).
        case gap = "GAP"

        public var blocking: Bool {
            switch self {
            case .forcedNotServed, .notServed, .gap: return false
            default: return true
            }
        }

        /// Kotlin `ordinal`: the position in the declaration.
        var ordinal: Int { Kind.allCases.firstIndex(of: self) ?? 0 }
    }

    public let kind: Kind
    public let band: Int?
    /// Second band of an overlap, or the zoom range of a gap.
    public let other: Int?

    public init(_ kind: Kind, _ band: Int? = nil, _ other: Int? = nil) {
        self.kind = kind
        self.band = band
        self.other = other
    }
}

/// A multi-zoom map being edited (ADR-031): the AlpineQuest `.aqx` whose levels point to different servers
/// (`USR.PERSONNAL_MULTIZOOM.AQX`), built in the app instead of by hand. Each band references a map of the catalogue,
/// so the tiles are those of that map: already downloaded tiles are used and nothing is stored twice.
public struct MultiZoomDraft: Equatable, Hashable, Sendable {
    private static let idPrefix = "mz-"
    private static let maxSlug = 32

    public var name: String
    public var bands: [ZoomBand]

    public init(_ name: String, _ bands: [ZoomBand]) {
        self.name = name
        self.bands = bands
    }

    /// Problems sorted by band; the draft can be saved when none is `DraftIssue.Kind.blocking`.
    public func issues(_ selfId: String?, _ lookup: (String) -> TileSource?) -> [DraftIssue] {
        var out: [DraftIssue] = []
        if kotlinTrim(name).isEmpty { out.append(DraftIssue(.noName)) }
        if bands.isEmpty { out.append(DraftIssue(.noBand)) }
        for (i, band) in bands.enumerated() {
            if band.from > band.to || band.from < 0 || band.to > ZoomSet.maxZoom {
                out.append(DraftIssue(.badRange, i))
                continue
            }
            guard let map = lookup(band.mapId) else {
                out.append(DraftIssue(.unknownMap, i))
                continue
            }
            if let target = selfId, MultiZoomDraft.reaches(map, target, lookup, 0) {
                out.append(DraftIssue(.loop, i))
            } else if let forced = band.forcedZoom, forced - band.from > ScalingPolicy.maxLevels {
                out.append(DraftIssue(.forcedTooFar, i))
            } else if let forced = band.forcedZoom, !map.isMultiZoom, map.levelFor(forced) == nil {
                out.append(DraftIssue(.forcedNotServed, i))
            } else if band.forcedZoom == nil, !map.isMultiZoom,
                      !stride(from: band.from, through: band.to, by: 1).contains(where: { map.levelFor($0) != nil })
            {
                out.append(DraftIssue(.notServed, i))
            }
        }
        let valid = MultiZoomDraft.stableSorted(
            bands.enumerated().filter { $0.element.from <= $0.element.to }
        ) { $0.element.from }
        for k in valid.indices.dropLast() {
            let a = valid[k]
            let b = valid[k + 1]
            if b.element.from <= a.element.to {
                out.append(DraftIssue(.overlap, a.offset, b.offset))
            } else if b.element.from > a.element.to + 1 {
                out.append(DraftIssue(.gap, a.offset, b.offset))
            }
        }
        return out.enumerated().sorted { a, b in
            let left = (a.element.band ?? -1, a.element.kind.ordinal, a.offset)
            let right = (b.element.band ?? -1, b.element.kind.ordinal, b.offset)
            return left < right
        }.map(\.element)
    }

    public func canSave(_ selfId: String?, _ lookup: (String) -> TileSource?) -> Bool {
        !issues(selfId, lookup).contains { $0.kind.blocking }
    }

    /// The map to save under `id`. Its credit, type and preview come from the maps it shows.
    public func toSource(_ id: String, _ lookup: (String) -> TileSource?) throws -> TileSource {
        let sorted = MultiZoomDraft.stableSorted(bands) { $0.from }
        var members: [TileSource] = []
        for band in sorted {
            if let member = lookup(band.mapId), !members.contains(where: { $0.id == member.id }) {
                members.append(member)
            }
        }
        var widestBand: ZoomBand?
        for band in sorted {
            if let current = widestBand, band.to - band.from <= current.to - current.from { continue }
            widestBand = band
        }
        let widest = widestBand.flatMap { lookup($0.mapId) }
        var copyrights: [String] = []
        for member in members {
            if let copyright = member.copyright, !copyrights.contains(copyright) { copyrights.append(copyright) }
        }
        var levels: [LevelDef] = []
        for band in sorted {
            let zooms = try ZoomSet.range(band.from, band.to)
            let reference = try MapRef(sourceId: band.mapId, tileZoom: band.forcedZoom)
            levels.append(LevelDef(zooms: zooms, servers: [reference.toServer()]))
        }
        return TileSource(
            id: id,
            name: kotlinTrim(name),
            type: widest?.type ?? members.first?.type,
            copyright: copyrights.joined(separator: " | ").nilIfEmpty,
            dataSource: members.lazy.compactMap(\.dataSource).first,
            region: members.lazy.compactMap(\.region).first,
            previewLocation: widest?.previewLocation ?? members.lazy.compactMap(\.previewLocation).first,
            levels: levels
        )
    }

    /// The draft of a map made of references only (one band per contiguous run of zooms), or nil.
    public static func of(_ source: TileSource) -> MultiZoomDraft? {
        if source.levels.isEmpty || source.levels.contains(where: { $0.mapRef == nil }) { return nil }
        var bands: [ZoomBand] = []
        for level in source.levels {
            guard let reference = level.mapRef else { return nil }
            for run in runsOf(level.zooms.toList()) {
                bands.append(ZoomBand(run.first, run.last, reference.sourceId, reference.tileZoom))
            }
        }
        return MultiZoomDraft(source.name, stableSorted(bands) { $0.from })
    }

    /// A new multi-zoom map built from any map: each level becomes bands showing the catalogue map that serves the same
    /// tiles (the built-in one first, else `source` itself), and a zoom missing inside a `<zoom-values>` list becomes a
    /// band forced to its nearest listed zoom (the coarser one on a tie), which is what AlpineQuest displays there. A
    /// multi-zoom map is simply copied.
    public static func derivedFrom(_ source: TileSource, _ catalogue: [TileSource], _ name: String) -> MultiZoomDraft {
        if let existing = of(source) {
            return MultiZoomDraft(name, existing.bands)
        }
        var owners: [String: String] = [:]
        for map in stableSorted(catalogue, by: { $0.id == source.id ? 1 : 0 }) {
            if map.isMultiZoom { continue }
            for identity in TileSetIdentity.allOf(map) where owners[identity] == nil {
                owners[identity] = map.id
            }
        }
        var bands: [ZoomBand] = []
        for level in source.levels {
            let levelRef = level.mapRef
            let mapId: String
            if let target = levelRef {
                mapId = target.sourceId
            } else if level.isLocalOnly {
                mapId = source.id
            } else {
                mapId = TileSetIdentity.of(source, level).flatMap { owners[$0] } ?? source.id
            }
            if let forced = levelRef?.tileZoom {
                for run in runsOf(level.zooms.toList()) {
                    bands.append(ZoomBand(run.first, run.last, mapId, forced))
                }
                continue
            }
            let zooms = level.zooms.toList()
            for run in runsOf(zooms) {
                bands.append(ZoomBand(run.first, run.last, mapId))
            }
            // Holes inside the list: forced to the nearest listed zoom.
            guard let low = zooms.first, let high = zooms.last, low <= high else { continue }
            for z in low ... high {
                if level.zooms.contains(z) || source.levelFor(z) != nil { continue }
                var nearest = low
                for candidate in zooms where (abs(candidate - z), candidate) < (abs(nearest - z), nearest) {
                    nearest = candidate
                }
                bands.append(ZoomBand(z, z, mapId, nearest))
            }
        }
        return MultiZoomDraft(name, mergeAdjacent(stableSorted(bands) { $0.from }))
    }

    /// A free id for a new multi-zoom map named `name`.
    public static func newId(_ name: String, taken: (String) -> Bool) -> String {
        var plain = String.UnicodeScalarView()
        for scalar in name.lowercased().decomposedStringWithCanonicalMapping.unicodeScalars
            where scalar.properties.generalCategory != .nonspacingMark
        {
            plain.append(scalar)
        }
        var slug = trimDashes(replaceNonIdRuns(String(plain)))
        slug = trimDashes(String(slug.prefix(maxSlug)))
        let base = idPrefix + (slug.isEmpty ? "carte" : slug)
        if !taken(base) { return base }
        var n = 2
        while taken("\(base)-\(n)") {
            n += 1
        }
        return "\(base)-\(n)"
    }

    /// Kotlin `replace(Regex("[^a-z0-9]+"), "-")`.
    private static func replaceNonIdRuns(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        var inRun = false
        for scalar in text.unicodeScalars {
            if ("a" ... "z").contains(scalar) || ("0" ... "9").contains(scalar) {
                out.append(scalar)
                inRun = false
            } else if !inRun {
                out.append("-")
                inRun = true
            }
        }
        return String(out)
    }

    /// Kotlin `trim('-')`.
    private static func trimDashes(_ text: String) -> String {
        var slice = Substring(text)
        while slice.hasPrefix("-") {
            slice = slice.dropFirst()
        }
        while slice.hasSuffix("-") {
            slice = slice.dropLast()
        }
        return String(slice)
    }

    private static func runsOf(_ zooms: [Int]) -> [IntRange] {
        guard var start = zooms.first else { return [] }
        var runs: [IntRange] = []
        var previous = start
        for z in zooms.dropFirst() {
            if z != previous + 1 {
                runs.append(IntRange(start, previous))
                start = z
            }
            previous = z
        }
        runs.append(IntRange(start, previous))
        return runs
    }

    private static func mergeAdjacent(_ bands: [ZoomBand]) -> [ZoomBand] {
        var out: [ZoomBand] = []
        for band in bands {
            if let last = out.last, last.to + 1 == band.from, last.mapId == band.mapId,
               last.forcedZoom == band.forcedZoom
            {
                out[out.count - 1] = ZoomBand(last.from, band.to, last.mapId, last.forcedZoom)
            } else {
                out.append(band)
            }
        }
        return out
    }

    /// True when `map` shows the map `targetId`, directly or through other multi-zoom maps.
    private static func reaches(
        _ map: TileSource,
        _ targetId: String,
        _ lookup: (String) -> TileSource?,
        _ depth: Int
    ) -> Bool {
        if map.id == targetId { return true }
        if depth > StorageKeys.maxDepth { return true }
        return map.levels.contains { level in
            guard let reference = level.mapRef, let next = lookup(reference.sourceId) else { return false }
            return next.id != map.id && reaches(next, targetId, lookup, depth + 1)
        }
    }

    /// Kotlin `sortedBy`: a stable sort on `key`.
    private static func stableSorted<T, K: Comparable>(_ items: [T], by key: (T) -> K) -> [T] {
        items.enumerated().sorted { a, b in
            let left = key(a.element)
            let right = key(b.element)
            return left != right ? left < right : a.offset < b.offset
        }.map(\.element)
    }
}
