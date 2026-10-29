// Port of core/src/main/kotlin/fr/toolmap/core/source/SourceDuplicates.kt.

/// What a map read from a file has in common with the maps already in the catalogue (import preview, ADR-031). Since
/// tiles are stored by `TileSetIdentity`, a duplicate costs no second download; the preview still says so, and leaves
/// an exact duplicate unticked so that the catalogue does not list the same map twice.
public struct DuplicateInfo: Equatable, Hashable, Sendable {
    public static let none = DuplicateInfo(duplicateOf: nil, sharesWith: [])

    /// A catalogue map that shows exactly the same tiles at every zoom of the new one, or nil.
    public let duplicateOf: TileSource?
    /// Catalogue maps sharing part of their tiles with the new one (without `duplicateOf`).
    public let sharesWith: [TileSource]

    public init(duplicateOf: TileSource?, sharesWith: [TileSource]) {
        self.duplicateOf = duplicateOf
        self.sharesWith = sharesWith
    }

    public var isEmpty: Bool { duplicateOf == nil && sharesWith.isEmpty }
}

public enum SourceDuplicates {
    /// `candidate` compared with every map of `catalogue` but itself (same id: replaced, not duplicated).
    public static func of(_ candidate: TileSource, _ catalogue: [TileSource]) -> DuplicateInfo {
        let identities = TileSetIdentity.allOf(candidate)
        if identities.isEmpty { return DuplicateInfo.none }
        var duplicate: TileSource?
        var shares: [TileSource] = []
        for map in catalogue {
            if map.id == candidate.id || map.isMultiZoom { continue }
            let theirs = TileSetIdentity.allOf(map)
            if !theirs.contains(where: { identities.contains($0) }) { continue }
            if duplicate == nil, covers(map, candidate) {
                duplicate = map
            } else {
                shares.append(map)
            }
        }
        return DuplicateInfo(duplicateOf: duplicate, sharesWith: shares)
    }

    /// True when every zoom of `candidate` is served by `map` with the same tiles, as a base map or a layer alike.
    private static func covers(_ map: TileSource, _ candidate: TileSource) -> Bool {
        if map.isLayer != candidate.isLayer { return false }
        return candidate.levels.allSatisfy { level in
            guard let identity = TileSetIdentity.of(candidate, level) else { return false }
            return level.zooms.toList().allSatisfy { z in
                map.levelFor(z).flatMap { TileSetIdentity.of(map, $0) } == identity
            }
        }
    }
}
