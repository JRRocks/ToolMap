package fr.toolmap.data.tracks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert
    suspend fun insertFolder(folder: FolderEntity): Long

    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<TrackChunkEntity>)

    @Insert
    suspend fun insertRoute(route: RouteEntity): Long

    @Insert
    suspend fun insertWaypoint(waypoint: WaypointEntity): Long

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM tracks ORDER BY createdAt DESC, id ASC")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM routes ORDER BY createdAt DESC, id ASC")
    fun observeRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM waypoints ORDER BY createdAt DESC, id ASC")
    fun observeWaypoints(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folder(id: Long): FolderEntity?

    @Query("SELECT * FROM folders")
    suspend fun allFolders(): List<FolderEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun track(id: Long): TrackEntity?

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun route(id: Long): RouteEntity?

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun waypoint(id: Long): WaypointEntity?

    @Query("SELECT * FROM track_chunks WHERE trackId = :trackId ORDER BY segment, chunk")
    suspend fun chunks(trackId: Long): List<TrackChunkEntity>

    @Query("SELECT * FROM track_chunks WHERE trackId = :trackId ORDER BY segment DESC, chunk DESC LIMIT 1")
    suspend fun lastChunk(trackId: Long): TrackChunkEntity?

    @Update
    suspend fun update(folder: FolderEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Update
    suspend fun update(route: RouteEntity)

    @Update
    suspend fun update(waypoint: WaypointEntity)

    @Query("UPDATE folders SET visible = :visible")
    suspend fun setAllFoldersVisible(visible: Boolean)

    @Query("UPDATE tracks SET visible = :visible")
    suspend fun setAllTracksVisible(visible: Boolean)

    @Query("UPDATE routes SET visible = :visible")
    suspend fun setAllRoutesVisible(visible: Boolean)

    @Query("UPDATE waypoints SET visible = :visible")
    suspend fun setAllWaypointsVisible(visible: Boolean)

    @Query("DELETE FROM track_chunks WHERE trackId = :trackId")
    suspend fun deleteChunks(trackId: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun deleteRoute(id: Long)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun deleteWaypoint(id: Long)
}
