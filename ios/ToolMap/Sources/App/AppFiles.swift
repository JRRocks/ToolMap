import Foundation

/// Where the app keeps its files: Application Support (kept by iOS, saved by the device backup unless excluded) and
/// Caches (may be emptied by iOS).
enum AppFiles {
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }()

    static let caches: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]

    /// A folder of Application Support, created on first use.
    static func folder(_ name: String, backedUp: Bool = true) -> URL {
        var url = support.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        if !backedUp {
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        }
        return url
    }

    /// A fresh file in the temporary folder, for a file shared with another app (GPX, PDF, diagnostic).
    static func temporary(_ name: String) -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(name)
    }
}
