package fr.toolmap.data.tracks

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities =
        [
            FolderEntity::class,
            TrackEntity::class,
            TrackChunkEntity::class,
            RouteEntity::class,
            WaypointEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun dao(): TrackDao

    companion object {
        const val FILE_NAME = "tracks.db"

        fun open(context: Context): TrackDatabase =
            Room.databaseBuilder(context.applicationContext, TrackDatabase::class.java, FILE_NAME).build()

        /** In-memory database for tests. */
        fun inMemory(context: Context): TrackDatabase =
            Room.inMemoryDatabaseBuilder(context, TrackDatabase::class.java).allowMainThreadQueries().build()
    }
}
