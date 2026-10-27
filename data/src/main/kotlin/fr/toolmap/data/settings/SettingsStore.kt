package fr.toolmap.data.settings

import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.AppSettingsCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The user's settings in one JSON file, replaced atomically so that a crash never leaves half a file. A missing or
 * damaged file gives the defaults. The current value is a [StateFlow]: screens and services read it, [update] writes.
 */
class SettingsStore(
    private val file: File,
) {
    private val state = MutableStateFlow(AppSettingsCodec.decodeOrDefault(readOrNull()))

    val settings: StateFlow<AppSettings> = state.asStateFlow()

    /** Applies [transform] to the current settings, saves the result and publishes it. */
    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        if (next == state.value) return
        write(next)
        state.value = next
    }

    /** Re-reads the file (after a restore). */
    @Synchronized
    fun reload() {
        state.value = AppSettingsCodec.decodeOrDefault(readOrNull())
    }

    private fun readOrNull(): String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    private fun write(settings: AppSettings) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(AppSettingsCodec.encode(settings), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot write ${file.name}" }
        }
    }
}
