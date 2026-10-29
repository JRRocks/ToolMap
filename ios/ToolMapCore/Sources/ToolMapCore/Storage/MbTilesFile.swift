// Port of data/src/main/kotlin/fr/toolmap/data/tiles/MbTilesFile.kt and StoredTile (TileStore.kt).

import Foundation

/// Raw tile bytes as received from the server, with the content type to answer with.
public struct StoredTile: Equatable, Sendable {
    /// Download date of tiles that carry none (files made by other tools): they never expire.
    public static let unknownDateMillis: Int64 = -1

    public let bytes: Data
    public let contentType: String
    public let storedAtMillis: Int64

    public init(bytes: Data, contentType: String, storedAtMillis: Int64 = StoredTile.nowMillis()) {
        self.bytes = bytes
        self.contentType = contentType
        self.storedAtMillis = storedAtMillis
    }

    /// True when the tile was downloaded more than `validityMillis` before `nowMillis` (nil = never expires).
    public func isStale(nowMillis: Int64, validityMillis: Int?) -> Bool {
        guard let validityMillis else { return false }
        return storedAtMillis != StoredTile.unknownDateMillis && nowMillis - storedAtMillis > Int64(validityMillis)
    }

    public static func nowMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }
}

/// How much of a tile's area is stored, see `MbTilesFile.coverage`.
public enum Coverage: Sendable {
    case none, partial, full
}

/// One tile to write with `MbTilesFile.putAll`.
public struct TileEntry: Sendable {
    public let tile: TileId
    public let stored: StoredTile

    public init(_ tile: TileId, _ stored: StoredTile) {
        self.tile = tile
        self.stored = stored
    }
}

/// One MBTiles file (ADR-003): the standard `metadata` and `tiles` tables (rows in TMS order), so QGIS and other tools
/// can open it, plus the annex table `tm_tile_info` that remembers when each tile was downloaded. All coordinates in
/// this API are XYZ (y from the top); the TMS flip happens here only. Safe to use from several threads.
public final class MbTilesFile: @unchecked Sendable {
    public static let defaultCoverageDepth = 3
    private static let maxZoom = 30
    private static let fallbackType = "image/png"

    public let url: URL
    private let db: SQLiteDatabase
    private let formatLock = NSLock()
    private var knownFormat: String?

    private init(url: URL, db: SQLiteDatabase) {
        self.url = url
        self.db = db
    }

    /// Opens `url`, creating the file and its tables when needed; a file SQLite cannot read is moved aside to
    /// `<name>.corrupt` and a new one is created.
    public static func open(_ url: URL, initialMetadata: [String: String] = [:]) throws -> MbTilesFile {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let db: SQLiteDatabase
        do {
            db = try openDatabase(url)
        } catch {
            let aside = URL(fileURLWithPath: url.path + ".corrupt")
            try? FileManager.default.removeItem(at: aside)
            try? FileManager.default.moveItem(at: url, to: aside)
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + "-wal"))
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + "-shm"))
            db = try openDatabase(url)
        }
        let file = MbTilesFile(url: url, db: db)
        let existing = try file.metadata()
        for (name, value) in initialMetadata where existing[name] == nil {
            try file.setMetadata(name, value)
        }
        return file
    }

    private static func openDatabase(_ url: URL) throws -> SQLiteDatabase {
        let db = try SQLiteDatabase(path: url.path)
        do {
            try db.execute("PRAGMA journal_mode=WAL")
            try db.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            try db.execute("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
            try db.execute(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)"
            )
            try db.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
            try db.execute(
                "CREATE TABLE IF NOT EXISTS tm_tile_info (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, " +
                    "stored_at INTEGER NOT NULL, PRIMARY KEY (zoom_level, tile_column, tile_row)) WITHOUT ROWID"
            )
            // Forces SQLite to read the file now, so a corrupt file fails here rather than later.
            _ = try db.query("SELECT COUNT(*) FROM sqlite_master")
            return db
        } catch {
            db.close()
            throw error
        }
    }

    public func close() {
        db.close()
    }

    public func get(_ tile: TileId) throws -> StoredTile? {
        let rows = try db.query(
            "SELECT t.tile_data, i.stored_at FROM tiles t LEFT JOIN tm_tile_info i ON i.zoom_level = t.zoom_level " +
                "AND i.tile_column = t.tile_column AND i.tile_row = t.tile_row " +
                "WHERE t.zoom_level = ? AND t.tile_column = ? AND t.tile_row = ?",
            try coordinates(tile)
        )
        guard let row = rows.first, let bytes = row[0].data else { return nil }
        // Tiles written by another tool have no download date: they never count as expired.
        let storedAt = row[1].int64 ?? StoredTile.unknownDateMillis
        return StoredTile(
            bytes: bytes,
            contentType: MbTilesFile.contentTypeOf(bytes) ?? formatContentType() ?? MbTilesFile.fallbackType,
            storedAtMillis: storedAt
        )
    }

    public func contains(_ tile: TileId) throws -> Bool {
        try !db.query(
            "SELECT 1 FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
            coordinates(tile)
        ).isEmpty
    }

    /// What is stored for the tile: the tile itself (`full`), or at least one of its descendants within `depth` zoom
    /// levels (`partial`), or nothing.
    public func coverage(_ tile: TileId, depth: Int = MbTilesFile.defaultCoverageDepth) throws -> Coverage {
        if try contains(tile) { return .full }
        for k in 1 ... Swift.max(depth, 1) where depth >= 1 {
            let level = tile.z + k
            if level > MbTilesFile.maxZoom { break }
            let span = 1 << k
            let rowLow = try TileMath.tmsY((tile.y + 1) * span - 1, level)
            let rowHigh = try TileMath.tmsY(tile.y * span, level)
            let found = try db.query(
                "SELECT 1 FROM tiles WHERE zoom_level = ? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ? LIMIT 1",
                [.int(level), .int(tile.x * span), .int((tile.x + 1) * span - 1), .int(rowLow), .int(rowHigh)]
            )
            if !found.isEmpty { return .partial }
        }
        return .none
    }

    public func put(_ tile: TileId, _ stored: StoredTile) throws {
        try putAll([TileEntry(tile, stored)])
    }

    /// Writes `entries` in one transaction, replacing tiles already stored unless `replace` is false.
    public func putAll(_ entries: [TileEntry], replace: Bool = true) throws {
        guard let first = entries.first else { return }
        let verb = replace ? "INSERT OR REPLACE" : "INSERT OR IGNORE"
        try db.transaction {
            for entry in entries {
                let row = try TileMath.tmsY(entry.tile.y, entry.tile.z)
                try db.run(
                    "\(verb) INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
                    [.int(entry.tile.z), .int(entry.tile.x), .int(row), .blob(entry.stored.bytes)]
                )
                try db.run(
                    "\(verb) INTO tm_tile_info (zoom_level, tile_column, tile_row, stored_at) VALUES (?, ?, ?, ?)",
                    [.int(entry.tile.z), .int(entry.tile.x), .int(row), .integer(entry.stored.storedAtMillis)]
                )
            }
        }
        rememberFormat(first.stored.bytes)
    }

    public func delete(_ tile: TileId) throws {
        let args = try coordinates(tile)
        try db.transaction {
            try db.run("DELETE FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?", args)
            try db.run("DELETE FROM tm_tile_info WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?", args)
        }
    }

    /// Number of tiles stored.
    public func tileCount() throws -> Int {
        Int(try db.query("SELECT COUNT(*) FROM tiles").first?.first?.int64 ?? 0)
    }

    /// Number of tiles stored at each zoom level.
    public func tileCountByZoom() throws -> [Int: Int] {
        var out: [Int: Int] = [:]
        for row in try db.query("SELECT zoom_level, COUNT(*) FROM tiles GROUP BY zoom_level ORDER BY zoom_level") {
            if let z = row[0].int64, let count = row[1].int64 { out[Int(z)] = Int(count) }
        }
        return out
    }

    /// Sum of the tile payload sizes in bytes (a full scan: for storage screens, not per tile).
    public func payloadBytes() throws -> Int64 {
        try db.query("SELECT COALESCE(SUM(LENGTH(tile_data)), 0) FROM tiles").first?.first?.int64 ?? 0
    }

    /// Size of the files on disk, including the write-ahead log.
    public func diskBytes() -> Int64 {
        func size(_ path: String) -> Int64 {
            ((try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? NSNumber)?.int64Value ?? 0
        }
        return size(url.path) + size(url.path + "-wal")
    }

    /// Which tiles of zoom `z` inside the inclusive XYZ ranges are stored.
    public func storedTiles(_ z: Int, x: ClosedRange<Int>, y: ClosedRange<Int>) throws -> Set<TileId> {
        let rowLow = try TileMath.tmsY(y.upperBound, z)
        let rowHigh = try TileMath.tmsY(y.lowerBound, z)
        var out: Set<TileId> = []
        for row in try db.query(
            "SELECT tile_column, tile_row FROM tiles WHERE zoom_level = ? AND tile_column BETWEEN ? AND ? " +
                "AND tile_row BETWEEN ? AND ?",
            [.int(z), .int(x.lowerBound), .int(x.upperBound), .int(rowLow), .int(rowHigh)]
        ) {
            if let column = row[0].int64, let tmsRow = row[1].int64 {
                let y = try TileMath.tmsY(Int(tmsRow), z)
                out.insert(TileId(z, Int(column), y))
            }
        }
        return out
    }

    /// How many tiles of zoom `z` inside the inclusive XYZ ranges are stored (the zone estimate).
    public func countTiles(_ z: Int, x: ClosedRange<Int>, y: ClosedRange<Int>) throws -> Int {
        let rowLow = try TileMath.tmsY(y.upperBound, z)
        let rowHigh = try TileMath.tmsY(y.lowerBound, z)
        let rows = try db.query(
            "SELECT COUNT(*) FROM tiles WHERE zoom_level = ? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
            [.int(z), .int(x.lowerBound), .int(x.upperBound), .int(rowLow), .int(rowHigh)]
        )
        return Int(rows.first?.first?.int64 ?? 0)
    }

    /// Deletes every tile of the zooms `zooms` whose area intersects `area` (degrees). Returns how many.
    @discardableResult
    public func deleteInArea(_ area: Bounds, zooms: ClosedRange<Int>) throws -> Int {
        try db.transaction {
            var removed = 0
            for z in zooms {
                let a = try TileMath.tileAt(area.minX, area.maxY, z)
                let b = try TileMath.tileAt(area.maxX, area.minY, z)
                let rowLow = try TileMath.tmsY(b.y, z)
                let rowHigh = try TileMath.tmsY(a.y, z)
                let args: [SQLiteDatabase.Value] = [.int(z), .int(a.x), .int(b.x), .int(rowLow), .int(rowHigh)]
                let filter = "zoom_level = ? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?"
                removed += try db.run("DELETE FROM tiles WHERE " + filter, args)
                try db.run("DELETE FROM tm_tile_info WHERE " + filter, args)
            }
            return removed
        }
    }

    /// Deletes tiles downloaded before `cutoffMillis`; tiles with an unknown date (imported ones) are kept.
    @discardableResult
    public func deleteOlderThan(_ cutoffMillis: Int64) throws -> Int {
        try db.transaction {
            let stale = try db.run(
                "DELETE FROM tiles WHERE EXISTS (SELECT 1 FROM tm_tile_info i WHERE i.zoom_level = tiles.zoom_level " +
                    "AND i.tile_column = tiles.tile_column AND i.tile_row = tiles.tile_row " +
                    "AND i.stored_at >= 0 AND i.stored_at < ?)",
                [.integer(cutoffMillis)]
            )
            try db.run("DELETE FROM tm_tile_info WHERE stored_at >= 0 AND stored_at < ?", [.integer(cutoffMillis)])
            return stale
        }
    }

    /// Empties the file (metadata is kept).
    public func clear() throws {
        try db.transaction {
            try db.run("DELETE FROM tiles")
            try db.run("DELETE FROM tm_tile_info")
        }
    }

    public func metadata() throws -> [String: String] {
        var out: [String: String] = [:]
        for row in try db.query("SELECT name, value FROM metadata") {
            if let name = row[0].string { out[name] = row[1].string ?? "" }
        }
        return out
    }

    public func setMetadata(_ name: String, _ value: String) throws {
        try db.run("INSERT OR REPLACE INTO metadata (name, value) VALUES (?, ?)", [.text(name), .text(value)])
    }

    /// Folds the write-ahead log into the main file so it can be copied alone (export, backup).
    public func checkpoint() throws {
        _ = try db.query("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private func coordinates(_ tile: TileId) throws -> [SQLiteDatabase.Value] {
        let row = try TileMath.tmsY(tile.y, tile.z)
        return [.int(tile.z), .int(tile.x), .int(row)]
    }

    private func rememberFormat(_ bytes: Data) {
        guard let type = MbTilesFile.contentTypeOf(bytes) else { return }
        formatLock.lock()
        let unknown = knownFormat == nil
        knownFormat = type
        formatLock.unlock()
        if unknown, let format = type.split(separator: "/").last {
            try? setMetadata("format", format == "jpeg" ? "jpg" : String(format))
        }
    }

    private func formatContentType() -> String? {
        formatLock.lock()
        let known = knownFormat
        formatLock.unlock()
        if let known { return known }
        guard let format = (try? metadata())?["format"] else { return nil }
        switch format.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp": return "image/webp"
        default: return nil
        }
    }

    /// Content type of an image payload from its magic bytes, or nil when it is not PNG, JPEG or WebP.
    public static func contentTypeOf(_ bytes: Data) -> String? {
        let b = [UInt8](bytes.prefix(12))
        if b.count >= 4, b[0] == 0x89, b[1] == 0x50, b[2] == 0x4E, b[3] == 0x47 { return "image/png" }
        if b.count >= 3, b[0] == 0xFF, b[1] == 0xD8, b[2] == 0xFF { return "image/jpeg" }
        if b.count >= 12, b[0 ..< 4] == [0x52, 0x49, 0x46, 0x46], b[8 ..< 12] == [0x57, 0x45, 0x42, 0x50] {
            return "image/webp"
        }
        return nil
    }
}
