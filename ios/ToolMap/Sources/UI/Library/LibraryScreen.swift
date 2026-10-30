import SwiftUI
import ToolMapCore
import UIKit

/// Traces et waypoints, the list (Android §6.1, `TracksScreen`, P29.4 « Topo »): the folders on top, foldable, then the
/// items in the order and of the kind chosen in the chips (kept in the settings); a search by name; the selection of
/// several items by a long press; the ⋮ of each item and folder; the detail of a track pushed in the same page.
struct LibraryScreen: View {
    @Environment(AppModel.self) private var model
    @State private var path: [Int64] = []

    var body: some View {
        NavigationStack(path: $path) {
            LibraryListPage(path: $path)
                .navigationDestination(for: Int64.self) { id in
                    LibraryTrackDetailPage(trackId: id, standalone: false)
                }
        }
    }
}

/// What the lines of the list ask of the page.
struct LibraryRowActions {
    let open: (LibraryItemLine) -> Void
    let toggleSelection: (ItemRefKey) -> Void
    let toggleFolder: (Int64) -> Void
    let dialog: (LibraryDialog) -> Void
    let export: (TrackSelection, String?) -> Void
    let details: (Int64) -> Void
    let editWaypoint: (Int64) -> Void
}

private struct LibraryListPage: View {
    @Environment(AppModel.self) private var model
    @Binding var path: [Int64]
    @State private var searchOpen = false
    @State private var query = ""
    @State private var selected: Set<ItemRefKey> = []
    @State private var dialog: LibraryDialog? = nil
    @State private var exportRequest: LibraryExportRequest? = nil
    @State private var editing: WaypointDraft? = nil
    @State private var importing = false

    var body: some View {
        let lib = model.library.library
        VStack(spacing: 0) {
            if model.library.busy {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            if searchOpen {
                LibrarySearchBar(query: $query)
            }
            content(lib)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Traces et waypoints")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { barItems(lib) }
        .overlay(alignment: .bottom) { LibraryMessageBar() }
        .libraryDialogs($dialog, onDone: { selected = [] })
        .libraryExport($exportRequest)
        .sheet(item: $editing) { draft in
            WaypointDialogSheet(draft: draft)
                .environment(model)
        }
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: LibraryFiles.importTypes,
            allowsMultipleSelection: true
        ) { result in
            if case let .success(urls) = result {
                model.library.importFiles(urls)
            }
        }
        .onChange(of: lib) { _, newValue in
            prune(newValue)
        }
    }

    @ViewBuilder
    private func content(_ lib: TrackLibrary?) -> some View {
        if let lib {
            if lib.isEmpty, !searchOpen {
                LibraryEmptyState(
                    onImport: { importing = true },
                    onDraw: { model.library.startTool(.draw(.track(nil))) }
                )
            } else {
                LibraryListBody(lib: lib, query: query, selected: selected.isEmpty ? nil : selected, actions: actions)
            }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ToolbarContentBuilder
    private func barItems(_ lib: TrackLibrary?) -> some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if selected.isEmpty {
                ScreenCloseButton()
            } else {
                Button {
                    selected = []
                } label: {
                    Image(systemName: "xmark")
                }
                .accessibilityLabel("Quitter la sélection")
            }
        }
        ToolbarItem(placement: .principal) {
            if selected.isEmpty {
                LibraryTitle(title: "Traces et waypoints", subtitle: subtitle(lib))
            } else {
                LibraryTitle(title: LibraryText.selected(selected.count), subtitle: nil)
            }
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if selected.isEmpty {
                Button {
                    searchOpen.toggle()
                    if !searchOpen { query = "" }
                } label: {
                    Image(systemName: "magnifyingglass")
                }
                .accessibilityLabel("Rechercher")
                Button {
                    importing = true
                } label: {
                    Image(systemName: LibraryIcons.importFile)
                }
                .accessibilityLabel("Importer")
            } else {
                Button {
                    toggleVisibility(lib)
                } label: {
                    Image(systemName: "eye")
                }
                .accessibilityLabel("Afficher / masquer sur la carte")
                Button {
                    dialog = .moveMany(Array(selected))
                } label: {
                    Image(systemName: "folder")
                }
                .accessibilityLabel("Déplacer vers…")
                Button {
                    exportSelection()
                } label: {
                    Image(systemName: LibraryIcons.importFile)
                }
                .accessibilityLabel("Exporter en un seul GPX…")
                Button {
                    dialog = .deleteMany(Array(selected))
                } label: {
                    Image(systemName: "trash")
                }
                .accessibilityLabel("Supprimer")
            }
        }
    }

    /// Shows them all when one is hidden, hides them all otherwise.
    private func toggleVisibility(_ lib: TrackLibrary?) {
        guard let lib else { return }
        let lines = LibraryListModel.allItems(lib).filter { selected.contains($0.ref) }
        let show = lines.contains { !$0.visible }
        for ref in selected {
            model.library.setVisible(ref, show)
        }
    }

    /// « Exporter en un seul GPX… » (« Sélection »).
    private func exportSelection() {
        let refs = Array(selected)
        exportRequest = LibraryExportRequest(selection: LibrarySelection.of(refs), name: "Sélection")
        selected = []
    }

    private func subtitle(_ lib: TrackLibrary?) -> String? {
        guard let lib, !lib.isEmpty else { return nil }
        return LibraryText.counts(tracks: lib.tracks.count, routes: lib.routes.count, waypoints: lib.waypoints.count)
    }

    private var actions: LibraryRowActions {
        LibraryRowActions(
            open: { line in openLine(line) },
            toggleSelection: { ref in toggle(ref) },
            toggleFolder: { id in
                if model.library.expandedFolders.contains(id) {
                    model.library.expandedFolders.remove(id)
                } else {
                    model.library.expandedFolders.insert(id)
                }
            },
            dialog: { asked in dialog = asked },
            export: { selection, name in exportRequest = LibraryExportRequest(selection: selection, name: name) },
            details: { id in path.append(id) },
            editWaypoint: { id in edit(id) }
        )
    }

    /// A track opens its detail; a route or a waypoint opens the map framed on it, with its sheet (P29.4).
    private func openLine(_ line: LibraryItemLine) {
        if !selected.isEmpty {
            toggle(line.ref)
            return
        }
        switch line.ref.kind {
        case .track:
            path.append(line.ref.id)
        case .route, .waypoint:
            model.library.reveal(line.ref)
        }
    }

    private func toggle(_ ref: ItemRefKey) {
        if selected.contains(ref) {
            selected.remove(ref)
        } else {
            selected.insert(ref)
        }
    }

    /// « Modifier… » of a waypoint: the dialog of the map, over the list.
    private func edit(_ id: Int64) {
        let library = model.library
        Task {
            if let waypoint = await library.waypoint(id) {
                editing = library.draft(id, waypoint)
            }
        }
    }

    /// An item deleted meanwhile leaves the selection.
    private func prune(_ lib: TrackLibrary?) {
        guard let lib, !selected.isEmpty else { return }
        let known = Set(LibraryListModel.allItems(lib).map { $0.ref })
        let kept = selected.intersection(known)
        if kept != selected {
            selected = kept
        }
    }
}

/// The chips, « Nouveau dossier » and the lines.
private struct LibraryListBody: View {
    @Environment(AppModel.self) private var model
    let lib: TrackLibrary
    let query: String
    /// nil outside the selection mode.
    let selected: Set<ItemRefKey>?
    let actions: LibraryRowActions

    var body: some View {
        let view = model.settings.library
        let rows = LibraryListModel.rows(lib, expanded: model.library.expandedFolders, view: view, query: query)
        VStack(spacing: 0) {
            LibraryChips(view: view)
            if selected == nil {
                HStack {
                    Button {
                        actions.dialog(.newFolder(parent: nil))
                    } label: {
                        Label("Nouveau dossier", systemImage: "plus")
                            .font(.subheadline.weight(.medium))
                    }
                    .foregroundStyle(Crete.petrol)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 4)
            }
            if rows.isEmpty {
                Text(emptyText)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .padding(24)
                Spacer()
            } else {
                List {
                    ForEach(rows) { row in
                        LibraryRowView(row: row, selected: selected, actions: actions)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    private var emptyText: String {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Aucun élément de ce type." : "Aucun élément ne contient « \(trimmed) »."
    }
}

/// « Date ↓ » and its sort menu, then the kinds of item to show (F7); both are kept in the settings.
private struct LibraryChips: View {
    @Environment(AppModel.self) private var model
    let view: LibraryViewSettings

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(LibrarySort.allCases, id: \.self) { sort in
                        Button {
                            model.settings.library.sort = sort
                        } label: {
                            if sort == view.sort {
                                Label(LibraryChips.sortName(sort), systemImage: "checkmark")
                            } else {
                                Text(LibraryChips.sortName(sort))
                            }
                        }
                    }
                } label: {
                    LibraryChip(title: LibraryChips.sortChip(view.sort), systemImage: "arrow.up.arrow.down", selected: true)
                }
                ForEach(LibraryFilter.allCases, id: \.self) { filter in
                    Button {
                        model.settings.library.filter = filter
                    } label: {
                        LibraryChip(title: LibraryChips.filterName(filter), systemImage: nil, selected: view.filter == filter)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    static func sortChip(_ sort: LibrarySort) -> String {
        switch sort {
        case .date: return "Date ↓"
        case .name: return "Nom"
        case .distance: return "Distance ↓"
        }
    }

    static func sortName(_ sort: LibrarySort) -> String {
        switch sort {
        case .date: return "Date (récent d'abord)"
        case .name: return "Nom"
        case .distance: return "Distance"
        }
    }

    static func filterName(_ filter: LibraryFilter) -> String {
        switch filter {
        case .all: return "Tout"
        case .tracks: return "Traces"
        case .routes: return "Itinéraires"
        case .waypoints: return "Waypoints"
        }
    }
}

private struct LibraryChip: View {
    let title: String
    let systemImage: String?
    let selected: Bool

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.caption.weight(.semibold))
            }
            Text(title)
                .font(.subheadline.weight(.medium))
        }
        .foregroundStyle(selected ? Color.white : Crete.onSurface)
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(selected ? Crete.petrol : Color.white, in: Capsule())
        .overlay(Capsule().strokeBorder(selected ? Color.clear : Crete.outline))
        .accessibilityAddTraits(selected ? AccessibilityTraits.isSelected : AccessibilityTraits())
    }
}

/// « Rechercher par nom » (F7), under the bar: it searches the items of every folder.
private struct LibrarySearchBar: View {
    @Binding var query: String
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Crete.onSurfaceVariant)
            TextField("Rechercher par nom", text: $query)
                .focused($focused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                .accessibilityLabel("Effacer la recherche")
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(Color.white, in: Capsule())
        .overlay(Capsule().strokeBorder(Crete.outline))
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .onAppear { focused = true }
    }
}

/// The empty library teaches what goes in it (P29.4): a mark, what will be here, the ways to fill it, and the import
/// as its one filled button.
private struct LibraryEmptyState: View {
    let onImport: () -> Void
    let onDraw: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Image(systemName: LibraryIcons.route)
                    .font(.system(size: 30, weight: .medium))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 64, height: 64)
                    .background(Crete.tile, in: Circle())
                Text("Vos traces et waypoints s'afficheront ici")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .padding(.top, 16)
                Text(
                    "Importez un fichier GPX, KML, KMZ ou GeoJSON, ou ouvrez-le avec ToolMap depuis une autre application. Vous pouvez aussi dessiner une trace sur la carte."
                )
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
                Button(action: onImport) {
                    Label("Importer un fichier…", systemImage: LibraryIcons.importFile)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
                .padding(.top, 24)
                Button("Dessiner une trace sur la carte", action: onDraw)
                    .foregroundStyle(Crete.petrol)
                    .padding(.top, 10)
            }
            .padding(.horizontal, 32)
            .padding(.vertical, 48)
            .frame(maxWidth: .infinity)
        }
    }
}

/// The title of a page with its second line (iOS 17 has no subtitle in the bar).
struct LibraryTitle: View {
    let title: String
    let subtitle: String?

    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .font(.headline)
                .lineLimit(1)
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
