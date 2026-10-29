// Port of core/src/test/kotlin/fr/toolmap/core/tiles/TileMathTest.kt.

import Testing
@testable import ToolMapCore

struct TileMathTests {
    private func expectNear(_ expected: Double, _ actual: Double, _ tolerance: Double = 1e-6) {
        #expect(abs(expected - actual) <= tolerance, "expected \(expected) but was \(actual)")
    }

    @Test("quadkey matches the Bing reference")
    func quadkeyMatchesBing() throws {
        #expect(try TileMath.quadKey(3, 5, 3) == "213")
        #expect(try TileMath.quadKey(0, 0, 0) == "")
        #expect(try TileMath.quadKey(0, 0, 1) == "0")
        #expect(try TileMath.quadKey(1, 0, 1) == "1")
        #expect(try TileMath.quadKey(0, 1, 1) == "2")
        #expect(try TileMath.quadKey(1, 1, 1) == "3")
    }

    @Test("quadkey length equals the zoom")
    func quadkeyLength() throws {
        #expect(try TileMath.quadKey(8460, 5950, 14).count == 14)
    }

    @Test("tms row is the flipped xyz row")
    func tmsRow() throws {
        #expect(try TileMath.tmsY(5, 3) == 2)
        #expect(try TileMath.tmsY(0, 3) == 7)
        #expect(try TileMath.tmsY(7, 3) == 0)
        #expect(try TileMath.tmsY(0, 0) == 0)
    }

    @Test("world tile bounds")
    func worldTileBounds() throws {
        let lonLat = try TileMath.boundsLonLat(0, 0, 0)
        expectNear(-180.0, lonLat.minX)
        expectNear(180.0, lonLat.maxX)
        expectNear(-85.0511287798, lonLat.minY, 1e-8)
        expectNear(85.0511287798, lonLat.maxY, 1e-8)
        let mercator = try TileMath.boundsMercator(0, 0, 0)
        expectNear(-TileMath.originShiftM, mercator.minX)
        expectNear(TileMath.originShiftM, mercator.maxY)
    }

    @Test("tileAt matches an independent computation")
    func tileAtReference() throws {
        // IGN preview location 5.90,44.80 is tile 14/8460/5906 and 12/2115/1476 (computed separately in Python).
        #expect(try TileMath.tileAt(5.90, 44.80, 14) == TileCoord(x: 8460, y: 5906))
        #expect(try TileMath.tileAt(5.90, 44.80, 12) == TileCoord(x: 2115, y: 1476))
        #expect(try TileMath.tileAt(-180.0, 85.0, 0) == TileCoord(x: 0, y: 0))
        #expect(try TileMath.tileAt(179.99, -85.0, 1) == TileCoord(x: 1, y: 1))
    }

    @Test("tileAt is clamped at the edges of the map")
    func tileAtClamped() throws {
        #expect(try TileMath.tileAt(-200.0, 89.9, 5) == TileCoord(x: 0, y: 0))
        #expect(try TileMath.tileAt(180.0, -89.9, 5) == TileCoord(x: 31, y: 31))
    }

    @Test("a tile contains the point it was computed from")
    func tileContainsPoint() throws {
        for zoom in [3, 9, 14, 18] {
            let tile = try TileMath.tileAt(5.90, 44.80, zoom)
            let bounds = try TileMath.boundsLonLat(tile.x, tile.y, zoom)
            #expect((bounds.minX ... bounds.maxX).contains(5.90), "lon not in \(bounds) at zoom \(zoom)")
            #expect((bounds.minY ... bounds.maxY).contains(44.80), "lat not in \(bounds) at zoom \(zoom)")
        }
    }

    @Test("row 5950 at zoom 14 is the tile checked with curl in session 1")
    func row5950() throws {
        // Tile 14/8460/5950 answered HTTP 200 on the IGN service; it lies around 44.11 N, not at the preview point.
        let bounds = try TileMath.boundsLonLat(8460, 5950, 14)
        expectNear(44.1034, bounds.minY, 1e-4)
        expectNear(44.1191, bounds.maxY, 1e-4)
    }

    @Test("adjacent tiles share an edge")
    func adjacentTiles() throws {
        let left = try TileMath.boundsLonLat(10, 20, 6)
        let right = try TileMath.boundsLonLat(11, 20, 6)
        expectNear(left.maxX, right.minX, 1e-12)
        let top = try TileMath.boundsMercator(10, 20, 6)
        let bottom = try TileMath.boundsMercator(10, 21, 6)
        expectNear(top.minY, bottom.maxY, 1e-6)
    }

    @Test("ground resolution")
    func groundResolution() {
        // Classic value: 156543.03 m/px at zoom 0 on the equator (256 px tiles).
        expectNear(156_543.03392804097, TileMath.metersPerPixel(0.0, 0), 1e-6)
        // Halves at each zoom and shrinks with cos(latitude).
        expectNear(TileMath.metersPerPixel(0.0, 10) / 2, TileMath.metersPerPixel(0.0, 11), 1e-9)
        expectNear(TileMath.metersPerPixel(0.0, 10) * 0.5, TileMath.metersPerPixel(60.0, 10), 1e-9)
        // 512 px tiles halve the resolution.
        expectNear(TileMath.metersPerPixel(45.0, 12) / 2, TileMath.metersPerPixel(45.0, 12, 512), 1e-9)
    }

    @Test("out of range tiles are rejected")
    func outOfRange() {
        #expect(throws: ToolMapError.self) { try TileMath.quadKey(8, 0, 3) }
        #expect(throws: ToolMapError.self) { try TileMath.tmsY(-1, 3) }
        #expect(throws: ToolMapError.self) { try TileMath.tilesPerAxis(31) }
        #expect(throws: ToolMapError.self) { try TileMath.boundsLonLat(0, 2, 1) }
    }
}
