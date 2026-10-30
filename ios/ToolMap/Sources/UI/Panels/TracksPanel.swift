import SwiftUI
import ToolMapCore
import UIKit

/// Traces et waypoints (Android §6.0, `TracksMenuPanel`, P29.4 « Topo » and « Repère »): the search as a field, three
/// tiles for what is made at the centre (a waypoint, a track, a route), then the list with what the library holds,
/// the import of a file and the switch that shows or hides everything. A command closes the panel; the switch does
/// not.
struct TracksPanel: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false

    var body: some View {
        VStack(spacing: 0) {
            PanelHeader(title: "Traces et waypoints")
            ScrollView {
                VStack(spacing: 12) {
                    TracksPanelSearchField {
                        model.library.openScreen(.search)
                    }
                    TracksPanelTiles()
                    TracksPanelRows(importing: $importing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .fileImporter(
            isPresented: $importing,
            allowedContentTypes: LibraryFiles.importTypes,
            allowsMultipleSelection: true
        ) { result in
            if case let .success(urls) = result {
                model.panel = nil
                model.library.importFiles(urls)
            }
        }
    }
}

/// « Lieu, trace ou waypoint »: a touch opens the search page, where the typing is done.
private struct TracksPanelSearchField: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(Crete.onSurfaceVariant)
                Text("Lieu, trace ou waypoint")
                    .foregroundStyle(Crete.onSurfaceVariant)
                Spacer()
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 48)
            .background(Crete.surfaceVariant, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Waypoint ici (greyed while a line is drawn) · Dessiner une trace · Dessiner un itinéraire (greyed while drawing or
/// measuring).
private struct TracksPanelTiles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let drawing = isDrawing
        let free = !drawing && model.tool != .measure
        HStack(spacing: 8) {
            ToolTile(systemImage: "mappin.and.ellipse", title: "Waypoint ici", enabled: !drawing) {
                model.panel = nil
                model.library.quickWaypoint()
            }
            ToolTile(systemImage: LibraryIcons.track, title: "Dessiner une trace", enabled: free) {
                model.library.startTool(.draw(.track(nil)))
            }
            ToolTile(systemImage: LibraryIcons.route, title: "Dessiner un itinéraire", enabled: free) {
                model.library.startTool(.draw(.route(nil)))
            }
        }
    }

    private var isDrawing: Bool {
        if case .some(.draw(_)) = model.tool {
            return true
        }
        return false
    }
}

/// The list with its counts, the import, and « Tout afficher sur la carte ».
private struct TracksPanelRows: View {
    @Environment(AppModel.self) private var model
    @Binding var importing: Bool

    var body: some View {
        let lib = model.library.library
        let counts = LibraryText.counts(
            tracks: lib?.tracks.count ?? 0,
            routes: lib?.routes.count ?? 0,
            waypoints: lib?.waypoints.count ?? 0
        )
        let allVisible = lib.map { LibraryListModel.allVisible($0) } ?? true
        VStack(spacing: 0) {
            TracksPanelRow(icon: "list.bullet", title: "Liste des traces et waypoints", subtitle: counts, chevron: true) {
                model.library.openScreen(.library)
            }
            Divider()
                .padding(.leading, 56)
            TracksPanelRow(icon: LibraryIcons.importFile, title: "Importer un fichier…", subtitle: LibraryText.importTypes) {
                importing = true
            }
            Divider()
                .padding(.leading, 56)
            Toggle(isOn: Binding(get: { allVisible }, set: { model.library.setAllVisible($0) })) {
                HStack(spacing: 14) {
                    Image(systemName: allVisible ? "eye" : "eye.slash")
                        .font(.system(size: 18))
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .frame(width: 26)
                    Text("Tout afficher sur la carte")
                        .foregroundStyle(Crete.onSurface)
                }
            }
            .tint(Crete.turquoise)
            .padding(.horizontal, 16)
            .frame(minHeight: 52)
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// A row of the panel: a grey icon, the name, its second line, a chevron when it opens a page.
private struct TracksPanelRow: View {
    let icon: String
    let title: String
    var subtitle: String?
    var chevron = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(Crete.onSurface)
                    if let subtitle {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 8)
                if chevron {
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
