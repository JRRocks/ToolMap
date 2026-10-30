import Foundation
import ToolMapCore

/// What an alert says, on the notification and on the map.
struct RouteAlertText {
    let title: String
    let body: String
}

/// The texts of the drawing, the computed routes, « Aller vers » and the following, as on Android (`strings.xml`).
enum RouteText {
    /// The label of a leg being computed.
    static let computing = "calcul…"
    /// The label of a leg drawn by hand while the route follows the ways.
    static let byHand = "à la main"
    /// « point choisi »: a target without a name.
    static let unnamedTarget = "point choisi"
    static let unnamed = "Sans nom"

    /// The name of a BRouter profile: the built-in ones in French, the others as typed.
    static func profileLabel(_ profile: String) -> String {
        switch profile {
        case RoutingProfiles.hiking: "Randonnée"
        case RoutingProfiles.bike: "Vélo"
        case RoutingProfiles.roadBike: "Vélo route"
        case RoutingProfiles.mtb: "VTT"
        case RoutingProfiles.car: "Voiture"
        case RoutingProfiles.shortest: "Plus court"
        default: profile
        }
    }

    /// The SF Symbol of a profile.
    static func profileIcon(_ profile: String) -> String {
        switch profile {
        case RoutingProfiles.hiking: "figure.hiking"
        case RoutingProfiles.bike, RoutingProfiles.roadBike, RoutingProfiles.mtb: "bicycle"
        case RoutingProfiles.car: "car.fill"
        default: "arrow.triangle.branch"
        }
    }

    /// `250 m`, `1 km` (Android `Formats.size`).
    static func size(_ meters: Int, _ settings: AppSettings) -> String {
        if settings.units == .metric, meters % 1000 == 0 {
            return "\(meters / 1000) km"
        }
        return MapText.distance(Double(meters), settings)
    }

    /// How the panel names stop `i`: its letter at the ends (as on the map), else its number.
    static func stopLabel(_ plan: RoutePlan, _ i: Int) -> String {
        let last = plan.stops.count - 1
        if i == 0 || (i == last && plan.isLoop) { return RouteMarkers.letter(0) }
        if i == last { return RouteMarkers.letter(i) }
        return String(i)
    }

    /// `1 étape`, `3 étapes`.
    static func count(_ n: Int, _ one: String, _ other: String) -> String {
        n == 1 ? "\(n) \(one)" : "\(n) \(other)"
    }

    /// The message of a leg BRouter could not compute (Android `RouteNoticeVisuals`); on iPhone, also without network.
    static func notice(_ notice: RouteNotice) -> String {
        switch notice {
        case let .legByHand(error, offline):
            if offline { return "Pas de réseau : tronçon tracé à la main." }
            switch error {
            case .noData, .notInstalled, .unknownProfile: return "Pas de données BRouter ici : tronçon tracé à la main."
            case .noPath: return "BRouter ne trouve pas de chemin ici : tronçon tracé à la main."
            case .timeout: return "Calcul trop long : tronçon tracé à la main."
            case .other: return "BRouter n'a pas pu calculer ce tronçon : tracé à la main."
            }
        case let .allByHand(error, offline):
            if offline { return "Dessin à la main activé : pas de réseau." }
            switch error {
            case .noData, .notInstalled, .unknownProfile: return "Dessin à la main activé : pas de données BRouter ici."
            case .noPath: return "Dessin à la main activé : BRouter ne trouve pas de chemin ici."
            case .timeout: return "Dessin à la main activé : calcul trop long."
            case .other: return "Dessin à la main activé : BRouter n'a pas pu calculer ce tronçon."
            }
        case let .unknownProfile(profile):
            return "Profil « \(profile) » absent de BRouter."
        }
    }

    /// Why there is no way to the target, in a few words (Android `failureText`).
    static func goToFailure(_ error: RoutingError, offline: Bool) -> String {
        if offline { return "Pas de réseau : à vol d'oiseau" }
        switch error {
        case .noData: return "Pas de données BRouter ici"
        case .notInstalled: return "BRouter n'est pas installé."
        case .noPath: return "BRouter ne trouve pas de chemin : à vol d'oiseau"
        default: return "BRouter n'a pas pu calculer le chemin : à vol d'oiseau"
        }
    }

    /// The texts of an alert (Android `AlertTexts`).
    static func alert(_ event: AlertEvent, _ settings: AppSettings) -> RouteAlertText {
        switch event {
        case let .proximity(place, distanceMeters):
            let trimmed = (place.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let name = trimmed.isEmpty ? "un point surveillé" : trimmed
            let distance = MapText.distance(distanceMeters, settings)
            return RouteAlertText(title: "À proximité : \(name)", body: "À \(distance) du point surveillé")
        case let .offTrack(distanceMeters, limitMeters):
            let distance = MapText.distance(distanceMeters, settings)
            let limit = MapText.distance(limitMeters, settings)
            return RouteAlertText(title: "Hors de la trace", body: "À \(distance) de la trace (limite : \(limit))")
        case .backOnTrack:
            return RouteAlertText(title: "De retour sur la trace", body: "Vous êtes à nouveau sur le tracé.")
        case .arrived:
            return RouteAlertText(title: "Arrivée", body: "Vous avez atteint la fin de la trace.")
        }
    }

    /// `15:20`: the local time of `millis`.
    static func clock(_ millis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.locale = MapText.locale
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// `128° SE`: a bearing in « Unité des angles », with its point of the compass.
    static func bearing(_ degrees: Double, _ settings: AppSettings) -> String {
        let normalised = BearingFormat.normalize(degrees)
        let text = BearingFormat.format(normalised, settings.bearing, locale: MapText.locale)
        return "\(text) \(Navigation.compassPoint(normalised))"
    }
}
