// Port of p2p/src/test/kotlin/fr/toolmap/p2p/RoomStateTest.kt (Android module :p2p) and of ShareStoreTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private final class RecordingSink: RoomStateSink {
    var events: [String] = []

    func heartbeat(_ member: String, _ serverMs: Int64, _ name: String, _ color: Int32) {
        events.append("beat \(member) \(serverMs) \(name) \(color)")
    }

    func frame(_ member: String, _ bytes: [UInt8], _ serverMs: Int64, _ isPosition: Bool) {
        events.append("\(isPosition ? "pos" : "ctrl") \(member) \(String(decoding: bytes, as: UTF8.self)) \(serverMs)")
    }

    func gone(_ member: String) {
        events.append("gone \(member)")
    }
}

struct RoomStateTests {
    private let sink = RecordingSink()
    private let state: RoomState

    init() {
        state = RoomState(sink)
    }

    private func b64(_ s: String) -> String { Data(s.utf8).base64EncodedString() }

    private func json(_ s: String) throws -> P2PJson { try P2PJson.parse(s) }

    private func put(_ path: String, _ text: String) throws {
        let value = try P2PJson.parse(text)
        state.put(path, value)
    }

    @Test("the first snapshot gives heartbeats before frames")
    func firstSnapshot() throws {
        let snapshot = """
        {"m1":{"h":{"t":100,"n":"Ann","k":7},"p":{"f":"\(b64("P"))","t":101},
           "c":{"-a":{"f":"\(b64("A"))","t":102},"-b":{"f":"\(b64("B"))","t":103}}},
           "m2":{"h":{"t":200,"n":"Bob","k":8}}}
        """
        try put("/", snapshot)
        #expect(sink.events == ["beat m1 100 Ann 7", "beat m2 200 Bob 8", "pos m1 P 101", "ctrl m1 A 102", "ctrl m1 B 103"])
    }

    @Test("a pushed child arrives once and a replayed snapshot does not repeat it")
    func pushedOnce() throws {
        let child = "{\"f\":\"\(b64("A"))\",\"t\":5}"
        try put("/m1/c/-a", child)
        try put("/m1/c/-a", child)
        #expect(sink.events == ["ctrl m1 A 5"])
    }

    @Test("a position overwritten in place is delivered each time it changes")
    func positionChanges() throws {
        try put("/m1/p", "{\"f\":\"\(b64("1"))\",\"t\":1}")
        try put("/m1/p", "{\"f\":\"\(b64("2"))\",\"t\":2}")
        try put("/m1/p", "{\"f\":\"\(b64("2"))\",\"t\":2}")
        #expect(sink.events == ["pos m1 1 1", "pos m1 2 2"])
    }

    @Test("a heartbeat update is seen and a patch sets several children")
    func heartbeatPatch() throws {
        try put("/m1/h", "{\"t\":10,\"n\":\"Ann\",\"k\":1}")
        let patch = try #require(try json("{\"t\":25}").objectValue)
        state.patch("/m1/h", patch)
        #expect(sink.events == ["beat m1 10 Ann 1", "beat m1 25 Ann 1"])
    }

    @Test("removing a member node or its heartbeat says it is gone")
    func gone() throws {
        try put("/m1/h", "{\"t\":10,\"n\":\"Ann\",\"k\":1}")
        try put("/m1", "null")
        try put("/m2/h", "{\"t\":10,\"n\":\"Bob\",\"k\":1}")
        try put("/m2/h", "null")
        #expect(sink.events == ["beat m1 10 Ann 1", "gone m1", "beat m2 10 Bob 1", "gone m2"])
    }

    @Test("a frame that is not Base64 is ignored")
    func notBase64() throws {
        try put("/m1/c/-a", "{\"f\":\"@@@\",\"t\":5}")
        #expect(sink.events.isEmpty)
    }

    @Test("server sent events are split into events")
    func serverSentEvents() {
        let text = ": comment\nevent: put\ndata: {\"path\":\"/\",\"data\":null}\n\n"
            + "event: keep-alive\ndata: null\n\nevent: patch\ndata: a\ndata: b\n\n"
        var out: [String] = []
        ServerSentEvents.read(text) { e, d in out.append(e + "|" + d) }
        #expect(out == ["put|{\"path\":\"/\",\"data\":null}", "keep-alive|null", "patch|a\nb"])
    }
}

struct ShareStoreTests {
    private let folder = FileManager.default.temporaryDirectory.appendingPathComponent("sharestore-\(UUID().uuidString)")

    private func file() -> URL { folder.appendingPathComponent("p2p/share.json") }

    private func store() -> ShareStore { ShareStore(file()) }

    @Test("a missing file gives the defaults and no group")
    func missing() {
        let state = store().read()
        #expect(state.group == nil)
        #expect(state.settings == ShareSettings())
    }

    @Test("settings and group survive a write and a read")
    func writeRead() throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        let group = GroupRecord.create()
        let written = ShareState(settings: ShareSettings(name: "Alice", colorIndex: 3, intervalSec: 60), group: group)
        try store().write(written)
        let read = store().read()
        #expect(read.settings.name == "Alice")
        #expect(read.settings.colorIndex == 3)
        #expect(read.settings.intervalSec == 60)
        let g = try #require(read.group)
        #expect(g.groupId == group.groupId)
        #expect(g.key == group.key)
        #expect(g.selfId == group.selfId)
    }

    @Test("a damaged file reads as the defaults")
    func damaged() throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        let f = file()
        try FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("{ not json".utf8).write(to: f)
        let state = ShareStore(f).read()
        #expect(state.group == nil)
        #expect(state.settings == ShareSettings())
    }

    @Test("a second write replaces the first and leaves no temporary file")
    func secondWrite() throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        let s = store()
        try s.write(ShareState(settings: ShareSettings(name: "A")))
        try s.write(ShareState(settings: ShareSettings(name: "B")))
        #expect(s.read().settings.name == "B")
        let names = try FileManager.default.contentsOfDirectory(atPath: folder.appendingPathComponent("p2p").path)
        #expect(names == ["share.json"])
    }

    @Test("the last message number is kept next to the state")
    func sequence() {
        defer { try? FileManager.default.removeItem(at: folder) }
        let s = store()
        #expect(s.readSeq() == nil)
        s.writeSeq(1_790_000_123)
        #expect(s.readSeq() == 1_790_000_123)
    }
}
