import SwiftUI
import ToolMapCore
import UIKit

/// « Signaler une erreur » (Android P16.3, AlpineQuest's « Report errors »): what the report holds, the report itself,
/// readable before it leaves, and « Partager », which opens the share sheet of iOS. Nothing is sent by ToolMap: the
/// user chooses the app and the person.
struct MenuReportSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var report = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Le rapport contient le téléphone, la version de ToolMap et vos réglages. ToolMap ne l'envoie à personne : vous choisissez l'application de partage.")
                        .font(.subheadline)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                Section("Rapport") {
                    Text(report)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Signaler une erreur")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    ShareLink(item: report, subject: Text("Rapport d'erreur ToolMap")) {
                        Text("Partager")
                    }
                    .disabled(report.isEmpty)
                }
            }
        }
        .tint(Crete.turquoise)
        .onAppear {
            report = MenuDiagnostics.text(model)
        }
    }
}

/// The diagnostic text of the iPhone app: the plain lines of `ErrorReport` (Android), with what iOS tells about the
/// phone in place of the Android ones (no crash report nor app log on iPhone yet), then the settings.
enum MenuDiagnostics {
    static func text(_ app: AppModel, at date: Date = Date()) -> String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        let device = UIDevice.current
        let screen = UIScreen.main
        let width = Int(screen.bounds.width)
        let height = Int(screen.bounds.height)
        var lines: [String] = []
        lines.append("ToolMap \(version) (build \(build))")
        lines.append("when: \(stamp(date))")
        lines.append("device: \(machine()) (\(device.model))")
        lines.append("ios: \(device.systemName) \(device.systemVersion)")
        lines.append("screen: \(width) x \(height) pt, scale \(Int(screen.scale))")
        lines.append("locale: \(Locale.current.identifier)")
        lines.append("free space: \(freeSpace())")
        lines.append("maps: \(app.maps.sources.count), offline: \(app.offline ? "yes" : "no")")
        lines.append("location: \(app.location.isAuthorized ? "authorized" : "not authorized")")
        if let settings = try? AppSettingsCodec.encode(app.settings) {
            lines.append("")
            lines.append("--- settings ---")
            lines.append(settings)
        }
        return lines.joined(separator: "\n") + "\n"
    }

    /// `2026-09-21 14:13:20 +02:00`, as the Android reports (`CrashText.date`).
    private static func stamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss xxx"
        return formatter.string(from: date)
    }

    /// The model of the phone (« iPhone16,2 »), or the one the simulator stands for.
    private static func machine() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return "\(simulated) simulator"
        }
        var system = utsname()
        _ = uname(&system)
        return withUnsafeBytes(of: system.machine) { (raw: UnsafeRawBufferPointer) -> String in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    private static func freeSpace() -> String {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        guard let free = values?.volumeAvailableCapacityForImportantUsage else { return "?" }
        return BackupText.bytes(free)
    }
}
