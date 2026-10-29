import CoreLocation
import Foundation
import Observation
import ToolMapCore

/// The panels opened from the bottom bar (Android: Menu, Cartes, Traces et waypoints, Positionnement).
enum Panel: String, Identifiable, CaseIterable {
    case menu, maps, tracks, position

    var id: String { rawValue }
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
    let sources: [TileSource]
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

    /// Camera as last reported by the map.
    var center = LonLat(lon: 5.9178, lat: 45.5646)
    var zoom: Double = 13
    var direction: Double = 0
    var metersPerPoint: Double = 10

    init() {
        let loaded = (try? BuiltinSources.load()) ?? []
        sources = loaded
        TileService.shared.register(loaded, builtins: loaded)
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
