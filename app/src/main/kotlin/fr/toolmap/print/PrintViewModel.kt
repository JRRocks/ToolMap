package fr.toolmap.print

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.R
import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.pdf.PdfImage
import fr.toolmap.core.print.AtlasMode
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.PrintArea
import fr.toolmap.core.print.PrintContent
import fr.toolmap.core.print.PrintExporter
import fr.toolmap.core.print.PrintFormat
import fr.toolmap.core.print.PrintIssue
import fr.toolmap.core.print.PrintPlan
import fr.toolmap.core.print.PrintPlanner
import fr.toolmap.core.print.PrintProgress
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.print.PrintTexts
import fr.toolmap.core.print.PrintWaypoint
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.download.RefusalReason
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.download.ZoneResult
import fr.toolmap.data.print.AndroidBandRenderer
import fr.toolmap.data.print.PrintTemplateStore
import fr.toolmap.data.print.PrintTileFetcher
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tracks.TrackDisplay
import fr.toolmap.map.UiMessage
import fr.toolmap.waypoints.WaypointIconRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A long job the sheet shows a progress bar for. */
sealed interface PrintJob {
    data class Downloading(
        val progress: ZoneProgress,
    ) : PrintJob

    data class Exporting(
        val progress: PrintProgress,
    ) : PrintJob
}

/** How an export ended, for the dialog that follows it. */
sealed interface PrintOutcome {
    data class Exported(
        val uri: Uri,
        val pages: Int,
        val bytes: Long,
        val missingTiles: Int,
    ) : PrintOutcome

    data class Failed(
        val message: String?,
    ) : PrintOutcome
}

/** The three steps of a print (ADR-061): the two questions, the frame on the map, the confirmation. */
enum class PrintStage { SETUP, FRAME, CONFIRM }

/** What the print dialogs and the frame over the map show. */
data class PrintUi(
    /** `null` when the print is not open. */
    val stage: PrintStage? = null,
    val template: PrintTemplate = PrintTemplate(),
    /** The centre of the frame on the ground: the frame stays there when the map moves. */
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0,
    /** The track the pages follow, for a print along a track (F9); empty for the single page of the print. */
    val path: List<LonLat> = emptyList(),
    val plan: PrintPlan? = null,
    val planning: Boolean = false,
    /** Tiles of the plan not in local storage yet; `null` while they are being counted. */
    val missingTiles: Int? = null,
    /** "Plus d'options" of the confirmation is unfolded. */
    val moreOptions: Boolean = false,
    /** Grows each time the frame takes a new size: the screen then fits the zoom to it again. */
    val fitRequest: Int = 0,
    val job: PrintJob? = null,
    val outcome: PrintOutcome? = null,
) {
    val active: Boolean get() = stage != null

    /** « Imprimer le long de cette trace… »: one page per frame, the frames follow the track and do not move. */
    val alongTrack: Boolean get() = path.isNotEmpty()

    /** The missing tiles can be fetched from the sheet; above this they stay hatched (P17, Q18). */
    val canDownloadMissing: Boolean get() = (missingTiles ?: 0) in 1..MAX_DOWNLOAD_TILES

    companion object {
        const val MAX_DOWNLOAD_TILES = 1_500
    }
}

/** What a plan is made for. */
private data class PlanInput(
    val template: PrintTemplate,
    val lat: Double,
    val lon: Double,
    val path: List<LonLat>,
    val stack: List<LayerConfig>,
    val sources: List<TileSource>,
)

/**
 * The state behind the print: the settings, the frame placed on the map, the plan computed for it, the missing tiles,
 * and the two long jobs (download of the missing tiles, export of the PDF). The pure work is in `:core` (planner,
 * composer, PDF writer) and `:data` (tile reading, Android drawing); this holds them together.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PrintViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val templates: PrintTemplateStore,
        private val downloader: ZoneDownloader,
        private val trackDisplay: TrackDisplay,
        private val sourceRepository: SourceRepository,
        private val fetcher: PrintTileFetcher,
    ) : ViewModel() {
        private val mutableUi = MutableStateFlow(PrintUi())
        val ui: StateFlow<PrintUi> = mutableUi.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        private val inputs = MutableStateFlow<PlanInput?>(null)
        private var stack: List<LayerConfig> = emptyList()
        private var job: Job? = null

        init {
            viewModelScope.launch {
                inputs.filterNotNull().debounce(PLAN_DEBOUNCE_MS).collectLatest { compute(it) }
            }
        }

        // --- the three steps

        /**
         * « Imprimer »: the two questions, with the settings of the last print; the first print takes the grid of the
         * coordinate format of the settings. The frame will be centred on [lat], [lon] (the map centre).
         */
        fun open(
            lat: Double,
            lon: Double,
            layers: List<LayerConfig>,
            coordinates: CoordinateSystem = CoordinateSystem.UTM,
        ) {
            stack = layers
            val last = templates.lastOrNull() ?: PrintTemplate(grid = gridFor(coordinates))
            val template = last.copy(atlas = AtlasMode.SINGLE, title = defaultTitle(layers), subtitle = "")
            mutableUi.value =
                PrintUi(stage = PrintStage.SETUP, template = template, centerLat = lat, centerLon = lon)
            requestPlan()
        }

        /**
         * « Imprimer le long de cette trace… » (F9): the same two questions, then pages chained along [path] at the
         * size and scale chosen, one page of the PDF each.
         */
        fun openAlongTrack(
            path: List<LonLat>,
            layers: List<LayerConfig>,
            coordinates: CoordinateSystem = CoordinateSystem.UTM,
        ) {
            if (path.size < 2) return
            stack = layers
            val last = templates.lastOrNull() ?: PrintTemplate(grid = gridFor(coordinates))
            val template = last.copy(atlas = AtlasMode.TRACK, title = defaultTitle(layers), subtitle = "")
            mutableUi.value =
                PrintUi(
                    stage = PrintStage.SETUP,
                    template = template,
                    centerLat = (path.minOf { it.lat } + path.maxOf { it.lat }) / 2,
                    centerLon = (path.minOf { it.lon } + path.maxOf { it.lon }) / 2,
                    path = path,
                )
            requestPlan()
        }

        /** « Choisir la zone »: the frame on the map. */
        fun chooseZone() {
            val ui = mutableUi.value
            if (ui.stage == null) return
            mutableUi.value = ui.copy(stage = PrintStage.FRAME, fitRequest = ui.fitRequest + 1)
        }

        /** « Suivant »: the confirmation, the frame stays where it is. */
        fun confirm() {
            if (mutableUi.value.stage ==
                PrintStage.FRAME
            ) {
                mutableUi.value = mutableUi.value.copy(stage = PrintStage.CONFIRM)
            }
        }

        /** « Retour à la zone ». */
        fun backToFrame() {
            if (mutableUi.value.stage ==
                PrintStage.CONFIRM
            ) {
                // The frame goes back to the middle of the map, at 70 % (it was above the sheet).
                mutableUi.value =
                    mutableUi.value.copy(stage = PrintStage.FRAME, fitRequest = mutableUi.value.fitRequest + 1)
            }
        }

        fun close() {
            job?.cancel()
            if (mutableUi.value.active) templates.remember(mutableUi.value.template)
            inputs.value = null
            mutableUi.value =
                mutableUi.value.copy(stage = null, plan = null, job = null, missingTiles = null, path = emptyList())
        }

        fun toggleMoreOptions() {
            mutableUi.value = mutableUi.value.copy(moreOptions = !mutableUi.value.moreOptions)
        }

        // --- the frame and the settings

        /** The frame was dragged to a new centre. */
        fun moveFrame(
            lat: Double,
            lon: Double,
        ) {
            if (!mutableUi.value.active || mutableUi.value.alongTrack) return
            mutableUi.value = mutableUi.value.copy(centerLat = lat, centerLon = lon)
            requestPlan()
        }

        /** The layers on screen changed: they are the ones printed. */
        fun onLayers(layers: List<LayerConfig>) {
            if (!mutableUi.value.active || layers == stack) return
            stack = layers
            requestPlan()
        }

        /** A setting changed; a new paper, orientation or scale keeps the centre and fits the zoom again. */
        fun update(change: (PrintTemplate) -> PrintTemplate) {
            val ui = mutableUi.value
            val next = change(ui.template).copy(atlas = if (ui.alongTrack) AtlasMode.TRACK else AtlasMode.SINGLE)
            val resized = next.page != ui.template.page || next.scale != ui.template.scale
            mutableUi.value =
                ui.copy(
                    template = next,
                    fitRequest =
                        if (resized &&
                            ui.stage == PrintStage.FRAME
                        ) {
                            ui.fitRequest + 1
                        } else {
                            ui.fitRequest
                        },
                )
            requestPlan()
        }

        /** ⟲: portrait ↔ landscape. */
        fun rotate() = update { it.copy(page = it.page.copy(landscape = !it.page.landscape)) }

        // --- the plan

        private fun requestPlan() {
            val ui = mutableUi.value
            if (!ui.active) return
            inputs.value =
                PlanInput(
                    template = ui.template,
                    lat = ui.centerLat,
                    lon = ui.centerLon,
                    path = ui.path,
                    stack = stack,
                    sources = sourceRepository.sources.value,
                )
        }

        private suspend fun compute(input: PlanInput) {
            mutableUi.value = mutableUi.value.copy(planning = true, missingTiles = null)
            val byId = input.sources.associateBy { it.id }
            val area = PrintArea(input.lat, input.lon, path = input.path)
            val plan =
                withContext(Dispatchers.Default) { PrintPlanner.plan(input.template, area, input.stack, byId::get) }
            mutableUi.value = mutableUi.value.copy(plan = plan)
            mutableUi.value = mutableUi.value.copy(missingTiles = countMissing(plan), planning = false)
        }

        private suspend fun countMissing(plan: PrintPlan): Int =
            if (plan.pages.isEmpty() || PrintIssue.TOO_MANY_TILES in plan.issues) {
                0
            } else {
                withContext(Dispatchers.IO) { downloader.missing(plan.requiredTiles()).values.sumOf { it.size } }
            }

        // --- downloading what is missing

        fun downloadMissing() {
            val plan = mutableUi.value.plan ?: return
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    mutableUi.value = mutableUi.value.copy(job = PrintJob.Downloading(ZoneProgress()))
                    try {
                        val result =
                            downloader.runTiles(plan.requiredTiles()) { progress ->
                                val current = mutableUi.value
                                if (current.job is PrintJob.Downloading) {
                                    mutableUi.value = current.copy(job = PrintJob.Downloading(progress))
                                }
                            }
                        mutableMessages.tryEmit(messageFor(result))
                    } finally {
                        mutableUi.value = mutableUi.value.copy(job = null)
                        // Whatever happened, some tiles may now be stored.
                        withContext(NonCancellable) {
                            mutableUi.value = mutableUi.value.copy(missingTiles = countMissing(plan))
                        }
                    }
                }
        }

        private fun messageFor(result: ZoneResult): UiMessage =
            when (result) {
                is ZoneResult.Completed -> {
                    UiMessage(
                        R.string.print_download_done,
                        listOf(result.progress.downloaded, result.progress.missing, result.progress.failed),
                    )
                }

                is ZoneResult.Interrupted -> {
                    UiMessage(R.string.print_download_interrupted, listOf(result.reason))
                }

                is ZoneResult.Refused -> {
                    when (result.reason) {
                        RefusalReason.OFFLINE -> UiMessage(R.string.print_download_refused_offline)
                        RefusalReason.RESTRICTED_SOURCE -> UiMessage(R.string.print_download_refused_restricted)
                        RefusalReason.NOTHING_TO_DOWNLOAD -> UiMessage(R.string.print_download_nothing)
                        else -> UiMessage(R.string.print_download_refused_other, listOf(result.detail))
                    }
                }
            }

        // --- exporting

        /** The name proposed to the file picker. */
        fun suggestedFileName(): String {
            val template = mutableUi.value.template
            val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            // The default title ends with today's date, which the name already carries in ISO form.
            val today = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
            val title = template.title.trim().removeSuffix(today).trim().removeSuffix("·").trim()
            return PrintFormat.fileName(title, template.scale, iso)
        }

        fun export(uri: Uri) {
            val plan = mutableUi.value.plan ?: return
            if (!plan.canExport || job?.isActive == true) return
            templates.remember(plan.template)
            job =
                viewModelScope.launch {
                    mutableUi.value =
                        mutableUi.value.copy(
                            job = PrintJob.Exporting(PrintProgress(0, plan.pages.size, 0, 1)),
                            outcome = null,
                        )
                    var written = false
                    val outcome =
                        try {
                            val content = buildContent(plan)
                            val result =
                                withContext(Dispatchers.IO) {
                                    val output =
                                        context.contentResolver.openOutputStream(uri, "w")
                                            ?: error("cannot open the destination")
                                    written = true
                                    output.buffered().use { stream ->
                                        AndroidBandRenderer(fetcher).use { renderer ->
                                            PrintExporter(renderer, ensureActive = { ensureActive() })
                                                .export(plan, content, stream) { progress ->
                                                    mutableUi.value =
                                                        mutableUi.value.copy(job = PrintJob.Exporting(progress))
                                                }
                                        }
                                    }
                                }
                            PrintOutcome.Exported(uri, result.pages, result.bytes, result.missingTiles)
                        } catch (e: CancellationException) {
                            if (written) discard(uri)
                            mutableMessages.tryEmit(UiMessage(R.string.print_cancelled))
                            mutableUi.value = mutableUi.value.copy(job = null)
                            throw e
                        } catch (e: Exception) {
                            if (written) discard(uri)
                            PrintOutcome.Failed(e.message)
                        }
                    mutableUi.value = mutableUi.value.copy(job = null, outcome = outcome)
                }
        }

        fun cancelJob() {
            job?.cancel()
        }

        fun dismissOutcome() {
            mutableUi.value = mutableUi.value.copy(outcome = null)
        }

        /** A half-written PDF is worse than none: remove it. */
        private fun discard(uri: Uri) {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        }

        private suspend fun buildContent(plan: PrintPlan): PrintContent {
            val dressing = plan.template.dressing
            val loaded = if (dressing.tracks || dressing.waypoints) trackDisplay.printItems() else null
            val layers = plan.mapPages.firstOrNull()?.layers?.layers.orEmpty()
            val frame = plan.mapPages.firstOrNull()?.frame ?: plan.pages.first().frame
            val now = System.currentTimeMillis()
            val declination = runCatching { PrintDeclination.at(frame.centerLat, frame.centerLon, now) }.getOrNull()
            val nextYear =
                runCatching {
                    PrintDeclination.at(frame.centerLat, frame.centerLon, now + YEAR_MILLIS)
                }.getOrNull()
            val waypoints = if (dressing.waypoints) loaded?.waypoints.orEmpty() else emptyList()
            val locale = Locale.getDefault()
            return PrintContent(
                tracks = if (dressing.tracks) loaded?.tracks.orEmpty() else emptyList(),
                waypoints = waypoints,
                attributions = layers.mapNotNull { it.source.copyright?.takeIf(String::isNotBlank) }.distinct(),
                layerNames = layers.map { it.source.name },
                declinationDegrees = declination,
                declinationYear = Calendar.getInstance().get(Calendar.YEAR),
                declinationAnnualDegrees =
                    if (declination != null &&
                        nextYear != null
                    ) {
                        nextYear - declination
                    } else {
                        null
                    },
                glyphs = withContext(Dispatchers.Default) { glyphs(waypoints) },
                dateText = DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(now)),
                texts = printTexts(),
                locale = locale,
            )
        }

        /** The white glyph of each icon used, as the map draws it on the coloured disc. */
        private fun glyphs(waypoints: List<PrintWaypoint>): Map<String, PdfImage> =
            waypoints
                .mapNotNull { it.iconId }
                .distinct()
                .mapNotNull { id ->
                    val res = WaypointIconRes.drawables[id] ?: return@mapNotNull null
                    val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return@mapNotNull null
                    val bitmap = createBitmap(GLYPH_PX, GLYPH_PX)
                    drawable.setTint(android.graphics.Color.WHITE)
                    drawable.setBounds(0, 0, GLYPH_PX, GLYPH_PX)
                    drawable.draw(android.graphics.Canvas(bitmap))
                    val argb = IntArray(GLYPH_PX * GLYPH_PX)
                    bitmap.getPixels(argb, 0, GLYPH_PX, 0, 0, GLYPH_PX, GLYPH_PX)
                    bitmap.recycle()
                    val white = ByteArray(argb.size * 3) { WHITE }
                    val alpha = ByteArray(argb.size) { (argb[it] ushr ALPHA_SHIFT).toByte() }
                    id to PdfImage.rgba(GLYPH_PX, GLYPH_PX, white, alpha)
                }.toMap()

        /** « IGN Cartes · 17/10/2026 »: the map printed and the day (P17, Q20). */
        private fun defaultTitle(layers: List<LayerConfig>): String {
            val byId = sourceRepository.sources.value.associateBy { it.id }
            val map = layers.firstOrNull { it.visible }?.let { byId[it.sourceId]?.name }
            val date = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())
            return listOfNotNull(map, date).joinToString(" · ")
        }

        private fun printTexts() =
            PrintTexts(
                legendTitle = context.getString(R.string.pdf_legend_title),
                layers = context.getString(R.string.pdf_layers),
                tracks = context.getString(R.string.pdf_tracks),
                waypoints = context.getString(R.string.pdf_waypoints),
                printedOn = context.getString(R.string.pdf_printed_on),
                gridDescription = context.getString(R.string.pdf_grid_description),
                declination = context.getString(R.string.pdf_declination),
                gridConvergence = context.getString(R.string.pdf_grid_convergence),
                page = context.getString(R.string.pdf_page),
                overviewTitle = context.getString(R.string.pdf_overview_title),
                producedWith = context.getString(R.string.pdf_made_with),
                missingTiles = context.getString(R.string.pdf_missing_tiles),
                oversampled = context.getString(R.string.pdf_oversampled),
                east = context.getString(R.string.pdf_east),
                west = context.getString(R.string.pdf_west),
                north = context.getString(R.string.pdf_north),
                scaleLabel = context.getString(R.string.pdf_scale_label),
                coordinateSystem = context.getString(R.string.pdf_coordinate_system),
                south = context.getString(R.string.pdf_south),
                mapSource = context.getString(R.string.pdf_map_source),
                cmOnGround = context.getString(R.string.pdf_cm_on_ground),
                scaleNote = context.getString(R.string.pdf_scale_note),
                declinationDetail = context.getString(R.string.pdf_declination_detail),
                gridSquares = context.getString(R.string.pdf_grid_squares),
                gridLines = context.getString(R.string.pdf_grid_lines),
                trueNorth = context.getString(R.string.pdf_true_north),
                magneticNorth = context.getString(R.string.pdf_magnetic_north),
                gridNorth = context.getString(R.string.pdf_grid_north),
                trueNorthName = context.getString(R.string.pdf_true_north_name),
                annualChange = context.getString(R.string.pdf_annual_change),
                gridMagneticAngle = context.getString(R.string.pdf_grid_magnetic_angle),
                mils = context.getString(R.string.pdf_mils),
                notToScale = context.getString(R.string.pdf_not_to_scale),
                noGrid = context.getString(R.string.pdf_no_grid),
                principalDigits = context.getString(R.string.pdf_principal_digits),
                squareKilometers = context.getString(R.string.pdf_square_kilometers),
                zoneEdgeMeridian = context.getString(R.string.pdf_zone_edge_meridian),
                zoneEdgeParallel = context.getString(R.string.pdf_zone_edge_parallel),
                neighbourGrid = context.getString(R.string.pdf_neighbour_grid),
                and = context.getString(R.string.pdf_and),
                squares = context.getString(R.string.pdf_squares),
                squaresOfZones = context.getString(R.string.pdf_squares_of_zones),
                westSide = context.getString(R.string.pdf_west_side),
                eastSide = context.getString(R.string.pdf_east_side),
                southSide = context.getString(R.string.pdf_south_side),
                northSide = context.getString(R.string.pdf_north_side),
                referenceTitle = context.getString(R.string.pdf_reference_title),
                referenceStep1 = context.getString(R.string.pdf_reference_step1),
                referenceStep2 = context.getString(R.string.pdf_reference_step2),
                referenceStep3 = context.getString(R.string.pdf_reference_step3),
                referenceUtmStep1 = context.getString(R.string.pdf_reference_utm_step1),
                referenceMetersStep2 = context.getString(R.string.pdf_reference_meters_step2),
                referenceMetersStep3 = context.getString(R.string.pdf_reference_meters_step3),
                referenceExample = context.getString(R.string.pdf_reference_example),
                scaleHeading = context.getString(R.string.pdf_scale_heading),
                northHeading = context.getString(R.string.pdf_north_heading),
                gridHeading = context.getString(R.string.pdf_grid_heading),
            )

        companion object {
            /** The grid of the first print: the one of the coordinate format of the settings (P17, Q8). */
            fun gridFor(coordinates: CoordinateSystem): GridKind =
                when (coordinates) {
                    CoordinateSystem.DD -> GridKind.WGS84_DECIMAL
                    CoordinateSystem.DDM, CoordinateSystem.DMS -> GridKind.WGS84
                    CoordinateSystem.UTM -> GridKind.UTM
                    CoordinateSystem.MGRS -> GridKind.MGRS
                    CoordinateSystem.LAMBERT93 -> GridKind.LAMBERT93
                }

            private const val PLAN_DEBOUNCE_MS = 250L
            private const val MESSAGE_BUFFER = 8
            private const val YEAR_MILLIS = 365L * 24 * 3_600 * 1_000

            /** Side of a waypoint glyph image: 2.8 mm drawn from 64 px is about 580 dpi, sharp on any printer. */
            private const val GLYPH_PX = 64
            private const val ALPHA_SHIFT = 24
            private const val WHITE: Byte = -1
        }
    }
