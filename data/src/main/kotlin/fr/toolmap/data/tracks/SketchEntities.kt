package fr.toolmap.data.tracks

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** A sketch (« Croquis », P28): a named drawing session, shown or hidden as a whole. */
@Entity(tableName = "sketches")
data class SketchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val visible: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One stroke of a sketch, in drawing order: its smoothed SVG path in map meters and its style (ADR-110). The raw
 * points are never stored.
 */
@Entity(
    tableName = "sketch_strokes",
    foreignKeys =
        [
            ForeignKey(
                SketchEntity::class,
                parentColumns = ["id"],
                childColumns = ["sketchId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("sketchId")],
)
data class SketchStrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sketchId: Long,
    val ord: Int,
    val colorArgb: Int,
    val widthMeters: Double,
    val smoothingMeters: Double,
    val path: String,
)

data class SketchWithStrokes(
    @Embedded val sketch: SketchEntity,
    @Relation(parentColumn = "id", entityColumn = "sketchId")
    val strokes: List<SketchStrokeEntity>,
)
