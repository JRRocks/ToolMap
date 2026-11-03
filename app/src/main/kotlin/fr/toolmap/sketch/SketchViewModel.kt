package fr.toolmap.sketch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.sketch.RawStroke
import fr.toolmap.core.sketch.SketchEraser
import fr.toolmap.core.sketch.SketchPalette
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchScale
import fr.toolmap.core.sketch.SketchSmoother
import fr.toolmap.core.sketch.SketchSmoothing
import fr.toolmap.core.sketch.XY
import fr.toolmap.data.tracks.SavedSketch
import fr.toolmap.data.tracks.SketchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a finger does on the map while a sketch is open (variant D of P28.0): move the map, draw or erase. */
enum class SketchMode { HAND, PEN, ERASER }

/** A stroke being edited and its smoothed look, computed once when it changes (ADR-110). */
data class EditStroke(
    val raw: RawStroke,
    val smooth: SketchPath,
)

/** The sketch tool (P28.3): the open sketch and the pen settings, which stay for the next sketch. */
data class SketchUi(
    val active: Boolean = false,
    /** The saved sketch being completed, or `null` for a new one. */
    val editingId: Long? = null,
    val name: String = "",
    val mode: SketchMode = SketchMode.HAND,
    val colorIndex: Int = 0,
    val widthPx: Int = DEFAULT_WIDTH_PX,
    val smoothing: SketchSmoothing = SketchSmoothing.MEDIUM,
    /** The size (diameter) of the eraser on the screen, kept for the next sketch. */
    val eraserPx: Int = DEFAULT_ERASER_PX,
    val strokes: List<EditStroke> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Something changed since the sketch was opened. */
    val dirty: Boolean = false,
) {
    val color: Int get() = SketchPalette.COLORS[colorIndex]

    companion object {
        const val DEFAULT_WIDTH_PX = 6
        const val DEFAULT_ERASER_PX = 32
        const val MIN_ERASER_PX = 8
        const val MAX_ERASER_PX = 64
    }
}

@HiltViewModel
class SketchViewModel
    @Inject
    constructor(
        private val repository: SketchRepository,
    ) : ViewModel() {
        val sketches: StateFlow<List<SavedSketch>> =
            repository.sketches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private val mutableUi = MutableStateFlow(SketchUi())
        val ui: StateFlow<SketchUi> = mutableUi.asStateFlow()

        private val undo = ArrayDeque<List<EditStroke>>()
        private val redo = ArrayDeque<List<EditStroke>>()

        /** A new sketch named [name] (« Croquis du 3 nov. »), opened with the hand: the map is framed first. */
        fun startNew(name: String) = begin(null, name, emptyList())

        /** Reopens a saved sketch to complete it: its curves become points again, so that the eraser works on them. */
        fun open(id: Long) {
            viewModelScope.launch {
                val saved = repository.load(id) ?: return@launch
                begin(id, saved.sketch.name, saved.sketch.strokes.map { s -> s.toRaw().let { EditStroke(it, s.path) } })
            }
        }

        private fun begin(
            id: Long?,
            name: String,
            strokes: List<EditStroke>,
        ) {
            undo.clear()
            redo.clear()
            mutableUi.value =
                mutableUi.value.copy(
                    active = true,
                    editingId = id,
                    name = name,
                    mode = SketchMode.HAND,
                    strokes = strokes,
                    canUndo = false,
                    canRedo = false,
                    dirty = false,
                )
        }

        /** Leaves the sketch without saving (after « Abandonner les modifications ? » when it changed). */
        fun close() {
            undo.clear()
            redo.clear()
            mutableUi.value =
                mutableUi.value.copy(active = false, editingId = null, strokes = emptyList(), dirty = false)
        }

        fun setMode(mode: SketchMode) {
            mutableUi.value = mutableUi.value.copy(mode = mode)
        }

        fun setColor(index: Int) {
            mutableUi.value = mutableUi.value.copy(colorIndex = index.coerceIn(SketchPalette.COLORS.indices))
        }

        fun setWidth(px: Int) {
            mutableUi.value =
                mutableUi.value.copy(widthPx = px.coerceIn(SketchScale.MIN_WIDTH_PX, SketchScale.MAX_WIDTH_PX))
        }

        fun setEraser(px: Int) {
            mutableUi.value =
                mutableUi.value.copy(eraserPx = px.coerceIn(SketchUi.MIN_ERASER_PX, SketchUi.MAX_ERASER_PX))
        }

        fun setSmoothing(smoothing: SketchSmoothing) {
            mutableUi.value = mutableUi.value.copy(smoothing = smoothing)
        }

        /** A finished pen stroke, in map coordinates, drawn at [cameraZoom]: its width and smoothing are fixed now. */
        fun addStroke(
            points: List<XY>,
            cameraZoom: Double,
        ) {
            if (points.isEmpty()) return
            val ui = mutableUi.value
            val style = SketchScale.style(ui.color, ui.widthPx, ui.smoothing, cameraZoom)
            val raw = RawStroke(style, points)
            change(ui.strokes + EditStroke(raw, SketchSmoother.fit(points, style.smoothingMeters)))
        }

        /** Starts an eraser gesture: everything it removes until the finger lifts is one step to undo. */
        fun beginErase() {
            undo.addLast(mutableUi.value.strokes)
        }

        /** Ends an eraser gesture: one that removed nothing leaves no step to undo. */
        fun endErase() {
            val ui = mutableUi.value
            if (undo.lastOrNull() === ui.strokes) {
                undo.removeLast()
                mutableUi.value = ui.copy(canUndo = undo.isNotEmpty())
            }
        }

        /** Removes what lies under the eraser disc at [center]; untouched strokes keep their smoothed look. */
        fun eraseAt(
            center: XY,
            radius: Double,
        ) {
            val ui = mutableUi.value
            var changed = false
            val next =
                ui.strokes.flatMap { s ->
                    val pieces = SketchEraser.cut(s.raw.points, center, radius)
                    if (pieces.size == 1 && pieces[0] == s.raw.points) {
                        listOf(s)
                    } else {
                        changed = true
                        pieces.map { p ->
                            EditStroke(RawStroke(s.raw.style, p), SketchSmoother.fit(p, s.raw.style.smoothingMeters))
                        }
                    }
                }
            if (!changed) return
            redo.clear()
            mutableUi.value = ui.copy(strokes = next, canUndo = true, canRedo = false, dirty = true)
        }

        fun undo() {
            val previous = undo.removeLastOrNull() ?: return
            redo.addLast(mutableUi.value.strokes)
            mutableUi.value =
                mutableUi.value.copy(strokes = previous, canUndo = undo.isNotEmpty(), canRedo = true, dirty = true)
        }

        fun redo() {
            val next = redo.removeLastOrNull() ?: return
            undo.addLast(mutableUi.value.strokes)
            mutableUi.value =
                mutableUi.value.copy(strokes = next, canUndo = true, canRedo = redo.isNotEmpty(), dirty = true)
        }

        private fun change(strokes: List<EditStroke>) {
            val ui = mutableUi.value
            undo.addLast(ui.strokes)
            redo.clear()
            mutableUi.value = ui.copy(strokes = strokes, canUndo = true, canRedo = false, dirty = true)
        }

        /**
         * « Enregistrer »: each stroke is smoothed and kept as its SVG path, the raw points are dropped (ADR-110). A
         * sketch left empty is deleted when it was saved before, and not created otherwise. [onDone] gets its id.
         */
        fun save(onDone: (Long?) -> Unit = {}) {
            val ui = mutableUi.value
            if (!ui.active) return
            viewModelScope.launch {
                val strokes = ui.strokes.map { it.raw.smoothed() }
                val id =
                    when {
                        strokes.isNotEmpty() -> {
                            repository.save(ui.editingId, ui.name, strokes)
                        }

                        ui.editingId != null -> {
                            repository.delete(ui.editingId)
                            null
                        }

                        else -> {
                            null
                        }
                    }
                close()
                onDone(id)
            }
        }

        fun rename(
            id: Long,
            name: String,
        ) {
            if (name.isBlank()) return
            viewModelScope.launch { repository.rename(id, name) }
        }

        fun setVisible(
            id: Long,
            visible: Boolean,
        ) {
            viewModelScope.launch { repository.setVisible(id, visible) }
        }

        fun delete(id: Long) {
            viewModelScope.launch { repository.delete(id) }
        }
    }
