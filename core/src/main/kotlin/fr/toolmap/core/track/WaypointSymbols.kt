package fr.toolmap.core.track

/**
 * The symbol of a waypoint: one of the icons of [WaypointCatalog]. The stored value (and the GPX `sym`) is the icon's
 * [WaypointCatalog.Icon.key]; the old Garmin names written by earlier versions and by other apps still resolve, to the
 * equivalent icon. Each icon is drawn in one colour, the one of its category.
 */
object WaypointSymbols {
    const val DEFAULT_KEY = "Waypoint"
    const val DEFAULT_ID = "waypoint"
    const val DEFAULT_COLOR: Int = 0xFFE53935.toInt()

    private val byId: Map<String, WaypointCatalog.Icon> =
        WaypointCatalog.CATEGORIES.flatMap { it.icons }.associateBy { it.id }
    private val byKey: Map<String, WaypointCatalog.Icon> =
        byId.values.associateBy { it.key.lowercase() } +
            WaypointCatalog.ALIASES.mapNotNull { (alias, id) -> byId[id]?.let { alias to it } }

    /** The icon a symbol name from any file stands for; unknown or missing names get the default waypoint. */
    fun iconOf(sym: String?): WaypointCatalog.Icon =
        sym?.let { byKey[it.trim().lowercase()] } ?: byId.getValue(DEFAULT_ID)

    /** The icon with this id, if any. */
    fun iconById(id: String): WaypointCatalog.Icon? = byId[id]

    /** Marker colour of a symbol name from any file; unknown or missing names get the default colour. */
    fun colorOf(sym: String?): Int = iconOf(sym).colorArgb

    /** The symbol name to show as selected: files may spell it differently from the list. */
    fun canonical(sym: String?): String = iconOf(sym).key
}
