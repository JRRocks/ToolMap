package fr.toolmap.wizard

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.aqx.AqxWriter
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.wizard.ApiKeyExtractor
import fr.toolmap.core.wizard.CapabilitiesDocument
import fr.toolmap.core.wizard.DraftIssue
import fr.toolmap.core.wizard.OgcService
import fr.toolmap.core.wizard.PreviewTile
import fr.toolmap.core.wizard.SourceDraft
import fr.toolmap.core.wizard.SourceIds
import fr.toolmap.core.wizard.WmsCapabilities
import fr.toolmap.core.wizard.WmsLayer
import fr.toolmap.core.wizard.WmsSourceBuilder
import fr.toolmap.core.wizard.WmtsCapabilities
import fr.toolmap.core.wizard.WmtsChoice
import fr.toolmap.core.wizard.WmtsLayer
import fr.toolmap.core.wizard.WmtsSourceBuilder
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.wizard.CapabilitiesFetchException
import fr.toolmap.data.wizard.CapabilitiesFetcher
import fr.toolmap.data.wizard.FetchedCapabilities
import fr.toolmap.data.wizard.ProbeResult
import fr.toolmap.data.wizard.RequestSettings
import fr.toolmap.data.wizard.TileProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** The five steps of the wizard; the kind of map is chosen before, in the sheet « Ajouter une carte » (P29.6). */
enum class WizardStep { ADDRESS, ACCESS, ZOOMS, TEST, SAVE }

/** How the map is described: a tile URL template, or a WMTS / WMS service whose layers are listed. */
enum class WizardKind(
    val service: OgcService?,
) {
    XYZ(null),
    WMTS(OgcService.WMTS),
    WMS(OgcService.WMS),
}

/** The capabilities request. */
sealed interface CapabilitiesState {
    data object Idle : CapabilitiesState

    data object Loading : CapabilitiesState

    data class Loaded(
        val fetched: FetchedCapabilities,
    ) : CapabilitiesState

    data class Failed(
        val error: CapabilitiesFetchException,
    ) : CapabilitiesState
}

/** The test tile. */
sealed interface ProbeState {
    data object Idle : ProbeState

    data object Running : ProbeState

    data class Done(
        val result: ProbeResult,
    ) : ProbeState
}

/** The layer picked in a capabilities list, with the options the user can still change. */
data class LayerChoice(
    val layerId: String,
    val styleId: String = "",
    val matrixSetId: String = "",
    val format: String = "",
)

data class WizardState(
    val step: WizardStep = WizardStep.ADDRESS,
    /** The furthest step reached: the stops up to it can be touched (all of them in a modification). */
    val reached: WizardStep = WizardStep.ADDRESS,
    val kind: WizardKind = WizardKind.XYZ,
    val draft: SourceDraft = SourceDraft(),
    /** Address typed for a WMTS / WMS server. */
    val serviceAddress: String = "",
    val capabilities: CapabilitiesState = CapabilitiesState.Idle,
    val layerFilter: String = "",
    val choice: LayerChoice? = null,
    /** Names of the parameters an API key was moved to (the notice shown to the user). */
    val keysMoved: List<String> = emptyList(),
    val probe: ProbeState = ProbeState.Idle,
    /** The source being modified, or null when a new one is created. */
    val editingId: String? = null,
    val addToMap: Boolean = true,
    val redactKeysOnExport: Boolean = true,
    val savedId: String? = null,
    val error: String? = null,
    val exportedTo: String? = null,
) {
    val isEditing: Boolean get() = editingId != null
    val issues: List<DraftIssue> get() = draft.validate()
    val blockingIssues: List<DraftIssue> get() = issues.filter { it.isError }
}

/** State and actions of the add-a-map wizard (N2). */
@HiltViewModel
class SourceWizardViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: SourceRepository,
        private val storage: StorageManager,
        private val fetcher: CapabilitiesFetcher,
        private val prober: TileProbe,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(WizardState())
        val state: StateFlow<WizardState> = mutableState.asStateFlow()

        /** Centre of the map when the wizard was opened: a place to test the tile at. */
        private var mapCenter: LonLatZoom? = null
        private var fetchJob: Job? = null
        private var probeJob: Job? = null

        /** Original servers of the source being edited: when they change, stored tiles no longer match. */
        private var originalServers: List<String> = emptyList()
        private var originalTileSize: Int = 0

        /** Opens the wizard for a new map, or for [sourceId] (an imported one is modified, a built-in one copied). */
        fun start(
            sourceId: String?,
            center: LonLatZoom?,
        ) {
            fetchJob?.cancel()
            probeJob?.cancel()
            mapCenter = center
            originalServers = emptyList()
            originalTileSize = 0
            val source = sourceId?.let(repository::find)
            val draft = source?.let(SourceDraft::fromSource)
            mutableState.value =
                if (source != null && draft != null) {
                    val editing = repository.isImported(source.id)
                    if (editing) {
                        originalServers = draft.urls
                        originalTileSize = draft.tileSize
                    }
                    WizardState(
                        step = WizardStep.ADDRESS,
                        reached = if (editing) WizardStep.SAVE else WizardStep.ADDRESS,
                        draft = if (editing) draft else draft.copy(id = "", name = source.name + COPY_SUFFIX),
                        editingId = if (editing) source.id else null,
                    )
                } else {
                    WizardState()
                }
        }

        private fun update(transform: (WizardState) -> WizardState) = mutableState.update(transform)

        private fun updateDraft(transform: (SourceDraft) -> SourceDraft) =
            update { it.copy(draft = transform(it.draft), probe = ProbeState.Idle, exportedTo = null) }

        // --- navigation ---------------------------------------------------------------------------------------

        fun chooseKind(kind: WizardKind) = update { it.copy(kind = kind, step = WizardStep.ADDRESS) }

        /**
         * Goes to [step], from « Suivant », from a stop of the indicator or from « Aller à l'accès ». Leaving the
         * address step, API keys written in the URL move to parameters (kept apart, redacted on export).
         */
        fun goTo(step: WizardStep) {
            val s = mutableState.value
            if (s.step == WizardStep.ADDRESS && step != WizardStep.ADDRESS) {
                val moved = s.draft.extractKeys()
                if (moved != s.draft) {
                    update { it.copy(draft = moved, keysMoved = (moved.params.keys - s.draft.params.keys).toList()) }
                }
            }
            update { it.copy(step = step, reached = maxOf(it.reached, step), error = null) }
        }

        fun next() {
            val following = WizardStep.entries.getOrNull(mutableState.value.step.ordinal + 1) ?: return
            goTo(following)
        }

        /** The step before; from the first one, nothing: the screen closes. */
        fun back() {
            val previous = WizardStep.entries.getOrNull(mutableState.value.step.ordinal - 1) ?: return
            goTo(previous)
        }

        // --- address ------------------------------------------------------------------------------------------

        fun setUrls(text: String) = updateDraft { it.copy(urls = parseUrls(text)) }

        fun setServiceAddress(text: String) = update { it.copy(serviceAddress = text) }

        fun setLayerFilter(text: String) = update { it.copy(layerFilter = text) }

        /** Moves the API keys found in the URLs to parameters right now (the button next to the detected key). */
        fun extractKeysNow() {
            val before = mutableState.value.draft
            val after = before.extractKeys()
            update {
                it.copy(
                    draft = after,
                    keysMoved = (after.params.keys - before.params.keys).toList(),
                    probe = ProbeState.Idle,
                )
            }
        }

        /** The secret found in the first URL, for the masked display next to the detection notice. */
        fun detectedKey(): Pair<String, String>? =
            mutableState.value.draft.urls
                .firstNotNullOfOrNull { ApiKeyExtractor.extract(it).params.entries.firstOrNull() }
                ?.toPair()

        fun readCapabilities() {
            val s = mutableState.value
            val service = s.kind.service ?: return
            fetchJob?.cancel()
            update { it.copy(capabilities = CapabilitiesState.Loading, choice = null) }
            val settings =
                RequestSettings(
                    referer = s.draft.referer.trim().ifEmpty { null },
                    userAgent = s.draft.userAgent.trim().ifEmpty { null },
                    headers = s.draft.headers,
                    cookie = s.draft.cookie.trim().ifEmpty { null },
                )
            fetchJob =
                viewModelScope.launch {
                    val next =
                        try {
                            CapabilitiesState.Loaded(fetcher.fetch(s.serviceAddress, service, settings))
                        } catch (e: CapabilitiesFetchException) {
                            CapabilitiesState.Failed(e)
                        }
                    update { it.copy(capabilities = next) }
                }
        }

        fun pickWmtsLayer(layer: WmtsLayer) {
            val loaded = mutableState.value.capabilities as? CapabilitiesState.Loaded ?: return
            val caps = (loaded.fetched.document as? CapabilitiesDocument.Wmts)?.capabilities ?: return
            val default = WmtsSourceBuilder.defaultChoice(caps, layer) ?: return
            applyWmts(caps, loaded.fetched.url, default)
        }

        fun changeWmtsChoice(transform: (LayerChoice) -> LayerChoice) {
            val s = mutableState.value
            val loaded = s.capabilities as? CapabilitiesState.Loaded ?: return
            val caps = (loaded.fetched.document as? CapabilitiesDocument.Wmts)?.capabilities ?: return
            val current = s.choice ?: return
            val changed = transform(current)
            applyWmts(
                caps,
                loaded.fetched.url,
                WmtsChoice(changed.layerId, changed.styleId, changed.matrixSetId, changed.format),
            )
        }

        private fun applyWmts(
            caps: WmtsCapabilities,
            capabilitiesUrl: String,
            choice: WmtsChoice,
        ) {
            val spec = WmtsSourceBuilder.build(caps, choice, capabilitiesUrl)
            update {
                it.copy(
                    choice = LayerChoice(choice.layerId, choice.styleId, choice.matrixSetId, choice.format),
                    draft =
                        it.draft.copy(
                            name = if (it.draft.name.isBlank() || it.editingId == null) spec.name else it.draft.name,
                            urls = listOf(spec.template),
                            zoomSpec = "${spec.minZoom}-${spec.maxZoom}",
                            tileSize = spec.tileSize,
                            copyright = spec.copyright.orEmpty(),
                            isLayer = false,
                            bboxCrs = LevelDef.CRS_WGS84,
                        ),
                    probe = ProbeState.Idle,
                )
            }
        }

        fun pickWmsLayer(layer: WmsLayer) {
            val s = mutableState.value
            val loaded = s.capabilities as? CapabilitiesState.Loaded ?: return
            val caps = (loaded.fetched.document as? CapabilitiesDocument.Wms)?.capabilities ?: return
            val format = s.choice?.format?.takeIf { it in caps.formats } ?: WmsSourceBuilder.defaultFormat(caps)
            applyWms(caps, loaded.fetched.url, layer, format)
        }

        fun changeWmsFormat(format: String) {
            val s = mutableState.value
            val loaded = s.capabilities as? CapabilitiesState.Loaded ?: return
            val caps = (loaded.fetched.document as? CapabilitiesDocument.Wms)?.capabilities ?: return
            val layer = caps.layers.firstOrNull { it.name == s.choice?.layerId } ?: return
            applyWms(caps, loaded.fetched.url, layer, format)
        }

        private fun applyWms(
            caps: WmsCapabilities,
            capabilitiesUrl: String,
            layer: WmsLayer,
            format: String,
        ) {
            val spec = WmsSourceBuilder.build(caps, layer, format, capabilitiesUrl)
            update {
                it.copy(
                    choice = LayerChoice(layer.name, format = format),
                    draft =
                        it.draft.copy(
                            name = if (it.draft.name.isBlank() || it.editingId == null) spec.name else it.draft.name,
                            urls = listOf(spec.template),
                            zoomSpec = "0-18",
                            tileSize = 256,
                            copyright = caps.title.orEmpty(),
                            isLayer = format.contains("png", ignoreCase = true),
                            bboxCrs = spec.bboxCrs,
                        ),
                    probe = ProbeState.Idle,
                )
            }
        }

        // --- access, zooms and appearance -----------------------------------------------------------------------

        fun setName(value: String) = updateDraft { it.copy(name = value) }

        fun setReferer(value: String) = updateDraft { it.copy(referer = value) }

        fun setUserAgent(value: String) = updateDraft { it.copy(userAgent = value) }

        fun setHeaders(value: String) = updateDraft { it.copy(headersText = value) }

        fun setCookie(value: String) = updateDraft { it.copy(cookie = value) }

        fun setMaxThreads(value: Int) = updateDraft { it.copy(maxThreads = value) }

        fun setUpdateDelay(value: String) = updateDraft { it.copy(updateDelay = value) }

        fun setParam(
            name: String,
            value: String,
        ) = updateDraft { it.copy(params = it.params + (name to value)) }

        fun setZoomSpec(value: String) = updateDraft { it.copy(zoomSpec = value) }

        fun setTileSize(value: Int) = updateDraft { it.copy(tileSize = value) }

        fun setType(value: String?) = updateDraft { it.copy(type = value) }

        fun setLayer(value: Boolean) = updateDraft { it.copy(isLayer = value) }

        fun setOpacity(value: Int) = updateDraft { it.copy(opacity = value) }

        fun setCopyright(value: String) = updateDraft { it.copy(copyright = value) }

        /** Uses the place the map showed when the wizard opened for the test tile (clamped to the zooms served). */
        fun usePreviewHere() {
            val center = mapCenter ?: return
            updateDraft { it.copy(previewLocation = center) }
        }

        fun clearPreviewLocation() = updateDraft { it.copy(previewLocation = null) }

        val hasMapCenter: Boolean get() = mapCenter != null

        // --- test -----------------------------------------------------------------------------------------------

        fun runProbe() {
            val draft = mutableState.value.draft
            if (!draft.isValid) return
            val source = draft.copy(id = draft.id.ifBlank { "probe" }).toSource()
            val tile = PreviewTile.choose(source.levels.first().zooms, draft.previewLocation)
            probeJob?.cancel()
            update { it.copy(probe = ProbeState.Running) }
            probeJob =
                viewModelScope.launch {
                    val result = prober.probe(source, tile)
                    update { it.copy(probe = ProbeState.Done(result)) }
                }
        }

        // --- save and export ----------------------------------------------------------------------------------

        fun setAddToMap(value: Boolean) = update { it.copy(addToMap = value) }

        fun setRedactKeys(value: Boolean) = update { it.copy(redactKeysOnExport = value) }

        /**
         * True when saving will erase stored tiles: the address of the map being edited changed and its old tiles
         * are shown by no other map (a tile set shared with another map is kept, ADR-031).
         */
        fun willPurgeStoredTiles(): Boolean = purgeTargets().isNotEmpty()

        private fun purgeTargets(): Set<String> {
            val s = mutableState.value
            val id = s.editingId ?: return emptySet()
            val changed = s.draft.urls != originalServers || s.draft.tileSize != originalTileSize
            if (!changed) return emptySet()
            return storage.unusedAfterReplacing(id, s.draft.copy(id = id).toSource())
        }

        /** Stores the source (new id when needed). Returns its id, or null when the draft is not valid. */
        fun save(): String? {
            val s = mutableState.value
            if (!s.draft.isValid) return null
            val id =
                s.editingId
                    ?: s.savedId
                    ?: SourceIds.unique(s.draft.id.ifBlank { SourceIds.fromName(s.draft.name) }) {
                        repository.find(it) != null
                    }
            return try {
                val purge = purgeTargets()
                repository.save(s.draft.copy(id = id).toSource())
                purge.forEach(storage::purge)
                update { it.copy(savedId = id, draft = it.draft.copy(id = id), error = null) }
                id
            } catch (e: IOException) {
                update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                null
            } catch (e: IllegalStateException) {
                update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                null
            } catch (e: IllegalArgumentException) {
                update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                null
            }
        }

        /** The saved (or about to be saved) source as an `.aqx` text; keys blanked when asked. */
        fun aqxText(): String? {
            val s = mutableState.value
            if (!s.draft.isValid) return null
            val source: TileSource =
                s.draft.copy(
                    id =
                        s.savedId ?: s.draft.id.ifBlank { SourceIds.fromName(s.draft.name) },
                ).toSource()
            return AqxWriter.write(
                listOf(source),
                name = source.name,
                redactedParams = if (s.redactKeysOnExport) source.params.keys else emptySet(),
            )
        }

        fun suggestedFileName(): String = SourceIds.fromName(mutableState.value.draft.name) + ".aqx"

        fun exportTo(uri: Uri) {
            val text = aqxText() ?: return
            viewModelScope.launch {
                val outcome =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val out =
                                context.contentResolver.openOutputStream(uri, "wt")
                                    ?: throw IOException("Cannot open $uri")
                            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                update {
                    outcome.fold(
                        onSuccess = { _ -> it.copy(exportedTo = uri.lastPathSegment ?: uri.toString(), error = null) },
                        onFailure = { e -> it.copy(error = e.message ?: e.javaClass.simpleName) },
                    )
                }
            }
        }

        fun dismissError() = update { it.copy(error = null) }

        private companion object {
            const val COPY_SUFFIX = " (copie)"
        }
    }

/** One server per non-blank line, spaces trimmed; an empty text gives a single empty entry (the draft's default). */
internal fun parseUrls(text: String): List<String> =
    text.lines().map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf("") }
