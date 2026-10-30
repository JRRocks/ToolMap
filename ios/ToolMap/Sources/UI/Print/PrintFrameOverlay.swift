import MapLibre
import SwiftUI
import ToolMapCore

/// The print frame over the map (Android T3 `PrintFrameOverlay`, `PrintFrameBar`): the page's map frame at its true
/// ground size (paper millimetres times the scale), fixed on the ground at the frame centre; what is inside is what the
/// sheet will show, the outside is dimmed. While it is placed, a finger dragged inside the frame moves it and two fingers
/// zoom the map; outside the frame the map keeps its own gestures, and the pill at the top changes the format, the
/// orientation and the scale. The print is always north up: on a turned map the frame turns with it. The pages along a
/// track (T3b) are map layers (`PrintLayers`); only their pill is here.
struct PrintFrameOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        if let stage = printer.stage {
            ZStack {
                if !printer.isAlongTrack {
                    PrintFrameCanvas(movable: stage == .frame)
                        .ignoresSafeArea()
                }
                if stage == .frame {
                    PrintFrameBar()
                        .padding(.top, 8)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                }
            }
        }
    }
}

/// The frame, the dimmed outside and, while the frame is placed, the area that moves it. In the coordinates of the map
/// view (the whole screen, safe areas ignored), where `MapProxy.point` places the positions.
private struct PrintFrameCanvas: View {
    @Environment(AppModel.self) private var model
    let movable: Bool
    @State private var dragStart: CGPoint? = nil
    @State private var pinchStartZoom: Double? = nil

    var body: some View {
        let printer = model.printer
        // Read so that the frame is drawn again whenever the camera moves.
        let camera = CameraKey(center: model.center, zoom: model.zoom, direction: model.direction)
        let corners = PrintFrameGeometry.screenCorners(model.mapProxy, printer.template, printer.center, camera)
        ZStack {
            if let corners {
                PrintFrameDrawing(corners: corners)
                    .allowsHitTesting(false)
                if movable {
                    Color.clear
                        .contentShape(PrintQuad(points: corners))
                        .gesture(drag(printer))
                        .simultaneousGesture(pinch)
                        .accessibilityElement()
                        .accessibilityLabel("Cadre d'impression : glisser pour le déplacer")
                }
            }
        }
    }

    /// One finger: the centre follows the finger from where it was when the finger went down.
    private func drag(_ printer: PrintController) -> some Gesture {
        DragGesture(minimumDistance: 1)
            .onChanged { value in
                guard pinchStartZoom == nil else { return }
                if dragStart == nil {
                    dragStart = model.mapProxy.point(printer.center)
                }
                guard let start = dragStart else { return }
                let target = CGPoint(x: start.x + value.translation.width, y: start.y + value.translation.height)
                if let position = model.mapProxy.position(target) {
                    printer.moveFrame(to: position)
                }
            }
            .onEnded { _ in
                dragStart = nil
            }
    }

    /// Two fingers: the map zooms, the frame keeps its place on the ground.
    private var pinch: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                guard let mapView = model.mapProxy.mapView else { return }
                if pinchStartZoom == nil {
                    pinchStartZoom = mapView.zoomLevel
                }
                let base = pinchStartZoom ?? mapView.zoomLevel
                let factor = Swift.max(Double(value.magnification), 0.01)
                mapView.setZoomLevel(base + log2(factor), animated: false)
            }
            .onEnded { _ in
                pinchStartZoom = nil
                dragStart = nil
            }
    }
}

/// What the frame is drawn from, so that SwiftUI draws it again when the camera moves.
private struct CameraKey: Equatable {
    let center: LonLat
    let zoom: Double
    let direction: Double
}

private enum PrintFrameGeometry {
    /// The four corners of the frame on the screen, clockwise from the top left, or nil when it cannot be drawn.
    static func screenCorners(_ proxy: MapProxy, _ template: PrintTemplate, _ center: LonLat, _ camera: CameraKey) -> [CGPoint]? {
        let page = template.page
        guard camera.zoom.isFinite,
              let frame = try? FrameProjection(center.lat, center.lon, template.scale, page.frameWidthMm, page.frameHeightMm)
        else { return nil }
        let ground = [
            frame.toLonLat(0.0, 0.0),
            frame.toLonLat(page.frameWidthMm, 0.0),
            frame.toLonLat(page.frameWidthMm, page.frameHeightMm),
            frame.toLonLat(0.0, page.frameHeightMm),
        ]
        var points: [CGPoint] = []
        for corner in ground {
            guard let point = proxy.point(corner), point.x.isFinite, point.y.isFinite else { return nil }
            points.append(point)
        }
        return points
    }
}

/// A closed polygon through `points`.
private struct PrintQuad: Shape {
    let points: [CGPoint]

    func path(in rect: CGRect) -> Path {
        PrintQuad.outline(points)
    }

    static func outline(_ points: [CGPoint]) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() {
            path.addLine(to: point)
        }
        path.closeSubpath()
        return path
    }
}

/// The dimmed outside, the petrol frame and the small cross at its centre.
private struct PrintFrameDrawing: View {
    let corners: [CGPoint]

    var body: some View {
        Canvas { context, size in
            let frame = PrintQuad.outline(corners)
            var outside = Path(CGRect(origin: .zero, size: size).insetBy(dx: -size.width, dy: -size.height))
            outside.addPath(frame)
            context.fill(outside, with: .color(Color.black.opacity(0.35)), style: FillStyle(eoFill: true))
            context.stroke(frame, with: .color(Crete.petrol), lineWidth: 2)
            let cx = corners.map(\.x).reduce(0, +) / CGFloat(Swift.max(corners.count, 1))
            let cy = corners.map(\.y).reduce(0, +) / CGFloat(Swift.max(corners.count, 1))
            var cross = Path()
            cross.move(to: CGPoint(x: cx - 8, y: cy))
            cross.addLine(to: CGPoint(x: cx + 8, y: cy))
            cross.move(to: CGPoint(x: cx, y: cy - 8))
            cross.addLine(to: CGPoint(x: cx, y: cy + 8))
            context.stroke(cross, with: .color(Crete.petrol), lineWidth: 1)
        }
    }
}

/// The band over the map while the frame is placed (Android `PrintFrameBar`, preview validated on 2026-10-26): the
/// format, the orientation and the scale, each a tap away, and ⟲; a white pill of the map (P29.10). The ground size and
/// the warnings wait for the confirmation.
private struct PrintFrameBar: View {
    @Environment(AppModel.self) private var model
    @State private var customPaper = false
    @State private var customScale = false

    var body: some View {
        let printer = model.printer
        let template = printer.template
        HStack(spacing: 6) {
            Menu {
                ForEach(Paper.offered, id: \.self) { paper in
                    Button(PrintWords.paperChoiceName(paper)) {
                        if paper == .custom {
                            customPaper = true
                        } else {
                            printer.update { $0.page.paper = paper }
                        }
                    }
                }
            } label: {
                PrintMenuChip(title: PrintWords.paperName(template.page))
            }
            Menu {
                Button("Portrait") { printer.update { $0.page.landscape = false } }
                Button("Paysage") { printer.update { $0.page.landscape = true } }
            } label: {
                PrintMenuChip(title: PrintWords.orientation(template.page.landscape))
            }
            Menu {
                ForEach(PrintTemplate.scales, id: \.self) { scale in
                    Button(PrintWords.scale(Double(scale))) { printer.update { $0.scale = Double(scale) } }
                }
                Button("Autre échelle…") { customScale = true }
            } label: {
                PrintMenuChip(title: PrintWords.scale(template.scale))
            }
            Button {
                printer.rotate()
            } label: {
                Image(systemName: "rotate.right")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 40, height: 36)
            }
            .accessibilityLabel("Tourner la feuille")
        }
        .pill()
        .printPaperAlert(isPresented: $customPaper, printer: printer)
        .printScaleAlert(isPresented: $customScale, printer: printer)
    }
}

/// A compact chip that opens a menu: its name and its arrow (Android `MenuChip`, 32 points tall).
private struct PrintMenuChip: View {
    let title: String

    var body: some View {
        HStack(spacing: 2) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
            Image(systemName: "arrowtriangle.down.fill")
                .font(.system(size: 8))
        }
        .foregroundStyle(Crete.onSurface)
        .padding(.leading, 10)
        .padding(.trailing, 8)
        .frame(height: 32)
        .overlay { RoundedRectangle(cornerRadius: 8).strokeBorder(PrintColors.outline, lineWidth: 1) }
    }
}
