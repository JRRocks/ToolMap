import SwiftUI
import ToolMapCore
import UIKit
import UniformTypeIdentifiers

/// Where « Cartes disponibles » goes inside its navigation stack.
enum AvailableMapsRoute: Hashable {
    case catalog
}

/// A map to open in the wizard: a new one of `kind`, or `sourceId` (modified when the user added it, copied else).
struct WizardRequest: Identifiable {
    let id = UUID()
    let kind: WizardKind
    let sourceId: String?
}

/// What the multi-zoom editor opens on.
struct MultiZoomRequest: Identifiable {
    enum Start {
        case new
        case edit(String)
        case derive(String)
    }

    let id = UUID()
    let start: Start
}

/// A file to hand to another app (« Exporter en .aqx… »).
struct MapsShareFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// The share sheet of iOS for a file.
struct MapsShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Cartes disponibles (Android §5.3, `AvailableMapsScreen.kt`, P29.6 « Bascule »): every map of the catalogue by family
/// and the way to show it. Two segments: « Fonds de carte », where a radio says which base is shown (the lowest of the
/// stack, the one a tap replaces), and « Calques », where a switch says whether a layer is shown, with « Annuler » in its
/// message. The search goes through the list and the online catalogue; ⋮ leads to the storage and the hidden maps;
/// « Ajouter une carte » to the online catalogue, the wizard, the multi-zoom editor and the file import.
struct AvailableMapsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var path: [AvailableMapsRoute] = []
    @State private var wizard: WizardRequest? = nil
    @State private var multiZoom: MultiZoomRequest? = nil
    @State private var message: MapsMessage? = nil

    init() {}

    var body: some View {
        NavigationStack(path: $path) {
            AvailableMapsList(
                message: $message,
                openRoute: { path.append($0) },
                openWizard: { wizard = $0 },
                openMultiZoom: { multiZoom = $0 }
            )
            .navigationDestination(for: AvailableMapsRoute.self) { route in
                destination(route)
            }
        }
        .overlay(alignment: .bottom) {
            MapsMessageBar(message: $message)
                .padding(.bottom, 72)
        }
        .animation(.easeOut(duration: 0.2), value: message?.id)
        .sheet(item: $wizard) { request in
            SourceWizardScreen(request: request) { saved in
                finishWizard(saved)
            }
            .environment(model)
        }
        .sheet(item: $multiZoom) { request in
            MultiZoomEditorScreen(request: request) { saved in
                finishMultiZoom(saved)
            }
            .environment(model)
        }
    }

    @ViewBuilder
    private func destination(_ route: AvailableMapsRoute) -> some View {
        switch route {
        case .catalog:
            OnlineCatalogScreen(message: $message)
        }
    }

    /// The wizard closed on a saved map, to be shown on the map when « Ajouter la carte à la carte affichée » is on.
    private func finishWizard(_ saved: WizardResult?) {
        guard let saved, saved.show, let map = model.maps.source(saved.id) else { return }
        model.maps.showMap(saved.id)
        message = MapsMessage(text: "Carte affichée : \(map.name)")
    }

    /// A multi-zoom map was saved: it is shown, « Carte affichée : X » (P29.6).
    private func finishMultiZoom(_ saved: String?) {
        guard let saved, let map = model.maps.source(saved) else { return }
        model.maps.showMap(saved)
        message = MapsMessage(text: "Carte affichée : \(map.name)")
    }
}

/// The two segments of the screen (P29.6).
private enum MapsSegment: Hashable {
    case bases
    case layers
}

/// The ways to add a map of the sheet « Ajouter une carte ».
private enum MapsAddChoice: Hashable {
    case catalog
    case xyz
    case wmts
    case wms
    case multiZoom
    case importFile
}

/// What the commands of a row and of its menu do.
private enum MapsRowAction {
    case pick
    case addLayer
    case hide
    case edit
    case derive
    case delete
    case saveZone
    case clear
    case export
    case hideFromList
    case showInList
}

/// A map of the online catalogue that asks for API keys.
struct CatalogKeyRequest: Identifiable {
    let id = UUID()
    let entry: CatalogEntry
}

private struct AvailableMapsList: View {
    @Environment(AppModel.self) private var model
    @Binding var message: MapsMessage?
    let openRoute: (AvailableMapsRoute) -> Void
    let openWizard: (WizardRequest) -> Void
    let openMultiZoom: (MultiZoomRequest) -> Void

    @State private var segment = MapsSegment.bases
    @State private var collapsed: Set<GroupKind> = []
    @State private var query = ""
    @State private var toDelete: TileSource? = nil
    @State private var toClear: TileSource? = nil
    @State private var addSheet = false
    @State private var pendingAdd: MapsAddChoice? = nil
    @State private var importing = false
    @State private var asking: CatalogKeyRequest? = nil
    @State private var shareFile: MapsShareFile? = nil
    @State private var importNotice: String? = nil

    var body: some View {
        withDialogs(page)
    }

    private var page: some View {
        list
            .navigationTitle("Cartes disponibles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ScreenCloseButton()
                }
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("Cartes disponibles")
                            .font(.headline)
                        Text(statusLine)
                            .font(.caption)
                            .foregroundStyle(Crete.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    barMenu
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Rechercher une carte")
            .safeAreaInset(edge: .bottom) {
                addButton
            }
            .onAppear {
                model.maps.refreshStorage()
            }
    }

    private func withDialogs(_ content: some View) -> some View {
        content
            .sheet(isPresented: $addSheet, onDismiss: runPendingAdd) {
                MapsAddSheet { choice in
                    pendingAdd = choice
                    addSheet = false
                }
            }
            .sheet(item: $asking) { request in
                CatalogKeySheet(entry: request.entry) { keys in
                    addFromCatalog(request.entry, keys)
                }
            }
            .sheet(item: $shareFile) { file in
                MapsShareSheet(url: file.url)
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: AvailableMapsList.importTypes) { result in
                handleImport(result)
            }
            .alert("Supprimer cette carte ?", isPresented: deleteShown, presenting: toDelete) { source in
                Button("Supprimer la carte", role: .destructive) {
                    model.maps.deleteSource(source.id)
                }
                Button("Annuler", role: .cancel) {}
            } message: { source in
                Text("« \(source.name) » sera retirée de ToolMap. Ses tuiles stockées restent sur l'appareil jusqu'à leur effacement dans « Stockage hors ligne ».")
            }
            .alert("Vider les tuiles stockées", isPresented: clearShown, presenting: toClear) { source in
                Button("Tout effacer", role: .destructive) {
                    clearTiles(source)
                }
                Button("Annuler", role: .cancel) {}
            } message: { source in
                Text("Effacer toutes les tuiles de « \(source.name) » ? Elles ne seront plus disponibles hors ligne.")
            }
            .alert("Importer une carte", isPresented: importShown) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importNotice ?? "")
            }
    }

    // MARK: List

    private var searchText: String {
        query.trimmingCharacters(in: .whitespaces)
    }

    private var list: some View {
        let maps = model.maps
        let text = searchText
        let groups = listedGroups(text)
        let found: [CatalogEntry] = text.isEmpty ? [] : maps.catalogMatches(text)
        let hidden = maps.hiddenMaps
        let empty: String = text.isEmpty ? "Aucune carte dans le catalogue." : "Aucune carte ne correspond à « \(text) »."
        return List {
            if text.isEmpty {
                Section {
                    Picker("Segment", selection: $segment) {
                        Text("Fonds de carte").tag(MapsSegment.bases)
                        Text("Calques").tag(MapsSegment.layers)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            if groups.isEmpty, found.isEmpty {
                Text(empty)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            ForEach(groups, id: \.kind) { group in
                groupSection(group, searching: !text.isEmpty, hidden: hidden)
            }
            if !found.isEmpty {
                catalogSection(found)
            }
            if !text.isEmpty, !groups.isEmpty {
                Text("La recherche porte sur le nom et le groupe.")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .listRowBackground(Color.clear)
            }
        }
        .listStyle(.insetGrouped)
    }

    private func groupSection(_ group: MapGroup, searching: Bool, hidden: Set<String>) -> some View {
        // The results of a search are never folded away.
        let open = searching || !collapsed.contains(group.kind)
        return Section {
            if open {
                ForEach(group.sources, id: \.id) { source in
                    row(source, searching: searching, hidden: hidden.contains(source.id))
                }
            }
        } header: {
            MapsGroupHeader(group: group, open: open, storage: model.maps.storage) {
                if collapsed.contains(group.kind) {
                    collapsed.remove(group.kind)
                } else {
                    collapsed.insert(group.kind)
                }
            }
        }
    }

    private func row(_ source: TileSource, searching: Bool, hidden: Bool) -> some View {
        let maps = model.maps
        let base = maps.isBase(source.id)
        // In « Calques », a base shown above the lowest one is a layer like the others.
        let asBase = base && (segment == .bases || searching)
        let displayed = maps.stack.layers.contains { $0.sourceId == source.id }
        let selected = asBase ? source.id == maps.bottomBase : displayed
        return MapsRowView(
            source: source,
            base: base,
            asBase: asBase,
            selected: selected,
            displayed: displayed,
            hidden: hidden,
            imported: maps.isImported(source.id),
            stored: maps.stored(source.id)
        ) { action in
            perform(action, source, asBase: asBase, displayed: displayed)
        }
    }

    private func catalogSection(_ found: [CatalogEntry]) -> some View {
        let added = model.maps.catalogAdded()
        return Section {
            ForEach(found, id: \.source.id) { entry in
                CatalogEntryRow(entry: entry, added: added.contains(entry.source.id)) {
                    if entry.keyParams.isEmpty {
                        addFromCatalog(entry, [:])
                    } else {
                        asking = CatalogKeyRequest(entry: entry)
                    }
                }
            }
        } header: {
            Text("Dans le catalogue en ligne")
                .textCase(nil)
        }
    }

    /// The sections of the segment, or of the search (both segments).
    private func listedGroups(_ text: String) -> [MapGroup] {
        let maps = model.maps
        let all = maps.groups()
        if !text.isEmpty {
            return MapCatalog.search(all, text, title: { MapsText.groupTitle($0) })
        }
        let bottom = maps.bottomBase
        let shown = Set(maps.stack.layers.map(\.sourceId))
        var result: [MapGroup] = []
        for group in all {
            let kept = group.sources.filter { inSegment($0, bottom: bottom, shown: shown) }
            if !kept.isEmpty {
                result.append(MapGroup(kind: group.kind, sources: kept))
            }
        }
        return result
    }

    /// The bases in « Fonds de carte »; in « Calques » the layers and the bases shown above the lowest one.
    private func inSegment(_ source: TileSource, bottom: String?, shown: Set<String>) -> Bool {
        let base = model.maps.isBase(source.id)
        switch segment {
        case .bases: return base
        case .layers: return !base || (shown.contains(source.id) && source.id != bottom)
        }
    }

    /// « Fond : IGN Cartes · 1 calque ».
    private var statusLine: String {
        let maps = model.maps
        let bottom = maps.bottomBase
        let layers = maps.stack.layers.filter { $0.sourceId != bottom }.count
        let name = bottom.flatMap { maps.source($0)?.name }
        return MapsText.status(base: name, layers: layers)
    }

    // MARK: Bar and button

    /// ⋮ of the bar: the storage (with its figures) and the hidden maps.
    private var barMenu: some View {
        let maps = model.maps
        let hiddenCount = maps.hiddenMaps.count
        let showLabel: String = hiddenCount < 2
            ? "Afficher la carte masquée (\(hiddenCount))"
            : "Afficher les cartes masquées (\(hiddenCount))"
        return Menu {
            Button {
                maps.openFromAvailableMaps(.storage)
            } label: {
                Text("Stockage hors ligne…")
                if let figures = maps.storage {
                    Text("\(MapsText.bytes(figures.tilesBytes)) de tuiles · \(MapsText.bytes(figures.freeBytes)) libres")
                }
            }
            if hiddenCount > 0 {
                Toggle(isOn: Binding(get: { maps.showHidden }, set: { maps.showHidden = $0 })) {
                    Text(showLabel)
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Plus d'options")
    }

    private var addButton: some View {
        HStack {
            Spacer()
            Button {
                addSheet = true
            } label: {
                Label("Ajouter une carte", systemImage: "plus")
                    .font(.headline)
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                    .background(Crete.turquoise, in: Capsule())
                    .shadow(color: .black.opacity(0.2), radius: 6, y: 2)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: Actions

    private func perform(_ action: MapsRowAction, _ source: TileSource, asBase: Bool, displayed: Bool) {
        let maps = model.maps
        switch action {
        case .pick:
            if asBase {
                if source.id != maps.bottomBase { showWithUndo(source) }
            } else if displayed {
                maps.hideSource(source.id)
            } else {
                showWithUndo(source)
            }
        case .addLayer:
            maps.addLayer(source.id)
            message = MapsMessage(text: "Carte affichée : \(source.name)")
        case .hide:
            maps.hideSource(source.id)
        case .edit:
            if source.isMultiZoom {
                openMultiZoom(MultiZoomRequest(start: .edit(source.id)))
            } else {
                openWizard(WizardRequest(kind: .xyz, sourceId: source.id))
            }
        case .derive:
            openMultiZoom(MultiZoomRequest(start: .derive(source.id)))
        case .delete:
            toDelete = source
        case .saveZone:
            // Back to the map, in « Enregistrer une zone » with this map alone checked (F4).
            maps.closeScreenToMap()
            model.downloads.askZone(only: source.id)
        case .clear:
            toClear = source
        case .export:
            if let url = maps.exportFile(source.id) {
                shareFile = MapsShareFile(url: url)
            } else {
                message = MapsMessage(text: "Opération impossible : \(source.name)")
            }
        case .hideFromList:
            maps.hideFromList(source.id)
        case .showInList:
            maps.showInList(source.id)
        }
    }

    /// « Carte affichée : X », with « Annuler », which puts the stack back as it was.
    private func showWithUndo(_ source: TileSource) {
        let maps = model.maps
        let before = maps.stack
        maps.showMap(source.id)
        message = MapsMessage(text: "Carte affichée : \(source.name)", action: "Annuler") {
            maps.restoreStack(before)
        }
    }

    private func addFromCatalog(_ entry: CatalogEntry, _ keys: [String: String]) {
        let maps = model.maps
        guard maps.addFromCatalog(entry, keys: keys) else { return }
        let id = entry.source.id
        message = MapsMessage(text: "« \(entry.source.name) » ajoutée à vos cartes.", action: "Afficher") {
            maps.showMap(id)
        }
    }

    private func clearTiles(_ source: TileSource) {
        let name = source.name
        model.maps.clearTiles(source.id) { cleared in
            message = MapsMessage(text: cleared ? "Tuiles effacées" : "Opération impossible : \(name)")
        }
    }

    private func runPendingAdd() {
        guard let choice = pendingAdd else { return }
        pendingAdd = nil
        switch choice {
        case .catalog: openRoute(.catalog)
        case .xyz: openWizard(WizardRequest(kind: .xyz, sourceId: nil))
        case .wmts: openWizard(WizardRequest(kind: .wmts, sourceId: nil))
        case .wms: openWizard(WizardRequest(kind: .wms, sourceId: nil))
        case .multiZoom: openMultiZoom(MultiZoomRequest(start: .new))
        case .importFile: importing = true
        }
    }

    /// One picker for both imports: a `.mbtiles` package goes to the downloads, an `.aqx` file to the catalogue.
    private func handleImport(_ result: Result<URL, Error>) {
        switch result {
        case let .success(url):
            if url.pathExtension.lowercased() == "mbtiles" {
                // A package goes to the storage, which says where it is kept (Android P29.6).
                model.downloads.importMbTiles(url)
                model.maps.openFromAvailableMaps(.storage)
            } else {
                importNotice = model.maps.importMaps(from: url)
            }
        case let .failure(error):
            importNotice = "Import impossible : \(error.localizedDescription)"
        }
    }

    static var importTypes: [UTType] {
        var types: [UTType] = []
        if let aqx = UTType("fr.toolmap.aqx") { types.append(aqx) }
        if let mbtiles = UTType("fr.toolmap.mbtiles") { types.append(mbtiles) }
        types.append(.xml)
        types.append(.data)
        return types
    }

    private var deleteShown: Binding<Bool> {
        Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })
    }

    private var clearShown: Binding<Bool> {
        Binding(get: { toClear != nil }, set: { if !$0 { toClear = nil } })
    }

    private var importShown: Binding<Bool> {
        Binding(get: { importNotice != nil }, set: { if !$0 { importNotice = nil } })
    }
}

/// The title of a section, its credit, the size of its tiles, and the arrow that folds it.
private struct MapsGroupHeader: View {
    let group: MapGroup
    let open: Bool
    let storage: MapsStorageFigures?
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(MapsText.groupTitle(group.kind))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Crete.turquoise)
                    if let credit = group.credit {
                        Text(credit)
                            .font(.caption)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                Spacer()
                if bytes > 0 {
                    Text(MapsText.bytes(bytes))
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Image(systemName: open ? "chevron.up" : "chevron.down")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .textCase(nil)
        .accessibilityHint(open ? "Replier le groupe" : "Déplier le groupe")
    }

    private var bytes: Int64 {
        guard let storage else { return 0 }
        var total: Int64 = 0
        for source in group.sources {
            total += storage.perSource[source.id]?.bytes ?? 0
        }
        return total
    }
}

/// A map of the catalogue: thumbnail, name, zooms (or why it cannot be shown, or « Masquée de la liste »), what is
/// stored, the radio of a base or the switch of a layer, and its ⋮ (eight commands at most).
private struct MapsRowView: View {
    let source: TileSource
    let base: Bool
    let asBase: Bool
    let selected: Bool
    let displayed: Bool
    let hidden: Bool
    let imported: Bool
    let stored: MapsStoredSize?
    let onAction: (MapsRowAction) -> Void

    var body: some View {
        let enabled = source.isSupported && !hidden
        HStack(spacing: 10) {
            Button {
                onAction(.pick)
            } label: {
                HStack(spacing: 12) {
                    MapsThumbnail(source: source, size: 48)
                    texts
                    Spacer(minLength: 4)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .accessibilityAddTraits(selected ? .isSelected : [])
            control(enabled: enabled)
            Menu {
                menuItems
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 30, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Menu de la carte")
        }
        .opacity(hidden ? 0.55 : 1)
    }

    private var texts: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(source.name)
                .font(.body)
                .foregroundStyle(Crete.onSurface)
                .lineLimit(2)
            Text(hidden ? "Masquée de la liste" : MapsText.zooms(source))
                .font(.caption)
                .foregroundStyle(Crete.onSurfaceVariant)
            if !hidden, let stored {
                Text("\(MapsText.bytes(stored.bytes)) sur l'appareil")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
    }

    @ViewBuilder
    private func control(enabled: Bool) -> some View {
        if asBase {
            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                .font(.title3)
                .foregroundStyle(enabled ? Crete.turquoise : Crete.outline)
                .accessibilityHidden(true)
        } else {
            Toggle("Afficher", isOn: Binding(get: { selected }, set: { _ in onAction(.pick) }))
                .labelsHidden()
                .tint(Crete.turquoise)
                .disabled(!enabled)
        }
    }

    @ViewBuilder
    private var menuItems: some View {
        if hidden {
            // A hidden map only comes back to the list (F3).
            Button {
                onAction(.showInList)
            } label: {
                Label("Remettre dans la liste", systemImage: "eye")
            }
        } else {
            showItems
            editItems
            Divider()
            fileItems
            Divider()
            if imported {
                Button(role: .destructive) {
                    onAction(.delete)
                } label: {
                    Label("Supprimer la carte", systemImage: "trash")
                }
            } else {
                // F3: a provided map cannot be deleted, it leaves the list.
                Button {
                    onAction(.hideFromList)
                } label: {
                    Label("Masquer de la liste", systemImage: "eye.slash")
                }
            }
        }
    }

    /// A base keeps « Ajouter comme calque » and « Ne plus afficher »: its radio only replaces the base.
    @ViewBuilder
    private var showItems: some View {
        if base {
            Button {
                onAction(.addLayer)
            } label: {
                Label("Ajouter comme calque", systemImage: "square.3.layers.3d")
            }
            .disabled(!source.isSupported)
            if displayed {
                Button {
                    onAction(.hide)
                } label: {
                    Label("Ne plus afficher", systemImage: "eye.slash")
                }
            }
        }
    }

    @ViewBuilder
    private var editItems: some View {
        if source.isMultiZoom {
            Button {
                onAction(.edit)
            } label: {
                Label(imported ? "Modifier les zooms" : "Copier pour modifier les zooms", systemImage: "pencil")
            }
        } else {
            if SourceDraft.fromSource(source) != nil {
                Button {
                    onAction(.edit)
                } label: {
                    Label(imported ? "Modifier cette carte" : "Copier pour modifier", systemImage: "pencil")
                }
            }
            if source.isSupported {
                Button {
                    onAction(.derive)
                } label: {
                    Label("Créer une carte multi-zoom à partir de celle-ci", systemImage: "square.stack.3d.up")
                }
            }
        }
    }

    /// F4: the zone, the stored tiles and the file of this map.
    @ViewBuilder
    private var fileItems: some View {
        if source.isSupported, !source.isLocalOnly {
            Button {
                onAction(.saveZone)
            } label: {
                Label("Télécharger une zone…", systemImage: "arrow.down.circle")
            }
        }
        if let stored, stored.tiles > 0, !source.isMultiZoom {
            Button(role: .destructive) {
                onAction(.clear)
            } label: {
                Text("Vider les tuiles stockées")
                Text(MapsText.tiles(stored.tiles))
            }
        }
        if !source.isMultiZoom, !source.isLocalOnly {
            Button {
                onAction(.export)
            } label: {
                Label("Exporter en .aqx…", systemImage: "square.and.arrow.up")
            }
        }
    }
}

/// « Ajouter une carte » (P29.6): every way to add a map in one sheet, each with what it is for.
private struct MapsAddSheet: View {
    let onChoose: (MapsAddChoice) -> Void

    var body: some View {
        NavigationStack {
            List {
                row(.catalog, "globe", "Catalogue en ligne", "Cartes de grands fournisseurs, ajoutées en un geste.")
                row(
                    .xyz,
                    "map",
                    "Adresse de tuiles (XYZ)",
                    "Une adresse avec {$z}, {$x} et {$y}, comme celles d'OpenStreetMap. Le cas le plus courant."
                )
                row(
                    .wmts,
                    "square.3.layers.3d",
                    "Service WMTS",
                    "L'adresse d'un service : ToolMap lit la liste des couches et prépare l'adresse des tuiles (IGN Géoplateforme, par exemple)."
                )
                row(
                    .wms,
                    "square.3.layers.3d",
                    "Service WMS",
                    "Un service d'images à la demande : ToolMap lit les couches et prépare des requêtes en Web Mercator (EPSG:3857)."
                )
                row(
                    .multiZoom,
                    "square.stack.3d.up",
                    "Nouvelle carte multi-zoom",
                    "Une carte faite d'autres cartes, une par plage de zooms."
                )
                row(
                    .importFile,
                    "doc.badge.plus",
                    "Importer un fichier (.aqx, .mbtiles)",
                    "Un fichier de cartes AlpineQuest, ou une carte déjà téléchargée, utilisable sans réseau."
                )
            }
            .listStyle(.plain)
            .navigationTitle("Ajouter une carte")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ choice: MapsAddChoice, _ icon: String, _ title: String, _ detail: String) -> some View {
        Button {
            onChoose(choice)
        } label: {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 40, height: 40)
                    .background(Crete.tile, in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(Crete.onSurface)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}
