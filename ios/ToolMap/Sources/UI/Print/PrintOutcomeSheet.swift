import QuickLook
import SwiftUI
import ToolMapCore

/// « PDF enregistré · 1 page · 2,4 Mo », the reminder to print at 100 %, Ouvrir (Quick Look preview) and Partager
/// (P17, Q19; Android `PrintOutcomeDialog`), or why the export failed. A small sheet rather than an alert: an alert
/// cannot hold the share button.
struct PrintOutcomeSheet: View {
    @Environment(\.dismiss) private var dismiss
    let outcome: PrintOutcome
    @State private var preview: URL? = nil

    init(outcome: PrintOutcome) {
        self.outcome = outcome
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch outcome.kind {
            case let .exported(url, pages, bytes, missingTiles):
                exported(url, pages, bytes, missingTiles)
            case let .failed(message):
                Text("Création du PDF")
                    .font(.headline)
                Text("L'export a échoué : \(message ?? "erreur inconnue")")
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                HStack {
                    Spacer()
                    Button("Fermer") { dismiss() }
                        .font(.body.weight(.semibold))
                }
            }
        }
        .foregroundStyle(Crete.onSurface)
        .tint(Crete.petrol)
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .quickLookPreview($preview)
        .presentationDetents([.height(260), .medium])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func exported(_ url: URL, _ pages: Int, _ bytes: Int, _ missingTiles: Int) -> some View {
        Text("PDF enregistré · \(PrintWords.pages(pages)) · \(PrintWords.bytes(bytes))")
            .font(.headline)
        if missingTiles > 0 {
            Text("Des tuiles manquaient : zones hachurées sur la carte (\(missingTiles)).")
                .font(.subheadline)
                .foregroundStyle(Crete.warning)
                .fixedSize(horizontal: false, vertical: true)
        }
        Text(verbatim: "Pour garder l'échelle, imprimez à 100 % (« taille réelle »), jamais « ajuster à la page ».")
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
        HStack(spacing: 20) {
            Button("Ouvrir") { preview = url }
            ShareLink(item: url) {
                Text("Partager")
            }
            Spacer()
            Button("Fermer") { dismiss() }
        }
        .font(.body.weight(.semibold))
        .padding(.top, 4)
    }
}
