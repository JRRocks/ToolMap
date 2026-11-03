package fr.toolmap.p2p

import fr.toolmap.core.p2p.GroupPoint
import fr.toolmap.core.p2p.ShareState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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

    private val pointsFile = File(file.parentFile, file.name.substringBeforeLast('.') + ".points.json")

    /** The points of the group kept by [writePoints] (P27.2); none when the file is missing or damaged. */
    fun readPoints(): List<GroupPoint> =
        runCatching {
            POINTS_JSON.decodeFromString(POINTS, pointsFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())

    /** Keeps the points of the group, tombstones included, so that a new process gives them to the next links. */
    fun writePoints(points: List<GroupPoint>) {
        runCatching {
            if (points.isEmpty()) {
                pointsFile.delete()
            } else {
                writeAtomically(pointsFile, POINTS_JSON.encodeToString(POINTS, points))
            }
        }
    }

    fun write(state: ShareState) {
        writeAtomically(file, state.toJson())
    }

    private fun writeAtomically(
        target: File,
        text: String,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot write ${target.name}" }
        }
    }

    private companion object {
        val POINTS = ListSerializer(GroupPoint.serializer())
        val POINTS_JSON = Json { ignoreUnknownKeys = true }
    }
}
