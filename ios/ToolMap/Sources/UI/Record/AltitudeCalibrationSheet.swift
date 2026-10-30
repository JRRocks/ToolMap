import SwiftUI
import ToolMapCore
import UIKit

/// « Caler l'altitude » (Android L6a, G7, `altitude/AltitudeDialog.kt`): the altitude of here typed by the user (a
/// marked summit, a signpost), in metres or feet as the settings say, filled in with the current one; the barometer
/// takes it at once. The sea-level pressure it reads altitudes against, in « Unité de pression ». « Caler » is greyed
/// without a number; a message when the barometer does not answer.
struct AltitudeCalibrationSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var failed = false
    @State private var prepared = false

    var body: some View {
        let imperial = model.settings.units == .imperial
        let field: String = imperial ? "Altitude (ft)" : "Altitude (m)"
        NavigationStack {
            Form {
                Section {
                    Text(AltitudeCalibrationSheet.explanation)
                        .font(.subheadline)
                        .foregroundStyle(Crete.onSurface)
                    TextField(field, text: $text)
                        .keyboardType(.numbersAndPunctuation)
                        .onChange(of: text) { _, _ in
                            failed = false
                        }
                } footer: {
                    AltitudeCalibrationFooter(failed: failed)
                }
            }
            .navigationTitle("Caler l'altitude")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Caler") {
                        calibrate(imperial: imperial)
                    }
                    .fontWeight(.semibold)
                    .disabled(value == nil)
                }
            }
        }
        .onAppear {
            if !prepared {
                prepared = true
                text = initialText(imperial: imperial)
            }
        }
    }

    static let explanation =
        "Saisissez l'altitude exacte d'ici (un sommet coté, un panneau, une borne). Le baromètre s'y cale aussitôt, "
        + "puis suit la météo grâce au GPS et au relief."

    /// The number typed, a comma or a point before the decimals.
    private var value: Double? {
        let cleaned = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let number = Double(cleaned), number.isFinite else { return nil }
        return number
    }

    /// The current altitude, rounded, in the unit of the settings; empty when none is known.
    private func initialText(imperial: Bool) -> String {
        guard let meters = model.location.altitude?.meters, meters.isFinite else { return "" }
        let shown = imperial ? meters * TrackFormat.feetPerMeter : meters
        return String(Int(shown.rounded()))
    }

    private func calibrate(imperial: Bool) {
        guard let number = value else { return }
        let meters = imperial ? number / TrackFormat.feetPerMeter : number
        if model.location.calibrate(meters) {
            dismiss()
        } else {
            failed = true
        }
    }
}

/// The sea-level pressure, and the message when the barometer does not answer.
private struct AltitudeCalibrationFooter: View {
    @Environment(AppModel.self) private var model
    let failed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let seaLevel = model.location.barometer.seaLevelHpa {
                Text("Pression estimée au niveau de la mer : \(RecordText.pressure(seaLevel, model.settings))")
            }
            if failed {
                Text("Le baromètre ne répond pas pour l'instant : réessayez dans quelques secondes.")
                    .foregroundStyle(Crete.danger)
            }
        }
    }
}

/// The barometer's line of the GPS pill (Android G7, detail of the GPS chip, while the barometer gives the altitude):
/// the pressure and the altitude, « Alt. : calage… » before the first calibration; a touch opens « Caler
/// l'altitude ». For `InfoPills` (`PillGpsDetail`).
struct BarometerPillLine: View {
    @Environment(AppModel.self) private var model
    @State private var calibrating = false

    var body: some View {
        let location = model.location
        let settings = model.settings
        if location.barometer.available, settings.altitude.barometerOn {
            Button {
                calibrating = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "gauge.with.needle")
                        .foregroundStyle(Crete.turquoise)
                    if let hpa = location.barometer.pressureHpa {
                        Text(RecordText.pressure(hpa, settings))
                    }
                    if let altitude = location.altitude {
                        Text("↑ \(RecordText.elevation(altitude.meters, settings))")
                    } else {
                        Text("Alt. : calage…")
                    }
                }
                .frame(minHeight: 32)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Caler l'altitude")
            .sheet(isPresented: $calibrating) {
                AltitudeCalibrationSheet()
                    .environment(model)
                    .presentationDetents([.medium, .large])
            }
        }
    }
}
