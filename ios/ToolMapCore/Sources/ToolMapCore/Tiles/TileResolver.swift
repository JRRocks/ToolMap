// Port of core/src/main/kotlin/fr/toolmap/core/tiles/TileResolver.kt (and TileId of TilePlanner.kt).

/// One tile (XYZ scheme, like the tile URLs).
public struct TileId: Equatable, Hashable, Sendable {
    public let z: Int
    public let x: Int
    public let y: Int

    public init(_ z: Int, _ x: Int, _ y: Int) {
        self.z = z
        self.x = x
        self.y = y
    }
}

/// How far the map may use the tiles of another zoom so that it never shows an empty area (ADR-031). AlpineQuest
/// fills a zoom missing from `<zoom-values>` with a neighbouring level; ToolMap does the same, and also below the first
/// zoom of a map (a map that starts at zoom 6 still shows something at zoom 5, 4, 3).
public struct ScalingPolicy: Equatable, Sendable {
    public static let defaultLevels = 3
    public static let maxLevels = 4
    /// Beyond 8 levels a 256-pixel tile would be enlarged from a single pixel: pointless.
    public static let maxEnlarge = 8
    public static let off = ScalingPolicy(levels: 0)

    /// Levels a finer zoom may be shrunk by (4^n tiles for one, so it is bounded). `0` turns all scaling off.
    public let levels: Int

    public init(levels: Int = ScalingPolicy.defaultLevels) {
        self.levels = levels
    }

    /// Levels a coarser tile may be enlarged by (one tile each, so only bounded by usefulness).
    public var enlargeLevels: Int { levels <= 0 ? 0 : ScalingPolicy.maxEnlarge }

    public var shrinkLevels: Int { Swift.min(Swift.max(levels, 0), ScalingPolicy.maxLevels) }
}

/// How to obtain one displayed tile.
public indirect enum TileRecipe: Equatable, Sendable {
    /// A real tile of `source` (the one in the URL of `level`), served or stored as it is.
    case direct(source: TileSource, level: LevelDef, tile: TileId)
    /// Part (`col`, `row` of a 2^depth × 2^depth grid) of a coarser tile, enlarged to a full tile.
    case enlarge(from: TileRecipe, depth: Int, col: Int, row: Int)
    /// 2^depth × 2^depth finer tiles, row after row, shrunk into one tile.
    case shrink(parts: [TileRecipe], depth: Int)
    /// Nothing to show here.
    case none

    /// A real tile and where it comes from.
    public struct Leaf: Equatable, Sendable {
        public let source: TileSource
        public let level: LevelDef
        public let tile: TileId

        public init(source: TileSource, level: LevelDef, tile: TileId) {
            self.source = source
            self.level = level
            self.tile = tile
        }
    }

    /// The real tiles this recipe needs.
    public func leaves() -> [Leaf] {
        switch self {
        case let .direct(source, level, tile): [Leaf(source: source, level: level, tile: tile)]
        case let .enlarge(from, _, _, _): from.leaves()
        case let .shrink(parts, _): parts.flatMap { $0.leaves() }
        case .none: []
        }
    }

    var leafCount: Int {
        switch self {
        case .direct: 1
        case let .enlarge(from, _, _, _): from.leafCount
        case let .shrink(parts, _): parts.reduce(0) { $0 + $1.leafCount }
        case .none: 0
        }
    }
}

/// Decides where a tile shown for a map comes from: the map's own tile, the tile of the map a multi-zoom level points to
/// (`MapRef`), possibly at a forced zoom, or a neighbouring zoom shrunk or enlarged when the map has no level for the
/// displayed zoom. Pure: the tile pipeline executes the recipe.
public struct TileResolver {
    /// Longest chain of maps referencing maps (a cycle stops here).
    public static let maxDepth = 6
    /// Most real tiles one displayed tile may be built from (4 levels of shrinking).
    public static let maxLeaves = 256
    private static let maxZoom = 30

    private let lookup: (String) -> TileSource?

    public init(lookup: @escaping (String) -> TileSource?) {
        self.lookup = lookup
    }

    /// Recipe of the tile `x`, `y` of zoom `z` of `source`.
    public func resolve(_ source: TileSource, _ z: Int, _ x: Int, _ y: Int, _ policy: ScalingPolicy) -> TileRecipe {
        let recipe = resolve(source, z, x, y, policy, depth: 0, shrunk: 0)
        return recipe.leafCount > TileResolver.maxLeaves ? .none : recipe
    }

    /// The real tile zooms of each map that displaying `source` at zoom `z` reads (what a download must fetch so that
    /// the zoom shows offline), by source id. Independent of the position: levels only depend on the zoom.
    public func tileZooms(_ source: TileSource, _ z: Int, _ policy: ScalingPolicy) -> [String: Set<Int>] {
        var out: [String: Set<Int>] = [:]
        for leaf in resolve(source, z, 0, 0, policy).leaves() {
            out[leaf.source.id, default: []].insert(leaf.tile.z)
        }
        return out
    }

    private func resolve(
        _ source: TileSource,
        _ z: Int,
        _ x: Int,
        _ y: Int,
        _ policy: ScalingPolicy,
        depth: Int,
        shrunk: Int
    ) -> TileRecipe {
        if depth > TileResolver.maxDepth || !(0 ... TileResolver.maxZoom).contains(z) { return .none }
        guard let level = source.levelFor(z) else {
            return fallback(source, z, x, y, policy, depth: depth, shrunk: shrunk)
        }
        guard let reference = level.mapRef else { return .direct(source: source, level: level, tile: TileId(z, x, y)) }
        guard let member = lookup(reference.sourceId), member.id != source.id else { return .none }
        let n = reference.tileZoom ?? z
        if n == z {
            return resolve(member, z, x, y, policy, depth: depth + 1, shrunk: shrunk)
        } else if n < z {
            return enlarge(z - n, x, y) { px, py in resolve(member, n, px, py, policy, depth: depth + 1, shrunk: shrunk) }
        } else {
            let d = n - z
            if shrunk + d > ScalingPolicy.maxLevels { return .none }
            return shrink(d, x, y) { cx, cy in resolve(member, n, cx, cy, policy, depth: depth + 1, shrunk: shrunk + d) }
        }
    }

    /// No level of `source` at `z`: the nearest zoom it has, enlarged first on a tie (one tile instead of four).
    private func fallback(
        _ source: TileSource,
        _ z: Int,
        _ x: Int,
        _ y: Int,
        _ policy: ScalingPolicy,
        depth: Int,
        shrunk: Int
    ) -> TileRecipe {
        let reach = Swift.max(policy.enlargeLevels, policy.shrinkLevels)
        guard reach >= 1 else { return .none }
        for d in 1 ... reach {
            let lower = z - d
            if d <= policy.enlargeLevels, lower >= 0, source.levelFor(lower) != nil {
                return enlarge(d, x, y) { px, py in resolve(source, lower, px, py, policy, depth: depth + 1, shrunk: shrunk) }
            }
            let higher = z + d
            if d <= policy.shrinkLevels, shrunk + d <= ScalingPolicy.maxLevels, higher <= TileResolver.maxZoom,
               source.levelFor(higher) != nil
            {
                return shrink(d, x, y) { cx, cy in
                    resolve(source, higher, cx, cy, policy, depth: depth + 1, shrunk: shrunk + d)
                }
            }
        }
        return .none
    }

    private func enlarge(_ d: Int, _ x: Int, _ y: Int, _ parent: (Int, Int) -> TileRecipe) -> TileRecipe {
        let from = parent(x >> d, y >> d)
        if from == TileRecipe.none { return .none }
        let mask = (1 << d) - 1
        return .enlarge(from: from, depth: d, col: x & mask, row: y & mask)
    }

    private func shrink(_ d: Int, _ x: Int, _ y: Int, _ child: (Int, Int) -> TileRecipe) -> TileRecipe {
        let side = 1 << d
        var parts: [TileRecipe] = []
        parts.reserveCapacity(side * side)
        for row in 0 ..< side {
            for col in 0 ..< side {
                parts.append(child((x << d) + col, (y << d) + row))
            }
        }
        return parts.allSatisfy { $0 == TileRecipe.none } ? .none : .shrink(parts: parts, depth: d)
    }
}
