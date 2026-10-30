import SwiftUI
import ToolMapCore
import UIKit

/// One line of the list: a folder or an item.
struct LibraryRowView: View {
    let row: LibraryListRow
    /// nil outside the selection mode.
    let selected: Set<ItemRefKey>?
    let actions: LibraryRowActions

    var body: some View {
        switch row {
        case let .folder(line):
            LibraryFolderRow(line: line, selecting: selected != nil, actions: actions)
        case let .item(line):
            LibraryItemRow(line: line, selected: selected, actions: actions)
        }
    }
}

/// A folder (P29.4): arrow, folder, name, « Dossier · 5 éléments », eye, ⋮. A touch folds or unfolds it.
private struct LibraryFolderRow: View {
    @Environment(AppModel.self) private var model
    let line: LibraryFolderLine
    let selecting: Bool
    let actions: LibraryRowActions

    var body: some View {
        let folder = line.folder
        HStack(spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: line.expanded ? "chevron.down" : "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 16)
                Image(systemName: "folder.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(folder.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Crete.onSurface)
                    Text(LibraryText.folderItems(line.itemCount))
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer(minLength: 4)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
            .onTapGesture { actions.toggleFolder(folder.id) }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(line.expanded ? "replier" : "déplier")
            if !selecting {
                LibraryEyeButton(visible: folder.visible, hiddenAbove: !line.parentsShown) { visible in
                    model.library.setFolderVisible(folder.id, visible)
                }
                LibraryFolderMenu(folder: folder, actions: actions)
            }
        }
        .padding(.leading, CGFloat(line.depth) * 20)
    }
}

private struct LibraryFolderMenu: View {
    @Environment(AppModel.self) private var model
    let folder: FolderEntity
    let actions: LibraryRowActions

    var body: some View {
        let selection = TrackSelection(folderIds: [folder.id])
        Menu {
            Button {
                actions.dialog(.renameFolder(folder.id, current: folder.name))
            } label: {
                Label("Renommer", systemImage: "pencil")
            }
            Button {
                actions.dialog(.newFolder(parent: folder.id))
            } label: {
                Label("Nouveau sous-dossier", systemImage: "folder.badge.plus")
            }
            Button {
                actions.dialog(.moveFolder(folder.id, name: folder.name, parent: folder.parentId))
            } label: {
                Label("Déplacer vers…", systemImage: "folder")
            }
            Button {
                actions.export(selection, folder.name)
            } label: {
                Label("Exporter en GPX…", systemImage: LibraryIcons.importFile)
            }
            if let share = model.library.share(selection, name: folder.name) {
                ShareLink(item: share, preview: SharePreview(share.fileName)) {
                    Label("Partager", systemImage: LibraryIcons.share)
                }
            }
            Button(role: .destructive) {
                actions.dialog(.deleteFolder(folder.id, name: folder.name))
            } label: {
                Label("Supprimer", systemImage: "trash")
            }
        } label: {
            LibraryMenuLabel()
        }
    }
}

/// A track, a route or a waypoint (P29.4): the mark it has on the map, the name on two lines at most, the figures,
/// an eye and a ⋮; a hidden item is faded. In the selection mode a touch picks or drops it; a long press starts it.
private struct LibraryItemRow: View {
    @Environment(AppModel.self) private var model
    let line: LibraryItemLine
    let selected: Set<ItemRefKey>?
    let actions: LibraryRowActions

    var body: some View {
        let selecting = selected != nil
        let picked = selected?.contains(line.ref) == true
        let subtitle = LibraryListModel.subtitle(line, model.settings)
        HStack(spacing: 4) {
            HStack(spacing: 10) {
                LibraryItemMarkSlot(line: line, selecting: selecting, picked: picked)
                VStack(alignment: .leading, spacing: 2) {
                    Text(line.name ?? LibraryText.unnamed)
                        .foregroundStyle(Crete.onSurface)
                        .lineLimit(2)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                .opacity(line.shown || selecting ? 1 : 0.45)
                Spacer(minLength: 4)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
            .onTapGesture { actions.open(line) }
            .onLongPressGesture(minimumDuration: 0.45) { actions.toggleSelection(line.ref) }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(kindName), \(line.name ?? LibraryText.unnamed), \(subtitle)")
            .accessibilityAddTraits(picked ? LibraryItemRow.pickedTraits : AccessibilityTraits.isButton)
            .accessibilityAction(named: "Sélectionner") { actions.toggleSelection(line.ref) }
            if !selecting {
                LibraryEyeButton(visible: line.visible, hiddenAbove: line.visible && !line.shown) { visible in
                    model.library.setVisible(line.ref, visible)
                }
                LibraryItemMenu(line: line, actions: actions)
            }
        }
        .padding(.leading, CGFloat(line.depth) * 20)
        .listRowBackground(picked ? Crete.pill.opacity(0.35) : Color(uiColor: .secondarySystemGroupedBackground))
    }

    private static let pickedTraits: AccessibilityTraits = [.isButton, .isSelected]

    private var kindName: String {
        switch line.ref.kind {
        case .track: return "Trace"
        case .route: return "Itinéraire"
        case .waypoint: return "Waypoint"
        }
    }
}

/// The mark of an item, or the ring of the selection mode.
private struct LibraryItemMarkSlot: View {
    let line: LibraryItemLine
    let selecting: Bool
    let picked: Bool

    var body: some View {
        if selecting {
            Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                .font(.system(size: 22))
                .foregroundStyle(picked ? Crete.turquoise : Crete.outline)
                .frame(width: 40, height: 40)
        } else {
            LibraryItemMark(kind: line.ref.kind, colorArgb: line.colorArgb, sym: line.sym)
                .opacity(line.shown ? 1 : 0.45)
        }
    }
}

/// The mark an item has on the map: the badge of a waypoint's category, the icon of a track or a route in the colour
/// of its line.
struct LibraryItemMark: View {
    let kind: ItemRefKey.Kind
    let colorArgb: Int32
    let sym: String?
    var size: CGFloat = 40

    var body: some View {
        switch kind {
        case .waypoint:
            WaypointIconBadge(icon: WaypointSymbols.iconOf(sym), size: size * 0.8)
                .frame(width: size, height: size)
        case .track:
            LibraryLineMark(systemImage: LibraryIcons.track, colorArgb: colorArgb, size: size)
        case .route:
            LibraryLineMark(systemImage: LibraryIcons.route, colorArgb: colorArgb, size: size)
        }
    }
}

/// The ⋮ of a track (Statistiques · Afficher sur la carte · Renommer · Déplacer vers… · Style · Exporter · Partager ·
/// Supprimer), of a route (the same without Statistiques) and of a waypoint (Modifier… first, no Style).
private struct LibraryItemMenu: View {
    @Environment(AppModel.self) private var model
    let line: LibraryItemLine
    let actions: LibraryRowActions

    var body: some View {
        let name = line.name ?? LibraryText.unnamed
        let selection = line.ref.librarySelection
        Menu {
            if line.ref.kind == .track {
                Button {
                    actions.details(line.ref.id)
                } label: {
                    Label("Statistiques", systemImage: "chart.bar")
                }
            }
            if line.ref.kind == .waypoint {
                Button {
                    actions.editWaypoint(line.ref.id)
                } label: {
                    Label("Modifier…", systemImage: "pencil")
                }
            }
            if let bounds = line.bounds {
                Button {
                    model.library.showOnMap(bounds)
                } label: {
                    Label("Afficher sur la carte", systemImage: "map")
                }
            }
            Button {
                actions.dialog(.rename(line.ref, current: line.name ?? ""))
            } label: {
                Label("Renommer", systemImage: "character.cursor.ibeam")
            }
            Button {
                actions.dialog(.move(line.ref, name: name, parent: line.folderId))
            } label: {
                Label("Déplacer vers…", systemImage: "folder")
            }
            if let style = line.style {
                Button {
                    actions.dialog(.style(line.ref, style))
                } label: {
                    Label("Style de la ligne", systemImage: "paintbrush")
                }
            }
            Button {
                actions.export(selection, line.name)
            } label: {
                Label("Exporter en GPX…", systemImage: LibraryIcons.importFile)
            }
            if let share = model.library.share(selection, name: line.name) {
                ShareLink(item: share, preview: SharePreview(share.fileName)) {
                    Label("Partager", systemImage: LibraryIcons.share)
                }
            }
            Button(role: .destructive) {
                actions.dialog(.delete(line.ref, name: name))
            } label: {
                Label("Supprimer", systemImage: "trash")
            }
        } label: {
            LibraryMenuLabel()
        }
    }
}

/// Shows or hides an item or a folder on the map (P29.4): an eye; grey when a folder above hides it.
struct LibraryEyeButton: View {
    let visible: Bool
    let hiddenAbove: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Button {
            onChange(!visible)
        } label: {
            Image(systemName: visible ? "eye" : "eye.slash")
                .font(.system(size: 17))
                .foregroundStyle(visible && !hiddenAbove ? Crete.petrol : Crete.onSurfaceVariant)
                .frame(width: 40, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(hiddenAbove ? "Afficher sur la carte, masqué avec son dossier" : "Afficher sur la carte")
        .accessibilityValue(visible ? "activé" : "désactivé")
    }
}

/// The ⋮ of a line or a sheet.
struct LibraryMenuLabel: View {
    var body: some View {
        Image(systemName: "ellipsis")
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(Crete.onSurfaceVariant)
            .frame(width: 36, height: 44)
            .contentShape(Rectangle())
            .accessibilityLabel("Menu")
    }
}
