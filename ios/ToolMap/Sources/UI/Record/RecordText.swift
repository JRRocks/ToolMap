import SwiftUI
import ToolMapCore
import UIKit

/// The texts of the position panel and of the recorder, in French as on Android (strings.xml: `position_*`,
/// `record_*`, `altitude_*`, `activity_*`), with the decimal comma whatever the language of the phone.
enum RecordText {
    static let locale = Locale(identifier: "fr_FR")

    // MARK: Messages

    static let finished = "Enregistrement terminé : la trace est dans la bibliothèque."
    static let resume = "Reprendre"
    static let permissionDenied = "L'enregistrement a besoin de l'autorisation de position précise."
    static let locationDenied = "La permission de localisation est refusée : la position ne peut pas être affichée."
    static let gpsUnavailable = "Le GPS n'a pas pu démarrer : vérifiez l'autorisation de position."
    static let saveFailing = "Les points n'ont pas pu être enregistrés ; nouvel essai en cours."
    static let signalLost = "Signal GNSS perdu pendant l'enregistrement"
    static let signalBack = "Signal GNSS retrouvé"
    static let paused = "En pause"
    static let recoverTitle = "Enregistrement interrompu"
    static let recoverMessage =
        "L'application s'est arrêtée pendant un enregistrement. Les points déjà notés sont conservés."
    static let recoverResume = "Reprendre l'enregistrement"
    static let recoverKeep = "Garder la trace telle quelle"

    // MARK: Figures

    static func distance(_ meters: Double, _ settings: AppSettings) -> String {
        TrackFormat.distance(meters, locale: locale, units: settings.units)
    }

    static func elevation(_ meters: Double, _ settings: AppSettings) -> String {
        TrackFormat.elevation(meters, locale: locale, units: settings.units)
    }

    static func speed(_ metersPerSecond: Double, _ settings: AppSettings) -> String {
        TrackFormat.speed(metersPerSecond, locale: locale, units: settings.units)
    }

    static func duration(_ millis: Int64) -> String {
        TrackFormat.duration(millis)
    }

    /// « 1 016,40 hPa » in « Unité de pression ».
    static func pressure(_ hectopascals: Double, _ settings: AppSettings) -> String {
        PressureFormat.format(hectopascals, settings.pressure, locale: locale)
    }

    /// The chip and the panel: « 1 h 12 · 4,82 km », « En pause · 1 h 12 · 4,82 km » (the time before the distance).
    static func progress(_ live: LiveStats, paused: Bool, _ settings: AppSettings) -> String {
        let figures = "\(duration(live.elapsedMillis)) · \(distance(live.distanceMeters, settings))"
        return paused ? "\(RecordText.paused) · \(figures)" : figures
    }

    // MARK: Names

    /// « Sortie du 27 oct. 2026 à 09:41 ».
    static func defaultName(_ millis: Int64) -> String {
        "Sortie du \(NameDates.format(millis))"
    }

    /// « Randonnée · Sortie du 27 oct. 2026 à 09:41 ».
    static func named(_ activity: TrackActivity, _ name: String) -> String {
        "\(activityName(activity)) · \(name)"
    }

    /// The four precisions of the points, from the densest (Android `RecordStartDialog.PROFILES`).
    static let profiles: [RecorderProfile] = [.precise, .walk, .bike, .saver]

    static func profileName(_ profile: RecorderProfile) -> String {
        switch profile {
        case .precise: "Précise"
        case .walk: "Normale"
        case .bike: "Espacée"
        case .saver: "Économie"
        }
    }

    static func profileDetail(_ profile: RecorderProfile) -> String {
        switch profile {
        case .precise: "toutes les secondes et 2 m"
        case .walk: "toutes les 2 s et 5 m"
        case .bike: "toutes les 2 s et 10 m"
        case .saver: "toutes les 10 s et 25 m"
        }
    }

    /// The name of an activity (Android `strings_waypoint_icons.xml`, `activity_*`).
    static func activityName(_ activity: TrackActivity) -> String {
        switch activity {
        case .walk: "Marche"
        case .hike: "Randonnée"
        case .run: "Course à pied"
        case .trailRun: "Trail"
        case .bicycle: "Vélo"
        case .scooter: "Trottinette"
        case .skate: "Skateboard"
        case .roller: "Roller"
        case .drive: "Voiture"
        case .motorcycle: "Moto"
        case .offroad: "Tout-terrain"
        case .rickshaw: "Pousse-pousse"
        case .autobus: "Autobus"
        case .train: "Train"
        case .tram: "Tramway"
        case .cableCar: "Téléphérique"
        case .ski: "Ski alpin"
        case .nordic: "Ski nordique"
        case .skimo: "Ski de randonnée"
        case .snowshoe: "Raquettes"
        case .atv: "Quad"
        case .snowmobile: "Motoneige"
        case .sleigh: "Luge"
        case .horse: "Cheval"
        case .boat: "Bateau"
        case .sail: "Voilier"
        case .canoe: "Canoë"
        case .water: "Sur l'eau"
        case .plane: "Avion"
        case .helicopter: "Hélicoptère"
        case .paragliding: "Parapente"
        case .air: "Dans les airs"
        case .other: "Autre"
        }
    }
}

/// The icons of the activities, drawn from the glyphs of the core (the drawables `ic_act_*` of Android) in the
/// activity's colour, kept once drawn.
enum RecordGlyphs {
    private static var cache: [String: UIImage] = [:]

    static func image(_ activity: TrackActivity, size: Int) -> UIImage? {
        let key = "\(activity.rawValue)-\(size)"
        if let hit = cache[key] { return hit }
        let color = uiColor(activity.colorArgb).cgColor
        guard let drawn = Glyphs.image(Glyphs.activityKey(activity), size: size, scale: 3, color: color) else { return nil }
        let image = UIImage(cgImage: drawn, scale: 3, orientation: .up)
        cache[key] = image
        return image
    }

    static func color(_ activity: TrackActivity) -> Color {
        Color(uiColor: uiColor(activity.colorArgb))
    }

    static func uiColor(_ argb: Int32) -> UIColor {
        let value = UInt32(bitPattern: argb)
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
    }
}

/// The red dot of a running recording, ringed in white as on the chip of the map (#E53935).
struct RecordDot: View {
    var size: CGFloat = 9

    var body: some View {
        Circle()
            .fill(Color(hex: 0xE53935))
            .frame(width: size, height: size)
            .padding(1.5)
            .background(Circle().fill(Color.white))
            .accessibilityHidden(true)
    }
}

/// An activity's icon, or a dot of its colour when the glyph is missing.
struct RecordActivityIcon: View {
    let activity: TrackActivity
    let size: CGFloat

    var body: some View {
        if let image = RecordGlyphs.image(activity, size: Int(size)) {
            Image(uiImage: image)
                .resizable()
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        } else {
            Circle()
                .fill(RecordGlyphs.color(activity))
                .frame(width: size * 0.6, height: size * 0.6)
                .frame(width: size, height: size)
                .accessibilityHidden(true)
        }
    }
}
