package fr.toolmap.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.toolmap.data.hierarchy.AndroidKeyVault
import fr.toolmap.data.hierarchy.OperationStorage
import java.io.File
import javax.inject.Singleton

/**
 * The storage of the « Opération » mode (P39.6, ADR-128): keys in the Keystore, operations sealed on the disk. Nothing
 * asks for it yet, so Hilt never builds it: the transport, next step of the wiring, will.
 */
@Module
@InstallIn(SingletonComponent::class)
object HierarchyModule {
    @Provides
    @Singleton
    fun operationStorage(
        @ApplicationContext context: Context,
    ): OperationStorage = OperationStorage(File(context.filesDir, OperationStorage.FOLDER), AndroidKeyVault())
}
