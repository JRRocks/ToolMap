import CoreTransferable
import SwiftUI
import ToolMapCore
import UniformTypeIdentifiers

/// Stockage hors ligne (Android STORAGE, P29.7): the page above the map; its content is `StorageView`.
struct StorageScreen: View {
    var body: some View {
        NavigationStack {
            StorageView()
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        ScreenCloseButton()
                    }
                }
        }
    }
}

extension UTType {
    /// The MBTiles package declared in Info.plist (`fr.toolmap.mbtiles`, « Ouvrir avec »).
    static let mbtilesPackage = UTType(importedAs: "fr.toolmap.mbtiles")
}

/// « Exporter » of a storage (Android `StorageManager.exportTo`): its MBTiles file, written in full (the write-ahead log
/// folded in) and copied under the name of its maps, made only when the share sheet asks for it.
struct MbTilesExport: Transferable, Sendable {
    let storageId: String
    let displayName: String

    static func fileName(_ name: String) -> String {
        MbTilesPackage.slug(name) + ".mbtiles"
    }

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .mbtilesPackage) { (export: MbTilesExport) in
            let url = try export.prepare()
            return SentTransferredFile(url)
        }
    }

    /// The file to send, in the temporary folder (an APFS copy: instant, whatever its size).
    func prepare() throws -> URL {
        let file = try TileService.shared.file(storageId)
        let zooms = Array(((try? file.tileCountByZoom()) ?? [:]).keys)
        try? file.setMetadata("name", displayName)
        try? file.setMetadata("toolmap-source-id", storageId)
        if let low = zooms.min(), let high = zooms.max() {
            try? file.setMetadata("minzoom", String(low))
            try? file.setMetadata("maxzoom", String(high))
        }
        try file.checkpoint()
        let target = AppFiles.temporary(MbTilesExport.fileName(displayName))
        try FileManager.default.copyItem(at: file.url, to: target)
        return target
    }
}
