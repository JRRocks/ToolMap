package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.BuildConfig
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.data.hierarchy.AndroidKeyVault
import fr.toolmap.data.hierarchy.OperationController
import fr.toolmap.data.hierarchy.OperationStorage
import java.io.File
import javax.inject.Singleton

/**
 * The « Opération » mode: its storage (P39.6, ADR-128: keys in the Keystore, operations sealed on the disk) and its
 * controller (P39.7, ADR-129), which goes through the same coordination server as « Entre amis », in rooms of its own.
 * Nothing asks for them yet, so Hilt never builds them: the screens, next step, will.
 */
@Module
@InstallIn(SingletonComponent::class)
object HierarchyModule {
    @Provides
    @Singleton
    fun operationStorage(
        @ApplicationContext context: Context,
    ): OperationStorage = OperationStorage(File(context.filesDir, OperationStorage.FOLDER), AndroidKeyVault())

    @Provides
    @Singleton
    fun operationController(
        storage: OperationStorage,
        relays: RelayTransportFactory,
    ): OperationController =
        OperationController(
            storage = storage,
            relays = relays,
            serverForNew = {
                RelayConfig.of(BuildConfig.FIREBASE_DB_URL, BuildConfig.FIREBASE_API_KEY).takeIf(RelayConfig::isUsable)
            },
        )
}
