package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.backup.RoomDatabaseFreezer
import fr.toolmap.data.backup.BackupHooks
import fr.toolmap.data.backup.BackupLocations
import fr.toolmap.data.backup.BackupService
import fr.toolmap.data.tiles.MbTilesFile
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.storage.TileStorages
import javax.inject.Singleton

/** Wiring of the backup and restore of the app's data (P9.6). */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun backupLocations(
        @ApplicationContext context: Context,
    ): BackupLocations = TileStorages.backupLocations(context)

    @Provides
    @Singleton
    fun backupService(
        @ApplicationContext context: Context,
        locations: BackupLocations,
        database: TrackDatabase,
    ): BackupService =
        BackupService(
            locations = locations,
            appVersion = appVersion(context),
            hooks =
                BackupHooks(
                    freezeDatabase = RoomDatabaseFreezer(database),
                    checkpointTiles = {
                        // A second connection folds the log of the same file: enough to copy it alone.
                        locations.liveFiles(fr.toolmap.core.backup.BackupArea.TILES).forEach { (_, file) ->
                            runCatching { MbTilesFile.open(file).use { it.checkpoint() } }
                        }
                    },
                ),
            workDir = context.cacheDir,
        )

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
