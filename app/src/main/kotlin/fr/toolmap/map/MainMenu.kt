package fr.toolmap.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.BuildConfig
import fr.toolmap.R
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.theme.toolMap

/*
 * The main menu and the bottom bar, laid out as in AlpineQuest (P11.6, ADR-034): a bar at the bottom (menu, maps,
 * tracks and waypoints, position), zoom buttons at the bottom right, and a panel that opens above the bar. Since P29.2
 * (« Portée », docs/previews/design-02-menu.html, variant B) the panel rises from the bar: the four tiles of the
 * frequent commands are its fixed foot, next to the thumb; above them come the night mode, the information on screen,
 * the block of the maps shown and « Réglages… »; at the top, far from the finger, the folded « Plus » row and
 * « Quitter » (ADR-075). The settings are not repeated in it: « Réglages… » is the way to them (P11.11, ADR-039).
 */

/** Commands of the main menu; the screen runs them and closes the menu. */
enum class MenuAction {
    HIDE,
    LOCK,
    PRINT,
    SETTINGS,
    EXIT,
    ABOUT,
    SEARCH,
    DRAW_TRACK,
    DRAW_ROUTE,
    MEASURE,
    WAYPOINT,
    SHARE,
    DIAGNOSTICS,
    WHATS_NEW,
    REPORT_ERROR,
    MAPS,
    IMPORT_TRACKS,
}

/** What the menu shows, read from the screen each time it is composed. */
data class MainMenuState(
    val showCoordinates: Boolean,
    /** « Mode nuit » (F14): the maps darkened by [nightPercent]. */
    val nightMode: Boolean = false,
    val nightPercent: Int = 60,
    /** The favourite that the maps shown match, if one does. */
    val favorite: String? = null,
    /** The names of the maps shown, top first; empty when none is. */
    val displayLayers: List<String>,
    val measureAllowed: Boolean,
    val printAllowed: Boolean,
    /** « Diagnostic des tuiles », a tool for developers: shown in debug builds only (N4). */
    val diagnostics: Boolean = false,
)

/**
 * The panel of the main menu, opened from the bar. It has at most [maxHeight]: the band and the tiles stay, the rows
 * between them scroll. The « ? » of the band opens the help. « Plus » unfolds in place (Back folds it before it closes
 * the menu). [displayPreview] draws the thumbnails of the maps shown, at the start of the block that names them.
 */
@Composable
fun MainMenuPanel(
    state: MainMenuState,
    onAction: (MenuAction) -> Unit,
    onCoordinates: (Boolean) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    onNightMode: (Boolean) -> Unit = {},
    displayPreview: @Composable () -> Unit = {},
) {
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    // Registered after the screen's own handler, so that it comes first: the row folds, then the menu closes.
    BackHandler(enabled = moreOpen) { moreOpen = false }
    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = { MenuHeader(onHelp = { onAction(MenuAction.ABOUT) }) },
        footer = { MenuTiles(state, onAction) },
    ) {
        MoreRow(open = moreOpen, onClick = { moreOpen = !moreOpen })
        if (moreOpen) {
            // What AlpineQuest keeps under « More… »: what is new and the report of an error (P16.3). They open a
            // dialog, so no chevron. The help is the « ? » of the band, and the backup is in the settings (ADR-039).
            MenuRow(
                stringResource(R.string.menu_whats_new),
                icon = MenuIcons.Campaign,
                subtitle = stringResource(R.string.menu_whats_new_sub),
                indented = true,
            ) {
                onAction(MenuAction.WHATS_NEW)
            }
            MenuRow(
                stringResource(R.string.menu_report_error),
                icon = MenuIcons.BugReport,
                subtitle = stringResource(R.string.menu_report_error_sub),
                indented = true,
            ) {
                onAction(MenuAction.REPORT_ERROR)
            }
            if (state.diagnostics) {
                MenuRow(
                    stringResource(R.string.menu_diagnostics),
                    icon = MenuIcons.Gauge,
                    subtitle = stringResource(R.string.menu_diagnostics_sub),
                    indented = true,
                    trailing = { PanelChevron() },
                ) {
                    onAction(MenuAction.DIAGNOSTICS)
                }
            }
        }
        // Leaving is the least used command, and the one not to touch by mistake: at the top, far from the thumb.
        MenuRow(
            stringResource(R.string.menu_exit),
            icon = SettingsIcons.ExitToApp,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            minHeight = SHORT_ROW,
        ) {
            onAction(MenuAction.EXIT)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        MenuRow(
            stringResource(R.string.menu_settings),
            icon = Icons.Filled.Settings,
            trailing = { PanelChevron() },
        ) {
            onAction(MenuAction.SETTINGS)
        }
        // What is shown, and the way to change it: the favourites are in « Cartes et calques » (R2).
        DisplayCard(state, displayPreview) { onAction(MenuAction.MAPS) }
        MenuRow(
            stringResource(R.string.menu_info_coordinates),
            icon = SettingsIcons.Dashboard,
            subtitle = stringResource(R.string.menu_info_coordinates_sub),
            switchOn = state.showCoordinates,
        ) {
            onCoordinates(!state.showCoordinates)
        }
        // The intensity is set in Réglages ▸ Affichage (F14, variant B); the line says what the switch does or did.
        MenuRow(
            stringResource(R.string.menu_night_mode),
            icon = SettingsIcons.Bedtime,
            subtitle =
                stringResource(
                    if (state.nightMode) R.string.menu_night_mode_sub else R.string.menu_night_mode_sub_off,
                    state.nightPercent,
                ),
            switchOn = state.nightMode,
        ) {
            onNightMode(!state.nightMode)
        }
    }
}

/** The head of the main menu, on the petrol band: the logo, the name and version of the app, the « ? » of the help. */
@Composable
private fun MenuHeader(onHelp: () -> Unit) {
    PanelBand(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.menu_version, BuildConfig.VERSION_NAME),
        leading = {
            Icon(
                MenuIcons.Mountain,
                contentDescription = null,
                tint = MaterialTheme.toolMap.onShell,
                modifier = Modifier.padding(start = 10.dp).size(28.dp),
            )
        },
    ) {
        IconButton(onClick = onHelp) {
            Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
        }
    }
}

/** The foot of the menu, which does not scroll: the four tiles, always at the same place over the bar. */
@Composable
private fun MenuTiles(
    state: MainMenuState,
    onAction: (MenuAction) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
            ActionTiles {
                ActionTile(MenuIcons.Fullscreen, stringResource(R.string.menu_tile_hide), minHeight = FOOT_TILE) {
                    onAction(MenuAction.HIDE)
                }
                ActionTile(Icons.Filled.Lock, stringResource(R.string.menu_tile_lock), minHeight = FOOT_TILE) {
                    onAction(MenuAction.LOCK)
                }
                ActionTile(
                    MenuIcons.Print,
                    stringResource(R.string.menu_tile_print),
                    enabled = state.printAllowed,
                    minHeight = FOOT_TILE,
                ) {
                    onAction(MenuAction.PRINT)
                }
                ActionTile(
                    MenuIcons.Measure,
                    stringResource(R.string.menu_tile_measure),
                    enabled = state.measureAllowed,
                    minHeight = FOOT_TILE,
                ) {
                    onAction(MenuAction.MEASURE)
                }
            }
        }
    }
}

/** The « Plus » row: folded, its line says what it holds; unfolded, the rows come under it, in the menu. */
@Composable
private fun MoreRow(
    open: Boolean,
    onClick: () -> Unit,
) {
    MenuRow(
        stringResource(R.string.menu_more_title),
        icon = MenuIcons.MoreHoriz,
        subtitle = if (open) null else stringResource(R.string.menu_more_sub),
        trailing = { PanelExpand(open) },
        minHeight = if (open) SHORT_ROW else 56.dp,
        clickLabel = stringResource(if (open) R.string.menu_more_fold else R.string.menu_more_unfold),
        stateText = stringResource(if (open) R.string.menu_more_shown else R.string.menu_more_hidden),
        onClick = onClick,
    )
}

/** The arrow at the end of a row that unfolds in place. */
@Composable
private fun PanelExpand(open: Boolean) {
    Icon(
        if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * The block of the maps shown (P29.2): the favourite they match, with the maps on the grey line (« Cartes affichées ·
 * Pentes + Plan IGN »); without a favourite, the maps themselves, and « Carte affichée · sans favori ».
 */
@Composable
private fun DisplayCard(
    state: MainMenuState,
    preview: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val maps = state.displayLayers.joinToString(" + ")
    val overline =
        stringResource(
            if (state.displayLayers.size >
                1
            ) {
                R.string.menu_display_overline_many
            } else {
                R.string.menu_display_overline
            },
        )
    StateCard(
        title = state.favorite ?: maps.ifEmpty { stringResource(R.string.menu_display_none) },
        overline = if (state.displayLayers.isEmpty()) null else overline,
        subtitle =
            if (state.favorite !=
                null
            ) {
                maps.ifEmpty { null }
            } else {
                stringResource(R.string.menu_display_no_favorite)
            },
        leading = preview,
        trailing = { PanelChevron() },
        onClick = onClick,
    )
}

/**
 * The bar at the bottom, the width of the screen, on the petrol of the shell (P29.0, « Crête »): menu, maps, tracks
 * and waypoints, position, each named under its icon. The item of the open panel stands on a light pill. The position
 * item is coloured by how the map follows the position, and a long press changes it, as in AlpineQuest where a long
 * press runs the primary action of a category. The compass stands over the zoom buttons since P29.0. The colour goes
 * [bottomBleed] lower than the items, under the margin of the map and the system bar.
 */
@Composable
fun MapBottomBar(
    locationMode: LocationMode,
    tracksOpen: Boolean,
    positionOpen: Boolean,
    onMenu: () -> Unit,
    onMaps: () -> Unit,
    onTracks: () -> Unit,
    onPosition: () -> Unit,
    onPositionLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    mapsOpen: Boolean = false,
    menuOpen: Boolean = false,
    bottomBleed: Dp = 0.dp,
) {
    val tm = MaterialTheme.toolMap
    val (locateTint, locateOnPill) =
        when (locationMode) {
            LocationMode.FREE -> tm.onShell to tm.onNavIndicator
            LocationMode.FOLLOW -> tm.navIndicator to tm.onNavIndicator
            LocationMode.FOLLOW_COMPASS -> FOLLOW_COMPASS_TINT to FOLLOW_COMPASS_ON_PILL
        }
    Row(
        modifier
            .fillMaxWidth()
            // Solid whatever « Menus opaques » says: the shell carries the names (preview of P29.0).
            .background(tm.shell)
            // The bar is not a way through to the map under it.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(bottom = bottomBleed)
            .height(NAV_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(MenuIcons.Mountain, R.string.menu, R.string.menu, selected = menuOpen, onClick = onMenu)
        // A map, as AlpineQuest (P16.7); the panel keeps its title « Cartes et calques ».
        NavItem(MapsIcons.Map, R.string.maps_open, R.string.maps_open, selected = mapsOpen, onClick = onMaps)
        NavItem(MenuIcons.Flag, R.string.bar_tracks, R.string.menu_tracks, selected = tracksOpen, onClick = onTracks)
        NavItem(
            MenuIcons.MyLocation,
            R.string.bar_position,
            R.string.locate_me,
            selected = positionOpen,
            tint = locateTint,
            tintOnPill = locateOnPill,
            onLongClick = onPositionLongPress,
            longClickLabel = stringResource(R.string.position_long_press),
            onClick = onPosition,
        )
    }
}

/**
 * An item of the bottom bar: its icon, on the light pill while its panel is open, over its [label]. The item is read
 * out by its [description] (the longer name the scripts and the help use), the label being only shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.NavItem(
    icon: ImageVector,
    label: Int,
    description: Int,
    selected: Boolean,
    tint: Color = MaterialTheme.toolMap.onShell,
    tintOnPill: Color = MaterialTheme.toolMap.onNavIndicator,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick: () -> Unit,
) {
    val tm = MaterialTheme.toolMap
    val spoken = stringResource(description)
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
                role = Role.Tab,
            ).semantics {
                contentDescription = spoken
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(CircleShape)
                .background(if (selected) tm.navIndicator else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) tintOnPill else tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = tm.onShell,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * The bar folded into its logo, while the controls are hidden: it is the way back to them. A white disc like the zoom
 * and the compass (P29.1, P29.2).
 */
@Composable
fun MenuLogoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapRoundButton(onClick = onClick, modifier = modifier) {
        Icon(MenuIcons.Mountain, contentDescription = stringResource(R.string.menu))
    }
}

/**
 * Shown in place of the bar while the screen is locked, in the shape of the information chips (P29.2): 12 dp corners,
 * the panel colour, a light shadow. A long press unlocks it, so that a pocket does not.
 */
@Composable
fun LockedPill(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlockLabel = stringResource(R.string.lock_action)
    Surface(
        modifier =
            modifier.semantics {
                onLongClick(label = unlockLabel) {
                    onUnlock()
                    true
                }
            },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.toolMap.panel,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onUnlock() }) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.lock_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The + and − buttons of the bottom right corner, two white discs (P29.1, « Pastilles »): one zoom level each. */
@Composable
fun MapZoomButtons(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapRoundButton(onClick = onZoomIn) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zoom_in))
        }
        MapRoundButton(onClick = onZoomOut) {
            Icon(MenuIcons.Remove, contentDescription = stringResource(R.string.zoom_out))
        }
    }
}

/** The height of the items of the bottom bar, the system bar under it not counted. */
private val NAV_HEIGHT = 76.dp

/** The rows of a single line (« Quitter », « Plus » unfolded), and the tiles of the menu's foot. */
private val SHORT_ROW = 48.dp
private val FOOT_TILE = 76.dp

/** The position item while the map turns with the compass: light orange on the bar, brown on the pill. */
private val FOLLOW_COMPASS_TINT = Color(0xFFF0B784)
private val FOLLOW_COMPASS_ON_PILL = Color(0xFF7A4512)

/**
 * Icons that `material-icons-core` does not ship, drawn from the Material path data on a 24 x 24 grid, as [MapsIcons]
 * does. They are tinted by the `Icon` that shows them.
 */
object MenuIcons {
    /** The logo of the bar: a mountain. */
    val Mountain: ImageVector by lazy { icon("Mountain", "M2,20L9,8L13,14L16,10L22,20Z") }

    val Flag: ImageVector by lazy {
        icon("Flag", "M14.4,6L14,4L5,4L5,21L7,21L7,14L12.6,14L13,16L20,16L20,6Z")
    }

    val MyLocation: ImageVector by lazy {
        icon(
            "MyLocation",
            "M12,8c-2.21,0-4,1.79-4,4s1.79,4,4,4,4-1.79,4-4-1.79-4-4-4zM20.94," +
                "11c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06" +
                "C6.83,3.52,3.52,6.83,3.06,11H1v2h2.06c.46,4.17,3.77,7.48,7.94,7.94V23h2v-2.06c4.17-.46," +
                "7.48-3.77,7.94-7.94H23v-2h-2.06z" +
                "M12,19c-3.87,0-7-3.13-7-7s3.13-7,7-7,7,3.13,7,7-3.13,7-7,7z",
        )
    }

    val Fullscreen: ImageVector by lazy {
        icon(
            "Fullscreen",
            "M7,14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z",
        )
    }

    val Print: ImageVector by lazy {
        icon(
            "Print",
            "M19,8H5c-1.66,0-3,1.34-3,3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3,11H8v-4h8v4zm3-7c-.55," +
                "0-1-.45-1-1s.45-1,1-1,1,.45,1,1-.45,1-1,1zm-1-9H6v4h12V3z",
        )
    }

    val Help: ImageVector by lazy {
        icon(
            "Help",
            "M11,18h2v-2h-2v2zm1-16C6.48,2,2,6.48,2,12s4.48,10,10,10,10-4.48,10-10S17.52,2,12,2zm0,18c-4.41," +
                "0-8-3.59-8-8" +
                "s3.59-8,8-8,8,3.59,8,8-3.59,8-8,8zm0-14c-2.21,0-4,1.79-4,4h2c0-1.1.9-2,2-2s2,.9,2,2c0,2-3," +
                "1.75-3,5h2c0-2.25,3-2.5,3-5" +
                ",0-2.21-1.79-4-4-4z",
        )
    }

    /** « Ouvrir avec » of the centre point: an arrow leaving a frame. */
    val OpenWith: ImageVector by lazy {
        icon(
            "OpenWith",
            "M10.09,15.59L11.5,17l5,-5l-5,-5l-1.41,1.41L12.67,11H3v2h9.67l-2.58,2.59z" +
                "M19,3H5c-1.11,0,-2,0.9,-2,2v4h2V5h14v14H5v-4H3v4" +
                "c0,1.1,0.89,2,2,2h14c1.1,0,2,-0.9,2,-2V5c0,-1.1,-0.9,-2,-2,-2z",
        )
    }

    val Remove: ImageVector by lazy { icon("Remove", "M19,13H5v-2h14v2z") }

    /** A loudspeaker that announces: « Nouveautés » (P29.2). */
    val Campaign: ImageVector by lazy {
        icon(
            "Campaign",
            "M18,11v2h4v-2h-4zm-2,6.61c.96,.71,2.21,1.65,3.2,2.39.4,-.53.8,-1.07,1.2,-1.6" +
                "-.99,-.74,-2.24,-1.68,-3.2,-2.4-.4,.54-.8,1.08-1.2,1.61z" +
                "M20.4,5.6c-.4,-.53-.8,-1.07-1.2,-1.6-.99,.74-2.24,1.68-3.2,2.4.4,.53.8,1.07,1.2,1.6" +
                ".96,-.72,2.21,-1.65,3.2,-2.4z" +
                "M4,9c-1.1,0-2,.9-2,2v2c0,1.1.9,2,2,2h1v4h2v-4h1l5,3V6L8,9H4z" +
                "m11.5,3c0,-1.33-.58,-2.53-1.5,-3.35v6.69c.92,-.81,1.5,-2.01,1.5,-3.34z",
        )
    }

    /** An insect: « Signaler une erreur » (P29.2), instead of the warning triangle. */
    val BugReport: ImageVector by lazy {
        icon(
            "BugReport",
            "M20,8h-2.81a5.985,5.985,0,0,0-1.82,-1.96L17,4.41,15.59,3l-2.17,2.17C12.96,5.06,12.49,5,12,5" +
                "c-.49,0-.96,.06-1.41,.17L8.41,3,7,4.41l1.62,1.63C7.88,6.55,7.26,7.22,6.81,8H4v2h2.09" +
                "c-.05,.33-.09,.66-.09,1v1H4v2h2v1c0,.34.04,.67.09,1H4v2h2.81c1.04,1.79,2.97,3,5.19,3" +
                "s4.15,-1.21,5.19,-3H20v-2h-2.09c.05,-.33.09,-.66.09,-1v-1h2v-2h-2v-1c0,-.34-.04,-.67-.09,-1H20V8z" +
                "m-6,8h-4v-2h4v2zm0,-4h-4v-2h4v2z",
        )
    }

    /** Three dots in a row: « Plus » at the start of its row (⋮ belongs to the menu buttons). */
    val MoreHoriz: ImageVector by lazy {
        icon(
            "MoreHoriz",
            "M6,10c-1.1,0-2,.9-2,2s.9,2,2,2,2,-.9,2,-2-.9,-2-2,-2zm12,0c-1.1,0-2,.9-2,2s.9,2,2,2,2,-.9,2,-2" +
                "-.9,-2-2,-2zm-6,0c-1.1,0-2,.9-2,2s.9,2,2,2,2,-.9,2,-2-.9,-2-2,-2z",
        )
    }

    /** A pin within waves: the position shared with a group (the exit dialog). */
    val ShareLocation: ImageVector by lazy {
        icon(
            "ShareLocation",
            "M13.02,19.93v2.02c2.01,-.2,3.84,-1,5.32,-2.21l-1.42,-1.43a7.941,7.941,0,0,1-3.9,1.62z" +
                "M4.03,12c0,-4.05,3.03,-7.41,6.95,-7.93V2.05C5.95,2.58,2.03,6.84,2.03,12c0,5.16,3.92,9.42,8.95,9.95" +
                "v-2.02c-3.92,-.52-6.95,-3.88-6.95,-7.93z" +
                "m15.92,-1h2.02c-.2,-2.01-1,-3.84-2.21,-5.32l-1.43,1.43c.86,1.1,1.44,2.43,1.62,3.89z" +
                "m-1.61,-6.74a9.981,9.981,0,0,0-5.32,-2.21v2.02c1.46,.18,2.79,.76,3.9,1.62l1.42,-1.43z" +
                "m-.01,12.64,1.43,1.42A9.949,9.949,0,0,0,21.97,13h-2.02a7.941,7.941,0,0,1-1.62,3.9z" +
                "M16,11.1C16,8.61,14.1,7,12,7s-4,1.61-4,4.1c0,1.66,1.33,3.63,4,5.9,2.67,-2.27,4,-4.24,4,-5.9z" +
                "m-4,.9a1.071,1.071,0,0,1,0,-2.14A1.071,1.071,0,0,1,12,12z",
        )
    }

    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }

    val Measure: ImageVector by lazy {
        icon(
            "Measure",
            "M21,6H3c-1.1,0-2,.9-2,2v8c0,1.1.9,2,2,2h18c1.1,0,2-.9,2-2V8c0-1.1-.9-2-2-2zm0," +
                "10H3V8h2v4h2V8h2v4h2V8h2v4h2V8h2v4" +
                "h2V8h2v4h2V8h2v8z",
        )
    }

    /** A target: concentric rings and a dot, for « Définir comme cible » of the centre point's menu. */
    val Target: ImageVector by lazy {
        icon(
            "Target",
            "M12,2C6.48,2,2,6.48,2,12s4.48,10,10,10,10-4.48,10-10S17.52,2,12,2zm0,18c-4.41,0-8-3.59-8-8" +
                "s3.59-8,8-8,8,3.59,8,8-3.59,8-8,8zM12,8c-2.21,0-4,1.79-4,4s1.79,4,4,4,4-1.79,4-4-1.79-4-4-4z",
        )
    }

    /** A dial with its needle: the barometer of the top left corner. */
    val Gauge: ImageVector by lazy {
        icon(
            "Gauge",
            "M20.38,8.57l-1.23,1.85a8,8,0,0,1-.22,7.58H5.07A8,8,0,0,1,15.58,6.85l1.85-1.23A10,10,0,0,0,3.35,19" +
                "a2,2,0,0,0,1.72,1h13.85a2,2,0,0,0,1.74-1,10,10,0,0,0-.27-10.44zm-9.79,6.84a2,2,0,0,0,2.83,0" +
                "l5.66-8.49-8.49,5.66a2,2,0,0,0,0,2.83z",
        )
    }

    /** A pin with a « + »: what creates a waypoint (the tracks panel, the centre point). */
    val AddLocation: ImageVector by lazy {
        icon(
            "AddLocation",
            "M12,2C8.14,2,5,5.14,5,9c0,5.25,7,13,7,13s7,-7.75,7,-13c0,-3.86,-3.14,-7,-7,-7z" +
                "M16,10h-3v3h-2v-3H8V8h3V5h2v3h3v2z",
        )
    }

    /** A line through three points: drawing a track. */
    val Polyline: ImageVector by lazy {
        icon(
            "Polyline",
            "M15,16v1.26l-6,-3v-3.17L11.7,8H16V2h-6v4.9L7.3,10H3v6h5l7,3.5V22h6v-6H15z" +
                "M12,4h2v2h-2V4zM7,14H5v-2h2V14zM19,18v2h-2v-2H19z",
        )
    }

    /** A road sign with a turning arrow: drawing a route. */
    val Directions: ImageVector by lazy {
        icon(
            "Directions",
            "M21.71,11.29l-9,-9c-0.39,-0.39,-1.02,-0.39,-1.41,0l-9,9c-0.39,0.39,-0.39,1.02,0,1.41l9,9" +
                "c0.39,0.39,1.02,0.39,1.41,0l9,-9c0.39,-0.38,0.39,-1.01,0,-1.41z" +
                "M14,14.5V12h-4v3H8v-4c0,-0.55,0.45,-1,1,-1h5V7.5l3.5,3.5,-3.5,3.5z",
        )
    }

    /** A filled disc: the track recorder. */
    val Record: ImageVector by lazy {
        icon("Record", "M12,4C7.58,4,4,7.58,4,12s3.58,8,8,8,8,-3.58,8,-8,-3.58,-8,-8,-8z")
    }

    /** A track drawn by a pin, as AlpineQuest's « Track recorder » (P16.7): a line, then a filled pin with its hole. */
    val RecordTrack: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "RecordTrack",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData = addPathNodes("M3.5,20.5c2.6,-0.4 3.6,-4.4 6.4,-4.6 2.7,-0.2 3.4,2.6 6.1,1.6"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ).addPath(
                pathData =
                    addPathNodes(
                        "M17.5,2.5a3.7,3.7 0,0 0,-3.7 3.7c0,2.7 3.7,6.6 3.7,6.6s3.7,-3.9 3.7,-6.6" +
                            "a3.7,3.7 0,0 0,-3.7 -3.7z" +
                            "m0,5.1a1.4,1.4 0,1 1,0 -2.8a1.4,1.4 0,0 1,0 2.8z",
                    ),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd,
            ).build()
    }

    /** Two sheets: copying the coordinates. */
    val Copy: ImageVector by lazy {
        icon(
            "Copy",
            "M16,1H4c-1.1,0,-2,0.9,-2,2v14h2V3h12V1zM19,5H8c-1.1,0,-2,0.9,-2,2v14c0,1.1,0.9,2,2,2h11" +
                "c1.1,0,2,-0.9,2,-2V7c0,-1.1,-0.9,-2,-2,-2zM19,21H8V7h11v14z",
        )
    }

    /** A code framed by the corners of a viewfinder: joining a group by scanning its code. */
    val QrCodeScanner: ImageVector by lazy {
        icon(
            "QrCodeScanner",
            "M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5L11,5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13L11,13z" +
                "M17.5,6.5v3h-3v-3H17.5M19,5h-6v6h6V5L19,5zM13,13h1.5v1.5H13V13zM14.5,14.5H16V16h-1.5V14.5z" +
                "M16,13h1.5v1.5H16V13zM13,16h1.5v1.5H13V16zM14.5,17.5H16V19h-1.5V17.5zM16,16h1.5v1.5H16V16z" +
                "M17.5,14.5H19V16h-1.5V14.5zM17.5,17.5H19V19h-1.5V17.5zM22,7h-2V4h-3V2h5V7zM22,22v-5h-2v3h-3v2H22z" +
                "M2,22h5v-2H4v-3H2V22zM2,2v5h2V4h3V2H2z",
        )
    }

    /** Two links of a chain: joining a group by a link. */
    val Link: ImageVector by lazy {
        icon(
            "Link",
            "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7" +
                "c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1" +
                "s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z",
        )
    }

    /** A sheet with an arrow going in: importing a file. */
    val UploadFile: ImageVector by lazy {
        icon(
            "UploadFile",
            "M14,2H6C4.9,2,4.01,2.9,4.01,4L4,20c0,1.1,0.89,2,1.99,2H18c1.1,0,2,-0.9,2,-2V8L14,2z" +
                "M18,20H6V4h7v5h5V20zM8,15.01l1.41,1.41L11,14.84V19h2v-4.16l1.59,1.59L16,15.01L12.01,11L8,15.01z",
        )
    }

    /** A crossed cloud: the offline mode. */
    val CloudOff: ImageVector by lazy {
        icon(
            "CloudOff",
            "M19.35,10.04C18.67,6.59,15.64,4,12,4c-1.48,0,-2.85,0.43,-4.01,1.17l1.46,1.46C10.21,6.23,11.08,6,12,6" +
                "c3.04,0,5.5,2.46,5.5,5.5v0.5H19c1.66,0,3,1.34,3,3c0,1.13,-0.64,2.11,-1.56,2.62l1.45,1.45" +
                "C23.16,18.16,24,16.68,24,15c0,-2.64,-2.05,-4.78,-4.65,-4.96z" +
                "M3,5.27l2.75,2.74C2.56,8.15,0,10.77,0,14c0,3.31,2.69,6,6,6h11.73l2,2L21,20.73L4.27,4L3,5.27z" +
                "M7.73,10l8,8H6c-2.21,0,-4,-1.79,-4,-4s1.79,-4,4,-4h1.73z",
        )
    }

    /** A crossed eye: hide from the map. */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12,7c2.76,0,5,2.24,5,5c0,0.65,-0.13,1.26,-0.36,1.83l2.92,2.92c1.51,-1.26,2.7,-2.89,3.43,-4.75" +
                "c-1.73,-4.39,-6,-7.5,-11,-7.5c-1.4,0,-2.74,0.25,-3.98,0.7l2.16,2.16C10.74,7.13,11.35,7,12,7z" +
                "M2,4.27l2.28,2.28l0.46,0.46C3.08,8.3,1.78,10.02,1,12" +
                "c1.73,4.39,6,7.5,11,7.5c1.55,0,3.03,-0.3,4.38,-0.84" +
                "l0.42,0.42L19.73,22L21,20.73L3.27,3L2,4.27z" +
                "M7.53,9.8l1.55,1.55c-0.05,0.21,-0.08,0.43,-0.08,0.65c0,1.66,1.34,3,3,3c0.22,0,0.44,-0.03,0.65,-0.08" +
                "l1.55,1.55c-0.67,0.33,-1.41,0.53,-2.2,0.53c-2.76,0,-5,-2.24,-5,-5c0,-0.79,0.2,-1.53,0.53,-2.2z" +
                "M11.84,9.02l3.15,3.15l0.02,-0.16c0,-1.66,-1.34,-3,-3,-3l-0.17,0.01z",
        )
    }

    /** A bell: the proximity alert of a waypoint. */
    val Bell: ImageVector by lazy {
        icon(
            "Bell",
            "M12,22c1.1,0,2,-0.9,2,-2h-4c0,1.1,0.89,2,2,2z" +
                "M18,16v-5c0,-3.07,-1.64,-5.64,-4.5,-6.32V4c0,-0.83,-0.67,-1.5,-1.5,-1.5s-1.5,0.67,-1.5,1.5v0.68" +
                "C7.63,5.36,6,7.92,6,11v5l-2,2v1h16v-1l-2,-2z",
        )
    }

    /** A sheet with an arrow going down: saving a file on the phone (« Exporter » of the track sheet, F8). */
    val FileSave: ImageVector by lazy {
        icon(
            "FileSave",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6z" +
                "M18,20H6V4h7v5h5v11z" +
                "M16,14.99l-1.41,-1.41L13,15.16V11h-2v4.16l-1.59,-1.59L8,14.99 11.99,19z",
        )
    }

    /** Two arrows, left and right: « Format suivant » of the position box (F6). */
    val SwapHoriz: ImageVector by lazy {
        icon("SwapHoriz", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")
    }

    /** Three lines, long to short: the sort chip of the explorer (F7). */
    val Sort: ImageVector by lazy { icon("Sort", "M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z") }

    /** A folder with an arrow: « Déplacer vers… » of the selection (F7). */
    val DriveFileMove: ImageVector by lazy {
        icon(
            "DriveFileMove",
            "M20,6h-8l-2,-2H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6z" +
                "M12,17v-3H8v-4h4V7l5,5L12,17z",
        )
    }

    /** An empty ring: an item not picked in the selection of the explorer (F7). */
    val RadioUnchecked: ImageVector by lazy {
        icon(
            "RadioUnchecked",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
        )
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
