package fr.toolmap.data.state

import fr.toolmap.core.state.WorkState
import fr.toolmap.core.state.WorkStateCodec
import java.io.File

/** Keeps the [WorkState] in one JSON file, replaced atomically so a crash never leaves half a file. */
class WorkStateStore(
    private val file: File,
) {
    fun load(): WorkState = WorkStateCodec.decodeOrDefault(runCatching { file.readText(Charsets.UTF_8) }.getOrNull())

    fun save(state: WorkState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(WorkStateCodec.encode(state), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot write ${file.name}" }
        }
    }
}
