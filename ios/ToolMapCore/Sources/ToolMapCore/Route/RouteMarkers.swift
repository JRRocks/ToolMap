// Port of core/src/main/kotlin/fr/toolmap/core/route/RouteMarkers.kt.

import Foundation

/// The markers of a computed route (`docs/specs/routing.md` §5.7): a green disc « A » on the start, a red disc with the
/// last letter on the end (none on a loop, which ends on « A »), the named stops and the points of interest (P30.1) with
/// their waypoint icon and name. Passages are not marked once saved.
public enum RouteMarkers {
    public static let startColor = Int32(bitPattern: 0xFF43_A047)
    public static let endColor = Int32(bitPattern: 0xFFE5_3935)

    /// Glyph ids of the letters: the map draws them as it draws the waypoint icons.
    public static let letterPrefix = "letter-"

    private static let letters = 26

    /// `A` to `Z`, then numbers: the label of the stop at `index`.
    public static func letter(_ index: Int) -> String {
        if index >= 0 && index < letters {
            return String(Character(Unicode.Scalar(UInt8(65 + index))))
        }
        return String(index + 1)
    }

    /// Markers of a saved route; `id` is given back by a tap (the negative route id, as for its line).
    public static func saved(_ id: Int64, _ plan: RoutePlan) -> [PointFeature] {
        let s = plan.stops
        if s.count < 2 { return [] }
        var out: [PointFeature] = []
        // Under the stops: drawn first.
        for p in plan.pois {
            out.append(PointFeature(id: id, name: p.name, lat: p.lat, lon: p.lon, sym: p.sym))
        }
        out.append(
            PointFeature(id: id, name: s[0].name, lat: s[0].lat, lon: s[0].lon, colorArgb: startColor, iconId: letterPrefix + letter(0))
        )
        for i in stride(from: 1, to: s.count - 1, by: 1) {
            let stop = s[i]
            if stop.kind != .named { continue }
            out.append(PointFeature(id: id, name: stop.name, lat: stop.lat, lon: stop.lon, sym: stop.sym))
        }
        // A loop (or an out and back) ends on its start: « A » alone, not a second letter drawn over it.
        if plan.isLoop { return out }
        let last = s.count - 1
        out.append(
            PointFeature(
                id: id,
                name: s[last].name,
                lat: s[last].lat,
                lon: s[last].lon,
                colorArgb: endColor,
                iconId: letterPrefix + letter(last)
            )
        )
        return out
    }
}
