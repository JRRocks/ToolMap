import SwiftUI
import ToolMapCore

/// The part of « Prise en main » a « ? » of a panel opens (Android `HelpSection`, R5, F11): the page scrolls to it and
/// lights it up for a moment. The « ? » of the main menu opens the page at its top (`nil`).
enum AboutHelpSection: String, CaseIterable {
    case maps, tracks, positioning, tools

    /// The section a `Screen.about` names: « maps » or « cartes », « tracks » or « traces », « position »,
    /// « positioning » or « positionnement », « tools » or « outils ».
    static func named(_ name: String?) -> AboutHelpSection? {
        guard let key = name?.lowercased() else { return nil }
        switch key {
        case "maps", "cartes", "layers": return .maps
        case "tracks", "traces", "waypoints": return .tracks
        case "position", "positioning", "positionnement": return .positioning
        case "tools", "outils", "share", "backup": return .tools
        default: return nil
        }
    }
}

/// À propos et aide (Android ABOUT, P29.10): ToolMap's tile, its name and version, an introduction; « Prise en main » in
/// blocks with their icons (Cartes et calques, Traces et waypoints, Positionnement, Outils, partage et sauvegarde), the
/// block of a panel's « ? » lit for a moment; privacy and limits in blocks of text; data and licences as rows.
struct AboutScreen: View {
    @Environment(AppModel.self) private var model
    let section: String?
    @State private var lit = false

    var body: some View {
        let target = AboutHelpSection.named(section)
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    AboutHeader(version: model.version)
                    AboutStart(target: target, lit: lit)
                    AboutTextSection(title: "Vie privée et données", paragraphs: AboutTexts.privacy)
                    AboutTextSection(title: "Limites connues", paragraphs: AboutTexts.limits)
                    AboutRowsSection(
                        title: "Cartes et données",
                        note: "Les cartes appartiennent à leurs fournisseurs : vérifiez leurs conditions d'utilisation, notamment pour le téléchargement hors ligne et l'impression.",
                        rows: AboutTexts.dataCredits
                    )
                    AboutRowsSection(
                        title: "Licences des logiciels utilisés",
                        note: "ToolMap est construit sur ces logiciels libres :",
                        rows: AboutTexts.thirdParty
                    )
                }
                .listStyle(.insetGrouped)
                .task {
                    guard let target else { return }
                    lit = true
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    withAnimation { proxy.scrollTo(target, anchor: .top) }
                    try? await Task.sleep(nanoseconds: 1_600_000_000)
                    withAnimation(.easeOut(duration: 0.9)) { lit = false }
                }
            }
            .navigationTitle("Aide et à propos")
            .navigationBarTitleDisplayMode(.inline)
            .settingsNavigationBar()
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { ScreenCloseButton() }
            }
        }
        .tint(Crete.turquoise)
    }
}

/// ToolMap's tile, its name, its version, then the introduction.
private struct AboutHeader: View {
    let version: String

    var body: some View {
        Section {
            HStack(spacing: 16) {
                Image(systemName: "mountain.2.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(Color.white)
                    .frame(width: 48, height: 48)
                    .background(Crete.petrol, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(AppInfo.name)
                        .font(.title2.weight(.semibold))
                    Text("Version \(version) pour iPhone")
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
            }
            .padding(.vertical, 4)
            Text(AboutTexts.intro)
                .font(.subheadline)
        }
    }
}

/// « Prise en main »: one block per panel of the bar, with its icon; the one asked for is tinted for a moment.
private struct AboutStart: View {
    let target: AboutHelpSection?
    let lit: Bool

    var body: some View {
        Section {
            ForEach(AboutHelpSection.allCases, id: \.self) { block in
                AboutHelpBlock(
                    icon: AboutTexts.icon(block),
                    title: AboutTexts.title(block),
                    paragraphs: AboutTexts.help(block)
                )
                .id(block)
                .listRowBackground(block == target && lit ? Crete.pill.opacity(0.35) : Color(uiColor: .secondarySystemGroupedBackground))
            }
        } header: {
            Text("Prise en main")
        }
    }
}

private struct AboutHelpBlock: View {
    let icon: String
    let title: String
    let paragraphs: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(Crete.petrol)
                    .frame(width: 26)
                Text(title)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
            }
            ForEach(paragraphs, id: \.self) { paragraph in
                Text(paragraph)
                    .font(.subheadline)
            }
        }
        .padding(.vertical, 6)
    }
}

/// Paragraphs in one block, under a title.
private struct AboutTextSection: View {
    let title: String
    let paragraphs: [String]

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(paragraphs, id: \.self) { paragraph in
                    Text(paragraph)
                        .font(.subheadline)
                }
            }
            .padding(.vertical, 4)
        } header: {
            Text(title)
        }
    }
}

/// Names with a grey line under each (the credits, the licences), after a short note.
private struct AboutRowsSection: View {
    let title: String
    let note: String
    let rows: [AboutRow]

    var body: some View {
        Section {
            ForEach(rows) { row in
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name)
                    Text(row.detail)
                        .font(.footnote)
                        .foregroundStyle(Crete.onSurfaceVariant)
                }
                .padding(.vertical, 2)
            }
        } header: {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                Text(note)
                    .textCase(nil)
                    .font(.footnote)
            }
        }
    }
}

/// A row of the credits or of the licences.
struct AboutRow: Identifiable {
    let name: String
    let detail: String
    var id: String { name }
}

/// The texts of the help (Android strings `about_*`, `help_position_*`, `maps_help_text`), adapted to the iPhone where
/// Android names its own buttons or limits.
enum AboutTexts {
    static let intro = "ToolMap affiche vos cartes, même hors ligne, enregistre vos traces et partage votre position avec vos proches, sans compte et sans serveur à vous."

    static func title(_ block: AboutHelpSection) -> String {
        switch block {
        case .maps: "Cartes et calques"
        case .tracks: "Traces et waypoints"
        case .positioning: "Positionnement"
        case .tools: "Outils, partage et sauvegarde"
        }
    }

    static func icon(_ block: AboutHelpSection) -> String {
        switch block {
        case .maps: "map"
        case .tracks: "flag"
        case .positioning: "location"
        case .tools: "ruler"
        }
    }

    static func help(_ block: AboutHelpSection) -> [String] {
        switch block {
        case .maps: maps
        case .tracks: tracks
        case .positioning: positioning
        case .tools: tools
        }
    }

    static let maps: [String] = [
        "Cartes : « Cartes » puis « Cartes disponibles » montre le catalogue ; « Ajouter une carte » puis « Importer une carte (.aqx)… » reprend un fichier d'AlpineQuest, depuis Fichiers, Mail ou AirDrop. Les calques se superposent, avec une opacité et une plage de zoom par calque.",
        "Le panneau montre la pile des cartes affichées, du dessus vers le dessous. Une carte : l'œil la montre ou la cache ; toucher la ligne la déplie : opacité, ordre, conditions (plage de zoom et bande d'altitude), couleurs.",
        "Favoris : touchez une puce pour retrouver une carte ou un ensemble de calques avec ses réglages ; l'icône du bandeau enregistre l'affichage actuel.",
        "Hors ligne : dans « Cartes », la tuile « Enregistrer une zone » enregistre dans le téléphone les tuiles des cartes cochées ; l'interrupteur « Hors ligne » n'utilise plus que ce qui est enregistré. « Stockage » gère l'espace occupé et importe ou exporte des fichiers .mbtiles.",
    ]

    static let tracks: [String] = [
        "Traces : « Traces et waypoints » importe et exporte du GPX ou du KML (Fichiers, Mail, AirDrop, « Ouvrir avec » ToolMap) ; « Ma position » puis « Enregistreur de trace » enregistre avec le GPS écran éteint ; « Dessiner une trace » ou « Dessiner un itinéraire » trace à la main.",
    ]

    static let positioning: [String] = [
        "Position en temps réel allume le GPS ; le cercle montre la précision.",
        "La carte suit : Libre, Position, Position et cap.",
        "Enregistreur de trace continue écran éteint, tant que ToolMap a le droit d'utiliser la position « Toujours » (Réglages de l'iPhone ▸ ToolMap ▸ Position).",
    ]

    static let tools: [String] = [
        "Outils : recherche de lieux, mesure de distance et de surface, grille de coordonnées (DD, DMS, UTM, MGRS, Lambert 93), alertes de proximité et suivi de trace avec alerte d'écart, altimètre barométrique, impression à l'échelle en PDF.",
        "Partage de position : un membre crée un groupe et montre un code QR ou envoie un lien, les autres le scannent ou l'ouvrent pour rejoindre le groupe. Un serveur de coordination (Firebase) fait passer les positions, chiffrées : la clé du groupe reste dans les téléphones.",
        "Sauvegarde : Réglages, « Stockage et sauvegarde » puis « Sauvegarde et restauration… » écrit un fichier zip de vos traces, réglages et cartes importées (les cartes hors ligne en option), à enregistrer dans Fichiers. Le même fichier se restaure sur Android.",
    ]

    static let privacy: [String] = [
        "ToolMap n'a pas de compte, n'envoie aucune statistique d'usage ni aucun rapport de plantage.",
        "Pour afficher une carte en ligne, l'application demande les tuiles aux serveurs de cette carte, avec l'adresse IP du téléphone et les en-têtes que sa définition prévoit (Referer, User-Agent, clé d'API). Les cartes en http ne sont pas chiffrées.",
        "La recherche de lieux n'envoie le texte tapé aux services (Géoplateforme IGN, Nominatim) qu'à la validation, et jamais en mode hors ligne.",
        "Le calage de l'altimètre peut demander des tuiles d'altitude (Terrarium) autour de votre position.",
        "Le partage passe par un serveur de coordination (Firebase) qui ne voit que des positions chiffrées : la clé du groupe reste dans les téléphones. Ni serveur de ToolMap, ni compte.",
        "Le fichier de sauvegarde n'est pas chiffré et contient vos traces : gardez-le en lieu sûr. La clé du partage de position n'y figure jamais.",
    ]

    static let limits: [String] = [
        "Le partage ne survit pas à l'arrêt de l'application par le système : il faut le rallumer.",
        "Position, enregistrement, alertes et partage demandent l'autorisation de localisation ; pour qu'ils continuent écran éteint, choisissez « Toujours » dans Réglages de l'iPhone ▸ ToolMap ▸ Position.",
        "Altitude : le relief des tuiles Terrarium peut se tromper de plusieurs dizaines de mètres sur une falaise.",
        "La carte n'affiche pas de texte (noms de lieux, de sommets) : les cartes raster le portent déjà.",
    ]

    static let dataCredits: [AboutRow] = [
        AboutRow(name: "OpenStreetMap", detail: "© les contributeurs d'OpenStreetMap, licence ODbL"),
        AboutRow(name: "OpenTopoMap", detail: "© OpenStreetMap, SRTM ; style © OpenTopoMap (CC-BY-SA)"),
        AboutRow(name: "IGN Géoplateforme", detail: "© IGN, selon les conditions d'utilisation de la Géoplateforme"),
        AboutRow(name: "Altitudes (tuiles Terrarium)", detail: "Mapzen, AWS Open Data Terrain Tiles"),
        AboutRow(name: "Recherche de lieux", detail: "Nominatim (OpenStreetMap) et Géoplateforme IGN"),
    ]

    static let thirdParty: [AboutRow] = [
        AboutRow(name: "MapLibre Native", detail: "BSD 2-Clause"),
        AboutRow(
            name: "Game-icons.net (jetons des membres du partage : Lorc, Delapouite et autres auteurs)",
            detail: "CC BY 3.0"
        ),
        AboutRow(name: "Maki (Mapbox) et Temaki (Rapid Editor) : icônes des waypoints", detail: "CC0 1.0 (domaine public)"),
        AboutRow(name: "Material Design Icons (Pictogrammes) : icônes des waypoints et des activités", detail: "Apache 2.0"),
    ]
}
