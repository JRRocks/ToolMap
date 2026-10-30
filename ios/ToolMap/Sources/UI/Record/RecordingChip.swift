import SwiftUI
import ToolMapCore

/// The running recording as a chip of the top right corner of the map (Android T4, `RecordingChip`, P29.1 variant E
/// « Pastilles »): turquoise, a red dot, the time then the distance (« 1 h 12 · 4,82 km »), « En pause » with a pause
/// sign while it waits; red while the GPS is missing or the points cannot be written. A touch opens its detail under
/// it: the four figures live, the warnings, Pause · Reprendre · Arrêter. It also asks, at launch, what to do with a
/// recording the app was killed in the middle of (« Enregistrement interrompu »).
struct RecordingChip: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let recorder = model.recorder
        ZStack(alignment: .topTrailing) {
            Color.clear
                .frame(width: 0, height: 0)
                .allowsHitTesting(false)
            if recorder.isRecording {
                RecordingChipButton()
                    .padding(.trailing, 12)
                    .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .alert(RecordText.recoverTitle, isPresented: recoveryShown) {
            Button(RecordText.recoverResume) {
                recorder.resumeRecovered()
            }
            Button(RecordText.recoverKeep) {
                recorder.finishRecovered()
            }
        } message: {
            Text(RecordText.recoverMessage)
        }
    }

    private var recoveryShown: Binding<Bool> {
        let recorder = model.recorder
        return Binding(
            get: { recorder.recoverable != nil && !recorder.recoveryDismissed },
            set: { shown in
                if !shown {
                    recorder.dismissRecovery()
                }
            }
        )
    }
}

/// The chip itself.
private struct RecordingChipButton: View {
    @Environment(AppModel.self) private var model
    @State private var open = false

    var body: some View {
        let recorder = model.recorder
        let paused = recorder.isPaused
        let text = RecordText.progress(recorder.live, paused: paused, model.settings)
        Button {
            open = true
        } label: {
            HStack(spacing: 6) {
                if paused {
                    Image(systemName: "pause.fill")
                        .font(.system(size: 11, weight: .bold))
                } else {
                    RecordDot(size: 9)
                }
                Text(text)
                    .font(.system(size: 13, weight: .medium).monospacedDigit())
                    .lineLimit(1)
            }
            .foregroundStyle(Color.white)
            .padding(.horizontal, 12)
            .frame(height: 32)
            .background(recorder.hasWarning ? Crete.danger : Crete.turquoise, in: Capsule())
            .shadow(color: .black.opacity(0.18), radius: 3, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Ouvrir l'enregistrement")
        .accessibilityValue(text)
        .popover(isPresented: $open) {
            RecordingDetail {
                open = false
            }
            .environment(model)
            .presentationCompactAdaptation(.popover)
        }
    }
}

/// Live figures of the running recording with Pause / Reprendre and Arrêter: the detail of the chip.
struct RecordingDetail: View {
    @Environment(AppModel.self) private var model
    /// Called before a stop, to close the popover.
    let onStop: () -> Void

    var body: some View {
        let recorder = model.recorder
        VStack(alignment: .leading, spacing: 12) {
            if recorder.isPaused {
                Text(RecordText.paused)
                    .font(.headline)
                    .foregroundStyle(Crete.onSurface)
            }
            RecordingFigures(live: recorder.live, settings: model.settings)
            RecordingWarnings()
            RecordingButtons(onStop: onStop)
        }
        .padding(16)
        .frame(minWidth: 300, alignment: .leading)
    }
}

/// Durée, Distance, Vitesse, Dénivelé +.
private struct RecordingFigures: View {
    let live: LiveStats
    let settings: AppSettings

    var body: some View {
        let speed = live.currentSpeedMps.map { RecordText.speed($0, settings) } ?? "–"
        HStack(alignment: .top, spacing: 16) {
            RecordingFigure(label: "Durée", value: RecordText.duration(live.elapsedMillis))
            RecordingFigure(label: "Distance", value: RecordText.distance(live.distanceMeters, settings))
            RecordingFigure(label: "Vitesse", value: speed)
            RecordingFigure(label: "Dénivelé +", value: RecordText.elevation(live.ascentMeters, settings))
        }
    }
}

private struct RecordingFigure: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.headline.monospacedDigit())
                .foregroundStyle(Crete.onSurface)
                .lineLimit(1)
            Text(label)
                .font(.caption)
                .foregroundStyle(Crete.onSurfaceVariant)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The alerts of the recording: no GPS, points not written, no fix for a minute.
private struct RecordingWarnings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let recorder = model.recorder
        if recorder.gpsUnavailable {
            warning(RecordText.gpsUnavailable)
        }
        if recorder.saveFailing {
            warning(RecordText.saveFailing)
        }
        if recorder.noSignal, !recorder.gpsUnavailable {
            warning(RecordText.signalLost)
        }
    }

    private func warning(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(Crete.danger)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Pause or Reprendre, then Arrêter.
private struct RecordingButtons: View {
    @Environment(AppModel.self) private var model
    let onStop: () -> Void

    var body: some View {
        let recorder = model.recorder
        HStack(spacing: 8) {
            if recorder.isPaused {
                Button("Reprendre") {
                    recorder.resume()
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button("Pause") {
                    recorder.pause()
                }
                .buttonStyle(.bordered)
            }
            Button("Arrêter") {
                onStop()
                recorder.stop()
            }
            .buttonStyle(.bordered)
        }
        .tint(Crete.turquoise)
    }
}
