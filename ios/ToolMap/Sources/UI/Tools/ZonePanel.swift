import SwiftUI
import ToolMapCore

/// Enregistrer une zone (Android T5, `maps/SaveAreaPanel.kt`, P29.7 « Poids », ADR-080, variant B of
/// `docs/previews/design-07-telechargements.html`), or « Enregistrer autour du tracé » (P30.2): the weight of the zone in
/// large with the room left on the phone, the width of a band, the maximal zoom, the maps folded in one line that
/// unfolds their rows and zoom strips, and « Enregistrer la zone » across the panel. The states of the total
/// (computing, too large, the limit of a community server, no tile, already saved) take the place of the large figure.
struct ZonePanel: View {
    @Environment(AppModel.self) private var model
    let around: ItemRefKey?
    @State private var unfolded = false
    @State private var showHelp = false

    var body: some View {
        let zone = model.downloads.zone
        ToolPanel(title: zone?.corridor != nil ? "Enregistrer autour du tracé" : "Enregistrer une zone", onClose: {
            model.downloads.endZone()
        }) {
            if let zone {
                ZonePanelContent(zone: zone, tileZoom: tileZoom, unfolded: $unfolded, showHelp: $showHelp)
            } else {
                Text("Estimation en cours…")
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .onAppear {
            model.downloads.beginZone(around: around)
        }
        .onChange(of: around) { _, value in
            model.downloads.beginZone(around: value)
        }
        .onDisappear {
            if case .some(.zone) = model.tool { return }
            model.downloads.zoneClosed()
        }
        .alert("Enregistrer une zone", isPresented: $showHelp) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(ZoneText.help)
        }
    }

    /// The zoom of the tiles on screen (ADR-032), marked on the zoom strips.
    private var tileZoom: Double {
        model.zoom + log2(512.0 / Double(MapStyle.declaredTileSize))
    }
}

private struct ZonePanelContent: View {
    @Environment(AppModel.self) private var model
    let zone: ZoneDraft
    let tileZoom: Double
    @Binding var unfolded: Bool
    @Binding var showHelp: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZoneWeightRow(zone: zone, freeBytes: model.downloads.space.free, showHelp: $showHelp)
            if let band = zone.corridor {
                ZoneCorridorWidthRow(radius: band.radiusMeters) { meters in
                    model.downloads.setCorridorRadius(meters)
                }
            }
            if zone.sliderMax > 0 {
                ZoneMaxZoomRow(maxZoom: zone.maxZoom, sliderMax: zone.sliderMax) { value in
                    model.downloads.setMaxZoom(value)
                }
            }
            Divider()
            if zone.rows.isEmpty {
                Text(ZoneText.noMaps)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                    .padding(.vertical, 6)
            } else {
                ZoneMapsLine(rows: zone.rows, unfolded: $unfolded)
                if unfolded {
                    ZoneMapRows(zone: zone, tileZoom: tileZoom)
                }
            }
            saveButton
        }
    }

    private var saveButton: some View {
        Button {
            model.downloads.saveZone()
        } label: {
            Text(zone.corridor != nil ? "Enregistrer la bande" : "Enregistrer la zone")
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 34)
        }
        .buttonStyle(.borderedProminent)
        .tint(Crete.turquoise)
        .disabled(!zone.canSave || zone.estimating)
        .padding(.top, 4)
    }
}

/// « 9,5 Mo » in large with « 5,0 Go libres », then « 415 tuiles à télécharger · 31 déjà stockées »; a state of the
/// total in place of the figure while there is none.
private struct ZoneWeightRow: View {
    let zone: ZoneDraft
    let freeBytes: Int64
    @Binding var showHelp: Bool

    var body: some View {
        let estimate = zone.estimating ? nil : zone.estimate
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .center, spacing: 8) {
                ZoneTotal(estimate: estimate)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if freeBytes > 0 {
                    HStack(spacing: 4) {
                        Image(systemName: "iphone")
                        Text(ZoneText.free(freeBytes))
                    }
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
                }
                Button {
                    showHelp = true
                } label: {
                    Image(systemName: "questionmark.circle")
                        .font(.system(size: 18))
                        .foregroundStyle(Crete.petrol)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Aide")
            }
            if let line = ZoneText.tilesLine(estimate) {
                Text(line)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
    }
}

private struct ZoneTotal: View {
    let estimate: ZoneEstimate?

    var body: some View {
        if let estimate, estimate.perSource != nil, estimate.missing > 0 {
            Text(ZoneText.bytes(Int64(estimate.bytes)))
                .font(.system(size: 28, weight: .medium))
                .foregroundStyle(Crete.onSurface)
        } else {
            Text(ZoneText.totalState(estimate))
                .font(.headline)
                .foregroundStyle(ZoneText.isError(estimate) ? Crete.danger : Crete.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// « Largeur de chaque côté » of a band (P30.2): a slider from 50 m to 5 km on a logarithmic scale, the width over it,
/// which a touch opens to type it. The band and its weight follow when the slider is let go.
private struct ZoneCorridorWidthRow: View {
    let radius: Int
    let onWidth: (Int) -> Void
    @State private var fraction = 0.0
    @State private var moved = false
    @State private var typing = false
    @State private var typed = ""

    var body: some View {
        let shown = moved ? CorridorWidth.fromFraction(fraction) : radius
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Largeur de chaque côté")
                    .foregroundStyle(Crete.onSurface)
                Spacer()
                Button {
                    typed = String(radius)
                    typing = true
                } label: {
                    Text("± " + ZoneText.meters(shown))
                        .font(.headline)
                        .underline()
                        .foregroundStyle(Crete.petrol)
                        .frame(minHeight: 36)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Taper la largeur")
            }
            Slider(value: $fraction, in: 0 ... 1, onEditingChanged: { editing in
                if editing {
                    moved = true
                } else {
                    onWidth(CorridorWidth.fromFraction(fraction))
                }
            })
            .tint(Crete.turquoise)
            .accessibilityValue("± " + ZoneText.meters(shown))
            HStack {
                Text(ZoneText.meters(CorridorWidth.min))
                Spacer()
                Text(ZoneText.meters(CorridorWidth.defaultWidth))
                Spacer()
                Text(ZoneText.meters(CorridorWidth.max))
            }
            .font(.caption)
            .foregroundStyle(Crete.onSurfaceVariant)
        }
        .onAppear {
            fraction = CorridorWidth.toFraction(radius)
        }
        .onChange(of: radius) { _, value in
            fraction = CorridorWidth.toFraction(value)
            moved = false
        }
        .alert("Largeur de chaque côté", isPresented: $typing) {
            TextField("m", text: $typed)
                .keyboardType(.numberPad)
            Button("OK") {
                let digits = typed.filter(\.isNumber)
                if let meters = Int(String(digits.prefix(5))) {
                    onWidth(CorridorWidth.clamp(meters))
                }
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("De 50 à 5 000 m")
        }
    }
}

/// « Zoom maximal » with its value at the right, then its slider.
private struct ZoneMaxZoomRow: View {
    let maxZoom: Int
    let sliderMax: Int
    let onChange: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Zoom maximal")
                    .foregroundStyle(Crete.onSurface)
                Spacer()
                Text("\(maxZoom)")
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(Crete.onSurface)
            }
            Slider(value: zoomBinding, in: 0 ... Double(sliderMax), step: 1)
                .tint(Crete.turquoise)
                .accessibilityLabel("Zoom maximal")
        }
    }

    private var zoomBinding: Binding<Double> {
        Binding(
            get: { Double(maxZoom) },
            set: { onChange(Int($0.rounded())) }
        )
    }
}

/// « Cartes » folded: the names of the maps checked and how many of the maps offered they are.
private struct ZoneMapsLine: View {
    let rows: [ZoneAreaRow]
    @Binding var unfolded: Bool

    var body: some View {
        Button {
            unfolded.toggle()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "square.stack.3d.up")
                    .foregroundStyle(Crete.onSurface)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cartes")
                        .foregroundStyle(Crete.onSurface)
                    Text(ZoneText.mapsSummary(rows))
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(2)
                }
                Spacer(minLength: 4)
                Image(systemName: unfolded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(unfolded ? "Replier le groupe" : "Déplier le groupe")
    }
}

/// The rows of the maps, scrolled beyond 216 points so that the map stays seen.
private struct ZoneMapRows: View {
    let zone: ZoneDraft
    let tileZoom: Double

    var body: some View {
        ViewThatFits(in: .vertical) {
            rows
            ScrollView {
                rows
            }
        }
        .frame(maxHeight: 216)
    }

    private var rows: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(zone.rows) { row in
                ZoneMapRow(row: row, zone: zone, tileZoom: tileZoom)
            }
        }
    }
}

/// One map: its box, its name, « Zooms 6 à 16 (sur 18) · 189 tuiles à télécharger · 4,4 Mo · 31 déjà stockées » and its
/// zoom strip with « 0 » and the deepest zoom at its ends.
private struct ZoneMapRow: View {
    @Environment(AppModel.self) private var model
    let row: ZoneAreaRow
    let zone: ZoneDraft
    let tileZoom: Double

    var body: some View {
        Button {
            model.downloads.setChecked(row.id, !row.checked)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: row.checked ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundStyle(row.checked ? Crete.turquoise : Crete.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 3) {
                    Text(ZoneText.rowName(row))
                        .lineLimit(1)
                        .foregroundStyle(Crete.onSurface)
                    Text(ZoneText.rowDetail(row, zone))
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 6) {
                        Text("0")
                        ZoneZoomStrip(
                            cells: ZoneStrips.cells(
                                sourceMin: row.minZoom,
                                sourceMax: row.maxZoom,
                                chosenMax: zone.maxZoom,
                                scaleMax: zone.sliderMax
                            ),
                            marker: ZoneStrips.marker(tileZoom, scaleMax: zone.sliderMax),
                            checked: row.checked
                        )
                        Text("\(zone.sliderMax)")
                    }
                    .font(.caption2)
                    .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(row.checked ? .isSelected : [])
    }
}

/// One cell per zoom: saved, kept out by the chosen maximum, or not served by the map; a red mark shows the zoom on
/// screen.
struct ZoneZoomStrip: View {
    let cells: [ZoomCell]
    let marker: Float?
    let checked: Bool

    var body: some View {
        let saved = checked ? Crete.petrol : Crete.onSurfaceVariant.opacity(0.6)
        let skipped = Crete.outline
        let absent = Crete.surfaceVariant.opacity(0.5)
        Canvas { context, size in
            guard !cells.isEmpty else { return }
            let width = size.width / CGFloat(cells.count)
            for (index, cell) in cells.enumerated() {
                let rect = CGRect(x: CGFloat(index) * width + 0.5, y: 1, width: max(width - 1, 1), height: 8)
                let color: Color
                switch cell {
                case .saved: color = saved
                case .skipped: color = skipped
                case .absent: color = absent
                }
                context.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(color))
            }
            if let marker {
                let x = CGFloat(marker) * size.width
                var line = Path()
                line.move(to: CGPoint(x: x, y: 0))
                line.addLine(to: CGPoint(x: x, y: 11))
                context.stroke(line, with: .color(Crete.danger), lineWidth: 2)
            }
        }
        .frame(height: 12)
        .accessibilityHidden(true)
    }
}
