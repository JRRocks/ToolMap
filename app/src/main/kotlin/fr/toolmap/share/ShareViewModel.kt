package fr.toolmap.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.R
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.p2p.GroupItemKind
import fr.toolmap.core.p2p.GroupPoint
import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.PointDuration
import fr.toolmap.core.p2p.ShareGeoJson
import fr.toolmap.core.p2p.ShareSettings
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.tracks.SketchRepository
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import fr.toolmap.tracks.MapRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the map layer needs of the group: the two GeoJSON documents. */
class ShareMapData(
    val membersGeoJson: String,
    val trailsGeoJson: String,
    /** The marker images the members need (one per icon, colour and, for initials, name). */
    val markers: List<MemberMarker> = emptyList(),
    /** The waypoints and tracks shared with the group (P27). */
    val itemsGeoJson: String = """{"type":"FeatureCollection","features":[]}""",
)

/** One of your waypoints, traces or sketches, offered to the group (P27). */
class MineItem(
    val kind: GroupItemKind,
    val id: Long,
    val name: String,
    val lengthMeters: Double? = null,
    val timeMs: Long? = null,
    /** Points of a track: past about 4 000, it is simplified to fit. */
    val points: Int = 0,
)

/** A marker image of the map: [key] is the `img` of the members' GeoJSON ([MemberLook.imageKey]). */
class MemberMarker(
    val key: String,
    val look: Int,
    val name: String,
    val picture: ByteArray? = null,
)

@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        private val controller: ShareController,
        private val tracks: TrackRepository,
        private val sketches: SketchRepository,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        val ui: StateFlow<ShareUi> = controller.state

        private val mutableSelected = MutableStateFlow<Long?>(null)

        /** The member whose sheet is open on the map. */
        val selected: StateFlow<Long?> = mutableSelected.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        /**
         * A pairing link was opened from outside (a message, a browser), or the notification touched: the sharing
         * screen should show. Kept until the screen reads it, as the request can come before it starts.
         */
        private val openChannel = Channel<Unit>(Channel.CONFLATED)
        val openRequests: Flow<Unit> = openChannel.receiveAsFlow()

        init {
            controller.load()
            // The app comes on screen: a sharing cut by a forced stop or an update carries on by itself; in a group,
            // the device listens to it, sharing or not (P22.3), from now on and as soon as a group is made or joined.
            val resumed = ShareService.resumeIfInterrupted(context, ui.value.inGroup, ui.value.sharing)
            viewModelScope.launch {
                ui
                    .map { it.inGroup }
                    .distinctUntilChanged()
                    .drop(if (resumed) 1 else 0)
                    .collect { inGroup -> if (inGroup) ShareService.listen(context) }
            }
        }

        /** The notification of the sharing was touched: the sharing screen should show (P22.3). */
        fun requestOpen() {
            openChannel.trySend(Unit)
        }

        /** The GeoJSON of the members for the map, from the current state. */
        fun mapData(state: ShareUi): ShareMapData {
            val now = System.currentTimeMillis()
            val sos = state.sos.map { it.memberId }.toSet()
            return ShareMapData(
                ShareGeoJson.members(state.members, sos, now),
                if (state.settings.showTrails) ShareGeoJson.trails(state.members) else ShareGeoJson.trails(emptyList()),
                state.members
                    // A member that only listens has no marker, unless its alert brought a position (P22.3).
                    .filter { !it.isSelf && it.fix != null && (it.sharing != false || it.id in sos) }
                    .map {
                        MemberMarker(MemberLook.imageKey(it.color, it.name, it.picture), it.color, it.name, it.picture)
                    }
                    .distinctBy { it.key },
                ShareGeoJson.items(state.points, state.members),
            )
        }

        fun select(memberId: Long?) {
            mutableSelected.value = memberId
        }

        fun createGroup() = controller.createGroup()

        fun leaveGroup() {
            ShareService.stop(context)
            controller.leaveGroup()
            mutableSelected.value = null
        }

        fun invite() = controller.invite()

        fun cancelInvitation() = controller.cancelInvitation()

        fun handleCode(text: String) = controller.handleCode(text)

        /** A `toolmap://pair?d=…` link came from another app. */
        fun onLink(link: String) {
            controller.handleCode(link)
            openChannel.trySend(Unit)
        }

        fun clearProblem() = controller.clearProblem()

        fun clearJoined() = controller.clearJoined()

        fun markMessagesRead() = controller.markMessagesRead()

        /** The phone settings that stop the sharing, read now (P21.5 o, c). */
        fun recheckPhone(): List<ShareAlert> = controller.recheckPhone()

        fun clearExcluded() = controller.clearExcluded()

        /** The caller holds the location permission (the service refuses to start without it). */
        fun startSharing() = ShareService.start(context)

        fun stopSharing() {
            controller.setSharing(false)
            ShareService.stop(context)
        }

        fun updateSettings(update: (ShareSettings) -> ShareSettings) = controller.updateSettings(update)

        fun removeMember(memberId: Long) {
            controller.removeMember(memberId)
            if (mutableSelected.value == memberId) mutableSelected.value = null
        }

        fun sendText(text: String) = controller.sendText(text)

        fun startSos(text: String) = controller.startSos(text)

        fun cancelSos() = controller.cancelSos()

        fun acknowledgeSos(memberId: Long) = controller.acknowledgeSos(memberId)

        fun acknowledgeLift(memberId: Long) = controller.acknowledgeLift(memberId)

        /** Keeps the recent positions of [member] as a track of the library. */
        fun saveTrail(member: MemberView) {
            val points = member.trail.map { TrackPoint(it.lat, it.lon, it.altM?.toDouble(), it.timeSec * MS_PER_S) }
            if (points.size < 2) {
                mutableMessages.tryEmit(UiMessage(R.string.share_trail_too_short, listOf(member.name)))
                return
            }
            viewModelScope.launch {
                runCatching {
                    tracks.createTrack(member.name, listOf(points), PathStyle(colorArgb = member.argb))
                }.onSuccess {
                    mutableMessages.tryEmit(UiMessage(R.string.share_trail_saved, listOf(member.name)))
                }.onFailure {
                    mutableMessages.tryEmit(UiMessage(R.string.share_trail_failed, listOf(it.message.orEmpty())))
                }
            }
        }

        // ---- shares of the group (P27) ----------------------------------------------------------------------------

        /** A waypoint for the group at [lat], [lon] (« Nouveau waypoint », at the centre of the map). */
        fun shareWaypoint(
            lat: Double,
            lon: Double,
            name: String,
            duration: PointDuration,
            alert: Boolean,
        ) = controller.createPoint(lat, lon, name, duration = duration, alert = alert) != null

        /** A track drawn for the group; `false` (and told) when it could not be sent. */
        fun shareTrack(
            name: String,
            points: List<LonLat>,
            duration: PointDuration,
            alert: Boolean,
        ): Boolean = sent(controller.createTrack(name, listOf(points), duration, alert))

        /** A sketch drawn for the group; `false` (and told) when it could not be sent. */
        fun shareSketch(
            name: String,
            strokes: List<SketchStroke>,
            duration: PointDuration,
            alert: Boolean,
        ): Boolean = sent(controller.createSketch(name, strokes, duration, alert))

        /** Favorites for the group (P25.4); `false` when the file is too large or there is no group any more. */
        fun shareFavorites(
            name: String,
            text: String,
            anchor: LonLat,
            duration: PointDuration,
        ): Boolean = controller.createFavorites(name, text, anchor, duration) != null

        private fun sent(point: GroupPoint?): Boolean {
            if (point == null) mutableMessages.tryEmit(UiMessage(R.string.group_send_failed, emptyList()))
            return point != null
        }

        fun removeShare(id: Long) = controller.removePoint(id)

        fun renameShare(
            point: GroupPoint,
            name: String,
        ) = controller.renamePoint(point.id, name, point.symbol)

        fun extendShare(
            id: Long,
            duration: PointDuration,
        ) = controller.extendPoint(id, duration)

        /** The area of a share on the map: its point, or the box of its track or sketch. */
        fun areaOf(point: GroupPoint): MapRequest {
            val points =
                when (point.kind) {
                    GroupItemKind.WAYPOINT -> {
                        emptyList()
                    }

                    GroupItemKind.TRACK -> {
                        point.track().flatten()
                    }

                    GroupItemKind.SKETCH -> {
                        point.sketch().flatMap { s ->
                            listOf(s.path.start) + s.path.curves.map { it.end }
                        }.map { it.toLonLat() }
                    }

                    GroupItemKind.FAVORITES -> {
                        emptyList()
                    }
                }.ifEmpty { listOf(LonLat(point.lon, point.lat)) }
            return MapRequest(
                points.minOf {
                    it.lat
                },
                points.minOf { it.lon },
                points.maxOf { it.lat },
                points.maxOf { it.lon },
            )
        }

        /** Your waypoints, traces and sketches, for « Partager un des miens… » (P27). */
        val mine: StateFlow<List<MineItem>> =
            combine(tracks.library, sketches.sketches) { library, saved ->
                library.waypoints.map {
                    MineItem(GroupItemKind.WAYPOINT, it.id, it.name.orEmpty(), timeMs = it.timeMillis)
                } +
                    library.tracks.map {
                        MineItem(
                            GroupItemKind.TRACK,
                            it.id,
                            it.name.orEmpty(),
                            it.distanceMeters,
                            it.createdAt,
                            it.pointCount,
                        )
                    } +
                    saved.map { MineItem(GroupItemKind.SKETCH, it.id, it.sketch.name, timeMs = it.updatedAt) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

        /**
         * Sends [items] of yours to the group, under [name] when there is only one; a track too long is simplified,
         * one that does not fit is told.
         */
        fun shareMine(
            items: List<MineItem>,
            name: String,
            duration: PointDuration,
            alert: Boolean,
        ) {
            viewModelScope.launch {
                var failed = 0
                for (item in items) {
                    val label = if (items.size == 1) name else item.name
                    val sent =
                        runCatching {
                            when (item.kind) {
                                GroupItemKind.WAYPOINT -> {
                                    val w = tracks.library.first().waypoints.first { it.id == item.id }
                                    val alt = w.ele?.let { Math.round(it).toInt() }
                                    controller.createPoint(w.lat, w.lon, label, w.sym.orEmpty(), alt, duration, alert)
                                }

                                GroupItemKind.TRACK -> {
                                    val segments = tracks.loadTrack(item.id)?.segments.orEmpty()
                                    controller.createTrack(
                                        label,
                                        segments.map { s -> s.map { LonLat(it.lon, it.lat) } },
                                        duration,
                                        alert,
                                    )
                                }

                                GroupItemKind.SKETCH -> {
                                    val strokes = sketches.load(item.id)?.sketch?.strokes.orEmpty()
                                    controller.createSketch(label, strokes, duration, alert)
                                }

                                // Favorites are sent from « Partager des favoris », never from this list.
                                GroupItemKind.FAVORITES -> {
                                    null
                                }
                            }
                        }.getOrNull()
                    if (sent == null) failed++
                }
                if (failed > 0) mutableMessages.tryEmit(UiMessage(R.string.group_send_failed, emptyList()))
            }
        }

        /** « Garder dans mes waypoints / traces / croquis »: a copy of the share that stays after the group. */
        fun keepShare(point: GroupPoint) {
            viewModelScope.launch {
                runCatching {
                    when (point.kind) {
                        GroupItemKind.WAYPOINT -> {
                            tracks.createWaypoint(
                                Waypoint(point.lat, point.lon, ele = point.altM?.toDouble(), name = point.name),
                            )
                        }

                        GroupItemKind.TRACK -> {
                            tracks.createTrack(
                                point.name,
                                point.track().map { s -> s.map { TrackPoint(it.lat, it.lon, null, null) } },
                            )
                        }

                        GroupItemKind.SKETCH -> {
                            sketches.save(null, point.name, point.sketch())
                        }

                        // Added by the sheet « Ajouter N favoris ? » instead (P25.4).
                        GroupItemKind.FAVORITES -> {
                            return@launch
                        }
                    }
                }.onSuccess {
                    mutableMessages.tryEmit(UiMessage(R.string.group_item_kept, listOf(point.name)))
                }.onFailure {
                    mutableMessages.tryEmit(UiMessage(R.string.share_trail_failed, listOf(it.message.orEmpty())))
                }
            }
        }

        private companion object {
            const val MESSAGE_BUFFER = 8
            const val STOP_MS = 5_000L
            const val MS_PER_S = 1000L
        }
    }
