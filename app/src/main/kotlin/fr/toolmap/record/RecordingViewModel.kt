package fr.toolmap.record

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.record.RecorderProfile
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
        }
    }
