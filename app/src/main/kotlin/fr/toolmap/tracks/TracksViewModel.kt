package fr.toolmap.tracks

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.settings.LibraryViewSettings
import fr.toolmap.core.track.ElevationProfile
import fr.toolmap.core.track.GpxWriter
import fr.toolmap.core.track.LibraryFilter
import fr.toolmap.core.track.LibrarySort
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackFormatException
import fr.toolmap.core.track.TrackImporter
import fr.toolmap.core.track.TrackPath
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStats
import fr.toolmap.core.track.TrackStatsCalculator
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.DisplayData
import fr.toolmap.data.tracks.ImportSummary
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackDisplay
import fr.toolmap.data.tracks.TrackEntity
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.data.tracks.TrackSelection
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** What the statistics screen shows for the selected track. */
class TrackDetail(
    val trackId: Long,
    val stats: TrackStats,
    val profile: ElevationProfile?,
    val path: TrackPath,
    /** Points without an elevation: the "complete from terrain data" button is offered when positive. */
    val missingElevation: Int,
)

/** A box on the map the map screen should fit once it is shown. */
class MapRequest(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/** State and actions of the tracks library, the statistics screen and the map layer of tracks. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TracksViewModel
    @Inject
    constructor(
        private val repository: TrackRepository,
        private val display: TrackDisplay,
        private val completer: ElevationCompleter,
        private val settings: SettingsStore,
    ) : ViewModel() {
        val library: StateFlow<TrackLibrary?> =
            repository.library.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** The sort and the filter of the explorer (F7), kept in the settings from one opening to the next. */
        val libraryView: StateFlow<LibraryViewSettings> =
            settings.settings
                .map { it.library }
                .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.library)

        fun setLibrarySort(sort: LibrarySort) = settings.update { it.copy(library = it.library.copy(sort = sort)) }

        fun setLibraryFilter(filter: LibraryFilter) =
            settings.update { it.copy(library = it.library.copy(filter = filter)) }

        private val openFolders = MutableStateFlow(emptySet<Long>())

        /**
         * Folders open in the explorer: kept back from a detail and when the explorer is closed and reopened, all
         * closed again at the next launch, since only this view model holds them (P14.11).
         */
        val expandedFolders: StateFlow<Set<Long>> = openFolders.asStateFlow()

        fun toggleFolder(id: Long) = openFolders.update { if (id in it) it - id else it + id }

        fun expandFolder(id: Long) = openFolders.update { it + id }

        /** First visible row of the explorer's list and its offset in pixels, saved when the explorer is left. */
        var libraryScroll: Pair<Int, Int> = 0 to 0

        /** GeoJSON of what the map shows; `null` until the library has been read once. */
        val mapData: StateFlow<DisplayData?> = display.data.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val selectedId = MutableStateFlow<Long?>(null)
        val selected: StateFlow<Long?> = selectedId.asStateFlow()

        /** Statistics and profile of the selected track; `null` while they are computed or nothing is selected. */
        val detail: StateFlow<TrackDetail?> =
            combine(selectedId, repository.library) { id, lib -> lib.tracks.firstOrNull { it.id == id } }
                .distinctUntilChanged { a, b -> sameGeometry(a, b) }
                .flatMapLatest { entity ->
                    flow {
                        emit(null)
                        if (entity != null) emit(loadDetail(entity.id))
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val cursorDistance = MutableStateFlow<Double?>(null)

        /** The point of the selected track under the profile cursor, shown on the map. */
        val cursor: StateFlow<TrackPoint?> =
            combine(detail, cursorDistance) { d, at -> if (d != null && at != null) d.path.interpolate(at) else null }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val mutableBusy = MutableStateFlow(false)

        /** True while a long operation (import, elevation completion) runs. */
        val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        private val mutableImported = MutableSharedFlow<ImportSummary>(extraBufferCapacity = 4)

        /** One value per finished import: lets the activity open the library. */
        val imported: SharedFlow<ImportSummary> = mutableImported.asSharedFlow()

        private val mutableMapRequest = MutableStateFlow<MapRequest?>(null)
        val mapRequest: StateFlow<MapRequest?> = mutableMapRequest.asStateFlow()

        private val mutableTapped = MutableStateFlow<ItemRef?>(null)

        /** The item the user last tapped on the map. */
        val tapped: StateFlow<ItemRef?> = mutableTapped.asStateFlow()

        fun select(trackId: Long?) {
            cursorDistance.value = null
            selectedId.value = trackId
        }

        fun setCursor(distanceMeters: Double?) {
            cursorDistance.value = distanceMeters
        }

        fun onMapTap(ref: ItemRef?) {
            mutableTapped.value = ref
        }

        fun fitMapTo(request: MapRequest?) {
            mutableMapRequest.value = request
        }

        fun import(
            bytes: ByteArray,
            name: String?,
        ) {
            viewModelScope.launch {
                mutableBusy.value = true
                try {
                    val summary =
                        withContext(Dispatchers.Default) {
                            repository.import(TrackImporter.read(bytes, name), name)
                        }
                    mutableMessages.tryEmit(
                        UiMessage(R.string.tracks_imported, listOf(summary.tracks, summary.routes, summary.waypoints)),
                    )
                    if (summary.issues.isNotEmpty()) {
                        mutableMessages.tryEmit(UiMessage(R.string.tracks_import_issues, listOf(summary.issues.size)))
                    }
                    mutableImported.tryEmit(summary)
                } catch (e: TrackFormatException) {
                    mutableMessages.tryEmit(UiMessage(R.string.tracks_import_failed, listOf(e.message.orEmpty())))
                } finally {
                    mutableBusy.value = false
                }
            }
        }

        fun reportReadError(reason: String) {
            mutableMessages.tryEmit(UiMessage(R.string.tracks_import_failed, listOf(reason)))
        }

        /** Writes the selection as GPX 1.1 to a document the user chose. */
        fun exportTo(
            resolver: ContentResolver,
            uri: Uri,
            selection: TrackSelection,
        ) {
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = GpxWriter.toBytes(repository.document(selection), CREATOR)
                        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: throw IOException("Cannot open $uri")
                    }
                    mutableMessages.tryEmit(UiMessage(R.string.tracks_exported))
                } catch (e: IOException) {
                    mutableMessages.tryEmit(UiMessage(R.string.tracks_export_failed, listOf(e.message.orEmpty())))
                }
            }
        }

        /** Writes the selection as GPX into [dir] for a "share" intent; returns the file, or `null` on failure. */
        suspend fun exportToCache(
            dir: File,
            fileName: String,
            selection: TrackSelection,
        ): File? =
            withContext(Dispatchers.IO) {
                try {
                    dir.mkdirs()
                    File(dir, fileName).also {
                        it.writeBytes(GpxWriter.toBytes(repository.document(selection), CREATOR))
                    }
                } catch (e: IOException) {
                    mutableMessages.tryEmit(UiMessage(R.string.tracks_export_failed, listOf(e.message.orEmpty())))
                    null
                }
            }

        fun setVisible(
            ref: ItemRef,
            visible: Boolean,
        ) = launch { repository.setVisible(ref, visible) }

        fun setAllVisible(visible: Boolean) = launch { repository.setAllVisible(visible) }

        fun setFolderVisible(
            id: Long,
            visible: Boolean,
        ) = launch { repository.setFolderVisible(id, visible) }

        fun rename(
            ref: ItemRef,
            name: String,
        ) = launch { repository.rename(ref, name) }

        fun renameFolder(
            id: Long,
            name: String,
        ) = launch { repository.renameFolder(id, name) }

        fun setStyle(
            ref: ItemRef,
            style: PathStyle,
        ) = launch { repository.setStyle(ref, style) }

        fun delete(ref: ItemRef) {
            if (ref.kind == ItemKind.TRACK && selectedId.value == ref.id) select(null)
            if (mutableTapped.value == ref) mutableTapped.value = null
            launch { repository.delete(ref) }
        }

        /**
         * « Convertir en trace » of a route's sheet (P19): its line is copied into a new track; the route stays. `false`
         * when the route is gone or has less than two points.
         */
        suspend fun convertRouteToTrack(id: Long): Boolean {
            val route = repository.loadRoute(id) ?: return false
            val points = route.points.map { TrackPoint(it.lat, it.lon, it.ele) }
            if (points.size < 2) return false
            repository.createTrack(route.name, listOf(points), route.style ?: PathStyle())
            return true
        }

        fun createFolder(
            parentId: Long?,
            name: String,
        ) = launch { repository.createFolder(parentId, name) }

        fun moveItem(
            ref: ItemRef,
            folderId: Long?,
        ) = launch {
            if (!repository.moveItem(ref, folderId)) mutableMessages.tryEmit(UiMessage(R.string.tracks_move_failed))
        }

        fun moveFolder(
            id: Long,
            parentId: Long?,
        ) = launch {
            if (!repository.moveFolder(id, parentId)) mutableMessages.tryEmit(UiMessage(R.string.tracks_move_failed))
        }

        fun deleteFolder(id: Long) {
            val lib = library.value
            if (lib != null &&
                selectedId.value in lib.tracks.filter { it.folderId in lib.descendants(id) }.map { it.id }
            ) {
                select(null)
            }
            launch { repository.deleteFolder(id) }
        }

        /** A waypoint to edit, from the library. */
        suspend fun waypoint(id: Long): Waypoint? = repository.loadWaypoint(id)

        /** The ground elevation at a point from the terrain tiles, `null` when the tile cannot be had (offline). */
        suspend fun terrainElevation(
            lat: Double,
            lon: Double,
        ): Double? = completer.fill(listOf(listOf(TrackPoint(lat, lon)))).segments[0][0].ele

        /** Creates a waypoint ([id] `null`) or rewrites an existing one. */
        fun saveWaypoint(
            id: Long?,
            waypoint: Waypoint,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                if (id == null) repository.createWaypoint(waypoint) else repository.updateWaypoint(id, waypoint)
                mutableMessages.tryEmit(UiMessage(R.string.waypoint_saved))
            }
        }

        /** The track walked the other way. */
        fun reverse(trackId: Long) {
            viewModelScope.launch(Dispatchers.Default) {
                repository.reverseTrack(trackId)
                mutableMessages.tryEmit(UiMessage(R.string.tracks_reversed))
            }
        }

        /** Thins the track to [toleranceMeters]. */
        fun simplify(
            trackId: Long,
            toleranceMeters: Double,
        ) {
            viewModelScope.launch(Dispatchers.Default) {
                val counts = repository.simplifyTrack(trackId, toleranceMeters)
                mutableMessages.tryEmit(
                    when {
                        counts == null -> UiMessage(R.string.draw_nothing_to_edit)
                        counts.second >= counts.first -> UiMessage(R.string.draw_simplify_nothing)
                        else -> UiMessage(R.string.draw_simplified, listOf(counts.first, counts.second))
                    },
                )
            }
        }

        /** A new track made of the given ones, in order; the sources stay. */
        fun merge(
            trackIds: List<Long>,
            name: String,
        ) {
            viewModelScope.launch(Dispatchers.Default) {
                val merged = repository.mergeTracks(trackIds, name) != null
                mutableMessages.tryEmit(
                    UiMessage(if (merged) R.string.tracks_merged else R.string.tracks_merge_failed),
                )
            }
        }

        /** The description shown in the detail screen and written to GPX. */
        fun describe(
            ref: ItemRef,
            description: String,
        ) = launch { repository.describe(ref, description) }

        /** Gives the points of the track that have no elevation one from the terrain tiles. */
        fun completeElevation(trackId: Long) {
            viewModelScope.launch {
                mutableBusy.value = true
                try {
                    val result = withContext(Dispatchers.Default) { repository.completeElevation(trackId, completer) }
                    mutableMessages.tryEmit(
                        when {
                            result == null -> {
                                UiMessage(R.string.tracks_elevation_failed)
                            }

                            result.filled == 0 -> {
                                UiMessage(R.string.tracks_elevation_none)
                            }

                            result.missingTiles > 0 -> {
                                UiMessage(
                                    R.string.tracks_elevation_partial,
                                    listOf(result.filled),
                                )
                            }

                            else -> {
                                UiMessage(R.string.tracks_elevation_done, listOf(result.filled))
                            }
                        },
                    )
                } finally {
                    mutableBusy.value = false
                }
            }
        }

        /** The track as the download planner wants it: its points, thinned to keep the planning fast. */
        suspend fun corridorPath(trackId: Long): List<TrackPoint> =
            withContext(Dispatchers.Default) {
                val track = repository.loadTrack(trackId) ?: return@withContext emptyList()
                track.segments.flatMap { fr.toolmap.core.track.PathSimplifier.simplify(it, CORRIDOR_TOLERANCE_METERS) }
            }

        private fun launch(block: suspend () -> Unit) {
            viewModelScope.launch(Dispatchers.IO) { block() }
        }

        private suspend fun loadDetail(trackId: Long): TrackDetail? =
            withContext(Dispatchers.Default) {
                val track = repository.loadTrack(trackId) ?: return@withContext null
                val path = TrackPath(track.segments)
                TrackDetail(
                    trackId = trackId,
                    stats = TrackStatsCalculator.compute(track.segments),
                    profile = ElevationProfile.build(path),
                    path = path,
                    missingElevation = path.points.count { it.ele == null },
                )
            }

        private fun sameGeometry(
            a: TrackEntity?,
            b: TrackEntity?,
        ) = a?.id == b?.id && a?.pointCount == b?.pointCount && a?.distanceMeters == b?.distanceMeters &&
            a?.ascentMeters == b?.ascentMeters

        private companion object {
            const val CREATOR = "ToolMap"
            const val CORRIDOR_TOLERANCE_METERS = 25.0
        }
    }
