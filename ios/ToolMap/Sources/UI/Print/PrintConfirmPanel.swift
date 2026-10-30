import SwiftUI
import ToolMapCore

/// Step 3 (P17.8, variant A « Gabarit », ADR-098): the state of the PDF first (pages, size, weight, tiles), then « Sur la
/// carte » and « Feuille », the layout elements and the quality behind two summarised lines that open their own page
/// in the same panel (← comes back), and the box of a heavy PDF. The actions stay at its foot while the settings scroll.
struct PrintConfirmPanel: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        let maxHeight = UIScreen.main.bounds.height * 0.38
        switch printer.sheetPage {
        case .main:
            PrintChrome(title: "Imprimer à l'échelle", onClose: { printer.close() }) {
                PrintFittedScroll(maxHeight: maxHeight) {
                    PrintMainSettings()
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                }
            } footer: {
                PrintConfirmActions()
            }
        case .elements:
            PrintChrome(
                title: "Éléments de la feuille",
                subtitle: "Imprimer à l'échelle",
                onBack: { printer.openPage(.main) },
                onClose: { printer.close() }
            ) {
                PrintFittedScroll(maxHeight: maxHeight) {
                    PrintElementsPage()
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                }
            } footer: {
                PrintConfirmActions()
            }
        case .quality:
            PrintChrome(
                title: "Qualité et marges",
                subtitle: "Imprimer à l'échelle",
                onBack: { printer.openPage(.main) },
                onClose: { printer.close() }
            ) {
                PrintFittedScroll(maxHeight: maxHeight) {
                    PrintQualityPage()
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                }
            } footer: {
                PrintConfirmActions()
            }
        }
    }
}

/// The main page of the confirmation.
private struct PrintMainSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        let template = printer.template
        VStack(alignment: .leading, spacing: 8) {
            PrintStatusBox()
            if let heavy = PrintWords.heavy(printer.plan) {
                PrintHeavyBox(
                    title: "PDF très lourd",
                    text: "\(heavy.dpi) dpi, \(PrintWords.megapixels(heavy.pixels)) Mpx par page : création longue, échec possible.",
                    actionTitle: "Qualité et marges",
                    onAction: { printer.openPage(.quality) }
                )
            }
            PrintGroupLabel(title: "Sur la carte")
            PrintMenuField(label: "Grille", value: PrintWords.gridName(template.grid)) {
                ForEach(PrintWords.gridOrder, id: \.self) { kind in
                    Button(PrintWords.gridName(kind)) {
                        printer.update {
                            $0.grid = kind
                            $0.gridStep = nil
                        }
                    }
                }
            }
            PrintSwitchRow(title: "Waypoints et leurs noms", isOn: waypoints(printer))
            PrintSwitchRow(title: "Traces", isOn: printer.binding(\.dressing.tracks))
            Divider()
            PrintGroupLabel(title: "Feuille")
            PrintTextField(label: "Titre", text: printer.binding(\.title))
            PrintNavRow(title: "Éléments de la feuille", summary: PrintWords.elementsSummary(template.dressing)) {
                printer.openPage(.elements)
            }
            PrintNavRow(title: "Qualité et marges", summary: PrintWords.qualitySummary(template)) {
                printer.openPage(.quality)
            }
        }
    }

    /// One switch for the waypoints and their names (P17.8).
    private func waypoints(_ printer: PrintController) -> Binding<Bool> {
        Binding(
            get: { printer.template.dressing.waypoints },
            set: { value in
                printer.update {
                    $0.dressing.waypoints = value
                    $0.dressing.waypointNames = value
                }
            }
        )
    }
}

/// « Éléments de la feuille »: the layout elements by where they stand on the sheet, then the subtitle.
private struct PrintElementsPage: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        VStack(alignment: .leading, spacing: 6) {
            PrintGroupLabel(title: "Bandeau du bas")
            PrintSwitchRow(title: "Échelle chiffrée", isOn: printer.binding(\.dressing.scaleText))
            PrintSwitchRow(title: "Barre d'échelle", isOn: printer.binding(\.dressing.scaleBar))
            PrintSwitchRow(title: "Nords et déclinaison", isOn: printer.binding(\.dressing.northArrow))
            PrintSwitchRow(title: "Légende", isOn: printer.binding(\.dressing.legend))
            PrintSwitchRow(title: "Date et source", isOn: printer.binding(\.dressing.cartouche))
            Divider()
            PrintGroupLabel(title: "Cadre")
            PrintSwitchRow(title: "Coordonnées des coins", isOn: printer.binding(\.dressing.cornerCoordinates))
            PrintTextField(label: "Sous-titre", text: printer.binding(\.subtitle))
        }
    }
}

/// « Qualité et marges »: the resolution (150, 200, 300 dpi or any other from 72 dpi, P17.9) with the box of a heavy
/// PDF, the lighter file, the layers out of their range, the margin and the spacing of the grid.
private struct PrintQualityPage: View {
    @Environment(AppModel.self) private var model
    @State private var otherDpi = false
    @State private var dpiText = ""

    var body: some View {
        let printer = model.printer
        let template = printer.template
        VStack(alignment: .leading, spacing: 8) {
            PrintSectionTitle(title: "Résolution")
            dpiChips(printer)
            if otherDpi {
                PrintTextField(label: "Résolution", text: $dpiText, suffix: "dpi", valid: dpiValue != nil, keyboard: .numberPad)
                Text("\(PrintTemplate.minDpi) dpi au moins, pas de maximum")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .padding(.leading, 4)
            }
            heavyBox(printer.plan)
            PrintSwitchRow(
                title: "Fichier plus léger",
                hint: "Tuiles d'un niveau de zoom en moins",
                isOn: printer.binding(\.lighter)
            )
            PrintSwitchRow(
                title: "Laisser de côté les calques hors de leur plage de zoom",
                isOn: printer.binding(\.respectLayerRanges)
            )
            Text("Marge : \(Int(template.page.marginMm.rounded())) mm")
                .font(.subheadline.weight(.medium))
            Slider(value: margin(printer), in: 0 ... 30, step: 1)
                .tint(Crete.turquoise)
            if template.grid != GridKind.none {
                gridStep(printer, template)
            }
        }
        .onAppear {
            otherDpi = !PrintTemplate.dpis.contains(printer.template.dpi)
            dpiText = String(printer.template.dpi)
        }
        .onChange(of: dpiText) { _, _ in
            if otherDpi, let value = dpiValue {
                printer.update { $0.dpi = value }
            }
        }
    }

    /// The resolution typed after « Autre… », applied from 72 dpi; no highest.
    private var dpiValue: Int? {
        guard let value = Int(dpiText.trimmingCharacters(in: .whitespaces)), value >= PrintTemplate.minDpi else { return nil }
        return value
    }

    private func dpiChips(_ printer: PrintController) -> some View {
        HStack(spacing: 8) {
            ForEach(PrintTemplate.dpis, id: \.self) { dpi in
                PrintChip(title: "\(dpi) dpi", selected: !otherDpi && printer.template.dpi == dpi) {
                    otherDpi = false
                    printer.update { $0.dpi = dpi }
                }
            }
            PrintChip(title: "Autre…", selected: otherDpi) {
                otherDpi = true
                dpiText = String(printer.template.dpi)
            }
        }
    }

    @ViewBuilder
    private func heavyBox(_ plan: PrintPlan?) -> some View {
        if let heavy = PrintWords.heavy(plan) {
            let low = PrintWords.bytes(plan?.estimatedBytes.0 ?? 0)
            let high = PrintWords.bytes(plan?.estimatedBytes.1 ?? 0)
            let megapixels = PrintWords.megapixels(heavy.pixels)
            let highDpi = heavy.dpi > PrintTemplate.highDpi
            let risk = "\(heavy.dpi) dpi : \(megapixels) Mpx par page, PDF de \(low) à \(high). Création plus longue, échec possible faute de mémoire ou de place"
            PrintHeavyBox(
                title: highDpi ? "Résolution élevée" : "PDF très lourd",
                text: highDpi
                    ? risk + " ; au-delà de 300 dpi, l'imprimante et la carte gagnent rarement en netteté."
                    : risk + "."
            )
        }
    }

    private func margin(_ printer: PrintController) -> Binding<Double> {
        Binding(
            get: { Swift.min(Swift.max(printer.template.page.marginMm, 0), 30) },
            set: { value in printer.update { $0.page.marginMm = value.rounded() } }
        )
    }

    private func gridStep(_ printer: PrintController, _ template: PrintTemplate) -> some View {
        let degrees = PrintWords.isDegrees(template.grid)
        let automatic = "Automatique · " + PrintWords.gridStepText(GridSpacing.auto(template.grid, template.scale), degrees: degrees)
        let value = template.gridStep.map { PrintWords.gridStepText($0, degrees: degrees) } ?? automatic
        return PrintMenuField(label: "Pas de la grille", value: value) {
            Button(automatic) {
                printer.update { $0.gridStep = nil }
            }
            ForEach(GridSpacing.choices(template.grid), id: \.self) { step in
                Button(PrintWords.gridStepText(step, degrees: degrees)) {
                    printer.update { $0.gridStep = step }
                }
            }
        }
    }
}

/// The state of the PDF, first on the confirmation: what comes out, its weight, what stops it, the tiles to fetch.
private struct PrintStatusBox: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        let template = printer.template
        let pages = Swift.max(printer.plan?.mapPages.count ?? 1, 1)
        let orientation = PrintWords.orientation(template.page.landscape).lowercased()
        VStack(alignment: .leading, spacing: 3) {
            Text("\(PrintWords.pages(pages)) \(PrintWords.paperName(template.page)) \(orientation) · \(PrintWords.scale(template.scale))")
                .font(.headline)
                .foregroundStyle(Crete.onSurface)
            if let plan = printer.plan, !printer.planning {
                PrintPlanLines(plan: plan, template: template)
            } else {
                Text("Calcul en cours…")
                    .font(.footnote)
            }
            PrintTilesLine()
        }
        .foregroundStyle(Crete.onSurface)
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Crete.tile, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// The size, resolution and weight of the PDF, what stops it (red) and the maps enlarged or left out (orange).
private struct PrintPlanLines: View {
    let plan: PrintPlan
    let template: PrintTemplate

    var body: some View {
        let ground = PrintWords.groundSize(template)
        let low = PrintWords.bytes(plan.estimatedBytes.0)
        let high = PrintWords.bytes(plan.estimatedBytes.1)
        Text("\(ground.0) × \(ground.1) · \(template.dpi) dpi · \(low) à \(high)")
            .font(.footnote)
        ForEach(plan.issues, id: \.self) { issue in
            Text(PrintWords.issue(issue))
                .font(.footnote)
                .foregroundStyle(Crete.danger)
        }
        ForEach(warnings, id: \.self) { text in
            Text(text)
                .font(.footnote)
                .foregroundStyle(Crete.warning)
        }
    }

    private var warnings: [String] {
        plan.warnings.compactMap { PrintWords.warning($0) }
    }
}

/// The tiles still to fetch, with « Télécharger » when they are few enough; hatched beyond.
private struct PrintTilesLine: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        if let missing = printer.missingTiles {
            if missing == 0 {
                if (printer.plan?.totalTiles ?? 0) > 0 {
                    Text("Toutes les tuiles sont déjà stockées.")
                        .font(.footnote)
                }
            } else {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.down.circle")
                        .font(.footnote)
                    Text(PrintWords.tilesMissing(missing))
                        .font(.footnote.weight(.medium))
                    Spacer(minLength: 4)
                    if printer.canDownloadMissing, printer.job == nil {
                        Button("Télécharger") { printer.downloadMissing() }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Crete.petrol)
                            .buttonStyle(.plain)
                    }
                }
                .foregroundStyle(Crete.warning)
                .frame(minHeight: 36)
                if !printer.canDownloadMissing {
                    Text("Plus de \(PrintController.maxDownloadTiles) tuiles : enregistrez la zone avec « Sélectionner et enregistrer une zone », sinon les manques seront hachurés.")
                        .font(.footnote)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        } else {
            Text("Tuiles à télécharger : calcul…")
                .font(.footnote)
        }
    }
}

/// Retour à la zone · Créer le PDF, or the progress of the job with « Annuler ».
private struct PrintConfirmActions: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        if let job = printer.job {
            VStack(alignment: .leading, spacing: 6) {
                switch job {
                case let .downloading(done, total):
                    Text("Téléchargement des tuiles")
                        .font(.subheadline.weight(.semibold))
                    ProgressView(value: Double(done), total: Double(Swift.max(total, 1)))
                        .tint(Crete.turquoise)
                    Text("Tuiles : \(done) / \(total)")
                        .font(.footnote)
                case let .exporting(fraction):
                    Text("Création du PDF")
                        .font(.subheadline.weight(.semibold))
                    ProgressView(value: Swift.min(Swift.max(fraction, 0), 1))
                        .tint(Crete.turquoise)
                }
                PrintButton(title: "Annuler") { printer.cancelJob() }
                    .frame(maxWidth: 160)
            }
            .foregroundStyle(Crete.onSurface)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        } else {
            PrintButtonRow(
                cancelTitle: "Retour à la zone",
                mainTitle: "Créer le PDF",
                mainEnabled: printer.plan?.canExport == true && !printer.planning,
                onCancel: { printer.backToFrame() },
                onMain: { printer.export() }
            )
        }
    }
}
