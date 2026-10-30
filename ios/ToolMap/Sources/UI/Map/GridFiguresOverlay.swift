import SwiftUI
import ToolMapCore

/// The figures of the UTM grid at the edge of the screen (Android `GridFigures`, variant A of
/// `docs/previews/p24-utm-reperes.html`, validated on 2026-10-28): the value in km of each kilometre line where it
/// leaves the screen, the last two figures larger, as on a paper map; the eastings at the bottom, just right of their
/// line, the northings on the left, just above theirs. A figure that would run into another one, the pills or the
/// buttons is left out. The lines are projected again whenever the camera moves.
struct GridFiguresOverlay: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        // Read here, so that a move of the camera draws the figures again.
        let camera = GridFiguresCamera(center: model.center, zoom: model.zoom, direction: model.direction)
        let pills: CGFloat = model.showInfo ? model.grid.pillsBottom : 0
        ZStack {
            Color.clear
            if let lines = shownLines {
                GeometryReader { geo in
                    GridFiguresCanvas(
                        lines: lines,
                        origin: geo.frame(in: .global).origin,
                        size: geo.size,
                        pillsBottom: pills,
                        camera: camera
                    )
                }
            }
        }
        .allowsHitTesting(false)
        .onAppear { model.grid.start() }
    }

    /// The kilometre lines of the UTM grid, nil when there are none or while a frame is placed (print, zone).
    private var shownLines: [GridFigureLine]? {
        guard let lines = model.grid.plan?.figureLines, !lines.isEmpty else { return nil }
        switch model.tool {
        case .some(.print), .some(.zone):
            return nil
        default:
            return lines
        }
    }
}

/// The camera the figures were placed for: a new one draws them again.
private struct GridFiguresCamera: Equatable {
    let center: LonLat
    let zoom: Double
    let direction: Double
}

private struct GridFiguresCanvas: View {
    @Environment(AppModel.self) private var model
    let lines: [GridFigureLine]
    /// Where this view is on the screen: the map view fills the screen, its points are global ones.
    let origin: CGPoint
    let size: CGSize
    /// Bottom of the pills, global; 0 when they are hidden.
    let pillsBottom: CGFloat
    let camera: GridFiguresCamera

    var body: some View {
        let projected = lines.map { line in
            GridFigureScreenLine(line: line, points: project(line.points))
        }
        let frame = figureFrame()
        Canvas { context, _ in
            GridFigurePainter.draw(projected, in: frame, context: &context)
        }
    }

    private func project(_ points: [LonLat]) -> [CGPoint] {
        var out: [CGPoint] = []
        out.reserveCapacity(points.count)
        for point in points {
            if let p = model.mapProxy.point(point) {
                out.append(CGPoint(x: p.x - origin.x, y: p.y - origin.y))
            }
        }
        return out
    }

    /// Where the figures may go: below the pills, above the credits and the bar, clear of the buttons on the right.
    private func figureFrame() -> GridFigureFrame {
        let margin: CGFloat = 5
        let pills = pillsBottom > 0 ? pillsBottom - origin.y + margin : margin
        let bottom = size.height - GridFigureFrame.barAndCredits
        let buttons = CGRect(
            x: size.width - GridFigureFrame.buttonsWidth,
            y: size.height - GridFigureFrame.buttonsHeight,
            width: GridFigureFrame.buttonsWidth,
            height: GridFigureFrame.buttonsHeight
        )
        return GridFigureFrame(
            left: margin,
            top: max(margin, pills),
            right: size.width - margin,
            bottom: bottom,
            blocked: [buttons]
        )
    }
}

/// A kilometre line and its points on this view.
private struct GridFigureScreenLine {
    let line: GridFigureLine
    let points: [CGPoint]
}

/// Where the figures may go, in points of the view: `left` is the column of the northings, `bottom` the line the
/// eastings stand on, `top` the bottom of the pills. A figure that runs into one of `blocked` is left out.
private struct GridFigureFrame {
    let left: CGFloat
    let top: CGFloat
    let right: CGFloat
    let bottom: CGFloat
    let blocked: [CGRect]

    /// The bottom bar and the line of the credits above it.
    static let barAndCredits: CGFloat = 84
    /// The round buttons on the right, above the bar.
    static let buttonsWidth: CGFloat = 64
    static let buttonsHeight: CGFloat = 230
}

private enum GridFigurePainter {
    // As in the validated preview: dark grey with a white halo.
    private static let textColor = Color(hex: 0x1B2B31)
    private static let smallSize: CGFloat = 13
    private static let bigSize: CGFloat = 18
    private static let halo: CGFloat = 1.5
    /// Between a line and its figure.
    private static let gap: CGFloat = 3
    /// The baseline of the eastings above the bottom of the frame.
    private static let row: CGFloat = 8
    /// Around a figure, a pill or a button, so that two of them never touch.
    private static let clear: CGFloat = 3

    static func draw(_ lines: [GridFigureScreenLine], in frame: GridFigureFrame, context: inout GraphicsContext) {
        var placed: [CGRect] = []
        // The northings first, on the left: the eastings give way in the bottom left corner.
        for item in lines where !item.line.easting {
            guard let y = crossing(item.points, at: frame.left, vertical: true) else { continue }
            let text = resolve(item.line, context)
            let base = text.firstBaseline(in: text.measure(in: CGSize(width: 400, height: 100)))
            place(text, CGPoint(x: frame.left, y: y - gap - base), frame, &placed, &context)
        }
        let rowY = frame.bottom - row
        for item in lines where item.line.easting {
            guard let x = crossing(item.points, at: rowY, vertical: false) else { continue }
            let text = resolve(item.line, context)
            let base = text.firstBaseline(in: text.measure(in: CGSize(width: 400, height: 100)))
            place(text, CGPoint(x: x + gap, y: rowY - base), frame, &placed, &context)
        }
    }

    private static func resolve(_ line: GridFigureLine, _ context: GraphicsContext) -> GraphicsContext.ResolvedText {
        let figures = line.figures
        let small = Text(figures.0).font(.system(size: smallSize, weight: .regular))
        let big = Text(figures.1).font(.system(size: bigSize, weight: .bold))
        return context.resolve(small + big)
    }

    private static func place(
        _ text: GraphicsContext.ResolvedText,
        _ topLeft: CGPoint,
        _ frame: GridFigureFrame,
        _ placed: inout [CGRect],
        _ context: inout GraphicsContext
    ) {
        let size = text.measure(in: CGSize(width: 400, height: 100))
        let box = CGRect(origin: topLeft, size: size)
        let inside = box.minX >= frame.left - 1 && box.maxX <= frame.right && box.minY >= frame.top
            && box.maxY <= frame.bottom
        if !inside { return }
        let others = placed + frame.blocked
        for other in others where other.insetBy(dx: -clear, dy: -clear).intersects(box) {
            return
        }
        placed.append(box)
        var white = text
        white.shading = .color(.white)
        let offsets: [CGSize] = [
            CGSize(width: -halo, height: 0), CGSize(width: halo, height: 0),
            CGSize(width: 0, height: -halo), CGSize(width: 0, height: halo),
            CGSize(width: -halo, height: -halo), CGSize(width: halo, height: halo),
            CGSize(width: -halo, height: halo), CGSize(width: halo, height: -halo),
        ]
        for offset in offsets {
            let at = CGPoint(x: topLeft.x + offset.width, y: topLeft.y + offset.height)
            context.draw(white, at: at, anchor: .topLeading)
        }
        var dark = text
        dark.shading = .color(textColor)
        context.draw(dark, at: topLeft, anchor: .topLeading)
    }

    /// Where the line crosses the vertical x = `at` (its y) or the horizontal y = `at` (its x), the first crossing
    /// along it; nil when it does not cross (Android `crossing`).
    static func crossing(_ points: [CGPoint], at: CGFloat, vertical: Bool) -> CGFloat? {
        guard points.count >= 2 else { return nil }
        for i in 0 ..< points.count - 1 {
            let a = points[i]
            let b = points[i + 1]
            let da = (vertical ? a.x : a.y) - at
            let db = (vertical ? b.x : b.y) - at
            if da == 0 { return vertical ? a.y : a.x }
            if da * db < 0 {
                let t = da / (da - db)
                return vertical ? a.y + (b.y - a.y) * t : a.x + (b.x - a.x) * t
            }
        }
        return nil
    }
}
