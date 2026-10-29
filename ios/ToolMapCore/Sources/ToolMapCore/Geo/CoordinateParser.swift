// Port of core/src/main/kotlin/fr/toolmap/core/geo/CoordinateParser.kt.

import Foundation

/// Result of reading a typed position.
public enum ParsedCoordinate: Equatable, Hashable, Sendable {
    case success(lat: Double, lon: Double)
    case failure(Reason)

    public enum Reason: String, Equatable, Hashable, Sendable, CaseIterable {
        /// Nothing recognisable, or not exactly two coordinates.
        case syntax = "SYNTAX"
        /// Latitude beyond 90 degrees or longitude beyond 180.
        case range = "RANGE"
        /// Minutes or seconds of 60 or more, or a fractional degree next to minutes.
        case sexagesimal = "SEXAGESIMAL"
        /// Two latitudes, two longitudes, or a sign together with a hemisphere letter.
        case axes = "AXES"
        /// A UTM, MGRS or Lambert 93 reference that cannot be a position (letters that do not go together, range).
        case grid = "GRID"
    }
}

/// Reads positions the way people type them: decimal degrees (`44.8, 5.9`), degrees and decimal minutes
/// (`N 44° 48.000' E 5° 54.000'`) and degrees, minutes and seconds (`44°48'00"N 5°54'00"E`), with a hemisphere letter
/// before or after (N, S, E, W, and the French O for ouest) or a minus sign. Latitude comes first unless the letters
/// say otherwise. A decimal comma is accepted when the two coordinates are told apart by something else. UTM, MGRS and
/// Lambert 93 references are read too (`GridReference`).
public enum CoordinateParser {
    /// The words of the hemispheres, replaced by their letter (Kotlin `\bNORD\b` and so on).
    private static let words: [(NSRegularExpression, String)] = {
        let pairs: [(String, String)] = [
            ("NORD", "N"),
            ("SUD", "S"),
            ("EST", "E"),
            ("OUEST", "W"),
            ("NORTH", "N"),
            ("SOUTH", "S"),
            ("WEST", "W"),
            ("EAST", "E"),
        ]
        return pairs.map { (pair: (String, String)) -> (NSRegularExpression, String) in
            (try! NSRegularExpression(pattern: "\\b" + pair.0 + "\\b"), pair.1)
        }
    }()

    /// The characters of the regular expression `\s` on the JVM.
    private static let spaceClass = " \\t\\n\\x0B\\f\\r"
    private static let digitCommaDigit = try! NSRegularExpression(pattern: "[0-9],[0-9]")
    private static let spaceOrLetter = try! NSRegularExpression(pattern: "[\(spaceClass)]|[NSEWO]")
    private static let decimalComma = try! NSRegularExpression(pattern: "([0-9]),([0-9])")
    private static let spaces = try! NSRegularExpression(pattern: "[\(spaceClass)]+")

    /// Typographic marks and their plain forms, replaced in this order.
    private static let marks: [(String, String)] = [
        ("′", "'"),
        ("’", "'"),
        ("‘", "'"),
        ("´", "'"),
        ("″", "\""),
        ("”", "\""),
        ("“", "\""),
        ("''", "\""),
        ("º", "°"),
        ("˚", "°"),
        ("−", "-"),
        ("–", "-"),
    ]

    private struct Group {
        var hemisphere: Character?
        var negative = false
        var numbers: [Double] = []
        var fractionalBeforeLast = false
    }

    private enum Token {
        case hemisphere(Unicode.Scalar)
        case number(Double)
        case separator
        case mark
    }

    private enum Axis {
        case lat
        case lon
        case unknown
    }

    private enum Reading {
        case ok(Double)
        case bad(ParsedCoordinate.Reason)
    }

    public static func parse(_ text: String) -> ParsedCoordinate {
        if let grid = GridReference.parse(text) { return grid }
        let input = normalise(text)
        if kotlinTrim(input).isEmpty { return .failure(.syntax) }
        guard let groups = split(input), groups.count == 2 else { return .failure(.syntax) }
        guard let axes = axesOf(groups) else { return .failure(.axes) }
        let lat = value(groups[axes.0], 90.0)
        let lon = value(groups[axes.1], 180.0)
        switch (lat, lon) {
        case let (.bad(reason), _):
            return .failure(reason)
        case let (_, .bad(reason)):
            return .failure(reason)
        case let (.ok(latDegrees), .ok(lonDegrees)):
            return .success(lat: latDegrees, lon: lonDegrees)
        }
    }

    private static func normalise(_ text: String) -> String {
        var s = kotlinTrim(text).uppercased()
        for (regex, letter) in words {
            s = replaceAll(regex, s, letter)
        }
        for (from, to) in marks {
            s = s.replacingOccurrences(of: from, with: to, options: .literal)
        }
        // A comma between digits is a decimal comma when a semicolon separates the pair, or when no dot is used and
        // something else (a space, a hemisphere letter) tells the two coordinates apart.
        if containsMatch(digitCommaDigit, s) {
            let withoutPairs = replaceAll(digitCommaDigit, s, "")
            let commaIsDecimal = s.contains(";") || (!s.contains(".") && containsMatch(spaceOrLetter, withoutPairs))
            if commaIsDecimal {
                s = replaceAll(decimalComma, s, "$1.$2")
            }
        }
        return s
    }

    /// Cuts the text into groups of numbers, closed by a trailing letter, a separator, or the start of a new letter.
    private static func split(_ input: String) -> [Group]? {
        var groups: [Group] = []
        var current = Group()
        var hasContent = false

        func close() {
            if hasContent { groups.append(current) }
            current = Group()
            hasContent = false
        }

        let stripped = Array(replaceAll(spaces, input, " ").unicodeScalars)
        // Only numbers (and unit marks): the pair is told apart by degree marks, or else by splitting the numbers in two.
        let simple = !stripped.contains { "NSEWO;,".unicodeScalars.contains($0) }
        let hasDegreeMark = stripped.contains { $0 == "°" }
        var index = 0
        while index < stripped.count {
            let c = stripped[index]
            if c == "-" || c == "+" {
                if !current.numbers.isEmpty { close() }
                current.negative = c == "-"
                hasContent = true
                index += 1
                continue
            }
            guard let found = tokenAt(stripped, index) else {
                if c == " " {
                    index += 1
                    continue
                }
                return nil
            }
            index = found.end
            switch found.token {
            case .separator:
                close()
            case let .hemisphere(raw):
                let letter: Character = raw == "O" ? "W" : Character(raw)
                if current.hemisphere != nil {
                    close()
                    current.hemisphere = letter
                    hasContent = true
                } else if !current.numbers.isEmpty {
                    current.hemisphere = letter
                    close()
                } else {
                    current.hemisphere = letter
                    hasContent = true
                }
            case let .number(number):
                if current.numbers.count == 3 { close() }
                if simple, hasDegreeMark, !current.numbers.isEmpty, degreeMarkFollows(stripped, index) {
                    close()
                }
                if let last = current.numbers.last, kotlinRem(last, 1.0) != 0.0 {
                    current.fractionalBeforeLast = true
                }
                current.numbers.append(number)
                hasContent = true
            case .mark:
                break
            }
        }
        close()
        if simple, !hasDegreeMark, groups.count == 1 { return halve(groups[0]) }
        let filled = groups.filter { !$0.numbers.isEmpty }
        return filled.count == groups.count ? filled : nil
    }

    /// The token that starts at `index` (Kotlin `[NSEWO]|\d+(?:\.\d+)?|[;,]|[°'"]`), and the index after it.
    private static func tokenAt(_ text: [Unicode.Scalar], _ index: Int) -> (token: Token, end: Int)? {
        let c = text[index]
        if "NSEWO".unicodeScalars.contains(c) { return (.hemisphere(c), index + 1) }
        if isDigit(c) {
            var end = index
            while end < text.count, isDigit(text[end]) {
                end += 1
            }
            if end + 1 < text.count, text[end] == ".", isDigit(text[end + 1]) {
                end += 1
                while end < text.count, isDigit(text[end]) {
                    end += 1
                }
            }
            let digits = String(String.UnicodeScalarView(text[index ..< end]))
            return (.number(Double(digits) ?? 0.0), end)
        }
        if c == ";" || c == "," { return (.separator, index + 1) }
        if c == "°" || c == "'" || c == "\"" { return (.mark, index + 1) }
        return nil
    }

    private static func isDigit(_ c: Unicode.Scalar) -> Bool {
        c.value >= 0x30 && c.value <= 0x39
    }

    /// Whether the number that ends at `end` is followed by a degree sign (spaces allowed).
    private static func degreeMarkFollows(_ text: [Unicode.Scalar], _ end: Int) -> Bool {
        var i = end
        while i < text.count, KotlinText.isWhitespace(text[i]) {
            i += 1
        }
        return i < text.count && text[i] == "°"
    }

    /// `44 48 36 5 54 36`: an even count of plain numbers is two coordinates of the same kind.
    private static func halve(_ all: Group) -> [Group]? {
        let n = all.numbers.count
        guard n == 2 || n == 4 || n == 6 else { return nil }
        let parts: [[Double]] = [Array(all.numbers[0 ..< n / 2]), Array(all.numbers[n / 2 ..< n])]
        return parts.map { (part: [Double]) -> Group in
            var group = Group()
            group.numbers = part
            group.fractionalBeforeLast = part.dropLast().contains { kotlinRem($0, 1.0) != 0.0 }
            return group
        }
    }

    /// Indices of the latitude and longitude groups, or nil for an impossible pairing.
    private static func axesOf(_ groups: [Group]) -> (Int, Int)? {
        let kinds: [Axis] = groups.map { (group: Group) -> Axis in
            let letter = group.hemisphere
            if letter == "N" || letter == "S" { return .lat }
            if letter == "E" || letter == "W" { return .lon }
            return .unknown
        }
        if kinds[0] == .lat, kinds[1] != .lat { return (0, 1) }
        if kinds[0] == .lon, kinds[1] != .lon { return (1, 0) }
        if kinds[0] == .unknown, kinds[1] == .lon { return (0, 1) }
        if kinds[0] == .unknown, kinds[1] == .lat { return (1, 0) }
        if kinds[0] == .unknown, kinds[1] == .unknown { return (0, 1) }
        return nil
    }

    private static func value(_ group: Group, _ limit: Double) -> Reading {
        let n = group.numbers
        let negativeLetter = group.hemisphere == "S" || group.hemisphere == "W"
        if group.negative, group.hemisphere != nil { return .bad(.axes) }
        if group.fractionalBeforeLast { return .bad(.sexagesimal) }
        if n.dropFirst().contains(where: { $0 >= 60.0 }) { return .bad(.sexagesimal) }
        var degrees = n[0]
        if n.count > 1 { degrees += n[1] / 60.0 }
        if n.count > 2 { degrees += n[2] / 3600.0 }
        if degrees > limit { return .bad(.range) }
        return .ok(group.negative || negativeLetter ? -degrees : degrees)
    }

    private static func replaceAll(_ regex: NSRegularExpression, _ text: String, _ template: String) -> String {
        regex.stringByReplacingMatches(
            in: text,
            options: [],
            range: NSRange(location: 0, length: (text as NSString).length),
            withTemplate: template
        )
    }

    private static func containsMatch(_ regex: NSRegularExpression, _ text: String) -> Bool {
        regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }
}
