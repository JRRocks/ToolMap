import CoreLocation
import SwiftUI
import ToolMapCore

/// The information pills at the top left of the map (Android G4 to G6, P29.1 variant E): position of the map
/// centre, scale, GPS.
struct InfoPills: View {
    @Environment(AppModel.self) private var model
    @State private var showZoom = false
    @State private var showGpsDetail = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(MapText.position(model.center))
                .font(.system(size: 13, weight: .medium).monospacedDigit())
                .foregroundStyle(Crete.onSurface)
                .pill()
                .accessibilityLabel("Position du centre de la carte")

            Button {
                showZoom.toggle()
            } label: {
                HStack(spacing: 8) {
                    Text(MapText.scaleRatio(metersPerPoint: model.metersPerPoint))
                    ScaleBarView(metersPerPoint: model.metersPerPoint)
                    if showZoom {
                        Text("zoom \(Int(model.zoom.rounded(.down)))")
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                .font(.system(size: 13, weight: .medium).monospacedDigit())
                .foregroundStyle(Crete.onSurface)
                .pill()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Échelle")

            Button {
                showGpsDetail.toggle()
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(MapText.gpsSummary(model.location.location, authorized: model.location.isAuthorized))
                    if showGpsDetail, let position = model.location.location {
                        Text(MapText.position(LonLat(lon: position.coordinate.longitude, lat: position.coordinate.latitude)))
                            .foregroundStyle(Crete.onSurfaceVariant)
                        Text(MapText.distance(MapText.distanceMeters(from: position, to: model.center)) + " du centre")
                            .foregroundStyle(Crete.onSurfaceVariant)
                    }
                }
                .font(.system(size: 13, weight: .medium).monospacedDigit())
                .foregroundStyle(Crete.onSurface)
                .pill()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("GPS")
        }
    }
}

/// A scale bar of a round length, at most 80 points wide.
struct ScaleBarView: View {
    let metersPerPoint: Double

    var body: some View {
        let length = MapText.roundLength(maxMeters: metersPerPoint * 80)
        let width = metersPerPoint > 0 ? CGFloat(length / metersPerPoint) : 0
        HStack(spacing: 4) {
            Rectangle()
                .fill(Crete.petrol)
                .frame(width: max(width, 4), height: 3)
            Text(MapText.distance(length))
        }
    }
}

/// Texts of the map screen, in French.
enum MapText {
    /// Points per metre of an iPhone screen (about 460 pixels per inch at 3 pixels per point).
    static let pointsPerMeter: Double = {
        let pixelsPerInch = UIScreen.main.scale >= 3 ? 460.0 : 326.0
        return pixelsPerInch / UIScreen.main.scale / 0.0254
    }()

    static func number(_ value: Double, decimals: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals
        formatter.usesGroupingSeparator = true
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// Decimal degrees, as the Android default format: `45,56460° N  5,91780° E`.
    static func position(_ point: LonLat) -> String {
        let lat = number(abs(point.lat), decimals: 5) + "° " + (point.lat >= 0 ? "N" : "S")
        let lon = number(abs(point.lon), decimals: 5) + "° " + (point.lon >= 0 ? "E" : "O")
        return "\(lat)  \(lon)"
    }

    /// `1:25 000`: the ratio rounded to two significant digits.
    static func scaleRatio(metersPerPoint: Double) -> String {
        let ratio = metersPerPoint * pointsPerMeter
        guard ratio.isFinite, ratio >= 1 else { return "1:1" }
        let magnitude = pow(10, floor(log10(ratio)) - 1)
        let rounded = (ratio / magnitude).rounded() * magnitude
        return "1:" + number(rounded, decimals: 0)
    }

    /// The largest 1, 2 or 5 times a power of ten below `maxMeters`.
    static func roundLength(maxMeters: Double) -> Double {
        guard maxMeters > 0 else { return 0 }
        let power = pow(10, floor(log10(maxMeters)))
        for factor in [5.0, 2.0, 1.0] where factor * power <= maxMeters {
            return factor * power
        }
        return power
    }

    static func distance(_ meters: Double) -> String {
        if meters >= 1000 {
            let km = meters / 1000
            return number(km, decimals: km < 10 ? 1 : 0).replacingOccurrences(of: ",0", with: "") + " km"
        }
        return number(meters, decimals: 0) + " m"
    }

    static func distanceMeters(from position: CLLocation, to point: LonLat) -> Double {
        position.distance(from: CLLocation(latitude: point.lat, longitude: point.lon))
    }

    /// `± 5 m · ↑ 227 m`, or the state of the GPS.
    static func gpsSummary(_ location: CLLocation?, authorized: Bool) -> String {
        guard authorized else { return "GPS : position non autorisée" }
        guard let location, location.horizontalAccuracy >= 0 else { return "GPS (recherche des satellites…)" }
        var text = "± " + number(location.horizontalAccuracy, decimals: 0) + " m"
        if location.verticalAccuracy >= 0 {
            text += " · ↑ " + number(location.altitude, decimals: 0) + " m"
        }
        return text
    }
}
