import CoreLocation
import SwiftUI
import ToolMapCore

/// « Envoyer une alerte au groupe », the red line under « Partager ma position » that opens the sheet of the alert
/// (Android `SendAlertLine`, P22 variant C).
struct ShareSendAlertLine: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 20) {
                Image(systemName: "sos.circle.fill")
                    .font(.system(size: 20))
                Text("Envoyer une alerte au groupe")
                    .font(.body)
                Spacer(minLength: 0)
            }
            .foregroundStyle(ShareColors.alertRed)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The sheet that sends the alert (variant B): an optional message, then the red button held for one second.
struct ShareSendAlertSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Envoyer une alerte au groupe")
                .font(.title2.weight(.semibold))
            TextField("Message (facultatif)", text: $text, axis: .vertical)
                .lineLimit(1 ... 3)
                .textFieldStyle(.roundedBorder)
                .onChange(of: text) { _, value in
                    if value.count > 200 {
                        text = String(value.prefix(200))
                    }
                }
            Text("Avec votre dernière position · répétée 2 min · annulable")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
            ShareHoldToSend {
                model.sharing.startSos(text)
                dismiss()
            }
            .padding(.top, 8)
            Button("Annuler") {
                dismiss()
            }
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 8)
        .frame(maxHeight: .infinity, alignment: .top)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// « Maintenez pour envoyer »: the button fills while it is held and sends once full; let go before, nothing is sent.
/// VoiceOver's double tap sends at once (its own gesture is deliberate enough).
struct ShareHoldToSend: View {
    let onSend: () -> Void
    @State private var progress: CGFloat = 0
    @State private var sent = false

    var body: some View {
        ZStack {
            Capsule()
                .fill(ShareColors.alertRed)
            GeometryReader { geometry in
                Rectangle()
                    .fill(Color.white.opacity(0.25))
                    .frame(width: geometry.size.width * progress)
            }
            .clipShape(Capsule())
            HStack(spacing: 10) {
                Image(systemName: "sos.circle.fill")
                Text("Maintenez pour envoyer")
                    .font(.headline)
            }
            .foregroundStyle(Color.white)
        }
        .frame(height: 64)
        .contentShape(Capsule())
        .onLongPressGesture(
            minimumDuration: 1,
            maximumDistance: 60,
            perform: { send() },
            onPressingChanged: { down in pressing(down) }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Maintenez pour envoyer")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: "Envoyer l'alerte") {
            send()
        }
    }

    private func pressing(_ down: Bool) {
        if down {
            withAnimation(.linear(duration: 1)) {
                progress = 1
            }
        } else if !sent {
            withAnimation(.easeOut(duration: 0.15)) {
                progress = 0
            }
        }
    }

    private func send() {
        guard !sent else { return }
        sent = true
        onSend()
    }
}

/// Your alert in progress (variant C): « Alerte en cours », the time its repeats still run then the time it was sent,
/// and « Annuler l'alerte » after a confirmation that says what the group will see.
struct ShareOwnAlertCard: View {
    @Environment(AppModel.self) private var model
    @State private var confirm = false

    var body: some View {
        let sharing = model.sharing
        let since = sharing.ownSosAtMs
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Image(systemName: "sos.circle.fill")
                    .font(.system(size: 20))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Alerte en cours")
                        .font(.body)
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let now = Int64(context.date.timeIntervalSince1970 * 1000)
                        Text(ShareOwnAlertCard.status(since: since, now: now))
                            .font(.footnote)
                    }
                }
            }
            HStack {
                Spacer()
                Button("Annuler l'alerte") {
                    confirm = true
                }
                .buttonStyle(.bordered)
                .tint(Crete.danger)
            }
        }
        .foregroundStyle(ShareColors.onErrorContainer)
        .padding(.vertical, 4)
        .listRowBackground(ShareColors.errorContainer)
        .alert("Annuler l'alerte ?", isPresented: $confirm) {
            Button("Garder l'alerte", role: .cancel) {}
            Button("Annuler l'alerte", role: .destructive) {
                sharing.cancelSos()
            }
        } message: {
            Text("Le groupe verra « Alerte levée par \(sharing.displayName) ».")
        }
    }

    /// « Répétée encore 1 min 24 s », « Répétée encore 12 s », then « Envoyée à 10:41 ».
    static func status(since: Int64, now: Int64) -> String {
        guard since > 0 else { return "" }
        let left = max(MeshNode.sosRepeatForS * 1000 - (now - since), 0) / 1000
        if left >= 60 {
            return "Répétée encore \(left / 60) min \(left % 60) s"
        }
        if left > 0 {
            return "Répétée encore \(left) s"
        }
        return "Envoyée à \(ShareText.clock(since))"
    }
}

/// The dialog of an alert of the group: « Alerte de … » not seen yet, or « Alerte levée » when it was lifted before it
/// was seen (Android `GroupAlertDialog`, `AlertLiftedDialog`).
enum ShareAlertDialog: Identifiable {
    case alert(ShareSos)
    case lifted(ShareSosLifted)

    var id: String {
        switch self {
        case let .alert(sos): "alert-\(sos.memberId)"
        case let .lifted(lifted): "lifted-\(lifted.memberId)"
        }
    }
}

/// Shows the alerts of the group over the view, when `enabled` (the view is the one on screen).
struct ShareAlertDialogs: ViewModifier {
    @Environment(AppModel.self) private var model
    let enabled: Bool

    func body(content: Content) -> some View {
        let sharing = model.sharing
        let dialog = enabled ? ShareAlertDialogs.current(sharing) : nil
        content
            .alert(
                ShareAlertDialogs.title(dialog),
                isPresented: Binding(
                    get: { dialog != nil },
                    set: { shown in
                        if !shown, let dialog {
                            ShareAlertDialogs.close(dialog, sharing)
                        }
                    }
                ),
                presenting: dialog
            ) { shown in
                switch shown {
                case let .alert(sos):
                    if let fix = sos.fix {
                        Button("Voir sur la carte") {
                            sharing.acknowledgeSos(sos.memberId)
                            sharing.showOnMap(fix)
                        }
                    }
                    Button("Vu", role: .cancel) {
                        sharing.acknowledgeSos(sos.memberId)
                    }
                case let .lifted(lifted):
                    Button("OK", role: .cancel) {
                        sharing.acknowledgeLift(lifted.memberId)
                    }
                }
            } message: { shown in
                Text(ShareAlertDialogs.message(shown, sharing, model.location.location))
            }
    }

    /// The lift waiting for « OK » first, then the first alert not seen.
    static func current(_ sharing: ShareController) -> ShareAlertDialog? {
        if let lifted = sharing.sosLifted.first(where: { $0.dialog }) {
            return .lifted(lifted)
        }
        if let alert = sharing.sos.first(where: { !$0.acknowledged }) {
            return .alert(alert)
        }
        return nil
    }

    static func title(_ dialog: ShareAlertDialog?) -> String {
        switch dialog {
        case let .alert(sos): "Alerte de \(sos.name)"
        case .lifted: "Alerte levée"
        case nil: ""
        }
    }

    static func close(_ dialog: ShareAlertDialog, _ sharing: ShareController) {
        switch dialog {
        case let .alert(sos): sharing.acknowledgeSos(sos.memberId)
        case let .lifted(lifted): sharing.acknowledgeLift(lifted.memberId)
        }
    }

    /// The message, the position, its age, and its distance and direction from this device when known.
    static func message(_ dialog: ShareAlertDialog, _ sharing: ShareController, _ here: CLLocation?) -> String {
        switch dialog {
        case let .lifted(lifted):
            return "\(lifted.name) a annulé son alerte de \(ShareText.clock(lifted.alertAtMs))."
        case let .alert(sos):
            var lines: [String] = []
            if !sos.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                lines.append(sos.text)
            }
            guard let fix = sos.fix else {
                lines.append("Pas encore de position reçue")
                return lines.joined(separator: "\n")
            }
            lines.append(CoordinateFormat.decimalDegrees(fix.lat, fix.lon))
            var last = "Dernière position " + ShareText.age(fix.timeSec * 1000)
            if let here, here.horizontalAccuracy >= 0 {
                let lat = here.coordinate.latitude
                let lon = here.coordinate.longitude
                let meters = Geodesy.distance(lat, lon, fix.lat, fix.lon)
                let bearing = Geodesy.bearing(lat, lon, fix.lat, fix.lon)
                let distance = TrackFormat.distance(meters, locale: ShareText.french, units: sharing.app.settings.units)
                last += " · \(distance) \(ShareText.directions[ShareText.octant(bearing)])"
            }
            lines.append(last)
            return lines.joined(separator: "\n")
        }
    }
}

/// The red banner of an alert on the map: « Alerte de … » and the message under it; a tap centres the map on it.
struct ShareAlertBanner: View {
    let alert: ShareSos
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "sos.circle.fill")
                    .font(.system(size: 18))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Alerte de \(alert.name)")
                        .font(.subheadline.weight(.semibold))
                    if !alert.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(alert.text)
                            .font(.caption)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(Color.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(ShareColors.alertRed, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
        }
        .buttonStyle(.plain)
    }
}

/// The banner of a lifted alert: white with a green tick, « Alerte levée par … ».
struct ShareLiftedBanner: View {
    let lifted: ShareSosLifted

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(ShareColors.liftedGreen)
            Text("Alerte levée par \(lifted.name)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.onSurface)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
    }
}
