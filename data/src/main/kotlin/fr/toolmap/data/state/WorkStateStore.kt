package fr.toolmap.data.state

import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.state.WorkState
import fr.toolmap.core.state.WorkStateCodec
import java.io.File
import java.io.IOException

/** Keeps the [WorkState] in one JSON file, replaced atomically so a crash never leaves half a file. */
class WorkStateStore(
    private val file: File,
) {
    /** The saved state; the defaults when there is none yet or the file cannot be read (logged). */
    fun load(): WorkState {
        val text =
            catchExpected("state.store.read", IOException::class) {
                file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }.getOrNull()
        return WorkStateCodec.decodeOrDefault(text)
    }

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
