package fr.toolmap.core.route

import fr.toolmap.core.track.Freehand
import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.PathSimplifier
import fr.toolmap.core.track.TrackPoint

/** What the route editor shows: the plan and the stop the user picked, if any. */
data class RouteEditState(
    val plan: RoutePlan = RoutePlan(),
    val selected: Int? = null,
)

/**
 * The editing of a route made of stops (P19, `docs/specs/routing.md` §5.3): add at the end or at the start, insert on
 * a leg, move, delete, leg by hand or routed, profile of a leg, reverse, loop, out and back, start from a stop, with
 * undo and redo. Every edit returns whether it changed anything; the legs it touches are [LegState.PENDING] (unless
 * [cache] knows them) and the caller hands them to BRouter, then gives the answers back with [applyResult].
 * Not thread-safe: used from the UI thread.
 */
class RoutePlanEditor(
    initial: RoutePlan = RoutePlan(),
    val cache: LegCache = LegCache(),
    private val maxHistory: Int = DEFAULT_HISTORY,
) {
    private val undoStack = ArrayDeque<RouteEditState>()
    private val redoStack = ArrayDeque<RouteEditState>()

    var state: RouteEditState = RouteEditState(initial)
        private set

    val plan: RoutePlan get() = state.plan
    val selected: Int? get() = state.selected
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Profile of the new legs. */
    var profile: String = RoutingProfiles.HIKING

    /** New legs follow the ways (« Suivre les chemins »); off, they are drawn by hand. */
    var routing: Boolean = true

    /** Replaces the whole plan (opening a route); clears the history. */
    fun load(plan: RoutePlan) {
        undoStack.clear()
        redoStack.clear()
        state = RouteEditState(plan)
    }

    /** Selection is not an edit: it is not undoable on its own. */
    fun select(index: Int?) {
        state = state.copy(selected = index?.takeIf { it in plan.stops.indices })
    }

    /**
     * Adds a stop: after the selected one, else at the end; with [atStart], before the selected one, else at the start.
     * The new stop becomes selected.
     */
    fun add(
        stop: RouteStop,
        atStart: Boolean = false,
    ): Boolean {
        val stops = plan.stops
        if (stops.isEmpty()) return change(RoutePlan(listOf(stop)), 0)
        val sel = selected
        val index = if (atStart) sel ?: 0 else sel?.plus(1) ?: stops.size
        return insertAt(index, stop)
    }

    /** Inserts a stop on leg [leg] (a detour through that place): the leg becomes two, with its profile and mode. */
    fun insertOnLeg(
        leg: Int,
        stop: RouteStop,
    ): Boolean {
        if (leg !in plan.legs.indices) return false
        return insertAt(leg + 1, stop)
    }

    private fun insertAt(
        index: Int,
        stop: RouteStop,
    ): Boolean {
        val s = plan.stops
        val stops = s.toMutableList().apply { add(index, stop) }
        val legs = plan.legs.toMutableList()
        when (index) {
            0 -> {
                legs.add(0, newLeg(stop, s[0]))
            }

            s.size -> {
                legs.add(newLeg(s.last(), stop))
            }

            else -> {
                val old = legs[index - 1]
                legs[index - 1] = newLeg(s[index - 1], stop, old.profile, old.mode)
                legs.add(index, newLeg(stop, s[index], old.profile, old.mode))
            }
        }
        return change(RoutePlan(stops, legs), index)
    }

    /** Puts the selected stop somewhere else; the legs on both sides are computed again. */
    fun moveSelected(
        lat: Double,
        lon: Double,
    ): Boolean {
        val i = selected ?: return false
        val stops = plan.stops.toMutableList()
        stops[i] = stops[i].copy(lat = lat, lon = lon)
        val legs = plan.legs.toMutableList()
        if (i > 0) legs[i - 1] = rebuild(legs[i - 1], stops[i - 1], stops[i])
        if (i < legs.size) legs[i] = rebuild(legs[i], stops[i], stops[i + 1])
        return change(RoutePlan(stops, legs), i)
    }

    fun deleteSelected(): Boolean {
        val i = selected ?: return false
        return delete(i)
    }

    /** Removes stop [i]: the two legs around it become one, with the profile and the mode of the first. */
    fun delete(i: Int): Boolean {
        val s = plan.stops
        if (i !in s.indices) return false
        val stops = s.toMutableList().apply { removeAt(i) }
        val legs = plan.legs.toMutableList()
        when {
            s.size == 1 -> {
                Unit
            }

            i == 0 -> {
                legs.removeAt(0)
            }

            i == s.lastIndex -> {
                legs.removeAt(legs.lastIndex)
            }

            else -> {
                val before = legs[i - 1]
                legs[i - 1] = newLeg(s[i - 1], s[i + 1], before.profile, before.mode)
                legs.removeAt(i)
            }
        }
        return change(RoutePlan(stops, legs), if (stops.isEmpty()) null else minOf(i, stops.lastIndex))
    }

    /** Leg [leg] drawn by hand (off the ways) or routed again. */
    fun setLegMode(
        leg: Int,
        mode: LegMode,
    ): Boolean {
        val old = plan.legs.getOrNull(leg) ?: return false
        if (old.mode == mode) return false
        return replaceLeg(leg, newLeg(plan.stops[leg], plan.stops[leg + 1], old.profile, mode))
    }

    /** Leg [leg] routed with its own profile. */
    fun setLegProfile(
        leg: Int,
        profile: String,
    ): Boolean {
        val old = plan.legs.getOrNull(leg) ?: return false
        if (old.profile == profile && old.mode == LegMode.ROUTED) return false
        return replaceLeg(leg, newLeg(plan.stops[leg], plan.stops[leg + 1], profile, LegMode.ROUTED))
    }

    /** The profile of the new legs and, with [allLegs], of every routed leg already there. */
    fun setProfile(
        profile: String,
        allLegs: Boolean,
    ): Boolean {
        this.profile = profile
        if (!allLegs) return false
        var changed = false
        val legs =
            plan.legs.mapIndexed { i, leg ->
                if (leg.mode != LegMode.ROUTED || leg.profile == profile) {
                    leg
                } else {
                    changed = true
                    newLeg(plan.stops[i], plan.stops[i + 1], profile, LegMode.ROUTED)
                }
            }
        return if (changed) change(RoutePlan(plan.stops, legs), selected) else false
    }

    /** Asks BRouter again for a leg it could not compute (the data may have been downloaded since). */
    fun recompute(leg: Int): Boolean {
        val old = plan.legs.getOrNull(leg) ?: return false
        if (old.state != LegState.NO_DATA && old.state != LegState.FAILED) return false
        return replaceLeg(leg, newLeg(plan.stops[leg], plan.stops[leg + 1], old.profile, LegMode.ROUTED))
    }

    private fun replaceLeg(
        index: Int,
        leg: RouteLeg,
    ): Boolean {
        val legs = plan.legs.toMutableList().apply { this[index] = leg }
        return change(RoutePlan(plan.stops, legs), selected)
    }

    /** Names a stop, makes it discreet, or gives it a waypoint's name and icon: its place does not change. */
    fun setStop(
        index: Int,
        stop: RouteStop,
    ): Boolean {
        val old = plan.stops.getOrNull(index) ?: return false
        if (old == stop) return false
        val stops = plan.stops.toMutableList().apply { this[index] = stop.copy(lat = old.lat, lon = old.lon) }
        return change(RoutePlan(stops, plan.legs), selected)
    }

    /** The other way round. Routed legs are computed again (one-way streets, slopes); legs by hand are just turned. */
    fun reverse(): Boolean {
        val s = plan.stops
        if (s.size < 2) return false
        val stops = s.reversed()
        val legs =
            plan.legs.reversed().mapIndexed { i, leg ->
                if (leg.mode == LegMode.STRAIGHT) {
                    leg.copy(points = leg.points.reversed())
                } else {
                    newLeg(stops[i], stops[i + 1], leg.profile, LegMode.ROUTED)
                }
            }
        return change(RoutePlan(stops, legs), selected?.let { s.lastIndex - it })
    }

    /** Back to the start: a copy of the first stop at the end. */
    fun loopToStart(): Boolean {
        val s = plan.stops
        if (s.size < 2 || plan.isLoop) return false
        return insertAt(s.size, s[0].discreet())
    }

    /** Out and back: the stops again in reverse order, each leg back with the profile and the mode it came with. */
    fun outAndBack(): Boolean {
        val s = plan.stops
        if (s.size < 2) return false
        val back = s.dropLast(1).reversed().map { it.discreet() }
        val stops = s + back
        val legs = plan.legs.toMutableList()
        plan.legs.reversed().forEachIndexed { k, leg ->
            val from = stops[s.lastIndex + k]
            val to = stops[s.lastIndex + k + 1]
            legs += newLeg(from, to, leg.profile, leg.mode)
        }
        return change(RoutePlan(stops, legs), stops.lastIndex)
    }

    /** How many stops [startFrom] removes: none on a loop, which turns instead. */
    fun stopsDroppedBy(index: Int): Int = if (plan.isLoop) 0 else index.coerceAtLeast(0)

    /**
     * The route starts at stop [index]: a loop turns round (nothing is computed again), any other route loses the stops
     * before it.
     */
    fun startFrom(index: Int): Boolean {
        val s = plan.stops
        if (index !in 1 until s.size) return false
        if (plan.isLoop) {
            if (index == s.lastIndex) return false
            val stops = s.subList(index, s.lastIndex) + s.subList(0, index + 1)
            val legs = plan.legs.subList(index, plan.legs.size) + plan.legs.subList(0, index)
            return change(RoutePlan(stops, legs), 0)
        }
        return change(RoutePlan(s.subList(index, s.size), plan.legs.subList(index, plan.legs.size)), 0)
    }

    /** Drops the stops before [index], loop or not (« Trim before » of the drawing by hand). */
    fun trimBefore(index: Int): Boolean {
        val s = plan.stops
        if (index !in 1 until s.size) return false
        return change(RoutePlan(s.subList(index, s.size), plan.legs.subList(index, plan.legs.size)), 0)
    }

    /** Drops the stops after [index] (« Trim after » of the drawing by hand). */
    fun endAt(index: Int): Boolean {
        val s = plan.stops
        if (index !in 0 until s.lastIndex) return false
        return change(RoutePlan(s.subList(0, index + 1), plan.legs.subList(0, index)), index)
    }

    /**
     * Thins the stops by Douglas-Peucker, named stops kept (for a route drawn by hand, or before following the ways).
     * Where stops go, the legs around them become one.
     */
    fun simplify(toleranceMeters: Double): Boolean {
        val s = plan.stops
        if (s.size < 3) return false
        val points = s.map { it.point }
        val kept = PathSimplifier.simplify(points, toleranceMeters)
        val keep = BooleanArray(s.size)
        var j = 0
        for (i in s.indices) {
            if (j < kept.size && points[i] === kept[j]) {
                keep[i] = true
                j++
            }
        }
        s.forEachIndexed { i, stop -> if (stop.kind == StopKind.NAMED) keep[i] = true }
        keep[0] = true
        keep[s.lastIndex] = true
        val indices = s.indices.filter { keep[it] }
        if (indices.size == s.size) return false
        val stops = indices.map { s[it] }
        val legs =
            (1 until indices.size).map { k ->
                val a = indices[k - 1]
                val b = indices[k]
                if (b == a + 1) {
                    plan.legs[a]
                } else {
                    val straight = (a until b).all { plan.legs[it].mode == LegMode.STRAIGHT }
                    newLeg(s[a], s[b], plan.legs[a].profile, if (straight) LegMode.STRAIGHT else LegMode.ROUTED)
                }
            }
        return change(RoutePlan(stops, legs), null)
    }

    /** A finger stroke drawn by hand: its thinned points become passages linked by straight legs. */
    fun addStroke(
        stroke: List<TrackPoint>,
        toleranceMeters: Double,
    ): Boolean {
        val thinned = Freehand.thin(stroke, toleranceMeters)
        if (thinned.isEmpty()) return false
        val stops = plan.stops.toMutableList()
        val legs = plan.legs.toMutableList()
        for (p in thinned) {
            val stop = RouteStop(p.lat, p.lon)
            stops.lastOrNull()?.let { legs += RouteLeg.straight(it, stop, profile) }
            stops += stop
        }
        return change(RoutePlan(stops, legs), stops.lastIndex)
    }

    /** Every leg drawn by hand follows the ways with [profile] (a route drawn by hand that is now routed). */
    fun routeAll(profile: String): Boolean {
        this.profile = profile
        if (plan.legs.none { it.mode == LegMode.STRAIGHT }) return false
        val legs =
            plan.legs.mapIndexed { i, leg ->
                if (leg.mode ==
                    LegMode.STRAIGHT
                ) {
                    newLeg(plan.stops[i], plan.stops[i + 1], profile, LegMode.ROUTED)
                } else {
                    leg
                }
            }
        return change(RoutePlan(plan.stops, legs), selected)
    }

    fun clear(): Boolean = if (plan.stops.isEmpty()) false else change(RoutePlan(), null)

    /**
     * The answer of BRouter for the legs of [key]: every leg still waiting for it takes it. This is not an edit (not
     * undoable); a leg changed in between has another key and is left alone.
     */
    fun applyResult(
        key: LegKey,
        result: LegResult,
    ): Boolean {
        if (result is LegResult.Ok) cache.put(key, result)
        var changed = false
        val p = plan
        val legs =
            p.legs.mapIndexed { i, leg ->
                if (leg.state != LegState.PENDING || p.key(i) != key) return@mapIndexed leg
                changed = true
                when (result) {
                    is LegResult.Ok -> {
                        legOf(p.stops[i], p.stops[i + 1], leg.profile, result)
                    }

                    is LegResult.Failed -> {
                        leg.copy(
                            state =
                                if (result.error ==
                                    RoutingError.NO_DATA
                                ) {
                                    LegState.NO_DATA
                                } else {
                                    LegState.FAILED
                                },
                        )
                    }
                }
            }
        if (changed) state = state.copy(plan = RoutePlan(p.stops, legs))
        return changed
    }

    /** A profile BRouter does not have: the legs that wanted it take [fallback] instead (not undoable either). */
    fun replaceProfile(
        unknown: String,
        fallback: String,
    ): Boolean {
        if (profile == unknown) profile = fallback
        var changed = false
        val p = plan
        val legs =
            p.legs.mapIndexed { i, leg ->
                if (leg.profile != unknown) return@mapIndexed leg
                changed = true
                newLeg(p.stops[i], p.stops[i + 1], fallback, leg.mode)
            }
        if (changed) state = state.copy(plan = RoutePlan(p.stops, legs))
        return changed
    }

    /** The legs drawn by hand take the elevations [elevations] knows for them (not an edit, not undoable). */
    fun applyElevations(elevations: HandLegElevations): Boolean {
        val next = elevations.apply(plan)
        if (next === plan) return false
        state = state.copy(plan = next)
        return true
    }

    /** The legs still waiting for a [key] that [cache] already has take it: after undo or redo, nothing is asked again. */
    fun fillFromCache(): Boolean {
        var changed = false
        for (i in plan.pending) {
            val key = plan.key(i)
            val hit = cache.get(key) ?: continue
            changed = applyResult(key, hit) || changed
        }
        return changed
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(state)
        state = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state)
        state = next
        return true
    }

    private fun rebuild(
        leg: RouteLeg,
        from: RouteStop,
        to: RouteStop,
    ): RouteLeg = newLeg(from, to, leg.profile, leg.mode)

    /** A new leg: by hand, or routed (known already, or waiting for BRouter with a straight line meanwhile). */
    private fun newLeg(
        from: RouteStop,
        to: RouteStop,
        profile: String = this.profile,
        mode: LegMode = if (routing) LegMode.ROUTED else LegMode.STRAIGHT,
    ): RouteLeg {
        if (mode == LegMode.STRAIGHT) return RouteLeg.straight(from, to, profile)
        val hit = cache.get(LegKey.of(from, to, profile))
        return if (hit != null) {
            legOf(from, to, profile, hit)
        } else {
            RouteLeg.straight(from, to, profile, LegMode.ROUTED, LegState.PENDING)
        }
    }

    private fun change(
        next: RoutePlan,
        selected: Int?,
    ): Boolean {
        undoStack.addLast(state)
        redoStack.clear()
        while (undoStack.size > maxHistory) undoStack.removeFirst()
        state = RouteEditState(next, selected)
        return true
    }

    companion object {
        const val DEFAULT_HISTORY = 200

        /** Ends of a computed way closer than this to the stops are put on them; farther ones are joined to them. */
        private const val SNAP_METERS = 0.5

        /**
         * The leg BRouter's way makes between two stops. BRouter starts and ends on the nearest way: the line is
         * joined to the stops so that the legs meet exactly on them.
         */
        fun legOf(
            from: RouteStop,
            to: RouteStop,
            profile: String,
            result: LegResult.Ok,
        ): RouteLeg {
            val way = result.points
            val points = ArrayList<TrackPoint>(way.size + 2)
            val first = way.firstOrNull()
            when {
                first == null -> {
                    points += from.point
                }

                Geodesy.distance(first.lat, first.lon, from.lat, from.lon) > SNAP_METERS -> {
                    points += from.point
                    points += first
                }

                else -> {
                    points += TrackPoint(from.lat, from.lon, first.ele)
                }
            }
            for (k in 1 until way.size) points += way[k]
            val last = points.last()
            if (points.size < 2 || Geodesy.distance(last.lat, last.lon, to.lat, to.lon) > SNAP_METERS) {
                points += to.point
            } else {
                points[points.lastIndex] = TrackPoint(to.lat, to.lon, last.ele)
            }
            return RouteLeg(profile, LegMode.ROUTED, LegState.OK, points, result.timeMillis)
        }
    }
}
