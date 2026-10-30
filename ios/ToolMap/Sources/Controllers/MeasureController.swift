import Foundation
import Observation
import ToolMapCore

/// Mesure (Android T2, `MeasureViewModel`, P9.2): distance, area and bearings between points placed on the map, at
/// the centre with « Ajouter ici » or by a tap on the map. The maths are the core's (`Measure`, `PolylineEditor`,
/// `MeasureGeoJson`); the map draws `MeasureLayers`.
@Observable
final class MeasureController {
    @ObservationIgnored unowned let app: AppModel

    /// What « Garder… » keeps the line as.
    enum KeepKind {
        case route, track
    }

    /// A surface needs three points: from then on it can be downloaded as a zone (F10).
    static let minAreaPoints = 3

    private(set) var active = false
    private(set) var mode: MeasureMode = .path
    private(set) var result: MeasureResult = Measure.measure([])
    private(set) var canUndo = false
    private(set) var canRedo = false

    @ObservationIgnored private var editor = PolylineEditor()
    /// The handler of a tap on an empty place of the map before the tool took it.
    @ObservationIgnored private var previousTap: ((LonLat) -> Void)?
    @ObservationIgnored private var holdsTaps = false

    init(app: AppModel) {
        self.app = app
    }

    /// The points as they are now.
    var points: [TrackPoint] { editor.points }

    /// The tool panel appeared (`AppModel.tool = .measure`): an empty measure, unless one is already open.
    func open() {
        guard !active else { return }
        begin()
        if UserDefaults.standard.bool(forKey: "ToolMapDemo") {
            addDemoPoints()
        }
    }

    /// « Mesurer depuis ici » (G12) and the long press set to measure (G1): a new measure from `position`.
    func start(at position: LonLat) {
        begin()
        add(position)
        app.centerMenuOpen = false
        app.tool = .measure
    }

    /// ✕, or the panel went away: the measure is dropped.
    func close() {
        if active {
            active = false
            editor = PolylineEditor()
            mode = .path
            result = Measure.measure([])
            canUndo = false
            canRedo = false
            releaseTaps()
            show(nil)
        }
        if app.tool == .measure {
            app.tool = nil
        }
    }

    /// « Ajouter ici »: a point at the centre of the map.
    func addHere() {
        add(app.center)
    }

    func add(_ position: LonLat) {
        guard active else { return }
        if editor.add(position.lat, position.lon) {
            publish()
        }
    }

    func undo() {
        if editor.undo() { publish() }
    }

    func redo() {
        if editor.redo() { publish() }
    }

    /// « Tout effacer » (undone by « Annuler »).
    func clear() {
        if editor.clear() { publish() }
    }

    func setMode(_ newMode: MeasureMode) {
        guard newMode != mode else { return }
        mode = newMode
        publish()
    }

    /// « Garder… ▸ Comme itinéraire / Comme trace »: the line goes to the library under `name`, then the tool closes.
    func keep(_ kind: KeepKind, name: String) {
        let kept = points
        if kept.count < 2 {
            app.say("Il faut au moins deux points.")
            return
        }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let id: Int64?
        switch kind {
        case .route:
            let stops = kept.map { Waypoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
            id = MapToolsBridge.saveRoute(app, name: trimmed, points: stops)
        case .track:
            id = MapToolsBridge.saveTrack(app, name: trimmed, points: kept)
        }
        if id == nil {
            app.notice = "L'enregistrement a échoué."
            return
        }
        close()
        app.say("Enregistré.")
    }

    /// The default name of a kept measure: « Mesure du 29 oct. 2026 à 17:02 ».
    func defaultName() -> String {
        let millis = Int64(Date().timeIntervalSince1970 * 1000)
        return "Mesure du \(NameDates.format(millis))"
    }

    /// The surface measured in « Surface » mode, as a zone to download (F10); nil before three points.
    func areaZone() -> ZoneShape? {
        let kept = points
        guard mode == .area, kept.count >= MeasureController.minAreaPoints else { return nil }
        let corners = kept.map { ZoneShape.Point(lon: $0.lon, lat: $0.lat) }
        return .polygon(ZoneShape.Polygon(points: corners))
    }

    /// « Télécharger cette zone… »: the download of the surface measured; the measure closes.
    func downloadArea() {
        guard let zone = areaZone() else { return }
        close()
        MapToolsBridge.downloadZone(app, zone)
    }

    // MARK: State and map

    private func begin() {
        active = true
        editor = PolylineEditor()
        mode = .path
        takeTaps()
        publish()
    }

    private func publish() {
        let kept = editor.points
        result = Measure.measure(Measure.ofTrack(kept))
        canUndo = editor.canUndo
        canRedo = editor.canRedo
        show(kept)
    }

    /// The line, the fill and the points on the map; nil takes them away.
    private func show(_ kept: [TrackPoint]?) {
        var features = app.mapFeatures
        if let kept {
            features[MeasureLayers.fillId] = MeasureGeoJson.fill(kept, mode)
            features[MeasureLayers.lineId] = MeasureGeoJson.line(kept, mode)
            features[MeasureLayers.pointsId] = MeasureGeoJson.vertices(kept)
        } else {
            features[MeasureLayers.fillId] = nil
            features[MeasureLayers.lineId] = nil
            features[MeasureLayers.pointsId] = nil
        }
        if features != app.mapFeatures {
            app.mapFeatures = features
        }
    }

    /// While the tool is open, a tap on the map adds a point there (Android `onDrawTap`).
    private func takeTaps() {
        guard !holdsTaps else { return }
        holdsTaps = true
        previousTap = app.emptyTapHandler
        app.emptyTapHandler = { [weak self] position in
            self?.add(position)
        }
    }

    private func releaseTaps() {
        guard holdsTaps else { return }
        holdsTaps = false
        app.emptyTapHandler = previousTap
        previousTap = nil
    }

    /// Debug only (`-ToolMapDemo YES`, CI screenshots): a surface of four points around the centre.
    private func addDemoPoints() {
        let c = app.center
        let offsets: [(Double, Double)] = [(-0.012, -0.006), (0.010, -0.008), (0.014, 0.006), (-0.004, 0.010)]
        mode = .area
        for (dLon, dLat) in offsets {
            _ = editor.add(c.lat + dLat, c.lon + dLon)
        }
        publish()
    }
}
