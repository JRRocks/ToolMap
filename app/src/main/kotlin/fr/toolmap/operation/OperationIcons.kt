package fr.toolmap.operation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons of the « Opération » screens that `material-icons-core` lacks (P39.8): the Material shapes the validated
 * maquettes draw (`docs/previews/p39-groupes/tour2` to `tour4`), with their own crown.
 */
object OperationIcons {
    /** « crown » of the maquettes: the chief of the operation (filled) and its deputy (hollow). */
    val Crown: ImageVector by lazy {
        icon(
            "Crown",
            "M3 18h18v2.5H3zM3.6 16.5 2 6.5l5.6 4.2L12 3.5l4.4 7.2L22 6.5l-1.6 10z",
        )
    }

    /** « star_border »: the post of deputy, free. */
    val StarBorder: ImageVector by lazy {
        icon(
            "StarBorder",
            "m22 9.24-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM" +
                "12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
        )
    }

    /** « key »: the creator. */
    val Key: ImageVector by lazy {
        icon(
            "Key",
            "M21 10h-8.35A5.99 5.99 0 0 0 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6a5.99 5.99 0 0 0 5.65-4H13l2 2 2-2" +
                " 2 2 4-4.04L21 10zM7 15c-1.65 0-3-1.35-3-3s1.35-3 3-3 3 1.35 3 3-1.35 3-3 3z",
        )
    }

    /** « shield »: an administrator. */
    val Shield: ImageVector by lazy {
        icon(
            "Shield",
            "M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z",
        )
    }

    /** « hourglass_empty »: waiting for a group. */
    val Hourglass: ImageVector by lazy {
        icon(
            "Hourglass",
            "M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99-.01-.01H18V2H6zm10 14.5" +
                "V20H8v-3.5l4-4 4 4zm-4-5-4-4V4h8v3.5l-4 4z",
        )
    }

    /** « hourglass_top »: this phone left and waits for its departure to be got (P39.14.5, M4 A, `tour8/img/14`). */
    val HourglassTop: ImageVector by lazy {
        icon("HourglassTop", "M6 2l.01 6L10 12l-3.99 4.01L6 22h12v-6l-4-4 4-3.99V2H6zm10 14.5V20H8v-3.5l4-4 4 4z")
    }

    /** « event »: the day the departure is given up at the latest (M4 A). */
    val Event: ImageVector by lazy {
        icon(
            "Event",
            "M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5" +
                "c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z",
        )
    }

    /** « location_off »: the position of this phone no longer goes (M4 A). */
    val LocationOff: ImageVector by lazy {
        icon(
            "LocationOff",
            "M12 6.5c1.38 0 2.5 1.12 2.5 2.5 0 .74-.33 1.39-.83 1.85l3.63 3.63c.98-1.86 1.7-3.8 1.7-5.48" +
                " 0-3.87-3.13-7-7-7-1.98 0-3.76.83-5.04 2.15l3.19 3.19c.46-.52 1.11-.84 1.85-.84zm4.37 9.6" +
                "l-4.63-4.63-.11-.11L3.27 3 2 4.27l3.18 3.18C5.07 7.95 5 8.47 5 9c0 5.25 7 13 7 13" +
                "s1.67-1.85 3.38-4.35L18.73 21 20 19.73l-3.63-3.63z",
        )
    }

    /** « visibility_off »: no position on the map while waiting. */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39" +
                "-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46A11." +
                "804 11.804 0 0 0 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3." +
                "27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.5" +
                "5 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78 3.15 3.15.02-.16c0" +
                "-1.66-1.34-3-3-3l-.17.01z",
        )
    }

    /** « visibility »: who reads the name of the operation (U9). */
    val Visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12" +
                " 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.3" +
                "4 3-3-1.34-3-3-3z",
        )
    }

    /** « speaker_notes_off »: nothing shared while waiting. */
    val SpeakerNotesOff: ImageVector by lazy {
        icon(
            "SpeakerNotesOff",
            "m10.54 11-.54-.54L7.54 8 6 6.46 2.38 2.84 1.27 1.73 0 3l2.01 2.01L2 22l4-4h9l5.73 5.73L22 22.46 " +
                "17.54 18l-7-7zM8 14H6v-2h2v2zm-2-3V9l2 2H6zm14-9H4.08L10 7.92V6h8v2h-7.92l1 1H18v2h-4.92l6.99 6." +
                "99C21.14 17.95 22 17.08 22 16V4c0-1.1-.9-2-2-2z",
        )
    }

    /** « info »: what an orange box explains. */
    val Info: ImageVector by lazy {
        icon(
            "Info",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
        )
    }

    /** « block »: the operation is full. */
    val Block: ImageVector by lazy {
        icon(
            "Block",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0" +
                " 3.55.63 4.9 1.69L5.69 16.9A7.902 7.902 0 0 1 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1A7.90" +
                "2 7.902 0 0 1 20 12c0 4.42-3.58 8-8 8z",
        )
    }

    /** « person_off »: a group without chief. */
    val PersonOff: ImageVector by lazy {
        icon(
            "PersonOff",
            "M8.65 5.82a3.999 3.999 0 1 1 5.53 5.53L8.65 5.82zM20 17.17c-.02-1.1-.63-2.11-1.61-2.62-.54-.28-1" +
                ".13-.54-1.77-.76L20 17.17zm1.19 4.02L2.81 2.81 1.39 4.22l8.89 8.89c-1.81.23-3.39.79-4.67 1.45A2." +
                "97 2.97 0 0 0 4 17.22V20h13.17l2.61 2.61 1.41-1.42z",
        )
    }

    /** « group_off »: dissolve the operation. */
    val GroupOff: ImageVector by lazy {
        icon(
            "GroupOff",
            "M15 8c0-1.42-.5-2.73-1.33-3.76.42-.14.86-.24 1.33-.24 2.21 0 4 1.79 4 4s-1.79 4-4 4h-.18l-.77-.7" +
                "7c.6-.94.95-2.05.95-3.23zm7.83 12H23v-3c0-2.18-3.58-3.47-6.34-3.87 1.1.75 1.95 1.71 2.23 2.94L22" +
                ".83 20zM7.24 4.41a3.996 3.996 0 0 1 5.35 5.35L7.24 4.41zM9.17 12H9c-2.21 0-4-1.79-4-4v-.17L.69 3" +
                ".51 2.1 2.1l19.8 19.8-1.41 1.41L17 19.83V20H1v-3c0-2.66 5.33-4 8-4 .37 0 .8.03 1.25.08L9.17 12z",
        )
    }

    /** « phone_iphone »: the reminder to the creator. */
    val PhoneIphone: ImageVector by lazy {
        icon(
            "PhoneIphone",
            "M15.5 1h-8A2.5 2.5 0 0 0 5 3.5v17A2.5 2.5 0 0 0 7.5 23h8a2.5 2.5 0 0 0 2.5-2.5v-17A2.5 2.5 0 0 0" +
                " 15.5 1zm-4 21c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm4.5-4H7V4h" +
                "9v14z",
        )
    }

    /** « swap_horiz »: change of group. */
    val SwapHoriz: ImageVector by lazy {
        icon(
            "SwapHoriz",
            "M6.99 11 3 15l3.99 4v-3H14v-2H6.99v-3zM21 9l-3.99-4v3H10v2h7.01v3L21 9z",
        )
    }

    /** « manage_accounts »: « Administrer ». */
    val ManageAccounts: ImageVector by lazy {
        icon(
            "ManageAccounts",
            "M6 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM10.67 13.02c-.22-.01-.44-.02-.67-.02-2.42 0-4.68.67-6.61 1.82" +
                "-.88.52-1.39 1.5-1.39 2.53V20h9.26a6.963 6.963 0 0 1-.59-6.98zM20.75 16c0-.22-.03-.42-.06-.63l1." +
                "14-1.01-1-1.73-1.45.49c-.32-.27-.68-.48-1.08-.63L18 11h-2l-.3 1.49c-.4.15-.76.36-1.08.63l-1.45-." +
                "49-1 1.73 1.14 1.01c-.03.21-.06.41-.06.63s.03.42.06.63l-1.14 1.01 1 1.73 1.45-.49c.32.27.68.48 1" +
                ".08.63L16 21h2l.3-1.49c.4-.15.76-.36 1.08-.63l1.45.49 1-1.73-1.14-1.01c.03-.21.06-.41.06-.63zM17" +
                " 18c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z",
        )
    }

    /** « person_add_disabled »: « Inviter », full. */
    val PersonAddDisabled: ImageVector by lazy {
        icon(
            "PersonAddDisabled",
            "M11 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0zM23 20v-2c0-2.3-4.1-3.7-6.9-3.9l6 5.9h.9zm-11.6-5.5C9.2 15.1 " +
                "7 16.3 7 18v2h9.9l4 4 1.3-1.3-21-20.9L0 3.1l4 4V10H1v2h3v3h2v-3h2.9l2.5 2.5zM6 10v-.9l.9.9H6z",
        )
    }

    /** « account_tree »: the mode « Opération ». */
    val AccountTree: ImageVector by lazy {
        icon(
            "AccountTree",
            "M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z",
        )
    }

    /** « person_remove »: take someone out of the operation. */
    val PersonRemove: ImageVector by lazy {
        icon(
            "PersonRemove",
            "M14 8c0-2.21-1.79-4-4-4S6 5.79 6 8s1.79 4 4 4 4-1.79 4-4zm3 2v2h6v-2h-6zM2 18v2h16v-2c0-2.66-5.3" +
                "3-4-8-4s-8 1.34-8 4z",
        )
    }

    /** « person_add »: « Inviter », « Ajouter des membres ». */
    val PersonAdd: ImageVector by lazy {
        icon(
            "PersonAdd",
            "M15 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-9-2V7H4v3H1v2h3v3h2v-3h3v-2H6zm9 4c" +
                "-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
        )
    }

    /** « group »: the arrival in a group. */
    val Group: ImageVector by lazy {
        icon(
            "Group",
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34" +
                " 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67" +
                "-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5" +
                "z",
        )
    }

    /** « edit »: rename a group. */
    val Edit: ImageVector by lazy {
        icon(
            "Edit",
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 0 0 0-1.41l-2.34-2.34a.996" +
                ".996 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        )
    }

    /** « delete »: delete a group. */
    val Delete: ImageVector by lazy {
        icon(
            "Delete",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
        )
    }

    /** « error »: a name is missing (`tour3/04-entree-nom-etats`). */
    val ErrorMark: ImageVector by lazy {
        icon(
            "ErrorMark",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z",
        )
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = SIZE.dp,
                defaultHeight = SIZE.dp,
                viewportWidth = SIZE,
                viewportHeight = SIZE,
            ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()

    private const val SIZE = 24f
}
