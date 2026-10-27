package fr.toolmap.core.track

/** What the editor shows: the line and the vertex the user picked, if any. */
data class EditorState(
    val points: List<TrackPoint> = emptyList(),
    val selected: Int? = null,
)

/**
 * The model behind drawing and editing a line on the map: add, move, insert, delete, freehand, with unlimited-feeling
 * undo and redo. Every operation returns whether it changed anything. Not thread-safe: used from the UI thread.
 *
 * History keeps whole snapshots; it is limited by the total number of points kept, not by the number of steps, so a
 * 50 000-point track still allows a few dozen undos without eating the memory.
 */
class PolylineEditor(
    initial: List<TrackPoint> = emptyList(),
    private val maxHistoryPoints: Int = DEFAULT_HISTORY_POINTS,
) {
    private val undoStack = ArrayDeque<EditorState>()
    private val redoStack = ArrayDeque<EditorState>()
    private var historyPoints = 0

    var state: EditorState = EditorState(initial)
        private set

    val points: List<TrackPoint> get() = state.points
    val selected: Int? get() = state.selected
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Adds a vertex: after the selected one when there is one, else at the end. The new vertex becomes selected. */
    fun add(
        lat: Double,
        lon: Double,
    ): Boolean {
        val at = (selected?.plus(1)) ?: points.size
        return change(EditorState(points.toMutableList().apply { add(at, TrackPoint(lat, lon)) }, at))
    }

    /** Puts the selected vertex somewhere else (its elevation and time are dropped: they belonged to the old place). */
    fun moveSelected(
        lat: Double,
        lon: Double,
    ): Boolean {
        val i = selected ?: return false
        return change(state.copy(points = points.toMutableList().apply { this[i] = TrackPoint(lat, lon) }))
    }

    fun deleteSelected(): Boolean {
        val i = selected ?: return false
        val next = points.toMutableList().apply { removeAt(i) }
        return change(EditorState(next, if (next.isEmpty()) null else minOf(i, next.lastIndex)))
    }

    /** Selection is not an edit: it is not undoable on its own. */
    fun select(index: Int?) {
        state = state.copy(selected = index?.takeIf { it in points.indices })
    }

    /** Inserts a vertex halfway along segment [index] (between vertex [index] and the next) and selects it. */
    fun insertMidpoint(index: Int): Boolean {
        if (index !in 0 until points.lastIndex) return false
        val mid = TrackOps.midpoint(points[index], points[index + 1])
        return change(EditorState(points.toMutableList().apply { add(index + 1, mid) }, index + 1))
    }

    /** Index of the vertex nearest to a position if it is within [maxMeters], else `null`. */
    fun nearest(
        lat: Double,
        lon: Double,
        maxMeters: Double,
    ): Int? {
        var best: Int? = null
        var bestDistance = maxMeters
        for (i in points.indices) {
            val d = Geodesy.distance(lat, lon, points[i].lat, points[i].lon)
            if (d <= bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** Appends a hand-drawn stroke, thinned to [toleranceMeters] (see [Freehand]). */
    fun addStroke(
        stroke: List<TrackPoint>,
        toleranceMeters: Double,
    ): Boolean {
        val thinned = Freehand.thin(stroke, toleranceMeters)
        if (thinned.isEmpty()) return false
        return change(EditorState(points + thinned, points.size + thinned.lastIndex))
    }

    fun reverse(): Boolean {
        if (points.size < 2) return false
        return change(EditorState(TrackOps.reverse(listOf(points)).single(), selected?.let { points.lastIndex - it }))
    }

    fun simplify(toleranceMeters: Double): Boolean {
        val next = TrackOps.simplify(points, toleranceMeters)
        if (next.size == points.size) return false
        return change(EditorState(next, null))
    }

    /** Drops everything before the selected vertex. */
    fun trimBeforeSelected(): Boolean {
        val i = selected ?: return false
        if (i == 0) return false
        return change(EditorState(TrackOps.trim(points, i, points.lastIndex), 0))
    }

    /** Drops everything after the selected vertex. */
    fun trimAfterSelected(): Boolean {
        val i = selected ?: return false
        if (i == points.lastIndex) return false
        return change(EditorState(TrackOps.trim(points, 0, i), i))
    }

    /**
     * Keeps the first half and returns the second (they share the selected vertex), for the caller to store as a new
     * track. `null` when nothing valid is selected.
     */
    fun splitAtSelected(): List<TrackPoint>? {
        val i = selected ?: return null
        if (i !in 1 until points.lastIndex) return null
        val (first, second) = TrackOps.split(points, i)
        change(EditorState(first, i))
        return second
    }

    fun clear(): Boolean = if (points.isEmpty()) false else change(EditorState())

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        historyPoints -= previous.points.size
        redoStack.addLast(state)
        state = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(state)
        historyPoints += state.points.size
        state = next
        return true
    }

    /** Replaces the whole line (loading a track into the editor); clears the history. */
    fun load(points: List<TrackPoint>) {
        undoStack.clear()
        redoStack.clear()
        historyPoints = 0
        state = EditorState(points)
    }

    private fun change(next: EditorState): Boolean {
        undoStack.addLast(state)
        historyPoints += state.points.size
        redoStack.clear()
        while (undoStack.size > 1 &&
            historyPoints > maxHistoryPoints
        ) {
            historyPoints -= undoStack.removeFirst().points.size
        }
        state = next
        return true
    }

    companion object {
        const val DEFAULT_HISTORY_POINTS = 400_000
    }
}

/** Turns the raw positions of a finger dragged over the map into a usable line. */
object Freehand {
    /**
     * Drops positions closer than half the tolerance to the last kept one (a finger resting on the glass), then
     * thins the rest with Douglas-Peucker. The first and last positions are always kept.
     */
    fun thin(
        stroke: List<TrackPoint>,
        toleranceMeters: Double,
    ): List<TrackPoint> {
        if (stroke.size < 2) return stroke
        val kept = ArrayList<TrackPoint>()
        kept += stroke.first()
        for (i in 1 until stroke.lastIndex) {
            if (Geodesy.distance(kept.last(), stroke[i]) >= toleranceMeters / 2) kept += stroke[i]
        }
        kept += stroke.last()
        return PathSimplifier.simplify(kept, toleranceMeters)
    }
}
