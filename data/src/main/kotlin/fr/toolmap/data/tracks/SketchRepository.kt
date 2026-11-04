package fr.toolmap.data.tracks

import fr.toolmap.core.sketch.Sketch
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchShape
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.SketchStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A saved sketch as the screens use it: its strokes decoded, in drawing order. */
data class SavedSketch(
    val id: Long,
    val visible: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val sketch: Sketch,
)

/** The sketches of P28, kept in the library database (`tracks.db`), hence in the backup with it. */
class SketchRepository(
    private val db: TrackDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.sketchDao()

    /** Every sketch, most recently changed first. */
    val sketches: Flow<List<SavedSketch>> = dao.observeSketches().map { list -> list.map(::decode) }

    suspend fun load(id: Long): SavedSketch? = dao.sketch(id)?.let(::decode)

    /** Saves a new sketch, or the strokes of an existing one; returns its id. */
    suspend fun save(
        id: Long?,
        name: String,
        strokes: List<SketchStroke>,
    ): Long {
        val now = clock()
        val sketchId = id ?: dao.insertSketch(SketchEntity(name = name, createdAt = now, updatedAt = now))
        dao.replaceStrokes(sketchId, strokes.mapIndexed { i, s -> encode(sketchId, i, s) }, now)
        return sketchId
    }

    suspend fun rename(
        id: Long,
        name: String,
    ) = dao.rename(id, name.trim())

    suspend fun setVisible(
        id: Long,
        visible: Boolean,
    ) = dao.setVisible(id, visible)

    suspend fun delete(id: Long) = dao.delete(id)

    private fun encode(
        sketchId: Long,
        ord: Int,
        s: SketchStroke,
    ) = SketchStrokeEntity(
        sketchId = sketchId,
        ord = ord,
        colorArgb = s.style.color,
        widthMeters = s.style.widthMeters,
        smoothingMeters = s.style.smoothingMeters,
        path = s.path.toSvg(),
        shape = s.shape?.let { if (it.closed) ZONE else LINE },
        fill = s.shape?.takeIf { it.closed }?.fill?.code,
    )

    private fun shapeOf(row: SketchStrokeEntity): SketchShape? =
        when (row.shape) {
            LINE -> SketchShape(closed = false)
            ZONE -> SketchShape(closed = true, fill = SketchFill.of(row.fill))
            else -> null
        }

    private fun decode(row: SketchWithStrokes): SavedSketch =
        SavedSketch(
            id = row.sketch.id,
            visible = row.sketch.visible,
            createdAt = row.sketch.createdAt,
            updatedAt = row.sketch.updatedAt,
            sketch =
                Sketch(
                    row.sketch.name,
                    row.strokes.sortedBy { it.ord }.mapNotNull { s ->
                        // A path that cannot be read is left out rather than losing the whole sketch.
                        runCatching { SketchPath.parse(s.path) }.getOrNull()?.let { path ->
                            SketchStroke(SketchStyle(s.colorArgb, s.widthMeters, s.smoothingMeters), path, shapeOf(s))
                        }
                    },
                ),
        )

    private companion object {
        const val LINE = "line"
        const val ZONE = "zone"
    }
}
