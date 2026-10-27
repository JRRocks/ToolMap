package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.settings.SettingsStore
import java.io.File
import javax.inject.Singleton

/** Wiring of the user's settings (units, coordinates, theme, alerts…). */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun settingsStore(
        @ApplicationContext context: Context,
    ): SettingsStore = SettingsStore(File(context.filesDir, "settings.json"))
}
