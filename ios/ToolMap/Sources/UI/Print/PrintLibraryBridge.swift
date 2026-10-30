import Foundation
import ToolMapCore
import UIKit

/// What the print needs from « Traces et waypoints », in one place.
enum PrintLibraryBridge {
    /// The line of track `id`, in walking order, simplified as for a corridor (Android `TracksViewModel.corridorPath`).
    static func trackPath(_ app: AppModel, _ id: Int64) async -> [LonLat] {
        await app.library.corridorPath(ItemRefKey(kind: .track, id: id))
    }

    /// The tracks, routes and waypoints shown on the map, ready for the page (core `TrackDisplay.printItems()`).
    static func printItems(_ app: AppModel) async -> PrintItems? {
        await app.library.printItems()
    }

    /// Shows the track on the map: it is printed with the map (Android `setVisible` before the print along it).
    static func showTrack(_ app: AppModel, _ id: Int64) {
        app.library.setVisible(ItemRefKey(kind: .track, id: id), true)
    }

    /// The white glyph of a waypoint icon (`WaypointSymbols` icon id), drawn on its coloured disc by the page.
    static func waypointGlyph(_ iconId: String) -> UIImage? {
        WaypointIconImages.waypointGlyph(iconId)
    }
}
