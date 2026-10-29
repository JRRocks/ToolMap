package fr.toolmap.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.edit
import fr.toolmap.BuildConfig
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.update.GitHubReleases
import fr.toolmap.core.update.PublishedApk
import fr.toolmap.core.update.UpdateDecision
import fr.toolmap.data.tiles.OfflineMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** A download of the new APK: what is done, and whether its dialog is on screen (closed, it goes on, P31 variant B). */
data class UpdateDownload(
    val version: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val shown: Boolean = true,
)

/** Where the update of the app stands (P31, ADR-094), read by the map, its menu and its dialogs. */
data class UpdateUi(
    /** The version of this APK (in debug builds, the one a test set in place of it). */
    val installed: String,
    /** The highest version published, newer or not; `null` before the first answer. */
    val latest: PublishedApk? = null,
    /** [latest] when it is above [installed]: what is offered. */
    val available: PublishedApk? = null,
    val checkedAtMillis: Long? = null,
    val checking: Boolean = false,
    /** The last check got no usable answer (no network, GitHub refused or down). */
    val unreachable: Boolean = false,
    val checkOnLaunch: Boolean = true,
    /** The banner of the map (variant D): after a check at launch that found a version, until closed or 15 s. */
    val banner: Boolean = false,
    val download: UpdateDownload? = null,
    /** The download stopped on an error. */
    val failed: Boolean = false,
    /** The error is a phone without room for the APK: « Pas assez de place » (variant A), not the connection. */
    val noSpace: Boolean = false,
    /** Android refused the APK, signed by another key: « Installation refusée ». */
    val refused: Boolean = false,
    /** Android's own confirmation, started by the screen as soon as it is shown. */
    val confirm: Intent? = null,
)

/**
 * The update of the app from GitHub (P31, ADR-094): at launch, asks the GitHub API for the debug APK of `main` and
 * offers it when its version is higher; downloads it in the app (progress in a dialog and a notification) and hands it
 * to Android's package installer, whose answer comes back through [UpdateInstallReceiver]. One for the whole process.
 */
class UpdateController(
    private val context: Context,
    private val offline: OfflineMode,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    private val mutableUi =
        MutableStateFlow(
            UpdateUi(
                installed = installedVersion(),
                checkOnLaunch = prefs.getBoolean(KEY_CHECK_ON_LAUNCH, true),
            ),
        )
    val ui: StateFlow<UpdateUi> = mutableUi.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** The check of a new start of the app, unless the user switched it off or the app is in offline mode. */
    fun checkAtLaunch() {
        if (!ui.value.checkOnLaunch || offline.isOffline()) return
        check(atLaunch = true)
    }

    /** Asks GitHub for the latest version; [atLaunch] shows the banner of the map when it is newer. */
    fun check(atLaunch: Boolean = false) {
        if (checkJob?.isActive == true) return
        mutableUi.update { it.copy(checking = true) }
        checkJob =
            scope.launch {
                val published = withContext(Dispatchers.IO) { fetchLatest() }
                val offer = UpdateDecision.offer(ui.value.installed, published)
                EventLog.event(
                    "update.check",
                    "installed" to ui.value.installed,
                    "latest" to published?.version?.toString(),
                    "launch" to atLaunch,
                )
                mutableUi.update {
                    it.copy(
                        checking = false,
                        unreachable = published == null,
                        latest = published ?: it.latest,
                        available = if (published == null) it.available else offer,
                        checkedAtMillis = if (published == null) it.checkedAtMillis else clock(),
                        banner = it.banner || (atLaunch && offer != null),
                    )
                }
            }
    }

    fun setCheckOnLaunch(on: Boolean) {
        prefs.edit { putBoolean(KEY_CHECK_ON_LAUNCH, on) }
        mutableUi.update { it.copy(checkOnLaunch = on) }
    }

    fun dismissBanner() = mutableUi.update { it.copy(banner = false) }

    /** Downloads the offered APK, then installs it; a second call while it runs only shows its dialog again. */
    fun startDownload() {
        val apk = ui.value.available ?: return
        if (downloadJob?.isActive == true) {
            mutableUi.update { it.copy(download = it.download?.copy(shown = true), banner = false) }
            return
        }
        val version = apk.version.toString()
        mutableUi.update {
            it.copy(
                banner = false,
                failed = false,
                noSpace = false,
                refused = false,
                download = UpdateDownload(version, 0, apk.sizeBytes),
            )
        }
        UpdateNotifications.ensureChannel(context)
        downloadJob =
            scope.launch {
                val installer = context.packageManager.packageInstaller
                var sessionId = NO_SESSION
                try {
                    withContext(Dispatchers.IO) {
                        if (lacksRoomFor(apk.sizeBytes)) throw IOException("no room for ${apk.sizeBytes} bytes")
                        sessionId = installer.createSession(sessionParams())
                        install(installer, sessionId, apk)
                    }
                } catch (e: CancellationException) {
                    abandon(installer, sessionId)
                    throw e
                } catch (e: IOException) {
                    abandon(installer, sessionId)
                    // Read once the session is dropped, so that its own room does not count: a cut connection stays one.
                    val noSpace = withContext(Dispatchers.IO) { lacksRoomFor(apk.sizeBytes) }
                    EventLog.event("update.failed", "version" to version, "error" to e.toString(), "noSpace" to noSpace)
                    UpdateNotifications.cancel(context)
                    mutableUi.update {
                        it.copy(download = it.download?.copy(shown = true), failed = true, noSpace = noSpace)
                    }
                }
            }
    }

    /** The dialog of the download is closed: the download goes on, its notification stays. */
    fun hideProgress() = mutableUi.update { it.copy(download = it.download?.copy(shown = false)) }

    fun cancelDownload() {
        downloadJob?.cancel()
        UpdateNotifications.cancel(context)
        mutableUi.update { it.copy(download = null, failed = false, noSpace = false) }
    }

    fun dismissFailed() = mutableUi.update { it.copy(download = null, failed = false, noSpace = false) }

    fun dismissRefused() = mutableUi.update { it.copy(refused = false) }

    /** The screen started Android's confirmation: it is not started twice. */
    fun confirmStarted() = mutableUi.update { it.copy(confirm = null) }

    /** The answer of the package installer to the session of [install] (through [UpdateInstallReceiver]). */
    fun onInstallStatus(
        status: Int,
        confirm: Intent?,
        message: String?,
    ) {
        EventLog.event("update.install", "status" to status, "message" to message)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm != null) {
                    mutableUi.update { it.copy(confirm = confirm, download = it.download?.copy(shown = false)) }
                    UpdateNotifications.ready(context, ui.value.download?.version.orEmpty())
                }
            }

            // Another key signed one of the two APKs (or the new one cannot replace this one): said, with what to do.
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                UpdateNotifications.cancel(context)
                mutableUi.update { it.copy(download = null, refused = true, confirm = null) }
            }

            // Cancelled in Android's dialog: the offer stays, nothing else is said.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateNotifications.cancel(context)
                mutableUi.update { it.copy(download = null, confirm = null) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                UpdateNotifications.cancel(context)
                mutableUi.update { it.copy(download = null, confirm = null) }
            }

            else -> {
                UpdateNotifications.cancel(context)
                val noSpace = status == PackageInstaller.STATUS_FAILURE_STORAGE
                mutableUi.update {
                    it.copy(
                        download = it.download?.copy(shown = true),
                        failed = true,
                        noSpace = noSpace,
                        confirm = null,
                    )
                }
            }
        }
    }

    private fun fetchLatest(): PublishedApk? =
        try {
            val request =
                Request
                    .Builder()
                    .url(GitHubReleases.API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && body != null) GitHubReleases.latestApk(body) else null
            }
        } catch (e: IOException) {
            EventLog.event("update.unreachable", "error" to e.toString())
            null
        }

    private fun sessionParams() =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }

    /**
     * Downloads [apk] straight into the session [id] of the package installer, then hands it to Android, whose answer
     * comes through [UpdateInstallReceiver]. No copy in the cache: the phone needs room for one APK, not two.
     */
    private suspend fun install(
        installer: PackageInstaller,
        id: Int,
        apk: PublishedApk,
    ) {
        installer.openSession(id).use { session ->
            // With its length, Android sets the room aside first: a full phone fails at once, not after the download.
            val length = apk.sizeBytes.takeIf { it > 0 } ?: -1L
            val bytes =
                session.openWrite("ToolMap-${apk.version}.apk", 0, length).use { output ->
                    fetchApk(apk, output).also { session.fsync(output) }
                }
            EventLog.event("update.downloaded", "version" to apk.version.toString(), "bytes" to bytes)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val answer =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                )
            session.commit(answer.intentSender)
        }
    }

    /** Writes the APK of [apk] to [output], with its progress; returns its size. */
    private suspend fun fetchApk(
        apk: PublishedApk,
        output: OutputStream,
    ): Long {
        val request = Request.Builder().url(apk.url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code}")
            val total = body.contentLength().takeIf { it > 0 } ?: apk.sizeBytes
            var done = 0L
            var shown = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (done - shown >= STEP_BYTES) {
                        shown = done
                        progress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("incomplete: $done of $total")
            progress(done, total)
            return done
        }
    }

    private fun progress(
        done: Long,
        total: Long,
    ) {
        mutableUi.update { it.copy(download = it.download?.copy(doneBytes = done, totalBytes = total)) }
        UpdateNotifications.progress(context, ui.value.download?.version.orEmpty(), done, total)
    }

    /**
     * Whether Android cannot set aside [bytes] where it writes its sessions (the app data volume), clearable caches
     * counted; a size unknown or a storage that does not answer is not taken for a full phone.
     */
    private fun lacksRoomFor(bytes: Long): Boolean {
        if (bytes <= 0) return false
        val storage = context.getSystemService(StorageManager::class.java) ?: return false
        return runCatching { storage.getAllocatableBytes(StorageManager.UUID_DEFAULT) < bytes }.getOrDefault(false)
    }

    /** Drops a session that was not handed to Android (cancelled, or the download failed). */
    private fun abandon(
        installer: PackageInstaller,
        id: Int,
    ) {
        if (id == NO_SESSION) return
        runCatching { installer.abandonSession(id) }
    }

    /** This APK's version; a debug build reads the one a test wrote in its preferences (`installed_override`). */
    private fun installedVersion(): String =
        (if (BuildConfig.DEBUG) prefs.getString(KEY_INSTALLED_OVERRIDE, null) else null) ?: BuildConfig.VERSION_NAME

    private companion object {
        const val PREFS = "updates"
        const val KEY_CHECK_ON_LAUNCH = "check_on_launch"
        const val KEY_INSTALLED_OVERRIDE = "installed_override"
        const val NO_SESSION = -1
        const val TIMEOUT_SECONDS = 30L
        const val BUFFER_BYTES = 64 * 1024
        const val STEP_BYTES = 512 * 1024L
        val USER_AGENT = "ToolMap/${BuildConfig.VERSION_NAME} (Android)"
    }
}
