import CoreLocation
import Foundation
import Observation
import ToolMapCore
import UIKit

/// The GPS position for the map, the information pills, the track recorder and the sharing (Android
/// `AndroidLocationSource`, GPS only, ADR-019, and `AltitudeService` for the altitude, P9.5).
///
/// - « Position en temps réel » (`isOn`, Android L3) shows the position on the map; `start()` and `stop()` turn it on and
///   off, `resume()` turns it on again at launch unless the user turned it off.
/// - The recorder and the sharing keep the receiver running while the app is in the background, screen off
///   (`keepRunningInBackground`): the `location` background mode of `project.yml`, the blue indicator of iOS in the
///   status bar instead of Android's notification.
/// - « Pendant l'utilisation » is asked when the position is first wanted; « Toujours » only when a recording starts
///   (`requestAlways`), so that it may go on screen off.
/// - iOS gives no count of satellites (Android's « 3 satellites vus »): only the accuracy says how good a fix is.
/// - CoreLocation gives the altitude above mean sea level (its own geoid), so the EGM96 correction of Android
///   (`positioning.egm96`) has nothing to do here; the barometer (`barometer`) corrects it as « Altitude » says.
@Observable
final class LocationService {
    /// The last fix; nil while nothing listens.
    private(set) var location: CLLocation?
    private(set) var authorization: CLAuthorizationStatus = .notDetermined
    /// « Position exacte » of the iOS settings: a reduced accuracy cannot record a track (Android: precise location).
    private(set) var accuracyAuthorization: CLAccuracyAuthorization = .fullAccuracy
    /// Compass heading in degrees from true north (magnetic when the true one is unknown); nil without a compass.
    private(set) var heading: Double?
    /// « Position en temps réel » (Android L3): the position is shown on the map.
    private(set) var isOn = false
    /// The altitude of the last fix as « Altitude » of the settings asks: the GPS's, or the barometer's once calibrated.
    private(set) var altitude: Altitude?
    /// The phone's barometer (Android L6, G7).
    let barometer = BarometerService()

    /// « Altitude » of the settings (GPS seulement, Automatique, Baromètre seulement); the recorder keeps it current.
    @ObservationIgnored var altitudeMode: AltitudeMode = .auto {
        didSet {
            if altitudeMode != oldValue { update() }
        }
    }

    /// « Désactiver en arrière-plan » (Réglages ▸ Position): the position of the map stops while the app is hidden; the
    /// recorder and the sharing are not affected. The recorder keeps it current.
    @ObservationIgnored var stopsInBackground = true {
        didSet {
            if stopsInBackground != oldValue { update() }
        }
    }

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var receiver: Receiver?
    /// Who wants each fix (the recorder, the sharing…), by name.
    @ObservationIgnored private var listeners: [String: (CLLocation) -> Void] = [:]
    /// Who keeps the receiver running in the background, with the accuracy each one needs.
    @ObservationIgnored private var owners: [String: CLLocationAccuracy] = [:]
    /// Calls waiting for the answer to « Pendant l'utilisation ».
    @ObservationIgnored private var waiters: [(Bool) -> Void] = []
    @ObservationIgnored private var running = false
    @ObservationIgnored private var headingRunning = false
    @ObservationIgnored private var inBackground = false
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    /// The user turned the position off: it stays off at the next launch.
    private static let offKey = "ToolMapPositionOff"
    /// The key of `NSLocationTemporaryUsageDescriptionDictionary` (Info.plist) that asks for the exact position.
    static let fullAccuracyPurpose = "Recording"

    init() {
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .fitness
        manager.headingFilter = 2
        authorization = manager.authorizationStatus
        accuracyAuthorization = manager.accuracyAuthorization
        let receiver = Receiver()
        self.receiver = receiver
        receiver.owner = self
        manager.delegate = receiver
        observeApplication()
    }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    /// The phone refuses the position (« Jamais », restrictions, or Location Services off).
    var isDenied: Bool {
        authorization == .denied || authorization == .restricted
    }

    /// A fix with a known accuracy.
    var hasFix: Bool {
        guard let location else { return false }
        return location.horizontalAccuracy >= 0
    }

    // MARK: - Position on the map

    /// Turns « Position en temps réel » on; the first time, iOS asks « Pendant l'utilisation ».
    func start() {
        isOn = true
        UserDefaults.standard.set(false, forKey: LocationService.offKey)
        if authorization == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        update()
    }

    /// At launch: the position comes back on, unless the user turned it off.
    func resume() {
        if UserDefaults.standard.bool(forKey: LocationService.offKey) { return }
        start()
    }

    /// Turns « Position en temps réel » off: the recorder and the sharing keep their own use of the receiver.
    func stop() {
        isOn = false
        UserDefaults.standard.set(true, forKey: LocationService.offKey)
        update()
    }

    // MARK: - Other users of the position

    /// Each fix, as it comes, for `owner` (the recorder, the sharing): on the main thread, after `location` and
    /// `altitude` are set.
    func addListener(_ owner: String, _ handler: @escaping (CLLocation) -> Void) {
        listeners[owner] = handler
    }

    func removeListener(_ owner: String) {
        listeners[owner] = nil
    }

    /// `owner` (« recorder », « share »…) needs the position even while the app is in the background, screen off, at
    /// `accuracy` (the most demanding owner wins). Turned off, the receiver stops unless someone else needs it.
    func keepRunningInBackground(_ owner: String, _ on: Bool, accuracy: CLLocationAccuracy = kCLLocationAccuracyBest) {
        if on {
            owners[owner] = accuracy
            if authorization == .notDetermined {
                manager.requestWhenInUseAuthorization()
            }
        } else {
            owners[owner] = nil
        }
        update()
    }

    /// Asks « Pendant l'utilisation » if it was never asked; `done(true)` once the position may be read.
    func requestAuthorization(_ done: @escaping (Bool) -> Void) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            done(true)
        case .notDetermined:
            waiters.append(done)
            manager.requestWhenInUseAuthorization()
        default:
            done(false)
        }
    }

    /// The exact position for a recording (Android asks for the precise location): `done(true)` when iOS gives it.
    func requestFullAccuracy(_ done: @escaping (Bool) -> Void) {
        if manager.accuracyAuthorization == .fullAccuracy {
            done(true)
            return
        }
        manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: LocationService.fullAccuracyPurpose) { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.accuracyAuthorization = self.manager.accuracyAuthorization
                done(self.accuracyAuthorization == .fullAccuracy)
            }
        }
    }

    /// « Toujours », asked once a recording starts: iOS shows its question only once.
    func requestAlways() {
        if manager.authorizationStatus == .authorizedWhenInUse {
            manager.requestAlwaysAuthorization()
        }
    }

    // MARK: - Altitude

    /// « Caler l'altitude » (Android `AltitudeService.calibrateManually`): the barometer takes `meters` as the altitude
    /// here. `false` when it cannot (no current pressure reading).
    func calibrate(_ meters: Double) -> Bool {
        let accepted = barometer.calibrate(meters)
        if accepted, let fix = location {
            altitude = chooseAltitude(fix, feed: false)
        }
        return accepted
    }

    private func chooseAltitude(_ fix: CLLocation, feed: Bool) -> Altitude? {
        let gps: Double? = fix.verticalAccuracy >= 0 ? fix.altitude : nil
        let sigma: Double? = gps == nil ? nil : fix.verticalAccuracy
        guard barometer.available, altitudeMode != .gps else {
            guard let meters = gps else { return nil }
            return Altitude(meters: meters, source: .gps, uncertaintyMeters: sigma)
        }
        if feed {
            barometer.reference(fix)
        }
        return AltitudeSelector.choose(altitudeMode, gps, sigma, barometer.reading())
    }

    // MARK: - Receiver

    /// Starts or stops the receiver, the compass and the barometer for what is wanted now.
    private func update() {
        let keep = !owners.isEmpty
        let shown = isOn && !(inBackground && stopsInBackground)
        let wanted = isAuthorized && (shown || keep)
        var accuracy = shown ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
        for value in owners.values {
            accuracy = Swift.min(accuracy, value)
        }
        if manager.desiredAccuracy != accuracy {
            manager.desiredAccuracy = accuracy
        }
        let background = keep || (isOn && !stopsInBackground)
        if manager.allowsBackgroundLocationUpdates != background {
            manager.allowsBackgroundLocationUpdates = background
        }
        manager.showsBackgroundLocationIndicator = background
        manager.pausesLocationUpdatesAutomatically = !keep
        if wanted, !running {
            running = true
            manager.startUpdatingLocation()
        } else if !wanted, running {
            running = false
            manager.stopUpdatingLocation()
            location = nil
            altitude = nil
        }
        let compass = wanted && !inBackground && CLLocationManager.headingAvailable()
        if compass, !headingRunning {
            headingRunning = true
            manager.startUpdatingHeading()
        } else if !compass, headingRunning {
            headingRunning = false
            manager.stopUpdatingHeading()
            heading = nil
        }
        if wanted, altitudeMode != .gps {
            barometer.start()
        } else {
            barometer.stop()
        }
    }

    fileprivate func received(_ fix: CLLocation) {
        location = fix
        altitude = chooseAltitude(fix, feed: true)
        for handler in listeners.values {
            handler(fix)
        }
    }

    fileprivate func receivedHeading(_ newHeading: CLHeading) {
        guard newHeading.headingAccuracy >= 0 else { return }
        let value = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        if let previous = heading, abs(previous - value) < 1 { return }
        heading = value
    }

    fileprivate func authorizationChanged() {
        let status = manager.authorizationStatus
        if authorization != status {
            authorization = status
        }
        let accuracy = manager.accuracyAuthorization
        if accuracyAuthorization != accuracy {
            accuracyAuthorization = accuracy
        }
        if status != .notDetermined, !waiters.isEmpty {
            let granted = isAuthorized
            let pending = waiters
            waiters = []
            for done in pending {
                done(granted)
            }
        }
        update()
    }

    fileprivate func failed(_ error: Error) {
        if let code = (error as? CLError)?.code, code == .denied {
            authorizationChanged()
        }
    }

    private func observeApplication() {
        let center = NotificationCenter.default
        let background = center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.applicationMoved(toBackground: true)
        }
        let foreground = center.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.applicationMoved(toBackground: false)
        }
        observers = [background, foreground]
    }

    private func applicationMoved(toBackground hidden: Bool) {
        inBackground = hidden
        update()
    }

    /// CoreLocation's delegate: an NSObject kept apart so that the observable model stays a plain class.
    private final class Receiver: NSObject, CLLocationManagerDelegate {
        weak var owner: LocationService?

        func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
            for fix in locations {
                owner?.received(fix)
            }
        }

        func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
            owner?.receivedHeading(newHeading)
        }

        func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
            owner?.authorizationChanged()
        }

        func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
            owner?.failed(error)
        }
    }
}
