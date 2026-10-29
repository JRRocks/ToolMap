// Port of core/src/main/kotlin/fr/toolmap/core/eventlog/EventLog.kt (P21, docs/specs/event-log.md).

import Foundation

/// One thing that happened: done by the user or the app (a button, a write to the server) or undergone (GPS off).
public struct Event: Sendable {
    public let timeMs: Int64
    public let name: String
    public let fields: [(String, (any Sendable)?)]

    public init(_ timeMs: Int64, _ name: String, _ fields: [(String, (any Sendable)?)]) {
        self.timeMs = timeMs
        self.name = name
        self.fields = fields
    }
}

/// Where events go (the files of `EventStore`); called on the thread that logs, so it must not block.
public protocol EventSink: AnyObject, Sendable {
    func write(_ event: Event)
}

/// A sink made of a closure (Kotlin's `fun interface EventSink` written as a lambda).
public final class ClosureEventSink: EventSink, @unchecked Sendable {
    private let body: @Sendable (Event) -> Void

    public init(_ body: @escaping @Sendable (Event) -> Void) {
        self.body = body
    }

    public func write(_ event: Event) { body(event) }
}

/// Started once with the main process. The debug build adds its test tools this way; a release has none (P21).
public protocol AppExtension {
    func start()
}

/// A gauge declared with `EventLog.gauge`; closing it removes the gauge (only if it is still this one).
public final class EventGauge: @unchecked Sendable {
    private let name: String
    private let token: Int

    init(name: String, token: Int) {
        self.name = name
        self.token = token
    }

    public func close() {
        EventLog.removeGauge(name, token)
    }
}

/// The values of a gauge, in the order they were given.
public typealias GaugeValues = [(String, (any Sendable)?)]

private final class EventLogState: @unchecked Sendable {
    let lock = NSLock()
    var verbose = false
    var sinks: [any EventSink] = []
    var gauges: [String: (token: Int, read: @Sendable () throws -> GaugeValues)] = [:]
    var nextToken = 0
    var clock: @Sendable () -> Int64 = { EventLog.currentTimeMillis() }
}

/// The event log of the app (P21, `docs/specs/event-log.md`): one line per event, kept on the phone. Logging costs
/// almost nothing while no `EventSink` is installed (tests); `detail` events are kept only while `verbose` is on,
/// which the test environment of the debug build turns on.
///
/// Gauges are values read on demand (the state of the server connection, the members of the group): components declare
/// them with `gauge` and the debug build writes them all in its periodic snapshots.
public enum EventLog {
    private static let state = EventLogState()

    /// `System.currentTimeMillis()`.
    public static func currentTimeMillis() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
    }

    /// Keeps the `detail` events too (the high-rate ones: every frame, every keep-alive).
    public static var verbose: Bool {
        get {
            state.lock.lock()
            defer { state.lock.unlock() }
            return state.verbose
        }
        set {
            state.lock.lock()
            state.verbose = newValue
            state.lock.unlock()
        }
    }

    /// The clock of the events; tests set their own.
    public static var clock: @Sendable () -> Int64 {
        get {
            state.lock.lock()
            defer { state.lock.unlock() }
            return state.clock
        }
        set {
            state.lock.lock()
            state.clock = newValue
            state.lock.unlock()
        }
    }

    public static var isOn: Bool { !sinks().isEmpty }

    private static func sinks() -> [any EventSink] {
        state.lock.lock()
        defer { state.lock.unlock() }
        return state.sinks
    }

    public static func install(_ sink: any EventSink) {
        state.lock.lock()
        state.sinks.append(sink)
        state.lock.unlock()
    }

    public static func uninstall(_ sink: any EventSink) {
        state.lock.lock()
        if let i = state.sinks.firstIndex(where: { $0 === sink }) {
            state.sinks.remove(at: i)
        }
        state.lock.unlock()
    }

    /// Logs `name` (dotted, lowercase: `relay.write`) with its `fields`; `nil` values are left out.
    public static func event(_ name: String, _ fields: (String, (any Sendable)?)...) {
        log(name, fields)
    }

    /// Like `event`, kept only while `verbose` is on.
    public static func detail(_ name: String, _ fields: (String, (any Sendable)?)...) {
        if verbose { log(name, fields) }
    }

    /// `event` with the fields as an array.
    public static func log(_ name: String, _ fields: [(String, (any Sendable)?)]) {
        let targets = sinks()
        if targets.isEmpty { return }
        let e = Event(clock(), name, fields.filter { $0.1 != nil })
        for sink in targets {
            // The log must never break what it observes: a sink does not throw.
            sink.write(e)
        }
    }

    /// Waits at most `timeoutMs` until the events logged so far are on disk (the process is about to die).
    public static func flush(_ timeoutMs: Int64) {
        for sink in sinks() {
            (sink as? EventStore)?.flush(timeoutMs)
        }
    }

    /// Declares a gauge read by the snapshots; closing the result removes it (only if it is still this one).
    @discardableResult
    public static func gauge(_ name: String, _ read: @escaping @Sendable () throws -> GaugeValues) -> EventGauge {
        state.lock.lock()
        defer { state.lock.unlock() }
        state.nextToken += 1
        let token = state.nextToken
        state.gauges[name] = (token, read)
        return EventGauge(name: name, token: token)
    }

    static func removeGauge(_ name: String, _ token: Int) {
        state.lock.lock()
        defer { state.lock.unlock() }
        if state.gauges[name]?.token == token {
            state.gauges[name] = nil
        }
    }

    /// Every gauge, read now; one that fails is reported as such instead of stopping the others.
    public static func readGauges() -> [String: GaugeValues] {
        state.lock.lock()
        let all = state.gauges
        state.lock.unlock()
        var out: [String: GaugeValues] = [:]
        for name in all.keys.sorted() {
            guard let gauge = all[name] else { continue }
            do {
                out[name] = try gauge.read()
            } catch {
                out[name] = [("error", String(describing: type(of: error)))]
            }
        }
        return out
    }
}

/// The text of a logged event: `<seq> <epoch ms> <HH:mm:ss.SSS> <name> k=v k="v w"`. Values with a space, a quote, an
/// equals sign or a line break, and empty ones, are quoted with `\"`, `\\`, `\n` escaped.
public enum EventLine {
    /// At most 7 decimals (1 cm in latitude), no trailing zeros.
    private static let scale = 1e7

    /// Beyond this, `d * scale` would not fit in a long: written as a whole number.
    private static let large = 1e11

    /// Fields start after the fourth space: seq, epoch, time, name.
    private static let fieldsStart = 4

    public static func format(_ seq: Int64, _ event: Event, _ zone: TimeZone) -> String {
        var sb = "\(seq) \(event.timeMs) " + clockTime(event.timeMs, zone) + " " + event.name
        for (key, value) in event.fields {
            guard let present = value else { continue }
            sb += " " + key + "="
            appendValue(&sb, present)
        }
        return sb
    }

    /// `HH:mm:ss.SSS` of `timeMs` in `zone`.
    static func clockTime(_ timeMs: Int64, _ zone: TimeZone) -> String {
        let date = Date(timeIntervalSince1970: Double(timeMs) / 1000)
        let local = timeMs + Int64(zone.secondsFromGMT(for: date)) * 1000
        let dayMs: Int64 = 86_400_000
        let ms = ((local % dayMs) + dayMs) % dayMs
        let hours = ms / 3_600_000
        let minutes = ms / 60000 % 60
        let seconds = ms / 1000 % 60
        let millis = ms % 1000
        return pad(hours, 2) + ":" + pad(minutes, 2) + ":" + pad(seconds, 2) + "." + pad(millis, 3)
    }

    private static func pad(_ v: Int64, _ width: Int) -> String {
        let digits = String(v)
        return digits.count >= width ? digits : String(repeating: "0", count: width - digits.count) + digits
    }

    /// The sequence number a line starts with, `nil` for anything else (a cut line).
    public static func seqOf(_ line: String) -> Int64? {
        let units = Array(line.utf16)
        guard let end = units.firstIndex(of: 0x20), end > 0 else { return nil }
        return Int64(String(decoding: units[0 ..< end], as: UTF16.self))
    }

    /// The fields of `line` (after the name), unquoted; for the tests and the readers of the log.
    public static func fieldsOf(_ line: String) -> [String: String] {
        let c = Array(line.unicodeScalars)
        var out: [String: String] = [:]
        guard var i = nthSpace(c, fieldsStart) else { return out }
        while i < c.count {
            while i < c.count, c[i] == " " {
                i += 1
            }
            guard let eq = c[i...].firstIndex(of: "=") else { break }
            let key = String(String.UnicodeScalarView(c[i ..< eq]))
            i = eq + 1
            var value = String.UnicodeScalarView()
            if i < c.count, c[i] == "\"" {
                i += 1
                while i < c.count, c[i] != "\"" {
                    let ch = c[i]
                    if ch == "\\", i + 1 < c.count {
                        i += 1
                        value.append(c[i] == "n" ? "\n" : c[i])
                    } else {
                        value.append(ch)
                    }
                    i += 1
                }
                i += 1
            } else {
                while i < c.count, c[i] != " " {
                    value.append(c[i])
                    i += 1
                }
            }
            out[key] = String(value)
        }
        return out
    }

    /// The event name of `line` (fourth token).
    public static func nameOf(_ line: String) -> String? {
        let c = Array(line.unicodeScalars)
        guard let start = nthSpace(c, fieldsStart - 1) else { return nil }
        var end = start
        while end < c.count, c[end] != " " {
            end += 1
        }
        return String(String.UnicodeScalarView(c[start ..< end]))
    }

    /// The index after the `n`-th space, `nil` when there are fewer.
    private static func nthSpace(_ c: [Unicode.Scalar], _ n: Int) -> Int? {
        var at = -1
        for _ in 0 ..< n {
            var next = at + 1
            while next < c.count, c[next] != " " {
                next += 1
            }
            if next >= c.count { return nil }
            at = next
        }
        return at + 1
    }

    private static func appendValue(_ sb: inout String, _ value: any Sendable) {
        let text: String
        if let b = value as? Bool {
            text = b ? "true" : "false"
        } else if let d = value as? Double {
            if d.isNaN {
                text = "NaN"
            } else if d.isInfinite {
                text = d > 0 ? "Infinity" : "-Infinity"
            } else {
                text = trim(d)
            }
        } else if let f = value as? Float {
            text = trim(Double(f))
        } else {
            text = String(describing: value)
        }
        let special: (Unicode.Scalar) -> Bool = { $0 == " " || $0 == "\"" || $0 == "=" || $0 == "\n" || $0 == "\\" }
        let plain = !text.isEmpty && !text.unicodeScalars.contains(where: special)
        if plain {
            sb += text
            return
        }
        sb += "\""
        for c in text.unicodeScalars {
            switch c {
            case "\"": sb += "\\\""
            case "\\": sb += "\\\\"
            case "\n": sb += "\\n"
            case "\r": break
            default: sb.unicodeScalars.append(c)
            }
        }
        sb += "\""
    }

    /// At most 7 decimals, no trailing zeros (Kotlin: `BigDecimal(rounded.toString()).stripTrailingZeros()`).
    static func trim(_ d: Double) -> String {
        if abs(d) >= large { return String(kotlinRound(d)) }
        let rounded = Double(kotlinRound(d * scale)) / scale
        let s = plainDecimal(rounded)
        return s == "-0" ? "0" : s
    }

    /// The shortest decimal form of `d` (as Java's `Double.toString` gives it), without exponent nor trailing zeros.
    static func plainDecimal(_ d: Double) -> String {
        var text = "\(d)"
        var negative = false
        if text.hasPrefix("-") {
            negative = true
            text.removeFirst()
        }
        var mantissa = text
        var exponent = 0
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            mantissa = String(text[..<e])
            exponent = Int(text[text.index(after: e)...]) ?? 0
        }
        var digits = ""
        var point = mantissa.count
        if let dot = mantissa.firstIndex(of: ".") {
            point = mantissa.distance(from: mantissa.startIndex, to: dot)
            digits = String(mantissa[..<dot]) + String(mantissa[mantissa.index(after: dot)...])
        } else {
            digits = mantissa
        }
        point += exponent
        // Leading zeros move the point; trailing zeros go.
        while digits.hasPrefix("0") {
            digits.removeFirst()
            point -= 1
        }
        while digits.hasSuffix("0") {
            digits.removeLast()
        }
        if digits.isEmpty { return "0" }
        var out: String
        if point <= 0 {
            out = "0." + String(repeating: "0", count: -point) + digits
        } else if point >= digits.count {
            out = digits + String(repeating: "0", count: point - digits.count)
        } else {
            let cut = digits.index(digits.startIndex, offsetBy: point)
            out = String(digits[..<cut]) + "." + String(digits[cut...])
        }
        if negative { out = "-" + out }
        return out
    }
}
