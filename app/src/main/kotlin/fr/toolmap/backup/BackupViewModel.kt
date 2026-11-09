package fr.toolmap.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.R
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupException
import fr.toolmap.core.backup.BackupFailure
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.problem.catchReported
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

/** The areas of data a backup holds by default: all of them, the offline maps apart (P33). */
val DATA_AREAS: Set<BackupArea> = BackupArea.entries.filterNot { it.optional }.toSet()

/** One offline map of the phone, as the backup lists it (P33). */
data class OfflineMapFile(
    /** Its file name in the tiles folder: what the backup is given. */
    val file: String,
    val name: String,
    /** The map whose picture stands for it. */
    val pictureId: String,
    val bytes: Long,
)

/** The offline maps' names, apart so that the view model is tested without the tile store. */
fun interface OfflineMapLabels {
    /** By file name in the tiles folder: the map's name and the map whose picture stands for it. */
    fun byFile(): Map<String, Pair<String, String>>
}

/** What the backup screen shows. */
data class BackupUi(
    val busy: BackupBusy? = null,
    /** 0..1 while [busy] is known to take a while, `null` when the amount is not known. */
    val progress: Float? = null,
    /** What each area of data weighs on the phone now. */
    val areaBytes: Map<BackupArea, Long> = emptyMap(),
    /** The areas of data ticked for the next backup: all of them at first. */
    val areas: Set<BackupArea> = DATA_AREAS,
    /** The offline maps of the phone, the heaviest first. */
    val offlineMaps: List<OfflineMapFile> = emptyList(),
    /** The files of the offline maps ticked: none at first (ADR-106). */
    val tiles: Set<String> = emptySet(),
    /** A backup the user picked, waiting for the go-ahead. */
    val preview: BackupManifest? = null,
    /** The areas of [preview] ticked for the restore: all it holds at first. */
    val restoreAreas: Set<BackupArea> = emptySet(),
    /** A restore already unpacked and checked: it is applied at the next start. */
    val pending: BackupManifest? = null,
    /** How the last restore went, until the user has seen it. */
    val report: RestoreReport? = null,
) {
    /** What the ticked areas of data weigh: the backup without its offline maps. */
    val dataBytes: Long get() = areas.sumOf { areaBytes[it] ?: 0L }

    /** What the ticked offline maps weigh. */
    val tilesBytes: Long get() = offlineMaps.filter { it.file in tiles }.sumOf { it.bytes }

    val canCreate: Boolean get() = areas.isNotEmpty() || tiles.isNotEmpty()
}

/** Ticks all of [these] when one of them is not, unticks them all otherwise: a line that stands for several. */
internal fun <T> Set<T>.flip(these: Set<T>): Set<T> = if (containsAll(these)) this - these else this + these

/**
 * Backup and restore (feature P9.6). Saving writes a zip of the areas and offline maps ticked (P33) where the user
 * chose; restoring checks the zip, unpacks the areas ticked aside and waits for the next start to put them in place
 * (see [fr.toolmap.data.backup.PendingRestore]).
 */
@HiltViewModel
class BackupViewModel
    @Inject
    constructor(
        private val service: BackupService,
        private val labels: OfflineMapLabels,
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

        private class OnDisk(
            val areaBytes: Map<BackupArea, Long>,
            val maps: List<OfflineMapFile>,
            val pending: BackupManifest?,
            val report: RestoreReport?,
        )

        /** Reads what is on disk: the weights, the offline maps, a restore waiting, the report of the last one. */
        fun refresh() {
            viewModelScope.launch {
                val disk =
                    withContext(Dispatchers.IO) {
                        // Names read from the catalogue in memory: a failure is a bug, the bare file names stand in.
                        val names = catchReported("backup.labels") { labels.byFile() }.getOrDefault(emptyMap())
                        val maps =
                            service
                                .tileFiles()
                                .map { (file, bytes) ->
                                    val bare = file.removeSuffix(TILES_EXTENSION)
                                    val (name, picture) = names[file] ?: (bare to bare)
                                    OfflineMapFile(file, name, picture, bytes)
                                }.sortedByDescending { it.bytes }
                        OnDisk(service.areaBytes(), maps, service.pending(), service.report())
                    }
                mutableUi.update { ui ->
                    ui.copy(
                        areaBytes = disk.areaBytes,
                        offlineMaps = disk.maps,
                        tiles = ui.tiles.filterTo(HashSet()) { file -> disk.maps.any { it.file == file } },
                        pending = disk.pending,
                        report = disk.report,
                    )
                }
            }
        }

        /** Ticks or unticks [areas] of data for the next backup, together when one line stands for several. */
        fun toggleAreas(areas: Set<BackupArea>) = mutableUi.update { it.copy(areas = it.areas.flip(areas)) }

        /** Ticks or unticks the offline map stored in [file] for the next backup. */
        fun toggleTiles(file: String) = mutableUi.update { it.copy(tiles = it.tiles.flip(setOf(file))) }

        /** Ticks or unticks [areas] of the backup chosen for the restore. */
        fun toggleRestore(areas: Set<BackupArea>) =
            mutableUi.update { it.copy(restoreAreas = it.restoreAreas.flip(areas)) }

        /**
         * Writes a backup to the stream [open] gives. [discard] removes the document when it fails, so that a broken
         * file is not left looking like a backup.
         */
        fun create(
            open: () -> OutputStream?,
            discard: () -> Unit,
        ) {
            val wanted = mutableUi.value
            if (wanted.busy != null || !wanted.canCreate) return
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
                            service.create(out, wanted.areas, wanted.tiles) { done, total -> progress(done, total) }
                        } catch (e: Throwable) {
                            // The screen's discard handles the failures it expects: anything else is a bug.
                            withContext(Dispatchers.IO) { catchReported("backup.create.discard") { discard() } }
                            throw e
                        }
                    say(R.string.backup_created, manifest.entries.size, formatBytes(manifest.totalBytes))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    // The document could not be written, or a file changed while it was saved: the user is told why.
                    Problems.expected("backup.create.write", e, "failure" to e.failure)
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
                    val held = BackupArea.entries.filterTo(HashSet()) { manifest.covers(it) }
                    mutableUi.update { it.copy(preview = manifest, restoreAreas = held) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    // The document the user picked is not a backup ToolMap reads: the user is told why.
                    Problems.expected("backup.restore.inspect", e, "failure" to e.failure)
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
            val areas = mutableUi.value.restoreAreas
            if (mutableUi.value.busy != null || areas.isEmpty()) return
            mutableUi.update { it.copy(preview = null, busy = BackupBusy.UNPACKING, progress = 0f) }
            viewModelScope.launch {
                try {
                    val stream = withContext(Dispatchers.IO) { open() }
                    if (stream == null) {
                        say(R.string.backup_cannot_open)
                        return@launch
                    }
                    val manifest = stream.use { service.stage(it, areas) { done, total -> progress(done, total) } }
                    mutableUi.update { it.copy(pending = manifest) }
                    say(R.string.backup_staged)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupException) {
                    // The backup could not be unpacked (damaged, unsafe, no space): the user is told why.
                    Problems.expected("backup.restore.stage", e, "failure" to e.failure)
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

        private companion object {
            const val TILES_EXTENSION = ".mbtiles"
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
