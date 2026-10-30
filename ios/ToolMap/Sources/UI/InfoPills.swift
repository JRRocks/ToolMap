import CoreLocation
import SwiftUI
import ToolMapCore
import UIKit

/// The pills at the top left of the map (Android `MapInfoBoxes`, G4 to G6, P29.1 variant E « Pastilles »), one short
/// line each: the centre of the map, the compass heading when it is shown as a number, the scale, then while the
/// location listens the GPS. A touch opens a pill: the position shows its menu (grid, copy, share, next format), the
/// scale its zoom (« zoom 16/18 »), the GPS its detail. « Taille des boîtes » and « Fond des boîtes » apply.
struct InfoPills: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            PillPosition()
            if let heading = headingText {
                PillChip {
                    Text("Cap \(heading)")
                        .foregroundStyle(Crete.onSurface)
                }
            }
            PillScale()
            if model.location.isOn {
                PillGps()
            }
        }
        .background(
            GeometryReader { geo in
                Color.clear
                    .onAppear { report(geo.frame(in: .global).maxY) }
                    .onChange(of: geo.frame(in: .global).maxY) { _, bottom in report(bottom) }
            }
        )
        .onAppear { model.grid.start() }
    }

    /// « Affichage du cap actuel » as a number (P11.8): the compass heading in the unit of « Unité des angles ».
    private var headingText: String? {
        let settings = model.settings
        guard settings.heading == HeadingDisplay.number || settings.heading == HeadingDisplay.both,
              let heading = model.location.heading
        else { return nil }
        return BearingFormat.format(heading, settings.bearing)
    }

    /// The figures of the UTM grid keep below the pills.
    private func report(_ bottom: CGFloat) {
        if abs(model.grid.pillsBottom - bottom) > 0.5 {
            model.grid.pillsBottom = bottom
        }
    }
}

/// The centre of the map in « Format de position » (and the second format when one is chosen), with a note when the
/// grid or the format does not hold there. A touch opens its menu (F6): the grid and its convergence, then Copier les
/// coordonnées · Partager cette position · Format suivant « DD ▸ DM ».
private struct PillPosition: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let settings = model.settings
        let center = model.center
        let position = CoordinateFormat.format(settings.coordinates, center.lat, center.lon)
        let plan = model.grid.plan
        Menu {
            PillPositionMenu(text: position.text, plan: plan, system: settings.coordinates)
        } label: {
            PillChip {
                HStack(spacing: 6) {
                    Image(systemName: "mappin")
                        .foregroundStyle(Crete.petrol)
                    PillPositionLines(position: position, center: center, plan: plan)
                }
            }
        }
        .accessibilityLabel("Ouvrir le menu de la position")
    }
}

/// The lines of the position pill.
private struct PillPositionLines: View {
    @Environment(AppModel.self) private var model
    let position: FormattedPosition
    let center: LonLat
    let plan: ScreenGridPlan?

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(position.text)
                .foregroundStyle(Crete.onSurface)
            if let secondary = model.settings.secondaryCoordinates {
                // A second format is a setting of its own (« Position secondaire »): it stays in sight.
                Text(CoordinateFormat.format(secondary, center.lat, center.lon).text)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            if let text = note {
                Text(text)
                    .foregroundStyle(Crete.danger)
            }
        }
        .multilineTextAlignment(.leading)
    }

    private var note: String? {
        switch plan?.issue {
        case .some(.polar):
            return "Grille UTM/MGRS indisponible près des pôles"
        case .some(.tooWide):
            return "Zone trop large pour une grille"
        case .some(.outsideValidity):
            return "Hors du domaine de validité du Lambert 93"
        default:
            return position.outsideValidity ? "Hors du domaine de validité du Lambert 93" : nil
        }
    }
}

/// The menu of the position pill.
private struct PillPositionMenu: View {
    @Environment(AppModel.self) private var model
    let text: String
    let plan: ScreenGridPlan?
    let system: CoordinateSystem

    var body: some View {
        if let grid = plan, !grid.isEmpty {
            Section {
                Text("Grille \(grid.systemText) · \(grid.stepText)")
                if let degrees = grid.convergenceDegrees {
                    // P24.3: grid north against true north at the centre.
                    Text("Convergence \(PrintFormat.declination(degrees, "E", "O"))")
                }
            }
        }
        Button {
            UIPasteboard.general.string = text
            model.say("Coordonnées copiées")
        } label: {
            Label("Copier les coordonnées", systemImage: "doc.on.doc")
        }
        ShareLink(item: text) {
            Label("Partager cette position", systemImage: "square.and.arrow.up")
        }
        Button {
            model.settings.coordinates = MapText.next(system)
        } label: {
            Label(nextTitle, systemImage: "arrow.left.arrow.right")
        }
    }

    private var nextTitle: String {
        let from = MapText.shortName(system)
        let to = MapText.shortName(MapText.next(system))
        return "Format suivant  \(from) ▸ \(to)"
    }
}

/// The scale as AlpineQuest gives it: the ratio and a bar whose length is the ground distance it names; opened, the
/// zoom of the tiles on screen over the last zoom of the base map (« zoom 16/18 »).
private struct PillScale: View {
    @Environment(AppModel.self) private var model
    @State private var open = false

    /// The widest the bar may be, in points.
    private static let barMaxWidth = 64.0

    var body: some View {
        let settings = model.settings
        let bar = ScaleBar.compute(model.center.lat, model.zoom, maxWidthDp: PillScale.barMaxWidth, units: settings.units)
        let ratio = ScaleBar.ratioText(MapText.scaleDenominator(metersPerPoint: model.metersPerPoint))
        Button {
            open.toggle()
        } label: {
            PillChip {
                HStack(spacing: 6) {
                    Image(systemName: "map")
                        .foregroundStyle(Crete.petrol)
                    Text(ratio)
                        .foregroundStyle(Crete.onSurface)
                    if bar.meters > 0 {
                        PillScaleTick(width: CGFloat(bar.widthDp))
                        Text(bar.label)
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                    if open {
                        Text("zoom \(zoomText)")
                            .foregroundStyle(Crete.onSurfaceVariant)
                            .padding(.leading, 2)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Échelle")
        .accessibilityHint("Afficher ou masquer le détail")
    }

    /// The zoom of the tiles MapLibre draws (ADR-032), and the last zoom of the base map.
    private var zoomText: String {
        let shown = model.zoom + log2(512.0 / Double(MapStyle.declaredTileSize))
        let zoom = String(Int(shown.rounded()))
        guard let base = model.maps.source(model.maps.baseId)?.maxZoom else { return zoom }
        return "\(zoom)/\(base)"
    }
}

/// The bar of the scale: a line with a tick at each end.
private struct PillScaleTick: View {
    let width: CGFloat

    var body: some View {
        Path { path in
            let w = max(width, 2)
            path.move(to: CGPoint(x: 1, y: 0))
            path.addLine(to: CGPoint(x: 1, y: 6))
            path.addLine(to: CGPoint(x: w - 1, y: 6))
            path.addLine(to: CGPoint(x: w - 1, y: 0))
        }
        .stroke(Crete.onSurface, style: StrokeStyle(lineWidth: 2, lineCap: .square, lineJoin: .miter))
        .frame(width: max(width, 2), height: 7)
    }
}

/// The GPS: the accuracy and the altitude of the last fix (« ±5 m · ↑ 227 m »), or the search for satellites.
/// Opened: the position of the fix (iOS gives no count of satellites) and the distance to the target.
private struct PillGps: View {
    @Environment(AppModel.self) private var model
    @State private var open = false

    var body: some View {
        PillChip {
            VStack(alignment: .leading, spacing: 2) {
                Button {
                    open.toggle()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "location.fill")
                            .foregroundStyle(Crete.turquoise)
                        PillGpsSummary()
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("GPS")
                .accessibilityHint("Afficher ou masquer le détail")
                if open {
                    PillGpsDetail()
                        .padding(.leading, 22)
                    // Android G7: the barometer's line, which opens « Caler l'altitude » (a button of its own).
                    BarometerPillLine()
                        .padding(.leading, 22)
                }
            }
        }
    }
}

private struct PillGpsSummary: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let settings = model.settings
        if let fix = model.location.location, fix.horizontalAccuracy >= 0 {
            HStack(spacing: 6) {
                Text("±\(MapText.distance(fix.horizontalAccuracy, settings))")
                    .foregroundStyle(Crete.onSurface)
                // The altitude chosen by the settings (GPS or barometer), else the GPS one.
                if let altitude = model.location.altitude {
                    Text("↑ \(MapText.elevation(altitude.meters, settings))")
                        .foregroundStyle(Crete.onSurfaceVariant)
                } else if fix.verticalAccuracy >= 0 {
                    Text("↑ \(MapText.elevation(fix.altitude, settings))")
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        } else {
            Text("GPS (recherche des satellites…)")
                .foregroundStyle(Crete.onSurfaceVariant)
        }
    }
}

private struct PillGpsDetail: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let settings = model.settings
        VStack(alignment: .leading, spacing: 2) {
            if let fix = model.location.location, fix.horizontalAccuracy >= 0 {
                let here = LonLat(lon: fix.coordinate.longitude, lat: fix.coordinate.latitude)
                Text(CoordinateFormat.format(settings.coordinates, here.lat, here.lon).text)
                if let meters = targetMeters(from: here) {
                    Text("→ \(MapText.distance(meters, settings))")
                }
            } else {
                Text("GPS (recherche des satellites…)")
            }
        }
        .foregroundStyle(Crete.onSurfaceVariant)
    }

    private func targetMeters(from here: LonLat) -> Double? {
        guard let target = MapToolsBridge.target(model) else { return nil }
        return Geodesy.distance(here.lat, here.lon, target.lat, target.lon)
    }
}

/// A pill of the map: white, 12 point corners, at least 32 points high, its text at 13 points; « Taille des boîtes »
/// scales it and « Fond des boîtes » sets how much of the map shows through it (no shadow under a see-through one).
private struct PillChip<Content: View>: View {
    @Environment(AppModel.self) private var model
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        let canvas = model.settings.canvas
        let scale = CGFloat(canvas.infoBoxSize.scale)
        let alpha = Double(canvas.infoBoxBackground.alpha)
        content()
            .font(.system(size: 13 * scale, weight: .medium).monospacedDigit())
            .padding(.horizontal, 10 * scale)
            .padding(.vertical, 7 * scale)
            .frame(minHeight: 32 * scale)
            .background(Color.white.opacity(alpha), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(alpha >= 0.9 ? 0.18 : 0), radius: 4, y: 2)
    }
}

/// A scale bar of a round length, at most 80 points wide (metric).
struct ScaleBarView: View {
    let metersPerPoint: Double

    var body: some View {
        let length = MapText.roundLength(maxMeters: metersPerPoint * 80)
        let width = metersPerPoint > 0 ? CGFloat(length / metersPerPoint) : 0
        HStack(spacing: 4) {
            Rectangle()
                .fill(Crete.petrol)
                .frame(width: max(width, 4), height: 3)
            Text(MapText.distance(length))
        }
    }
}

/// Texts of the map screen, in French (the decimal comma whatever the language of the phone, as every text of the app
/// is French).
enum MapText {
    static let locale = Locale(identifier: "fr_FR")

    /// Points per metre of an iPhone screen (about 460 pixels per inch at 3 pixels per point).
    static let pointsPerMeter: Double = {
        let pixelsPerInch = UIScreen.main.scale >= 3 ? 460.0 : 326.0
        return pixelsPerInch / UIScreen.main.scale / 0.0254
    }()

    static func number(_ value: Double, decimals: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals
        formatter.usesGroupingSeparator = true
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// Decimal degrees with the hemispheres: `45,56460° N  5,91780° E`.
    static func position(_ point: LonLat) -> String {
        let lat = number(abs(point.lat), decimals: 5) + "° " + (point.lat >= 0 ? "N" : "S")
        let lon = number(abs(point.lon), decimals: 5) + "° " + (point.lon >= 0 ? "E" : "O")
        return "\(lat)  \(lon)"
    }

    /// A position in `system` (Android `Formats.position`).
    static func position(_ point: LonLat, _ system: CoordinateSystem) -> FormattedPosition {
        CoordinateFormat.format(system, point.lat, point.lon)
    }

    /// The `n` of « 1:n » on this screen: ground metres of a point over the printed length of a point.
    static func scaleDenominator(metersPerPoint: Double) -> Double {
        metersPerPoint * pointsPerMeter
    }

    /// `1:25 000`: the ratio rounded to two significant digits.
    static func scaleRatio(metersPerPoint: Double) -> String {
        let ratio = scaleDenominator(metersPerPoint: metersPerPoint)
        guard ratio.isFinite, ratio >= 1 else { return "1:1" }
        let magnitude = pow(10, floor(log10(ratio)) - 1)
        let rounded = (ratio / magnitude).rounded() * magnitude
        return "1:" + number(rounded, decimals: 0)
    }

    /// The largest 1, 2 or 5 times a power of ten below `maxMeters`.
    static func roundLength(maxMeters: Double) -> Double {
        guard maxMeters > 0 else { return 0 }
        let power = pow(10, floor(log10(maxMeters)))
        for factor in [5.0, 2.0, 1.0] where factor * power <= maxMeters {
            return factor * power
        }
        return power
    }

    /// Metres or kilometres, metric.
    static func distance(_ meters: Double) -> String {
        if meters >= 1000 {
            let km = meters / 1000
            return number(km, decimals: km < 10 ? 1 : 0).replacingOccurrences(of: ",0", with: "") + " km"
        }
        return number(meters, decimals: 0) + " m"
    }

    /// A distance in « Unité des distances » (Android `Formats.distance`): `850 m`, `2,35 km`, `3.25 mi`.
    static func distance(_ meters: Double, _ settings: AppSettings) -> String {
        TrackFormat.distance(meters, locale: locale, units: settings.units)
    }

    /// A height in « Unité des distances »: `227 m`, `745 ft`.
    static func elevation(_ meters: Double, _ settings: AppSettings) -> String {
        TrackFormat.elevation(meters, locale: locale, units: settings.units)
    }

    /// An area in « Unité des surfaces »: `850 m²`, `12,30 ha`, `2,350 km²`.
    static func area(_ squareMeters: Double, _ settings: AppSettings) -> String {
        TrackFormat.area(squareMeters, locale: locale, units: settings.areaUnits)
    }

    /// A bearing in « Unité des angles », with its point of the compass: `128° SE`.
    static func bearing(_ degrees: Double, _ settings: AppSettings) -> String {
        let normalised = BearingFormat.normalize(degrees)
        let text = BearingFormat.format(normalised, settings.bearing)
        return "\(text) \(Navigation.compassPoint(normalised))"
    }

    static func distanceMeters(from position: CLLocation, to point: LonLat) -> Double {
        position.distance(from: CLLocation(latitude: point.lat, longitude: point.lon))
    }

    /// `± 5 m · ↑ 227 m`, or the state of the GPS.
    static func gpsSummary(_ location: CLLocation?, authorized: Bool) -> String {
        guard authorized else { return "GPS : position non autorisée" }
        guard let location, location.horizontalAccuracy >= 0 else { return "GPS (recherche des satellites…)" }
        var text = "± " + number(location.horizontalAccuracy, decimals: 0) + " m"
        if location.verticalAccuracy >= 0 {
            text += " · ↑ " + number(location.altitude, decimals: 0) + " m"
        }
        return text
    }

    /// The short name of a position format, as on its button (« DD ▾ »).
    static func shortName(_ system: CoordinateSystem) -> String {
        switch system {
        case .dd: "DD"
        case .ddm: "DM"
        case .dms: "DMS"
        case .utm: "UTM"
        case .mgrs: "MGRS"
        case .lambert93: "L93"
        }
    }

    /// The name of a position format in the menu of its button.
    static func fullName(_ system: CoordinateSystem) -> String {
        switch system {
        case .dd: "Degrés"
        case .ddm: "Degrés, minutes"
        case .dms: "Degrés, minutes, secondes"
        case .utm: "UTM"
        case .mgrs: "MGRS"
        case .lambert93: "Lambert 93"
        }
    }

    /// The format after `system`, in the order of the setting: DD, DM, DMS, UTM, MGRS, Lambert 93, then DD again.
    static func next(_ system: CoordinateSystem) -> CoordinateSystem {
        let all = CoordinateSystem.allCases
        guard let index = all.firstIndex(of: system) else { return system }
        return all[(index + 1) % all.count]
    }
}
