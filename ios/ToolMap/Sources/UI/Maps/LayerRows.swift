import SwiftUI
import ToolMapCore

/// A line of the layer stack in « Cartes et calques » (Android `StackRows.kt`, P29.3): the handle, the thumbnail
/// (ringed in turquoise when the map draws it at its zoom, faded when it does not), the name and state, the arrow and the
/// eye. A tap unfolds the line in place (one at a time): the opacity, then Monter · Descendre · Conditions · Couleurs ·
/// Plus, the colours unfolding under them.
struct MapsLayerRow: View {
    @Environment(AppModel.self) private var model
    let layer: LayerConfig
    let canUp: Bool
    let canDown: Bool
    /// The handle: the order of the lines can then be changed by dragging them.
    let onReorder: () -> Void
    let onDetails: () -> Void

    var body: some View {
        let open = model.maps.expandedLayer == layer.id
        VStack(alignment: .leading, spacing: 10) {
            line(open: open)
            if open {
                MapsLayerSettings(layer: layer, canUp: canUp, canDown: canDown, onDetails: onDetails)
            }
        }
        .padding(.vertical, 2)
    }

    private func line(open: Bool) -> some View {
        let maps = model.maps
        let source = maps.source(layer.sourceId)
        let drawn = maps.isDrawn(layer)
        let name = source?.name ?? "Carte introuvable (\(layer.sourceId))"
        return HStack(spacing: 10) {
            Button(action: onReorder) {
                Image(systemName: "line.3.horizontal")
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .frame(width: 28, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Faire glisser pour changer l'ordre")
            .accessibilityAction(named: "Monter") {
                if canUp { maps.moveUp(layer.id) }
            }
            .accessibilityAction(named: "Descendre") {
                if canDown { maps.moveDown(layer.id) }
            }
            Button {
                withAnimation(.easeOut(duration: 0.2)) {
                    maps.expandedLayer = open ? nil : layer.id
                }
            } label: {
                HStack(spacing: 10) {
                    MapsThumbnail(source: source, size: 32)
                        .padding(2)
                        .overlay(
                            RoundedRectangle(cornerRadius: 9)
                                .stroke(drawn ? Crete.turquoise : Color.clear, lineWidth: 2)
                        )
                        .opacity(drawn ? 1 : 0.5)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name)
                            .font(.subheadline.weight(drawn ? .medium : .regular))
                            .foregroundStyle(Crete.onSurface)
                            .lineLimit(1)
                        Text(MapsText.layerState(layer, drawnNow: LayerElevation.isDrawnAt(layer, maps.displayedZoom)))
                            .font(.caption)
                            .foregroundStyle(Crete.onSurfaceVariant)
                            .lineLimit(2)
                    }
                    Spacer(minLength: 4)
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(open ? "replier les réglages" : "déplier les réglages")
            Button {
                maps.setVisible(layer.id, !layer.visible)
            } label: {
                Image(systemName: layer.visible ? "eye" : "eye.slash")
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 36, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(layer.visible ? "Masquer la carte" : "Afficher la carte")
        }
    }
}

/// What an unfolded line adds: the opacity, the commands of the layer, and the colours when « Couleurs » is on.
private struct MapsLayerSettings: View {
    @Environment(AppModel.self) private var model
    let layer: LayerConfig
    let canUp: Bool
    let canDown: Bool
    let onDetails: () -> Void

    @State private var colors = false

    var body: some View {
        let maps = model.maps
        VStack(alignment: .leading, spacing: 8) {
            MapsLayerSlider(
                label: "Opacité : \(MapsText.percent(layer.opacity)) %",
                value: Double(layer.opacity),
                range: 0 ... 1
            ) { maps.setOpacity(layer.id, Float($0)) }
            commands
            if colors {
                MapsColorSettings(layer: layer)
            }
        }
        .padding(.bottom, 4)
    }

    private var commands: some View {
        let maps = model.maps
        let forced = maps.forced.contains(layer.sourceId)
        return HStack(spacing: 6) {
            MapsCommandTile(icon: "arrow.up", label: "Monter", enabled: canUp) {
                maps.moveUp(layer.id)
            }
            MapsCommandTile(icon: "arrow.down", label: "Descendre", enabled: canDown) {
                maps.moveDown(layer.id)
            }
            MapsCommandTile(icon: "slider.horizontal.3", label: "Conditions") {
                maps.expandedLayer = layer.id
                maps.conditionsLayer = layer.id
            }
            MapsCommandTile(icon: "paintpalette", label: "Couleurs", selected: colors) {
                withAnimation(.easeOut(duration: 0.2)) {
                    colors.toggle()
                }
            }
            Menu {
                Button("Détails", action: onDetails)
                Toggle(
                    "Forcer la mise à jour des données locales (cette session)",
                    isOn: Binding(get: { forced }, set: { maps.setForced(layer.sourceId, $0) })
                )
                Button("Ne plus afficher") {
                    maps.removeLayer(layer.id)
                }
                Button("Ne plus afficher les autres") {
                    maps.removeOtherLayers(layer.id)
                }
            } label: {
                MapsCommandLabel(icon: "ellipsis", label: "Plus", selected: false)
            }
            .buttonStyle(.borderless)
        }
    }
}

/// Contrast, brightness, saturation, hue (−180° to +180°), inverted colours and « Réinitialiser les couleurs ».
private struct MapsColorSettings: View {
    @Environment(AppModel.self) private var model
    let layer: LayerConfig

    var body: some View {
        let maps = model.maps
        let hue = Int(layer.hue.rounded())
        VStack(alignment: .leading, spacing: 8) {
            MapsLayerSlider(label: "Contraste : \(MapsText.percent(layer.contrast)) %", value: Double(layer.contrast), range: -1 ... 1) {
                maps.setAdjustments(layer.id, contrast: Float($0), brightness: layer.brightness, saturation: layer.saturation)
            }
            MapsLayerSlider(label: "Luminosité : \(MapsText.percent(layer.brightness)) %", value: Double(layer.brightness), range: -1 ... 1) {
                maps.setAdjustments(layer.id, contrast: layer.contrast, brightness: Float($0), saturation: layer.saturation)
            }
            MapsLayerSlider(label: "Saturation : \(MapsText.percent(layer.saturation)) %", value: Double(layer.saturation), range: -1 ... 1) {
                maps.setAdjustments(layer.id, contrast: layer.contrast, brightness: layer.brightness, saturation: Float($0))
            }
            MapsLayerSlider(label: "Teinte : \(hue)°", value: Double(layer.hue), range: -180 ... 180, hueTrack: true) {
                // Whole degrees: a favourite keeps the value its line shows.
                maps.setHue(layer.id, Float($0.rounded()))
            }
            Toggle("Couleurs inversées", isOn: Binding(get: { layer.inverted }, set: { maps.setInverted(layer.id, $0) }))
                .font(.subheadline)
                .tint(Crete.turquoise)
            Button("Réinitialiser les couleurs") {
                maps.resetColors(layer.id)
            }
            .buttonStyle(.borderless)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(Crete.turquoise)
        }
    }
}

/// A slider of an unfolded line: its name and value above it.
private struct MapsLayerSlider: View {
    let label: String
    let value: Double
    let range: ClosedRange<Double>
    var hueTrack = false
    let onChange: (Double) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
                .monospacedDigit()
            Slider(value: Binding(get: { value }, set: onChange), in: range)
                .tint(Crete.turquoise)
                .background(alignment: .center) {
                    if hueTrack {
                        // What the red becomes, round the colour wheel (P26.6).
                        LinearGradient(colors: MapsLayerSlider.wheel, startPoint: .leading, endPoint: .trailing)
                            .frame(height: 4)
                            .clipShape(Capsule())
                    }
                }
                .accessibilityLabel(label)
        }
    }

    private static let wheel: [Color] = [
        Color(hue: 0.5, saturation: 0.8, brightness: 0.9),
        Color(hue: 0.67, saturation: 0.8, brightness: 0.9),
        Color(hue: 0.83, saturation: 0.8, brightness: 0.9),
        Color(hue: 0.0, saturation: 0.8, brightness: 0.9),
        Color(hue: 0.17, saturation: 0.8, brightness: 0.9),
        Color(hue: 0.33, saturation: 0.8, brightness: 0.9),
        Color(hue: 0.5, saturation: 0.8, brightness: 0.9),
    ]
}

/// A command of an unfolded line: its icon over a short name.
private struct MapsCommandTile: View {
    let icon: String
    let label: String
    var enabled = true
    var selected = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MapsCommandLabel(icon: icon, label: label, selected: selected)
        }
        .buttonStyle(.borderless)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.38)
    }
}

private struct MapsCommandLabel: View {
    let icon: String
    let label: String
    let selected: Bool

    var body: some View {
        VStack(spacing: 3) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .medium))
            Text(label)
                .font(.system(size: 11, weight: .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .foregroundStyle(selected ? Color.white : Crete.petrol)
        .frame(maxWidth: .infinity, minHeight: 50)
        .background(selected ? Crete.turquoise : Crete.tile, in: RoundedRectangle(cornerRadius: 10))
    }
}
