package fr.toolmap.debugkit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import fr.toolmap.core.eventlog.EventLog
import java.io.File

/**
 * The periodic snapshot of the test mode: `snap.sys` (battery, current drawn, CPU time, memory, data sent by the app,
 * screen, doze) then one `snap.<gauge>` line per gauge of the app (the server connection, the group...).
 */
internal class Snapshots(
    private val context: Context,
) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun write() {
        EventLog.event("snap.sys", *system().toTypedArray())
        for ((name, values) in EventLog.readGauges()) {
            EventLog.event("snap.$name", *values.toList().toTypedArray())
        }
    }

    private fun system(): List<Pair<String, Any?>> {
        val sticky =
            runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
        val runtime = Runtime.getRuntime()
        val uid = Process.myUid()
        return listOf(
            "bat" to battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "currentUa" to battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            "chargeUah" to battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            "plugged" to sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            "tempDc" to sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
            "cpuMs" to Process.getElapsedCpuTime(),
            "heapKb" to (runtime.totalMemory() - runtime.freeMemory()) / KB,
            "nativeKb" to Debug.getNativeHeapAllocatedSize() / KB,
            "threads" to threads(),
            "rxB" to TrafficStats.getUidRxBytes(uid),
            "txB" to TrafficStats.getUidTxBytes(uid),
            "screen" to power?.isInteractive,
            "doze" to power?.isDeviceIdleMode,
            "saver" to power?.isPowerSaveMode,
        )
    }

    private fun threads(): Int? =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toInt()
            }
        }.getOrNull()

    private companion object {
        const val KB = 1024
    }
}
