import Foundation
import ToolMapCore

/// The maps the user added from `.aqx` files (Android `SourceRepository`, P1.7): each imported file is kept as it is in
/// Application Support/sources and read again at the next launch.
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

    /// Every map of the imported files, in file name order.
    static func load() -> [TileSource] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        var sources: [TileSource] = []
        for name in names.sorted() where name.lowercased().hasSuffix(".aqx") {
            let url = directory.appendingPathComponent(name)
            guard let data = try? Data(contentsOf: url),
                  let result = try? AqxParser.parse(String(decoding: data, as: UTF8.self))
            else { continue }
            sources.append(contentsOf: result.file.sources)
        }
        return sources
    }

    /// Reads `url` (a file chosen in Files), keeps a copy, and returns its maps that are not already in `existing`.
    static func importFile(_ url: URL, existing: Set<String>) throws -> ImportResult {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let result = try AqxParser.parse(String(decoding: data, as: UTF8.self))
        let fresh = result.file.sources.filter { !existing.contains($0.id) }
        let skipped = result.file.sources.filter { existing.contains($0.id) }.map(\.name)
        if !fresh.isEmpty {
            let name = url.deletingPathExtension().lastPathComponent
            var target = directory.appendingPathComponent("\(name).aqx")
            var index = 2
            while FileManager.default.fileExists(atPath: target.path) {
                target = directory.appendingPathComponent("\(name)-\(index).aqx")
                index += 1
            }
            try data.write(to: target, options: .atomic)
        }
        return ImportResult(added: fresh, skipped: skipped, warnings: result.warnings)
    }
}
