package fr.toolmap.debugkit

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import fr.toolmap.core.eventlog.AppExtension

/** Adds the test tools to the extensions the app starts (the set is empty in a release, which has no `:debugkit`). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugKitModule {
    @Binds
    @IntoSet
    abstract fun debugKit(kit: DebugKit): AppExtension
}
