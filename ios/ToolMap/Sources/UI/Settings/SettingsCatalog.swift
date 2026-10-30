import Foundation
import ToolMapCore
import UIKit

// The settings of the iPhone app (Android settings/SettingsEntries.kt and SettingsLabels.kt, P29.9 « Loupe »): the nine
// categories, and each setting with its icon, its name, its explanation, its options and how it reads and writes
// `AppSettings`. The same list builds the pages of the categories and the results of the search. The settings that
// only make sense on Android (navigation bar, wallpaper colours, over the lock screen, confirm the exit, SD card,
// « Ouvrir les positions avec ») are left out, and so are those whose screen is not on iPhone yet (multi-zoom maps).

/// The nine categories, in the order of the list, with their short name for the tabs.
enum SettingsCategory: String, CaseIterable, Identifiable, Hashable {
    case units, display, canvas, overlay, placemarks, positioning, recorder, alerts, data

    var id: String { rawValue }

    var title: String {
        switch self {
        case .units: "Unités et coordonnées"
        case .display: "Affichage"
        case .canvas: "Carte"
        case .overlay: "Grille et informations"
        case .placemarks: "Repères et traces"
        case .positioning: "Position et cap"
        case .recorder: "Enregistreur de trace"
        case .alerts: "Alertes"
        case .data: "Stockage et sauvegarde"
        }
    }

    var tab: String {
        switch self {
        case .units: "Unités"
        case .display: "Affichage"
        case .canvas: "Carte"
        case .overlay: "Grille"
        case .placemarks: "Repères"
        case .positioning: "Position"
        case .recorder: "Enregistreur"
        case .alerts: "Alertes"
        case .data: "Stockage"
        }
    }

    /// The icon of the category, the only one of the settings in the accent colour.
    var icon: String {
        switch self {
        case .units: "ruler"
        case .display: "circle.lefthalf.filled"
        case .canvas: "square.3.layers.3d"
        case .overlay: "grid"
        case .placemarks: "point.topleft.down.to.point.bottomright.curvepath"
        case .positioning: "location"
        case .recorder: "figure.walk"
        case .alerts: "bell.badge"
        case .data: "externaldrive"
        }
    }
}

/// One option of a choice: its short name (a segment), its full name (the second line, the list of a choice), what the
/// row says when it is chosen, and an optional detail under its name in the list.
struct SettingsOption: Identifiable, Hashable {
    let id: String
    let short: String
    let full: String
    var value: String?
    var detail: String?

    init(_ id: String, _ short: String, full: String? = nil, value: String? = nil, detail: String? = nil) {
        self.id = id
        self.short = short
        self.full = full ?? short
        self.value = value
        self.detail = detail
    }
}

/// How a setting is changed.
enum SettingsEntryKind {
    /// Yes or no.
    case toggle(get: (AppSettings) -> Bool, set: (inout AppSettings, Bool) -> Void)
    /// Two or three options on a segmented control, the full name of the chosen one on the second line.
    case segmented(options: [SettingsOption], get: (AppSettings) -> String, set: (inout AppSettings, String) -> Void)
    /// A list of options on a page of its own (Android: a dialog), with an optional question above them.
    case choice(
        options: [SettingsOption],
        question: String?,
        get: (AppSettings) -> String,
        set: (inout AppSettings, String) -> Void
    )
    /// A value on a slider in the row, written when the finger leaves it.
    case slider(
        range: ClosedRange<Int>,
        step: Int,
        label: (Int, AppSettings) -> String,
        get: (AppSettings) -> Int,
        set: (inout AppSettings, Int) -> Void
    )
    /// A colour, as an ARGB integer (Android `Int`).
    case color(get: (AppSettings) -> Int32, set: (inout AppSettings, Int32) -> Void)
    /// « Sauvegarde et restauration… ».
    case backupLink
}

/// One setting, the same on the page of its category and in the results of a search.
struct SettingsEntry: Identifiable {
    let id: String
    let category: SettingsCategory
    let icon: String
    let title: String
    var hint: String?
    /// The name of the block of settings it belongs to on its page (« Aller vers un point »).
    var group: String?
    /// Shown only while this holds (the radius of an alert only while the alert is on).
    var visible: (AppSettings) -> Bool = { _ in true }
    let kind: SettingsEntryKind

    /// What the search reads: the name first, then the explanation and the names of the options.
    var words: [String] {
        var out: [String] = [title]
        switch kind {
        case .toggle, .slider, .color:
            if let hint { out.append(hint) }
        case let .segmented(options, _, _):
            out.append(contentsOf: options.map(\.short))
            out.append(contentsOf: options.map(\.full))
        case let .choice(options, question, _, _):
            if let question { out.append(question) }
            out.append(contentsOf: options.map(\.full))
        case .backupLink:
            break
        }
        return out
    }

    /// The second line of the row: the explanation, or the option chosen.
    func subtitle(_ settings: AppSettings) -> String? {
        switch kind {
        case .toggle, .slider, .color:
            return hint
        case let .segmented(options, read, _):
            let chosen = read(settings)
            return options.first { $0.id == chosen }?.full
        case let .choice(options, _, read, _):
            let chosen = read(settings)
            guard let option = options.first(where: { $0.id == chosen }) else { return nil }
            return option.value ?? option.full
        case .backupLink:
            return nil
        }
    }
}

/// The settings, category by category, in the order of their pages.
enum SettingsCatalog {
    /// A position the examples of the coordinate formats are written for (the summit of the Grand Veymont).
    private static let exampleLat = 44.8386
    private static let exampleLon = 5.5108
    private static let french = Locale(identifier: "fr_FR")

    static func items(_ category: SettingsCategory) -> [SettingsEntry] {
        switch category {
        case .units: units()
        case .display: display()
        case .canvas: canvas()
        case .overlay: overlay()
        case .placemarks: placemarks()
        case .positioning: positioning()
        case .recorder: recorder()
        case .alerts: alerts()
        case .data: data()
        }
    }

    /// Every setting, category by category.
    static func all() -> [SettingsEntry] {
        SettingsCategory.allCases.flatMap { items($0) }
    }

    // MARK: - Labels

    static func coordinateLabel(_ system: CoordinateSystem) -> String {
        switch system {
        case .dd: "Degrés décimaux"
        case .ddm: "Degrés et minutes décimales"
        case .dms: "Degrés, minutes, secondes"
        case .utm: "UTM"
        case .mgrs: "MGRS"
        case .lambert93: "Lambert 93 (France)"
        }
    }

    /// A position in the coordinate format, as the example of the settings (« MGRS 31T GL 27960 49623 »).
    static func example(_ system: CoordinateSystem) -> String {
        CoordinateFormat.format(system, exampleLat, exampleLon).text
    }

    static func distance(_ meters: Int, _ settings: AppSettings) -> String {
        TrackFormat.distance(Double(meters), locale: french, units: settings.units)
    }

    static func profileLabel(_ profile: String) -> String {
        switch profile {
        case RoutingProfiles.hiking: "Randonnée"
        case RoutingProfiles.bike: "Vélo"
        case RoutingProfiles.roadBike: "Vélo route"
        case RoutingProfiles.mtb: "VTT"
        case RoutingProfiles.car: "Voiture"
        case RoutingProfiles.shortest: "Plus court"
        default: profile
        }
    }

    /// "1,5", "2", "3" in French.
    private static func factor(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = french
        formatter.maximumFractionDigits = 1
        formatter.minimumFractionDigits = 0
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    // MARK: - Units and coordinates

    private static func units() -> [SettingsEntry] {
        let systems = CoordinateSystem.allCases.map { system in
            SettingsOption(
                system.rawValue,
                coordinateLabel(system),
                value: "\(coordinateLabel(system)) · \(example(system))",
                detail: example(system)
            )
        }
        var secondary = [SettingsOption("", "Aucun")]
        for system in CoordinateSystem.allCases {
            secondary.append(SettingsOption(system.rawValue, coordinateLabel(system), detail: example(system)))
        }
        return [
            SettingsEntry(
                id: "coordinates", category: .units, icon: "mappin.and.ellipse", title: "Format de position",
                kind: .choice(
                    options: systems, question: nil,
                    get: { $0.coordinates.rawValue },
                    set: { s, v in s.coordinates = CoordinateSystem(rawValue: v) ?? s.coordinates }
                )
            ),
            SettingsEntry(
                id: "secondaryCoordinates", category: .units, icon: "mappin.circle", title: "Format de position secondaire",
                kind: .choice(
                    options: secondary, question: nil,
                    get: { $0.secondaryCoordinates?.rawValue ?? "" },
                    set: { s, v in s.secondaryCoordinates = CoordinateSystem(rawValue: v) }
                )
            ),
            SettingsEntry(
                id: "units", category: .units, icon: "ruler", title: "Unité de distance",
                kind: .segmented(
                    options: [
                        SettingsOption(UnitSystem.metric.rawValue, "Métrique", full: "Métrique (m, km)"),
                        SettingsOption(UnitSystem.imperial.rawValue, "Impériale", full: "Impériale (ft, mi)"),
                    ],
                    get: { $0.units.rawValue },
                    set: { s, v in s.units = UnitSystem(rawValue: v) ?? s.units }
                )
            ),
            SettingsEntry(
                id: "areaUnits", category: .units, icon: "square.dashed", title: "Unité de surface",
                kind: .segmented(
                    options: [
                        SettingsOption(UnitSystem.metric.rawValue, "Métrique", full: "Métrique (m², km², ha)"),
                        SettingsOption(UnitSystem.imperial.rawValue, "Impériale", full: "Impériale (ft², ac, mi²)"),
                    ],
                    get: { $0.areaUnits.rawValue },
                    set: { s, v in s.areaUnits = UnitSystem(rawValue: v) ?? s.areaUnits }
                )
            ),
            SettingsEntry(
                id: "bearing", category: .units, icon: "safari", title: "Unité de cap",
                kind: .segmented(
                    options: [
                        SettingsOption(BearingUnit.degrees.rawValue, "Degrés", full: "Degrés (360° par tour)"),
                        SettingsOption(BearingUnit.mils.rawValue, "Mils OTAN", full: "Mils OTAN (6 400 par tour)"),
                        SettingsOption(BearingUnit.gradians.rawValue, "Grades", full: "Grades (400 par tour)"),
                    ],
                    get: { $0.bearing.rawValue },
                    set: { s, v in s.bearing = BearingUnit(rawValue: v) ?? s.bearing }
                )
            ),
            SettingsEntry(
                id: "slope", category: .units, icon: "triangle", title: "Unité de pente",
                kind: .segmented(
                    options: [
                        SettingsOption(SlopeUnit.percent.rawValue, "Pourcents", full: "Pourcents (%)"),
                        SettingsOption(SlopeUnit.degrees.rawValue, "Degrés", full: "Degrés (°)"),
                    ],
                    get: { $0.slope.rawValue },
                    set: { s, v in s.slope = SlopeUnit(rawValue: v) ?? s.slope }
                )
            ),
            SettingsEntry(
                id: "pressure", category: .units, icon: "gauge.with.dots.needle.33percent", title: "Unité de pression",
                kind: .segmented(
                    options: [
                        SettingsOption(PressureUnit.hpa.rawValue, "hPa", full: "Hectopascals / millibars (hPa)"),
                        SettingsOption(PressureUnit.mmhg.rawValue, "mmHg", full: "Millimètres de mercure (mmHg)"),
                        SettingsOption(PressureUnit.inhg.rawValue, "inHg", full: "Pouces de mercure (inHg)"),
                    ],
                    get: { $0.pressure.rawValue },
                    set: { s, v in s.pressure = PressureUnit(rawValue: v) ?? s.pressure }
                )
            ),
            SettingsEntry(
                id: "north", category: .units, icon: "location.north.line", title: "Nord de référence",
                kind: .segmented(
                    options: [
                        SettingsOption(NorthReference.trueNorth.rawValue, "Géographique", full: "Nord géographique (vrai)"),
                        SettingsOption(NorthReference.magnetic.rawValue, "Magnétique", full: "Nord magnétique"),
                        SettingsOption(NorthReference.grid.rawValue, "Grille", full: "Nord de la grille (UTM)"),
                    ],
                    get: { $0.north.rawValue },
                    set: { s, v in s.north = NorthReference(rawValue: v) ?? s.north }
                )
            ),
        ]
    }

    // MARK: - Display

    private static func display() -> [SettingsEntry] {
        [
            SettingsEntry(
                id: "theme", category: .display, icon: "circle.lefthalf.filled", title: "Thème",
                kind: .segmented(
                    options: [
                        SettingsOption(ThemeMode.system.rawValue, "Système", full: "Celui du système"),
                        SettingsOption(ThemeMode.light.rawValue, "Clair"),
                        SettingsOption(ThemeMode.dark.rawValue, "Sombre"),
                    ],
                    get: { $0.theme.rawValue },
                    set: { s, v in s.theme = ThemeMode(rawValue: v) ?? s.theme }
                )
            ),
            // « Mode nuit de la carte » (F14, ADR-067): how much the night mode darkens; its switch is in the menu.
            SettingsEntry(
                id: "nightPercent", category: .display, icon: "moon", title: "Mode nuit de la carte",
                hint: "Assombrit les cartes, pas les traces ni les menus.",
                kind: .slider(
                    range: MapDisplaySettings.minNightPercent ... MapDisplaySettings.maxNightPercent,
                    step: MapDisplaySettings.nightStepPercent,
                    label: { value, _ in "Intensité : \(value) %" },
                    get: { $0.maps.nightPercent },
                    set: { s, v in s.maps.nightPercent = v }
                )
            ),
            SettingsEntry(
                id: "gloves", category: .display, icon: "hand.raised", title: "Mode gants",
                hint: "Boutons plus grands et texte agrandi de 20 %",
                kind: .toggle(get: { $0.gloves }, set: { s, v in s.gloves = v })
            ),
            SettingsEntry(
                id: "menusOpaque", category: .display, icon: "menubar.rectangle", title: "Fond opaque des menus",
                hint: "Les menus ne laissent pas voir la carte.",
                kind: .toggle(get: { $0.canvas.menusOpaque }, set: { s, v in s.canvas.menusOpaque = v })
            ),
            SettingsEntry(
                id: "hideStatusBar", category: .display, icon: "rectangle.topthird.inset.filled",
                title: "Masquer la barre d'état",
                kind: .toggle(get: { $0.hideStatusBar }, set: { s, v in s.hideStatusBar = v })
            ),
            SettingsEntry(
                id: "keepScreenOn", category: .display, icon: "sun.max", title: "Garder l'écran allumé sur la carte",
                kind: .toggle(get: { $0.keepScreenOn }, set: { s, v in s.keepScreenOn = v })
            ),
        ]
    }

    // MARK: - Map

    private static func canvas() -> [SettingsEntry] {
        [
            sharpnessItem(),
            // The zooms a map borrows from its neighbours (AlpineQuest's `default_overscaling_levels`, ADR-031).
            SettingsEntry(
                id: "overscaling", category: .canvas, icon: "square.3.layers.3d", title: "Zooms manquants",
                hint: "Au-delà de ses zooms, une carte réduit ou agrandit ses tuiles au lieu de laisser du vide. À 0, rien n'est complété.",
                kind: .slider(
                    range: 0 ... ScalingPolicy.maxLevels,
                    step: 1,
                    label: { levels, _ in
                        if levels == 0 { return "Non complétés" }
                        return levels == 1 ? "Complétés sur 1 niveau" : "Complétés sur \(levels) niveaux"
                    },
                    get: { $0.maps.overscalingLevels },
                    set: { s, v in s.maps.overscalingLevels = v }
                )
            ),
            SettingsEntry(
                id: "zoomButtons", category: .canvas, icon: "plus.magnifyingglass", title: "Afficher les boutons + et −",
                kind: .toggle(get: { $0.canvas.zoomButtons }, set: { s, v in s.canvas.zoomButtons = v })
            ),
            SettingsEntry(
                id: "menusLeftHanded", category: .canvas, icon: "align.horizontal.left", title: "Boutons + et − à gauche",
                hint: "Pour un gaucher : les boutons de zoom passent à gauche.",
                kind: .toggle(get: { $0.canvas.menusLeftHanded }, set: { s, v in s.canvas.menusLeftHanded = v })
            ),
            SettingsEntry(
                id: "zoomStep", category: .canvas, icon: "plusminus", title: "Pas du zoom",
                kind: .segmented(
                    options: [
                        SettingsOption(ZoomStep.half.rawValue, "½ niveau", full: "Un demi-niveau"),
                        SettingsOption(ZoomStep.one.rawValue, "1 niveau", full: "Un niveau"),
                        SettingsOption(ZoomStep.two.rawValue, "2 niveaux", full: "Deux niveaux"),
                    ],
                    get: { $0.canvas.zoomStep.rawValue },
                    set: { s, v in s.canvas.zoomStep = ZoomStep(rawValue: v) ?? s.canvas.zoomStep }
                )
            ),
            SettingsEntry(
                id: "longPress", category: .canvas, icon: "hand.tap", title: "Appui long sur la carte",
                kind: .choice(
                    options: [
                        SettingsOption(LongPressAction.none.rawValue, "Aucune action"),
                        SettingsOption(LongPressAction.waypoint.rawValue, "Créer un repère ici"),
                        SettingsOption(LongPressAction.measure.rawValue, "Mesurer depuis ce point"),
                        SettingsOption(LongPressAction.target.rawValue, "Définir comme cible"),
                    ],
                    question: nil,
                    get: { $0.canvas.longPress.rawValue },
                    set: { s, v in s.canvas.longPress = LongPressAction(rawValue: v) ?? s.canvas.longPress }
                )
            ),
            SettingsEntry(
                id: "pinchZoom", category: .canvas, icon: "arrow.up.left.and.arrow.down.right", title: "Zoom au pincement",
                kind: .toggle(get: { $0.canvas.pinchZoom }, set: { s, v in s.canvas.pinchZoom = v })
            ),
            SettingsEntry(
                id: "manualRotation", category: .canvas, icon: "arrow.triangle.2.circlepath",
                title: "Rotation manuelle de la carte",
                hint: "Tourner la carte en tournant deux doigts.",
                kind: .toggle(get: { $0.canvas.manualRotation }, set: { s, v in s.canvas.manualRotation = v })
            ),
            SettingsEntry(
                id: "perspective", category: .canvas, icon: "perspective", title: "Vue en perspective",
                hint: "Incliner la carte en glissant deux doigts vers le bas.",
                kind: .toggle(get: { $0.canvas.perspective }, set: { s, v in s.canvas.perspective = v })
            ),
            SettingsEntry(
                id: "explorerAuto", category: .canvas, icon: "figure.hiking", title: "Mode explorateur automatique",
                hint: "Quand on incline la carte : perspective, orientée vers le cap, qui suit la position.",
                kind: .toggle(get: { $0.canvas.explorerAuto }, set: { s, v in s.canvas.explorerAuto = v })
            ),
        ]
    }

    /// « Résolution d'affichage de la carte » (ADR-032): the choices the screen of the iPhone allows.
    private static func sharpnessItem() -> SettingsEntry {
        let density = Double(UIScreen.main.scale)
        let choices = TileSharpness.choices(density)
        var options: [SettingsOption] = []
        for choice in choices {
            let id = String(choice)
            if choice == TileSharpness.sharp {
                options.append(
                    SettingsOption(
                        id, "Nette, comme AlpineQuest",
                        detail: "Un pixel des tuiles sur un pixel de l'écran : textes de la carte petits, davantage de tuiles à charger."
                    )
                )
            } else if choice == TileSharpness.androidSize {
                options.append(
                    SettingsOption(
                        id, "Taille iPhone ×\(factor(density))",
                        detail: "Un pixel des tuiles par point de l'écran : textes les plus gros, image la moins nette."
                    )
                )
            } else {
                options.append(
                    SettingsOption(
                        id, "Agrandie ×\(factor(choice))",
                        detail: "Un pixel des tuiles sur \(factor(choice)) pixels de l'écran : textes plus gros, moins de tuiles, image moins nette."
                    )
                )
            }
        }
        return SettingsEntry(
            id: "sharpness", category: .canvas, icon: "aspectratio", title: "Résolution d'affichage de la carte",
            kind: .choice(
                options: options, question: nil,
                get: { settings in
                    // A value saved on another phone may not be offered here: the choice that draws the same is chosen.
                    let saved = settings.maps.tileMagnification
                    if choices.contains(saved) { return String(saved) }
                    let mapping = settings.maps.zoomMapping(density)
                    let same = choices.first { ZoomMapping.forScreen(density, $0) == mapping }
                    return String(same ?? TileSharpness.sharp)
                },
                set: { s, v in
                    if let value = Double(v) { s.maps.tileMagnification = value }
                }
            )
        )
    }

    // MARK: - Grid and information boxes

    private static func overlay() -> [SettingsEntry] {
        let grids: [(GridKind, String)] = [
            (.none, "Aucune"), (.wgs84, "Latitude et longitude"), (.utm, "UTM"), (.lambert93, "Lambert 93 (France)"),
            (.mgrs, "MGRS"),
        ]
        return [
            SettingsEntry(
                id: "screenGrid", category: .overlay, icon: "grid", title: "Grille sur la carte",
                kind: .choice(
                    options: grids.map { SettingsOption($0.0.rawValue, $0.1) },
                    question: nil,
                    get: { $0.screenGrid == .wgs84Decimal ? GridKind.wgs84.rawValue : $0.screenGrid.rawValue },
                    set: { s, v in s.screenGrid = GridKind(rawValue: v) ?? s.screenGrid }
                )
            ),
            SettingsEntry(
                id: "gridDensity", category: .overlay, icon: "squareshape.split.3x3", title: "Densité des grilles",
                kind: .segmented(
                    options: [
                        SettingsOption(GridDensity.fine.rawValue, "Serrée", full: "Serrée (moitié de l'espacement)"),
                        SettingsOption(GridDensity.normal.rawValue, "Normale"),
                        SettingsOption(GridDensity.coarse.rawValue, "Large", full: "Large (double)"),
                    ],
                    get: { $0.canvas.gridDensity.rawValue },
                    set: { s, v in s.canvas.gridDensity = GridDensity(rawValue: v) ?? s.canvas.gridDensity }
                )
            ),
            SettingsEntry(
                id: "gridStyle", category: .overlay, icon: "line.3.horizontal", title: "Traits des grilles",
                kind: .segmented(
                    options: [
                        SettingsOption(GridStyle.solid.rawValue, "Pleins", full: "Traits pleins"),
                        SettingsOption(GridStyle.dashed.rawValue, "Pointillés"),
                    ],
                    get: { $0.canvas.gridStyle.rawValue },
                    set: { s, v in s.canvas.gridStyle = GridStyle(rawValue: v) ?? s.canvas.gridStyle }
                )
            ),
            SettingsEntry(
                id: "infoBoxSize", category: .overlay, icon: "textformat.size", title: "Taille des boîtes",
                kind: .segmented(
                    options: [
                        SettingsOption(InfoBoxSize.small.rawValue, "Petite"),
                        SettingsOption(InfoBoxSize.normal.rawValue, "Normale"),
                        SettingsOption(InfoBoxSize.large.rawValue, "Grande"),
                    ],
                    get: { $0.canvas.infoBoxSize.rawValue },
                    set: { s, v in s.canvas.infoBoxSize = InfoBoxSize(rawValue: v) ?? s.canvas.infoBoxSize }
                )
            ),
            SettingsEntry(
                id: "infoBoxBackground", category: .overlay, icon: "drop.halffull", title: "Fond des boîtes",
                kind: .segmented(
                    options: [
                        SettingsOption(InfoBoxBackground.transparent.rawValue, "Transparent"),
                        SettingsOption(InfoBoxBackground.translucent.rawValue, "Translucide"),
                        SettingsOption(InfoBoxBackground.opaque.rawValue, "Opaque"),
                    ],
                    get: { $0.canvas.infoBoxBackground.rawValue },
                    set: { s, v in
                        s.canvas.infoBoxBackground = InfoBoxBackground(rawValue: v) ?? s.canvas.infoBoxBackground
                    }
                )
            ),
        ]
    }

    // MARK: - Placemarks and tracks

    private static func placemarks() -> [SettingsEntry] {
        var out: [SettingsEntry] = [
            SettingsEntry(
                id: "placemarkSize", category: .placemarks, icon: "textformat.size", title: "Taille d'affichage",
                kind: .segmented(
                    options: [
                        SettingsOption(PlacemarkSize.small.rawValue, "Petite", full: "Petite (× 0,75)"),
                        SettingsOption(PlacemarkSize.normal.rawValue, "Normale"),
                        SettingsOption(PlacemarkSize.large.rawValue, "Grande", full: "Grande (× 1,5)"),
                    ],
                    get: { $0.placemarks.size.rawValue },
                    set: { s, v in s.placemarks.size = PlacemarkSize(rawValue: v) ?? s.placemarks.size }
                )
            ),
            SettingsEntry(
                id: "defaultColor", category: .placemarks, icon: "paintpalette", title: "Couleur par défaut",
                hint: "Couleur des nouveaux repères et traces.",
                kind: .color(get: { $0.placemarks.defaultColor }, set: { s, v in s.placemarks.defaultColor = v })
            ),
            SettingsEntry(
                id: "targetColor", category: .placemarks, icon: "scope", title: "Couleur de la cible",
                hint: "Couleur du repère visé.",
                kind: .color(get: { $0.placemarks.targetColor }, set: { s, v in s.placemarks.targetColor = v })
            ),
            SettingsEntry(
                id: "trackArrows", category: .placemarks, icon: "arrow.right", title: "Flèches de sens",
                hint: "Sur toutes les traces, sauf celles qui les cachent.",
                kind: .toggle(get: { $0.placemarks.trackArrows }, set: { s, v in s.placemarks.trackArrows = v })
            ),
            SettingsEntry(
                id: "trackContour", category: .placemarks, icon: "square.dashed.inset.filled", title: "Liseré autour des traces",
                kind: .toggle(get: { $0.placemarks.trackContour }, set: { s, v in s.placemarks.trackContour = v })
            ),
            SettingsEntry(
                id: "routingNoData", category: .placemarks, icon: "arrow.triangle.branch", title: "Sans données BRouter",
                kind: .choice(
                    options: [
                        SettingsOption(
                            RoutingNoData.legByHand.rawValue, "Tracer le tronçon à la main",
                            detail: "Le routage reprend aux tronçons suivants."
                        ),
                        SettingsOption(
                            RoutingNoData.allByHand.rawValue, "Passer tout l'itinéraire en dessin à la main",
                            detail: "Comme sur une feuille blanche, jusqu'à rallumer « Suivre les chemins »."
                        ),
                    ],
                    question: "Quand BRouter n'a pas les données d'une zone, ou ne trouve pas de chemin :",
                    get: { $0.placemarks.routingNoData.rawValue },
                    set: { s, v in s.placemarks.routingNoData = RoutingNoData(rawValue: v) ?? s.placemarks.routingNoData }
                )
            ),
        ]
        out.append(contentsOf: goTo())
        out.append(contentsOf: [
            SettingsEntry(
                id: "labels", category: .placemarks, icon: "character.textbox", title: "Noms sur la carte",
                hint: "Le nom des waypoints et des membres du partage, à côté de leur repère.",
                kind: .toggle(get: { $0.placemarks.labels }, set: { s, v in s.placemarks.labels = v })
            ),
            SettingsEntry(
                id: "borders", category: .placemarks, icon: "circle.circle", title: "Bordures des repères",
                hint: "Un liseré blanc autour des icônes et des disques.",
                kind: .toggle(get: { $0.placemarks.borders }, set: { s, v in s.placemarks.borders = v })
            ),
            SettingsEntry(
                id: "shadows", category: .placemarks, icon: "shadow", title: "Ombres des repères",
                kind: .toggle(get: { $0.placemarks.shadows }, set: { s, v in s.placemarks.shadows = v })
            ),
        ])
        return out
    }

    /// « Aller vers un point » (P30.3): by the ways of BRouter or in a straight line, the profile, how often the way is
    /// computed again and how far off it it is computed at once, the vibration.
    private static func goTo() -> [SettingsEntry] {
        let group = "Aller vers un point"
        let profiles = GoToRules.profiles.map { SettingsOption($0, profileLabel($0)) }
        let minutes = GoToRules.intervalMinutesOffered.map { SettingsOption(String($0), "\($0) min") }
        return [
            SettingsEntry(
                id: "goToRouted", category: .placemarks, icon: "arrow.triangle.turn.up.right.diamond",
                title: "Par les chemins (BRouter)",
                hint: "Sinon : à vol d'oiseau, comme avant",
                group: group,
                kind: .toggle(get: { $0.placemarks.goToRouted }, set: { s, v in s.placemarks.goToRouted = v })
            ),
            SettingsEntry(
                id: "goToProfile", category: .placemarks, icon: "figure.hiking", title: "Profil",
                group: group,
                kind: .choice(
                    options: profiles, question: nil,
                    get: { $0.placemarks.goToProfile },
                    set: { s, v in s.placemarks.goToProfile = v }
                )
            ),
            SettingsEntry(
                id: "goToInterval", category: .placemarks, icon: "arrow.clockwise", title: "Recalculer toutes les",
                group: group,
                kind: .choice(
                    options: minutes, question: nil,
                    get: { String($0.placemarks.goToIntervalMinutes) },
                    set: { s, v in s.placemarks.goToIntervalMinutes = Int(v) ?? s.placemarks.goToIntervalMinutes }
                )
            ),
            SettingsEntry(
                id: "goToOffRoute", category: .placemarks, icon: "arrow.left.and.right",
                title: "Recalculer si je m'écarte de plus de",
                group: group,
                kind: .choice(
                    options: GoToRules.offRouteMetersOffered.map { SettingsOption(String($0), "\($0) m") },
                    question: nil,
                    get: { String($0.placemarks.goToOffRouteMeters) },
                    set: { s, v in s.placemarks.goToOffRouteMeters = Int(v) ?? s.placemarks.goToOffRouteMeters }
                )
            ),
            SettingsEntry(
                id: "goToVibrate", category: .placemarks, icon: "iphone.radiowaves.left.and.right",
                title: "Vibrer en quittant le chemin",
                group: group,
                kind: .toggle(get: { $0.placemarks.goToVibrate }, set: { s, v in s.placemarks.goToVibrate = v })
            ),
        ]
    }

    // MARK: - Positioning and heading

    private static func positioning() -> [SettingsEntry] {
        [
            SettingsEntry(
                id: "course", category: .positioning, icon: "location.north", title: "Affichage du cap GNSS",
                kind: .segmented(
                    options: [
                        SettingsOption(CourseDisplay.none.rawValue, "Aucun"),
                        SettingsOption(CourseDisplay.arrow.rawValue, "Flèche", full: "Flèche de direction du déplacement"),
                    ],
                    get: { $0.positioning.course.rawValue },
                    set: { s, v in s.positioning.course = CourseDisplay(rawValue: v) ?? s.positioning.course }
                )
            ),
            SettingsEntry(
                id: "autoRelock", category: .positioning, icon: "location", title: "Recentrage automatique GNSS",
                hint: "Recentrer la carte sur la position après 10 s sans geste.",
                kind: .toggle(get: { $0.positioning.autoRelock }, set: { s, v in s.positioning.autoRelock = v })
            ),
            SettingsEntry(
                id: "showTrail", category: .positioning, icon: "point.bottomleft.forward.to.point.topright.scurvepath",
                title: "Positions précédentes",
                hint: "Dessiner le chemin récent du téléphone sur la carte.",
                kind: .toggle(get: { $0.positioning.showTrail }, set: { s, v in s.positioning.showTrail = v })
            ),
            SettingsEntry(
                id: "disableInBackground", category: .positioning, icon: "battery.75", title: "Désactiver en arrière-plan",
                hint: "Arrête la localisation quand l'application est masquée. N'affecte pas l'enregistreur de trace.",
                kind: .toggle(
                    get: { $0.positioning.disableInBackground },
                    set: { s, v in s.positioning.disableInBackground = v }
                )
            ),
            SettingsEntry(
                id: "altitude", category: .positioning, icon: "mountain.2", title: "Altitude",
                kind: .choice(
                    options: [
                        SettingsOption(
                            AltitudeMode.auto.rawValue, "Automatique", detail: "Le baromètre une fois calé, le GPS sinon"
                        ),
                        SettingsOption(
                            AltitudeMode.gps.rawValue, "GPS seulement", detail: "Précision verticale du GPS : souvent 10 à 30 m"
                        ),
                        SettingsOption(
                            AltitudeMode.barometer.rawValue, "Baromètre seulement",
                            detail: "Précis en relatif ; calé sur le GPS et le relief"
                        ),
                    ],
                    question: nil,
                    get: { $0.altitude.rawValue },
                    set: { s, v in s.altitude = AltitudeMode(rawValue: v) ?? s.altitude }
                )
            ),
            SettingsEntry(
                id: "heading", category: .positioning, icon: "safari", title: "Affichage du cap actuel",
                kind: .choice(
                    options: [
                        SettingsOption(HeadingDisplay.none.rawValue, "Aucun"),
                        SettingsOption(HeadingDisplay.number.rawValue, "Valeur seulement"),
                        SettingsOption(HeadingDisplay.arrow.rawValue, "Cône sur la position"),
                        SettingsOption(HeadingDisplay.both.rawValue, "Cône et valeur"),
                    ],
                    question: nil,
                    get: { $0.heading.rawValue },
                    set: { s, v in s.heading = HeadingDisplay(rawValue: v) ?? s.heading }
                )
            ),
        ]
    }

    // MARK: - Track recorder

    private static func recorder() -> [SettingsEntry] {
        [
            SettingsEntry(
                id: "lostSignal", category: .recorder, icon: "location.slash", title: "Avertissement de perte de signal",
                hint: "Prévenir si le GNSS ne donne plus de position pendant une minute.",
                kind: .toggle(get: { $0.recorder.lostSignalWarning }, set: { s, v in s.recorder.lostSignalWarning = v })
            ),
            SettingsEntry(
                id: "askActivity", category: .recorder, icon: "figure.walk", title: "Demander l'activité",
                hint: "Au démarrage d'un enregistrement. Le nom de la trace en tient compte.",
                kind: .toggle(get: { $0.recorder.askActivity }, set: { s, v in s.recorder.askActivity = v })
            ),
            SettingsEntry(
                id: "resetStats", category: .recorder, icon: "arrow.counterclockwise",
                title: "Réinitialiser les statistiques du segment",
                hint: "Au nouveau segment, les statistiques « fait jusqu'ici » repartent de zéro.",
                kind: .toggle(
                    get: { $0.recorder.resetStatsOnSegment },
                    set: { s, v in s.recorder.resetStatsOnSegment = v }
                )
            ),
            SettingsEntry(
                id: "dashboard", category: .recorder, icon: "gauge.with.needle", title: "Mode tableau de bord",
                hint: "L'enregistrement s'arrête et reprend avec l'application.",
                kind: .toggle(get: { $0.recorder.dashboardMode }, set: { s, v in s.recorder.dashboardMode = v })
            ),
        ]
    }

    // MARK: - Alerts

    private static func alerts() -> [SettingsEntry] {
        [
            SettingsEntry(
                id: "proximity", category: .alerts, icon: "bell.badge", title: "Alerte de proximité",
                hint: "Prévient en approchant d'un point surveillé",
                kind: .toggle(get: { $0.alerts.proximityEnabled }, set: { s, v in s.alerts.proximityEnabled = v })
            ),
            SettingsEntry(
                id: "proximityRadius", category: .alerts, icon: "dot.radiowaves.left.and.right", title: "Rayon par défaut",
                visible: { $0.alerts.proximityEnabled },
                kind: .slider(
                    range: 20 ... 1000,
                    step: 10,
                    label: { value, settings in distance(value, settings) },
                    get: { $0.alerts.proximityRadiusMeters },
                    set: { s, v in s.alerts.proximityRadiusMeters = v }
                )
            ),
            SettingsEntry(
                id: "offTrack", category: .alerts, icon: "location.slash.circle", title: "Alerte d'écart de trace",
                hint: "Prévient quand on s'éloigne de la trace suivie",
                kind: .toggle(get: { $0.alerts.offTrackEnabled }, set: { s, v in s.alerts.offTrackEnabled = v })
            ),
            SettingsEntry(
                id: "offTrackMeters", category: .alerts, icon: "arrow.left.and.right", title: "Écart toléré",
                visible: { $0.alerts.offTrackEnabled },
                kind: .slider(
                    range: 10 ... 500,
                    step: 5,
                    label: { value, settings in distance(value, settings) },
                    get: { $0.alerts.offTrackMeters },
                    set: { s, v in s.alerts.offTrackMeters = v }
                )
            ),
            SettingsEntry(
                id: "alertVibrate", category: .alerts, icon: "iphone.radiowaves.left.and.right", title: "Vibration",
                kind: .toggle(get: { $0.alerts.vibrate }, set: { s, v in s.alerts.vibrate = v })
            ),
            SettingsEntry(
                id: "alertSound", category: .alerts, icon: "speaker.wave.2", title: "Son",
                kind: .toggle(get: { $0.alerts.sound }, set: { s, v in s.alerts.sound = v })
            ),
        ]
    }

    // MARK: - Storage and backup

    private static func data() -> [SettingsEntry] {
        [
            SettingsEntry(
                id: "backup", category: .data, icon: "arrow.clockwise.icloud", title: "Sauvegarde et restauration…",
                kind: .backupLink
            ),
        ]
    }
}
