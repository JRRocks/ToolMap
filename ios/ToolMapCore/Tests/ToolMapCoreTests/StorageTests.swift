// The MBTiles file (port of data/src/main/kotlin/fr/toolmap/data/tiles/MbTilesFile.kt, tested on Android with
// Robolectric) and the composer of enlarged or shrunk tiles (BitmapTileComposer).

import CoreGraphics
import Foundation
import Testing
@testable import ToolMapCore

private func temporaryFile(_ name: String) -> URL {
    FileManager.default.temporaryDirectory
        .appendingPathComponent("toolmap-tests-\(UUID().uuidString)")
        .appendingPathComponent(name)
}

/// A solid PNG tile of one colour.
private func solidTile(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat, size: Int = 256) throws -> Data {
    let context = try #require(TileComposer.context(size))
    context.setFillColor(red: red, green: green, blue: blue, alpha: 1)
    context.fill(CGRect(x: 0, y: 0, width: size, height: size))
    let image = try #require(context.makeImage())
    return try #require(TileComposer.png(image))
}

/// The RGB of the pixel at (x, y), counted from the top-left corner.
private func pixel(_ png: Data, _ x: Int, _ y: Int) throws -> [UInt8] {
    let image = try #require(TileComposer.decode(png))
    let context = try #require(TileComposer.context(image.width))
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    let data = try #require(context.data)
    let bytes = data.bindMemory(to: UInt8.self, capacity: context.bytesPerRow * image.height)
    let offset = y * context.bytesPerRow + x * 4
    return [bytes[offset], bytes[offset + 1], bytes[offset + 2]]
}

struct MbTilesFileTests {
    @Test("a tile written comes back with its type and date, in TMS rows on disk")
    func roundTrip() throws {
        let url = temporaryFile("osm.mbtiles")
        let file = try MbTilesFile.open(url, initialMetadata: ["name": "OpenStreetMap"])
        let png = try solidTile(1, 0, 0)
        try file.put(TileId(14, 8460, 5906), StoredTile(bytes: png, contentType: "image/png", storedAtMillis: 1000))
        let back = try #require(try file.get(TileId(14, 8460, 5906)))
        #expect(back.bytes == png)
        #expect(back.contentType == "image/png")
        #expect(back.storedAtMillis == 1000)
        #expect(try file.get(TileId(14, 8460, 5907)) == nil)
        #expect(try file.contains(TileId(14, 8460, 5906)))
        #expect(try file.tileCount() == 1)
        #expect(try file.tileCountByZoom() == [14: 1])
        #expect(try file.metadata()["name"] == "OpenStreetMap")
        #expect(try file.metadata()["format"] == "png")
        file.close()
        // Another reader sees the standard schema with the TMS row: 2^14 - 1 - 5906 = 10477.
        let raw = try SQLiteDatabase(path: url.path)
        let rows = try raw.query("SELECT zoom_level, tile_column, tile_row FROM tiles")
        #expect(rows == [[.integer(14), .integer(8460), .integer(10477)]])
        raw.close()
    }

    @Test("coverage tells a stored tile from one with stored descendants")
    func coverage() throws {
        let file = try MbTilesFile.open(temporaryFile("c.mbtiles"))
        let png = try solidTile(0, 1, 0)
        try file.put(TileId(12, 2115, 1477), StoredTile(bytes: png, contentType: "image/png"))
        #expect(try file.coverage(TileId(12, 2115, 1477)) == .full)
        #expect(try file.coverage(TileId(11, 1057, 738)) == .partial)
        #expect(try file.coverage(TileId(9, 264, 184)) == .partial)
        #expect(try file.coverage(TileId(8, 132, 92)) == .none)
        #expect(try file.countTiles(12, x: 2110 ... 2120, y: 1470 ... 1480) == 1)
        #expect(try file.storedTiles(12, x: 2110 ... 2120, y: 1470 ... 1480) == [TileId(12, 2115, 1477)])
    }

    @Test("old tiles expire and are purged, imported ones never")
    func expiry() throws {
        let file = try MbTilesFile.open(temporaryFile("e.mbtiles"))
        let png = try solidTile(0, 0, 1)
        try file.putAll([
            TileEntry(TileId(10, 1, 1), StoredTile(bytes: png, contentType: "image/png", storedAtMillis: 1000)),
            TileEntry(TileId(10, 1, 2), StoredTile(bytes: png, contentType: "image/png", storedAtMillis: 5000)),
            TileEntry(TileId(10, 1, 3), StoredTile(bytes: png, contentType: "image/png", storedAtMillis: StoredTile.unknownDateMillis)),
        ])
        let old = try #require(try file.get(TileId(10, 1, 1)))
        #expect(old.isStale(nowMillis: 10000, validityMillis: 5000))
        #expect(!old.isStale(nowMillis: 10000, validityMillis: nil))
        let imported = try #require(try file.get(TileId(10, 1, 3)))
        #expect(!imported.isStale(nowMillis: 10000, validityMillis: 1))
        #expect(try file.deleteOlderThan(3000) == 1)
        #expect(try file.tileCount() == 2)
        try file.clear()
        #expect(try file.tileCount() == 0)
    }

    @Test("a file SQLite cannot read is moved aside and replaced")
    func corruptFile() throws {
        let url = temporaryFile("bad.mbtiles")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(repeating: 0x42, count: 4096).write(to: url)
        let file = try MbTilesFile.open(url)
        #expect(try file.tileCount() == 0)
        #expect(FileManager.default.fileExists(atPath: url.path + ".corrupt"))
    }

    @Test("image types are read from the magic bytes")
    func contentTypes() throws {
        #expect(MbTilesFile.contentTypeOf(try solidTile(1, 1, 1)) == "image/png")
        #expect(MbTilesFile.contentTypeOf(Data([0xFF, 0xD8, 0xFF, 0xE0])) == "image/jpeg")
        #expect(MbTilesFile.contentTypeOf(Data("RIFF0000WEBPVP8 ".utf8)) == "image/webp")
        #expect(MbTilesFile.contentTypeOf(Data("<html>".utf8)) == nil)
    }
}

struct TileComposerTests {
    private func leaf(_ z: Int, _ x: Int, _ y: Int) -> TileRecipe {
        let source = TileSource(id: "s", name: "s")
        let level = LevelDef(zooms: try! ZoomSet.range(0, 20), servers: ["https://s/{$z}/{$x}/{$y}"])
        return .direct(source: source, level: level, tile: TileId(z, x, y))
    }

    @Test("four finer tiles are shrunk into one, row after row")
    func shrink() throws {
        let colours: [Int: Data] = [
            0: try solidTile(1, 0, 0), 1: try solidTile(0, 1, 0), 2: try solidTile(0, 0, 1), 3: try solidTile(1, 1, 1),
        ]
        let recipe = TileRecipe.shrink(parts: [leaf(6, 32, 22), leaf(6, 33, 22), leaf(6, 32, 23), leaf(6, 33, 23)], depth: 1)
        let png = try #require(TileComposer().compose(recipe) { leaf in
            colours[(leaf.tile.x - 32) + 2 * (leaf.tile.y - 22)]
        })
        #expect(try pixel(png, 60, 60) == [255, 0, 0])
        #expect(try pixel(png, 200, 60) == [0, 255, 0])
        #expect(try pixel(png, 60, 200) == [0, 0, 255])
        #expect(try pixel(png, 200, 200) == [255, 255, 255])
    }

    @Test("part of a coarser tile is enlarged to a whole tile; missing tiles give nothing")
    func enlarge() throws {
        // A tile red on its left half and blue on its right half: column 1 of 2 is blue.
        let context = try #require(TileComposer.context(256))
        context.setFillColor(red: 1, green: 0, blue: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: 128, height: 256))
        context.setFillColor(red: 0, green: 0, blue: 1, alpha: 1)
        context.fill(CGRect(x: 128, y: 0, width: 128, height: 256))
        let image = try #require(context.makeImage())
        let halves = try #require(TileComposer.png(image))
        let recipe = TileRecipe.enlarge(from: leaf(16, 2100, 1490), depth: 1, col: 1, row: 0)
        let png = try #require(TileComposer().compose(recipe) { _ in halves })
        #expect(try pixel(png, 10, 10) == [0, 0, 255])
        #expect(try pixel(png, 250, 250) == [0, 0, 255])
        #expect(TileComposer().compose(recipe) { _ in nil } == nil)
        #expect(TileComposer().compose(.none) { _ in halves } == nil)
    }
}
