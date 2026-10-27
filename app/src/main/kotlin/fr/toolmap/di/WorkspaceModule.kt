package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.workspaces.WorkspaceStore
import java.io.File
import javax.inject.Singleton

/** Wiring of the saved workspaces (named layer stacks with their zoom ranges). */
@Module
@InstallIn(SingletonComponent::class)
object WorkspaceModule {
    @Provides
    @Singleton
    fun workspaceStore(
        @ApplicationContext context: Context,
    ): WorkspaceStore = WorkspaceStore(File(context.filesDir, "workspaces"))
}
