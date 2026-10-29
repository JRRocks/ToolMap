import SwiftUI
import ToolMapCore

/// Cartes et calques (Android §5, P29.3 "Strates" and P29.6 "Bascule"): the maps on screen, then the available maps,
/// the background by a radio button and the overlays by a switch.
struct MapsPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            PanelHeader(title: "Cartes et calques", subtitle: subtitle)
            List {
                Section("Cartes affichées") {
                    ForEach(model.overlays.reversed()) { overlay in
                        if let source = model.source(overlay.sourceId) {
                            OpacityRow(name: source.name, opacity: overlay.opacity) { value in
                                model.setOverlayOpacity(overlay.sourceId, value)
                            }
                        }
                    }
                    if let base = model.source(model.baseId) {
                        HStack {
                            Image(systemName: "square.stack.3d.down.right")
                                .foregroundStyle(Crete.turquoise)
                            Text(base.name)
                            Spacer()
                            Text("Fond")
                                .font(.footnote)
                                .foregroundStyle(Crete.onSurfaceVariant)
                        }
                    }
                }
                Section("Fonds de carte") {
                    ForEach(model.baseMaps, id: \.id) { source in
                        Button {
                            model.baseId = source.id
                        } label: {
                            HStack {
                                Image(systemName: model.baseId == source.id ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(Crete.turquoise)
                                MapNameRow(source: source)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                Section("Calques") {
                    ForEach(model.overlayMaps, id: \.id) { source in
                        Toggle(isOn: Binding(
                            get: { model.isOverlayShown(source.id) },
                            set: { model.setOverlay(source.id, shown: $0) }
                        )) {
                            MapNameRow(source: source)
                        }
                        .tint(Crete.turquoise)
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private var subtitle: String {
        let count = 1 + model.overlays.count
        return count == 1 ? "1 carte" : "\(count) cartes"
    }
}

/// Name of a map and its zooms.
struct MapNameRow: View {
    let source: TileSource

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(source.name)
                .foregroundStyle(Crete.onSurface)
            Text("Zooms \(source.minZoom ?? 0) à \(source.maxZoom ?? 0)" + (source.copyright.map { " · © \($0)" } ?? ""))
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
        }
    }
}

/// A displayed overlay with its opacity slider.
struct OpacityRow: View {
    let name: String
    let opacity: Double
    let onChange: (Double) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(name)
                Spacer()
                Text("Opacité : \(Int((opacity * 100).rounded())) %")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Slider(value: Binding(get: { opacity }, set: onChange), in: 0 ... 1)
                .tint(Crete.turquoise)
        }
    }
}
