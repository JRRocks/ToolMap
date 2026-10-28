package fr.toolmap.navigate

import fr.toolmap.core.geo.Guidance
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.Navigation
import fr.toolmap.core.record.Fix
import fr.toolmap.core.route.GoTo
import fr.toolmap.core.route.GoToProgress
import fr.toolmap.core.route.GoToRules
import fr.toolmap.core.route.GoToWay
import fr.toolmap.core.route.LegResult
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RoutingEngine
import fr.toolmap.core.route.RoutingError
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What « Aller vers un point » shows (P30.3, variant C of `docs/previews/p30-itineraires.html`). */
data class GoToUi(
    val target: NavTarget? = null,
    /** The start sheet is open on [target]: the way is computed for its figures, nothing runs with the screen off. */
    val previewing: Boolean = false,
    /** By the ways BRouter computes, rather than in a straight line. */
    val routed: Boolean = true,
    val profile: String = RoutingProfiles.HIKING,
    val intervalMillis: Long = GoToRules.DEFAULT_INTERVAL_MILLIS,
    /** The last position received while a target was set. */
    val position: Fix? = null,
    /** The straight line to the target; `null` before the first position. */
    val guidance: Guidance? = null,
    val way: GoToWay? = null,
    val progress: GoToProgress? = null,
    /** What is left of the way from the walker, drawn on the map. */
    val remaining: List<TrackPoint>? = null,
    val computing: Boolean = false,
    /** When BRouter was last asked: the next question is due [intervalMillis] later. */
    val askedAtMillis: Long? = null,
    /** Time of the last update (a position each second): the clock of « recalcul dans … ». */
    val nowMillis: Long = 0L,
    /** Why there is no way (no data here, BRouter missing, no path): the straight line is shown instead. */
    val failure: RoutingError? = null,
    /** The walker left the way by this many meters and a new way was asked: the red pill. */
    val rerouteMeters: Double? = null,
    /** When the new way asked after leaving the old one came: the red pill says so for a moment. */
    val reroutedAtMillis: Long? = null,
    val gpsFailed: Boolean = false,
) {
    val arrived: Boolean get() = progress?.arrived == true || guidance?.let(Navigation::arrived) == true

    /** « Recalculer toutes les … min ». */
    val intervalMinutes: Int get() = (intervalMillis / MILLIS_PER_MINUTE).toInt()

    /** When BRouter is asked again if nothing happens before; `null` in a straight line or before the first question. */
    val nextAtMillis: Long? get() = askedAtMillis?.takeIf { routed && !arrived }?.let { it + intervalMillis }

    /** The red pill: while the new way is computed, and a moment after it came. */
    val rerouting: Boolean
        get() =
            rerouteMeters != null &&
                (reroutedAtMillis == null || nowMillis - reroutedAtMillis < REROUTED_SHOWN_MILLIS)

    companion object {
        const val REROUTED_SHOWN_MILLIS = 8_000L
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** What the navigation needs of Android: the foreground service that keeps it with the screen off, the vibration. */
interface GoToPlatform {
    fun startService()

    fun stopService()

    /** The walker left the way (« Vibrer en quittant le chemin »). */
    fun vibrate()
}

/**
 * « Aller vers un point » for the whole process (P30.3, ADR-093): the GPS each second while a target is set, the way
 * from the position asked of BRouter ([GoTo.needsWay]: every [GoToRules.intervalMillis], and at once off the way),
 * what remains of it, and the straight line of before when there is no BRouter, no data or no way. Outside the start
 * sheet the foreground service keeps it running with the screen off.
 */
class GoToController(
    private val location: LocationSource,
    private val engine: RoutingEngine,
    private val settings: SettingsStore,
    private val platform: GoToPlatform,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableUi = MutableStateFlow(GoToUi())
    val ui: StateFlow<GoToUi> = mutableUi.asStateFlow()

    private var listening: Job? = null
    private var computing: Job? = null

    /** The navigation the start sheet stands in front of, given back when it is closed without « Partir ». */
    private var resume: GoToUi? = null

    /** Off the way at the last position: the vibration is given once, as the walker leaves it. */
    private var wasOff = false

    /** « Aller vers » of a search result, of the centre, of a member…: straight away, with the saved choices. */
    fun start(target: NavTarget) {
        val p = settings.settings.value.placemarks
        resume = null
        begin(target, p.goToRouted, p.goToProfile, previewing = false)
    }

    /** « Aller vers ce point » of a waypoint: the start sheet, the way computed behind it with the saved choices. */
    fun preview(target: NavTarget) {
        val current = mutableUi.value
        if (!current.previewing) resume = current.takeIf { it.target != null }
        val p = settings.settings.value.placemarks
        begin(target, p.goToRouted, p.goToProfile, previewing = true)
    }

    /** A choice of the start sheet: by the ways or not, the profile, the interval; the way follows a new choice. */
    fun choose(
        routed: Boolean,
        profile: String,
        intervalMinutes: Int,
    ) {
        val ui = mutableUi.value
        if (ui.target == null) return
        val interval = GoToRules.of(intervalMinutes, settings.settings.value.placemarks.goToOffRouteMeters)
        val newWay = routed != ui.routed || profile != ui.profile
        if (newWay) computing?.cancel()
        mutableUi.update {
            val chosen = it.copy(routed = routed, profile = profile, intervalMillis = interval.intervalMillis)
            if (newWay) chosen.withoutWay() else chosen
        }
        if (newWay) mutableUi.value.position?.let(::onFix)
    }

    /** « Partir »: the choices are kept for the next time, and the navigation goes on with the screen off. */
    fun go() {
        val ui = mutableUi.value
        if (ui.target == null || !ui.previewing) return
        resume = null
        settings.update { s ->
            s.copy(
                placemarks =
                    s.placemarks.copy(
                        goToRouted = ui.routed,
                        goToProfile = ui.profile,
                        goToIntervalMinutes = ui.intervalMinutes,
                    ),
            )
        }
        mutableUi.update { it.copy(previewing = false) }
        platform.startService()
    }

    /** The start sheet closed without « Partir »: the navigation it stood in front of comes back, if any. */
    fun cancelPreview() {
        if (!mutableUi.value.previewing) return
        val back = resume
        resume = null
        val target = back?.target
        if (target != null) begin(target, back.routed, back.profile, previewing = false) else stop()
    }

    /** « Arrêter », in the pill or the notification. */
    fun stop() {
        listening?.cancel()
        listening = null
        computing?.cancel()
        computing = null
        resume = null
        wasOff = false
        mutableUi.value = GoToUi(position = mutableUi.value.position)
        platform.stopService()
    }

    private fun begin(
        target: NavTarget,
        routed: Boolean,
        profile: String,
        previewing: Boolean,
    ) {
        computing?.cancel()
        wasOff = false
        val fix = mutableUi.value.position
        mutableUi.value =
            GoToUi(
                target = target,
                previewing = previewing,
                routed = routed,
                profile = profile,
                intervalMillis = rules().intervalMillis,
                position = fix,
                guidance = fix?.let { Navigation.guide(it.lat, it.lon, target) },
                nowMillis = clock(),
            )
        if (listening == null) {
            listening =
                scope.launch {
                    location
                        .fixes(UPDATE_MILLIS, 0f)
                        .catch { mutableUi.update { it.copy(gpsFailed = true) } }
                        .collect { onFix(it) }
                }
        } else {
            fix?.let(::onFix)
        }
        if (previewing) platform.stopService() else platform.startService()
    }

    private fun onFix(fix: Fix) {
        val now = clock()
        val ui = mutableUi.value
        val target = ui.target ?: return
        val rules = rules().copy(intervalMillis = ui.intervalMillis)
        val way = ui.way
        val progress = way?.let { GoTo.progress(it, fix.lat, fix.lon, rules, ui.progress?.alongMeters) }
        val off = ui.routed && progress?.offRoute == true
        if (off && !wasOff && !ui.previewing && settings.settings.value.placemarks.goToVibrate) platform.vibrate()
        wasOff = off
        val shownOver = ui.reroutedAtMillis != null && now - ui.reroutedAtMillis >= GoToUi.REROUTED_SHOWN_MILLIS
        val next =
            ui.copy(
                position = fix,
                guidance = Navigation.guide(fix.lat, fix.lon, target),
                progress = progress,
                remaining = if (way != null && progress != null) way.remaining(progress.alongMeters) else null,
                nowMillis = now,
                gpsFailed = false,
                rerouteMeters = if (shownOver) null else ui.rerouteMeters,
                reroutedAtMillis = if (shownOver) null else ui.reroutedAtMillis,
            )
        mutableUi.value = next
        val asking =
            ui.routed &&
                computing?.isActive != true &&
                !next.arrived &&
                GoTo.needsWay(way, progress, ui.askedAtMillis, now, rules)
        if (asking) ask(fix, target, ui.profile, if (off) progress?.offMeters else null)
    }

    /** Asks BRouter for the way from [fix] to [target]; [offMeters] when the walker left the old one. */
    private fun ask(
        fix: Fix,
        target: NavTarget,
        profile: String,
        offMeters: Double?,
    ) {
        val asked = clock()
        mutableUi.update {
            it.copy(
                computing = true,
                askedAtMillis = asked,
                rerouteMeters = offMeters ?: it.rerouteMeters,
                reroutedAtMillis = if (offMeters != null) null else it.reroutedAtMillis,
            )
        }
        computing =
            scope.launch {
                val result =
                    try {
                        engine.route(RouteStop(fix.lat, fix.lon), RouteStop(target.lat, target.lon), profile)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LegResult.Failed(RoutingError.OTHER, e.toString())
                    }
                val done = clock()
                mutableUi.update { ui ->
                    // Another target or choice meanwhile: its own way is on the way.
                    if (ui.target != target || ui.profile != profile || !ui.routed) return@update ui
                    answered(ui, result, fix, done)
                }
            }
    }

    private fun answered(
        ui: GoToUi,
        result: LegResult,
        from: Fix,
        doneMillis: Long,
    ): GoToUi {
        val points = (result as? LegResult.Ok)?.points.orEmpty()
        if (result is LegResult.Failed || points.size < 2) {
            val error = (result as? LegResult.Failed)?.error ?: RoutingError.NO_PATH
            // Without BRouter or its data, the straight line of before; otherwise the old way stays until a new one.
            val keep = error != RoutingError.NO_DATA && error != RoutingError.NOT_INSTALLED
            val failed = ui.copy(computing = false, failure = error, rerouteMeters = null, reroutedAtMillis = null)
            return if (keep) failed else failed.copy(way = null, progress = null, remaining = null)
        }
        val ok = result as LegResult.Ok
        val way = GoToWay(points, ui.profile, ok.timeMillis, doneMillis, from = TrackPoint(from.lat, from.lon))
        val at = ui.position ?: from
        val progress = GoTo.progress(way, at.lat, at.lon, rules().copy(intervalMillis = ui.intervalMillis))
        wasOff = false
        return ui.copy(
            way = way,
            progress = progress,
            remaining = progress?.let { way.remaining(it.alongMeters) } ?: points,
            computing = false,
            failure = null,
            reroutedAtMillis = if (ui.rerouteMeters != null) doneMillis else null,
        )
    }

    private fun rules(): GoToRules = settings.settings.value.placemarks.goToRules

    private fun GoToUi.withoutWay() =
        copy(
            way = null,
            progress = null,
            remaining = null,
            computing = false,
            askedAtMillis = null,
            failure = null,
            rerouteMeters = null,
            reroutedAtMillis = null,
        )

    private companion object {
        const val UPDATE_MILLIS = 1_000L
    }
}
