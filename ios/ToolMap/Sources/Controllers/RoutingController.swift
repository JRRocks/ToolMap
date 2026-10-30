import CoreLocation
import Foundation
import Network
import Observation
import ToolMapCore
import UIKit
import UserNotifications

/// A leg handed to BRouter: its key, its ends and the zones it goes round.
struct LegRequest: Sendable {
    let key: LegKey
    let from: RouteStop
    let to: RouteStop
    let zones: [AvoidZone]
}

/// Itinéraires calculés, « Aller vers » et suivi d'une trace (Android `RoutingQueue` P19, `GoToController` P30.3,
/// `AlertController` P9.4; ADR-102): the legs are asked of BRouter's public server (`BRouterWebEngine` of the core), one
/// at a time, off the main thread; the way to the target is asked again at its interval and when the walker leaves it;
/// the followed line and the watched waypoints raise the alerts of `AlertEngine` (vibration, local notification). The
/// map draws `RouteLayers`.
@Observable
final class RoutingController {
    unowned let app: AppModel

    /// A leg gives up after this long (Android: 35 s, BRouter stops itself after 30 s).
    static let legTimeoutSeconds: TimeInterval = 35
    /// « Recalculé à l'instant » for this long after BRouter answered.
    static let justNowMillis: Int64 = 10_000
    /// The red pill « nouveau chemin calculé » stays this long.
    static let reroutedShownMillis: Int64 = 8_000
    /// The message of a leg that was not asked: the phone has no network.
    static let offlineMessage = "offline"

    /// BRouter's public server (ADR-102).
    let engine: BRouterWebEngine
    /// The ways already computed, kept from one route to the next (undo, redo, reverse twice ask nothing again).
    let legCache = LegCache()

    // MARK: Legs (Android `RoutingQueue`)

    @ObservationIgnored private var legWanted: [LegRequest] = []
    @ObservationIgnored private var legRunning: LegKey?
    @ObservationIgnored private var legJob: Task<Void, Never>?
    @ObservationIgnored private var legHandler: ((LegKey, LegResult) -> Void)?
    /// The phone has a network path (`NWPathMonitor`).
    @ObservationIgnored private(set) var online = true
    private let monitor = NWPathMonitor()

    // MARK: Aller vers (G8, P30.3)

    /// The place the user walks to.
    private(set) var target: NavTarget?
    /// The start sheet « Aller vers … » stands in front of `target`: the way is computed for its figures.
    private(set) var previewing = false
    /// The start sheet is shown (`NavigationBanner`).
    var startSheetShown = false
    /// By the ways BRouter computes, rather than in a straight line.
    private(set) var goRouted = true
    private(set) var goProfile = RoutingProfiles.hiking
    private(set) var goIntervalMillis: Int64 = GoToRules.defaultIntervalMillis
    /// The last position received.
    private(set) var fix: Fix?
    /// The straight line to the target; nil before the first position.
    private(set) var guidance: Guidance?
    private(set) var way: GoToWay?
    private(set) var progress: GoToProgress?
    /// What is left of the way from the walker, drawn on the map.
    private(set) var remaining: [TrackPoint]?
    private(set) var computingWay = false
    /// When BRouter was last asked: the next question is due `goIntervalMillis` later.
    private(set) var askedAtMillis: Int64?
    /// The clock of « recalcul dans … », updated each second.
    private(set) var nowMillis: Int64 = 0
    /// Why there is no way: the straight line is shown instead.
    private(set) var failure: RoutingError?
    /// The failure came from the network (no connection, or « Mode hors ligne »).
    private(set) var failureOffline = false
    /// The walker left the way by this many meters and a new way was asked: the red pill.
    private(set) var rerouteMeters: Double?
    /// When the way asked after leaving the old one came.
    private(set) var reroutedAtMillis: Int64?

    @ObservationIgnored private var wayJob: Task<Void, Never>?
    @ObservationIgnored private var resume: NavResume?
    @ObservationIgnored private var wasOff = false

    /// The navigation the start sheet stands in front of, given back when it is closed without « Partir ».
    private struct NavResume {
        let target: NavTarget
        let routed: Bool
        let profile: String
    }

    // MARK: Suivi et alertes (G9, P9.4)

    /// What is followed and watched, kept across restarts (`alerts.json`, Android keys).
    private(set) var alertState: AlertState
    private(set) var followName: String?
    /// Where the walker stands on the followed line; nil until the first usable position.
    private(set) var followStatus: FollowStatus?
    /// The last alert, kept on the map until « Compris » (or until the situation is over).
    private(set) var alertBanner: AlertEvent?
    /// The watched waypoints found in the library, with the radius in force.
    private(set) var watchedPlaces: [WatchedPlace] = []
    @ObservationIgnored private var followLine: [LonLat] = []
    private let alertEngine = AlertEngine()

    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var lastFixDate: Date?

    init(app: AppModel) {
        self.app = app
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        engine = BRouterWebEngine(timeoutSeconds: RoutingController.legTimeoutSeconds, userAgent: "ToolMap/\(version) (iOS)")
        let saved = try? String(contentsOf: RoutingController.alertFile, encoding: .utf8)
        alertState = AlertStateCodec.decodeOrEmpty(saved)
        startMonitor()
        // Not while the view that created the controller is being drawn.
        DispatchQueue.main.async { [weak self] in
            self?.restoreAlerts()
        }
    }

    static var alertFile: URL {
        AppFiles.support.appendingPathComponent("alerts.json")
    }

    static func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// The repository of the library (`RouteLibrarySource`), nil while the library does not offer it.
    var libraryRepository: TrackRepository? {
        let library: AnyObject = app.library
        return (library as? RouteLibrarySource)?.trackRepository
    }

    private func startMonitor() {
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            DispatchQueue.main.async {
                self?.online = satisfied
            }
        }
        monitor.start(queue: DispatchQueue(label: "fr.toolmap.routing.network"))
    }

    /// The phone can reach BRouter now.
    var reachable: Bool {
        online && !app.offline
    }

    /// A failure that came from the network rather than from BRouter.
    func isNetworkFailure(_ failed: LegResult.Failed) -> Bool {
        failed.error == .other && (failed.message == RoutingController.offlineMessage || !reachable)
    }

    // MARK: - Legs

    /// The whole list of the legs still wanted after a change: one no longer in it is not computed, the one being
    /// computed is cancelled; `onResult` gets each answer on the main thread.
    func submitLegs(_ requests: [LegRequest], onResult: @escaping (LegKey, LegResult) -> Void) {
        legHandler = onResult
        var seen = Set<LegKey>()
        legWanted = requests.filter { seen.insert($0.key).inserted }
        if let current = legRunning, !legWanted.contains(where: { $0.key == current }) {
            stopLeg()
        }
        if legJob == nil {
            nextLeg()
        }
    }

    /// Forgets every leg (end of the edit).
    func cancelLegs() {
        legWanted = []
        legHandler = nil
        stopLeg()
    }

    private func stopLeg() {
        legJob?.cancel()
        legJob = nil
        legRunning = nil
    }

    private func nextLeg() {
        guard let request = legWanted.first else { return }
        legRunning = request.key
        let engine = self.engine
        let canAsk = reachable
        legJob = Task { @MainActor [weak self] in
            let result: LegResult
            if canAsk {
                result = await engine.route(
                    from: request.from,
                    to: request.to,
                    profile: request.key.profile,
                    zones: request.zones
                )
            } else {
                result = .failed(LegResult.Failed(.other, RoutingController.offlineMessage))
            }
            if Task.isCancelled { return }
            self?.finishLeg(request.key, result)
        }
    }

    private func finishLeg(_ key: LegKey, _ result: LegResult) {
        legJob = nil
        legRunning = nil
        legWanted.removeAll { $0.key == key }
        legHandler?(key, result)
        if legJob == nil {
            nextLeg()
        }
    }

    // MARK: - Aller vers

    var arrived: Bool {
        if progress?.arrived == true { return true }
        if let line = guidance { return Navigation.arrived(line) }
        return false
    }

    /// « Recalculer toutes les … min ».
    var goIntervalMinutes: Int {
        Int(goIntervalMillis / 60_000)
    }

    /// When BRouter is asked again if nothing happens before; nil in a straight line or before the first question.
    var nextAtMillis: Int64? {
        guard let asked = askedAtMillis, goRouted, !arrived else { return nil }
        return asked + goIntervalMillis
    }

    /// The red pill: while the new way is computed, and a moment after it came.
    var rerouting: Bool {
        guard rerouteMeters != nil else { return false }
        guard let at = reroutedAtMillis else { return true }
        return nowMillis - at < RoutingController.reroutedShownMillis
    }

    /// The position is refused: no distance can be given.
    var gpsFailed: Bool {
        let status = app.location.authorization
        return status == .denied || status == .restricted
    }

    /// « Définir comme cible », « Aller vers » of a search result, of the centre, of a member: straight away, with the
    /// choices of the settings (Android `GoToController.start`).
    func setTarget(_ target: NavTarget) {
        let placemarks = app.settings.placemarks
        resume = nil
        startSheetShown = false
        begin(target, routed: placemarks.goToRouted, profile: placemarks.goToProfile, previewing: false)
    }

    /// The same, from a position and a name (nil: « point choisi »).
    func setTarget(_ position: LonLat, name: String?) {
        setTarget(NavTarget(lat: position.lat, lon: position.lon, name: name))
    }

    /// « Aller vers ce point » of a waypoint (variant C): the start sheet first, the way computed behind it with the
    /// saved choices (Android `GoToController.preview`).
    func askTarget(_ target: NavTarget) {
        if !previewing, let current = self.target {
            resume = NavResume(target: current, routed: goRouted, profile: goProfile)
        }
        let placemarks = app.settings.placemarks
        begin(target, routed: placemarks.goToRouted, profile: placemarks.goToProfile, previewing: true)
        let covered = app.panel != nil || app.screen != nil
        app.panel = nil
        app.screen = nil
        app.centerMenuOpen = false
        if covered {
            // The sheet or page in front goes away first: one sheet at a time.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                guard let self, self.previewing else { return }
                self.startSheetShown = true
            }
        } else {
            startSheetShown = true
        }
    }

    /// A choice of the start sheet or of the banner: by the ways or not, the profile, the interval; a new way follows a
    /// new choice.
    func choose(routed: Bool, profile: String, intervalMinutes: Int) {
        guard target != nil else { return }
        let rules = GoToRules.of(intervalMinutes, app.settings.placemarks.goToOffRouteMeters)
        let newWay = routed != goRouted || profile != goProfile
        if newWay {
            wayJob?.cancel()
            wayJob = nil
        }
        goRouted = routed
        goProfile = profile
        goIntervalMillis = rules.intervalMillis
        if newWay {
            clearWay()
            if let last = fix {
                onNavFix(last, now: RoutingController.now())
                return
            }
        }
        publishNav()
    }

    /// « Partir »: the choices are kept for the next time.
    func go() {
        guard target != nil, previewing else { return }
        resume = nil
        let routed = goRouted
        let profile = goProfile
        let minutes = goIntervalMinutes
        app.settingsStore.update { s in
            s.placemarks.goToRouted = routed
            s.placemarks.goToProfile = profile
            s.placemarks.goToIntervalMinutes = minutes
        }
        previewing = false
        startSheetShown = false
    }

    /// The start sheet went away (✕, a swipe): without « Partir », the navigation it stood in front of comes back.
    func startSheetClosed() {
        startSheetShown = false
        cancelPreview()
    }

    func cancelPreview() {
        guard previewing else { return }
        startSheetShown = false
        let back = resume
        resume = nil
        if let back {
            begin(back.target, routed: back.routed, profile: back.profile, previewing: false)
        } else {
            clearTarget()
        }
    }

    /// « Arrêter ».
    func clearTarget() {
        wayJob?.cancel()
        wayJob = nil
        resume = nil
        wasOff = false
        target = nil
        previewing = false
        startSheetShown = false
        guidance = nil
        clearWay()
        publishNav()
        updateTicking()
    }

    private func clearWay() {
        way = nil
        progress = nil
        remaining = nil
        computingWay = false
        askedAtMillis = nil
        failure = nil
        failureOffline = false
        rerouteMeters = nil
        reroutedAtMillis = nil
    }

    private func begin(_ newTarget: NavTarget, routed: Bool, profile: String, previewing preview: Bool) {
        wayJob?.cancel()
        wayJob = nil
        wasOff = false
        clearWay()
        target = newTarget
        previewing = preview
        goRouted = routed
        goProfile = profile
        goIntervalMillis = app.settings.placemarks.goToRules.intervalMillis
        nowMillis = RoutingController.now()
        if let last = fix {
            guidance = Navigation.guide(last.lat, last.lon, newTarget)
        } else {
            guidance = nil
        }
        app.location.start()
        updateTicking()
        if let last = fix {
            onNavFix(last, now: nowMillis)
        } else {
            publishNav()
        }
    }

    private func navRules() -> GoToRules {
        let offRoute = app.settings.placemarks.goToRules.offRouteMeters
        return GoToRules(intervalMillis: goIntervalMillis, offRouteMeters: offRoute)
    }

    private func onNavFix(_ newFix: Fix, now: Int64) {
        guard let current = target else { return }
        let rules = navRules()
        let currentWay = way
        var newProgress: GoToProgress?
        if let w = currentWay {
            newProgress = GoTo.progress(w, newFix.lat, newFix.lon, rules, hintAlongMeters: progress?.alongMeters)
        }
        let off = goRouted && newProgress?.offRoute == true
        if off, !wasOff, !previewing, app.settings.placemarks.goToVibrate {
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        }
        wasOff = off
        var shownOver = false
        if let at = reroutedAtMillis {
            shownOver = now - at >= RoutingController.reroutedShownMillis
        }
        guidance = Navigation.guide(newFix.lat, newFix.lon, current)
        progress = newProgress
        if let w = currentWay, let p = newProgress {
            remaining = w.remaining(p.alongMeters)
        } else {
            remaining = nil
        }
        nowMillis = now
        if shownOver {
            rerouteMeters = nil
            reroutedAtMillis = nil
        }
        let asking = goRouted && wayJob == nil && !arrived
            && GoTo.needsWay(currentWay, newProgress, askedAtMillis, now, rules)
        if asking {
            askWay(newFix, current, goProfile, offMeters: off ? newProgress?.offMeters : nil)
        }
        publishNav()
    }

    /// Asks BRouter for the way from `from` to `to`; `offMeters` when the walker left the old one.
    private func askWay(_ from: Fix, _ to: NavTarget, _ profile: String, offMeters: Double?) {
        computingWay = true
        askedAtMillis = RoutingController.now()
        if let off = offMeters {
            rerouteMeters = off
            reroutedAtMillis = nil
        }
        let engine = self.engine
        let canAsk = reachable
        let start = RouteStop(lat: from.lat, lon: from.lon)
        let end = RouteStop(lat: to.lat, lon: to.lon)
        wayJob = Task { @MainActor [weak self] in
            let result: LegResult
            if canAsk {
                result = await engine.route(from: start, to: end, profile: profile)
            } else {
                result = .failed(LegResult.Failed(.other, RoutingController.offlineMessage))
            }
            if Task.isCancelled { return }
            self?.wayAnswered(result, from: from, for: to, profile: profile)
        }
    }

    private func wayAnswered(_ result: LegResult, from: Fix, for asked: NavTarget, profile: String) {
        wayJob = nil
        // Another target or choice meanwhile: its own way is on the way.
        guard target == asked, goProfile == profile, goRouted else {
            computingWay = false
            return
        }
        let done = RoutingController.now()
        switch result {
        case let .failed(failed):
            wayFailed(failed.error, offline: isNetworkFailure(failed))
        case let .ok(ok):
            if ok.points.count < 2 {
                wayFailed(.noPath, offline: false)
                return
            }
            let newWay = GoToWay(
                points: ok.points,
                profile: goProfile,
                timeMillis: ok.timeMillis,
                computedAtMillis: done,
                from: TrackPoint(lat: from.lat, lon: from.lon)
            )
            let at = fix ?? from
            let newProgress = GoTo.progress(newWay, at.lat, at.lon, navRules())
            wasOff = false
            way = newWay
            progress = newProgress
            if let p = newProgress {
                remaining = newWay.remaining(p.alongMeters)
            } else {
                remaining = ok.points
            }
            computingWay = false
            failure = nil
            failureOffline = false
            reroutedAtMillis = rerouteMeters != nil ? done : nil
            publishNav()
        }
    }

    /// Without data, the straight line of before; otherwise the old way stays until a new one comes.
    private func wayFailed(_ error: RoutingError, offline: Bool) {
        let keep = error != .noData && error != .notInstalled
        computingWay = false
        failure = error
        failureOffline = offline
        rerouteMeters = nil
        reroutedAtMillis = nil
        if !keep {
            way = nil
            progress = nil
            remaining = nil
        }
        publishNav()
    }

    /// The ring on the target and the way to it (white edge, in the colour of the target; pale while the walker is off
    /// it), else a dotted line from the position.
    private func publishNav() {
        var features = app.mapFeatures
        if let current = target {
            let color = RouteGeo.hex(app.settings.placemarks.targetColor)
            features[RouteLayers.targetId] = RouteGeo.point(current.lat, current.lon, color: color)
            var shownWay: [TrackPoint]?
            if goRouted, !arrived, let rest = remaining, rest.count >= 2 {
                shownWay = rest
            }
            if let line = shownWay {
                let pale = goRouted && progress?.offRoute == true
                let points = line.map { LonLat(lon: $0.lon, lat: $0.lat) }
                features[RouteLayers.wayId] = RouteGeo.line(points, color: color, pale: pale)
                features[RouteLayers.straightId] = nil
            } else if let from = fix {
                let points = [LonLat(lon: from.lon, lat: from.lat), LonLat(lon: current.lon, lat: current.lat)]
                features[RouteLayers.straightId] = RouteGeo.line(points, color: color)
                features[RouteLayers.wayId] = nil
            } else {
                features[RouteLayers.straightId] = nil
                features[RouteLayers.wayId] = nil
            }
        } else {
            features[RouteLayers.targetId] = nil
            features[RouteLayers.wayId] = nil
            features[RouteLayers.straightId] = nil
        }
        if features != app.mapFeatures {
            app.mapFeatures = features
        }
    }

    // MARK: - Position

    /// While a target is set or something is watched: the GPS each second.
    private func updateTicking() {
        let needed = target != nil || alertState.follow != nil || !watchedPlaces.isEmpty
        // The way to a target, the following and the alerts go on with the screen off (Android: the background service).
        app.location.keepRunningInBackground("routing", needed)
        if needed, ticker == nil {
            ticker = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    if let owner = self {
                        owner.tick()
                    } else {
                        return
                    }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
        } else if !needed, let running = ticker {
            running.cancel()
            ticker = nil
        }
    }

    private func tick() {
        let now = RoutingController.now()
        guard let location = app.location.location, location.horizontalAccuracy >= 0 else {
            if target != nil {
                nowMillis = now
            }
            return
        }
        if location.timestamp != lastFixDate {
            lastFixDate = location.timestamp
            let newFix = RoutingController.fixOf(location)
            fix = newFix
            if target != nil {
                onNavFix(newFix, now: now)
            }
            if alertState.follow != nil || !watchedPlaces.isEmpty {
                feedAlerts(newFix)
            }
            return
        }
        guard target != nil else { return }
        nowMillis = now
        // The way is asked again at its interval even while the walker stands still.
        if let last = fix, let current = target, goRouted, wayJob == nil, !arrived,
           GoTo.needsWay(way, progress, askedAtMillis, now, navRules())
        {
            askWay(last, current, goProfile, offMeters: nil)
        }
    }

    static func fixOf(_ location: CLLocation) -> Fix {
        let c = location.coordinate
        return Fix(
            lat: c.latitude,
            lon: c.longitude,
            ele: location.verticalAccuracy >= 0 ? location.altitude : nil,
            timeMillis: Int64(location.timestamp.timeIntervalSince1970 * 1000),
            accuracyMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
            speedMps: location.speed >= 0 ? location.speed : nil,
            verticalAccuracyMeters: location.verticalAccuracy >= 0 ? location.verticalAccuracy : nil,
            eleIsMsl: true
        )
    }

    // MARK: - Suivi d'une trace

    var following: FollowRef? {
        alertState.follow
    }

    /// « Suivre cette trace » (alerte d'écart).
    func follow(trackId: Int64) {
        setFollow(FollowRef(kind: .track, id: trackId))
    }

    /// « Suivre cet itinéraire ».
    func follow(routeId: Int64) {
        setFollow(FollowRef(kind: .route, id: routeId))
    }

    func isFollowing(trackId: Int64) -> Bool {
        alertState.follow?.kind == .track && alertState.follow?.id == trackId
    }

    func isFollowing(routeId: Int64) -> Bool {
        alertState.follow?.kind == .route && alertState.follow?.id == routeId
    }

    func stopFollowing() {
        setFollow(nil)
    }

    /// « Sens inverse »: the same line the other way round.
    func reverseFollow() {
        guard let current = alertState.follow else { return }
        setFollow(FollowRef(kind: current.kind, id: current.id, reversed: !current.reversed))
    }

    /// « Compris ».
    func dismissAlert() {
        alertBanner = nil
    }

    private func setFollow(_ ref: FollowRef?) {
        saveAlertState(AlertState(follow: ref, watches: alertState.watches))
        loadFollow(ref)
    }

    /// Loads the followed line; `quiet` at the start of the app, when the library may not be there yet.
    private func loadFollow(_ ref: FollowRef?, quiet: Bool = false) {
        guard let wanted = ref else {
            alertEngine.setFollow(nil)
            followName = nil
            followStatus = nil
            followLine = []
            alertBanner = nil
            publishAlerts()
            updateTicking()
            return
        }
        guard let repository = libraryRepository else {
            if !quiet {
                app.say("La bibliothèque n'est pas encore prête : rien à suivre.")
            }
            return
        }
        Task { @MainActor [weak self] in
            let loaded = await RoutingController.lineOf(wanted, repository)
            self?.followLoaded(wanted, loaded)
        }
    }

    /// The name and the points of a track (its segments end to end) or of a route, read away from the main thread.
    private static func lineOf(_ ref: FollowRef, _ repository: TrackRepository) async -> FollowedLine? {
        switch ref.kind {
        case .track:
            guard let found = try? repository.loadTrack(ref.id) else { return nil }
            var points: [LonLat] = []
            for segment in found.segments {
                for p in segment {
                    points.append(LonLat(lon: p.lon, lat: p.lat))
                }
            }
            return FollowedLine(name: found.name, points: points)
        case .route:
            guard let found = try? repository.loadRoute(ref.id) else { return nil }
            let points = found.points.map { LonLat(lon: $0.lon, lat: $0.lat) }
            return FollowedLine(name: found.name, points: points)
        }
    }

    private struct FollowedLine: Sendable {
        let name: String?
        let points: [LonLat]
    }

    private func followLoaded(_ ref: FollowRef, _ loaded: FollowedLine?) {
        guard alertState.follow == ref else { return }
        var follower: TrackFollower?
        if let line = loaded {
            follower = TrackFollower(line.points, reversed: ref.reversed)
        }
        guard let usable = follower, usable.usable, let line = loaded else {
            saveAlertState(AlertState(follow: nil, watches: alertState.watches))
            loadFollow(nil)
            app.say("Cette ligne n'a pas deux points distincts : rien à suivre.")
            return
        }
        alertEngine.updateSettings(app.settings.alerts)
        alertEngine.setFollow(usable)
        followName = line.name
        followStatus = nil
        followLine = ref.reversed ? Array(line.points.reversed()) : line.points
        alertBanner = nil
        app.location.start()
        requestNotifications()
        publishAlerts()
        updateTicking()
    }

    // MARK: - Alertes de proximité

    /// The watch of a waypoint, if any (« Alerte à 200 m »).
    func watching(_ waypointId: Int64) -> WatchEntry? {
        alertState.watching(waypointId)
    }

    /// Watches a waypoint; `radiusMeters` nil means the default radius of the settings.
    func watch(waypointId: Int64, radiusMeters: Int?) {
        saveAlertState(alertState.withWatch(waypointId, radiusMeters))
        requestNotifications()
        refreshWatched()
    }

    func unwatch(waypointId: Int64) {
        saveAlertState(alertState.withoutWatch(waypointId))
        refreshWatched()
    }

    /// « Tout arrêter »: nothing is followed or watched any more.
    func stopAllAlerts() {
        saveAlertState(AlertState())
        loadFollow(nil)
        refreshWatched()
    }

    private func refreshWatched() {
        let watches = alertState.watches
        let radius = app.settings.alerts.proximityRadiusMeters
        guard !watches.isEmpty, let repository = libraryRepository else {
            watchedLoaded([])
            return
        }
        Task { @MainActor [weak self] in
            let found = await RoutingController.placesOf(watches, radius, repository)
            self?.watchedLoaded(found)
        }
    }

    /// The watched waypoints that are still in the library, read away from the main thread.
    private static func placesOf(_ watches: [WatchEntry], _ radius: Int, _ repository: TrackRepository) async -> [WatchedPlace] {
        var places: [WatchedPlace] = []
        for watch in watches {
            guard let waypoint = try? repository.loadWaypoint(watch.waypointId) else { continue }
            let meters = Double(watch.radiusMeters ?? radius)
            places.append(
                WatchedPlace(id: watch.waypointId, name: waypoint.name, lat: waypoint.lat, lon: waypoint.lon, radiusMeters: meters)
            )
        }
        return places
    }

    private func watchedLoaded(_ places: [WatchedPlace]) {
        watchedPlaces = places
        alertEngine.setWatched(places)
        if !places.isEmpty {
            app.location.start()
        }
        publishAlerts()
        updateTicking()
    }

    private func restoreAlerts() {
        if let ref = alertState.follow {
            loadFollow(ref, quiet: true)
        }
        if !alertState.watches.isEmpty {
            refreshWatched()
        }
    }

    private func saveAlertState(_ state: AlertState) {
        alertState = state
        let text = AlertStateCodec.encode(state)
        try? text.write(to: RoutingController.alertFile, atomically: true, encoding: .utf8)
        updateTicking()
    }

    private func feedAlerts(_ newFix: Fix) {
        alertEngine.updateSettings(app.settings.alerts)
        let update = alertEngine.onFix(newFix)
        for event in update.events {
            raise(event)
        }
        if let status = update.status {
            followStatus = status
        }
    }

    /// An alert: on the map, a vibration, and a notification for when the phone is in the pocket.
    private func raise(_ event: AlertEvent) {
        alertBanner = event == .backOnTrack ? nil : event
        let settings = app.settings
        if settings.alerts.vibrate {
            let kind: UINotificationFeedbackGenerator.FeedbackType = event == .backOnTrack ? .success : .warning
            UINotificationFeedbackGenerator().notificationOccurred(kind)
        }
        let text = RouteText.alert(event, settings)
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = text.body
        if settings.alerts.sound {
            content.sound = .default
        }
        let request = UNNotificationRequest(identifier: "toolmap-alert-\(UUID().uuidString)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    private func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// The followed line and the circles of the watched waypoints.
    private func publishAlerts() {
        var features = app.mapFeatures
        features[RouteLayers.alertsId] = watchedPlaces.isEmpty ? nil : AlertGeoJson.circles(watchedPlaces)
        if followLine.count >= 2 {
            features[RouteLayers.followId] = RouteGeo.line(followLine, color: "#14918c")
        } else {
            features[RouteLayers.followId] = nil
        }
        if features != app.mapFeatures {
            app.mapFeatures = features
        }
    }
}
