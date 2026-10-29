// Port of core/src/main/kotlin/fr/toolmap/core/track/NameDates.kt.

import Foundation

/// Dates in default names ("Sortie du 22 oct. 2026 à 17:02") and in the start and end rows of a trace: always French,
/// whatever the phone language, since every other text of the app is French. The month abbreviations are those of
/// `DateTimeFormatter.ofPattern("MMM", Locale.FRENCH)` (CLDR), written here so that they never depend on the system.
public enum NameDates {
    private static let months = [
        "janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    ]

    /// `millis` since the epoch, read in `zone` (the phone's by default), e.g. `22 oct. 2026 à 17:02`.
    public static func format(_ millis: Int64, zone: TimeZone = .current) -> String {
        let t = local(millis, zone)
        return "\(t.day) \(months[Int(t.month) - 1]) \(year(t.year)) à \(TrackTime.pad(t.hour, 2)):\(TrackTime.pad(t.minute, 2))"
    }

    /// The day of `millis` alone, e.g. `25 oct.`: when a download finished (P29.7).
    public static func formatDay(_ millis: Int64, zone: TimeZone = .current) -> String {
        let t = local(millis, zone)
        return "\(t.day) \(months[Int(t.month) - 1])"
    }

    /// Pattern `yyyy`: the year of the era on four digits at least, a `+` beyond 9999.
    private static func year(_ year: Int64) -> String {
        let ofEra = year <= 0 ? 1 - year : year
        let text = TrackTime.pad(ofEra, 4)
        return ofEra > 9999 ? "+" + text : text
    }

    private static func local(
        _ millis: Int64,
        _ zone: TimeZone
    ) -> (year: Int64, month: Int64, day: Int64, hour: Int64, minute: Int64) {
        let seconds = TrackTime.floorDiv(millis, 1000)
        let offset = Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(seconds))))
        let localSeconds = seconds + offset
        let days = TrackTime.floorDiv(localSeconds, 86400)
        let secondOfDay = localSeconds - days * 86400
        let date = TrackTime.civil(fromEpochDay: days)
        return (date.year, date.month, date.day, secondOfDay / 3600, secondOfDay / 60 % 60)
    }
}
