// Port of p2p/src/test/kotlin/fr/toolmap/p2p/FirebaseRelayFixesTest.kt and FirebaseRelayReconnectTest.kt (Android
// module :p2p, against OkHttp's MockWebServer): the relay against a fake Firebase served by a URLProtocol.
// Not ported: FirebaseRelayEmulatorTest (it needs the Firebase emulators), and the check that the first call after a
// network change opens a new connection (URLProtocol has no connections to count).

import Foundation
import Testing
@testable import ToolMapCore

/// A value shared with the threads of the relay and of URLSession.
private final class RelayTestBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: T

    init(_ value: T) {
        stored = value
    }

    var value: T {
        get {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }
        set {
            lock.lock()
            stored = newValue
            lock.unlock()
        }
    }

    func update<R>(_ body: (inout T) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&stored)
    }
}

/// What the fake server answers to a stream.
private enum StreamAnswer {
    /// Another member's heartbeat, then a stream that stays open.
    case heartbeat
    /// A refusal of the server (503).
    case unavailable
    /// A stream that stays open and silent (the network went away under it).
    case silent
}

private struct FakeAnswer {
    let status: Int
    let body: Data
    let keepOpen: Bool
}

/// A fake Firebase: auth, database and stream, with the knobs of the Kotlin tests. Requests reach it by its host.
private final class FakeFirebase: @unchecked Sendable {
    struct Call {
        let method: String
        let path: String
        let auth: String?
        let stream: Bool
        let body: String
    }

    static let serverMs: Int64 = 1_700_000_000_000
    private static let servers = RelayTestBox<[String: FakeFirebase]>([:])

    let host = "fb-\(UUID().uuidString.lowercased()).test"
    private let callsValue = RelayTestBox<[Call]>([])
    let streamAnswer: RelayTestBox<StreamAnswer>
    /// Lifetime of the first token: short, so that a write renews it (P21.5 g).
    let firstTokenLifetimeS = RelayTestBox(3600)
    let failPositions = RelayTestBox(false)
    /// What a `shallow` read of the own control frames answers (P21.5 r).
    let ownFrameKeys = RelayTestBox("null")
    /// Added to the phone clock, to move it during a test (P21.5 n, q).
    let clockShiftMs = RelayTestBox<Int64>(0)
    /// Moves the phone clock by this much when the server time is first read (P21.5 n).
    let shiftAtFirstTimeRead = RelayTestBox<Int64>(0)

    init(_ stream: StreamAnswer = .heartbeat) {
        streamAnswer = RelayTestBox(stream)
        FakeFirebase.servers.update { $0[host] = self }
    }

    static func server(_ host: String) -> FakeFirebase? { servers.value[host] }

    var calls: [Call] { callsValue.value }

    func count(_ predicate: (Call) -> Bool) -> Int { calls.filter(predicate).count }

    var configuration: URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeFirebaseProtocol.self]
        return c
    }

    var endpoints: FirebaseEndpoints {
        FirebaseEndpoints(signUp: "https://\(host)/signUp", refresh: "https://\(host)/refresh", dbBase: "https://\(host)/")
    }

    func answer(_ request: URLRequest, _ body: Data) -> FakeAnswer {
        let url = request.url
        let path = url?.path ?? ""
        let items = url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems } ?? []
        let query = Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { _, last in last })
        let method = request.httpMethod ?? "GET"
        let stream = request.value(forHTTPHeaderField: "Accept") == "text/event-stream"
        let text = String(decoding: body, as: UTF8.self)
        let index = callsValue.update { calls -> Int in
            calls.append(Call(method: method, path: path, auth: query["auth"], stream: stream, body: text))
            return calls.count
        }
        if path.hasPrefix("/signUp") {
            let lifetime = firstTokenLifetimeS.value
            return json("{\"idToken\":\"t1\",\"refreshToken\":\"r1\",\"expiresIn\":\"\(lifetime)\",\"localId\":\"u\"}")
        }
        if path.hasPrefix("/refresh") {
            return json("{\"id_token\":\"t2\",\"refresh_token\":\"r2\",\"expires_in\":\"3600\",\"user_id\":\"u\"}")
        }
        if stream {
            switch streamAnswer.value {
            case .heartbeat:
                let event = "event: put\ndata: {\"path\":\"/\",\"data\":{\"m2b\":{\"h\":"
                    + "{\"t\":\(FakeFirebase.serverMs),\"n\":\"B\",\"k\":0}}}}\n\n"
                return FakeAnswer(status: 200, body: Data(event.utf8), keepOpen: true)
            case .unavailable:
                return FakeAnswer(status: 503, body: Data(), keepOpen: false)
            case .silent:
                return FakeAnswer(status: 200, body: Data(), keepOpen: true)
            }
        }
        if method == "GET", query["shallow"] == "true" {
            return json(ownFrameKeys.value)
        }
        if method == "GET" {
            let shift = shiftAtFirstTimeRead.value
            clockShiftMs.update { $0 += shift }
            shiftAtFirstTimeRead.value = 0
            return json("\(FakeFirebase.serverMs)")
        }
        if method == "PUT", path.hasSuffix("/p.json"), failPositions.value {
            return FakeAnswer(status: 503, body: Data(), keepOpen: false)
        }
        if method == "POST" {
            return json("{\"name\":\"-k\(index)\"}")
        }
        return json("{}")
    }

    private func json(_ text: String) -> FakeAnswer {
        FakeAnswer(status: 200, body: Data(text.utf8), keepOpen: false)
    }
}

/// Serves the requests of the relay's sessions from the `FakeFirebase` of their host.
private final class FakeFirebaseProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        guard let host = request.url?.host else { return false }
        return FakeFirebase.server(host) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host, let server = FakeFirebase.server(host) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        let answer = server.answer(request, FakeFirebaseProtocol.body(of: request))
        let headers = [
            "Content-Type": answer.keepOpen ? "text/event-stream" : "application/json; charset=utf-8",
            "X-Content-Type-Options": "nosniff",
        ]
        guard let response = HTTPURLResponse(url: url, statusCode: answer.status, httpVersion: "HTTP/1.1", headerFields: headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !answer.body.isEmpty {
            client?.urlProtocol(self, didLoad: answer.body)
        }
        if !answer.keepOpen {
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}

    /// The body of a request: URLSession hands it to a protocol as a stream.
    private static func body(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let capacity = 4096
        var buffer = [UInt8](repeating: 0, count: capacity)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: capacity)
            if n <= 0 { break }
            data.append(contentsOf: buffer[0 ..< n])
        }
        return data
    }
}

private final class LinkCatcher: LinkListener, @unchecked Sendable {
    private let linkValue = RelayTestBox<(any MeshLink)?>(nil)

    var link: (any MeshLink)? { linkValue.value }

    func onLinkOpen(_ peer: Int64, _ name: String, _ color: Int32, _ link: any MeshLink) {
        linkValue.value = link
    }

    func onLinkClosed(_ peer: Int64) {}

    func onFrame(_ peer: Int64, _ bytes: [UInt8]) {}
}

private final class MemoryAuth: AuthStore, @unchecked Sendable {
    func read() -> String? { nil }

    func write(_ refreshToken: String?) {}
}

/// Waits until `condition` holds, at most `timeoutMs`, without holding a thread.
/// Waits until `condition` holds, at most `timeoutMs`. A wait for something that must happen in the end (a link, a
/// stream, a write) is long: the iPhone simulator runs the thousand tests at once on few cores, and a short wait
/// failed there while nothing was wrong (2026-10-29). Waits that check promptness ("at once") keep their short bound.
private func relayWaitFor(_ timeoutMs: Int, _ condition: () -> Bool) async throws -> Bool {
    let until = Date().addingTimeInterval(Double(timeoutMs) / 1000)
    while Date() < until {
        if condition() { return true }
        try await Task.sleep(nanoseconds: 20_000_000)
    }
    return condition()
}

private func pauseMs(_ ms: UInt64) async throws {
    try await Task.sleep(nanoseconds: ms * 1_000_000)
}

/// The relay defects found by the live tests (P21.5 b, e, f, g, h, n, p, q, r), against a fake Firebase.
@Suite(.serialized)
struct FirebaseRelayTests {
    private static let ownId: Int64 = 42
    private static let own = "m2a"
    private static let frameBytes = 40

    private func relay(_ server: FakeFirebase, _ catcher: LinkCatcher) -> FirebaseRelay {
        let groupId = [UInt8](repeating: 1, count: 8)
        let shift = server.clockShiftMs
        let r = FirebaseRelay(
            RelayConfig("https://example.invalid", "key"),
            groupId,
            catcher,
            MemoryAuth(),
            configuration: server.configuration,
            endpoints: server.endpoints,
            clockMs: { EventLog.currentTimeMillis() + shift.value }
        )
        r.identity = TransportIdentity(groupId, FirebaseRelayTests.ownId, "A", 0)
        return r
    }

    private func streams(_ s: FakeFirebase) -> Int { s.count { $0.stream } }

    private func positionWrites(_ s: FakeFirebase) -> Int {
        s.count { $0.method == "PUT" && $0.path.hasSuffix("/\(FirebaseRelayTests.own)/p.json") }
    }

    private func controlWrites(_ s: FakeFirebase) -> Int {
        s.count { $0.method == "POST" && $0.path.hasSuffix("/\(FirebaseRelayTests.own)/c.json") }
    }

    private func heartbeats(_ s: FakeFirebase) -> Int {
        s.count { $0.method == "PUT" && $0.path.hasSuffix("/\(FirebaseRelayTests.own)/h.json") }
    }

    /// A frame of `type`: the relay only reads its first byte (the type) and never decrypts it.
    private func frame(_ type: MsgType, _ n: Int) -> [UInt8] {
        (0 ..< FirebaseRelayTests.frameBytes).map { $0 == 0 ? UInt8(type.code) : UInt8(truncatingIfNeeded: n) }
    }

    private func linked(_ catcher: LinkCatcher) async throws -> any MeshLink {
        #expect(try await relayWaitFor(20_000) { catcher.link != nil }, "the other member's link opens")
        return try #require(catcher.link)
    }

    @Test("a PING goes with the control frames and leaves the latest position in p")
    func pingToControl() async throws {
        let server = FakeFirebase()
        let catcher = LinkCatcher()
        let r = relay(server, catcher)
        r.start()
        defer { r.close() }
        let l = try await linked(catcher)
        _ = l.send(frame(.pos, 1), true)
        #expect(try await relayWaitFor(10_000) { positionWrites(server) == 1 }, "the position goes to p")
        _ = l.send(frame(.ping, 2), true)
        #expect(try await relayWaitFor(10_000) { controlWrites(server) == 1 }, "the PING goes to c")
        try await pauseMs(300)
        #expect(positionWrites(server) == 1, "p still holds the position")
    }

    @Test("a position held during an outage is written as soon as the stream reopens")
    func heldPosition() async throws {
        let server = FakeFirebase()
        let catcher = LinkCatcher()
        let r = relay(server, catcher)
        r.onNetwork("net-1")
        r.start()
        defer { r.close() }
        let l = try await linked(catcher)
        server.failPositions.value = true
        _ = l.send(frame(.pos, 1), true)
        #expect(try await relayWaitFor(10_000) { positionWrites(server) == 1 }, "the write fails")
        server.failPositions.value = false
        // A new network: the stream reopens at once; the next heartbeat would only come 15 s after the start.
        r.onNetwork("net-2")
        r.reconnectNow()
        #expect(try await relayWaitFor(6_000) { streams(server) == 2 }, "the stream reopens")
        #expect(try await relayWaitFor(3_000) { positionWrites(server) == 2 }, "the held position is written at once")
    }

    @Test("a stream opened on the previous network is dropped at once, even a recent one")
    func previousNetwork() async throws {
        let server = FakeFirebase()
        let catcher = LinkCatcher()
        let r = relay(server, catcher)
        r.onNetwork("net-1")
        r.start()
        defer { r.close() }
        #expect(try await relayWaitFor(20_000) { streams(server) == 1 }, "stream opened")
        // Same network, stream under 5 s old: kept (the call that comes with the registration).
        r.onNetwork("net-1")
        r.reconnectNow()
        try await pauseMs(1_000)
        #expect(streams(server) == 1, "a recent stream on the same network is kept")
        let l = try await linked(catcher)
        _ = l.send(frame(.pos, 1), true)
        #expect(try await relayWaitFor(10_000) { positionWrites(server) == 1 }, "position written")
        try await pauseMs(200)
        // A second network arrives: the stream still uses the first one.
        r.onNetwork("net-2")
        r.reconnectNow()
        #expect(try await relayWaitFor(5_000) { streams(server) == 2 }, "the stream moves to the new network")
    }

    @Test("a renewed token reopens the stream at once, with no gap in the state")
    func renewedToken() async throws {
        let server = FakeFirebase()
        // The token expires 2 min and 1 s after the sign-in: from 1 s on, a write renews it first.
        server.firstTokenLifetimeS.value = 121
        let catcher = LinkCatcher()
        let states = RelayTestBox<[ServerState]>([])
        let r = relay(server, catcher)
        r.onState = { state in states.update { $0.append(state) } }
        r.start()
        defer { r.close() }
        let l = try await linked(catcher)
        #expect(try await relayWaitFor(20_000) { states.value.contains(.online) }, "online")
        try await pauseMs(1_200)
        _ = l.send(frame(.pos, 1), true)
        #expect(try await relayWaitFor(6_000) { streams(server) == 2 }, "the stream reopens after the renewal")
        let last = server.calls.last { $0.stream }
        #expect(last?.auth == "t2", "with the new token")
        #expect(server.count { $0.path.hasPrefix("/refresh") } == 1, "renewed once")
        try await pauseMs(300)
        #expect(!states.value.contains(.unreachable), "the state never left online: \(states.value)")
    }

    @Test("no attempt while Android blocks the network, one at once when it lets it back")
    func blocked() async throws {
        let server = FakeFirebase()
        let r = relay(server, LinkCatcher())
        r.onBlocked(true)
        r.start()
        defer { r.close() }
        try await pauseMs(1_500)
        #expect(server.calls.isEmpty, "nothing tried while blocked")
        r.onBlocked(false)
        #expect(try await relayWaitFor(3_000) { streams(server) == 1 }, "connected right after")
    }

    @Test("no attempt while there is no network, one at once when a network comes")
    func offline() async throws {
        let server = FakeFirebase()
        let r = relay(server, LinkCatcher())
        r.onOffline()
        r.start()
        defer { r.close() }
        try await pauseMs(1_500)
        #expect(server.calls.isEmpty, "nothing tried without a network")
        r.onNetwork("net-1")
        #expect(try await relayWaitFor(3_000) { streams(server) == 1 }, "connected right after")
    }

    @Test("the heartbeat follows the wall clock, not the timer's own delay")
    func wallClockHeartbeat() async throws {
        let server = FakeFirebase()
        let catcher = LinkCatcher()
        let r = relay(server, catcher)
        r.start()
        defer { r.close() }
        _ = try await linked(catcher)
        let before = heartbeats(server)
        try await pauseMs(1_500)
        #expect(heartbeats(server) == before, "not before 15 s")
        // 16 s pass on the wall clock while the processor slept: the beat goes within a second or so.
        server.clockShiftMs.update { $0 += 16_000 }
        #expect(try await relayWaitFor(5_000) { heartbeats(server) == before + 1 }, "the beat is sent")
    }

    @Test("a clock measure spoilt by a clock change is made again")
    func spoiltClockMeasure() async throws {
        let server = FakeFirebase()
        // The clock goes back by 815 s while the first measure waits for the server time.
        server.shiftAtFirstTimeRead.value = -815_000
        let r = relay(server, LinkCatcher())
        r.start()
        defer { r.close() }
        #expect(try await relayWaitFor(20_000) { streams(server) == 1 }, "stream opened")
        let beforeStream = server.calls.prefix { !$0.stream }
        #expect(beforeStream.filter { $0.path.hasSuffix("/h.json") }.count == 2)
    }

    @Test("frames left on the server by a previous process are deleted at the start")
    func purge() async throws {
        let old = PushIds.timePrefix(FakeFirebase.serverMs - 600_000) + "oldoldoldold"
        let recent = PushIds.timePrefix(FakeFirebase.serverMs - 10_000) + "newnewnewnew"
        #expect(PushIds.timeMs(old) == FakeFirebase.serverMs - 600_000)
        let server = FakeFirebase()
        server.ownFrameKeys.value = "{\"\(old)\":true,\"\(recent)\":true}"
        let r = relay(server, LinkCatcher())
        r.start()
        defer { r.close() }
        let purges = { server.count { $0.method == "PATCH" && $0.path.hasSuffix("/\(FirebaseRelayTests.own)/c.json") } }
        #expect(try await relayWaitFor(6_000) { purges() == 1 }, "one grouped delete")
        let body = server.calls.first { $0.method == "PATCH" }?.body ?? ""
        #expect(body.contains(old), "\(body)")
        #expect(!body.contains(recent), "a frame under 2 min old stays: \(body)")
    }

    // FirebaseRelayReconnectTest: the relay comes back quickly after a network change (field test of 2026-10-14).

    @Test("reconnectNow skips the back-off pause")
    func reconnectNowSkipsTheBackoffPause() async throws {
        let server = FakeFirebase(.unavailable)
        let r = relay(server, LinkCatcher())
        r.start()
        defer { r.close() }
        // Attempts at about 0, 1 and 3 s; the next one would only come 4 s later.
        #expect(try await relayWaitFor(8_000) { streams(server) >= 3 }, "three failed attempts")
        try await pauseMs(300)
        let before = streams(server)
        r.reconnectNow()
        #expect(try await relayWaitFor(2_500) { streams(server) > before }, "an attempt right after reconnectNow")
    }

    @Test("reconnectNow drops a stream left on the old network")
    func reconnectNowDropsAStreamLeftOnTheOldNetwork() async throws {
        // A stream that stays silent (the network went away under it) until its 45 s read timeout.
        let server = FakeFirebase(.silent)
        let r = relay(server, LinkCatcher())
        r.start()
        defer { r.close() }
        #expect(try await relayWaitFor(20_000) { streams(server) == 1 }, "stream opened")
        // A stream opened under 5 s ago is already on the new network and kept.
        try await pauseMs(5_200)
        r.reconnectNow()
        #expect(try await relayWaitFor(2_500) { streams(server) >= 2 }, "the silent stream is replaced at once")
    }
}
