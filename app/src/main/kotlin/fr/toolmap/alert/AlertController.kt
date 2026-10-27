package fr.toolmap.alert

import fr.toolmap.R
import fr.toolmap.core.alert.AlertEngine
import fr.toolmap.core.alert.AlertEvent
import fr.toolmap.core.alert.AlertState
import fr.toolmap.core.alert.FollowKind
import fr.toolmap.core.alert.FollowRef
import fr.toolmap.core.alert.FollowStatus
import fr.toolmap.core.alert.TrackFollower
import fr.toolmap.core.alert.WatchEntry
import fr.toolmap.core.alert.WatchedPlace
import fr.toolmap.core.geo.LonLat
import fr.toolmap.data.alert.AlertStateStore
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How an alert reaches the user outside the app: notification, vibration, sound. */
interface AlertNotifier {
    fun raise(event: AlertEvent)
}

/** The foreground service that keeps the GPS and the alerts alive with the screen off. */
interface AlertServiceControl {
    fun start()

    fun stop()
}

/** The line being followed and where the walker stands on it. */
data class FollowUi(
    val kind: FollowKind,
    val id: Long,
    val name: String?,
    val reversed: Boolean,
    /** `null` until the first usable position. */
    val status: FollowStatus? = null,
)

/** The last alert raised, kept on the map until dismissed (or until the situation is over). */
data class AlertBanner(
    val event: AlertEvent,
    val atMillis: Long,
)

data class AlertUi(
    val follow: FollowUi? = null,
    val watches: List<WatchEntry> = emptyList(),
    /** The watched waypoints found in the library, with the radius in force: what the engine really watches. */
    val places: List<WatchedPlace> = emptyList(),
    /** The GPS could not be started: nothing can be raised. */
    val gpsFailed: Boolean = false,
    val banner: AlertBanner? = null,
) {
    val active: Boolean get() = follow != null || watches.isNotEmpty()
}

/**
 * The alerts of feature P9.4 (proximity of watched waypoints, following a track or route with an off-track warning)
 * for the whole process. What is watched is [AlertStateStore]'s and survives restarts; the rules are [AlertEngine]'s.
 * While something is watched this class listens to the GPS and keeps the foreground service running; when nothing is,
 * it does neither.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertController(
    private val repository: TrackRepository,
    private val settings: SettingsStore,
    private val store: AlertStateStore,
    private val location: LocationSource,
    private val notifier: AlertNotifier,
    private val service: AlertServiceControl,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val engine = AlertEngine(settings.settings.value.alerts)
    private val mutableUi = MutableStateFlow(AlertUi(watches = store.state.value.watches))
    val ui: StateFlow<AlertUi> = mutableUi.asStateFlow()

    private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

    @Volatile private var library: TrackLibrary? = null

    /** What the saved state mentioned when the process started: the only things the first library reading may drop. */
    private val restored = store.state.value

    init {
        scope.launch {
            settings.settings.collect { s ->
                synchronized(engine) { engine.updateSettings(s.alerts) }
                refreshWatched()
            }
        }
        scope.launch { store.state.collect { state -> onState(state) } }
        scope.launch { repository.library.collect { lib -> onLibrary(lib) } }
        scope.launch { store.state.map { it.follow }.distinctUntilChanged().collectLatest { loadFollow(it) } }
        scope.launch { store.state.map { !it.isIdle }.distinctUntilChanged().collectLatest { run(it) } }
    }

    // --- what the user asks for ---------------------------------------------------------------------------------

    /** Follows a track or a route; a waypoint cannot be followed. */
    fun follow(ref: ItemRef) {
        val kind =
            when (ref.kind) {
                ItemKind.TRACK -> FollowKind.TRACK
                ItemKind.ROUTE -> FollowKind.ROUTE
                ItemKind.WAYPOINT -> return
            }
        store.update { it.copy(follow = FollowRef(kind, ref.id)) }
    }

    fun stopFollowing() = store.update { it.copy(follow = null) }

    /** Follows the same line the other way round. */
    fun reverseFollow() =
        store.update { state ->
            state.copy(follow = state.follow?.let { it.copy(reversed = !it.reversed) })
        }

    /** Watches a waypoint; [radiusMeters] `null` means the default radius of the settings. */
    fun watch(
        waypointId: Long,
        radiusMeters: Int?,
    ) = store.update { it.withWatch(waypointId, radiusMeters) }

    fun unwatch(waypointId: Long) = store.update { it.withoutWatch(waypointId) }

    /** Stops everything that is watched. */
    fun stopAll() = store.update { AlertState() }

    fun dismissBanner() = mutableUi.update { it.copy(banner = null) }

    fun isFollowing(ref: ItemRef): Boolean {
        val follow = store.state.value.follow ?: return false
        return when (ref.kind) {
            ItemKind.TRACK -> follow.kind == FollowKind.TRACK && follow.id == ref.id
            ItemKind.ROUTE -> follow.kind == FollowKind.ROUTE && follow.id == ref.id
            ItemKind.WAYPOINT -> false
        }
    }

    // --- the work -----------------------------------------------------------------------------------------------

    private fun onState(state: AlertState) {
        mutableUi.update { it.copy(watches = state.watches) }
        refreshWatched()
    }

    /**
     * Items deleted from the library are forgotten. Only what was seen and has gone is dropped (and, on the first
     * reading, what the state saved before the start mentions but the library does not have): a snapshot of the
     * library that does not contain an item yet must not erase a watch just set on it.
     */
    private fun onLibrary(lib: TrackLibrary) {
        val previous = library
        library = lib
        val tracks = lib.tracks.mapTo(HashSet()) { it.id }
        val routes = lib.routes.mapTo(HashSet()) { it.id }
        val waypoints = lib.waypoints.mapTo(HashSet()) { it.id }
        val goneTracks: Set<Long>
        val goneRoutes: Set<Long>
        val goneWaypoints: Set<Long>
        if (previous == null) {
            val follow = restored.follow
            goneTracks = setOfNotNull(follow?.takeIf { it.kind == FollowKind.TRACK }?.id) - tracks
            goneRoutes = setOfNotNull(follow?.takeIf { it.kind == FollowKind.ROUTE }?.id) - routes
            goneWaypoints = restored.watches.mapTo(HashSet()) { it.waypointId } - waypoints
        } else {
            goneTracks = previous.tracks.map { it.id }.toSet() - tracks
            goneRoutes = previous.routes.map { it.id }.toSet() - routes
            goneWaypoints = previous.waypoints.map { it.id }.toSet() - waypoints
        }
        if (goneTracks.isNotEmpty() || goneRoutes.isNotEmpty() || goneWaypoints.isNotEmpty()) {
            store.update { it.removing(goneTracks, goneRoutes, goneWaypoints) }
        }
        refreshWatched()
    }

    private suspend fun run(active: Boolean) {
        if (!active) {
            service.stop()
            mutableUi.update { it.copy(follow = null, gpsFailed = false, banner = null) }
            return
        }
        service.start()
        mutableUi.update { it.copy(gpsFailed = false) }
        location
            .fixes(UPDATE_MILLIS, 0f)
            .catch { mutableUi.update { ui -> ui.copy(gpsFailed = true) } }
            .collect { fix ->
                val update = synchronized(engine) { engine.onFix(fix) }
                for (event in update.events) raise(event)
                val status = update.status
                if (status != null) mutableUi.update { ui -> ui.copy(follow = ui.follow?.copy(status = status)) }
            }
    }

    private fun raise(event: AlertEvent) {
        notifier.raise(event)
        mutableUi.update {
            it.copy(banner = if (event == AlertEvent.BackOnTrack) null else AlertBanner(event, clock()))
        }
    }

    private suspend fun loadFollow(ref: FollowRef?) {
        if (ref == null) {
            synchronized(engine) { engine.setFollow(null) }
            mutableUi.update { it.copy(follow = null) }
            return
        }
        val loaded =
            when (ref.kind) {
                FollowKind.TRACK -> {
                    repository.loadTrack(ref.id)?.let { t ->
                        t.name to
                            t.segments.flatten().map { LonLat(it.lon, it.lat) }
                    }
                }

                FollowKind.ROUTE -> {
                    repository.loadRoute(ref.id)?.let { r -> r.name to r.points.map { LonLat(it.lon, it.lat) } }
                }
            }
        val line = loaded?.let { TrackFollower(it.second, ref.reversed) }
        if (loaded == null || line == null || !line.usable) {
            store.update { it.copy(follow = null) }
            mutableMessages.tryEmit(UiMessage(R.string.follow_unusable))
            return
        }
        synchronized(engine) { engine.setFollow(line) }
        mutableUi.update { it.copy(follow = FollowUi(ref.kind, ref.id, loaded.first, ref.reversed)) }
    }

    private fun refreshWatched() {
        val lib = library ?: return
        val defaultRadius = settings.settings.value.alerts.proximityRadiusMeters
        val byId = lib.waypoints.associateBy { it.id }
        val places =
            store.state.value.watches.mapNotNull { watch ->
                val w = byId[watch.waypointId] ?: return@mapNotNull null
                WatchedPlace(w.id, w.name, w.lat, w.lon, (watch.radiusMeters ?: defaultRadius).toDouble())
            }
        synchronized(engine) { engine.setWatched(places) }
        mutableUi.update { it.copy(places = places) }
    }

    private companion object {
        /** Alerts need a position every few seconds, not every second: easier on the battery. */
        const val UPDATE_MILLIS = 2_000L
    }
}
