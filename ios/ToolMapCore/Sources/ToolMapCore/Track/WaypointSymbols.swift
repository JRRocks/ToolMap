// Port of core/src/main/kotlin/fr/toolmap/core/track/WaypointSymbols.kt.

/// The symbol of a waypoint: one of the icons of `WaypointCatalog`. The stored value (and the GPX `sym`) is the icon's
/// `WaypointCatalog.Icon.key`; the old Garmin names written by earlier versions and by other apps still resolve, to the
/// equivalent icon. Each icon is drawn in one colour, the one of its category.
public enum WaypointSymbols {
    public static let defaultKey = "Waypoint"
    public static let defaultId = "waypoint"
    public static let defaultColor = Int32(bitPattern: 0xFFE5_3935)

    private static let byId: [String: WaypointCatalog.Icon] = {
        var map: [String: WaypointCatalog.Icon] = [:]
        for category in WaypointCatalog.categories {
            for icon in category.icons {
                map[icon.id] = icon
            }
        }
        return map
    }()

    private static let byKey: [String: WaypointCatalog.Icon] = {
        var map: [String: WaypointCatalog.Icon] = [:]
        for category in WaypointCatalog.categories {
            for icon in category.icons {
                if let current = WaypointSymbols.byId[icon.id] { map[current.key.lowercased()] = current }
            }
        }
        for (alias, id) in WaypointCatalog.aliases {
            if let icon = WaypointSymbols.byId[id] { map[alias] = icon }
        }
        return map
    }()

    private static let defaultIcon = WaypointSymbols.byId[WaypointSymbols.defaultId] ?? WaypointCatalog.Icon(
        id: WaypointSymbols.defaultId,
        key: WaypointSymbols.defaultKey,
        category: "placemark",
        colorArgb: WaypointSymbols.defaultColor
    )

    /// The icon a symbol name from any file stands for; unknown or missing names get the default waypoint.
    public static func iconOf(_ sym: String?) -> WaypointCatalog.Icon {
        guard let sym else { return defaultIcon }
        return byKey[kotlinTrim(sym).lowercased()] ?? defaultIcon
    }

    /// The icon with this id, if any.
    public static func iconById(_ id: String) -> WaypointCatalog.Icon? { byId[id] }

    /// Marker colour of a symbol name from any file; unknown or missing names get the default colour.
    public static func colorOf(_ sym: String?) -> Int32 { iconOf(sym).colorArgb }

    /// The symbol name to show as selected: files may spell it differently from the list.
    public static func canonical(_ sym: String?) -> String { iconOf(sym).key }
}
