import SwiftUI
import ToolMapCore

/// The profile of a track's detail (Android `ElevationProfileChart`, P29.4 « Topo »): the curve coloured by slope with
/// its cursor (touch or drag), the axis from « 0 » to the length, and the legend of the five slope colours.
struct LibraryProfileChart: View {
    @Environment(AppModel.self) private var model
    let profile: ElevationProfile
    @Binding var cursor: Double?

    var body: some View {
        let settings = model.settings
        VStack(alignment: .leading, spacing: 2) {
            LibraryProfileCanvas(profile: profile, cursor: cursor, height: 140, onCursor: { cursor = $0 })
                .accessibilityLabel(LibraryProfileText.description(profile, settings))
            HStack {
                Text("0")
                Spacer()
                Text(LibraryText.distance(max(profile.totalDistanceMeters, 1), settings))
            }
            .font(.caption)
            .foregroundStyle(Crete.onSurfaceVariant)
            LibrarySlopeLegend()
                .padding(.top, 4)
        }
    }
}

/// A small profile without cursor, in the sheet of a track touched on the map (P29.4, « Repère »).
struct LibraryMiniProfile: View {
    @Environment(AppModel.self) private var model
    let profile: ElevationProfile

    var body: some View {
        LibraryProfileCanvas(profile: profile, cursor: nil, height: 56, onCursor: nil)
            .accessibilityLabel(LibraryProfileText.description(profile, model.settings))
    }
}

/// The five slope bands and their names.
private struct LibrarySlopeLegend: View {
    private static let bands: [(GradeBand, String)] = [
        (.flat, "< 3 %"), (.gentle, "3–8 %"), (.moderate, "8–15 %"), (.steep, "15–25 %"), (.verySteep, "≥ 25 %"),
    ]

    var body: some View {
        HStack(spacing: 10) {
            ForEach(0 ..< LibrarySlopeLegend.bands.count, id: \.self) { index in
                HStack(spacing: 4) {
                    Circle()
                        .fill(LibraryColors.grade(LibrarySlopeLegend.bands[index].0))
                        .frame(width: 8, height: 8)
                    Text(LibrarySlopeLegend.bands[index].1)
                        .font(.caption2)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

enum LibraryProfileText {
    /// « 3,20 km · 612 m · pente 18 % » under the cursor, « De 278 m à 1 020 m » without it.
    static func reading(_ profile: ElevationProfile, _ cursor: Double?, _ settings: AppSettings) -> String {
        if let at = cursor, let sample = profile.sampleAt(at) {
            let distance = LibraryText.distance(sample.distanceMeters, settings)
            let elevation = LibraryText.elevation(sample.elevationMeters, settings)
            let slope = LibraryText.slope(sample.gradePercent, settings)
            return "\(distance) · \(elevation) · pente \(slope)"
        }
        let low = LibraryText.elevation(profile.minElevation ?? 0, settings)
        let high = LibraryText.elevation(profile.maxElevation ?? 0, settings)
        return "De \(low) à \(high)"
    }

    /// What VoiceOver reads, which the colours alone would not give.
    static func description(_ profile: ElevationProfile, _ settings: AppSettings) -> String {
        let low = LibraryText.elevation(profile.minElevation ?? 0, settings)
        let high = LibraryText.elevation(profile.maxElevation ?? 0, settings)
        let length = LibraryText.distance(max(profile.totalDistanceMeters, 1), settings)
        return "Profil d'altitude de \(low) à \(high) sur \(length), coloré selon la pente"
    }
}

/// The curve itself; `onCursor` nil takes no touch.
struct LibraryProfileCanvas: View {
    let profile: ElevationProfile
    let cursor: Double?
    let height: CGFloat
    let onCursor: ((Double?) -> Void)?

    var body: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width, 1)
            Canvas { context, size in
                LibraryProfileDrawing.draw(profile, cursor: cursor, in: &context, size: size, mini: onCursor == nil)
            }
            .contentShape(Rectangle())
            .gesture(drag(width), including: mask)
        }
        .frame(height: height)
    }

    /// The mini profile takes no touch.
    private var mask: GestureMask {
        onCursor == nil ? [] : .all
    }

    private func drag(_ width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let total = max(profile.totalDistanceMeters, 1)
                let fraction = min(max(Double(value.location.x / width), 0), 1)
                onCursor?(fraction * total)
            }
    }
}

/// Drawing of the profile (Android `ProfileCanvas`): the light area, the curve stroke by stroke in the colour of its
/// slope, the axis, the cursor.
enum LibraryProfileDrawing {
    private static let minSpanMeters = 10.0
    private static let usableHeight = 0.85
    private static let bottomMargin = 0.06

    static func draw(_ profile: ElevationProfile, cursor: Double?, in context: inout GraphicsContext, size: CGSize, mini: Bool) {
        let samples = profile.samples
        guard let minEle = profile.minElevation, let maxEle = profile.maxElevation, samples.count >= 2,
              let first = samples.first, let last = samples.last
        else { return }
        let span = max(maxEle - minEle, minSpanMeters)
        let total = max(profile.totalDistanceMeters, 1)
        let w = Double(size.width)
        let h = Double(size.height)
        func x(_ d: Double) -> CGFloat { CGFloat(d / total * w) }
        func y(_ e: Double) -> CGFloat { CGFloat(h - ((e - minEle) / span * (h * usableHeight) + h * bottomMargin)) }

        var area = Path()
        area.move(to: CGPoint(x: x(first.distanceMeters), y: CGFloat(h)))
        for sample in samples {
            area.addLine(to: CGPoint(x: x(sample.distanceMeters), y: y(sample.elevationMeters)))
        }
        area.addLine(to: CGPoint(x: x(last.distanceMeters), y: CGFloat(h)))
        area.closeSubpath()
        context.fill(area, with: .color(Crete.petrol.opacity(0.12)))

        let lineWidth: CGFloat = mini ? 1.6 : 2.4
        for index in 1 ..< samples.count {
            let a = samples[index - 1]
            let b = samples[index]
            var stroke = Path()
            stroke.move(to: CGPoint(x: x(a.distanceMeters), y: y(a.elevationMeters)))
            stroke.addLine(to: CGPoint(x: x(b.distanceMeters), y: y(b.elevationMeters)))
            let color = LibraryColors.grade(GradeBand.of(b.gradePercent))
            context.stroke(stroke, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
        }

        var axis = Path()
        axis.move(to: CGPoint(x: 0, y: CGFloat(h)))
        axis.addLine(to: CGPoint(x: CGFloat(w), y: CGFloat(h)))
        context.stroke(axis, with: .color(Crete.outline), lineWidth: 1)

        if let at = cursor, let sample = profile.sampleAt(at) {
            let cx = x(sample.distanceMeters)
            var line = Path()
            line.move(to: CGPoint(x: cx, y: 0))
            line.addLine(to: CGPoint(x: cx, y: CGFloat(h)))
            context.stroke(line, with: .color(Crete.onSurface), lineWidth: 1.5)
            let cy = y(sample.elevationMeters)
            let ring = Path(ellipseIn: CGRect(x: cx - 5, y: cy - 5, width: 10, height: 10))
            context.fill(ring, with: .color(Color.white))
            context.stroke(ring, with: .color(Crete.onSurface), lineWidth: 1.5)
        }
    }
}
