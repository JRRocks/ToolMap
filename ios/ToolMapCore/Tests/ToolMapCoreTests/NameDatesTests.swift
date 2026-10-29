// Port of core/src/test/kotlin/fr/toolmap/core/track/NameDatesTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct NameDatesTests {
    private let paris = TimeZone(identifier: "Europe/Paris")!

    /// Kotlin `LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()`.
    private func millis(_ text: String, _ zone: TimeZone? = nil) throws -> Int64 {
        let parts = text.split(whereSeparator: { $0 == "-" || $0 == "T" || $0 == ":" }).compactMap { Int($0) }
        try #require(parts.count >= 5)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone ?? paris
        let components = DateComponents(
            year: parts[0],
            month: parts[1],
            day: parts[2],
            hour: parts[3],
            minute: parts[4],
            second: parts.count > 5 ? parts[5] : 0
        )
        let date = try #require(calendar.date(from: components))
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    @Test("day, abbreviated French month, year and 24-hour time")
    func format() throws {
        #expect(try NameDates.format(millis("2026-10-22T17:02:59"), zone: paris) == "22 oct. 2026 à 17:02")
        #expect(try NameDates.format(millis("2027-05-01T08:05"), zone: paris) == "1 mai 2027 à 08:05")
        #expect(try NameDates.format(millis("2026-06-30T00:00"), zone: paris) == "30 juin 2026 à 00:00")
        #expect(try NameDates.format(millis("2026-02-15T23:59"), zone: paris) == "15 févr. 2026 à 23:59")
    }

    @Test("the day alone, for a finished download")
    func day() throws {
        #expect(try NameDates.formatDay(millis("2026-10-25T17:02"), zone: paris) == "25 oct.")
        #expect(try NameDates.formatDay(millis("2027-05-01T08:05"), zone: paris) == "1 mai")
    }

    // Kotlin changes `Locale.setDefault` (US, GERMANY, JAPAN); Swift cannot change `Locale.current`. NameDates takes no
    // locale at all: the text is the same whatever the phone language.
    @Test("the phone language does not change the format")
    func language() throws {
        let value = try millis("2026-10-22T17:02")
        for _ in ["en_US", "de_DE", "ja_JP"] {
            #expect(NameDates.format(value, zone: paris) == "22 oct. 2026 à 17:02")
        }
    }

    @Test("the time is read in the given zone")
    func zone() throws {
        let utc = try millis("2026-10-22T15:02", TimeZone(identifier: "UTC"))
        #expect(NameDates.format(utc, zone: paris) == "22 oct. 2026 à 17:02")
        let newYork = try #require(TimeZone(identifier: "America/New_York"))
        #expect(NameDates.format(utc, zone: newYork) == "22 oct. 2026 à 11:02")
    }
}
