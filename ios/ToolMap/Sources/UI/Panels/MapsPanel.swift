import SwiftUI
import ToolMapCore

/// « Cartes et calques » (Android §5.1, P29.3 « Strates », ADR-076): the band (favourite applied, number of maps, how
/// many the map draws at its zoom, « Enregistrer l'affichage », « ? »), the favourites as chips, the layer stack from the
/// top of the screen down (each line unfolds in place), then « Hors ligne » and « Couverture », and the four ways to the
/// catalogue, the zone, the downloads and the storage. « Conditions » of a line opens « Conditions d'affichage » in the
/// panel, the map staying visible above it.
struct MapsPanel: View {
    @Environment(AppModel.self) private var model
    @State private var message: MapsMessage? = nil
    @State private var savingFavorite = false
    @State private var favoriteName = ""
    @State private var detailsLayer: String? = nil
    @State private var reordering = false

    init() {}

    var body: some View {
        Group {
            if let id = model.maps.conditionsLayer, let layer = model.maps.stack[id] {
                DisplayConditionsView(layer: layer)
            } else {
                stackPage
            }
        }
        .overlay(alignment: .bottom) {
            MapsMessageBar(message: $message)
        }
        .animation(.easeOut(duration: 0.2), value: message?.id)
        .alert("Nouveau favori", isPresented: $savingFavorite) {
            favoriteActions
        } message: {
            if model.maps.stack.isEmpty {
                Text("Affichez au moins une carte avant de l'enregistrer.")
            }
        }
        .alert(detailsTitle, isPresented: detailsShown) {
            Button("Fermer", role: .cancel) {}
        } message: {
            Text(detailsText)
        }
        .onAppear {
            model.maps.refreshStorage()
        }
    }

    // MARK: Stack page

    private var stackPage: some View {
        VStack(spacing: 0) {
            MapsBand(title: "Cartes et calques", subtitle: bandSubtitle) {
                MapsBandButton(systemImage: "bookmark", label: "Enregistrer l'affichage") {
                    startSave()
                }
                MapsBandButton(systemImage: "questionmark.circle", label: "Aide et à propos") {
                    model.maps.openFromPanel(.about("maps"))
                }
            }
            List {
                Section {
                    MapsFavoriteChips(onMessage: { message = $0 })
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
                stackSection
                Section {
                    MapsSwitchCells()
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    MapsNavTiles()
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
                .listRowSeparator(.hidden)
            }
            .listStyle(.insetGrouped)
            .environment(\.editMode, .constant(reordering ? EditMode.active : EditMode.inactive))
        }
    }

    private var stackSection: some View {
        let maps = model.maps
        let layers = maps.stack.topToBottom()
        return Section {
            if layers.isEmpty {
                Text("Aucune carte affichée. Ouvrez « Cartes disponibles » pour en choisir une.")
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            ForEach(Array(layers.enumerated()), id: \.element.id) { index, layer in
                MapsLayerRow(
                    layer: layer,
                    canUp: index > 0,
                    canDown: index < layers.count - 1,
                    onReorder: { reordering.toggle() },
                    onDetails: { detailsLayer = layer.id }
                )
                .swipeActions(edge: .trailing) {
                    Button("Ne plus afficher", role: .destructive) {
                        maps.removeLayer(layer.id)
                    }
                }
            }
            .onMove { from, to in
                var ids = layers.map(\.id)
                ids.move(fromOffsets: from, toOffset: to)
                maps.setOrder(topToBottom: ids)
            }
            if !layers.isEmpty, maps.appliedFavorite == nil {
                // The stack matches no favourite: the way to keep it, next to it.
                Button {
                    startSave()
                } label: {
                    Label("Enregistrer comme favori…", systemImage: "bookmark")
                        .foregroundStyle(Crete.turquoise)
                }
            }
        } header: {
            HStack {
                Text("Cartes affichées · du dessus vers le dessous")
                Spacer()
                if layers.count > 1 {
                    Button {
                        reordering.toggle()
                    } label: {
                        Image(systemName: reordering ? "checkmark" : "arrow.up.arrow.down")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(Crete.turquoise)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Faire glisser pour changer l'ordre")
                }
            }
            .textCase(nil)
        }
    }

    private var bandSubtitle: String {
        let maps = model.maps
        let layers = maps.stack.layers
        var single: String?
        if layers.count == 1, let only = layers.first {
            single = maps.source(only.sourceId)?.name
        }
        let drawn = layers.filter { maps.isDrawn($0) }.count
        return MapsText.bandSubtitle(
            favorite: maps.appliedFavorite?.name,
            single: single,
            count: layers.count,
            drawn: drawn,
            zoom: Int(floor(maps.displayedZoom))
        )
    }

    // MARK: Favourite dialog

    private func startSave() {
        favoriteName = ""
        savingFavorite = true
    }

    @ViewBuilder
    private var favoriteActions: some View {
        if model.maps.stack.isEmpty {
            Button("OK", role: .cancel) {}
        } else {
            TextField("Nom du favori", text: $favoriteName)
            Button("Annuler", role: .cancel) {}
            Button("Enregistrer") {
                if let text = model.maps.saveWorkspace(favoriteName) {
                    message = MapsMessage(text: text)
                }
            }
        }
    }

    // MARK: Details of a layer

    private var detailsShown: Binding<Bool> {
        Binding(get: { detailsLayer != nil }, set: { if !$0 { detailsLayer = nil } })
    }

    private var detailsTitle: String {
        guard let id = detailsLayer, let layer = model.maps.stack[id] else { return "" }
        return model.maps.source(layer.sourceId)?.name ?? "Carte introuvable"
    }

    /// « Détails » of a layer: its kind, zooms, origin, rights, data source, what is stored and the forced update.
    private var detailsText: String {
        let maps = model.maps
        guard let id = detailsLayer, let layer = maps.stack[id] else { return "" }
        var lines: [String] = []
        if let source = maps.source(layer.sourceId) {
            lines.append("Type : " + (source.isLayer ? "calque superposable" : "fond de carte"))
            if source.isSupported {
                lines.append("Zooms disponibles : \(source.minZoom ?? 0) à \(source.maxZoom ?? 0)")
            }
            if source.isLocalOnly {
                lines.append("Origine : paquet de tuiles local, sans serveur")
            } else if maps.isImported(source.id) {
                lines.append("Origine : ajoutée par vous (fichier .aqx ou assistant)")
            } else {
                lines.append("Origine : fournie avec l'application")
            }
            if let owner = source.copyright.flatMap({ MapCatalog.cleanCopyright($0) }) {
                lines.append("Droits : \(owner)")
            }
            if let site = source.dataSource.flatMap({ MapCatalog.hostOf($0) }) {
                lines.append("Source des données : \(site)")
            }
            if let stored = maps.stored(source.id) {
                lines.append("Sur l'appareil : \(MapsText.tiles(stored.tiles)), \(MapsText.bytes(stored.bytes))")
            } else if maps.storage != nil {
                lines.append("Aucune tuile stockée sur l'appareil.")
            }
        } else {
            lines.append(layer.sourceId)
        }
        if maps.forced.contains(layer.sourceId) {
            lines.append("Mise à jour forcée pendant cette session.")
        }
        return lines.joined(separator: "\n")
    }
}

/// The favourites as chips: the round picture of the top base map of each, its name; the one applied is filled. A tap
/// applies it (the panel stays open); a long press offers « Appliquer » and « Supprimer ». « Rétablir (n) » last brings
/// back the provided ones deleted.
private struct MapsFavoriteChips: View {
    @Environment(AppModel.self) private var model
    let onMessage: (MapsMessage) -> Void

    var body: some View {
        let maps = model.maps
        let applied = maps.appliedFavorite?.id
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(maps.favorites, id: \.id) { favorite in
                    chip(favorite, selected: favorite.id == applied)
                }
                if !maps.hiddenBuiltins.isEmpty {
                    Button {
                        maps.restoreBuiltinWorkspaces()
                    } label: {
                        Label("Rétablir (\(maps.hiddenBuiltins.count))", systemImage: "arrow.counterclockwise")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 12)
                            .frame(height: 32)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Crete.outline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }

    private func chip(_ favorite: Workspace, selected: Bool) -> some View {
        let maps = model.maps
        // The top map that is not an overlay: what the favourite shows first.
        let cover = favorite.layers.reversed().first { maps.source($0.sourceId)?.isLayer == false }?.sourceId
            ?? favorite.layers.last?.sourceId
        return Button {
            let text = maps.applyWorkspace(favorite)
            onMessage(MapsMessage(text: text))
        } label: {
            HStack(spacing: 8) {
                MapsThumbnail(source: cover.flatMap { maps.source($0) }, size: 24, circle: true)
                Text(favorite.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .padding(.leading, 4)
            .padding(.trailing, 12)
            .frame(height: 32)
            .foregroundStyle(selected ? Color.white : Crete.onSurface)
            .background(selected ? Crete.turquoise : Color.white, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? Color.clear : Crete.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .contextMenu {
            Button {
                let text = maps.applyWorkspace(favorite)
                onMessage(MapsMessage(text: text))
            } label: {
                Label("Appliquer", systemImage: "checkmark.circle")
            }
            Button(role: .destructive) {
                maps.deleteWorkspace(favorite)
                onMessage(MapsMessage(text: "Favori supprimé : \(favorite.name)", action: "Annuler") {
                    maps.restoreWorkspace(favorite)
                })
            } label: {
                Label("Supprimer", systemImage: "trash")
            }
        }
    }
}

/// « Hors ligne » and « Couverture », two switches of the same kind side by side.
private struct MapsSwitchCells: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let maps = model.maps
        HStack(spacing: 8) {
            cell("Hors ligne", isOn: Binding(get: { model.offline }, set: { maps.setOffline($0) }))
                .accessibilityLabel("Mode hors ligne, tuiles stockées seulement")
            cell("Couverture", isOn: Binding(get: { maps.coverage }, set: { maps.coverage = $0 }))
        }
        .padding(.top, 4)
    }

    private func cell(_ label: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(Crete.petrol)
                .lineLimit(1)
        }
        .tint(Crete.turquoise)
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .frame(maxWidth: .infinity, minHeight: 56)
        .background(Crete.tile, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// The four ways out of the panel, in a 2 × 2 grid: icon and name on one line.
private struct MapsNavTiles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let maps = model.maps
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                tile("map", "Cartes disponibles") {
                    maps.openFromPanel(.availableMaps)
                }
                tile("crop", "Enregistrer une zone") {
                    model.panel = nil
                    model.tool = .zone(around: nil)
                }
            }
            HStack(spacing: 8) {
                tile("arrow.down.circle", "Téléchargements") {
                    maps.openFromPanel(.downloads)
                }
                tile("internaldrive", "Stockage") {
                    maps.openFromPanel(.storage)
                }
            }
        }
        .padding(.bottom, 12)
    }

    private func tile(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .medium))
                    .frame(width: 22)
                Text(label)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
            }
            .padding(.leading, 12)
            .padding(.trailing, 4)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(Crete.petrol)
            .background(Crete.tile, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
