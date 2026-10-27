package fr.toolmap.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.measure.Measure
import fr.toolmap.core.measure.MeasureGeoJson
import fr.toolmap.core.measure.MeasureMode
import fr.toolmap.core.measure.MeasureResult
import fr.toolmap.core.track.PolylineEditor
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A surface needs three points: from then on it can be downloaded as a zone (F10). */
internal const val MIN_AREA_POINTS = 3

/** What the screen shows of the measure tool. */
data class MeasureUi(
    val active: Boolean = false,
    val mode: MeasureMode = MeasureMode.PATH,
    val result: MeasureResult = Measure.measure(emptyList()),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** The GeoJSON the map draws for the tool. */
class MeasureOverlay(
    val lineGeoJson: String,
    val fillGeoJson: String,
    val verticesGeoJson: String,
)

/** Distance, area and bearing between points placed on the map (P9.2): the maths are [Measure]'s. */
@HiltViewModel
class MeasureViewModel
    @Inject
    constructor(
        private val repository: TrackRepository,
    ) : ViewModel() {
        private val editor = PolylineEditor()

        private val mutableUi = MutableStateFlow(MeasureUi())
        val ui: StateFlow<MeasureUi> = mutableUi.asStateFlow()

        private val mutableOverlay = MutableStateFlow<MeasureOverlay?>(null)

        /** Line, fill and vertices to draw; `null` when the tool is closed. */
        val overlay: StateFlow<MeasureOverlay?> = mutableOverlay.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        fun open() {
            editor.clear()
            mutableUi.value = MeasureUi(active = true)
            publish()
        }

        fun close() {
            editor.clear()
            mutableUi.value = MeasureUi()
            mutableOverlay.value = null
        }

        fun addAt(
            lat: Double,
            lon: Double,
        ) {
            if (!mutableUi.value.active) return
            if (editor.add(lat, lon)) publish()
        }

        fun undo() {
            if (editor.undo()) publish()
        }

        fun redo() {
            if (editor.redo()) publish()
        }

        fun clear() {
            if (editor.clear()) publish()
        }

        fun setMode(mode: MeasureMode) {
            mutableUi.value = mutableUi.value.copy(mode = mode)
            publish()
        }

        /** The points as they are now, for the map to frame. */
        fun points(): List<TrackPoint> = editor.points

        /** The surface measured in « Surface » mode, as a zone to download (F10); `null` before three points. */
        fun areaZone(): ZoneShape.Polygon? {
            val points = editor.points
            if (mutableUi.value.mode != MeasureMode.AREA || points.size < MIN_AREA_POINTS) return null
            return ZoneShape.Polygon(points.map { ZoneShape.Point(it.lon, it.lat) })
        }

        /** Keeps the line as a route (a plan) or as a track (a recorded way), then closes the tool. */
        fun save(
            kind: ItemKind,
            name: String?,
        ) {
            val points = editor.points
            if (points.size < 2 || kind == ItemKind.WAYPOINT) {
                mutableMessages.tryEmit(UiMessage(R.string.draw_too_short))
                return
            }
            viewModelScope.launch {
                if (kind == ItemKind.ROUTE) {
                    repository.createRoute(name, points.map { Waypoint(it.lat, it.lon, it.ele) })
                } else {
                    repository.createTrack(name, listOf(points))
                }
                mutableMessages.tryEmit(UiMessage(R.string.draw_saved))
                close()
            }
        }

        private fun publish() {
            val points = editor.points
            val mode = mutableUi.value.mode
            mutableUi.value =
                mutableUi.value.copy(
                    result = Measure.measure(Measure.ofTrack(points)),
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                )
            mutableOverlay.value =
                MeasureOverlay(
                    MeasureGeoJson.line(points, mode),
                    MeasureGeoJson.fill(points, mode),
                    MeasureGeoJson.vertices(points),
                )
        }
    }
