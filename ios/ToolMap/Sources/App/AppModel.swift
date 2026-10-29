import CoreLocation
import Foundation
import Observation
import ToolMapCore

/// The panels opened from the bottom bar (Android: Menu, Cartes, Traces et waypoints, Positionnement).
enum Panel: String, Identifiable, CaseIterable {
    case menu, maps, tracks, position

    var id: String { rawValue }
}

/// How the map follows the position (Android « La carte suit », L5): not at all, the position, the position and the
/// compass heading.
enum FollowMode: String, CaseIterable, Identifiable {
    case free, position, heading

    var id: String { rawValue }

    var title: String {
        switch self {
        case .free: "Libre"
        case .position: "Position"
        case .heading: "Position et cap"
        }
    }
}

/// One map drawn on the map view, bottom to top.
struct LayerSpec: Equatable {
    let sourceId: String
    let opacity: Double
    let minZoom: Int
    let maxZoom: Int
}

/// A camera move asked by the interface; the map applies each request once (by its id).
struct CameraRequest: Equatable {
    enum Kind: Equatable {
        case zoomBy(Double)
        case center(LonLat, zoom: Double?)
        case resetNorth
    }

    let id = UUID()
    let kind: Kind
}

/// A map shown above the background, with its own opacity.
struct Overlay: Identifiable, Equatable {
    let sourceId: String
    var opacity: Double
    var id: String { sourceId }
}

/// State of the map screen, shared by the map view and the panels.
@Observable
final class AppModel {
    private(set) var sources: [TileSource]
    /// Ids of the maps shipped with the app (the others come from imported `.aqx` files).
    private(set) var builtinIds: Set<String> = []
    let location = LocationService()

    var baseId: String
    var baseOpacity: Double = 1
    var overlays: [Overlay] = []
    var panel: Panel?
    var showInfo = true
    var nightMode = false
    /// Android "Mode hors ligne": only the stored tiles are shown, nothing is requested.
    var offline = false {
        didSet { TileService.shared.offline = offline }
    }
    var cameraRequest: CameraRequest?
    var followMode: FollowMode = .free

    /// Camera as last reported by the map.
    var center = LonLat(lon: 5.9178, lat: 45.5646)
    var zoom: Double = 13
    var direction: Double = 0
    var metersPerPoint: Double = 10

    init() {
        let builtins = (try? BuiltinSources.load()) ?? []
        let ids = Set(builtins.map(\.id))
        let imported = SourceLibrary.load().filter { !ids.contains($0.id) }
        let loaded = builtins + imported
        sources = loaded
        builtinIds = ids
        TileService.shared.register(loaded, builtins: builtins)
        baseId = loaded.first { $0.id == "ign-maps" }?.id ?? loaded.first?.id ?? ""
        applyLaunchArguments()
    }

    var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    func source(_ id: String) -> TileSource? {
        sources.first { $0.id == id }
    }

    /// Background maps offered in the panel: drawable sources that are not overlays (elevation data left out).
    var baseMaps: [TileSource] {
        sources.filter { !$0.isLayer && $0.type != "dem" && $0.isSupported }
    }

    var overlayMaps: [TileSource] {
        sources.filter { $0.isLayer && $0.isSupported }
    }

    /// What the map draws, bottom to top.
    var layerSpecs: [LayerSpec] {
        var specs: [LayerSpec] = []
        if let base = source(baseId) {
            specs.append(spec(for: base, opacity: baseOpacity))
        }
        for overlay in overlays {
            if let source = source(overlay.sourceId) {
                specs.append(spec(for: source, opacity: overlay.opacity))
            }
        }
        return specs
    }

    private func spec(for source: TileSource, opacity: Double) -> LayerSpec {
        LayerSpec(sourceId: source.id, opacity: opacity, minZoom: source.minZoom ?? 0, maxZoom: source.maxZoom ?? 22)
    }

    /// Copyright of the maps on screen, for the attribution line.
    var credits: String {
        var names: [String] = []
        for spec in layerSpecs {
            if let copyright = source(spec.sourceId)?.copyright, !names.contains(copyright) {
                names.append(copyright)
            }
        }
        return names.map { "© \($0)" }.joined(separator: " · ")
    }

    /// Adds the maps of an `.aqx` file chosen by the user; returns a sentence for the alert.
    func importMaps(from url: URL) -> String {
        do {
            let result = try SourceLibrary.importFile(url, existing: Set(sources.map(\.id)))
            sources.append(contentsOf: result.added)
            TileService.shared.register(result.added, builtins: sources.filter { builtinIds.contains($0.id) })
            var text = result.added.isEmpty
                ? "Aucune carte ajoutée."
                : (result.added.count == 1 ? "1 carte ajoutée : " : "\(result.added.count) cartes ajoutées : ")
                    + result.added.map(\.name).joined(separator: ", ") + "."
            if !result.skipped.isEmpty {
                text += " Déjà présentes : " + result.skipped.joined(separator: ", ") + "."
            }
            let unsupported = result.added.filter { !$0.isSupported }
            if !unsupported.isEmpty {
                text += " Non affichables : " + unsupported.map { "\($0.name) (\($0.unsupportedReason ?? "?"))" }.joined(separator: ", ") + "."
            }
            return text
        } catch let error as AqxParseError {
            return "Ce fichier n'est pas un fichier AQX lisible : \(error.message)"
        } catch {
            return "Le fichier n'a pas pu être lu : \(error.localizedDescription)"
        }
    }

    func isOverlayShown(_ id: String) -> Bool {
        overlays.contains { $0.sourceId == id }
    }

    func setOverlay(_ id: String, shown: Bool) {
        if shown {
            guard !isOverlayShown(id), let source = source(id) else { return }
            overlays.append(Overlay(sourceId: id, opacity: Double(source.opacity) / 100))
        } else {
            overlays.removeAll { $0.sourceId == id }
        }
    }

    func setOverlayOpacity(_ id: String, _ opacity: Double) {
        guard let index = overlays.firstIndex(where: { $0.sourceId == id }) else { return }
        overlays[index].opacity = opacity
    }

    func zoomIn() { cameraRequest = CameraRequest(kind: .zoomBy(1)) }
    func zoomOut() { cameraRequest = CameraRequest(kind: .zoomBy(-1)) }
    func resetNorth() { cameraRequest = CameraRequest(kind: .resetNorth) }

    func centerOnPosition() {
        guard let position = location.location else {
            location.start()
            return
        }
        let target = LonLat(lon: position.coordinate.longitude, lat: position.coordinate.latitude)
        cameraRequest = CameraRequest(kind: .center(target, zoom: max(zoom, 14)))
    }

    /// Debug only: `-ToolMapPanel maps`, `-ToolMapBase osm`, `-ToolMapOverlays ign-pentes`, `-ToolMapCenter 45.56,5.92,14`
    /// set the screen captured by the iOS CI (.github/workflows/ios.yml).
    private func applyLaunchArguments() {
        let defaults = UserDefaults.standard
        if let base = defaults.string(forKey: "ToolMapBase"), source(base) != nil {
            baseId = base
        }
        if let list = defaults.string(forKey: "ToolMapOverlays") {
            for id in list.split(separator: ",") {
                setOverlay(String(id), shown: true)
            }
        }
        if let text = defaults.string(forKey: "ToolMapCenter") {
            let parts = text.split(separator: ",").compactMap { Double($0) }
            if parts.count >= 2 {
                center = LonLat(lon: parts[1], lat: parts[0])
            }
            if parts.count >= 3 {
                zoom = parts[2]
            }
        }
        if let name = defaults.string(forKey: "ToolMapPanel") {
            panel = Panel(rawValue: name)
        }
        if defaults.bool(forKey: "ToolMapNight") {
            nightMode = true
        }
    }
}
