import SwiftUI
import ToolMapCore
import UniformTypeIdentifiers

/// Stockage hors ligne (Android `storage/StorageScreen.kt`, P29.7 « Poids », ADR-080): the gauge of the phone (used and
/// total, ToolMap's part in turquoise), then the maps from the heaviest in one block, each with its ⋮ (Exporter,
/// Effacer les tuiles de plus de 30 j, Tout effacer, or Supprimer cette carte for a local map); the import of a
/// `.mbtiles` file is an icon of the bar; the empty storage leads to the selection of a zone. Shown in the page
/// `Screen.storage` (`StorageScreen`) and pushed from the maps panel.
struct StorageView: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false
    @State private var confirm: StorageConfirm?

    var body: some View {
        let downloads = model.downloads
        StorageList(
            usages: downloads.usages,
            space: downloads.space,
            busy: downloads.storageBusy,
            onConfirm: { confirm = $0 },
            onImport: { importing = true }
        )
        .navigationTitle("Stockage hors ligne")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    importing = true
                } label: {
                    Image(systemName: "square.and.arrow.down")
                }
                .accessibilityLabel("Importer un fichier .mbtiles")
                .disabled(downloads.storageBusy)
            }
        }
        .overlay(alignment: .top) {
            if downloads.storageBusy {
                ProgressView()
                    .tint(Crete.turquoise)
                    .padding(8)
                    .background(Color.white, in: Circle())
                    .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
                    .padding(.top, 8)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let message = downloads.storageMessage {
                DownloadsMessageBar(text: message, onDismiss: { downloads.storageMessage = nil })
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType.mbtilesPackage, UTType.data]) { result in
            switch result {
            case let .success(url):
                downloads.stageMbTiles(url)
            case let .failure(error):
                downloads.storageMessage = "Opération impossible : \(error.localizedDescription)"
            }
        }
        .sheet(item: importBinding) { pending in
            StorageImportSheet(pending: pending)
                .environment(model)
        }
        .alert(
            confirm?.usage.name ?? "",
            isPresented: confirmShown,
            presenting: confirm
        ) { pending in
            Button("Confirmer", role: .destructive) {
                perform(pending)
            }
            Button("Annuler", role: .cancel) {}
        } message: { pending in
            Text(pending.message)
        }
        .onAppear {
            downloads.refreshStorage()
        }
    }

    /// The sheet of the package waiting; swiped away, the import is cancelled.
    private var importBinding: Binding<PendingMbTiles?> {
        Binding(
            get: { model.downloads.pendingImport },
            set: { value in
                if value == nil {
                    model.downloads.cancelImport()
                }
            }
        )
    }

    private var confirmShown: Binding<Bool> {
        Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } })
    }

    private func perform(_ pending: StorageConfirm) {
        switch pending {
        case let .purge(usage):
            model.downloads.purgeAll(usage.id)
        case let .deleteMap(usage):
            model.downloads.deleteLocalMap(usage.id)
        }
        confirm = nil
    }
}

/// What a confirmation is about.
enum StorageConfirm {
    case purge(StorageUsage)
    case deleteMap(StorageUsage)

    var usage: StorageUsage {
        switch self {
        case let .purge(usage), let .deleteMap(usage): usage
        }
    }

    var message: String {
        switch self {
        case let .purge(usage):
            "Effacer toutes les tuiles de « \(usage.name) » ? Elles ne seront plus disponibles hors ligne."
        case let .deleteMap(usage):
            """
            Supprimer la carte locale « \(usage.name) » et toutes ses tuiles ? Cette carte n'a pas de serveur : elle ne \
            pourra pas être retéléchargée.
            """
        }
    }
}

private struct StorageList: View {
    @Environment(AppModel.self) private var model
    let usages: [StorageUsage]
    let space: StorageSpace
    let busy: Bool
    let onConfirm: (StorageConfirm) -> Void
    let onImport: () -> Void

    var body: some View {
        let total = usages.reduce(Int64(0)) { $0 + $1.diskBytes }
        let sorted = usages.sorted { $0.diskBytes > $1.diskBytes }
        List {
            Section {
                StorageSpaceGauge(space: space, tilesBytes: total, maps: usages.count)
            }
            if usages.isEmpty {
                Section {
                    StorageEmpty(busy: busy, onImport: onImport)
                }
            } else {
                Section {
                    ForEach(sorted) { usage in
                        StorageUsageRow(
                            usage: usage,
                            share: total > 0 ? Double(usage.diskBytes) / Double(total) : 0,
                            busy: busy,
                            onConfirm: onConfirm
                        )
                    }
                } header: {
                    Text("De la plus lourde à la plus légère").textCase(nil)
                }
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// « 5,4 Go utilisés » and « sur 10,4 Go », a bar where ToolMap's part is turquoise (never thinner than 8 points, so
/// that it stays seen) and the rest of the used room grey, and its legend « Tuiles de ToolMap ».
private struct StorageSpaceGauge: View {
    let space: StorageSpace
    let tilesBytes: Int64
    let maps: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if space.total > 0 {
                HStack(alignment: .lastTextBaseline) {
                    Text("\(ZoneText.bytes(used)) utilisés")
                        .font(.headline)
                        .foregroundStyle(Crete.onSurface)
                    Spacer()
                    Text("sur \(ZoneText.bytes(space.total))")
                        .font(.subheadline)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                GeometryReader { proxy in
                    bar(width: proxy.size.width)
                }
                .frame(height: 10)
            }
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Crete.turquoise)
                    .frame(width: 10, height: 10)
                Text("Tuiles de ToolMap : \(ZoneText.bytes(tilesBytes)) · \(ZoneText.maps(maps))")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .padding(.vertical, 4)
    }

    private var used: Int64 {
        max(space.total - space.free, 0)
    }

    private func bar(width: CGFloat) -> some View {
        let total = CGFloat(max(space.total, 1))
        let usedWidth = width * min(max(CGFloat(used) / total, 0), 1)
        let ours = min(max(width * CGFloat(tilesBytes) / total, 8), max(usedWidth, 8))
        let rest = max(usedWidth - ours - 2, 0)
        return ZStack(alignment: .leading) {
            Rectangle()
                .fill(Crete.outline)
            HStack(spacing: 2) {
                Rectangle()
                    .fill(Crete.turquoise)
                    .frame(width: ours)
                if rest > 0 {
                    Rectangle()
                        .fill(Crete.onSurfaceVariant)
                        .frame(width: rest)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 5))
    }
}

/// Nothing stored yet: say so, and lead to a zone or to an import.
private struct StorageEmpty: View {
    @Environment(AppModel.self) private var model
    let busy: Bool
    let onImport: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Aucune tuile stockée pour l'instant.")
                .font(.headline)
                .foregroundStyle(Crete.onSurface)
            Text(ZoneText.emptyHint)
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
            Button("Sélectionner et enregistrer une zone") {
                model.downloads.askZone(only: nil)
            }
            .buttonStyle(.borderedProminent)
            .tint(Crete.turquoise)
            Button("Importer un fichier .mbtiles", action: onImport)
                .buttonStyle(.bordered)
                .tint(Crete.petrol)
                .disabled(busy)
        }
        .padding(.vertical, 6)
    }
}

/// One map: its name and its size at the end, its tiles, zooms and kind, a bar of its part of the total, and its
/// commands in the ⋮ menu.
private struct StorageUsageRow: View {
    @Environment(AppModel.self) private var model
    let usage: StorageUsage
    let share: Double
    let busy: Bool
    let onConfirm: (StorageConfirm) -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(usage.name)
                        .lineLimit(1)
                        .foregroundStyle(Crete.onSurface)
                    Spacer(minLength: 4)
                    Text(ZoneText.bytes(usage.diskBytes))
                        .font(.body.weight(.medium))
                        .foregroundStyle(Crete.petrol)
                }
                Text(StorageText.detail(usage))
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                ProgressView(value: min(max(share, 0), 1))
                    .tint(Crete.turquoise)
            }
            Menu {
                menuItems
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 32, height: 32)
            }
            .disabled(busy)
            .accessibilityLabel("Actions sur « \(usage.name) »")
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private var menuItems: some View {
        ShareLink(
            item: MbTilesExport(storageId: usage.id, displayName: usage.name),
            preview: SharePreview(MbTilesExport.fileName(usage.name))
        ) {
            Label("Exporter", systemImage: "square.and.arrow.up")
        }
        if usage.isLocalMap {
            Button(role: .destructive) {
                onConfirm(.deleteMap(usage))
            } label: {
                Label("Supprimer cette carte", systemImage: "trash")
            }
        } else {
            Button {
                model.downloads.purgeOld(usage.id)
            } label: {
                Label("Effacer les tuiles de plus de 30 j", systemImage: "clock.arrow.circlepath")
            }
            Button(role: .destructive) {
                onConfirm(.purge(usage))
            } label: {
                Label("Tout effacer", systemImage: "trash")
            }
        }
    }
}

/// « Importer un paquet de tuiles »: a new local map with its name, or the map that receives the tiles.
private struct StorageImportSheet: View {
    @Environment(AppModel.self) private var model
    let pending: PendingMbTiles
    @State private var name = ""
    /// nil: a new local map; otherwise the id of the map that receives the tiles.
    @State private var targetId: String?
    @State private var ready = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(infoLine)
                        .foregroundStyle(Crete.onSurface)
                }
                Section {
                    choice(nil, "Nouvelle carte locale")
                    if targetId == nil {
                        TextField("Nom de la carte", text: $name)
                        Text("Une carte locale n'affiche que ces tuiles : rien n'est téléchargé.")
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                    ForEach(targets, id: \.id) { source in
                        choice(source.id, "Ajouter à « \(source.name) »")
                    }
                }
            }
            .navigationTitle("Importer un paquet de tuiles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") {
                        model.downloads.cancelImport()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Importer") {
                        commit()
                    }
                    .disabled(targetId == nil && trimmedName.isEmpty)
                }
            }
            .onAppear {
                guard !ready else { return }
                ready = true
                name = pending.info.name ?? ""
                targetId = pending.knownSourceId
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var infoLine: String {
        let info = pending.info
        let title = info.name ?? "Sans nom"
        return "\(title) · zooms \(info.minZoom) à \(info.maxZoom) · tuiles : \(info.tiles)"
    }

    private var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The maps that can receive tiles: those with a server (multi-zoom maps and elevation data left out).
    private var targets: [TileSource] {
        model.maps.sources.filter { $0.type != "dem" && $0.isSupported && !$0.isLocalOnly && !$0.isMultiZoom }
    }

    private func choice(_ id: String?, _ label: String) -> some View {
        Button {
            targetId = id
        } label: {
            HStack(spacing: 10) {
                Image(systemName: targetId == id ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(Crete.turquoise)
                Text(label)
                    .foregroundStyle(Crete.onSurface)
            }
        }
    }

    private func commit() {
        if let id = targetId {
            model.downloads.commitImport(.existing(id))
        } else {
            model.downloads.commitImport(.newLocalMap(trimmedName))
        }
    }
}
