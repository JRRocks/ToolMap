package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.alert.AlertController
import fr.toolmap.alert.AlertNotifier
import fr.toolmap.alert.AlertServiceControl
import fr.toolmap.alert.AndroidAlertNotifier
import fr.toolmap.alert.AndroidAlertServiceControl
import fr.toolmap.data.alert.AlertStateStore
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/** Wiring of the alerts (proximity, following a track): one controller for the whole process. */
@Module
@InstallIn(SingletonComponent::class)
object AlertModule {
    @Provides
    @Singleton
    fun alertStateStore(
        @ApplicationContext context: Context,
    ): AlertStateStore = AlertStateStore(File(context.filesDir, "alerts.json"))

    @Provides
    @Singleton
    fun alertNotifier(
        @ApplicationContext context: Context,
        settings: SettingsStore,
    ): AlertNotifier = AndroidAlertNotifier(context, settings)

    @Provides
    @Singleton
    fun alertServiceControl(
        @ApplicationContext context: Context,
    ): AlertServiceControl = AndroidAlertServiceControl(context)

    @Provides
    @Singleton
    fun alertController(
        repository: TrackRepository,
        settings: SettingsStore,
        store: AlertStateStore,
        location: LocationSource,
        notifier: AlertNotifier,
        service: AlertServiceControl,
    ): AlertController =
        AlertController(
            repository,
            settings,
            store,
            location,
            notifier,
            service,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
}
