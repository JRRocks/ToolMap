import Observation
import SwiftUI
import ToolMapCore

/// Réglages (Android §8, P29.9 « Loupe », docs/previews/design-09-reglages.html variant C): « Rechercher un réglage »
/// over the nine categories; what is typed shows the categories found and the settings themselves, usable there, the
/// category at the start of their second line and the words found in bold (`SettingsSearch`). A category opens on a
/// page with the tabs of the nine categories under the bar. Every change is saved at once; ⋯ « Rétablir les réglages par
/// défaut » applies at once and offers « Annuler ».
struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @State private var undo = SettingsUndo()
    @State private var query = ""
    @State private var path: [SettingsCategory] = []

    var body: some View {
        NavigationStack(path: $path) {
            SettingsHome(query: $query)
                .navigationTitle("Réglages")
                .navigationBarTitleDisplayMode(.inline)
                .settingsNavigationBar()
                .searchable(
                    text: $query,
                    placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Rechercher un réglage"
                )
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { ScreenCloseButton() }
                    ToolbarItem(placement: .topBarTrailing) { SettingsMoreMenu() }
                }
                .navigationDestination(for: SettingsCategory.self) { category in
                    SettingsCategoryPage(category: category, onSearch: { path = [] })
                }
        }
        .tint(Crete.turquoise)
        .environment(undo)
        .overlay(alignment: .bottom) {
            if let before = undo.before {
                SettingsToast(
                    text: "Réglages par défaut rétablis",
                    action: "Annuler",
                    id: undo.id,
                    onAction: {
                        model.settingsStore.settings = before
                        undo.before = nil
                    },
                    onTimeout: { undo.before = nil }
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
            }
        }
    }
}

/// The settings before « Rétablir les réglages par défaut », for « Annuler ».
@Observable
final class SettingsUndo {
    var before: AppSettings?
    var id = UUID()

    func remember(_ settings: AppSettings) {
        before = settings
        id = UUID()
    }
}

/// The ⋯ of the bar: « Rétablir les réglages par défaut ».
struct SettingsMoreMenu: View {
    @Environment(AppModel.self) private var model
    @Environment(SettingsUndo.self) private var undo

    var body: some View {
        Menu {
            Button {
                let before = model.settingsStore.reset()
                undo.remember(before)
            } label: {
                Label("Rétablir les réglages par défaut", systemImage: "arrow.counterclockwise")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Plus d'options")
    }
}

/// The list: the nine categories, or what the search found.
struct SettingsHome: View {
    @Environment(AppModel.self) private var model
    @Binding var query: String

    var body: some View {
        let terms = SettingsSearch.terms(query)
        List {
            if terms.isEmpty {
                Section {
                    ForEach(SettingsCategory.allCases) { category in
                        NavigationLink(value: category) {
                            SettingsCategoryLabel(category: category, terms: [], kind: false)
                        }
                    }
                }
            } else {
                SettingsResults(terms: terms, onClear: { query = "" })
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// The results of a search: the categories whose name holds the words, then the settings, those whose name holds them
/// first.
struct SettingsResults: View {
    @Environment(AppModel.self) private var model
    let terms: [String]
    let onClear: () -> Void

    var body: some View {
        let settings = model.settings
        let categories = SettingsCategory.allCases.filter { category in
            SettingsSearch.matches(terms, [category.title, category.tab])
        }
        let found = SettingsResults.found(terms, settings)
        if categories.isEmpty && found.isEmpty {
            Section {
                VStack(spacing: 16) {
                    Text("Aucun réglage ne contient ce mot.")
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    Button("Effacer la recherche", action: onClear)
                        .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
            }
        } else {
            Section {
                ForEach(categories) { category in
                    NavigationLink(value: category) {
                        SettingsCategoryLabel(category: category, terms: terms, kind: true)
                    }
                }
                ForEach(found) { item in
                    SettingsEntryRow(item: item, place: SettingsRowPlace(category: item.category.title, terms: terms))
                }
            }
        }
    }

    /// The settings that hold every word, those with the words in their name first.
    static func found(_ terms: [String], _ settings: AppSettings) -> [SettingsEntry] {
        let matching = SettingsCatalog.all().filter { item in
            item.visible(settings) && SettingsSearch.matches(terms, item.words)
        }
        let byName = matching.filter { SettingsSearch.matches(terms, [$0.title]) }
        let byText = matching.filter { !SettingsSearch.matches(terms, [$0.title]) }
        return byName + byText
    }
}

/// A category of the list: its icon in the accent colour, its name, « Catégorie » among the results.
struct SettingsCategoryLabel: View {
    let category: SettingsCategory
    let terms: [String]
    let kind: Bool

    var body: some View {
        SettingsEntryLabel(
            icon: category.icon,
            title: category.title,
            subtitle: kind ? "Catégorie" : nil,
            place: SettingsRowPlace(category: nil, terms: terms),
            iconColor: Crete.turquoise
        )
    }
}

/// The page of a category, with the tabs of the nine categories under the bar.
struct SettingsCategoryPage: View {
    @Environment(AppModel.self) private var model
    @State private var current: SettingsCategory
    let onSearch: () -> Void

    init(category: SettingsCategory, onSearch: @escaping () -> Void) {
        _current = State(initialValue: category)
        self.onSearch = onSearch
    }

    var body: some View {
        let settings = model.settings
        let sections = SettingsCategoryPage.sections(SettingsCatalog.items(current).filter { $0.visible(settings) })
        List {
            ForEach(sections) { section in
                Section {
                    ForEach(section.items) { item in
                        SettingsEntryRow(item: item)
                    }
                } header: {
                    if let title = section.title {
                        Text(title)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .id(current)
        .safeAreaInset(edge: .top, spacing: 0) {
            SettingsTabs(current: $current)
        }
        .navigationTitle(current.title)
        .navigationBarTitleDisplayMode(.inline)
        .settingsNavigationBar()
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onSearch) {
                    Image(systemName: "magnifyingglass")
                }
                .accessibilityLabel("Rechercher un réglage")
            }
            ToolbarItem(placement: .topBarTrailing) { SettingsMoreMenu() }
        }
    }

    /// Consecutive settings of the same block in one section.
    struct PageSection: Identifiable {
        let id: Int
        let title: String?
        var items: [SettingsEntry]
    }

    static func sections(_ items: [SettingsEntry]) -> [PageSection] {
        var out: [PageSection] = []
        for item in items {
            if let last = out.last, last.title == item.group {
                out[out.count - 1].items.append(item)
            } else {
                out.append(PageSection(id: out.count, title: item.group, items: [item]))
            }
        }
        return out
    }
}

/// The tabs of the nine categories, on the petrol of the bar, scrolled so that the one open is in view.
struct SettingsTabs: View {
    @Binding var current: SettingsCategory

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(SettingsCategory.allCases) { category in
                        SettingsTab(category: category, selected: category == current) {
                            current = category
                        }
                        .id(category)
                    }
                }
                .padding(.horizontal, 8)
            }
            .onAppear { proxy.scrollTo(current, anchor: .center) }
            .onChange(of: current) { _, newValue in
                withAnimation { proxy.scrollTo(newValue, anchor: .center) }
            }
        }
        .padding(.vertical, 6)
        .background(Crete.petrol)
    }
}

private struct SettingsTab: View {
    let category: SettingsCategory
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(category.tab)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? Crete.petrol : Crete.barText)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? Crete.pill : Color.clear, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
