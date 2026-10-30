import SwiftUI
import ToolMapCore

/// Imprimer à l'échelle (Android T3, T3b, P17): the panel of the step the print is at, above the bottom bar. The frame
/// and its pill are drawn over the map by `PrintFrameOverlay`, the pages along a track by `PrintLayers`.
struct PrintPanel: View {
    @Environment(AppModel.self) private var model
    let alongTrack: Int64?

    var body: some View {
        let printer = model.printer
        VStack(spacing: 0) {
            switch printer.stage {
            case .setup?:
                PrintSetupPanel()
            case .frame?:
                if printer.isAlongTrack {
                    PrintTrackActions()
                } else {
                    PrintFrameActions()
                }
            case .confirm?:
                PrintConfirmPanel()
            case nil:
                Color.clear
                    .frame(height: 1)
            }
        }
        .background(
            GeometryReader { proxy in
                Color.clear
                    .onAppear { printer.panelTop = proxy.frame(in: .global).minY }
                    .onChange(of: proxy.frame(in: .global).minY) { _, top in printer.panelTop = top }
            }
        )
        .onAppear { printer.open(alongTrack: alongTrack) }
        .onChange(of: alongTrack) { _, id in printer.open(alongTrack: id) }
        .onChange(of: model.maps.stack) { _, _ in printer.layersChanged() }
        .onDisappear { printer.toolDismissed() }
        .sheet(item: outcome(printer)) { outcome in
            PrintOutcomeSheet(outcome: outcome)
        }
    }

    private func outcome(_ printer: PrintController) -> Binding<PrintOutcome?> {
        Binding(get: { printer.outcome }, set: { printer.outcome = $0 })
    }
}
