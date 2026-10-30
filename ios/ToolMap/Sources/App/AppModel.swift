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
        case .position: "Suivi"
        case .heading: "Boussole"
        }
    }
}

/// A camera move asked by the interface; the map applies each request once (by its id).
struct CameraRequest: Equatable {
    enum Kind: Equatable {
        case zoomBy(Double)
        case center(LonLat, zoom: Double?)
        /// Shows the whole area, with `padding` points around it.
        case fit(Bounds, padding: Double)
        case resetNorth
    }

    let id = UUID()
    let kind: Kind
}

/// State of the map screen, shared by the map view, the panels and the feature controllers. Each feature keeps its
/// state in its own controller (`Controllers/`), created on first use.
@Observable
final class AppModel {
    let location = LocationService()

    @ObservationIgnored lazy var settingsStore = SettingsStore()
    @ObservationIgnored lazy var maps = MapsController(app: self)
    @ObservationIgnored lazy var library = LibraryController(app: self)
    @ObservationIgnored lazy var recorder = RecorderController(app: self)
    @ObservationIgnored lazy var measure = MeasureController(app: self)
    @ObservationIgnored lazy var search = SearchController(app: self)
    @ObservationIgnored lazy var downloads = DownloadController(app: self)
    @ObservationIgnored lazy var printer = PrintController(app: self)
    @ObservationIgnored lazy var routing = RoutingController(app: self)
    @ObservationIgnored lazy var sharing = ShareController(app: self)
    @ObservationIgnored lazy var grid = GridController(app: self)
    @ObservationIgnored lazy var backup = BackupController(app: self)
    @ObservationIgnored lazy var draw = DrawController(app: self)

    var panel: Panel?
    /// A full-screen page above the map.
    var screen: Screen?
    /// The tool panel above the bottom bar (measure, drawing, print, zone).
    var tool: Tool?
    /// Android M8 « Informations à l'écran », kept from one launch to the next.
    var showInfo: Bool {
        get { settingsStore.settings.showCoordinates }
        set { settingsStore.update { $0.showCoordinates = newValue } }
    }
    /// Android M2 « Masquer »: every control hidden but a white disc with the logo, which brings them back.
    var controlsHidden = false
    /// Android M3 « Verrouiller »: map gestures off, controls hidden, « Écran verrouillé » pill; a long press unlocks.
    var locked = false
    /// The quick menu of the centre point (Android G12) is open.
    var centerMenuOpen = false
    /// Android "Mode hors ligne": only the stored tiles are shown, nothing is requested.
    var offline = false {
        didSet { TileService.shared.offline = offline }
    }
    var cameraRequest: CameraRequest?
    var followMode: FollowMode = .free
    /// A message for the user (import results, errors), shown as an alert on the map.
    var notice: String?
    /// A short message at the bottom of the map (Android snackbar), with an optional action.
    var snack: Snack?

    /// Camera as last reported by the map.
    var center = LonLat(lon: 5.9178, lat: 45.5646)
    var zoom: Double = 13
    var direction: Double = 0
    var metersPerPoint: Double = 10

    /// GeoJSON drawn above the maps, by `FeatureLayerSet.id` (see `MapFeatureRegistry`).
    var mapFeatures: [String: String] = [:]

    @ObservationIgnored let mapProxy = MapProxy()
    /// What a tap on a feature does, by `FeatureLayerSet.id`; set by the feature controllers.
    @ObservationIgnored var tapHandlers: [String: (FeatureTap) -> Void] = [:]
    /// A tap on the map where there is no feature.
    @ObservationIgnored var emptyTapHandler: ((LonLat) -> Void)?
    /// A long press on the map (Android G1: waypoint, measure, target or nothing, from the settings).
    @ObservationIgnored var longPressHandler: ((LonLat) -> Void)?

    init() {
        applyLaunchArguments()
        // Android G1: one action for a long press on the map, chosen in the settings.
        longPressHandler = { [unowned self] position in
            MapToolsBridge.longPress(self, at: position)
        }
    }

    var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    var settings: AppSettings {
        get { settingsStore.settings }
        set { settingsStore.settings = newValue }
    }

    /// Android M9: the maps are darkened, not the tracks.
    var nightMode: Bool {
        get { settingsStore.settings.maps.nightMode }
        set { settingsStore.update { $0.maps.nightMode = newValue } }
    }

    /// A file opened with ToolMap from Files, Mail or AirDrop (« Ouvrir avec »).
    func open(_ url: URL) {
        switch url.pathExtension.lowercased() {
        case "aqx":
            notice = maps.importMaps(from: url)
        case "gpx", "kml", "kmz", "geojson", "json":
            library.importFiles([url])
        case "mbtiles":
            downloads.importMbTiles(url)
        default:
            if url.scheme == "toolmap" {
                sharing.openLink(url)
            } else {
                notice = "ToolMap ne sait pas ouvrir les fichiers .\(url.pathExtension)."
            }
        }
    }

    func zoomIn() { cameraRequest = CameraRequest(kind: .zoomBy(1)) }
    func zoomOut() { cameraRequest = CameraRequest(kind: .zoomBy(-1)) }
    func resetNorth() { cameraRequest = CameraRequest(kind: .resetNorth) }

    func show(_ position: LonLat, zoom: Double? = nil) {
        followMode = .free
        cameraRequest = CameraRequest(kind: .center(position, zoom: zoom))
    }

    func show(_ bounds: Bounds, padding: Double = 48) {
        followMode = .free
        cameraRequest = CameraRequest(kind: .fit(bounds, padding: padding))
    }

    func centerOnPosition() {
        guard let position = location.location else {
            location.start()
            return
        }
        let target = LonLat(lon: position.coordinate.longitude, lat: position.coordinate.latitude)
        cameraRequest = CameraRequest(kind: .center(target, zoom: max(zoom, 14)))
    }

    /// Shows a short message at the bottom of the map.
    func say(_ text: String, action: String? = nil, perform: (() -> Void)? = nil) {
        snack = Snack(text: text, action: action, perform: perform)
    }

    /// Debug only: `-ToolMapPanel maps`, `-ToolMapBase osm`, `-ToolMapOverlays ign-pentes`, `-ToolMapCenter 45.56,5.92,14`,
    /// `-ToolMapScreen settings`, `-ToolMapTool measure` set the screen captured by the iOS CI (.github/workflows/ios.yml).
    private func applyLaunchArguments() {
        let defaults = UserDefaults.standard
        maps.applyLaunchArguments(defaults)
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
        if defaults.bool(forKey: "ToolMapCenterMenu") {
            centerMenuOpen = true
        }
        if defaults.bool(forKey: "ToolMapLocked") {
            locked = true
        }
        switch defaults.string(forKey: "ToolMapScreen") {
        case "library": screen = .library
        case "search": screen = .search
        case "share": screen = .share
        case "downloads": screen = .downloads
        case "storage": screen = .storage
        case "available-maps": screen = .availableMaps
        case "settings": screen = .settings
        case "backup": screen = .backup
        case "about": screen = .about(nil)
        case "about-maps": screen = .about("maps")
        case "whats-new": screen = .whatsNew
        default: break
        }
        switch defaults.string(forKey: "ToolMapTool") {
        case "measure": tool = .measure
        case "draw": tool = .draw(.track(nil))
        case "route": tool = .draw(.route(nil))
        case "print": tool = .print(alongTrack: nil)
        case "zone": tool = .zone(around: nil)
        default: break
        }
    }
}

/// A short message at the bottom of the map (Android snackbar).
struct Snack: Identifiable {
    let id = UUID()
    let text: String
    let action: String?
    let perform: (() -> Void)?
}
