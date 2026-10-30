import CoreLocation
import CoreMotion
import Foundation
import Observation
import ToolMapCore

/// The phone's barometer (Android `AndroidPressureSource` and the barometer part of `AltitudeService`, P9.5): the
/// pressure of `CMAltimeter` goes into the core's `BarometricAltimeter`, which keeps the sea-level pressure of the moment
/// from the references it gets: the GPS altitude (above mean sea level on iOS), the absolute altitude of iOS (GPS and
/// barometer fused by the system, in the place of Android's elevation tiles) and the user's own value (« Caler
/// l'altitude »). It runs only while the position is listened to and « Altitude » is not « GPS seulement ». On the main
/// thread.
@Observable
final class BarometerService {
    /// The phone has a pressure sensor (every iPhone since the 6); with `-ToolMapDemo YES`, the rows show for the
    /// screenshots of the simulator, which has none.
    let available: Bool
    /// The last pressure read, in hPa; nil until the sensor has answered.
    private(set) var pressureHpa: Double?
    /// The barometer has a sea-level pressure to read altitudes against (« · calée »).
    private(set) var calibrated = false
    /// The sea-level pressure it reads altitudes against, in hPa.
    private(set) var seaLevelHpa: Double?

    @ObservationIgnored private let altimeter = BarometricAltimeter()
    @ObservationIgnored private let sensor = CMAltimeter()
    @ObservationIgnored private var running = false

    /// Positions less precise than this say little about the altitude either (Android: 50 m).
    private static let maxReferenceAccuracy = 50.0
    /// The vertical accuracy of iOS is optimistic: a reference is never trusted below this (Android: 8 m).
    private static let minGpsSigma = 8.0
    /// The fused altitude of iOS stands in for the elevation model, which Android trusts to 20 m.
    private static let minSystemSigma = 10.0
    /// A value the user typed (Android: 2 m).
    private static let manualSigma = 2.0

    init() {
        available = CMAltimeter.isRelativeAltitudeAvailable() || UserDefaults.standard.bool(forKey: "ToolMapDemo")
    }

    func start() {
        guard !running, CMAltimeter.isRelativeAltitudeAvailable() else { return }
        running = true
        sensor.startRelativeAltitudeUpdates(to: OperationQueue.main) { [weak self] data, _ in
            guard let self, let data else { return }
            // CoreMotion gives kilopascals.
            self.onPressure(data.pressure.doubleValue * 10)
        }
        if CMAltimeter.isAbsoluteAltitudeAvailable() {
            sensor.startAbsoluteAltitudeUpdates(to: OperationQueue.main) { [weak self] data, _ in
                guard let self, let data else { return }
                self.onSystemAltitude(data.altitude, data.accuracy)
            }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        sensor.stopRelativeAltitudeUpdates()
        if CMAltimeter.isAbsoluteAltitudeAvailable() {
            sensor.stopAbsoluteAltitudeUpdates()
        }
    }

    /// The altitude of the barometer now; nil before calibration or without a current pressure reading.
    func reading() -> BaroReading? {
        altimeter.reading(BarometerService.now())
    }

    /// A GPS fix as a reference altitude (Android `feedFromGps`, the altitude being above sea level on iOS).
    func reference(_ fix: CLLocation) {
        guard running,
              fix.horizontalAccuracy >= 0,
              fix.horizontalAccuracy <= BarometerService.maxReferenceAccuracy,
              fix.verticalAccuracy >= 0
        else { return }
        let sigma = Swift.max(fix.verticalAccuracy, BarometerService.minGpsSigma)
        _ = altimeter.onReference(fix.altitude, sigma, BarometerService.now(), .gpsMsl)
        publish()
    }

    /// « Caler l'altitude »: `false` when the sensor has given no current pressure.
    func calibrate(_ meters: Double) -> Bool {
        let result = altimeter.onReference(meters, BarometerService.manualSigma, BarometerService.now(), .manual)
        publish()
        return result == .accepted
    }

    private func onPressure(_ hpa: Double) {
        altimeter.onPressure(hpa, BarometerService.now())
        if let shown = pressureHpa, abs(shown - hpa) < 0.05 {
            publish()
            return
        }
        pressureHpa = hpa
        publish()
    }

    private func onSystemAltitude(_ meters: Double, _ accuracy: Double) {
        guard meters.isFinite, accuracy > 0 else { return }
        let sigma = Swift.max(accuracy, BarometerService.minSystemSigma)
        _ = altimeter.onReference(meters, sigma, BarometerService.now(), .terrain)
        publish()
    }

    /// Copies the state of the altimeter to the observed properties, only when it changed.
    private func publish() {
        if calibrated != altimeter.calibrated {
            calibrated = altimeter.calibrated
        }
        let seaLevel = altimeter.seaLevelHpa
        if let value = seaLevel, let shown = seaLevelHpa, abs(value - shown) < 0.01 { return }
        if seaLevel != seaLevelHpa {
            seaLevelHpa = seaLevel
        }
    }

    private static func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
