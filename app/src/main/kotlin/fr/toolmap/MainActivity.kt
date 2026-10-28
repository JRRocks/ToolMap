package fr.toolmap

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.toolmap.R
import fr.toolmap.about.AboutScreen
import fr.toolmap.about.HelpSection
import fr.toolmap.alert.AlertController
import fr.toolmap.altitude.AltitudeController
import fr.toolmap.backup.BackupScreen
import fr.toolmap.backup.BackupViewModel
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.settings.ThemeMode
import fr.toolmap.core.track.TrackImporter
import fr.toolmap.crash.CrashActivity
import fr.toolmap.crash.CrashReporter
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.TileLocation
import fr.toolmap.data.tiles.TileStats
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.debug.SpikeScreen
import fr.toolmap.download.DownloadScreen
import fr.toolmap.download.DownloadViewModel
import fr.toolmap.download.ZoneQueue
import fr.toolmap.draw.DrawViewModel
import fr.toolmap.map.MapScreen
import fr.toolmap.map.MapViewModel
import fr.toolmap.maps.AvailableMapsActions
import fr.toolmap.maps.AvailableMapsScreen
import fr.toolmap.maps.MapsViewModel
import fr.toolmap.measure.MeasureViewModel
import fr.toolmap.multizoom.MultiZoomScreen
import fr.toolmap.multizoom.MultiZoomViewModel
import fr.toolmap.navigate.NavigationViewModel
import fr.toolmap.perf.PerfIntent
import fr.toolmap.print.PrintViewModel
import fr.toolmap.record.RecordingViewModel
import fr.toolmap.search.SearchScreen
import fr.toolmap.search.SearchViewModel
import fr.toolmap.settings.SettingsScreen
import fr.toolmap.settings.SettingsViewModel
import fr.toolmap.share.AlertLiftedDialog
import fr.toolmap.share.GroupAlertDialog
import fr.toolmap.share.ShareScreen
import fr.toolmap.share.ShareViewModel
import fr.toolmap.sources.UriReader
import fr.toolmap.storage.StorageMoveActivity
import fr.toolmap.storage.StorageScreen
import fr.toolmap.storage.StorageViewModel
import fr.toolmap.storage.TileMoves
import fr.toolmap.tracks.MapRequest
import fr.toolmap.tracks.TrackDetailScreen
import fr.toolmap.tracks.TracksScreen
import fr.toolmap.tracks.TracksViewModel
import fr.toolmap.ui.BackStack
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import fr.toolmap.wizard.SourceWizardScreen
import fr.toolmap.wizard.SourceWizardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The app's screens; swapped in place over the map, kept in a back stack (no navigation library, RS1). */
private enum class Screen {
    MAP,
    AVAILABLE,
    DOWNLOADS,
    STORAGE,
    DIAGNOSTICS,
    WIZARD,
    TRACKS,
    TRACK_DETAIL,
    SHARE,
    SETTINGS,
    SEARCH,
    BACKUP,
    ABOUT,
    MULTI_ZOOM,
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var repository: SourceRepository

    @Inject
    lateinit var tileStats: TileStats

    @Inject
    lateinit var offlineMode: OfflineMode

    @Inject
    lateinit var zoneQueue: ZoneQueue

    // Injected for its side effect too: building it brings back what was being watched before the app was closed.
    @Inject
    lateinit var alertController: AlertController

    @Inject
    lateinit var altitudeController: AltitudeController

    private val viewModel: MapViewModel by viewModels()
    private val downloadViewModel: DownloadViewModel by viewModels()
    private val storageViewModel: StorageViewModel by viewModels()
    private val wizardViewModel: SourceWizardViewModel by viewModels()
    private val mapsViewModel: MapsViewModel by viewModels()
    private val tracksViewModel: TracksViewModel by viewModels()
    private val drawViewModel: DrawViewModel by viewModels()
    private val recordingViewModel: RecordingViewModel by viewModels()
    private val navigationViewModel: NavigationViewModel by viewModels()
    private val printViewModel: PrintViewModel by viewModels()
    private val shareViewModel: ShareViewModel by viewModels()
    private val measureViewModel: MeasureViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val searchViewModel: SearchViewModel by viewModels()
    private val backupViewModel: BackupViewModel by viewModels()
    private val multiZoomViewModel: MultiZoomViewModel by viewModels()

    /** The map can be used over the lock screen: a call from API 27, the window flag before it (minSdk 26). */
    @Suppress("DEPRECATION")
    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
        } else if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The report of a crash not yet seen comes before the map, which may be what crashed.
        if (CrashReporter.hasPending(this)) {
            startActivity(CrashActivity.intent(this))
            finish()
            return
        }
        // A move of the offline maps waits for this start: it runs first, in its own process, before any tile is opened.
        if (TileLocation(filesDir).requestedMove() != null) {
            startActivity(Intent(this, StorageMoveActivity::class.java))
            finish()
            return
        }
        (application as ToolMapApp).ensureMapLibre()
        enableEdgeToEdge()
        setContent {
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark =
                when (settings.theme) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            // The bars follow the theme the user chose, not only the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle =
                        SystemBarStyle.auto(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        ) {
                            dark
                        },
                    navigationBarStyle = SystemBarStyle.auto(NAV_BAR_LIGHT_SCRIM, NAV_BAR_DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            val formats = Formats(settings)
            ToolMapTheme(darkTheme = dark, wallpaperColors = settings.wallpaperColors, gloves = settings.gloves) {
                CompositionLocalProvider(LocalFormats provides formats, LocalAppSettings provides settings) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        // The screens opened from the map, the shown one last: Back shows the one it was opened from.
                        var stack by rememberSaveable(stateSaver = ScreenStackSaver) {
                            mutableStateOf(listOf(Screen.MAP))
                        }
                        val screen = stack.last()
                        LaunchedEffect(screen) {
                            EventLog.event("ui.screen", "screen" to screen, "depth" to stack.size)
                        }

                        fun open(next: Screen) {
                            stack = BackStack.open(stack, next, Screen.MAP)
                        }

                        fun back() {
                            stack = BackStack.back(stack)
                        }

                        fun toMap() {
                            stack = listOf(Screen.MAP)
                        }
                        // The screens' own handlers (wizard steps, editors, dialogs) are registered after this one, so
                        // they come first.
                        BackHandler(enabled = stack.size > 1) { back() }
                        val scope = rememberCoroutineScope()
                        val picker =
                            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                if (uri != null) scope.launch(Dispatchers.IO) { readForImport(uri, tracksOnly = false) }
                            }
                        // « Importer un fichier (.aqx, .mbtiles) » of « Cartes disponibles » (P29.6): one picker, a
                        // package goes to the storage screen, which asks where it goes; anything else is tried as a map.
                        val mapPicker =
                            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                if (uri != null) {
                                    scope.launch {
                                        val mbTiles =
                                            withContext(Dispatchers.IO) { UriReader.isMbTiles(this@MainActivity, uri) }
                                        if (mbTiles) {
                                            storageViewModel.stage(uri)
                                            open(Screen.STORAGE)
                                        } else {
                                            mapsViewModel.close()
                                            toMap()
                                            withContext(Dispatchers.IO) { readForImport(uri, tracksOnly = false) }
                                        }
                                    }
                                }
                            }
                        val trackPicker =
                            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                if (uri != null) scope.launch(Dispatchers.IO) { readForImport(uri, tracksOnly = true) }
                            }
                        var detailId by rememberSaveable { mutableLongStateOf(NO_TRACK) }
                        // The panel whose « ? » opened the help, which then starts at its section (R5, F11).
                        var helpSection by rememberSaveable { mutableStateOf<HelpSection?>(null) }
                        val openWizard = { sourceId: String? ->
                            val camera = viewModel.state.value.camera
                            val mapping =
                                ZoomMapping.forScreen(
                                    resources.displayMetrics.density.toDouble(),
                                    viewModel.tileMagnification.value,
                                )
                            val zoom = mapping.toTileZoom(camera.zoom).toInt().coerceAtLeast(0)
                            wizardViewModel.start(sourceId, LonLatZoom(camera.lon, camera.lat, zoom))
                            open(Screen.WIZARD)
                        }
                        // Opened from the catalogue, the editor is all the screen is: cancelling it closes the screen.
                        var multiZoomEditorOnly by rememberSaveable { mutableStateOf(false) }

                        fun openMultiZoom(
                            editorOnly: Boolean,
                            start: MultiZoomViewModel.() -> Unit,
                        ) {
                            multiZoomViewModel.start()
                            multiZoomEditorOnly = editorOnly
                            open(Screen.MULTI_ZOOM)
                        }
                        LaunchedEffect(Unit) {
                            // A finished import (from the library, or from "open with") shows the library.
                            tracksViewModel.imported.collect { open(Screen.TRACKS) }
                        }
                        LaunchedEffect(Unit) {
                            // A pairing link opened from a message or a browser shows the sharing screen.
                            shareViewModel.openRequests.collect { open(Screen.SHARE) }
                        }
                        val sources by repository.sources.collectAsStateWithLifecycle()
                        val shareUi by shareViewModel.ui.collectAsStateWithLifecycle()
                        val view = LocalView.current
                        val keepOn = settings.keepScreenOn && screen == Screen.MAP
                        DisposableEffect(keepOn) {
                            view.keepScreenOn = keepOn
                            onDispose { view.keepScreenOn = false }
                        }
                        // The bars are hidden on the map only. A swipe from an edge shows them for a moment.
                        val hideStatus = settings.hideStatusBar && screen == Screen.MAP
                        val hideNavigation = settings.hideNavigationBar && screen == Screen.MAP
                        DisposableEffect(hideStatus, hideNavigation) {
                            val insets = WindowCompat.getInsetsController(window, view)
                            insets.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            val bars = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
                            val hidden =
                                (if (hideStatus) WindowInsetsCompat.Type.statusBars() else 0) or
                                    (if (hideNavigation) WindowInsetsCompat.Type.navigationBars() else 0)
                            if (hidden != 0) insets.hide(hidden)
                            if ((bars and hidden.inv()) != 0) insets.show(bars and hidden.inv())
                            onDispose {}
                        }
                        val showOverLock = settings.showOverLockScreen
                        DisposableEffect(showOverLock) {
                            showOverLockScreen(showOverLock)
                            onDispose {}
                        }
                        // The map stays composed under the full screens, stopped while covered (PF1).
                        val covered = screen != Screen.MAP
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Hidden from the screen readers and not drawn while a screen covers it.
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = if (covered) 0f else 1f }
                                        .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier),
                            ) {
                                MapScreen(
                                    vm = viewModel,
                                    onOpenDiagnostics = { open(Screen.DIAGNOSTICS) },
                                    onOpenDownloads = {
                                        downloadViewModel.clearPending()
                                        open(Screen.DOWNLOADS)
                                    },
                                    onOpenStorage = {
                                        storageViewModel.refresh()
                                        open(Screen.STORAGE)
                                    },
                                    onDownloadZone = { shape ->
                                        downloadViewModel.prepare(
                                            viewModel.pendingZone(shape, CORRIDOR_MIN_ZOOM, CORRIDOR_MAX_ZOOM),
                                        )
                                        open(Screen.DOWNLOADS)
                                    },
                                    maps = mapsViewModel,
                                    onOpenAvailable = { open(Screen.AVAILABLE) },
                                    tracks = tracksViewModel,
                                    onOpenTracks = { open(Screen.TRACKS) },
                                    onImportTracks = { trackPicker.launch(arrayOf("*/*")) },
                                    onOpenTrackDetail = { id ->
                                        detailId = id
                                        open(Screen.TRACK_DETAIL)
                                    },
                                    draw = drawViewModel,
                                    recording = recordingViewModel,
                                    navigation = navigationViewModel,
                                    print = printViewModel,
                                    share = shareViewModel,
                                    onOpenShare = { open(Screen.SHARE) },
                                    measure = measureViewModel,
                                    onOpenSettings = {
                                        settingsViewModel.openList()
                                        open(Screen.SETTINGS)
                                    },
                                    onOpenAbout = { section ->
                                        helpSection = section
                                        open(Screen.ABOUT)
                                    },
                                    alerts = alertController,
                                    altitude = altitudeController,
                                    onOpenSearch = {
                                        val camera = viewModel.state.value.camera
                                        searchViewModel.open(camera.lat, camera.lon)
                                        open(Screen.SEARCH)
                                    },
                                    covered = covered,
                                )
                            }
                            if (covered) {
                                // Registered when a screen opens, after the map's handlers: Back leaves the screen.
                                BackHandler(enabled = stack.size > 1) { back() }
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background,
                                ) {
                                    when (screen) {
                                        Screen.SEARCH -> {
                                            SearchScreen(
                                                vm = searchViewModel,
                                                onBack = ::back,
                                                onShow = { result ->
                                                    val b = result.bounds
                                                    tracksViewModel.fitMapTo(
                                                        if (b == null) {
                                                            MapRequest(result.lat, result.lon, result.lat, result.lon)
                                                        } else {
                                                            MapRequest(b.minY, b.minX, b.maxY, b.maxX)
                                                        },
                                                    )
                                                    toMap()
                                                },
                                                onGoTo = { result ->
                                                    val name = result.title.ifBlank { null }
                                                    val target = NavTarget(result.lat, result.lon, name)
                                                    navigationViewModel.setTarget(target)
                                                    toMap()
                                                },
                                            )
                                        }

                                        Screen.SETTINGS -> {
                                            SettingsScreen(
                                                vm = settingsViewModel,
                                                onBack = ::back,
                                                onOpenBackup = {
                                                    backupViewModel.refresh()
                                                    open(Screen.BACKUP)
                                                },
                                                onOpenMultiZoom = { openMultiZoom(editorOnly = false) { cancel() } },
                                                onMoveTiles = { target ->
                                                    TileMoves.request(this@MainActivity, target)
                                                },
                                            )
                                        }

                                        Screen.MULTI_ZOOM -> {
                                            MultiZoomScreen(
                                                vm = multiZoomViewModel,
                                                onBack = ::back,
                                                editorOnly = multiZoomEditorOnly,
                                                onShow = { id ->
                                                    if (viewModel.isBase(id)) {
                                                        viewModel.replaceBase(id)
                                                    } else {
                                                        viewModel.addLayer(id)
                                                    }
                                                    val name = repository.find(id)?.name ?: id
                                                    if (multiZoomEditorOnly) {
                                                        // Saved from « Cartes disponibles »: back there, as the wizard.
                                                        mapsViewModel.notice(getString(R.string.am_shown, name))
                                                        back()
                                                    } else {
                                                        viewModel.notify(R.string.mz_shown, name)
                                                        mapsViewModel.close()
                                                        toMap()
                                                    }
                                                },
                                            )
                                        }

                                        Screen.BACKUP -> {
                                            BackupScreen(vm = backupViewModel, onBack = ::back)
                                        }

                                        Screen.ABOUT -> {
                                            AboutScreen(onBack = ::back, section = helpSection)
                                        }

                                        Screen.DIAGNOSTICS -> {
                                            SpikeScreen(
                                                sources = sources,
                                                stats = tileStats,
                                                offline = offlineMode,
                                                onBack = ::back,
                                            )
                                        }

                                        Screen.DOWNLOADS -> {
                                            DownloadScreen(
                                                vm = downloadViewModel,
                                                onBack = ::back,
                                                onSelectZone = {
                                                    mapsViewModel.askSaveArea()
                                                    toMap()
                                                },
                                            )
                                        }

                                        Screen.STORAGE -> {
                                            StorageScreen(
                                                vm = storageViewModel,
                                                onBack = ::back,
                                                onSelectZone = {
                                                    mapsViewModel.askSaveArea()
                                                    toMap()
                                                },
                                            )
                                        }

                                        Screen.WIZARD -> {
                                            SourceWizardScreen(
                                                vm = wizardViewModel,
                                                onClose = { addedId ->
                                                    if (addedId != null) {
                                                        viewModel.addLayer(addedId)
                                                        val name = repository.find(addedId)?.name ?: addedId
                                                        viewModel.notify(R.string.wizard_saved, name)
                                                    }
                                                    back()
                                                },
                                            )
                                        }

                                        Screen.TRACKS -> {
                                            TracksScreen(
                                                vm = tracksViewModel,
                                                onBack = ::back,
                                                onImport = { trackPicker.launch(arrayOf("*/*")) },
                                                onOpenDetail = { id ->
                                                    detailId = id
                                                    open(Screen.TRACK_DETAIL)
                                                },
                                                onShowOnMap = { request ->
                                                    tracksViewModel.fitMapTo(request)
                                                    toMap()
                                                },
                                                onOpenOnMap = { ref, request ->
                                                    tracksViewModel.fitMapTo(request)
                                                    tracksViewModel.onMapTap(ref)
                                                    toMap()
                                                },
                                                onDrawTrack = {
                                                    drawViewModel.startNewTrack()
                                                    toMap()
                                                },
                                                mapCenter = { viewModel.state.value.camera.let { it.lat to it.lon } },
                                                onGoTo = { w ->
                                                    navigationViewModel.setTarget(NavTarget(w.lat, w.lon, w.name))
                                                    toMap()
                                                },
                                            )
                                        }

                                        Screen.TRACK_DETAIL -> {
                                            TrackDetailScreen(
                                                vm = tracksViewModel,
                                                trackId = detailId,
                                                onBack = ::back,
                                                onShowOnMap = { request ->
                                                    tracksViewModel.fitMapTo(request)
                                                    toMap()
                                                },
                                                onDownloadAround = { id, radius ->
                                                    downloadAround(id, radius) { open(Screen.DOWNLOADS) }
                                                },
                                                onPrintAlong = { id ->
                                                    printAlong(id, settings.coordinates) { toMap() }
                                                },
                                                onEdit = { id ->
                                                    drawViewModel.startEditTrack(id)
                                                    toMap()
                                                },
                                                onFollow = { id ->
                                                    alertController.follow(ItemRef(ItemKind.TRACK, id))
                                                    toMap()
                                                },
                                            )
                                        }

                                        Screen.SHARE -> {
                                            ShareScreen(
                                                vm = shareViewModel,
                                                onBack = ::back,
                                                onShowOnMap = { lat, lon ->
                                                    tracksViewModel.fitMapTo(MapRequest(lat, lon, lat, lon))
                                                    toMap()
                                                },
                                                onGoTo = { lat, lon, name ->
                                                    navigationViewModel.setTarget(NavTarget(lat, lon, name))
                                                    toMap()
                                                },
                                            )
                                        }

                                        Screen.AVAILABLE -> {
                                            AvailableMapsScreen(
                                                maps = mapsViewModel,
                                                vm = viewModel,
                                                actions =
                                                    AvailableMapsActions(
                                                        onBack = ::back,
                                                        onOpenWizard = { sourceId -> openWizard(sourceId) },
                                                        onImport = { mapPicker.launch(arrayOf("*/*")) },
                                                        onOpenStorage = {
                                                            storageViewModel.refresh()
                                                            open(Screen.STORAGE)
                                                        },
                                                        onNewMultiZoom = {
                                                            openMultiZoom(editorOnly = true) { startNew() }
                                                        },
                                                        onEditMultiZoom = { id ->
                                                            openMultiZoom(editorOnly = true) { edit(id) }
                                                        },
                                                        onDeriveMultiZoom = { id ->
                                                            openMultiZoom(editorOnly = true) { derive(id) }
                                                        },
                                                        onSaveZone = { id ->
                                                            mapsViewModel.askSaveArea(only = id)
                                                            toMap()
                                                        },
                                                        onNewMap = { kind ->
                                                            openWizard(null)
                                                            wizardViewModel.chooseKind(kind)
                                                        },
                                                    ),
                                            )
                                        }

                                        Screen.MAP -> {
                                            Unit
                                        }
                                    }
                                }
                            }
                        }
                        // An alert of the group comes before everything else, on whichever screen is showing; once
                        // lifted before it was seen, « Alerte levée » takes its place until « OK » (P22, variant C).
                        shareUi.sosLifted.firstOrNull { it.dialog }?.let { lifted ->
                            AlertLiftedDialog(lifted, onOk = { shareViewModel.acknowledgeLift(lifted.memberId) })
                        }
                        shareUi.sos.firstOrNull { !it.acknowledged }?.let { alert ->
                            GroupAlertDialog(
                                alert = alert,
                                from = shareUi.self?.fix,
                                onAcknowledge = { shareViewModel.acknowledgeSos(alert.memberId) },
                                onShowOnMap =
                                    alert.fix?.let { f ->
                                        {
                                            tracksViewModel.fitMapTo(MapRequest(f.lat, f.lon, f.lat, f.lon))
                                            toMap()
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" / "Share" of an `.aqx` map definition or of a GPX, KML, KMZ or GeoJSON file from another app. */
    private fun handleIntent(intent: Intent?) {
        // The fixed starting points of the measure scripts (tools/perf): debug and benchmark builds only.
        if (BuildConfig.PERF_LOG) PerfIntent.handle(intent, viewModel, zoneQueue)
        val data = intent?.data
        EventLog.event("app.intent", "action" to intent?.action, "scheme" to data?.scheme)
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == PAIR_SCHEME) {
            shareViewModel.onLink(data.toString())
            return
        }
        val uri: Uri? =
            when (intent?.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> intent.streamExtra()
                else -> null
            }
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) { readForImport(uri, tracksOnly = false) }
    }

    /** The content decides what a file is: a track file goes to the library, anything else is tried as a map. */
    private fun readForImport(
        uri: Uri,
        tracksOnly: Boolean,
    ) {
        try {
            val read = UriReader.readBytes(this, uri, TrackImporter.MAX_BYTES)
            when {
                tracksOnly || TrackImporter.detect(read.bytes) != null -> {
                    tracksViewModel.import(read.bytes, read.name)
                }

                read.bytes.size > UriReader.DEFAULT_MAX_BYTES -> {
                    viewModel.reportImportReadError("File too large (over 6 MB)")
                }

                else -> {
                    viewModel.previewImport(String(read.bytes, Charsets.UTF_8), read.name)
                }
            }
        } catch (e: java.io.IOException) {
            reportReadError(e, tracksOnly)
        } catch (e: SecurityException) {
            reportReadError(e, tracksOnly)
        }
    }

    private fun reportReadError(
        e: Exception,
        tracksOnly: Boolean,
    ) {
        val reason = e.message ?: e.javaClass.simpleName
        if (tracksOnly) tracksViewModel.reportReadError(reason) else viewModel.reportImportReadError(reason)
    }

    /** Opens the download form for a band around a track, for the layers on screen. */
    private fun downloadAround(
        trackId: Long,
        radiusMeters: Double,
        onReady: () -> Unit,
    ) {
        lifecycleScope.launch {
            val path = tracksViewModel.corridorPath(trackId)
            if (path.size < 2) return@launch
            val shape = ZoneShape.Corridor(path.map { ZoneShape.Point(it.lon, it.lat) }, radiusMeters)
            downloadViewModel.prepare(viewModel.pendingZone(shape, CORRIDOR_MIN_ZOOM, CORRIDOR_MAX_ZOOM))
            onReady()
        }
    }

    /**
     * « Imprimer le long de cette trace… » (F9): the track is shown (it is printed with the map), then the two questions
     * of the print, over the map.
     */
    private fun printAlong(
        trackId: Long,
        coordinates: CoordinateSystem,
        onReady: () -> Unit,
    ) {
        lifecycleScope.launch {
            val path = tracksViewModel.corridorPath(trackId)
            if (path.size < 2) return@launch
            tracksViewModel.setVisible(ItemRef(ItemKind.TRACK, trackId), true)
            printViewModel.openAlongTrack(
                path.map { LonLat(it.lon, it.lat) },
                viewModel.state.value.stack.layers,
                coordinates,
            )
            onReady()
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
}

/** The back stack is saved by the names of its screens. */
private val ScreenStackSaver =
    Saver<List<Screen>, ArrayList<String>>(
        save = { stack -> ArrayList(stack.map { it.name }) },
        restore = { names -> names.map(Screen::valueOf) },
    )

/** Scrims behind the navigation bar when it is not transparent (3-button navigation). */
private const val NAV_BAR_LIGHT_SCRIM = 0xE6FFFFFF.toInt()
private const val NAV_BAR_DARK_SCRIM = 0x801B1B1B.toInt()

/** The scheme of the pairing links of the position sharing (`toolmap://pair?d=…`). */
private const val PAIR_SCHEME = "toolmap"

/** No track selected in the statistics screen. */
private const val NO_TRACK = -1L

/** A corridor download starts at the zooms a walker uses (the form lets the user change them). */
private const val CORRIDOR_MIN_ZOOM = 12
private const val CORRIDOR_MAX_ZOOM = 16
