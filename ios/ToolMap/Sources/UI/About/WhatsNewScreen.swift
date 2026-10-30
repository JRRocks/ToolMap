import SwiftUI

/// One entry of « Nouveautés »: the title of an entry of docs/JOURNAL.md and its « Objectif » line.
struct WhatsNewEntry: Decodable, Identifiable {
    let title: String
    let goal: String

    var id: String { title }
}

/// whats-new.json, written into the resources by ios/ci/whats_new.py before the app is built.
struct WhatsNewFile: Decodable {
    let entries: [WhatsNewEntry]

    /// The entries shipped with the app; none when the file is missing or damaged.
    static func load(bundle: Bundle = .main) -> [WhatsNewEntry] {
        guard let url = bundle.url(forResource: "whats-new", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(WhatsNewFile.self, from: data)
        else { return [] }
        return file.entries
    }
}

/// Nouveautés (Android P9, P16.3: « Les 5 dernières mises à jour »): the five latest entries of the journal that
/// concern the iPhone, their title and their goal.
struct WhatsNewScreen: View {
    @State private var entries: [WhatsNewEntry] = WhatsNewFile.load()

    var body: some View {
        NavigationStack {
            Group {
                if entries.isEmpty {
                    ContentUnavailableView("Aucune nouveauté enregistrée.", systemImage: "megaphone")
                } else {
                    List {
                        ForEach(entries) { entry in
                            WhatsNewRow(entry: entry)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Nouveautés")
            .navigationBarTitleDisplayMode(.inline)
            .settingsNavigationBar()
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { ScreenCloseButton() }
            }
        }
        .tint(Crete.turquoise)
    }
}

private struct WhatsNewRow: View {
    let entry: WhatsNewEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(entry.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.onSurface)
            if !entry.goal.isEmpty {
                Text(entry.goal)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .padding(.vertical, 4)
    }
}
