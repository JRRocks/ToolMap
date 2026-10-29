import SwiftUI
import ToolMapCore

/// Stockage hors ligne (Android `storage/StorageScreen.kt`, P29.7 "Poids"): the MBTiles files of the device, the place
/// they take, and their purge.
struct StorageView: View {
    @Environment(AppModel.self) private var model
    @State private var files: [TileService.StoredFile] = []
    @State private var toPurge: String?

    var body: some View {
        List {
            Section {
                LabeledContent("Cartes hors ligne", value: Self.size(files.reduce(0) { $0 + $1.bytes }))
                if let free = Self.freeBytes() {
                    LabeledContent("Place libre sur l'iPhone", value: Self.size(free))
                }
            }
            Section("Jeux de tuiles") {
                if files.isEmpty {
                    Text("Aucune tuile stockée pour l'instant : les cartes affichées sont gardées au fur et à mesure.")
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                ForEach(files) { file in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.source(file.id)?.name ?? file.id)
                            Text(Self.size(file.bytes))
                                .font(.footnote)
                                .foregroundStyle(Crete.onSurfaceVariant)
                        }
                        Spacer()
                        Button(role: .destructive) {
                            toPurge = file.id
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Effacer")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Stockage hors ligne")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .onAppear(perform: refresh)
        .confirmationDialog(
            "Effacer toutes les tuiles de « \(toPurge.map { model.source($0)?.name ?? $0 } ?? "") » ? Elles ne seront plus disponibles hors ligne.",
            isPresented: Binding(get: { toPurge != nil }, set: { if !$0 { toPurge = nil } }),
            titleVisibility: .visible
        ) {
            Button("Effacer", role: .destructive) {
                if let id = toPurge {
                    TileService.shared.clear(id)
                }
                toPurge = nil
                refresh()
            }
        }
    }

    private func refresh() {
        files = TileService.shared.storedFiles()
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func freeBytes() -> Int64? {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage
    }
}
