package fr.toolmap

import android.app.Application
import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import fr.toolmap.core.eventlog.AppExtension
import fr.toolmap.crash.CrashReporter
import fr.toolmap.data.backup.PendingRestore
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.TileLocation
import fr.toolmap.eventlog.AppEventLog
import fr.toolmap.eventlog.SystemEvents
import fr.toolmap.storage.TileStorages
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import javax.inject.Inject

@HiltAndroidApp
class ToolMapApp : Application() {
    // Lazy: the report screen's process (CrashReporter) is also a ToolMapApp and must not build the HTTP stack.
    @Inject
    lateinit var tileHttpClient: Lazy<OkHttpClient>

    @Inject
    lateinit var storageManager: Lazy<StorageManager>

    /** The live-test tools (`:debugkit`); none since that module left the build (ADR-072). */
    @Inject
    lateinit var extensions: Lazy<Set<@JvmSuppressWildcards AppExtension>>

    private var mapLibreReady = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (CrashReporter.isCrashProcess(this)) return
        // First of all: from here on, an exception ends on the report screen instead of a silent exit.
        CrashReporter.install(this)
        // A restore chosen in the backup screen is put in place now, before anything opens the database or reads a file.
        runCatching { PendingRestore.apply(TileStorages.backupLocations(this)) }
    }

    override fun onCreate() {
        super.onCreate() // Hilt injects the fields here
        if (CrashReporter.isCrashProcess(this)) return
        // Before anything else, so that the log also tells about the starts that stop at a crash report.
        if (CrashReporter.isMainProcess(this)) startEventLog()
        CrashReporter.collectNativeCrash(this)
        // A crash not yet seen: MainActivity opens its report, nothing that may crash again runs before.
        if (CrashReporter.hasPending(this)) return
        // A move of the offline maps is waiting: it runs in its own process first, so nothing here may open a tile file.
        if (TileLocation(filesDir).requestedMove() != null) return
        ensureMapLibre()
        migrateTileStorage()
    }

    /** The event log of the process (P21) and its sources outside the app's own code; then the test tools, if any. */
    private fun startEventLog() {
        AppEventLog.store(this)
        SystemEvents(this).start()
        for (extension in extensions.get()) {
            runCatching { extension.start() }.onFailure { Log.w(TAG, "Extension not started", it) }
        }
    }

    /**
     * Tiles stored per map before ADR-031, or for an imported map that is now built in (ADR-069), move to the storage
     * they share with the maps serving the same tiles.
     */
    private fun migrateTileStorage() {
        Thread({
            runCatching { storageManager.get().migrateLegacyFiles() }
                .onSuccess { if (it > 0) Log.i(TAG, "$it tile files moved to shared storage") }
                .onFailure { Log.w(TAG, "Tile storage migration failed", it) }
        }, "tile-storage-migration").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Sets MapLibre up, once per process. Also called by [MainActivity] before the map, for a process whose [onCreate]
     * skipped it (a crash report was waiting, then the user restarted from the report screen).
     */
    fun ensureMapLibre() {
        if (mapLibreReady) return
        mapLibreReady = true
        // MapLibre first: setOkHttpClient loads HttpRequestImpl, whose static initializer reads MapLibre's context and
        // throws (killing the process before any window) when MapLibre is not initialized yet.
        MapLibre.getInstance(this)
        // Every MapLibre HTTP request (tiles, style...) now goes through our client and its TileInterceptor.
        // Must be set before the first MapView is created.
        HttpRequestUtil.setOkHttpClient(tileHttpClient.get())
        // MapLibre asks for no tile at all while the phone has no network (airplane mode), not even the stored ones:
        // always online for it, the TileInterceptor serves the store and reports what it cannot fetch.
        MapLibre.setConnected(true)
        disableAmbientCache()
    }

    // Tiles are stored by our own store (ADR-003): MapLibre's ambient cache would duplicate them.
    private fun disableAmbientCache() {
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(
            0L,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    Log.i(TAG, "MapLibre ambient cache disabled")
                }

                override fun onError(message: String) {
                    Log.w(TAG, "Could not disable the MapLibre ambient cache: $message")
                }
            },
        )
    }

    private companion object {
        const val TAG = "ToolMap"
    }
}
