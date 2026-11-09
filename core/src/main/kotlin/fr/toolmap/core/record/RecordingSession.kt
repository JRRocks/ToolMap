package fr.toolmap.core.record

import fr.toolmap.core.problem.catchReported
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What survives the death of the recording process: the stored track the points go to, and the time spent recording
 * so far. The points themselves are already in the database; this is enough to rebuild the live figures and to offer
 * the user to carry on.
 */
@Serializable
data class RecordingSession(
    val trackId: Long,
    val startedAtMillis: Long,
    /** Recording time up to the last save, pauses excluded. */
    val activeMillis: Long,
    val paused: Boolean,
    val savedAtMillis: Long,
    /** [RecorderProfile] name, to carry on with the same settings after a restart. */
    val profile: String = RecorderProfile.WALK.name,
)

object RecordingSessionCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(session: RecordingSession): String = json.encodeToString(RecordingSession.serializer(), session)

    /** `null` for a damaged file: the app wrote it itself, so that is a bug or a damaged disk. */
    fun decodeOrNull(text: String): RecordingSession? =
        catchReported("record.session.decode") {
            json.decodeFromString(RecordingSession.serializer(), text)
        }.getOrNull()
}
