package fr.toolmap.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.backup.BackupException
import fr.toolmap.core.backup.BackupFailure
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.data.backup.BackupService
import fr.toolmap.download.formatBytes
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

enum class BackupBusy { CREATING, CHECKING, UNPACKING }

/** What the backup screen shows. */
data class BackupUi(
    val busy: BackupBusy? = null,
    /** 0..1 while [busy] is known to take a while, `null` when the amount is not known. */
    val progress: Float? = null,
    val includeTiles: Boolean = false,
    /** What the offline maps weigh: the cost of ticking [includeTiles]. */
    val tilesBytes: Long = 0L,
    /** A backup the user picked, waiting for the go-ahead. */
    val preview: BackupManifest? = null,
    /** A restore already unpacked and checked: it is applied at the next start. */
    val pending: BackupManifest? = null,
    /** How the last restore went, until the user has seen it. */
    val report: RestoreReport? = null,
)

/**
 * Backup and restore (feature P9.6). Saving writes a zip where the user chose; restoring checks the zip, unpacks it
 * aside and waits for the next start to put it in place (see [fr.toolmap.data.backup.PendingRestore]).
 */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val service: BackupService,
    ) : ViewModel() {
        private val mutableUi = MutableStateFlow(BackupUi())
        val ui: StateFlow<BackupUi> = mutableUi.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        /** Reopens the archive the user picked: the same document is read twice (preview, then unpacking). */
        private var chosen: (() -> InputStream?)? = null

        init {
            refresh()
        }

        /** Reads what is on disk: the offline maps' weight, a restore waiting, the report of the last one. */
        fun refresh() {
            viewModelScope.launch {
                val state =
                    withContext(Dispatchers.IO) {
                        Triple(service.tilesBytes(), service.pending(), service.report())
                    }
                mutableUi.update { it.copy(tilesBytes = state.first, pending = state.second, report = state.third) }
            }
        }

        fun setIncludeTiles(on: Boolean) = mutableUi.update { it.copy(includeTiles = on) }

        /**
         * Writes a backup to the stream [open] gives. [discard] removes the document when it fails, so that a broken
         * file is not left looking like a backup.
         */
        fun create(
            open: () -> OutputStream?,
            discard: () -> Unit,
        ) {
            if (mutableUi.value.busy != null) return
            val includeTiles = mutableUi.value.includeTiles
            mutableUi.update { it.copy(busy = BackupBusy.CREATING, progress = 0f) }
            viewModelScope.launch {
                try {
                    val out = withContext(Dispatchers.IO) { open() }
                    if (out == null) {
                        say(R.string.backup_cannot_open)
                        return@launch
                    }
                    val manifest =
                        try {
                            service.create(out, includeTiles) { done, total -> progress(done, total) }
                        } catch (e: Throwable) {
                            withContext(Dispatchers.IO) { runCatching { discard() } }
                            throw e
                        }
                    say(R.string.backup_created, manifest.entries.size, formatBytes(manifest.totalBytes))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    sayFailure(e)
                } finally {
                    mutableUi.update { it.copy(busy = null, progress = null) }
                }
            }
        }

        /** Reads the manifest of the backup [open] gives and asks for confirmation. */
        fun choose(open: () -> InputStream?) {
            if (mutableUi.value.busy != null) return
            mutableUi.update { it.copy(busy = BackupBusy.CHECKING, progress = null) }
            viewModelScope.launch {
                try {
                    val manifest =
                        withContext(Dispatchers.IO) { open() }?.use { service.inspect(it) }
                    if (manifest == null) {
                        say(R.string.backup_cannot_open)
                        return@launch
                    }
                    chosen = open
                    mutableUi.update { it.copy(preview = manifest) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    sayFailure(e)
                } finally {
                    mutableUi.update { it.copy(busy = null) }
                }
            }
        }

        fun cancelPreview() {
            chosen = null
            mutableUi.update { it.copy(preview = null) }
        }

        /** The user accepted the preview: unpack and check, then wait for the restart. */
        fun confirm() {
            val open = chosen ?: return
            if (mutableUi.value.busy != null) return
            mutableUi.update { it.copy(preview = null, busy = BackupBusy.UNPACKING, progress = 0f) }
            viewModelScope.launch {
                try {
                    val stream = withContext(Dispatchers.IO) { open() }
                    if (stream == null) {
                        say(R.string.backup_cannot_open)
                        return@launch
                    }
                    val manifest = stream.use { service.stage(it) { done, total -> progress(done, total) } }
                    mutableUi.update { it.copy(pending = manifest) }
                    say(R.string.backup_staged)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    sayFailure(e)
                } finally {
                    chosen = null
                    mutableUi.update { it.copy(busy = null, progress = null) }
                }
            }
        }

        fun cancelPending() {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { service.discardPending() }
                mutableUi.update { it.copy(pending = null) }
            }
        }

        fun dismissReport() {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { service.clearReport() }
                mutableUi.update { it.copy(report = null) }
            }
        }

        private fun progress(
            done: Long,
            total: Long,
        ) {
            val fraction = if (total <= 0L) 1f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
            mutableUi.update { it.copy(progress = fraction) }
        }

        private fun say(
            resId: Int,
            vararg args: Any,
        ) {
            mutableMessages.tryEmit(UiMessage(resId, args.toList()))
        }

        private fun sayFailure(e: BackupException) {
            when (e.failure) {
                BackupFailure.NOT_A_BACKUP -> say(R.string.backup_err_not_a_backup)
                BackupFailure.UNSUPPORTED_VERSION -> say(R.string.backup_err_version)
                BackupFailure.CORRUPT -> say(R.string.backup_err_corrupt)
                BackupFailure.UNSAFE -> say(R.string.backup_err_unsafe)
                BackupFailure.NO_SPACE -> say(R.string.backup_err_space)
                BackupFailure.TILES_UNAVAILABLE -> say(R.string.backup_err_tiles_card)
                BackupFailure.IO -> say(R.string.backup_err_io, e.message.orEmpty())
            }
        }
    }
