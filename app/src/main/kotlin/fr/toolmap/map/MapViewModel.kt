package fr.toolmap.map

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoomLimit
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.Workspaces
import fr.toolmap.core.maps.MapCatalog
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.state.WorkState
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.DuplicatePolicy
import fr.toolmap.data.sources.ImportPreview
import fr.toolmap.data.sources.SourceImportException
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.state.WorkStateStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.TileStats
import fr.toolmap.data.workspaces.WorkspaceStore
import fr.toolmap.download.PendingZone
import fr.toolmap.record.GnssSatellites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A short message for the snackbar: a string resource and its arguments. */
class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
)

/** State and actions behind the main map screen; persists the [WorkState] shortly after each change. */
@OptIn(FlowPreview::class)
@HiltViewModel
class MapViewModel
    @Inject
    constructor(
        private val repository: SourceRepository,
        private val store: WorkStateStore,
        val tileStats: TileStats,
        private val offlineMode: OfflineMode,
        private val workspaceStore: WorkspaceStore,
        private val settingsStore: SettingsStore,
        private val gnss: GnssSatellites,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(store.load().withoutRetiredMaps())
        val state: StateFlow<WorkState> = mutableState.asStateFlow()

        /** The satellites used by the GPS fix, while the map collects it (the GPS box of the top left corner). */
        fun satellites(): Flow<Int> = gnss.used()

        /** How far maps borrow tiles of another zoom to never show an empty area (option of the settings, ADR-031). */
        val scaling: StateFlow<ScalingPolicy> =
            settingsStore.settings
                .map { it.maps.scaling }
                .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settings.value.maps.scaling)

        /** Screen pixels per tile pixel chosen in the settings ("map sharpness", ADR-032). */
        val tileMagnification: StateFlow<Double> =
            settingsStore.settings
                .map { it.maps.tileMagnification }
                .stateIn(viewModelScope, SharingStarted.Eagerly, settingsStore.settings.value.maps.tileMagnification)
        val sources: StateFlow<List<TileSource>> = repository.sources
        val offline: StateFlow<Boolean> = offlineMode.enabled

        /** Workspaces saved by the user (the built-in presets are `Workspaces.builtin`). */
        val savedWorkspaces: StateFlow<List<Workspace>> = workspaceStore.workspaces

        /** The favorites: built-in presets the user kept, then the saved workspaces. */
        val favorites: StateFlow<List<Workspace>> = workspaceStore.favorites

        /** Ids of the built-in presets the user deleted (they can be shown again). */
        val hiddenBuiltins: StateFlow<Set<String>> = workspaceStore.hiddenBuiltins

        private val mutableCoverage = MutableStateFlow(false)

        /** True while the stored-coverage overlay is shown. */
        val coverage: StateFlow<Boolean> = mutableCoverage.asStateFlow()

        private val mutablePreview = MutableStateFlow<ImportPreview?>(null)

        /** Non-null while the import confirmation dialog is open. */
        val importPreview: StateFlow<ImportPreview?> = mutablePreview.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        init {
            offlineMode.set(mutableState.value.offline)
            viewModelScope.launch {
                mutableState.debounce(SAVE_DELAY_MS).collect { value ->
                    withContext(Dispatchers.IO) { runCatching { store.save(value) } }
                }
            }
            viewModelScope.launch {
                offlineMode.enabled.collect { value -> mutableState.update { it.copy(offline = value) } }
            }
        }

        /** A state saved before a built-in map was retired, with its maps put back (ADR-063). */
        private fun WorkState.withoutRetiredMaps(): WorkState {
            val known = { id: String -> repository.find(id) != null }
            return copy(
                stack = Workspaces.replaceRetired(stack, known),
                recentBases = recentBases.filter(known).ifEmpty { listOf("osm") },
            )
        }

        /** A base map is a visible-background source: not a transparent overlay and not elevation data. */
        fun isBase(sourceId: String): Boolean {
            val source = repository.find(sourceId) ?: return false
            return !source.isLayer && source.type != "dem"
        }

        fun setCamera(camera: CameraState) = mutableState.update { it.withCamera(camera) }

        fun addLayer(sourceId: String) {
            val source = repository.find(sourceId) ?: return
            mutableState.update { it.withStack(it.stack.add(sourceId, opacity = source.opacity / PERCENT)) }
        }

        fun removeLayer(id: String) = mutableState.update { it.withStack(it.stack.remove(id)) }

        /** "Stop displaying the others": keeps only the layer [id] in the stack. */
        fun removeOtherLayers(id: String) = mutableState.update { it.withStack(it.stack.removeOthers(id)) }

        /** Takes every layer of the map [sourceId] off the stack. */
        fun hideSource(sourceId: String) {
            mutableState.update { current ->
                val ids = current.stack.layers.filter { it.sourceId == sourceId }.map { it.id }
                current.withStack(ids.fold(current.stack) { stack, id -> stack.remove(id) })
            }
        }

        fun setInverted(
            id: String,
            inverted: Boolean,
        ) = mutableState.update { it.withStack(it.stack.setInverted(id, inverted)) }

        fun moveLayerUp(id: String) = mutableState.update { it.withStack(it.stack.moveUp(id)) }

        fun moveLayerDown(id: String) = mutableState.update { it.withStack(it.stack.moveDown(id)) }

        fun setVisible(
            id: String,
            visible: Boolean,
        ) = mutableState.update { it.withStack(it.stack.setVisible(id, visible)) }

        fun setOpacity(
            id: String,
            opacity: Float,
        ) = mutableState.update { it.withStack(it.stack.setOpacity(id, opacity)) }

        /** Display range of a layer in displayed zoom, inclusive; `null` = no limit (feature N3). */
        fun setZoomRange(
            id: String,
            min: Int?,
            max: Int?,
        ) = mutableState.update { it.withStack(it.stack.setZoomRange(id, min, max)) }

        /** Elevation band of a layer in metres, inclusive; `null` = no limit (P20). */
        fun setElevationRange(
            id: String,
            min: Int?,
            max: Int?,
            logic: RangeLogic,
        ) = mutableState.update { it.withStack(it.stack.setElevationRange(id, min, max, logic)) }

        /** Replaces the layer stack with [workspace]; layers whose map is not available here are left out. */
        fun applyWorkspace(workspace: Workspace) {
            // A map hidden from the list (F3) is left out like a missing one: « Cartes indisponibles ignorées ».
            val hidden = settingsStore.settings.value.maps.hiddenMaps
            val applied =
                workspace.toStack { id ->
                    repository.find(id)?.isSupported == true && !MapCatalog.isHidden(id, hidden, repository::isImported)
                }
            mutableState.update { current ->
                val next = current.withStack(applied.stack)
                applied.stack.baseLayer(::isBase)?.let { next.withBaseUsed(it.sourceId) } ?: next
            }
            mutableMessages.tryEmit(UiMessage(R.string.workspace_applied, listOf(workspace.name)))
            if (applied.skipped.isNotEmpty()) {
                mutableMessages.tryEmit(UiMessage(R.string.workspace_skipped, listOf(applied.skipped.size)))
            }
        }

        /** Saves the current layer stack, with its zoom ranges, as a workspace called [name]. */
        fun saveWorkspace(name: String) {
            if (name.isBlank()) return
            viewModelScope.launch(Dispatchers.IO) {
                workspaceStore.save(Workspace.of(WorkspaceStore.newId(), name.trim(), mutableState.value.stack))
            }
            mutableMessages.tryEmit(UiMessage(R.string.workspace_saved, listOf(name.trim())))
        }

        fun deleteWorkspace(id: String) {
            viewModelScope.launch(Dispatchers.IO) { workspaceStore.delete(id) }
        }

        /** Shows again the built-in favorites the user deleted. */
        fun restoreBuiltinWorkspaces() {
            viewModelScope.launch(Dispatchers.IO) { workspaceStore.restoreBuiltins() }
        }

        fun setAdjustments(
            id: String,
            contrast: Float,
            brightness: Float,
            saturation: Float,
        ) = mutableState.update { it.withStack(it.stack.setAdjustments(id, contrast, brightness, saturation)) }

        /** Puts [sourceId] in place of the current base map and remembers the previous one for the quick swap. */
        fun replaceBase(sourceId: String) {
            mutableState.update { current ->
                val newStack = current.stack.replaceBase(sourceId, ::isBase)
                current.withStack(newStack).withBaseUsed(sourceId)
            }
        }

        /** Quick swap between the two last base maps. */
        fun swapBase() {
            val target = mutableState.value.swapTarget() ?: return
            replaceBase(target)
        }

        fun setOffline(value: Boolean) = offlineMode.set(value)

        /** Changes a setting from the main menu; the settings screen reads the same store. */
        fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

        /** A download of [shape] for the visible layers, with the display range of each (feature N3). */
        fun pendingZone(
            shape: ZoneShape,
            minZoom: Int,
            maxZoom: Int,
        ): PendingZone {
            val visible = mutableState.value.stack.layers.filter { it.visible }
            val limits =
                visible
                    .filter { it.displayMinZoom != null || it.displayMaxZoom != null }
                    .associate { it.sourceId to ZoomLimit(it.displayMinZoom, it.displayMaxZoom) }
            return PendingZone(shape, visible.map { it.sourceId }, minZoom, maxZoom, limits)
        }

        fun setCoverage(value: Boolean) {
            mutableCoverage.value = value
        }

        /** Reads an `.aqx` text and opens the confirmation dialog (or reports why it cannot be imported). */
        fun previewImport(
            text: String,
            fileName: String?,
        ) {
            try {
                mutablePreview.value = repository.preview(text, fileName)
            } catch (e: SourceImportException) {
                mutableMessages.tryEmit(UiMessage(R.string.import_failed, listOf(e.message.orEmpty())))
            }
        }

        /** Shows a message in the snackbar of the map screen. */
        fun notify(
            @StringRes resId: Int,
            vararg args: Any,
        ) {
            mutableMessages.tryEmit(UiMessage(resId, args.toList()))
        }

        fun reportImportReadError(reason: String) {
            mutableMessages.tryEmit(UiMessage(R.string.import_failed, listOf(reason)))
        }

        fun dismissImport() {
            mutablePreview.value = null
        }

        fun confirmImport(
            policy: DuplicatePolicy,
            selectedIds: Set<String>,
        ) {
            val preview = mutablePreview.value ?: return
            mutablePreview.value = null
            viewModelScope.launch {
                val outcome = withContext(Dispatchers.IO) { repository.import(preview, policy, selectedIds) }
                val count = outcome.added.size + outcome.replaced.size
                mutableMessages.tryEmit(UiMessage(R.string.import_done, listOf(count)))
            }
        }

        fun deleteSource(id: String) {
            if (repository.delete(id)) hideSource(id)
        }

        fun isImported(id: String): Boolean = repository.isImported(id)

        private companion object {
            const val SAVE_DELAY_MS = 400L
            const val PERCENT = 100f
        }
    }
