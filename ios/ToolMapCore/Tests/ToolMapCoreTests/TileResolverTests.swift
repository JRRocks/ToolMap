// Port of core/src/test/kotlin/fr/toolmap/core/tiles/TileResolverTest.kt.

import Testing
@testable import ToolMapCore

/// The maps a resolver can look up; the cases add their own.
private final class Catalogue {
    var maps: [String: TileSource] = [:]
}

struct TileResolverTests {
    private let catalogue = Catalogue()
    private let scan: TileSource
    private let maps: TileSource
    private let resolver: TileResolver
    private let standard = ScalingPolicy()

    init() throws {
        scan = try Self.map("scan", ZoomSet.range(6, 16))
        maps = try Self.map("maps", ZoomSet.range(1, 18))
        catalogue.maps = [scan.id: scan, maps.id: maps]
        let catalogue = catalogue
        resolver = TileResolver { catalogue.maps[$0] }
    }

    private static func map(_ id: String, _ zooms: ZoomSet...) -> TileSource {
        TileSource(id: id, name: id, levels: zooms.map { LevelDef(zooms: $0, servers: ["https://\(id).example.org/{$z}/{$x}/{$y}.png"]) })
    }

    private func multi(_ id: String, _ bands: (ZoomSet, MapRef)...) -> TileSource {
        TileSource(id: id, name: id, levels: bands.map { LevelDef(zooms: $0.0, servers: [$0.1.toServer()]) })
    }

    private func zooms(_ recipe: TileRecipe) -> Set<Int> {
        Set(recipe.leaves().map(\.tile.z))
    }

    @Test("a zoom the map serves is used as it is")
    func direct() {
        let recipe = resolver.resolve(scan, 12, 2100, 1490, standard)
        #expect(recipe == .direct(source: scan, level: scan.levels[0], tile: TileId(12, 2100, 1490)))
    }

    @Test("below its first zoom a map is drawn by shrinking its first zoom")
    func shrinkBelow() {
        let recipe = resolver.resolve(scan, 5, 16, 11, standard)
        guard case let .shrink(_, depth) = recipe else {
            Issue.record("expected a shrink, got \(recipe)")
            return
        }
        #expect(depth == 1)
        #expect(recipe.leaves().map(\.tile) == [TileId(6, 32, 22), TileId(6, 33, 22), TileId(6, 32, 23), TileId(6, 33, 23)])
        // Three levels by default, then nothing: 4^3 = 64 tiles for one is the limit.
        #expect(resolver.resolve(scan, 3, 2, 1, standard).leaves().count == 64)
        #expect(resolver.resolve(scan, 2, 1, 0, standard) == TileRecipe.none)
        #expect(resolver.resolve(scan, 2, 1, 0, ScalingPolicy(levels: 4)).leaves().count == 256)
    }

    @Test("above its last zoom a map is enlarged")
    func enlargeAbove() {
        let recipe = resolver.resolve(scan, 18, 4 * 2100 + 3, 4 * 1490 + 1, standard)
        let expected = TileRecipe.enlarge(
            from: .direct(source: scan, level: scan.levels[0], tile: TileId(16, 2100, 1490)),
            depth: 2,
            col: 3,
            row: 1
        )
        #expect(recipe == expected)
    }

    @Test("scaling off leaves the map empty outside its zooms")
    func scalingOff() {
        #expect(resolver.resolve(scan, 5, 16, 11, .off) == TileRecipe.none)
        #expect(resolver.resolve(scan, 17, 0, 0, .off) == TileRecipe.none)
    }

    @Test("a gap in the zoom list uses the nearest zoom, the coarser one on a tie")
    func gaps() throws {
        // Zoom values 3,5,7-16 as in the ski touring file: zoom 4 and 6 are missing.
        let gaps = Self.map("gaps", try ZoomSet.parse("3,5,7-16"))
        guard case .enlarge = resolver.resolve(gaps, 4, 5, 3, standard) else {
            Issue.record("zoom 4 should enlarge zoom 3")
            return
        }
        let six = resolver.resolve(gaps, 6, 33, 22, standard)
        guard case .enlarge = six else {
            Issue.record("zoom 6 should enlarge zoom 5")
            return
        }
        #expect(six.leaves().first?.tile.z == 5)
        // Zoom 1 and 2 have no coarser zoom: zoom 3 is shrunk.
        #expect(zooms(resolver.resolve(gaps, 2, 1, 1, standard)) == [3])
    }

    @Test("a multi-zoom level shows the referenced map at the displayed zoom")
    func multiZoom() throws {
        let mz = multi(
            "mz",
            (try ZoomSet.range(1, 11), try MapRef(sourceId: "maps")),
            (try ZoomSet.range(12, 16), try MapRef(sourceId: "scan"))
        )
        catalogue.maps[mz.id] = mz
        #expect(resolver.resolve(mz, 9, 260, 180, standard) == .direct(source: maps, level: maps.levels[0], tile: TileId(9, 260, 180)))
        #expect(
            resolver.resolve(mz, 14, 8400, 5960, standard) ==
                .direct(source: scan, level: scan.levels[0], tile: TileId(14, 8400, 5960))
        )
        // Beyond its last band (16) the multi-zoom map enlarges its own zoom 16, i.e. SCAN 25.
        let z17 = resolver.resolve(mz, 17, 2 * 8400, 2 * 5960, standard)
        guard case .enlarge = z17 else {
            Issue.record("zoom 17 should enlarge zoom 16")
            return
        }
        #expect(z17.leaves().first?.tile == TileId(16, 8400, 5960))
        #expect(z17.leaves().first?.source == scan)
    }

    @Test("a forced zoom shrinks or enlarges the tiles of that zoom")
    func forcedZoom() throws {
        // IGN Topo 25k: zooms 13-14 show zoom-15 tiles of IGN Cartes (the SCAN 25), 17-18 its zoom-16 tiles.
        let topo25 = multi(
            "topo25",
            (try ZoomSet.range(1, 12), try MapRef(sourceId: "maps")),
            (try ZoomSet.range(13, 14), try MapRef(sourceId: "maps", tileZoom: 15)),
            (try ZoomSet.range(15, 16), try MapRef(sourceId: "maps")),
            (try ZoomSet.range(17, 18), try MapRef(sourceId: "maps", tileZoom: 16))
        )
        let z13 = resolver.resolve(topo25, 13, 4200, 2980, standard)
        guard case let .shrink(_, depth) = z13 else {
            Issue.record("zoom 13 should shrink zoom 15")
            return
        }
        #expect(depth == 2)
        #expect(z13.leaves().count == 16)
        #expect(z13.leaves().allSatisfy { $0.source == maps && $0.tile.z == 15 })
        #expect(z13.leaves().first?.tile == TileId(15, 4 * 4200, 4 * 2980))
        let z18 = resolver.resolve(topo25, 18, 4 * 33600 + 1, 4 * 23840 + 2, standard)
        let expected = TileRecipe.enlarge(
            from: .direct(source: maps, level: maps.levels[0], tile: TileId(16, 33600, 23840)),
            depth: 2,
            col: 1,
            row: 2
        )
        #expect(z18 == expected)
        // The forced zoom does not depend on the display scaling setting.
        #expect(resolver.resolve(topo25, 13, 4200, 2980, .off).leaves().count == 16)
        #expect(resolver.tileZooms(topo25, 14, standard) == ["maps": [15]])
        #expect(resolver.tileZooms(topo25, 17, standard) == ["maps": [16]])
        #expect(resolver.tileZooms(topo25, 12, standard) == ["maps": [12]])
    }

    @Test("a forced zoom too far above is refused")
    func forcedZoomTooFar() throws {
        // 6 levels below the forced zoom would need 4096 tiles for one.
        let far = multi("far", (try ZoomSet.range(1, 10), try MapRef(sourceId: "maps", tileZoom: 16)))
        #expect(resolver.resolve(far, 10, 0, 0, standard) == TileRecipe.none)
        let near = multi("near", (try ZoomSet.range(12, 12), try MapRef(sourceId: "maps", tileZoom: 16)))
        #expect(resolver.resolve(near, 12, 0, 0, standard).leaves().count == 256)
    }

    @Test("missing maps and cycles give nothing instead of looping")
    func cycles() throws {
        let orphan = multi("orphan", (try ZoomSet.range(1, 18), try MapRef(sourceId: "deleted")))
        #expect(resolver.resolve(orphan, 10, 0, 0, standard) == TileRecipe.none)
        let a = multi("a", (try ZoomSet.range(1, 18), try MapRef(sourceId: "b")))
        let b = multi("b", (try ZoomSet.range(1, 18), try MapRef(sourceId: "a")))
        catalogue.maps["a"] = a
        catalogue.maps["b"] = b
        #expect(resolver.resolve(a, 10, 0, 0, standard) == TileRecipe.none)
        let selfRef = multi("self", (try ZoomSet.range(1, 18), try MapRef(sourceId: "self")))
        catalogue.maps["self"] = selfRef
        #expect(resolver.resolve(selfRef, 10, 0, 0, standard) == TileRecipe.none)
    }

    @Test("a tile never needs more than the leaf limit")
    func leafLimit() throws {
        // A chain of forced zooms that would multiply shrinking: 4 levels, then 4 more (65,536 tiles).
        let inner = multi("inner", (try ZoomSet.range(0, 20), try MapRef(sourceId: "maps", tileZoom: 18)))
        let outer = multi("outer", (try ZoomSet.range(0, 20), try MapRef(sourceId: "inner", tileZoom: 14)))
        catalogue.maps[inner.id] = inner
        catalogue.maps[outer.id] = outer
        #expect(resolver.resolve(outer, 10, 0, 0, standard) == TileRecipe.none)
    }
}
