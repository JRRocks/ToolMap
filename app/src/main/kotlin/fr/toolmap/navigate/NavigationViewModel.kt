package fr.toolmap.navigate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.geo.Guidance
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.record.Fix
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The place the user is walking to, and the distance and bearing from the current position. */
@HiltViewModel
class NavigationViewModel
    @Inject
    constructor(
        private val location: LocationSource,
    ) : ViewModel() {
        private val mutableTarget = MutableStateFlow<NavTarget?>(null)
        val target: StateFlow<NavTarget?> = mutableTarget.asStateFlow()

        private val mutableFix = MutableStateFlow<Fix?>(null)

        /** The last position received while a target was set. */
        val position: StateFlow<Fix?> = mutableFix.asStateFlow()

        private val mutableGuidance = MutableStateFlow<Guidance?>(null)

        /** `null` without a target or before the first position. */
        val guidance: StateFlow<Guidance?> = mutableGuidance.asStateFlow()

        private val mutableGpsFailed = MutableStateFlow(false)

        /** The GPS could not be started: the target is shown but the distance cannot be computed. */
        val gpsFailed: StateFlow<Boolean> = mutableGpsFailed.asStateFlow()

        private var listening: Job? = null

        fun setTarget(target: NavTarget) {
            mutableTarget.value = target
            mutableGpsFailed.value = false
            mutableGuidance.value = mutableFix.value?.let { guide(it, target) }
            if (listening == null) {
                listening =
                    viewModelScope.launch {
                        location
                            .fixes(UPDATE_MILLIS, 0f)
                            .catch { mutableGpsFailed.value = true }
                            .collect { fix ->
                                mutableFix.value = fix
                                mutableTarget.value?.let { mutableGuidance.value = guide(fix, it) }
                            }
                    }
            }
        }

        fun clearTarget() {
            mutableTarget.value = null
            mutableGuidance.value = null
            listening?.cancel()
            listening = null
        }

        private fun guide(
            fix: Fix,
            target: NavTarget,
        ): Guidance = Navigation.guide(fix.lat, fix.lon, target)

        private companion object {
            const val UPDATE_MILLIS = 1_000L
        }
    }
