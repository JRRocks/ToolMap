package fr.toolmap.data.alert

import fr.toolmap.core.alert.AlertState
import fr.toolmap.core.alert.AlertStateCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * What the alerts watch (followed line, watched waypoints) in one JSON file, replaced atomically. A missing or damaged
 * file means nothing is watched.
 */
class AlertStateStore(
    private val file: File,
) {
    private val flow = MutableStateFlow(AlertStateCodec.decodeOrEmpty(readOrNull()))

    val state: StateFlow<AlertState> = flow.asStateFlow()

    @Synchronized
    fun update(transform: (AlertState) -> AlertState) {
        val next = transform(flow.value)
        if (next == flow.value) return
        write(next)
        flow.value = next
    }

    /** Re-reads the file (after a restore). */
    @Synchronized
    fun reload() {
        flow.value = AlertStateCodec.decodeOrEmpty(readOrNull())
    }

    private fun readOrNull(): String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    private fun write(state: AlertState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(AlertStateCodec.encode(state), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot write ${file.name}" }
        }
    }
}
