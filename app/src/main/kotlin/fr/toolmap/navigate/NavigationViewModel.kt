package fr.toolmap.navigate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.geo.Guidance
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.record.Fix
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The place the user is walking to, and how to get there: the screen's side of [GoToController], which keeps the
 * navigation for the whole process (P30.3), with the screen off too.
 */
@HiltViewModel
class NavigationViewModel
    @Inject
    constructor(
        private val goTo: GoToController,
    ) : ViewModel() {
        /** The whole state of « Aller vers un point »: the way, what remains of it, the start sheet. */
        val ui: StateFlow<GoToUi> = goTo.ui

        val target: StateFlow<NavTarget?> = derived { it.target }

        /** The last position received while a target was set. */
        val position: StateFlow<Fix?> = derived { it.position }

        /** The straight line to the target; `null` without a target or before the first position. */
        val guidance: StateFlow<Guidance?> = derived { it.guidance }

        /** The GPS could not be started: the target is shown but the distance cannot be computed. */
        val gpsFailed: StateFlow<Boolean> = derived { it.gpsFailed }

        /** « Aller vers » straight away, with the choices of the settings. */
        fun setTarget(target: NavTarget) = goTo.start(target)

        /** « Aller vers ce point » of a waypoint: the start sheet first (variant C). */
        fun askTarget(target: NavTarget) = goTo.preview(target)

        fun choose(
            routed: Boolean,
            profile: String,
            intervalMinutes: Int,
        ) = goTo.choose(routed, profile, intervalMinutes)

        /** « Partir » of the start sheet. */
        fun go() = goTo.go()

        /** The start sheet closed without « Partir ». */
        fun cancelStart() = goTo.cancelPreview()

        fun clearTarget() = goTo.stop()

        private fun <T> derived(pick: (GoToUi) -> T): StateFlow<T> =
            goTo.ui.map(pick).stateIn(viewModelScope, SharingStarted.Eagerly, pick(goTo.ui.value))
    }
