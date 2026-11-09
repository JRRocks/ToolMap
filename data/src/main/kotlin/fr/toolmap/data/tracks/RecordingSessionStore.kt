package fr.toolmap.data.tracks

import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.record.RecordingSession
import fr.toolmap.core.record.RecordingSessionCodec
import java.io.File
import java.io.IOException

/**
 * The one file telling that a recording is under way (or was, when the process died). Written atomically so that a
 * kill in the middle of a save leaves the previous version; a damaged file reads as "no recording".
 */
class RecordingSessionStore(
    private val file: File,
) {
    fun read(): RecordingSession? {
        val text =
            catchExpected("recording.session.read", IOException::class) {
                file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }.getOrNull()
        return text?.let(RecordingSessionCodec::decodeOrNull)
    }

    fun write(session: RecordingSession) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(RecordingSessionCodec.encode(session), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot write ${file.name}" }
        }
    }

    fun clear() {
        file.delete()
    }
}
