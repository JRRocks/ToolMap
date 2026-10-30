import SwiftUI
import ToolMapCore
import UIKit

/// The add-a-map wizard (Android `SourceWizardScreen.kt`, N2, P29.6): address (or capabilities) → access → zooms and
/// look → test tile → save. The kind is chosen in « Ajouter une carte » and is the title of a new map; the five named
/// stops take a tick once done and can be touched to go back (all of them in a modification); the filled button names
/// what follows (« Suivant : Accès »… « Enregistrer »). No « Retour » at the first step: ✕ closes the wizard.
struct SourceWizardScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let request: WizardRequest
    let onDone: (WizardResult?) -> Void

    @State private var wizard = SourceWizardModel()
    @State private var shareFile: MapsShareFile? = nil

    init(request: WizardRequest, onDone: @escaping (WizardResult?) -> Void) {
        self.request = request
        self.onDone = onDone
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                WizardStops(wizard: wizard)
                Divider()
                Form {
                    stepContent
                    WizardIssueLines(issues: stepIssues)
                }
            }
            .safeAreaInset(edge: .bottom) {
                bottomBar
            }
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
                    VStack(spacing: 0) {
                        Text(wizard.isEditing ? "Modifier une carte" : wizard.kind.title)
                            .font(.headline)
                        Text(wizard.isEditing ? wizard.draft.name : "Nouvelle carte")
                            .font(.caption)
                            .foregroundStyle(Crete.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled()
        .onAppear {
            let center = model.center
            let here = LonLatZoom(lon: center.lon, lat: center.lat, zoom: Int(model.maps.displayedZoom.rounded()))
            wizard.start(kind: request.kind, sourceId: request.sourceId, maps: model.maps, center: here)
        }
        .sheet(item: $shareFile) { file in
            MapsShareSheet(url: file.url)
        }
        .alert("ToolMap", isPresented: errorShown) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(wizard.error ?? "")
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch wizard.step {
        case .address: WizardAddressStep(wizard: wizard)
        case .access: WizardAccessStep(wizard: wizard)
        case .zooms: WizardZoomsStep(wizard: wizard)
        case .test: WizardTestStep(wizard: wizard)
        case .save: WizardSaveStep(wizard: wizard) { exportFile() }
        }
    }

    /// The issues shown under each step: errors of the step, and the warnings at the address.
    private var stepIssues: [SourceDraftIssue] {
        let issues = wizard.issues
        switch wizard.step {
        case .address: return issues.filter { SourceWizardModel.addressIssues.contains($0) || !$0.isError }
        case .access: return issues.filter { SourceWizardModel.accessIssues.contains($0) }
        case .zooms: return issues.filter { SourceWizardModel.zoomIssues.contains($0) }
        case .test, .save: return []
        }
    }

    private var bottomBar: some View {
        HStack(spacing: 8) {
            if wizard.step != .address {
                Button("Retour") {
                    wizard.back()
                }
                .foregroundStyle(Crete.turquoise)
            }
            Spacer()
            if wizard.step == .save {
                Button("Enregistrer") {
                    if let id = wizard.save() {
                        onDone(WizardResult(id: id, show: wizard.addToMap && !wizard.isEditing))
                        dismiss()
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
                .disabled(!wizard.blockingIssues.isEmpty)
            } else if let following = WizardStep(rawValue: wizard.step.rawValue + 1) {
                Button("Suivant : \(following.title)") {
                    wizard.next()
                }
                .buttonStyle(.borderedProminent)
                .tint(Crete.turquoise)
                .disabled(!wizard.canContinue)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func exportFile() {
        if let url = wizard.exportFile() {
            shareFile = MapsShareFile(url: url)
        }
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { wizard.error != nil }, set: { if !$0 { wizard.error = nil } })
    }
}

/// The five named stops: the current one a filled dot, a done one a tick, the others a ring, joined by a line. A stop up
/// to the furthest reached can be touched (forward only while the step in progress is right).
private struct WizardStops: View {
    let wizard: SourceWizardModel

    var body: some View {
        HStack(spacing: 0) {
            ForEach(WizardStep.allCases, id: \.self) { stop in
                stopView(stop)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
    }

    private func stopView(_ stop: WizardStep) -> some View {
        let current = stop == wizard.step
        let done = stop < wizard.reached && !current
        let enabled = !current && stop <= wizard.reached && (stop < wizard.step || wizard.canContinue)
        let spoken = current ? "\(stop.title), étape en cours" : (done ? "\(stop.title), fait" : stop.title)
        return Button {
            wizard.goTo(stop)
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    HStack(spacing: 0) {
                        Rectangle()
                            .fill(stop == .address ? Color.clear : Crete.outline)
                            .frame(height: 2)
                        Color.clear.frame(width: 20, height: 2)
                        Rectangle()
                            .fill(stop == .save ? Color.clear : Crete.outline)
                            .frame(height: 2)
                    }
                    marker(current: current, done: done)
                }
                .frame(height: 20)
                Text(stop.title)
                    .font(.caption.weight(current ? .bold : .regular))
                    .foregroundStyle(current ? Crete.onSurface : Crete.onSurfaceVariant)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(spoken)
    }

    @ViewBuilder
    private func marker(current: Bool, done: Bool) -> some View {
        if current {
            Circle().fill(Crete.turquoise).frame(width: 14, height: 14)
        } else if done {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 18))
                .foregroundStyle(Crete.turquoise)
                .background(Circle().fill(Color.white))
        } else {
            Circle()
                .fill(Color.white)
                .frame(width: 14, height: 14)
                .overlay(Circle().stroke(Crete.outline, lineWidth: 2))
        }
    }
}

/// Errors in red, warnings with their icon.
private struct WizardIssueLines: View {
    let issues: [SourceDraftIssue]

    var body: some View {
        if !issues.isEmpty {
            Section {
                ForEach(issues, id: \.self) { issue in
                    HStack(alignment: .top, spacing: 8) {
                        if !issue.isError {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(Crete.warning)
                        }
                        Text(WizardTexts.issue(issue))
                            .font(.footnote)
                            .foregroundStyle(issue.isError ? Crete.danger : Crete.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/// Texts of the wizard (Android `strings.xml`).
enum WizardTexts {
    static func issue(_ issue: SourceDraftIssue) -> String {
        switch issue {
        case .nameEmpty: return "Donnez un nom à la carte."
        case .urlEmpty: return "Aucune adresse de tuiles."
        case .urlNotHttp: return "L'adresse doit commencer par http:// ou https://."
        case .noTilePlaceholder: return "L'adresse doit contenir {$z}, {$x} et {$y} (ou {$q}, ou {$bbox})."
        case .unknownPlaceholder:
            return "L'adresse contient un marqueur inconnu (valeurs possibles : {$x} {$y} {$!y} {$z} {$q} {$bbox} {$ibbox} {$param:nom})."
        case .missingParam: return "L'adresse utilise un paramètre {$param:…} qui n'existe pas."
        case .zoomInvalid: return "Zooms invalides : écrivez par exemple 0-17 ou 1-5,8 (de 0 à 30)."
        case .tileSizeInvalid: return "La taille des tuiles doit être 256 ou 512."
        case .updateDelayInvalid: return "Délai de renouvellement invalide."
        case .headerInvalid: return "Une ligne d'en-tête est invalide : « Nom: valeur » attendu."
        case .threadsInvalid: return "Le nombre de téléchargements simultanés doit être entre 1 et 16."
        case .opacityInvalid: return "L'opacité doit être entre 0 et 100."
        case .cleartext: return "Adresse non chiffrée (http://) : les échanges peuvent être lus par un tiers."
        case .keyInUrl: return "Une clé d'API est écrite dans l'adresse : elle serait exportée avec la carte."
        }
    }

    static let placeholders = ["{$z}", "{$x}", "{$y}", "{$q}", "{$!y}", "{$bbox}"]

    static let delays: [WizardOption] = [
        WizardOption(id: "", label: "Jamais"),
        WizardOption(id: "1D", label: "1 jour"),
        WizardOption(id: "1W", label: "1 semaine"),
        WizardOption(id: "1M", label: "1 mois"),
        WizardOption(id: "3M", label: "3 mois"),
        WizardOption(id: "1Y", label: "1 an"),
    ]

    static let kinds: [WizardOption] = [
        WizardOption(id: "roads", label: "Routes"),
        WizardOption(id: "topo", label: "Topographique"),
        WizardOption(id: "satellite", label: "Satellite"),
        WizardOption(id: "hybrid", label: "Hybride"),
        WizardOption(id: "hillshade", label: "Ombrage"),
        WizardOption(id: "nautical", label: "Nautique"),
    ]

    /// Options whose value is also their name.
    static func plain(_ values: [String]) -> [WizardOption] {
        values.map { WizardOption(id: $0, label: $0) }
    }
}

/// A choice of a row of chips: its value and its name.
struct WizardOption: Identifiable {
    let id: String
    let label: String
}

/// A row of choices as chips (Android `FilterChip`).
private struct WizardChips: View {
    let options: [WizardOption]
    let selected: String?
    let onSelect: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options) { option in
                    let on = option.id == selected
                    Button {
                        onSelect(option.id)
                    } label: {
                        Text(option.label)
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .frame(height: 32)
                            .foregroundStyle(on ? Color.white : Crete.onSurface)
                            .background(on ? Crete.turquoise : Crete.tile, in: RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.borderless)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
    }
}

// MARK: Address

private struct WizardAddressStep: View {
    @Bindable var wizard: SourceWizardModel
    /// The typed text, kept as it is (blank lines too) while the draft gets its servers.
    @State private var urlsText = ""

    var body: some View {
        if wizard.kind == .xyz {
            xyzAddress
                .onAppear {
                    urlsText = wizard.draft.urls.joined(separator: "\n")
                }
                .onChange(of: urlsText) { _, text in
                    wizard.setUrls(text)
                }
                .onChange(of: wizard.draft.urls) { _, urls in
                    // Follow changes made elsewhere (key moved to a parameter) without disturbing typing.
                    if SourceWizardModel.parseUrls(urlsText) != urls {
                        urlsText = urls.joined(separator: "\n")
                    }
                }
        } else {
            WizardServiceAddress(wizard: wizard)
        }
        if !wizard.keysMoved.isEmpty {
            Section {
                Text("Clé rangée dans le paramètre : \(wizard.keysMoved.joined(separator: ", "))")
                    .font(.footnote)
            }
        }
    }

    @ViewBuilder
    private var xyzAddress: some View {
        Section {
            TextEditor(text: $urlsText)
                .font(.system(.footnote, design: .monospaced))
                .frame(minHeight: 88)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } header: {
            Text("Adresse des tuiles")
        } footer: {
            Text("Une adresse par ligne : plusieurs lignes répartissent les requêtes entre plusieurs serveurs.")
        }
        Section("Insérer") {
            WizardChips(options: WizardTexts.plain(WizardTexts.placeholders), selected: nil) { token in
                urlsText += token
            }
        }
        if let detected = wizard.detectedKey {
            Section {
                Text("Clé d'API détectée dans l'adresse : \(detected.0) = \(ApiKeyExtractor.masked(detected.1))")
                    .font(.subheadline)
                Text("Elle sera rangée dans un paramètre séparé quand vous continuerez : vous pourrez la masquer et l'omettre des exports.")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
                Button("Ranger la clé maintenant") {
                    wizard.extractKeysNow()
                }
                .foregroundStyle(Crete.turquoise)
            }
        }
    }
}

/// The address of a WMTS / WMS service, its layers and the options of the one chosen.
private struct WizardServiceAddress: View {
    @Bindable var wizard: SourceWizardModel
    private static let maxListed = 40

    var body: some View {
        Section {
            TextField("ex. https://data.geopf.fr/wmts", text: $wizard.serviceAddress)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            HStack {
                Button(loading ? "Lecture en cours…" : "Lire les couches") {
                    wizard.readCapabilities()
                }
                .buttonStyle(.borderless)
                .disabled(loading || wizard.serviceAddress.trimmingCharacters(in: .whitespaces).isEmpty)
                Spacer()
                Button("Saisir l'adresse des tuiles à la main") {
                    wizard.kind = .xyz
                }
                .buttonStyle(.borderless)
                .font(.footnote)
            }
            .foregroundStyle(Crete.turquoise)
        } header: {
            Text("Adresse du service")
        }
        capabilitiesContent
    }

    private var loading: Bool {
        if case .loading = wizard.capabilities { return true }
        return false
    }

    @ViewBuilder
    private var capabilitiesContent: some View {
        switch wizard.capabilities {
        case .idle, .loading:
            EmptyView()
        case let .failed(text):
            Section {
                Text(text)
                    .foregroundStyle(Crete.danger)
            }
        case let .loaded(_, document):
            switch document {
            case let .wmts(caps): wmtsLayers(caps)
            case let .wms(caps): wmsLayers(caps)
            }
        }
    }

    private func needle() -> String {
        wizard.layerFilter.trimmingCharacters(in: .whitespaces)
    }

    private func wmtsLayers(_ caps: WmtsCapabilities) -> some View {
        let usable = caps.usableLayers
        let text = needle()
        let matches = usable.filter { layer in
            text.isEmpty || layer.title.localizedCaseInsensitiveContains(text)
                || layer.id.localizedCaseInsensitiveContains(text)
        }
        let chosen = wizard.choice
        let layer = chosen.flatMap { choice in caps.layers.first { $0.id == choice.layerId } }
        return Group {
            Section {
                if usable.isEmpty {
                    Text("Aucune couche utilisable : ToolMap n'affiche que le Web Mercator (EPSG:3857).")
                } else {
                    TextField("Filtrer par nom ou identifiant", text: $wizard.layerFilter)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    ForEach(Array(matches.prefix(WizardServiceAddress.maxListed)), id: \.id) { item in
                        WizardLayerRow(title: item.title, id: item.id, selected: chosen?.layerId == item.id) {
                            wizard.pickWmtsLayer(item)
                        }
                    }
                    if matches.count > WizardServiceAddress.maxListed {
                        Text("Autres couches (affinez le filtre) : \(matches.count - WizardServiceAddress.maxListed)")
                            .font(.footnote)
                    }
                }
            } header: {
                Text("Couches utilisables / au total : \(usable.count) / \(caps.layers.count)")
            }
            if let chosen, let layer {
                wmtsOptions(caps, layer, chosen)
            }
        }
    }

    private func wmtsOptions(_ caps: WmtsCapabilities, _ layer: WmtsLayer, _ chosen: WizardLayerChoice) -> some View {
        let styles = layer.styles.map(\.id)
        let sets = caps.usableLinks(layer).map(\.matrixSetId)
        return Section {
            if styles.count > 1 {
                Text("Style").font(.caption)
                WizardChips(options: WizardTexts.plain(styles), selected: chosen.styleId) { id in
                    wizard.changeWmts { $0.styleId = id }
                }
            }
            if sets.count > 1 {
                Text("Grille de tuiles").font(.caption)
                WizardChips(options: WizardTexts.plain(sets), selected: chosen.matrixSetId) { id in
                    wizard.changeWmts { $0.matrixSetId = id }
                }
            }
            if layer.formats.count > 1 {
                Text("Format").font(.caption)
                WizardChips(options: WizardTexts.plain(layer.formats), selected: chosen.format) { format in
                    wizard.changeWmts { $0.format = format }
                }
            }
        } header: {
            Text("Couche choisie : \(layer.title)")
        }
    }

    private func wmsLayers(_ caps: WmsCapabilities) -> some View {
        let usable = caps.usableLayers
        let text = needle()
        let matches = usable.filter { layer in
            text.isEmpty || layer.title.localizedCaseInsensitiveContains(text)
                || layer.name.localizedCaseInsensitiveContains(text)
        }
        let chosen = wizard.choice
        return Group {
            Section {
                if usable.isEmpty {
                    Text("Aucune couche utilisable : ToolMap n'affiche que le Web Mercator (EPSG:3857).")
                } else {
                    TextField("Filtrer par nom ou identifiant", text: $wizard.layerFilter)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    ForEach(Array(matches.prefix(WizardServiceAddress.maxListed)), id: \.name) { item in
                        WizardLayerRow(title: item.title, id: item.name, selected: chosen?.layerId == item.name) {
                            wizard.pickWmsLayer(item)
                        }
                    }
                    if matches.count > WizardServiceAddress.maxListed {
                        Text("Autres couches (affinez le filtre) : \(matches.count - WizardServiceAddress.maxListed)")
                            .font(.footnote)
                    }
                }
            } header: {
                Text("Couches utilisables / au total : \(usable.count) / \(caps.layers.count)")
            }
            if let chosen, caps.formats.count > 1 {
                Section("Format") {
                    WizardChips(options: WizardTexts.plain(caps.formats), selected: chosen.format) { format in
                        wizard.changeWmsFormat(format)
                    }
                }
            }
        }
    }
}

private struct WizardLayerRow: View {
    let title: String
    let id: String
    let selected: Bool
    let onPick: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .lineLimit(2)
                if id != title {
                    Text(id)
                        .font(.caption2)
                        .foregroundStyle(Crete.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            Spacer()
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Crete.turquoise)
                    .accessibilityLabel("Couche choisie")
            } else {
                Button("Ajouter", action: onPick)
                    .buttonStyle(.borderless)
                    .foregroundStyle(Crete.turquoise)
            }
        }
    }
}

// MARK: Access

private struct WizardAccessStep: View {
    @Bindable var wizard: SourceWizardModel

    var body: some View {
        Section {
            Text("À remplir seulement si le serveur l'exige.")
                .font(.subheadline)
            WizardLabeledField(label: "Referer") {
                TextField("https://data.geopf.fr/", text: $wizard.draft.referer)
                    .keyboardType(.URL)
            }
            WizardLabeledField(label: "User-Agent (par défaut : celui de ToolMap)") {
                TextField("", text: $wizard.draft.userAgent)
            }
            WizardLabeledField(label: "En-têtes supplémentaires (un par ligne, « Nom: valeur »)") {
                TextEditor(text: $wizard.draft.headersText)
                    .frame(minHeight: 60)
            }
            WizardLabeledField(label: "Cookie") {
                TextField("", text: $wizard.draft.cookie)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        if !wizard.draft.params.isEmpty {
            Section("Paramètres (clés d'API)") {
                ForEach(wizard.draft.params.keys.sorted(), id: \.self) { name in
                    WizardParamField(name: name, value: paramBinding(name))
                }
            }
        }
        Section {
            Text("Téléchargements simultanés : \(wizard.draft.maxThreads)")
                .font(.subheadline)
            Slider(value: threadsBinding, in: 1 ... 16, step: 1)
                .tint(Crete.turquoise)
            Text("Renouveler les tuiles stockées après")
                .font(.subheadline)
            WizardChips(
                options: WizardTexts.delays,
                selected: wizard.draft.updateDelay.trimmingCharacters(in: .whitespaces).uppercased()
            ) { value in
                wizard.draft.updateDelay = value
            }
        }
    }

    private func paramBinding(_ name: String) -> Binding<String> {
        Binding(get: { wizard.draft.params[name] ?? "" }, set: { wizard.draft.params[name] = $0 })
    }

    private var threadsBinding: Binding<Double> {
        Binding(get: { Double(wizard.draft.maxThreads) }, set: { wizard.draft.maxThreads = Int($0.rounded()) })
    }
}

/// A field with its label above it (Android `OutlinedTextField` label).
private struct WizardLabeledField<Field: View>: View {
    let label: String
    let field: () -> Field

    init(label: String, @ViewBuilder field: @escaping () -> Field) {
        self.label = label
        self.field = field
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Crete.onSurfaceVariant)
            field()
        }
    }
}

/// An API key, hidden until « Afficher ».
private struct WizardParamField: View {
    let name: String
    @Binding var value: String
    @State private var visible = false

    var body: some View {
        WizardLabeledField(label: name) {
            HStack {
                if visible {
                    TextField(name, text: $value)
                } else {
                    SecureField(name, text: $value)
                }
                Button(visible ? "Masquer" : "Afficher") {
                    visible.toggle()
                }
                .buttonStyle(.borderless)
                .font(.footnote)
                .foregroundStyle(Crete.turquoise)
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
    }
}

// MARK: Zooms and look

private struct WizardZoomsStep: View {
    @Bindable var wizard: SourceWizardModel

    var body: some View {
        Section {
            WizardLabeledField(label: "Nom de la carte") {
                TextField("", text: $wizard.draft.name)
            }
            WizardLabeledField(label: "Zooms servis (ex. 0-17 ou 1-5,8)") {
                TextField("", text: $wizard.draft.zoomSpec)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
            }
            Text("Taille des tuiles")
                .font(.caption)
            WizardChips(
                options: SourceDraft.tileSizes.map { WizardOption(id: "\($0)", label: "\($0) px") },
                selected: "\(wizard.draft.tileSize)"
            ) { value in
                wizard.draft.tileSize = Int(value) ?? 256
            }
            Text("Type (facultatif)")
                .font(.caption)
            WizardChips(options: WizardTexts.kinds, selected: wizard.draft.type) { value in
                wizard.draft.type = wizard.draft.type == value ? nil : value
            }
        }
        Section {
            Toggle("Calque superposable (transparent) plutôt que fond de carte", isOn: $wizard.draft.isLayer)
                .tint(Crete.turquoise)
            if wizard.draft.isLayer {
                Text("Opacité initiale : \(wizard.draft.opacity) %")
                    .font(.subheadline)
                Slider(value: opacityBinding, in: 0 ... 1)
                    .tint(Crete.turquoise)
            }
            WizardLabeledField(label: "Mention de droits (copyright)") {
                TextField("", text: $wizard.draft.copyright)
            }
        }
        Section {
            Text("Lieu du test : \(placeText)")
                .font(.subheadline)
            HStack {
                if wizard.mapCenter != nil {
                    Button("Utiliser le centre de la carte") {
                        wizard.usePreviewHere()
                    }
                    .buttonStyle(.borderless)
                }
                Spacer()
                if wizard.draft.previewLocation != nil {
                    Button("Revenir à Paris") {
                        wizard.draft.previewLocation = nil
                    }
                    .buttonStyle(.borderless)
                }
            }
            .foregroundStyle(Crete.turquoise)
        }
    }

    private var opacityBinding: Binding<Double> {
        Binding(get: { Double(wizard.draft.opacity) / 100 }, set: { wizard.draft.opacity = Int($0 * 100) })
    }

    private var placeText: String {
        guard let place = wizard.draft.previewLocation else { return "Paris (par défaut)" }
        let lat = String(format: "%.4f", place.lat).replacingOccurrences(of: ".", with: ",")
        let lon = String(format: "%.4f", place.lon).replacingOccurrences(of: ".", with: ",")
        return "\(lat), \(lon) (zoom \(place.zoom))"
    }
}

// MARK: Test

private struct WizardTestStep: View {
    let wizard: SourceWizardModel

    var body: some View {
        Section {
            Text("ToolMap demande une tuile à ce serveur avec exactement les réglages choisis, pour vérifier que la carte s'affichera.")
                .font(.subheadline)
            Button(buttonTitle) {
                wizard.runProbe()
            }
            .disabled(wizard.probing || !wizard.draft.isValid)
            .foregroundStyle(Crete.turquoise)
            if wizard.probing {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
        if let result = wizard.probe {
            WizardProbeCard(result: result) { target in
                wizard.goTo(target)
            }
        }
    }

    private var buttonTitle: String {
        if wizard.probing { return "Test en cours…" }
        return wizard.probe == nil ? "Tester une tuile" : "Tester de nouveau"
    }
}

private struct WizardProbeCard: View {
    let result: WizardProbeResult
    let onFix: (WizardStep) -> Void

    var body: some View {
        Section {
            Text("Tuile demandée : zoom \(result.tile.z), colonne \(result.tile.x), ligne \(result.tile.y)")
                .font(.caption)
            if let data = result.image, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.none)
                    .frame(width: 256, height: 256)
                    .accessibilityLabel("Tuile reçue du serveur")
            }
            verdictText
            Text(statsLine)
                .font(.caption2)
                .foregroundStyle(Crete.onSurfaceVariant)
            if let detail = result.detail {
                Text("Réponse du serveur : \(detail)")
                    .font(.footnote)
            }
            if let url = result.url {
                Text("Adresse demandée : \(url)")
                    .font(.caption2)
                    .foregroundStyle(Crete.onSurfaceVariant)
                    .textSelection(.enabled)
            }
            if result.verdict != .ok {
                HStack {
                    if result.verdict == .keyOrReferer {
                        Button("Aller à l'accès") { onFix(.access) }
                            .buttonStyle(.bordered)
                    }
                    Button("Aller à l'adresse") { onFix(.address) }
                        .buttonStyle(.bordered)
                }
                .tint(Crete.turquoise)
            }
        }
    }

    /// « HTTP 200 · image/png · 12 Ko · 85 ms ».
    private var statsLine: String {
        let code = result.httpCode.map { String($0) } ?? "–"
        let type = result.contentType ?? "–"
        let size = MapsText.bytes(Int64(result.sizeBytes))
        return "HTTP \(code) · \(type) · \(size) · \(result.latencyMillis) ms"
    }

    @ViewBuilder
    private var verdictText: some View {
        if result.verdict == .certificateRejected {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.octagon.fill")
                    .foregroundStyle(Crete.danger)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Certificat du serveur refusé")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Crete.danger)
                    Text(result.verdict.text)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        } else {
            Text(result.verdict.text)
                .font(.subheadline)
                .foregroundStyle(result.verdict == .ok ? Crete.turquoise : Crete.danger)
        }
    }
}

// MARK: Save

private struct WizardSaveStep: View {
    @Bindable var wizard: SourceWizardModel
    let onExport: () -> Void

    var body: some View {
        let draft = wizard.draft
        let problems = wizard.issues.filter(\.isError)
        let notes = wizard.issues.filter { !$0.isError }
        let servers = draft.urls.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.count
        Section {
            Text("Carte : \(draft.name)")
                .font(.headline)
            Text("Zooms \(draft.zoomSpec) · tuiles \(draft.tileSize) px")
            Text("Serveurs : \(servers)")
        }
        if !problems.isEmpty {
            Section("À corriger avant d'enregistrer") {
                ForEach(problems, id: \.self) { issue in
                    Text(WizardTexts.issue(issue))
                        .font(.footnote)
                        .foregroundStyle(Crete.danger)
                }
            }
        }
        if !notes.isEmpty {
            Section("À savoir") {
                ForEach(notes, id: \.self) { issue in
                    Label(WizardTexts.issue(issue), systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
        }
        Section {
            if wizard.willPurgeStoredTiles {
                Text("L'adresse a changé : les tuiles déjà stockées pour cette carte seront effacées.")
                    .foregroundStyle(Crete.danger)
            }
            if !wizard.isEditing {
                Toggle("Ajouter la carte à la carte affichée", isOn: $wizard.addToMap)
                    .tint(Crete.turquoise)
            }
            if !draft.params.isEmpty {
                Toggle("Sans mes clés d'API (valeur vide dans le fichier)", isOn: $wizard.redactKeys)
                    .tint(Crete.turquoise)
            }
            Button("Exporter en .aqx…", action: onExport)
                .disabled(!problems.isEmpty)
                .foregroundStyle(Crete.turquoise)
        }
    }
}
