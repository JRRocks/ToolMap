import SwiftUI
import ToolMapCore

/// Rechercher un lieu, des coordonnées ou un élément (Android SEARCH, P29.10 « Équerre »): a field with its loupe and
/// a cross that clears it (the keyboard open on arrival), the state of the search in cards under the field (what to
/// type, offline, nothing found, a service down with « Réessayer »), the results in white groups « Sur mon
/// téléphone » and « En ligne » with an icon each and their distance on the second line, each with its ⋮ (Voir sur la
/// carte · Aller vers · Enregistrer comme waypoint), and the credits at the foot.
struct SearchScreen: View {
    @Environment(AppModel.self) private var model
    @FocusState private var fieldFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                SearchField(focused: $fieldFocused)
                if model.search.searching {
                    ProgressView()
                        .padding(.vertical, 6)
                }
                SearchResultsList()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Recherche")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { ScreenCloseButton() }
            }
            .overlay(alignment: .bottom) {
                SearchMessage()
            }
        }
        .onAppear { model.search.open() }
        .task {
            // The keyboard opens on arrival, once the page is in place.
            try? await Task.sleep(nanoseconds: 450_000_000)
            fieldFocused = true
        }
    }
}

/// « Lieu, coordonnées ou élément », with the loupe and ✕ Effacer; the search key asks the services online.
private struct SearchField: View {
    @Environment(AppModel.self) private var model
    var focused: FocusState<Bool>.Binding

    var body: some View {
        let search = model.search
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Crete.onSurfaceVariant)
            TextField(
                "Lieu, coordonnées ou élément",
                text: Binding(get: { search.query }, set: { search.setQuery($0) })
            )
            .focused(focused)
            .submitLabel(.search)
            .onSubmit { search.submit() }
            .autocorrectionDisabled()
            if !search.query.isEmpty {
                Button {
                    search.setQuery("")
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                }
                .accessibilityLabel("Effacer")
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .frame(minHeight: 48)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Crete.outline, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }
}

/// The cards of state, then the two groups of results, then the credits.
private struct SearchResultsList: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let search = model.search
        List {
            SearchNotices()
            if !search.here.isEmpty {
                Section("Sur mon téléphone") {
                    SearchResultRows(results: search.here)
                }
            }
            if !search.online.isEmpty {
                Section("En ligne") {
                    SearchResultRows(results: search.online)
                }
            }
            if let credits = creditsText {
                Section {
                    EmptyView()
                } footer: {
                    Text(credits)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollDismissesKeyboard(.interactively)
    }

    /// « Recherche : © IGN Géoplateforme · © contributeurs OpenStreetMap (ODbL) », for the services shown.
    private var creditsText: String? {
        let online = model.search.online
        var credits: [String] = []
        if online.contains(where: { $0.source == .ign }) {
            credits.append("© IGN Géoplateforme")
        }
        if online.contains(where: { $0.source == .osm }) {
            credits.append("© contributeurs OpenStreetMap (ODbL)")
        }
        if credits.isEmpty { return nil }
        return "Recherche : " + credits.joined(separator: " · ")
    }
}

/// The state of the search, in cards: what to type, offline, « Validez au clavier… », nothing found, a service down.
private struct SearchNotices: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let search = model.search
        if let notice = noticeText {
            Section {
                Text(notice)
                    .font(.subheadline)
                    .foregroundStyle(Crete.onSurface)
                    .listRowBackground(Crete.tile)
            }
        }
        ForEach(Array(search.failures.enumerated()), id: \.offset) { pair in
            Section {
                SearchFailureCard(failure: pair.element)
                    .listRowBackground(Crete.danger.opacity(0.1))
            }
        }
    }

    private var noticeText: String? {
        let search = model.search
        let query = search.query.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty {
            return "Tapez un nom de lieu ou une position (44.84, 5.51 · 31T 452482 E 5011717 N). Mes traces, "
                + "itinéraires et waypoints sont cherchés au fil de la frappe ; les services en ligne le sont quand "
                + "vous validez."
        }
        if search.skippedOffline {
            return "Mode hors ligne : la recherche en ligne n'est pas envoyée. Seuls mes éléments et les coordonnées "
                + "sont cherchés."
        }
        if !search.searched, !search.searching {
            return "Validez au clavier pour chercher en ligne (IGN pour la France, OpenStreetMap pour le monde)."
        }
        if search.searched, search.online.isEmpty, search.failures.isEmpty, search.here.isEmpty {
            return "Aucun résultat."
        }
        return nil
    }
}

/// A service that did not answer, with « Réessayer ».
private struct SearchFailureCard: View {
    @Environment(AppModel.self) private var model
    let failure: GeocodeFailure

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Crete.danger)
            HStack {
                Spacer()
                Button("Réessayer") { model.search.submit() }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.petrol)
                    .buttonStyle(.borderless)
            }
        }
    }

    private var text: String {
        let service = failure.source == .ign ? "IGN Géoplateforme" : "OpenStreetMap"
        switch failure.reason {
        case .network:
            return "\(service) : pas de réponse (réseau ?)."
        case .unreadable:
            return "\(service) : réponse illisible."
        case .http:
            if failure.httpCode == 429 {
                return "\(service) : trop de demandes, réessayez dans un instant."
            }
            return "\(service) : erreur \(failure.httpCode ?? 0)."
        }
    }
}

private struct SearchResultRows: View {
    let results: [SearchResult]

    var body: some View {
        ForEach(Array(results.enumerated()), id: \.offset) { pair in
            SearchResultRow(result: pair.element)
        }
    }
}

/// A result: its icon in a disc (the user's own items on the light turquoise), its name, then what it is, where and
/// how far on one line; a touch shows it on the map, its ⋮ offers the rest.
private struct SearchResultRow: View {
    @Environment(AppModel.self) private var model
    let result: SearchResult

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 14) {
                SearchResultIcon(result: result)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(Crete.onSurface)
                        .lineLimit(1)
                    if !detail.isEmpty {
                        Text(detail)
                            .font(.footnote)
                            .foregroundStyle(Crete.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
            .onTapGesture { model.search.show(result) }
            SearchResultMenu(result: result)
        }
        .frame(minHeight: 52)
    }

    private var title: String {
        if result.source == .coordinates {
            return CoordinateFormat.format(model.settings.coordinates, result.lat, result.lon).text
        }
        return result.title
    }

    /// « Sommet · 38000 Grenoble · à 450 m ».
    private var detail: String {
        var parts: [String] = []
        if let kindText = kind, !kindText.trimmingCharacters(in: .whitespaces).isEmpty {
            parts.append(kindText)
        }
        if !result.subtitle.trimmingCharacters(in: .whitespaces).isEmpty {
            parts.append(result.subtitle)
        }
        if let center = model.search.center {
            let meters = Geodesy.distance(center.lat, center.lon, result.lat, result.lon)
            parts.append("à \(MapText.distance(meters, model.settings))")
        }
        return parts.joined(separator: " · ")
    }

    private var kind: String? {
        switch result.source {
        case .coordinates:
            return "Position"
        case .library:
            switch result.libraryKind {
            case .some(.track): return "Trace"
            case .some(.route): return "Itinéraire"
            default: return "Waypoint"
            }
        case .ign, .osm:
            return result.category
        }
    }
}

private struct SearchResultIcon: View {
    let result: SearchResult

    var body: some View {
        let mine = result.source == .library || result.source == .coordinates
        Image(systemName: symbol)
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(mine ? Crete.petrol : Crete.onSurfaceVariant)
            .frame(width: 40, height: 40)
            .background(mine ? Crete.pill : Crete.tile, in: Circle())
    }

    private var symbol: String {
        if result.source == .coordinates { return "location.fill" }
        switch result.libraryKind {
        case .some(.track): return "point.topleft.down.curvedto.point.bottomright.up"
        case .some(.route): return "arrow.triangle.branch"
        default: return "mappin.and.ellipse"
        }
    }
}

/// ⋮ of a result: Voir sur la carte · Aller vers · Enregistrer comme waypoint (not for the user's own items).
private struct SearchResultMenu: View {
    @Environment(AppModel.self) private var model
    let result: SearchResult

    var body: some View {
        Menu {
            Button {
                model.search.show(result)
            } label: {
                Label("Voir sur la carte", systemImage: "map")
            }
            Button {
                model.search.goTo(result)
            } label: {
                Label("Aller vers", systemImage: "location.north.line")
            }
            if result.source != .library {
                Button {
                    model.search.saveAsWaypoint(result)
                } label: {
                    Label("Enregistrer comme waypoint", systemImage: "mappin.and.ellipse")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Crete.onSurfaceVariant)
                .frame(width: 40, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("Actions")
    }
}

/// « Waypoint enregistré. » at the bottom of the page, for a few seconds.
private struct SearchMessage: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let message = model.search.message {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(Color.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
                .padding(.bottom, 16)
                .task(id: message) {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    if model.search.message == message {
                        model.search.message = nil
                    }
                }
        }
    }
}
