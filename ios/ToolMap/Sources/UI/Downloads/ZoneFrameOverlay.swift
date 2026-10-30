import SwiftUI
import ToolMapCore

/// The zone being saved over the map (Android `SaveAreaOverlay`, `SaveCorridorOverlay`): outside it dimmed, its edge in
/// petrol, four handles on the corners of a rectangle. The zone is fixed on the ground, so it is drawn again from the
/// camera each time the map moves; only the handles take touches, the rest of the map stays usable.
///
/// The view also starts the download queue at launch (`DownloadController.start`), being always on the map.
struct ZoneFrameOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Color.clear
                .allowsHitTesting(false)
            if isZoneTool, let zone = model.downloads.zone {
                ZoneShade(shape: ZoneScreenShape.make(zone, model.mapProxy), camera: camera)
                    .allowsHitTesting(false)
                if zone.hasHandles {
                    ZoneHandles(selection: zone.selection, camera: camera)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // The map view goes under the bars: the points of `MapProxy` are in its full-screen space.
        .ignoresSafeArea()
        .onAppear {
            // On the main thread, as `start` requires.
            model.downloads.start()
        }
    }

    private var isZoneTool: Bool {
        if case .some(.zone) = model.tool { return true }
        return false
    }

    /// Read here so that the zone is drawn again when the map moves.
    private var camera: ZoneCamera {
        ZoneCamera(center: model.center, zoom: model.zoom, direction: model.direction)
    }
}

/// The camera a drawing of the zone was made for.
struct ZoneCamera: Equatable {
    let center: LonLat
    let zoom: Double
    let direction: Double
}

/// The zone in points of the map view.
enum ZoneScreenShape {
    case none
    /// A rectangle or a polygon.
    case ring([CGPoint])
    /// The line of a band and half its width.
    case band([CGPoint], halfWidth: CGFloat)

    static func make(_ zone: ZoneDraft, _ proxy: MapProxy) -> ZoneScreenShape {
        if let band = zone.corridor {
            let line = zone.corridorLine.compactMap { proxy.point($0) }
            guard line.count >= 2 else { return .none }
            return .band(line, halfWidth: halfWidth(zone.corridorLine, CGFloat(band.radiusMeters), proxy))
        }
        if let polygon = zone.polygon {
            let ring = polygon.compactMap { proxy.point($0) }
            return ring.count >= 3 ? .ring(ring) : .none
        }
        let corners = cornerPoints(zone.selection, proxy)
        let order: [AreaCorner] = [.northWest, .northEast, .southEast, .southWest]
        let ring = order.compactMap { corners[$0] }
        return ring.count == 4 ? .ring(ring) : .none
    }

    static func cornerPoints(_ selection: AreaSelection, _ proxy: MapProxy) -> [AreaCorner: CGPoint] {
        var out: [AreaCorner: CGPoint] = [:]
        for corner in AreaCorner.allCases {
            let position = LonLat(lon: selection.lonOf(corner), lat: selection.latOf(corner))
            if let point = proxy.point(position) {
                out[corner] = point
            }
        }
        return out
    }

    /// Half the width of the band on screen, measured at the middle of its line.
    private static func halfWidth(_ line: [LonLat], _ meters: CGFloat, _ proxy: MapProxy) -> CGFloat {
        let middle = line[line.count / 2]
        let metersPerDegree = 111_320 * cos(middle.lat * Double.pi / 180)
        guard metersPerDegree > 1 else { return 0 }
        let east = LonLat(lon: middle.lon + Double(meters) / metersPerDegree, lat: middle.lat)
        guard let a = proxy.point(middle), let b = proxy.point(east) else { return 0 }
        return hypot(b.x - a.x, b.y - a.y)
    }
}

/// The dimmed outside of the zone and its petrol edge.
private struct ZoneShade: View {
    let shape: ZoneScreenShape
    let camera: ZoneCamera

    var body: some View {
        Canvas { context, size in
            ZoneShade.draw(shape, in: &context, size: size)
        }
    }

    private static let scrim = Color.black.opacity(0.45)

    static func draw(_ shape: ZoneScreenShape, in context: inout GraphicsContext, size: CGSize) {
        let screen = Path(CGRect(origin: .zero, size: size))
        switch shape {
        case .none:
            return
        case let .ring(points):
            var hole = Path()
            hole.addLines(points)
            hole.closeSubpath()
            var shade = screen
            shade.addPath(hole)
            context.fill(shade, with: .color(scrim), style: FillStyle(eoFill: true))
            context.stroke(hole, with: .color(Crete.petrol), lineWidth: 2)
        case let .band(points, halfWidth):
            var line = Path()
            line.addLines(points)
            let inner = StrokeStyle(lineWidth: 2 * halfWidth, lineCap: .round, lineJoin: .round)
            let outer = StrokeStyle(lineWidth: 2 * halfWidth + 4, lineCap: .round, lineJoin: .round)
            // Everything but the inside of the band: the outside is dimmed, the edge is what the wider line leaves.
            context.clipToLayer(options: .inverse) { layer in
                layer.stroke(line, with: .color(Color.black), style: inner)
            }
            context.fill(screen, with: .color(scrim))
            context.stroke(line, with: .color(Crete.petrol), style: outer)
        }
    }
}

/// The four handles of the rectangle (touch target 48 points, as on Android).
private struct ZoneHandles: View {
    @Environment(AppModel.self) private var model
    let selection: AreaSelection
    let camera: ZoneCamera

    var body: some View {
        let corners = ZoneScreenShape.cornerPoints(selection, model.mapProxy)
        ZStack {
            ForEach(AreaCorner.allCases, id: \.self) { corner in
                if let point = corners[corner] {
                    ZoneHandle(corner: corner, point: point)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct ZoneHandle: View {
    @Environment(AppModel.self) private var model
    let corner: AreaCorner
    let point: CGPoint
    /// Where the handle was when the finger came down: the corner follows the finger's moves from there.
    @State private var start: CGPoint?

    var body: some View {
        Circle()
            .fill(Color.white)
            .overlay(Circle().strokeBorder(Crete.petrol, lineWidth: 3))
            .frame(width: 22, height: 22)
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .frame(width: 48, height: 48)
            .contentShape(Rectangle())
            .gesture(drag)
            .position(point)
            .accessibilityLabel("Poignée de la zone")
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let origin = start ?? point
                if start == nil {
                    start = point
                }
                let target = CGPoint(x: origin.x + value.translation.width, y: origin.y + value.translation.height)
                if let position = model.mapProxy.position(target) {
                    model.downloads.dragCorner(corner, to: position)
                }
            }
            .onEnded { _ in
                start = nil
            }
    }
}
