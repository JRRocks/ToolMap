// The iPhone's routing engine (P32.12): there is no BRouter app to call on iOS (ADR-064 is Android only), so the legs are
// asked of BRouter's public web server, with the same parameters as the Android AIDL service (`lonlats`, `profile`,
// `nogos` of ADR-091) and the same GeoJSON reader (`BRouterJson`). Android's counterpart: data/.../BRouterEngine.kt.

import Foundation

/// Computes a leg with a BRouter web server: `GET <server>?lonlats=lon,lat|lon,lat&profile=…&alternativeidx=0&
/// format=geojson[&nogos=lon,lat,radius|…]`. Online only; the answer is the GeoJSON of `BRouterJson`, or a plain-text
/// message that `BRouterErrors` sorts.
public struct BRouterWebEngine: RoutingEngine {
    /// BRouter's public server (brouter.de).
    public static let publicServer = URL(string: "https://brouter.de/brouter")!

    /// Longest message kept from an answer that is not a way (Kotlin's `take(200)`).
    private static let maxMessage = 200

    public let server: URL
    public let session: URLSession
    public let timeoutSeconds: TimeInterval
    public let userAgent: String?

    public init(
        server: URL = BRouterWebEngine.publicServer,
        session: URLSession = .shared,
        timeoutSeconds: TimeInterval = 60,
        userAgent: String? = nil
    ) {
        self.server = server
        self.session = session
        self.timeoutSeconds = timeoutSeconds
        self.userAgent = userAgent
    }

    /// The request for one leg; `nil` when `server` cannot carry a query.
    public func request(from: RouteStop, to: RouteStop, profile: String, zones: [AvoidZone]) -> URLRequest? {
        guard var components = URLComponents(url: server, resolvingAgainstBaseURL: false) else { return nil }
        let lonlats = "\(Self.degrees(from.lon)),\(Self.degrees(from.lat))|\(Self.degrees(to.lon)),\(Self.degrees(to.lat))"
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "lonlats", value: lonlats))
        items.append(URLQueryItem(name: "profile", value: profile))
        items.append(URLQueryItem(name: "alternativeidx", value: "0"))
        items.append(URLQueryItem(name: "format", value: "geojson"))
        // Circles the way must not enter (P19.8): `lon,lat,radius|…`.
        if !zones.isEmpty { items.append(URLQueryItem(name: "nogos", value: AvoidZone.nogos(zones))) }
        components.queryItems = items
        guard let url = components.url else { return nil }
        var urlRequest = URLRequest(url: url, timeoutInterval: timeoutSeconds)
        if let agent = userAgent { urlRequest.setValue(agent, forHTTPHeaderField: "User-Agent") }
        return urlRequest
    }

    public func route(from: RouteStop, to: RouteStop, profile: String, zones: [AvoidZone]) async -> LegResult {
        guard let leg = self.request(from: from, to: to, profile: profile, zones: zones) else {
            return .failed(LegResult.Failed(.other, "bad server address"))
        }
        let answer: (Data, URLResponse)
        do {
            answer = try await session.data(for: leg)
        } catch let error as URLError {
            return .failed(LegResult.Failed(error.code == .timedOut ? .timeout : .other, error.localizedDescription))
        } catch {
            return .failed(LegResult.Failed(.other, String(describing: error)))
        }
        let status = (answer.1 as? HTTPURLResponse)?.statusCode ?? 200
        return Self.answerOf(String(decoding: answer.0, as: UTF8.self), status: status)
    }

    /// The way, or the reason BRouter gives instead (Android's `BRouterEngine.answerOf`, without the compressed form the
    /// web server does not use).
    static func answerOf(_ text: String, status: Int = 200) -> LegResult {
        let firstVisible = text.unicodeScalars.first { !KotlinText.isWhitespace($0) }
        if firstVisible == "{" {
            if let ok = BRouterJson.parse(text) { return .ok(ok) }
            return .failed(LegResult.Failed(.other, String(text.prefix(maxMessage))))
        }
        if firstVisible == nil && !(200 ..< 300).contains(status) {
            return .failed(LegResult.Failed(.other, "HTTP \(status)"))
        }
        return .failed(LegResult.Failed(BRouterErrors.classify(text), text))
    }

    private static func degrees(_ value: Double) -> String {
        TrackNumberText.fixed(value, 7)
    }
}
