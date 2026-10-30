import Foundation
import SwiftUI
import ToolMapCore
import UIKit

/// What the drawing, the computed routes and the following need of « Traces et waypoints » (P32.12): the shared
/// repository of the library, which saves the drawn tracks and routes, opens the ones to edit or to follow, and finds
/// the waypoints under the crosshair. `LibraryController` conforms; until it does, nothing can be saved or followed
/// and the user is told so.
protocol RouteLibrarySource: AnyObject {
    var trackRepository: TrackRepository? { get }
}

/// The elevations of the terrain (Terrarium tiles at zoom 13, as Android's `ElevationCompleter`) for the points of the
/// legs drawn by hand (`HandLegElevations`): stored tiles first, nothing asked offline. A point whose tile cannot be had
/// keeps no elevation.
enum TerrainElevations {
    static let sourceId = "terrarium"
    static let zoom = 13

    /// The same lists of points, with an elevation wherever the terrain is known; run away from the main thread.
    static func fill(_ lists: [[TrackPoint]]) async -> [[TrackPoint]] {
        var tiles: [TileCoord: TerrariumTile] = [:]
        var missing = Set<TileCoord>()
        var out: [[TrackPoint]] = []
        out.reserveCapacity(lists.count)
        for list in lists {
            var filled: [TrackPoint] = []
            filled.reserveCapacity(list.count)
            for point in list {
                guard let tile = try? TileMath.tileAt(point.lon, point.lat, zoom) else {
                    filled.append(point)
                    continue
                }
                if tiles[tile] == nil, !missing.contains(tile) {
                    if let decoded = await load(tile) {
                        tiles[tile] = decoded
                    } else {
                        missing.insert(tile)
                    }
                }
                guard let decoded = tiles[tile],
                      let fraction = try? TerrariumTile.fractionIn(point.lon, point.lat, zoom, tile.x, tile.y)
                else {
                    filled.append(point)
                    continue
                }
                let ele = decoded.elevationAt(fraction.0, fraction.1)
                filled.append(TrackPoint(lat: point.lat, lon: point.lon, ele: ele, timeMillis: point.timeMillis))
            }
            out.append(filled)
        }
        return out
    }

    private static func load(_ tile: TileCoord) async -> TerrariumTile? {
        let x = tile.x
        let y = tile.y
        let data: Data? = await withCheckedContinuation { (continuation: CheckedContinuation<Data?, Never>) in
            TileService.shared.load(sourceId, zoom, x, y) { bytes, _ in
                continuation.resume(returning: bytes)
            }
        }
        guard let png = data else { return nil }
        return try? TerrariumTile.fromPng(png)
    }
}

/// Small GeoJSON texts of the routing layers (`RouteLayers`): a line, a point, with their colour.
enum RouteGeo {
    static let empty = #"{"type":"FeatureCollection","features":[]}"#

    /// `#rrggbb` of an ARGB colour (Android's `"#%06x".format(argb and 0xFFFFFF)`).
    static func hex(_ argb: Int32) -> String {
        let rgb = UInt32(bitPattern: argb) & 0xFF_FFFF
        let digits = String(rgb, radix: 16)
        let pad = String(repeating: "0", count: max(0, 6 - digits.count))
        return "#\(pad)\(digits)"
    }

    /// One line through `points` (`[lon, lat]`), with `color` and `pale`.
    static func line(_ points: [LonLat], color: String, pale: Bool = false) -> String {
        guard points.count >= 2 else { return empty }
        var coordinates: [String] = []
        coordinates.reserveCapacity(points.count)
        for p in points {
            coordinates.append(position(p.lon, p.lat))
        }
        let joined = coordinates.joined(separator: ",")
        let flag = pale ? "true" : "false"
        let head = #"{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"color":""#
        let properties = "\(color)\",\"pale\":\(flag)}"
        let geometry = #","geometry":{"type":"LineString","coordinates":["#
        return "\(head)\(properties)\(geometry)\(joined)]}}]}"
    }

    /// One point with `color`.
    static func point(_ lat: Double, _ lon: Double, color: String) -> String {
        let head = #"{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"color":""#
        let geometry = #"},"geometry":{"type":"Point","coordinates":"#
        return "\(head)\(color)\"\(geometry)\(position(lon, lat))}}]}"
    }

    /// `[lon,lat]` with six decimals (about 10 cm), whatever the language of the phone.
    static func position(_ lon: Double, _ lat: Double) -> String {
        let x = String(format: "%.6f", lon)
        let y = String(format: "%.6f", lat)
        return "[\(x),\(y)]"
    }
}

extension Color {
    /// A colour of the core (ARGB in a signed 32-bit integer, as Android's).
    init(routeArgb: Int32) {
        let value = UInt32(bitPattern: routeArgb)
        self.init(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}

extension UIColor {
    /// A colour of the routing layers, `0xRRGGBB`.
    static func routeColor(_ hex: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

/// The waypoint icons drawn white on the disc of their colour, for the lists and sheets of the routes (Android
/// `WaypointIconBadge`); kept once drawn.
enum RouteBadgeImages {
    private static var cache: [String: UIImage] = [:]

    /// The badge of `icon`, `size` points wide.
    static func badge(_ icon: WaypointCatalog.Icon, size: CGFloat) -> UIImage? {
        let side = max(Int(size.rounded()), 8)
        let key = "\(icon.id)-\(side)"
        if let found = cache[key] { return found }
        let scale = max(1, Int(UIScreen.main.scale.rounded()))
        let white = UIColor.white.cgColor
        let disc = UIColor(Color(routeArgb: icon.colorArgb)).cgColor
        let padding = Double(side) * 0.2
        guard let image = Glyphs.image(
            Glyphs.waypointKey(icon),
            size: side,
            scale: scale,
            color: white,
            background: disc,
            padding: padding
        ) else { return nil }
        let made = UIImage(cgImage: image, scale: CGFloat(scale), orientation: .up)
        cache[key] = made
        return made
    }
}

/// A waypoint icon on its coloured disc (Android `WaypointIconBadge`).
struct RouteIconBadge: View {
    let icon: WaypointCatalog.Icon
    let size: CGFloat

    var body: some View {
        if let image = RouteBadgeImages.badge(icon, size: size) {
            Image(uiImage: image)
                .resizable()
                .frame(width: size, height: size)
        } else {
            Circle()
                .fill(Color(routeArgb: icon.colorArgb))
                .frame(width: size, height: size)
        }
    }
}
