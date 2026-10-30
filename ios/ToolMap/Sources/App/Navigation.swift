import Foundation
import ToolMapCore

/// The full-screen pages (Android navigation destinations, docs/specs/ui-index.md §1), shown above the map.
enum Screen: Identifiable, Hashable {
    /// Traces et waypoints (§6.1).
    case library
    /// Détail d'une trace (§6.2), by its id in the library.
    case trackDetail(Int64)
    /// Rechercher un lieu, des coordonnées ou un élément (§7 SEARCH).
    case search
    /// Partage de position (§7 SHARE).
    case share
    /// Téléchargements (§7 DOWNLOADS).
    case downloads
    /// Stockage hors ligne (§7 STORAGE).
    case storage
    /// Cartes disponibles (§5.3).
    case availableMaps
    /// Réglages (§8).
    case settings
    /// Sauvegarde et restauration (§7 BACKUP).
    case backup
    /// À propos et aide (§7 ABOUT), opened at a section (`nil`: the top).
    case about(String?)
    /// Nouveautés (§3.1 P9).
    case whatsNew

    var id: String {
        switch self {
        case .library: "library"
        case let .trackDetail(id): "track-\(id)"
        case .search: "search"
        case .share: "share"
        case .downloads: "downloads"
        case .storage: "storage"
        case .availableMaps: "available-maps"
        case .settings: "settings"
        case .backup: "backup"
        case let .about(section): "about-\(section ?? "")"
        case .whatsNew: "whats-new"
        }
    }
}

/// What a drawing tool draws (Android T1 / T1b).
enum DrawKind: Equatable {
    /// A new track, or the track of this id being edited.
    case track(Int64?)
    /// A new route, or the route of this id being edited.
    case route(Int64?)
}

/// The tool panels that float above the bottom bar (Android §2.3): one at a time.
enum Tool: Equatable {
    case measure
    case draw(DrawKind)
    /// Imprimer à l'échelle (T3), or the pages along the track of this id (T3b).
    case print(alongTrack: Int64?)
    /// Enregistrer une zone (T5), or the band around the track or route of this reference (P30.2).
    case zone(around: ItemRefKey?)
}

/// A library item named in a tool, without depending on the library types.
struct ItemRefKey: Equatable, Hashable {
    enum Kind: String { case track, route, waypoint }

    let kind: Kind
    let id: Int64
}
