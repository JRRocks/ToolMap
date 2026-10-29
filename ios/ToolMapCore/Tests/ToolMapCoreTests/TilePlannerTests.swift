// Port of core/src/test/kotlin/fr/toolmap/core/tiles/TilePlannerTest.kt and tiles/TilePlannerStoredTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct TilePlannerTests {
    private let paris = LonLat(lon: 2.35, lat: 48.85)

    private func rectangle(_ minX: Double, _ minY: Double, _ maxX: Double, _ maxY: Double) -> DownloadArea {
        .rectangle(bounds: Bounds(minX: minX, minY: minY, maxX: maxX, maxY: maxY))
    }

    @Test("the whole world has 1, 4, 16 tiles at zoom 0, 1, 2")
    func wholeWorld() throws {
        let world = rectangle(-180.0, -85.0, 179.999, 85.0)
        #expect(try TilePlanner.count(world, 0 ... 2) == [0: 1, 1: 4, 2: 16])
    }

    @Test("a rectangle covers exactly the tiles between its corners")
    func rectangleCorners() throws {
        let area = rectangle(2.30, 48.80, 2.40, 48.90)
        let a = try TileMath.tileAt(2.30, 48.90, 14)
        let b = try TileMath.tileAt(2.40, 48.80, 14)
        let tiles = try TilePlanner.tiles(area, 14 ... 14)
        #expect(tiles.count == (b.x - a.x + 1) * (b.y - a.y + 1))
        #expect(tiles.allSatisfy { $0.z == 14 && $0.x >= a.x && $0.x <= b.x && $0.y >= a.y && $0.y <= b.y })
        #expect(try TilePlanner.count(area, 14 ... 14)[14] == tiles.count)
        #expect(Set(tiles).count == tiles.count)
    }

    @Test("a tiny rectangle is a single tile at every zoom")
    func tinyRectangle() throws {
        let area = rectangle(2.3501, 48.8501, 2.3502, 48.8502)
        #expect(try TilePlanner.count(area, 8 ... 12) == [8: 1, 9: 1, 10: 1, 11: 1, 12: 1])
    }

    @Test("tiles come ordered by zoom then row then column")
    func ordered() throws {
        let tiles = try TilePlanner.tiles(rectangle(2.0, 48.5, 2.7, 49.1), 9 ... 10)
        let sorted = tiles.sorted { a, b in (a.z, a.y, a.x) < (b.z, b.y, b.x) }
        #expect(tiles == sorted)
    }

    @Test("a polygon smaller than a tile gives that one tile")
    func smallPolygon() throws {
        let c = try TileMath.tileAt(paris.lon, paris.lat, 12)
        let box = try TileMath.boundsLonLat(c.x, c.y, 12)
        let cx = (box.minX + box.maxX) / 2
        let cy = (box.minY + box.maxY) / 2
        let e = (box.maxX - box.minX) / 10
        let ring = [LonLat(lon: cx - e, lat: cy - e), LonLat(lon: cx + e, lat: cy - e), LonLat(lon: cx, lat: cy + e)]
        let tiles = try TilePlanner.tiles(.polygon(ring: ring), 12 ... 12)
        #expect(tiles == [TileId(12, c.x, c.y)])
    }

    @Test("a polygon larger than a tile and fully inside includes tiles with no vertex in them")
    func largePolygon() throws {
        // A big square around Paris at z13: the centre tile has no vertex and no edge in it.
        let ring = [
            LonLat(lon: 2.20, lat: 48.75),
            LonLat(lon: 2.50, lat: 48.75),
            LonLat(lon: 2.50, lat: 48.95),
            LonLat(lon: 2.20, lat: 48.95),
        ]
        let tiles = try Set(TilePlanner.tiles(.polygon(ring: ring), 13 ... 13))
        let centre = try TileMath.tileAt(2.35, 48.85, 13)
        #expect(tiles.contains(TileId(13, centre.x, centre.y)))
        let rect = try Set(TilePlanner.tiles(rectangle(2.20, 48.75, 2.50, 48.95), 13 ... 13))
        // The polygon is a rectangle in lon/lat: Mercator edges are straight too, so both plans agree.
        #expect(rect == tiles)
    }

    @Test("a concave polygon leaves out the notch")
    func concavePolygon() throws {
        // U shape opening to the north: the tile in the notch is excluded.
        let ring = [
            LonLat(lon: 2.0, lat: 48.0),
            LonLat(lon: 2.3, lat: 48.0),
            LonLat(lon: 2.3, lat: 48.3),
            LonLat(lon: 2.2, lat: 48.3),
            LonLat(lon: 2.2, lat: 48.1),
            LonLat(lon: 2.1, lat: 48.1),
            LonLat(lon: 2.1, lat: 48.3),
            LonLat(lon: 2.0, lat: 48.3),
        ]
        let z = 12
        let tiles = try Set(TilePlanner.tiles(.polygon(ring: ring), z ... z))
        let notch = try TileMath.tileAt(2.15, 48.25, z) // well inside the notch (x 2.1..2.2, y > 48.1)
        #expect(!tiles.contains(TileId(z, notch.x, notch.y)))
        let arm = try TileMath.tileAt(2.05, 48.25, z)
        #expect(tiles.contains(TileId(z, arm.x, arm.y)))
        let bottom = try TileMath.tileAt(2.15, 48.05, z)
        #expect(tiles.contains(TileId(z, bottom.x, bottom.y)))
    }

    @Test("a polygon needs three points")
    func polygonNeedsThreePoints() {
        let ring = [paris, paris]
        #expect(throws: ToolMapError.self) {
            _ = try TilePlanner.tiles(.polygon(ring: ring), 5 ... 5)
        }
    }

    // Independent check for corridors: walk the path every few meters and measure the distance to each tile.
    private func distanceToTile(_ path: [LonLat], _ tile: TileId) throws -> Double {
        let box = try TileMath.boundsMercator(tile.x, tile.y, tile.z)
        var best = Double.greatestFiniteMagnitude
        for i in path.indices.dropFirst() {
            let a = path[i - 1]
            let b = path[i]
            let steps = 4000
            for k in 0 ... steps {
                let t = Double(k) / Double(steps)
                let lon = a.lon + (b.lon - a.lon) * t
                let lat = a.lat + (b.lat - a.lat) * t
                let mx = TileMath.mercatorX(lon)
                let my = TileMath.mercatorY(lat)
                let dx = Swift.max(Swift.max(box.minX - mx, 0.0), mx - box.maxX)
                let dy = Swift.max(Swift.max(box.minY - my, 0.0), my - box.maxY)
                best = Swift.min(best, hypot(dx, dy) * cos(Angle.radians(lat)))
            }
        }
        return best
    }

    @Test("a corridor keeps every tile within the radius and none far from it")
    func corridor() throws {
        let path = [LonLat(lon: 2.30, lat: 48.84), LonLat(lon: 2.36, lat: 48.87), LonLat(lon: 2.42, lat: 48.86)]
        let radius = 1_000.0
        let z = 14
        let tiles = try Set(TilePlanner.tiles(.corridor(path: path, radiusMeters: radius), z ... z))
        // Every tile of the neighbourhood that really is within the radius must be present.
        let a = try TileMath.tileAt(2.25, 48.92, z)
        let b = try TileMath.tileAt(2.47, 48.80, z)
        var within = 0
        for y in a.y ... b.y {
            for x in a.x ... b.x {
                let d = try distanceToTile(path, TileId(z, x, y))
                if d <= radius {
                    within += 1
                    #expect(tiles.contains(TileId(z, x, y)), "tile \(x),\(y) at \(Int(d)) m is missing")
                }
            }
        }
        #expect(within > 10, "only \(within) tiles within the radius")
        // And the plan must not wander: nothing farther than the radius plus about one tile and a half.
        let tileMeters = try 2 * TileMath.originShiftM / Double(TileMath.tilesPerAxis(z)) * cos(Angle.radians(48.85))
        for tile in tiles {
            let d = try distanceToTile(path, tile)
            #expect(d <= radius + tileMeters, "tile \(tile.x),\(tile.y) too far")
        }
    }

    @Test("a single point corridor is the disc around it")
    func singlePointCorridor() throws {
        let z = 15
        let tiles = try TilePlanner.tiles(.corridor(path: [paris], radiusMeters: 50.0), z ... z)
        #expect(!tiles.isEmpty && tiles.count <= 9)
        let c = try TileMath.tileAt(paris.lon, paris.lat, z)
        #expect(tiles.contains(TileId(z, c.x, c.y)))
    }

    @Test("a wider corridor never has fewer tiles")
    func widerCorridor() throws {
        let path = [LonLat(lon: 2.30, lat: 48.84), LonLat(lon: 2.42, lat: 48.88)]
        let narrow = try TilePlanner.count(.corridor(path: path, radiusMeters: 200.0), 12 ... 15)
        let wide = try TilePlanner.count(.corridor(path: path, radiusMeters: 2_000.0), 12 ... 15)
        for z in 12 ... 15 {
            #expect((wide[z] ?? 0) >= (narrow[z] ?? 0))
        }
        #expect((wide[15] ?? 0) > (narrow[15] ?? 0))
    }

    @Test("a zone that is too big is refused")
    func tooBig() {
        let europe = rectangle(-10.0, 35.0, 30.0, 60.0)
        #expect(throws: TooManyTilesError.self) {
            _ = try TilePlanner.count(europe, 0 ... 18)
        }
        #expect(throws: TooManyTilesError.self) {
            _ = try TilePlanner.tiles(europe, 16 ... 16)
        }
        let france = rectangle(2.0, 48.0, 3.0, 49.0)
        #expect(throws: TooManyTilesError.self) {
            _ = try TilePlanner.tiles(france, 10 ... 14, limit: 100)
        }
    }

    @Test("size estimation multiplies the tile count")
    func sizeEstimation() {
        #expect(TilePlanner.estimateBytes(100) == 2_000_000)
        #expect(TilePlanner.estimateBytes(5, averageTileBytes: 100) == 500)
    }
}

/// A fake store holding `stored` tiles at one zoom: answers the two box questions of `countStored`.
private final class StoredTilesFake {
    let stored: Set<TileCoord>
    var listed = false

    init(_ stored: Set<TileCoord>) {
        self.stored = stored
    }

    func countIn(_ xs: ClosedRange<Int>, _ ys: ClosedRange<Int>) -> Int {
        stored.filter { xs.contains($0.x) && ys.contains($0.y) }.count
    }

    func storedIn(_ xs: ClosedRange<Int>, _ ys: ClosedRange<Int>) -> (Int, Int) -> Bool {
        listed = true
        let inBox = stored.filter { xs.contains($0.x) && ys.contains($0.y) }
        return { x, y in inBox.contains(TileCoord(x: x, y: y)) }
    }
}

struct TilePlannerStoredTests {
    private let z = 13
    private let triangle = [
        LonLat(lon: 2.20, lat: 48.75),
        LonLat(lon: 2.50, lat: 48.75),
        LonLat(lon: 2.20, lat: 48.95),
    ]

    private func count(_ area: DownloadArea, _ store: StoredTilesFake, sampleLimit: Int = TilePlanner.storedSampleLimit) throws -> Int {
        try TilePlanner.countStored(
            area,
            z,
            countIn: { xs, ys in store.countIn(xs, ys) },
            storedIn: { xs, ys in store.storedIn(xs, ys) },
            sampleLimit: sampleLimit
        )
    }

    private func coord(_ tile: TileId) -> TileCoord {
        TileCoord(x: tile.x, y: tile.y)
    }

    @Test("a rectangle counts the stored tiles of its box without listing them")
    func rectangleBox() throws {
        let area = DownloadArea.rectangle(bounds: Bounds(minX: 2.30, minY: 48.80, maxX: 2.40, maxY: 48.90))
        let tiles = try TilePlanner.tiles(area, z ... z)
        let first = try #require(tiles.first)
        let maxX = try #require(tiles.map(\.x).max())
        let outside = TileCoord(x: maxX + 1, y: first.y)
        let store = StoredTilesFake(Set(tiles.prefix(5).map(coord) + [outside]))
        #expect(try count(area, store) == 5)
        #expect(!store.listed)
    }

    @Test("a polygon counts only the stored tiles it covers, not those of its box")
    func polygonOwnTiles() throws {
        let area = DownloadArea.polygon(ring: triangle)
        let inside = try TilePlanner.tiles(area, z ... z).map(coord)
        let insideSet = Set(inside)
        let box = DownloadArea.rectangle(bounds: Bounds(minX: 2.20, minY: 48.75, maxX: 2.50, maxY: 48.95))
        let outside = try TilePlanner.tiles(box, z ... z).map(coord).filter { !insideSet.contains($0) }
        #expect(!outside.isEmpty)
        let store = StoredTilesFake(Set(inside.prefix(7)).union(outside))
        #expect(try count(area, store) == 7)
    }

    @Test("nothing stored in the box gives zero without listing")
    func nothingStored() throws {
        let store = StoredTilesFake([TileCoord(x: 0, y: 0)])
        #expect(try count(.polygon(ring: triangle), store) == 0)
        #expect(!store.listed)
    }

    @Test("beyond the sample limit a polygon scales the box count by its share of the box")
    func sampleLimit() throws {
        let area = DownloadArea.polygon(ring: triangle)
        let tiles = try TilePlanner.tiles(area, z ... z)
        let minX = try #require(tiles.map(\.x).min())
        let maxX = try #require(tiles.map(\.x).max())
        let minY = try #require(tiles.map(\.y).min())
        let maxY = try #require(tiles.map(\.y).max())
        var everything = Set<TileCoord>()
        for x in minX ... maxX {
            for y in minY ... maxY {
                everything.insert(TileCoord(x: x, y: y))
            }
        }
        let store = StoredTilesFake(everything)
        // Every tile of the box is stored: the scaled count is exactly the polygon's own tiles.
        #expect(try count(area, store, sampleLimit: 1) == tiles.count)
        #expect(!store.listed)
    }
}
