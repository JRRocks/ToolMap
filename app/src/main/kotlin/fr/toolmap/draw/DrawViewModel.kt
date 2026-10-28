package fr.toolmap.draw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.route.AvoidZone
import fr.toolmap.core.route.HandLegElevations
import fr.toolmap.core.route.LegKey
import fr.toolmap.core.route.LegMode
import fr.toolmap.core.route.LegResult
import fr.toolmap.core.route.LegState
import fr.toolmap.core.route.RouteLeg
import fr.toolmap.core.route.RoutePlan
import fr.toolmap.core.route.RoutePlanEditor
import fr.toolmap.core.route.RoutePlanGeoJson
import fr.toolmap.core.route.RoutePlanGpx
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RouteSummary
import fr.toolmap.core.route.RoutingError
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.route.StopKind
import fr.toolmap.core.settings.RoutingNoData
import fr.toolmap.core.track.EditorGeoJson
import fr.toolmap.core.track.EditorGeoJson.TapAction
import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.PolylineEditor
import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.TrackOps
import fr.toolmap.core.track.TrackPath
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the editor works on. */
sealed interface DrawTarget {
    data object NewTrack : DrawTarget

    data object NewRoute : DrawTarget

    /** One segment of a stored track; the others are kept as they are. */
    data class Track(
        val id: Long,
        val name: String?,
        val segments: List<List<TrackPoint>>,
        val index: Int,
    ) : DrawTarget

    data class Route(
        val id: Long,
        val name: String?,
        val points: List<Waypoint>,
        /** The GPX extensions of the route: kept, the plan of a computed route replaced (P19). */
        val extras: List<RawXml> = emptyList(),
    ) : DrawTarget
}

/** What the screen shows of a route being edited (P19, `docs/specs/routing.md` §5). */
data class RouteUi(
    val plan: RoutePlan,
    /** « Suivre les chemins (BRouter) »: new legs follow the ways. */
    val routing: Boolean,
    /** The BRouter app is installed: the switch can be turned on. */
    val installed: Boolean,
    val profile: String,
    /** The profiles offered: the built-in ones, then those the user typed. */
    val profiles: List<String>,
    /** « Ajouter au début »: new stops go before the selected one, or before A. */
    val atStart: Boolean,
    val summary: RouteSummary,
)

/** What the screen shows of the editor. */
data class DrawUi(
    val active: Boolean = false,
    val target: DrawTarget? = null,
    val pointCount: Int = 0,
    val selected: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val freehand: Boolean = false,
    val lengthMeters: Double = 0.0,
    /** A stored track with several segments: how many points each has, for the user to pick the one to edit. */
    val chooseSegment: List<Int>? = null,
    val dirty: Boolean = false,
    /** Set while a route is edited (with or without BRouter). */
    val route: RouteUi? = null,
    /** The stop taken at the crosshair (« Prendre l'étape »), which follows it until « Poser ici ». */
    val taken: Int? = null,
    /** Radius in meters of the zone being placed at the crosshair (« Éviter une zone », P19.8); `null` when none is. */
    val zoneRadius: Int? = null,
) {
    val isRoute: Boolean get() = target is DrawTarget.NewRoute || target is DrawTarget.Route

    /** The route follows the ways: the panel of the computed routes is shown instead of the drawing by hand. */
    val routed: Boolean get() = route?.routing == true
}

/** The GeoJSON the map draws for the editor. */
class EditOverlay(
    val lineGeoJson: String,
    val handlesGeoJson: String,
    /** Glyphs the handles use (letters of the ends, icons of the named stops), loaded by the map. */
    val icons: Set<String> = emptySet(),
    /** The dashed lines that join a stop being moved to its neighbours; `null` when none is. */
    val elasticGeoJson: String? = null,
    /** The zones to avoid and the circle being placed (P19.8); `null` when there are none. */
    val zonesGeoJson: String? = null,
)

/** What BRouter's answers make the screen say, with the shortcuts of the message (`docs/specs/routing.md` §3). */
sealed interface RouteNotice {
    /** One leg was drawn by hand; the routing goes on. */
    data class LegByHand(
        val error: RoutingError,
    ) : RouteNotice

    /** The routing was turned off: the rest is drawn by hand. */
    data class AllByHand(
        val error: RoutingError,
    ) : RouteNotice

    /** BRouter does not have this profile: the previous one is back. */
    data class UnknownProfile(
        val profile: String,
    ) : RouteNotice
}

/**
 * Drawing and editing of tracks and routes on the map. Tracks: the logic is [PolylineEditor]'s. Routes: it is
 * [RoutePlanEditor]'s, by hand (straight legs) or following the ways with BRouter (P19); this class connects them to
 * the screen and to the [RoutingQueue].
 */
@HiltViewModel
class DrawViewModel
    @Inject
    constructor(
        private val repository: TrackRepository,
        private val brouter: BRouterEngine,
        private val settings: SettingsStore,
        private val completer: ElevationCompleter,
    ) : ViewModel() {
        private var editor = PolylineEditor()
        private var pendingTrack: DrawTarget.Track? = null
        private var savedPoints: List<TrackPoint> = emptyList()

        /** The route being edited; `null` for a track. */
        private var route: RoutePlanEditor? = null
        private var savedPlan: RoutePlan = RoutePlan()
        private var atStart = false
        private var installed = false

        /** The profile before the last change: back to it when BRouter does not know the new one. */
        private var previousProfile = RoutingProfiles.HIKING

        /** Only one message about BRouter's answers per edit of the user. */
        private var noticeGiven = false

        /** The stop taken at the crosshair and where the crosshair is (variant A of `p19-reprendre-etape.html`). */
        private var taken: Int? = null
        private var takenAt: TrackPoint? = null

        /** The radius of the zone being placed and where the crosshair is (variant A of `p19-nogos.html`). */
        private var placing: Int? = null
        private var placingAt: TrackPoint? = null

        /** The zone under the crosshair, drawn with a solid edge and given a handle (P30.4); `null` outside them all. */
        private var hovered: Int? = null

        private val queue = RoutingQueue(viewModelScope, brouter, ::onLegResult)

        /** Terrain elevations of the legs drawn by hand (`docs/specs/routing.md` §5.6), asked one batch at a time. */
        private val elevations = HandLegElevations()
        private var elevating: Job? = null

        /** The colour of a new track, the « Default color » of the placemarks (P11.8). */
        private var defaultColor = PathStyle.DEFAULT_COLOR

        /** Labels written on the legs being computed and on those drawn by hand. */
        private var legLabels = "" to ""

        fun setDefaultColor(argb: Int) {
            defaultColor = argb
        }

        fun setLegLabels(
            computing: String,
            byHand: String,
        ) {
            if (legLabels == computing to byHand) return
            legLabels = computing to byHand
            if (route != null) publish()
        }

        private val mutableUi = MutableStateFlow(DrawUi())
        val ui: StateFlow<DrawUi> = mutableUi.asStateFlow()

        private val mutableOverlay = MutableStateFlow<EditOverlay?>(null)

        /** Line and handles to draw; `null` when not editing. */
        val overlay: StateFlow<EditOverlay?> = mutableOverlay.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        private val mutableNotices = MutableSharedFlow<RouteNotice>(extraBufferCapacity = 4)

        /** BRouter's answers that the user should know about, with their shortcuts. */
        val notices: SharedFlow<RouteNotice> = mutableNotices.asSharedFlow()

        private val mutableSaved = MutableSharedFlow<ItemRef>(extraBufferCapacity = 4)

        /** The track or route just saved: lets the screen offer to frame it. */
        val saved: SharedFlow<ItemRef> = mutableSaved.asSharedFlow()

        private val mutableCursor = MutableStateFlow<TrackPoint?>(null)

        /** The point of the route under the cursor of its elevation profile. */
        val cursor: StateFlow<TrackPoint?> = mutableCursor.asStateFlow()

        fun startNewTrack() = begin(DrawTarget.NewTrack, emptyList())

        fun startNewRoute() = beginRoute(DrawTarget.NewRoute, RoutePlan())

        fun startEditTrack(id: Long) {
            viewModelScope.launch {
                val track = repository.loadTrack(id)
                if (track == null || track.segments.isEmpty()) {
                    mutableMessages.tryEmit(UiMessage(R.string.draw_nothing_to_edit))
                    return@launch
                }
                val target = DrawTarget.Track(id, track.name, track.segments, index = 0)
                if (track.segments.size == 1) {
                    begin(target, track.segments[0])
                } else {
                    pendingTrack = target
                    mutableUi.value =
                        DrawUi(active = true, target = target, chooseSegment = track.segments.map { it.size })
                }
            }
        }

        /** The user picked which segment of a multi-segment track to edit. */
        fun chooseSegment(index: Int) {
            val pending = pendingTrack ?: return
            pendingTrack = null
            val target = pending.copy(index = index)
            begin(target, target.segments[index])
        }

        /** Opens a route: with its stops and profiles when it was computed, else as drawn by hand (every point a stop). */
        fun startEditRoute(id: Long) {
            viewModelScope.launch {
                val r = repository.loadRoute(id)
                if (r == null || r.points.isEmpty()) {
                    mutableMessages.tryEmit(UiMessage(R.string.draw_nothing_to_edit))
                    return@launch
                }
                val geometry = r.points.map { TrackPoint(it.lat, it.lon, it.ele) }
                val plan = RoutePlanGpx.planOf(geometry, r.extras) ?: manualPlan(r.points)
                beginRoute(DrawTarget.Route(id, r.name, r.points, r.extras), plan)
            }
        }

        private fun begin(
            target: DrawTarget,
            points: List<TrackPoint>,
        ) {
            route = null
            editor = PolylineEditor(points)
            savedPoints = points
            pendingTrack = null
            mutableUi.value = DrawUi(active = true, target = target)
            publish()
        }

        private fun beginRoute(
            target: DrawTarget,
            plan: RoutePlan,
        ) {
            queue.cancel()
            elevating?.cancel()
            elevations.forgetFailures()
            installed = brouter.isInstalled()
            val placemarks = settings.settings.value.placemarks
            val e = RoutePlanEditor(plan)
            e.profile = plan.legs.lastOrNull { it.mode == LegMode.ROUTED }?.profile ?: placemarks.routingProfile
            // On for a new route when BRouter is there, and for a route computed before; off for one drawn by hand.
            e.routing = installed && (plan.stops.isEmpty() || !plan.isManual)
            previousProfile = e.profile
            route = e
            savedPlan = plan
            atStart = false
            placing = null
            placingAt = null
            hovered = null
            pendingTrack = null
            mutableCursor.value = null
            mutableUi.value = DrawUi(active = true, target = target)
            publish()
        }

        /** The BRouter app may have been installed or removed while the user was away. */
        fun refreshBRouter() {
            val e = route ?: return
            val now = brouter.isInstalled()
            if (now == installed) return
            installed = now
            if (!now) e.routing = false
            publish()
        }

        /** A tap on the map while editing: pick a vertex or a stop, grow a midpoint handle into one, or add one. */
        fun onMapTap(
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ) {
            val ui = mutableUi.value
            if (!ui.active || ui.chooseSegment != null || taken != null || placing != null) return
            val e = route
            if (e != null) {
                routeTap(e, lat, lon, reachMeters)
                return
            }
            when (val action = EditorGeoJson.interpret(editor.points, lat, lon, reachMeters)) {
                is TapAction.Select -> editor.select(if (editor.selected == action.index) null else action.index)
                is TapAction.InsertMidpoint -> editor.insertMidpoint(action.segment)
                TapAction.Add -> editor.add(lat, lon)
            }
            publish()
        }

        /**
         * Following the ways, a tap only selects the stop under it (the crosshair places the stops); drawing by hand, it
         * also grows a midpoint into a stop or adds one, as for a track.
         */
        private fun routeTap(
            e: RoutePlanEditor,
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ) {
            val plan = e.plan
            val stop = plan.nearestStop(lat, lon, reachMeters)
            if (stop != null) {
                e.select(if (e.selected == stop) null else stop)
                publish()
                return
            }
            if (!e.routing) {
                val leg = nearestMidpoint(plan, lat, lon, reachMeters)
                if (leg != null) {
                    val m = RoutePlanGeoJson.midpoint(plan, leg)
                    edited(e.insertOnLeg(leg, RouteStop(m.lat, m.lon)))
                } else {
                    edited(e.add(RouteStop(lat, lon), atStart))
                }
                return
            }
            if (e.selected != null) {
                e.select(null)
                publish()
            }
        }

        private fun nearestMidpoint(
            plan: RoutePlan,
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ): Int? {
            if (plan.stops.size > EditorGeoJson.MAX_HANDLES) return null
            var best: Int? = null
            var bestDistance = reachMeters
            plan.legs.forEachIndexed { k, leg ->
                if (leg.mode != LegMode.STRAIGHT) return@forEachIndexed
                val m = RoutePlanGeoJson.midpoint(plan, k)
                val d = Geodesy.distance(lat, lon, m.lat, m.lon)
                if (d <= bestDistance) {
                    bestDistance = d
                    best = k
                }
            }
            return best
        }

        /**
         * The leg that « Insérer ici » would cut at the crosshair: within [reachMeters] of the route and not on a stop.
         * `null` when the crosshair is elsewhere (« Ajouter ici »).
         */
        fun insertLegAt(
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ): Int? {
            val plan = route?.plan ?: return null
            if (plan.legs.isEmpty()) return null
            if (plan.nearestStop(lat, lon, reachMeters) != null) return null
            val (leg, distance) = plan.nearestLeg(lat, lon) ?: return null
            return leg.takeIf { distance <= reachMeters }
        }

        /** The stop under the crosshair that « Prendre l'étape » would take; `null` elsewhere. */
        fun stopUnder(
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ): Int? = route?.plan?.nearestStop(lat, lon, reachMeters)

        /** « Prendre l'étape »: stop [index] follows the crosshair, at [lat], [lon] now, until [putDown]. */
        fun take(
            index: Int,
            lat: Double,
            lon: Double,
        ) {
            val e = route ?: return
            if (index !in e.plan.stops.indices) return
            e.select(index)
            taken = index
            takenAt = TrackPoint(lat, lon)
            publish()
        }

        /** The crosshair moved while a stop is taken: only its handle and its elastic lines are drawn again. */
        fun followCrosshair(
            lat: Double,
            lon: Double,
        ) {
            val e = route ?: return
            val i = taken ?: return
            val at = TrackPoint(lat, lon)
            takenAt = at
            val shown = mutableOverlay.value ?: return
            mutableOverlay.value =
                EditOverlay(
                    shown.lineGeoJson,
                    RoutePlanGeoJson.handles(e.plan, i, midpoints = !e.routing, moved = at),
                    shown.icons,
                    RoutePlanGeoJson.elastic(e.plan, i, at),
                    shown.zonesGeoJson,
                )
        }

        /** « Poser ici »: the stop taken goes to the crosshair, and its legs are computed again. */
        fun putDown(
            lat: Double,
            lon: Double,
        ) {
            val e = route ?: return
            val i = taken ?: return
            taken = null
            takenAt = null
            if (!edited(e.move(i, lat, lon))) publish()
        }

        /** « Annuler » (or ✕, or back) while a stop is taken: it stays where it was. */
        fun cancelTake() {
            if (taken == null) return
            taken = null
            takenAt = null
            publish()
        }

        // ---- Zones to avoid (P19.8, variant A of `docs/previews/p19-nogos.html`) ----

        /** Outils ▸ « Éviter une zone »: a circle of the last radius follows the crosshair until it is placed. */
        fun startZone() {
            if (route == null) return
            taken = null
            takenAt = null
            placing = settings.settings.value.placemarks.routingAvoidRadius
            publish()
        }

        /** One of the radii of the chips: kept for the next zone. */
        fun setZoneRadius(meters: Int) {
            if (placing == null || meters <= 0) return
            placing = meters
            settings.update { s -> s.copy(placemarks = s.placemarks.copy(routingAvoidRadius = meters)) }
            publish()
        }

        /** The crosshair moved while a zone is being placed: only the circle is drawn again. */
        fun followZone(
            lat: Double,
            lon: Double,
        ) {
            val e = route ?: return
            val r = placing ?: return
            placingAt = TrackPoint(lat, lon)
            val shown = mutableOverlay.value ?: return
            mutableOverlay.value =
                EditOverlay(
                    shown.lineGeoJson,
                    shown.handlesGeoJson,
                    shown.icons,
                    shown.elasticGeoJson,
                    RoutePlanGeoJson.zones(e.plan, AvoidZone(lat, lon, r)),
                )
        }

        /** « Éviter cette zone »: the circle is placed at the crosshair, and the legs that cross it go round it. */
        fun placeZone(
            lat: Double,
            lon: Double,
        ) {
            val e = route ?: return
            val r = placing ?: return
            placing = null
            placingAt = null
            if (!edited(e.addZone(AvoidZone(lat, lon, r)))) publish()
        }

        /** « Annuler » (or ✕, or back) while a zone is being placed. */
        fun cancelZone() {
            if (placing == null) return
            placing = null
            placingAt = null
            publish()
        }

        /** The zone the crosshair is in, that « Retirer la zone » would remove; `null` outside them all. */
        fun zoneUnder(
            lat: Double,
            lon: Double,
        ): Int? = route?.plan?.zoneAt(lat, lon)

        fun removeZone(index: Int) {
            edited(route?.removeZone(index) == true)
        }

        // ---- Size of a zone, at its handle (P30.4, variant C of `docs/previews/p30-itineraires.html`) ----

        /** The crosshair entered zone [index], or left them all (`null`): only the zones are drawn again. */
        fun hoverZone(index: Int?) {
            if (index == hovered) return
            hovered = index
            val e = route ?: return
            val shown = mutableOverlay.value ?: return
            mutableOverlay.value =
                EditOverlay(
                    shown.lineGeoJson,
                    shown.handlesGeoJson,
                    shown.icons,
                    shown.elasticGeoJson,
                    RoutePlanGeoJson.zones(e.plan, placingRing(), hovered),
                )
        }

        /** The handle of zone [index] is pulled to [radius] meters: the circle grows or shrinks, the route pales. */
        fun previewZoneSize(
            index: Int,
            radius: Int,
        ) {
            val e = route ?: return
            if (index !in e.plan.zones.indices) return
            val shown = mutableOverlay.value ?: return
            mutableOverlay.value =
                EditOverlay(
                    RoutePlanGeoJson.lines(
                        e.plan,
                        legLabels.first,
                        if (e.routing) legLabels.second else "",
                        pale = true,
                    ),
                    shown.handlesGeoJson,
                    shown.icons,
                    shown.elasticGeoJson,
                    RoutePlanGeoJson.zones(e.plan, placingRing(), index, radius),
                )
        }

        /**
         * The handle of zone [index] let go at [radius] meters: the zone keeps its centre and takes that size (one
         * « Annuler »), which is kept for the next zone; the legs it touches are computed again.
         */
        fun resizeZone(
            index: Int,
            radius: Int,
        ) {
            val e = route ?: return
            val zone = e.plan.zones.getOrNull(index) ?: return
            if (radius <= 0 || radius == zone.radiusMeters) {
                publish()
                return
            }
            settings.update { s -> s.copy(placemarks = s.placemarks.copy(routingAvoidRadius = radius)) }
            if (!edited(e.replaceZone(index, zone.copy(radiusMeters = radius)))) publish()
        }

        private fun placingRing(): AvoidZone? = placing?.let { r -> placingAt?.let { AvoidZone(it.lat, it.lon, r) } }

        /** "Add here" at the crosshair; on a route, a waypoint under it becomes a named stop. */
        fun addAt(
            lat: Double,
            lon: Double,
            reachMeters: Double = 0.0,
        ) {
            val e = route
            if (e == null) {
                editor.add(lat, lon)
                publish()
                return
            }
            viewModelScope.launch {
                val stop = stopAt(lat, lon, reachMeters)
                edited(e.add(stop, atStart))
            }
        }

        /** « Insérer ici »: a stop on leg [leg] at the crosshair (a detour through that place). */
        fun insertAt(
            leg: Int,
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ) {
            val e = route ?: return
            viewModelScope.launch {
                val stop = stopAt(lat, lon, reachMeters)
                edited(e.insertOnLeg(leg, stop))
            }
        }

        /** The stop to put at the crosshair: the visible waypoint under it, with its name and icon, else a passage. */
        private suspend fun stopAt(
            lat: Double,
            lon: Double,
            reachMeters: Double,
        ): RouteStop {
            if (reachMeters <= 0.0) return RouteStop(lat, lon)
            val lib = repository.library.first()
            val near =
                lib.waypoints
                    .asSequence()
                    .filter { lib.isShown(it.folderId, it.visible) }
                    .map { it to Geodesy.distance(lat, lon, it.lat, it.lon) }
                    .filter { it.second <= reachMeters }
                    .minByOrNull { it.second }
                    ?.first
            return near?.let { RouteStop(it.lat, it.lon, StopKind.NAMED, it.name, it.sym) } ?: RouteStop(lat, lon)
        }

        /** "Move here": the selected vertex goes to the crosshair. */
        fun moveSelectedTo(
            lat: Double,
            lon: Double,
        ) {
            val e = route
            if (e != null) {
                edited(e.moveSelected(lat, lon))
                return
            }
            if (editor.moveSelected(lat, lon)) publish()
        }

        fun deleteSelected() {
            val e = route
            if (e != null) {
                edited(e.deleteSelected())
                return
            }
            if (editor.deleteSelected()) publish()
        }

        fun undo() {
            val e = route
            if (e != null) {
                if (e.undo()) {
                    e.fillFromCache()
                    edited(true)
                }
                return
            }
            if (editor.undo()) publish()
        }

        fun redo() {
            val e = route
            if (e != null) {
                if (e.redo()) {
                    e.fillFromCache()
                    edited(true)
                }
                return
            }
            if (editor.redo()) publish()
        }

        fun setFreehand(on: Boolean) {
            mutableUi.value = mutableUi.value.copy(freehand = on)
        }

        /** A finger stroke, already converted to positions. */
        fun addStroke(
            stroke: List<TrackPoint>,
            toleranceMeters: Double,
        ) {
            val e = route
            if (e != null) {
                edited(e.addStroke(stroke, toleranceMeters))
                return
            }
            if (editor.addStroke(stroke, toleranceMeters)) publish()
        }

        fun reverse() {
            val e = route
            if (e != null) {
                edited(e.reverse())
                return
            }
            if (editor.reverse()) publish()
        }

        fun simplify(toleranceMeters: Double) {
            val e = route
            val before = e?.plan?.stops?.size ?: editor.points.size
            val done = e?.simplify(toleranceMeters) ?: editor.simplify(toleranceMeters)
            if (done) {
                val after = e?.plan?.stops?.size ?: editor.points.size
                mutableMessages.tryEmit(UiMessage(R.string.draw_simplified, listOf(before, after)))
                if (e != null) edited(true) else publish()
            } else {
                mutableMessages.tryEmit(UiMessage(R.string.draw_simplify_nothing))
            }
        }

        fun trimBeforeSelected() {
            val e = route
            if (e != null) {
                e.selected?.let { edited(e.trimBefore(it)) }
                return
            }
            if (editor.trimBeforeSelected()) publish()
        }

        fun trimAfterSelected() {
            val e = route
            if (e != null) {
                e.selected?.let { edited(e.endAt(it)) }
                return
            }
            if (editor.trimAfterSelected()) publish()
        }

        /**
         * Cuts the stored track at the selected vertex into two tracks right away (the first keeps the name and the
         * style, the second is named after it) and leaves the editor.
         */
        fun splitAtSelected() {
            val target = mutableUi.value.target as? DrawTarget.Track ?: return
            val second = editor.splitAtSelected()
            if (second == null) {
                mutableMessages.tryEmit(UiMessage(R.string.draw_split_invalid))
                return
            }
            val segments = target.segments.toMutableList().apply { this[target.index] = editor.points }
            viewModelScope.launch {
                repository.replaceSegments(target.id, segments)
                val style = repository.loadTrack(target.id)?.style ?: PathStyle()
                val newName = (target.name ?: "").trim().takeIf { it.isNotEmpty() }?.let { "$it (2)" }
                repository.createTrack(newName, listOf(second), style)
                mutableMessages.tryEmit(UiMessage(R.string.draw_split_done))
                finish()
            }
        }

        fun clear() {
            val e = route
            if (e != null) {
                edited(e.clear())
                return
            }
            if (editor.clear()) publish()
        }

        // ---- Computed routes (P19) ----

        /** Selects stop [index] (a line of the list of stops). */
        fun selectStop(index: Int?) {
            val e = route ?: return
            e.select(index)
            publish()
        }

        /**
         * Turns « Suivre les chemins » on or off. On, a route drawn by hand follows the ways from then on (its legs are
         * computed again: [routeAllNow]); off, the next legs are drawn by hand.
         */
        fun setRouting(on: Boolean) {
            val e = route ?: return
            if (on && !installed) return
            e.routing = on
            if (on && e.plan.legs.isNotEmpty() && e.plan.isManual) {
                routeAllNow()
                return
            }
            mutableUi.value = mutableUi.value.copy(freehand = false)
            publish()
        }

        /** Every leg drawn by hand follows the ways with the current profile. */
        fun routeAllNow() {
            val e = route ?: return
            e.routing = true
            mutableUi.value = mutableUi.value.copy(freehand = false)
            if (!edited(e.routeAll(e.profile))) publish()
        }

        fun setAtStart(on: Boolean) {
            if (route == null) return
            atStart = on
            publish()
        }

        /** The profile of the chip; with [allLegs], the legs already there take it too. */
        fun setProfile(
            profile: String,
            allLegs: Boolean,
        ) {
            val e = route ?: return
            val name = profile.trim()
            if (name.isEmpty()) return
            previousProfile = e.profile
            val changed = e.setProfile(name, allLegs)
            settings.update { s ->
                val known = RoutingProfiles.BUILT_IN + s.placemarks.routingProfiles
                val custom = if (name in known) s.placemarks.routingProfiles else s.placemarks.routingProfiles + name
                s.copy(placemarks = s.placemarks.copy(routingProfile = name, routingProfiles = custom))
            }
            if (!edited(changed)) publish()
        }

        fun setLegProfile(
            leg: Int,
            profile: String,
        ) {
            val e = route ?: return
            previousProfile = e.plan.legs.getOrNull(leg)?.profile ?: previousProfile
            edited(e.setLegProfile(leg, profile))
        }

        fun setLegMode(
            leg: Int,
            mode: LegMode,
        ) {
            edited(route?.setLegMode(leg, mode) == true)
        }

        fun recompute(leg: Int) {
            edited(route?.recompute(leg) == true)
        }

        /** A stop's name and icon (« Nommer… », « Modifier… »); an empty name makes it a passage again. */
        fun nameStop(
            index: Int,
            name: String?,
            sym: String?,
        ) {
            val e = route ?: return
            val old = e.plan.stops.getOrNull(index) ?: return
            val clean = name?.trim()?.takeIf { it.isNotEmpty() }
            val stop = if (clean == null) old.discreet() else old.copy(kind = StopKind.NAMED, name = clean, sym = sym)
            edited(e.setStop(index, stop))
        }

        fun makeDiscreet(index: Int) {
            val e = route ?: return
            val old = e.plan.stops.getOrNull(index) ?: return
            edited(e.setStop(index, old.discreet()))
        }

        /** « Enregistrer comme waypoint »: a waypoint of the library at the stop, with its name and icon. */
        fun saveStopAsWaypoint(index: Int) {
            val stop = route?.plan?.stops?.getOrNull(index) ?: return
            val ele = route?.plan?.let { p -> p.geometry.getOrNull(p.stopIndices[index])?.ele }
            viewModelScope.launch {
                repository.createWaypoint(Waypoint(stop.lat, stop.lon, ele, name = stop.name, sym = stop.sym))
                mutableMessages.tryEmit(UiMessage(R.string.waypoint_saved))
            }
        }

        fun deleteStop(index: Int) {
            edited(route?.delete(index) == true)
        }

        /** How many stops « Partir d'ici » on stop [index] would remove (none on a loop). */
        fun stopsDroppedBy(index: Int): Int = route?.stopsDroppedBy(index) ?: 0

        fun startFrom(index: Int) {
            edited(route?.startFrom(index) == true)
        }

        fun loopToStart() {
            edited(route?.loopToStart() == true)
        }

        fun outAndBack() {
            edited(route?.outAndBack() == true)
        }

        /** The cursor of the elevation profile, as a distance from the start; `null` hides it. */
        fun setCursor(distanceMeters: Double?) {
            val plan = route?.plan
            mutableCursor.value =
                if (plan == null ||
                    distanceMeters == null
                ) {
                    null
                } else {
                    TrackPath(listOf(plan.geometry)).interpolate(distanceMeters)
                }
        }

        /** After an edit of the route: a new message may come, and the legs it touched are computed. */
        private fun edited(changed: Boolean): Boolean {
            if (!changed) return false
            noticeGiven = false
            // Any edit (undo, delete…) lets go of a stop taken at the crosshair: the indices may have changed.
            taken = null
            takenAt = null
            publish()
            return true
        }

        /** Asks BRouter for the legs still waiting for it. */
        private fun schedule() {
            val e = route ?: return
            val plan = e.plan
            queue.submit(
                plan.pending.map { i ->
                    RoutingQueue.Request(plan.key(i), plan.stops[i], plan.stops[i + 1], plan.legZones(i))
                },
            )
        }

        private fun onLegResult(
            key: LegKey,
            result: LegResult,
        ) {
            val e = route ?: return
            val error = (result as? LegResult.Failed)?.error
            when {
                error == RoutingError.NOT_INSTALLED -> {
                    installed = false
                    e.routing = false
                    e.applyResult(key, result)
                }

                // BRouter answers so for a profile it lacks, and also when it was never set up (no data at all).
                error == RoutingError.UNKNOWN_PROFILE && key.profile != RoutingProfiles.HIKING -> {
                    val fallback = previousProfile.takeIf { it != key.profile } ?: RoutingProfiles.HIKING
                    e.replaceProfile(key.profile, fallback)
                    if (e.profile == fallback) {
                        settings.update { s -> s.copy(placemarks = s.placemarks.copy(routingProfile = fallback)) }
                    }
                    notice(RouteNotice.UnknownProfile(key.profile))
                }

                error != null -> {
                    val reason = if (error == RoutingError.UNKNOWN_PROFILE) RoutingError.NO_DATA else error
                    e.applyResult(key, LegResult.Failed(reason, result.message))
                    if (settings.settings.value.placemarks.routingNoData == RoutingNoData.ALL_BY_HAND && e.routing) {
                        e.routing = false
                        notice(RouteNotice.AllByHand(reason))
                    } else if (e.routing) {
                        notice(RouteNotice.LegByHand(reason))
                    }
                }

                else -> {
                    e.applyResult(key, result)
                }
            }
            publish()
        }

        private fun notice(n: RouteNotice) {
            if (noticeGiven) return
            noticeGiven = true
            mutableNotices.tryEmit(n)
        }

        /** Whether there is anything to save: two points make a line. */
        fun canSave(): Boolean = (route?.plan?.stops?.size ?: editor.points.size) >= 2

        /** Stores the result. [name] is used for a new line; an edited one keeps its own. */
        fun save(name: String?) {
            val target = mutableUi.value.target ?: return
            val e = route
            if (e != null) {
                saveRoute(target, e.plan, name)
                return
            }
            val points = editor.points
            if (points.size < 2) {
                mutableMessages.tryEmit(UiMessage(R.string.draw_too_short))
                return
            }
            viewModelScope.launch {
                val ref =
                    when (target) {
                        DrawTarget.NewTrack -> {
                            ItemRef(
                                ItemKind.TRACK,
                                repository.createTrack(name, listOf(points), PathStyle(colorArgb = defaultColor)),
                            )
                        }

                        is DrawTarget.Track -> {
                            val segments = target.segments.toMutableList().apply { this[target.index] = points }
                            repository.replaceSegments(target.id, segments)
                            ItemRef(ItemKind.TRACK, target.id)
                        }

                        else -> {
                            return@launch
                        }
                    }
                mutableMessages.tryEmit(UiMessage(R.string.draw_saved))
                mutableSaved.tryEmit(ref)
                finish()
            }
        }

        /**
         * A route keeps its plan in its GPX extensions (ADR-065), except one drawn wholly by hand: it is stored as before,
         * its points being its stops.
         */
        private fun saveRoute(
            target: DrawTarget,
            plan: RoutePlan,
            name: String?,
        ) {
            if (plan.stops.size < 2) {
                mutableMessages.tryEmit(UiMessage(R.string.draw_too_short))
                return
            }
            // A route wholly by hand keeps no plan, unless it has zones to avoid (P19.8) for when it follows the ways.
            val manual = plan.isManual && plan.zones.isEmpty()
            val original = (target as? DrawTarget.Route)?.points.orEmpty()
            val points =
                keepKnown(if (manual) RoutePlanGpx.stopPoints(plan) else RoutePlanGpx.routePoints(plan), original)
            val extras =
                RoutePlanGpx.attach(
                    (target as? DrawTarget.Route)?.extras.orEmpty(),
                    plan.takeUnless {
                        manual
                    },
                )
            viewModelScope.launch {
                val ref =
                    when (target) {
                        is DrawTarget.Route -> {
                            repository.replaceRoutePoints(target.id, points, extras)
                            ItemRef(ItemKind.ROUTE, target.id)
                        }

                        else -> {
                            ItemRef(ItemKind.ROUTE, repository.createRoute(name, points, extras = extras))
                        }
                    }
                mutableMessages.tryEmit(UiMessage(R.string.draw_saved))
                mutableSaved.tryEmit(ref)
                finish()
            }
        }

        fun cancel() = finish()

        /** Points that stayed where they were keep their name, notes and altitude; moved or new ones start bare. */
        private fun keepKnown(
            points: List<Waypoint>,
            before: List<Waypoint>,
        ): List<Waypoint> {
            if (before.isEmpty()) return points
            val known = HashMap<Pair<Double, Double>, Waypoint>()
            for (w in before) known.putIfAbsent(w.lat to w.lon, w)
            return points.map { p ->
                val old = known[p.lat to p.lon] ?: return@map p
                old.copy(name = p.name ?: old.name, sym = p.sym ?: old.sym, ele = p.ele ?: old.ele)
            }
        }

        private fun finish() {
            queue.cancel()
            elevating?.cancel()
            route = null
            taken = null
            takenAt = null
            placing = null
            placingAt = null
            hovered = null
            editor = PolylineEditor()
            pendingTrack = null
            mutableUi.value = DrawUi()
            mutableOverlay.value = null
            mutableCursor.value = null
        }

        override fun onCleared() {
            queue.cancel()
            brouter.release()
        }

        private fun publish() {
            val e = route
            if (e != null) {
                publishRoute(e)
                return
            }
            val points = editor.points
            mutableUi.value =
                mutableUi.value.copy(
                    pointCount = points.size,
                    selected = editor.selected,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    lengthMeters = TrackOps.length(points),
                    dirty = points != savedPoints,
                    route = null,
                )
            mutableOverlay.value =
                EditOverlay(EditorGeoJson.line(points), EditorGeoJson.handles(points, editor.selected))
        }

        private fun publishRoute(e: RoutePlanEditor) {
            e.applyElevations(elevations)
            savedPlan = elevations.apply(savedPlan)
            val plan = e.plan
            val summary = RouteSummary.of(plan, e.profile)
            val custom = settings.settings.value.placemarks.routingProfiles
            val profiles = (RoutingProfiles.BUILT_IN + custom + e.profile).distinct()
            mutableUi.value =
                mutableUi.value.copy(
                    pointCount = plan.stops.size,
                    selected = e.selected,
                    canUndo = e.canUndo,
                    canRedo = e.canRedo,
                    lengthMeters = summary.lengthMeters,
                    dirty = plan != savedPlan,
                    route =
                        RouteUi(
                            plan = plan,
                            routing = e.routing,
                            installed = installed,
                            profile = e.profile,
                            profiles = profiles,
                            atStart = atStart,
                            summary = summary,
                        ),
                    taken = taken,
                    zoneRadius = placing,
                )
            val t = taken
            val at = takenAt?.takeIf { t != null }
            if (hovered?.let { it !in plan.zones.indices } == true) hovered = null
            mutableOverlay.value =
                EditOverlay(
                    RoutePlanGeoJson.lines(plan, legLabels.first, if (e.routing) legLabels.second else "", t),
                    RoutePlanGeoJson.handles(plan, e.selected, midpoints = !e.routing, moved = at),
                    RoutePlanGeoJson.icons(plan),
                    if (t != null && at != null) RoutePlanGeoJson.elastic(plan, t, at) else null,
                    RoutePlanGeoJson.zones(plan, placingRing(), hovered),
                )
            schedule()
            elevate()
        }

        /**
         * Gives the legs drawn by hand the elevations of the terrain tiles (stored ones, or downloaded when online), all
         * in one go so that a tile is read once; the screen is published again with them, and asks for the next ones.
         */
        private fun elevate() {
            if (elevating?.isActive == true) return
            val e = route ?: return
            val wanted = elevations.wanted(e.plan)
            if (wanted.isEmpty()) return
            elevating =
                viewModelScope.launch {
                    val keys = wanted.keys.toList()
                    val result = withContext(Dispatchers.Default) { completer.fill(keys.map { wanted.getValue(it) }) }
                    keys.forEachIndexed { i, key -> elevations.put(key, result.segments[i]) }
                    elevating = null
                    if (route === e) publish()
                }
        }

        private companion object {
            /** A route drawn by hand, or imported: every point is a stop, joined to the next by a straight leg. */
            fun manualPlan(points: List<Waypoint>): RoutePlan {
                val stops =
                    points.map { w ->
                        val named = !w.name.isNullOrBlank()
                        RouteStop(
                            w.lat,
                            w.lon,
                            if (named) StopKind.NAMED else StopKind.PASSAGE,
                            w.name?.takeIf { named },
                            w.sym.takeIf { named },
                        )
                    }
                val legs =
                    (1 until stops.size).map { i ->
                        val a = points[i - 1]
                        val b = points[i]
                        RouteLeg(
                            RoutingProfiles.HIKING,
                            LegMode.STRAIGHT,
                            LegState.OK,
                            listOf(TrackPoint(a.lat, a.lon, a.ele), TrackPoint(b.lat, b.lon, b.ele)),
                        )
                    }
                return RoutePlan(stops, legs)
            }
        }
    }
