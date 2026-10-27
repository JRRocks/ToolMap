package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.altitude.AltitudeController
import fr.toolmap.altitude.AndroidPressureSource
import fr.toolmap.altitude.AssetGeoidModel
import fr.toolmap.data.altitude.AltitudeService
import fr.toolmap.data.altitude.GeoidModel
import fr.toolmap.data.altitude.PressureSource
import fr.toolmap.data.altitude.TerrainElevation
import fr.toolmap.data.altitude.TerrariumElevation
import fr.toolmap.data.elevation.SourceElevationTiles
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Wiring of the barometric altimeter. The service does not depend on [LocationSource] (which depends on it). */
@Module
@InstallIn(SingletonComponent::class)
object AltitudeModule {
    @Provides
    @Singleton
    fun pressureSource(
        @ApplicationContext context: Context,
    ): PressureSource = AndroidPressureSource(context)

    @Provides
    @Singleton
    fun terrainElevation(client: OkHttpClient): TerrainElevation = TerrariumElevation(SourceElevationTiles(client))

    @Provides
    @Singleton
    fun geoidModel(
        @ApplicationContext context: Context,
    ): GeoidModel = AssetGeoidModel(context)

    @Provides
    @Singleton
    fun altitudeService(
        settings: SettingsStore,
        pressure: PressureSource,
        terrain: TerrainElevation,
        geoid: GeoidModel,
    ): AltitudeService =
        AltitudeService(
            settings.settings,
            pressure,
            terrain,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            geoid = geoid,
        )

    @Provides
    @Singleton
    fun altitudeController(
        service: AltitudeService,
        location: LocationSource,
    ): AltitudeController = AltitudeController(service, location)
}
