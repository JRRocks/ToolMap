package fr.toolmap.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.download.ZoneJobStore
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.download.DownloadScheduler
import fr.toolmap.download.ZoneQueue
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/** Wiring of the zone downloads: job files, the downloader (same HTTP client as the map) and WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides
    @Singleton
    fun zoneJobStore(
        @ApplicationContext context: Context,
    ): ZoneJobStore = ZoneJobStore(File(context.filesDir, "downloads"))

    @Provides
    @Singleton
    fun zoneDownloader(
        client: OkHttpClient,
        sources: SourceRepository,
        store: MbTilesStore,
        offline: OfflineMode,
        settings: SettingsStore,
    ): ZoneDownloader =
        ZoneDownloader(client, sources, store, offline, scaling = {
            settings.settings.value.maps.scaling
        })

    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}

/** The scheduler answers to its interface, so a view model that only queues a download needs no WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadBindings {
    @Binds
    abstract fun zoneQueue(scheduler: DownloadScheduler): ZoneQueue
}
