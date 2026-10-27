package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.R
import fr.toolmap.altitude.AltitudeAwareLocationSource
import fr.toolmap.core.track.NameDates
import fr.toolmap.data.altitude.AltitudeService
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.RecordingSessionStore
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.record.AndroidLocationSource
import fr.toolmap.record.LocationSource
import fr.toolmap.record.RecordingController
import java.io.File
import javax.inject.Singleton

/** Wiring of the GPS recorder: one controller for the whole process, shared by the service and the screens. */
@Module
@InstallIn(SingletonComponent::class)
object RecordingModule {
    @Provides
    @Singleton
    fun locationSource(
        @ApplicationContext context: Context,
        altitude: AltitudeService,
    ): LocationSource = AltitudeAwareLocationSource(AndroidLocationSource(context), altitude)

    @Provides
    @Singleton
    fun recordingSessionStore(
        @ApplicationContext context: Context,
    ): RecordingSessionStore = RecordingSessionStore(File(context.filesDir, "recording/session.json"))

    @Provides
    @Singleton
    fun recordingController(
        @ApplicationContext context: Context,
        repository: TrackRepository,
        store: RecordingSessionStore,
        location: LocationSource,
        settings: SettingsStore,
    ): RecordingController =
        RecordingController(
            repository,
            store,
            location,
            options = { settings.settings.value.recorder },
            defaultName = { context.getString(R.string.record_default_name, NameDates.format(it)) },
        )
}
