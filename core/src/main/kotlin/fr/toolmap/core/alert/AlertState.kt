package fr.toolmap.core.alert

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class FollowKind { TRACK, ROUTE }

/** The line being followed: a stored track or route, walked forwards or backwards. */
@Serializable
data class FollowRef(
    val kind: FollowKind,
    val id: Long,
    val reversed: Boolean = false,
)

/** A waypoint to be warned about; without [radiusMeters] the default radius of the settings applies. */
@Serializable
data class WatchEntry(
    val waypointId: Long,
    val radiusMeters: Int? = null,
)

/** What the user asked the alerts to watch: kept across restarts, so that a walk can go on after the app is killed. */
@Serializable
data class AlertState(
    val follow: FollowRef? = null,
    val watches: List<WatchEntry> = emptyList(),
) {
    val isIdle: Boolean get() = follow == null && watches.isEmpty()

    fun watching(waypointId: Long): WatchEntry? = watches.firstOrNull { it.waypointId == waypointId }

    /** Watches the waypoint, or changes its radius when it is already watched. */
    fun withWatch(
        waypointId: Long,
        radiusMeters: Int?,
    ): AlertState =
        copy(
            watches =
                watches.filterNot { it.waypointId == waypointId } + WatchEntry(waypointId, radiusMeters),
        )

    fun withoutWatch(waypointId: Long): AlertState = copy(watches = watches.filterNot { it.waypointId == waypointId })

    /** Forgets what refers to the given items, which were deleted. */
    fun removing(
        trackIds: Set<Long>,
        routeIds: Set<Long>,
        waypointIds: Set<Long>,
    ): AlertState {
        val dropFollow =
            when (follow?.kind) {
                FollowKind.TRACK -> follow.id in trackIds
                FollowKind.ROUTE -> follow.id in routeIds
                null -> false
            }
        return AlertState(
            follow = follow.takeUnless { dropFollow },
            watches = watches.filterNot { it.waypointId in waypointIds },
        )
    }

    /** Keeps only what refers to items that exist: [removing] with everything not listed. */
    fun pruned(
        trackIds: Set<Long>,
        routeIds: Set<Long>,
        waypointIds: Set<Long>,
    ): AlertState {
        val keepFollow =
            when (follow?.kind) {
                FollowKind.TRACK -> follow.id in trackIds
                FollowKind.ROUTE -> follow.id in routeIds
                null -> false
            }
        return AlertState(
            follow = follow.takeIf { keepFollow },
            watches = watches.filter { it.waypointId in waypointIds },
        )
    }
}

object AlertStateCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

    fun encode(state: AlertState): String = json.encodeToString(AlertState.serializer(), state)

    /** Never throws: a missing or damaged file means nothing is watched. */
    fun decodeOrEmpty(text: String?): AlertState =
        if (text.isNullOrBlank()) {
            AlertState()
        } else {
            runCatching { json.decodeFromString(AlertState.serializer(), text) }.getOrDefault(AlertState())
        }
}
