package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.SourceElevationTiles
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackDisplay
import fr.toolmap.data.tracks.TrackRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Wiring of the tracks library (Room), its map display and the elevation completion. */
@Module
@InstallIn(SingletonComponent::class)
object TrackModule {
    @Provides
    @Singleton
    fun trackDatabase(
        @ApplicationContext context: Context,
    ): TrackDatabase = TrackDatabase.open(context)

    @Provides
    @Singleton
    fun trackRepository(db: TrackDatabase): TrackRepository = TrackRepository(db)

    @Provides
    @Singleton
    fun trackDisplay(repository: TrackRepository): TrackDisplay = TrackDisplay(repository)

    // Elevation tiles go through the tile client: stored tiles first, offline mode respected (ADR-003).
    @Provides
    @Singleton
    fun elevationCompleter(client: OkHttpClient): ElevationCompleter = ElevationCompleter(SourceElevationTiles(client))

    // Routing through the BRouter app (P19, ADR-064): bound on the first computed leg.
    @Provides
    @Singleton
    fun brouterEngine(
        @ApplicationContext context: Context,
    ): BRouterEngine = BRouterEngine(context)
}
