import CoreLocation
import Foundation
import Observation
import ToolMapCore
import UIKit

/// What the drawing works on (Android `DrawTarget`).
enum DrawTarget: Equatable, Sendable {
    case newTrack
    case newRoute
    /// One segment of a stored track; the others are kept as they are.
    case track(id: Int64, name: String?, segments: [[TrackPoint]], index: Int)
    /// A stored route, with its GPX extensions (kept; the plan of a computed route replaced).
    case route(id: Int64, name: String?, points: [Waypoint], extras: [RawXml])

    var isRoute: Bool {
        switch self {
        case .newRoute, .route: true
        case .newTrack, .track: false
        }
    }
}

/// What BRouter's answers make the map say (Android `RouteNotice`, `docs/specs/routing.md` §3).
enum RouteNotice: Equatable {
    /// One leg was drawn by hand; the routing goes on.
    case legByHand(RoutingError, offline: Bool)
    /// The routing was turned off: the rest is drawn by hand.
    case allByHand(RoutingError, offline: Bool)
    /// BRouter does not have this profile: the previous one is back.
    case unknownProfile(String)
}

/// Dessin d'une trace ou d'un itinéraire (Android T1, T1b, `DrawViewModel`, P19, P30.1, P30.4): tracks are the core's
/// `PolylineEditor`, routes its `RoutePlanEditor`, by hand (straight legs) or following the ways (legs asked of BRouter
/// by `RoutingController`). The crosshair is the centre of the map; the map draws `EditLayers`.
@Observable
final class DrawController {
    unowned let app: AppModel

    /// A tap selects a vertex within this many points of it (Android `TAP_REACH_DP`).
    static let reachPoints = 24.0
    /// A finger stroke is thinned to this many points (Android `STROKE_TOLERANCE_DP`).
    static let strokePoints = 4.0
    /// The choices of « Simplifier… », in meters.
    static let simplifyTolerances: [Double] = [1, 2, 5, 10, 25]
    /// The radii offered for a zone to avoid (P19.8), in meters.
    static let zoneRadii = [100, 250, 500, 1000]
    /// A route drawn by hand with more stops than this asks before following the ways.
    static let manyStops = 30
    /// A point of interest this close to the way is « sur le tracé » (P30.1).
    static let onRouteMeters = 15.0

    private(set) var active = false
    private(set) var target: DrawTarget?
    private(set) var pointCount = 0
    private(set) var selected: Int?
    private(set) var canUndo = false
    private(set) var canRedo = false
    private(set) var freehand = false
    /// Length of the line, or of the route.
    private(set) var lengthMeters = 0.0
    /// Something changed since the line was opened.
    private(set) var dirty = false
    /// A stored track with several segments: the number of points of each, for « Quel segment modifier ? ».
    private(set) var segmentChoices: [Int]?

    /// The route being edited; nil for a track.
    private(set) var plan: RoutePlan?
    /// « Suivre les chemins (BRouter) »: new legs follow the ways.
    private(set) var routing = false
    private(set) var profile = RoutingProfiles.hiking
    /// The profiles offered: the built-in ones, then those the user typed.
    private(set) var profiles: [String] = RoutingProfiles.builtIn
    /// « Ajouter au début »: new stops go before the selected one, or before A.
    private(set) var atStart = false
    private(set) var summary = RouteSummary.empty
    /// The stop taken at the crosshair (« Prendre l'étape »), which follows it until « Poser ici ».
    private(set) var taken: Int?
    /// The radius of the zone being placed at the crosshair (« Éviter une zone »); nil when none is.
    private(set) var zoneRadius: Int?
    /// « Poser : Étape | Point d'intérêt » on « Point d'intérêt » (P30.1).
    private(set) var poiMode = false
    /// The point of interest taken at the crosshair (« Déplacer »).
    private(set) var movingPoi: Int?
    /// The zone under the crosshair, drawn with a solid edge and given a handle (P30.4).
    private(set) var hoveredZone: Int?
    /// The point of the route under the cursor of its elevation profile.
    private(set) var cursor: TrackPoint?

    @ObservationIgnored private var editor = PolylineEditor()
    @ObservationIgnored private var route: RoutePlanEditor?
    @ObservationIgnored private var pendingTrack: DrawTarget?
    @ObservationIgnored private var savedPoints: [TrackPoint] = []
    @ObservationIgnored private var savedPlan = RoutePlan()
    /// The profile before the last change: back to it when BRouter does not know the new one.
    @ObservationIgnored private var previousProfile = RoutingProfiles.hiking
    /// Only one message about BRouter's answers per edit of the user.
    @ObservationIgnored private var noticeGiven = false
    @ObservationIgnored private var takenAt: TrackPoint?
    @ObservationIgnored private var placingAt: TrackPoint?
    @ObservationIgnored private var movingPoiAt: TrackPoint?
    /// The zone whose handle is pulled and its size meanwhile (P30.4).
    @ObservationIgnored private var resizingZone: Int?
    @ObservationIgnored private var resizingRadius: Int?
    private let elevations = HandLegElevations()
    @ObservationIgnored private var elevating = false
    @ObservationIgnored private var previousTap: ((LonLat) -> Void)?
    @ObservationIgnored private var holdsTaps = false
    /// The tool the drawing was opened for: the same kind opened again changes nothing.
    @ObservationIgnored private var openedKind: DrawKind?

    init(app: AppModel) {
        self.app = app
    }

    var isRoute: Bool {
        target?.isRoute ?? false
    }

    /// A stored track is edited (« Couper en deux ici »).
    var editsStoredTrack: Bool {
        if case .track? = target { return true }
        return false
    }

    /// The route follows the ways: the panel of the computed routes replaces the drawing by hand.
    var routed: Bool {
        plan != nil && routing
    }

    /// 24 points on the ground at the current zoom: how close the crosshair or a finger must be to a point.
    var reachMeters: Double {
        DrawController.reachPoints * app.metersPerPoint
    }

    // MARK: - Opening and closing

    /// The tool panel appeared, or asks for another line (`AppModel.tool = .draw(kind)`).
    func open(_ kind: DrawKind) {
        if active || pendingTrack != nil, openedKind == kind { return }
        openedKind = kind
        app.panel = nil
        app.centerMenuOpen = false
        switch kind {
        case .track(.none):
            begin(.newTrack, [])
        case let .track(.some(id)):
            startEditTrack(id)
        case .route(.none):
            let demo = UserDefaults.standard.bool(forKey: "ToolMapDemo")
            beginRoute(.newRoute, demo ? DrawController.demoPlan() : RoutePlan())
        case let .route(.some(id)):
            startEditRoute(id)
        }
    }

    /// ✕ without changes, « Abandonner », or the tool went away: the line is dropped.
    func finish() {
        app.routing.cancelLegs()
        route = nil
        editor = PolylineEditor()
        pendingTrack = nil
        openedKind = nil
        taken = nil
        takenAt = nil
        zoneRadius = nil
        placingAt = nil
        hoveredZone = nil
        resizingZone = nil
        resizingRadius = nil
        movingPoi = nil
        movingPoiAt = nil
        cursor = nil
        active = false
        target = nil
        plan = nil
        pointCount = 0
        selected = nil
        canUndo = false
        canRedo = false
        freehand = false
        lengthMeters = 0
        dirty = false
        segmentChoices = nil
        summary = RouteSummary.empty
        releaseTaps()
        show(line: nil, handles: nil, elastic: nil, zones: nil)
        setFeature(EditLayers.cursorId, nil)
        if let tool = app.tool, case .draw = tool {
            app.tool = nil
        }
    }

    private func startEditTrack(_ id: Int64) {
        guard let repository = app.routing.libraryRepository else {
            nothingToEdit()
            return
        }
        Task { @MainActor [weak self] in
            let track = await DrawController.loadTrack(id, repository)
            guard let self, self.openedKind == DrawKind.track(id) else { return }
            guard let found = track, !found.segments.isEmpty else {
                self.nothingToEdit()
                return
            }
            let edited = DrawTarget.track(id: id, name: found.name, segments: found.segments, index: 0)
            if found.segments.count == 1 {
                self.begin(edited, found.segments[0])
            } else {
                self.pendingTrack = edited
                self.active = true
                self.target = edited
                self.segmentChoices = found.segments.map { $0.count }
            }
        }
    }

    /// « Segment n » of « Quel segment modifier ? ».
    func pickSegment(_ index: Int) {
        guard let pending = pendingTrack, case let .track(id, name, segments, _) = pending, segments.indices.contains(index)
        else { return }
        pendingTrack = nil
        begin(.track(id: id, name: name, segments: segments, index: index), segments[index])
    }

    /// Opens a route: with its stops and profiles when it was computed, else as drawn by hand (every point a stop).
    private func startEditRoute(_ id: Int64) {
        guard let repository = app.routing.libraryRepository else {
            nothingToEdit()
            return
        }
        Task { @MainActor [weak self] in
            let loaded = await DrawController.loadRoute(id, repository)
            guard let self, self.openedKind == DrawKind.route(id) else { return }
            guard let found = loaded, !found.points.isEmpty else {
                self.nothingToEdit()
                return
            }
            let geometry = found.points.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
            let opened = RoutePlanGpx.planOf(geometry, found.extras) ?? DrawController.manualPlan(found.points)
            self.beginRoute(.route(id: id, name: found.name, points: found.points, extras: found.extras), opened)
        }
    }

    private static func loadTrack(_ id: Int64, _ repository: TrackRepository) async -> Track? {
        try? repository.loadTrack(id)
    }

    private static func loadRoute(_ id: Int64, _ repository: TrackRepository) async -> Route? {
        try? repository.loadRoute(id)
    }

    private func nothingToEdit() {
        app.say("Rien à modifier : cet élément est vide ou introuvable.")
        finish()
    }

    private func begin(_ newTarget: DrawTarget, _ points: [TrackPoint]) {
        app.routing.cancelLegs()
        route = nil
        editor = PolylineEditor(points)
        savedPoints = points
        pendingTrack = nil
        segmentChoices = nil
        freehand = false
        active = true
        target = newTarget
        takeTaps()
        publish()
    }

    private func beginRoute(_ newTarget: DrawTarget, _ initial: RoutePlan) {
        app.routing.cancelLegs()
        elevations.forgetFailures()
        let placemarks = app.settings.placemarks
        let e = RoutePlanEditor(initial, cache: app.routing.legCache)
        e.profile = initial.legs.last(where: { $0.mode == .routed })?.profile ?? placemarks.routingProfile
        // On for a new route and for a route computed before; off for one drawn by hand.
        e.routing = initial.stops.isEmpty || !initial.isManual
        if UserDefaults.standard.bool(forKey: "ToolMapDemo"), newTarget == .newRoute {
            // The demonstration route shows the panel of the computed routes, without asking BRouter.
            e.routing = true
        }
        previousProfile = e.profile
        route = e
        savedPlan = initial
        atStart = false
        taken = nil
        takenAt = nil
        zoneRadius = nil
        placingAt = nil
        hoveredZone = nil
        resizingZone = nil
        resizingRadius = nil
        movingPoi = nil
        movingPoiAt = nil
        cursor = nil
        pendingTrack = nil
        segmentChoices = nil
        freehand = false
        active = true
        target = newTarget
        takeTaps()
        publish()
    }

    // MARK: - Taps on the map

    /// While the tool is open, a tap on the map is the drawing's (Android `onDrawTap`).
    private func takeTaps() {
        app.tapHandlers[EditLayers.handlesId] = { [weak self] tap in
            self?.onMapTap(tap.position)
        }
        app.tapHandlers[EditLayers.lineId] = { [weak self] tap in
            self?.onMapTap(tap.position)
        }
        guard !holdsTaps else { return }
        holdsTaps = true
        previousTap = app.emptyTapHandler
        app.emptyTapHandler = { [weak self] position in
            self?.onMapTap(position)
        }
    }

    private func releaseTaps() {
        app.tapHandlers[EditLayers.handlesId] = nil
        app.tapHandlers[EditLayers.lineId] = nil
        guard holdsTaps else { return }
        holdsTaps = false
        app.emptyTapHandler = previousTap
        previousTap = nil
    }

    /// A tap: pick a vertex or a stop, grow a midpoint handle into one, or add one.
    func onMapTap(_ position: LonLat) {
        guard active, segmentChoices == nil, taken == nil, zoneRadius == nil, movingPoi == nil, !freehand else { return }
        let reach = reachMeters
        if let e = route {
            routeTap(e, position, reach)
            return
        }
        switch EditorGeoJson.interpret(editor.points, position.lat, position.lon, reachMeters: reach) {
        case let .select(index):
            editor.select(editor.selected == index ? nil : index)
        case let .insertMidpoint(segment):
            editor.insertMidpoint(segment)
        case .add:
            editor.add(position.lat, position.lon)
        }
        publish()
    }

    /// Following the ways, a tap only selects the stop under it (the crosshair places the stops); by hand, it also
    /// grows a midpoint into a stop or adds one, as for a track.
    private func routeTap(_ e: RoutePlanEditor, _ position: LonLat, _ reach: Double) {
        let current = e.plan
        if let stop = current.nearestStop(position.lat, position.lon, reach) {
            e.select(e.selected == stop ? nil : stop)
            publish()
            return
        }
        if !e.routing {
            if let leg = DrawController.nearestMidpoint(current, position, reach) {
                let middle = RoutePlanGeoJson.midpoint(current, leg)
                edited(e.insertOnLeg(leg, RouteStop(lat: middle.lat, lon: middle.lon)))
            } else {
                edited(e.add(RouteStop(lat: position.lat, lon: position.lon), atStart: atStart))
            }
            return
        }
        if e.selected != nil {
            e.select(nil)
            publish()
        }
    }

    private static func nearestMidpoint(_ current: RoutePlan, _ position: LonLat, _ reach: Double) -> Int? {
        if current.stops.count > EditorGeoJson.maxHandles { return nil }
        var best: Int?
        var bestDistance = reach
        for (k, leg) in current.legs.enumerated() where leg.mode == .straight {
            let m = RoutePlanGeoJson.midpoint(current, k)
            let d = Geodesy.distance(position.lat, position.lon, m.lat, m.lon)
            if d <= bestDistance {
                bestDistance = d
                best = k
            }
        }
        return best
    }

    // MARK: - What is under the crosshair

    /// The leg « Insérer ici » would cut at the crosshair: within reach of the route and not on a stop.
    func insertLegAt(_ c: LonLat) -> Int? {
        guard let current = route?.plan, !current.legs.isEmpty else { return nil }
        let reach = reachMeters
        if current.nearestStop(c.lat, c.lon, reach) != nil { return nil }
        guard let found = current.nearestLeg(c.lat, c.lon) else { return nil }
        return found.meters <= reach ? found.leg : nil
    }

    /// The stop « Prendre l'étape » would take at the crosshair.
    func stopUnder(_ c: LonLat) -> Int? {
        route?.plan.nearestStop(c.lat, c.lon, reachMeters)
    }

    /// The zone to avoid the crosshair is in, that « Retirer la zone » would remove.
    func zoneUnder(_ c: LonLat) -> Int? {
        route?.plan.zoneAt(c.lat, c.lon)
    }

    /// The point of interest « Modifier le point » would open at the crosshair.
    func poiUnder(_ c: LonLat) -> Int? {
        route?.plan.nearestPoi(c.lat, c.lon, reachMeters)
    }

    func zone(_ index: Int) -> AvoidZone? {
        guard let current = plan, current.zones.indices.contains(index) else { return nil }
        return current.zones[index]
    }

    /// The camera moved: a stop, a zone or a point of interest in hand follows the crosshair; the zone under it gets
    /// its handle.
    func crosshairMoved(_ c: LonLat) {
        guard active, let e = route else { return }
        let at = TrackPoint(lat: c.lat, lon: c.lon)
        if let i = taken {
            takenAt = at
            setFeature(EditLayers.handlesId, EditLayers.prefixIcons(handlesGeoJson(e)))
            setFeature(EditLayers.elasticId, RoutePlanGeoJson.elastic(e.plan, i, at))
            return
        }
        if zoneRadius != nil {
            placingAt = at
            setFeature(EditLayers.zonesId, zonesGeoJson(e))
            return
        }
        if movingPoi != nil {
            movingPoiAt = at
            setFeature(EditLayers.handlesId, EditLayers.prefixIcons(handlesGeoJson(e)))
            return
        }
        var hover: Int?
        if e.routing, !e.plan.zones.isEmpty, e.plan.nearestStop(c.lat, c.lon, reachMeters) == nil {
            hover = e.plan.zoneAt(c.lat, c.lon)
        }
        if hover != hoveredZone, resizingZone == nil {
            hoveredZone = hover
            setFeature(EditLayers.zonesId, zonesGeoJson(e))
        }
    }

    // MARK: - Editing

    /// « Ajouter ici » at the crosshair; on a route, a waypoint under it becomes a named stop.
    func addHere() {
        addAt(app.center)
    }

    /// « Depuis ma position » of an empty route.
    func addFromPosition() {
        guard let location = app.location.location else { return }
        let c = location.coordinate
        addAt(LonLat(lon: c.longitude, lat: c.latitude))
    }

    private func addAt(_ position: LonLat) {
        guard let e = route else {
            editor.add(position.lat, position.lon)
            publish()
            return
        }
        let reach = reachMeters
        let first = atStart
        let repository = app.routing.libraryRepository
        Task { @MainActor [weak self] in
            let stop = await DrawController.stopAt(position, reach, repository)
            guard let self, self.route === e else { return }
            self.edited(e.add(stop, atStart: first))
        }
    }

    /// « Insérer ici »: a stop on leg `leg` at the crosshair (a detour through that place).
    func insertHere(_ leg: Int) {
        guard let e = route else { return }
        let position = app.center
        let repository = app.routing.libraryRepository
        let reach = reachMeters
        Task { @MainActor [weak self] in
            let stop = await DrawController.stopAt(position, reach, repository)
            guard let self, self.route === e else { return }
            self.edited(e.insertOnLeg(leg, stop))
        }
    }

    /// The stop to put at `position`: the visible waypoint under it, with its name and icon, else a passage.
    private static func stopAt(_ position: LonLat, _ reach: Double, _ repository: TrackRepository?) async -> RouteStop {
        let plain = RouteStop(lat: position.lat, lon: position.lon)
        guard reach > 0, let repository, let library = try? repository.library() else { return plain }
        var best: WaypointEntity?
        var bestDistance = reach
        for w in library.waypoints where library.isShown(w.folderId, w.visible) {
            let d = Geodesy.distance(position.lat, position.lon, w.lat, w.lon)
            if d <= bestDistance {
                bestDistance = d
                best = w
            }
        }
        guard let found = best else { return plain }
        return RouteStop(lat: found.lat, lon: found.lon, kind: .named, name: found.name, sym: found.sym)
    }

    /// « Déplacer ici »: the selected point goes to the crosshair.
    func moveSelectedHere() {
        let c = app.center
        if let e = route {
            edited(e.moveSelected(c.lat, c.lon))
            return
        }
        if editor.moveSelected(c.lat, c.lon) { publish() }
    }

    func deleteSelected() {
        if let e = route {
            edited(e.deleteSelected())
            return
        }
        if editor.deleteSelected() { publish() }
    }

    func undo() {
        if let e = route {
            if e.undo() {
                e.fillFromCache()
                edited(true)
            }
            return
        }
        if editor.undo() { publish() }
    }

    func redo() {
        if let e = route {
            if e.redo() {
                e.fillFromCache()
                edited(true)
            }
            return
        }
        if editor.redo() { publish() }
    }

    /// « Main levée »: while on, a finger on the map draws instead of moving it.
    func setFreehand(_ on: Bool) {
        freehand = on
    }

    /// A finger stroke, already turned into positions.
    func addStroke(_ stroke: [TrackPoint]) {
        guard active else { return }
        let tolerance = DrawController.strokePoints * app.metersPerPoint
        if let e = route {
            edited(e.addStroke(stroke, tolerance))
            return
        }
        if editor.addStroke(stroke, toleranceMeters: tolerance) { publish() }
    }

    func reverse() {
        if let e = route {
            edited(e.reverse())
            return
        }
        if editor.reverse() { publish() }
    }

    /// « Simplifier… »: `toleranceMeters` the largest gap to the line as it was.
    func simplify(_ toleranceMeters: Double) {
        let before = route?.plan.stops.count ?? editor.points.count
        let done: Bool
        if let e = route {
            done = e.simplify(toleranceMeters)
        } else {
            done = editor.simplify(toleranceMeters)
        }
        guard done else {
            app.say("Aucun point à supprimer avec cet écart.")
            return
        }
        let after = route?.plan.stops.count ?? editor.points.count
        app.say("Points avant : \(before) — après : \(after)")
        if route != nil {
            edited(true)
        } else {
            publish()
        }
    }

    func trimBeforeSelected() {
        if let e = route {
            if let i = e.selected { edited(e.trimBefore(i)) }
            return
        }
        if editor.trimBeforeSelected() { publish() }
    }

    func trimAfterSelected() {
        if let e = route {
            if let i = e.selected { edited(e.endAt(i)) }
            return
        }
        if editor.trimAfterSelected() { publish() }
    }

    /// « Couper en deux ici »: the stored track is cut at the selected vertex into two tracks at once (the first keeps
    /// the name and the style, the second is named after it), and the drawing closes.
    func splitAtSelected() {
        guard case let .track(id, name, segments, index) = target else { return }
        guard let second = editor.splitAtSelected() else {
            app.say("Choisissez d'abord un point à l'intérieur de la trace.")
            return
        }
        guard let repository = app.routing.libraryRepository else {
            app.notice = "L'enregistrement a échoué."
            return
        }
        var all = segments
        if all.indices.contains(index) {
            all[index] = editor.points
        }
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let newName: String? = trimmed.isEmpty ? nil : "\(trimmed) (2)"
        let first = all
        Task { @MainActor [weak self] in
            let saved = await DrawController.split(id, first, second, newName, repository)
            guard let self else { return }
            if saved {
                self.app.say("Trace coupée en deux.")
                self.finish()
            } else {
                self.app.notice = "L'enregistrement a échoué."
            }
        }
    }

    private static func split(
        _ id: Int64,
        _ first: [[TrackPoint]],
        _ second: [TrackPoint],
        _ name: String?,
        _ repository: TrackRepository
    ) async -> Bool {
        guard (try? repository.replaceSegments(id, first)) != nil else { return false }
        let style = (try? repository.loadTrack(id))?.style ?? PathStyle()
        return (try? repository.createTrack(name, segments: [second], style: style)) != nil
    }

    func clear() {
        if let e = route {
            edited(e.clear())
            return
        }
        if editor.clear() { publish() }
    }

    // MARK: - Computed routes (P19)

    /// Selects stop `index` (a line of « Étapes »).
    func selectStop(_ index: Int?) {
        guard let e = route else { return }
        e.select(index)
        publish()
    }

    /// « Suivre les chemins »: on, a route drawn by hand follows the ways from then on (its legs are computed again);
    /// off, the next legs are drawn by hand.
    func setRouting(_ on: Bool) {
        guard let e = route else { return }
        e.routing = on
        if on, !e.plan.legs.isEmpty, e.plan.isManual {
            routeAllNow()
            return
        }
        freehand = false
        publish()
    }

    /// Every leg drawn by hand follows the ways with the current profile.
    func routeAllNow() {
        guard let e = route else { return }
        e.routing = true
        freehand = false
        if !edited(e.routeAll(e.profile)) { publish() }
    }

    func setAtStart(_ on: Bool) {
        guard route != nil else { return }
        atStart = on
        publish()
    }

    /// The profile of the chip; with `allLegs`, the legs already there take it too.
    func setProfile(_ newProfile: String, allLegs: Bool) {
        guard let e = route else { return }
        let name = newProfile.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return }
        previousProfile = e.profile
        let changed = e.setProfile(name, allLegs: allLegs)
        app.settingsStore.update { s in
            let known = RoutingProfiles.builtIn + s.placemarks.routingProfiles
            if !known.contains(name) {
                s.placemarks.routingProfiles.append(name)
            }
            s.placemarks.routingProfile = name
        }
        if !edited(changed) { publish() }
    }

    func setLegProfile(_ leg: Int, _ newProfile: String) {
        guard let e = route else { return }
        let name = newProfile.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return }
        if e.plan.legs.indices.contains(leg) {
            previousProfile = e.plan.legs[leg].profile
        }
        edited(e.setLegProfile(leg, name))
    }

    func setLegMode(_ leg: Int, _ mode: LegMode) {
        edited(route?.setLegMode(leg, mode) == true)
    }

    /// « Recalculer » a leg BRouter could not compute.
    func recompute(_ leg: Int) {
        edited(route?.recompute(leg) == true)
    }

    /// A stop's name and icon (« Nommer… », « Modifier… »); an empty name makes it a passage again.
    func nameStop(_ index: Int, _ name: String?, _ sym: String?) {
        guard let e = route, e.plan.stops.indices.contains(index) else { return }
        let old = e.plan.stops[index]
        let clean = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let stop: RouteStop
        if clean.isEmpty {
            stop = old.discreet()
        } else {
            stop = RouteStop(lat: old.lat, lon: old.lon, kind: .named, name: clean, sym: sym)
        }
        edited(e.setStop(index, stop))
    }

    /// « Rendre discrète ».
    func makeDiscreet(_ index: Int) {
        guard let e = route, e.plan.stops.indices.contains(index) else { return }
        edited(e.setStop(index, e.plan.stops[index].discreet()))
    }

    /// « Enregistrer comme waypoint » of a stop: a waypoint of the library there, with its name and icon.
    func saveStopAsWaypoint(_ index: Int) {
        guard let current = route?.plan, current.stops.indices.contains(index) else { return }
        let stop = current.stops[index]
        let at = current.stopIndices[index]
        let ele = current.geometry.indices.contains(at) ? current.geometry[at].ele : nil
        saveWaypoint(Waypoint(lat: stop.lat, lon: stop.lon, ele: ele, name: stop.name, sym: stop.sym))
    }

    /// « Enregistrer comme waypoint » of a point of interest: a copy in the library.
    func savePoiAsWaypoint(_ index: Int) {
        guard let current = route?.plan, current.pois.indices.contains(index) else { return }
        let poi = current.pois[index]
        saveWaypoint(Waypoint(lat: poi.lat, lon: poi.lon, name: poi.name, sym: poi.sym))
    }

    private func saveWaypoint(_ waypoint: Waypoint) {
        guard let repository = app.routing.libraryRepository else {
            app.notice = "L'enregistrement a échoué."
            return
        }
        Task { @MainActor [weak self] in
            let saved = await DrawController.createWaypoint(waypoint, repository)
            if saved {
                self?.app.say("Waypoint enregistré.")
            } else {
                self?.app.notice = "L'enregistrement a échoué."
            }
        }
    }

    private static func createWaypoint(_ waypoint: Waypoint, _ repository: TrackRepository) async -> Bool {
        (try? repository.createWaypoint(waypoint)) != nil
    }

    func deleteStop(_ index: Int) {
        edited(route?.delete(index) == true)
    }

    /// How many stops « Partir d'ici » on stop `index` would remove (none on a loop).
    func stopsDroppedBy(_ index: Int) -> Int {
        route?.stopsDroppedBy(index) ?? 0
    }

    func startFrom(_ index: Int) {
        edited(route?.startFrom(index) == true)
    }

    func loopToStart() {
        edited(route?.loopToStart() == true)
    }

    func outAndBack() {
        edited(route?.outAndBack() == true)
    }

    /// The cursor of the elevation profile, as a distance from the start; nil hides it.
    func setCursor(_ distanceMeters: Double?) {
        guard let current = plan, let d = distanceMeters else {
            cursor = nil
            setFeature(EditLayers.cursorId, nil)
            return
        }
        let point = TrackPath([current.geometry]).interpolate(d)
        cursor = point
        if let p = point {
            setFeature(EditLayers.cursorId, RouteGeo.point(p.lat, p.lon, color: "#ff6f00"))
        } else {
            setFeature(EditLayers.cursorId, nil)
        }
    }

    // MARK: - A stop in hand (« Prendre l'étape »)

    /// Stop `index` follows the crosshair until « Poser ici ».
    func take(_ index: Int) {
        guard let e = route, e.plan.stops.indices.contains(index) else { return }
        let c = app.center
        e.select(index)
        taken = index
        takenAt = TrackPoint(lat: c.lat, lon: c.lon)
        publish()
    }

    /// « Poser ici »: the stop taken goes to the crosshair, and its legs are computed again.
    func putDown() {
        guard let e = route, let i = taken else { return }
        let c = app.center
        taken = nil
        takenAt = nil
        if !edited(e.move(i, c.lat, c.lon)) { publish() }
    }

    /// « Annuler » (or ✕) while a stop is taken: it stays where it was.
    func cancelTake() {
        guard taken != nil else { return }
        taken = nil
        takenAt = nil
        publish()
    }

    // MARK: - Zones to avoid (P19.8, P30.4)

    /// Outils ▸ « Éviter une zone »: a circle of the last radius follows the crosshair until it is placed.
    func startZone() {
        guard route != nil else { return }
        let c = app.center
        taken = nil
        takenAt = nil
        let last = app.settings.placemarks.routingAvoidRadius
        zoneRadius = last > 0 ? last : 250
        placingAt = TrackPoint(lat: c.lat, lon: c.lon)
        publish()
    }

    /// One of the radii of the chips: kept for the next zone.
    func setZoneRadius(_ meters: Int) {
        guard zoneRadius != nil, meters > 0 else { return }
        zoneRadius = meters
        app.settingsStore.update { $0.placemarks.routingAvoidRadius = meters }
        publish()
    }

    /// « Éviter cette zone »: the circle is placed at the crosshair, and the legs that cross it go round it.
    func placeZone() {
        guard let e = route, let r = zoneRadius else { return }
        let c = app.center
        zoneRadius = nil
        placingAt = nil
        if !edited(e.addZone(AvoidZone(lat: c.lat, lon: c.lon, radiusMeters: r))) { publish() }
    }

    func cancelZone() {
        guard zoneRadius != nil else { return }
        zoneRadius = nil
        placingAt = nil
        publish()
    }

    /// « Retirer la zone ».
    func removeZone(_ index: Int) {
        hoveredZone = nil
        edited(route?.removeZone(index) == true)
    }

    /// The handle of zone `index` is pulled to `radius` meters: the circle follows, the route pales.
    func previewZoneSize(_ index: Int, _ radius: Int) {
        guard let e = route, e.plan.zones.indices.contains(index) else { return }
        resizingZone = index
        resizingRadius = radius
        let lines = RoutePlanGeoJson.lines(e.plan, RouteText.computing, e.routing ? RouteText.byHand : "", pale: true)
        setFeature(EditLayers.lineId, lines)
        setFeature(EditLayers.zonesId, zonesGeoJson(e))
    }

    /// The handle let go at `radius` meters: the zone keeps its centre and takes that size (one « Annuler »), which is
    /// kept for the next zone; the legs it touches are computed again.
    func resizeZone(_ index: Int, _ radius: Int) {
        resizingZone = nil
        resizingRadius = nil
        guard let e = route, e.plan.zones.indices.contains(index) else { return }
        let zone = e.plan.zones[index]
        if radius <= 0 || radius == zone.radiusMeters {
            publish()
            return
        }
        app.settingsStore.update { $0.placemarks.routingAvoidRadius = radius }
        if !edited(e.replaceZone(index, zone.copy(radiusMeters: radius))) { publish() }
    }

    // MARK: - Points of interest (P30.1)

    /// « Poser : Étape | Point d'intérêt ».
    func setPoiMode(_ on: Bool) {
        guard poiMode != on else { return }
        poiMode = on
        publish()
    }

    /// « Ajouter » of « Quel point ? »: a point of interest at `position`; the way does not change.
    func addPoi(_ position: LonLat, name: String?, sym: String?) {
        guard let e = route else { return }
        let clean = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        edited(e.addPoi(RoutePoi(lat: position.lat, lon: position.lon, name: clean.isEmpty ? nil : clean, sym: sym)))
    }

    /// « OK » of « Quel point ? » on point of interest `index`: its name and its icon.
    func editPoi(_ index: Int, name: String?, sym: String?) {
        guard let e = route, e.plan.pois.indices.contains(index) else { return }
        let poi = e.plan.pois[index]
        let clean = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        edited(e.replacePoi(index, RoutePoi(lat: poi.lat, lon: poi.lon, name: clean.isEmpty ? nil : clean, sym: sym)))
    }

    func removePoi(_ index: Int) {
        movingPoi = nil
        movingPoiAt = nil
        if !edited(route?.removePoi(index) == true) { publish() }
    }

    /// « Déplacer » of point of interest `index`: it follows the crosshair until « Poser ici ».
    func takePoi(_ index: Int) {
        guard let e = route, e.plan.pois.indices.contains(index) else { return }
        let c = app.center
        taken = nil
        takenAt = nil
        zoneRadius = nil
        placingAt = nil
        movingPoi = index
        movingPoiAt = TrackPoint(lat: c.lat, lon: c.lon)
        publish()
    }

    /// « Poser ici » of a point of interest (one « Annuler »).
    func putDownPoi() {
        guard let e = route, let i = movingPoi else { return }
        let c = app.center
        movingPoi = nil
        movingPoiAt = nil
        guard e.plan.pois.indices.contains(i) else {
            publish()
            return
        }
        let poi = e.plan.pois[i]
        let moved = RoutePoi(lat: c.lat, lon: c.lon, name: poi.name, sym: poi.sym)
        if !edited(e.replacePoi(i, moved)) { publish() }
    }

    func cancelMovePoi() {
        guard movingPoi != nil else { return }
        movingPoi = nil
        movingPoiAt = nil
        publish()
    }

    // MARK: - Saving

    /// The default name of a new line: « Tracé du 29 oct. 2026 à 17:02 », « Itinéraire du … ».
    func defaultName() -> String {
        let date = NameDates.format(Int64(Date().timeIntervalSince1970 * 1000))
        return isRoute ? "Itinéraire du \(date)" : "Tracé du \(date)"
    }

    /// A new line asks its name first; an edited one keeps its own.
    var needsName: Bool {
        target == .newTrack || target == .newRoute
    }

    /// Stores the line; `name` is used for a new one.
    func save(name: String?) {
        guard let current = target else { return }
        if let e = route {
            saveRoute(current, e.plan, name)
            return
        }
        let points = editor.points
        guard points.count >= 2 else {
            app.say("Il faut au moins deux points.")
            return
        }
        guard let repository = app.routing.libraryRepository else {
            app.notice = "L'enregistrement a échoué."
            return
        }
        let color = app.settings.placemarks.defaultColor
        Task { @MainActor [weak self] in
            let saved = await DrawController.storeTrack(current, points, name, color, repository)
            self?.afterSave(saved)
        }
    }

    private static func storeTrack(
        _ target: DrawTarget,
        _ points: [TrackPoint],
        _ name: String?,
        _ color: Int32,
        _ repository: TrackRepository
    ) async -> Bool {
        switch target {
        case .newTrack:
            return (try? repository.createTrack(name, segments: [points], style: PathStyle(colorArgb: color))) != nil
        case let .track(id, _, segments, index):
            var all = segments
            if all.indices.contains(index) {
                all[index] = points
            }
            return (try? repository.replaceSegments(id, all)) != nil
        case .newRoute, .route:
            return false
        }
    }

    /// A route keeps its plan in its GPX extensions (ADR-065), except one drawn wholly by hand without zones nor points
    /// of interest: it is stored as before, its points being its stops.
    private func saveRoute(_ current: DrawTarget, _ toSave: RoutePlan, _ name: String?) {
        guard toSave.stops.count >= 2 else {
            app.say("Il faut au moins deux points.")
            return
        }
        guard let repository = app.routing.libraryRepository else {
            app.notice = "L'enregistrement a échoué."
            return
        }
        let manual = toSave.isManual && toSave.zones.isEmpty && toSave.pois.isEmpty
        var original: [Waypoint] = []
        var oldExtras: [RawXml] = []
        var existing: Int64?
        if case let .route(id, _, points, extras) = current {
            original = points
            oldExtras = extras
            existing = id
        }
        let fresh = manual ? RoutePlanGpx.stopPoints(toSave) : RoutePlanGpx.routePoints(toSave)
        let points = DrawController.keepKnown(fresh, original)
        let extras = RoutePlanGpx.attach(oldExtras, manual ? nil : toSave)
        let routeId = existing
        Task { @MainActor [weak self] in
            let saved = await DrawController.storeRoute(routeId, name, points, extras, repository)
            self?.afterSave(saved)
        }
    }

    private static func storeRoute(
        _ id: Int64?,
        _ name: String?,
        _ points: [Waypoint],
        _ extras: [RawXml],
        _ repository: TrackRepository
    ) async -> Bool {
        if let routeId = id {
            return (try? repository.replaceRoutePoints(routeId, points, extras: extras)) != nil
        }
        return (try? repository.createRoute(name, points, extras: extras)) != nil
    }

    private func afterSave(_ saved: Bool) {
        if saved {
            app.say("Enregistré.")
            finish()
        } else {
            app.notice = "L'enregistrement a échoué."
        }
    }

    /// Points that stayed where they were keep their name, notes and altitude; moved or new ones start bare.
    private static func keepKnown(_ points: [Waypoint], _ before: [Waypoint]) -> [Waypoint] {
        if before.isEmpty { return points }
        var known: [PointKey: Waypoint] = [:]
        for w in before {
            let key = PointKey(lat: w.lat, lon: w.lon)
            if known[key] == nil {
                known[key] = w
            }
        }
        return points.map { p in
            guard let old = known[PointKey(lat: p.lat, lon: p.lon)] else { return p }
            return Waypoint(
                lat: old.lat,
                lon: old.lon,
                ele: p.ele ?? old.ele,
                timeMillis: old.timeMillis,
                name: p.name ?? old.name,
                cmt: old.cmt,
                desc: old.desc,
                src: old.src,
                links: old.links,
                sym: p.sym ?? old.sym,
                type: old.type,
                extras: old.extras,
                folder: old.folder
            )
        }
    }

    private struct PointKey: Hashable {
        let lat: Double
        let lon: Double
    }

    /// A route drawn by hand, or imported: every point is a stop, joined to the next by a straight leg.
    static func manualPlan(_ points: [Waypoint]) -> RoutePlan {
        var stops: [RouteStop] = []
        for w in points {
            let named = !(w.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            if named {
                stops.append(RouteStop(lat: w.lat, lon: w.lon, kind: .named, name: w.name, sym: w.sym))
            } else {
                stops.append(RouteStop(lat: w.lat, lon: w.lon))
            }
        }
        var legs: [RouteLeg] = []
        if points.count >= 2 {
            for i in 1 ..< points.count {
                let a = points[i - 1]
                let b = points[i]
                let line = [TrackPoint(lat: a.lat, lon: a.lon, ele: a.ele), TrackPoint(lat: b.lat, lon: b.lon, ele: b.ele)]
                legs.append(RouteLeg(profile: RoutingProfiles.hiking, mode: .straight, state: .ok, points: line))
            }
        }
        return RoutePlan(stops: stops, legs: legs)
    }

    /// Debug only (`-ToolMapDemo YES`, CI screenshots): three stops around the default centre, joined by hand, with
    /// elevations, so that the panel of the computed routes shows its figures without a call to BRouter.
    static func demoPlan() -> RoutePlan {
        let a = RouteStop(lat: 45.5598, lon: 5.9062)
        let b = RouteStop(lat: 45.5688, lon: 5.9171, kind: .named, name: "Refuge du Pré", sym: "Alpinehut")
        let c = RouteStop(lat: 45.5631, lon: 5.9302)
        let ab = [TrackPoint(lat: a.lat, lon: a.lon, ele: 612), TrackPoint(lat: b.lat, lon: b.lon, ele: 846)]
        let bc = [TrackPoint(lat: b.lat, lon: b.lon, ele: 846), TrackPoint(lat: c.lat, lon: c.lon, ele: 703)]
        let legs = [
            RouteLeg(profile: RoutingProfiles.hiking, mode: .straight, state: .ok, points: ab),
            RouteLeg(profile: RoutingProfiles.hiking, mode: .straight, state: .ok, points: bc),
        ]
        return RoutePlan(stops: [a, b, c], legs: legs)
    }

    // MARK: - Publishing

    /// After an edit of the route: a new message may come, and the legs it touched are computed.
    @discardableResult
    private func edited(_ changed: Bool) -> Bool {
        if !changed { return false }
        noticeGiven = false
        // Any edit lets go of a stop or a point of interest in hand: the indices may have changed.
        taken = nil
        takenAt = nil
        movingPoi = nil
        movingPoiAt = nil
        publish()
        return true
    }

    private func publish() {
        if let e = route {
            publishRoute(e)
            return
        }
        let points = editor.points
        pointCount = points.count
        selected = editor.selected
        canUndo = editor.canUndo
        canRedo = editor.canRedo
        lengthMeters = TrackOps.length(points)
        dirty = points != savedPoints
        plan = nil
        let handles = EditorGeoJson.handles(points, selected: editor.selected)
        show(line: EditLayers.trackLine(points), handles: handles, elastic: nil, zones: nil)
    }

    private func publishRoute(_ e: RoutePlanEditor) {
        e.applyElevations(elevations)
        savedPlan = elevations.apply(savedPlan)
        let current = e.plan
        let newSummary = RouteSummary.of(current, e.profile)
        var list: [String] = []
        let candidates = RoutingProfiles.builtIn + app.settings.placemarks.routingProfiles
        for p in candidates where !list.contains(p) {
            list.append(p)
        }
        if !list.contains(e.profile) {
            list.append(e.profile)
        }
        if let h = hoveredZone, !current.zones.indices.contains(h) {
            hoveredZone = nil
        }
        let byHand = e.routing ? RouteText.byHand : ""
        let lines = RoutePlanGeoJson.lines(current, RouteText.computing, byHand, taken: taken)
        var elastic: String?
        if let t = taken, let at = takenAt {
            elastic = RoutePlanGeoJson.elastic(current, t, at)
        }
        show(line: lines, handles: handlesGeoJson(e), elastic: elastic, zones: zonesGeoJson(e))
        plan = current
        pointCount = current.stops.count
        selected = e.selected
        canUndo = e.canUndo
        canRedo = e.canRedo
        lengthMeters = newSummary.lengthMeters
        summary = newSummary
        dirty = current != savedPlan
        routing = e.routing
        profile = e.profile
        profiles = list
        schedule()
        elevate()
    }

    private func handlesGeoJson(_ e: RoutePlanEditor) -> String {
        let moved = taken != nil ? takenAt : nil
        return RoutePlanGeoJson.handles(
            e.plan,
            selected: e.selected,
            midpoints: !e.routing,
            moved: moved,
            poiMoved: movingPoi,
            poiAt: movingPoiAt
        )
    }

    private func zonesGeoJson(_ e: RoutePlanEditor) -> String {
        var ring: AvoidZone?
        if let r = zoneRadius, let at = placingAt, r > 0 {
            ring = AvoidZone(lat: at.lat, lon: at.lon, radiusMeters: r)
        }
        let hovered = resizingZone ?? hoveredZone
        return RoutePlanGeoJson.zones(e.plan, placing: ring, hovered: hovered, hoverRadius: resizingRadius)
    }

    private func show(line: String?, handles: String?, elastic: String?, zones: String?) {
        var features = app.mapFeatures
        features[EditLayers.lineId] = line
        if let text = handles {
            features[EditLayers.handlesId] = EditLayers.prefixIcons(text)
        } else {
            features[EditLayers.handlesId] = nil
        }
        features[EditLayers.elasticId] = elastic
        features[EditLayers.zonesId] = zones
        if features != app.mapFeatures {
            app.mapFeatures = features
        }
    }

    private func setFeature(_ id: String, _ text: String?) {
        if app.mapFeatures[id] != text {
            app.mapFeatures[id] = text
        }
    }

    /// Asks BRouter for the legs still waiting for it.
    private func schedule() {
        guard let e = route else { return }
        let current = e.plan
        var requests: [LegRequest] = []
        for i in current.pending {
            requests.append(LegRequest(key: current.key(i), from: current.stops[i], to: current.stops[i + 1], zones: current.legZones(i)))
        }
        app.routing.submitLegs(requests) { [weak self] key, result in
            self?.onLegResult(key, result)
        }
    }

    private func onLegResult(_ key: LegKey, _ result: LegResult) {
        guard let e = route else { return }
        switch result {
        case .ok:
            e.applyResult(key, result)
        case let .failed(failed):
            let offline = app.routing.isNetworkFailure(failed)
            if failed.error == .unknownProfile, key.profile != RoutingProfiles.hiking {
                // BRouter answers so for a profile it lacks.
                let fallback = previousProfile != key.profile ? previousProfile : RoutingProfiles.hiking
                e.replaceProfile(key.profile, fallback)
                if e.profile == fallback {
                    app.settingsStore.update { $0.placemarks.routingProfile = fallback }
                }
                notice(.unknownProfile(key.profile))
            } else {
                let reason: RoutingError = failed.error == .unknownProfile ? .noData : failed.error
                e.applyResult(key, .failed(LegResult.Failed(reason, failed.message)))
                if app.settings.placemarks.routingNoData == .allByHand, e.routing {
                    e.routing = false
                    notice(.allByHand(reason, offline: offline))
                } else if e.routing {
                    notice(.legByHand(reason, offline: offline))
                }
            }
        }
        publish()
    }

    private func notice(_ message: RouteNotice) {
        if noticeGiven { return }
        noticeGiven = true
        let text = RouteText.notice(message)
        if case .legByHand = message {
            app.say(text, action: "Tout dessiner à la main") { [weak self] in
                self?.setRouting(false)
            }
        } else {
            app.say(text)
        }
    }

    /// Gives the legs drawn by hand the elevations of the terrain tiles, all in one go; the route is published again
    /// with them, and asks for the next ones.
    private func elevate() {
        guard !elevating, let e = route else { return }
        let wanted = elevations.wanted(e.plan)
        if wanted.isEmpty { return }
        elevating = true
        let keys = wanted.map { $0.key }
        let lists = wanted.map { $0.points }
        Task { @MainActor [weak self] in
            let filled = await TerrainElevations.fill(lists)
            guard let self else { return }
            self.elevating = false
            for (i, key) in keys.enumerated() where i < filled.count {
                self.elevations.put(key, filled[i])
            }
            if self.route === e {
                self.publish()
            }
        }
    }
}
