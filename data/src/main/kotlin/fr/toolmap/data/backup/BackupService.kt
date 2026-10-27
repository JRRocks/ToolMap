package fr.toolmap.data.backup

import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupEntry
import fr.toolmap.core.backup.BackupException
import fr.toolmap.core.backup.BackupFailure
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.BackupManifestCodec
import fr.toolmap.core.backup.BackupPaths
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.core.backup.RestoreReportCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bytes handled so far out of the total, for a progress bar. */
fun interface BackupProgress {
    fun onBytes(
        done: Long,
        total: Long,
    )
}

/**
 * Backup and restore of the app's data (feature P9.6): one zip whose first entry is the manifest (every file with its
 * size and SHA-256), then the files. Writing reads each file twice (checksums, then content) so that the manifest can
 * come first and a restore can be previewed and checked as the archive is read, without copying it anywhere first.
 *
 * Restoring never touches live data: [stage] unpacks and verifies into a staging folder; [PendingRestore.apply] swaps
 * it in at the next start, before anything opens the database or reads a file.
 */
class BackupService(
    private val locations: BackupLocations,
    private val appVersion: String,
    private val hooks: BackupHooks,
    /** Scratch space for the database snapshot. */
    private val workDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Planned(
        val path: String,
        val file: File,
        val size: Long,
        val sha256: String,
        val crc: Long,
    )

    /** The size of the offline maps, to tell the user what including them costs. */
    fun tilesBytes(): Long = locations.liveFiles(BackupArea.TILES).sumOf { it.second.length() }

    suspend fun create(
        out: OutputStream,
        includeTiles: Boolean,
        progress: BackupProgress = BackupProgress { _, _ -> },
    ): BackupManifest =
        withContext(Dispatchers.IO) {
            // Without its card the folder is empty: an archive would then silently leave the offline maps out.
            if (includeTiles && !locations.tilesReady()) {
                throw BackupException(
                    BackupFailure.TILES_UNAVAILABLE,
                    "The offline maps are on a card that is not mounted",
                )
            }
            workDir.mkdirs()
            val snapshot = File(workDir, "tracks.db.snapshot")
            try {
                val areas = BackupArea.entries.filter { !it.optional || includeTiles }
                if (BackupArea.TILES in areas) hooks.checkpointTiles()
                val planned = ArrayList<Planned>()
                for (area in areas) {
                    currentCoroutineContext().ensureActive()
                    for ((path, file) in sourcesOf(area, snapshot)) planned += plan(path, file)
                }
                val manifest =
                    BackupManifest(
                        appVersion = appVersion,
                        createdAtMillis = clock(),
                        areas = areas.map { it.id },
                        entries = planned.map { BackupEntry(it.path, it.size, it.sha256) },
                    )
                write(out, manifest, planned, progress)
                manifest
            } catch (e: IOException) {
                throw BackupException(BackupFailure.IO, e.message ?: "I/O error", e)
            } finally {
                snapshot.delete()
            }
        }

    /** The manifest of the archive in [input], to show before anything is unpacked. */
    suspend fun inspect(input: InputStream): BackupManifest =
        withContext(Dispatchers.IO) {
            try {
                readManifest(ZipInputStream(input))
            } catch (e: IOException) {
                throw BackupException(BackupFailure.NOT_A_BACKUP, "Not a readable archive", e)
            }
        }

    /**
     * Unpacks and checks the archive in [input] into the staging folder; it is applied at the next start. Nothing live
     * is touched, and a bad archive leaves no staging behind.
     */
    suspend fun stage(
        input: InputStream,
        progress: BackupProgress = BackupProgress { _, _ -> },
    ): BackupManifest =
        withContext(Dispatchers.IO) {
            discardPending()
            var staged = false
            try {
                val zip = ZipInputStream(input)
                val manifest = readManifest(zip)
                if (locations.filesDir.usableSpace < manifest.totalBytes + SPACE_MARGIN) {
                    throw BackupException(BackupFailure.NO_SPACE, "Not enough free space")
                }
                unpack(zip, manifest, progress)
                atomicWrite(File(locations.stagingDir, BackupLocations.MARKER), BackupManifestCodec.encode(manifest))
                staged = true
                manifest
            } catch (e: IOException) {
                throw BackupException(BackupFailure.IO, e.message ?: "I/O error", e)
            } finally {
                // Whatever went wrong (a bad archive, no space, a cancellation), nothing half-unpacked stays.
                if (!staged) discardPending()
            }
        }

    /** The restore waiting for the next start, if there is one. */
    fun pending(): BackupManifest? =
        runCatching {
            BackupManifestCodec.decode(File(locations.stagingDir, BackupLocations.MARKER).readText(Charsets.UTF_8))
        }.getOrNull()

    fun discardPending() {
        locations.stagingDir.deleteRecursively()
    }

    /** How the last restore went, until [clearReport]. */
    fun report(): RestoreReport? =
        RestoreReportCodec.decodeOrNull(runCatching { locations.reportFile.readText(Charsets.UTF_8) }.getOrNull())

    fun clearReport() {
        locations.reportFile.delete()
    }

    // ---- writing

    private fun sourcesOf(
        area: BackupArea,
        snapshot: File,
    ): List<Pair<String, File>> {
        if (area != BackupArea.DATABASE) {
            return locations.liveFiles(area).filterNot { it.second.name.endsWith(TEMP_SUFFIX) }
        }
        if (!locations.databaseFile.isFile) return emptyList()
        hooks.freezeDatabase { locations.databaseFile.copyTo(snapshot, overwrite = true) }
        return listOf(BackupPaths.DATABASE_FILE to snapshot)
    }

    private fun plan(
        path: String,
        file: File,
    ): Planned {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return Planned(path, file, size, hex(digest.digest()), crc.value)
    }

    private suspend fun write(
        out: OutputStream,
        manifest: BackupManifest,
        planned: List<Planned>,
        progress: BackupProgress,
    ) {
        val total = planned.sumOf { it.size }
        var done = 0L
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupPaths.MANIFEST))
            zip.write(BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (item in planned) {
                currentCoroutineContext().ensureActive()
                val entry = ZipEntry(item.path)
                if (item.path.endsWith(".mbtiles")) {
                    // Tiles are images already: storing them is faster and just as small.
                    entry.method = ZipEntry.STORED
                    entry.size = item.size
                    entry.compressedSize = item.size
                    entry.crc = item.crc
                }
                zip.putNextEntry(entry)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                item.file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (copied + n > item.size) break
                        digest.update(buffer, 0, n)
                        zip.write(buffer, 0, n)
                        copied += n
                        done += n
                        progress.onBytes(done, total)
                    }
                }
                if (copied != item.size || hex(digest.digest()) != item.sha256) {
                    throw BackupException(BackupFailure.CORRUPT, "${item.path} changed while it was being saved")
                }
                zip.closeEntry()
            }
        }
    }

    // ---- reading

    private fun readManifest(zip: ZipInputStream): BackupManifest {
        val first = zip.nextEntry
        if (first == null || first.name != BackupPaths.MANIFEST) {
            throw BackupException(BackupFailure.NOT_A_BACKUP, "No manifest at the start of the archive")
        }
        val text = readLimited(zip, MAX_MANIFEST_BYTES)
        return BackupManifestCodec.decode(String(text, Charsets.UTF_8))
    }

    private fun readLimited(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw BackupException(BackupFailure.NOT_A_BACKUP, "Manifest too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private suspend fun unpack(
        zip: ZipInputStream,
        manifest: BackupManifest,
        progress: BackupProgress,
    ) {
        val expected = manifest.entries.associateBy { it.path }
        val payload = File(locations.stagingDir, BackupLocations.PAYLOAD).also { it.mkdirs() }
        val payloadRoot = payload.canonicalFile
        val seen = HashSet<String>()
        val total = manifest.totalBytes
        var done = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val declared =
                expected[entry.name] ?: throw BackupException(BackupFailure.CORRUPT, "Unlisted ${entry.name}")
            if (!seen.add(entry.name)) throw BackupException(BackupFailure.CORRUPT, "${entry.name} twice")
            val target = File(payload, entry.name)
            val canonical = target.canonicalFile
            if (!canonical.path.startsWith(payloadRoot.path + File.separator)) {
                throw BackupException(BackupFailure.UNSAFE, "${entry.name} leaves the staging folder")
            }
            canonical.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            canonical.outputStream().use { sink ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    size += n
                    if (size > declared.size) throw BackupException(BackupFailure.CORRUPT, "${entry.name} is larger")
                    digest.update(buffer, 0, n)
                    sink.write(buffer, 0, n)
                    done += n
                    progress.onBytes(done, total)
                }
            }
            if (size != declared.size || hex(digest.digest()) != declared.sha256) {
                throw BackupException(BackupFailure.CORRUPT, "${entry.name} does not match its checksum")
            }
        }
        val missing = manifest.entries.firstOrNull { it.path !in seen }
        if (missing != null) throw BackupException(BackupFailure.CORRUPT, "${missing.path} is missing")
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        const val SPACE_MARGIN = 16L * 1024 * 1024
        const val TEMP_SUFFIX = ".tmp"
    }
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal fun atomicWrite(
    target: File,
    text: String,
) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, target.name + ".tmp")
    temp.writeText(text, Charsets.UTF_8)
    if (!temp.renameTo(target)) {
        target.delete()
        if (!temp.renameTo(target)) throw IOException("Cannot write ${target.name}")
    }
}
