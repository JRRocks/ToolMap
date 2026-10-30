import CryptoKit
import Foundation
import Observation
import ToolMapCore

// Sauvegarde et restauration (Android P9.6, data/backup/BackupService.kt and PendingRestore.kt; format:
// docs/specs/backup-format.md, ADR-026). The archive is the Android one: `manifest.json` first, then the files under
// their paths, each listed in the manifest with its size and SHA-256 (`BackupModel` of the core). A restore never
// touches live data: the archive is checked and unpacked into Application Support/restore-pending, then swapped in at
// the next launch (`BackupController.applyPendingRestore()`, first line of `ToolMapApp.init`), before anything opens
// the library or reads a file.

/// The paths of the backup areas on the iPhone (Android `BackupLocations`): relative to Application Support, as on
/// Android to `filesDir`, but the library database.
enum BackupPlaces {
    /// The library database, by the names the app may give it (the first that exists).
    static let libraryNames = ["library.sqlite", TrackDatabase.fileName]

    static var support: URL { AppFiles.support }
    static var staging: URL { support.appendingPathComponent("restore-pending", isDirectory: true) }
    static var payload: URL { staging.appendingPathComponent("payload", isDirectory: true) }
    /// Written last: the unpacking is complete.
    static var marker: URL { staging.appendingPathComponent("restore.json") }
    static var undo: URL { support.appendingPathComponent("restore-undo", isDirectory: true) }
    static var report: URL { support.appendingPathComponent("restore-report.json") }

    /// The database of the library: the first of `libraryNames` that exists, the first name otherwise.
    static func database() -> URL {
        for name in libraryNames {
            let url = support.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        return support.appendingPathComponent(libraryNames[0])
    }

    /// The live file of an archive path.
    static func fileFor(_ path: String, database: URL) -> URL {
        path == BackupPaths.databaseFile ? database : support.appendingPathComponent(path)
    }

    /// Everything that holds the data of `area`: replaced as a whole by a restore.
    static func targets(_ area: BackupArea, database: URL) -> [URL] {
        switch area {
        case .database:
            return [database] + ["-wal", "-shm", "-journal"].map { URL(fileURLWithPath: database.path + $0) }
        case .settings:
            return [support.appendingPathComponent(BackupPaths.settingsFile)]
        case .alerts:
            return [support.appendingPathComponent(BackupPaths.alertsFile)]
        case .state:
            return [support.appendingPathComponent("state", isDirectory: true)]
        case .sources:
            return [support.appendingPathComponent("sources", isDirectory: true)]
        case .workspaces:
            return [support.appendingPathComponent("workspaces", isDirectory: true)]
        case .print:
            return [support.appendingPathComponent("print", isDirectory: true)]
        case .tiles:
            return [support.appendingPathComponent("tiles", isDirectory: true)]
        }
    }

    /// The archive path and live file of each file of `area` that exists now (the database apart: it is copied first).
    static func liveFiles(_ area: BackupArea) -> [(String, URL)] {
        switch area {
        case .database: return []
        case .settings: return single(BackupPaths.settingsFile)
        case .alerts: return single(BackupPaths.alertsFile)
        case .state: return folder("state")
        case .sources: return folder("sources")
        case .workspaces: return folder("workspaces")
        case .print: return folder("print")
        case .tiles: return folder("tiles")
        }
    }

    private static func single(_ path: String) -> [(String, URL)] {
        let url = support.appendingPathComponent(path)
        return FileManager.default.fileExists(atPath: url.path) ? [(path, url)] : []
    }

    private static func folder(_ name: String) -> [(String, URL)] {
        let directory = support.appendingPathComponent(name, isDirectory: true)
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey]
        )) ?? []
        var out: [(String, URL)] = []
        for url in urls {
            let regular = (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile ?? false
            let path = "\(name)/\(url.lastPathComponent)"
            if regular, !path.hasSuffix(".tmp"), BackupPaths.areaOf(path) != nil {
                out.append((path, url))
            }
        }
        return out.sorted { $0.0 < $1.0 }
    }
}

/// The file chosen in Files could not be read.
struct BackupOpenError: Error {}

/// The work of a backup and of a restore, off the main thread.
enum BackupWorker {
    private static let chunk = 1 << 20
    private static let maxManifestBytes = 8 * 1024 * 1024
    private static let spaceMargin: Int64 = 16 * 1024 * 1024

    private struct Planned {
        let path: String
        let file: URL
        let size: Int64
        let sha256: String
        let crc: UInt32
    }

    static func nowMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }

    // MARK: Writing

    /// Writes the archive at `zip`: the manifest, then every file of the areas (the offline maps on demand).
    static func create(
        at zip: URL,
        includeTiles: Bool,
        appVersion: String,
        progress: @escaping (Int64, Int64) -> Void
    ) throws -> BackupManifest {
        let fileManager = FileManager.default
        let work = fileManager.temporaryDirectory.appendingPathComponent("backup-work", isDirectory: true)
        try? fileManager.removeItem(at: work)
        try fileManager.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: work) }
        let areas = BackupArea.allCases.filter { !$0.optional || includeTiles }
        if areas.contains(.tiles) { checkpointTiles() }
        var planned: [Planned] = []
        for area in areas {
            let files = try sources(area, work: work)
            for (path, file) in files {
                let item = try plan(path, file)
                planned.append(item)
            }
        }
        let entries = planned.map { BackupEntry($0.path, $0.size, $0.sha256) }
        let manifest = BackupManifest(
            appVersion: appVersion,
            createdAtMillis: nowMillis(),
            areas: areas.map(\.id),
            entries: entries
        )
        do {
            try write(zip, manifest, planned, progress)
        } catch {
            try? fileManager.removeItem(at: zip)
            throw error
        }
        return manifest
    }

    private static func sources(_ area: BackupArea, work: URL) throws -> [(String, URL)] {
        guard area == .database else { return BackupPlaces.liveFiles(area) }
        let database = BackupPlaces.database()
        guard FileManager.default.fileExists(atPath: database.path) else { return [] }
        let snapshot = work.appendingPathComponent("tracks.db.snapshot")
        try snapshotDatabase(database, to: snapshot)
        return [(BackupPaths.databaseFile, snapshot)]
    }

    /// A consistent copy of the library, write-ahead log included, while the app may still write to it (Android freezes
    /// Room while it copies the file; SQLite's `VACUUM INTO` does the same in one statement).
    private static func snapshotDatabase(_ database: URL, to snapshot: URL) throws {
        try? FileManager.default.removeItem(at: snapshot)
        do {
            let connection = try SQLiteDatabase(path: database.path)
            defer { connection.close() }
            let quoted = snapshot.path.replacingOccurrences(of: "'", with: "''")
            try connection.execute("VACUUM INTO '\(quoted)'")
        } catch {
            throw BackupError(.io, "Cannot copy the library: \(error)", cause: error)
        }
    }

    /// Folds the write-ahead log of every offline map into its file, so that the file can be copied alone.
    private static func checkpointTiles() {
        let service = TileService.shared
        for stored in service.storedFiles() {
            try? service.file(stored.id).checkpoint()
        }
    }

    /// Size, SHA-256 and CRC-32 of a file (first pass).
    private static func plan(_ path: String, _ file: URL) throws -> Planned {
        guard let handle = FileHandle(forReadingAtPath: file.path) else {
            throw BackupError(.io, "Cannot read \(path)")
        }
        defer { try? handle.close() }
        var hasher = SHA256()
        var crc = ZipCrc32()
        var size: Int64 = 0
        while true {
            let data = try handle.read(upToCount: chunk) ?? Data()
            if data.isEmpty { break }
            hasher.update(data: data)
            crc.update(data)
            size += Int64(data.count)
        }
        return Planned(path: path, file: file, size: size, sha256: BackupDigest.hex(hasher.finalize()), crc: crc.value)
    }

    /// The archive (second pass): each file copied again and checked against its first pass.
    private static func write(
        _ zip: URL,
        _ manifest: BackupManifest,
        _ planned: [Planned],
        _ progress: (Int64, Int64) -> Void
    ) throws {
        let writer = try ZipWriter(url: zip)
        let manifestText = try BackupManifestCodec.encode(manifest)
        try writer.add(BackupPaths.manifest, Data(manifestText.utf8))
        let total = planned.reduce(Int64(0)) { $0 + $1.size }
        var done: Int64 = 0
        for item in planned {
            try writer.begin(item.path, size: UInt64(item.size), crc: item.crc)
            guard let handle = FileHandle(forReadingAtPath: item.file.path) else {
                throw BackupError(.io, "Cannot read \(item.path)")
            }
            defer { try? handle.close() }
            var hasher = SHA256()
            var copied: Int64 = 0
            while copied < item.size {
                let wanted = Int(min(Int64(chunk), item.size - copied))
                let data = try handle.read(upToCount: wanted) ?? Data()
                if data.isEmpty { break }
                hasher.update(data: data)
                try writer.write(data)
                copied += Int64(data.count)
                done += Int64(data.count)
                progress(done, total)
            }
            let digest = BackupDigest.hex(hasher.finalize())
            if copied != item.size || digest != item.sha256 {
                throw BackupError(.corrupt, "\(item.path) changed while it was being saved")
            }
        }
        try writer.finish()
    }

    // MARK: Reading

    /// Copies the file chosen in Files (iCloud Drive, another app's folder) into the temporary folder, where it can be
    /// mapped in memory.
    static func copyChosen(_ url: URL) throws -> URL {
        let fileManager = FileManager.default
        let folder = fileManager.temporaryDirectory.appendingPathComponent("backup-restore", isDirectory: true)
        try? fileManager.removeItem(at: folder)
        try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent("backup.zip")
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [.withoutChanges], error: &coordinationError) { readable in
            do {
                try fileManager.copyItem(at: readable, to: target)
            } catch {
                copyError = error
            }
        }
        if coordinationError != nil || copyError != nil {
            try? fileManager.removeItem(at: folder)
            throw BackupOpenError()
        }
        return target
    }

    private static func open(_ url: URL) throws -> BackupZipReader {
        do {
            return try BackupZipReader(url: url)
        } catch {
            throw BackupError(.notABackup, "Not a readable archive", cause: error)
        }
    }

    private static func readManifest(_ reader: BackupZipReader) throws -> BackupManifest {
        guard let first = reader.entries.first, first.name == BackupPaths.manifest else {
            throw BackupError(.notABackup, "No manifest at the start of the archive")
        }
        let bytes: Data
        do {
            bytes = try reader.contents(first, limit: maxManifestBytes)
        } catch {
            throw BackupError(.notABackup, "Unreadable manifest", cause: error)
        }
        return try BackupManifestCodec.decode(String(decoding: bytes, as: UTF8.self))
    }

    /// The manifest of the archive, to show before anything is unpacked.
    static func inspect(_ url: URL) throws -> BackupManifest {
        let reader = try open(url)
        return try readManifest(reader)
    }

    /// Unpacks and checks the archive into the staging folder; it is applied at the next launch. Nothing live is
    /// touched, and a bad archive leaves nothing behind.
    static func stage(_ url: URL, progress: (Int64, Int64) -> Void) throws -> BackupManifest {
        discardPending()
        var staged = false
        defer { if !staged { discardPending() } }
        let reader = try open(url)
        let manifest = try readManifest(reader)
        try checkSpace(manifest.totalBytes + spaceMargin)
        try unpack(reader, manifest, progress)
        let text = try BackupManifestCodec.encode(manifest)
        try Data(text.utf8).write(to: BackupPlaces.marker, options: .atomic)
        staged = true
        return manifest
    }

    private static func checkSpace(_ needed: Int64) throws {
        let values = try? BackupPlaces.support.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let free = values?.volumeAvailableCapacityForImportantUsage, free < needed {
            throw BackupError(.noSpace, "Not enough free space")
        }
    }

    private static func unpack(_ reader: BackupZipReader, _ manifest: BackupManifest, _ progress: (Int64, Int64) -> Void) throws {
        let fileManager = FileManager.default
        var expected: [String: BackupEntry] = [:]
        for entry in manifest.entries {
            expected[entry.path] = entry
        }
        let payload = BackupPlaces.payload
        try fileManager.createDirectory(at: payload, withIntermediateDirectories: true)
        let root = payload.standardizedFileURL.path + "/"
        var seen = Set<String>()
        let total = manifest.totalBytes
        var done: Int64 = 0
        // The manifest is the first entry (`readManifest`).
        for entry in reader.entries.dropFirst() {
            if entry.isDirectory { continue }
            guard let declared = expected[entry.name] else {
                throw BackupError(.corrupt, "Unlisted \(entry.name)")
            }
            if !seen.insert(entry.name).inserted { throw BackupError(.corrupt, "\(entry.name) twice") }
            let target = payload.appendingPathComponent(entry.name).standardizedFileURL
            if !target.path.hasPrefix(root) {
                throw BackupError(.unsafe, "\(entry.name) leaves the staging folder")
            }
            try fileManager.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard fileManager.createFile(atPath: target.path, contents: nil),
                  let sink = FileHandle(forWritingAtPath: target.path)
            else { throw BackupError(.io, "Cannot write \(entry.name)") }
            defer { try? sink.close() }
            var hasher = SHA256()
            var size: Int64 = 0
            do {
                try reader.stream(entry, limit: declared.size) { chunk in
                    size += Int64(chunk.count)
                    hasher.update(data: chunk)
                    try sink.write(contentsOf: chunk)
                    done += Int64(chunk.count)
                    progress(done, total)
                }
            } catch let error as BackupZipReader.ReadError {
                throw BackupError(.corrupt, error.message, cause: error)
            }
            let digest = BackupDigest.hex(hasher.finalize())
            if size != declared.size || digest != declared.sha256 {
                throw BackupError(.corrupt, "\(entry.name) does not match its checksum")
            }
        }
        if let missing = manifest.entries.first(where: { !seen.contains($0.path) }) {
            throw BackupError(.corrupt, "\(missing.path) is missing")
        }
    }

    // MARK: Pending restore

    /// The restore waiting for the next launch, if there is one.
    static func pending() -> BackupManifest? {
        guard let text = try? String(contentsOf: BackupPlaces.marker, encoding: .utf8) else { return nil }
        return try? BackupManifestCodec.decode(text)
    }

    static func discardPending() {
        try? FileManager.default.removeItem(at: BackupPlaces.staging)
    }

    /// How the last restore went, until it is hidden.
    static func report() -> RestoreReport? {
        RestoreReportCodec.decodeOrNull(try? String(contentsOf: BackupPlaces.report, encoding: .utf8))
    }

    static func clearReport() {
        try? FileManager.default.removeItem(at: BackupPlaces.report)
    }

    private struct Moved {
        let live: URL
        let undo: URL
    }

    /// Android `PendingRestore.apply`: each covered area moved aside, then the staged one moved in; if a step fails,
    /// everything is put back as it was.
    static func apply(_ manifest: BackupManifest) throws {
        let fileManager = FileManager.default
        // Chosen once, before anything moves: the restored library goes where the app reads it.
        let database = BackupPlaces.database()
        try? fileManager.removeItem(at: BackupPlaces.undo)
        var aside: [Moved] = []
        var placed: [URL] = []
        do {
            for area in BackupArea.allCases where manifest.covers(area) {
                for (index, live) in BackupPlaces.targets(area, database: database).enumerated() {
                    if !fileManager.fileExists(atPath: live.path) { continue }
                    let undo = BackupPlaces.undo.appendingPathComponent("\(area.id)/\(index)-\(live.lastPathComponent)")
                    try move(live, undo)
                    aside.append(Moved(live: live, undo: undo))
                }
                for entry in manifest.entries where BackupPaths.areaOf(entry.path) == area {
                    let staged = BackupPlaces.payload.appendingPathComponent(entry.path)
                    let live = BackupPlaces.fileFor(entry.path, database: database)
                    try move(staged, live)
                    placed.append(live)
                }
            }
        } catch {
            for file in placed.reversed() {
                try? fileManager.removeItem(at: file)
            }
            for moved in aside.reversed() {
                try? fileManager.removeItem(at: moved.live)
                try? move(moved.undo, moved.live)
            }
            throw error
        }
    }

    private static func move(_ from: URL, _ to: URL) throws {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: to.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fileManager.fileExists(atPath: to.path) {
            try fileManager.removeItem(at: to)
        }
        try fileManager.moveItem(at: from, to: to)
    }
}

/// Reports the progress of a task to the main thread, at most once per percent.
final class BackupProgressMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var last = -1.0
    private let onChange: (Double) -> Void

    init(_ onChange: @escaping (Double) -> Void) {
        self.onChange = onChange
    }

    func report(_ done: Int64, _ total: Int64) {
        let fraction = total > 0 ? min(Double(done) / Double(total), 1) : 0
        lock.lock()
        let due = fraction - last >= 0.01 || (fraction >= 1 && last < 1)
        if due { last = fraction }
        lock.unlock()
        if due {
            DispatchQueue.main.async { self.onChange(fraction) }
        }
    }
}

/// The French texts of the backup (Android strings `backup_*`).
enum BackupText {
    private static let french = Locale(identifier: "fr_FR")

    static func failure(_ error: Error) -> String {
        if error is BackupOpenError { return "Impossible d'ouvrir ce fichier." }
        guard let backup = error as? BackupError else {
            return "Lecture ou écriture impossible : \(error.localizedDescription)"
        }
        switch backup.failure {
        case .notABackup: return "Ce fichier n'est pas une sauvegarde ToolMap."
        case .unsupportedVersion:
            return "Cette sauvegarde vient d'une version plus récente de ToolMap : mettez l'application à jour."
        case .corrupt: return "La sauvegarde est abîmée ou incomplète : rien n'a été modifié."
        case .unsafe: return "La sauvegarde contient un chemin interdit : rien n'a été modifié."
        case .noSpace: return "Pas assez de place sur le téléphone pour préparer cette restauration."
        case .tilesUnavailable, .io: return "Lecture ou écriture impossible : \(backup.message)"
        }
    }

    /// « 18,9 Mo », as Android `formatBytes`.
    static func bytes(_ count: Int64) -> String {
        if count >= 1_000_000_000 { return decimal(Double(count) / 1_000_000_000) + " Go" }
        if count >= 1_000_000 { return decimal(Double(count) / 1_000_000) + " Mo" }
        if count >= 1000 { return "\(count / 1000) Ko" }
        return "\(count) o"
    }

    private static func decimal(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = french
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        return formatter.string(from: NSNumber(value: value)) ?? String(format: "%.1f", value)
    }

    /// « 12 oct. 2026 à 18:40 ».
    static func date(_ millis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = french
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// « 2026-10-29 », for the name of the file.
    static func day(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    static func area(_ area: BackupArea) -> String {
        switch area {
        case .database: "Traces, itinéraires et waypoints"
        case .settings: "Réglages"
        case .alerts: "Alertes et suivi de trace"
        case .state: "Dernière position de la carte et calques"
        case .sources: "Cartes importées ou créées"
        case .workspaces: "Espaces de travail"
        case .print: "Mises en page d'impression"
        case .tiles: "Cartes hors ligne"
        }
    }

    static func files(_ count: Int) -> String {
        count == 1 ? "1 fichier" : "\(count) fichiers"
    }
}

/// A message at the bottom of the backup page.
struct BackupMessage: Identifiable {
    let id = UUID()
    let text: String
}

/// The backup page's state and actions (Android `BackupViewModel`).
@Observable
final class BackupController {
    enum Busy {
        case creating, checking, unpacking

        var label: String {
            switch self {
            case .creating: "Création de la sauvegarde…"
            case .checking: "Lecture de la sauvegarde…"
            case .unpacking: "Vérification et préparation de la restauration…"
            }
        }
    }

    /// A backup chosen in Files, read, waiting for « Préparer la restauration ».
    struct Preview: Identifiable {
        let id = UUID()
        let manifest: BackupManifest
        let file: URL
    }

    /// A backup written, waiting to be saved in Files.
    struct Export: Identifiable {
        let id = UUID()
        let url: URL
        let manifest: BackupManifest
    }

    @ObservationIgnored unowned let app: AppModel

    private(set) var busy: Busy?
    /// From 0 to 1, or nil while the amount is not known.
    private(set) var progress: Double?
    var includeTiles = false
    private(set) var tilesBytes: Int64 = 0
    private(set) var pending: BackupManifest?
    private(set) var report: RestoreReport?
    var preview: Preview?
    var export: Export?
    var message: BackupMessage?

    init(app: AppModel) {
        self.app = app
        refresh()
    }

    /// Applies the restore waiting from the last session, if there is one. Called once at launch, before anything opens
    /// the library or reads the settings (Android `Application.attachBaseContext`).
    static func applyPendingRestore() {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: BackupPlaces.staging.path) else { return }
        guard let manifest = BackupWorker.pending() else {
            // An unpacking that never finished (the app was closed half-way): not usable, not to be kept.
            BackupWorker.discardPending()
            return
        }
        let result: RestoreReport
        do {
            try BackupWorker.apply(manifest)
            result = RestoreReport(
                ok: true,
                atMillis: BackupWorker.nowMillis(),
                backupMillis: manifest.createdAtMillis,
                files: manifest.entries.count
            )
        } catch {
            result = RestoreReport(
                ok: false,
                atMillis: BackupWorker.nowMillis(),
                backupMillis: manifest.createdAtMillis,
                error: "\(error)"
            )
        }
        BackupWorker.discardPending()
        try? fileManager.removeItem(at: BackupPlaces.undo)
        if let encoded = try? RestoreReportCodec.encode(result) {
            try? Data(encoded.utf8).write(to: BackupPlaces.report, options: .atomic)
        }
    }

    /// Reads again what waits on disk: the restore, its report, the size of the offline maps.
    func refresh() {
        pending = BackupWorker.pending()
        report = BackupWorker.report()
        tilesBytes = TileService.shared.storedFiles().reduce(Int64(0)) { $0 + $1.bytes }
    }

    func say(_ text: String) {
        message = BackupMessage(text: text)
    }

    // MARK: Backup

    /// « Créer une sauvegarde… »: the archive is written in the temporary folder, then saved where the user chooses.
    func create() {
        guard busy == nil else { return }
        busy = .creating
        progress = 0
        let tiles = includeTiles
        let version = "\(app.version) iOS"
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("backup-export", isDirectory: true)
        let zip = folder.appendingPathComponent("toolmap-\(BackupText.day(Date())).zip")
        let meter = BackupProgressMeter { [weak self] fraction in
            self?.progress = fraction
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let outcome: Result<BackupManifest, Error>
            do {
                try? FileManager.default.removeItem(at: folder)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let manifest = try BackupWorker.create(
                    at: zip,
                    includeTiles: tiles,
                    appVersion: version,
                    progress: meter.report
                )
                outcome = .success(manifest)
            } catch {
                outcome = .failure(error)
            }
            DispatchQueue.main.async {
                self?.created(outcome, zip)
            }
        }
    }

    private func created(_ outcome: Result<BackupManifest, Error>, _ zip: URL) {
        busy = nil
        progress = nil
        switch outcome {
        case let .success(manifest):
            export = Export(url: zip, manifest: manifest)
        case let .failure(error):
            say(BackupText.failure(error))
        }
    }

    /// The save sheet closed: the archive was saved (`saved`) or not; the temporary copy goes either way.
    func exportFinished(saved: Bool) {
        guard let done = export else { return }
        export = nil
        if saved {
            let count = done.manifest.entries.count
            let size = BackupText.bytes(done.manifest.totalBytes)
            say("Sauvegarde créée (nombre de fichiers : \(count), taille : \(size)).")
        }
        try? FileManager.default.removeItem(at: done.url.deletingLastPathComponent())
    }

    // MARK: Restore

    /// The file chosen with « Choisir une sauvegarde… »: copied, its manifest read, then « Restaurer cette sauvegarde ? ».
    func choose(_ result: Result<URL, Error>) {
        guard busy == nil else { return }
        guard case let .success(url) = result else {
            if case let .failure(error) = result, (error as? CocoaError)?.code != .userCancelled {
                say(BackupText.failure(BackupOpenError()))
            }
            return
        }
        busy = .checking
        progress = nil
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let outcome: Result<Preview, Error>
            do {
                let copy = try BackupWorker.copyChosen(url)
                do {
                    let manifest = try BackupWorker.inspect(copy)
                    outcome = .success(Preview(manifest: manifest, file: copy))
                } catch {
                    try? FileManager.default.removeItem(at: copy.deletingLastPathComponent())
                    throw error
                }
            } catch {
                outcome = .failure(error)
            }
            DispatchQueue.main.async {
                self?.inspected(outcome)
            }
        }
    }

    private func inspected(_ outcome: Result<Preview, Error>) {
        busy = nil
        switch outcome {
        case let .success(chosen):
            preview = chosen
        case let .failure(error):
            say(BackupText.failure(error))
        }
    }

    func cancelPreview() {
        if let chosen = preview {
            try? FileManager.default.removeItem(at: chosen.file.deletingLastPathComponent())
        }
        preview = nil
    }

    /// « Préparer la restauration »: the archive is checked and unpacked; it replaces the data at the next launch.
    func confirmRestore() {
        guard let chosen = preview, busy == nil else { return }
        preview = nil
        busy = .unpacking
        progress = 0
        let meter = BackupProgressMeter { [weak self] fraction in
            self?.progress = fraction
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let outcome: Result<BackupManifest, Error>
            do {
                let manifest = try BackupWorker.stage(chosen.file, progress: meter.report)
                outcome = .success(manifest)
            } catch {
                outcome = .failure(error)
            }
            try? FileManager.default.removeItem(at: chosen.file.deletingLastPathComponent())
            DispatchQueue.main.async {
                self?.staged(outcome)
            }
        }
    }

    private func staged(_ outcome: Result<BackupManifest, Error>) {
        busy = nil
        progress = nil
        switch outcome {
        case let .success(manifest):
            pending = manifest
            say("Restauration prête : elle s'applique au prochain démarrage.")
        case let .failure(error):
            say(BackupText.failure(error))
        }
    }

    /// « Annuler » of « Restauration prête ».
    func cancelPending() {
        BackupWorker.discardPending()
        pending = nil
    }

    /// « Masquer » of the report of the last restore.
    func dismissReport() {
        BackupWorker.clearReport()
        report = nil
    }
}
