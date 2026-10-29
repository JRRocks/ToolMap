// Port of core/src/main/kotlin/fr/toolmap/core/route/RoutePlanEditor.kt.

import Foundation

/// What the route editor shows: the plan and the stop the user picked, if any.
public struct RouteEditState: Hashable, Sendable {
    public let plan: RoutePlan
    public let selected: Int?

    public init(plan: RoutePlan = RoutePlan(), selected: Int? = nil) {
        self.plan = plan
        self.selected = selected
    }
}

/// The editing of a route made of stops (P19, `docs/specs/routing.md` §5.3): add at the end or at the start, insert on
/// a leg, move, delete, leg by hand or routed, profile of a leg, reverse, loop, out and back, start from a stop, zones
/// to avoid (P19.8), with undo and redo. Every edit returns whether it changed anything; the legs it touches are
/// `.pending` (unless `cache` knows them) and the caller hands them to BRouter, then gives the answers back with
/// `applyResult`. Not thread-safe: used from the main thread.
public final class RoutePlanEditor {
    public static let defaultHistory = 200

    /// Ends of a computed way closer than this to the stops are put on them; farther ones are joined to them.
    private static let snapMeters = 0.5

    public let cache: LegCache
    private let maxHistory: Int
    private var undoStack: [RouteEditState] = []
    private var redoStack: [RouteEditState] = []

    public private(set) var state: RouteEditState

    public var plan: RoutePlan { state.plan }
    public var selected: Int? { state.selected }
    public var canUndo: Bool { !undoStack.isEmpty }
    public var canRedo: Bool { !redoStack.isEmpty }

    /// Profile of the new legs.
    public var profile: String = RoutingProfiles.hiking

    /// New legs follow the ways (« Suivre les chemins »); off, they are drawn by hand.
    public var routing: Bool = true

    public init(_ initial: RoutePlan = RoutePlan(), cache: LegCache = LegCache(), maxHistory: Int = RoutePlanEditor.defaultHistory) {
        self.cache = cache
        self.maxHistory = maxHistory
        state = RouteEditState(plan: initial)
    }

    /// Replaces the whole plan (opening a route); clears the history.
    public func load(_ plan: RoutePlan) {
        undoStack.removeAll()
        redoStack.removeAll()
        state = RouteEditState(plan: plan)
    }

    /// Selection is not an edit: it is not undoable on its own.
    public func select(_ index: Int?) {
        var valid: Int?
        if let wanted = index, plan.stops.indices.contains(wanted) { valid = wanted }
        state = RouteEditState(plan: state.plan, selected: valid)
    }

    /// Adds a stop: after the selected one, else at the end; with `atStart`, before the selected one, else at the start.
    /// The new stop becomes selected.
    @discardableResult
    public func add(_ stop: RouteStop, atStart: Bool = false) -> Bool {
        let stops = plan.stops
        if stops.isEmpty { return change(plan.with([stop], []), 0) }
        let index: Int
        if atStart {
            index = selected ?? 0
        } else if let current = selected {
            index = current + 1
        } else {
            index = stops.count
        }
        return insertAt(index, stop)
    }

    /// Inserts a stop on leg `leg` (a detour through that place): the leg becomes two, with its profile and mode.
    @discardableResult
    public func insertOnLeg(_ leg: Int, _ stop: RouteStop) -> Bool {
        guard plan.legs.indices.contains(leg) else { return false }
        return insertAt(leg + 1, stop)
    }

    private func insertAt(_ index: Int, _ stop: RouteStop) -> Bool {
        let s = plan.stops
        var stops = s
        stops.insert(stop, at: index)
        var legs = plan.legs
        if index == 0 {
            legs.insert(newLeg(stop, s[0]), at: 0)
        } else if index == s.count {
            legs.append(newLeg(s[s.count - 1], stop))
        } else {
            let old = legs[index - 1]
            legs[index - 1] = newLeg(s[index - 1], stop, profile: old.profile, mode: old.mode)
            legs.insert(newLeg(stop, s[index], profile: old.profile, mode: old.mode), at: index)
        }
        return change(plan.with(stops, legs), index)
    }

    /// Puts the selected stop somewhere else; the legs on both sides are computed again.
    @discardableResult
    public func moveSelected(_ lat: Double, _ lon: Double) -> Bool {
        guard let i = selected else { return false }
        return move(i, lat, lon)
    }

    /// Puts stop `i` somewhere else (« Poser ici » of a stop taken at the crosshair); it becomes the selected one.
    @discardableResult
    public func move(_ i: Int, _ lat: Double, _ lon: Double) -> Bool {
        guard plan.stops.indices.contains(i) else { return false }
        var stops = plan.stops
        stops[i] = stops[i].copy(lat: lat, lon: lon)
        var legs = plan.legs
        if i > 0 { legs[i - 1] = rebuild(legs[i - 1], stops[i - 1], stops[i]) }
        if i < legs.count { legs[i] = rebuild(legs[i], stops[i], stops[i + 1]) }
        return change(plan.with(stops, legs), i)
    }

    @discardableResult
    public func deleteSelected() -> Bool {
        guard let i = selected else { return false }
        return delete(i)
    }

    /// Removes stop `i`: the two legs around it become one, with the profile and the mode of the first.
    @discardableResult
    public func delete(_ i: Int) -> Bool {
        let s = plan.stops
        guard s.indices.contains(i) else { return false }
        var stops = s
        stops.remove(at: i)
        var legs = plan.legs
        if s.count == 1 {
            // The last stop goes: there was no leg.
        } else if i == 0 {
            legs.remove(at: 0)
        } else if i == s.count - 1 {
            legs.removeLast()
        } else {
            let before = legs[i - 1]
            legs[i - 1] = newLeg(s[i - 1], s[i + 1], profile: before.profile, mode: before.mode)
            legs.remove(at: i)
        }
        return change(plan.with(stops, legs), stops.isEmpty ? nil : Swift.min(i, stops.count - 1))
    }

    /// Leg `leg` drawn by hand (off the ways) or routed again.
    @discardableResult
    public func setLegMode(_ leg: Int, _ mode: LegMode) -> Bool {
        guard plan.legs.indices.contains(leg) else { return false }
        let old = plan.legs[leg]
        if old.mode == mode { return false }
        return replaceLeg(leg, newLeg(plan.stops[leg], plan.stops[leg + 1], profile: old.profile, mode: mode))
    }

    /// Leg `leg` routed with its own profile.
    @discardableResult
    public func setLegProfile(_ leg: Int, _ profile: String) -> Bool {
        guard plan.legs.indices.contains(leg) else { return false }
        let old = plan.legs[leg]
        if old.profile == profile && old.mode == .routed { return false }
        return replaceLeg(leg, newLeg(plan.stops[leg], plan.stops[leg + 1], profile: profile, mode: .routed))
    }

    /// The profile of the new legs and, with `allLegs`, of every routed leg already there.
    @discardableResult
    public func setProfile(_ profile: String, allLegs: Bool) -> Bool {
        self.profile = profile
        if !allLegs { return false }
        var changed = false
        var legs: [RouteLeg] = []
        for (i, leg) in plan.legs.enumerated() {
            if leg.mode != .routed || leg.profile == profile {
                legs.append(leg)
            } else {
                changed = true
                legs.append(newLeg(plan.stops[i], plan.stops[i + 1], profile: profile, mode: .routed))
            }
        }
        return changed ? change(plan.with(plan.stops, legs), selected) : false
    }

    /// Asks BRouter again for a leg it could not compute (the data may have been downloaded since).
    @discardableResult
    public func recompute(_ leg: Int) -> Bool {
        guard plan.legs.indices.contains(leg) else { return false }
        let old = plan.legs[leg]
        if old.state != .noData && old.state != .failed { return false }
        return replaceLeg(leg, newLeg(plan.stops[leg], plan.stops[leg + 1], profile: old.profile, mode: .routed))
    }

    private func replaceLeg(_ index: Int, _ leg: RouteLeg) -> Bool {
        var legs = plan.legs
        legs[index] = leg
        return change(plan.with(plan.stops, legs), selected)
    }

    /// Names a stop, makes it discreet, or gives it a waypoint's name and icon: its place does not change.
    @discardableResult
    public func setStop(_ index: Int, _ stop: RouteStop) -> Bool {
        guard plan.stops.indices.contains(index) else { return false }
        let old = plan.stops[index]
        if old == stop { return false }
        var stops = plan.stops
        stops[index] = stop.copy(lat: old.lat, lon: old.lon)
        return change(plan.with(stops, plan.legs), selected)
    }

    /// The other way round. Routed legs are computed again (one-way streets, slopes); legs by hand are just turned.
    @discardableResult
    public func reverse() -> Bool {
        let s = plan.stops
        if s.count < 2 { return false }
        let stops = Array(s.reversed())
        var legs: [RouteLeg] = []
        for (i, leg) in plan.legs.reversed().enumerated() {
            if leg.mode == .straight {
                legs.append(leg.copy(points: Array(leg.points.reversed())))
            } else {
                legs.append(newLeg(stops[i], stops[i + 1], profile: leg.profile, mode: .routed))
            }
        }
        return change(plan.with(stops, legs), selected.map { s.count - 1 - $0 })
    }

    /// Back to the start: a copy of the first stop at the end.
    @discardableResult
    public func loopToStart() -> Bool {
        let s = plan.stops
        if s.count < 2 || plan.isLoop { return false }
        return insertAt(s.count, s[0].discreet())
    }

    /// Out and back: the stops again in reverse order, each leg back with the profile and the mode it came with.
    @discardableResult
    public func outAndBack() -> Bool {
        let s = plan.stops
        if s.count < 2 { return false }
        let back: [RouteStop] = s.dropLast().reversed().map { $0.discreet() }
        let stops = s + back
        var legs = plan.legs
        for (k, leg) in plan.legs.reversed().enumerated() {
            let from = stops[s.count - 1 + k]
            let to = stops[s.count + k]
            legs.append(newLeg(from, to, profile: leg.profile, mode: leg.mode))
        }
        return change(plan.with(stops, legs), stops.count - 1)
    }

    /// How many stops `startFrom` removes: none on a loop, which turns instead.
    public func stopsDroppedBy(_ index: Int) -> Int {
        plan.isLoop ? 0 : Swift.max(index, 0)
    }

    /// The route starts at stop `index`: a loop turns round (nothing is computed again), any other route loses the stops
    /// before it.
    @discardableResult
    public func startFrom(_ index: Int) -> Bool {
        let s = plan.stops
        guard index >= 1, index < s.count else { return false }
        if plan.isLoop {
            if index == s.count - 1 { return false }
            let stops = Array(s[index ..< s.count - 1]) + Array(s[0 ... index])
            let legs = Array(plan.legs[index...]) + Array(plan.legs[0 ..< index])
            return change(plan.with(stops, legs), 0)
        }
        return change(plan.with(Array(s[index...]), Array(plan.legs[index...])), 0)
    }

    /// Drops the stops before `index`, loop or not (« Trim before » of the drawing by hand).
    @discardableResult
    public func trimBefore(_ index: Int) -> Bool {
        let s = plan.stops
        guard index >= 1, index < s.count else { return false }
        return change(plan.with(Array(s[index...]), Array(plan.legs[index...])), 0)
    }

    /// Drops the stops after `index` (« Trim after » of the drawing by hand).
    @discardableResult
    public func endAt(_ index: Int) -> Bool {
        let s = plan.stops
        guard index >= 0, index < s.count - 1 else { return false }
        return change(plan.with(Array(s[0 ... index]), Array(plan.legs[0 ..< index])), index)
    }

    /// Thins the stops by Douglas-Peucker, named stops kept (for a route drawn by hand, or before following the ways).
    /// Where stops go, the legs around them become one.
    @discardableResult
    public func simplify(_ toleranceMeters: Double) -> Bool {
        let s = plan.stops
        if s.count < 3 { return false }
        // Kotlin tells the kept stops by the identity of their points; here the index of each stop rides in the time of
        // its point, which the simplification does not look at.
        var points: [TrackPoint] = []
        points.reserveCapacity(s.count)
        for (i, stop) in s.enumerated() {
            points.append(TrackPoint(lat: stop.lat, lon: stop.lon, timeMillis: Int64(i)))
        }
        let kept = PathSimplifier.simplify(points, toleranceMeters)
        var keep = [Bool](repeating: false, count: s.count)
        for p in kept {
            if let t = p.timeMillis, t >= 0, t < Int64(s.count) { keep[Int(t)] = true }
        }
        for (i, stop) in s.enumerated() where stop.kind == .named {
            keep[i] = true
        }
        keep[0] = true
        keep[s.count - 1] = true
        let indices = s.indices.filter { keep[$0] }
        if indices.count == s.count { return false }
        let stops = indices.map { s[$0] }
        var legs: [RouteLeg] = []
        for k in 1 ..< indices.count {
            let a = indices[k - 1]
            let b = indices[k]
            if b == a + 1 {
                legs.append(plan.legs[a])
            } else {
                let straight = (a ..< b).allSatisfy { plan.legs[$0].mode == .straight }
                legs.append(newLeg(s[a], s[b], profile: plan.legs[a].profile, mode: straight ? .straight : .routed))
            }
        }
        return change(plan.with(stops, legs), nil)
    }

    /// A finger stroke drawn by hand: its thinned points become passages linked by straight legs.
    @discardableResult
    public func addStroke(_ stroke: [TrackPoint], _ toleranceMeters: Double) -> Bool {
        let thinned = Freehand.thin(stroke, toleranceMeters)
        if thinned.isEmpty { return false }
        var stops = plan.stops
        var legs = plan.legs
        for p in thinned {
            let stop = RouteStop(lat: p.lat, lon: p.lon)
            if let last = stops.last { legs.append(RouteLeg.straight(last, stop, profile)) }
            stops.append(stop)
        }
        return change(plan.with(stops, legs), stops.count - 1)
    }

    /// Every leg drawn by hand follows the ways with `profile` (a route drawn by hand that is now routed).
    @discardableResult
    public func routeAll(_ profile: String) -> Bool {
        self.profile = profile
        if !plan.legs.contains(where: { $0.mode == .straight }) { return false }
        var legs: [RouteLeg] = []
        for (i, leg) in plan.legs.enumerated() {
            if leg.mode == .straight {
                legs.append(newLeg(plan.stops[i], plan.stops[i + 1], profile: profile, mode: .routed))
            } else {
                legs.append(leg)
            }
        }
        return change(plan.with(plan.stops, legs), selected)
    }

    @discardableResult
    public func clear() -> Bool {
        if plan.stops.isEmpty && plan.zones.isEmpty && plan.pois.isEmpty { return false }
        return change(RoutePlan(), nil)
    }

    // ---- Points of interest (P30.1, variant B of `docs/previews/p30-itineraires.html`): no leg changes ----

    /// Adds a point of interest; the way does not change.
    @discardableResult
    public func addPoi(_ poi: RoutePoi) -> Bool {
        change(plan.copy(pois: plan.pois + [poi]), selected)
    }

    /// Point of interest `index` renamed, given another icon or moved; the way does not change.
    @discardableResult
    public func replacePoi(_ index: Int, _ poi: RoutePoi) -> Bool {
        guard plan.pois.indices.contains(index) else { return false }
        if plan.pois[index] == poi { return false }
        var pois = plan.pois
        pois[index] = poi
        return change(plan.copy(pois: pois), selected)
    }

    @discardableResult
    public func removePoi(_ index: Int) -> Bool {
        guard plan.pois.indices.contains(index) else { return false }
        var pois = plan.pois
        pois.remove(at: index)
        return change(plan.copy(pois: pois), selected)
    }

    /// A zone the routed legs must go round (P19.8). Adding an obstacle off a way leaves it the best one: only the legs
    /// whose way crosses the zone are computed again.
    @discardableResult
    public func addZone(_ zone: AvoidZone) -> Bool {
        changeZones(plan.zones + [zone]) { $0.state == .ok && zone.crosses($0.points) }
    }

    /// Removes zone `index`: every routed leg is computed again (the ways already known come from `cache`).
    @discardableResult
    public func removeZone(_ index: Int) -> Bool {
        guard plan.zones.indices.contains(index) else { return false }
        var zones = plan.zones
        zones.remove(at: index)
        return changeZones(zones) { _ in true }
    }

    /// Zone `index` moved or resized. Grown around itself, it is only stricter: as for `addZone`, only the legs crossing
    /// it are computed again; otherwise every routed leg is.
    @discardableResult
    public func replaceZone(_ index: Int, _ zone: AvoidZone) -> Bool {
        guard plan.zones.indices.contains(index) else { return false }
        let old = plan.zones[index]
        if old == zone { return false }
        var zones = plan.zones
        zones[index] = zone
        if zone.covers(old) {
            return changeZones(zones) { $0.state == .ok && zone.crosses($0.points) }
        }
        return changeZones(zones) { _ in true }
    }

    /// The plan with `zones`: legs drawn by hand do not change, routed legs that are `affected` or still waiting are asked
    /// again (with the new zones), the others keep their way.
    private func changeZones(_ zones: [AvoidZone], _ affected: (RouteLeg) -> Bool) -> Bool {
        var legs: [RouteLeg] = []
        for (i, leg) in plan.legs.enumerated() {
            if leg.mode != .routed || (leg.state != .pending && !affected(leg)) {
                legs.append(leg)
            } else {
                legs.append(newLeg(plan.stops[i], plan.stops[i + 1], profile: leg.profile, mode: .routed, zones: zones))
            }
        }
        return change(plan.with(plan.stops, legs, zones: zones), selected)
    }

    /// The answer of BRouter for the legs of `key`: every leg still waiting for it takes it. This is not an edit (not
    /// undoable); a leg changed in between has another key and is left alone.
    @discardableResult
    public func applyResult(_ key: LegKey, _ result: LegResult) -> Bool {
        if case let .ok(ok) = result { cache.put(key, ok) }
        var changed = false
        let p = plan
        var legs: [RouteLeg] = []
        for (i, leg) in p.legs.enumerated() {
            if leg.state != .pending || p.key(i) != key {
                legs.append(leg)
                continue
            }
            changed = true
            switch result {
            case let .ok(ok):
                legs.append(RoutePlanEditor.legOf(p.stops[i], p.stops[i + 1], leg.profile, ok))
            case let .failed(failed):
                legs.append(leg.copy(state: failed.error == .noData ? .noData : .failed))
            }
        }
        if changed { state = RouteEditState(plan: p.copy(legs: legs), selected: state.selected) }
        return changed
    }

    /// A profile BRouter does not have: the legs that wanted it take `fallback` instead (not undoable either).
    @discardableResult
    public func replaceProfile(_ unknown: String, _ fallback: String) -> Bool {
        if profile == unknown { profile = fallback }
        var changed = false
        let p = plan
        var legs: [RouteLeg] = []
        for (i, leg) in p.legs.enumerated() {
            if leg.profile != unknown {
                legs.append(leg)
                continue
            }
            changed = true
            legs.append(newLeg(p.stops[i], p.stops[i + 1], profile: fallback, mode: leg.mode))
        }
        if changed { state = RouteEditState(plan: p.copy(legs: legs), selected: state.selected) }
        return changed
    }

    /// The legs drawn by hand take the elevations `elevations` knows for them (not an edit, not undoable).
    @discardableResult
    public func applyElevations(_ elevations: HandLegElevations) -> Bool {
        guard let next = elevations.applied(plan) else { return false }
        state = RouteEditState(plan: next, selected: state.selected)
        return true
    }

    /// The legs still waiting for a key that `cache` already has take it: after undo or redo, nothing is asked again.
    @discardableResult
    public func fillFromCache() -> Bool {
        var changed = false
        for i in plan.pending {
            let key = plan.key(i)
            guard let hit = cache.get(key) else { continue }
            let applied = applyResult(key, .ok(hit))
            changed = applied || changed
        }
        return changed
    }

    @discardableResult
    public func undo() -> Bool {
        guard let previous = undoStack.popLast() else { return false }
        redoStack.append(state)
        state = previous
        return true
    }

    @discardableResult
    public func redo() -> Bool {
        guard let next = redoStack.popLast() else { return false }
        undoStack.append(state)
        state = next
        return true
    }

    private func rebuild(_ leg: RouteLeg, _ from: RouteStop, _ to: RouteStop) -> RouteLeg {
        newLeg(from, to, profile: leg.profile, mode: leg.mode)
    }

    /// A new leg: by hand, or routed (known already, or waiting for BRouter with a straight line meanwhile). Without
    /// `profile`, `mode` or `zones`, those of the editor and of the current plan (Kotlin's defaults).
    private func newLeg(
        _ from: RouteStop,
        _ to: RouteStop,
        profile: String? = nil,
        mode: LegMode? = nil,
        zones: [AvoidZone]? = nil
    ) -> RouteLeg {
        let legProfile = profile ?? self.profile
        let legMode = mode ?? (routing ? .routed : .straight)
        if legMode == .straight { return RouteLeg.straight(from, to, legProfile) }
        if let hit = cache.get(LegKey.of(from, to, legProfile, zones: zones ?? plan.zones)) {
            return RoutePlanEditor.legOf(from, to, legProfile, hit)
        }
        return RouteLeg.straight(from, to, legProfile, mode: .routed, state: .pending)
    }

    private func change(_ next: RoutePlan, _ selected: Int?) -> Bool {
        undoStack.append(state)
        redoStack.removeAll()
        while undoStack.count > maxHistory {
            undoStack.removeFirst()
        }
        state = RouteEditState(plan: next, selected: selected)
        return true
    }

    /// The leg BRouter's way makes between two stops. BRouter starts and ends on the nearest way: the line is joined to
    /// the stops so that the legs meet exactly on them.
    public static func legOf(_ from: RouteStop, _ to: RouteStop, _ profile: String, _ result: LegResult.Ok) -> RouteLeg {
        let way = result.points
        var points: [TrackPoint] = []
        points.reserveCapacity(way.count + 2)
        if let first = way.first {
            if Geodesy.distance(first.lat, first.lon, from.lat, from.lon) > snapMeters {
                points.append(from.point)
                points.append(first)
            } else {
                points.append(TrackPoint(lat: from.lat, lon: from.lon, ele: first.ele))
            }
        } else {
            points.append(from.point)
        }
        for k in stride(from: 1, to: way.count, by: 1) {
            points.append(way[k])
        }
        let last = points[points.count - 1]
        if points.count < 2 || Geodesy.distance(last.lat, last.lon, to.lat, to.lon) > snapMeters {
            points.append(to.point)
        } else {
            points[points.count - 1] = TrackPoint(lat: to.lat, lon: to.lon, ele: last.ele)
        }
        return RouteLeg(profile: profile, mode: .routed, state: .ok, points: points, timeMillis: result.timeMillis)
    }
}
