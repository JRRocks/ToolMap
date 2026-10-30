import Foundation
import ToolMapCore

/// The French texts of « Cartes et calques », « Cartes disponibles » and « Conditions d'affichage », as the Android
/// `strings.xml` writes them (French plurals: « one » for 0 and 1).
enum MapsText {
    /// "40" of a 0...1 fraction (Kotlin `(fraction * 100).toInt()`).
    static func percent(_ fraction: Float) -> Int {
        Int(fraction * 100)
    }

    static func plural(_ count: Int, _ one: String, _ other: String) -> String {
        count < 2 ? "\(count) \(one)" : "\(count) \(other)"
    }

    /// « 11,7 Mo » (Android `formatBytes`).
    static func bytes(_ value: Int64) -> String {
        let kb: Int64 = 1024
        let mb = kb * 1024
        let gb = mb * 1024
        if value >= gb { return decimal(Double(value) / Double(gb)) + " Go" }
        if value >= mb { return decimal(Double(value) / Double(mb)) + " Mo" }
        if value >= kb { return "\(value / kb) Ko" }
        return "\(value) o"
    }

    private static func decimal(_ value: Double) -> String {
        String(format: "%.1f", value).replacingOccurrences(of: ".", with: ",")
    }

    /// « 1 tuile », « 12 tuiles ».
    static func tiles(_ count: Int) -> String {
        plural(count, "tuile", "tuiles")
    }

    // MARK: Band of the panel

    /// The subtitle of « Cartes et calques »: what is shown, then how many maps the map draws at its zoom.
    static func bandSubtitle(favorite: String?, single: String?, count: Int, drawn: Int, zoom: Int) -> String {
        if count == 0 { return "Aucune carte" }
        let maps = plural(count, "carte", "cartes")
        var what = maps
        if let name = favorite ?? single {
            what = "\(name) · \(maps)"
        }
        let now: String
        if drawn == 0 {
            now = "Aucune dessinée au zoom \(zoom)"
        } else if drawn < 2 {
            now = "\(drawn) dessinée au zoom \(zoom)"
        } else {
            now = "\(drawn) dessinées au zoom \(zoom)"
        }
        return "\(what)\n\(now)"
    }

    // MARK: Layer lines

    /// "Visible aux zooms 12 à 17" for a layer display range (nil = unbounded on that side).
    static func zoomRange(_ min: Int?, _ max: Int?) -> String {
        if min == nil, max == nil { return "Visible à tous les zooms" }
        guard let high = max else { return "Visible à partir du zoom \(min ?? 0)" }
        guard let low = min else { return "Visible jusqu'au zoom \(high)" }
        if low == high { return "Visible au zoom \(low)" }
        return "Visible aux zooms \(low) à \(high)"
    }

    /// The grey line of a layer: hidden; else its zoom range or elevation band and whether it is drawn now (ADR-063,
    /// P20), with the opacity and inverted colours when they are not the plain ones.
    static func layerState(_ layer: LayerConfig, drawnNow: Bool) -> String {
        let value = percent(layer.opacity)
        let look = layer.inverted ? "\(value) % d'opacité · couleurs inversées" : "\(value) % d'opacité"
        if !layer.visible { return "masquée" }
        if !layer.hasZoomRange, !layer.hasElevationRange { return look }
        var parts = [conditionsLine(layer) ?? zoomRange(layer.displayMinZoom, layer.displayMaxZoom)]
        if !drawnNow { parts.append("inactif à ce zoom") }
        if value < 100 || layer.inverted { parts.append(look) }
        return parts.joined(separator: " · ")
    }

    /// "Au-dessus de 1500 m · zooms 8 à 16" (ET) or "Au-dessus de 1500 m ou zooms 8 à 16" (OU); nil without a band.
    static func conditionsLine(_ layer: LayerConfig) -> String? {
        if !layer.hasElevationRange { return nil }
        let band = bandSummary(layer.displayMinElevation, layer.displayMaxElevation)
        if !layer.hasZoomRange { return band }
        let zooms: String
        if let high = layer.displayMaxZoom {
            if let low = layer.displayMinZoom {
                zooms = low == high ? "zoom \(low)" : "zooms \(low) à \(high)"
            } else {
                zooms = "jusqu'au zoom \(high)"
            }
        } else {
            zooms = "à partir du zoom \(layer.displayMinZoom ?? 0)"
        }
        return layer.rangeLogic == .or ? "\(band) ou \(zooms)" : "\(band) · \(zooms)"
    }

    // MARK: Conditions d'affichage

    /// « 12 à 17 », « tous »: the zoom range in the label column.
    static func zoomShort(_ min: Int?, _ max: Int?) -> String {
        if min == nil, max == nil { return "tous" }
        guard let high = max else { return "dès \(min ?? 0)" }
        guard let low = min else { return "jusqu'à \(high)" }
        return low == high ? "\(low)" : "\(low) à \(high)"
    }

    /// « toutes », « dès 1500 m »: the elevation band in the label column.
    static func bandShort(_ min: Int?, _ max: Int?) -> String {
        if min == nil, max == nil { return "toutes" }
        guard let high = max else { return "dès \(min ?? 0) m" }
        guard let low = min else { return "sous \(high) m" }
        return "\(low) à \(high) m"
    }

    /// "Zooms 8 à 16": the zoom range as the panel says it.
    static func zoomSummary(_ min: Int?, _ max: Int?) -> String {
        if min == nil, max == nil { return "Tous les zooms" }
        guard let high = max else { return "À partir du zoom \(min ?? 0)" }
        guard let low = min else { return "Jusqu'au zoom \(high)" }
        return low == high ? "Zoom \(low)" : "Zooms \(low) à \(high)"
    }

    /// "Au-dessus de 1500 m": the elevation band as the panel says it.
    static func bandSummary(_ min: Int?, _ max: Int?) -> String {
        if min == nil, max == nil { return "Toutes les altitudes" }
        guard let high = max else { return "Au-dessus de \(min ?? 0) m" }
        guard let low = min else { return "En dessous de \(high) m" }
        return "Entre \(low) et \(high) m"
    }

    /// "au-dessus de 1500 m", inside a sentence; the band is set.
    private static func bandPhrase(_ min: Int?, _ max: Int?) -> String {
        guard let high = max else { return "au-dessus de \(min ?? 0) m" }
        guard let low = min else { return "en dessous de \(high) m" }
        return "entre \(low) et \(high) m"
    }

    /// The sentence under ET / OU saying where the layer is drawn, whole or in its band.
    static func explanation(_ layer: LayerConfig) -> String {
        let zoomMin = layer.displayMinZoom
        let zoomMax = layer.displayMaxZoom
        if !layer.hasElevationRange {
            return "\(zoomRange(zoomMin, zoomMax))."
        }
        let band = bandPhrase(layer.displayMinElevation, layer.displayMaxElevation)
        if layer.rangeLogic == .and {
            return "\(zoomRange(zoomMin, zoomMax)), \(band) seulement."
        }
        if !layer.hasZoomRange {
            return "Sans plage de zoom, OU revient à ET."
        }
        let zooms: String
        if let high = zoomMax {
            if let low = zoomMin {
                zooms = low == high ? "au zoom \(low)" : "aux zooms \(low) à \(high)"
            } else {
                zooms = "jusqu'au zoom \(high)"
            }
        } else {
            zooms = "à partir du zoom \(zoomMin ?? 0)"
        }
        return "Entière \(zooms) ; aux autres zooms, \(band) seulement."
    }

    // MARK: Cartes disponibles

    /// The title of a section of the catalogue, also searched (F5).
    static func groupTitle(_ kind: GroupKind) -> String {
        switch kind {
        case .openStreetMap: return "Cartes OpenStreetMap"
        case .ign: return "France · Cartes officielles IGN"
        case .multiZoom: return "Cartes multi-zoom"
        case .imported: return "Mes cartes"
        case .local: return "Cartes locales (paquets .mbtiles)"
        case .other: return "Autres cartes"
        }
    }

    /// « Fond : IGN Cartes · 1 calque »: what the map shows, in the words of the two segments.
    static func status(base: String?, layers: Int) -> String {
        let count = layers == 0 ? "aucun calque" : plural(layers, "calque", "calques")
        guard let base else { return "Aucun fond · \(count)" }
        return "Fond : \(base) · \(count)"
    }

    /// The title of the section of a provider of the online catalogue.
    static func providerTitle(_ provider: CatalogProvider) -> String {
        switch provider {
        case .openStreetMap: return "OpenStreetMap"
        case .ign: return "IGN France"
        case .swisstopo: return "Swisstopo (Suisse)"
        case .bkg: return "BKG (Allemagne)"
        case .usgs: return "USGS (États-Unis)"
        case .ordnanceSurvey: return "Ordnance Survey (Royaume-Uni)"
        }
    }

    /// The SF Symbol of the kind of a map: relief data, an overlay, an aerial image, a shaded relief, a map.
    static func typeIcon(_ source: TileSource?) -> String {
        guard let source else { return "map" }
        if source.type == "dem" || source.type == "hillshade" { return "mountain.2" }
        if source.isLayer { return "square.3.layers.3d" }
        if source.type == "satellite" || source.type == "hybrid" { return "globe.europe.africa" }
        return "map"
    }

    /// « Zooms a à b », or why the map cannot be shown.
    static func zooms(_ source: TileSource) -> String {
        guard source.isSupported else { return "Non supportée : \(source.unsupportedReason ?? "")" }
        return "Zooms \(source.minZoom ?? 0) à \(source.maxZoom ?? 0)"
    }
}
