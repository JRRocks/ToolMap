import SwiftUI
import ToolMapCore
import UIKit

/// « Nouveau waypoint » / « Modifier le waypoint » (Android `WaypointDialog`, P14.9, P29.4): the symbol (its badge with
/// a small pencil, which opens the picker) beside the name; the position on one line with its format button and the
/// altitude, read from the relief for a new waypoint; the pencil opens the coordinates typed in any common format and
/// the altitude; the description on request. A new waypoint is named « Waypoint N ».
struct WaypointDialogSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let draft: WaypointDraft

    @State private var name: String
    @State private var descriptionText: String
    @State private var describing: Bool
    @State private var symbol: String
    @State private var system: CoordinateSystem
    @State private var coordinates: String
    @State private var altitude: String
    /// The altitude comes from the relief until the user types one; only a new waypoint asks the relief.
    @State private var altitudeTyped: Bool
    @State private var fromTerrain = false
    @State private var lookingUp = false
    @State private var editingPosition = false
    @State private var picking = false
    @FocusState private var nameFocused: Bool

    init(draft: WaypointDraft) {
        self.draft = draft
        let initial = draft.initial
        _name = State(initialValue: initial?.name ?? draft.defaultName)
        let desc = initial?.desc ?? ""
        _descriptionText = State(initialValue: desc)
        _describing = State(initialValue: !desc.isEmpty)
        _symbol = State(initialValue: WaypointSymbols.canonical(initial?.sym))
        _system = State(initialValue: draft.system)
        let lat = initial?.lat ?? draft.start.lat
        let lon = initial?.lon ?? draft.start.lon
        _coordinates = State(initialValue: WaypointDialogSheet.written(draft.system, lat, lon))
        _altitude = State(initialValue: initial?.ele.map { WaypointDialogSheet.meters($0) } ?? "")
        _altitudeTyped = State(initialValue: initial != nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    nameRow
                }
                Section {
                    if editingPosition {
                        positionEditor
                    } else {
                        positionSummary
                    }
                }
                Section {
                    descriptionRow
                }
                Section {
                    // « Y aller »: saves the waypoint and makes it the target (Android, tonal button).
                    Button {
                        goThere()
                    } label: {
                        Label("Y aller", systemImage: "location.north.fill")
                    }
                    .disabled(build() == nil)
                }
            }
            .navigationTitle(draft.waypointId == nil ? "Nouveau waypoint" : "Modifier le waypoint")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") { save() }
                        .disabled(build() == nil)
                }
            }
            .navigationDestination(isPresented: $picking) {
                WaypointIconPicker(symbol: $symbol)
            }
            .task(id: lookupKey) {
                await lookUpAltitude()
            }
            .onAppear {
                if draft.waypointId == nil {
                    nameFocused = true
                }
            }
        }
    }

    // MARK: Rows

    private var nameRow: some View {
        HStack(spacing: 12) {
            Button {
                picking = true
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    WaypointIconBadge(icon: WaypointSymbols.iconOf(symbol), size: 48)
                    Image(systemName: "pencil")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Crete.onSurface)
                        .frame(width: 18, height: 18)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().strokeBorder(Crete.outline))
                }
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Changer le symbole")
            VStack(alignment: .leading, spacing: 2) {
                Text("Nom")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
                TextField("Nom", text: $name)
                    .focused($nameFocused)
                    .submitLabel(.done)
            }
        }
    }

    /// The position on one line, its altitude on the next, and the pencil that opens both for typing.
    @ViewBuilder
    private var positionSummary: some View {
        HStack(spacing: 8) {
            Image(systemName: "mappin")
                .foregroundStyle(Crete.petrol)
                .frame(width: 20)
            Text(shownCoordinates)
                .font(.subheadline)
                .lineLimit(2)
            Spacer(minLength: 4)
            formatMenu
        }
        .listRowBackground(Crete.tile)
        HStack(spacing: 8) {
            Image(systemName: "mountain.2")
                .foregroundStyle(Crete.petrol)
                .frame(width: 20)
            Text(altitudeText)
                .font(.subheadline)
                .foregroundStyle(Crete.onSurfaceVariant)
            Spacer(minLength: 4)
            Button {
                editingPosition = true
            } label: {
                Image(systemName: "pencil")
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Modifier les coordonnées et l'altitude")
        }
        .listRowBackground(Crete.tile)
    }

    @ViewBuilder
    private var positionEditor: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Coordonnées")
                .font(.caption)
                .foregroundStyle(Crete.onSurfaceVariant)
            HStack(spacing: 8) {
                TextField("Coordonnées", text: $coordinates, prompt: Text("44.8, 5.9 · 44°48.5'N 5°54'E"))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                formatMenu
            }
            if let error = parseError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(Crete.danger)
            }
        }
        VStack(alignment: .leading, spacing: 4) {
            Text("Altitude (m)")
                .font(.caption)
                .foregroundStyle(altitudeOk ? Crete.onSurfaceVariant : Crete.danger)
            TextField("Altitude (m)", text: altitudeBinding)
                .keyboardType(.numbersAndPunctuation)
        }
        if draft.waypointId != nil {
            Button("Déplacer au centre de la carte") {
                coordinates = WaypointDialogSheet.written(system, model.center.lat, model.center.lon)
            }
        }
    }

    @ViewBuilder
    private var descriptionRow: some View {
        if describing {
            TextField("Description", text: $descriptionText, axis: .vertical)
                .lineLimit(2 ... 6)
        } else {
            Button {
                describing = true
            } label: {
                Label("Ajouter une description", systemImage: "plus")
            }
        }
    }

    /// The short name of the format shown; touching it lists the six formats, and choosing one rewrites the position.
    private var formatMenu: some View {
        Menu {
            ForEach(CoordinateSystem.allCases, id: \.self) { candidate in
                Button {
                    select(candidate)
                } label: {
                    if candidate == system {
                        Label(WaypointDialogSheet.fullName(candidate), systemImage: "checkmark")
                    } else {
                        Text(WaypointDialogSheet.fullName(candidate))
                    }
                }
            }
        } label: {
            HStack(spacing: 2) {
                Text(WaypointDialogSheet.shortName(system))
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.bold))
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Crete.petrol)
            .frame(minHeight: 36)
        }
        .accessibilityLabel("Format des coordonnées")
    }

    // MARK: State

    private var parsed: ParsedCoordinate {
        CoordinateParser.parse(coordinates)
    }

    private var position: LonLat? {
        if case let .success(lat, lon) = parsed {
            return LonLat(lon: lon, lat: lat)
        }
        return nil
    }

    private var shownCoordinates: String {
        guard let at = position else { return coordinates }
        return WaypointDialogSheet.written(system, at.lat, at.lon)
    }

    private var parseError: String? {
        guard case let .failure(reason) = parsed else { return nil }
        switch reason {
        case .syntax: return "Format de coordonnées non reconnu."
        case .range: return "Latitude entre −90 et 90, longitude entre −180 et 180."
        case .sexagesimal: return "Minutes et secondes doivent être inférieures à 60."
        case .axes: return "Une latitude (N ou S) et une longitude (E ou O) sont attendues, sans signe en plus de la lettre."
        case .grid: return "Référence UTM, MGRS ou Lambert 93 impossible : zone, bande ou carré hors limites."
        }
    }

    private var altitudeValue: Double? {
        let text = altitude.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        return text.isEmpty ? nil : Double(text)
    }

    private var altitudeOk: Bool {
        altitude.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || altitudeValue != nil
    }

    private var altitudeText: String {
        if lookingUp { return "Altitude…" }
        guard let value = altitudeValue else { return "Altitude inconnue" }
        let text = LibraryText.elevation(value, model.settings)
        return fromTerrain ? "\(text) · relief" : text
    }

    private var altitudeBinding: Binding<String> {
        Binding(
            get: { altitude },
            set: { value in
                altitude = value
                altitudeTyped = true
                fromTerrain = false
            }
        )
    }

    private var lookupKey: String {
        guard let at = position else { return "" }
        return "\(at.lat),\(at.lon)"
    }

    private func select(_ candidate: CoordinateSystem) {
        system = candidate
        if let at = position {
            coordinates = WaypointDialogSheet.written(candidate, at.lat, at.lon)
        }
    }

    /// The altitude of the relief under the position, while the user has not typed one.
    private func lookUpAltitude() async {
        guard !altitudeTyped, let at = position else { return }
        if editingPosition {
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
        }
        lookingUp = true
        let ele = await LibraryRelief.at(at)
        lookingUp = false
        if Task.isCancelled || altitudeTyped { return }
        altitude = ele.map { WaypointDialogSheet.meters($0) } ?? ""
        fromTerrain = ele != nil
    }

    private func build() -> Waypoint? {
        guard let at = position, altitudeOk else { return nil }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDescription = descriptionText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = draft.initial ?? Waypoint(lat: at.lat, lon: at.lon)
        return base.libraryCopy(
            lat: at.lat,
            lon: at.lon,
            ele: altitudeValue,
            name: trimmedName.isEmpty ? nil : trimmedName,
            desc: trimmedDescription.isEmpty ? nil : trimmedDescription,
            sym: symbol
        )
    }

    private func save() {
        guard let waypoint = build() else { return }
        model.library.saveWaypoint(draft.waypointId, waypoint)
        dismiss()
    }

    private func goThere() {
        guard let waypoint = build() else { return }
        model.library.saveWaypoint(draft.waypointId, waypoint)
        let target = NavTarget(lat: waypoint.lat, lon: waypoint.lon, name: waypoint.name, sym: waypoint.sym)
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            model.routing.setTarget(target)
        }
    }

    // MARK: Formats

    /// The coordinates in `system`; degrees keep six decimals so that nothing is lost.
    static func written(_ system: CoordinateSystem, _ lat: Double, _ lon: Double) -> String {
        if system == .dd {
            return CoordinateFormat.decimalDegrees(lat, lon, decimals: 6)
        }
        return CoordinateFormat.format(system, lat, lon).text
    }

    static func meters(_ value: Double) -> String {
        String(Int(value.rounded()))
    }

    static func shortName(_ system: CoordinateSystem) -> String {
        switch system {
        case .dd: return "DD"
        case .ddm: return "DM"
        case .dms: return "DMS"
        case .utm: return "UTM"
        case .mgrs: return "MGRS"
        case .lambert93: return "L93"
        }
    }

    static func fullName(_ system: CoordinateSystem) -> String {
        switch system {
        case .dd: return "Degrés"
        case .ddm: return "Degrés, minutes"
        case .dms: return "Degrés, minutes, secondes"
        case .utm: return "UTM"
        case .mgrs: return "MGRS"
        case .lambert93: return "Lambert 93"
        }
    }
}

/// One category of the symbol picker with the icons it shows.
private struct WaypointIconGroup: Identifiable {
    let category: WaypointCatalog.Category
    let icons: [WaypointCatalog.Icon]

    var id: String { category.id }
}

/// « Symbole » (Android `WaypointIconPicker`, P16.6, P29.4): a search field, the 16 categories that fold, the 313
/// icons; it opens on the category of the current symbol, unfolded. The chosen icon is the one of the map.
struct WaypointIconPicker: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var symbol: String
    @State private var query = ""
    @State private var unfolded: Set<String>
    private let startCategory: String

    init(symbol: Binding<String>) {
        _symbol = symbol
        let current = WaypointSymbols.iconOf(symbol.wrappedValue).category
        startCategory = current
        _unfolded = State(initialValue: [current])
    }

    var body: some View {
        let selectedId = WaypointSymbols.iconOf(symbol).id
        let groups = WaypointIconPicker.groups(query)
        let searching = !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        ScrollViewReader { proxy in
            List {
                if groups.isEmpty {
                    Text("Aucune icône ne correspond.")
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                ForEach(groups) { group in
                    Section {
                        categoryRow(group, open: searching || unfolded.contains(group.id))
                        if searching || unfolded.contains(group.id) {
                            WaypointIconGrid(icons: group.icons, selectedId: selectedId) { icon in
                                symbol = icon.key
                                dismiss()
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .onAppear {
                let target = startCategory
                DispatchQueue.main.async {
                    proxy.scrollTo(target, anchor: .top)
                }
            }
        }
        .searchable(text: $query, prompt: "Rechercher une icône")
        .navigationTitle("Symbole")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func categoryRow(_ group: WaypointIconGroup, open: Bool) -> some View {
        Button {
            if unfolded.contains(group.id) {
                unfolded.remove(group.id)
            } else {
                unfolded.insert(group.id)
            }
        } label: {
            HStack(spacing: 10) {
                Circle()
                    .fill(LibraryColors.color(group.category.colorArgb))
                    .frame(width: 12, height: 12)
                Text(WaypointLabels.category(group.id))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Crete.onSurface)
                Spacer()
                Text("\(group.icons.count)")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
                Image(systemName: open ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .id(group.id)
    }

    /// The categories that have icons matching `query` (their name, case ignored), every icon without a query.
    private static func groups(_ query: String) -> [WaypointIconGroup] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var out: [WaypointIconGroup] = []
        for category in WaypointCatalog.categories {
            let icons: [WaypointCatalog.Icon]
            if needle.isEmpty {
                icons = category.icons
            } else {
                icons = category.icons.filter { WaypointLabels.icon($0.id, key: $0.key).lowercased().contains(needle) }
            }
            if !icons.isEmpty {
                out.append(WaypointIconGroup(category: category, icons: icons))
            }
        }
        return out
    }
}

/// The icons of a category, 64 points wide each with their name in 12 points.
private struct WaypointIconGrid: View {
    let icons: [WaypointCatalog.Icon]
    let selectedId: String
    let onPick: (WaypointCatalog.Icon) -> Void

    private static let columns = [GridItem(.adaptive(minimum: 64), spacing: 4)]

    var body: some View {
        LazyVGrid(columns: WaypointIconGrid.columns, spacing: 6) {
            ForEach(icons, id: \.id) { icon in
                Button {
                    onPick(icon)
                } label: {
                    VStack(spacing: 3) {
                        WaypointIconBadge(icon: icon, size: 36)
                        Text(WaypointLabels.icon(icon.id, key: icon.key))
                            .font(.system(size: 12))
                            .foregroundStyle(Crete.onSurface)
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                    }
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(
                        icon.id == selectedId ? Crete.pill.opacity(0.45) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 8)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(WaypointLabels.icon(icon.id, key: icon.key))
            }
        }
        .padding(.vertical, 4)
    }
}
