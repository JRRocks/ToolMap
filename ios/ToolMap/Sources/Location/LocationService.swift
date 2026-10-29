import CoreLocation
import Foundation
import Observation

/// GPS position for the map and the information pills (Android: `AndroidLocationSource`, GPS only, ADR-019).
@Observable
final class LocationService {
    var location: CLLocation?
    var authorization: CLAuthorizationStatus = .notDetermined
    var heading: Double?

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var receiver: Receiver?

    init() {
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        receiver = Receiver(owner: self)
        authorization = manager.authorizationStatus
    }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    func start() {
        manager.delegate = receiver
        if authorization == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        manager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() {
            manager.startUpdatingHeading()
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    /// CoreLocation's delegate: an NSObject kept apart so that the observable model stays a plain class.
    private final class Receiver: NSObject, CLLocationManagerDelegate {
        weak var owner: LocationService?

        init(owner: LocationService) {
            self.owner = owner
        }

        func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
            if let last = locations.last {
                owner?.location = last
            }
        }

        func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
            owner?.heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        }

        func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
            owner?.authorization = manager.authorizationStatus
            if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
                manager.startUpdatingLocation()
            }
        }

        func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
    }
}
