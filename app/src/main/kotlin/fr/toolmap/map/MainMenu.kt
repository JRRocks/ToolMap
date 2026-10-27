package fr.toolmap.map

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/*
 * The main menu and the bottom bar, laid out as in AlpineQuest (P11.6, ADR-034): a bar of five buttons at the bottom
 * left (menu, maps, tracks and waypoints, position, compass), zoom buttons at the bottom right, and a panel that opens
 * above the bar. Since P14.10 the panel is in the style of the « Nouveau waypoint » dialog: four tiles for the frequent
 * commands, a tinted block that says which maps are shown (it opens « Cartes et calques », where the favourites are:
 * the « Espace de travail » section that repeated them is gone, R2), then the rows, « Quitter » last. The settings are
 * not repeated in it: « Réglages… » is the way to them (P11.11, ADR-039).
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
    /** The favourite that the maps shown match, else « Pile personnalisée ». */
    val displayName: String,
    /** The names of the maps shown, top first; empty when none is. */
    val displayLayers: List<String>,
    val measureAllowed: Boolean,
    val printAllowed: Boolean,
    /** The controls are hidden: the first command gives them back instead of hiding them. */
    val controlsHidden: Boolean = false,
    /** « Diagnostic des tuiles », a tool for developers: shown in debug builds only (N4). */
    val diagnostics: Boolean = false,
)

private enum class MenuPage { MAIN, MORE }

/**
 * The panel of the main menu, opened from the bar. It has at most [maxHeight] and scrolls. The « ? » of the header
 * opens the help, « Plus… » the less used commands, on a page of their own with a way back. [displayPreview] draws the
 * thumbnails of the maps shown, at the start of the block that names them.
 */
@Composable
fun MainMenuPanel(
    state: MainMenuState,
    onAction: (MenuAction) -> Unit,
    onCoordinates: (Boolean) -> Unit,
    maxHeight: Dp,
    onNightMode: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    displayPreview: @Composable () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(MenuPage.MAIN) }
    BarPanel(
        maxHeight = maxHeight,
        modifier = modifier,
        header = {
            if (page == MenuPage.MAIN) {
                MenuHeader(onHelp = { onAction(MenuAction.ABOUT) })
            } else {
                PanelBand(
                    title = stringResource(R.string.menu_more_title),
                    leading = {
                        IconButton(
                            onClick = { page = MenuPage.MAIN },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.toolMap.onShell),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.menu_back),
                            )
                        }
                    },
                )
            }
        },
    ) {
        if (page == MenuPage.MAIN) {
            ActionTiles {
                if (state.controlsHidden) {
                    ActionTile(MenuIcons.Fullscreen, stringResource(R.string.menu_tile_show)) {
                        onAction(MenuAction.HIDE)
                    }
                } else {
                    ActionTile(MenuIcons.Fullscreen, stringResource(R.string.menu_tile_hide)) {
                        onAction(MenuAction.HIDE)
                    }
                }
                ActionTile(Icons.Filled.Lock, stringResource(R.string.menu_tile_lock)) { onAction(MenuAction.LOCK) }
                ActionTile(MenuIcons.Print, stringResource(R.string.menu_tile_print), enabled = state.printAllowed) {
                    onAction(MenuAction.PRINT)
                }
                ActionTile(
                    MenuIcons.Measure,
                    stringResource(R.string.menu_tile_measure),
                    enabled = state.measureAllowed,
                ) {
                    onAction(MenuAction.MEASURE)
                }
            }
            // What is shown, and the way to change it: the favourites are in « Cartes et calques » (R2).
            StateCard(
                overline = stringResource(R.string.menu_display_overline),
                title = state.displayName,
                subtitle =
                    state.displayLayers
                        .joinToString(" + ")
                        .ifEmpty { stringResource(R.string.menu_display_none) },
                leading = displayPreview,
                trailing = { PanelChevron() },
                onClick = { onAction(MenuAction.MAPS) },
            )
            MenuRow(
                stringResource(R.string.menu_info_coordinates),
                icon = SettingsIcons.Dashboard,
                subtitle = stringResource(R.string.menu_info_coordinates_sub),
                trailing = { PanelSwitch(checked = state.showCoordinates) },
            ) {
                onCoordinates(!state.showCoordinates)
            }
            // The intensity is set in Réglages ▸ Affichage (F14, variant B).
            MenuRow(
                stringResource(R.string.menu_night_mode),
                icon = SettingsIcons.Bedtime,
                subtitle = stringResource(R.string.menu_night_mode_sub, state.nightPercent),
                trailing = { PanelSwitch(checked = state.nightMode) },
            ) {
                onNightMode(!state.nightMode)
            }
            MenuRow(
                stringResource(R.string.menu_settings),
                icon = Icons.Filled.Settings,
                trailing = { PanelChevron() },
            ) {
                onAction(MenuAction.SETTINGS)
            }
            MenuRow(
                stringResource(R.string.menu_more),
                icon = Icons.Filled.MoreVert,
                subtitle = stringResource(R.string.menu_more_sub),
                trailing = { PanelChevron() },
            ) {
                page = MenuPage.MORE
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))
            // Leaving is the least used command, and the one not to touch by mistake: a text button, last.
            TextButton(
                onClick = { onAction(MenuAction.EXIT) },
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            ) {
                Icon(SettingsIcons.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.menu_exit), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            // What AlpineQuest keeps under « More… »: what is new and the report of an error (P16.3). The help is the
            // « ? » of the header, and the backup is in the settings (ADR-039: no second way to the same screen).
            MenuRow(stringResource(R.string.menu_whats_new), icon = Icons.Filled.Info) {
                onAction(MenuAction.WHATS_NEW)
            }
            MenuRow(stringResource(R.string.menu_report_error), icon = Icons.Filled.Warning) {
                onAction(MenuAction.REPORT_ERROR)
            }
            // Import, downloads and storage are in the maps panel (P11.11); a zone is saved from there only (P14.3).
            if (state.diagnostics) {
                MenuRow(stringResource(R.string.menu_diagnostics), icon = MenuIcons.Gauge) {
                    onAction(MenuAction.DIAGNOSTICS)
                }
            }
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

/** The bar folded into its logo, while the controls are hidden: it is the way back to them. */
@Composable
fun MenuLogoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = BarShape, color = barColor(), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        LogoButton(onClick = onClick)
    }
}

/** Shown in place of the bar while the screen is locked. A long press unlocks it, so that a pocket does not. */
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
        shape = BarShape,
        color = barColor(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onUnlock() }) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
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

@Composable
private fun LogoButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            MenuIcons.Mountain,
            contentDescription = stringResource(R.string.menu),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun barColor(): Color {
    val opaque = LocalAppSettings.current.canvas.menusOpaque
    return MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (opaque) 1f else BAR_ALPHA)
}

private val BarShape = RoundedCornerShape(24.dp)
private const val BAR_ALPHA = 0.92f

/** The height of the items of the bottom bar, the system bar under it not counted. */
private val NAV_HEIGHT = 76.dp

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
