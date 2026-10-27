package fr.toolmap.service

import android.content.Context
import android.os.PowerManager

/**
 * The partial wake lock of a foreground service. Android drops a lock taken with a timeout once it elapses, so the
 * services call [hold] each time their state changes: a lock that timed out during a long outing (more than a day of
 * recording, say) is taken again at the next call instead of staying "present but not held".
 */
internal class ServiceWakeLock(
    private val context: Context,
    private val tag: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private var lock: PowerManager.WakeLock? = null

    val held: Boolean get() = lock?.isHeld == true

    fun hold() {
        val current =
            lock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                .also { lock = it }
        if (!current.isHeld) current.acquire(timeoutMillis)
    }

    fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 24L * 60 * 60 * 1000
    }
}
