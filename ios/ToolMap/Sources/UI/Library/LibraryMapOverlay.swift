import CoreLocation
import SwiftUI
import ToolMapCore
import UIKit

/// The sheet of a track, route or waypoint touched on the map and the waypoint dialog (Android §2.4), presented from
/// over the map; the overlay itself catches no gesture.
struct LibraryMapOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var library = model.library
        Color.clear
            .allowsHitTesting(false)
            .sheet(item: $library.tapped) { item in
                LibraryItemSheet(ref: item.ref)
                    .environment(model)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .background(
                Color.clear
                    .allowsHitTesting(false)
                    .sheet(item: $library.waypointDraft) { draft in
                        WaypointDialogSheet(draft: draft)
                            .environment(model)
                    }
            )
    }
}

/// What the sheet shows of an item.
private struct LibrarySheetInfo {
    let ref: ItemRefKey
    /// The name as stored (nil: unnamed).
    let storedName: String?
    let figures: String
    let notes: [String]
    let colorArgb: Int32
    let sym: String?
    let lat: Double
    let lon: Double

    var name: String { storedName ?? LibraryText.unnamed }

    static func of(_ ref: ItemRefKey, _ lib: TrackLibrary?, _ settings: AppSettings) -> LibrarySheetInfo? {
        guard let lib else { return nil }
        switch ref.kind {
        case .track:
            guard let t = lib.tracks.first(where: { $0.id == ref.id }) else { return nil }
            var parts = [LibraryText.distance(t.distanceMeters, settings)]
            if let up = t.ascentMeters {
                parts.append("↑ " + LibraryText.elevation(up, settings))
            }
            if let down = t.descentMeters {
                parts.append("↓ " + LibraryText.elevation(down, settings))
            }
            return LibrarySheetInfo(
                ref: ref,
                storedName: t.name,
                figures: parts.joined(separator: " · "),
                notes: [],
                colorArgb: t.colorArgb,
                sym: nil,
                lat: (t.minLat + t.maxLat) / 2,
                lon: (t.minLon + t.maxLon) / 2
            )
        case .route:
            guard let r = lib.routes.first(where: { $0.id == ref.id }) else { return nil }
            return LibrarySheetInfo(
                ref: ref,
                storedName: r.name,
                figures: routeFigures(r, settings),
                notes: [],
                colorArgb: r.colorArgb,
                sym: nil,
                lat: (r.minLat + r.maxLat) / 2,
                lon: (r.minLon + r.maxLon) / 2
            )
        case .waypoint:
            guard let w = lib.waypoints.first(where: { $0.id == ref.id }) else { return nil }
            var parts: [String] = []
            if let ele = w.ele {
                parts.append(LibraryText.elevation(ele, settings))
            }
            parts.append(LibraryText.position(w.lat, w.lon, settings))
            var notes: [String] = []
            if let cmt = w.cmt, !cmt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                notes.append(cmt)
            }
            if let desc = w.desc, !desc.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, desc != w.cmt {
                notes.append(desc)
            }
            return LibrarySheetInfo(
                ref: ref,
                storedName: w.name,
                figures: parts.joined(separator: " · "),
                notes: notes,
                colorArgb: WaypointSymbols.colorOf(w.sym),
                sym: w.sym,
                lat: w.lat,
                lon: w.lon
            )
        }
    }

    /// « 9,37 km · ↑ 412 m · ↓ 398 m · 3 h 05 · Randonnée »: length, climb, time and profile of the plan (P19).
    private static func routeFigures(_ r: RouteEntity, _ settings: AppSettings) -> String {
        var parts = [LibraryText.distance(r.distanceMeters, settings)]
        let info = LibraryText.routeInfo(r)
        if let summary = info?.summary, summary.hasElevation {
            parts.append("↑ " + LibraryText.elevation(summary.ascentMeters, settings))
            parts.append("↓ " + LibraryText.elevation(summary.descentMeters, settings))
        }
        if let duration = LibraryText.routeDuration(info) {
            parts.append(duration)
        }
        if let profile = info?.profile {
            parts.append(LibraryText.profileName(profile))
        }
        return parts.joined(separator: " · ")
    }
}

/// The sheet of an item touched on the map (Android `TappedItemSheet`, P29.4 « Repère »): its mark, name and figures
/// with a ⋮ (Partager · Exporter · Convertir en trace · Masquer · Supprimer), then one action put forward and two or
/// three tiles.
private struct LibraryItemSheet: View {
    @Environment(AppModel.self) private var model
    let ref: ItemRefKey
    @State private var dialog: LibraryDialog? = nil
    @State private var exportRequest: LibraryExportRequest? = nil
    @State private var profile: ElevationProfile? = nil

    var body: some View {
        let info = LibrarySheetInfo.of(ref, model.library.library, model.settings)
        ScrollView {
            if let info {
                VStack(alignment: .leading, spacing: 10) {
                    LibrarySheetHeader(info: info, dialog: $dialog, exportRequest: $exportRequest)
                    ForEach(0 ..< info.notes.count, id: \.self) { index in
                        Text(info.notes[index])
                            .font(.subheadline)
                            .foregroundStyle(Crete.onSurface)
                    }
                    LibrarySheetActions(info: info, profile: profile)
                }
                .padding(.horizontal, 16)
                .padding(.top, 22)
                .padding(.bottom, 16)
            }
        }
        .libraryDialogs($dialog, onDeleted: { model.library.tapped = nil })
        .libraryExport($exportRequest)
        .task(id: ref.id) {
            if ref.kind == .track {
                profile = await model.library.profile(ref.id)
            }
        }
        .onChange(of: info == nil) { _, gone in
            if gone, model.library.library != nil {
                model.library.tapped = nil
            }
        }
    }
}

private struct LibrarySheetHeader: View {
    @Environment(AppModel.self) private var model
    let info: LibrarySheetInfo
    @Binding var dialog: LibraryDialog?
    @Binding var exportRequest: LibraryExportRequest?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            LibraryItemMark(kind: info.ref.kind, colorArgb: info.colorArgb, sym: info.sym, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(info.name)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Crete.onSurface)
                    .lineLimit(2)
                Text(info.figures)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Spacer(minLength: 4)
            menu
        }
    }

    private var menu: some View {
        let ref = info.ref
        return Menu {
            if let share = model.library.share(ref.librarySelection, name: info.storedName) {
                ShareLink(item: share, preview: SharePreview(share.fileName)) {
                    Label("Partager", systemImage: LibraryIcons.share)
                }
            }
            Button {
                exportRequest = LibraryExportRequest(selection: ref.librarySelection, name: info.storedName)
            } label: {
                Label("Exporter", systemImage: LibraryIcons.importFile)
            }
            if ref.kind == .route {
                Button {
                    model.library.convertToTrack(ref.id, name: info.name)
                } label: {
                    Label("Convertir en trace", systemImage: LibraryIcons.track)
                }
            }
            Button {
                model.library.setVisible(ref, false)
                model.library.tapped = nil
            } label: {
                Label("Masquer", systemImage: "eye.slash")
            }
            Button(role: .destructive) {
                dialog = .delete(ref, name: info.name)
            } label: {
                Label("Supprimer", systemImage: "trash")
            }
        } label: {
            LibraryMenuLabel()
        }
    }
}

/// What the sheet puts forward for each kind of item.
private struct LibrarySheetActions: View {
    @Environment(AppModel.self) private var model
    let info: LibrarySheetInfo
    let profile: ElevationProfile?
    @State private var proximity = false

    var body: some View {
        let ref = info.ref
        switch ref.kind {
        case .waypoint:
            LibraryBearingReading(lat: info.lat, lon: info.lon)
            LibraryPrimaryButton(systemImage: "location.north.fill", title: "Aller vers ce point") {
                goTo()
            }
            HStack(spacing: 8) {
                ToolTile(systemImage: "bell", title: proximityTitle(ref.id)) {
                    proximity = true
                }
                .sheet(isPresented: $proximity) {
                    ProximityAlertSheet(waypointId: ref.id, name: info.storedName)
                        .environment(model)
                }
                ToolTile(systemImage: "pencil", title: "Modifier") {
                    model.library.editWaypoint(ref.id)
                }
            }
        case .track:
            if let profile {
                LibraryMiniProfile(profile: profile)
                    .padding(.vertical, 4)
            }
            let followed = model.routing.isFollowing(trackId: ref.id)
            LibraryPrimaryButton(systemImage: "figure.walk", title: followed ? "Ne plus suivre" : "Suivre cette trace") {
                if followed {
                    model.routing.stopFollowing()
                } else {
                    model.library.tapped = nil
                    model.routing.follow(trackId: ref.id)
                }
            }
            HStack(spacing: 8) {
                ToolTile(systemImage: "gauge.with.dots.needle.33percent", title: "Statistiques") {
                    model.library.openScreen(.trackDetail(ref.id))
                }
                ToolTile(systemImage: "pencil", title: "Modifier le tracé") {
                    model.library.startTool(.draw(.track(ref.id)))
                }
                ToolTile(systemImage: "arrow.down.circle", title: "Télécharger autour") {
                    model.library.downloadAround(ref)
                }
            }
        case .route:
            let followed = model.routing.isFollowing(routeId: ref.id)
            LibraryPrimaryButton(systemImage: "figure.walk", title: followed ? "Ne plus suivre" : "Suivre cet itinéraire") {
                if followed {
                    model.routing.stopFollowing()
                } else {
                    model.library.tapped = nil
                    model.routing.follow(routeId: ref.id)
                }
            }
            HStack(spacing: 8) {
                ToolTile(systemImage: "pencil", title: "Modifier le tracé") {
                    model.library.startTool(.draw(.route(ref.id)))
                }
                ToolTile(systemImage: "arrow.down.circle", title: "Télécharger autour") {
                    model.library.downloadAround(ref)
                }
            }
        }
    }

    /// « Aller vers ce point »: the sheet closes, then the start sheet of the way to the point opens (G8).
    private func goTo() {
        let target = NavTarget(lat: info.lat, lon: info.lon, name: info.storedName, sym: info.sym)
        model.library.tapped = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            model.routing.askTarget(target)
        }
    }

    /// « Alerte à 200 m » once the alert is set.
    private func proximityTitle(_ id: Int64) -> String {
        guard let watch = model.routing.watching(id) else { return "Alerte de proximité" }
        let radius = watch.radiusMeters ?? model.settings.alerts.proximityRadiusMeters
        return "Alerte à \(radius) m"
    }
}

/// « 3,90 km ↗ cap 146° SE », large, « depuis votre position » under it, when the position is known: the arrow turns
/// to the bearing of the item. VoiceOver reads it as one sentence.
private struct LibraryBearingReading: View {
    @Environment(AppModel.self) private var model
    let lat: Double
    let lon: Double

    var body: some View {
        if let here = model.location.location {
            let settings = model.settings
            let guide = ToolMapCore.Navigation.guide(here.coordinate.latitude, here.coordinate.longitude, NavTarget(lat: lat, lon: lon))
            let distance = LibraryText.distance(guide.distanceMeters, settings)
            let bearing = LibraryText.bearing(guide.bearingDegrees, settings)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 12) {
                    Text(distance)
                        .font(.title.weight(.medium))
                    Image(systemName: "arrow.up")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(Crete.turquoise)
                        .rotationEffect(.degrees(guide.bearingDegrees))
                    Text("cap \(bearing)")
                        .font(.body)
                }
                Text("depuis votre position")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .padding(.top, 6)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(distance), cap \(bearing), depuis votre position")
        }
    }
}

/// The one action the sheet puts forward, the width of the sheet.
private struct LibraryPrimaryButton: View {
    let systemImage: String
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(Color.white)
                .background(Crete.turquoise, in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}
