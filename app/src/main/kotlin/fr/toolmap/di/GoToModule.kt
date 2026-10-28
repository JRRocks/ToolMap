package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.routing.BRouterEngine
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.navigate.AndroidGoToPlatform
import fr.toolmap.navigate.GoToController
import fr.toolmap.navigate.GoToPlatform
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Wiring of « Aller vers un point » (P30.3, ADR-093): one navigation for the whole process. */
@Module
@InstallIn(SingletonComponent::class)
object GoToModule {
    @Provides
    @Singleton
    fun goToPlatform(
        @ApplicationContext context: Context,
    ): GoToPlatform = AndroidGoToPlatform(context)

    @Provides
    @Singleton
    fun goToController(
        location: LocationSource,
        brouter: BRouterEngine,
        settings: SettingsStore,
        platform: GoToPlatform,
    ): GoToController =
        GoToController(location, brouter, settings, platform, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
