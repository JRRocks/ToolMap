import SwiftUI
import ToolMapCore

/// Mesure (Android T2, `MeasureBar`, P29.10 « Équerre »): the petrol band « Mesure » with its figures (« 3 points ·
/// 2,35 km ») and ✕; « Distance | Surface », the figures, the tiles at the centre (Ajouter ici, Tout effacer, and in
/// « Surface » mode from three points Télécharger cette zone…), then undo, redo and « Garder… ▾ ».
struct MeasurePanel: View {
    @Environment(AppModel.self) private var model
    /// « Garder… » asks the name of the route or track.
    @State private var askName = false
    @State private var keepKind: MeasureController.KeepKind = .track
    @State private var keepName = ""

    var body: some View {
        let measure = model.measure
        ToolPanel(title: "Mesure", subtitle: band(measure.result), onClose: { measure.close() }) {
            VStack(alignment: .leading, spacing: 10) {
                MeasureModePicker()
                MeasureFiguresView(result: measure.result, mode: measure.mode, settings: model.settings)
                MeasureTiles()
                MeasureFooter { kind in
                    keepName = measure.defaultName()
                    keepKind = kind
                    askName = true
                }
            }
        }
        .onAppear { model.measure.open() }
        .onDisappear {
            // Another tool took the place; a page in front of the map (full screen) leaves the measure as it is.
            if model.tool != .measure {
                model.measure.close()
            }
        }
        .alert(Text(alertTitle), isPresented: $askName) {
            TextField("Nom", text: $keepName)
            Button("Annuler", role: .cancel) {}
            Button("OK") { save() }
        }
    }

    private var alertTitle: String {
        keepKind == .route ? "Nom de l'itinéraire" : "Nom de la trace"
    }

    /// « 3 points · 2,35 km », nothing before the first point.
    private func band(_ result: MeasureResult) -> String? {
        let count = result.pointCount
        guard count > 0 else { return nil }
        let points = count == 1 ? "1 point" : "\(count) points"
        let length = MapText.distance(result.lengthMeters, model.settings)
        return "\(points) · \(length)"
    }

    private func save() {
        let name = keepName.trimmingCharacters(in: .whitespacesAndNewlines)
        model.measure.keep(keepKind, name: name.isEmpty ? model.measure.defaultName() : name)
    }
}

/// « Distance | Surface ».
private struct MeasureModePicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let measure = model.measure
        Picker("Mode de mesure", selection: Binding(get: { measure.mode }, set: { measure.setMode($0) })) {
            Text("Distance").tag(MeasureMode.path)
            Text("Surface").tag(MeasureMode.area)
        }
        .pickerStyle(.segmented)
    }
}

/// The figures of the measure (Android `MeasureFigures`); the number of points is in the band of the panel.
struct MeasureFiguresView: View {
    let result: MeasureResult
    let mode: MeasureMode
    let settings: AppSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if result.pointCount == 0 {
                Text("Placez des points : appui sur la carte, ou « Ajouter ici » au centre du viseur.")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            } else {
                if result.pointCount >= 2 {
                    lengthLines
                }
                if mode == .area {
                    areaLines
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var lengthLines: some View {
        Text("Longueur : \(MapText.distance(result.lengthMeters, settings))")
            .font(.headline)
            .foregroundStyle(Crete.onSurface)
        if let last = lastLine {
            Text(last)
                .font(.subheadline)
                .foregroundStyle(Crete.onSurface)
        }
        if let direct = directLine {
            Text(direct)
                .font(.subheadline)
                .foregroundStyle(Crete.onSurface)
        }
    }

    @ViewBuilder
    private var areaLines: some View {
        if let area = result.areaSquareMeters, let perimeter = result.perimeterMeters {
            Text("Surface : \(MapText.area(area, settings))")
                .font(.headline)
                .foregroundStyle(Crete.onSurface)
            Text("Périmètre : \(MapText.distance(perimeter, settings))")
                .font(.subheadline)
                .foregroundStyle(Crete.onSurface)
        } else {
            Text("Il faut au moins trois points pour une surface.")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
        }
    }

    /// « Dernier segment : 850 m, cap 128° SE » from three points, « Cap : 128° SE » with two.
    private var lastLine: String? {
        guard let bearing = result.lastBearingDegrees else { return nil }
        let heading = MapText.bearing(bearing, settings)
        if let meters = result.lastSegmentMeters, result.pointCount > 2 {
            return "Dernier segment : \(MapText.distance(meters, settings)), cap \(heading)"
        }
        return "Cap : \(heading)"
    }

    /// « À vol d'oiseau : 1,20 km, cap 74° E », from three points.
    private var directLine: String? {
        guard let meters = result.directMeters, let bearing = result.directBearingDegrees, result.pointCount > 2
        else { return nil }
        return "À vol d'oiseau : \(MapText.distance(meters, settings)), cap \(MapText.bearing(bearing, settings))"
    }
}

/// The actions at the centre, as tiles: « Ajouter ici » first and filled.
private struct MeasureTiles: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let measure = model.measure
        let count = measure.result.pointCount
        HStack(spacing: 8) {
            ToolTile(systemImage: "plus.circle", title: "Ajouter ici", primary: true) {
                measure.addHere()
            }
            ToolTile(systemImage: "trash", title: "Tout effacer", enabled: count > 0) {
                measure.clear()
            }
            // Only in « Surface » mode, from three points (F10).
            if measure.mode == .area, count >= MeasureController.minAreaPoints {
                ToolTile(systemImage: "arrow.down.circle", title: "Télécharger cette zone…") {
                    measure.downloadArea()
                }
            }
        }
    }
}

/// Undo, redo and « Garder… ▾ » (Android `ToolFooter`).
private struct MeasureFooter: View {
    @Environment(AppModel.self) private var model
    let onKeep: (MeasureController.KeepKind) -> Void

    var body: some View {
        let measure = model.measure
        HStack(spacing: 4) {
            Button {
                measure.undo()
            } label: {
                Image(systemName: "arrow.uturn.backward")
                    .frame(width: 44, height: 40)
            }
            .disabled(!measure.canUndo)
            .accessibilityLabel("Annuler la dernière action")
            Button {
                measure.redo()
            } label: {
                Image(systemName: "arrow.uturn.forward")
                    .frame(width: 44, height: 40)
            }
            .disabled(!measure.canRedo)
            .accessibilityLabel("Rétablir")
            Menu {
                Button("Comme itinéraire") { onKeep(.route) }
                Button("Comme trace") { onKeep(.track) }
            } label: {
                HStack(spacing: 3) {
                    Text("Garder…")
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                }
                .frame(minHeight: 40)
                .padding(.horizontal, 8)
            }
            .disabled(measure.result.pointCount < 2)
            Spacer(minLength: 0)
        }
        .font(.system(size: 16, weight: .medium))
        .foregroundStyle(Crete.petrol)
    }
}
