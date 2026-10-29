// Port of core/src/main/kotlin/fr/toolmap/core/route/RoutePlanGpx.kt.

import Foundation

/// The plan of a computed route in the extensions of its GPX `<rte>` (`docs/specs/routing.md` §7.3, ADR-065). The
/// `<rtept>` hold the whole geometry, readable by any app; the plan says which of them are stops and how each leg was
/// made:
///
/// ```xml
/// <extensions><tm:plan xmlns:tm="https://toolmap.app/gpx/1">
///   <tm:stop lat="45.9" lon="6.8" kind="named" name="Refuge" sym="Lodge"/>
///   <tm:leg profile="hiking-mountain" mode="routed" n="152" time="5400000"/>
///   <tm:stop lat="45.8" lon="6.9" kind="passage"/>
///   <tm:zone lat="45.85" lon="6.86" r="250"/>
///   <tm:poi lat="45.86" lon="6.85" name="Source" sym="Drinking Water"/>
/// </tm:plan></extensions>
/// ```
///
/// `n` is the number of points of the leg, both stops included: the geometry is cut back into legs with it. `tm:zone`
/// are the zones to avoid (P19.8), their radius in meters; `tm:poi` the points of interest (P30.1), which the export also
/// writes as `<wpt>` for the other apps (`poiWaypoints`). The plan is kept in the route's extras, so the library, the
/// backups and the exports carry it with no change.
public enum RoutePlanGpx {
    public static let namespace = "https://toolmap.app/gpx/1"

    // The Kotlin patterns, with Java's ASCII `\w` and `\s` spelled out (ICU's are Unicode).
    private static let planPattern = try! NSRegularExpression(
        pattern: #"<(?:[A-Za-z0-9_.-]+:)?plan\b[^>]*>[\s\S]*?</(?:[A-Za-z0-9_.-]+:)?plan>"#
    )
    private static let childPattern = try! NSRegularExpression(
        pattern: #"<(?:[A-Za-z0-9_.-]+:)?(stop|leg|zone|poi)\b([^>]*?)/?>"#
    )
    private static let attributePattern = try! NSRegularExpression(
        pattern: #"([A-Za-z0-9_.:-]+)[ \t\n\x{0B}\f\r]*=[ \t\n\x{0B}\f\r]*"([^"]*)""#
    )
    /// Kotlin's `matches`: the whole text.
    private static let emptyExtensionsPattern = try! NSRegularExpression(
        pattern: #"\A(?:<(?:[A-Za-z0-9_.-]+:)?extensions\b[^>]*>[ \t\n\x{0B}\f\r]*</(?:[A-Za-z0-9_.-]+:)?extensions>|<(?:[A-Za-z0-9_.-]+:)?extensions\b[^>]*/>)\z"#
    )
    private static let entityPattern = try! NSRegularExpression(
        pattern: #"&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);"#
    )

    /// Positions read back must fall this close to the stops for the plan to be trusted.
    private static let matchMeters = 1.0

    /// The `<tm:plan>` element of `plan`.
    public static func element(_ plan: RoutePlan) -> String {
        var out = "<tm:plan xmlns:tm=\"\(namespace)\">"
        for (i, s) in plan.stops.enumerated() {
            out += "<tm:stop lat=\"\(TrackNumberText.javaString(s.lat))\" lon=\"\(TrackNumberText.javaString(s.lon))\""
            out += " kind=\"\(s.kind == .named ? "named" : "passage")\""
            if let name = s.name { out += " name=\"\(XmlText.attribute(name))\"" }
            if let sym = s.sym { out += " sym=\"\(XmlText.attribute(sym))\"" }
            out += "/>"
            if i < plan.legs.count {
                let leg = plan.legs[i]
                out += "<tm:leg profile=\"\(XmlText.attribute(leg.profile))\""
                out += " mode=\"\(leg.mode == .straight ? "straight" : "routed")\""
                out += " n=\"\(leg.points.count)\""
                if leg.state != .ok { out += " state=\"\(stateName(leg.state))\"" }
                if let time = leg.timeMillis { out += " time=\"\(time)\"" }
                out += "/>"
            }
        }
        for z in plan.zones {
            out += "<tm:zone lat=\"\(TrackNumberText.javaString(z.lat))\" lon=\"\(TrackNumberText.javaString(z.lon))\""
            out += " r=\"\(z.radiusMeters)\"/>"
        }
        for p in plan.pois {
            out += "<tm:poi lat=\"\(TrackNumberText.javaString(p.lat))\" lon=\"\(TrackNumberText.javaString(p.lon))\""
            if let name = p.name { out += " name=\"\(XmlText.attribute(name))\"" }
            if let sym = p.sym { out += " sym=\"\(XmlText.attribute(sym))\"" }
            out += "/>"
        }
        return out + "</tm:plan>"
    }

    /// The route's extras with `plan` in place of the one they held (none when `plan` is `nil`).
    public static func attach(_ extras: [RawXml], _ plan: RoutePlan?) -> [RawXml] {
        var cleaned: [RawXml] = []
        for raw in extras {
            if !isOurs(raw) {
                cleaned.append(raw)
                continue
            }
            let xml = raw.xml
            let rest = kotlinTrim(planPattern.stringByReplacingMatches(in: xml, range: whole(xml), withTemplate: ""))
            if emptyExtensionsPattern.firstMatch(in: rest, range: whole(rest)) == nil {
                cleaned.append(RawXml(rest))
            }
        }
        guard let newPlan = plan else { return cleaned }
        let text = RoutePlanGpx.element(newPlan)
        guard let at = cleaned.firstIndex(where: { $0.localName == "extensions" && $0.xml.hasSuffix(">") && $0.xml.contains("</") })
        else {
            return cleaned + [RawXml("<extensions>\(text)</extensions>")]
        }
        let host = cleaned[at].xml
        guard let close = host.range(of: "</", options: .backwards) else { return cleaned }
        cleaned[at] = RawXml(String(host[..<close.lowerBound]) + text + String(host[close.lowerBound...]))
        return cleaned
    }

    /// The plan of a route whose points are `geometry` and extensions `extras`, or `nil` when it has none, or when the
    /// points no longer match it (the route was changed by another app): it is then a route drawn by hand.
    public static func planOf(_ geometry: [TrackPoint], _ extras: [RawXml]) -> RoutePlan? {
        guard let raw = extras.first(where: { isOurs($0) }) else { return nil }
        let xml = raw.xml
        guard let found = planPattern.firstMatch(in: xml, range: whole(xml)), let foundRange = Range(found.range, in: xml)
        else { return nil }
        let body = String(xml[foundRange])
        var stops: [RouteStop] = []
        var legs: [(attributes: [String: String], n: Int, time: Int64?)] = []
        var zones: [AvoidZone] = []
        var pois: [RoutePoi] = []
        for m in childPattern.matches(in: body, range: whole(body)) {
            let attributesText = group(m, 2, body)
            var a: [String: String] = [:]
            for am in attributePattern.matches(in: attributesText, range: whole(attributesText)) {
                a[group(am, 1, attributesText)] = unescape(group(am, 2, attributesText))
            }
            let tag = group(m, 1, body)
            if tag == "poi" {
                guard let lat = a["lat"].flatMap(kotlinDouble), let lon = a["lon"].flatMap(kotlinDouble) else { return nil }
                pois.append(RoutePoi(lat: lat, lon: lon, name: a["name"], sym: a["sym"]))
            } else if tag == "zone" {
                guard let lat = a["lat"].flatMap(kotlinDouble),
                      let lon = a["lon"].flatMap(kotlinDouble),
                      let r = a["r"].flatMap(kotlinInt),
                      r > 0
                else { return nil }
                zones.append(AvoidZone(lat: lat, lon: lon, radiusMeters: r))
            } else if tag == "stop" {
                guard let lat = a["lat"].flatMap(kotlinDouble), let lon = a["lon"].flatMap(kotlinDouble) else { return nil }
                let kind: StopKind = a["kind"] == "named" ? .named : .passage
                stops.append(RouteStop(lat: lat, lon: lon, kind: kind, name: a["name"], sym: a["sym"]))
            } else {
                guard let n = a["n"].flatMap(kotlinInt) else { return nil }
                legs.append((attributes: a, n: n, time: a["time"].flatMap { Int64($0) }))
            }
        }
        if stops.isEmpty || legs.count != stops.count - 1 { return nil }
        if stops.count == 1 {
            if geometry.count == 1 && matchesStop(geometry[0], stops[0]) {
                return RoutePlan(stops: stops, zones: zones, pois: pois)
            }
            return nil
        }
        if legs.contains(where: { $0.n < 2 }) { return nil }
        let pointCount = legs.reduce(0) { $0 + $1.n - 1 } + 1
        if pointCount != geometry.count { return nil }
        var at = 0
        var built: [RouteLeg] = []
        built.reserveCapacity(legs.count)
        for (i, leg) in legs.enumerated() {
            let points = Array(geometry[at ..< at + leg.n])
            at += leg.n - 1
            if !matchesStop(points[0], stops[i]) || !matchesStop(points[points.count - 1], stops[i + 1]) { return nil }
            built.append(
                RouteLeg(
                    profile: leg.attributes["profile"] ?? RoutingProfiles.hiking,
                    mode: leg.attributes["mode"] == "straight" ? .straight : .routed,
                    state: stateOf(leg.attributes["state"]),
                    points: points,
                    timeMillis: leg.time
                )
            )
        }
        return RoutePlan(stops: stops, legs: built, zones: zones, pois: pois)
    }

    /// The points of interest of `route`'s plan as GPX waypoints (P30.1), for an export that other apps read; none when
    /// the route has no plan.
    public static func poiWaypoints(_ route: Route) -> [Waypoint] {
        let points = route.points.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
        guard let plan = planOf(points, route.extras) else { return [] }
        return plan.pois.map { Waypoint(lat: $0.lat, lon: $0.lon, name: $0.name, sym: $0.sym) }
    }

    /// A waypoint of a file that is one of the points of interest of a route of the same file (written by
    /// `poiWaypoints`): an import keeps it in its route only, not twice.
    public static func isPoiOf(_ waypoint: Waypoint, _ routes: [Route]) -> Bool {
        routes.contains { route in
            poiWaypoints(route).contains { p in
                p.name == waypoint.name && Geodesy.distance(p.lat, p.lon, waypoint.lat, waypoint.lon) <= matchMeters
            }
        }
    }

    /// The `<rtept>` of a planned route: its whole geometry, the points on a named stop with its name and icon.
    public static func routePoints(_ plan: RoutePlan) -> [Waypoint] {
        let geometry = plan.geometry
        var named: [Int: RouteStop] = [:]
        for (i, s) in plan.stops.enumerated() where s.kind == .named {
            named[plan.stopIndices[i]] = s
        }
        var out: [Waypoint] = []
        out.reserveCapacity(geometry.count)
        for (i, p) in geometry.enumerated() {
            let stop = named[i]
            out.append(Waypoint(lat: p.lat, lon: p.lon, ele: p.ele, name: stop?.name, sym: stop?.sym))
        }
        return out
    }

    /// The `<rtept>` of a route wholly drawn by hand, stored without its plan (ADR-065): its stops, each with the
    /// elevation its legs found for it, named stops with their name and icon.
    public static func stopPoints(_ plan: RoutePlan) -> [Waypoint] {
        let geometry = plan.geometry
        var out: [Waypoint] = []
        out.reserveCapacity(plan.stops.count)
        for (i, s) in plan.stops.enumerated() {
            let named = s.kind == .named
            let at = plan.stopIndices[i]
            let ele = at >= 0 && at < geometry.count ? geometry[at].ele : nil
            out.append(Waypoint(lat: s.lat, lon: s.lon, ele: ele, name: named ? s.name : nil, sym: named ? s.sym : nil))
        }
        return out
    }

    private static func isOurs(_ raw: RawXml) -> Bool {
        raw.xml.contains(namespace) || raw.xml.contains("<tm:plan")
    }

    private static func matchesStop(_ p: TrackPoint, _ s: RouteStop) -> Bool {
        Geodesy.distance(p.lat, p.lon, s.lat, s.lon) <= matchMeters
    }

    private static func stateName(_ state: LegState) -> String {
        switch state {
        case .ok: "ok"
        case .pending: "pending"
        case .noData: "nodata"
        case .failed: "failed"
        }
    }

    private static func stateOf(_ name: String?) -> LegState {
        switch name {
        case "pending": .pending
        case "nodata": .noData
        case "failed": .failed
        default: .ok
        }
    }

    private static func unescape(_ value: String) -> String {
        if !value.contains("&") { return value }
        var out = ""
        var last = value.startIndex
        for m in entityPattern.matches(in: value, range: whole(value)) {
            guard let entity = Range(m.range, in: value) else { continue }
            out.append(contentsOf: value[last ..< entity.lowerBound])
            out += entityText(group(m, 1, value)) ?? String(value[entity])
            last = entity.upperBound
        }
        out.append(contentsOf: value[last...])
        return out
    }

    /// The character an entity stands for; `nil` for a code point that is none (Kotlin would throw there).
    private static func entityText(_ e: String) -> String? {
        switch e {
        case "amp": return "&"
        case "lt": return "<"
        case "gt": return ">"
        case "quot": return "\""
        case "apos": return "'"
        default:
            let code: Int32? = e.hasPrefix("#x") ? Int32(e.dropFirst(2), radix: 16) : Int32(e.dropFirst())
            guard let c = code, c >= 0, let scalar = Unicode.Scalar(UInt32(c)) else { return nil }
            return String(Character(scalar))
        }
    }

    private static func whole(_ text: String) -> NSRange {
        NSRange(text.startIndex..., in: text)
    }

    private static func group(_ match: NSTextCheckingResult, _ index: Int, _ text: String) -> String {
        let r = match.range(at: index)
        guard r.location != NSNotFound, let range = Range(r, in: text) else { return "" }
        return String(text[range])
    }
}
