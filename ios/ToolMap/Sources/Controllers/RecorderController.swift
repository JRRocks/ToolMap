import CoreLocation
import Foundation
import Observation
import ToolMapCore
import UIKit

/// The track recorder (Android `record/RecordingController`, `RecordingViewModel` and `RecordingService`, L4, T4): the
/// fixes go through the core's `RecorderCore`, the kept points are written to the library in small batches (every ten
/// points and every 15 s), and a small session file says which track is being recorded, so that an app killed by iOS
/// loses at most the last batch and offers to carry on at the next launch (ADR-019). The receiver keeps running screen
/// off while it records (`LocationService.keepRunningInBackground`).
///
/// It also draws the previous positions (« Afficher les positions précédentes ») and the end of the recording not yet
/// written (`TrailLayers`), and keeps the location service in line with the settings (altitude, background).
///
/// Everything runs on the main thread but the writes, which go one after the other on `io`.
@Observable
final class RecorderController {
    @ObservationIgnored unowned let app: AppModel

    private(set) var phase: RecorderState = .idle
    /// The track the points go to, while recording or paused.
    private(set) var trackId: Int64?
    private(set) var profile: RecorderProfile = .walk
    /// The figures of the recording, or of its segment with « Réinitialiser les statistiques du segment ».
    private(set) var live = LiveStats()
    /// A recording the app was killed in the middle of: the user carries on with it or keeps what there is.
    private(set) var recoverable: RecordingSession?
    /// The recovery question was put away without an answer.
    private(set) var recoveryDismissed = false
    /// Points could not be written to the library; they are kept and tried again.
    private(set) var saveFailing = false
    /// No fix for a minute while recording, with « Avertissement de perte de signal » on.
    private(set) var signalLost = false
    /// No fix for a minute while recording, whatever the setting: the chip turns red.
    private(set) var noSignal = false
    /// The track of the recording that just ended: the message offers « Reprendre » for a while.
    private(set) var finishedTrackId: Int64?

    @ObservationIgnored private var core = RecorderCore()
    @ObservationIgnored private var startedAt: Int64 = 0
    /// Accepted points waiting to be written, one batch per segment start.
    @ObservationIgnored private var batches: [PointBatch] = []
    /// The batches being written now.
    @ObservationIgnored private var inFlight: [PointBatch] = []
    @ObservationIgnored private var lastFlush: Int64 = 0
    /// The last point written to the library, where the line of the waiting points starts.
    @ObservationIgnored private var lastStored: TrackPoint?
    @ObservationIgnored private var segmentBase: LiveStats?
    /// The recording that just ended, which « Reprendre » opens again (P29.5).
    @ObservationIgnored private var lastFinished: RecordingSession?
    @ObservationIgnored private let signal = SignalWatch()
    @ObservationIgnored private let trail = PositionTrail()
    @ObservationIgnored private var ticker: Timer?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var pausedByDashboard = false
    @ObservationIgnored private let io = DispatchQueue(label: "fr.toolmap.recorder", qos: .utility)
    @ObservationIgnored private let store = RecordingSessionStore(
        AppFiles.support.appendingPathComponent("recording_session.json")
    )
    /// `-ToolMapDemo YES`: a recording made up for the screenshots, without GPS, nothing written.
    @ObservationIgnored private let demo: Bool
    @ObservationIgnored private var demoActiveBefore: Int64 = 0
    @ObservationIgnored private var demoActiveSince: Int64?

    static let flushPoints = 10
    static let flushMillis: Int64 = 15_000
    static let recentActivityCount = 4
    private static let listenerName = "recorder"
    private static let profileKey = "ToolMapRecorderProfile"
    private static let activitiesKey = "ToolMapRecorderActivities"

    /// Accepted points to append to the track in one call; `newSegment` starts a segment with the first of them.
    private struct PointBatch {
        var points: [TrackPoint]
        let newSegment: Bool
    }

    init(app: AppModel) {
        self.app = app
        demo = UserDefaults.standard.bool(forKey: "ToolMapDemo")
        // Created while a view is drawn (the chip of the map): the observed state changes after that.
        DispatchQueue.main.async { [weak self] in
            self?.setUp()
        }
    }

    var isRecording: Bool { phase != .idle }

    var isPaused: Bool { phase == .paused }

    /// The GPS cannot run: no permission, or Location Services off.
    var gpsUnavailable: Bool {
        isRecording && !demo && app.location.isDenied
    }

    /// The chip turns red: the GPS is missing or the points cannot be written.
    var hasWarning: Bool {
        gpsUnavailable || saveFailing || noSignal
    }

    /// The library's repository, shared with the list and the map layers (`LibraryController.repository`).
    private var repository: TrackRepository? {
        app.library.repository
    }

    // MARK: - Start dialog

    /// The precision of the points used last.
    func lastProfile() -> RecorderProfile {
        RecorderProfile.fromName(UserDefaults.standard.string(forKey: RecorderController.profileKey))
    }

    /// The four activities offered first (P29.5): the last ones used, the newest first, then the first ones of
    /// AlpineQuest's list.
    func recentActivities() -> [TrackActivity] {
        var list: [TrackActivity] = []
        let candidates = usedActivities() + TrackActivity.allCases
        for activity in candidates where !list.contains(activity) {
            list.append(activity)
            if list.count == RecorderController.recentActivityCount { break }
        }
        return list
    }

    /// The activity chosen in advance: the last one used, none before any.
    func initialActivity() -> TrackActivity? {
        usedActivities().first
    }

    private func usedActivities() -> [TrackActivity] {
        let text = UserDefaults.standard.string(forKey: RecorderController.activitiesKey) ?? ""
        return text.split(separator: ",").compactMap { TrackActivity(rawValue: String($0)) }
    }

    private func rememberActivity(_ activity: TrackActivity) {
        var kept: [TrackActivity] = [activity]
        for other in usedActivities() where !kept.contains(other) {
            kept.append(other)
        }
        let names = kept.prefix(RecorderController.recentActivityCount).map(\.rawValue)
        UserDefaults.standard.set(names.joined(separator: ","), forKey: RecorderController.activitiesKey)
    }

    /// « Démarrer » of « Enregistrer une trace »: the name (the date one when left empty), the precision, the activity
    /// put before the name. The position is asked first (« Pendant l'utilisation », the exact position); `refused` gets
    /// the message when it is not given.
    func begin(name: String, profile: RecorderProfile, activity: TrackActivity?, refused: @escaping (String) -> Void) {
        if let activity {
            rememberActivity(activity)
        }
        UserDefaults.standard.set(profile.rawValue, forKey: RecorderController.profileKey)
        let base = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let plain = base.isEmpty ? RecordText.defaultName(RecorderController.now()) : base
        let finalName = activity.map { RecordText.named($0, plain) } ?? plain
        if demo {
            startDemo()
            return
        }
        let location = app.location
        location.requestAuthorization { [weak self] granted in
            guard let self else { return }
            guard granted else {
                refused(RecordText.permissionDenied)
                return
            }
            location.requestFullAccuracy { [weak self] precise in
                guard let self else { return }
                guard precise else {
                    refused(RecordText.permissionDenied)
                    return
                }
                self.start(finalName, profile)
                location.requestAlways()
            }
        }
    }

    // MARK: - Recording

    private func start(_ name: String, _ chosen: RecorderProfile) {
        guard phase == .idle, trackId == nil, recoverable == nil, let repository else { return }
        let id: Int64
        do {
            id = try repository.createTrack(name)
        } catch {
            saveFailing = true
            return
        }
        let now = RecorderController.now()
        lastFinished = nil
        finishedTrackId = nil
        profile = chosen
        core = RecorderCore(chosen.settings)
        try? core.start(now)
        trackId = id
        startedAt = now
        batches = []
        lastStored = nil
        lastFlush = now
        segmentBase = nil
        saveFailing = false
        signalLost = false
        noSignal = false
        writeSession()
        listen()
        publish()
        updateTail()
    }

    func pause() {
        if demo {
            demoPause()
            return
        }
        guard phase == .recording, let id = trackId else { return }
        core.pause(RecorderController.now())
        stopListening()
        flush(id)
        writeSession()
        publish()
    }

    func resume() {
        if demo {
            demoResume()
            return
        }
        guard phase == .paused, trackId != nil else { return }
        core.resume(RecorderController.now())
        lastFlush = RecorderController.now()
        writeSession()
        listen()
        publish()
    }

    /// Ends the recording: the waiting points are written, the figures of the track computed again, and the message
    /// offers « Reprendre » (a stop by mistake is undone without a question first).
    func stop() {
        if demo {
            demoStop()
            return
        }
        guard phase != .idle, let id = trackId else { return }
        let now = RecorderController.now()
        core.stop(now)
        stopListening()
        lastFinished = RecordingSession(
            trackId: id,
            startedAtMillis: startedAt,
            activeMillis: core.stats(now).elapsedMillis,
            paused: true,
            savedAtMillis: now,
            profile: profile.rawValue
        )
        trackId = nil
        phase = .idle
        core = RecorderCore()
        segmentBase = nil
        signalLost = false
        noSignal = false
        live = LiveStats()
        // After the batch being written, if any (`io` is serial): the points that wait, then the figures of the track.
        let remaining = batches
        batches = []
        let repository = self.repository
        let store = self.store
        io.async {
            for batch in remaining {
                do {
                    try repository?.appendPoints(id, batch.points, newSegment: batch.newSegment)
                } catch {
                    break
                }
            }
            try? repository?.finishTrack(id)
            store.clear()
        }
        updateTail()
        announceFinished(id)
    }

    /// « Reprendre » of the end message: the track that just ended is recorded again, with its figures; the next fix
    /// opens a new segment (the way walked meanwhile is not part of it).
    func reopen(_ id: Int64) {
        if demo {
            finishedTrackId = nil
            startDemo()
            return
        }
        guard let last = lastFinished, last.trackId == id, phase == .idle, trackId == nil else { return }
        lastFinished = nil
        finishedTrackId = nil
        restore(last)
    }

    /// « Reprendre l'enregistrement »: carries on with the recording found at launch.
    func resumeRecovered() {
        guard let session = recoverable else { return }
        recoverable = nil
        restore(session)
    }

    /// « Garder la trace telle quelle »: closes the recording found at launch.
    func finishRecovered() {
        guard let session = recoverable else { return }
        recoverable = nil
        lastFinished = session
        let repository = self.repository
        let store = self.store
        io.async {
            try? repository?.finishTrack(session.trackId)
            store.clear()
        }
        announceFinished(session.trackId)
    }

    /// The recovery question was put away: it comes back at the next launch.
    func dismissRecovery() {
        recoveryDismissed = true
    }

    /// The end message has been read.
    func clearFinished() {
        finishedTrackId = nil
    }

    private func announceFinished(_ id: Int64) {
        finishedTrackId = id
        app.say(RecordText.finished, action: RecordText.resume) { [weak self] in
            self?.reopen(id)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard let self, self.finishedTrackId == id else { return }
            self.finishedTrackId = nil
        }
    }

    /// Opens `session`'s track again: its points rebuild the figures, and the recording goes on in a new segment.
    private func restore(_ session: RecordingSession) {
        guard let repository else { return }
        let store = self.store
        io.async { [weak self] in
            let found: Track?
            do {
                found = try repository.loadTrack(session.trackId)
            } catch {
                return
            }
            guard let track = found else {
                store.clear()
                return
            }
            DispatchQueue.main.async {
                self?.carryOn(session, track)
            }
        }
    }

    private func carryOn(_ session: RecordingSession, _ track: Track) {
        guard phase == .idle, trackId == nil else { return }
        let chosen = RecorderProfile.fromName(session.profile)
        let replayed = RecorderCore(chosen.settings)
        do {
            try replayed.replay(track.segments, activeMillis: session.activeMillis)
        } catch {
            return
        }
        let now = RecorderController.now()
        replayed.resume(now)
        profile = chosen
        core = replayed
        trackId = session.trackId
        startedAt = session.startedAtMillis
        batches = []
        inFlight = []
        lastStored = track.segments.last?.last
        lastFlush = now
        segmentBase = nil
        saveFailing = false
        writeSession()
        listen()
        publish()
        updateTail()
    }

    /// At launch: a session file left by a recording the app was killed in the middle of.
    private func checkForRecovery() {
        guard let repository else { return }
        let store = self.store
        io.async { [weak self] in
            guard let session = store.read() else { return }
            var exists = false
            do {
                exists = try repository.loadTrack(session.trackId) != nil
            } catch {
                return
            }
            if !exists {
                store.clear()
                return
            }
            DispatchQueue.main.async {
                guard let self, self.phase == .idle, self.trackId == nil else { return }
                self.recoverable = session
            }
        }
    }

    // MARK: - Fixes

    private func listen() {
        signal.start(RecorderController.now())
        app.location.keepRunningInBackground(
            RecorderController.listenerName,
            true,
            accuracy: RecorderController.accuracy(profile)
        )
        startTicker()
    }

    private func stopListening() {
        app.location.keepRunningInBackground(RecorderController.listenerName, false)
        ticker?.invalidate()
        ticker = nil
    }

    /// What the receiver is asked for each precision: the densest wants the best, « Économie » ten metres.
    private static func accuracy(_ profile: RecorderProfile) -> CLLocationAccuracy {
        switch profile {
        case .precise: kCLLocationAccuracyBestForNavigation
        case .walk, .bike: kCLLocationAccuracyBest
        case .saver: kCLLocationAccuracyNearestTenMeters
        }
    }

    private func received(_ location: CLLocation) {
        guard location.horizontalAccuracy >= 0 else { return }
        let now = RecorderController.now()
        if app.location.isOn {
            trail.add(location.coordinate.latitude, location.coordinate.longitude, now)
            showTrail()
        }
        guard phase == .recording, !demo else { return }
        if let change = signal.onFix(now) {
            applySignal(change)
        }
        // Taken before the fix: the new segment's own figures then include its first point.
        let before = core.stats(now)
        switch core.onFix(fix(location)) {
        case let .accepted(point, newSegment):
            if newSegment || batches.isEmpty {
                batches.append(PointBatch(points: [point], newSegment: newSegment))
            } else {
                batches[batches.count - 1].points.append(point)
            }
            if newSegment, app.settings.recorder.resetStatsOnSegment {
                segmentBase = before
            }
            if waitingCount >= RecorderController.flushPoints, let id = trackId {
                flush(id)
            }
            updateTail()
        case .rejected:
            break
        }
        publish()
    }

    /// The fix with the altitude « Altitude » asks for (GPS or barometer).
    private func fix(_ location: CLLocation) -> Fix {
        let altitude = app.location.altitude
        return Fix(
            lat: location.coordinate.latitude,
            lon: location.coordinate.longitude,
            ele: altitude?.meters,
            timeMillis: Int64(location.timestamp.timeIntervalSince1970 * 1000),
            accuracyMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
            speedMps: location.speed >= 0 ? location.speed : nil,
            verticalAccuracyMeters: altitude?.uncertaintyMeters,
            eleIsMsl: altitude != nil
        )
    }

    private var waitingCount: Int {
        batches.reduce(0) { $0 + $1.points.count }
    }

    private func startTicker() {
        ticker?.invalidate()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(timer, forMode: .common)
        ticker = timer
    }

    private func tick() {
        if demo {
            demoTick()
            return
        }
        guard phase == .recording, let id = trackId else { return }
        let now = RecorderController.now()
        if let change = signal.check(now) {
            applySignal(change)
        }
        if now - lastFlush >= RecorderController.flushMillis {
            flush(id)
        }
        publish()
    }

    /// « Avertissement de perte de signal » (P11.8): a message when the GNSS has been silent for a minute, and when it is
    /// back.
    private func applySignal(_ change: SignalWatch.Change) {
        noSignal = change == .lost
        let warn = app.settings.recorder.lostSignalWarning
        let wasLost = signalLost
        signalLost = warn && change == .lost
        if signalLost {
            app.say(RecordText.signalLost)
        } else if wasLost {
            app.say(RecordText.signalBack)
        }
    }

    private func publish() {
        let total = core.stats(RecorderController.now())
        if let base = segmentBase {
            live = RecorderController.since(total, base)
        } else {
            live = total
        }
        if phase != core.state {
            phase = core.state
        }
    }

    /// The figures of the segment since `base`: distances, times and counts start again from it.
    private static func since(_ total: LiveStats, _ base: LiveStats) -> LiveStats {
        LiveStats(
            pointCount: total.pointCount - base.pointCount,
            segmentCount: total.segmentCount - base.segmentCount,
            distanceMeters: total.distanceMeters - base.distanceMeters,
            elapsedMillis: total.elapsedMillis - base.elapsedMillis,
            movingMillis: total.movingMillis - base.movingMillis,
            ascentMeters: total.ascentMeters - base.ascentMeters,
            descentMeters: total.descentMeters - base.descentMeters,
            currentSpeedMps: total.currentSpeedMps,
            minEleMeters: total.minEleMeters,
            maxEleMeters: total.maxEleMeters,
            lastFix: total.lastFix
        )
    }

    // MARK: - Writing

    /// Writes the waiting points on `io`; on failure they wait again and the next flush tries once more.
    private func flush(_ id: Int64) {
        lastFlush = RecorderController.now()
        guard inFlight.isEmpty, !batches.isEmpty, let repository else { return }
        let sending = batches
        batches = []
        inFlight = sending
        io.async { [weak self] in
            var written = 0
            for batch in sending {
                do {
                    try repository.appendPoints(id, batch.points, newSegment: batch.newSegment)
                    written += 1
                } catch {
                    break
                }
            }
            let count = written
            DispatchQueue.main.async {
                self?.flushed(id, sending, count)
            }
        }
    }

    private func flushed(_ id: Int64, _ sending: [PointBatch], _ written: Int) {
        inFlight = []
        // The recording ended meanwhile: `stop` wrote the rest.
        guard trackId == id else { return }
        if let last = sending.prefix(written).last?.points.last {
            lastStored = last
        }
        let failed = Array(sending.dropFirst(written))
        if failed.isEmpty {
            if saveFailing { saveFailing = false }
        } else {
            batches = failed + batches
            saveFailing = true
        }
        writeSession()
        updateTail()
    }

    private func writeSession() {
        guard let id = trackId, !demo else { return }
        let now = RecorderController.now()
        let session = RecordingSession(
            trackId: id,
            startedAtMillis: startedAt,
            activeMillis: core.stats(now).elapsedMillis,
            paused: core.state != .recording,
            savedAtMillis: now,
            profile: profile.rawValue
        )
        let store = self.store
        io.async {
            try? store.write(session)
        }
    }

    // MARK: - Map

    /// The previous positions, while the position is on and « Afficher les positions précédentes » is.
    private func showTrail() {
        var text: String?
        if app.settings.positioning.showTrail, app.location.isOn {
            let points = trail.points(RecorderController.now()).map { LonLat(lon: $0.lon, lat: $0.lat) }
            text = TrailLayers.geoJson([points])
        }
        if app.mapFeatures[TrailLayers.trailId] != text {
            app.mapFeatures[TrailLayers.trailId] = text
        }
    }

    /// The line from the last point in the library through the points that wait to be written.
    private func updateTail() {
        if demo { return }
        var text: String?
        if phase != .idle {
            var lines: [[LonLat]] = []
            var current: [LonLat] = []
            if let stored = lastStored {
                current.append(LonLat(lon: stored.lon, lat: stored.lat))
            }
            let pending = inFlight + batches
            for batch in pending {
                if batch.newSegment {
                    lines.append(current)
                    current = []
                }
                for point in batch.points {
                    current.append(LonLat(lon: point.lon, lat: point.lat))
                }
            }
            lines.append(current)
            text = TrailLayers.geoJson(lines)
        }
        if app.mapFeatures[TrailLayers.recordingId] != text {
            app.mapFeatures[TrailLayers.recordingId] = text
        }
    }

    // MARK: - Set-up

    private func setUp() {
        app.location.addListener(RecorderController.listenerName) { [weak self] location in
            self?.received(location)
        }
        applySettings()
        watchSettings()
        observeApplication()
        if demo {
            startDemo()
        } else {
            checkForRecovery()
        }
    }

    /// The location service follows « Altitude » and « Désactiver en arrière-plan »; the trail « Afficher les
    /// positions précédentes » and the position switch.
    private func applySettings() {
        let settings = app.settings
        app.location.altitudeMode = settings.altitude
        app.location.stopsInBackground = settings.positioning.disableInBackground
        if !app.location.isOn {
            trail.clear()
        }
        showTrail()
    }

    private func watchSettings() {
        withObservationTracking {
            _ = app.settings
            _ = app.location.isOn
        } onChange: { [weak self] in
            DispatchQueue.main.async {
                self?.applySettings()
                self?.watchSettings()
            }
        }
    }

    /// « Mode tableau de bord » (P11.8): the recording pauses when the app is hidden and resumes when it is back.
    private func observeApplication() {
        let center = NotificationCenter.default
        let background = center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.enteredBackground()
        }
        let foreground = center.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.enteringForeground()
        }
        let terminate = center.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.terminating()
        }
        observers = [background, foreground, terminate]
    }

    /// The app is closed while it records: the waiting points are written before it goes (the session file stays, for
    /// « Enregistrement interrompu » at the next launch).
    private func terminating() {
        guard let id = trackId, !demo, let repository else { return }
        let remaining = batches
        batches = []
        io.sync {
            for batch in remaining {
                do {
                    try repository.appendPoints(id, batch.points, newSegment: batch.newSegment)
                } catch {
                    break
                }
            }
        }
    }

    private func enteredBackground() {
        if let id = trackId {
            flush(id)
        }
        guard app.settings.recorder.dashboardMode, phase == .recording else { return }
        pausedByDashboard = true
        pause()
    }

    private func enteringForeground() {
        guard pausedByDashboard else { return }
        pausedByDashboard = false
        if phase == .paused {
            resume()
        }
    }

    // MARK: - Demonstration (`-ToolMapDemo YES`)

    /// A walk of 1 h 12 and 4,82 km going on, ending at the centre of the map; nothing is written.
    private func startDemo() {
        profile = .walk
        demoActiveBefore = 72 * 60_000
        demoActiveSince = RecorderController.now()
        phase = .recording
        live = demoStats()
        let center = app.center
        let steps: [(Double, Double)] = [
            (-0.0120, -0.0080), (-0.0102, -0.0066), (-0.0088, -0.0061), (-0.0071, -0.0043), (-0.0056, -0.0036),
            (-0.0041, -0.0022), (-0.0026, -0.0017), (-0.0011, -0.0006), (0, 0),
        ]
        let line = steps.map { step in
            LonLat(lon: center.lon + step.0, lat: center.lat + step.1)
        }
        app.mapFeatures[TrailLayers.recordingId] = TrailLayers.geoJson([line])
        startTicker()
    }

    private func demoStats() -> LiveStats {
        var elapsed = demoActiveBefore
        if let since = demoActiveSince {
            elapsed += RecorderController.now() - since
        }
        return LiveStats(
            pointCount: 1_420,
            segmentCount: 1,
            distanceMeters: 4_820,
            elapsedMillis: elapsed,
            movingMillis: elapsed - 9 * 60_000,
            ascentMeters: 186,
            descentMeters: 122,
            currentSpeedMps: 3.9 / 3.6
        )
    }

    private func demoTick() {
        guard phase == .recording else { return }
        live = demoStats()
    }

    private func demoPause() {
        guard phase == .recording, let since = demoActiveSince else { return }
        demoActiveBefore += RecorderController.now() - since
        demoActiveSince = nil
        phase = .paused
        live = demoStats()
    }

    private func demoResume() {
        guard phase == .paused else { return }
        demoActiveSince = RecorderController.now()
        phase = .recording
    }

    private func demoStop() {
        guard phase != .idle else { return }
        ticker?.invalidate()
        ticker = nil
        phase = .idle
        live = LiveStats()
        app.mapFeatures[TrailLayers.recordingId] = nil
        finishedTrackId = 0
        app.say(RecordText.finished, action: RecordText.resume) { [weak self] in
            self?.reopen(0)
        }
    }

    private static func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
