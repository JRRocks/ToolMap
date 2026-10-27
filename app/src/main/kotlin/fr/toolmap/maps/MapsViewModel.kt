package fr.toolmap.maps

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.aqx.AqxWriter
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoomLimit
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.layers.LayerStack
import fr.toolmap.core.maps.AreaCorner
import fr.toolmap.core.maps.AreaSelection
import fr.toolmap.core.maps.CatalogEntry
import fr.toolmap.core.maps.CatalogGroup
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.maps.MapGroup
import fr.toolmap.core.maps.OnlineCatalog
import fr.toolmap.core.maps.ZoneStrips
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.wizard.SourceIds
import fr.toolmap.data.elevation.SourceElevationTiles
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.ForcedRefresh
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.download.ZoneEstimate
import fr.toolmap.download.ZoneEstimator
import fr.toolmap.download.ZoneQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * The panel of the map screen that is open (`NONE` = only the map). `CONDITIONS` is the « Conditions d'affichage »
 * panel of the layer [MapsViewModel.conditionsLayer] (P20).
 */
enum class MapsPage { NONE, ROOT, SAVE_AREA, CONDITIONS }

/** Disk figures behind the gauge of the "Available maps" screen. */
data class StorageFigures(
    val tilesBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    /** Tiles and disk size stored for each map (shared tile sets count for every map showing them). */
    val perSource: Map<String, Pair<Long, Long>>,
)

/**
 * One map of the "Select and save an area" list, saved at the zooms [minZoom]..[maxZoom] it serves. The « Altitudes »
 * row (P20) has [forMaps]: the maps drawn in an elevation band, whose masks need these elevation tiles offline; it
 * saves only the zooms the masks read.
 */
data class AreaRow(
    val source: TileSource,
    val checked: Boolean,
    val forMaps: List<String> = emptyList(),
    val minZoom: Int = source.minZoom,
    val maxZoom: Int = source.maxZoom,
) {
    val isElevation: Boolean get() = forMaps.isNotEmpty()
}

/** State of the "Select and save an area" mode. */
data class SaveAreaUi(
    val selection: AreaSelection,
    val rows: List<AreaRow>,
    val maxZoom: Int,
    val sliderMax: Int,
    /** `null` while the figures are being computed. */
    val estimate: ZoneEstimate? = null,
    val estimating: Boolean = true,
) {
    /** Something to fetch: a zone already stored in full has nothing left to save. */
    val canSave: Boolean
        get() = estimate is ZoneEstimate.Ready && estimate.missing > 0 && rows.any { it.checked }
}

/** Why a save could not start. */
enum class SaveRefusal { OFFLINE, NOTHING }

/**
 * State behind the "Maps & layers" panels: which page is open, the catalogue, the disk figures and the area
 * being selected for an offline save. The layer stack itself stays in the map view model.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MapsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: SourceRepository,
        private val storage: StorageManager,
        private val estimator: ZoneEstimator,
        private val queue: ZoneQueue,
        private val offlineMode: OfflineMode,
        val forcedRefresh: ForcedRefresh,
        val thumbnails: MapThumbnails,
        private val settingsStore: SettingsStore,
    ) : ViewModel() {
        private val mutablePage = MutableStateFlow(MapsPage.NONE)
        val page: StateFlow<MapsPage> = mutablePage.asStateFlow()

        private val mutableConditionsLayer = MutableStateFlow<String?>(null)

        /** The layer whose « Conditions d'affichage » are open (page `CONDITIONS`). */
        val conditionsLayer: StateFlow<String?> = mutableConditionsLayer.asStateFlow()

        private val mutableExpandedLayer = MutableStateFlow<String?>(null)

        /**
         * The layer whose line is unfolded in « Cartes et calques » (P29.3, « Strates »): kept while its conditions are
         * open, so that closing them comes back to the line being set.
         */
        val expandedLayer: StateFlow<String?> = mutableExpandedLayer.asStateFlow()

        fun setExpanded(layerId: String?) {
            mutableExpandedLayer.value = layerId
        }

        val sources: StateFlow<List<TileSource>> = repository.sources

        /** Maps whose stored tiles are fetched again when the map asks for them (this session only). */
        val forced: StateFlow<Set<String>> = forcedRefresh.sourceIds

        /**
         * The catalogue by family, recomputed when a map is added or removed. Built when the maps panel first asks for it
         * (P15.6): the map screen creates this view model at every start, and the catalogue is not needed to draw the map.
         */
        val groups: StateFlow<List<MapGroup>> by lazy {
            combine(repository.sources, settingsStore.settings, mutableShowHidden) { list, settings, show ->
                MapCatalog.groups(list, settings.maps.hiddenMaps, show, repository::isImported)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, catalogue(repository.sources.value))
        }

        private fun catalogue(list: List<TileSource>) =
            MapCatalog.groups(
                list,
                settingsStore.settings.value.maps.hiddenMaps,
                mutableShowHidden.value,
                repository::isImported,
            )

        private val mutableShowHidden = MutableStateFlow(false)

        /** « N cartes masquées · Afficher » is on: the hidden maps are listed too, greyed (F3). */
        val showHidden: StateFlow<Boolean> = mutableShowHidden.asStateFlow()

        /** The provided maps the user hid from the list (F3), among those the app still ships. */
        val hidden: StateFlow<Set<String>> by lazy {
            combine(repository.sources, settingsStore.settings) { list, settings ->
                hiddenAmong(list, settings.maps.hiddenMaps)
            }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                hiddenAmong(repository.sources.value, settingsStore.settings.value.maps.hiddenMaps),
            )
        }

        private fun hiddenAmong(
            list: List<TileSource>,
            ids: Set<String>,
        ): Set<String> = list.map { it.id }.filter { MapCatalog.isHidden(it, ids, repository::isImported) }.toSet()

        fun setShowHidden(show: Boolean) {
            mutableShowHidden.value = show
        }

        /** « Masquer de la liste »: the map leaves the catalogue and the favorites (the map view model stops showing it). */
        fun hideFromList(sourceId: String) =
            settingsStore.update { it.copy(maps = it.maps.copy(hiddenMaps = it.maps.hiddenMaps + sourceId)) }

        /** « Remettre dans la liste ». The last hidden map back in the list also turns « Afficher » off. */
        fun showInList(sourceId: String) {
            settingsStore.update { it.copy(maps = it.maps.copy(hiddenMaps = it.maps.hiddenMaps - sourceId)) }
            if (hiddenAmong(repository.sources.value, settingsStore.settings.value.maps.hiddenMaps).isEmpty()) {
                mutableShowHidden.value = false
            }
        }

        /** « Vider les tuiles stockées » of a map of the catalogue (F4): its tile file emptied, then the figures read again. */
        suspend fun clearTiles(sourceId: String): Boolean {
            val done = withContext(Dispatchers.IO) { runCatching { storage.purge(sourceId) }.isSuccess }
            refreshStorage()
            return done
        }

        /** The file name offered when a map is exported as `.aqx`. */
        fun aqxFileName(source: TileSource): String = SourceIds.fromName(source.name) + ".aqx"

        /**
         * « Exporter en .aqx… » (F4): the map alone in an `.aqx` file. The keys of a map the user added are left out,
         * as the wizard does by default; a provided map keeps the public key it ships with.
         */
        suspend fun exportAqx(
            sourceId: String,
            uri: Uri,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val source = repository.find(sourceId) ?: throw IOException("Unknown map $sourceId")
                    val text =
                        AqxWriter.write(
                            listOf(source),
                            name = source.name,
                            redactedParams = if (repository.isImported(sourceId)) source.params.keys else emptySet(),
                        )
                    val out =
                        context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }

        /** The online catalogue (P16.4): read once, it ships with the app. */
        val onlineCatalog: List<CatalogGroup> by lazy { OnlineCatalog.load() }

        /** The ids of the maps of the online catalogue that the user's catalogue already gives (same id or tiles). */
        val catalogAdded: StateFlow<Set<String>> by lazy {
            repository.sources
                .map { _ -> alreadyAdded() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, alreadyAdded())
        }

        private fun alreadyAdded(): Set<String> =
            onlineCatalog
                .flatMap { it.entries }
                .filter { entry -> repository.alreadyHave(entry.source) != null }
                .map { entry -> entry.source.id }
                .toSet()

        /** The maps of the online catalogue whose name holds every word of [query] (P29.6). */
        fun catalogMatches(query: String): List<CatalogEntry> =
            onlineCatalog.flatMap { it.entries }.filter { MapCatalog.matches(it.source.name, query) }

        // A channel, not a shared flow: the message is sent while « Cartes disponibles » is not composed (the
        // multi-zoom editor is in front) and must wait for it.
        private val noticeChannel = Channel<String>(Channel.BUFFERED)

        /**
         * Messages for « Cartes disponibles » from the screens it opens (a multi-zoom map saved and shown, P29.6),
         * shown when it is back in front.
         */
        val notices: Flow<String> = noticeChannel.receiveAsFlow()

        fun notice(text: String) {
            noticeChannel.trySend(text)
        }

        /** Adds a map of the online catalogue, with the API keys the user gave for it. */
        fun addFromCatalog(
            entry: CatalogEntry,
            keys: Map<String, String>,
        ) {
            repository.addFromCatalog(OnlineCatalog.withKeys(entry.source, keys))
        }

        private val mutableStorage = MutableStateFlow<StorageFigures?>(null)
        val storageFigures: StateFlow<StorageFigures?> = mutableStorage.asStateFlow()

        private val mutableArea = MutableStateFlow<SaveAreaUi?>(null)
        val area: StateFlow<SaveAreaUi?> = mutableArea.asStateFlow()

        init {
            viewModelScope.launch {
                mutableArea
                    .map { ui -> ui?.let { EstimateInput(it.selection, it.rows.filter(AreaRow::checked), it.maxZoom) } }
                    .distinctUntilChanged()
                    .debounce(ESTIMATE_DEBOUNCE_MS)
                    .collect { input -> if (input != null) recompute(input) }
            }
        }

        fun open(page: MapsPage) {
            if (page != MapsPage.SAVE_AREA) mutableArea.value = null
            mutablePage.value = page
        }

        fun close() = open(MapsPage.NONE)

        /** Opens the « Conditions d'affichage » panel of layer [layerId] over the map, the maps panel closing. */
        fun openConditions(layerId: String) {
            mutableConditionsLayer.value = layerId
            mutableExpandedLayer.value = layerId
            open(MapsPage.CONDITIONS)
        }

        /** Closes the conditions: back to the maps panel, the line of the layer still unfolded (P29.3). */
        fun closeConditions() = open(MapsPage.ROOT)

        private val mutableSaveAreaAsked = MutableStateFlow(false)

        /**
         * Another screen asked the map to start a zone selection: « Cartes hors ligne » with nothing in it, or
         * « Télécharger une zone… » of a map of the catalogue (F4).
         */
        val saveAreaAsked: StateFlow<Boolean> = mutableSaveAreaAsked.asStateFlow()

        /** The one map to check in the next zone selection, asked by the catalogue. */
        private var onlyMap: String? = null

        fun askSaveArea(only: String? = null) {
            onlyMap = only
            mutableSaveAreaAsked.value = true
        }

        fun saveAreaStarted() {
            mutableSaveAreaAsked.value = false
        }

        fun isImported(id: String): Boolean = repository.isImported(id)

        fun setForced(
            sourceId: String,
            forced: Boolean,
        ) = forcedRefresh.set(sourceId, forced)

        /** Reads the disk figures (a few queries on the tile files, so off the main thread). */
        fun refreshStorage() {
            viewModelScope.launch {
                mutableStorage.value =
                    withContext(Dispatchers.IO) {
                        val usages = runCatching { storage.usage() }.getOrDefault(emptyList())
                        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
                        StorageFigures(
                            tilesBytes = usages.sumOf { it.diskBytes },
                            freeBytes = stat?.availableBytes ?: 0L,
                            totalBytes = stat?.totalBytes ?: 0L,
                            // A tile set shown by several maps (ADR-031) counts for each of them.
                            perSource = runCatching { storage.usageBySource(usages) }.getOrDefault(emptyMap()),
                        )
                    }
            }
        }

        // ---- Select and save an area ----

        /**
         * Opens the mode over the part of the map that stays visible above the panel. The maps offered are those
         * of [stack] that have a server; the visible ones start checked. Asked from the catalogue for one map, that
         * map comes first and alone checked.
         */
        fun openSaveArea(
            visible: ZoneShape.Rectangle,
            tileZoom: Int,
            stack: LayerStack,
        ) {
            val only = onlyMap?.let(repository::find)?.takeIf { it.isSupported && !it.isLocalOnly }
            onlyMap = null
            val shown =
                stack.layers
                    .distinctBy { it.sourceId }
                    .mapNotNull { layer ->
                        val source = repository.find(layer.sourceId) ?: return@mapNotNull null
                        if (!source.isSupported || source.isLocalOnly) return@mapNotNull null
                        AreaRow(source, checked = layer.visible && only == null)
                    }.asReversed()
            val rows =
                if (only ==
                    null
                ) {
                    shown
                } else {
                    listOf(AreaRow(only, checked = true)) + shown.filter { it.source.id != only.id }
                }
            val sliderMax = ZoneStrips.sliderMax(rows.map { it.source.maxZoom })
            val all = listOfNotNull(elevationRow(stack, rows)).let { rows + it }
            val upper =
                ZoneShape.Rectangle(
                    visible.west,
                    visible.north - (visible.north - visible.south) * UPPER_PART,
                    visible.east,
                    visible.north,
                )
            mutableArea.value =
                SaveAreaUi(
                    selection = AreaSelection.centered(upper, START_FRACTION),
                    rows = all,
                    maxZoom = ZoneStrips.defaultMax(tileZoom, EXTRA_ZOOMS, sliderMax),
                    sliderMax = sliderMax,
                )
            mutablePage.value = MapsPage.SAVE_AREA
        }

        /**
         * The « Altitudes » row: offered when maps of [rows] are drawn in an elevation band, checked when one of them
         * is, from their first zoom to the deepest the masks read ([DemWindow.MAX_DEM_ZOOM]).
         */
        private fun elevationRow(
            stack: LayerStack,
            rows: List<AreaRow>,
        ): AreaRow? {
            val dem =
                repository
                    .find(SourceElevationTiles.TERRARIUM_SOURCE_ID)
                    ?.takeIf { it.isSupported && !it.isLocalOnly && rows.none { row -> row.source.id == it.id } }
                    ?: return null
            val banded = stack.layers.filter { it.hasElevationRange }.map { it.sourceId }.toSet()
            val maps = rows.filter { it.source.id in banded }.takeIf { it.isNotEmpty() } ?: return null
            val deepest = minOf(DemWindow.MAX_DEM_ZOOM, dem.maxZoom)
            return AreaRow(
                source = dem,
                checked = maps.any { it.checked },
                forMaps = maps.map { it.source.name },
                minZoom = maps.minOf { it.source.minZoom }.coerceIn(dem.minZoom, deepest),
                maxZoom = deepest,
            )
        }

        fun dragCorner(
            corner: AreaCorner,
            lat: Double,
            lon: Double,
        ) = mutableArea.update { ui ->
            val moved = ui?.selection?.drag(corner, lat, lon)
            if (ui == null || moved == ui.selection) ui else ui.copy(selection = moved!!, estimating = true)
        }

        fun setAreaChecked(
            sourceId: String,
            checked: Boolean,
        ) = mutableArea.update { ui ->
            val rows = ui?.rows?.map { if (it.source.id == sourceId) it.copy(checked = checked) else it }
            if (ui == null || rows == ui.rows) ui else ui.copy(rows = rows!!, estimating = true)
        }

        fun setAreaMaxZoom(zoom: Int) =
            mutableArea.update { ui ->
                val value = zoom.coerceIn(0, ui?.sliderMax ?: 0)
                if (ui == null || value == ui.maxZoom) ui else ui.copy(maxZoom = value, estimating = true)
            }

        /** Queues the download of the selected area; returns why it could not, or `null` once queued. */
        fun saveArea(name: String): SaveRefusal? {
            val ui = mutableArea.value ?: return SaveRefusal.NOTHING
            val checked = ui.rows.filter { it.checked }
            if (checked.isEmpty() || ui.estimate !is ZoneEstimate.Ready) return SaveRefusal.NOTHING
            if (offlineMode.isOffline()) return SaveRefusal.OFFLINE
            queue.start(request(ui.selection, checked, ui.maxZoom, name))
            return null
        }

        /** Figures of the last estimate for one map, or `null` when it has none (not checked, nothing to fetch). */
        fun rowFigures(
            ui: SaveAreaUi,
            sourceId: String,
        ) = (ui.estimate as? ZoneEstimate.Ready)?.perSource?.get(sourceId)

        private suspend fun recompute(input: EstimateInput) {
            if (input.rows.isEmpty()) {
                mutableArea.update { it?.copy(estimate = ZoneEstimate.Empty, estimating = false) }
                return
            }
            val result =
                estimator.estimate(
                    request(input.selection, input.rows, input.maxZoom, "estimate"),
                )
            mutableArea.update { ui ->
                // A newer edit already replaced the inputs: its own estimate is on the way.
                if (ui != null && input.matches(ui)) ui.copy(estimate = result, estimating = false) else ui
            }
        }

        private fun request(
            selection: AreaSelection,
            rows: List<AreaRow>,
            maxZoom: Int,
            name: String,
        ) = ZoneRequest(
            id = UUID.randomUUID().toString(),
            name = name,
            sourceIds = rows.map { it.source.id },
            minZoom = 0,
            maxZoom = maxZoom.coerceIn(0, ZoneRequest.MAX_ZOOM),
            shape = selection.toShape(),
            createdAtMillis = System.currentTimeMillis(),
            // The « Altitudes » row keeps to the zooms the masks read.
            zoomLimits =
                rows
                    .filter { it.minZoom != it.source.minZoom || it.maxZoom != it.source.maxZoom }
                    .associate { it.source.id to ZoomLimit(it.minZoom, it.maxZoom) },
        )

        private data class EstimateInput(
            val selection: AreaSelection,
            val rows: List<AreaRow>,
            val maxZoom: Int,
        ) {
            fun matches(ui: SaveAreaUi) =
                selection == ui.selection && maxZoom == ui.maxZoom && rows == ui.rows.filter(AreaRow::checked)
        }

        private companion object {
            const val ESTIMATE_DEBOUNCE_MS = 250L
            const val UPPER_PART = 0.55
            const val START_FRACTION = 0.7
            const val EXTRA_ZOOMS = 3
        }
    }
