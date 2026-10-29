// Port of data/src/main/kotlin/fr/toolmap/data/tracks/TrackDatabase.kt (Android Room database, version 1).

import Foundation

/// The SQLite file of the library. Its schema is the one Room writes on Android (statements copied from
/// data/schemas/fr.toolmap.data.tracks.TrackDatabase/1.json), with Room's `room_master_table` and the database version
/// in `PRAGMA user_version` as Room keeps it: a `tracks.db` of either app opens in the other, and a later version of
/// the schema migrates from `user_version`.
public enum TrackDatabase {
    public static let fileName = "tracks.db"
    /// Room's `@Database(version = 1)`, kept in `PRAGMA user_version`.
    public static let version = 1
    /// Room's `identityHash` of schema version 1: Room on Android refuses a file whose hash differs.
    static let identityHash = "edd986eec0fa9bb18756aad5db9e14a6"

    /// Room's statements, one per line, with the table names filled in.
    static let createStatements: [String] = [
        "CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `parentId` INTEGER, `name` TEXT NOT NULL, `visible` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `namespacesJson` TEXT, FOREIGN KEY(`parentId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_folders_parentId` ON `folders` (`parentId`)",
        "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` INTEGER, `name` TEXT, `cmt` TEXT, `desc` TEXT, `src` TEXT, `number` INTEGER, `type` TEXT, `linksJson` TEXT, `extrasJson` TEXT, `colorArgb` INTEGER NOT NULL, `widthDp` REAL NOT NULL, `dashed` INTEGER NOT NULL, `arrows` INTEGER NOT NULL, `visible` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `format` TEXT, `pointCount` INTEGER NOT NULL, `segmentCount` INTEGER NOT NULL, `minLat` REAL NOT NULL, `minLon` REAL NOT NULL, `maxLat` REAL NOT NULL, `maxLon` REAL NOT NULL, `distanceMeters` REAL NOT NULL, `ascentMeters` REAL, `descentMeters` REAL, `startTimeMillis` INTEGER, `endTimeMillis` INTEGER, FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_tracks_folderId` ON `tracks` (`folderId`)",
        "CREATE TABLE IF NOT EXISTS `track_chunks` (`trackId` INTEGER NOT NULL, `segment` INTEGER NOT NULL, `chunk` INTEGER NOT NULL, `pointCount` INTEGER NOT NULL, `data` BLOB NOT NULL, PRIMARY KEY(`trackId`, `segment`, `chunk`), FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `routes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` INTEGER, `name` TEXT, `cmt` TEXT, `desc` TEXT, `src` TEXT, `number` INTEGER, `type` TEXT, `linksJson` TEXT, `extrasJson` TEXT, `colorArgb` INTEGER NOT NULL, `widthDp` REAL NOT NULL, `dashed` INTEGER NOT NULL, `arrows` INTEGER NOT NULL, `visible` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `format` TEXT, `pointCount` INTEGER NOT NULL, `minLat` REAL NOT NULL, `minLon` REAL NOT NULL, `maxLat` REAL NOT NULL, `maxLon` REAL NOT NULL, `distanceMeters` REAL NOT NULL, `pointsJson` TEXT NOT NULL, FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_routes_folderId` ON `routes` (`folderId`)",
        "CREATE TABLE IF NOT EXISTS `waypoints` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` INTEGER, `name` TEXT, `cmt` TEXT, `desc` TEXT, `src` TEXT, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `ele` REAL, `timeMillis` INTEGER, `sym` TEXT, `type` TEXT, `linksJson` TEXT, `extrasJson` TEXT, `visible` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `format` TEXT, FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_waypoints_folderId` ON `waypoints` (`folderId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'edd986eec0fa9bb18756aad5db9e14a6')",
    ]

    /// Turns the foreign keys on (Room does it on every opening: the cascades delete the contents of a folder and the
    /// points of a track) and creates the schema of a new file. A file of a newer version is refused.
    static func prepare(_ db: SQLiteDatabase) throws {
        try db.execute("PRAGMA foreign_keys = ON")
        let rows = try db.query("PRAGMA user_version")
        let current = rows.first?.first?.int64 ?? 0
        if current > Int64(version) {
            throw SQLiteError(message: "\(fileName) is of version \(current), newer than \(version)")
        }
        if current == Int64(version) { return }
        try db.transaction {
            for sql in createStatements {
                try db.execute(sql)
            }
            try db.execute("PRAGMA user_version = \(version)")
        }
    }
}
