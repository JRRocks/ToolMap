import Foundation
import ToolMapCore

/// The maps the user added (Android `SourceRepository`, P1.7): an imported `.aqx` file, the « Ajouter une carte »
/// wizard, the online catalogue or the multi-zoom editor. One `.aqx` file per map in Application Support/sources, written
/// with `AqxWriter` and named as on Android, read again at the next launch; a file of an earlier version that holds
/// several maps is read too.
enum SourceLibrary {
    static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("sources", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    /// What an import gave: the maps added, those skipped (already in the catalogue), the file's warnings.
    struct ImportResult {
        let added: [TileSource]
        let skipped: [String]
        let warnings: [AqxWarning]
    }

    /// Every map of the stored files, in file name order; a damaged file is skipped, never fatal.
    static func load() -> [TileSource] {
        var sources: [TileSource] = []
        var seen = Set<String>()
        for url in files() {
            for source in read(url) where !seen.contains(source.id) {
                seen.insert(source.id)
                sources.append(source)
            }
        }
        return sources
    }

    /// Reads `url` (a file chosen in Files or opened with ToolMap) and stores its maps that are not already in
    /// `existing`, one file each.
    static func importFile(_ url: URL, existing: Set<String>) throws -> ImportResult {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let result = try AqxParser.parse(String(decoding: data, as: UTF8.self))
        var fresh: [TileSource] = []
        var skipped: [String] = []
        var taken = existing
        for source in result.file.sources {
            if taken.contains(source.id) {
                skipped.append(source.name)
            } else {
                taken.insert(source.id)
                fresh.append(source)
            }
        }
        for source in fresh {
            try save(source)
        }
        return ImportResult(added: fresh, skipped: skipped, warnings: result.warnings)
    }

    /// Creates or replaces one map (the wizard, the catalogue, the multi-zoom editor).
    static func save(_ source: TileSource) throws {
        let target = directory.appendingPathComponent(fileName(source.id))
        removeEntry(source.id, keeping: target)
        let text = AqxWriter.write([source])
        try Data(text.utf8).write(to: target, options: .atomic)
    }

    /// Removes a map: its own file, or its entry in an older file holding several maps.
    static func delete(_ id: String) {
        let own = directory.appendingPathComponent(fileName(id))
        try? FileManager.default.removeItem(at: own)
        removeEntry(id, keeping: nil)
    }

    /// `<id with safe characters>-<hash of the id>.aqx`, the name Android gives the file of a map.
    static func fileName(_ id: String) -> String {
        var safe = ""
        for scalar in id.unicodeScalars {
            let allowed = ("A" ... "Z").contains(scalar) || ("a" ... "z").contains(scalar)
                || ("0" ... "9").contains(scalar) || scalar == "." || scalar == "_" || scalar == "-"
            safe.unicodeScalars.append(allowed ? scalar : "_")
        }
        let short = String(safe.prefix(60))
        var hash: Int32 = 0
        for unit in id.utf16 {
            hash = hash &* 31 &+ Int32(unit)
        }
        let hex = String(UInt32(bitPattern: hash), radix: 16)
        return "\(short)-\(hex).aqx"
    }

    private static func files() -> [URL] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.sorted()
            .filter { $0.lowercased().hasSuffix(".aqx") }
            .map { directory.appendingPathComponent($0) }
    }

    private static func read(_ url: URL) -> [TileSource] {
        guard let data = try? Data(contentsOf: url),
              let result = try? AqxParser.parse(String(decoding: data, as: UTF8.self))
        else { return [] }
        return result.file.sources
    }

    /// Takes the map `id` out of every other file (an import of an earlier version kept whole files).
    private static func removeEntry(_ id: String, keeping target: URL?) {
        for url in files() where url.lastPathComponent != target?.lastPathComponent {
            let sources = read(url)
            guard sources.contains(where: { $0.id == id }) else { continue }
            let rest = sources.filter { $0.id != id }
            if rest.isEmpty {
                try? FileManager.default.removeItem(at: url)
            } else {
                let text = AqxWriter.write(rest)
                try? Data(text.utf8).write(to: url, options: .atomic)
            }
        }
    }
}

/// The favourites the user saved (Android `WorkspaceStore`): one JSON file each in Application Support/workspaces, and
/// the provided ones the user deleted listed in `hidden-builtins.txt`; the same files as Android, so that a backup goes
/// from one app to the other. A damaged file is ignored, never fatal.
enum WorkspaceFiles {
    static let directory: URL = AppFiles.folder("workspaces")
    private static let hiddenFile = "hidden-builtins.txt"

    /// Saved favourites, oldest first.
    static func load() -> [Workspace] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        var dated: [(Date, Workspace)] = []
        for name in names where name.hasSuffix(".json") {
            let url = directory.appendingPathComponent(name)
            guard let text = try? String(contentsOf: url, encoding: .utf8),
                  let workspace = WorkspaceCodec.decodeOrNull(text),
                  !workspace.isBuiltin
            else { continue }
            let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
            dated.append((values?.contentModificationDate ?? Date.distantPast, workspace))
        }
        return dated.sorted { $0.0 < $1.0 }.map { $0.1 }
    }

    /// Ids of the provided favourites the user deleted.
    static func loadHidden() -> Set<String> {
        let url = directory.appendingPathComponent(hiddenFile)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        var ids = Set<String>()
        for line in text.components(separatedBy: .newlines) {
            let id = line.trimmingCharacters(in: .whitespaces)
            if !id.isEmpty { ids.insert(id) }
        }
        return ids
    }

    static func save(_ workspace: Workspace) throws {
        let text = try WorkspaceCodec.encode(workspace)
        try Data(text.utf8).write(to: file(workspace.id), options: .atomic)
    }

    static func delete(_ id: String) {
        try? FileManager.default.removeItem(at: file(id))
    }

    static func writeHidden(_ ids: Set<String>) {
        let url = directory.appendingPathComponent(hiddenFile)
        if ids.isEmpty {
            try? FileManager.default.removeItem(at: url)
        } else {
            let text = ids.sorted().joined(separator: "\n")
            try? Data(text.utf8).write(to: url, options: .atomic)
        }
    }

    /// An id for a favourite the user is saving now.
    static func newId() -> String {
        "ws-\(UUID().uuidString.lowercased())"
    }

    private static func file(_ id: String) -> URL {
        var safe = ""
        for scalar in id.unicodeScalars {
            let allowed = ("A" ... "Z").contains(scalar) || ("a" ... "z").contains(scalar)
                || ("0" ... "9").contains(scalar) || scalar == "." || scalar == "_" || scalar == "-"
            safe.unicodeScalars.append(allowed ? scalar : "_")
        }
        return directory.appendingPathComponent(String(safe.prefix(80)) + ".json")
    }
}

/// What the app restores at startup (Android `WorkStateStore`, `state/workstate.json`): the layer stack, the camera,
/// the two last base maps and the offline mode, with the keys of Android.
enum WorkStateFile {
    static let url: URL = AppFiles.folder("state").appendingPathComponent("workstate.json")

    /// The saved state, or nil at a first start (no file yet).
    static func load() -> WorkState? {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return WorkStateCodec.decodeOrDefault(text)
    }

    static func save(_ state: WorkState) {
        guard let text = try? WorkStateCodec.encode(state) else { return }
        try? Data(text.utf8).write(to: url, options: .atomic)
    }
}
