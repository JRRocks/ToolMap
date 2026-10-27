package fr.toolmap.record

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.TrackActivity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The recording panel's actions. The recording is the [RecordingController]'s; this only connects the screen to it. */
@HiltViewModel
class RecordingViewModel
    @Inject
    constructor(
        private val controller: RecordingController,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val state: StateFlow<RecordingUi> = controller.state

        init {
            viewModelScope.launch { controller.checkForRecovery() }
        }

        /** The profile the user used last. */
        fun lastProfile(): RecorderProfile = RecorderProfile.fromName(prefs.getString(KEY_PROFILE, null))

        /**
         * The four activities offered first when a recording starts (P29.5): the last ones used, the newest first, then
         * the first ones of AlpineQuest's list.
         */
        fun recentActivities(): List<TrackActivity> =
            (usedActivities() + TrackActivity.entries).distinct().take(RECENT_ACTIVITIES)

        /** Whether an activity was ever chosen: the newest is then chosen in advance. */
        fun hasRecentActivity(): Boolean = usedActivities().isNotEmpty()

        /** [activity] goes first among the recent activities. */
        fun rememberActivity(activity: TrackActivity) {
            val kept = (listOf(activity) + usedActivities()).distinct().take(RECENT_ACTIVITIES)
            prefs.edit { putString(KEY_ACTIVITIES, kept.joinToString(",") { it.name }) }
        }

        /** The activities chosen before, the newest first. */
        private fun usedActivities(): List<TrackActivity> =
            prefs.getString(KEY_ACTIVITIES, null).orEmpty().split(',').mapNotNull { name ->
                TrackActivity.entries.firstOrNull { it.name == name }
            }

        /** The caller holds the location permission. */
        fun start(
            name: String?,
            profile: RecorderProfile,
        ) {
            prefs.edit { putString(KEY_PROFILE, profile.name) }
            RecordingService.start(context, name, profile)
        }

        fun pause() = RecordingService.send(context, RecordingService.ACTION_PAUSE)

        fun resume() = RecordingService.send(context, RecordingService.ACTION_RESUME)

        fun stop() = RecordingService.send(context, RecordingService.ACTION_STOP)

        /** « Reprendre » of the end message: the track that just ended is recorded again. */
        fun reopen(trackId: Long) = RecordingService.reopen(context, trackId)

        /** Carries on with a recording found after a crash. */
        fun resumeRecovered() = RecordingService.restoreRecovered(context)

        fun finishRecovered() {
            viewModelScope.launch { controller.finishRecovered() }
        }

        /** The "recording finished" notice was shown. */
        fun acknowledgeFinished() {
            controller.clearFinished()
        }

        private companion object {
            const val PREFS = "recorder"
            const val KEY_PROFILE = "profile"
            const val KEY_ACTIVITIES = "activities"
            const val RECENT_ACTIVITIES = 4
        }
    }
