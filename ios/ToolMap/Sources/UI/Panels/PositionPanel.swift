import CoreLocation
import SwiftUI
import ToolMapCore
import UIKit

/// Positionnement (Android §4, P29.5 variant A « Sentier », docs/previews/design-05-position.html): the petrol band and
/// its « ? »; the block « Position en temps réel » with its switch and its state; « La carte suit » (Libre · Suivi ·
/// Boussole) and the help line under it; the recorder (switch: the start dialog, or stop); the barometer and « Caler
/// l'altitude… » under it; « Partage de position ». The rows do not close the panel, but the sharing and the « ? ».
/// The end of a recording shows its message at the top of the panel, with « Reprendre ».
struct PositionPanel: View {
    @Environment(AppModel.self) private var model
    @State private var startShown = false
    @State private var calibrateShown = false
    @State private var message: String?

    var body: some View {
        VStack(spacing: 0) {
            PositionBand {
                open(.about("positioning"))
            }
            if let id = model.recorder.finishedTrackId {
                RecordFinishedBanner(trackId: id)
            }
            PositionList(
                onRefused: { message = $0 },
                onStart: { startShown = true },
                onCalibrate: { calibrateShown = true },
                onShare: { open(.share) }
            )
            .sheet(isPresented: $startShown) {
                startSheet
            }
        }
        .sheet(isPresented: $calibrateShown) {
            AltitudeCalibrationSheet()
                .environment(model)
                .presentationDetents([.medium, .large])
        }
        .alert(
            "ToolMap",
            isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })
        ) {
            if message == RecordText.locationDenied || message == RecordText.permissionDenied {
                Button("Réglages") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }

    /// « Enregistrer une trace », with the choices kept from the last time.
    private var startSheet: some View {
        let recorder = model.recorder
        return RecordStartSheet(
            askActivity: model.settings.recorder.askActivity,
            recent: recorder.recentActivities(),
            initialActivity: recorder.initialActivity(),
            initialProfile: recorder.lastProfile()
        ) { name, profile, activity in
            recorder.begin(name: name, profile: profile, activity: activity) { text in
                // After the sheet has gone: one presentation at a time.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    message = text
                }
            }
        }
        .environment(model)
        .presentationDetents([.large])
    }

    /// A full-screen page: the panel goes first, then the page comes (one presentation at a time on iOS).
    private func open(_ screen: Screen) {
        let app = model
        app.panel = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            app.screen = screen
        }
    }
}

/// The rows of the panel.
private struct PositionList: View {
    @Environment(AppModel.self) private var model
    let onRefused: (String) -> Void
    let onStart: () -> Void
    let onCalibrate: () -> Void
    let onShare: () -> Void

    var body: some View {
        let barometer = model.location.barometer
        List {
            Section {
                PositionLiveRow(onRefused: onRefused)
            }
            PositionFollowSection(onRefused: onRefused)
            Section {
                PositionRecorderRow(onStart: onStart)
                if barometer.available {
                    PositionBarometerRow()
                    if model.settings.altitude.barometerOn {
                        PositionCalibrateRow(action: onCalibrate)
                    }
                }
                PositionShareRow(action: onShare)
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// The petrol band of the panel (ADR-060), with its « ? » (Android L1: À propos, section « Positionnement »).
private struct PositionBand: View {
    let onHelp: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            Text("Positionnement")
                .font(.headline)
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onHelp) {
                Image(systemName: "questionmark.circle")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Color.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Aide et à propos")
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .padding(.top, 18)
        .padding(.bottom, 6)
        .background(Crete.petrol)
    }
}

/// « Position en temps réel » (L3): the switch and what the GPS gives (« ± 4 m · alt. 297 m · GPS + baromètre »).
private struct PositionLiveRow: View {
    @Environment(AppModel.self) private var model
    let onRefused: (String) -> Void

    var body: some View {
        let location = model.location
        let on = location.isOn && !location.isDenied
        Toggle(isOn: Binding(get: { on }, set: { switchTo($0) })) {
            HStack(spacing: 14) {
                PositionStateIcon(on: on)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Position en temps réel")
                        .foregroundStyle(Crete.onSurface)
                    Text(PositionText.state(location, model.settings))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        }
        .tint(Crete.turquoise)
    }

    /// On: the map follows the position, north up (Android `applyLocationMode(FOLLOW)`); off: the marker and the
    /// following go, the recorder and the sharing keep their own position.
    private func switchTo(_ on: Bool) {
        let location = model.location
        if on {
            if location.isDenied {
                onRefused(RecordText.locationDenied)
                return
            }
            location.start()
            model.followMode = .position
        } else {
            location.stop()
            model.followMode = .free
        }
    }
}

/// The disc of the position: turquoise when on, pale when off.
private struct PositionStateIcon: View {
    let on: Bool

    var body: some View {
        Image(systemName: "location.fill")
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(on ? Color.white : Crete.turquoise)
            .frame(width: 36, height: 36)
            .background(on ? Crete.turquoise : Crete.turquoise.opacity(0.16), in: Circle())
            .accessibilityHidden(true)
    }
}

/// « La carte suit » (L5): Libre · Suivi · Boussole, none chosen while the position is off; choosing one turns it on.
private struct PositionFollowSection: View {
    @Environment(AppModel.self) private var model
    let onRefused: (String) -> Void

    var body: some View {
        let location = model.location
        let current: FollowMode? = location.isOn && !location.isDenied ? model.followMode : nil
        Section {
            Picker("La carte suit", selection: Binding(get: { current }, set: { choose($0) })) {
                ForEach(FollowMode.allCases) { mode in
                    Text(PositionText.followName(mode))
                        .tag(Optional(mode))
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        } header: {
            Text("La carte suit")
        } footer: {
            Text(PositionText.followHelp(current))
        }
    }

    private func choose(_ mode: FollowMode?) {
        guard let mode else { return }
        let location = model.location
        if location.isDenied {
            onRefused(RecordText.locationDenied)
            return
        }
        if !location.isOn {
            location.start()
        }
        model.followMode = mode
    }
}

/// « Enregistreur de trace » (L4): « Arrêté », or the red dot and « En cours · 1 h 12 · 4,82 km »; the icon turns
/// turquoise while it records. The switch opens « Enregistrer une trace », or stops.
private struct PositionRecorderRow: View {
    @Environment(AppModel.self) private var model
    let onStart: () -> Void

    var body: some View {
        let recorder = model.recorder
        let recording = recorder.isRecording
        Toggle(isOn: Binding(get: { recording }, set: { on in
            if on {
                onStart()
            } else {
                recorder.stop()
            }
        })) {
            HStack(spacing: 14) {
                PositionRowIcon(systemImage: PositionText.recorderIcon, tint: recording ? Crete.turquoise : nil)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Enregistreur de trace")
                        .foregroundStyle(Crete.onSurface)
                    PositionRecorderState()
                }
            }
        }
        .tint(Crete.turquoise)
    }
}

private struct PositionRecorderState: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let recorder = model.recorder
        HStack(spacing: 6) {
            if recorder.isRecording, !recorder.isPaused {
                RecordDot(size: 8)
            }
            Text(PositionText.recorderState(recorder, model.settings))
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Crete.onSurfaceVariant)
        }
    }
}

/// « Baromètre » (L6): the same choice as « Altitude » in the settings (ADR-053): on from « GPS seulement » it gives
/// « Automatique », off « GPS seulement ».
private struct PositionBarometerRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let on = model.settings.altitude.barometerOn
        let calibrated = on && model.location.barometer.calibrated
        Toggle(isOn: Binding(get: { on }, set: { value in
            model.settings.altitude = model.settings.altitude.withBarometer(value)
        })) {
            HStack(spacing: 14) {
                PositionRowIcon(systemImage: "gauge.with.needle", tint: nil)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Baromètre")
                        .foregroundStyle(Crete.onSurface)
                    Text(calibrated ? "Corrige l'altitude du GPS · calée" : "Corrige l'altitude du GPS")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        }
        .tint(Crete.turquoise)
    }
}

/// « Caler l'altitude… » (L6a), under the barometer: greyed while the position is off (the sensor runs with it).
private struct PositionCalibrateRow: View {
    @Environment(AppModel.self) private var model
    let action: () -> Void

    var body: some View {
        let enabled = model.location.isOn && !model.location.isDenied
        Button(action: action) {
            HStack(spacing: 14) {
                Color.clear
                    .frame(width: 26, height: 1)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Caler l'altitude…")
                        .foregroundStyle(Crete.onSurface)
                    Text(enabled ? "Sommet coté, panneau, borne" : "Allumez la position pour caler")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                PositionChevron()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .accessibilityLabel("Caler l'altitude")
    }
}

/// « Partage de position » (L7): « Aucun groupe » or « 3 membres · position envoyée »; opens the sharing screen.
private struct PositionShareRow: View {
    @Environment(AppModel.self) private var model
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                PositionRowIcon(systemImage: "person.2.wave.2", tint: nil)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Partage de position")
                        .foregroundStyle(Crete.onSurface)
                    Text(model.sharing.panelSummary)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Spacer(minLength: 8)
                PositionChevron()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The grey icon of a row (turquoise for the recorder while it records).
private struct PositionRowIcon: View {
    let systemImage: String
    let tint: Color?

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 18))
            .foregroundStyle(tint ?? Crete.onSurfaceVariant)
            .frame(width: 26)
            .accessibilityHidden(true)
    }
}

private struct PositionChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Crete.onSurfaceVariant)
            .accessibilityHidden(true)
    }
}

/// « Enregistrement terminé : la trace est dans la bibliothèque. » with « Reprendre », above the rows of the open panel
/// (P29.5: the message of the map would be under the panel).
private struct RecordFinishedBanner: View {
    @Environment(AppModel.self) private var model
    let trackId: Int64

    var body: some View {
        HStack(spacing: 10) {
            Text(RecordText.finished)
                .font(.subheadline)
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(RecordText.resume) {
                model.recorder.reopen(trackId)
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Crete.pill)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }
}

/// The texts of the panel (Android `PositionMenu.kt`, strings `position_*`).
enum PositionText {
    /// A trace and a pin, as AlpineQuest's recorder.
    static let recorderIcon = "point.topleft.down.curvedto.point.bottomright.up"

    /// The segments: Android « Libre · Suivi · Boussole » (its « Explorer » tilts the map, which the iPhone map does not).
    static func followName(_ mode: FollowMode) -> String {
        switch mode {
        case .free: "Libre"
        case .position: "Suivi"
        case .heading: "Boussole"
        }
    }

    /// What the chosen way to follow does, or that choosing one turns the position on.
    static func followHelp(_ mode: FollowMode?) -> String {
        switch mode {
        case .none: "Choisir un mode allume la position."
        case .some(.free): "La carte reste où vous la placez ; le repère montre votre position."
        case .some(.position): "La carte vous suit, nord en haut."
        case .some(.heading): "La carte vous suit et tourne avec vous."
        }
    }

    /// « Désactivée », « En attente du GPS… » (iOS does not say how many satellites it sees), or the accuracy, the
    /// altitude and where it comes from.
    static func state(_ location: LocationService, _ settings: AppSettings) -> String {
        guard location.isOn, !location.isDenied else { return "Désactivée" }
        guard let fix = location.location, fix.horizontalAccuracy >= 0 else { return "En attente du GPS…" }
        var parts = ["± \(RecordText.distance(fix.horizontalAccuracy, settings))"]
        if let altitude = location.altitude {
            parts.append("alt. \(RecordText.elevation(altitude.meters, settings))")
            parts.append(altitude.source == .barometer ? "GPS + baromètre" : "GPS")
        }
        return parts.joined(separator: " · ")
    }

    /// « Arrêté », « En cours · 1 h 12 · 4,82 km », « En pause · 1 h 12 · 4,82 km ».
    static func recorderState(_ recorder: RecorderController, _ settings: AppSettings) -> String {
        guard recorder.isRecording else { return "Arrêté" }
        let figures = RecordText.progress(recorder.live, paused: false, settings)
        return recorder.isPaused ? "\(RecordText.paused) · \(figures)" : "En cours · \(figures)"
    }
}
