// Port of core/src/main/kotlin/fr/toolmap/core/p2p/ShareGeoJson.kt.

import Foundation

/// The members of the group as map features. The member id is a 64-bit number, which GeoJSON consumers read as a
/// double and lose digits of, so it travels as a string (`mid`).
public enum ShareGeoJson {
    /// A position older than this is drawn faded: the member may have moved a lot since.
    public static let oldFixMs: Int64 = 10 * 60 * 1000

    private static let msPerS: Int64 = 1000

    /// One point per other member with a known position; `sos` are the ids that called for help. A member that only
    /// listens (P22.3) has no point, unless its alert brought a position.
    public static func members(_ members: [MemberView], _ sos: Set<Int64>, _ nowMs: Int64) -> String {
        var out = "{\"type\":\"FeatureCollection\",\"features\":["
        var first = true
        for m in members {
            guard let fix = m.fix else { continue }
            if m.isSelf || (m.sharing == false && !sos.contains(m.id)) { continue }
            if !first { out += "," }
            first = false
            let old = m.status == .lost || m.status == .left || nowMs - fix.timeSec * msPerS > oldFixMs
            out += "{\"type\":\"Feature\",\"properties\":{\"mid\":" + JsonText.quote(String(m.id))
            out += ",\"name\":" + JsonText.quote(m.name)
            out += ",\"color\":\"" + hex(m.color) + "\""
            out += ",\"img\":" + JsonText.quote(MemberLook.imageKey(m.color, m.name, m.picture))
            out += ",\"old\":" + (old ? "true" : "false")
            out += ",\"sos\":" + (sos.contains(m.id) ? "true" : "false")
            out += "},\"geometry\":{\"type\":\"Point\",\"coordinates\":["
            out += coordinate(fix.lon) + "," + coordinate(fix.lat) + "]}}"
        }
        out += "]}"
        return out
    }

    /// The recent positions of each other member as a line, in the member's colour.
    public static func trails(_ members: [MemberView]) -> String {
        var out = "{\"type\":\"FeatureCollection\",\"features\":["
        var first = true
        for m in members {
            if m.isSelf || m.trail.count < 2 || m.sharing == false { continue }
            if !first { out += "," }
            first = false
            out += "{\"type\":\"Feature\",\"properties\":{\"color\":\"" + hex(m.color)
            out += "\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":["
            for (i, f) in m.trail.enumerated() {
                if i > 0 { out += "," }
                out += "[" + coordinate(f.lon) + "," + coordinate(f.lat) + "]"
            }
            out += "]}}"
        }
        out += "]}"
        return out
    }

    /// `#RRGGBB` of the colour, uppercase.
    private static func hex(_ argb: Int32) -> String {
        let digits = String(UInt32(bitPattern: argb) & 0xFFFFFF, radix: 16, uppercase: true)
        return "#" + String(repeating: "0", count: 6 - digits.count) + digits
    }

    private static func coordinate(_ v: Double) -> String { String(format: "%.7f", v) }
}
