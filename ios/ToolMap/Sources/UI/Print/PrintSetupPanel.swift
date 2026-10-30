import SwiftUI
import ToolMapCore

/// Step 1 (P17.8, variant E « Épreuve » of docs/previews/p17-impression-menus.html, ADR-098): the sheets drawn to the
/// same scale, the orientation (or the size of a custom sheet, with no largest), the scale and the ground the frame
/// covers, in a panel at the foot of the map with the frame drawn above it. « Choisir la zone » then lets the frame be
/// moved.
struct PrintSetupPanel: View {
    @Environment(AppModel.self) private var model
    @State private var customScale = false

    var body: some View {
        let printer = model.printer
        PrintChrome(title: "Imprimer à l'échelle") {
            PrintFittedScroll(maxHeight: UIScreen.main.bounds.height * 0.45) {
                PrintSetupContent(customScale: $customScale)
                    .padding(.vertical, 10)
            }
        } footer: {
            PrintButtonRow(
                cancelTitle: "Annuler",
                mainTitle: "Choisir la zone",
                onCancel: { printer.close() },
                onMain: { printer.chooseZone() }
            )
        }
        .printScaleAlert(isPresented: $customScale, printer: printer)
    }
}

private struct PrintSetupContent: View {
    @Environment(AppModel.self) private var model
    @Binding var customScale: Bool

    var body: some View {
        let printer = model.printer
        let page = printer.template.page
        VStack(alignment: .leading, spacing: 10) {
            PrintSheetCards(page: page) { paper in
                printer.update { $0.page.paper = paper }
            }
            Group {
                if page.paper == .custom {
                    PrintCustomSize(page: page)
                } else {
                    PrintOrientationChoice(landscape: page.landscape) { landscape in
                        printer.update { $0.page.landscape = landscape }
                    }
                }
                PrintSectionTitle(title: "Échelle")
                PrintScaleChips(scale: printer.template.scale, onScale: { scale in
                    printer.update { $0.scale = scale }
                }, onOther: { customScale = true })
                PrintGroundLine(template: printer.template)
                if let heavy = PrintWords.heavy(printer.plan) {
                    PrintHeavyLine(dpi: heavy.dpi, pixels: heavy.pixels, plan: printer.plan)
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

/// The sheets offered, each drawn at the same millimetres per point; the custom one last, fitted to its card.
private struct PrintSheetCards: View {
    let page: PageSetup
    let onPaper: (Paper) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 10) {
                    ForEach(Paper.offered, id: \.self) { paper in
                        PrintSheetCard(paper: paper, page: page, selected: page.paper == paper) {
                            onPaper(paper)
                        }
                        .id(paper)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
            .onChange(of: page.paper == .custom) { _, custom in
                // The custom sheet is the last card: the row comes to it once it is chosen.
                if custom {
                    withAnimation { proxy.scrollTo(Paper.custom, anchor: .trailing) }
                }
            }
        }
    }
}

private struct PrintSheetCard: View {
    let paper: Paper
    let page: PageSetup
    let selected: Bool
    let action: () -> Void

    private var other: Bool { paper == .custom }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                thumbnail
                Text(other ? "Autre" : PrintWords.paperChoiceName(paper))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(selected ? PrintColors.onSecondaryContainer : Crete.onSurface)
                Text(dimensions)
                    .font(.caption2)
                    .foregroundStyle(selected ? PrintColors.onSecondaryContainer : Crete.onSurfaceVariant)
            }
            .frame(minWidth: 64)
            .padding(8)
            .background(selected ? PrintColors.secondaryContainer : Color.clear, in: RoundedRectangle(cornerRadius: 14))
            .overlay { border }
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? AccessibilityTraits.isSelected : AccessibilityTraits())
    }

    @ViewBuilder
    private var thumbnail: some View {
        if !other || selected {
            let sheet = sheetOf(paper)
            let size = PrintSheetThumbnail.size(sheet, fitted: other)
            PrintSheetThumbnail(setup: sheet)
                .frame(width: size.width, height: size.height)
        } else {
            Color.clear
                .frame(width: 44, height: 44)
        }
    }

    @ViewBuilder
    private var border: some View {
        if selected {
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(Crete.turquoise, lineWidth: 2)
        } else if other {
            // The custom card waits for its size with a dashed edge, as a sheet still to be cut.
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(PrintColors.outline, style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
        } else {
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(Crete.outline, lineWidth: 1)
        }
    }

    private var dimensions: String {
        if !other { return PrintWords.sizeText(paper.widthMm, paper.heightMm) }
        if selected { return PrintWords.sizeText(page.widthMm, page.heightMm) }
        return "en mm"
    }

    /// The current sheet on `paper`: its orientation and margin are kept.
    private func sheetOf(_ paper: Paper) -> PageSetup {
        var sheet = page
        sheet.paper = paper
        return sheet
    }
}

/// Width and height of a custom sheet, applied as soon as both are numbers of at least 50 mm; no largest.
private struct PrintCustomSize: View {
    @Environment(AppModel.self) private var model
    @State private var width: String
    @State private var height: String

    init(page: PageSetup) {
        _width = State(initialValue: String(Int(page.widthMm.rounded())))
        _height = State(initialValue: String(Int(page.heightMm.rounded())))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                PrintTextField(label: "Largeur", text: $width, suffix: "mm", valid: isValid(width), keyboard: .decimalPad)
                PrintTextField(label: "Hauteur", text: $height, suffix: "mm", valid: isValid(height), keyboard: .decimalPad)
            }
            Text("50 mm au moins, pas de maximum")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
                .padding(.leading, 4)
        }
        .onChange(of: width) { _, _ in push() }
        .onChange(of: height) { _, _ in push() }
    }

    private func isValid(_ text: String) -> Bool {
        guard let value = PrintWords.millimetres(text) else { return false }
        return PageSetup.isCustomSide(value)
    }

    private func push() {
        guard let w = PrintWords.millimetres(width), let h = PrintWords.millimetres(height),
              PageSetup.isCustomSide(w), PageSetup.isCustomSide(h)
        else { return }
        model.printer.update {
            $0.page.paper = .custom
            $0.page.customWidthMm = w
            $0.page.customHeightMm = h
            $0.page.landscape = w >= h
        }
    }
}

/// « Portrait | Paysage ».
private struct PrintOrientationChoice: View {
    let landscape: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Picker(
            "Orientation",
            selection: Binding(get: { landscape }, set: { onChange($0) })
        ) {
            Text("Portrait").tag(false)
            Text("Paysage").tag(true)
        }
        .pickerStyle(.segmented)
    }
}

/// The scales offered on one line that scrolls, then « Autre échelle… » (or the scale typed).
private struct PrintScaleChips: View {
    let scale: Double
    let onScale: (Double) -> Void
    let onOther: () -> Void

    var body: some View {
        let other = !PrintTemplate.scales.contains(Int(scale.rounded()))
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(PrintTemplate.scales, id: \.self) { offered in
                    PrintChip(title: PrintWords.scale(Double(offered)), selected: scale == Double(offered)) {
                        onScale(Double(offered))
                    }
                }
                PrintChip(title: other ? PrintWords.scale(scale) : "Autre échelle…", selected: other, action: onOther)
            }
        }
    }
}

/// « Sur le terrain 6,6 × 3,7 km ».
private struct PrintGroundLine: View {
    let template: PrintTemplate

    var body: some View {
        let ground = PrintWords.groundSize(template)
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("Sur le terrain")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
            Text("\(ground.0) × \(ground.1)")
                .font(.body.weight(.medium))
                .foregroundStyle(Crete.onSurface)
        }
    }
}

/// The orange line of a heavy PDF under the sheet (P17.9): its weight, its risk, where the resolution is set.
private struct PrintHeavyLine: View {
    let dpi: Int
    let pixels: Int
    let plan: PrintPlan?

    var body: some View {
        let low = PrintWords.bytes(plan?.estimatedBytes.0 ?? 0)
        let high = PrintWords.bytes(plan?.estimatedBytes.1 ?? 0)
        let megapixels = PrintWords.megapixels(pixels)
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.footnote)
            Text(
                "À \(dpi) dpi, page de \(megapixels) Mpx et PDF de \(low) à \(high) : création longue, échec possible. Résolution réglable dans « Qualité et marges »."
            )
            .font(.footnote.weight(.medium))
            .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Crete.warning)
    }
}

/// A sheet of the print in miniature (Android `SheetThumbnail`): the paper, the map frame with its double line and a
/// few grid lines, and the boxes of the footer, all placed by the real layout (`PageSetup`, `SheetLayout`); no tiles.
/// The colours are those of the printed sheet, the same by day and by night.
struct PrintSheetThumbnail: View {
    let setup: PageSetup

    private static let edge = Color(hex: 0x9AA9AE)
    private static let map = Color(hex: 0xE4EBCF)
    private static let grid = Color(hex: 0x1D3D8F).opacity(0.6)
    private static let ink = Color(hex: 0x1B2A30)
    private static let box = Color(hex: 0xC4CDD0)
    private static let title = Color(hex: 0x163A4A)

    /// The size a sheet is drawn at among the others: A3, the largest offered, is 74 points tall and the others follow
    /// at the same scale; a custom sheet is fitted in 96 × 74 points.
    static func size(_ setup: PageSetup, fitted: Bool) -> CGSize {
        let w = setup.widthMm
        let h = setup.heightMm
        guard w > 0, h > 0 else { return CGSize(width: 44, height: 44) }
        let pointsPerMm = fitted ? Swift.min(96.0 / w, 74.0 / h) : 74.0 / 420.0
        return CGSize(width: w * pointsPerMm, height: h * pointsPerMm)
    }

    var body: some View {
        Canvas { context, size in
            draw(&context, size)
        }
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize) {
        guard setup.widthMm > 0 else { return }
        let k = size.width / CGFloat(setup.widthMm)
        let hairline: CGFloat = 0.5
        let paper = Path(CGRect(origin: .zero, size: size))
        context.fill(paper, with: .color(.white))
        context.stroke(paper, with: .color(PrintSheetThumbnail.edge), lineWidth: hairline)
        let left = CGFloat(setup.frameLeftMm) * k
        let top = CGFloat(setup.frameTopMm) * k
        let w = CGFloat(setup.frameWidthMm) * k
        let h = CGFloat(setup.frameHeightMm) * k
        guard w > 0, h > 0 else { return }
        let frame = CGRect(x: left, y: top, width: w, height: h)
        context.fill(Path(frame), with: .color(PrintSheetThumbnail.map))
        var lines = Path()
        for i in 1 ..< 4 {
            let x = left + w * CGFloat(i) / 4
            let y = top + h * CGFloat(i) / 4
            lines.move(to: CGPoint(x: x, y: top))
            lines.addLine(to: CGPoint(x: x, y: top + h))
            lines.move(to: CGPoint(x: left, y: y))
            lines.addLine(to: CGPoint(x: left + w, y: y))
        }
        context.stroke(lines, with: .color(PrintSheetThumbnail.grid), lineWidth: hairline)
        context.stroke(Path(frame), with: .color(PrintSheetThumbnail.ink), lineWidth: hairline * 2)
        let band = CGFloat(PageSetup.graduationMm) * k
        context.stroke(Path(frame.insetBy(dx: -band, dy: -band)), with: .color(PrintSheetThumbnail.ink), lineWidth: hairline)
        drawFooter(&context, k, hairline)
    }

    /// The boxes of the footer: the title in petrol, the scale bar, the north, the grid.
    private func drawFooter(_ context: inout GraphicsContext, _ k: CGFloat, _ hairline: CGFloat) {
        let footerLeft = CGFloat(setup.marginMm) * k
        let footerTop = CGFloat(setup.footerTopMm) * k
        for placed in SheetLayout.place(setup.widthMm - 2 * setup.marginMm) {
            let bx = footerLeft + CGFloat(placed.xMm) * k
            let by = footerTop + CGFloat(placed.yMm) * k
            let bw = CGFloat(placed.widthMm) * k
            let bh = CGFloat(placed.heightMm) * k
            context.stroke(Path(CGRect(x: bx, y: by, width: bw, height: bh)), with: .color(PrintSheetThumbnail.box), lineWidth: hairline)
            let pad = bh * 0.15
            switch placed.block {
            case .title:
                let rect = CGRect(x: bx + pad, y: by + pad, width: bw * 0.6, height: bh * 0.2)
                context.fill(Path(rect), with: .color(PrintSheetThumbnail.title))
            case .scale:
                let step = (bw - 2 * pad) / 4
                for j in 0 ..< 4 {
                    let cell = CGRect(x: bx + pad + CGFloat(j) * step, y: by + bh * 0.55, width: step, height: bh * 0.15)
                    if j % 2 == 0 {
                        context.fill(Path(cell), with: .color(PrintSheetThumbnail.ink))
                    } else {
                        context.stroke(Path(cell), with: .color(PrintSheetThumbnail.ink), lineWidth: hairline)
                    }
                }
            case .north:
                let cx = bx + bw / 2
                var arrow = Path()
                arrow.move(to: CGPoint(x: cx, y: by + pad))
                arrow.addLine(to: CGPoint(x: cx + bh * 0.18, y: by + bh - pad))
                arrow.addLine(to: CGPoint(x: cx, y: by + bh * 0.7))
                arrow.addLine(to: CGPoint(x: cx - bh * 0.18, y: by + bh - pad))
                arrow.closeSubpath()
                context.fill(arrow, with: .color(PrintSheetThumbnail.ink))
            case .grid:
                let rect = CGRect(x: bx + pad, y: by + pad, width: bw * 0.4, height: bh * 0.12)
                context.fill(Path(rect), with: .color(PrintSheetThumbnail.grid))
            }
        }
    }
}
