package fr.toolmap.eventlog

import android.content.Context
import android.util.Log
import fr.toolmap.BuildConfig
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.eventlog.EventStore
import java.io.File

/**
 * The event log of the app process (P21, `docs/specs/event-log.md`): the files in `files/eventlog`, opened once with
 * the main process. A debug build keeps more (larger files, more lines in memory for the test tools) and copies every
 * line to logcat under [LOGCAT_TAG], which the test scripts on the PC read.
 */
object AppEventLog {
    const val DIR = "eventlog"
    const val LOGCAT_TAG = "TMEvent"
    private const val DEBUG_FILE_BYTES = 4L shl 20
    private const val DEBUG_MEMORY_LINES = 4096

    @Volatile
    private var store: EventStore? = null

    /** The store of this process, made and installed in [EventLog] on the first call. */
    @Synchronized
    fun store(context: Context): EventStore =
        store ?: EventStore(
            File(context.applicationContext.filesDir, DIR),
            maxFileBytes = if (BuildConfig.DEBUG) DEBUG_FILE_BYTES else EventStore.DEFAULT_MAX_FILE_BYTES,
            memoryLines = if (BuildConfig.DEBUG) DEBUG_MEMORY_LINES else EventStore.DEFAULT_MEMORY_LINES,
            mirror = if (BuildConfig.DEBUG) { line -> Log.i(LOGCAT_TAG, line) } else null,
        ).also {
            store = it
            EventLog.install(it)
        }
}
