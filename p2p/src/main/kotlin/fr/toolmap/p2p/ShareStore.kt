package fr.toolmap.p2p

import fr.toolmap.core.p2p.ShareState
import java.io.File

/**
 * The one file keeping the sharing settings and the group key between launches. Written atomically so that a kill in
 * the middle of a save leaves the previous version; a damaged file reads as "defaults, no group".
 */
class ShareStore(
    private val file: File,
) {
    fun read(): ShareState =
        runCatching { file.takeIf { it.isFile }?.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.let(ShareState::fromJson)
            ?: ShareState()

    private val seqFile = File(file.parentFile, file.name.substringBeforeLast('.') + ".seq")

    /** The last message number kept by [writeSeq], or `null` (P21.5 m). */
    fun readSeq(): Long? = runCatching { seqFile.readText().trim().toLong() }.getOrNull()

    /** Keeps the last message number used, so that the next process starts above it (P21.5 m). */
    fun writeSeq(seq: Long) {
        runCatching {
            seqFile.parentFile?.mkdirs()
            seqFile.writeText(seq.toString())
        }
    }

    fun write(state: ShareState) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(state.toJson(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot write ${file.name}" }
        }
    }
}
