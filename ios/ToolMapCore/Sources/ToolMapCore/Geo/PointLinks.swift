// Port of core/src/main/kotlin/fr/toolmap/core/geo/PointLinks.kt.

import Foundation

/// Where a position goes when it is handed to another app: the link to open, and the app that must open it, if any.
public struct PointTarget: Equatable, Hashable, Sendable {
    public let uri: String
    /// The package that must open the link, or nil: the system then offers the apps that can (ADR-044).
    public let packageName: String?

    public init(uri: String, packageName: String?) {
        self.uri = uri
        self.packageName = packageName
    }
}

/// The links that hand a position to another app, for « Ouvrir avec » of the centre point (ADR-044). Waze has its own
/// link, which starts the guidance to the position; the other apps get the generic `geo:` link.
public enum PointLinks {
    public static let wazePackage = "com.waze"

    /// The link for `app`: Waze's own link for Waze, the generic one for any other app and for the system's choice.
    public static func target(_ app: String?, _ lat: Double, _ lon: Double) -> PointTarget {
        if app == wazePackage {
            return PointTarget(uri: "https://waze.com/ul?ll=\(coordinates(lat, lon))&navigate=yes", packageName: wazePackage)
        }
        return PointTarget(uri: geo(lat, lon), packageName: app)
    }

    /// `geo:<lat>,<lon>?q=<lat>,<lon>`: the position as a pin, for the apps that open geographic positions.
    public static func geo(_ lat: Double, _ lon: Double) -> String {
        let at = coordinates(lat, lon)
        return "geo:\(at)?q=\(at)"
    }

    /// Decimal degrees with a point, whatever the language of the phone: `48.500000,2.250000`.
    private static func coordinates(_ lat: Double, _ lon: Double) -> String {
        String(format: "%.6f,%.6f", lat, lon)
    }
}
