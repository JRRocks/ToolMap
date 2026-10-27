package fr.toolmap.storage

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.ImportTarget
import fr.toolmap.data.storage.PackageException
import fr.toolmap.data.storage.PackageInfo
import fr.toolmap.data.storage.SourceUsage
import fr.toolmap.data.storage.StagedPackage
import fr.toolmap.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** A package waiting for the user to choose where it goes. */
data class PendingImport(
    val info: PackageInfo,
    /** The source the package was exported from, when it still exists here. */
    val knownSourceId: String?,
)

/** One-shot outcome shown in a snackbar. */
sealed interface StorageMessage {
    data class Exported(
        val bytes: Long,
    ) : StorageMessage

    data class Imported(
        val sourceName: String,
    ) : StorageMessage

    data object Purged : StorageMessage

    data class PurgedTiles(
        val count: Int,
    ) : StorageMessage

    data object MapDeleted : StorageMessage

    data class Refused(
        val reason: PackageException.Reason,
        val detail: String,
    ) : StorageMessage

    data class Failed(
        val detail: String,
    ) : StorageMessage
}

data class StorageUiState(
    val usages: List<SourceUsage> = emptyList(),
    val busy: Boolean = false,
    val pendingImport: PendingImport? = null,
    val message: StorageMessage? = null,
)

@HiltViewModel
class StorageViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val manager: StorageManager,
        private val repository: SourceRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(StorageUiState())
        val state: StateFlow<StorageUiState> = mutableState.asStateFlow()
        val sources: StateFlow<List<TileSource>> = repository.sources

        private var staged: StagedPackage? = null

        init {
            refresh()
        }

        fun refresh() {
            // launchBusy reloads the usage list after the (empty) work.
            launchBusy { null }
        }

        fun isLocalMap(sourceId: String): Boolean =
            repository.find(sourceId)?.let { it.isLocalOnly && repository.isImported(it.id) } == true

        fun purge(sourceId: String) =
            launchBusy {
                manager.purge(sourceId)
                StorageMessage.Purged
            }

        fun purgeOlderThan(
            sourceId: String,
            days: Int,
        ) = launchBusy {
            val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
            StorageMessage.PurgedTiles(manager.purgeOlderThan(sourceId, cutoff))
        }

        fun deleteLocalMap(sourceId: String) =
            launchBusy {
                manager.deleteLocalMap(sourceId)
                StorageMessage.MapDeleted
            }

        fun export(
            sourceId: String,
            uri: Uri,
        ) = launchBusy {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
            StorageMessage.Exported(out.use { manager.exportTo(sourceId, it) })
        }

        /** Copies the picked file and checks it; the dialog then asks where it goes. */
        fun stage(uri: Uri) =
            launchBusy {
                discardStaged()
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                val pack = input.use { manager.stage(it) }
                staged = pack
                mutableState.update {
                    it.copy(
                        pendingImport =
                            PendingImport(
                                pack.info,
                                pack.info.sourceId?.takeIf { id -> repository.find(id) != null },
                            ),
                    )
                }
                null
            }

        fun cancelImport() {
            viewModelScope.launch(Dispatchers.IO) { discardStaged() }
        }

        fun commitImport(target: ImportTarget) {
            val pack = staged ?: return
            launchBusy {
                val id = manager.commit(pack, target)
                staged = null
                mutableState.update { it.copy(pendingImport = null) }
                StorageMessage.Imported(repository.find(id)?.name ?: id)
            }
        }

        fun consumeMessage() = mutableState.update { it.copy(message = null) }

        override fun onCleared() {
            staged?.let(manager::discard)
            staged = null
        }

        private fun discardStaged() {
            staged?.let(manager::discard)
            staged = null
            mutableState.update { it.copy(pendingImport = null) }
        }

        /** Runs [work] off the main thread with the busy flag up, then reloads the usage list and posts its message. */
        private fun launchBusy(work: suspend () -> StorageMessage?) {
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true) }
                val message =
                    try {
                        withContext(Dispatchers.IO) { work() }
                    } catch (e: PackageException) {
                        StorageMessage.Refused(e.reason, e.message.orEmpty())
                    } catch (e: IOException) {
                        StorageMessage.Failed(e.message ?: e.javaClass.simpleName)
                    } catch (e: SecurityException) {
                        StorageMessage.Failed(e.message ?: e.javaClass.simpleName)
                    } catch (e: android.database.sqlite.SQLiteException) {
                        StorageMessage.Failed(e.message ?: e.javaClass.simpleName)
                    }
                val usages = withContext(Dispatchers.IO) { runCatching { manager.usage() }.getOrDefault(emptyList()) }
                mutableState.update { it.copy(busy = false, usages = usages, message = message ?: it.message) }
            }
        }

        private companion object {
            const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        }
    }
