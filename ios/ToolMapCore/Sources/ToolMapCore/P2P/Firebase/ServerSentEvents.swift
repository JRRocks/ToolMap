// Port of p2p/src/main/kotlin/fr/toolmap/p2p/ServerSentEvents.kt (Android module :p2p).

/// Reads a `text/event-stream` (what Firebase sends for a streamed read) and hands over each event.
struct ServerSentEvents {
    private var event = ""
    private var data = ""

    /// Takes one line (without its line break); returns the event it completes, if any.
    mutating func line(_ line: String) -> (event: String, data: String)? {
        if line.isEmpty {
            var done: (event: String, data: String)?
            if !event.isEmpty || !data.isEmpty {
                done = (event.isEmpty ? "message" : event, data)
            }
            event = ""
            data = ""
            return done
        }
        if line.hasPrefix(":") { return nil }
        if line.hasPrefix("event:") {
            event = kotlinTrim(String(line.dropFirst("event:".count)))
        } else if line.hasPrefix("data:") {
            if !data.isEmpty { data += "\n" }
            var value = line.dropFirst("data:".count)
            if value.hasPrefix(" ") { value = value.dropFirst() }
            data += String(value)
        }
        return nil
    }

    /// Calls `onEvent` with the event name and its data for every event of `text`, until it ends.
    static func read(_ text: String, _ onEvent: (String, String) throws -> Void) rethrows {
        var reader = ServerSentEvents()
        for l in lines(text) {
            if let e = reader.line(l) { try onEvent(e.event, e.data) }
        }
    }

    /// The lines of `text` as Okio's `readUtf8Line` gives them: cut at `\n`, a `\r` before it removed, a last line
    /// without a break kept.
    static func lines(_ text: String) -> [String] {
        var out: [String] = []
        var current: [UInt8] = []
        for byte in text.utf8 {
            if byte == 0x0A {
                out.append(lineText(current))
                current.removeAll(keepingCapacity: true)
            } else {
                current.append(byte)
            }
        }
        if !current.isEmpty { out.append(lineText(current)) }
        return out
    }

    /// The text of a line's bytes, a final `\r` (of `\r\n`) removed.
    static func lineText(_ bytes: [UInt8]) -> String {
        if bytes.last == 0x0D {
            return String(decoding: bytes.dropLast(), as: UTF8.self)
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}
