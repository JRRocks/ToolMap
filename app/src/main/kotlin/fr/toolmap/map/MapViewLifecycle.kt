package fr.toolmap.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

/**
 * A [MapView] whose lifecycle follows the current [androidx.lifecycle.LifecycleOwner], except that it is kept stopped
 * (no rendering, no location updates) while a full screen [covered] it: the map stays alive under that screen, so coming
 * back does not rebuild it (PF1).
 */
@Composable
fun rememberMapViewWithLifecycle(covered: Boolean = false): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phases = remember(mapView) { MapViewPhases(mapView) }
    SideEffect {
        phases.covered = covered
        phases.follow(lifecycle.currentState)
    }
    DisposableEffect(lifecycle, mapView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> phases.create()
                    Lifecycle.Event.ON_DESTROY -> phases.destroy()
                    else -> phases.follow(lifecycle.currentState)
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Bring the view down to DESTROYED from wherever the owner currently is.
            phases.destroy()
        }
    }
    return mapView
}

/** Where a [MapView] is in its lifecycle, so that it is moved one step at a time whatever the order of the calls. */
private class MapViewPhases(
    private val mapView: MapView,
) {
    var covered = false
    private var created = false
    private var level = STOPPED
    private var destroyed = false

    fun create() {
        if (created || destroyed) return
        created = true
        mapView.onCreate(null)
    }

    /** Moves the view to the phase the owner is in, or to the stopped one while covered. */
    fun follow(state: Lifecycle.State) {
        if (!created || destroyed) return
        val target =
            when {
                covered -> STOPPED
                state.isAtLeast(Lifecycle.State.RESUMED) -> RESUMED
                state.isAtLeast(Lifecycle.State.STARTED) -> STARTED
                else -> STOPPED
            }
        while (level < target) {
            level++
            if (level == STARTED) mapView.onStart() else mapView.onResume()
        }
        while (level > target) {
            if (level == RESUMED) mapView.onPause() else mapView.onStop()
            level--
        }
    }

    fun destroy() {
        if (destroyed) return
        covered = true
        follow(Lifecycle.State.CREATED)
        if (created) mapView.onDestroy()
        destroyed = true
    }

    private companion object {
        const val STOPPED = 0
        const val STARTED = 1
        const val RESUMED = 2
    }
}
