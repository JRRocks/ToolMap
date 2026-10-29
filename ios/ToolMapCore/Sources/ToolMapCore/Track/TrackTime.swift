// Port of core/src/main/kotlin/fr/toolmap/core/track/TrackTime.kt.

/// Times in GPX, KML and GeoJSON files: ISO 8601, with or without offset, with up to nine fractional digits.
///
/// Kotlin tries `Instant.parse`, then `OffsetDateTime.parse`, then `LocalDateTime.parse` (as UTC). This parser accepts
/// exactly what one of the three accepts (checked against Java 21): `T` or `t`; seconds optional except for the
/// `Instant` forms; zero to nine fractional digits after a point (none after the point is accepted); offsets `Z`, `z`,
/// `+HH`, `+HH:MM`, `+HH:MM:SS` up to 18 hours; with an offset and seconds, `24:00:00` (the next day) and the leap
/// second `23:59:60` (read as `23:59:59`); years of four digits, or more with a sign.
public enum TrackTime {
    /// Milliseconds since the epoch, or nil when `text` is not a date-time. A date-time without offset is UTC.
    public static func parse(_ text: String?) -> Int64? {
        guard let text else { return nil }
        let value = kotlinTrim(text)
        if value.isEmpty { return nil }
        return parseIso(Array(value.utf8))
    }

    /// ISO 8601 in UTC, e.g. `2012-10-24T23:29:40Z`, with `.SSS` only when there are milliseconds (`Instant.toString`).
    public static func format(_ millis: Int64) -> String {
        let seconds = floorDiv(millis, 1000)
        let milli = millis - seconds * 1000
        let days = floorDiv(seconds, 86400)
        let secondOfDay = seconds - days * 86400
        let (year, month, day) = civil(fromEpochDay: days)
        var out = yearText(year) + "-" + pad(month, 2) + "-" + pad(day, 2)
        out += "T" + pad(secondOfDay / 3600, 2) + ":" + pad(secondOfDay / 60 % 60, 2) + ":" + pad(secondOfDay % 60, 2)
        if milli > 0 { out += "." + pad(milli, 3) }
        return out + "Z"
    }

    // MARK: - Calendar arithmetic (proleptic ISO calendar, as java.time)

    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    static func isLeapYear(_ year: Int64) -> Bool {
        (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0)
    }

    static func daysInMonth(_ year: Int64, _ month: Int64) -> Int64 {
        switch month {
        case 2: return isLeapYear(year) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// `LocalDate.toEpochDay`.
    static func epochDay(_ year: Int64, _ month: Int64, _ day: Int64) -> Int64 {
        let y = year
        let m = month
        var total: Int64 = 365 * y
        if y >= 0 {
            total += (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
        } else {
            total -= y / -4 - y / -100 + y / -400
        }
        total += (367 * m - 362) / 12
        total += day - 1
        if m > 2 {
            total -= 1
            if !isLeapYear(y) { total -= 1 }
        }
        return total - 719_528
    }

    /// `LocalDate.ofEpochDay`.
    static func civil(fromEpochDay epochDay: Int64) -> (year: Int64, month: Int64, day: Int64) {
        let daysPerCycle: Int64 = 146_097
        var zeroDay = epochDay + 719_528 - 60
        var adjust: Int64 = 0
        if zeroDay < 0 {
            let adjustCycles = (zeroDay + 1) / daysPerCycle - 1
            adjust = adjustCycles * 400
            zeroDay += -adjustCycles * daysPerCycle
        }
        var yearEst = (400 * zeroDay + 591) / daysPerCycle
        var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        if doyEst < 0 {
            yearEst -= 1
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        }
        yearEst += adjust
        let marchDoy0 = doyEst
        let marchMonth0 = (marchDoy0 * 5 + 2) / 153
        let month = (marchMonth0 + 2) % 12 + 1
        let day = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
        yearEst += marchMonth0 / 10
        return (yearEst, month, day)
    }

    /// The year as `LocalDate.toString` writes it: `0326`, `2026`, `+10000`, `-0005`.
    static func yearText(_ year: Int64) -> String {
        let absYear = year < 0 ? -year : year
        if absYear < 1000 { return (year < 0 ? "-" : "") + pad(absYear, 4) }
        return (year > 9999 ? "+" : "") + String(year)
    }

    static func pad(_ value: Int64, _ width: Int) -> String {
        let text = String(value)
        return text.count >= width ? text : String(repeating: "0", count: width - text.count) + text
    }

    // MARK: - Parsing

    private static func isDigit(_ b: UInt8) -> Bool { b >= 0x30 && b <= 0x39 }

    /// Reads exactly `count` ASCII digits at `i`.
    private static func number(_ s: [UInt8], _ i: inout Int, _ count: Int) -> Int64? {
        guard i + count <= s.count else { return nil }
        var value: Int64 = 0
        for k in i ..< i + count {
            guard isDigit(s[k]) else { return nil }
            value = value * 10 + Int64(s[k] - 0x30)
        }
        i += count
        return value
    }

    private static func parseIso(_ s: [UInt8]) -> Int64? {
        var i = 0
        // Year: four digits, or a sign and more digits ('+' needs more than four; "-0000" is refused).
        var negative = false
        var signed = false
        if i < s.count, s[i] == UInt8(ascii: "+") || s[i] == UInt8(ascii: "-") {
            signed = true
            negative = s[i] == UInt8(ascii: "-")
            i += 1
        }
        let yearStart = i
        while i < s.count, isDigit(s[i]) {
            i += 1
        }
        let yearDigits = i - yearStart
        if yearDigits < 4 || yearDigits > 10 { return nil }
        if !signed, yearDigits != 4 { return nil }
        if signed, !negative, yearDigits == 4 { return nil }
        var yearIndex = yearStart
        guard let yearValue = number(s, &yearIndex, yearDigits), yearValue <= 999_999_999 else { return nil }
        if negative, yearValue == 0 { return nil }
        let year = negative ? -yearValue : yearValue

        guard i < s.count, s[i] == UInt8(ascii: "-") else { return nil }
        i += 1
        guard let month = number(s, &i, 2), month >= 1, month <= 12 else { return nil }
        guard i < s.count, s[i] == UInt8(ascii: "-") else { return nil }
        i += 1
        guard let day = number(s, &i, 2), day >= 1, day <= daysInMonth(year, month) else { return nil }
        guard i < s.count, s[i] == UInt8(ascii: "T") || s[i] == UInt8(ascii: "t") else { return nil }
        i += 1
        guard let hour = number(s, &i, 2) else { return nil }
        guard i < s.count, s[i] == UInt8(ascii: ":") else { return nil }
        i += 1
        guard let minute = number(s, &i, 2) else { return nil }

        var second: Int64?
        var nanos: Int64 = 0
        if i < s.count, s[i] == UInt8(ascii: ":") {
            i += 1
            guard let secondValue = number(s, &i, 2) else { return nil }
            second = secondValue
            if i < s.count, s[i] == UInt8(ascii: ".") {
                i += 1
                var count = 0
                while i < s.count, isDigit(s[i]) {
                    if count == 9 { return nil }
                    nanos = nanos * 10 + Int64(s[i] - 0x30)
                    count += 1
                    i += 1
                }
                for _ in count ..< 9 {
                    nanos *= 10
                }
            }
        }

        var offsetSeconds: Int64?
        if i < s.count {
            let c = s[i]
            if c == UInt8(ascii: "Z") || c == UInt8(ascii: "z") {
                offsetSeconds = 0
                i += 1
            } else if c == UInt8(ascii: "+") || c == UInt8(ascii: "-") {
                i += 1
                guard let oh = number(s, &i, 2), oh <= 18 else { return nil }
                var om: Int64 = 0
                var os: Int64 = 0
                if i < s.count, s[i] == UInt8(ascii: ":") {
                    i += 1
                    guard let m = number(s, &i, 2), m <= 59 else { return nil }
                    om = m
                    if i < s.count, s[i] == UInt8(ascii: ":") {
                        i += 1
                        guard let sv = number(s, &i, 2), sv <= 59 else { return nil }
                        os = sv
                    }
                }
                let total = oh * 3600 + om * 60 + os
                if total > 18 * 3600 { return nil }
                offsetSeconds = c == UInt8(ascii: "-") ? -total : total
            } else {
                return nil
            }
        }
        if i != s.count { return nil }

        // Ranges: the usual ones, or with an offset and seconds (Instant.parse) 24:00:00 and 23:59:60.
        var h = hour
        var sec = second ?? 0
        let usual = hour <= 23 && minute <= 59 && sec <= 59
        if !usual {
            guard offsetSeconds != nil, second != nil else { return nil }
            if hour == 24, minute == 0, sec == 0, nanos == 0 {
                h = 24
            } else if hour == 23, minute == 59, sec == 60 {
                sec = 59
            } else {
                return nil
            }
        }

        let days = epochDay(year, month, day)
        let localSeconds = days * 86400 + h * 3600 + minute * 60 + sec
        let epochSeconds = localSeconds - (offsetSeconds ?? 0)
        let (millis, overflow) = epochSeconds.multipliedReportingOverflow(by: 1000)
        if overflow { return nil }
        let (result, overflow2) = millis.addingReportingOverflow(nanos / 1_000_000)
        return overflow2 ? nil : result
    }
}
