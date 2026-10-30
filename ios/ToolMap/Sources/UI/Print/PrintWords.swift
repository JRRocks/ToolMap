import Foundation
import ToolMapCore
import UIKit

/// The French texts of the print, as the Android app writes them (`strings.xml`, `print_*` and `pdf_*`), and the
/// content of the PDF besides the tiles (Android `PrintViewModel.buildContent`).
enum PrintWords {
    static let locale = Locale(identifier: "fr_FR")

    // MARK: Numbers

    static func number(_ value: Double, decimals: Int, grouping: Bool = false) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals
        formatter.usesGroupingSeparator = grouping
        formatter.roundingMode = .halfUp
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// "850 Ko", "2,4 Mo", "1,2 Go" (Android `formatBytes`).
    static func bytes(_ count: Int) -> String {
        if count >= 1_000_000_000 { return number(Double(count) / 1_000_000_000, decimals: 1) + " Go" }
        if count >= 1_000_000 { return number(Double(count) / 1_000_000, decimals: 1) + " Mo" }
        if count >= 1_000 { return "\(count / 1_000) Ko" }
        return "\(count) o"
    }

    /// "21,8" or "257": millions of pixels.
    static func megapixels(_ pixels: Int) -> String {
        let millions = Double(pixels) / 1_000_000
        return number(millions, decimals: millions < 100 ? 1 : 0)
    }

    /// "210 × 297", "2 000 × 1 000": the millimetres of a sheet.
    static func sizeText(_ widthMm: Double, _ heightMm: Double) -> String {
        "\(number(widthMm, decimals: 0, grouping: true)) × \(number(heightMm, decimals: 0, grouping: true))"
    }

    /// "1 page", "3 pages" (French plural: one for 0 and 1).
    static func pages(_ count: Int) -> String {
        count < 2 ? "\(count) page" : "\(count) pages"
    }

    static func tilesMissing(_ count: Int) -> String {
        count < 2 ? "\(count) tuile à télécharger" : "\(count) tuiles à télécharger"
    }

    static func scale(_ denominator: Double) -> String {
        PrintFormat.scale(denominator, PrintTexts())
    }

    /// The scale typed after « Autre échelle… », when it is a number between 1:500 and 1:10 000 000.
    static func scaleValue(_ text: String) -> Double? {
        let cleaned = text.replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\u{00A0}", with: "")
            .replacingOccurrences(of: "\u{202F}", with: "")
            .replacingOccurrences(of: ",", with: ".")
        guard let value = Double(cleaned), value >= PrintTemplate.minScale, value <= PrintTemplate.maxScale else { return nil }
        return value
    }

    /// Millimetres typed in a field: "250", "250,5".
    static func millimetres(_ text: String) -> Double? {
        let cleaned = text.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: ",", with: ".")
        return Double(cleaned)
    }

    // MARK: Sheet

    /// "A4", "Letter", "250 × 180 mm".
    static func paperName(_ setup: PageSetup) -> String {
        switch setup.paper {
        case .letter: return "Letter"
        case .legal: return "Legal"
        case .custom:
            let w = Int(setup.customWidthMm.rounded())
            let h = Int(setup.customHeightMm.rounded())
            return "\(w) × \(h) mm"
        default: return setup.paper.rawValue
        }
    }

    /// The name in the list of formats: "A4", "Letter", "Autre…".
    static func paperChoiceName(_ paper: Paper) -> String {
        switch paper {
        case .letter: "Letter"
        case .legal: "Legal"
        case .custom: "Autre…"
        default: paper.rawValue
        }
    }

    static func orientation(_ landscape: Bool) -> String {
        landscape ? "Paysage" : "Portrait"
    }

    /// The ground the frame covers: "6,6 km" and "3,7 km".
    static func groundSize(_ template: PrintTemplate) -> (String, String) {
        PrintFormat.groundSize(
            PrintViewport.groundMeters(template.page.frameWidthMm, template.scale),
            PrintViewport.groundMeters(template.page.frameHeightMm, template.scale),
            locale
        )
    }

    // MARK: Grid

    /// The grids in the order of the choice (P17, Q8).
    static let gridOrder: [GridKind] = [.none, .utm, .mgrs, .lambert93, .wgs84, .wgs84Decimal]

    static func gridName(_ kind: GridKind) -> String {
        switch kind {
        case .none: "Aucune"
        case .utm: "UTM"
        case .mgrs: "MGRS"
        case .lambert93: "Lambert 93"
        case .wgs84: "WGS84 DMS"
        case .wgs84Decimal: "WGS84 décimal"
        }
    }

    static func isDegrees(_ kind: GridKind) -> Bool {
        kind == .wgs84 || kind == .wgs84Decimal
    }

    /// "1 km", "500 m", "30″", "2′": the spacing of the grid in its own unit (as Android writes it).
    static func gridStepText(_ step: Double, degrees: Bool) -> String {
        if !degrees { return ScaleBar.format(step) }
        if step < 1.0 / 60.0 { return "\(Int((step * 3_600).rounded()))″" }
        if step < 1.0 { return "\(Int((step * 60).rounded()))′" }
        return "\(Int(step.rounded()))°"
    }

    // MARK: Plan

    static func issue(_ issue: PrintIssue) -> String {
        switch issue {
        case .pageSetup: "Le format de page ou les marges ne laissent pas de place à la carte."
        case .scaleRange: "L'échelle doit être comprise entre 1:500 et 1:10 000 000."
        case .noArea: "Pas de zone à couvrir."
        case .noTrack: "Choisissez la trace à suivre."
        case .tooManyPages: "Trop de pages : réduisez la zone ou l'échelle."
        case .tooManyTiles: "Trop de tuiles : baissez la résolution ou le format."
        case .noLayer: "Aucun calque à imprimer à cette échelle : affichez une carte ou élargissez sa plage de zoom."
        }
    }

    /// Oversampled maps and maps left out, in orange; a heavy PDF has its own box.
    static func warning(_ warning: PrintWarning) -> String? {
        switch warning {
        case let .oversampled(_, sourceName, factor, _, _):
            let times = number(factor, decimals: 1)
            return "\(sourceName) : tuiles agrandies ×\(times) (la carte s'arrête à un zoom plus faible), impression moins nette."
        case let .outsideRange(_, sourceName):
            return "\(sourceName) : hors de sa plage de zoom à cette échelle, non imprimé."
        case let .unavailable(sourceId):
            return "La carte « \(sourceId) » n'est pas disponible."
        case .heavy:
            return nil
        }
    }

    /// The warning of a heavy PDF in `plan`, if any (P17.9): the resolution and the pixels of the largest page.
    static func heavy(_ plan: PrintPlan?) -> (dpi: Int, pixels: Int)? {
        guard let plan else { return nil }
        for warning in plan.warnings {
            if case let .heavy(dpi, pixels) = warning {
                return (dpi, pixels)
            }
        }
        return nil
    }

    /// « Tous », « 5 sur 6 · sans coordonnées des coins », « 4 sur 6 ».
    static func elementsSummary(_ d: Dressing) -> String {
        let elements: [(Bool, String)] = [
            (d.scaleText, "Échelle chiffrée"),
            (d.scaleBar, "Barre d'échelle"),
            (d.northArrow, "Nords et déclinaison"),
            (d.legend, "Légende"),
            (d.cartouche, "Date et source"),
            (d.cornerCoordinates, "Coordonnées des coins"),
        ]
        let on = elements.filter { $0.0 }.count
        let off = elements.filter { !$0.0 }
        if off.isEmpty { return "Tous" }
        if off.count == 1 {
            let name = off[0].1
            let first = name.prefix(1).lowercased()
            let rest = String(name.dropFirst())
            return "\(on) sur \(elements.count) · sans \(first)\(rest)"
        }
        return "\(on) sur \(elements.count)"
    }

    /// « 300 dpi · marge 8 mm · grille tous les 1 km ».
    static func qualitySummary(_ template: PrintTemplate) -> String {
        let margin = Int(template.page.marginMm.rounded())
        let base = "\(template.dpi) dpi · marge \(margin) mm"
        if template.grid == GridKind.none { return base }
        let step = gridStepText(template.effectiveGridStep, degrees: isDegrees(template.grid))
        return "\(base) · grille tous les \(step)"
    }

    // MARK: Dates

    /// "29/10/2026".
    static func shortDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .short
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    // MARK: The PDF

    /// What goes on the pages besides the map tiles: the visible tracks and waypoints, the credits of the maps, the
    /// legend, the date and the texts. No magnetic declination yet on iPhone (Android reads it from the World Magnetic
    /// Model of the system; iOS has none at an arbitrary place): the north diagram then shows the true north only.
    static func content(_ plan: PrintPlan, items: PrintItems?) -> PrintContent {
        let dressing = plan.template.dressing
        let layers = plan.mapPages.first?.layers.layers ?? []
        var attributions: [String] = []
        for layer in layers {
            guard let copyright = layer.source.copyright,
                  !copyright.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !attributions.contains(copyright)
            else { continue }
            attributions.append(copyright)
        }
        let waypoints = dressing.waypoints ? (items?.waypoints ?? []) : []
        let tracks = dressing.tracks ? (items?.tracks ?? []) : []
        return PrintContent(
            tracks: tracks,
            waypoints: waypoints,
            attributions: attributions,
            layerNames: layers.map(\.source.name),
            declinationDegrees: nil,
            declinationYear: Calendar(identifier: .gregorian).component(.year, from: Date()),
            declinationAnnualDegrees: nil,
            glyphs: glyphs(waypoints),
            dateText: shortDate(Date()),
            texts: pdfTexts(),
            locale: locale
        )
    }

    /// The white glyph of each icon used, as the map draws it on the coloured disc: 64 px, about 580 dpi at 2.8 mm.
    private static func glyphs(_ waypoints: [PrintWaypoint]) -> [String: PdfImage] {
        var out: [String: PdfImage] = [:]
        for waypoint in waypoints {
            guard let id = waypoint.iconId, out[id] == nil, let image = PrintLibraryBridge.waypointGlyph(id) else { continue }
            if let glyph = whiteGlyph(image) {
                out[id] = glyph
            }
        }
        return out
    }

    private static func whiteGlyph(_ image: UIImage) -> PdfImage? {
        let side = 64
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format)
        let drawn = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        guard let cgImage = drawn.cgImage, let rgba = PrintImages.pixels(cgImage), rgba.width == side, rgba.height == side
        else { return nil }
        var alpha = Data(count: side * side)
        for i in 0 ..< side * side {
            alpha[i] = rgba.bytes[i * 4 + 3]
        }
        let white = Data(repeating: 0xFF, count: side * side * 3)
        return try? PdfImage.rgba(side, side, white, alpha)
    }

    /// The French texts of the sheet (Android `pdf_*` strings).
    static func pdfTexts() -> PrintTexts {
        PrintTexts(
            legendTitle: "Légende",
            layers: "Cartes",
            tracks: "Traces",
            waypoints: "Waypoints (%d)",
            printedOn: "Imprimé le %s",
            gridDescription: "Quadrillage %1$s, %2$s",
            declination: "Déclinaison",
            gridConvergence: "Convergence",
            page: "Page %1$d/%2$d",
            overviewTitle: "Vue d'ensemble de l'atlas (pages : %d)",
            producedWith: "Réalisé avec ToolMap",
            missingTiles: "Zones hachurées : tuiles indisponibles",
            oversampled: "%1$s : agrandi ×%2$s",
            east: "E",
            west: "O",
            north: "N",
            scaleLabel: "1:%s",
            coordinateSystem: "WGS84 / Web Mercator",
            south: "S",
            mapSource: "Carte : %s",
            cmOnGround: "1 cm sur la carte = %s sur le terrain",
            scaleNote: "exacte au centre, ±%s %% aux bords",
            declinationDetail: "en %1$d, évolue de %2$s par an",
            gridSquares: "Carreaux de %s",
            gridLines: "Lignes tous les %s",
            trueNorth: "NG",
            magneticNorth: "NM",
            gridNorth: "NQ",
            trueNorthName: "nord géographique",
            annualChange: "variation annuelle %s",
            gridMagneticAngle: "Angle quadrillage–magnétique",
            notToScale: "Schéma hors échelle",
            noGrid: "Sans quadrillage",
            principalDigits: "Gros chiffres : kilomètres",
            squareKilometers: "= %s km dans le carré de 100 km",
            zoneEdgeMeridian: "Limite des zones %1$s / %2$s : méridien %3$s (trait épais)",
            zoneEdgeParallel: "Limite des bandes %1$s / %2$s : parallèle %3$s (trait épais)",
            neighbourGrid: "En bleu dans la marge : quadrillage de la zone voisine",
            and: "et",
            squares: "Carré de 100 km : %s",
            squaresOfZones: "Carrés de 100 km : %s",
            westSide: "ouest",
            eastSide: "est",
            southSide: "sud",
            northSide: "nord",
            scaleHeading: "ÉCHELLE",
            northHeading: "NORDS",
            gridHeading: "QUADRILLAGE"
        )
    }
}
