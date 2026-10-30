import SwiftUI
import ToolMapCore
import UIKit

/// Détail d'une trace opened from the sheet of the map (Android §6.2): the same page as in the list, in its own stack.
struct TrackDetailScreen: View {
    let trackId: Int64

    var body: some View {
        NavigationStack {
            LibraryTrackDetailPage(trackId: trackId, standalone: true)
        }
    }
}

/// Statistics, elevation profile and actions of one track (Android `TrackDetailScreen`, P29.4 « Topo »): four key
/// figures and the profile with its axis and legend first; one filled button; the other figures folded in « Tous les
/// chiffres »; the tools grouped by when they serve (Préparer, Sur le terrain, Modifier); renaming, style, description,
/// moving, export and deletion in the ⋮ of the bar, sharing as its icon.
struct LibraryTrackDetailPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let trackId: Int64
    /// Opened from the map (its own stack, ✕ closes the page) rather than pushed from the list.
    let standalone: Bool
    @State private var detail: LibraryTrackDetail? = nil
    @State private var cursor: Double? = nil
    @State private var dialog: LibraryDialog? = nil
    @State private var exportRequest: LibraryExportRequest? = nil
    @State private var askMerge = false

    var body: some View {
        let entity = model.library.library?.tracks.first(where: { $0.id == trackId })
        content(entity)
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(entity?.name ?? LibraryText.unnamed)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { barItems(entity) }
            .task(id: geometryKey(entity)) {
                detail = await model.library.trackDetail(trackId)
            }
            .overlay(alignment: .bottom) { LibraryMessageBar() }
            .libraryDialogs($dialog, onDeleted: { leave() })
            .libraryExport($exportRequest)
            .sheet(isPresented: $askMerge) {
                LibraryMergeSheet(trackId: trackId, name: entity?.name ?? LibraryText.unnamed)
                    .environment(model)
            }
    }

    @ViewBuilder
    private func content(_ entity: TrackEntity?) -> some View {
        if let entity {
            if let detail, detail.trackId == trackId {
                LibraryDetailList(entity: entity, detail: detail, cursor: $cursor, askMerge: $askMerge)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else if model.library.library == nil {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Text("Cette trace n'existe plus.")
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    /// Changes when the points of the track change (edit, reverse, simplify, altitudes): the detail is read again.
    private func geometryKey(_ entity: TrackEntity?) -> String {
        guard let e = entity else { return "none" }
        return "\(e.id)-\(e.pointCount)-\(e.distanceMeters)-\(e.ascentMeters ?? -1)-\(e.descentMeters ?? -1)"
    }

    @ToolbarContentBuilder
    private func barItems(_ entity: TrackEntity?) -> some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            if standalone {
                ScreenCloseButton()
            }
        }
        ToolbarItem(placement: .principal) {
            LibraryTitle(title: entity?.name ?? LibraryText.unnamed, subtitle: subtitle(entity))
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if let entity {
                let ref = ItemRefKey(kind: .track, id: entity.id)
                if let share = model.library.share(ref.librarySelection, name: entity.name) {
                    ShareLink(item: share, preview: SharePreview(share.fileName)) {
                        Image(systemName: LibraryIcons.share)
                    }
                    .accessibilityLabel("Partager")
                }
                LibraryDetailMenu(entity: entity, dialog: $dialog, exportRequest: $exportRequest)
            }
        }
    }

    /// « Trace · 21 oct. 2026 »: the day it was walked, or added.
    private func subtitle(_ entity: TrackEntity?) -> String? {
        guard let e = entity else { return nil }
        return "Trace · " + LibraryText.fullDate(e.startTimeMillis ?? e.createdAt)
    }

    /// After a deletion: back to the list, or to the map.
    private func leave() {
        if standalone {
            model.screen = nil
        } else {
            dismiss()
        }
    }
}

/// The ⋮ of the bar: Renommer · Style de la ligne · Décrire · Déplacer vers… · Exporter en GPX… · Supprimer.
private struct LibraryDetailMenu: View {
    let entity: TrackEntity
    @Binding var dialog: LibraryDialog?
    @Binding var exportRequest: LibraryExportRequest?

    var body: some View {
        let ref = ItemRefKey(kind: .track, id: entity.id)
        let name = entity.name ?? LibraryText.unnamed
        let style = PathStyle(colorArgb: entity.colorArgb, widthDp: entity.widthDp, dashed: entity.dashed, arrows: entity.arrows)
        Menu {
            Button("Renommer") { dialog = .rename(ref, current: entity.name ?? "") }
            Button("Style de la ligne") { dialog = .style(ref, style) }
            Button("Décrire") { dialog = .describe(ref, current: entity.desc ?? "") }
            Button("Déplacer vers…") { dialog = .move(ref, name: name, parent: entity.folderId) }
            Button("Exporter en GPX…") {
                exportRequest = LibraryExportRequest(selection: ref.librarySelection, name: entity.name)
            }
            Button("Supprimer", role: .destructive) { dialog = .delete(ref, name: name) }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Menu")
    }
}

/// The page itself: the figures and the profile, then the groups of actions.
private struct LibraryDetailList: View {
    @Environment(AppModel.self) private var model
    let entity: TrackEntity
    let detail: LibraryTrackDetail
    @Binding var cursor: Double?
    @Binding var askMerge: Bool
    @State private var allFigures = false
    @State private var askReverse = false
    @State private var askSimplify = false

    private static let tolerances: [Double] = [1, 2, 5, 10, 25]

    var body: some View {
        let ref = ItemRefKey(kind: .track, id: entity.id)
        List {
            Section {
                LibraryDetailHeader(entity: entity, detail: detail, cursor: $cursor)
            }
            Section {
                LibraryAllFigures(stats: detail.stats, open: $allFigures)
            }
            Section {
                LibraryActionRow(icon: "arrow.down.circle", title: "Télécharger autour de cette trace") {
                    model.library.downloadAround(ref)
                }
                LibraryActionRow(
                    icon: "printer",
                    title: "Imprimer le long de cette trace…",
                    subtitle: "Un PDF de plusieurs pages à l'échelle, qui suivent la trace"
                ) {
                    model.library.startTool(.print(alongTrack: entity.id))
                }
            } header: {
                LibrarySectionTitle(text: "Préparer")
            }
            Section {
                let followed = model.routing.isFollowing(trackId: entity.id)
                LibraryActionRow(
                    icon: "figure.walk",
                    title: followed ? "Ne plus suivre" : "Suivre cette trace",
                    subtitle: "Alerte d'écart"
                ) {
                    if followed {
                        model.routing.stopFollowing()
                    } else {
                        model.routing.follow(trackId: entity.id)
                        model.screen = nil
                    }
                }
            } header: {
                LibrarySectionTitle(text: "Sur le terrain")
            }
            Section {
                editRows
            } header: {
                LibrarySectionTitle(text: "Modifier")
            }
        }
        .listStyle(.insetGrouped)
        .alert("Inverser le sens", isPresented: $askReverse) {
            Button("Inverser le sens") { model.library.reverse(entity.id) }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("La trace sera parcourue dans l'autre sens ; ses heures seront effacées.")
        }
        .confirmationDialog("Simplifier", isPresented: $askSimplify, titleVisibility: .visible) {
            ForEach(LibraryDetailList.tolerances, id: \.self) { meters in
                Button(LibraryText.distance(meters, model.settings)) {
                    model.library.simplify(entity.id, meters)
                }
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Écart maximal toléré avec le tracé d'origine :")
        }
    }

    @ViewBuilder
    private var editRows: some View {
        if detail.missingElevation > 0 {
            LibraryActionRow(
                icon: "mountain.2",
                title: "Compléter les altitudes",
                subtitle: "Points sans altitude : \(detail.missingElevation).",
                enabled: !model.library.busy
            ) {
                model.library.completeElevation(entity.id)
            }
        }
        LibraryActionRow(icon: "pencil", title: "Modifier le tracé") {
            model.library.startTool(.draw(.track(entity.id)))
        }
        LibraryActionRow(icon: "arrow.left.arrow.right", title: "Inverser le sens") {
            askReverse = true
        }
        LibraryActionRow(icon: "arrow.down.right.and.arrow.up.left", title: "Simplifier…") {
            askSimplify = true
        }
        LibraryActionRow(icon: "arrow.triangle.merge", title: "Fusionner avec une autre trace…") {
            askMerge = true
        }
    }
}

/// The description, the four key figures, the profile and « Afficher sur la carte ».
private struct LibraryDetailHeader: View {
    @Environment(AppModel.self) private var model
    let entity: TrackEntity
    let detail: LibraryTrackDetail
    @Binding var cursor: Double?

    var body: some View {
        let settings = model.settings
        VStack(alignment: .leading, spacing: 12) {
            if let text = entity.desc, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(text)
                    .font(.subheadline)
            }
            LibraryKeyFigures(stats: detail.stats)
            HStack(alignment: .firstTextBaseline) {
                Text("Profil d'altitude")
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                if let profile = detail.profile {
                    Text(LibraryProfileText.reading(profile, cursor, settings))
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            profileBlock
            Button {
                showTrack()
            } label: {
                Label("Afficher sur la carte", systemImage: "map")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Crete.turquoise)
            .controlSize(.large)
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var profileBlock: some View {
        if let profile = detail.profile {
            LibraryProfileChart(profile: profile, cursor: $cursor)
            if let at = cursor, let point = detail.path.interpolate(at) {
                Button {
                    model.library.showOnMap(Bounds(minX: point.lon, minY: point.lat, maxX: point.lon, maxY: point.lat))
                } label: {
                    Label("Voir ce point sur la carte", systemImage: "mappin")
                        .font(.subheadline.weight(.medium))
                }
                .buttonStyle(.borderless)
                .foregroundStyle(Crete.petrol)
            }
        } else {
            Text("Cette trace n'a pas assez d'altitudes pour tracer un profil.")
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
        }
    }

    private func showTrack() {
        guard entity.pointCount > 0 else { return }
        let bounds = Bounds(minX: entity.minLon, minY: entity.minLat, maxX: entity.maxLon, maxY: entity.maxLat)
        model.library.showOnMap(bounds)
    }
}

/// Distance, climb, descent and walking time, large: what decides a walk.
private struct LibraryKeyFigures: View {
    @Environment(AppModel.self) private var model
    let stats: TrackStats

    var body: some View {
        let figures = LibraryKeyFigures.figures(stats, model.settings)
        HStack(alignment: .top, spacing: 8) {
            ForEach(0 ..< figures.count, id: \.self) { index in
                VStack(alignment: .leading, spacing: 2) {
                    Text(figures[index].value)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(figures[index].label)
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
    }

    static func figures(_ stats: TrackStats, _ settings: AppSettings) -> [(value: String, label: String)] {
        var out: [(value: String, label: String)] = []
        out.append((LibraryText.distance(stats.distance2dMeters, settings), "Distance"))
        if stats.minEleMeters != nil, stats.maxEleMeters != nil {
            out.append((LibraryText.elevation(stats.ascentMeters, settings), "Dénivelé +"))
            out.append((LibraryText.elevation(stats.descentMeters, settings), "Dénivelé −"))
        }
        out.append((TrackFormat.duration(stats.estimatedWalkMillis), "Temps estimé"))
        return out
    }
}

/// « Tous les chiffres », folded: a touch unfolds the lines of the statistics in the same block.
private struct LibraryAllFigures: View {
    @Environment(AppModel.self) private var model
    let stats: TrackStats
    @Binding var open: Bool

    var body: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { open.toggle() }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "chart.bar")
                    .font(.system(size: 18))
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Tous les chiffres")
                        .foregroundStyle(Crete.onSurface)
                    Text("Altitudes, durées, vitesses, début, fin, points")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                Image(systemName: open ? "chevron.up" : "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(open ? "dépliée" : "repliée")
        if open {
            let lines = LibraryAllFigures.lines(stats, model.settings)
            ForEach(0 ..< lines.count, id: \.self) { index in
                LabeledContent(lines[index].label, value: lines[index].value)
                    .font(.subheadline)
            }
        }
    }

    /// Every figure of the track, in the order of the former statistics card.
    static func lines(_ stats: TrackStats, _ settings: AppSettings) -> [(label: String, value: String)] {
        var rows: [(label: String, value: String)] = []
        rows.append(("Distance", LibraryText.distance(stats.distance2dMeters, settings)))
        if let low = stats.minEleMeters, let high = stats.maxEleMeters {
            rows.append(("Dénivelé positif", "↑ " + LibraryText.elevation(stats.ascentMeters, settings)))
            rows.append(("Dénivelé négatif", "↓ " + LibraryText.elevation(stats.descentMeters, settings)))
            rows.append(("Altitude minimale", LibraryText.elevation(low, settings)))
            rows.append(("Altitude maximale", LibraryText.elevation(high, settings)))
        }
        rows.append(("Temps de marche estimé", TrackFormat.duration(stats.estimatedWalkMillis)))
        if let millis = stats.totalDurationMillis {
            rows.append(("Durée totale", TrackFormat.duration(millis)))
        }
        if let millis = stats.movingDurationMillis {
            rows.append(("Durée en mouvement", TrackFormat.duration(millis)))
        }
        if let speed = stats.averageSpeedMps {
            rows.append(("Vitesse moyenne", LibraryText.speed(speed, settings)))
        }
        if let speed = stats.averageMovingSpeedMps {
            rows.append(("Vitesse moyenne en mouvement", LibraryText.speed(speed, settings)))
        }
        if let speed = stats.maxSpeedMps {
            rows.append(("Vitesse maximale", LibraryText.speed(speed, settings)))
        }
        if let millis = stats.startTimeMillis {
            rows.append(("Début", NameDates.format(millis)))
        }
        if let millis = stats.endTimeMillis {
            rows.append(("Fin", NameDates.format(millis)))
        }
        rows.append(("Points", LibraryText.pointsSegments(stats.pointCount, stats.segmentCount)))
        return rows
    }
}

/// « Fusionner avec : » the other tracks; the new track is « nom + autre nom », the sources stay.
private struct LibraryMergeSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let trackId: Int64
    let name: String

    var body: some View {
        let others = (model.library.library?.tracks ?? []).filter { $0.id != trackId }
        NavigationStack {
            Group {
                if others.isEmpty {
                    Text("Il n'y a pas d'autre trace.")
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .padding()
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                } else {
                    List(others, id: \.id) { other in
                        Button {
                            merge(other)
                        } label: {
                            HStack(spacing: 10) {
                                LibraryLineMark(systemImage: LibraryIcons.track, colorArgb: other.colorArgb, size: 32)
                                Text(other.name ?? LibraryText.unnamed)
                                    .foregroundStyle(Crete.onSurface)
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Fusionner avec :")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func merge(_ other: TrackEntity) {
        let otherName = other.name ?? LibraryText.unnamed
        model.library.merge([trackId, other.id], name: "\(name) + \(otherName)")
        dismiss()
    }
}

/// A row of action: a grey icon, the name, its second line.
struct LibraryActionRow: View {
    let icon: String
    let title: String
    var subtitle: String?
    var enabled = true
    let action: () -> Void

    init(icon: String, title: String, subtitle: String? = nil, enabled: Bool = true, action: @escaping () -> Void) {
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.enabled = enabled
        self.action = action
    }

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
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// A section title as written (no capitals).
struct LibrarySectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Crete.onSurfaceVariant)
            .textCase(nil)
    }
}
