package fr.toolmap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.MbTilesStore
import javax.inject.Singleton

/** Wiring of the storage manager (usage, purges, `.mbtiles` export and import). */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun storageManager(
        store: MbTilesStore,
        repository: SourceRepository,
    ): StorageManager = StorageManager(store, repository)
}
