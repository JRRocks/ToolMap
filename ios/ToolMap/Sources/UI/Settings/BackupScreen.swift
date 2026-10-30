import SwiftUI
import ToolMapCore
import UIKit
import UniformTypeIdentifiers

/// Sauvegarde et restauration as a page of its own (`Screen.backup`); from the settings it is pushed (`BackupPage`).
struct BackupScreen: View {
    var body: some View {
        NavigationStack {
            BackupPage()
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { ScreenCloseButton() }
                }
        }
        .tint(Crete.turquoise)
    }
}

/// The two halves of the page (P29.9): one decision at a time.
enum BackupSegment: String, CaseIterable, Identifiable {
    case save, restore

    var id: String { rawValue }

    var title: String {
        switch self {
        case .save: "Sauvegarder"
        case .restore: "Restaurer"
        }
    }
}

/// Saves the app's data to a zip and restores it from one (Android BACKUP, P29.9 « Loupe »): a segmented control parts
/// « Sauvegarder » and « Restaurer », each with one main action. A restore waiting for the next launch opens the page on
/// « Restaurer », its card first; the report of the last restore and the progress of a task are above the segmented
/// control, which is greyed while a task runs.
struct BackupPage: View {
    @Environment(AppModel.self) private var model
    @State private var segment: BackupSegment = .save
    @State private var importing = false

    var body: some View {
        @Bindable var backup = model.backup
        BackupList(segment: $segment, importing: $importing)
            .navigationTitle("Sauvegarde et restauration")
            .navigationBarTitleDisplayMode(.inline)
            .settingsNavigationBar()
            .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .data]) { result in
                backup.choose(result)
            }
            // On a view of its own, so that the save sheet and the file importer never share a presenter.
            .background {
                Color.clear
                    .sheet(item: $backup.export) { export in
                        BackupExportPicker(url: export.url) { saved in
                            backup.exportFinished(saved: saved)
                        }
                        .ignoresSafeArea()
                    }
            }
            .alert(
                "Restaurer cette sauvegarde ?",
                // Both buttons clear the preview themselves: the binding never cancels, so that « Préparer la
                // restauration » keeps its file whatever the order SwiftUI runs things in.
                isPresented: Binding(get: { backup.preview != nil }, set: { _ in }),
                presenting: backup.preview
            ) { _ in
                Button("Annuler", role: .cancel) { backup.cancelPreview() }
                Button("Préparer la restauration") { backup.confirmRestore() }
            } message: { chosen in
                Text(BackupPage.previewText(chosen.manifest))
            }
            .overlay(alignment: .bottom) {
                if let message = backup.message {
                    SettingsToast(text: message.text, id: message.id, onTimeout: {
                        if backup.message?.id == message.id { backup.message = nil }
                    })
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                }
            }
            .onAppear {
                backup.refresh()
                if backup.pending != nil { segment = .restore }
            }
            .onChange(of: backup.pending != nil) { _, waiting in
                if waiting { segment = .restore }
            }
    }

    /// Date, version, areas with their number of files, size, warning.
    static func previewText(_ manifest: BackupManifest) -> String {
        var lines: [String] = []
        lines.append("Faite le \(BackupText.date(manifest.createdAtMillis)) avec ToolMap \(manifest.appVersion).")
        for area in BackupArea.allCases where manifest.covers(area) {
            let files = BackupText.files(manifest.fileCount(area))
            lines.append("• \(BackupText.area(area)) (\(files))")
        }
        lines.append("Taille : \(BackupText.bytes(manifest.totalBytes)).")
        lines.append(
            "Ces données remplaceront celles du téléphone, pour chacune des rubriques ci-dessus. ToolMap devra redémarrer ; un enregistrement en cours sera arrêté."
        )
        return lines.joined(separator: "\n")
    }
}

/// The list of the page: progress, report, segmented control, then the chosen half.
private struct BackupList: View {
    @Environment(AppModel.self) private var model
    @Binding var segment: BackupSegment
    @Binding var importing: Bool

    var body: some View {
        let backup = model.backup
        let busy = backup.busy != nil
        List {
            if let task = backup.busy {
                Section {
                    BackupProgressRow(label: task.label, progress: backup.progress)
                }
            }
            if let report = backup.report {
                Section {
                    BackupReportRow(report: report) { backup.dismissReport() }
                }
            }
            Section {
                Picker("Sauvegarde", selection: $segment) {
                    ForEach(BackupSegment.allCases) { item in
                        Text(item.title).tag(item)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .disabled(busy)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            switch segment {
            case .save:
                BackupSaveHalf(busy: busy)
            case .restore:
                BackupRestoreHalf(busy: busy, importing: $importing)
            }
        }
        .listStyle(.insetGrouped)
    }
}

private struct BackupProgressRow: View {
    let label: String
    let progress: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.subheadline)
            if let progress {
                ProgressView(value: progress)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .tint(Crete.turquoise)
        .padding(.vertical, 4)
    }
}

/// How the last restore went, until « Masquer ».
private struct BackupReportRow: View {
    let report: RestoreReport
    let onHide: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(report.ok ? Crete.onSurface : Crete.danger)
            HStack {
                Spacer()
                Button("Masquer", action: onHide)
                    .font(.subheadline.weight(.semibold))
            }
        }
        .padding(.vertical, 4)
    }

    private var text: String {
        if report.ok {
            return "Restauration réussie : sauvegarde du \(BackupText.date(report.backupMillis))."
        }
        return "La restauration a échoué : vos données n'ont pas été modifiées."
    }
}

/// « Sauvegarder »: what the file is for, what it holds and the offline maps on demand, what it never holds, read before
/// « Créer une sauvegarde… » (bordered while a restore waits).
private struct BackupSaveHalf: View {
    @Environment(AppModel.self) private var model
    let busy: Bool

    var body: some View {
        @Bindable var backup = model.backup
        Section {
            BackupInfoRow(
                icon: "doc.zipper",
                title: "Contenu",
                subtitle: "Traces, itinéraires, waypoints, réglages, alertes, cartes importées, espaces de travail, mises en page"
            )
            Toggle(isOn: $backup.includeTiles) {
                BackupInfoRow(
                    icon: "square.and.arrow.down.on.square",
                    title: "Inclure les cartes hors ligne",
                    subtitle: "Occupent \(BackupText.bytes(backup.tilesBytes)) ; la sauvegarde sera longue et volumineuse"
                )
            }
            .tint(Crete.turquoise)
            .disabled(busy)
        } header: {
            Text("Un fichier .zip à garder hors du téléphone (ordinateur, cloud) : il sert si vous changez de téléphone ou réinstallez l'application.")
                .textCase(nil)
        } footer: {
            Text("Pas sauvegardés : le partage de position (à refaire sur le nouveau téléphone), l'enregistrement en cours, les téléchargements en attente.")
        }
        Section {
            BackupMainButton(title: "Créer une sauvegarde…", prominent: backup.pending == nil, enabled: !busy) {
                backup.create()
            }
        }
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}

/// « Restaurer »: the restore waiting first, then what a restore does, and « Choisir une sauvegarde… ».
private struct BackupRestoreHalf: View {
    @Environment(AppModel.self) private var model
    let busy: Bool
    @Binding var importing: Bool

    var body: some View {
        let backup = model.backup
        if let pending = backup.pending {
            Section {
                BackupPendingCard(manifest: pending, busy: busy) { backup.cancelPending() }
            }
        }
        Section {
            BackupMainButton(title: "Choisir une sauvegarde…", prominent: backup.pending == nil, enabled: !busy) {
                importing = true
            }
        } header: {
            Text("Choisissez un fichier créé par ToolMap. Il est vérifié en entier avant tout changement ; il remplace ensuite vos données actuelles au prochain démarrage de l'application.")
                .textCase(nil)
        }
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}

/// « Restauration prête »: the backup waiting, and how to apply it on iPhone (an app does not restart itself).
private struct BackupPendingCard: View {
    let manifest: BackupManifest
    let busy: Bool
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.title3)
                    .foregroundStyle(Crete.turquoise)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Restauration prête")
                        .font(.headline)
                    Text(
                        "Sauvegarde du \(BackupText.date(manifest.createdAtMillis)) (nombre de fichiers : \(manifest.entries.count)). Elle remplacera vos données au prochain démarrage de ToolMap."
                    )
                    .font(.subheadline)
                    Text("Pour l'appliquer maintenant, fermez ToolMap (balayez-la vers le haut dans le sélecteur d'apps), puis rouvrez-la.")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            HStack {
                Spacer()
                Button("Annuler", action: onCancel)
                    .font(.subheadline.weight(.semibold))
                    .disabled(busy)
            }
        }
        .padding(.vertical, 4)
    }
}

/// A row of the group of « Sauvegarder »: a grey icon, the name, its second line.
private struct BackupInfoRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundStyle(Crete.onSurfaceVariant)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(Crete.onSurface)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .padding(.vertical, 2)
    }
}

/// The main action of a half: filled, or bordered when another action of the page comes first.
private struct BackupMainButton: View {
    let title: String
    let prominent: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        if prominent {
            Button(action: action) { label }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
                .disabled(!enabled)
        } else {
            Button(action: action) { label }
                .buttonStyle(.bordered)
                .tint(Crete.turquoise)
                .disabled(!enabled)
        }
    }

    private var label: some View {
        Text(title)
            .font(.body.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: 36)
    }
}

/// « Enregistrer dans Fichiers » of the archive written: the iOS document picker, which keeps the name of the file
/// (« toolmap-2026-10-29.zip »).
struct BackupExportPicker: UIViewControllerRepresentable {
    let url: URL
    let onFinish: (Bool) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        picker.delegate = context.coordinator
        picker.shouldShowFileExtensions = true
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFinish: (Bool) -> Void
        private var finished = false

        init(onFinish: @escaping (Bool) -> Void) {
            self.onFinish = onFinish
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            finish(true)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            finish(false)
        }

        private func finish(_ saved: Bool) {
            if finished { return }
            finished = true
            onFinish(saved)
        }
    }
}
