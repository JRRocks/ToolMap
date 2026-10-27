package fr.toolmap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.wizard.CapabilitiesFetcher
import fr.toolmap.data.wizard.TileProbe
import javax.inject.Singleton

/** Wiring of the add-a-map wizard: capabilities download and test tile, on their own plain HTTP client. */
@Module
@InstallIn(SingletonComponent::class)
object WizardModule {
    @Provides
    @Singleton
    fun capabilitiesFetcher(): CapabilitiesFetcher = CapabilitiesFetcher()

    @Provides
    @Singleton
    fun tileProbe(): TileProbe = TileProbe()
}
