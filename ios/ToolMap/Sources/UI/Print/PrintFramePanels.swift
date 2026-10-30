import SwiftUI
import ToolMapCore

/// Annuler / Suivant under the frame, with the reminder that the frame moves under a finger: the panel « Imprimer à
/// l'échelle » without ✕ (Android `PrintFrameActions`, P29.10: « Annuler » gives up).
struct PrintFrameActions: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        PrintChrome(title: "Imprimer à l'échelle") {
            Text("Glissez le cadre pour le placer ; la carte se déplace autour.")
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.top, 12)
        } footer: {
            PrintButtonRow(
                cancelTitle: "Annuler",
                mainTitle: "Suivant",
                onCancel: { printer.close() },
                onMain: { printer.confirm() }
            )
        }
    }
}

/// Under the pages of a print along a track (Android `PrintTrackActions`, T3b): how many pages, how they overlap, then
/// Annuler / Suivant. Suivant waits for the pages to be counted.
struct PrintTrackActions: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        let pages = printer.plan?.mapPages.count ?? 0
        PrintChrome(title: "Imprimer à l'échelle") {
            PrintTrackSummary()
                .padding(.horizontal, 16)
                .padding(.top, 12)
        } footer: {
            PrintButtonRow(
                cancelTitle: "Annuler",
                mainTitle: "Suivant",
                mainEnabled: pages > 0 && !printer.planning,
                onCancel: { printer.close() },
                onMain: { printer.confirm() }
            )
        }
    }
}

private struct PrintTrackSummary: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let printer = model.printer
        VStack(alignment: .leading, spacing: 4) {
            if let plan = printer.plan, !printer.planning {
                let pages = plan.mapPages.count
                let overlap = Int(plan.template.overlapPercent.rounded())
                Text(PrintWords.pages(pages)).fontWeight(.bold) + Text(" le long de la trace")
                Text(verbatim: "Chaque page recouvre la suivante de \(overlap) % · carte remise au nord")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                ForEach(plan.issues, id: \.self) { issue in
                    Text(PrintWords.issue(issue))
                        .font(.footnote)
                        .foregroundStyle(Crete.danger)
                }
            } else {
                Text("Calcul en cours…")
                    .font(.subheadline)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
