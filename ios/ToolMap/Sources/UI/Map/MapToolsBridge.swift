import Foundation
import ToolMapCore

/// What the map tools (measure, search, centre point menu, pills) ask of the other features: the library, the
/// routing and the downloads. Every such call is here, so that a change of their interface touches one file.
enum MapToolsBridge {
    /// « Garder… ▸ Comme trace »: a new track of one segment in the library; its id, nil when it could not be saved.
    static func saveTrack(_ app: AppModel, name: String, points: [TrackPoint]) -> Int64? {
        app.library.saveDrawnTrack(name: name, points: points)
    }

    /// « Garder… ▸ Comme itinéraire »: a new route through `points`; its id, nil when it could not be saved.
    static func saveRoute(_ app: AppModel, name: String, points: [Waypoint]) -> Int64? {
        app.library.saveDrawnRoute(name: name, points: points)
    }

    /// « Enregistrer comme waypoint » of a search result: recorded at once; its id, nil when it could not be saved.
    static func saveWaypoint(_ app: AppModel, _ waypoint: Waypoint) -> Int64? {
        app.library.createWaypoint(waypoint)
    }

    /// « Créer un waypoint… » of the centre point, and the long press set to waypoint: the waypoint dialog of the
    /// library, filled with `position` (Android `WaypointDraft`).
    static func newWaypoint(_ app: AppModel, at position: LonLat) {
        app.library.newWaypoint(at: position)
    }

    /// The tracks, routes and waypoints of the library as the search sees them.
    static func libraryItems(_ app: AppModel) -> [LibraryItem] {
        app.library.searchItems()
    }

    /// « Définir comme cible », « Aller vers » and the long press set to target (Android `NavigationViewModel`).
    static func setTarget(_ app: AppModel, _ target: NavTarget) {
        app.routing.setTarget(target)
    }

    /// The target of « Aller vers », for its distance in the GPS pill.
    static func target(_ app: AppModel) -> NavTarget? {
        app.routing.target
    }

    /// « Télécharger cette zone… » of the measure (F10): the zone tool opens with the surface measured.
    static func downloadZone(_ app: AppModel, _ shape: ZoneShape) {
        app.downloads.pendingZoneShape = shape
        app.tool = .zone(around: nil)
    }

    /// Name of a point aimed at from the map (Android `target_point_name`).
    static let targetPointName = "Point visé"

    /// A long press on the map (Android G1): one action, chosen in the settings (« Appui long »): a waypoint, a
    /// measure, a target or nothing. Nothing while a tool is open.
    static func longPress(_ app: AppModel, at position: LonLat) {
        guard app.tool == nil else { return }
        switch app.settings.canvas.longPress {
        case LongPressAction.none:
            break
        case LongPressAction.waypoint:
            newWaypoint(app, at: position)
        case LongPressAction.measure:
            app.measure.start(at: position)
        case LongPressAction.target:
            setTarget(app, NavTarget(lat: position.lat, lon: position.lon, name: targetPointName))
        }
    }
}
