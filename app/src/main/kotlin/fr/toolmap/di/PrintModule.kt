package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.print.HttpPrintTileFetcher
import fr.toolmap.data.print.PrintTemplateStore
import fr.toolmap.data.print.PrintTileFetcher
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.TileStore
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/** Wiring of the PDF export: saved layouts and the tile reader (stored tiles first, then the tile client). */
@Module
@InstallIn(SingletonComponent::class)
object PrintModule {
    @Provides
    @Singleton
    fun printTemplateStore(
        @ApplicationContext context: Context,
    ): PrintTemplateStore = PrintTemplateStore(File(context.filesDir, "print"))

    @Provides
    @Singleton
    fun printTileFetcher(
        client: OkHttpClient,
        store: TileStore,
        offline: OfflineMode,
        sources: SourceRepository,
        settings: SettingsStore,
    ): PrintTileFetcher =
        HttpPrintTileFetcher(client, store, offline, sources, scaling = { settings.settings.value.maps.scaling })
}
