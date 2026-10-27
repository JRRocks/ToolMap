package fr.toolmap.core.route

import fr.toolmap.core.track.TrackPoint

/** Why BRouter gave no way. */
enum class RoutingError {
    /** No routing data (`.rd5`) for the area: they are downloaded in BRouter. */
    NO_DATA,

    /** The profile is not among those of BRouter. */
    UNKNOWN_PROFILE,

    /** No way between the two points (island, forbidden area). */
    NO_PATH,
    TIMEOUT,

    /** The BRouter app is not installed. */
    NOT_INSTALLED,
    OTHER,
}

/** The answer for one leg. */
sealed interface LegResult {
    data class Ok(
        val points: List<TrackPoint>,
        val timeMillis: Long?,
    ) : LegResult

    data class Failed(
        val error: RoutingError,
        val message: String,
    ) : LegResult
}

/** Computes the way between two points (BRouter in the app; a fake in the tests). */
interface RoutingEngine {
    suspend fun route(
        from: RouteStop,
        to: RouteStop,
        profile: String,
    ): LegResult
}

/** The BRouter profiles offered by name; others can be typed (personal profiles of BRouter). */
object RoutingProfiles {
    const val HIKING = "hiking-mountain"
    const val BIKE = "trekking"
    const val ROAD_BIKE = "fastbike"
    const val MTB = "mtb"
    const val CAR = "car-fast"
    const val SHORTEST = "shortest"

    val BUILT_IN: List<String> = listOf(HIKING, BIKE, ROAD_BIKE, MTB, CAR, SHORTEST)

    /** Walking profiles, whose time is estimated by DIN 33466 rather than taken from BRouter. */
    fun isWalking(profile: String): Boolean = profile == HIKING
}

/**
 * Results of the legs computed during an edit, the most recently used kept: undo, redo, reversing twice or going back
 * over a way already computed do not ask BRouter again.
 */
class LegCache(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val map = LinkedHashMap<LegKey, LegResult.Ok>(16, 0.75f, true)

    @Synchronized
    fun get(key: LegKey): LegResult.Ok? = map[key]

    @Synchronized
    fun put(
        key: LegKey,
        result: LegResult.Ok,
    ) {
        map[key] = result
        while (map.size > capacity) map.remove(map.keys.first())
    }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
