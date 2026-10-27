package fr.toolmap.map

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.toolmap.BuildConfig
import fr.toolmap.R
import fr.toolmap.about.HelpSection
import fr.toolmap.alert.AlertController
import fr.toolmap.alert.FollowHud
import fr.toolmap.alert.WatchDialog
import fr.toolmap.altitude.AltitudeController
import fr.toolmap.altitude.AltitudeDialog
import fr.toolmap.core.alert.AlertGeoJson
import fr.toolmap.core.altitude.AltitudeSource
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.ScreenGrid
import fr.toolmap.core.geo.ScreenGridPlan
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.layers.StackToStyle
import fr.toolmap.core.maps.AreaSelection
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.print.CameraFit
import fr.toolmap.core.print.PrintViewport
import fr.toolmap.core.record.PositionTrail
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.RecorderState
import fr.toolmap.core.settings.BearingFormat
import fr.toolmap.core.settings.GridStyle
import fr.toolmap.core.settings.HeadingDisplay
import fr.toolmap.core.settings.LongPressAction
import fr.toolmap.core.settings.NorthReferenceMath
import fr.toolmap.core.settings.barometerOn
import fr.toolmap.core.settings.withBarometer
import fr.toolmap.core.track.NameDates
import fr.toolmap.core.track.TrackFormat
import fr.toolmap.core.track.Waypoint
import fr.toolmap.core.track.WaypointNames
import fr.toolmap.crash.CrashReporter
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackSelection
import fr.toolmap.download.ZoneEstimate
import fr.toolmap.download.formatBytes
import fr.toolmap.draw.DrawBar
import fr.toolmap.draw.DrawViewModel
import fr.toolmap.draw.FreehandLayer
import fr.toolmap.draw.RouteHooks
import fr.toolmap.draw.RouteNoticeSnackbar
import fr.toolmap.draw.RouteNoticeVisuals
import fr.toolmap.draw.RouteStatsCard
import fr.toolmap.draw.SegmentChooser
import fr.toolmap.draw.installBRouter
import fr.toolmap.draw.openBRouter
import fr.toolmap.maps.DisplayConditionsPanel
import fr.toolmap.maps.HelpDialog
import fr.toolmap.maps.MapsIcons
import fr.toolmap.maps.MapsPage
import fr.toolmap.maps.MapsRootActions
import fr.toolmap.maps.MapsRootPanel
import fr.toolmap.maps.MapsViewModel
import fr.toolmap.maps.SaveAreaOverlay
import fr.toolmap.maps.SaveAreaPanel
import fr.toolmap.maps.SaveRefusal
import fr.toolmap.maps.StackThumbnails
import fr.toolmap.maps.tilesText
import fr.toolmap.measure.MeasureBar
import fr.toolmap.measure.MeasureViewModel
import fr.toolmap.navigate.NavigationViewModel
import fr.toolmap.navigate.TargetHud
import fr.toolmap.perf.MapPerfLog
import fr.toolmap.print.PrintActions
import fr.toolmap.print.PrintConfirmSheet
import fr.toolmap.print.PrintFrameActions
import fr.toolmap.print.PrintFrameBar
import fr.toolmap.print.PrintFrameOverlay
import fr.toolmap.print.PrintOutcome
import fr.toolmap.print.PrintOutcomeDialog
import fr.toolmap.print.PrintPagesOverlay
import fr.toolmap.print.PrintSetupDialog
import fr.toolmap.print.PrintStage
import fr.toolmap.print.PrintTrackActions
import fr.toolmap.print.PrintUi
import fr.toolmap.print.PrintViewModel
import fr.toolmap.print.pageCorners
import fr.toolmap.record.RecordStartDialog
import fr.toolmap.record.RecordingChip
import fr.toolmap.record.RecordingRecoveryDialog
import fr.toolmap.record.RecordingViewModel
import fr.toolmap.record.SatelliteCount
import fr.toolmap.record.labelRes
import fr.toolmap.share.MemberDialog
import fr.toolmap.share.ShareHud
import fr.toolmap.share.ShareViewModel
import fr.toolmap.share.serverUp
import fr.toolmap.sources.ImportDialog
import fr.toolmap.tracks.TappedItemSheet
import fr.toolmap.tracks.TrackSharing
import fr.toolmap.tracks.TracksViewModel
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.rememberNotificationAsk
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.waypoints.WaypointDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.roundToInt

/** The main screen: the map and its controls (position, compass, scale, layers, menu). */
@Composable
fun MapScreen(
    vm: MapViewModel,
    onOpenDiagnostics: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenStorage: () -> Unit,
    /** The download form for a zone drawn on the map (the surface of the measure tool, F10). */
    onDownloadZone: (ZoneShape) -> Unit = {},
    maps: MapsViewModel,
    onOpenAvailable: () -> Unit,
    tracks: TracksViewModel,
    onOpenTracks: () -> Unit,
    onOpenTrackDetail: (Long) -> Unit,
    /** « Importer un fichier… » of the tracks panel: the picker of track files (GPX, KML, GeoJSON). */
    onImportTracks: () -> Unit,
    draw: DrawViewModel,
    recording: RecordingViewModel,
    navigation: NavigationViewModel,
    print: PrintViewModel,
    share: ShareViewModel,
    onOpenShare: () -> Unit,
    measure: MeasureViewModel,
    onOpenSettings: () -> Unit,
    /** The help page, at the section of the panel whose « ? » was touched (`null`: the main menu, at the top). */
    onOpenAbout: (HelpSection?) -> Unit,
    onOpenSearch: () -> Unit,
    alerts: AlertController,
    altitude: AltitudeController,
    /** A full screen is open over the map: it is kept alive and stopped, not rebuilt on return (PF1). */
    covered: Boolean = false,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by vm.state.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val importPreview by vm.importPreview.collectAsStateWithLifecycle()
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val trackData by tracks.mapData.collectAsStateWithLifecycle()
    val trackCursor by tracks.cursor.collectAsStateWithLifecycle()
    val mapRequest by tracks.mapRequest.collectAsStateWithLifecycle()
    val tapped by tracks.tapped.collectAsStateWithLifecycle()
    val library by tracks.library.collectAsStateWithLifecycle()
    val drawUi by draw.ui.collectAsStateWithLifecycle()
    val editOverlay by draw.overlay.collectAsStateWithLifecycle()
    val drawCursor by draw.cursor.collectAsStateWithLifecycle()
    val recordingUi by recording.state.collectAsStateWithLifecycle()
    val navTarget by navigation.target.collectAsStateWithLifecycle()
    val navGuidance by navigation.guidance.collectAsStateWithLifecycle()
    val navPosition by navigation.position.collectAsStateWithLifecycle()
    val gpsFailed by navigation.gpsFailed.collectAsStateWithLifecycle()
    val alertUi by alerts.ui.collectAsStateWithLifecycle()
    val altitudeStatus by altitude.status.collectAsStateWithLifecycle()
    var showAltitude by remember { mutableStateOf(false) }
    var watchDraft by remember { mutableStateOf<ItemRef?>(null) }
    val printUi by print.ui.collectAsStateWithLifecycle()
    val shareUi by share.ui.collectAsStateWithLifecycle()
    val selectedMember by share.selected.collectAsStateWithLifecycle()
    val measureUi by measure.ui.collectAsStateWithLifecycle()
    val measureOverlay by measure.overlay.collectAsStateWithLifecycle()
    val appSettings = LocalAppSettings.current
    val formats = LocalFormats.current
    val targetPointName = stringResource(R.string.target_point_name)

    val controller = remember { MapController(context.applicationContext) }
    // The reference north « Nord de référence »: the map and the compass turn with it (P11.8). It is read where it is
    // used (P15.7): read here, the whole screen would be composed again at every frame of a camera move.
    val northSetting by rememberUpdatedState(appSettings.north)
    val northOffset by remember(controller) {
        derivedStateOf {
            NorthOffsets.offsetDegrees(northSetting, controller.liveCamera.lat, controller.liveCamera.lon)
        }
    }
    // « Affichage du cap actuel » as a number: the magnetic heading of the phone, turned to the true north and then to
    // the reference north.
    val showHeadingNumber = appSettings.heading == HeadingDisplay.NUMBER || appSettings.heading == HeadingDisplay.BOTH
    val headingSensor = remember { HeadingSensor(context) }
    val magneticHeading by produceState<Double?>(null, showHeadingNumber, covered) {
        if (showHeadingNumber && !covered) headingSensor.magneticHeadings().collect { value = it }
    }
    val currentFormats by rememberUpdatedState(formats)
    val headingText by remember(controller) {
        derivedStateOf {
            magneticHeading?.let { magnetic ->
                val declination =
                    NorthOffsets.declinationDegrees(controller.liveCamera.lat, controller.liveCamera.lon)
                val relative =
                    NorthReferenceMath.relative(BearingFormat.normalize(magnetic + declination), northOffset)
                "${currentFormats.bearing(relative)} ${Navigation.compassPoint(relative)}"
            }
        }
    }
    // The satellites seen and used by the GPS, listened to only while the location is on.
    val satellites by produceState<SatelliteCount?>(null, controller.locationOn, covered) {
        value = null
        if (controller.locationOn && !covered) vm.satellites().collect { value = it }
    }
    // The zoom of the base map, the "/18" after the zoom shown in the top left corner.
    val baseMaxZoom =
        state.stack.baseLayer(vm::isBase)?.sourceId?.let { id -> sources.firstOrNull { it.id == id }?.maxZoom }
    val mapView = rememberMapViewWithLifecycle(covered)
    // Timing of the return to the map after a full screen (debug and benchmark builds only).
    val perfLog = remember { arrayOfNulls<MapPerfLog>(1) }
    var wasCovered by remember { mutableStateOf(false) }
    LaunchedEffect(covered) {
        if (covered) {
            wasCovered = true
        } else if (wasCovered) {
            wasCovered = false
            perfLog[0]?.returned()
        }
    }
    val mapsPage by maps.page.collectAsStateWithLifecycle()
    val saveArea by maps.area.collectAsStateWithLifecycle()
    var showAreaHelp by remember { mutableStateOf(false) }
    var saveAreaPanelHeight by remember { mutableIntStateOf(0) }
    val conditionsLayerId by maps.conditionsLayer.collectAsStateWithLifecycle()
    val conditionsLayer = conditionsLayerId?.let { state.stack[it] }?.takeIf { mapsPage == MapsPage.CONDITIONS }
    var conditionsPanelHeight by remember { mutableIntStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    // The positioning panel of the position button (P11.9): it closes like the main menu does.
    var showPositioning by remember { mutableStateOf(false) }
    // The tracks and waypoints panel of the flag (P14.3), and the quick menu of the centre point (F1, ADR-043).
    var showTracks by remember { mutableStateOf(false) }
    var pointMenu by remember { mutableStateOf(false) }
    // The menus and panels that lead to a full screen are closed behind it, as when the map was rebuilt on return.
    LaunchedEffect(covered) {
        if (covered) {
            showMenu = false
            showPositioning = false
            showTracks = false
            pointMenu = false
        }
    }
    // « Masquer les commandes » and « Verrouiller l'écran » of the main menu (P11.6), kept across a rotation.
    var chromeHidden by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var barHeight by remember { mutableIntStateOf(0) }
    var waypointDraft by remember { mutableStateOf<WaypointDraft?>(null) }
    var showRecordStart by remember { mutableStateOf(false) }
    var recoveryDismissed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // The height of the panel open above the bar, so that a message stands over it rather than on its last rows.
    var barPanelHeight by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var drawBarHeight by remember { mutableIntStateOf(0) }
    var printPanelHeight by remember { mutableIntStateOf(0) }
    var measureBarHeight by remember { mutableIntStateOf(0) }
    var gridPlan by remember { mutableStateOf<ScreenGridPlan?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    // Where the info boxes end on screen (pixels), for the zone selection to start clear of them (V2).
    var infoBoxesEnd by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val bottomClearance =
        when {
            mapsPage == MapsPage.SAVE_AREA -> with(density) { saveAreaPanelHeight.toDp() }
            conditionsLayer != null -> with(density) { conditionsPanelHeight.toDp() }
            printUi.active -> with(density) { printPanelHeight.toDp() }
            drawUi.active && drawUi.chooseSegment == null -> with(density) { drawBarHeight.toDp() }
            measureUi.active -> with(density) { measureBarHeight.toDp() }
            else -> 0.dp
        }

    // Tiles are drawn about one tile pixel per screen pixel, like AlpineQuest, unless the settings say otherwise
    // (ADR-032): this also sets the zoom shown to the user.
    val tileMagnification by vm.tileMagnification.collectAsStateWithLifecycle()
    val mapping =
        remember(density.density, tileMagnification) {
            ZoomMapping.forScreen(density.density.toDouble(), tileMagnification)
        }

    // The style is rebuilt incrementally from the layer stack whenever the stack or the catalogue changes.
    // The night mode darkens the maps alone (F14): the tracks, the waypoints and the menus keep their colours.
    val darkening = appSettings.maps.darkening
    val desired =
        remember(state.stack, sources, mapping, darkening) {
            StackToStyle.desired(state.stack, mapping, darkening) { id -> sources.any { it.id == id } }
        }
    val scaling by vm.scaling.collectAsStateWithLifecycle()
    LaunchedEffect(controller.isReady, desired, sources, scaling, mapping) {
        if (controller.isReady) {
            perfLog[0]?.restyled()
            controller.sync(desired, scaling, mapping) { id -> sources.firstOrNull { it.id == id } }
        }
    }
    // « Verrouiller l'écran » stops every gesture on the map until the locked screen is unlocked.
    LaunchedEffect(controller.isReady, locked) {
        if (controller.isReady) controller.setGesturesEnabled(!locked)
    }
    // « Positionnement » and « Orientation »: the position marker, the camera after a pause, the trail (P11.8).
    LaunchedEffect(controller.isReady, appSettings.positioning, appSettings.heading) {
        if (controller.isReady) controller.applyPositioning(appSettings.positioning, appSettings.heading)
    }
    val positionTrail = remember { PositionTrail() }
    LaunchedEffect(navPosition, appSettings.positioning.showTrail) {
        val now = System.currentTimeMillis()
        navPosition?.let { positionTrail.add(it.lat, it.lon, now) }
        if (controller.isReady) {
            val points =
                if (appSettings.positioning.showTrail) {
                    positionTrail.points(now).map { it.lat to it.lon }
                } else {
                    emptyList()
                }
            controller.showTrail(points)
        }
    }
    // The « Placemarks » settings: sizes, arrows, contours, borders, shadows and the target colour.
    LaunchedEffect(controller.isReady, appSettings.placemarks) {
        if (controller.isReady) controller.applyPlacemarks(appSettings.placemarks)
    }
    LaunchedEffect(appSettings.placemarks.defaultColor) {
        draw.setDefaultColor(appSettings.placemarks.defaultColor)
    }
    // The gestures of the « Map canvas settings » (pinch zoom, manual rotation, perspective).
    LaunchedEffect(controller.isReady, appSettings.canvas) {
        if (controller.isReady) controller.applyCanvas(appSettings.canvas)
    }

    // The coverage overlay follows the base map (bottom layer) of the stack.
    val coverageSource = if (coverage) state.stack.baseLayer(vm::isBase)?.sourceId else null
    LaunchedEffect(controller.isReady, coverageSource) {
        if (controller.isReady) controller.showCoverage(coverageSource)
    }

    // Tracks, routes and waypoints are data of two GeoJSON sources, refreshed whenever the library changes.
    LaunchedEffect(controller.isReady, trackData) {
        if (controller.isReady) trackData?.let(controller::showTracks)
    }
    LaunchedEffect(controller.isReady, trackCursor, drawCursor) {
        if (controller.isReady) controller.showCursor(drawCursor ?: trackCursor)
    }
    LaunchedEffect(controller.isReady, mapRequest) {
        val request = mapRequest
        if (controller.isReady && request != null) {
            controller.fitBounds(request.south, request.west, request.north, request.east)
            tracks.fitMapTo(null)
        }
    }

    // The measure tool's line, fill and points; the coordinate grid follows the visible region.
    LaunchedEffect(controller.isReady, measureOverlay) {
        if (controller.isReady) {
            controller.showMeasure(
                measureOverlay?.lineGeoJson,
                measureOverlay?.fillGeoJson,
                measureOverlay?.verticesGeoJson,
            )
        }
    }
    // The altitude chip follows the position, but only while the screen is on: the sensor and the GPS stop with it.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(controller.locationOn, lifecycleOwner, appSettings.positioning.disableInBackground, covered) {
        if (controller.locationOn && !covered) {
            if (appSettings.positioning.disableInBackground) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { altitude.watch() }
            } else {
                altitude.watch()
            }
        }
    }
    LaunchedEffect(controller.isReady, alertUi.places) {
        if (controller.isReady) controller.showWatches(AlertGeoJson.circles(alertUi.places))
    }
    LaunchedEffect(controller.isReady, appSettings.canvas.gridStyle) {
        if (controller.isReady) controller.setGridDashed(appSettings.canvas.gridStyle == GridStyle.DASHED)
    }
    LaunchedEffect(controller.isReady, appSettings.screenGrid, appSettings.canvas.gridDensity, state.camera) {
        if (!controller.isReady) return@LaunchedEffect
        val area = controller.visibleArea()
        val plan =
            if (area == null) {
                ScreenGridPlan.none(appSettings.screenGrid)
            } else {
                withContext(Dispatchers.Default) {
                    ScreenGrid.plan(
                        appSettings.screenGrid,
                        Bounds(area.west, area.south, area.east, area.north),
                        appSettings.canvas.gridDensity.spacing,
                    )
                }
            }
        gridPlan = plan
        controller.showGrid(plan.geoJson, plan.labelsGeoJson)
    }
    LaunchedEffect(measure) {
        measure.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.resId, *message.args.toTypedArray()))
        }
    }

    // The line being drawn, and the way to the target.
    LaunchedEffect(controller.isReady, editOverlay) {
        if (controller.isReady) {
            controller.showEdit(
                editOverlay?.lineGeoJson,
                editOverlay?.handlesGeoJson,
                editOverlay?.icons ?: emptySet(),
            )
        }
    }
    // The labels of the legs drawn by hand or being computed, in the language of the app (P19).
    val legComputing = stringResource(R.string.route_leg_computing)
    val legByHand = stringResource(R.string.route_leg_by_hand)
    LaunchedEffect(draw, legComputing, legByHand) { draw.setLegLabels(legComputing, legByHand) }
    // The other members of the sharing group: keyed on the documents so that an unchanged second changes nothing.
    val shareData =
        remember(shareUi.members, shareUi.sos, shareUi.settings.showTrails) { share.mapData(shareUi) }
    LaunchedEffect(controller.isReady, shareData.membersGeoJson, shareData.trailsGeoJson) {
        if (controller.isReady) controller.showShare(shareData)
    }
    LaunchedEffect(share) {
        share.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.resId, *message.args.toTypedArray()))
        }
    }
    LaunchedEffect(controller.isReady, navTarget, navPosition) {
        if (controller.isReady) {
            controller.showTarget(
                navPosition?.let { it.lat to it.lon },
                navTarget?.let { it.lat to it.lon },
            )
        }
    }
    SideEffect {
        controller.onDrawTap =
            when {
                drawUi.active && drawUi.chooseSegment == null && !drawUi.freehand -> {
                    { lat, lon -> draw.onMapTap(lat, lon, controller.metersFor(TAP_REACH_DP)) }
                }

                measureUi.active -> {
                    { lat, lon -> measure.addAt(lat, lon) }
                }

                else -> {
                    null
                }
            }
        // A locked screen takes no gesture at all: a long press in a pocket must not add a waypoint.
        controller.onLongPress = { lat, lon ->
            if (!locked && !drawUi.active && !measureUi.active) {
                when (appSettings.canvas.longPress) {
                    LongPressAction.NONE -> {
                        Unit
                    }

                    LongPressAction.WAYPOINT -> {
                        waypointDraft = WaypointDraft(null, lat to lon)
                    }

                    LongPressAction.MEASURE -> {
                        measure.open()
                        measure.addAt(lat, lon)
                    }

                    LongPressAction.TARGET -> {
                        navigation.setTarget(NavTarget(lat, lon, targetPointName))
                    }
                }
            }
        }
    }

    // The print shows the layers on screen.
    LaunchedEffect(printUi.active, state.stack) {
        if (printUi.active) print.onLayers(state.stack.layers)
    }
    LaunchedEffect(print) {
        print.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.resId, *message.args.toTypedArray()))
        }
    }
    BackHandler(enabled = printUi.active) {
        if (printUi.stage == PrintStage.CONFIRM) print.backToFrame() else print.close()
    }
    BackHandler(enabled = mapsPage == MapsPage.SAVE_AREA) { maps.close() }
    // Back from the conditions comes back to the maps panel, the line of the layer still unfolded (P29.3).
    BackHandler(enabled = mapsPage == MapsPage.CONDITIONS) { maps.closeConditions() }
    SideEffect {
        maps.thumbnails.around =
            state.camera.let { LonLatZoom(it.lon, it.lat, mapping.toTileZoom(it.zoom).toInt().coerceAtLeast(0)) }
    }
    val createPdf =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) print.export(uri)
        }
    // « Exporter » of the sheet of a track, a route or a waypoint (F8): the GPX in a file the user picks.
    var pendingGpx by remember { mutableStateOf<TrackSelection?>(null) }
    val saveGpx =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TrackSharing.GPX_MIME)) { uri ->
            val selection = pendingGpx
            pendingGpx = null
            if (uri != null && selection != null) tracks.exportTo(context.contentResolver, uri, selection)
        }
    // The frame takes about 70 % of the map left between the band of chips and the buttons (P17, Q4), centred on it.
    LaunchedEffect(printUi.fitRequest) {
        val ui = printUi
        if (ui.stage != PrintStage.FRAME || ui.alongTrack || rootSize == IntSize.Zero) return@LaunchedEffect
        val widthDp = rootSize.width / density.density.toDouble()
        val heightDp = rootSize.height / density.density.toDouble()
        val availableHeight = (heightDp - PRINT_FIT_RESERVE_DP).coerceAtLeast(PRINT_MIN_FIT_DP)
        val zoom =
            PrintViewport.zoomToFit(
                ui.template.page,
                ui.template.scale,
                ui.centerLat,
                widthDp * PRINT_FIT_RATIO,
                availableHeight * PRINT_FIT_RATIO,
            )
        controller.flyTo(ui.centerLat, ui.centerLon, zoom)
    }
    // The pages along a track (F9) take the same room, north up, once they are counted for the size and scale asked.
    var fittedPages by remember { mutableIntStateOf(-1) }
    LaunchedEffect(printUi.fitRequest, printUi.plan) {
        val ui = printUi
        val plan = ui.plan
        if (ui.stage != PrintStage.FRAME || !ui.alongTrack || fittedPages == ui.fitRequest) return@LaunchedEffect
        if (plan == null || plan.template != ui.template.sanitized() || rootSize == IntSize.Zero) return@LaunchedEffect
        val widthDp = rootSize.width / density.density.toDouble()
        val heightDp = rootSize.height / density.density.toDouble()
        val availableHeight = (heightDp - PRINT_FIT_RESERVE_DP).coerceAtLeast(PRINT_MIN_FIT_DP)
        val fit =
            PrintViewport.fit(
                plan.pageCorners().flatten(),
                widthDp * PRINT_FIT_RATIO,
                availableHeight * PRINT_FIT_RATIO,
            ) ?: return@LaunchedEffect
        fittedPages = ui.fitRequest
        controller.flyTo(fit.lat, fit.lon, fit.zoom, bearing = northOffset)
    }
    // On the confirmation sheet the frame stays in sight, fitted to the map left above the sheet. The short wait lets
    // the sheet take its height (it replaces the buttons of the frame, measured in the same place).
    LaunchedEffect(printUi.stage, printPanelHeight) {
        val ui = printUi
        if (ui.stage != PrintStage.CONFIRM || rootSize == IntSize.Zero || printPanelHeight == 0) return@LaunchedEffect
        delay(PRINT_CONFIRM_SETTLE_MS)
        val widthDp = rootSize.width / density.density.toDouble()
        val heightDp = rootSize.height / density.density.toDouble()
        val sheetDp = printPanelHeight / density.density.toDouble()
        val availableWidth = (widthDp - 2 * PRINT_CONFIRM_SIDE_DP).coerceAtLeast(PRINT_MIN_FIT_DP)
        val availableHeight =
            (heightDp - sheetDp - PRINT_CONFIRM_TOP_DP - PRINT_CONFIRM_BOTTOM_DP).coerceAtLeast(PRINT_MIN_FIT_DP)
        // A print along a track keeps all its pages in sight (F9).
        val pages = if (ui.alongTrack) ui.plan?.pageCorners()?.flatten() else null
        val fit =
            if (pages != null) {
                PrintViewport.fit(pages, availableWidth, availableHeight) ?: return@LaunchedEffect
            } else {
                CameraFit(
                    ui.centerLat,
                    ui.centerLon,
                    PrintViewport.zoomToFit(
                        ui.template.page,
                        ui.template.scale,
                        ui.centerLat,
                        availableWidth,
                        availableHeight,
                    ),
                )
            }
        // The middle of the room left is (sheet + bottom - top) / 2 above the middle of the screen.
        val shiftDp = (sheetDp + PRINT_CONFIRM_BOTTOM_DP - PRINT_CONFIRM_TOP_DP) / 2
        val shiftLat = ScaleBar.metersPerDp(fit.lat, fit.zoom) * shiftDp / METERS_PER_DEGREE_OF_LATITUDE
        controller.flyTo(fit.lat - shiftLat, fit.lon, fit.zoom)
    }
    val printActions =
        remember(print) {
            PrintActions(
                onUpdate = print::update,
                onRotate = print::rotate,
                onChooseZone = print::chooseZone,
                onNext = print::confirm,
                onBack = print::backToFrame,
                onToggleMore = print::toggleMoreOptions,
                onClose = print::close,
                onDownload = print::downloadMissing,
                onExport = { createPdf.launch(print.suggestedFileName()) },
                onCancelJob = print::cancelJob,
            )
        }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.resId, *message.args.toTypedArray()))
        }
    }
    // The messages of the tracks (« Fichier GPX écrit » of F8) while the map is in front: the explorer shows its own.
    LaunchedEffect(tracks, covered) {
        if (covered) return@LaunchedEffect
        tracks.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.resId, *message.args.toTypedArray()))
        }
    }
    LaunchedEffect(draw) {
        draw.messages.collect { message ->
            snackbar.showSnackbar(resources.getString(message.resId, *message.args.toTypedArray()))
        }
    }
    LaunchedEffect(draw) {
        draw.notices.collect { notice -> snackbar.showSnackbar(RouteNoticeVisuals.of(resources, notice)) }
    }
    // The end of a recording says where the track went, and « Reprendre » opens it again in a new segment (P29.5): a
    // stop by mistake is undone without a question first.
    LaunchedEffect(recordingUi.finishedTrackId) {
        val id = recordingUi.finishedTrackId
        if (id != null) {
            val answer =
                snackbar.showSnackbar(
                    resources.getString(R.string.record_finished),
                    actionLabel = resources.getString(R.string.record_resume),
                    duration = SnackbarDuration.Long,
                )
            recording.acknowledgeFinished()
            if (answer == SnackbarResult.ActionPerformed) recording.reopen(id)
        }
    }
    // « Avertissement de perte de signal » (P11.8): a message when the GNSS has been silent for a minute, and when it is back.
    val signalLostText = stringResource(R.string.settings_lost_signal_notice)
    val signalBackText = stringResource(R.string.settings_lost_signal_back)
    var signalWasLost by remember { mutableStateOf(false) }
    LaunchedEffect(recordingUi.signalLost) {
        val wasLost = signalWasLost
        signalWasLost = recordingUi.signalLost
        if (recordingUi.signalLost) {
            snackbar.showSnackbar(signalLostText)
        } else if (wasLost) {
            snackbar.showSnackbar(signalBackText)
        }
    }
    // « Mode tableau de bord » (P11.8): the recording pauses when the app is hidden and resumes when it is back.
    val dashboardMode by rememberUpdatedState(appSettings.recorder.dashboardMode)
    val recordingPhase by rememberUpdatedState(recordingUi.phase)
    var pausedByDashboard by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && dashboardMode && recordingPhase == RecorderState.RECORDING) {
                    recording.pause()
                    pausedByDashboard = true
                } else if (event == Lifecycle.Event.ON_START && pausedByDashboard) {
                    pausedByDashboard = false
                    recording.resume()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The action waiting for the location permission: it runs once the system has answered (P11.9).
    var locationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = locationAction
            locationAction = null
            if (grants.values.any { it }) {
                action?.invoke()
            } else {
                vm.notify(R.string.location_permission_denied)
            }
        }
    // Runs [action] with the location permission, asking for it first when the phone does not have it yet.
    val withLocationPermission: (() -> Unit) -> Unit = { action ->
        val granted =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        if (granted) {
            action()
        } else {
            locationAction = action
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
    // A long press on the position button cycles the way the map follows the position, as before P11.9.
    val onPositionLongPress: () -> Unit = {
        withLocationPermission { controller.applyLocationMode(controller.locationMode.next()) }
    }
    // The ticks of the positioning panel (P11.9), each one doing what the matching line of AlpineQuest does.
    val onRealTime: (Boolean) -> Unit = { on ->
        if (on) {
            withLocationPermission { controller.applyLocationMode(LocationMode.FOLLOW) }
        } else {
            controller.disableLocation(northOffset)
        }
    }
    // « La carte suit » (R4): the four ways to follow, the position turned on first when it is off.
    val onFollow: (FollowChoice) -> Unit = { choice ->
        withLocationPermission {
            when (choice) {
                FollowChoice.FREE -> {
                    controller.applyFollow(LocationMode.FREE, explorer = false, northOffset)
                }

                FollowChoice.FOLLOW -> {
                    controller.applyFollow(LocationMode.FOLLOW, explorer = false, northOffset)
                }

                FollowChoice.COMPASS -> {
                    controller.applyFollow(
                        LocationMode.FOLLOW_COMPASS,
                        explorer = false,
                        northOffset,
                    )
                }

                FollowChoice.EXPLORER -> {
                    controller.applyFollow(
                        LocationMode.FOLLOW_COMPASS,
                        explorer = true,
                        northOffset,
                    )
                }
            }
        }
    }
    val onRecorder: (Boolean) -> Unit = { on ->
        if (on) {
            showRecordStart = true
        } else {
            recording.stop()
        }
    }
    // Recording needs the precise location; the notification permission (API 33+) only affects the notification.
    val recordPermissions =
        remember {
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        }
    var pendingRecord by remember { mutableStateOf<Pair<String, RecorderProfile>?>(null) }
    val askNotifications = rememberNotificationAsk(R.string.background_ask_recording)
    val recordPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val pending = pendingRecord
            pendingRecord = null
            if (pending != null && grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                recording.start(pending.first, pending.second)
                askNotifications()
            } else if (pending != null) {
                vm.notify(R.string.record_permission_denied)
            }
        }
    val startWithPermission = { name: String, profile: RecorderProfile ->
        val fine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (fine) {
            recording.start(name, profile)
            askNotifications()
        } else {
            pendingRecord = name to profile
            recordPermissionLauncher.launch(recordPermissions)
        }
    }
    val attribution =
        remember(state.stack, sources) {
            state.stack.layers
                .filter { it.visible && it.opacity > 0f }
                .mapNotNull { layer -> sources.firstOrNull { it.id == layer.sourceId }?.copyright }
                .distinct()
                .joinToString(" · ")
        }

    // The main menu (P11.6): what it shows, and what its commands and options do. A command closes the menu.
    val workspaces by vm.favorites.collectAsStateWithLifecycle()
    // The maps shown, top first, with their names: the block of the main menu and its thumbnails (P14.10).
    val shownMaps =
        state.stack.topToBottom().map { layer ->
            layer.sourceId to (sources.firstOrNull { it.id == layer.sourceId }?.name ?: layer.sourceId)
        }
    val menuState =
        MainMenuState(
            showCoordinates = appSettings.showCoordinates,
            nightMode = appSettings.maps.nightMode,
            nightPercent = appSettings.maps.nightPercent,
            favorite = workspaces.firstOrNull { it.matches(state.stack) }?.name,
            displayLayers = shownMaps.map { it.second },
            measureAllowed = !drawUi.active && !measureUi.active && !printUi.active,
            printAllowed = !drawUi.active && !measureUi.active && !printUi.active,
            diagnostics = BuildConfig.DEBUG,
        )
    val trackLibrary = library
    val tracksState =
        TracksMenuState(
            drawAllowed = !drawUi.active && !measureUi.active,
            waypointAllowed = !drawUi.active,
            tracks = trackLibrary?.tracks?.size ?: 0,
            routes = trackLibrary?.routes?.size ?: 0,
            waypoints = trackLibrary?.waypoints?.size ?: 0,
            allVisible =
                trackLibrary == null ||
                    (
                        trackLibrary.folders.all { it.visible } && trackLibrary.tracks.all { it.visible } &&
                            trackLibrary.routes.all { it.visible } && trackLibrary.waypoints.all { it.visible }
                    ),
        )
    // The quick menu of the centre point works where the map is only looked at: not while drawing, measuring,
    // printing, saving an area, or while the screen is locked.
    val pointMenuAllowed =
        !locked && !drawUi.active && !measureUi.active && !printUi.active && mapsPage != MapsPage.SAVE_AREA
    val pointShareTitle = stringResource(R.string.point_share)
    val pointCopiedText = stringResource(R.string.point_copied)
    val positionShareTitle = stringResource(R.string.position_box_share)
    val pointChooserTitle = stringResource(R.string.point_open_title)
    val pointNoAppMessage = stringResource(R.string.point_open_none)
    LaunchedEffect(pointMenuAllowed) {
        if (!pointMenuAllowed) pointMenu = false
    }
    val positionState =
        PositionMenuState(
            realTime = controller.locationOn,
            follow =
                when {
                    !controller.locationOn -> null
                    controller.locationMode == LocationMode.FREE -> FollowChoice.FREE
                    controller.locationMode == LocationMode.FOLLOW -> FollowChoice.FOLLOW
                    controller.tilted -> FollowChoice.EXPLORER
                    else -> FollowChoice.COMPASS
                },
            recording = recordingUi.phase != RecorderState.IDLE,
            recordingPaused = recordingUi.phase == RecorderState.PAUSED,
            recordedMeters = recordingUi.live.distanceMeters,
            recordedMillis = recordingUi.live.elapsedMillis,
            sharing = shareUi.sharing,
            groupMembers = if (shareUi.inGroup) shareUi.members.size else 0,
            barometerAvailable = altitudeStatus.barometerAvailable,
            barometer = appSettings.altitude.barometerOn(),
            accuracyMeters = altitudeStatus.accuracyMeters.takeIf { controller.locationOn },
            altitudeMeters = altitudeStatus.altitude?.meters.takeIf { controller.locationOn },
            altitudeFromBarometer = altitudeStatus.altitude?.source == AltitudeSource.BAROMETER,
            satellitesSeen = satellites?.seen,
            barometerCalibrated = altitudeStatus.calibrated,
        )
    // Something that goes on in the background when the screen is left: a recording, a share, an alert (ADR-053).
    val runningInBackground = positionState.recording || positionState.sharing || alertUi.active
    // What the exit dialog names, with the figures and the stop button of ToolMap's notification (P29.2).
    val runningServices =
        buildList {
            if (positionState.recording) {
                val paused = positionState.recordingPaused
                add(
                    RunningService(
                        if (paused) RunningService.Kind.RECORDING_PAUSED else RunningService.Kind.RECORDING,
                        stringResource(if (paused) R.string.exit_recording_paused else R.string.exit_recording),
                        TrackFormat.duration(positionState.recordedMillis) + " · " +
                            formats.distance(positionState.recordedMeters),
                        stringResource(R.string.background_stop_recording),
                    ),
                )
            }
            if (positionState.sharing) {
                val linked = shareUi.others.count { it.status == MemberStatus.DIRECT }
                add(
                    RunningService(
                        RunningService.Kind.SHARING,
                        stringResource(R.string.exit_sharing),
                        pluralStringResource(R.plurals.share_notification_members, linked, linked),
                        stringResource(R.string.background_stop_sharing),
                    ),
                )
            }
            val follow = alertUi.follow
            if (follow != null) {
                val name = follow.name ?: stringResource(R.string.tracks_unnamed)
                add(
                    RunningService(
                        RunningService.Kind.ALERTS,
                        stringResource(R.string.exit_following),
                        follow.status?.let {
                            stringResource(R.string.exit_following_left, name, formats.distance(it.remainingMeters))
                        } ?: name,
                        stringResource(R.string.background_stop_following),
                    ),
                )
            } else if (alertUi.watches.isNotEmpty()) {
                add(
                    RunningService(
                        RunningService.Kind.ALERTS,
                        stringResource(R.string.exit_alerts),
                        pluralStringResource(
                            R.plurals.alert_service_watching,
                            alertUi.watches.size,
                            alertUi.watches.size,
                        ),
                        stringResource(R.string.background_stop_alerts),
                    ),
                )
            }
        }
    var exitAsked by remember { mutableStateOf(false) }
    // « Nouveautés » (the text, once read) and « Signaler une erreur » (asked, then built off the main thread) (P16.3).
    var whatsNew by remember { mutableStateOf<String?>(null) }
    var reportAsked by remember { mutableStateOf(false) }
    val runMenuAction: (MenuAction) -> Unit = { action ->
        showMenu = false
        when (action) {
            MenuAction.WHATS_NEW -> {
                whatsNew =
                    runCatching {
                        context.assets.open("whats-new.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }.getOrDefault("")
            }

            MenuAction.REPORT_ERROR -> {
                reportAsked = true
            }

            MenuAction.EXIT -> {
                if (runningInBackground) exitAsked = true else (context as? Activity)?.finish()
            }

            MenuAction.HIDE -> {
                chromeHidden = !chromeHidden
            }

            MenuAction.LOCK -> {
                locked = true
            }

            MenuAction.PRINT -> {
                controller.resetNorth(northOffset)
                val c = controller.liveCamera
                print.open(c.lat, c.lon, state.stack.layers, formats.coordinates)
            }

            MenuAction.SETTINGS -> {
                onOpenSettings()
            }

            MenuAction.ABOUT -> {
                onOpenAbout(null)
            }

            MenuAction.SEARCH -> {
                onOpenSearch()
            }

            MenuAction.DRAW_TRACK -> {
                draw.startNewTrack()
            }

            MenuAction.DRAW_ROUTE -> {
                draw.startNewRoute()
            }

            MenuAction.MEASURE -> {
                measure.open()
            }

            MenuAction.WAYPOINT -> {
                // « Waypoint ici » records at once (P29.4, « Repère »); its message opens the dialog to change it.
                val c = controller.liveCamera
                val prefix = resources.getString(R.string.waypoint_default_prefix)
                val next = WaypointNames.nextNumber(library?.waypoints.orEmpty().map { it.name }, prefix)
                val name = resources.getString(R.string.waypoint_default_name, prefix, next)
                scope.launch {
                    val made = tracks.quickWaypoint(c.lat, c.lon, name)
                    val text =
                        made.waypoint.ele?.let {
                            resources.getString(R.string.waypoint_quick_saved, name, formats.elevation(it))
                        }
                            ?: resources.getString(R.string.waypoint_quick_saved_bare, name)
                    val answer =
                        snackbar.showSnackbar(
                            text,
                            actionLabel = resources.getString(R.string.tracks_edit_waypoint),
                            duration = SnackbarDuration.Long,
                        )
                    if (answer == SnackbarResult.ActionPerformed) {
                        waypointDraft = WaypointDraft(made.id to made.waypoint, made.waypoint.lat to made.waypoint.lon)
                    }
                }
            }

            MenuAction.SHARE -> {
                onOpenShare()
            }

            MenuAction.DIAGNOSTICS -> {
                onOpenDiagnostics()
            }

            MenuAction.MAPS -> {
                maps.open(MapsPage.ROOT)
            }

            MenuAction.IMPORT_TRACKS -> {
                onImportTracks()
            }
        }
    }
    // The option of the menu switches without closing it.
    val onCoordinates: (Boolean) -> Unit = { on -> vm.updateSettings { it.copy(showCoordinates = on) } }
    val chromeShown = !locked && !chromeHidden
    // While the print frame is placed, only the format band, Annuler / Suivant and an SOS stay over the map (preview
    // validated on 2026-10-26, docs/previews/cadre-impression.html, variant A).
    val framing = printUi.stage == PrintStage.FRAME
    val barDp = with(density) { barHeight.toDp() }
    // The panel stays below the top of the screen, and above the bar.
    val menuMaxHeight =
        (
            with(density) {
                rootSize.height.toDp()
            } - bottomClearance - barDp - 96.dp
        ).coerceAtLeast(160.dp)
    BackHandler(enabled = showMenu) { showMenu = false }
    BackHandler(enabled = showPositioning) { showPositioning = false }
    BackHandler(enabled = showTracks) { showTracks = false }
    BackHandler(enabled = mapsPage == MapsPage.ROOT && !covered) { maps.close() }
    // A locked screen keeps the app open: Back only says how to unlock it.
    BackHandler(enabled = locked && !showMenu) {
        scope.launch { snackbar.showSnackbar(resources.getString(R.string.lock_hint)) }
    }
    // Hidden controls come back with Back, before anything leaves the map.
    BackHandler(enabled = chromeHidden && !locked && !showMenu) { chromeHidden = false }
    // Leaving the app from the map asks first when the setting is on. The menus, panels and tools close before that
    // (the drawing and measure bars hold their own handlers); the handlers are exclusive, so the order in which they
    // were registered does not matter.
    BackHandler(
        enabled =
            appSettings.confirmExit && !exitAsked && !showMenu && !showPositioning && !showTracks &&
                !printUi.active && mapsPage != MapsPage.SAVE_AREA && mapsPage != MapsPage.ROOT &&
                mapsPage != MapsPage.CONDITIONS && !locked &&
                !chromeHidden &&
                !drawUi.active && !measureUi.active,
    ) { exitAsked = true }
    if (exitAsked) {
        val activity = context as? Activity
        ExitDialog(running = runningServices, onExit = { activity?.finish() }, onDismiss = { exitAsked = false })
    }

    whatsNew?.let { text ->
        AlertDialog(
            onDismissRequest = { whatsNew = null },
            title = { Text(stringResource(R.string.whats_new_title)) },
            text = {
                Text(
                    text.ifBlank { stringResource(R.string.whats_new_empty) },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { whatsNew = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    if (reportAsked) {
        val subject = stringResource(R.string.report_subject)
        val chooser = stringResource(R.string.report_share)
        AlertDialog(
            onDismissRequest = { reportAsked = false },
            title = { Text(stringResource(R.string.report_title)) },
            text = { Text(stringResource(R.string.report_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reportAsked = false
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { CrashReporter.errorReport(context) }
                            val send =
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, subject)
                                    .putExtra(Intent.EXTRA_TEXT, text)
                            runCatching { context.startActivity(Intent.createChooser(send, chooser)) }
                        }
                    },
                ) { Text(stringResource(R.string.report_share)) }
            },
            dismissButton = {
                TextButton(onClick = { reportAsked = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val boxesShown by rememberUpdatedState(appSettings.showCoordinates)
    val rootActions =
        remember(maps, controller, mapping) {
            MapsRootActions(
                onOpenAvailable = onOpenAvailable,
                onSaveArea = {
                    // The zone starts in the part of the screen the info boxes leave free (V2).
                    val boxesEnd = if (boxesShown) infoBoxesEnd else IntOffset.Zero
                    val free = ChromePlacement.freeOfBoxes(rootSize.width, rootSize.height, boxesEnd.x, boxesEnd.y)
                    val area = controller.screenArea(free.left, free.top, free.right, free.bottom)
                    (area ?: controller.visibleArea())?.let { visible ->
                        val tileZoom = mapping.toTileZoom(controller.liveCamera.zoom).toInt().coerceAtLeast(0)
                        maps.openSaveArea(visible, tileZoom, vm.state.value.stack)
                    }
                },
                // Left open behind the full screen (hidden while it covers the map): back from Downloads or
                // Storage returns to this panel, where the user came from.
                onOpenDownloads = onOpenDownloads,
                onOpenStorage = onOpenStorage,
                onHelp = { onOpenAbout(HelpSection.MAPS) },
                onFavoriteDeleted = { favorite ->
                    scope.launch {
                        val answer =
                            snackbar.showSnackbar(
                                resources.getString(R.string.maps_favorite_deleted, favorite.name),
                                actionLabel = resources.getString(R.string.action_cancel),
                                duration = SnackbarDuration.Short,
                            )
                        if (answer == SnackbarResult.ActionPerformed) vm.restoreWorkspace(favorite)
                    }
                },
            )
        }
    // « Sélectionner une zone » of an empty « Cartes hors ligne » screen: start the selection once the map is laid out.
    val saveAreaAsked by maps.saveAreaAsked.collectAsStateWithLifecycle()
    LaunchedEffect(saveAreaAsked, controller.isReady, rootSize) {
        if (saveAreaAsked && controller.isReady && rootSize.width > 0) {
            maps.saveAreaStarted()
            rootActions.onSaveArea()
        }
    }
    val barPanelOpen = showMenu || showTracks || showPositioning || (mapsPage == MapsPage.ROOT && !covered)
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                controller.onCameraIdle = vm::setCamera
                controller.onItemTap = tracks::onMapTap
                controller.onMemberTap = share::select
                mapView.also {
                    controller.attach(it, vm.state.value.camera)
                    // Timing lines for the measures of P15 (debug and benchmark builds only).
                    if (BuildConfig.PERF_LOG) perfLog[0] = MapPerfLog.watch(it, vm.tileStats)
                }
            },
        )
        if (printUi.stage == PrintStage.FRAME || printUi.stage == PrintStage.CONFIRM) {
            PrintFrameLayer(controller, printUi, print)
        }
        if (!framing) {
            CenterPoint(
                menuEnabled = pointMenuAllowed,
                menuOpen = pointMenu,
                // A waypoint, a track or a member under the finger opens as anywhere else on the map (G12).
                onTap = { dx, dy -> if (pointMenu || !controller.tapNearCentre(dx, dy)) pointMenu = !pointMenu },
                onMenu = { pointMenu = true },
                onDismiss = { pointMenu = false },
                menu = {
                    val centre = controller.liveCamera
                    CenterQuickMenu(
                        lat = centre.lat,
                        lon = centre.lon,
                        terrainElevation = tracks::terrainElevation,
                        onTarget = {
                            pointMenu = false
                            val c = controller.liveCamera
                            navigation.setTarget(NavTarget(c.lat, c.lon, targetPointName))
                        },
                        onWaypoint = {
                            pointMenu = false
                            val c = controller.liveCamera
                            waypointDraft = WaypointDraft(null, c.lat to c.lon)
                        },
                        onMeasure = {
                            pointMenu = false
                            val c = controller.liveCamera
                            measure.open()
                            measure.addAt(c.lat, c.lon)
                        },
                        onCopy = { text ->
                            pointMenu = false
                            copyToClipboard(context, text)
                            scope.launch { snackbar.showSnackbar(pointCopiedText) }
                        },
                        onShare = { text ->
                            pointMenu = false
                            shareText(context, pointShareTitle, text)
                        },
                        onOpenWith = {
                            pointMenu = false
                            val c = controller.liveCamera
                            openPoint(context, c.lat, c.lon, appSettings.pointApp, pointChooserTitle, pointNoAppMessage)
                        },
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (drawUi.active && drawUi.freehand && !drawUi.routed && drawUi.chooseSegment == null) {
            FreehandLayer(
                toPoint = controller::screenToLatLon,
                onStroke = { stroke -> draw.addStroke(stroke, controller.metersFor(STROKE_TOLERANCE_DP)) },
            )
        }

        saveArea?.takeIf { mapsPage == MapsPage.SAVE_AREA }?.let { area ->
            SaveAreaLayer(controller, area.selection, maps, handles = false)
        }

        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            // The parts of the map push each other instead of covering each other (V2, MapChromeLayout).
            MapTopChrome(
                boxes = {
                    val route = drawUi.route
                    if (drawUi.active && drawUi.routed && route != null && route.plan.stops.size >= 2) {
                        // Variant A of P19: the length, climb and time of the route take the place of the boxes.
                        RouteStatsCard(route = route, modifier = Modifier.fillMaxWidth())
                    } else if (appSettings.showCoordinates && !framing) {
                        MapInfoBoxes(
                            camera = controller.liveCamera,
                            grid = gridPlan,
                            mapping = mapping,
                            baseMaxZoom = baseMaxZoom,
                            gps = altitudeStatus.takeIf { controller.locationOn },
                            satellites = satellites?.used,
                            targetMeters = if (navTarget != null) navGuidance?.distanceMeters else null,
                            heading = headingText,
                            onAltitudeClick = { showAltitude = true },
                            positionActions =
                                PositionBoxActions(
                                    onCopy = { text ->
                                        copyToClipboard(context, text)
                                        scope.launch { snackbar.showSnackbar(pointCopiedText) }
                                    },
                                    onShare = { text -> shareText(context, positionShareTitle, text) },
                                    onNextFormat = {
                                        vm.updateSettings {
                                            it.copy(coordinates = nextCoordinateSystem(it.coordinates))
                                        }
                                    },
                                ),
                            modifier =
                                Modifier.onGloballyPositioned {
                                    val end = it.boundsInRoot().bottomRight
                                    infoBoxesEnd = IntOffset(end.x.roundToInt(), end.y.roundToInt())
                                },
                        )
                    }
                },
                panels = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (printUi.stage == PrintStage.FRAME) {
                            PrintFrameBar(ui = printUi, onUpdate = print::update, onRotate = print::rotate)
                        }
                        ShareHud(
                            ui = shareUi,
                            onOpen = onOpenShare,
                            onShowSos = { alert ->
                                alert.fix?.let { controller.fitBounds(it.lat, it.lon, it.lat, it.lon) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            sosOnly = framing,
                        )
                        if (!framing) {
                            navTarget?.let { target ->
                                TargetHud(
                                    target = target,
                                    guidance = navGuidance,
                                    gpsFailed = gpsFailed,
                                    mapBearing = controller.liveCamera.bearing,
                                    onClear = navigation::clearTarget,
                                )
                            }
                            FollowHud(
                                ui = alertUi,
                                onReverse = alerts::reverseFollow,
                                onStop = alerts::stopFollowing,
                                onDismissBanner = alerts::dismissBanner,
                            )
                        }
                    }
                },
                // P29.1, « Pastilles »: the recording is a chip of the top right corner, its figures under a tap.
                corner = {
                    if (!framing) {
                        RecordingChip(
                            ui = recordingUi,
                            onPause = recording::pause,
                            onResume = recording::resume,
                            onStop = recording::stop,
                        )
                    }
                },
            )
            if (chromeShown && !framing && appSettings.canvas.zoomButtons && appSettings.canvas.menusLeftHanded) {
                MapZoomButtons(
                    onZoomIn = { controller.zoomBy(appSettings.canvas.zoomStep.levels) },
                    onZoomOut = { controller.zoomBy(-appSettings.canvas.zoomStep.levels) },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            MapBottomChrome(
                modifier = Modifier.padding(bottom = bottomClearance),
                attribution = { if (!framing) AttributionText(attribution) },
                bar = {
                    when {
                        locked -> {
                            LockedPill(onUnlock = { locked = false })
                        }

                        framing -> {}

                        chromeHidden -> {
                            MenuLogoButton(
                                onClick = { chromeHidden = false },
                                modifier = Modifier.onSizeChanged { barHeight = it.height },
                            )
                        }

                        else -> {
                            // The width of the screen and down to its bottom edge (P29.0, « Crête »), through the
                            // margin of the chrome; it stops at the panel under it (drawing, measure, print...).
                            val bottomBleed =
                                if (bottomClearance > 0.dp) 0.dp else safe.calculateBottomPadding() + 12.dp
                            MapBottomBar(
                                locationMode = controller.locationMode,
                                tracksOpen = showTracks,
                                positionOpen = showPositioning,
                                mapsOpen = mapsPage == MapsPage.ROOT,
                                menuOpen = showMenu,
                                onMenu = { showMenu = true },
                                onMaps = {
                                    if (mapsPage == MapsPage.ROOT) maps.close() else maps.open(MapsPage.ROOT)
                                },
                                onTracks = { showTracks = !showTracks },
                                onPosition = { showPositioning = !showPositioning },
                                onPositionLongPress = onPositionLongPress,
                                bottomBleed = bottomBleed,
                                modifier =
                                    Modifier.onSizeChanged { barHeight = it.height }.bleed(
                                        start = safe.calculateStartPadding(layoutDirection) + 12.dp,
                                        end = safe.calculateEndPadding(layoutDirection) + 12.dp,
                                        bottom = bottomBleed,
                                    ),
                            )
                        }
                    }
                },
                end = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (chromeShown && !framing) {
                            // Out of the bar since P29.0; shown only while the map does not point north.
                            CompassButton(
                                bearing = controller.liveCamera.bearing - northOffset,
                                onClick = { controller.resetNorth(northOffset) },
                            )
                            if (state.swapTarget() != null) {
                                val swapLabel = stringResource(R.string.a11y_swap_base)
                                SmallFloatingActionButton(
                                    onClick = vm::swapBase,
                                    modifier =
                                        Modifier.minimumInteractiveComponentSize().semantics {
                                            contentDescription = swapLabel
                                        },
                                ) {
                                    Text(
                                        stringResource(R.string.swap_base_short),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.clearAndSetSemantics { },
                                    )
                                }
                            }
                            if (appSettings.canvas.zoomButtons && !appSettings.canvas.menusLeftHanded) {
                                MapZoomButtons(
                                    onZoomIn = { controller.zoomBy(appSettings.canvas.zoomStep.levels) },
                                    onZoomOut = { controller.zoomBy(-appSettings.canvas.zoomStep.levels) },
                                )
                            }
                        }
                    }
                },
            )
        }
        // The handles of the zone stand over the info boxes, so that a corner taken under them can still be moved (V2).
        saveArea?.takeIf { mapsPage == MapsPage.SAVE_AREA }?.let { area ->
            SaveAreaLayer(controller, area.selection, maps, shade = false)
        }
        if (showMenu) {
            Box(
                modifier =
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showMenu = false },
            )
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                MainMenuPanel(
                    state = menuState,
                    onAction = runMenuAction,
                    onCoordinates = onCoordinates,
                    onNightMode = { on -> vm.updateSettings { it.copy(maps = it.maps.copy(nightMode = on)) } },
                    maxHeight = menuMaxHeight,
                    displayPreview = { StackThumbnails(shownMaps, maps.thumbnails) },
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = bottomClearance + barDp + 12.dp)
                            .onSizeChanged { barPanelHeight = it.height },
                )
            }
        }
        if (showTracks) {
            Box(
                modifier =
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showTracks = false },
            )
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                TracksMenuPanel(
                    state = tracksState,
                    // The commands run from the panel, which closes like the menu does.
                    onAction = { action ->
                        showTracks = false
                        runMenuAction(action)
                    },
                    onExplorer = {
                        showTracks = false
                        onOpenTracks()
                    },
                    onShowAll = tracks::setAllVisible,
                    onHelp = {
                        showTracks = false
                        onOpenAbout(HelpSection.TRACKS)
                    },
                    maxHeight = menuMaxHeight,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = bottomClearance + barDp + 12.dp)
                            .onSizeChanged { barPanelHeight = it.height },
                )
            }
        }
        if (showPositioning) {
            Box(
                modifier =
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showPositioning = false },
            )
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                PositionMenuPanel(
                    state = positionState,
                    onFollow = onFollow,
                    onRealTime = onRealTime,
                    onRecorder = onRecorder,
                    onBarometer = { on -> vm.updateSettings { it.copy(altitude = it.altitude.withBarometer(on)) } },
                    onShare = {
                        showPositioning = false
                        onOpenShare()
                    },
                    onHelp = {
                        showPositioning = false
                        onOpenAbout(HelpSection.POSITIONING)
                    },
                    onCalibrate = { showAltitude = true },
                    maxHeight = menuMaxHeight,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = bottomClearance + barDp + 12.dp)
                            .onSizeChanged { barPanelHeight = it.height },
                )
            }
        }
        if (mapsPage == MapsPage.ROOT && !covered) {
            Box(
                modifier =
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { maps.close() },
            )
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                MapsRootPanel(
                    state = state,
                    maps = maps,
                    vm = vm,
                    actions = rootActions,
                    currentZoom = mapping.toTileZoom(controller.liveCamera.zoom),
                    maxHeight = menuMaxHeight,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = bottomClearance + barDp + 12.dp)
                            .onSizeChanged { barPanelHeight = it.height },
                )
            }
        }
        saveArea?.takeIf { mapsPage == MapsPage.SAVE_AREA }?.let { area ->
            SaveAreaPanel(
                ui = area,
                currentTileZoom = mapping.toTileZoom(controller.liveCamera.zoom),
                maps = maps,
                onSave = {
                    val name =
                        resources.getString(
                            R.string.sa_job_name,
                            NameDates.format(System.currentTimeMillis()),
                        )
                    when (maps.saveArea(name)) {
                        null -> {
                            val ready = area.estimate as? ZoneEstimate.Ready
                            vm.notify(
                                R.string.sa_started,
                                tilesText(resources, ready?.missing ?: 0L),
                                formatBytes(ready?.bytes ?: 0L),
                            )
                            maps.close()
                        }

                        SaveRefusal.OFFLINE -> {
                            vm.notify(R.string.download_offline_warning)
                        }

                        SaveRefusal.NOTHING -> {
                            vm.notify(R.string.sa_no_maps)
                        }
                    }
                },
                onClose = maps::close,
                onHelp = { showAreaHelp = true },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        ).onSizeChanged { saveAreaPanelHeight = it.height },
            )
        }
        conditionsLayer?.let { layer ->
            val sources by maps.sources.collectAsStateWithLifecycle()
            val camera = controller.liveCamera
            // The ground under the centre of the map, marked on the elevation bar once the map stops moving.
            val centerElevation by produceState<Double?>(null, camera.lat, camera.lon) {
                delay(CENTER_ELEVATION_DELAY_MS)
                value = tracks.terrainElevation(camera.lat, camera.lon)
            }
            DisplayConditionsPanel(
                layer = layer,
                name =
                    sources.firstOrNull { it.id == layer.sourceId }?.name
                        ?: stringResource(R.string.layers_missing_source, layer.sourceId),
                currentZoom = mapping.toTileZoom(camera.zoom),
                centerElevation = centerElevation,
                onZoomRange = { min, max -> vm.setZoomRange(layer.id, min, max) },
                onElevationRange = { min, max, logic -> vm.setElevationRange(layer.id, min, max, logic) },
                onClose = maps::closeConditions,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        ).onSizeChanged { conditionsPanelHeight = it.height },
            )
        }
        // The messages stand over the panels (P29.5): « Enregistrement terminé » and its « Reprendre » stay in sight when
        // the recording is stopped from the positioning panel; drawing, measure and print controls stay over them.
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            SnackbarHost(
                snackbar,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomClearance + if (chromeShown) barDp else 0.dp)
                        .padding(bottom = if (barPanelOpen) with(density) { barPanelHeight.toDp() } + 12.dp else 0.dp),
            ) { data ->
                val visuals = data.visuals
                if (visuals is RouteNoticeVisuals) {
                    RouteNoticeSnackbar(
                        visuals = visuals,
                        onAllByHand = {
                            data.dismiss()
                            draw.setRouting(false)
                        },
                        onOpenBRouter = {
                            data.dismiss()
                            openBRouter(context)
                        },
                    )
                } else {
                    Snackbar(data)
                }
            }
        }
        if (drawUi.active && drawUi.chooseSegment == null) {
            DrawBar(
                ui = drawUi,
                vm = draw,
                center = { controller.liveCamera.let { it.lat to it.lon } },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { drawBarHeight = it.height },
                backEnabled = !locked,
                hooks =
                    RouteHooks(
                        insertLegAt = {
                            val camera = controller.liveCamera
                            draw.insertLegAt(camera.lat, camera.lon, controller.metersFor(TAP_REACH_DP))
                        },
                        reachMeters = { controller.metersFor(TAP_REACH_DP) },
                        position = { navPosition?.let { it.lat to it.lon } ?: controller.lastPosition() },
                        onCenter = { lat, lon -> controller.flyTo(lat, lon) },
                        onInstall = { installBRouter(context) },
                    ),
            )
        }
        if (measureUi.active) {
            MeasureBar(
                ui = measureUi,
                vm = measure,
                center = { controller.liveCamera.let { it.lat to it.lon } },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { measureBarHeight = it.height },
                backEnabled = !locked,
                onDownloadArea = onDownloadZone,
            )
        }
        if (printUi.stage == PrintStage.FRAME || printUi.stage == PrintStage.CONFIRM) {
            val printModifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ).onSizeChanged { printPanelHeight = it.height }
            if (printUi.stage == PrintStage.FRAME && printUi.alongTrack) {
                PrintTrackActions(
                    ui = printUi,
                    onCancel = print::close,
                    onNext = print::confirm,
                    modifier = printModifier,
                )
            } else if (printUi.stage == PrintStage.FRAME) {
                PrintFrameActions(onCancel = print::close, onNext = print::confirm, modifier = printModifier)
            } else {
                PrintConfirmSheet(ui = printUi, actions = printActions, modifier = printModifier)
            }
        }
    }
    drawUi.chooseSegment?.let { sizes ->
        SegmentChooser(sizes = sizes, onPick = draw::chooseSegment, onCancel = draw::cancel)
    }

    // The sheets and dialogs are windows of their own: they would stay on top of the screen that covers the map.
    if (!covered) {
        if (showAreaHelp) HelpDialog(stringResource(R.string.sa_help_text)) { showAreaHelp = false }
        val tappedRef = tapped
        val lib = library
        if (tappedRef != null && lib != null) {
            TappedItemSheet(
                ref = tappedRef,
                library = lib,
                from = navPosition?.let { it.lat to it.lon } ?: controller.lastPosition(),
                onShare = { ref, name ->
                    tracks.onMapTap(null)
                    scope.launch {
                        tracks
                            .exportToCache(
                                TrackSharing.directory(context),
                                TrackSharing.fileName(name),
                                ref.selection(),
                            )
                            ?.let { TrackSharing.share(context, it, resources.getString(R.string.tracks_share)) }
                    }
                },
                onExport = { ref, name ->
                    tracks.onMapTap(null)
                    pendingGpx = ref.selection()
                    saveGpx.launch(TrackSharing.fileName(name))
                },
                onDelete = { ref -> tracks.delete(ref) },
                onDetails = { id ->
                    tracks.onMapTap(null)
                    onOpenTrackDetail(id)
                },
                onHide = { ref ->
                    tracks.setVisible(ref, false)
                    tracks.onMapTap(null)
                },
                onEdit = { ref ->
                    tracks.onMapTap(null)
                    when (ref.kind) {
                        ItemKind.TRACK -> {
                            draw.startEditTrack(ref.id)
                        }

                        ItemKind.ROUTE -> {
                            draw.startEditRoute(ref.id)
                        }

                        ItemKind.WAYPOINT -> {
                            scope.launch {
                                tracks.waypoint(ref.id)?.let { w ->
                                    waypointDraft = WaypointDraft(ref.id to w, w.lat to w.lon)
                                }
                            }
                        }
                    }
                },
                onGoTo = { ref ->
                    tracks.onMapTap(null)
                    scope.launch {
                        tracks.waypoint(ref.id)?.let { navigation.setTarget(NavTarget(it.lat, it.lon, it.name)) }
                    }
                },
                alerts = alertUi,
                onFollow = { ref ->
                    tracks.onMapTap(null)
                    alerts.follow(ref)
                },
                onStopFollowing = {
                    tracks.onMapTap(null)
                    alerts.stopFollowing()
                },
                onWatch = { ref ->
                    tracks.onMapTap(null)
                    watchDraft = ref
                },
                onConvert = { ref, label ->
                    tracks.onMapTap(null)
                    scope.launch {
                        if (tracks.convertRouteToTrack(ref.id)) {
                            snackbar.showSnackbar(resources.getString(R.string.route_converted, label))
                        }
                    }
                },
                onDismiss = { tracks.onMapTap(null) },
                profileOf = tracks::profileOf,
            )
        }
        if (showAltitude) {
            AltitudeDialog(
                status = altitudeStatus,
                onCalibrate = altitude::calibrate,
                onDismiss = { showAltitude = false },
            )
        }
        watchDraft?.let { ref ->
            val waypoint = library?.waypoints?.firstOrNull { it.id == ref.id }
            val current = alertUi.watches.firstOrNull { it.waypointId == ref.id }
            WatchDialog(
                name = waypoint?.name,
                currentRadius = current?.radiusMeters,
                watched = current != null,
                onConfirm = { radius ->
                    watchDraft = null
                    alerts.watch(ref.id, radius)
                },
                onDisable = {
                    watchDraft = null
                    alerts.unwatch(ref.id)
                },
                onDismiss = { watchDraft = null },
            )
        }
        waypointDraft?.let { draft ->
            val defaultName =
                remember(draft) {
                    val prefix = resources.getString(R.string.waypoint_default_prefix)
                    val next = WaypointNames.nextNumber(library?.waypoints.orEmpty().map { it.name }, prefix)
                    resources.getString(R.string.waypoint_default_name, prefix, next)
                }
            WaypointDialog(
                initial = draft.existing?.second,
                start = draft.at,
                defaultName = defaultName,
                center = { controller.liveCamera.let { it.lat to it.lon } },
                terrainElevation = tracks::terrainElevation,
                onSave = { waypoint ->
                    waypointDraft = null
                    tracks.saveWaypoint(draft.existing?.first, waypoint)
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.waypoint_saved)) }
                },
                onSaveAndGo = { waypoint ->
                    waypointDraft = null
                    tracks.saveWaypoint(draft.existing?.first, waypoint)
                    navigation.setTarget(NavTarget(waypoint.lat, waypoint.lon, waypoint.name))
                },
                onDismiss = { waypointDraft = null },
            )
        }
        if (showRecordStart) {
            // One dialog (P29.5): the activity (« Demander l'activité »), the name and the precision of the points.
            RecordStartDialog(
                initialProfile = remember { recording.lastProfile() },
                askActivity = appSettings.recorder.askActivity,
                recentActivities = remember { recording.recentActivities() },
                initialActivity =
                    remember {
                        recording.recentActivities().first().takeIf { recording.hasRecentActivity() }
                    },
                onStart = { name, profile, activity ->
                    showRecordStart = false
                    activity?.let(recording::rememberActivity)
                    val named =
                        activity?.let {
                            resources.getString(R.string.record_named, resources.getString(it.labelRes()), name)
                        }
                    startWithPermission(named ?: name, profile)
                },
                onDismiss = { showRecordStart = false },
            )
        }
        val recoverable = recordingUi.recoverable
        if (recoverable != null && !recoveryDismissed) {
            RecordingRecoveryDialog(
                onResume = recording::resumeRecovered,
                onKeep = recording::finishRecovered,
                onDismiss = { recoveryDismissed = true },
            )
        }
        if (printUi.stage == PrintStage.SETUP) PrintSetupDialog(ui = printUi, actions = printActions)
        printUi.outcome?.let { outcome ->
            PrintOutcomeDialog(
                outcome = outcome,
                onDismiss = {
                    print.dismissOutcome()
                    // A saved PDF ends the print; after a failure the sheet stays to try again.
                    if (outcome is PrintOutcome.Exported) print.close()
                },
            )
        }
        selectedMember?.let { id ->
            val member = shareUi.members.firstOrNull { it.id == id }
            if (member == null) {
                share.select(null)
            } else {
                val fix = member.fix
                MemberDialog(
                    member = member,
                    state = PeerState.of(member, System.currentTimeMillis(), serverUp(shareUi)),
                    onShowOnMap =
                        fix?.let { f ->
                            {
                                share.select(null)
                                controller.fitBounds(f.lat, f.lon, f.lat, f.lon)
                            }
                        },
                    onGoTo =
                        fix?.takeIf { !member.isSelf }?.let { f ->
                            {
                                share.select(null)
                                navigation.setTarget(NavTarget(f.lat, f.lon, member.name))
                            }
                        },
                    onSaveTrail = {
                        share.saveTrail(member)
                        share.select(null)
                    },
                    onRemove = { share.removeMember(member.id) },
                    onDismiss = { share.select(null) },
                )
            }
        }
        importPreview?.let { preview ->
            ImportDialog(preview = preview, onConfirm = vm::confirmImport, onDismiss = vm::dismissImport)
        }
    }
}

/**
 * The frame of the print, fixed on the ground: it reads the camera itself, so a move recomposes it alone (P15.7). While
 * it is placed, a finger moves it and two fingers zoom the map.
 */
@Composable
private fun PrintFrameLayer(
    controller: MapController,
    ui: PrintUi,
    print: PrintViewModel,
) {
    if (ui.alongTrack) {
        val pages = remember(ui.plan) { ui.plan?.pageCorners().orEmpty() }
        PrintPagesOverlay(camera = controller.liveCamera, pages = pages, project = controller::latLonToScreen)
        return
    }
    PrintFrameOverlay(
        camera = controller.liveCamera,
        template = ui.template,
        centerLat = ui.centerLat,
        centerLon = ui.centerLon,
        project = controller::latLonToScreen,
        unproject = controller::screenToLatLon,
        onMove = if (ui.stage == PrintStage.FRAME) print::moveFrame else null,
        onZoom = { factor ->
            val c = controller.liveCamera
            controller.moveTo(c.copy(zoom = c.zoom + ln(factor.toDouble()) / ln(2.0)))
        },
    )
}

/** The zone being saved, with its corners projected on the screen: it reads the camera itself (P15.7). */
@Composable
private fun SaveAreaLayer(
    controller: MapController,
    selection: AreaSelection,
    maps: MapsViewModel,
    shade: Boolean = true,
    handles: Boolean = true,
) {
    SaveAreaOverlay(
        selection = selection,
        camera = controller.liveCamera,
        project = controller::latLonToScreen,
        unproject = controller::screenToLatLon,
        onDrag = maps::dragCorner,
        shade = shade,
        handles = handles,
    )
}

/** A waypoint being created at [at] or edited ([existing] = id and value). */
private class WaypointDraft(
    val existing: Pair<Long, Waypoint>?,
    val at: Pair<Double, Double>,
)

/** A tap selects a vertex when it falls within this distance of it. */
private const val TAP_REACH_DP = 24f

/** The elevation of the map centre is looked up once the map has stood still this long (P20 panel). */
private const val CENTER_ELEVATION_DELAY_MS = 300L

/** The one track, route or waypoint of a sheet, as the GPX writer takes it (« Partager », « Exporter »). */
private fun ItemRef.selection(): TrackSelection =
    when (kind) {
        ItemKind.TRACK -> TrackSelection(trackIds = listOf(id))
        ItemKind.ROUTE -> TrackSelection(routeIds = listOf(id))
        ItemKind.WAYPOINT -> TrackSelection(waypointIds = listOf(id))
    }

/** Free-hand strokes are thinned to this tolerance. */
private const val STROKE_TOLERANCE_DP = 4f

/** The frame takes this share of the room it is fitted in (P17, Q4). */
private const val PRINT_FIT_RATIO = 0.7

/** Room taken by the band of chips above and the buttons below the frame when fitting the zoom, in dp. */
private const val PRINT_FIT_RESERVE_DP = 220.0
private const val PRINT_MIN_FIT_DP = 120.0

/**
 * Above the confirmation sheet, the room kept clear of the frame: the info boxes at the top, the bottom bar and its
 * credits, the column of zoom buttons on each side (to stay centred); then the wait for the sheet to take its height.
 */
private const val PRINT_CONFIRM_TOP_DP = 96.0
private const val PRINT_CONFIRM_BOTTOM_DP = 110.0
private const val PRINT_CONFIRM_SIDE_DP = 72.0
private const val PRINT_CONFIRM_SETTLE_MS = 150L
private const val METERS_PER_DEGREE_OF_LATITUDE = 111_320.0
