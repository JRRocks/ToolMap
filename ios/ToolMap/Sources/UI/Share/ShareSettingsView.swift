import PhotosUI
import SwiftUI
import ToolMapCore

/// « Réglages du partage » (Android SharePage.SETTINGS): in a group, the sending settings, « Ce que les autres voient »,
/// « Tracé des membres » and the coordination server; out of one, the last two (your look is your line of the main
/// page). The STUN server of the direct mode is left out: the iPhone has no direct mode.
struct ShareSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        List {
            if sharing.inGroup {
                Section {
                    ShareSendSettings()
                }
                Section {
                    NavigationLink(value: SharePage.profile) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Ce que les autres voient")
                            Text(sharing.displayName)
                                .font(.footnote)
                                .foregroundStyle(Crete.onSurfaceVariant)
                        }
                    }
                }
            }
            Section {
                ShareTrailsToggle()
            }
            Section {
                ShareServerSettings()
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Réglages du partage")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// « Réglages d'envoi »: how often the position goes out, folded under a one-line summary.
struct ShareSendSettings: View {
    @Environment(AppModel.self) private var model
    @State private var open = false

    var body: some View {
        let sharing = model.sharing
        let settings = sharing.settings
        let summary = "Toutes les \(ShareText.seconds(settings.intervalSec)) · déplacement minimal : \(ShareText.move(settings.minMoveMeters))"
        DisclosureGroup(isExpanded: $open) {
            SharePresetSlider(
                label: "Fréquence d'envoi",
                presets: ShareCadence.presetsS,
                value: settings.intervalSec,
                format: { ShareText.seconds($0) },
                onChange: { value in sharing.updateSettings { $0.intervalSec = value } }
            )
            SharePresetSlider(
                label: "Déplacement minimal",
                presets: ShareCadence.movePresetsM,
                value: settings.minMoveMeters,
                format: { ShareText.move($0) },
                onChange: { value in sharing.updateSettings { $0.minMoveMeters = value } }
            )
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Réglages d'envoi")
                    .foregroundStyle(Crete.onSurface)
                Text(summary)
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
    }
}

/// A slider on a few fixed values (Android `PresetSlider`); the value is kept when the finger lifts.
struct SharePresetSlider: View {
    let label: String
    let presets: [Int]
    let value: Int
    let format: (Int) -> String
    let onChange: (Int) -> Void
    @State private var position: Double = 0
    @State private var editing = false

    var body: some View {
        let shown = presets[SharePresetSlider.clamp(Int(position.rounded()), presets.count)]
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label)
                Spacer()
                Text(format(shown))
                    .font(.subheadline.weight(.semibold))
            }
            Slider(
                value: $position,
                in: 0 ... Double(max(presets.count - 1, 1)),
                step: 1,
                onEditingChanged: { active in
                    editing = active
                    if !active {
                        let index = SharePresetSlider.clamp(Int(position.rounded()), presets.count)
                        onChange(presets[index])
                    }
                }
            )
            .tint(Crete.turquoise)
        }
        .padding(.vertical, 4)
        .onAppear {
            position = Double(SharePresetSlider.index(of: value, in: presets))
        }
        .onChange(of: value) { _, newValue in
            if !editing {
                position = Double(SharePresetSlider.index(of: newValue, in: presets))
            }
        }
    }

    /// The first preset at or above `value`, else the last.
    static func index(of value: Int, in presets: [Int]) -> Int {
        presets.firstIndex { $0 >= value } ?? max(presets.count - 1, 0)
    }

    static func clamp(_ index: Int, _ count: Int) -> Int {
        min(max(index, 0), max(count - 1, 0))
    }
}

/// « Tracé des membres »: their path and where they are, or only where they are.
struct ShareTrailsToggle: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        let on = sharing.settings.showTrails
        Toggle(isOn: Binding(get: { on }, set: { value in sharing.updateSettings { $0.showTrails = value } })) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Tracé des membres")
                Text(on
                    ? "La carte montre le chemin parcouru par chaque membre et sa position actuelle."
                    : "La carte ne montre que la position actuelle de chaque membre.")
                    .font(.footnote)
                    .foregroundStyle(Crete.onSurfaceVariant)
            }
        }
        .tint(Crete.turquoise)
    }
}

/// « Serveur de coordination »: where new groups are made, with its address and key when the user brings their own
/// (editable out of a group only).
struct ShareServerSettings: View {
    @Environment(AppModel.self) private var model
    @State private var url = ""
    @State private var key = ""
    @State private var loaded = false
    @State private var open = false

    var body: some View {
        let sharing = model.sharing
        DisclosureGroup(isExpanded: $open) {
            Text("Les groupes créés ici passent par ce serveur Firebase. Ceux qui rejoignent un groupe utilisent le serveur de l'invitation, sans rien configurer.")
                .font(.footnote)
                .foregroundStyle(Crete.onSurfaceVariant)
            if !sharing.inGroup {
                ShareServerFields(url: $url, key: $key)
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Serveur de coordination")
                    .foregroundStyle(Crete.onSurface)
                Text(summary(sharing))
                    .font(.footnote)
                    .foregroundStyle(sharing.serverForNewGroups == nil ? Crete.danger : Crete.onSurfaceVariant)
            }
        }
        .onAppear {
            if !loaded {
                loaded = true
                url = sharing.settings.server?.dbUrl ?? ""
                key = sharing.settings.server?.apiKey ?? ""
                open = sharing.serverForNewGroups == nil && !sharing.inGroup
            }
        }
    }

    private func summary(_ sharing: ShareController) -> String {
        if sharing.settings.server != nil { return "Serveur personnalisé." }
        if sharing.serverForNewGroups != nil { return "Serveur intégré à l'application." }
        return "Serveur de coordination non configuré"
    }
}

/// The address and the key of the server, kept only when they are usable; an empty pair clears the choice.
struct ShareServerFields: View {
    @Environment(AppModel.self) private var model
    @Binding var url: String
    @Binding var key: String

    var body: some View {
        let blankUrl = url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let blankKey = key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let filled = !blankUrl || !blankKey
        let problem: RelayConfig.Problem? = filled ? RelayConfig.problem(RelayConfig.of(url, key)) : nil
        VStack(alignment: .leading, spacing: 6) {
            TextField("Adresse de la base (https://….firebasedatabase.app)", text: $url)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
                .onChange(of: url) { _, _ in save() }
            if problem == .badUrl {
                Text("Adresse invalide : elle doit commencer par https://")
                    .font(.footnote)
                    .foregroundStyle(Crete.danger)
            }
            TextField("Clé API web du projet", text: $key)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
                .onChange(of: key) { _, _ in save() }
            if problem == .badKey {
                Text("Clé API invalide")
                    .font(.footnote)
                    .foregroundStyle(Crete.danger)
            }
            if filled {
                Button("Revenir au serveur intégré") {
                    url = ""
                    key = ""
                    model.sharing.updateSettings { $0.server = nil }
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
    }

    private func save() {
        let config = RelayConfig.of(url, key)
        let blank = url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        model.sharing.updateSettings { settings in
            if blank {
                settings.server = nil
            } else if config.isUsable {
                settings.server = config
            }
        }
    }
}

/// « Ce que les autres voient » (Android `ProfileEditor`): the name the others see, the 15 tokens, the picture and the
/// colour of the icon.
struct ShareProfileView: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var loaded = false

    var body: some View {
        let sharing = model.sharing
        let settings = sharing.settings
        List {
            Section {
                HStack {
                    Spacer()
                    ShareMemberBadge(
                        name: sharing.displayName,
                        look: settings.look(),
                        size: 72,
                        picture: settings.pictureBytes()
                    )
                    Spacer()
                }
                .listRowBackground(Color.clear)
            }
            Section("Votre nom") {
                TextField("Nom du téléphone par défaut", text: $name)
                    .onChange(of: name) { _, value in
                        if loaded {
                            sharing.updateSettings { $0.name = value }
                        }
                    }
            }
            Section("Profil sur la carte des autres") {
                ShareTokenPicker()
            }
            Section {
                SharePicturePicker()
            } header: {
                Text("Votre image")
            } footer: {
                Text("L'image est recadrée en carré et réduite à 64 × 64 pixels (6 Ko au plus) avant d'être envoyée au groupe.")
            }
            Section("Couleur de l'icône") {
                ShareColorPicker()
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Ce que les autres voient")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if !loaded {
                name = settings.name
                loaded = true
            }
        }
    }
}

/// The 15 tokens: the initials and 14 silhouettes (their pictures come with the core's glyphs).
struct ShareTokenPicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        let selected = sharing.settings.icon
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)], spacing: 8) {
            ForEach(0 ..< ShareText.tokenNames.count, id: \.self) { index in
                Button {
                    sharing.updateSettings { $0.icon = index }
                } label: {
                    Text(ShareText.tokenNames[index])
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .foregroundStyle(index == selected ? Color.white : Crete.petrol)
                        .background(index == selected ? Crete.turquoise : Crete.tile, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(index == selected ? .isSelected : [])
            }
        }
        .padding(.vertical, 6)
    }
}

/// « Importer une image… », « Changer d'image… », « Retirer ».
struct SharePicturePicker: View {
    @Environment(AppModel.self) private var model
    @State private var item: PhotosPickerItem?
    @State private var failed = false

    var body: some View {
        let sharing = model.sharing
        let hasPicture = sharing.settings.pictureBytes() != nil
        PhotosPicker(selection: $item, matching: .images) {
            Label(hasPicture ? "Changer d'image…" : "Importer une image…", systemImage: "photo")
        }
        .onChange(of: item) { _, picked in
            guard let picked else { return }
            Task {
                let data = try? await picked.loadTransferable(type: Data.self)
                let bytes: [UInt8]? = await Task.detached {
                    data.flatMap { SharePicture.make($0) }
                }.value
                item = nil
                if let bytes {
                    sharing.updateSettings { $0 = $0.withPicture(bytes) }
                } else {
                    failed = true
                }
            }
        }
        if hasPicture {
            Button("Retirer", role: .destructive) {
                sharing.updateSettings { $0 = $0.withPicture(nil) }
            }
        }
        if failed {
            Text("Cette image n'a pas pu être lue. Essayez une autre photo.")
                .font(.footnote)
                .foregroundStyle(Crete.danger)
        }
    }
}

/// The 12 colours of the palette and any other one (the colour wheel of Android becomes the iOS colour picker).
struct ShareColorPicker: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let sharing = model.sharing
        let current = sharing.settings.colorArgb()
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 40), spacing: 10)], spacing: 10) {
            ForEach(0 ..< MemberPalette.colors.count, id: \.self) { index in
                let argb = MemberPalette.colors[index]
                Button {
                    sharing.updateSettings { $0.color = argb }
                } label: {
                    Circle()
                        .fill(ShareColors.color(argb))
                        .frame(width: 36, height: 36)
                        .overlay {
                            if (argb & 0xFF_FFFF) == (current & 0xFF_FFFF) {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(Color.white)
                            }
                        }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
        ColorPicker(
            "Autre couleur…",
            selection: Binding(
                get: { ShareColors.color(current) },
                set: { color in
                    let argb = ShareColors.argb(color)
                    sharing.updateSettings { $0.color = argb }
                }
            ),
            supportsOpacity: false
        )
    }
}
