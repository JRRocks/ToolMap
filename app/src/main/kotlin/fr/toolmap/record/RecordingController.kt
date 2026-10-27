package fr.toolmap.record

import fr.toolmap.core.record.Fix
import fr.toolmap.core.record.FixDecision
import fr.toolmap.core.record.LiveStats
import fr.toolmap.core.record.RecorderCore
import fr.toolmap.core.record.RecorderProfile
import fr.toolmap.core.record.RecorderState
import fr.toolmap.core.record.RecordingSession
import fr.toolmap.core.record.SignalWatch
import fr.toolmap.core.settings.RecorderSettings
import fr.toolmap.core.track.NameDates
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.tracks.RecordingSessionStore
import fr.toolmap.data.tracks.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the recording panel and the notification show. */
data class RecordingUi(
    val phase: RecorderState = RecorderState.IDLE,
    val trackId: Long? = null,
    val profile: RecorderProfile = RecorderProfile.WALK,
    val live: LiveStats = LiveStats(),
    /** A recording the process died in the middle of: the user chooses to carry on or to keep what there is. */
    val recoverable: RecordingSession? = null,
    /** The GPS could not be started (no permission, no receiver). */
    val gpsUnavailable: Boolean = false,
    /** Points could not be written to the database; they are kept in memory and retried. */
    val saveFailing: Boolean = false,
    /** The track of the recording that just ended. */
    val finishedTrackId: Long? = null,
    /** No fix for a minute while recording, with « Avertissement de perte de signal » on (P11.8). */
    val signalLost: Boolean = false,
)

/**
 * Records the GPS track into the library as it goes: fixes go through [RecorderCore], accepted points are written in
 * small batches (every few points and every few seconds), and a small session file says which track is being
 * recorded so that a killed process loses at most the last batch (ADR-019). The foreground service only hosts it.
 */
class RecordingController(
    private val repository: TrackRepository,
    private val store: RecordingSessionStore,
    private val location: LocationSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val flushEveryPoints: Int = FLUSH_POINTS,
    private val flushEveryMillis: Long = FLUSH_MILLIS,
    /** The « Track recorder » settings, read at each event so that a change applies to the recording in progress. */
    private val options: () -> RecorderSettings = { RecorderSettings() },
    /** Name of a recording started without one, from its start time (« Sortie du 22 oct. 2026 à 17:02 »). */
    private val defaultName: (Long) -> String = NameDates::format,
) {
    private val mutex = Mutex()
    private var core = RecorderCore()
    private var trackId: Long? = null
    private var startedAt = 0L
    private var profile = RecorderProfile.WALK
    private var collector: Job? = null
    private var ticker: Job? = null
    private val pending = ArrayList<TrackPoint>()
    private var pendingNewSegment = false
    private var lastFlush = 0L
    private val signal = SignalWatch()

    /** The statistics of the segment in progress, when « Réinitialiser les statistiques du segment » is on. */
    private var segmentBase: LiveStats? = null

    /** The recording that just ended, which « Reprendre » can open again (P29.5). */
    private var lastFinished: RecordingSession? = null

    private val mutableState = MutableStateFlow(RecordingUi())
    val state: StateFlow<RecordingUi> = mutableState.asStateFlow()

    /** Starts a new recording; returns the id of the track it fills. */
    suspend fun start(
        name: String?,
        profile: RecorderProfile,
    ): Long =
        mutex.withLock {
            check(core.state == RecorderState.IDLE && mutableState.value.recoverable == null) { "already recording" }
            lastFinished = null
            val now = clock()
            this.profile = profile
            core = RecorderCore(profile.settings)
            core.start(now)
            val id = repository.createTrack(name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName(now))
            trackId = id
            startedAt = now
            pending.clear()
            lastFlush = now
            segmentBase = null
            mutableState.value = RecordingUi(profile = profile, trackId = id)
            writeSession()
            listen()
            publish()
            id
        }

    suspend fun pause() {
        mutex.withLock {
            if (core.state != RecorderState.RECORDING) return
            core.pause(clock())
            stopListening()
            flush()
            writeSession()
            publish()
        }
    }

    suspend fun resume() {
        mutex.withLock {
            if (core.state != RecorderState.PAUSED) return
            core.resume(clock())
            lastFlush = clock()
            writeSession()
            listen()
            publish()
        }
    }

    /** Ends the recording; returns the finished track, or `null` when none was running. */
    suspend fun stop(): Long? =
        mutex.withLock {
            val id = trackId ?: return null
            core.stop(clock())
            stopListening()
            flush()
            lastFinished =
                RecordingSession(
                    trackId = id,
                    startedAtMillis = startedAt,
                    activeMillis = core.stats(clock()).elapsedMillis,
                    paused = true,
                    savedAtMillis = clock(),
                    profile = profile.name,
                )
            repository.finishTrack(id)
            store.clear()
            trackId = null
            segmentBase = null
            core = RecorderCore()
            mutableState.value = RecordingUi(profile = profile, finishedTrackId = id)
            id
        }

    /**
     * « Reprendre » of the end message (P29.5): the track that just ended is recorded again, with its figures, the next
     * fix opening a new segment (the way walked meanwhile is not part of it). `false` when [id] is not the recording
     * that just ended, when the track is gone or when a recording runs.
     */
    suspend fun reopen(id: Long): Boolean =
        mutex.withLock {
            val last = lastFinished?.takeIf { it.trackId == id } ?: return false
            if (core.state != RecorderState.IDLE || trackId != null) return false
            val track = repository.loadTrack(id) ?: return false
            lastFinished = null
            profile = RecorderProfile.fromName(last.profile)
            core = RecorderCore(profile.settings)
            core.replay(track.segments, last.activeMillis)
            core.resume(clock())
            trackId = id
            startedAt = last.startedAtMillis
            pending.clear()
            lastFlush = clock()
            segmentBase = null
            mutableState.value = RecordingUi(profile = profile, trackId = id)
            writeSession()
            listen()
            publish()
            true
        }

    /** Looks for a recording the process left unfinished; the result is in [state]. */
    suspend fun checkForRecovery() {
        mutex.withLock {
            if (core.state != RecorderState.IDLE) return
            val session = store.read()
            if (session == null) {
                mutableState.value = mutableState.value.copy(recoverable = null)
                return
            }
            if (repository.loadTrack(session.trackId) == null) {
                store.clear()
                mutableState.value = mutableState.value.copy(recoverable = null)
                return
            }
            mutableState.value = mutableState.value.copy(recoverable = session)
        }
    }

    /** Carries on with the unfinished recording: the next fix opens a new segment. */
    suspend fun resumeRecovered() {
        mutex.withLock {
            val session = mutableState.value.recoverable ?: return
            val track = repository.loadTrack(session.trackId)
            if (track == null) {
                store.clear()
                mutableState.value = mutableState.value.copy(recoverable = null)
                return
            }
            profile = RecorderProfile.fromName(session.profile)
            core = RecorderCore(profile.settings)
            core.replay(track.segments, session.activeMillis)
            core.resume(clock())
            trackId = session.trackId
            startedAt = session.startedAtMillis
            pending.clear()
            lastFlush = clock()
            mutableState.value = RecordingUi(profile = profile, trackId = trackId)
            writeSession()
            listen()
            publish()
        }
    }

    /** Keeps what was recorded and closes the unfinished recording. */
    suspend fun finishRecovered() {
        mutex.withLock {
            val session = mutableState.value.recoverable ?: return
            repository.finishTrack(session.trackId)
            store.clear()
            mutableState.value = RecordingUi(profile = profile, finishedTrackId = session.trackId)
        }
    }

    /** The "recording finished" notice was shown. */
    fun clearFinished() {
        mutableState.value = mutableState.value.copy(finishedTrackId = null)
    }

    private fun listen() {
        stopListening()
        signal.start(clock())
        val settings = profile.settings
        collector =
            scope.launch {
                location
                    .fixes(settings.minIntervalMillis, 0f)
                    .catch { mutableState.value = mutableState.value.copy(gpsUnavailable = true) }
                    .collect { fix -> mutex.withLock { handle(fix) } }
            }
        ticker =
            scope.launch {
                while (isActive) {
                    delay(TICK_MILLIS)
                    mutex.withLock {
                        if (core.state == RecorderState.RECORDING) {
                            signal.check(clock())?.let(::applySignal)
                            if (clock() - lastFlush >= flushEveryMillis) flush()
                            publish()
                        }
                    }
                }
            }
    }

    private fun stopListening() {
        collector?.cancel()
        ticker?.cancel()
        collector = null
        ticker = null
    }

    private suspend fun handle(fix: Fix) {
        if (core.state != RecorderState.RECORDING) return
        signal.onFix(clock())?.let(::applySignal)
        // Taken before the fix: the new segment's own statistics then include its first point.
        val before = core.stats(clock())
        when (val decision = core.onFix(fix)) {
            is FixDecision.Accepted -> {
                if (decision.newSegment && pending.isNotEmpty()) flush()
                if (pending.isEmpty()) pendingNewSegment = decision.newSegment
                pending += decision.point
                if (decision.newSegment && options().resetStatsOnSegment) segmentBase = before
                if (pending.size >= flushEveryPoints) flush()
            }

            is FixDecision.Rejected -> {
                return
            }
        }
        publish()
    }

    /** Writes the waiting points; on failure they stay waiting and the next flush tries again. */
    private suspend fun flush() {
        val id = trackId ?: return
        lastFlush = clock()
        if (pending.isEmpty()) return
        val batch = pending.toList()
        val newSegment = pendingNewSegment
        try {
            repository.appendPoints(id, batch, newSegment)
            pending.clear()
            pendingNewSegment = false
            writeSession()
            if (mutableState.value.saveFailing) mutableState.value = mutableState.value.copy(saveFailing = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(saveFailing = true)
        }
    }

    private fun applySignal(change: SignalWatch.Change) {
        val warn = options().lostSignalWarning
        mutableState.value = mutableState.value.copy(signalLost = warn && change == SignalWatch.Change.LOST)
    }

    /** The statistics of the segment since [base]: distances, times and counts start again from it. */
    private fun since(
        total: LiveStats,
        base: LiveStats,
    ): LiveStats =
        total.copy(
            pointCount = total.pointCount - base.pointCount,
            segmentCount = total.segmentCount - base.segmentCount,
            distanceMeters = total.distanceMeters - base.distanceMeters,
            elapsedMillis = total.elapsedMillis - base.elapsedMillis,
            movingMillis = total.movingMillis - base.movingMillis,
            ascentMeters = total.ascentMeters - base.ascentMeters,
            descentMeters = total.descentMeters - base.descentMeters,
        )

    private fun writeSession() {
        val id = trackId ?: return
        val now = clock()
        runCatching {
            store.write(
                RecordingSession(
                    trackId = id,
                    startedAtMillis = startedAt,
                    activeMillis = core.stats(now).elapsedMillis,
                    paused = core.state != RecorderState.RECORDING,
                    savedAtMillis = now,
                    profile = profile.name,
                ),
            )
        }
    }

    private fun publish() {
        val total = core.stats(clock())
        mutableState.value =
            mutableState.value.copy(
                phase = core.state,
                trackId = trackId,
                profile = profile,
                live = segmentBase?.let { since(total, it) } ?: total,
            )
    }

    companion object {
        const val FLUSH_POINTS = 10
        const val FLUSH_MILLIS = 15_000L
        private const val TICK_MILLIS = 1_000L
    }
}
