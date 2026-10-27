package fr.toolmap.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import fr.toolmap.core.eventlog.AppExtension
import fr.toolmap.core.eventlog.EventStore
import fr.toolmap.core.p2p.DiagChannel
import fr.toolmap.eventlog.AppEventLog
import fr.toolmap.share.ShareController
import javax.inject.Singleton

/**
 * The event log (P21) and what the live-test tools (`:debugkit`, ADR-070) find in the app: the log itself, the channel
 * to the other members of the sharing group. No build takes `:debugkit` since P21 was closed (ADR-072): the set of
 * [AppExtension] stays empty.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventLogModule {
    @Multibinds
    abstract fun extensions(): Set<AppExtension>

    @Binds
    abstract fun diagChannel(controller: ShareController): DiagChannel

    companion object {
        @Provides
        @Singleton
        fun eventStore(
            @ApplicationContext context: Context,
        ): EventStore = AppEventLog.store(context)
    }
}
