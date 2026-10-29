import Foundation
#if canImport(SQLite3)
    import SQLite3
#endif

/// The SQLite error message of a failed call.
public struct SQLiteError: Error, CustomStringConvertible {
    public let message: String
    public var description: String { message }
}

/// A small wrapper of the SQLite C library: one connection, every call under one lock (safe from several threads).
/// The iOS counterpart of Android's `SQLiteDatabase` for the MBTiles files and the library of tracks.
public final class SQLiteDatabase: @unchecked Sendable {
    /// One value bound to a `?` or read from a column.
    public enum Value: Equatable, Sendable {
        case null
        case integer(Int64)
        case real(Double)
        case text(String)
        case blob(Data)

        public var int64: Int64? {
            switch self {
            case let .integer(value): value
            case let .real(value): Int64(value)
            default: nil
            }
        }

        public var double: Double? {
            switch self {
            case let .integer(value): Double(value)
            case let .real(value): value
            default: nil
            }
        }

        public var string: String? {
            switch self {
            case let .text(value): value
            case let .integer(value): String(value)
            case let .real(value): String(value)
            default: nil
            }
        }

        public var data: Data? {
            switch self {
            case let .blob(value): value
            case let .text(value): Data(value.utf8)
            default: nil
            }
        }
    }

    public let path: String
    private var handle: OpaquePointer?
    private let lock = NSRecursiveLock()

    public init(path: String) throws {
        self.path = path
        var opened: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(path, &opened, flags, nil) == SQLITE_OK, let connection = opened else {
            let message = opened.map { String(cString: sqlite3_errmsg($0)) } ?? "cannot open \(path)"
            sqlite3_close(opened)
            throw SQLiteError(message: message)
        }
        handle = connection
        sqlite3_busy_timeout(connection, 5000)
    }

    deinit {
        close()
    }

    public func close() {
        lock.lock()
        defer { lock.unlock() }
        if let handle {
            sqlite3_close_v2(handle)
        }
        handle = nil
    }

    /// Runs one or more statements without parameters.
    public func execute(_ sql: String) throws {
        lock.lock()
        defer { lock.unlock() }
        var error: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(handle, sql, nil, nil, &error) != SQLITE_OK {
            let message = error.map { String(cString: $0) } ?? "SQLite error"
            sqlite3_free(error)
            throw SQLiteError(message: message)
        }
    }

    /// Runs one statement with `values` bound in order; returns the number of rows it changed.
    @discardableResult
    public func run(_ sql: String, _ values: [Value] = []) throws -> Int {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(sql, values)
        defer { sqlite3_finalize(statement) }
        let result = sqlite3_step(statement)
        guard result == SQLITE_DONE || result == SQLITE_ROW else { throw lastError() }
        return Int(sqlite3_changes(handle))
    }

    /// The rows of a query, each as its column values.
    public func query(_ sql: String, _ values: [Value] = []) throws -> [[Value]] {
        lock.lock()
        defer { lock.unlock() }
        let statement = try prepare(sql, values)
        defer { sqlite3_finalize(statement) }
        var rows: [[Value]] = []
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { break }
            guard result == SQLITE_ROW else { throw lastError() }
            let count = sqlite3_column_count(statement)
            var row: [Value] = []
            row.reserveCapacity(Int(count))
            for column in 0 ..< count {
                row.append(value(statement, column))
            }
            rows.append(row)
        }
        return rows
    }

    /// Runs `body` in one transaction, rolled back when it throws.
    public func transaction<T>(_ body: () throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    public var lastInsertRowId: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return sqlite3_last_insert_rowid(handle)
    }

    private func prepare(_ sql: String, _ values: [Value]) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else { throw lastError() }
        // SQLITE_TRANSIENT: SQLite copies the bound text and bytes.
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, value) in values.enumerated() {
            let position = Int32(index + 1)
            let result: Int32
            switch value {
            case .null:
                result = sqlite3_bind_null(statement, position)
            case let .integer(number):
                result = sqlite3_bind_int64(statement, position, number)
            case let .real(number):
                result = sqlite3_bind_double(statement, position, number)
            case let .text(text):
                result = sqlite3_bind_text(statement, position, text, -1, transient)
            case let .blob(data):
                result = data.withUnsafeBytes { bytes in
                    sqlite3_bind_blob(statement, position, bytes.baseAddress, Int32(bytes.count), transient)
                }
            }
            guard result == SQLITE_OK else {
                sqlite3_finalize(statement)
                throw lastError()
            }
        }
        return statement
    }

    private func value(_ statement: OpaquePointer?, _ column: Int32) -> Value {
        switch sqlite3_column_type(statement, column) {
        case SQLITE_INTEGER:
            return .integer(sqlite3_column_int64(statement, column))
        case SQLITE_FLOAT:
            return .real(sqlite3_column_double(statement, column))
        case SQLITE_TEXT:
            guard let text = sqlite3_column_text(statement, column) else { return .text("") }
            return .text(String(cString: text))
        case SQLITE_BLOB:
            let count = Int(sqlite3_column_bytes(statement, column))
            guard count > 0, let bytes = sqlite3_column_blob(statement, column) else { return .blob(Data()) }
            return .blob(Data(bytes: bytes, count: count))
        default:
            return .null
        }
    }

    private func lastError() -> SQLiteError {
        SQLiteError(message: handle.map { String(cString: sqlite3_errmsg($0)) } ?? "SQLite error")
    }
}

extension SQLiteDatabase.Value {
    public static func int(_ value: Int) -> SQLiteDatabase.Value { .integer(Int64(value)) }
}
