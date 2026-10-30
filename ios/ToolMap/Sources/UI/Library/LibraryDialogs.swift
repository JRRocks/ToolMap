import SwiftUI
import ToolMapCore
import UIKit

/// A question of the library (Android `Dialog` of `TracksScreen` and `TrackDialogs`): a name to type, a confirmation
/// of a deletion, a folder to move to, the style of a line.
enum LibraryDialog: Identifiable {
    case rename(ItemRefKey, current: String)
    case renameFolder(Int64, current: String)
    /// A new folder under `parent` (nil: the top of the library).
    case newFolder(parent: Int64?)
    case describe(ItemRefKey, current: String)
    case style(ItemRefKey, PathStyle)
    /// Move an item now in `parent`.
    case move(ItemRefKey, name: String, parent: Int64?)
    case moveFolder(Int64, name: String, parent: Int64?)
    case moveMany([ItemRefKey])
    case delete(ItemRefKey, name: String)
    case deleteFolder(Int64, name: String)
    case deleteMany([ItemRefKey])

    var id: String {
        switch self {
        case let .rename(ref, _): return "rename-\(ref.kind.rawValue)-\(ref.id)"
        case let .renameFolder(id, _): return "rename-folder-\(id)"
        case let .newFolder(parent): return "new-folder-\(parent ?? 0)"
        case let .describe(ref, _): return "describe-\(ref.kind.rawValue)-\(ref.id)"
        case let .style(ref, _): return "style-\(ref.kind.rawValue)-\(ref.id)"
        case let .move(ref, _, _): return "move-\(ref.kind.rawValue)-\(ref.id)"
        case let .moveFolder(id, _, _): return "move-folder-\(id)"
        case let .moveMany(refs): return "move-many-\(refs.count)"
        case let .delete(ref, _): return "delete-\(ref.kind.rawValue)-\(ref.id)"
        case let .deleteFolder(id, _): return "delete-folder-\(id)"
        case let .deleteMany(refs): return "delete-many-\(refs.count)"
        }
    }

    /// A text to type in an alert.
    var asksText: Bool {
        switch self {
        case .rename, .renameFolder, .newFolder, .describe: return true
        default: return false
        }
    }

    /// A deletion to confirm in an alert.
    var asksDeletion: Bool {
        switch self {
        case .delete, .deleteFolder, .deleteMany: return true
        default: return false
        }
    }

    /// A choice in a sheet (style, folder).
    var isSheet: Bool {
        !asksText && !asksDeletion
    }

    var initialText: String {
        switch self {
        case let .rename(_, current), let .renameFolder(_, current), let .describe(_, current): return current
        default: return ""
        }
    }

    var title: String {
        switch self {
        case .rename, .renameFolder: return "Renommer"
        case let .newFolder(parent): return parent == nil ? "Nouveau dossier" : "Nouveau sous-dossier"
        case .describe: return "Description"
        case .style: return "Style de la ligne"
        case let .move(_, name, _), let .moveFolder(_, name, _): return "Déplacer « \(name) » vers"
        case let .moveMany(refs): return LibraryText.moveMany(refs.count)
        case let .delete(_, name): return "Supprimer « \(name) » ?"
        case let .deleteFolder(_, name): return "Supprimer le dossier « \(name) » ?"
        case let .deleteMany(refs): return LibraryText.deleteMany(refs.count)
        }
    }

    var message: String {
        switch self {
        case .deleteFolder: return LibraryText.deleteFolderFinal
        case .delete, .deleteMany: return LibraryText.deleteFinal
        default: return ""
        }
    }
}

extension View {
    /// The questions of the library: `onDeleted` after a deletion (back to the list from a detail), `onDone` after a
    /// move or a deletion of several items (the selection ends).
    func libraryDialogs(
        _ dialog: Binding<LibraryDialog?>,
        onDeleted: (() -> Void)? = nil,
        onDone: (() -> Void)? = nil
    ) -> some View {
        modifier(LibraryDialogsModifier(dialog: dialog, onDeleted: onDeleted, onDone: onDone))
    }

    /// « Exporter en GPX… »: the GPX of the request saved where the user picks (`fileExporter`).
    func libraryExport(_ request: Binding<LibraryExportRequest?>) -> some View {
        modifier(LibraryExportModifier(request: request))
    }
}

private struct LibraryDialogsModifier: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var dialog: LibraryDialog?
    let onDeleted: (() -> Void)?
    let onDone: (() -> Void)?
    @State private var text = ""

    func body(content: Content) -> some View {
        content
            .alert(dialog?.title ?? "", isPresented: alertShown, presenting: dialog) { asked in
                if asked.asksText {
                    TextField(asked.title, text: $text)
                    Button("Annuler", role: .cancel) {}
                    Button("OK") { confirmText(asked) }
                } else {
                    Button("Supprimer", role: .destructive) { confirmDeletion(asked) }
                    Button("Annuler", role: .cancel) {}
                }
            } message: { asked in
                if !asked.message.isEmpty {
                    Text(asked.message)
                }
            }
            .sheet(item: sheetItem) { asked in
                LibraryDialogSheet(dialog: asked, onDone: onDone)
                    .environment(model)
            }
            .onChange(of: dialog?.id) { _, _ in
                // Only when a question comes: the answer is read after the alert has gone.
                if let asked = dialog {
                    text = asked.initialText
                }
            }
    }

    private var alertShown: Binding<Bool> {
        Binding(
            get: { dialog.map { !$0.isSheet } ?? false },
            set: { shown in
                if !shown, dialog.map({ !$0.isSheet }) == true {
                    dialog = nil
                }
            }
        )
    }

    private var sheetItem: Binding<LibraryDialog?> {
        Binding(
            get: { dialog.flatMap { $0.isSheet ? $0 : nil } },
            set: { value in
                if value == nil, dialog?.isSheet == true {
                    dialog = nil
                }
            }
        )
    }

    private func confirmText(_ asked: LibraryDialog) {
        let typed = text
        let blank = typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        switch asked {
        case let .rename(ref, _):
            if !blank { model.library.rename(ref, typed) }
        case let .renameFolder(id, _):
            if !blank { model.library.renameFolder(id, typed) }
        case let .newFolder(parent):
            if !blank { model.library.createFolder(parent, typed) }
        case let .describe(ref, _):
            model.library.describe(ref, typed)
        default:
            break
        }
        dialog = nil
    }

    private func confirmDeletion(_ asked: LibraryDialog) {
        switch asked {
        case let .delete(ref, _):
            model.library.delete([ref])
            onDeleted?()
        case let .deleteFolder(id, _):
            model.library.deleteFolder(id)
        case let .deleteMany(refs):
            model.library.delete(refs)
            onDone?()
        default:
            break
        }
        dialog = nil
    }
}

/// The questions answered in a sheet: the style of a line, the folder to move to.
private struct LibraryDialogSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let dialog: LibraryDialog
    let onDone: (() -> Void)?

    var body: some View {
        switch dialog {
        case let .style(ref, style):
            LibraryStyleSheet(initial: style) { chosen in
                model.library.setStyle(ref, chosen)
            }
            .presentationDetents([.medium, .large])
        default:
            LibraryMoveSheet(title: dialog.title, targets: targets) { target in
                pick(target)
            }
            .presentationDetents([.medium, .large])
        }
    }

    private var targets: [LibraryMoveTarget] {
        guard let lib = model.library.library else { return [] }
        switch dialog {
        case let .move(_, _, parent):
            return LibraryMoveTarget.targets(lib, moving: nil, current: parent)
        case let .moveFolder(id, _, parent):
            return LibraryMoveTarget.targets(lib, moving: id, current: parent)
        case let .moveMany(refs):
            // The folder they are all in is « actuel »; none is when they come from several.
            let parents = Set(refs.map { LibraryMoveTarget.folderOf($0, lib) })
            var current: Int64? = LibraryMoveTarget.severalFolders
            if parents.count == 1, let only = parents.first {
                current = only
            }
            return LibraryMoveTarget.targets(lib, moving: nil, current: current)
        default:
            return []
        }
    }

    private func pick(_ target: Int64?) {
        switch dialog {
        case let .move(ref, _, _):
            model.library.move([ref], to: target)
        case let .moveFolder(id, _, _):
            model.library.moveFolder(id, to: target)
        case let .moveMany(refs):
            model.library.move(refs, to: target)
            onDone?()
        default:
            break
        }
        dismiss()
    }
}

/// One place an item or a folder can be moved to (Android `MoveTarget`).
struct LibraryMoveTarget: Identifiable {
    /// No folder is the current one: the items come from several.
    static let severalFolders: Int64 = -1

    /// nil is the top of the library.
    let folderId: Int64?
    let label: String
    let depth: Int
    let current: Bool

    var id: String { folderId.map { "f\($0)" } ?? "top" }

    /// The top of the library and every folder that can take what is moved, in tree order; a folder never goes into
    /// itself or into what it holds.
    static func targets(_ lib: TrackLibrary, moving folder: Int64?, current: Int64?) -> [LibraryMoveTarget] {
        let excluded = folder.map { lib.descendants($0) } ?? []
        var out = [LibraryMoveTarget(folderId: nil, label: "Haut de la bibliothèque", depth: 0, current: current == nil)]
        func visit(_ parent: Int64?, _ depth: Int) {
            for f in lib.folders where f.parentId == parent && !excluded.contains(f.id) {
                out.append(LibraryMoveTarget(folderId: f.id, label: f.name, depth: depth, current: current == f.id))
                visit(f.id, depth + 1)
            }
        }
        visit(nil, 1)
        return out
    }

    static func folderOf(_ ref: ItemRefKey, _ lib: TrackLibrary) -> Int64? {
        switch ref.kind {
        case .track: return lib.tracks.first(where: { $0.id == ref.id })?.folderId
        case .route: return lib.routes.first(where: { $0.id == ref.id })?.folderId
        case .waypoint: return lib.waypoints.first(where: { $0.id == ref.id })?.folderId
        }
    }
}

/// « Déplacer vers… »: the top of the library and the folders, the current one greyed.
struct LibraryMoveSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let targets: [LibraryMoveTarget]
    let onPick: (Int64?) -> Void

    var body: some View {
        NavigationStack {
            List(targets) { target in
                Button {
                    onPick(target.folderId)
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: target.folderId == nil ? "tray.full" : LibraryIcons.folder)
                            .foregroundStyle(Crete.petrol)
                        Text(target.label)
                            .foregroundStyle(target.current ? Crete.onSurfaceVariant : Crete.onSurface)
                        if target.current {
                            Text("· actuel")
                                .font(.footnote)
                                .foregroundStyle(Crete.onSurfaceVariant)
                        }
                        Spacer()
                    }
                    .padding(.leading, CGFloat(target.depth) * 16)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(target.current)
            }
            .listStyle(.insetGrouped)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
            }
        }
    }
}

/// « Style de la ligne »: colour, width, dashes and direction arrows, with the line as it will look.
struct LibraryStyleSheet: View {
    @Environment(\.dismiss) private var dismiss
    let initial: PathStyle
    let onConfirm: (PathStyle) -> Void
    @State private var color: Int32 = PathStyle.defaultColor
    @State private var width: Double = Double(PathStyle.defaultWidth)
    @State private var dashed = false
    @State private var arrows = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LibraryPalette(selected: $color)
                }
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Épaisseur : \(Int(width))")
                        Slider(value: $width, in: 1 ... 12, step: 1)
                            .tint(Crete.turquoise)
                    }
                    Toggle("Pointillés", isOn: $dashed)
                        .tint(Crete.turquoise)
                    Toggle("Flèches de sens", isOn: $arrows)
                        .tint(Crete.turquoise)
                }
                Section {
                    LibraryLinePreview(color: color, width: width, dashed: dashed)
                }
            }
            .navigationTitle("Style de la ligne")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") {
                        onConfirm(PathStyle(colorArgb: color, widthDp: Float(width), dashed: dashed, arrows: arrows))
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            color = initial.colorArgb
            width = Double(initial.widthDp.rounded())
            dashed = initial.dashed
            arrows = initial.arrows
        }
    }
}

/// The eight colours of the lines (`PathStyle.palette`).
private struct LibraryPalette: View {
    @Binding var selected: Int32

    private static let pickedTraits: AccessibilityTraits = [.isButton, .isSelected]

    var body: some View {
        HStack(spacing: 10) {
            ForEach(Array(PathStyle.palette.enumerated()), id: \.offset) { index, argb in
                let chosen = argb == selected
                Circle()
                    .fill(LibraryColors.color(argb))
                    .frame(width: 32, height: 32)
                    .overlay(
                        Circle().strokeBorder(chosen ? Crete.onSurface : Crete.outline, lineWidth: chosen ? 3 : 1)
                    )
                    .contentShape(Circle())
                    .onTapGesture { selected = argb }
                    .accessibilityLabel("Couleur \(index + 1)")
                    .accessibilityAddTraits(chosen ? LibraryPalette.pickedTraits : AccessibilityTraits.isButton)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
    }
}

/// The line as it will look on the map.
private struct LibraryLinePreview: View {
    let color: Int32
    let width: Double
    let dashed: Bool

    var body: some View {
        Canvas { context, size in
            var path = Path()
            path.move(to: CGPoint(x: 8, y: size.height / 2))
            path.addLine(to: CGPoint(x: size.width - 8, y: size.height / 2))
            let dash: [CGFloat] = dashed ? [CGFloat(width) * 2, CGFloat(width) * 1.5] : []
            let style = StrokeStyle(lineWidth: CGFloat(width), lineCap: dashed ? .butt : .round, dash: dash)
            context.stroke(path, with: .color(LibraryColors.color(color)), style: style)
        }
        .frame(height: 28)
        .accessibilityHidden(true)
    }
}

// MARK: - Export

private struct LibraryExportModifier: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var request: LibraryExportRequest?
    @State private var document: LibraryGpxDocument? = nil
    @State private var presented = false
    @State private var fileName = "trace"

    func body(content: Content) -> some View {
        content
            .task(id: request?.id) {
                await prepare()
            }
            .fileExporter(
                isPresented: $presented,
                document: document,
                contentType: LibraryFiles.gpxType,
                defaultFilename: fileName
            ) { result in
                switch result {
                case .success:
                    model.library.say("Fichier GPX écrit")
                case let .failure(error):
                    model.library.say("Export impossible : \(error.localizedDescription)")
                }
                document = nil
            }
    }

    private func prepare() async {
        guard let wanted = request else { return }
        let data = await model.library.gpxData(wanted.selection)
        request = nil
        guard let data else {
            model.library.say("Export impossible : la bibliothèque ne peut pas être lue.")
            return
        }
        fileName = LibraryText.baseName(wanted.name)
        document = LibraryGpxDocument(data: data)
        presented = true
    }
}

// MARK: - Messages of the pages

/// The message at the bottom of a library page (Android snackbar of `TracksScreen`); it goes after 4 s.
struct LibraryMessageBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let message = model.library.screenMessage {
            HStack {
                Text(message.text)
                    .font(.subheadline)
                    .foregroundStyle(Color.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let action = message.action {
                    Button(action) {
                        model.library.screenMessage = nil
                        message.perform?()
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.pill)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .task(id: message.id) {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                if model.library.screenMessage?.id == message.id {
                    model.library.screenMessage = nil
                }
            }
        }
    }
}
