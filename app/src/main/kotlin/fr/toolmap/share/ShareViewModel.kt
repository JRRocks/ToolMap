package fr.toolmap.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.R
import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.ShareGeoJson
import fr.toolmap.core.p2p.ShareSettings
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.tracks.TrackRepository
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the map layer needs of the group: the two GeoJSON documents. */
class ShareMapData(
    val membersGeoJson: String,
    val trailsGeoJson: String,
    /** The marker images the members need (one per icon, colour and, for initials, name). */
    val markers: List<MemberMarker> = emptyList(),
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
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        val ui: StateFlow<ShareUi> = controller.state

        private val mutableSelected = MutableStateFlow<Long?>(null)

        /** The member whose sheet is open on the map. */
        val selected: StateFlow<Long?> = mutableSelected.asStateFlow()

        private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
        val messages: SharedFlow<UiMessage> = mutableMessages.asSharedFlow()

        /** A pairing link was opened from outside (a message, a browser): the sharing screen should show. */
        private val mutableOpenRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val openRequests: SharedFlow<Unit> = mutableOpenRequests.asSharedFlow()

        init {
            controller.load()
            // The app comes on screen: a sharing cut by a forced stop or an update carries on by itself.
            ShareService.resumeIfInterrupted(context, ui.value.inGroup, ui.value.sharing)
        }

        /** The GeoJSON of the members for the map, from the current state. */
        fun mapData(state: ShareUi): ShareMapData {
            val now = System.currentTimeMillis()
            val sos = state.sos.map { it.memberId }.toSet()
            return ShareMapData(
                ShareGeoJson.members(state.members, sos, now),
                if (state.settings.showTrails) ShareGeoJson.trails(state.members) else ShareGeoJson.trails(emptyList()),
                state.members
                    .filter { !it.isSelf && it.fix != null }
                    .map {
                        MemberMarker(MemberLook.imageKey(it.color, it.name, it.picture), it.color, it.name, it.picture)
                    }
                    .distinctBy { it.key },
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
            mutableOpenRequests.tryEmit(Unit)
        }

        fun clearProblem() = controller.clearProblem()

        fun clearJoined() = controller.clearJoined()

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

        fun stopSos() = controller.stopSos()

        fun acknowledgeSos(memberId: Long) = controller.acknowledgeSos(memberId)

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

        private companion object {
            const val MESSAGE_BUFFER = 8
            const val MS_PER_S = 1000L
        }
    }
