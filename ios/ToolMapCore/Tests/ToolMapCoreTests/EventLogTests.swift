// Port of core/src/test/kotlin/fr/toolmap/core/eventlog/EventLogTest.kt.
//
// The tests run in parallel with the others, which log too while a sink is installed: the global-log test marks its
// own events with a field and reads back only those (the Kotlin tests run one at a time).

import Foundation
import Testing
@testable import ToolMapCore

private struct IllegalStateException: Error {}

private func temporaryDirectory(_ name: String) -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("\(name)-\(UUID().uuidString)")
}

private let utc = TimeZone(secondsFromGMT: 0) ?? TimeZone.current

private func event(_ name: String, _ fields: (String, (any Sendable)?)...) -> ToolMapCore.Event {
    ToolMapCore.Event(1_790_000_000_123, name, fields)
}

struct EventLogTests {
    @Test("a line holds the number, the time, the name and the fields, quoted when needed")
    func lineFormat() {
        let line = EventLine.format(
            12,
            event("relay.write", ("ch", "p"), ("ms", 85), ("why", "socket closed"), ("lat", 45.123456789), ("n", nil)),
            utc
        )
        #expect(line == "12 1790000000123 14:13:20.123 relay.write ch=p ms=85 why=\"socket closed\" lat=45.1234568")
        #expect(EventLine.seqOf(line) == 12)
        #expect(EventLine.nameOf(line) == "relay.write")
        #expect(EventLine.fieldsOf(line) == ["ch": "p", "ms": "85", "why": "socket closed", "lat": "45.1234568"])
    }

    @Test("quotes, backslashes, equal signs, line breaks and empty values survive a round trip")
    func quoting() {
        let text = "a \"b\" c" + "\\" + "d=e\nf"
        let line = EventLine.format(1, event("test.mark", ("text", text), ("empty", "")), utc)
        #expect(!line.contains("\n"))
        #expect(EventLine.fieldsOf(line) == ["text": text, "empty": ""])
        #expect(EventLine.seqOf("cut line") == nil)
    }

    @Test("details are kept only in verbose mode and a failing gauge does not stop the others")
    func detailsAndGauges() {
        let dir = temporaryDirectory("eventlog")
        // Other tests log in parallel while this sink is installed: memory keeps all their lines too.
        let s = EventStore(dir, memoryLines: 1_000_000, zone: utc)
        let tag = UUID().uuidString
        EventLog.install(s)
        defer {
            EventLog.uninstall(s)
            EventLog.verbose = false
            s.close()
            try? FileManager.default.removeItem(at: dir)
        }
        EventLog.verbose = false
        EventLog.detail("mesh.frame.in", ("tag", tag))
        EventLog.event("share.fix", ("tag", tag))
        EventLog.verbose = true
        EventLog.detail("mesh.ping.out", ("tag", tag))
        let mine = s.linesAfter(0, 1_000_000).filter { EventLine.fieldsOf($0)["tag"] == tag }
        #expect(mine.compactMap { EventLine.nameOf($0) } == ["share.fix", "mesh.ping.out"])
        let a = EventLog.gauge("a") { [("x", 1)] }
        let b = EventLog.gauge("b") { throw IllegalStateException() }
        let gauges = EventLog.readGauges()
        #expect(gauges["a"]?.first?.0 == "x")
        #expect(gauges["a"]?.first?.1 as? Int == 1)
        #expect(gauges["b"]?.first?.0 == "error")
        #expect(gauges["b"]?.first?.1 as? String == "IllegalStateException")
        a.close()
        b.close()
        #expect(!EventLog.readGauges().keys.contains { $0 == "a" || $0 == "b" })
    }

    @Test("numbers go on after a restart, under the same epoch, and old lines come from the files")
    func restart() throws {
        let dir = temporaryDirectory("eventlog")
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = EventStore(dir, memoryLines: 4, zone: utc)
        for i in 0 ..< 10 {
            first.write(event("e\(i)"))
        }
        #expect(first.lastSeq == 10)
        #expect(first.linesAfter(7, 10_000).compactMap { EventLine.nameOf($0) } == ["e7", "e8", "e9"])
        // Older than what memory keeps: read back from the file.
        first.flush()
        #expect(Array(first.linesAfter(2, 10_000).compactMap { EventLine.nameOf($0) }.prefix(2)) == ["e2", "e3"])
        let epoch = first.epoch
        first.close()

        let second = EventStore(dir, zone: utc)
        defer { second.close() }
        #expect(second.epoch == epoch)
        #expect(second.lastSeq == 10)
        second.write(event("again"))
        let after = second.linesAfter(10, 10_000)
        #expect(after.count == 1)
        #expect(after.first.flatMap { EventLine.seqOf($0) } == 11)
    }

    @Test("a full file rotates to the previous one and both are read")
    func rotation() throws {
        let dir = temporaryDirectory("eventlog")
        defer { try? FileManager.default.removeItem(at: dir) }
        let s = EventStore(dir, maxFileBytes: 2_000, memoryLines: 2, zone: utc)
        defer { s.close() }
        for i in 0 ..< 60 {
            s.write(event("rotating.event.\(i)", ("pad", String(repeating: "x", count: 40))))
        }
        s.flush()
        let fm = FileManager.default
        #expect(fm.fileExists(atPath: dir.appendingPathComponent(EventStore.previous).path))
        let attributes = try fm.attributesOfItem(atPath: dir.appendingPathComponent(EventStore.current).path)
        let size = (attributes[.size] as? NSNumber)?.intValue ?? Int.max
        #expect(size <= 2_000 + 200)
        // The oldest lines are gone; the files give the next ones in order, then memory the last ones.
        let numbers = s.linesAfter(0, 100_000).compactMap { EventLine.seqOf($0) }
        let firstNumber = try #require(numbers.first)
        let lastNumber = try #require(numbers.last)
        #expect(firstNumber > 1)
        #expect(numbers == Array(firstNumber ... lastNumber))
        #expect(s.linesAfter(58, 100_000).compactMap { EventLine.seqOf($0) } == [59, 60])
    }

    @Test("at most the asked number of bytes, but one line at least")
    func byteLimit() {
        let dir = temporaryDirectory("eventlog")
        defer { try? FileManager.default.removeItem(at: dir) }
        let s = EventStore(dir, zone: utc)
        defer { s.close() }
        for _ in 0 ..< 5 {
            s.write(event("big", ("pad", String(repeating: "y", count: 100))))
        }
        #expect(s.linesAfter(0, 10).count == 1)
        #expect(s.linesAfter(0, 300).count == 2)
        #expect(s.linesAfter(5, 1_000).isEmpty)
    }

    @Test("a new directory gets a new epoch")
    func newEpoch() {
        let dirA = temporaryDirectory("eventlog")
        let dirB = temporaryDirectory("eventlog2")
        defer {
            try? FileManager.default.removeItem(at: dirA)
            try? FileManager.default.removeItem(at: dirB)
        }
        let a = EventStore(dirA, zone: utc)
        let other = EventStore(dirB)
        #expect(a.epoch != other.epoch)
        a.close()
        other.close()
    }
}
