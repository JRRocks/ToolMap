// The rows of `tracks.db` read and written by column, as Room's generated DAO code does on Android
// (data/src/main/kotlin/fr/toolmap/data/tracks/TrackDao.kt). Booleans are stored as 0 / 1, `Float` widths as REAL,
// colours as signed 32-bit integers, exactly as Room stores them.

import Foundation

/// The column values of one row, read by position (the order of the entity's `columns`).
struct LibraryRow {
    let values: [SQLiteDatabase.Value]

    init(_ values: [SQLiteDatabase.Value]) {
        self.values = values
    }

    private func value(_ index: Int) -> SQLiteDatabase.Value {
        index < values.count ? values[index] : .null
    }

    private func isNull(_ index: Int) -> Bool {
        if case .null = value(index) { return true }
        return false
    }

    func int64(_ index: Int) -> Int64 {
        value(index).int64 ?? 0
    }

    func optionalInt64(_ index: Int) -> Int64? {
        isNull(index) ? nil : value(index).int64
    }

    func int(_ index: Int) -> Int {
        Int(truncatingIfNeeded: int64(index))
    }

    func optionalInt(_ index: Int) -> Int? {
        guard let number = optionalInt64(index) else { return nil }
        return Int(truncatingIfNeeded: number)
    }

    func int32(_ index: Int) -> Int32 {
        Int32(truncatingIfNeeded: int64(index))
    }

    func bool(_ index: Int) -> Bool {
        int64(index) != 0
    }

    func double(_ index: Int) -> Double {
        value(index).double ?? 0.0
    }

    func optionalDouble(_ index: Int) -> Double? {
        isNull(index) ? nil : value(index).double
    }

    func float(_ index: Int) -> Float {
        Float(double(index))
    }

    func string(_ index: Int) -> String {
        value(index).string ?? ""
    }

    func optionalString(_ index: Int) -> String? {
        isNull(index) ? nil : value(index).string
    }

    func data(_ index: Int) -> Data {
        value(index).data ?? Data()
    }
}

/// SQL values of the Kotlin column types.
enum LibrarySql {
    static func integer(_ value: Int64?) -> SQLiteDatabase.Value {
        guard let value else { return .null }
        return .integer(value)
    }

    static func int(_ value: Int?) -> SQLiteDatabase.Value {
        guard let value else { return .null }
        return .integer(Int64(value))
    }

    static func color(_ value: Int32) -> SQLiteDatabase.Value {
        .integer(Int64(value))
    }

    static func bool(_ value: Bool) -> SQLiteDatabase.Value {
        .integer(value ? 1 : 0)
    }

    static func real(_ value: Double?) -> SQLiteDatabase.Value {
        guard let value else { return .null }
        return .real(value)
    }

    static func float(_ value: Float) -> SQLiteDatabase.Value {
        .real(Double(value))
    }

    static func text(_ value: String?) -> SQLiteDatabase.Value {
        guard let value else { return .null }
        return .text(value)
    }

    /// `name` between backticks, as Room writes column and table names (`desc` is an SQL keyword).
    static func quoted(_ name: String) -> String {
        "`" + name + "`"
    }
}

/// A row of a table whose first column is the `id` primary key that SQLite gives on insert.
protocol LibraryRecord: Sendable {
    static var table: String { get }
    /// Every column, `id` first, in the order of `columnValues` and of `init(row:)`.
    static var columns: [String] { get }
    var id: Int64 { get }
    var columnValues: [SQLiteDatabase.Value] { get }
    init(row: LibraryRow)
}

extension LibraryRecord {
    static var selectSql: String {
        let list = columns.map { LibrarySql.quoted($0) }.joined(separator: ", ")
        return "SELECT \(list) FROM \(LibrarySql.quoted(table))"
    }

    /// Every column but `id`, which SQLite chooses (Room's `autoGenerate`).
    static var insertSql: String {
        let names = columns.dropFirst()
        let list = names.map { LibrarySql.quoted($0) }.joined(separator: ", ")
        let marks = Array(repeating: "?", count: names.count).joined(separator: ", ")
        return "INSERT INTO \(LibrarySql.quoted(table)) (\(list)) VALUES (\(marks))"
    }

    /// Every column but `id`, then the `id` of the row.
    static var updateSql: String {
        let list = columns.dropFirst().map { LibrarySql.quoted($0) + " = ?" }.joined(separator: ", ")
        return "UPDATE \(LibrarySql.quoted(table)) SET \(list) WHERE `id` = ?"
    }
}

extension FolderEntity: LibraryRecord {
    static let table = "folders"
    static let columns = ["id", "parentId", "name", "visible", "createdAt", "namespacesJson"]

    var columnValues: [SQLiteDatabase.Value] {
        [
            .integer(id),
            LibrarySql.integer(parentId),
            .text(name),
            LibrarySql.bool(visible),
            .integer(createdAt),
            LibrarySql.text(namespacesJson),
        ]
    }

    init(row: LibraryRow) {
        self.init(
            id: row.int64(0),
            parentId: row.optionalInt64(1),
            name: row.string(2),
            visible: row.bool(3),
            createdAt: row.int64(4),
            namespacesJson: row.optionalString(5)
        )
    }
}

extension TrackEntity: LibraryRecord {
    static let table = "tracks"
    static let columns = [
        "id", "folderId", "name", "cmt", "desc", "src", "number", "type", "linksJson", "extrasJson", "colorArgb",
        "widthDp", "dashed", "arrows", "visible", "createdAt", "format", "pointCount", "segmentCount", "minLat",
        "minLon", "maxLat", "maxLon", "distanceMeters", "ascentMeters", "descentMeters", "startTimeMillis",
        "endTimeMillis",
    ]

    var columnValues: [SQLiteDatabase.Value] {
        [
            .integer(id),
            LibrarySql.integer(folderId),
            LibrarySql.text(name),
            LibrarySql.text(cmt),
            LibrarySql.text(desc),
            LibrarySql.text(src),
            LibrarySql.int(number),
            LibrarySql.text(type),
            LibrarySql.text(linksJson),
            LibrarySql.text(extrasJson),
            LibrarySql.color(colorArgb),
            LibrarySql.float(widthDp),
            LibrarySql.bool(dashed),
            LibrarySql.bool(arrows),
            LibrarySql.bool(visible),
            .integer(createdAt),
            LibrarySql.text(format),
            LibrarySql.int(pointCount),
            LibrarySql.int(segmentCount),
            .real(minLat),
            .real(minLon),
            .real(maxLat),
            .real(maxLon),
            .real(distanceMeters),
            LibrarySql.real(ascentMeters),
            LibrarySql.real(descentMeters),
            LibrarySql.integer(startTimeMillis),
            LibrarySql.integer(endTimeMillis),
        ]
    }

    init(row: LibraryRow) {
        self.init(
            id: row.int64(0),
            folderId: row.optionalInt64(1),
            name: row.optionalString(2),
            cmt: row.optionalString(3),
            desc: row.optionalString(4),
            src: row.optionalString(5),
            number: row.optionalInt(6),
            type: row.optionalString(7),
            linksJson: row.optionalString(8),
            extrasJson: row.optionalString(9),
            colorArgb: row.int32(10),
            widthDp: row.float(11),
            dashed: row.bool(12),
            arrows: row.bool(13),
            visible: row.bool(14),
            createdAt: row.int64(15),
            format: row.optionalString(16),
            pointCount: row.int(17),
            segmentCount: row.int(18),
            minLat: row.double(19),
            minLon: row.double(20),
            maxLat: row.double(21),
            maxLon: row.double(22),
            distanceMeters: row.double(23),
            ascentMeters: row.optionalDouble(24),
            descentMeters: row.optionalDouble(25),
            startTimeMillis: row.optionalInt64(26),
            endTimeMillis: row.optionalInt64(27)
        )
    }
}

extension RouteEntity: LibraryRecord {
    static let table = "routes"
    static let columns = [
        "id", "folderId", "name", "cmt", "desc", "src", "number", "type", "linksJson", "extrasJson", "colorArgb",
        "widthDp", "dashed", "arrows", "visible", "createdAt", "format", "pointCount", "minLat", "minLon", "maxLat",
        "maxLon", "distanceMeters", "pointsJson",
    ]

    var columnValues: [SQLiteDatabase.Value] {
        [
            .integer(id),
            LibrarySql.integer(folderId),
            LibrarySql.text(name),
            LibrarySql.text(cmt),
            LibrarySql.text(desc),
            LibrarySql.text(src),
            LibrarySql.int(number),
            LibrarySql.text(type),
            LibrarySql.text(linksJson),
            LibrarySql.text(extrasJson),
            LibrarySql.color(colorArgb),
            LibrarySql.float(widthDp),
            LibrarySql.bool(dashed),
            LibrarySql.bool(arrows),
            LibrarySql.bool(visible),
            .integer(createdAt),
            LibrarySql.text(format),
            LibrarySql.int(pointCount),
            .real(minLat),
            .real(minLon),
            .real(maxLat),
            .real(maxLon),
            .real(distanceMeters),
            .text(pointsJson),
        ]
    }

    init(row: LibraryRow) {
        self.init(
            id: row.int64(0),
            folderId: row.optionalInt64(1),
            name: row.optionalString(2),
            cmt: row.optionalString(3),
            desc: row.optionalString(4),
            src: row.optionalString(5),
            number: row.optionalInt(6),
            type: row.optionalString(7),
            linksJson: row.optionalString(8),
            extrasJson: row.optionalString(9),
            colorArgb: row.int32(10),
            widthDp: row.float(11),
            dashed: row.bool(12),
            arrows: row.bool(13),
            visible: row.bool(14),
            createdAt: row.int64(15),
            format: row.optionalString(16),
            pointCount: row.int(17),
            minLat: row.double(18),
            minLon: row.double(19),
            maxLat: row.double(20),
            maxLon: row.double(21),
            distanceMeters: row.double(22),
            pointsJson: row.string(23)
        )
    }
}

extension WaypointEntity: LibraryRecord {
    static let table = "waypoints"
    static let columns = [
        "id", "folderId", "name", "cmt", "desc", "src", "lat", "lon", "ele", "timeMillis", "sym", "type", "linksJson",
        "extrasJson", "visible", "createdAt", "format",
    ]

    var columnValues: [SQLiteDatabase.Value] {
        [
            .integer(id),
            LibrarySql.integer(folderId),
            LibrarySql.text(name),
            LibrarySql.text(cmt),
            LibrarySql.text(desc),
            LibrarySql.text(src),
            .real(lat),
            .real(lon),
            LibrarySql.real(ele),
            LibrarySql.integer(timeMillis),
            LibrarySql.text(sym),
            LibrarySql.text(type),
            LibrarySql.text(linksJson),
            LibrarySql.text(extrasJson),
            LibrarySql.bool(visible),
            .integer(createdAt),
            LibrarySql.text(format),
        ]
    }

    init(row: LibraryRow) {
        self.init(
            id: row.int64(0),
            folderId: row.optionalInt64(1),
            name: row.optionalString(2),
            cmt: row.optionalString(3),
            desc: row.optionalString(4),
            src: row.optionalString(5),
            lat: row.double(6),
            lon: row.double(7),
            ele: row.optionalDouble(8),
            timeMillis: row.optionalInt64(9),
            sym: row.optionalString(10),
            type: row.optionalString(11),
            linksJson: row.optionalString(12),
            extrasJson: row.optionalString(13),
            visible: row.bool(14),
            createdAt: row.int64(15),
            format: row.optionalString(16)
        )
    }
}

extension TrackChunkEntity {
    static let table = "track_chunks"
    static let columns = ["trackId", "segment", "chunk", "pointCount", "data"]

    static var selectSql: String {
        let list = columns.map { LibrarySql.quoted($0) }.joined(separator: ", ")
        return "SELECT \(list) FROM \(LibrarySql.quoted(table))"
    }

    /// Room's `OnConflictStrategy.REPLACE`: the last chunk of a segment is rewritten in place by the recorder.
    static var insertSql: String {
        let list = columns.map { LibrarySql.quoted($0) }.joined(separator: ", ")
        return "INSERT OR REPLACE INTO \(LibrarySql.quoted(table)) (\(list)) VALUES (?, ?, ?, ?, ?)"
    }

    var columnValues: [SQLiteDatabase.Value] {
        [.integer(trackId), LibrarySql.int(segment), LibrarySql.int(chunk), LibrarySql.int(pointCount), .blob(data)]
    }

    init(row: LibraryRow) {
        self.init(
            trackId: row.int64(0),
            segment: row.int(1),
            chunk: row.int(2),
            pointCount: row.int(3),
            data: row.data(4)
        )
    }
}
