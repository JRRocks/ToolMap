import Foundation
import ToolMapCore

extension TileService {
    /// The app's tile pipeline: MBTiles files in Application Support/tiles (kept by iOS, not backed up to iCloud).
    static let shared: TileService = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var url = base.appendingPathComponent("tiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        return TileService(directory: url, userAgent: "ToolMap/\(version) (iOS)")
    }()
}
