import Foundation
import Observation
import ToolMapCore

/// Rechercher (Android SEARCH, `SearchViewModel`, P9.1): a position typed in the field and the user's own tracks,
/// routes and waypoints as the text changes (nothing leaves the phone), and the geocoders (IGN, OpenStreetMap) only
/// when the text is submitted, never while typing, never in offline mode.
@Observable
final class SearchController {
    @ObservationIgnored unowned let app: AppModel

    private(set) var query = ""
    /// A typed position, then the user's own items: instant.
    private(set) var here: [SearchResult] = []
    /// What the geocoders found for the text last submitted.
    private(set) var online: [SearchResult] = []
    private(set) var searching = false
    /// The text was submitted and the answer is in (found or not).
    private(set) var searched = false
    /// The text was submitted in offline mode: nothing was sent.
    private(set) var skippedOffline = false
    private(set) var failures: [GeocodeFailure] = []
    /// The map centre when the page opened, for the distance shown on each result.
    private(set) var center: LonLat?
    /// A short message at the bottom of the page (« Waypoint enregistré. »).
    var message: String?

    @ObservationIgnored private var items: [ToolMapCore.LibraryItem] = []
    @ObservationIgnored private var task: Task<Void, Never>?

    init(app: AppModel) {
        self.app = app
    }

    /// The page opens: the map centre favours the places close to it; the library is read again.
    func open() {
        center = app.center
        items = MapToolsBridge.libraryItems(app)
        refreshHere()
        // Debug only (`-ToolMapSearch "Chamrousse"`, CI screenshots): a text typed and submitted.
        if query.isEmpty, let text = UserDefaults.standard.string(forKey: "ToolMapSearch"), !text.isEmpty {
            setQuery(text)
            submit()
        }
    }

    func setQuery(_ text: String) {
        guard text != query else { return }
        task?.cancel()
        task = nil
        query = text
        online = []
        searching = false
        searched = false
        skippedOffline = false
        failures = []
        refreshHere()
    }

    /// The keyboard's search key, or « Réessayer »: the geocoders are asked.
    func submit() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return }
        task?.cancel()
        if app.offline {
            online = []
            searching = false
            searched = false
            skippedOffline = true
            return
        }
        searching = true
        searched = false
        skippedOffline = false
        failures = []
        online = []
        let near = center
        let userAgent = "ToolMap/\(app.version) (iOS)"
        let language = Locale.current.language.languageCode?.identifier ?? "fr"
        task = Task { [weak self] in
            let outcome = await Geocoder.search(text, near: near, language: language, userAgent: userAgent)
            if Task.isCancelled { return }
            await MainActor.run {
                self?.received(outcome, for: text)
            }
        }
    }

    private func received(_ outcome: GeocodeOutcome, for text: String) {
        guard query.trimmingCharacters(in: .whitespacesAndNewlines) == text else { return }
        online = outcome.results
        failures = outcome.failures
        searching = false
        searched = true
    }

    /// « Voir sur la carte »: the map frames the place (an area or a line whole), and the page closes.
    func show(_ result: SearchResult) {
        app.screen = nil
        if let b = result.bounds, b.maxX - b.minX > 1e-9 || b.maxY - b.minY > 1e-9 {
            app.show(b)
        } else {
            app.show(LonLat(lon: result.lon, lat: result.lat), zoom: SearchController.pointZoom)
        }
    }

    /// « Aller vers »: the place becomes the target, and the page closes.
    func goTo(_ result: SearchResult) {
        let name = result.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = NavTarget(lat: result.lat, lon: result.lon, name: name.isEmpty ? nil : result.title)
        MapToolsBridge.setTarget(app, target)
        app.screen = nil
    }

    /// « Enregistrer comme waypoint »: a found place kept in the library.
    func saveAsWaypoint(_ result: SearchResult) {
        let title = result.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let subtitle = result.subtitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let waypoint = Waypoint(
            lat: result.lat,
            lon: result.lon,
            name: title.isEmpty ? nil : result.title,
            desc: subtitle.isEmpty ? nil : result.subtitle
        )
        if MapToolsBridge.saveWaypoint(app, waypoint) != nil {
            message = "Waypoint enregistré."
        } else {
            message = "L'enregistrement a échoué."
        }
    }

    private func refreshHere() {
        var found: [SearchResult] = []
        if let position = CoordinateQuery.resolve(query) {
            found.append(position)
        }
        found.append(contentsOf: LocalSearch.search(query, items))
        here = found
    }

    /// Camera zoom of a place without extent (Android `MapController.POINT_ZOOM`).
    static let pointZoom = 15.0
}

/// Why a geocoder gave nothing (Android `GeocodeFailure`, `ProviderFailure`).
struct GeocodeFailure: Equatable, Sendable {
    enum Reason: Equatable, Sendable {
        /// No answer: offline, DNS, timeout.
        case network
        /// The service answered with an error status (`429` for Nominatim means "too many requests").
        case http
        /// The answer was not what the service documents.
        case unreadable
    }

    let source: SearchSource
    let reason: Reason
    var httpCode: Int?
}

/// What came back: the places found, and the services that could not answer (the others still count).
struct GeocodeOutcome: Sendable {
    let results: [SearchResult]
    let failures: [GeocodeFailure]
}

/// Looks a place up by name (Android `GeocoderClient`) in the IGN Géoplateforme geocoder (France: toponyms and
/// addresses) and in OpenStreetMap Nominatim (worldwide), at once and independently: one service down or slow does not
/// hide the other's answer. Nominatim's usage policy is followed: an identifying User-Agent, one request a second at
/// most, no search while typing.
enum Geocoder {
    static let ignURL = "https://data.geopf.fr/geocodage/search"
    static let nominatimURL = "https://nominatim.openstreetmap.org/search"
    static let defaultLimit = 8
    private static let maxQueryLength = 200
    private static let timeout: TimeInterval = 10
    private static let maxBytes = 1_000_000
    private static let viewboxDegrees = 1.0
    private static let turn = NominatimTurn()

    private struct Answer: Sendable {
        let results: [SearchResult]
        var failure: GeocodeFailure?
    }

    static func search(
        _ query: String,
        near: LonLat?,
        language: String,
        userAgent: String,
        limit: Int = defaultLimit
    ) async -> GeocodeOutcome {
        let text = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxQueryLength))
        if text.isEmpty { return GeocodeOutcome(results: [], failures: []) }
        async let poi = ign(text, index: "poi", near: near, limit: limit, userAgent: userAgent)
        async let address = ign(text, index: "address", near: near, limit: limit, userAgent: userAgent)
        async let osm = nominatim(text, near: near, language: language, limit: limit, userAgent: userAgent)
        let poiAnswer = await poi
        let addressAnswer = await address
        let osmAnswer = await osm
        // "12 rue ..." is an address; anything else is more likely a place, and places come first.
        let addressFirst = text.first?.isNumber ?? false
        let groups: [Answer] = addressFirst
            ? [addressAnswer, poiAnswer, osmAnswer]
            : [poiAnswer, osmAnswer, addressAnswer]
        var failures: [GeocodeFailure] = []
        for answer in groups {
            if let failure = answer.failure, !failures.contains(failure) {
                failures.append(failure)
            }
        }
        let merged = SearchMerge.merge(groups.map(\.results), limit: limit)
        return GeocodeOutcome(results: merged, failures: failures)
    }

    private static func ign(
        _ query: String,
        index: String,
        near: LonLat?,
        limit: Int,
        userAgent: String
    ) async -> Answer {
        var items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "index", value: index),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        if let near {
            items.append(URLQueryItem(name: "lat", value: String(near.lat)))
            items.append(URLQueryItem(name: "lon", value: String(near.lon)))
        }
        return await fetch(ignURL, items, .ign, userAgent: userAgent) { GeocodeParsers.parseIgn($0) }
    }

    private static func nominatim(
        _ query: String,
        near: LonLat?,
        language: String,
        limit: Int,
        userAgent: String
    ) async -> Answer {
        var items = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "format", value: "jsonv2"),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "accept-language", value: language),
        ]
        if let near {
            // A hint, not a restriction (`bounded` stays off): places inside the box rank first.
            let west = String(near.lon - viewboxDegrees)
            let north = String(near.lat + viewboxDegrees)
            let east = String(near.lon + viewboxDegrees)
            let south = String(near.lat - viewboxDegrees)
            items.append(URLQueryItem(name: "viewbox", value: "\(west),\(north),\(east),\(south)"))
        }
        // One call a second at most, whatever the number of searches in flight.
        await turn.wait()
        return await fetch(nominatimURL, items, .osm, userAgent: userAgent) { GeocodeParsers.parseNominatim($0) }
    }

    private static func fetch(
        _ base: String,
        _ items: [URLQueryItem],
        _ source: SearchSource,
        userAgent: String,
        parse: @Sendable (String) -> [SearchResult]?
    ) async -> Answer {
        guard var components = URLComponents(string: base) else {
            return Answer(results: [], failure: GeocodeFailure(source: source, reason: .network))
        }
        components.queryItems = items
        // URLComponents leaves "+" as it is, which servers read as a space.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let url = components.url else {
            return Answer(results: [], failure: GeocodeFailure(source: source, reason: .network))
        }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if !(200 ..< 300).contains(status) {
                return Answer(results: [], failure: GeocodeFailure(source: source, reason: .http, httpCode: status))
            }
            let body = String(decoding: data.prefix(maxBytes), as: UTF8.self)
            guard let results = parse(body) else {
                return Answer(results: [], failure: GeocodeFailure(source: source, reason: .unreadable))
            }
            return Answer(results: results, failure: nil)
        } catch {
            return Answer(results: [], failure: GeocodeFailure(source: source, reason: .network))
        }
    }
}

/// Nominatim allows an average of one request a second: each call takes the next free slot, a little over a second
/// after the one before.
private actor NominatimTurn {
    private static let gap: TimeInterval = 1.1
    private var next = Date.distantPast

    func wait() async {
        let now = Date()
        let slot = max(now, next)
        next = slot.addingTimeInterval(NominatimTurn.gap)
        let delay = slot.timeIntervalSince(now)
        if delay > 0 {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
    }
}
