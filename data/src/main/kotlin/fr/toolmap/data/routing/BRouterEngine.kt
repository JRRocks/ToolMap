package fr.toolmap.data.routing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import btools.routingapp.IBRouterService
import fr.toolmap.core.route.AvoidZone
import fr.toolmap.core.route.BRouterErrors
import fr.toolmap.core.route.BRouterJson
import fr.toolmap.core.route.LegResult
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RoutingEngine
import fr.toolmap.core.route.RoutingError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The routing of the BRouter app (`btools.routingapp`), through its AIDL service (P19.1, ADR-064): one call per leg,
 * the way as GeoJSON. Offline: BRouter reads its own data (`.rd5`), downloaded in BRouter. The service is bound on
 * the first call and kept until [release].
 */
class BRouterEngine(
    context: Context,
) : RoutingEngine {
    private val app = context.applicationContext
    private val lock = Mutex()

    @Volatile private var service: IBRouterService? = null
    private var connection: ServiceConnection? = null

    fun isInstalled(): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(PACKAGE, 0)
            }
        }.isSuccess

    /** Opens BRouter itself (its home screen leads to the download of the data); `false` when it is not there. */
    fun launchIntent(): Intent? = app.packageManager.getLaunchIntentForPackage(PACKAGE)

    override suspend fun route(
        from: RouteStop,
        to: RouteStop,
        profile: String,
        zones: List<AvoidZone>,
    ): LegResult =
        withContext(Dispatchers.IO) {
            if (!isInstalled()) return@withContext LegResult.Failed(RoutingError.NOT_INSTALLED, "not installed")
            // One call at a time: BRouter computes one way at a time anyway.
            lock.withLock {
                val s = bound() ?: return@withLock LegResult.Failed(RoutingError.NOT_INSTALLED, "bind failed")
                val params =
                    Bundle().apply {
                        putString("lonlats", "${deg(from.lon)},${deg(from.lat)}|${deg(to.lon)},${deg(to.lat)}")
                        putString("profile", profile)
                        putString("trackFormat", "json")
                        putString("maxRunningTime", MAX_RUNNING_SECONDS.toString())
                        // Circles the way must not enter (P19.8): `lon,lat,radius|…`.
                        if (zones.isNotEmpty()) putString("nogos", AvoidZone.nogos(zones))
                        // Gzipped and in Base64: a long way stays far below the 1 MB of a Binder call.
                        putString("acceptCompressedResult", "true")
                    }
                val text =
                    try {
                        runInterruptible { s.getTrackFromParams(params) }
                    } catch (e: android.os.RemoteException) {
                        Log.w(TAG, "BRouter call failed", e)
                        release()
                        return@withLock LegResult.Failed(RoutingError.OTHER, e.toString())
                    } catch (e: SecurityException) {
                        Log.w(TAG, "BRouter call refused", e)
                        return@withLock LegResult.Failed(RoutingError.OTHER, e.toString())
                    }
                answerOf(text)
            }
        }

    /** Unbinds the service (end of the edit). */
    fun release() {
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        service = null
    }

    private suspend fun bound(): IBRouterService? {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        release()
        val ready = CompletableDeferred<IBRouterService?>()
        val c =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    val s = binder?.let { IBRouterService.Stub.asInterface(it) }
                    service = s
                    ready.complete(s)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }

                override fun onBindingDied(name: ComponentName?) {
                    service = null
                    ready.complete(null)
                }

                override fun onNullBinding(name: ComponentName?) {
                    ready.complete(null)
                }
            }
        val intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE))
        val ok = runCatching { app.bindService(intent, c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) {
            runCatching { app.unbindService(c) }
            return null
        }
        connection = c
        return withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { ready.await() }
    }

    companion object {
        const val PACKAGE = "btools.routingapp"
        private const val SERVICE = "btools.routingapp.BRouterService"
        private const val TAG = "BRouterEngine"
        private const val MAX_RUNNING_SECONDS = 30
        private const val BIND_TIMEOUT_MILLIS = 10_000L

        /** "z64" in Base64: the start of a compressed answer. */
        private const val COMPRESSED = "ejY0"

        private fun deg(value: Double): String = String.format(Locale.ROOT, "%.7f", value)

        /** The way, or the reason BRouter gives instead. */
        internal fun answerOf(text: String?): LegResult {
            if (text == null) return LegResult.Failed(RoutingError.OTHER, "no answer")
            val plain = if (text.startsWith(COMPRESSED)) inflate(text) ?: return bad(text) else text
            if (plain.trimStart().startsWith("{")) return BRouterJson.parse(plain) ?: bad(plain)
            return LegResult.Failed(BRouterErrors.classify(plain), plain)
        }

        private fun bad(text: String) = LegResult.Failed(RoutingError.OTHER, text.take(MAX_MESSAGE))

        private const val MAX_MESSAGE = 200

        private fun inflate(text: String): String? =
            runCatching {
                val bytes = Base64.decode(text, Base64.DEFAULT)
                GZIPInputStream(bytes.inputStream(MARKER, bytes.size - MARKER)).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()

        private const val MARKER = 3
    }
}
