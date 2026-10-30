import SwiftUI
import ToolMapCore

/// « Conditions d'affichage » of a layer (Android `DisplayConditionsPanel.kt`, P20, compact since P29.3): in the maps
/// panel, the map staying visible above it; under the petrol band (title, name of the map, ✕ back to the panel with the
/// line still unfolded), the zoom range and the elevation band as bars in line with their label; ET / OU and its
/// sentence only when both are set. A bar applies its range when the finger lifts (the map is redrawn then); the choice
/// ET / OU and the resets apply at once.
struct DisplayConditionsView: View {
    @Environment(AppModel.self) private var model
    let layer: LayerConfig

    @State private var zoomDraft = ZoomBar.Range(0, ZoomBar.max)
    @State private var bandDraft = ZoomBar.Range(0, ElevationBar.steps)
    @State private var centerElevation: Double? = nil

    var body: some View {
        let maps = model.maps
        let name = maps.source(layer.sourceId)?.name ?? layer.sourceId
        VStack(spacing: 0) {
            MapsBand(title: "Conditions d'affichage", subtitle: name) {
                MapsBandButton(systemImage: "xmark", label: "Fermer") {
                    maps.conditionsLayer = nil
                }
            }
            ScrollView {
                VStack(spacing: 4) {
                    zoomRow
                    bandRow
                    scaleLegend
                    if layer.hasZoomRange, layer.hasElevationRange {
                        logicChoice
                    }
                }
                .padding(.vertical, 8)
            }
            .background(Color.white)
        }
        .onAppear(perform: syncDrafts)
        .onChange(of: layer) { _, _ in
            syncDrafts()
        }
        .task(id: centerKey) {
            centerElevation = await DisplayConditionsView.elevation(at: model.center)
        }
    }

    /// The layer as the bars show it while a finger moves them.
    private var draft: LayerConfig {
        let zooms = ZoomBar.toBounds(zoomDraft)
        let band = ElevationBar.toBounds(bandDraft)
        var copy = layer
        copy.displayMinZoom = zooms.0
        copy.displayMaxZoom = zooms.1
        copy.displayMinElevation = band.0
        copy.displayMaxElevation = band.1
        return copy
    }

    private var zoomRow: some View {
        let maps = model.maps
        let zooms = ZoomBar.toBounds(zoomDraft)
        let drawnNow = LayerElevation.isDrawnAt(draft, maps.displayedZoom)
        let reset: (() -> Void)? = layer.hasZoomRange ? { maps.setZoomRange(layer.id, nil, nil) } : nil
        return ConditionsBarRow(label: "Zoom", value: MapsText.zoomShort(zooms.0, zooms.1), onReset: reset) {
            ConditionsRangeBar(
                range: zoomDraft,
                max: ZoomBar.max,
                cursor: maps.displayedZoom,
                active: drawnNow,
                onChange: { zoomDraft = $0 },
                onRelease: {
                    let bounds = ZoomBar.toBounds(zoomDraft)
                    if bounds.0 != layer.displayMinZoom || bounds.1 != layer.displayMaxZoom {
                        maps.setZoomRange(layer.id, bounds.0, bounds.1)
                    }
                }
            )
            .accessibilityLabel("Plage de zoom du calque")
            .accessibilityValue(MapsText.zoomSummary(zooms.0, zooms.1))
        }
    }

    private var bandRow: some View {
        let maps = model.maps
        let band = ElevationBar.toBounds(bandDraft)
        let drawnNow = LayerElevation.isDrawnAt(draft, maps.displayedZoom)
        let logic = layer.rangeLogic
        let reset: (() -> Void)? = layer.hasElevationRange ? { maps.setElevationRange(layer.id, nil, nil, logic) } : nil
        return ConditionsBarRow(label: "Altitude", value: MapsText.bandShort(band.0, band.1), onReset: reset) {
            ConditionsRangeBar(
                range: bandDraft,
                max: ElevationBar.steps,
                cursor: centerElevation.map { ElevationBar.positionOf($0) },
                active: drawnNow,
                onChange: { bandDraft = $0 },
                onRelease: {
                    let bounds = ElevationBar.toBounds(bandDraft)
                    if bounds.0 != layer.displayMinElevation || bounds.1 != layer.displayMaxElevation {
                        maps.setElevationRange(layer.id, bounds.0, bounds.1, logic)
                    }
                }
            )
            .accessibilityLabel("Plage d'altitude du calque")
            .accessibilityValue(MapsText.bandSummary(band.0, band.1))
        }
    }

    /// « 0 m · centre N m · 4800 m et + » under the elevation bar.
    private var scaleLegend: some View {
        HStack {
            Text("0 m")
            Spacer()
            if let center = centerElevation {
                Text("centre \(Int(center.rounded())) m")
                    .foregroundStyle(Crete.warning)
                Spacer()
            }
            Text("\(ElevationBar.maxMeters) m et +")
        }
        .font(.caption2)
        .foregroundStyle(Crete.onSurfaceVariant)
        .padding(.leading, 96)
        .padding(.trailing, 56)
    }

    /// ET / OU, and the sentence saying where the layer is drawn (only when both conditions are set).
    private var logicChoice: some View {
        let maps = model.maps
        let selection = Binding<RangeLogic>(
            get: { layer.rangeLogic },
            set: { maps.setElevationRange(layer.id, layer.displayMinElevation, layer.displayMaxElevation, $0) }
        )
        return VStack(spacing: 8) {
            Picker("Logique", selection: selection) {
                Text("ET").tag(RangeLogic.and)
                Text("OU").tag(RangeLogic.or)
            }
            .pickerStyle(.segmented)
            .frame(width: 168)
            Text(MapsText.explanation(draft))
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
        }
        .padding(.top, 10)
    }

    private func syncDrafts() {
        zoomDraft = ZoomBar.rangeOf(layer.displayMinZoom, layer.displayMaxZoom)
        bandDraft = ElevationBar.rangeOf(layer.displayMinElevation, layer.displayMaxElevation)
    }

    /// The map centre rounded to about 100 m: the elevation is read again when it moves further.
    private var centerKey: String {
        let lat = (model.center.lat * 1000).rounded()
        let lon = (model.center.lon * 1000).rounded()
        return "\(lat),\(lon)"
    }

    /// Ground elevation at `point` from the elevation tiles (`terrarium`, zoom 13), or nil when they are not available.
    static func elevation(at point: LonLat) async -> Double? {
        let zoom = DemWindow.maxDemZoom
        guard let tile = try? TileMath.tileAt(point.lon, point.lat, zoom) else { return nil }
        let window = DemWindow.of(zoom, tile.x, tile.y)
        let loaded: MaskDemGrid? = await withCheckedContinuation { (continuation: CheckedContinuation<MaskDemGrid?, Never>) in
            TileElevationMasker.shared.dem(window) { found in
                continuation.resume(returning: found)
            }
        }
        guard let grid = loaded,
              let fraction = try? TerrariumTile.fractionIn(point.lon, point.lat, zoom, tile.x, tile.y),
              let terrain = try? TerrariumTile(size: grid.size, elevations: grid.elevations)
        else { return nil }
        return terrain.elevationAt(fraction.0, fraction.1)
    }
}

/// A condition in one line: its name and value on the left, its bar, « Réinitialiser » as an icon at the end.
private struct ConditionsBarRow<Bar: View>: View {
    let label: String
    let value: String
    let onReset: (() -> Void)?
    let bar: () -> Bar

    init(label: String, value: String, onReset: (() -> Void)?, @ViewBuilder bar: @escaping () -> Bar) {
        self.label = label
        self.value = value
        self.onReset = onReset
        self.bar = bar
    }

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 1) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(width: 76, alignment: .leading)
            bar()
            if let onReset {
                Button(action: onReset) {
                    Image(systemName: "arrow.counterclockwise")
                        .foregroundStyle(Crete.turquoise)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Réinitialiser")
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .frame(minHeight: 56)
    }
}

/// A bar with two handles (Android `RangeBar`): `max` positions, the selected range in turquoise while the layer is
/// drawn, grey otherwise, and a mark at `cursor` (the zoom of the map, the elevation of its centre). The handle nearest
/// to the finger follows it (`ZoomBar`).
struct ConditionsRangeBar: View {
    let range: ZoomBar.Range
    let max: Int
    let cursor: Double?
    let active: Bool
    let onChange: (ZoomBar.Range) -> Void
    let onRelease: () -> Void

    @State private var handle: ZoomBar.Handle? = nil

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let low = CGFloat(ZoomBar.fractionOf(Double(range.min), max)) * width
            let high = CGFloat(ZoomBar.fractionOf(Double(range.max), max)) * width
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Crete.outline)
                    .frame(height: 4)
                Capsule()
                    .fill(active ? Crete.turquoise : Crete.onSurfaceVariant)
                    .frame(width: Swift.max(high - low, 4), height: 4)
                    .offset(x: low)
                if let cursor {
                    Rectangle()
                        .fill(Crete.warning)
                        .frame(width: 2, height: 18)
                        .offset(x: CGFloat(ZoomBar.fractionOf(cursor, max)) * width - 1)
                }
                thumb.offset(x: low - 11)
                thumb.offset(x: high - 11)
            }
            .frame(width: width, height: 44)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let x = Float(Swift.min(Swift.max(value.location.x, 0), width))
                        let chosen = handle ?? ZoomBar.nearestHandle(x, Float(width), range, max)
                        handle = chosen
                        let zoom = ZoomBar.zoomAt(x, Float(width), max)
                        let moved = ZoomBar.moved(range, chosen, zoom, max)
                        if moved != range { onChange(moved) }
                    }
                    .onEnded { _ in
                        handle = nil
                        onRelease()
                    }
            )
        }
        .frame(height: 44)
    }

    private var thumb: some View {
        Circle()
            .fill(Color.white)
            .frame(width: 22, height: 22)
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .overlay(Circle().stroke(active ? Crete.turquoise : Crete.onSurfaceVariant, lineWidth: 2))
    }
}
