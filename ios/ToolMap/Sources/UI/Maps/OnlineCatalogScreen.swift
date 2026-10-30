import SwiftUI
import ToolMapCore

/// « Catalogue en ligne » (Android `OnlineCatalogScreen.kt`, P16.4, ADR-054): the maps of the providers that can be
/// added in one gesture. The same two segments as « Cartes disponibles », with their counts, the search, the providers
/// as headers. A map already in the catalogue (same id or same tiles) shows « Ajoutée »; one that needs an API key asks
/// for it first; the message of an added map offers « Afficher ». The terms of use close the list.
struct OnlineCatalogScreen: View {
    @Environment(AppModel.self) private var model
    @Binding var message: MapsMessage?

    @State private var layers = false
    @State private var query = ""
    @State private var asking: CatalogKeyRequest? = nil

    var body: some View {
        let maps = model.maps
        let catalog = maps.onlineCatalog
        let all = catalog.flatMap(\.entries)
        let layerCount = all.filter { $0.source.isLayer }.count
        let added = maps.catalogAdded()
        let text = query.trimmingCharacters(in: .whitespaces)
        let groups = filtered(catalog, text)
        List {
            if text.isEmpty {
                Section {
                    Picker("Segment", selection: $layers) {
                        Text("Fonds de carte · \(all.count - layerCount)").tag(false)
                        Text("Calques · \(layerCount)").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            if groups.isEmpty {
                Text("Aucune carte du catalogue ne correspond à « \(text) ».")
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            ForEach(groups, id: \.provider) { group in
                Section {
                    ForEach(group.entries, id: \.source.id) { entry in
                        CatalogEntryRow(entry: entry, added: added.contains(entry.source.id)) {
                            if entry.keyParams.isEmpty {
                                add(entry, [:])
                            } else {
                                asking = CatalogKeyRequest(entry: entry)
                            }
                        }
                    }
                } header: {
                    CatalogProviderHeader(group: group)
                }
            }
            Text("Cartes à ajouter en un geste, servies par chaque fournisseur selon ses conditions d'usage")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
                .listRowBackground(Color.clear)
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text("Catalogue en ligne")
                        .font(.headline)
                    Text(subtitle(maps: all.count, providers: catalog.count))
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Rechercher une carte")
        .sheet(item: $asking) { request in
            CatalogKeySheet(entry: request.entry) { keys in
                add(request.entry, keys)
            }
        }
    }

    /// The search goes through both segments, as in « Cartes disponibles ».
    private func filtered(_ catalog: [CatalogGroup], _ text: String) -> [CatalogGroup] {
        var result: [CatalogGroup] = []
        for group in catalog {
            let kept = group.entries.filter { entry in
                text.isEmpty ? entry.source.isLayer == layers : MapCatalog.matches(entry.source.name, text)
            }
            if !kept.isEmpty {
                result.append(CatalogGroup(provider: group.provider, entries: kept))
            }
        }
        return result
    }

    private func subtitle(maps: Int, providers: Int) -> String {
        let first = MapsText.plural(maps, "carte", "cartes")
        let second = MapsText.plural(providers, "fournisseur", "fournisseurs")
        return "\(first) · \(second)"
    }

    private func add(_ entry: CatalogEntry, _ keys: [String: String]) {
        let maps = model.maps
        guard maps.addFromCatalog(entry, keys: keys) else { return }
        let id = entry.source.id
        message = MapsMessage(text: "« \(entry.source.name) » ajoutée à vos cartes.", action: "Afficher") {
            maps.showMap(id)
        }
    }
}

/// A provider over its maps, with how many there are of each kind (« 4 calques »).
private struct CatalogProviderHeader: View {
    let group: CatalogGroup

    var body: some View {
        let layers = group.entries.filter { $0.source.isLayer }.count
        let bases = group.entries.count - layers
        var parts: [String] = []
        if bases > 0 { parts.append(MapsText.plural(bases, "fond", "fonds")) }
        if layers > 0 { parts.append(MapsText.plural(layers, "calque", "calques")) }
        return HStack {
            Text(MapsText.providerTitle(group.provider))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.turquoise)
            Spacer()
            Text(parts.joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(Crete.onSurfaceVariant)
        }
        .textCase(nil)
    }
}

/// A map of the online catalogue: the icon of its type in a disc, its name and zooms, « Ajouter » or « Ajoutée ». Also
/// the rows of the search of « Cartes disponibles ».
struct CatalogEntryRow: View {
    let entry: CatalogEntry
    let added: Bool
    let onAdd: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: MapsText.typeIcon(entry.source))
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(Crete.petrol)
                .frame(width: 40, height: 40)
                .background(Crete.tile, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.source.name)
                    .font(.body)
                    .foregroundStyle(Crete.onSurface)
                Text(MapsText.zooms(entry.source))
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Spacer(minLength: 4)
            if added {
                Label("Ajoutée", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Crete.turquoise)
            } else {
                Button("Ajouter", action: onAdd)
                    .buttonStyle(.borderless)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.turquoise)
            }
        }
        .padding(.vertical, 2)
    }
}

/// Asks for the API keys of a map (one field per parameter left empty in the catalogue); they stay on the phone.
struct CatalogKeySheet: View {
    @Environment(\.dismiss) private var dismiss
    let entry: CatalogEntry
    let onConfirm: ([String: String]) -> Void

    @State private var values: [String: String] = [:]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Cette carte demande une clé gratuite, à créer sur \(entry.source.dataSource ?? ""). Elle reste sur ce téléphone.")
                        .font(.subheadline)
                }
                Section {
                    ForEach(entry.keyParams, id: \.self) { key in
                        TextField("Clé d'API", text: binding(key))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                }
            }
            .navigationTitle("Clé d'API : \(entry.source.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Ajouter") {
                        onConfirm(values)
                        dismiss()
                    }
                    .disabled(!complete)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private var complete: Bool {
        entry.keyParams.allSatisfy { key in
            !(values[key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func binding(_ key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })
    }
}
