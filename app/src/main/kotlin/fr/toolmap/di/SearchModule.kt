package fr.toolmap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.search.Geocoder
import fr.toolmap.data.search.GeocoderClient
import javax.inject.Singleton

/** Wiring of the place search: one geocoder client on its own plain HTTP client (not the tile one). */
@Module
@InstallIn(SingletonComponent::class)
object SearchModule {
    @Provides
    @Singleton
    fun geocoder(): Geocoder = GeocoderClient()
}
