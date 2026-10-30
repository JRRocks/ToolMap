import Foundation
import SwiftUI
import ToolMapCore

/// Texts of « Enregistrer une zone », in French, as in the Android strings (`sa_*`, `download_*`).
enum ZoneText {
    static let noMaps = "Aucune carte à enregistrer : affichez une carte qui a un serveur."
    static let offlineWarning = "Le mode hors ligne est actif : aucun téléchargement n'est possible."
    static let emptyHint = """
    Pour utiliser une carte sans réseau, choisissez une zone sur la carte et le zoom maximal : ses tuiles sont \
    enregistrées dans le téléphone.
    """
    static let help = """
    Déplacez les quatre poignées pour entourer la zone à garder, réglez le zoom maximal, puis touchez « Cartes » pour \
    cocher les cartes. Chaque barre montre les niveaux de zoom de la carte : en couleur ceux qui seront enregistrés, en \
    gris clair ceux qui ne le seront pas ou que la carte n'a pas, et le trait rouge marque le zoom affiché.
    """

    /// "9,5 Mo", "480 Ko"… (decimal units, like the file managers; Android `formatBytes`).
    static func bytes(_ value: Int64) -> String {
        if value >= 1_000_000_000 {
            return oneDecimal(Double(value) / 1_000_000_000) + " Go"
        }
        if value >= 1_000_000 {
            return oneDecimal(Double(value) / 1_000_000) + " Mo"
        }
        if value >= 1_000 {
            return "\(value / 1_000) Ko"
        }
        return "\(value) o"
    }

    /// Counts with the thousands apart (« 1 450 »).
    static func count(_ value: Int64) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = true
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// « 415 tuiles » (French: 0 and 1 are singular).
    static func tiles(_ n: Int) -> String {
        n <= 1 ? "\(n) tuile" : "\(n) tuiles"
    }

    /// « 31 déjà stockées ».
    static func stored(_ n: Int) -> String {
        n <= 1 ? "\(n) déjà stockée" : "\(n) déjà stockées"
    }

    static func maps(_ n: Int) -> String {
        n <= 1 ? "\(n) carte" : "\(n) cartes"
    }

    /// « 5,0 Go libres ».
    static func free(_ value: Int64) -> String {
        bytes(value) + " libres"
    }

    /// « 500 m », « 1,25 km », « 5 km ».
    static func meters(_ value: Int) -> String {
        guard value >= 1_000 else { return "\(value) m" }
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 2
        let km = Double(value) / 1_000
        return (formatter.string(from: NSNumber(value: km)) ?? "\(km)") + " km"
    }

    private static func oneDecimal(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        formatter.usesGroupingSeparator = false
        return formatter.string(from: NSNumber(value: value)) ?? String(format: "%.1f", value)
    }

    /// The total when there is no weight to show: computing, already saved, nothing, too large, a server's limit.
    static func totalState(_ estimate: ZoneEstimate?) -> String {
        guard let estimate else { return "Estimation en cours…" }
        switch estimate {
        case .ready:
            return "Déjà enregistrée"
        case .empty:
            return "Aucune tuile : choisissez une carte et des zooms disponibles."
        case .tooMany:
            return "Zone trop grande : réduisez la surface ou les zooms."
        case let .restricted(name, tiles, limit):
            return "\(name) : serveur communautaire, limite de tuiles : \(limit), zone demandée : \(tiles)."
        }
    }

    /// True for the states written in red.
    static func isError(_ estimate: ZoneEstimate?) -> Bool {
        switch estimate {
        case .some(.tooMany), .some(.restricted): return true
        default: return false
        }
    }

    /// « 415 tuiles à télécharger · 31 déjà stockées » under the weight.
    static func tilesLine(_ estimate: ZoneEstimate?) -> String? {
        guard let estimate, estimate.perSource != nil else { return nil }
        if estimate.missing == 0 {
            return tiles(estimate.tiles)
        }
        let fetch = "\(tiles(estimate.missing)) à télécharger"
        return estimate.stored == 0 ? fetch : "\(fetch) · \(stored(estimate.stored))"
    }

    /// « IGN Cartes, OpenTopoMap · 2 sur 3 »: the maps checked and how many of the maps offered they are.
    static func mapsSummary(_ rows: [ZoneAreaRow]) -> String {
        let maps = rows.filter { !$0.isElevation }
        let checked = maps.filter(\.checked)
        let names = checked.map(\.source.name).joined(separator: ", ")
        let share = "\(checked.count) sur \(maps.count)"
        return names.isEmpty ? share : "\(names) · \(share)"
    }

    /// The name of a row: the map, or « Altitudes (pour IGN Cartes) ».
    static func rowName(_ row: ZoneAreaRow) -> String {
        row.isElevation ? "Altitudes (pour \(row.forMaps.joined(separator: ", ")))" : row.source.name
    }

    /// The line under a map: its zooms, its tiles and their weight, or its state.
    static func rowDetail(_ row: ZoneAreaRow, _ zone: ZoneDraft) -> String {
        if !row.checked { return "Non cochée" }
        guard let span = ZoneStrips.span(sourceMin: row.minZoom, sourceMax: row.maxZoom, chosenMax: zone.maxZoom) else {
            return "Aucune tuile à ces zooms"
        }
        // The « Altitudes » row has the zooms the masks read, not a map's own.
        let zooms = row.isElevation
            ? "Zooms \(span.from) à \(span.to)"
            : "Zooms \(span.from) à \(span.to) (sur \(span.available))"
        if zone.estimating || zone.estimate == nil {
            return "\(zooms) · calcul…"
        }
        guard let figures = zone.estimate?.perSource?[row.id] else { return zooms }
        let weight = bytes(Int64(figures.bytes))
        if figures.stored == 0 {
            return "\(zooms) · \(tiles(figures.tiles)) · \(weight)"
        }
        if figures.missing == 0 {
            return row.isElevation ? "\(zooms) · déjà enregistrées" : "\(zooms) · déjà enregistrée"
        }
        return "\(zooms) · \(tiles(figures.missing)) à télécharger · \(weight) · \(stored(figures.stored))"
    }
}

/// Texts of « Téléchargements » (Android `DownloadScreen`).
enum DownloadText {
    static func status(_ status: ZoneStatus) -> String {
        switch status {
        case .queued: "En attente"
        case .running: "En cours"
        case .paused: "En pause"
        case .interrupted: "Interrompu : nouvelle tentative automatique"
        case .done: "Terminé"
        case .failed: "Échec"
        }
    }

    /// The icon of a job's state and its colour.
    static func icon(_ status: ZoneStatus) -> (name: String, color: Color) {
        switch status {
        case .done: return (name: "checkmark.circle.fill", color: Crete.turquoise)
        case .failed: return (name: "exclamationmark.circle.fill", color: Crete.danger)
        case .paused: return (name: "pause.circle", color: Crete.onSurfaceVariant)
        case .interrupted: return (name: "arrow.clockwise.circle", color: Crete.onSurfaceVariant)
        case .queued, .running: return (name: "clock", color: Crete.onSurfaceVariant)
        }
    }

    static func mapNames(_ request: ZoneRequest) -> String {
        request.sourceIds.map { TileService.shared.source($0)?.name ?? $0 }.joined(separator: ", ")
    }

    /// « IGN Cartes, OpenTopoMap · zooms jusqu'à 16 ».
    static func mapsAndZooms(_ request: ZoneRequest) -> String {
        let zooms = request.minZoom <= 0
            ? "zooms jusqu'à \(request.maxZoom)"
            : "zooms \(request.minZoom) à \(request.maxZoom)"
        return "\(mapNames(request)) · \(zooms)"
    }

    /// « 310 / 1 450 tuiles · 6,2 Mo », and the errors only when there are some.
    static func progressLine(_ job: ZoneJob) -> String {
        let progress = job.progress
        var parts: [String] = []
        let processed = ZoneText.count(progress.processed)
        if job.status == .done {
            parts.append("\(processed) tuiles")
        } else {
            parts.append("\(processed) / \(ZoneText.count(progress.total)) tuiles")
        }
        if progress.bytes > 0 {
            parts.append(ZoneText.bytes(progress.bytes))
        }
        if progress.failed > 0 {
            parts.append(progress.failed == 1 ? "1 erreur" : "\(progress.failed) erreurs")
        }
        return parts.joined(separator: " · ")
    }

    /// « 51 % ».
    static func percent(_ fraction: Float) -> String {
        "\(Int(fraction * 100))\u{00A0}%"
    }

    /// The line under a job of a block: its state while it waits, its counts, its maps, the day it finished.
    static func detail(_ job: ZoneJob) -> String {
        var parts: [String] = []
        if job.status == .queued || job.status == .interrupted {
            parts.append(status(job.status))
        }
        parts.append(progressLine(job))
        parts.append(mapNames(job.request))
        if job.status == .done, job.updatedAtMillis > 0 {
            parts.append("le \(NameDates.formatDay(job.updatedAtMillis))")
        }
        return parts.joined(separator: " · ")
    }
}

/// Texts of « Stockage hors ligne » (Android `StorageScreen`).
enum StorageText {
    /// « 8412 tuiles · zooms 6 à 16 · Carte locale (aucun serveur) · Tuiles partagées par 2 cartes ».
    static func detail(_ usage: StorageUsage) -> String {
        var parts: [String] = [ZoneText.tiles(usage.tiles)]
        if let low = usage.minZoom, let high = usage.maxZoom {
            parts.append(low == high ? "zoom \(low)" : "zooms \(low) à \(high)")
        }
        if usage.isLocalMap {
            parts.append("Carte locale (aucun serveur)")
        }
        if usage.usedBy.count > 1 {
            parts.append("Tuiles partagées par \(usage.usedBy.count) cartes")
        }
        return parts.joined(separator: " · ")
    }
}

/// A short message at the bottom of a page, with an optional action (the Android snackbar of the page): the map's own
/// snackbar is hidden under a full-screen page.
struct DownloadsMessageBar: View {
    let text: String
    var action: String?
    var perform: (() -> Void)?
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let action {
                Button(action) {
                    perform?()
                    onDismiss()
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Crete.pill)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 10))
        .task(id: text) {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            onDismiss()
        }
    }
}
