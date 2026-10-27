package fr.toolmap.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.BuildConfig
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.state.WorkStateStore
import fr.toolmap.data.text.GlyphInterceptor
import fr.toolmap.data.tiles.BitmapElevationMasker
import fr.toolmap.data.tiles.CoverageInterceptor
import fr.toolmap.data.tiles.ForcedRefresh
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.TileInterceptor
import fr.toolmap.data.tiles.TileLocation
import fr.toolmap.data.tiles.TileStats
import fr.toolmap.data.tiles.TileStore
import fr.toolmap.perf.MapPerfLog
import fr.toolmap.storage.TileStorages
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Wiring of the tile pipeline: sources, local store, statistics and the OkHttp client given to MapLibre. */
@Module
@InstallIn(SingletonComponent::class)
object TileModule {
    @Provides
    @Singleton
    fun sourceRepository(
        @ApplicationContext context: Context,
    ): SourceRepository = SourceRepository(File(context.filesDir, "sources"))

    @Provides
    @Singleton
    fun workStateStore(
        @ApplicationContext context: Context,
    ): WorkStateStore = WorkStateStore(File(context.filesDir, "state/workstate.json"))

    // Every tile shown is stored here (automatic cache) and served from here, online or offline (ADR-003). The folder is
    // the one in use: the phone's, or the card's after a move (ADR-042). A move restarts the process, so it is read once.
    @Provides
    @Singleton
    fun mbTilesStore(
        @ApplicationContext context: Context,
        sources: SourceRepository,
    ): MbTilesStore {
        val directory = TileLocation(context.filesDir).active()
        return MbTilesStore(
            directory,
            sources,
            onError = { e -> Log.w("MbTilesStore", "Tile storage failed", e) },
            available = { TileStorages.isUsable(context, directory) },
        )
    }

    @Provides
    @Singleton
    fun tileStore(store: MbTilesStore): TileStore = store

    @Provides
    @Singleton
    fun tileStats(): TileStats = TileStats()

    @Provides
    @Singleton
    fun offlineMode(): OfflineMode = OfflineMode()

    @Provides
    @Singleton
    fun forcedRefresh(): ForcedRefresh = ForcedRefresh()

    @Provides
    @Singleton
    fun tileHttpClient(
        sources: SourceRepository,
        mbTiles: MbTilesStore,
        store: TileStore,
        stats: TileStats,
        offline: OfflineMode,
        forcedRefresh: ForcedRefresh,
        settings: SettingsStore,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            // Names on the map: glyphs drawn on the device from the system font (ADR-062).
            .addInterceptor(GlyphInterceptor())
            .addInterceptor(CoverageInterceptor(mbTiles))
            .addInterceptor(
                TileInterceptor(
                    sources,
                    store,
                    stats,
                    offline,
                    forcedRefresh = forcedRefresh,
                    // Read at every tile: a change in the settings applies to the next tiles shown (ADR-031).
                    scaling = { settings.settings.value.maps.scaling },
                    // Timing of the tiles drawn in an elevation band (P20): measure builds only (tools/perf).
                    masker = BitmapElevationMasker(log = perfLog()),
                    log = perfLog(),
                ),
            )
            // All tile calls share the fake host: room for a whole screen of tiles (40 to 60) plus the stored ones, which must
            // not wait behind the network (ADR-058). The per-source limit is the interceptor's (`<max-threads>`).
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MAX_PARALLEL_CALLS
                    maxRequestsPerHost = MAX_PARALLEL_CALLS
                },
            )
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private fun perfLog(): ((String) -> Unit)? =
        if (BuildConfig.PERF_LOG) {
            { line -> Log.i(MapPerfLog.TAG, line) }
        } else {
            null
        }

    private const val MAX_PARALLEL_CALLS = 64
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
}
