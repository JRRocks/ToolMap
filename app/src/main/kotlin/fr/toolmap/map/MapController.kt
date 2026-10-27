package fr.toolmap.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.layers.AppliedLayer
import fr.toolmap.core.layers.BandSwap
import fr.toolmap.core.layers.StyleDiff
import fr.toolmap.core.layers.StyleOp
import fr.toolmap.core.layers.StyleSources
import fr.toolmap.core.settings.CourseDisplay
import fr.toolmap.core.settings.HeadingDisplay
import fr.toolmap.core.settings.MapCanvasSettings
import fr.toolmap.core.settings.PlacemarkSettings
import fr.toolmap.core.settings.PositionSettings
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.core.track.TrackGeoJson
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.tracks.DisplayData
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.share.ShareMapData
import fr.toolmap.waypoints.WaypointGlyphs
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** How the camera behaves with respect to the user's position. */
enum class LocationMode {
    /** Position shown (once enabled) but the camera is free. */
    FREE,

    /** The camera follows the position, north up. */
    FOLLOW,

    /** The camera follows the position and rotates with the compass heading. */
    FOLLOW_COMPASS,

    ;

    /** The mode a long press on the position button goes to: free, then following, then following with the compass. */
    fun next(): LocationMode =
        when (this) {
            FREE -> FOLLOW
            FOLLOW -> FOLLOW_COMPASS
            FOLLOW_COMPASS -> FREE
        }
}

/**
 * The thin layer between the Compose UI and MapLibre (ADR-011): everything MapLibre-specific for the main map
 * lives here, so the rest of the app talks to it with plain types. Single-threaded: used from the main thread.
 */
class MapController(
    private val appContext: Context,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var applied: List<AppliedLayer> = emptyList()
    private var appliedSignatures: Map<String, String?> = emptyMap()

    /** Layers rebuilt on a new elevation band, by id, with their previous state drawn below them meanwhile (P20). */
    private val ghosts = mutableMapOf<String, AppliedLayer>()
    private var ghostFrameSeen = false
    private var mapping: ZoomMapping = ZoomMapping.STANDARD
    private var locationActivated = false

    /** The « Map canvas settings » the gestures follow (P11.8), and whether the user locked the screen. */
    private var canvas = MapCanvasSettings()
    private var gesturesLocked = false

    /** The « Placemarks » settings: sizes, arrows, contours, borders, shadows and the target colour (P11.8). */
    private var placemarks = PlacemarkSettings()
    private var gridDashed = false

    /** « Positionnement » and « Orientation » (P11.8): the puck, the camera after a pause, the trail. */
    private var positioning = PositionSettings()
    private var headingDisplay = HeadingDisplay.ARROW
    private var trail: List<Pair<Double, Double>> = emptyList()

    /** The mode to come back to after the user moved the map, and whether the auto relock is waiting for it. */
    private var followMode = LocationMode.FOLLOW
    private var relockArmed = false
    private var movedByUser = false
    private val relockHandler = Handler(Looper.getMainLooper())
    private val relock =
        Runnable {
            if (relockArmed && positioning.autoRelock && locationOn && locationMode == LocationMode.FREE) {
                relockArmed = false
                applyLocationMode(followMode)
            }
        }
    private var coverageSourceId: String? = null
    private var tracks: DisplayData? = null
    private var cursor: TrackPoint? = null
    private var edit: Triple<String, String, Set<String>>? = null
    private var nav: Pair<Pair<Double, Double>?, Pair<Double, Double>?>? = null
    private var share: ShareMapData? = null
    private var measure: Triple<String, String, String>? = null
    private var grid: Pair<String, String>? = null
    private var watches: String? = null

    /** True once the style is loaded and layers can be synchronised. */
    var isReady by mutableStateOf(false)
        private set

    /** Camera as it is right now (updated continuously while the user moves the map). */
    var liveCamera by mutableStateOf(CameraState())
        private set

    var locationMode by mutableStateOf(LocationMode.FREE)
        private set

    /** Whether the position is shown (from the first tap on the locate button); it stays on after that. */
    var locationOn by mutableStateOf(false)
        private set

    /** The map is tilted into perspective (the explorer mode, or a two-finger tilt). */
    var tilted by mutableStateOf(false)
        private set

    /** Called when the camera stops moving: the place to persist it. */
    var onCameraIdle: (CameraState) -> Unit = {}

    /** Called with the track, route or waypoint under a tap, or `null` for a tap on the bare map. */
    var onItemTap: (ItemRef?) -> Unit = {}

    /** Called with the id of the group member whose disc was tapped. */
    var onMemberTap: (Long) -> Unit = {}

    /** While set (drawing mode) taps go here, with the latitude and longitude, instead of selecting items. */
    var onDrawTap: ((lat: Double, lon: Double) -> Unit)? = null

    /** A long press on the map, with its latitude and longitude. */
    var onLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> }

    fun attach(
        mapView: MapView,
        initial: CameraState,
    ) {
        // The previous drawing of a re-banded layer goes once the map has drawn its new state completely.
        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> if (ghosts.isNotEmpty()) ghostFrameSeen = true }
        mapView.addOnDidBecomeIdleListener { if (ghostFrameSeen) dropGhosts() }
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                // The app draws its own compass, attribution and scale bar.
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
            }
            applyGestures()
            m.setMaxZoomPreference(MAX_CAMERA_ZOOM)
            m.setStyle(Style.Builder().fromJson(StyleApplier.BASE_STYLE)) { loaded ->
                style = loaded
                moveTo(initial)
                m.addOnCameraMoveListener {
                    liveCamera = readCamera(m)
                    tilted = m.cameraPosition.tilt >= TILTED_DEGREES
                }
                m.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        movedByUser = true
                        // Another gesture restarts the 10 s of « Recentrage automatique ».
                        relockHandler.removeCallbacks(relock)
                    }
                }
                m.addOnCameraIdleListener {
                    liveCamera = readCamera(m)
                    tilted = m.cameraPosition.tilt >= TILTED_DEGREES
                    if (movedByUser) {
                        movedByUser = false
                        if (relockArmed && positioning.autoRelock) relockHandler.postDelayed(relock, RELOCK_DELAY_MS)
                    }
                    // Pitching the map down with two fingers starts the explorer mode, when the location is on.
                    if (canvas.explorerAuto && canvas.perspective && locationOn &&
                        locationMode != LocationMode.FOLLOW_COMPASS &&
                        m.cameraPosition.tilt >= EXPLORER_TILT_DEGREES
                    ) {
                        applyLocationMode(LocationMode.FOLLOW_COMPASS)
                    }
                    onCameraIdle(liveCamera)
                }
                liveCamera = readCamera(m)
                installTracks(m, loaded)
                isReady = true
            }
        }
    }

    /**
     * Brings the style in line with [desired] (bottom to top), changing as little as possible. A map whose
     * definition changed (a multi-zoom map edited, another overscaling setting: its zoom range in MapLibre changes,
     * ADR-031) or another tile sharpness ([mapping], ADR-032) cannot be updated in place: the style is rebuilt.
     */
    fun sync(
        desired: List<AppliedLayer>,
        scaling: ScalingPolicy = ScalingPolicy(),
        mapping: ZoomMapping = ZoomMapping.STANDARD,
        find: (String) -> TileSource?,
    ) {
        val s = style ?: return
        val signatures =
            desired.map { it.sourceId }.distinct().associateWith { id ->
                find(StyleSources.sourceOf(id))?.let { StyleApplier.signature(it, scaling, mapping) }
            }
        val mappingChanged = mapping != this.mapping
        this.mapping = mapping
        val changed = mappingChanged || appliedSignatures.any { (id, old) -> id in signatures && signatures[id] != old }
        if (!changed) {
            val ops = StyleDiff.compute(applied, desired)
            if (ops.isEmpty()) return
            try {
                keepWhileLoading(s, desired)
                // A source still drawn by a previous drawing stays; one back in use is there already.
                val kept = ghosts.values.map { it.sourceId }.toSet()
                val run =
                    ops.filterNot { op ->
                        (op is StyleOp.RemoveSource && op.sourceId in kept) ||
                            (op is StyleOp.AddSource && s.getSource(op.sourceId) != null)
                    }
                StyleApplier.apply(s, run, find, scaling, mapping)
                applied = desired
                appliedSignatures = signatures
                return
            } catch (e: RuntimeException) {
                // A half-applied update would leave `applied` wrong: rebuild everything from scratch.
                Log.e(TAG, "Layer update failed, rebuilding the style", e)
            }
        }
        StyleApplier.clear(s)
        ghosts.clear()
        applied = emptyList()
        appliedSignatures = emptyMap()
        runCatching {
            StyleApplier.apply(s, StyleDiff.compute(emptyList(), desired), find, scaling, mapping)
            applied = desired
            appliedSignatures = signatures
        }.onFailure { Log.e(TAG, "Layer rebuild failed", it) }
        showCoverage(coverageSourceId)
    }

    /**
     * Before the layers whose elevation band changed are rebuilt on their new source (P20): their current drawing is
     * kept below them ([StyleApplier.addGhost]) until the map has drawn the new one, so that the map does not go
     * blank meanwhile ([BandSwap] says which).
     */
    private fun keepWhileLoading(
        s: Style,
        desired: List<AppliedLayer>,
    ) {
        val needed = desired.map { it.sourceId }.toSet()
        for (id in BandSwap.stale(ghosts, desired)) dropGhost(s, id, needed)
        for (old in BandSwap.newGhosts(applied, desired, ghosts.keys)) {
            StyleApplier.addGhost(s, old)
            ghosts[old.id] = old
            ghostFrameSeen = false
        }
    }

    private fun dropGhosts() {
        val s = style ?: return
        val needed = applied.map { it.sourceId }.toSet()
        for (id in ghosts.keys.toList()) dropGhost(s, id, needed)
        ghostFrameSeen = false
    }

    /** Removes the previous drawing of layer [id], and its source unless [needed] or still drawn by another one. */
    private fun dropGhost(
        s: Style,
        id: String,
        needed: Set<String>,
    ) {
        val ghost = ghosts.remove(id) ?: return
        s.removeLayer(StyleApplier.ghostId(id))
        if (ghost.sourceId !in needed && ghosts.values.none { it.sourceId == ghost.sourceId }) {
            s.removeSource(ghost.sourceId)
        }
    }

    /** Draws the visible tracks, routes and waypoints; applied as soon as the style exists. */
    fun showTracks(data: DisplayData) {
        tracks = data
        val s = style ?: return
        runCatching {
            TrackOverlay.setLines(s, data.linesGeoJson)
            TrackOverlay.setPoints(s, data.pointsGeoJson, data.pointIcons) { WaypointGlyphs.render(appContext, it) }
        }.onFailure { Log.e(TAG, "Track overlay failed", it) }
    }

    /** The marker that follows the elevation profile cursor; `null` removes it. */
    fun showCursor(point: TrackPoint?) {
        cursor = point
        val s = style ?: return
        runCatching { TrackOverlay.setCursor(s, point?.let { TrackGeoJson.point(it.lat, it.lon) }) }
            .onFailure { Log.e(TAG, "Cursor overlay failed", it) }
    }

    /** The line being drawn and its handles; `null` clears them. */
    fun showEdit(
        lineGeoJson: String?,
        handlesGeoJson: String?,
        icons: Set<String> = emptySet(),
    ) {
        edit = if (lineGeoJson != null && handlesGeoJson != null) Triple(lineGeoJson, handlesGeoJson, icons) else null
        val s = style ?: return
        runCatching {
            TrackOverlay.addGlyphs(s, icons) { WaypointGlyphs.render(appContext, it) }
            EditLayers.show(s, lineGeoJson, handlesGeoJson)
        }.onFailure { Log.e(TAG, "Edit overlay failed", it) }
    }

    /** The ring on the target and the line to it from [from]; a `null` [target] hides both. */
    fun showTarget(
        from: Pair<Double, Double>?,
        target: Pair<Double, Double>?,
    ) {
        nav = from to target
        val s = style ?: return
        runCatching { NavLayers.show(s, from, target, placemarks.targetColor) }
            .onFailure { Log.e(TAG, "Target overlay failed", it) }
    }

    /** The other members of the sharing group and their trails, as GeoJSON; applied as soon as the style exists. */
    fun showShare(data: ShareMapData) {
        share = data
        val s = style ?: return
        runCatching { ShareLayers.show(appContext, s, data) }
            .onFailure { Log.e(TAG, "Share overlay failed", it) }
    }

    /** The measure tool's line, fill and vertices; `null` clears them. */
    fun showMeasure(
        lineGeoJson: String?,
        fillGeoJson: String?,
        verticesGeoJson: String?,
    ) {
        measure =
            if (lineGeoJson != null && fillGeoJson != null && verticesGeoJson != null) {
                Triple(lineGeoJson, fillGeoJson, verticesGeoJson)
            } else {
                null
            }
        val s = style ?: return
        runCatching { MeasureLayers.show(s, lineGeoJson, fillGeoJson, verticesGeoJson) }
            .onFailure { Log.e(TAG, "Measure overlay failed", it) }
    }

    /** The radius circles of the watched waypoints, as GeoJSON; applied as soon as the style exists. */
    fun showWatches(geoJson: String) {
        watches = geoJson
        val s = style ?: return
        runCatching { AlertLayers.show(s, geoJson) }.onFailure { Log.e(TAG, "Watch overlay failed", it) }
    }

    /** The coordinate grid as GeoJSON lines, and its names as points; applied as soon as the style exists. */
    fun showGrid(
        geoJson: String,
        labelsGeoJson: String,
    ) {
        grid = geoJson to labelsGeoJson
        val s = style ?: return
        runCatching { GridLayers.show(s, geoJson, labelsGeoJson) }.onFailure { Log.e(TAG, "Grid overlay failed", it) }
    }

    /** Position under a screen point (pixels in the map view), or `null` before the map is ready. */
    fun screenToLatLon(
        x: Float,
        y: Float,
    ): TrackPoint? {
        val p = map?.projection?.fromScreenLocation(android.graphics.PointF(x, y)) ?: return null
        return TrackPoint(p.latitude, p.longitude)
    }

    /**
     * The ground under the part of the screen from [left], [top] to [right], [bottom] (pixels in the map view), as the
     * rectangle that holds its four corners; `null` before the map is ready.
     */
    fun screenArea(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): ZoneShape.Rectangle? {
        val corners =
            listOf(left to top, right to top, right to bottom, left to bottom).map { (x, y) ->
                screenToLatLon(x.toFloat(), y.toFloat()) ?: return null
            }
        return ZoneShape.Rectangle(
            corners.minOf { it.lon },
            corners.minOf { it.lat },
            corners.maxOf { it.lon },
            corners.maxOf { it.lat },
        )
    }

    /** Screen position (pixels in the map view) of a point on the ground, or `null` before the map is ready. */
    fun latLonToScreen(
        lat: Double,
        lon: Double,
    ): Offset? {
        val p = map?.projection?.toScreenLocation(LatLng(lat, lon)) ?: return null
        return Offset(p.x, p.y)
    }

    /** Ground meters under [dp] density-independent pixels at the centre of the map. */
    fun metersFor(dp: Float): Double = ScaleBar.metersPerDp(liveCamera.lat, liveCamera.zoom) * dp

    /** Frames a box, or flies to its centre when it is as small as a point. */
    fun fitBounds(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ) {
        val m = map ?: return
        if (north - south < MIN_FIT_SPAN && east - west < MIN_FIT_SPAN) {
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng((south + north) / 2, (west + east) / 2), POINT_ZOOM),
            )
            return
        }
        val bounds = LatLngBounds.Builder().include(LatLng(south, west)).include(LatLng(north, east)).build()
        val padding = (FIT_PADDING_DP * appContext.resources.displayMetrics.density).toInt()
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }

    private fun installTracks(
        m: MapLibreMap,
        s: Style,
    ) {
        runCatching { TrackOverlay.install(s) }.onFailure { Log.e(TAG, "Track overlay install failed", it) }
        TrackOverlay.applyStyle(s, placemarks)
        runCatching { EditLayers.install(s) }.onFailure { Log.e(TAG, "Edit overlay install failed", it) }
        runCatching { NavLayers.install(s) }.onFailure { Log.e(TAG, "Target overlay install failed", it) }
        runCatching { ShareLayers.install(s) }.onFailure { Log.e(TAG, "Share overlay install failed", it) }
        ShareLayers.showLabels(s, placemarks.labels)
        runCatching { MeasureLayers.install(s) }.onFailure { Log.e(TAG, "Measure overlay install failed", it) }
        runCatching { GridLayers.install(s) }.onFailure { Log.e(TAG, "Grid overlay install failed", it) }
        runCatching { AlertLayers.install(s) }.onFailure { Log.e(TAG, "Watch overlay install failed", it) }
        tracks?.let(::showTracks)
        showCursor(cursor)
        edit?.let { showEdit(it.first, it.second, it.third) }
        nav?.let { showTarget(it.first, it.second) }
        share?.let(::showShare)
        measure?.let { showMeasure(it.first, it.second, it.third) }
        grid?.let { showGrid(it.first, it.second) }
        GridLayers.setDashed(s, gridDashed)
        TrailLayers.install(s)
        TrailLayers.show(s, trail)
        watches?.let(::showWatches)
        m.addOnMapClickListener { point ->
            val drawing = onDrawTap
            if (drawing != null) {
                drawing(point.latitude, point.longitude)
                return@addOnMapClickListener true
            }
            memberAt(m, point)?.let {
                onMemberTap(it)
                return@addOnMapClickListener true
            }
            val ref = itemAt(m, point)
            onItemTap(ref)
            ref != null
        }
        m.addOnMapLongClickListener { point ->
            onLongPress(point.latitude, point.longitude)
            true
        }
    }

    /** The member whose disc is under [point], if any. */
    private fun memberAt(
        m: MapLibreMap,
        point: LatLng,
    ): Long? {
        val screen = m.projection.toScreenLocation(point)
        val reach = TAP_REACH_DP * appContext.resources.displayMetrics.density
        val box = RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
        return m
            .queryRenderedFeatures(box, ShareLayers.MEMBERS_LAYER)
            .firstOrNull()
            ?.getStringProperty("mid")
            ?.toLongOrNull()
    }

    /**
     * A tap on the centre point, [dx], [dy] px from the middle of the map: the member or the item drawn under the
     * finger opens as a tap on the map would open it. Returns false when there is none (the point opens its menu).
     */
    fun tapNearCentre(
        dx: Float,
        dy: Float,
    ): Boolean {
        val m = map ?: return false
        if (onDrawTap != null) return false
        val camera = liveCamera
        val centre = m.projection.toScreenLocation(LatLng(camera.lat, camera.lon))
        val point = m.projection.fromScreenLocation(android.graphics.PointF(centre.x + dx, centre.y + dy))
        memberAt(m, point)?.let {
            onMemberTap(it)
            return true
        }
        val ref = itemAt(m, point) ?: return false
        onItemTap(ref)
        return true
    }

    /** The item drawn under [point] (a finger is about 20 dp wide), points before lines. */
    private fun itemAt(
        m: MapLibreMap,
        point: LatLng,
    ): ItemRef? {
        val screen = m.projection.toScreenLocation(point)
        val reach = TAP_REACH_DP * appContext.resources.displayMetrics.density
        val box = RectF(screen.x - reach, screen.y - reach, screen.x + reach, screen.y + reach)
        for (layer in TrackOverlay.TAPPABLE) {
            val id = m.queryRenderedFeatures(box, layer).firstOrNull()?.getNumberProperty("id")?.toLong() ?: continue
            TrackOverlay.refOf(layer, id)?.let { return it }
        }
        return null
    }

    /** The area currently on screen, or `null` before the map is ready. */
    fun visibleArea(): ZoneShape.Rectangle? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return ZoneShape.Rectangle(b.getLonWest(), b.getLatSouth(), b.getLonEast(), b.getLatNorth())
    }

    /** Shows the stored coverage of the source [sourceId] over the map, or hides it when `null`. */
    fun showCoverage(sourceId: String?) {
        coverageSourceId = sourceId
        val s = style ?: return
        runCatching {
            StyleApplier.setCoverage(s, sourceId, mapping)
        }.onFailure { Log.e(TAG, "Coverage overlay failed", it) }
    }

    fun moveTo(camera: CameraState) {
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(position(camera)))
    }

    /** Moves the camera to [lat], [lon], at [zoom] when given, turned to [bearing] when given. */
    fun flyTo(
        lat: Double,
        lon: Double,
        zoom: Double? = null,
        bearing: Double? = null,
    ) {
        val m = map ?: return
        val target = LatLng(lat, lon)
        m.animateCamera(
            when {
                bearing != null -> {
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition
                            .Builder()
                            .target(target)
                            .zoom(zoom ?: m.cameraPosition.zoom)
                            .bearing(bearing)
                            .build(),
                    )
                }

                zoom == null -> {
                    CameraUpdateFactory.newLatLng(target)
                }

                else -> {
                    CameraUpdateFactory.newLatLngZoom(target, zoom)
                }
            },
        )
    }

    /** One zoom level in or out, from the + and − buttons. */
    fun zoomBy(levels: Double) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(levels))
    }

    /** Stops or restores every gesture on the map (the main menu's « Verrouiller l'écran »). */
    fun setGesturesEnabled(enabled: Boolean) {
        gesturesLocked = !enabled
        applyGestures()
    }

    /** « Style des grilles »: dashed or solid lines. */
    fun setGridDashed(dashed: Boolean) {
        gridDashed = dashed
        style?.let { GridLayers.setDashed(it, dashed) }
    }

    /** Brings the placemarks in line with the « Placemarks » settings, the target included. */
    fun applyPlacemarks(settings: PlacemarkSettings) {
        placemarks = settings
        style?.let {
            TrackOverlay.applyStyle(it, settings)
            ShareLayers.showLabels(it, settings.labels)
        }
        nav?.let { showTarget(it.first, it.second) }
    }

    /** Brings the gestures in line with the « Map canvas settings » (pinch zoom, manual rotation, perspective). */
    fun applyCanvas(settings: MapCanvasSettings) {
        canvas = settings
        applyGestures()
    }

    private fun applyGestures() {
        val ui = map?.uiSettings ?: return
        ui.setAllGesturesEnabled(!gesturesLocked)
        if (!gesturesLocked) {
            ui.isZoomGesturesEnabled = canvas.pinchZoom
            ui.isRotateGesturesEnabled = canvas.manualRotation
            ui.isTiltGesturesEnabled = canvas.perspective
        }
    }

    /** Turns the map so that the reference north ([offsetDegrees] from true north, « Nord de référence ») is up. */
    fun resetNorth(offsetDegrees: Double = 0.0) {
        map?.animateCamera(CameraUpdateFactory.bearingTo(offsetDegrees))
    }

    /** Switches the position behaviour; the caller must hold the location permission. */
    @SuppressLint("MissingPermission")
    fun applyLocationMode(mode: LocationMode) {
        val m = map ?: return
        val s = style ?: return
        val component = m.locationComponent
        if (!locationActivated) {
            val request =
                LocationEngineRequest
                    .Builder(LOCATION_INTERVAL_MS)
                    .setFastestInterval(LOCATION_FASTEST_INTERVAL_MS)
                    .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                    .build()
            component.activateLocationComponent(
                LocationComponentActivationOptions
                    .builder(appContext, s)
                    .useDefaultLocationEngine(true)
                    .locationEngineRequest(request)
                    .build(),
            )
            component.addOnCameraTrackingChangedListener(
                object : OnCameraTrackingChangedListener {
                    override fun onCameraTrackingDismissed() {
                        // The user dragged the map: stop following, and come back to the mode if the auto relock is on.
                        if (locationMode != LocationMode.FREE) {
                            followMode = locationMode
                            relockArmed = true
                        }
                        locationMode = LocationMode.FREE
                    }

                    override fun onCameraTrackingChanged(currentMode: Int) = Unit
                },
            )
            locationActivated = true
        }
        component.isLocationComponentEnabled = true
        component.cameraMode =
            when (mode) {
                LocationMode.FREE -> CameraMode.NONE
                LocationMode.FOLLOW -> CameraMode.TRACKING
                LocationMode.FOLLOW_COMPASS -> CameraMode.TRACKING_COMPASS
            }
        component.renderMode = puckMode()
        if (mode == LocationMode.FREE) relockArmed = false
        locationMode = mode
        locationOn = true
    }

    /** The last position of the marker while the real-time position is on, or null. */
    @SuppressLint("MissingPermission")
    fun lastPosition(): Pair<Double, Double>? {
        if (!locationOn || !locationActivated) return null
        return map?.locationComponent?.lastKnownLocation?.let { it.latitude to it.longitude }
    }

    /**
     * Turns the position off (« Position en temps réel », P11.9): the marker stops and the camera no longer follows it.
     * The track recorder and the sharing have their own location and carry on. An explorer mode that was on ends too:
     * the map goes back flat and north up ([northDegrees] from true north, the reference north).
     */
    @SuppressLint("MissingPermission")
    fun disableLocation(northDegrees: Double) {
        val m = map ?: return
        if (!locationOn) return
        val wasExplorer = locationMode == LocationMode.FOLLOW_COMPASS
        relockHandler.removeCallbacks(relock)
        relockArmed = false
        m.locationComponent.isLocationComponentEnabled = false
        locationMode = LocationMode.FREE
        locationOn = false
        if (wasExplorer) m.animateCamera(flatNorth(northDegrees))
    }

    /**
     * The explorer mode (P11.9), as the two-finger tilt starts it: the camera follows the position, turns with the
     * heading and is tilted into perspective. The caller holds the location permission, as for [applyLocationMode].
     */
    fun enterExplorer() {
        applyLocationMode(LocationMode.FOLLOW_COMPASS)
        map?.animateCamera(CameraUpdateFactory.tiltTo(EXPLORER_VIEW_TILT_DEGREES))
    }

    /** Leaves the explorer mode: the camera keeps following the position, but flat and north up. */
    fun leaveExplorer(northDegrees: Double) {
        applyLocationMode(LocationMode.FOLLOW)
        map?.animateCamera(flatNorth(northDegrees))
    }

    /**
     * « La carte suit » of the positioning panel (R4, P14.10): [mode], tilted into perspective when [explorer]. Leaving
     * the perspective lays the map flat; it also goes back north up, but for the compass, which turns it anyway.
     */
    fun applyFollow(
        mode: LocationMode,
        explorer: Boolean,
        northDegrees: Double,
    ) {
        if (explorer) {
            enterExplorer()
            return
        }
        applyLocationMode(mode)
        if (tilted) {
            map?.animateCamera(
                if (mode == LocationMode.FOLLOW_COMPASS) CameraUpdateFactory.tiltTo(0.0) else flatNorth(northDegrees),
            )
        }
    }

    private fun flatNorth(northDegrees: Double): CameraUpdate =
        CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().tilt(0.0).bearing(northDegrees).build())

    /**
     * The look of the position marker (P11.8): the compass cone when the heading is drawn as a cone, else the course
     * arrow when the GNSS course is shown, else a plain dot.
     */
    private fun puckMode(): Int =
        when {
            headingDisplay == HeadingDisplay.ARROW || headingDisplay == HeadingDisplay.BOTH -> RenderMode.COMPASS
            positioning.course == CourseDisplay.ARROW -> RenderMode.GPS
            else -> RenderMode.NORMAL
        }

    /** Brings the position marker, the camera after a pause and the trail in line with the settings (P11.8). */
    fun applyPositioning(
        settings: PositionSettings,
        heading: HeadingDisplay,
    ) {
        positioning = settings
        headingDisplay = heading
        if (!settings.autoRelock) relockHandler.removeCallbacks(relock)
        if (locationOn) applyLocationMode(locationMode)
    }

    /** The last positions of the device, oldest first, as latitude and longitude pairs. */
    fun showTrail(points: List<Pair<Double, Double>>) {
        trail = points
        style?.let { TrailLayers.show(it, points) }
    }

    private fun readCamera(m: MapLibreMap): CameraState {
        val p = m.cameraPosition
        val target = p.target ?: return liveCamera
        return CameraState(target.latitude, target.longitude, p.zoom, p.bearing)
    }

    private fun position(camera: CameraState) =
        CameraPosition
            .Builder()
            .target(LatLng(camera.lat, camera.lon))
            .zoom(camera.zoom)
            .bearing(camera.bearing)
            .build()

    private companion object {
        const val TAG = "ToolMap.Map"
        const val MAX_CAMERA_ZOOM = 22.0
        const val MIN_FIT_SPAN = 1e-4
        const val POINT_ZOOM = 15.0
        const val FIT_PADDING_DP = 48
        const val TAP_REACH_DP = 20
        const val LOCATION_INTERVAL_MS = 1000L
        const val LOCATION_FASTEST_INTERVAL_MS = 500L

        /** Pitch from which the map is in perspective, and the explorer mode starts (P11.8). */
        const val EXPLORER_TILT_DEGREES = 20.0

        /** Pitch of the explorer mode when the positioning panel starts it (P11.9); MapLibre allows up to 60. */
        const val EXPLORER_VIEW_TILT_DEGREES = 60.0

        /** Above this tilt the map counts as in perspective, for the choice shown in the positioning panel. */
        private const val TILTED_DEGREES = 1.0

        /** « Recentrage automatique »: the camera goes back to the position after this idle time (P11.8). */
        const val RELOCK_DELAY_MS = 10_000L
    }
}
