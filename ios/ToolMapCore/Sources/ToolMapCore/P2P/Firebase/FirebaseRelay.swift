// Port of p2p/src/main/kotlin/fr/toolmap/p2p/FirebaseRelay.kt (Android module :p2p): the room of a group on a
// Firebase Realtime Database, over REST and a server-sent event stream read with URLSession (docs/specs/firebase-relay.md).

import Foundation

/// A value shared between threads (Kotlin `@Volatile` fields and atomics).
private final class RelayAtomic<T>: @unchecked Sendable {
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

    func getAndSet(_ newValue: T) -> T {
        lock.lock()
        defer { lock.unlock() }
        let old = stored
        stored = newValue
        return old
    }

    func update<R>(_ body: (inout T) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&stored)
    }
}

extension RelayAtomic where T: Equatable {
    func compareAndSet(_ expected: T, _ newValue: T) -> Bool {
        update { current in
            if current == expected {
                current = newValue
                return true
            }
            return false
        }
    }
}

/// A broken invariant of the relay itself (Kotlin `error(...)` or a malformed answer): not a network failure.
private struct RelayStateError: Error, CustomStringConvertible {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var description: String { message }
}

/// The waiting thread and the task that reads the stream.
/// Reads the event stream on the delegate queue of its session, not on Swift's shared pool of threads, so that the
/// stream is served even while that pool is busy (the iPhone simulator running a thousand tests, 2026-10-29), like the
/// stream thread of Android. `opened` gets the status code at the first bytes (or at the end) and returns the error that
/// ends the stream, if any; each line, without its break, goes to `line`; `done` is signalled at the end, with `error`
/// set when the stream failed or was cancelled.
private final class StreamReader: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    let done = DispatchSemaphore(value: 0)
    var opened: (Int) -> (any Error)? = { _ in nil }
    var line: (String) throws -> Void = { _ in }
    private let lock = NSLock()
    private var failure: (any Error)?
    private var cancelled = false
    private var task: URLSessionTask?
    // Only touched on the session's delegate queue.
    private var answered = false
    private var pending: [UInt8] = []

    var error: (any Error)? {
        lock.lock()
        defer { lock.unlock() }
        return failure
    }

    func start(_ session: URLSession, _ request: URLRequest) {
        let task = session.dataTask(with: request)
        task.delegate = self
        lock.lock()
        self.task = task
        let stop = cancelled
        lock.unlock()
        task.resume()
        if stop { task.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let task = self.task
        lock.unlock()
        task?.cancel()
    }

    private func fail(_ error: any Error) {
        lock.lock()
        if failure == nil { failure = error }
        let task = self.task
        lock.unlock()
        task?.cancel()
    }

    /// The status of the answer, once: a refusal or a failure ends the stream.
    private func answer(_ task: URLSessionTask) -> Bool {
        if answered { return true }
        answered = true
        let code = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        if let error = opened(code) {
            fail(error)
            return false
        }
        return true
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard answer(dataTask) else { return }
        for byte in data {
            if byte == 0x0A {
                let text = ServerSentEvents.lineText(pending)
                pending.removeAll(keepingCapacity: true)
                do {
                    try line(text)
                } catch {
                    fail(error)
                    return
                }
            } else {
                pending.append(byte)
            }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        lock.lock()
        let failed = failure != nil
        let stopped = cancelled
        lock.unlock()
        if !failed {
            if let error {
                lock.lock()
                failure = stopped ? CancellationError() : error
                lock.unlock()
            } else if answer(task), !pending.isEmpty {
                let text = ServerSentEvents.lineText(pending)
                pending.removeAll()
                do {
                    try line(text)
                } catch {
                    lock.lock()
                    failure = error
                    lock.unlock()
                }
            }
        }
        lock.lock()
        self.task = nil
        lock.unlock()
        done.signal()
    }
}

/// The link to a member present in the room: everything sent on it is written once to the room, which every member
/// reads (`isBroadcastMedium`).
private final class RelayLink: MeshLink, @unchecked Sendable {
    let peer: Int64
    let closedFlag = RelayAtomic(false)
    private weak var relay: FirebaseRelay?

    init(_ peer: Int64, _ relay: FirebaseRelay) {
        self.peer = peer
        self.relay = relay
    }

    var isBroadcastMedium: Bool { true }

    func send(_ frame: [UInt8], _ onPositionChannel: Bool) -> Bool {
        guard let relay, !closedFlag.value, !relay.isClosed else { return false }
        relay.sendFrame(frame, onPositionChannel)
        return true
    }

    func close() {
        if closedFlag.getAndSet(true) { return }
        relay?.linkClosed(self)
    }
}

/// The server side of the sharing (ADR-033): a room of a Firebase Realtime Database used as a bus. Every member writes
/// its frames under its own node of `rooms/<room>/b` and streams the whole `b` node, so one write reaches the whole
/// group. A member is present while its heartbeat (server time, every `heartbeatS` s) is recent; each present member
/// is handed to the group logic as a `MeshLink`. Frames are the same sealed frames as in the direct mode: the server
/// only ever sees ciphertext.
///
/// Threads: a thread for the connection loop (it waits for the stream, read by a task), a serial queue for the writes
/// (kept in order), a serial queue for the timers (heartbeat, presence, clean-up). The listener may be called from the
/// stream's task and from the timers.
public final class FirebaseRelay: RelayTransport, RoomStateSink, @unchecked Sendable {
    public static let heartbeatS: Int64 = 15
    public static let presenceEveryS = 5
    public static let cleanupS = 60
    /// A member is linked while its last heartbeat is at most this old (two missed beats plus margin).
    public static let openWithinMs: Int64 = 40_000
    public static let closeAfterMs: Int64 = 75_000
    public static let controlMaxAgeMs: Int64 = 150_000
    public static let ownFrameTtlMs: Int64 = 120_000
    private static let stableMs: Int64 = 15_000
    /// Short on purpose: in the field the network comes and goes, and a member must come back quickly.
    private static let maxBackoffS: Int64 = 8
    /// Without a network, an attempt all the same after this long, in case its return was not told (P21.5 p).
    private static let offlineMaxWaitMs: Int64 = 300_000
    /// A clock measure is made again when its round trip is longer (P21.5 n).
    private static let maxClockRttMs: Int64 = 10_000
    private static let clockTries = 3
    private static let msPerS: Int64 = 1000
    /// A stream opened this recently is already on the new network: `reconnectNow` keeps it.
    private static let freshStreamMs: Int64 = 5_000
    /// Enough of the room name to tell two rooms apart in the event log.
    private static let roomPrefix = 6

    private final class Peer {
        let id: Int64
        var name = ""
        var color: Int32 = 0
        var lastBeat: Int64 = 0
        var droppedAt: Int64 = 0
        var banned = false
        var link: RelayLink?

        init(_ id: Int64) {
            self.id = id
        }
    }

    private let config: RelayConfig
    private let listener: any LinkListener
    private let clockMs: @Sendable () -> Int64
    private let room: String
    private let http: FirebaseHttp
    private let auth: FirebaseAuth
    private let db: FirebaseDb
    private let writer = DispatchQueue(label: "toolmap-relay-io")
    private let timerQueue = DispatchQueue(label: "toolmap-relay-timer")
    private let closedFlag = RelayAtomic(false)
    private let started = RelayAtomic(false)

    /// Guards `peers`, `lastCtrlFrame` and `lastPosFrame`.
    private let lock = NSLock()
    private var peers: [Int64: Peer] = [:]
    private var lastCtrlFrame: [UInt8]?
    private var lastPosFrame: [UInt8]?
    private let ownKeys = RelayAtomic<[(String, Int64)]>([])

    private let identityValue = RelayAtomic<TransportIdentity?>(nil)
    private let onStateValue = RelayAtomic<(@Sendable (ServerState) -> Void)?>(nil)
    private let stateValue = RelayAtomic(ServerState.connecting)
    private let streamTask = RelayAtomic<StreamReader?>(nil)
    private let streamUp = RelayAtomic(false)
    private let streamOpenedAt = RelayAtomic<Int64>(0)
    private let writesOk = RelayAtomic(true)
    private let refused = RelayAtomic(false)
    private let offsetMs = RelayAtomic<Int64?>(nil)
    private let pendingPosition = RelayAtomic<[UInt8]?>(nil)
    /// The position frame being written now: replacing it loses nothing (P21.5 b).
    private let writingPosition = RelayAtomic<[UInt8]?>(nil)
    /// The token the open stream was opened with (P21.5 g).
    private let streamToken = RelayAtomic<String?>(nil)
    /// The default network as the app last told it, and the one the open stream was opened on (P21.5 f).
    private let network = RelayAtomic<String?>(nil)
    private let streamNetwork = RelayAtomic<String?>(nil)
    /// When the last heartbeat was due, on the wall clock (P21.5 q).
    private let lastBeatAt = RelayAtomic<Int64>(0)
    /// The frames a previous process left on the server are still to be deleted (P21.5 r).
    private let purgeDue = RelayAtomic(true)
    /// The stream is being closed on purpose to take a renewed token: it reopens at once, the state stays.
    private let reopening = RelayAtomic(false)
    /// A position write or a heartbeat already waits in the writer queue: the next one only updates what it sends.
    private let positionQueued = RelayAtomic(false)
    private let heartbeatQueued = RelayAtomic(false)

    /// Wakes the connection loop out of its pause between two attempts (`reconnectNow`); guards the three flags below.
    private let wake = NSCondition()
    private var woken = false
    /// The system keeps the app off the network (P21.5 h).
    private var blocked = false
    /// No network at all (P21.5 p).
    private var offline = false

    /// Counters read by the event log's gauge (`EventLog.gauge`, P21).
    private let queuedWrites = RelayAtomic(0)
    private let writes = RelayAtomic<Int64>(0)
    private let writeFails = RelayAtomic<Int64>(0)
    private let bytesUp = RelayAtomic<Int64>(0)
    private let framesIn = RelayAtomic<Int64>(0)
    private let framesDropped = RelayAtomic<Int64>(0)
    private let positionsReplaced = RelayAtomic<Int64>(0)
    private let gauge = RelayAtomic<EventGauge?>(nil)
    private let timers = RelayAtomic<[any DispatchSourceTimer]>([])

    /// - Parameter configuration: the base of the URL sessions (tests add their fake server's protocol to it).
    public init(
        _ config: RelayConfig,
        _ groupId: [UInt8],
        _ listener: any LinkListener,
        _ authStore: any AuthStore,
        configuration: URLSessionConfiguration = URLSessionConfiguration.default,
        endpoints: FirebaseEndpoints = FirebaseEndpoints(),
        clockMs: @escaping @Sendable () -> Int64 = { EventLog.currentTimeMillis() }
    ) {
        self.config = config
        self.listener = listener
        self.clockMs = clockMs
        room = RelayPaths.room(groupId)
        let client = FirebaseHttp(configuration)
        let authenticator = FirebaseAuth(config, client, authStore, endpoints, clockMs)
        var base = endpoints.dbBase ?? config.base
        while base.hasSuffix("/") {
            base.removeLast()
        }
        http = client
        auth = authenticator
        db = FirebaseDb(base, authenticator, client)
    }

    public var identity: TransportIdentity? {
        get { identityValue.value }
        set { identityValue.value = newValue }
    }

    public var onState: (@Sendable (ServerState) -> Void)? {
        get { onStateValue.value }
        set { onStateValue.value = newValue }
    }

    public var state: ServerState { stateValue.value }

    public var pendingWrites: Int { queuedWrites.value }

    var isClosed: Bool { closedFlag.value }

    // MARK: - life

    public func start() {
        if closedFlag.value || !started.compareAndSet(false, true) { return }
        EventLog.event(
            "relay.start",
            ("room", String(room.prefix(FirebaseRelay.roomPrefix))),
            ("me", identity.map { MeshNode.hex($0.memberId) })
        )
        gauge.value = EventLog.gauge("relay") { [weak self] in self?.gaugeValues() ?? [] }
        let thread = Thread { [self] in connectLoop() }
        thread.name = "toolmap-relay-stream"
        thread.start()
        lastBeatAt.value = clockMs()
        let beat = schedule(1) { [weak self] in self?.beatIfDue() }
        let presence = schedule(FirebaseRelay.presenceEveryS) { [weak self] in self?.evaluate() }
        let clean = schedule(FirebaseRelay.cleanupS) { [weak self] in self?.cleanUp() }
        timers.value = [beat, presence, clean]
    }

    private func schedule(_ seconds: Int, _ body: @escaping @Sendable () -> Void) -> any DispatchSourceTimer {
        let timer = DispatchSource.makeTimerSource(queue: timerQueue)
        timer.schedule(deadline: .now() + .seconds(seconds), repeating: .seconds(seconds))
        timer.setEventHandler { body() }
        timer.resume()
        return timer
    }

    public func ban(_ peer: Int64) {
        EventLog.event("relay.ban", ("peer", MeshNode.hex(peer)))
        lock.lock()
        let p = peerOrNew(peer)
        p.banned = true
        let link = p.link
        p.link = nil
        lock.unlock()
        link?.closedFlag.value = true
    }

    public func close() {
        if !closedFlag.compareAndSet(false, true) { return }
        EventLog.event(
            "relay.close",
            ("writes", writes.value),
            ("fails", writeFails.value),
            ("queued", queuedWrites.value)
        )
        gauge.value?.close()
        for timer in timers.getAndSet([]) {
            timer.cancel()
        }
        lock.lock()
        for p in peers.values {
            p.link?.closedFlag.value = true
            // Released here: a link keeps its relay, which keeps its peers.
            p.link = nil
        }
        lock.unlock()
        wake.lock()
        wake.broadcast()
        wake.unlock()
        streamTask.value?.cancel()
        // The final delete touches the network: on the writer, never on the caller's (main) thread.
        let me = identity?.memberId
        let threadStarted = started.value
        writer.async { [self] in
            if let me { try? db.delete("rooms/\(room)/b/\(RelayPaths.member(me))") }
            http.closeWrites()
            if !threadStarted { http.releaseStreams() }
        }
    }

    // MARK: - MeshTransport: links appear by themselves, nothing to negotiate

    public func offer(_ peer: Int64, _ done: @escaping @Sendable (PairingPayload?) -> Void) { done(nil) }

    public func answer(_ offer: PairingPayload, _ done: @escaping @Sendable (PairingPayload?) -> Void) { done(nil) }

    public func accept(_ answer: PairingPayload) {}

    public func drop(_ peer: Int64) {
        let now = serverNow()
        lock.lock()
        if let p = peers[peer] {
            p.link?.closedFlag.value = true
            p.link = nil
            p.droppedAt = now
        }
        lock.unlock()
    }

    // MARK: - connection

    private func connectLoop() {
        defer { http.releaseStreams() }
        var backoffS: Int64 = 1
        var attempt = 0
        while !closedFlag.value {
            waitWhileOff()
            if closedFlag.value { return }
            let startedAt = clockMs()
            attempt += 1
            EventLog.event("relay.connect", ("attempt", attempt))
            var ended: (any Error)?
            var planned = false
            reopening.value = false
            do {
                try syncClock()
                refused.value = false
                writesOk.value = true
                if purgeDue.compareAndSet(true, false) {
                    enqueue(work: { [self] in try purgeOldFrames() })
                }
                try openStream()
            } catch let e as FirebaseRefused {
                if closedFlag.value { return }
                ended = e
                auth.invalidate(nil)
                refused.value = true
            } catch {
                if closedFlag.value { return }
                ended = error
                if FirebaseRelay.isNetworkError(error) {
                    // Closed on purpose to take the renewed token: not a failure.
                    planned = reopening.getAndSet(false)
                    if !planned { writesOk.value = false }
                } else {
                    writesOk.value = false
                }
            }
            EventLog.event(
                "relay.stream.end",
                ("upS", (clockMs() - startedAt) / FirebaseRelay.msPerS),
                ("error", ended.map { String(describing: type(of: $0)) }),
                ("why", ended.map { String(describing: $0) })
            )
            streamUp.value = false
            // A planned reopen takes a fraction of a second: the state does not flicker to "unreachable".
            if !planned { publishState() }
            if clockMs() - startedAt > FirebaseRelay.stableMs { backoffS = 1 }
            let kicked = pause(backoffS * FirebaseRelay.msPerS)
            if closedFlag.value { return }
            EventLog.event("relay.retry", ("waitedS", backoffS), ("kicked", kicked))
            backoffS = kicked ? 1 : Swift.min(backoffS * 2, FirebaseRelay.maxBackoffS)
        }
    }

    /// A failure of the network or of the server (Kotlin `IOException`), as opposed to a broken answer.
    private static func isNetworkError(_ error: any Error) -> Bool {
        error is FirebaseIOError || error is URLError || error is CancellationError
    }

    /// Waits before the next attempt; returns early (true) when `reconnectNow` is called meanwhile.
    private func pause(_ millis: Int64) -> Bool {
        wake.lock()
        defer { wake.unlock() }
        let deadline = Date(timeIntervalSinceNow: Double(millis) / 1000)
        while !woken, !closedFlag.value {
            if !wake.wait(until: deadline) { break }
        }
        let kicked = woken
        woken = false
        return kicked
    }

    /// Waits while the system keeps the app off the network (P21.5 h) or while there is no network at all (P21.5 p):
    /// every attempt would fail. Without a network the wait is capped, in case the call telling its return is missed.
    private func waitWhileOff() {
        wake.lock()
        defer { wake.unlock() }
        if blocked {
            EventLog.event("relay.blocked.wait")
            while blocked, !closedFlag.value {
                wake.wait()
            }
            woken = false
        }
        if offline, !closedFlag.value {
            EventLog.event("relay.offline.wait")
            let deadline = Date(timeIntervalSinceNow: Double(FirebaseRelay.offlineMaxWaitMs) / 1000)
            while offline, !closedFlag.value {
                if !wake.wait(until: deadline) { break }
            }
            woken = false
        }
    }

    private func wakeUp() {
        wake.lock()
        woken = true
        wake.broadcast()
        wake.unlock()
    }

    private var isBlocked: Bool {
        wake.lock()
        defer { wake.unlock() }
        return blocked
    }

    public func reconnectNow() {
        if closedFlag.value { return }
        wakeUp()
        // A stream opened on the network that went away may hang until its read timeout: cancel it now; the loop then
        // reconnects on the new network. A stream opened in the last seconds is kept, unless it was opened on another
        // network than the one now in use (P21.5 f).
        let task = streamTask.value
        let fresh = clockMs() - streamOpenedAt.value < FirebaseRelay.freshStreamMs
        let current = network.value
        let moved = current != nil && streamNetwork.value != current
        let cancel = task != nil && (!fresh || moved)
        EventLog.event("relay.reconnect.now", ("cancel", cancel), ("moved", moved))
        if cancel { task?.cancel() }
    }

    public func onNetwork(_ network: String) {
        wake.lock()
        let wasOffline = offline
        if wasOffline {
            offline = false
            woken = true
            wake.broadcast()
        }
        wake.unlock()
        if wasOffline { EventLog.event("relay.offline", ("offline", false)) }
        let previous = self.network.getAndSet(network)
        if previous == nil || previous == network || closedFlag.value { return }
        // Calls from now on open new connections on the new network; the stream and a write in flight end on the old.
        http.renew()
        EventLog.event("relay.network", ("pool", "new"))
    }

    public func onBlocked(_ blocked: Bool) {
        if closedFlag.value || blocked == isBlocked { return }
        EventLog.event("relay.blocked", ("blocked", blocked), ("stream", streamUp.value))
        wake.lock()
        self.blocked = blocked
        if !blocked {
            woken = true
            wake.broadcast()
        }
        wake.unlock()
        // Let back on the network with the stream still open: a position held meanwhile goes now.
        if !blocked, streamUp.value, pendingPosition.value != nil, let node = ownNode() { queuePosition(node) }
    }

    public func onOffline() {
        wake.lock()
        let already = offline
        wake.unlock()
        if closedFlag.value || already { return }
        EventLog.event("relay.offline", ("offline", true), ("stream", streamUp.value))
        wake.lock()
        offline = true
        wake.unlock()
    }

    public func onClockChanged() {
        if closedFlag.value || offsetMs.value == nil { return }
        EventLog.event("relay.clock.changed")
        enqueue(work: { [self] in try syncClock() })
    }

    private func ownNode() -> String? {
        guard let id = identity?.memberId else { return nil }
        return "rooms/\(room)/b/\(RelayPaths.member(id))"
    }

    /// Writes the heartbeat and reads its server time back: the difference with the phone clock is the offset. A
    /// measure whose round trip is negative or too long (the clock was set meanwhile) is made again (P21.5 n).
    private func syncClock() throws {
        guard let id = identity?.memberId else { throw RelayStateError("identity not set") }
        let me = RelayPaths.member(id)
        for attempt in 1 ... FirebaseRelay.clockTries {
            let t0 = clockMs()
            try db.put("rooms/\(room)/b/\(me)/h", heartbeatJson())
            let text = try db.get("rooms/\(room)/b/\(me)/h/t")
            guard let server = Int64(kotlinTrim(text)) else { throw FirebaseIOError("no server time") }
            let t1 = clockMs()
            let rtt = t1 - t0
            // The last try is kept when there is no offset yet: the presence needs one.
            if (rtt >= 0 && rtt <= FirebaseRelay.maxClockRttMs) || (attempt == FirebaseRelay.clockTries && offsetMs.value == nil) {
                let offset = server - (t0 + t1) / 2
                offsetMs.value = offset
                EventLog.event("relay.clock", ("offsetMs", offset), ("rttMs", rtt))
                return
            }
            EventLog.event("relay.clock.bad", ("rttMs", rtt), ("try", attempt))
        }
    }

    private func openStream() throws {
        streamOpenedAt.value = clockMs()
        streamNetwork.value = network.value
        // The stream that used the previous network's sessions has ended: nothing there is in use any more.
        http.releaseRetired()
        let token = try auth.token()
        let session = try http.streamSession()
        let request = try db.streamRequest("rooms/\(room)/b", token)
        let state = RoomState(self)
        var events = ServerSentEvents()
        let reader = StreamReader()
        reader.opened = { [self] code in streamOpened(code, token) }
        reader.line = { [self] text in
            if let e = events.line(text) { try onEvent(state, token, e.event, e.data) }
        }
        streamTask.value = reader
        reader.start(session, request)
        reader.done.wait()
        if let error = reader.error { throw error }
    }

    /// The answer of the stream: a refusal or a failure ends it, else the stream is up.
    private func streamOpened(_ code: Int, _ token: String) -> (any Error)? {
        let ok = code >= 200 && code < 300
        if !ok { EventLog.event("fb.http", ("method", "STREAM"), ("code", code)) }
        if code == 401 || code == 403 {
            auth.invalidate(token)
            return FirebaseRefused("permission denied (\(code))")
        }
        if !ok { return FirebaseIOError("HTTP \(code)") }
        EventLog.event("relay.stream.open", ("ms", clockMs() - streamOpenedAt.value))
        streamToken.value = token
        streamUp.value = true
        publishState()
        // A position held during the outage goes at once, not at the next heartbeat (P21.5 e).
        if pendingPosition.value != nil, let node = ownNode() { queuePosition(node) }
        return nil
    }

    /// A write renewed the token (2 min before it expires) but the stream still carries the old one, which the server
    /// revokes at its expiry, cutting the stream: reopen it now with the new token, without the pause (P21.5 g).
    private func reopenIfTokenRenewed() {
        guard let used = streamToken.value, let now = auth.current else { return }
        if !streamUp.value || used == now { return }
        streamToken.value = nil
        EventLog.event("relay.stream.renew", ("streamAgeS", (clockMs() - streamOpenedAt.value) / FirebaseRelay.msPerS))
        reopening.value = true
        wakeUp()
        streamTask.value?.cancel()
    }

    private func onEvent(_ state: RoomState, _ token: String, _ event: String, _ data: String) throws {
        EventLog.detail("relay.sse", ("event", event), ("bytes", data.utf16.count))
        switch event {
        case "put", "patch":
            let parsed = try P2PJson.parse(data)
            guard let body = parsed.objectValue else { throw RelayStateError("not a JSON object") }
            guard let pathValue = body["path"] else { return }
            let path: String
            switch pathValue {
            case let .literal(text), let .string(text): path = text
            case .null: path = "null"
            default: throw RelayStateError("bad path")
            }
            let payload = body["data"] ?? .null
            if event == "put" {
                state.put(path, payload)
            } else if let object = payload.objectValue {
                state.patch(path, object)
            }
        case "cancel":
            throw FirebaseRefused("stream cancelled by the server")
        case "auth_revoked":
            // Only this stream's token: a newer one, renewed by a write meanwhile, serves as it is. No pause.
            auth.invalidate(token)
            wakeUp()
            throw FirebaseIOError("token expired")
        default:
            break
        }
    }

    private func publishState() {
        let closed = closedFlag.value
        let isRefused = refused.value
        let up = streamUp.value
        let ok = writesOk.value
        let change: (ServerState, ServerState)? = stateValue.update { current in
            let next: ServerState
            if closed {
                next = current
            } else if isRefused {
                next = .refused
            } else if up, ok {
                next = .online
            } else if current == .connecting, !up, ok {
                next = .connecting
            } else {
                next = .unreachable
            }
            if next == current { return nil }
            let previous = current
            current = next
            return (previous, next)
        }
        guard let transition = change else { return }
        let from = transition.0
        let to = transition.1
        EventLog.event(
            "relay.state",
            ("from", from),
            ("to", to),
            ("stream", up),
            ("writesOk", ok),
            ("refused", isRefused)
        )
        onState?(to)
    }

    // MARK: - presence

    private func serverNow() -> Int64 { clockMs() + (offsetMs.value ?? 0) }

    /// Called with the lock held.
    private func peerOrNew(_ id: Int64) -> Peer {
        if let p = peers[id] { return p }
        let p = Peer(id)
        peers[id] = p
        return p
    }

    /// Opens the link of every member that is present and closes that of those who stopped answering.
    private func evaluate() {
        if offsetMs.value == nil || closedFlag.value { return }
        let now = serverNow()
        var opened: [(Int64, String, Int32, RelayLink)] = []
        var dropped: [Int64] = []
        lock.lock()
        for id in peers.keys.sorted() {
            guard let p = peers[id] else { continue }
            let age = now - p.lastBeat
            if p.link == nil {
                if !p.banned, age <= FirebaseRelay.openWithinMs, p.lastBeat > p.droppedAt {
                    let link = RelayLink(p.id, self)
                    p.link = link
                    let blank = p.name.unicodeScalars.allSatisfy(KotlinText.isWhitespace)
                    opened.append((p.id, blank ? "?" : p.name, p.color, link))
                    EventLog.event("relay.peer.open", ("peer", MeshNode.hex(p.id)), ("beatAgeMs", age))
                }
            } else if age > FirebaseRelay.closeAfterMs {
                EventLog.event("relay.peer.close", ("peer", MeshNode.hex(p.id)), ("beatAgeMs", age))
                p.link?.closedFlag.value = true
                p.link = nil
                p.droppedAt = now
                dropped.append(p.id)
            }
        }
        lock.unlock()
        for id in dropped {
            listener.onLinkClosed(id)
        }
        for (id, name, color, link) in opened {
            listener.onLinkOpen(id, name, color, link)
        }
    }

    /// `RoomStateSink`: a member's heartbeat.
    func heartbeat(_ member: String, _ serverMs: Int64, _ name: String, _ color: Int32) {
        guard let id = RelayPaths.memberOf(member) else { return }
        if id == identity?.memberId { return }
        EventLog.detail("relay.hb.in", ("peer", MeshNode.hex(id)), ("ageMs", serverNow() - serverMs))
        lock.lock()
        let p = peerOrNew(id)
        p.lastBeat = serverMs
        if !name.isEmpty { p.name = name }
        p.color = color
        lock.unlock()
        evaluate()
    }

    /// `RoomStateSink`: a member left the room.
    func gone(_ member: String) {
        guard let id = RelayPaths.memberOf(member) else { return }
        EventLog.event("relay.peer.gone", ("peer", MeshNode.hex(id)))
        let now = serverNow()
        lock.lock()
        var closing = false
        if let p = peers[id] {
            p.droppedAt = now
            p.link?.closedFlag.value = true
            closing = p.link != nil
            p.link = nil
        }
        lock.unlock()
        if closing { listener.onLinkClosed(id) }
    }

    /// `RoomStateSink`: a frame of another member.
    func frame(_ member: String, _ bytes: [UInt8], _ serverMs: Int64, _ isPosition: Bool) {
        guard let id = RelayPaths.memberOf(member) else { return }
        if id == identity?.memberId { return }
        lock.lock()
        let present = peers[id]?.link != nil
        lock.unlock()
        let ageMs = serverNow() - serverMs
        var drop: String?
        if !present {
            drop = "nolink"
        } else if !isPosition, ageMs > FirebaseRelay.controlMaxAgeMs {
            drop = "old"
        }
        if let drop {
            framesDropped.update { $0 += 1 }
            EventLog.event(
                "relay.frame.drop",
                ("peer", MeshNode.hex(id)),
                ("why", drop),
                ("pos", isPosition),
                ("type", Wire.typeOf(bytes)),
                ("fid", Wire.frameId(bytes)),
                ("ageMs", ageMs)
            )
            return
        }
        framesIn.update { $0 += 1 }
        EventLog.detail(
            "relay.frame.in",
            ("peer", MeshNode.hex(id)),
            ("pos", isPosition),
            ("type", Wire.typeOf(bytes)),
            ("fid", Wire.frameId(bytes)),
            ("ageMs", ageMs),
            ("bytes", bytes.count)
        )
        listener.onFrame(id, bytes)
    }

    /// A link closed by the group logic.
    fileprivate func linkClosed(_ link: RelayLink) {
        let now = serverNow()
        lock.lock()
        if let p = peers[link.peer], p.link === link {
            p.link = nil
            p.droppedAt = now
        }
        lock.unlock()
    }

    // MARK: - writing

    private func heartbeatJson() -> String {
        let id = identity
        return "{\"t\":{\".sv\":\"timestamp\"},\"n\":" + P2PJsonText.quote(id?.name ?? "") + ",\"k\":\(id?.color ?? 0)}"
    }

    private func frameJson(_ frame: [UInt8]) -> String {
        "{\"f\":\"" + P2PBase64.encode(frame) + "\",\"t\":{\".sv\":\"timestamp\"}}"
    }

    /// Sends the heartbeat when `heartbeatS` seconds of wall clock have passed (or the clock went back), checked every
    /// second: a timer's own delay does not count the time the processor sleeps (P21.5 q).
    private func beatIfDue() {
        let t = clockMs()
        let last = lastBeatAt.value
        if t >= last, t - last < FirebaseRelay.heartbeatS * FirebaseRelay.msPerS { return }
        lastBeatAt.value = t
        heartbeat()
    }

    private func heartbeat() {
        if !streamUp.value || isBlocked || closedFlag.value { return }
        guard let node = ownNode() else { return }
        // Offline, writes fail slowly (up to the call timeout): never let heartbeats pile up behind each other.
        if !heartbeatQueued.compareAndSet(false, true) { return }
        enqueue(onStart: { [self] in heartbeatQueued.value = false }, work: { [self] in
            let json = heartbeatJson()
            try timed("h", nil, json.utf16.count) { try db.put(node + "/h", json) }
        })
        if pendingPosition.value != nil { queuePosition(node) }
    }

    /// Writes the latest position not yet written; one write at most waits in the queue, always with the newest fix.
    private func queuePosition(_ node: String) {
        if !positionQueued.compareAndSet(false, true) { return }
        enqueue(onStart: { [self] in positionQueued.value = false }, work: { [self] in
            guard let pos = pendingPosition.value else { return }
            let json = frameJson(pos)
            writingPosition.value = pos
            do {
                try timed("p", pos, json.utf16.count) { try db.put(node + "/p", json) }
            } catch {
                writingPosition.value = nil
                throw error
            }
            writingPosition.value = nil
            // A newer fix that came during the write stays pending for the next write.
            pendingPosition.update { pending in
                if pending == pos { pending = nil }
            }
        })
    }

    private func cleanUp() {
        guard let node = ownNode() else { return }
        let cutoff = clockMs() - FirebaseRelay.ownFrameTtlMs
        let old: [String] = ownKeys.update { keys in
            var out: [String] = []
            while let first = keys.first, first.1 < cutoff {
                out.append(first.0)
                keys.removeFirst()
            }
            return out
        }
        if old.isEmpty { return }
        let body = FirebaseRelay.nullPatch(old)
        EventLog.detail("relay.c.clean", ("keys", old.count))
        enqueue(work: { [self] in
            try timed("c.clean", nil, body.utf16.count) { try db.patch(node + "/c", body) }
        })
    }

    /// `{"key":null,…}`: deletes these children in one patch.
    private static func nullPatch(_ keys: [String]) -> String {
        "{" + keys.map { P2PJsonText.quote($0) + ":null" }.joined(separator: ",") + "}"
    }

    /// Deletes the control frames of this member that a previous process left on the server (stopped, reinstalled,
    /// data cleared): `cleanUp` only knows the keys written by this one (P21.5 r). The time is read in the key.
    private func purgeOldFrames() throws {
        guard let node = ownNode() else { return }
        let cutoff = serverNow() - FirebaseRelay.ownFrameTtlMs
        let mine = Set(ownKeys.value.map { $0.0 })
        let keys = try timed("c.list", nil, 0) { try db.keys(node + "/c") }
        let old = keys.filter { !mine.contains($0) && (PushIds.timeMs($0) ?? Int64.max) < cutoff }
        EventLog.event("relay.c.purge", ("keys", keys.count), ("old", old.count))
        if old.isEmpty { return }
        let body = FirebaseRelay.nullPatch(old)
        try timed("c.purge", nil, body.utf16.count) { try db.patch(node + "/c", body) }
    }

    /// Runs one server call and logs it (P21): what was written (`what`: `h`, `p`, `c`), the frame, how long it took;
    /// a failure is logged with its cause and passed on.
    @discardableResult
    private func timed<T>(_ what: String, _ frame: [UInt8]?, _ bytes: Int, _ call: () throws -> T) throws -> T {
        let t0 = clockMs()
        let result: T
        do {
            result = try call()
        } catch {
            throw failed(what, frame, t0, error)
        }
        writes.update { $0 += 1 }
        bytesUp.update { $0 += Int64(bytes) }
        let type: MsgType? = frame.flatMap { Wire.typeOf($0) }
        let fid: String? = frame.map { Wire.frameId($0) }
        let fields: [(String, (any Sendable)?)] = [
            ("what", what),
            ("ms", clockMs() - t0),
            ("type", type),
            ("fid", fid),
            ("bytes", bytes),
        ]
        // The position writes are the subject of the tests: always kept; the others only in the test mode.
        if what == "p" || EventLog.verbose { EventLog.log("relay.write", fields) }
        return result
    }

    private func failed(_ what: String, _ frame: [UInt8]?, _ t0: Int64, _ e: any Error) -> any Error {
        writeFails.update { $0 += 1 }
        let type: MsgType? = frame.flatMap { Wire.typeOf($0) }
        let fid: String? = frame.map { Wire.frameId($0) }
        EventLog.event(
            "relay.write.fail",
            ("what", what),
            ("ms", clockMs() - t0),
            ("type", type),
            ("fid", fid),
            ("error", String(describing: Swift.type(of: e))),
            ("why", String(describing: e))
        )
        return e
    }

    private func gaugeValues() -> GaugeValues {
        let now = serverNow()
        lock.lock()
        let peerList = peers.keys.sorted().compactMap { peers[$0] }.map { p in
            MeshNode.hex(p.id) + ":" + String((now - p.lastBeat) / FirebaseRelay.msPerS) + "s:"
                + (p.link != nil ? "on" : "off")
        }.joined(separator: ",")
        lock.unlock()
        let up = streamUp.value
        let streamAge: Int64? = up ? (clockMs() - streamOpenedAt.value) / FirebaseRelay.msPerS : nil
        return [
            ("state", state),
            ("stream", up),
            ("streamAgeS", streamAge),
            ("writesOk", writesOk.value),
            ("refused", refused.value),
            ("offsetMs", offsetMs.value),
            ("queued", queuedWrites.value),
            ("posPending", pendingPosition.value != nil),
            ("blocked", isBlocked),
            ("writes", writes.value),
            ("fails", writeFails.value),
            ("bytesUp", bytesUp.value),
            ("framesIn", framesIn.value),
            ("dropped", framesDropped.value),
            ("pReplaced", positionsReplaced.value),
            ("ownKeys", ownKeys.value.count),
            ("peers", peerList),
        ]
    }

    /// Runs a write on the writer queue; a network failure or a refusal is reflected in the connection state.
    private func enqueue(onStart: (@Sendable () -> Void)? = nil, work: @escaping @Sendable () throws -> Void) {
        if closedFlag.value { return }
        queuedWrites.update { $0 += 1 }
        writer.async { [self] in
            queuedWrites.update { $0 -= 1 }
            do {
                // Cleared first: a write that comes during this one queues again rather than being lost.
                onStart?()
                try work()
                if !writesOk.value {
                    writesOk.value = true
                    publishState()
                }
                reopenIfTokenRenewed()
            } catch is FirebaseRefused {
                refused.value = true
                publishState()
            } catch {
                writesOk.value = false
                publishState()
            }
        }
    }

    /// What a `RelayLink` sends: positions to `p` (one slot, the newest wins), the rest to `c`.
    fileprivate func sendFrame(_ frame: [UInt8], _ onPositionChannel: Bool) {
        guard let node = ownNode() else { return }
        // `p` holds a single frame, read by a member who arrives: only positions go there. A PING (liveness, every
        // 15 s) goes with the control frames, else it would hide the position until the next one (P21.5 b).
        let toPositionSlot = onPositionChannel && Wire.typeOf(frame) == .pos
        lock.lock()
        // The group logic sends one broadcast to every link with the same frame: one write is enough.
        if toPositionSlot {
            if frame == lastPosFrame {
                lock.unlock()
                return
            }
            lastPosFrame = frame
        } else {
            if frame == lastCtrlFrame {
                lock.unlock()
                return
            }
            lastCtrlFrame = frame
        }
        lock.unlock()
        if toPositionSlot {
            let replaced = pendingPosition.getAndSet(frame)
            if let replaced, replaced != writingPosition.value {
                // Never written and never will be (a write already started goes through): only the newest goes.
                positionsReplaced.update { $0 += 1 }
                EventLog.event(
                    "relay.p.replaced",
                    ("old", Wire.typeOf(replaced)),
                    ("oldFid", Wire.frameId(replaced)),
                    ("new", Wire.typeOf(frame)),
                    ("newFid", Wire.frameId(frame)),
                    ("queued", queuedWrites.value)
                )
            }
            queuePosition(node)
        } else {
            enqueue(work: { [self] in
                let json = frameJson(frame)
                let key = try timed("c", frame, json.utf16.count) { try db.push(node + "/c", json) }
                if !key.isEmpty {
                    let at = clockMs()
                    ownKeys.update { $0.append((key, at)) }
                }
            })
        }
    }
}

/// The anonymous identity kept in a small file, so that the device keeps the same Firebase user between launches.
public final class FileAuthStore: AuthStore, @unchecked Sendable {
    private let file: URL

    public init(_ file: URL) {
        self.file = file
    }

    public func read() -> String? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        let text = kotlinTrim(String(decoding: data, as: UTF8.self))
        return text.isEmpty ? nil : text
    }

    public func write(_ refreshToken: String?) {
        let fm = FileManager.default
        guard let refreshToken else {
            try? fm.removeItem(at: file)
            return
        }
        try? fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = URL(fileURLWithPath: file.path + ".tmp")
        do {
            try Data(refreshToken.utf8).write(to: temp)
            if fm.fileExists(atPath: file.path) {
                _ = try fm.replaceItemAt(file, withItemAt: temp)
            } else {
                try fm.moveItem(at: temp, to: file)
            }
        } catch {
            try? Data(refreshToken.utf8).write(to: file)
        }
    }
}

/// Makes the relay of each server group.
public final class FirebaseRelayFactory: RelayTransportFactory, @unchecked Sendable {
    private let authStore: any AuthStore
    private let configuration: URLSessionConfiguration
    private let endpoints: FirebaseEndpoints

    public init(
        _ authStore: any AuthStore,
        configuration: URLSessionConfiguration = URLSessionConfiguration.default,
        endpoints: FirebaseEndpoints = FirebaseEndpoints()
    ) {
        self.authStore = authStore
        self.configuration = configuration
        self.endpoints = endpoints
    }

    public func create(_ config: RelayConfig, _ groupId: [UInt8], _ listener: any LinkListener) -> any RelayTransport {
        FirebaseRelay(config, groupId, listener, authStore, configuration: configuration, endpoints: endpoints)
    }
}
