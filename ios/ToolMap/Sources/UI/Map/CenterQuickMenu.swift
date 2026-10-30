import SwiftUI
import ToolMapCore
import UIKit

/// The quick menu of the centre point (Android G12, `CenterQuickMenu`, F1, ADR-043, P14.10), opened by a touch on the
/// ring (`AppModel.centerMenuOpen`), above the point with a tip towards it: the point in a tinted block (its
/// coordinates in a format chosen on the spot, « DD ▾ », and the altitude of the relief; a touch on the block copies
/// the coordinates), then Définir comme cible · Créer un waypoint… · Mesurer depuis ici, and a row of icons Copier ·
/// Partager · Ouvrir avec. Not while a tool is open. A touch elsewhere closes it.
struct CenterQuickMenu: View {
    @Environment(AppModel.self) private var model

    /// Between the bottom of the tip and the centre of the ring.
    private static let gap: CGFloat = 14

    var body: some View {
        if model.centerMenuOpen, model.tool == nil {
            GeometryReader { geo in
                ZStack {
                    Color.black.opacity(0.001)
                        .contentShape(Rectangle())
                        .onTapGesture { model.centerMenuOpen = false }
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        CenterMenuCard(at: model.center, system: model.settings.coordinates)
                        Color.clear
                            .frame(height: geo.size.height / 2 + CenterQuickMenu.gap)
                    }
                    .frame(width: geo.size.width, height: geo.size.height)
                }
            }
            // The ring is in the middle of the map view, which goes under the bars.
            .ignoresSafeArea()
        }
    }
}

/// The card of the quick menu and its tip.
private struct CenterMenuCard: View {
    let at: LonLat
    @State private var system: CoordinateSystem
    @State private var relief: Relief = .asking

    /// The altitude of the relief under the point: asked, known, or unknown (offline, no tile).
    enum Relief: Equatable {
        case asking
        case known(Double)
        case unknown
    }

    init(at: LonLat, system: CoordinateSystem) {
        self.at = at
        _system = State(initialValue: system)
    }

    var body: some View {
        let coordinates = CoordinateFormat.format(system, at.lat, at.lon).text
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                CenterPointBlock(coordinates: coordinates, system: $system, relief: relief)
                CenterMenuActions(at: at)
                Divider()
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                CenterMenuIcons(at: at, coordinates: coordinates)
            }
            .padding(.vertical, 8)
            .frame(width: 292)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .shadow(color: .black.opacity(0.22), radius: 10, y: 3)
            CenterMenuTip()
                .fill(Color.white)
                .frame(width: 18, height: 8)
        }
        .task(id: at) {
            relief = .asking
            if let meters = await ReliefAltitude.at(at) {
                relief = .known(meters)
            } else {
                relief = .unknown
            }
        }
    }
}

/// The tinted block of the point: its coordinates with the format button, and the altitude of the relief when it is
/// known. A touch on the block copies the coordinates.
private struct CenterPointBlock: View {
    @Environment(AppModel.self) private var model
    let coordinates: String
    @Binding var system: CoordinateSystem
    let relief: CenterMenuCard.Relief

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Image(systemName: "mappin")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 18)
                Text(coordinates)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                CenterFormatMenu(system: $system)
            }
            reliefLine
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 6)
        .background(Crete.tile, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture {
            UIPasteboard.general.string = coordinates
            model.centerMenuOpen = false
            model.say("Coordonnées copiées")
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var reliefLine: some View {
        switch relief {
        case .asking:
            reliefRow("Altitude…")
        case let .known(meters):
            reliefRow("\(MapText.elevation(meters, model.settings)) · relief")
        case .unknown:
            EmptyView()
        }
    }

    private func reliefRow(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "mountain.2")
                .font(.system(size: 13))
                .foregroundStyle(Crete.petrol)
                .frame(width: 18)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
        }
        .padding(.bottom, 2)
    }
}

/// « DD ▾ »: the format of the coordinates of the menu, chosen on the spot (the setting does not change).
private struct CenterFormatMenu: View {
    @Binding var system: CoordinateSystem

    var body: some View {
        Menu {
            Section("Format des coordonnées") {
                ForEach(CoordinateSystem.allCases, id: \.self) { choice in
                    Button {
                        system = choice
                    } label: {
                        if choice == system {
                            Label(MapText.fullName(choice), systemImage: "checkmark")
                        } else {
                            Text(MapText.fullName(choice))
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 2) {
                Text(MapText.shortName(system))
                    .font(.subheadline.weight(.semibold))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .bold))
            }
            .foregroundStyle(Crete.turquoise)
            .padding(.horizontal, 8)
            .frame(minWidth: 44, minHeight: 36)
        }
        .accessibilityLabel("Format des coordonnées")
    }
}

/// The three commands for the point; they use the same engines as the long press and the main menu.
private struct CenterMenuActions: View {
    @Environment(AppModel.self) private var model
    let at: LonLat

    var body: some View {
        VStack(spacing: 0) {
            CenterMenuRow(title: "Définir comme cible", systemImage: "scope") {
                model.centerMenuOpen = false
                let target = NavTarget(lat: at.lat, lon: at.lon, name: MapToolsBridge.targetPointName)
                MapToolsBridge.setTarget(model, target)
            }
            CenterMenuRow(title: "Créer un waypoint…", systemImage: "mappin.and.ellipse") {
                model.centerMenuOpen = false
                MapToolsBridge.newWaypoint(model, at: at)
            }
            CenterMenuRow(title: "Mesurer depuis ici", systemImage: "ruler") {
                model.measure.start(at: at)
            }
        }
    }
}

private struct CenterMenuRow: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 24)
                Text(title)
                    .font(.body)
                    .foregroundStyle(Crete.onSurface)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 18)
            .frame(height: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Copier · Partager · Ouvrir avec (ADR-044): the icons share the width of the row, their names under them.
private struct CenterMenuIcons: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let at: LonLat
    let coordinates: String

    var body: some View {
        HStack(spacing: 0) {
            Button {
                UIPasteboard.general.string = coordinates
                model.centerMenuOpen = false
                model.say("Coordonnées copiées")
            } label: {
                CenterMenuIcon(title: "Copier", systemImage: "doc.on.doc")
            }
            .buttonStyle(.plain)
            ShareLink(item: coordinates, subject: Text("Partager ce point")) {
                CenterMenuIcon(title: "Partager", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(.plain)
            openWith
        }
        .padding(.horizontal, 8)
    }

    /// The app of « Ouvrir les positions avec » when one is set, otherwise the choice of the app.
    @ViewBuilder
    private var openWith: some View {
        if let app = CenterPointApp.chosen(model.settings.pointApp) {
            Button {
                open(app)
            } label: {
                CenterMenuIcon(title: "Ouvrir avec", systemImage: "arrow.up.forward.app")
            }
            .buttonStyle(.plain)
        } else {
            Menu {
                Section("Ouvrir le point avec") {
                    ForEach(CenterPointApp.allCases, id: \.self) { app in
                        Button(app.title) { open(app) }
                    }
                }
            } label: {
                CenterMenuIcon(title: "Ouvrir avec", systemImage: "arrow.up.forward.app")
            }
        }
    }

    private func open(_ app: CenterPointApp) {
        model.centerMenuOpen = false
        guard let url = app.link(at.lat, at.lon) else { return }
        openURL(url) { accepted in
            if !accepted {
                model.notice = "Aucune application ne sait ouvrir une position sur ce téléphone."
            }
        }
    }
}

private struct CenterMenuIcon: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: systemImage)
                .font(.system(size: 19, weight: .medium))
                .foregroundStyle(Crete.turquoise)
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(Crete.onSurface)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

/// The tip under the card, pointing at the centre.
private struct CenterMenuTip: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// The apps « Ouvrir avec » hands a position to, by their universal links (Waze's own link starts the guidance,
/// core `PointLinks`).
enum CenterPointApp: String, CaseIterable {
    case appleMaps
    case googleMaps
    case waze

    var title: String {
        switch self {
        case .appleMaps: "Plans"
        case .googleMaps: "Google Maps"
        case .waze: "Waze"
        }
    }

    /// The app of the setting « Ouvrir les positions avec » (`AppSettings.pointApp`, a package name on Android), or
    /// nil to ask each time.
    static func chosen(_ setting: String?) -> CenterPointApp? {
        guard let name = setting else { return nil }
        if name == "com.apple.Maps" { return .appleMaps }
        if name == "com.google.android.apps.maps" || name == "com.google.Maps" { return .googleMaps }
        if name == PointLinks.wazePackage { return .waze }
        return nil
    }

    func link(_ lat: Double, _ lon: Double) -> URL? {
        let at = String(format: "%.6f,%.6f", lat, lon)
        switch self {
        case .appleMaps:
            return URL(string: "https://maps.apple.com/?ll=\(at)&q=\(at)")
        case .googleMaps:
            return URL(string: "https://www.google.com/maps/search/?api=1&query=\(at)")
        case .waze:
            return URL(string: PointLinks.target(PointLinks.wazePackage, lat, lon).uri)
        }
    }
}

/// The altitude of the relief at a point, from the Terrarium tiles (Android `ElevationCompleter`, zoom 13) through
/// the app's tile service: stored tiles first, nothing asked offline.
enum ReliefAltitude {
    static let sourceId = "terrarium"
    static let zoom = 13

    /// Meters above the sea, nil when the tile cannot be had.
    static func at(_ position: LonLat) async -> Double? {
        await Task.detached(priority: .userInitiated) {
            await ReliefAltitude.lookup(position)
        }.value
    }

    private static func lookup(_ position: LonLat) async -> Double? {
        guard let tile = try? TileMath.tileAt(position.lon, position.lat, zoom) else { return nil }
        let data: Data? = await withCheckedContinuation { (continuation: CheckedContinuation<Data?, Never>) in
            TileService.shared.load(sourceId, zoom, tile.x, tile.y) { bytes, _ in
                continuation.resume(returning: bytes)
            }
        }
        guard let data,
              let decoded = try? TerrariumTile.fromPng(data),
              let fraction = try? TerrariumTile.fractionIn(position.lon, position.lat, zoom, tile.x, tile.y)
        else { return nil }
        return decoded.elevationAt(fraction.0, fraction.1)
    }
}
