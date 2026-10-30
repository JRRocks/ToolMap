import Observation
import SwiftUI
import ToolMapCore

/// The editor of a multi-zoom map (Android `MultiZoomViewModel`, ADR-031): the AlpineQuest `.aqx` whose levels point to
/// different maps (`USR.PERSONNAL_MULTIZOOM.AQX`), built band by band. A saved map is an ordinary map of the user whose
/// levels reference other maps (`toolmap-map:`), so it is shown, downloaded and exported like any other.
@Observable
final class MultiZoomEditorModel {
    /// Highest zoom offered by the editor (beyond, MapLibre shows nothing useful).
    static let maxEditZoom = 22
    /// A forced zoom may be at most this many levels above the first zoom of its band.
    static let forceReach = ScalingPolicy.maxLevels
    private static let defaultTop = 18
    private static let newBandSpan = 2

    /// Id of the map being modified, nil for a new one (or a copy of a provided one).
    var editingId: String?
    var draft = MultiZoomDraft("", [])
    var issues: [DraftIssue] = []
    /// Why the last save failed, if it did.
    var error: String?

    @ObservationIgnored private weak var maps: MapsController?
    @ObservationIgnored private var started = false

    var canSave: Bool { !issues.contains { $0.kind.blocking } }

    /// The maps a band can show.
    var choices: [TileSource] {
        (maps?.sources ?? []).filter { $0.isSupported && $0.id != editingId }
    }

    func source(_ id: String) -> TileSource? {
        maps?.source(id)
    }

    func start(_ request: MultiZoomRequest, maps: MapsController) {
        guard !started else { return }
        started = true
        self.maps = maps
        switch request.start {
        case .new: startNew()
        case let .edit(id): edit(id)
        case let .derive(id): derive(id)
        }
    }

    /// A new map: one band over the first zooms of the first base map, to be split from there.
    private func startNew() {
        let base = maps?.sources.first { $0.isSupported && !$0.isLayer && !$0.isMultiZoom }
        var bands: [ZoomBand] = []
        if let base {
            let top = Swift.min(base.maxZoom ?? MultiZoomEditorModel.defaultTop, MultiZoomEditorModel.defaultTop)
            bands.append(ZoomBand(base.minZoom ?? 0, top, base.id))
        }
        open(nil, MultiZoomDraft("", bands))
    }

    /// Edits the multi-zoom map `id`; a provided one is copied (it cannot be modified).
    private func edit(_ id: String) {
        guard let maps, let map = maps.source(id) else { return }
        guard let found = MultiZoomDraft.of(map) else {
            derive(id)
            return
        }
        if maps.isImported(id) {
            open(id, found)
        } else {
            var copy = found
            copy.name = copyName(map)
            open(nil, copy)
        }
    }

    /// A new multi-zoom map starting from `id`: its levels become bands showing the maps that serve the same tiles.
    private func derive(_ id: String) {
        guard let maps, let map = maps.source(id) else { return }
        open(nil, MultiZoomDraft.derivedFrom(map, maps.sources, copyName(map)))
    }

    private func copyName(_ map: TileSource) -> String {
        map.isMultiZoom ? "\(map.name) (copie)" : "\(map.name) (multi-zoom)"
    }

    private func open(_ id: String?, _ next: MultiZoomDraft) {
        editingId = id
        draft = next
        issues = next.issues(id) { self.maps?.source($0) }
    }

    private func change(_ edit: (inout MultiZoomDraft) -> Void) {
        var copy = draft
        edit(&copy)
        draft = copy
        issues = copy.issues(editingId) { self.maps?.source($0) }
        error = nil
    }

    func setName(_ name: String) {
        change { $0.name = name }
    }

    /// Adds a band just above the last one, showing the same map; returns its index.
    func addBand() -> Int {
        let last = draft.bands.max { $0.to < $1.to }
        let band: ZoomBand
        if let last {
            let from = Swift.min(last.to + 1, MultiZoomEditorModel.maxEditZoom)
            let to = Swift.min(from + MultiZoomEditorModel.newBandSpan, MultiZoomEditorModel.maxEditZoom)
            band = ZoomBand(from, to, last.mapId)
        } else {
            let first = maps?.sources.first { $0.isSupported && !$0.isMultiZoom }
            band = ZoomBand(0, MultiZoomEditorModel.defaultTop, first?.id ?? "")
        }
        change { $0.bands.append(band) }
        return draft.bands.count - 1
    }

    /// « Combler » (P29.6): a band over the zooms `from` to `to` left without one, showing the map of the band just
    /// below them (the one above when there is none below). Returns the index of the new band.
    func fillGap(_ from: Int, _ to: Int) -> Int? {
        let bands = draft.bands
        let below = bands.filter { $0.to < from }.max { $0.to < $1.to }
        guard let neighbour = below ?? bands.min(by: { $0.from < $1.from }) else { return nil }
        change { $0.bands.append(ZoomBand(from, to, neighbour.mapId)) }
        return bands.count
    }

    func removeBand(_ index: Int) {
        guard draft.bands.indices.contains(index) else { return }
        change { $0.bands.remove(at: index) }
    }

    /// Sets the zooms of a band; a forced zoom follows when it would end up too far above it.
    func setRange(_ index: Int, _ from: Int, _ to: Int) {
        guard draft.bands.indices.contains(index) else { return }
        let band = draft.bands[index]
        let forced = band.forcedZoom.map { Swift.min($0, from + MultiZoomEditorModel.forceReach) }
        change { $0.bands[index] = ZoomBand(from, to, band.mapId, forced) }
    }

    func setMap(_ index: Int, _ mapId: String) {
        guard draft.bands.indices.contains(index) else { return }
        let band = draft.bands[index]
        change { $0.bands[index] = ZoomBand(band.from, band.to, mapId, band.forcedZoom) }
    }

    /// Turns the forced zoom of a band on (at the zoom of the map closest to the band) or off.
    func setForced(_ index: Int, _ on: Bool) {
        guard draft.bands.indices.contains(index) else { return }
        let band = draft.bands[index]
        let forced: Int? = on ? defaultForcedZoom(band) : nil
        change { $0.bands[index] = ZoomBand(band.from, band.to, band.mapId, forced) }
    }

    func setForcedZoom(_ index: Int, _ zoom: Int) {
        guard draft.bands.indices.contains(index) else { return }
        let band = draft.bands[index]
        change { $0.bands[index] = ZoomBand(band.from, band.to, band.mapId, zoom) }
    }

    /// Stores the map; returns its id, or nil when it cannot be saved yet.
    func save() -> String? {
        guard canSave, let maps else { return nil }
        let id = editingId ?? MultiZoomDraft.newId(draft.name) { maps.source($0) != nil }
        do {
            let map = try draft.toSource(id) { maps.source($0) }
            try maps.saveSource(map)
            return id
        } catch {
            self.error = "\(error)"
            return nil
        }
    }

    /// The zoom of the map nearest to the band (its first zoom when it serves the band).
    private func defaultForcedZoom(_ band: ZoomBand) -> Int {
        guard let map = maps?.source(band.mapId) else { return band.from }
        let served = (0 ... MultiZoomEditorModel.maxEditZoom).filter { map.levelFor($0) != nil }
        let best = served.min { distance($0, band) < distance($1, band) }
        return Swift.min(best ?? band.from, band.from + MultiZoomEditorModel.forceReach)
    }

    /// 0 inside the band, else the number of zooms to its nearest end.
    private func distance(_ zoom: Int, _ band: ZoomBand) -> Int {
        if zoom >= band.from, zoom <= band.to { return 0 }
        return Swift.min(abs(zoom - band.from), abs(zoom - band.to))
    }
}

/// Colours and texts of the multi-zoom editor.
enum MultiZoomStyle {
    private static let colors: [Color] = [
        Color(hex: 0x1E88E5), Color(hex: 0x43A047), Color(hex: 0xFB8C00), Color(hex: 0x8E24AA),
        Color(hex: 0xE53935), Color(hex: 0x00897B), Color(hex: 0x6D4C41),
    ]

    static func color(_ index: Int) -> Color {
        colors[index % colors.count]
    }

    /// « Zooms 1 à 8 » or « Zoom 18 ».
    static func zooms(_ from: Int, _ to: Int) -> String {
        from == to ? "Zoom \(from)" : "Zooms \(from) à \(to)"
    }

    static func issue(_ issue: DraftIssue, _ bands: [ZoomBand]) -> String {
        switch issue.kind {
        case .noName: return "Donnez un nom à la carte."
        case .noBand: return "Ajoutez au moins une tranche."
        case .badRange: return "Plage de zooms invalide."
        case .overlap: return "Ses zooms chevauchent ceux de la tranche \((issue.other ?? 0) + 1)."
        case .unknownMap: return "Choisissez une carte."
        case .loop: return "Cette carte affiche la carte en cours de modification : choisissez-en une autre."
        case .forcedTooFar:
            let reach = MultiZoomEditorModel.forceReach
            let levels = reach < 2 ? "niveau" : "niveaux"
            return "Zoom forcé trop fin : au plus \(reach) \(levels) au-dessus du premier zoom de la tranche."
        case .forcedNotServed: return "La carte n'a pas ce zoom : son zoom le plus proche sera utilisé."
        case .notServed: return "La carte n'a aucun de ces zooms : son zoom le plus proche sera agrandi ou réduit."
        case .gap:
            let lower = issue.band.flatMap { bands.indices.contains($0) ? bands[$0] : nil }
            let upper = issue.other.flatMap { bands.indices.contains($0) ? bands[$0] : nil }
            guard let before = lower, let after = upper else {
                return "Des zooms n'ont pas de tranche : ils sont remplis par la tranche voisine."
            }
            if before.to + 1 == after.from - 1 {
                return "Aucune tranche pour le zoom \(before.to + 1) : il est rempli par la tranche voisine."
            }
            return "Aucune tranche pour les zooms \(before.to + 1) à \(after.from - 1) : ils sont remplis par la tranche voisine."
        }
    }

    /// What a forced zoom does to the band: enlarged, shrunk, or the band's own zooms.
    static func forcedEffect(_ band: ZoomBand, _ forced: Int) -> String {
        if forced > band.to { return "réduites" }
        if forced < band.from { return "agrandies" }
        return "réduites au-dessous, agrandies au-dessus"
    }
}

/// A line of « Ce qui s'affiche »: a band (by its index in the draft) or zooms without one.
private struct MultiZoomRow: Identifiable {
    let id: String
    let index: Int?
    let from: Int
    let to: Int

    /// The bands from the lowest zooms up, with the zooms left between two of them (filled by a neighbour).
    static func rows(_ bands: [ZoomBand]) -> [MultiZoomRow] {
        let sorted = bands.enumerated().sorted { lhs, rhs in
            lhs.element.from != rhs.element.from ? lhs.element.from < rhs.element.from : lhs.element.to < rhs.element.to
        }
        var rows: [MultiZoomRow] = []
        var top = -1
        for (index, band) in sorted {
            if top >= 0, band.from > top + 1 {
                rows.append(MultiZoomRow(id: "gap-\(top + 1)", index: nil, from: top + 1, to: band.from - 1))
            }
            rows.append(MultiZoomRow(id: "band-\(index)", index: index, from: band.from, to: band.to))
            top = Swift.max(top, band.to)
        }
        return rows
    }
}

/// A band whose sheet is open.
private struct MultiZoomBandRef: Identifiable {
    let id: Int
}

/// The editor of a multi-zoom map (Android `MultiZoomScreen.kt`, P29.6 « Bascule »): the name, the zoom strip that stays
/// at the top, then « Ce qui s'affiche », one line per band and one per zooms left without a band, with « Combler ». A
/// band is set in its sheet: its zooms, its map, its forced zoom, its problems, its removal.
struct MultiZoomEditorScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let request: MultiZoomRequest
    let onDone: (String?) -> Void

    @State private var editor = MultiZoomEditorModel()
    @State private var openBand: MultiZoomBandRef? = nil

    init(request: MultiZoomRequest, onDone: @escaping (String?) -> Void) {
        self.request = request
        self.onDone = onDone
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Nom de la carte", text: nameBinding)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                MultiZoomStrip(bands: editor.draft.bands)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 10)
                Divider()
                bandList
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        onDone(nil)
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Annuler")
                }
                ToolbarItem(placement: .principal) {
                    Text(editor.editingId == nil ? "Nouvelle carte multi-zoom" : "Modifier la carte multi-zoom")
                        .font(.headline)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") {
                        if let id = editor.save() {
                            onDone(id)
                            dismiss()
                        }
                    }
                    .disabled(!editor.canSave)
                }
            }
        }
        .interactiveDismissDisabled()
        .onAppear {
            editor.start(request, maps: model.maps)
        }
        .sheet(item: $openBand) { ref in
            MultiZoomBandSheet(editor: editor, index: ref.id)
                .presentationDetents([.medium, .large])
        }
    }

    private var nameBinding: Binding<String> {
        Binding(get: { editor.draft.name }, set: { editor.setName($0) })
    }

    private var bandList: some View {
        let bands = editor.draft.bands
        let rows = MultiZoomRow.rows(bands)
        let general = editor.issues.filter { $0.band == nil }
        return List {
            Section {
                ForEach(rows) { row in
                    if let index = row.index {
                        bandRow(index, bands[index])
                    } else {
                        gapRow(row.from, row.to)
                    }
                }
            } header: {
                Text("Ce qui s'affiche")
                    .textCase(nil)
            }
            if !general.isEmpty {
                Section {
                    ForEach(Array(general.enumerated()), id: \.offset) { _, issue in
                        Text(MultiZoomStyle.issue(issue, bands))
                            .font(.footnote)
                            .foregroundStyle(issue.kind.blocking ? Crete.danger : Crete.warning)
                    }
                }
            }
            Section {
                Button {
                    openBand = MultiZoomBandRef(id: editor.addBand())
                } label: {
                    Label("Ajouter une tranche", systemImage: "plus")
                }
                .disabled(bands.count >= MultiZoomEditorModel.maxEditZoom + 1)
                .foregroundStyle(Crete.turquoise)
                if let error = editor.error {
                    Text(error)
                        .foregroundStyle(Crete.danger)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func bandRow(_ index: Int, _ band: ZoomBand) -> some View {
        let name = editor.source(band.mapId)?.name ?? "Choisir une carte"
        let subtitle = band.forcedZoom.map { "\(name) · tuiles du zoom \($0)" } ?? name
        let issues = editor.issues.filter { $0.band == index && $0.kind != .gap }
        let blocking = issues.contains { $0.kind.blocking }
        return Button {
            openBand = MultiZoomBandRef(id: index)
        } label: {
            HStack(spacing: 16) {
                Rectangle()
                    .fill(MultiZoomStyle.color(index))
                    .frame(width: 16, height: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text(MultiZoomStyle.zooms(band.from, band.to))
                        .foregroundStyle(Crete.onSurface)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer()
                if let first = issues.first {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(blocking ? Crete.danger : Crete.warning)
                        .accessibilityLabel(MultiZoomStyle.issue(first, []))
                }
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func gapRow(_ from: Int, _ to: Int) -> some View {
        HStack(spacing: 16) {
            Rectangle()
                .fill(Crete.surfaceVariant)
                .frame(width: 16, height: 16)
            VStack(alignment: .leading, spacing: 2) {
                Text(MultiZoomStyle.zooms(from, to))
                Text("Aucune tranche : rempli par la tranche voisine")
                    .font(.caption)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
            Spacer()
            Button("Combler") {
                if let index = editor.fillGap(from, to) {
                    openBand = MultiZoomBandRef(id: index)
                }
            }
            .buttonStyle(.borderless)
            .foregroundStyle(Crete.turquoise)
        }
    }
}

/// Zooms 0 to 22 in a row, each in the colour of the band that shows it (grey when none does); a dot marks a forced band.
private struct MultiZoomStrip: View {
    let bands: [ZoomBand]

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 1) {
                ForEach(0 ... MultiZoomEditorModel.maxEditZoom, id: \.self) { zoom in
                    cell(zoom)
                }
            }
            .frame(height: 18)
            HStack {
                ForEach([0, 5, 10, 15, 20], id: \.self) { zoom in
                    Text("\(zoom)")
                        .font(.caption2)
                        .foregroundStyle(Crete.onSurfaceVariant)
                    if zoom != 20 { Spacer() }
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func cell(_ zoom: Int) -> some View {
        let index = bands.firstIndex { zoom >= $0.from && zoom <= $0.to }
        let forced = index.map { bands[$0].forcedZoom != nil } ?? false
        return ZStack {
            Rectangle()
                .fill(index.map { MultiZoomStyle.color($0) } ?? Crete.surfaceVariant)
            if forced {
                Circle()
                    .fill(Crete.onSurface)
                    .frame(width: 6, height: 6)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// The settings of a band, in a sheet over the list: its zooms (two handles), its map and the map's zooms, its forced
/// zoom with its effect, its problems, and « Supprimer la tranche » in red.
private struct MultiZoomBandSheet: View {
    @Environment(\.dismiss) private var dismiss
    let editor: MultiZoomEditorModel
    let index: Int

    @State private var range = ZoomBar.Range(0, MultiZoomEditorModel.maxEditZoom)
    @State private var forcedDraft: Double = 0

    var body: some View {
        NavigationStack {
            Group {
                if editor.draft.bands.indices.contains(index) {
                    form(editor.draft.bands[index])
                } else {
                    Color.clear
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK") { dismiss() }
                }
            }
        }
        .onAppear(perform: sync)
    }

    private func form(_ band: ZoomBand) -> some View {
        let map = editor.source(band.mapId)
        let issues = editor.issues.filter { $0.band == index && $0.kind != .gap }
        return Form {
            Section {
                HStack(spacing: 12) {
                    Rectangle()
                        .fill(MultiZoomStyle.color(index))
                        .frame(width: 16, height: 16)
                    Text(MultiZoomStyle.zooms(range.min, range.max))
                        .font(.title3)
                }
                ConditionsRangeBar(
                    range: range,
                    max: MultiZoomEditorModel.maxEditZoom,
                    cursor: nil,
                    active: true,
                    onChange: { range = $0 },
                    onRelease: { editor.setRange(index, range.min, range.max) }
                )
            }
            Section("Carte") {
                Picker("Carte", selection: mapBinding(band)) {
                    ForEach(editor.choices, id: \.id) { choice in
                        VStack(alignment: .leading) {
                            Text(choice.name)
                            Text("Zooms \(choice.minZoom ?? 0) à \(choice.maxZoom ?? 0)")
                                .font(.caption)
                                .foregroundStyle(Crete.onSurfaceVariant)
                        }
                        .tag(choice.id)
                    }
                }
                .pickerStyle(.navigationLink)
                if let map, !map.isMultiZoom {
                    Text("Zooms de cette carte : \(map.minZoom ?? 0) à \(map.maxZoom ?? 0)")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            forcedSection(band)
            if !issues.isEmpty {
                Section {
                    ForEach(Array(issues.enumerated()), id: \.offset) { _, issue in
                        Text(MultiZoomStyle.issue(issue, []))
                            .font(.footnote)
                            .foregroundStyle(issue.kind.blocking ? Crete.danger : Crete.warning)
                    }
                }
            }
            Section {
                Button(role: .destructive) {
                    dismiss()
                    editor.removeBand(index)
                } label: {
                    Label("Supprimer la tranche", systemImage: "trash")
                }
            }
        }
        .onChange(of: band) { _, _ in
            sync()
        }
    }

    private func forcedSection(_ band: ZoomBand) -> some View {
        let top = Swift.min(band.from + MultiZoomEditorModel.forceReach, MultiZoomEditorModel.maxEditZoom)
        let forced = Int(forcedDraft.rounded())
        return Section {
            Toggle(isOn: Binding(get: { band.forcedZoom != nil }, set: { editor.setForced(index, $0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Zoom forcé")
                    Text("Les tuiles d'un seul zoom sur toute la tranche : plus fin que la tranche, il les réduit (4 par niveau, 4 niveaux au plus) ; plus grossier, il les agrandit.")
                        .font(.caption)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .tint(Crete.turquoise)
            if band.forcedZoom != nil {
                Text("Tuiles du zoom \(forced) (\(MultiZoomStyle.forcedEffect(band, forced)))")
                    .font(.subheadline)
                Slider(value: $forcedDraft, in: 0 ... Double(Swift.max(top, 1)), step: 1) { editing in
                    if !editing {
                        editor.setForcedZoom(index, Int(forcedDraft.rounded()))
                    }
                }
                .tint(Crete.turquoise)
            }
        }
    }

    private func mapBinding(_ band: ZoomBand) -> Binding<String> {
        Binding(get: { band.mapId }, set: { editor.setMap(index, $0) })
    }

    private func sync() {
        guard editor.draft.bands.indices.contains(index) else { return }
        let band = editor.draft.bands[index]
        range = ZoomBar.Range(band.from, Swift.max(band.to, band.from))
        forcedDraft = Double(band.forcedZoom ?? band.from)
    }
}
