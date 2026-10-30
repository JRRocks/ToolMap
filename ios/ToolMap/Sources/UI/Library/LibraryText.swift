import Foundation
import SwiftUI
import ToolMapCore
import UIKit

/// The texts of « Traces et waypoints », in French as on Android (strings.xml `tracks_*`, `waypoint_*`), with the
/// figures in the units of the settings and the decimal comma whatever the language of the phone.
enum LibraryText {
    static let locale = Locale(identifier: "fr_FR")
    static let unnamed = "Sans nom"
    static let moveFailed =
        "Déplacement impossible : l'élément ou le dossier n'existe plus, ou le dossier serait placé dans lui-même."
    static let nothingToEdit = "Rien à modifier : cet élément est vide ou introuvable."
    static let deleteFinal = "Cette action est définitive."
    static let deleteFolderFinal = "Tout ce qu'il contient sera supprimé. Cette action est définitive."
    static let importTypes = "GPX, KML, KMZ, GeoJSON"

    // MARK: Units

    static func distance(_ meters: Double, _ settings: AppSettings) -> String {
        TrackFormat.distance(meters, locale: locale, units: settings.units)
    }

    static func elevation(_ meters: Double, _ settings: AppSettings) -> String {
        TrackFormat.elevation(meters, locale: locale, units: settings.units)
    }

    static func speed(_ metersPerSecond: Double, _ settings: AppSettings) -> String {
        TrackFormat.speed(metersPerSecond, locale: locale, units: settings.units)
    }

    static func slope(_ percent: Double, _ settings: AppSettings) -> String {
        SlopeFormat.format(percent, settings.slope, locale: locale)
    }

    /// `146° SE`: the bearing in « Unité des angles » and its point of the compass.
    static func bearing(_ degrees: Double, _ settings: AppSettings) -> String {
        let normalised = BearingFormat.normalize(degrees)
        let value = BearingFormat.format(normalised, settings.bearing, locale: locale)
        return "\(value) \(ToolMapCore.Navigation.compassPoint(normalised))"
    }

    /// A position in « Format de position ».
    static func position(_ lat: Double, _ lon: Double, _ settings: AppSettings) -> String {
        CoordinateFormat.format(settings.coordinates, lat, lon).text
    }

    // MARK: Counts

    /// « 1 trace · aucun itinéraire · 12 waypoints » (zero is written « aucun », P18.5).
    static func counts(tracks: Int, routes: Int, waypoints: Int) -> String {
        let t = count(tracks, one: "trace", many: "traces", none: "aucune trace")
        let r = count(routes, one: "itinéraire", many: "itinéraires", none: "aucun itinéraire")
        let w = count(waypoints, one: "waypoint", many: "waypoints", none: "aucun waypoint")
        return "\(t) · \(r) · \(w)"
    }

    static func count(_ n: Int, one: String, many: String, none: String) -> String {
        if n == 0 { return none }
        return n == 1 ? "1 \(one)" : "\(n) \(many)"
    }

    /// « Dossier · 5 éléments » (French counts 0 and 1 as singular).
    static func folderItems(_ n: Int) -> String {
        n <= 1 ? "Dossier · \(n) élément" : "Dossier · \(n) éléments"
    }

    static func selected(_ n: Int) -> String {
        n <= 1 ? "\(n) sélectionné" : "\(n) sélectionnés"
    }

    static func deleteMany(_ n: Int) -> String {
        n <= 1 ? "Supprimer \(n) élément ?" : "Supprimer \(n) éléments ?"
    }

    static func moveMany(_ n: Int) -> String {
        n <= 1 ? "Déplacer \(n) élément vers" : "Déplacer \(n) éléments vers"
    }

    /// « 358 · 1 segment »: the value of the « Points » row of a track.
    static func pointsSegments(_ points: Int, _ segments: Int) -> String {
        segments <= 1 ? "\(points) · \(segments) segment" : "\(points) · \(segments) segments"
    }

    /// « 3 traces, 12 waypoints importés »: what an import added, the kinds it did not add left out.
    static func imported(tracks: Int, routes: Int, waypoints: Int) -> String {
        var parts: [String] = []
        if tracks > 0 { parts.append(count(tracks, one: "trace", many: "traces", none: "")) }
        if routes > 0 { parts.append(count(routes, one: "itinéraire", many: "itinéraires", none: "")) }
        if waypoints > 0 { parts.append(count(waypoints, one: "waypoint", many: "waypoints", none: "")) }
        if parts.isEmpty {
            return "Importé — traces : 0, itinéraires : 0, waypoints : 0"
        }
        let total = tracks + routes + waypoints
        let feminine = routes == 0 && waypoints == 0
        let verb: String
        if total == 1 {
            verb = feminine ? "importée" : "importé"
        } else {
            verb = feminine ? "importées" : "importés"
        }
        return parts.joined(separator: ", ") + " " + verb
    }

    // MARK: Dates

    private static let months = [
        "janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    ]

    /// « 18 oct. », with the year when it is not this one.
    static func shortDate(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let calendar = Calendar.current
        let parts = calendar.dateComponents([.day, .month, .year], from: date)
        let thisYear = calendar.component(.year, from: Date())
        let day = "\(parts.day ?? 1) \(months[max(0, min(11, (parts.month ?? 1) - 1))])"
        let year = parts.year ?? thisYear
        return year == thisYear ? day : "\(day) \(year)"
    }

    /// « 21 oct. 2026 ».
    static func fullDate(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let parts = Calendar.current.dateComponents([.day, .month, .year], from: date)
        let month = months[max(0, min(11, (parts.month ?? 1) - 1))]
        return "\(parts.day ?? 1) \(month) \(parts.year ?? 0)"
    }

    // MARK: Items

    /// Name of a routing profile (Android `profileLabel`).
    static func profileName(_ profile: String) -> String {
        switch profile {
        case RoutingProfiles.hiking: return "Randonnée"
        case RoutingProfiles.bike: return "Vélo"
        case RoutingProfiles.roadBike: return "Vélo route"
        case RoutingProfiles.mtb: return "VTT"
        case RoutingProfiles.car: return "Voiture"
        case RoutingProfiles.shortest: return "Plus court"
        default: return profile
        }
    }

    /// Length, climb, time and profile of a saved route (P19); nil for a route drawn by hand without a plan.
    static func routeInfo(_ route: RouteEntity) -> SavedRouteInfo? {
        guard route.extrasJson != nil,
              let waypoints = try? TrackJson.decodeWaypoints(route.pointsJson),
              let extras = try? TrackJson.decodeExtras(route.extrasJson)
        else { return nil }
        let points = waypoints.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
        return SavedRouteInfo.of(points, extras)
    }

    /// `≥ 3 h 05`: the walking time of a saved route, nil without one.
    static func routeDuration(_ info: SavedRouteInfo?) -> String? {
        guard let summary = info?.summary, let millis = summary.durationMillis else { return nil }
        let prefix = summary.durationIsMinimum ? "≥ " : ""
        return prefix + TrackFormat.duration(millis)
    }

    /// A safe file name for an item called `name` (Android `TrackSharing.fileName`), with its `.gpx`.
    static func fileName(_ name: String?) -> String {
        baseName(name) + ".gpx"
    }

    /// The file name without its extension: letters, digits, `.`, `_` and `-`, at most 60 characters.
    static func baseName(_ name: String?) -> String {
        var out = ""
        var pendingGap = false
        for scalar in (name ?? "").unicodeScalars {
            let keep = CharacterSet.letters.contains(scalar) || CharacterSet.decimalDigits.contains(scalar)
                || scalar == "." || scalar == "_" || scalar == "-"
            if keep {
                if pendingGap { out.append("_") }
                pendingGap = false
                out.unicodeScalars.append(scalar)
            } else {
                pendingGap = true
            }
        }
        if pendingGap { out.append("_") }
        let trimmed = out.trimmingCharacters(in: CharacterSet(charactersIn: "_."))
        let short = String(trimmed.prefix(60))
        return short.isEmpty ? "trace" : short
    }
}

/// Colours of the library: ARGB integers as Android stores them.
enum LibraryColors {
    static func uiColor(_ argb: Int32) -> UIColor {
        let value = UInt32(bitPattern: argb)
        let alpha = CGFloat((value >> 24) & 0xFF) / 255
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: alpha
        )
    }

    static func color(_ argb: Int32) -> Color {
        Color(uiColor: uiColor(argb))
    }

    /// The slope bands of the profiles (Android `gradeColor`), from easy to very steep.
    static func grade(_ band: GradeBand) -> Color {
        switch band {
        case .flat: return Color(hex: 0x26A69A)
        case .gentle: return Color(hex: 0x66BB6A)
        case .moderate: return Color(hex: 0xFFCA28)
        case .steep: return Color(hex: 0xFB8C00)
        case .verySteep: return Color(hex: 0xE53935)
        }
    }
}

/// The SF Symbols of the library, in one place.
enum LibraryIcons {
    static let track = "point.topleft.down.curvedto.point.bottomright.up"
    static let route = "arrow.triangle.turn.up.right.diamond"
    static let waypoint = "mappin.and.ellipse"
    static let folder = "folder"
    static let importFile = "square.and.arrow.down"
    static let share = "square.and.arrow.up"
}
