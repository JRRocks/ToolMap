package fr.toolmap.core.state

import fr.toolmap.core.layers.LayerStack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CameraState(
    val lat: Double = 46.5,
    val lon: Double = 2.5,
    /** MapLibre camera zoom. */
    val zoom: Double = 5.0,
    val bearing: Double = 0.0,
)

/** What the app restores at startup: layers, camera and the two last base maps for the quick swap. */
@Serializable
data class WorkState(
    val stack: LayerStack = LayerStack().add("osm"),
    val camera: CameraState = CameraState(),
    /** Most recent first, at most two entries. */
    val recentBases: List<String> = listOf("osm"),
    val offline: Boolean = false,
) {
    /** Remembers [sourceId] as the base map in use (keeps the two latest distinct ones). */
    fun withBaseUsed(sourceId: String): WorkState =
        copy(recentBases = (listOf(sourceId) + recentBases).distinct().take(2))

    /** Base map to switch to with the quick-swap button: the previous one, if there is one. */
    fun swapTarget(): String? = recentBases.getOrNull(1)

    fun withStack(stack: LayerStack): WorkState = copy(stack = stack)

    fun withCamera(camera: CameraState): WorkState = copy(camera = camera)
}

object WorkStateCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    fun encode(state: WorkState): String = json.encodeToString(WorkState.serializer(), state)

    /** Never throws: a missing or damaged file gives the default state. */
    fun decodeOrDefault(text: String?): WorkState =
        if (text.isNullOrBlank()) {
            WorkState()
        } else {
            runCatching { json.decodeFromString(WorkState.serializer(), text) }.getOrDefault(WorkState())
        }
}
