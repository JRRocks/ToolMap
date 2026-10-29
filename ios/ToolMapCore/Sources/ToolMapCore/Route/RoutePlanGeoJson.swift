// Port of core/src/main/kotlin/fr/toolmap/core/route/RoutePlanGeoJson.kt.

import Foundation

/// GeoJSON of a route being edited (`docs/specs/routing.md` §5.7), for the editing layers of the map.
///
/// Lines: one feature per leg, whose `st` is `r` (routed), `h` (by hand: dashed), `p` (computing: grey dots) or `g`
/// (pale: the legs of a stop being moved, whose `elastic` lines join it to the crosshair); legs by hand or computing also
/// get a point at their middle carrying a `label` (none when that label is empty: a route drawn all by hand needs no
/// « à la main »). Handles: one feature per stop, whose `kind` is `start` (green, letter), `end` (red, letter), `named`
/// (colour and icon of its symbol, with its `name`), `pass` (small white disc) or `pass-sel`; `sel` marks the selected
/// stop, `i` its index.
public enum RoutePlanGeoJson {
    private static let empty = #"{"type":"FeatureCollection","features":[]}"#
    private static let circleSides = 64

    /// Glyph ids the handles use: the map loads those images.
    public static func icons(_ plan: RoutePlan) -> Set<String> {
        var out = Set<String>()
        for (i, s) in plan.stops.enumerated() {
            switch kindOf(plan, i) {
            case "start":
                out.insert(RouteMarkers.letterPrefix + RouteMarkers.letter(i))
            case "end":
                if !plan.isLoop { out.insert(RouteMarkers.letterPrefix + RouteMarkers.letter(i)) }
            case "named":
                out.insert(WaypointSymbols.iconOf(s.sym).id)
            default:
                break
            }
        }
        for p in plan.pois {
            out.insert(WaypointSymbols.iconOf(p.sym).id)
        }
        return out
    }

    /// With `taken`, the legs on both sides of that stop are `g` (pale): it is being moved (« Prendre l'étape »). With
    /// `pale`, every leg is: a zone is being resized and the way will be computed again (P30.4).
    public static func lines(
        _ plan: RoutePlan,
        _ computingLabel: String,
        _ byHandLabel: String,
        taken: Int? = nil,
        pale: Bool = false
    ) -> String {
        if plan.legs.isEmpty { return empty }
        var out = #"{"type":"FeatureCollection","features":["#
        var first = true
        var labels: [(point: TrackPoint, text: String)] = []
        for (k, leg) in plan.legs.enumerated() {
            if leg.points.count < 2 { continue }
            var isTaken = false
            if let stop = taken { isTaken = k == stop - 1 || k == stop }
            let st: String
            if pale || isTaken {
                st = "g"
            } else if leg.state == .pending {
                st = "p"
            } else if leg.isByHand {
                st = "h"
            } else {
                st = "r"
            }
            if !first { out += "," }
            first = false
            out += #"{"type":"Feature","properties":{"st":""# + st + #""}"#
            out += #","geometry":{"type":"LineString","coordinates":["#
            for (i, p) in leg.points.enumerated() {
                if i > 0 { out += "," }
                position(&out, p.lat, p.lon)
            }
            out += "]}}"
            let label = st == "p" ? computingLabel : byHandLabel
            if (st == "p" || st == "h") && !label.isEmpty {
                let path = TrackPath([leg.points])
                if let middle = path.interpolate(path.length / 2) { labels.append((point: middle, text: label)) }
            }
        }
        for entry in labels {
            if !first { out += "," }
            first = false
            out += #"{"type":"Feature","properties":{"label":"# + JsonText.quote(entry.text) + "}"
            out += #","geometry":{"type":"Point","coordinates":"#
            position(&out, entry.point.lat, entry.point.lon)
            out += "}}"
        }
        return out + "]}"
    }

    /// With `moved`, the `selected` stop is drawn there instead of at its place: it follows the crosshair. The points of
    /// interest (P30.1) come first, under the stops (`kind` = `poi`, `p` their index); with `poiMoved`, point of interest
    /// `poiMoved` is drawn at `poiAt` instead.
    public static func handles(
        _ plan: RoutePlan,
        selected: Int?,
        midpoints: Bool = false,
        moved: TrackPoint? = nil,
        poiMoved: Int? = nil,
        poiAt: TrackPoint? = nil
    ) -> String {
        if plan.stops.isEmpty && plan.pois.isEmpty { return empty }
        let all = plan.stops.count <= EditorGeoJson.maxHandles
        let loop = plan.isLoop
        var out = #"{"type":"FeatureCollection","features":["#
        var first = true
        for (k, p) in plan.pois.enumerated() {
            if !first { out += "," }
            first = false
            out += #"{"type":"Feature","properties":{"kind":"poi","p":"# + String(k)
            out += #","sel":false,"color":""# + hex(Int32(truncatingIfNeeded: WaypointSymbols.colorOf(p.sym))) + "\""
            out += #","icon":""# + WaypointSymbols.iconOf(p.sym).id + "\""
            if let name = p.name, !isBlank(name) { out += #","name":"# + JsonText.quote(name) }
            out += #"},"geometry":{"type":"Point","coordinates":"#
            var at = p.point
            if k == poiMoved, let place = poiAt { at = place }
            position(&out, at.lat, at.lon)
            out += "}}"
        }
        // Drawing by hand: the middle of each straight leg is a handle that inserts a stop there (as for a track).
        if all && midpoints {
            for (k, leg) in plan.legs.enumerated() where leg.mode == .straight {
                let m = midpoint(plan, k)
                if !first { out += "," }
                first = false
                out += #"{"type":"Feature","properties":{"kind":"m","leg":"# + String(k)
                out += #"},"geometry":{"type":"Point","coordinates":"#
                position(&out, m.lat, m.lon)
                out += "}}"
            }
        }
        // Drawn in order: the start last, so that it stays on top of the end of a loop.
        for i in plan.stops.indices.reversed() {
            let s = plan.stops[i]
            let base = kindOf(plan, i)
            let sel = i == selected
            // Thousands of passages are no help: only the ends, the named stops and the selected one then.
            if !all && base == "pass" && !sel { continue }
            let kind = base == "pass" && sel ? "pass-sel" : base
            if !first { out += "," }
            first = false
            out += #"{"type":"Feature","properties":{"kind":""# + kind + "\""
            out += #","i":"# + String(i) + #","sel":"# + (sel ? "true" : "false")
            switch base {
            case "start":
                out += #","color":""# + hex(RouteMarkers.startColor) + "\""
            case "end":
                out += #","color":""# + hex(RouteMarkers.endColor) + "\""
            case "named":
                out += #","color":""# + hex(Int32(truncatingIfNeeded: WaypointSymbols.colorOf(s.sym))) + "\""
            default:
                break
            }
            switch base {
            case "start", "end":
                // A loop ends on its start: « A » alone, not a second letter drawn through it.
                if base == "start" || !loop {
                    out += #","icon":""# + RouteMarkers.letterPrefix + RouteMarkers.letter(i) + "\""
                }
            case "named":
                out += #","icon":""# + WaypointSymbols.iconOf(s.sym).id + "\""
            default:
                break
            }
            if let name = s.name, !isBlank(name) { out += #","name":"# + JsonText.quote(name) }
            out += #"},"geometry":{"type":"Point","coordinates":"#
            var at = s.point
            if sel, let place = moved { at = place }
            position(&out, at.lat, at.lon)
            out += "}}"
        }
        return out + "]}"
    }

    /// The elastic lines of a stop being moved: from the stop before `taken` to `to` (the crosshair) and on to the stop
    /// after it, as one line; the map draws them dashed until the stop is put down and its legs computed.
    public static func elastic(_ plan: RoutePlan, _ taken: Int, _ to: TrackPoint) -> String {
        var ends: [TrackPoint] = []
        if plan.stops.indices.contains(taken - 1) { ends.append(plan.stops[taken - 1].point) }
        ends.append(to)
        if plan.stops.indices.contains(taken + 1) { ends.append(plan.stops[taken + 1].point) }
        if !plan.stops.indices.contains(taken) || ends.count < 2 { return empty }
        var out = #"{"type":"FeatureCollection","features":[{"type":"Feature","properties":{}"#
        out += #","geometry":{"type":"LineString","coordinates":["#
        for (i, p) in ends.enumerated() {
            if i > 0 { out += "," }
            position(&out, p.lat, p.lon)
        }
        return out + "]}}]}"
    }

    /// The zones to avoid (P19.8, variant A of `docs/previews/p19-nogos.html`): each a circle (`z` = `zone`) with its
    /// centre (`z` = `sign`, the no-entry sign); `placing`, the circle that follows the crosshair before it is placed
    /// (`z` = `ring`); zone `hovered`, the one under the crosshair, with a solid edge (`z` = `hover`, P30.4), drawn with
    /// the radius `hoverRadius` while its handle is pulled.
    public static func zones(
        _ plan: RoutePlan,
        placing: AvoidZone? = nil,
        hovered: Int? = nil,
        hoverRadius: Int? = nil
    ) -> String {
        if plan.zones.isEmpty && placing == nil { return empty }
        var drawn: [(zone: AvoidZone, z: String)] = []
        for (i, zone) in plan.zones.enumerated() {
            if i == hovered {
                if let radius = hoverRadius, radius > 0 {
                    drawn.append((zone: zone.copy(radiusMeters: radius), z: "hover"))
                } else {
                    drawn.append((zone: zone, z: "hover"))
                }
            } else {
                drawn.append((zone: zone, z: "zone"))
            }
        }
        if let ring = placing { drawn.append((zone: ring, z: "ring")) }
        var out = #"{"type":"FeatureCollection","features":["#
        var first = true
        for entry in drawn {
            if !first { out += "," }
            first = false
            out += #"{"type":"Feature","properties":{"z":""# + entry.z + #""}"#
            out += #","geometry":{"type":"Polygon","coordinates":[["#
            for (i, p) in circle(entry.zone).enumerated() {
                if i > 0 { out += "," }
                position(&out, p.lat, p.lon)
            }
            out += "]]}}"
            if entry.z != "ring" {
                out += #",{"type":"Feature","properties":{"z":"sign"}"#
                out += #","geometry":{"type":"Point","coordinates":"#
                position(&out, entry.zone.lat, entry.zone.lon)
                out += "}}"
            }
        }
        return out + "]}"
    }

    /// The outline of `zone`, closed: `circleSides` sides on a local flat projection (exact enough for a few km).
    static func circle(_ zone: AvoidZone) -> [TrackPoint] {
        let perDegree = Angle.radians(1.0) * Geodesy.earthRadiusMeters
        let dLat = Double(zone.radiusMeters) / perDegree
        let dLon = dLat / cos(Angle.radians(zone.lat))
        var out: [TrackPoint] = []
        out.reserveCapacity(circleSides + 1)
        for k in 0 ... circleSides {
            let a = 2 * Double.pi * Double(k % circleSides) / Double(circleSides)
            out.append(TrackPoint(lat: zone.lat + dLat * sin(a), lon: zone.lon + dLon * cos(a)))
        }
        return out
    }

    /// The middle of the straight line of leg `leg` (where its midpoint handle stands).
    public static func midpoint(_ plan: RoutePlan, _ leg: Int) -> TrackPoint {
        TrackOps.midpoint(plan.stops[leg].point, plan.stops[leg + 1].point)
    }

    private static func kindOf(_ plan: RoutePlan, _ i: Int) -> String {
        if i == 0 { return "start" }
        if i == plan.stops.count - 1 { return "end" }
        if plan.stops[i].kind == .named { return "named" }
        return "pass"
    }

    /// Kotlin `isBlank()`.
    private static func isBlank(_ text: String) -> Bool {
        kotlinTrim(text).isEmpty
    }

    private static func position(_ out: inout String, _ lat: Double, _ lon: Double) {
        out += "["
        appendDegrees(&out, lon)
        out += ","
        appendDegrees(&out, lat)
        out += "]"
    }

    /// Kotlin `TrackGeoJson.appendDegrees`: six decimals, trailing zeros removed, never in scientific notation.
    private static func appendDegrees(_ out: inout String, _ value: Double) {
        let scaled = kotlinRound(value * 1e6)
        if scaled < 0 { out += "-" }
        let magnitude = scaled.magnitude
        out += String(magnitude / 1_000_000)
        let fraction = magnitude % 1_000_000
        if fraction == 0 { return }
        var digits = String(fraction)
        if digits.count < 6 { digits = String(repeating: "0", count: 6 - digits.count) + digits }
        while digits.hasSuffix("0") {
            digits.removeLast()
        }
        out += "." + digits
    }

    /// `#rrggbb` of an ARGB colour (Kotlin `"#%06x".format(argb and 0xFFFFFF)`).
    private static func hex(_ argb: Int32) -> String {
        let rgb = UInt32(bitPattern: argb) & 0xFF_FFFF
        let digits = String(rgb, radix: 16)
        return "#" + String(repeating: "0", count: Swift.max(0, 6 - digits.count)) + digits
    }
}
