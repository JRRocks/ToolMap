package fr.toolmap.di

import android.content.Context
import android.database.sqlite.SQLiteException
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.backup.OfflineMapLabels
import fr.toolmap.backup.RoomDatabaseFreezer
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.problem.catchReported
import fr.toolmap.data.backup.BackupHooks
import fr.toolmap.data.backup.BackupLocations
import fr.toolmap.data.backup.BackupService
import fr.toolmap.data.storage.StorageManager
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
                            // Best effort: a file that is busy or failing is copied without its log folded in.
                            catchExpected("backup.tiles.checkpoint", SQLiteException::class) {
                                MbTilesFile.open(file).use { it.checkpoint() }
                            }
                        }
                    },
                ),
            workDir = context.cacheDir,
        )

    @Provides
    fun offlineMapLabels(storage: StorageManager): OfflineMapLabels = OfflineMapLabels(storage::labelsByFile)

    // The app's own package is always installed: a failure is a bug, the backup then has no version.
    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String =
        catchReported("backup.app_version") {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
}
