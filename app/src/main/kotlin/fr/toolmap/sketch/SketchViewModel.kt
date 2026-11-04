package fr.toolmap.sketch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.sketch.RawStroke
import fr.toolmap.core.sketch.SketchEraser
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchPalette
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchScale
import fr.toolmap.core.sketch.SketchShape
import fr.toolmap.core.sketch.SketchSmoother
import fr.toolmap.core.sketch.SketchSmoothing
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.SketchStyle
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

/**
 * What a finger does on the map while a sketch is open (variant D of P28.0): move the map, draw, place the points of a
 * shape (P28.4: the map moves as with the hand, a tap places a point) or erase.
 */
enum class SketchMode { HAND, PEN, SHAPE, ERASER }

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
    /** A sketch for the sharing group (P27): « Envoyer » sends it, it is never stored here. */
    val forGroup: Boolean = false,
    /** « Ligne | Zone » of the shape mode (P28.4), kept for the next shape. */
    val shapeClosed: Boolean = true,
    /** The pattern of the next zone, kept as the colour is. */
    val fill: SketchFill = SketchFill.HATCH,
    /** The points of the shape being placed, in map coordinates. */
    val draft: List<XY> = emptyList(),
    /** The width of the shape being placed, fixed at its first point as a pen stroke's is. */
    val draftWidthMeters: Double = 0.0,
) {
    val color: Int get() = SketchPalette.COLORS[colorIndex]

    /** A line needs two points, a zone three, before « Terminer ». */
    val draftComplete: Boolean get() = draft.size >= if (shapeClosed) MIN_ZONE_POINTS else MIN_LINE_POINTS

    companion object {
        const val DEFAULT_WIDTH_PX = 6
        const val DEFAULT_ERASER_PX = 32
        const val MIN_ERASER_PX = 8
        const val MAX_ERASER_PX = 64
        const val MIN_LINE_POINTS = 2
        const val MIN_ZONE_POINTS = 3
    }
}

/** What undo brings back: the strokes and the points of the shape being placed. */
private data class SketchSnapshot(
    val strokes: List<EditStroke>,
    val draft: List<XY>,
)

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

        private val undo = ArrayDeque<SketchSnapshot>()
        private val redo = ArrayDeque<SketchSnapshot>()

        private fun snapshot(ui: SketchUi = mutableUi.value) = SketchSnapshot(ui.strokes, ui.draft)

        /** A new sketch named [name] (« Croquis du 3 nov. »), opened with the hand: the map is framed first. */
        fun startNew(name: String) = begin(null, name, emptyList())

        /** « Nouveau croquis » of the group (P27): the same pen, sent to the group instead of saved. */
        fun startForGroup(name: String) = begin(null, name, emptyList(), forGroup = true)

        /** The strokes as they will be sent: smoothed, as « Enregistrer » keeps them. */
        fun groupStrokes(): List<SketchStroke> = mutableUi.value.strokes.map { it.raw.smoothed() }

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
            forGroup: Boolean = false,
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
                    draft = emptyList(),
                    canUndo = false,
                    canRedo = false,
                    dirty = false,
                    forGroup = forGroup,
                )
        }

        /** Leaves the sketch without saving (after « Abandonner les modifications ? » when it changed). */
        fun close() {
            undo.clear()
            redo.clear()
            mutableUi.value =
                mutableUi.value.copy(
                    active = false,
                    editingId = null,
                    strokes = emptyList(),
                    draft = emptyList(),
                    dirty = false,
                    forGroup = false,
                )
        }

        /** Leaving the shape mode keeps a shape that has its points, and drops one that has too few. */
        fun setMode(mode: SketchMode) {
            if (mode != SketchMode.SHAPE) {
                if (mutableUi.value.draftComplete) {
                    finishShape()
                } else {
                    mutableUi.value = mutableUi.value.copy(draft = emptyList())
                }
            }
            mutableUi.value = mutableUi.value.copy(mode = mode)
        }

        /** « Ligne | Zone »: also changes the shape being placed. */
        fun setShapeClosed(closed: Boolean) {
            mutableUi.value = mutableUi.value.copy(shapeClosed = closed)
        }

        fun setFill(fill: SketchFill) {
            mutableUi.value = mutableUi.value.copy(fill = fill)
        }

        /**
         * A point of the shape at [point] (« Ajouter ici », or a tap on the map), placed at [cameraZoom]. On a zone of
         * three points or more, a point within [reach] meters of the first one closes it instead.
         */
        fun addShapePoint(
            point: XY,
            cameraZoom: Double,
            reach: Double = 0.0,
        ) {
            val ui = mutableUi.value
            if (ui.shapeClosed && ui.draft.size >= SketchUi.MIN_ZONE_POINTS && point.distanceTo(ui.draft[0]) <= reach) {
                finishShape()
                return
            }
            val width =
                if (ui.draft.isEmpty()) {
                    SketchScale.style(ui.color, ui.widthPx, ui.smoothing, cameraZoom).widthMeters
                } else {
                    ui.draftWidthMeters
                }
            change(ui.strokes, ui.draft + point)
            mutableUi.value = mutableUi.value.copy(draftWidthMeters = width)
        }

        /** « Terminer »: the shape joins the strokes, in the colour and the fill chosen now. */
        fun finishShape() {
            val ui = mutableUi.value
            if (!ui.draftComplete) return
            val style = SketchStyle(ui.color, ui.draftWidthMeters, ui.draftWidthMeters)
            val shape = SketchShape(ui.shapeClosed, if (ui.shapeClosed) ui.fill else SketchFill.NONE)
            val raw = RawStroke(style, ui.draft, shape)
            change(ui.strokes + EditStroke(raw, SketchPath.through(ui.draft, ui.shapeClosed)), emptyList())
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
            change(ui.strokes + EditStroke(raw, SketchSmoother.fit(points, style.smoothingMeters)), ui.draft)
        }

        /** Starts an eraser gesture: everything it removes until the finger lifts is one step to undo. */
        fun beginErase() {
            undo.addLast(snapshot())
        }

        /** Ends an eraser gesture: one that removed nothing leaves no step to undo. */
        fun endErase() {
            val ui = mutableUi.value
            if (undo.lastOrNull()?.strokes === ui.strokes) {
                undo.removeLast()
                mutableUi.value = ui.copy(canUndo = undo.isNotEmpty())
            }
        }

        /**
         * Removes what lies under the eraser disc at [center]; untouched strokes keep their smoothed look. A shape
         * touched on its outline goes as a whole: its sides are not cut.
         */
        fun eraseAt(
            center: XY,
            radius: Double,
        ) {
            val ui = mutableUi.value
            var changed = false
            val next =
                ui.strokes.flatMap { s ->
                    if (s.raw.shape != null) {
                        val outline = s.smooth.sample(radius / 4)
                        val touched = SketchEraser.cut(outline, center, radius) != listOf(outline)
                        if (touched) changed = true
                        return@flatMap if (touched) emptyList() else listOf(s)
                    }
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
            redo.addLast(snapshot())
            mutableUi.value =
                mutableUi.value.copy(
                    strokes = previous.strokes,
                    draft = previous.draft,
                    canUndo = undo.isNotEmpty(),
                    canRedo = true,
                    dirty = true,
                )
        }

        fun redo() {
            val next = redo.removeLastOrNull() ?: return
            undo.addLast(snapshot())
            mutableUi.value =
                mutableUi.value.copy(
                    strokes = next.strokes,
                    draft = next.draft,
                    canUndo = true,
                    canRedo = redo.isNotEmpty(),
                    dirty = true,
                )
        }

        private fun change(
            strokes: List<EditStroke>,
            draft: List<XY>,
        ) {
            val ui = mutableUi.value
            undo.addLast(snapshot(ui))
            redo.clear()
            mutableUi.value =
                ui.copy(strokes = strokes, draft = draft, canUndo = true, canRedo = false, dirty = true)
        }

        /** Before « Enregistrer » or « Envoyer »: a shape that has its points is kept, one with too few is dropped. */
        fun commitShape() {
            if (mutableUi.value.draftComplete) finishShape()
            mutableUi.value = mutableUi.value.copy(draft = emptyList())
        }

        /**
         * « Enregistrer »: each stroke is smoothed and kept as its SVG path, the raw points are dropped (ADR-110). A
         * sketch left empty is deleted when it was saved before, and not created otherwise. [onDone] gets its id.
         */
        fun save(onDone: (Long?) -> Unit = {}) {
            commitShape()
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
