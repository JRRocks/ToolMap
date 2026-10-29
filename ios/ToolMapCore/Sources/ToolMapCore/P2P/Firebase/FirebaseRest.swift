// Port of p2p/src/main/kotlin/fr/toolmap/p2p/FirebaseRest.kt (Android module :p2p): anonymous sign-in, token renewal
// and the few calls of the Realtime Database, over REST with URLSession instead of OkHttp.

import Foundation

/// The keys made by a Firebase push: their first 8 characters are the server time in milliseconds, in base 64.
enum PushIds {
    private static let chars = Array("-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz".utf16)
    private static let timeChars = 8
    private static let bits: Int64 = 6

    /// The time a push key was made, or `nil` when `key` is not one.
    static func timeMs(_ key: String) -> Int64? {
        let units = Array(key.utf16)
        if units.count < timeChars { return nil }
        var t: Int64 = 0
        for i in 0 ..< timeChars {
            guard let v = chars.firstIndex(of: units[i]) else { return nil }
            t = (t << bits) | Int64(v)
        }
        return t
    }

    /// The time part of a key made at `timeMs` (tests).
    static func timePrefix(_ timeMs: Int64) -> String {
        var out = [UInt16](repeating: 0, count: timeChars)
        var t = timeMs
        for i in stride(from: timeChars - 1, through: 0, by: -1) {
            out[i] = chars[Int(t & ((1 << bits) - 1))]
            t >>= bits
        }
        return String(decoding: out, as: UTF16.self)
    }
}

/// Where the Firebase services are; the defaults are Google's, tests point them at a fake server.
public struct FirebaseEndpoints: Equatable, Sendable {
    public let signUp: String
    public let refresh: String
    /// Overrides the database address of the `RelayConfig` (tests only).
    public let dbBase: String?

    public init(
        signUp: String = "https://identitytoolkit.googleapis.com/v1/accounts:signUp",
        refresh: String = "https://securetoken.googleapis.com/v1/token",
        dbBase: String? = nil
    ) {
        self.signUp = signUp
        self.refresh = refresh
        self.dbBase = dbBase
    }
}

/// The server said no (as opposed to not answering): the key is wrong, anonymous sign-in is off, the rules refuse.
/// A kind of `FirebaseIOError` on Android (`FirebaseRefused : IOException`): catch it first.
public struct FirebaseRefused: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// The network or the server failed (Kotlin `IOException`).
public struct FirebaseIOError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// What survives a restart of the app so that a device keeps its anonymous Firebase identity.
public protocol AuthStore: AnyObject {
    func read() -> String?

    func write(_ refreshToken: String?)
}

/// An answer of the server: its status and body.
struct FirebaseAnswer: Sendable {
    let code: Int
    let body: Data

    var isSuccessful: Bool { code >= 200 && code < 300 }

    var text: String { String(decoding: body, as: UTF8.self) }
}

/// The URL sessions of the relay, one pair per network (P21.5 f): calls are made here, blocking, from the relay's own
/// threads. A session given up for a new network is invalidated only once nothing can start a call on it.
final class FirebaseHttp: @unchecked Sendable {
    private final class Sessions {
        let write: URLSession
        let stream: URLSession

        init(_ base: URLSessionConfiguration) {
            let w = (base.copy() as? URLSessionConfiguration) ?? URLSessionConfiguration.default
            w.timeoutIntervalForRequest = FirebaseHttp.writeTimeoutS
            w.timeoutIntervalForResource = FirebaseHttp.writeTimeoutS
            w.requestCachePolicy = .reloadIgnoringLocalCacheData
            w.urlCache = nil
            let s = (base.copy() as? URLSessionConfiguration) ?? URLSessionConfiguration.default
            // Firebase sends a keep-alive event every 30 s: a silent stream is a dead one well before this.
            s.timeoutIntervalForRequest = FirebaseHttp.streamReadTimeoutS
            s.timeoutIntervalForResource = 7 * 24 * 3600
            s.requestCachePolicy = .reloadIgnoringLocalCacheData
            s.urlCache = nil
            write = URLSession(configuration: w)
            stream = URLSession(configuration: s)
        }
    }

    private final class CallResult: @unchecked Sendable {
        let done = DispatchSemaphore(value: 0)
        var data: Data?
        var response: URLResponse?
        var error: (any Error)?
    }

    static let writeTimeoutS: TimeInterval = 20
    static let streamReadTimeoutS: TimeInterval = 45

    private let base: URLSessionConfiguration
    private let lock = NSLock()
    private var current: Sessions
    private var retired: [Sessions] = []
    private var writesClosed = false
    private var streamsClosed = false
    private var streamsReleased = false

    init(_ base: URLSessionConfiguration) {
        self.base = base
        current = Sessions(base)
    }

    /// Runs `request` on the current write session and waits for its answer; a network failure is a
    /// `FirebaseIOError`.
    func execute(_ request: URLRequest) throws -> FirebaseAnswer {
        let result = CallResult()
        lock.lock()
        if writesClosed {
            lock.unlock()
            throw FirebaseIOError("closed")
        }
        let task = current.write.dataTask(with: request) { data, response, error in
            result.data = data
            result.response = response
            result.error = error
            result.done.signal()
        }
        lock.unlock()
        task.resume()
        result.done.wait()
        if let error = result.error { throw FirebaseHttp.ioError(error) }
        guard let http = result.response as? HTTPURLResponse else { throw FirebaseIOError("no answer") }
        return FirebaseAnswer(code: http.statusCode, body: result.data ?? Data())
    }

    /// The session of the stream, for the thread that reads it.
    func streamSession() throws -> URLSession {
        lock.lock()
        defer { lock.unlock() }
        if streamsClosed { throw FirebaseIOError("closed") }
        return current.stream
    }

    /// A new default network: calls from now on open new connections on it (P21.5 f).
    func renew() {
        lock.lock()
        defer { lock.unlock() }
        if writesClosed { return }
        retired.append(current)
        current = Sessions(base)
    }

    /// The stream of the previous network has ended: its sessions can go (called by the thread of the stream).
    func releaseRetired() {
        lock.lock()
        defer { lock.unlock() }
        for s in retired {
            s.write.finishTasksAndInvalidate()
            s.stream.finishTasksAndInvalidate()
        }
        retired.removeAll()
    }

    /// No more call: the write sessions go once their calls are over.
    func closeWrites() {
        lock.lock()
        defer { lock.unlock() }
        writesClosed = true
        current.write.finishTasksAndInvalidate()
        for s in retired {
            s.write.finishTasksAndInvalidate()
        }
        if streamsReleased { invalidateStreams() }
    }

    /// The thread of the stream is over: the stream sessions go once the writes are closed too.
    func releaseStreams() {
        lock.lock()
        defer { lock.unlock() }
        streamsReleased = true
        streamsClosed = true
        if writesClosed { invalidateStreams() }
    }

    private func invalidateStreams() {
        current.stream.invalidateAndCancel()
        for s in retired {
            s.stream.invalidateAndCancel()
        }
        retired.removeAll()
    }

    static func ioError(_ error: any Error) -> FirebaseIOError {
        if let e = error as? FirebaseIOError { return e }
        if let e = error as? URLError { return FirebaseIOError("URLError \(e.code.rawValue)") }
        return FirebaseIOError(String(describing: error))
    }

    /// `url` with `name=value` added to its query, the value percent-encoded (OkHttp `addQueryParameter`).
    static func addQuery(_ url: String, _ name: String, _ value: String) -> String {
        let separator = url.contains("?") ? "&" : "?"
        return url + separator + encode(name) + "=" + encode(value)
    }

    /// Percent-encoding of a query or form value: only the unreserved characters stay.
    static func encode(_ value: String) -> String {
        var out = ""
        for byte in value.utf8 {
            let c = Unicode.Scalar(byte)
            let unreserved = (byte >= 0x30 && byte <= 0x39) || (byte >= 0x41 && byte <= 0x5A)
                || (byte >= 0x61 && byte <= 0x7A) || byte == 0x2D || byte == 0x2E || byte == 0x5F || byte == 0x7E
            if unreserved {
                out.unicodeScalars.append(c)
            } else {
                let hex = String(byte, radix: 16, uppercase: true)
                out += "%" + (hex.count < 2 ? "0" + hex : hex)
            }
        }
        return out
    }
}

/// The text of a JSON object's member as kotlinx's `jsonPrimitive.contentOrNull` reads it.
private func firebaseJsonContent(_ object: P2PJsonObject, _ key: String) -> String? {
    guard let value = object[key] else { return nil }
    switch value {
    case let .literal(text), let .string(text): return text
    default: return nil
    }
}

/// Anonymous sign-in and token renewal over REST (`accounts:signUp`, `securetoken`). Calls block. The sessions are
/// those of the relay, which changes them with the network (P21.5 f).
final class FirebaseAuth: @unchecked Sendable {
    private static let renewMarginMs: Int64 = 120_000
    private static let defaultLifetimeS: Int64 = 3600
    private static let msPerS: Int64 = 1000
    private static let refusedCodes: Set<Int> = [400, 401, 403]

    private let config: RelayConfig
    private let http: FirebaseHttp
    private let store: any AuthStore
    private let endpoints: FirebaseEndpoints
    private let clockMs: @Sendable () -> Int64
    private let lock = NSLock()
    private let tokenLock = NSLock()
    private var idToken: String?
    private var expiresAtMs: Int64 = 0
    private var refreshToken: String?
    private var uidValue = ""

    init(
        _ config: RelayConfig,
        _ http: FirebaseHttp,
        _ store: any AuthStore,
        _ endpoints: FirebaseEndpoints,
        _ clockMs: @escaping @Sendable () -> Int64
    ) {
        self.config = config
        self.http = http
        self.store = store
        self.endpoints = endpoints
        self.clockMs = clockMs
        refreshToken = store.read()
    }

    var uid: String {
        tokenLock.lock()
        defer { tokenLock.unlock() }
        return uidValue
    }

    /// The ID token in use, as it is (no renewal, never blocks): the stream compares it with its own (P21.5 g).
    var current: String? {
        tokenLock.lock()
        defer { tokenLock.unlock() }
        return idToken
    }

    /// A valid ID token, signing in or renewing first when needed.
    func token() throws -> String {
        lock.lock()
        defer { lock.unlock() }
        tokenLock.lock()
        let t = idToken
        let expires = expiresAtMs
        tokenLock.unlock()
        if let t, clockMs() < expires - FirebaseAuth.renewMarginMs { return t }
        return try renew()
    }

    /// Forgets `token` (any token when `nil`); a newer one, already renewed, is kept for the next call.
    func invalidate(_ token: String? = nil) {
        lock.lock()
        defer { lock.unlock() }
        tokenLock.lock()
        if token == nil || token == idToken { idToken = nil }
        tokenLock.unlock()
    }

    private func renew() throws -> String {
        let t0 = clockMs()
        if let rt = refreshToken {
            do {
                let address = try url(FirebaseHttp.addQuery(endpoints.refresh, "key", config.apiKey))
                var request = URLRequest(url: address)
                request.httpMethod = "POST"
                request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
                let form = "grant_type=refresh_token&refresh_token=" + FirebaseHttp.encode(rt)
                request.httpBody = Data(form.utf8)
                let json = try call(request)
                let id = try str(json, "id_token")
                let refresh = try str(json, "refresh_token")
                let expires = try str(json, "expires_in")
                let user = try str(json, "user_id")
                let token = accept(id, refresh, expires, user)
                EventLog.event("fb.auth", ("via", "refresh"), ("ms", clockMs() - t0))
                return token
            } catch let e as FirebaseRefused {
                // The saved identity is gone (account deleted, token revoked): start again as a new anonymous user.
                EventLog.event("fb.auth.lost", ("why", e.message))
                refreshToken = nil
                store.write(nil)
            } catch {
                EventLog.event(
                    "fb.auth.fail",
                    ("via", "refresh"),
                    ("error", String(describing: type(of: error))),
                    ("why", String(describing: error))
                )
                throw error
            }
        }
        let json: P2PJsonObject
        do {
            let address = try url(FirebaseHttp.addQuery(endpoints.signUp, "key", config.apiKey))
            var request = URLRequest(url: address)
            request.httpMethod = "POST"
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data("{\"returnSecureToken\":true}".utf8)
            json = try call(request)
        } catch {
            EventLog.event(
                "fb.auth.fail",
                ("via", "signup"),
                ("error", String(describing: type(of: error))),
                ("why", String(describing: error))
            )
            throw error
        }
        let id = try str(json, "idToken")
        let refresh = try str(json, "refreshToken")
        let expires = try str(json, "expiresIn")
        let user = try str(json, "localId")
        let token = accept(id, refresh, expires, user)
        EventLog.event("fb.auth", ("via", "signup"), ("ms", clockMs() - t0))
        return token
    }

    private func accept(_ id: String, _ refresh: String, _ expiresInS: String, _ user: String) -> String {
        let lifetime = Int64(expiresInS) ?? FirebaseAuth.defaultLifetimeS
        tokenLock.lock()
        idToken = id
        uidValue = user
        expiresAtMs = clockMs() + lifetime * FirebaseAuth.msPerS
        tokenLock.unlock()
        refreshToken = refresh
        store.write(refresh)
        return id
    }

    private func url(_ text: String) throws -> URL {
        guard let u = URL(string: text) else { throw FirebaseIOError("bad address") }
        return u
    }

    private func call(_ request: URLRequest) throws -> P2PJsonObject {
        let r = try http.execute(request)
        let text = r.text
        if r.isSuccessful {
            guard let object = (try? P2PJson.parse(text))?.objectValue else { throw FirebaseIOError("bad answer") }
            return object
        }
        if FirebaseAuth.refusedCodes.contains(r.code) {
            throw FirebaseRefused(errorMessage(text) ?? "HTTP \(r.code)")
        }
        throw FirebaseIOError("HTTP \(r.code)")
    }

    private func errorMessage(_ text: String) -> String? {
        guard let root = (try? P2PJson.parse(text))?.objectValue, let error = root["error"]?.objectValue else {
            return nil
        }
        return firebaseJsonContent(error, "message")
    }

    private func str(_ json: P2PJsonObject, _ key: String) throws -> String {
        guard let value = firebaseJsonContent(json, key) else { throw FirebaseIOError("missing \(key)") }
        return value
    }
}

/// A few calls on the Realtime Database over REST; every call blocks and carries the current token.
final class FirebaseDb: @unchecked Sendable {
    private static let unauthorized = 401
    private static let forbidden = 403

    private let base: String
    private let auth: FirebaseAuth
    private let http: FirebaseHttp

    init(_ base: String, _ auth: FirebaseAuth, _ http: FirebaseHttp) {
        self.base = base
        self.auth = auth
        self.http = http
    }

    /// `<base>/<path>.json?auth=<token>`, plus `extra` query parameters.
    func url(_ path: String, _ token: String, _ extra: [(String, String)] = []) throws -> URL {
        var text = FirebaseHttp.addQuery("\(base)/\(path).json", "auth", token)
        for (name, value) in extra {
            text = FirebaseHttp.addQuery(text, name, value)
        }
        guard let u = URL(string: text) else { throw FirebaseIOError("bad address") }
        return u
    }

    /// Runs `build` with a fresh token; one retry with a renewed token when the server answers 401.
    private func exchange(_ method: String, _ build: (String) throws -> URLRequest) throws -> FirebaseAnswer {
        for attempt in 0 ..< 2 {
            let token = try auth.token()
            let built = try build(token)
            let r = try http.execute(built)
            // The address is never logged: it carries the token.
            if !r.isSuccessful {
                EventLog.event("fb.http", ("method", method), ("code", r.code), ("try", attempt))
            }
            if r.code == FirebaseDb.unauthorized, attempt == 0 {
                auth.invalidate(token)
            } else if r.code == FirebaseDb.unauthorized || r.code == FirebaseDb.forbidden {
                throw FirebaseRefused("permission denied (\(r.code))")
            } else if !r.isSuccessful {
                throw FirebaseIOError("HTTP \(r.code)")
            } else {
                return r
            }
        }
        throw FirebaseRefused("permission denied")
    }

    private func makeRequest(_ method: String, _ path: String, _ token: String, _ json: String?) throws -> URLRequest {
        let address = try url(path, token)
        var request = URLRequest(url: address)
        request.httpMethod = method
        if let json {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(json.utf8)
        }
        return request
    }

    func put(_ path: String, _ json: String) throws {
        _ = try exchange("PUT") { try makeRequest("PUT", path, $0, json) }
    }

    func patch(_ path: String, _ json: String) throws {
        _ = try exchange("PATCH") { try makeRequest("PATCH", path, $0, json) }
    }

    func delete(_ path: String) throws {
        _ = try exchange("DELETE") { try makeRequest("DELETE", path, $0, nil) }
    }

    /// Appends a child with a time-ordered key and returns the key.
    func push(_ path: String, _ json: String) throws -> String {
        let r = try exchange("POST") { try makeRequest("POST", path, $0, json) }
        guard let object = (try? P2PJson.parse(r.text))?.objectValue else {
            throw FirebaseIOError("bad answer")
        }
        return firebaseJsonContent(object, "name") ?? ""
    }

    func get(_ path: String) throws -> String {
        try exchange("GET") { try makeRequest("GET", path, $0, nil) }.text
    }

    /// The keys of the children of `path`, without their content (`shallow`); none when `path` is empty.
    func keys(_ path: String) throws -> [String] {
        let r = try exchange("GET") { token in
            let address = try url(path, token, [("shallow", "true")])
            var request = URLRequest(url: address)
            request.httpMethod = "GET"
            return request
        }
        return (try? P2PJson.parse(r.text))?.objectValue?.keys ?? []
    }

    /// The request of a streamed read of `path` with `token`.
    func streamRequest(_ path: String, _ token: String) throws -> URLRequest {
        let address = try url(path, token)
        var request = URLRequest(url: address)
        request.httpMethod = "GET"
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        return request
    }
}
