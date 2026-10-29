package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** The update of the app from GitHub (P31): one controller for the process, which outlives the dialogs. */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    @Provides
    @Singleton
    fun updateController(
        @ApplicationContext context: Context,
        offline: OfflineMode,
    ): UpdateController = UpdateController(context, offline, CoroutineScope(SupervisorJob() + Dispatchers.Main))
}
