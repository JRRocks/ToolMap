package fr.toolmap.data.tracks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SketchDao {
    @Transaction
    @Query("SELECT * FROM sketches ORDER BY updatedAt DESC, id DESC")
    fun observeSketches(): Flow<List<SketchWithStrokes>>

    @Transaction
    @Query("SELECT * FROM sketches WHERE id = :id")
    suspend fun sketch(id: Long): SketchWithStrokes?

    @Insert
    suspend fun insertSketch(sketch: SketchEntity): Long

    @Insert
    suspend fun insertStrokes(strokes: List<SketchStrokeEntity>)

    @Query("UPDATE sketches SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(
        id: Long,
        updatedAt: Long,
    )

    @Query("DELETE FROM sketch_strokes WHERE sketchId = :sketchId")
    suspend fun deleteStrokes(sketchId: Long)

    @Transaction
    suspend fun replaceStrokes(
        sketchId: Long,
        strokes: List<SketchStrokeEntity>,
        updatedAt: Long,
    ) {
        deleteStrokes(sketchId)
        insertStrokes(strokes)
        touch(sketchId, updatedAt)
    }

    @Query("UPDATE sketches SET name = :name WHERE id = :id")
    suspend fun rename(
        id: Long,
        name: String,
    )

    @Query("UPDATE sketches SET visible = :visible WHERE id = :id")
    suspend fun setVisible(
        id: Long,
        visible: Boolean,
    )

    @Query("DELETE FROM sketches WHERE id = :id")
    suspend fun delete(id: Long)
}
