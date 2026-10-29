// Port of core/src/main/kotlin/fr/toolmap/core/geo/GridReference.kt.

import Foundation

/// Reads the references of the projected grids: UTM (`31T 452482 E 5011717 N`), MGRS (`31T DQ 52482 11717`) and
/// Lambert 93 (`L93 E 452482 N 6411717`). Returns nil when the text does not look like one of them, so that the caller
/// can try degrees; a text that looks like one but cannot be a position gives a `.grid` failure.
///
/// A letter right after the zone number is a latitude band (C..M south, N..X north), as printed on maps.
public enum GridReference {
    /// The characters of the regular expression `\s` on the JVM (ICU's `\s` would also take the Unicode spaces).
    private static let spaceClass = " \\t\\n\\x0B\\f\\r"

    /// Kotlin `^(?:MGRS)?\s*(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])([A-HJ-NP-V])\s*((?:\d\s*){0,10})$`.
    private static let mgrsRegex: NSRegularExpression = {
        let s = "[" + spaceClass + "]"
        let pattern = "\\A(?:MGRS)?\(s)*([0-9]{1,2})\(s)*([C-HJ-NP-X])\(s)*([A-HJ-NP-Z])([A-HJ-NP-V])\(s)*((?:[0-9]\(s)*){0,10})\\z"
        return try! NSRegularExpression(pattern: pattern)
    }()

    /// Kotlin `^(UTM)?\s*(\d{1,2})(?![\d.])\s*([C-HJ-NP-X])?\s*(.*)$`.
    private static let utmRegex: NSRegularExpression = {
        let s = "[" + spaceClass + "]"
        let pattern = "\\A(UTM)?\(s)*([0-9]{1,2})(?![0-9.])\(s)*([C-HJ-NP-X])?\(s)*(.*)\\z"
        return try! NSRegularExpression(pattern: pattern)
    }()

    /// Kotlin `^(?:L[\s-]?93|LAMBERT[\s-]*93|RGF[\s-]?93|EPSG:?2154)[\s:]*(.*)$`.
    private static let lambertRegex: NSRegularExpression = {
        let dash = "[" + spaceClass + "-]"
        let colon = "[" + spaceClass + ":]"
        let pattern = "\\A(?:L\(dash)?93|LAMBERT\(dash)*93|RGF\(dash)?93|EPSG:?2154)\(colon)*(.*)\\z"
        return try! NSRegularExpression(pattern: pattern)
    }()

    private static let numberRegex = try! NSRegularExpression(pattern: "[0-9]+(?:\\.[0-9]+)?")
    private static let marksRegex = try! NSRegularExpression(pattern: "M?[EN]|M\\b")
    private static let spacesRegex = try! NSRegularExpression(pattern: "[" + spaceClass + "]+")

    private static let mgrsDigits = 5
    private static let minEasting = 10_000.0
    private static let eastingLimit = 1_000_000.0
    private static let northingLimit = 10_000_000.0
    private static let latLimit = 90.0
    private static let lambertERange = 0.0 ... 1_300_000.0
    private static let lambertNRange = 6_000_000.0 ... 7_200_000.0

    public static func parse(_ text: String) -> ParsedCoordinate? {
        let upper = kotlinTrim(text).uppercased()
            .replacingOccurrences(of: ",", with: " ", options: .literal)
            .replacingOccurrences(of: ";", with: " ", options: .literal)
        let input = replaceAll(spacesRegex, upper, " ")
        if input.isEmpty { return nil }
        return parseMgrs(input) ?? parseLambert(input) ?? parseUtm(input)
    }

    private static func parseMgrs(_ input: String) -> ParsedCoordinate? {
        guard let m = groupValues(mgrsRegex, input) else { return nil }
        let digits = m[5].filter { (c: Character) -> Bool in c >= "0" && c <= "9" }
        if digits.count % 2 != 0 { return .failure(.grid) }
        let half = digits.count / 2
        let e = half == 0 ? 0 : (Int(digits.prefix(half)) ?? 0) * pow10(mgrsDigits - half)
        let n = half == 0 ? 0 : (Int(digits.dropFirst(half)) ?? 0) * pow10(mgrsDigits - half)
        guard let zone = Int(m[1]), let band = m[2].first, let column = m[3].first, let row = m[4].first else {
            return nil
        }
        guard let position = Mgrs.toLonLat(
            zone: zone,
            band: band,
            columnLetter: column,
            rowLetter: row,
            easting: e,
            northing: n,
            digits: half
        ) else {
            return .failure(.grid)
        }
        return .success(lat: position.lat, lon: position.lon)
    }

    private static func parseUtm(_ input: String) -> ParsedCoordinate? {
        guard let m = groupValues(utmRegex, input) else { return nil }
        let explicit = !m[1].isEmpty
        let bandText = m[3]
        if !explicit, bandText.isEmpty { return nil }
        guard let numbers = numbersOf(m[4]), numbers.count == 2 else { return nil }
        guard let zone = Int(m[2]) else { return nil }
        let easting = numbers[0]
        let northing = numbers[1]
        // A real easting has at least five digits; "44 N 5 6" is degrees and minutes. (A northing can be 0 on the equator.)
        if !explicit, easting < minEasting { return nil }
        if !(1 ... 60).contains(zone) || !(0.0 ... eastingLimit).contains(easting) || !(0.0 ... northingLimit).contains(northing) {
            return .failure(.grid)
        }
        let north = bandText.first.map { $0 >= "N" } ?? true
        let position = Utm.toLonLat(UtmCoordinate(zone: zone, north: north, easting: easting, northing: northing))
        if !(-latLimit ... latLimit).contains(position.lat) { return .failure(.grid) }
        return .success(lat: position.lat, lon: position.lon)
    }

    private static func parseLambert(_ input: String) -> ParsedCoordinate? {
        guard let m = groupValues(lambertRegex, input) else { return nil }
        guard let numbers = numbersOf(m[1]), numbers.count == 2 else { return .failure(.grid) }
        let easting = numbers[0]
        let northing = numbers[1]
        if !lambertERange.contains(easting) || !lambertNRange.contains(northing) { return .failure(.grid) }
        let position = Lambert93.toLonLat(PlanarCoordinate(easting: easting, northing: northing))
        return .success(lat: position.lat, lon: position.lon)
    }

    /// The numbers of `text` once the E / N / m marks are removed; nil when anything else is left in it.
    private static func numbersOf(_ text: String) -> [Double]? {
        let cleaned = replaceAll(marksRegex, text, " ")
        let ns = cleaned as NSString
        let matches = numberRegex.matches(in: cleaned, options: [], range: NSRange(location: 0, length: ns.length))
        let numbers = matches.compactMap { Double(ns.substring(with: $0.range)) }
        let leftover = kotlinTrim(replaceAll(numberRegex, cleaned, ""))
        return leftover.isEmpty ? numbers : nil
    }

    /// Kotlin `matchEntire(...)?.groupValues`: every group of a match of the whole text, "" for a group left out.
    private static func groupValues(_ regex: NSRegularExpression, _ text: String) -> [String]? {
        let ns = text as NSString
        guard let match = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: ns.length)),
              match.range.location == 0, match.range.length == ns.length
        else { return nil }
        var values: [String] = []
        for i in 0 ..< match.numberOfRanges {
            let range = match.range(at: i)
            values.append(range.location == NSNotFound ? "" : ns.substring(with: range))
        }
        return values
    }

    private static func replaceAll(_ regex: NSRegularExpression, _ text: String, _ template: String) -> String {
        regex.stringByReplacingMatches(
            in: text,
            options: [],
            range: NSRange(location: 0, length: (text as NSString).length),
            withTemplate: template
        )
    }

    private static func pow10(_ n: Int) -> Int {
        var r = 1
        for _ in 0 ..< n {
            r *= 10
        }
        return r
    }
}
