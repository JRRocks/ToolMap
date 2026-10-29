import Foundation

// Port of core/src/main/kotlin/fr/toolmap/core/backup/BackupModel.kt (format: docs/specs/backup-format.md). The
// manifest and the restore report are the JSON kotlinx.serialization writes on Android: the same keys, a missing key
// gives its default, unknown keys are ignored, and a null or a value of the wrong type makes the file unreadable.

/// Why a backup could not be written, read or applied.
public enum BackupFailure: String, CaseIterable, Sendable {
    /// The file is not a ToolMap backup (not a zip, no manifest first, wrong application).
    case notABackup = "NOT_A_BACKUP"
    /// Made by a newer version of the format than this one reads.
    case unsupportedVersion = "UNSUPPORTED_VERSION"
    /// A size or a checksum does not match, an entry is missing or not listed, a file changed while it was copied.
    case corrupt = "CORRUPT"
    /// An entry would land outside the places a backup may write to.
    case unsafe = "UNSAFE"
    /// The offline maps are on a removable card that is not mounted: they can be neither read nor written (ADR-042).
    case tilesUnavailable = "TILES_UNAVAILABLE"
    case noSpace = "NO_SPACE"
    case io = "IO"
}

/// Kotlin `BackupException`.
public struct BackupError: Error, CustomStringConvertible {
    public let failure: BackupFailure
    public let message: String
    public let cause: (any Error)?

    public init(_ failure: BackupFailure, _ message: String, cause: (any Error)? = nil) {
        self.failure = failure
        self.message = message
        self.cause = cause
    }

    public var description: String { message }
}

/// What a backup covers. A restore replaces the whole of each area the backup lists, and leaves the others alone.
public enum BackupArea: String, CaseIterable, Sendable {
    /// The tracks, routes, waypoints and folders.
    case database
    case settings
    case alerts
    /// The camera and layer stack of the last session.
    case state
    /// The map sources the user imported or created (`.aqx`).
    case sources
    case workspaces
    case print
    /// The offline maps (`.mbtiles`).
    case tiles

    /// The name of the area in the manifest.
    public var id: String { rawValue }

    /// Left out of a backup unless asked for (big).
    public var optional: Bool { self == .tiles }

    public static func of(_ id: String) -> BackupArea? {
        allCases.first { $0.id == id }
    }
}

/// Which files a backup may hold and where each belongs. Everything else is refused, so that a crafted archive cannot
/// write anywhere else (path traversal) nor put the secret key of the position sharing (`p2p/`) or a half-written
/// recording (`recording/`) back. Paths use `/`, are relative, and have at most one folder level.
public enum BackupPaths {
    public static let manifest = "manifest.json"
    public static let databaseFile = "tracks.db"
    public static let settingsFile = "settings.json"
    public static let alertsFile = "alerts.json"

    /// The built-in favorites the user deleted (ADR-052), kept beside the saved ones in `workspaces/`.
    public static let hiddenBuiltinsFile = "hidden-builtins.txt"
    private static let maxName = 200
    private static let space: UInt16 = 0x20
    private static let backslash: UInt16 = 0x5C

    /// The area of an allowed path, nil for any other.
    public static func areaOf(_ path: String) -> BackupArea? {
        if !isSafe(path) { return nil }
        let parts = kotlinSplit(path, "/")
        switch parts.count {
        case 1:
            switch path {
            case databaseFile: return .database
            case settingsFile: return .settings
            case alertsFile: return .alerts
            default: return nil
            }
        case 2:
            let folder = parts[0]
            let name = parts[1]
            if folder == "state", name.hasSuffix(".json") { return .state }
            if folder == "sources", name.hasSuffix(".aqx") { return .sources }
            if folder == "workspaces", name.hasSuffix(".json") || name == hiddenBuiltinsFile { return .workspaces }
            if folder == "print", name.hasSuffix(".json") { return .print }
            if folder == "tiles", name.hasSuffix(".mbtiles") { return .tiles }
            return nil
        default:
            return nil
        }
    }

    /// Relative, forward slashes, no `.` or `..` segment, no empty segment, no control character or backslash.
    public static func isSafe(_ path: String) -> Bool {
        if path.isEmpty || path.hasPrefix("/") || path.hasSuffix("/") { return false }
        return kotlinSplit(path, "/").allSatisfy { segment in
            let units = Array(segment.utf16)
            return !units.isEmpty &&
                segment != "." &&
                segment != ".." &&
                units.count <= maxName &&
                units[0] != 0x2E && // '.'
                !units.contains { $0 == backslash || $0 < space }
        }
    }
}

/// One file of a backup.
public struct BackupEntry: Equatable, Hashable, Sendable, Codable {
    public let path: String
    public let size: Int64
    /// Lower-case hexadecimal SHA-256 of the content.
    public let sha256: String

    public init(_ path: String, _ size: Int64, _ sha256: String) {
        self.path = path
        self.size = size
        self.sha256 = sha256
    }

    private enum CodingKeys: String, CodingKey {
        case path, size, sha256
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        path = try c.decode(String.self, forKey: .path)
        size = try c.decode(Int64.self, forKey: .size)
        sha256 = try c.decode(String.self, forKey: .sha256)
    }
}

/// The first entry of a backup (`manifest.json`): what the archive holds, with a checksum of each file, so that a
/// restore can check everything before it touches anything.
public struct BackupManifest: Equatable, Hashable, Sendable, Codable {
    /// Kotlin `BackupManifest.FORMAT`: the version of the format this app writes and reads.
    public static let currentFormat = 1
    /// Kotlin `BackupManifest.APP`.
    public static let appName = "ToolMap"

    public let format: Int
    public let app: String
    public let appVersion: String
    public let createdAtMillis: Int64
    /// The `BackupArea.id`s the backup covers, empty ones included.
    public let areas: [String]
    public let entries: [BackupEntry]

    public init(
        format: Int = BackupManifest.currentFormat,
        app: String = BackupManifest.appName,
        appVersion: String = "",
        createdAtMillis: Int64 = 0,
        areas: [String] = [],
        entries: [BackupEntry] = []
    ) {
        self.format = format
        self.app = app
        self.appVersion = appVersion
        self.createdAtMillis = createdAtMillis
        self.areas = areas
        self.entries = entries
    }

    public var totalBytes: Int64 { entries.reduce(0) { $0 + $1.size } }

    public func covers(_ area: BackupArea) -> Bool { areas.contains(area.id) }

    public func fileCount(_ area: BackupArea) -> Int {
        entries.filter { BackupPaths.areaOf($0.path) == area }.count
    }

    private enum CodingKeys: String, CodingKey {
        case format, app, appVersion, createdAtMillis, areas, entries
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = BackupManifest()
        format = try c.backupValue(.format, d.format)
        app = try c.backupValue(.app, d.app)
        appVersion = try c.backupValue(.appVersion, d.appVersion)
        createdAtMillis = try c.backupValue(.createdAtMillis, d.createdAtMillis)
        areas = try c.backupValue(.areas, d.areas)
        entries = try c.backupValue(.entries, d.entries)
    }
}

/// Reads, writes and checks `manifest.json`.
public enum BackupManifestCodec {
    private static let maxEntries = 100_000
    private static let maxEntryBytes: Int64 = 64 * 1024 * 1024 * 1024

    public static func encode(_ manifest: BackupManifest) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        let data = try encoder.encode(manifest)
        return String(decoding: data, as: UTF8.self)
    }

    /// Throws `BackupError` when `text` is not a manifest this version can apply.
    public static func decode(_ text: String) throws -> BackupManifest {
        let manifest: BackupManifest
        do {
            manifest = try JSONDecoder().decode(BackupManifest.self, from: Data(text.utf8))
        } catch {
            throw BackupError(.notABackup, "Unreadable manifest", cause: error)
        }
        try validate(manifest)
        return manifest
    }

    public static func validate(_ manifest: BackupManifest) throws {
        if manifest.app != BackupManifest.appName {
            throw BackupError(.notABackup, "Not a ToolMap backup")
        }
        if manifest.format > BackupManifest.currentFormat {
            throw BackupError(.unsupportedVersion, "Backup format \(manifest.format)")
        }
        if manifest.format < 1 { throw BackupError(.notABackup, "Backup format \(manifest.format)") }
        var areas: [BackupArea] = []
        for id in manifest.areas {
            guard let area = BackupArea.of(id) else { throw BackupError(.notABackup, "Unknown area \(id)") }
            areas.append(area)
        }
        if Set(areas).count != areas.count { throw BackupError(.notABackup, "Area listed twice") }
        if manifest.entries.count > maxEntries { throw BackupError(.notABackup, "Too many files") }
        var seen = Set<String>()
        for entry in manifest.entries {
            guard let area = BackupPaths.areaOf(entry.path) else {
                throw BackupError(.unsafe, "Path not allowed: \(entry.path)")
            }
            if !areas.contains(area) { throw BackupError(.corrupt, "\(entry.path) is outside the areas") }
            if !seen.insert(entry.path).inserted { throw BackupError(.corrupt, "\(entry.path) listed twice") }
            if entry.size < 0 || entry.size > maxEntryBytes {
                throw BackupError(.corrupt, "Size of \(entry.path)")
            }
            if !isSha256(entry.sha256) { throw BackupError(.corrupt, "Checksum of \(entry.path)") }
        }
    }

    /// The regular expression `[0-9a-f]{64}` on the whole text.
    private static func isSha256(_ text: String) -> Bool {
        let units = Array(text.utf16)
        return units.count == 64 && units.allSatisfy { (0x30 ... 0x39).contains($0) || (0x61 ... 0x66).contains($0) }
    }
}

/// What happened to a restore applied at start-up, kept until the user has seen it.
public struct RestoreReport: Equatable, Hashable, Sendable, Codable {
    public let ok: Bool
    public let atMillis: Int64
    /// When the backup was made.
    public let backupMillis: Int64
    public let files: Int
    /// Why it failed, for the log; the screen only says that it did.
    public let error: String?

    public init(ok: Bool, atMillis: Int64, backupMillis: Int64 = 0, files: Int = 0, error: String? = nil) {
        self.ok = ok
        self.atMillis = atMillis
        self.backupMillis = backupMillis
        self.files = files
        self.error = error
    }

    private enum CodingKeys: String, CodingKey {
        case ok, atMillis, backupMillis, files, error
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decode(Bool.self, forKey: .ok)
        atMillis = try c.decode(Int64.self, forKey: .atMillis)
        backupMillis = try c.backupValue(.backupMillis, Int64(0))
        files = try c.backupValue(.files, 0)
        error = try c.decodeIfPresent(String.self, forKey: .error)
    }

    /// Every field is written, a null error included (`encodeDefaults` and `explicitNulls` of kotlinx.serialization).
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(ok, forKey: .ok)
        try c.encode(atMillis, forKey: .atMillis)
        try c.encode(backupMillis, forKey: .backupMillis)
        try c.encode(files, forKey: .files)
        if let message = error {
            try c.encode(message, forKey: .error)
        } else {
            try c.encodeNil(forKey: .error)
        }
    }
}

/// Reads and writes `restore-report.json`.
public enum RestoreReportCodec {
    public static func encode(_ report: RestoreReport) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        let data = try encoder.encode(report)
        return String(decoding: data, as: UTF8.self)
    }

    /// Nil for a missing, empty or damaged file.
    public static func decodeOrNull(_ text: String?) -> RestoreReport? {
        guard let raw = text, !raw.unicodeScalars.allSatisfy(KotlinText.isWhitespace) else { return nil }
        return try? JSONDecoder().decode(RestoreReport.self, from: Data(raw.utf8))
    }
}

/// kotlinx.serialization without `coerceInputValues`: a missing key gives the default, a null or a value of the
/// wrong type fails.
fileprivate extension KeyedDecodingContainer {
    func backupValue<T: Decodable>(_ key: Key, _ fallback: T) throws -> T {
        if contains(key) { return try decode(T.self, forKey: key) }
        return fallback
    }
}
