package fr.toolmap.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.geo.CoordinateFormat
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.TileSharpness
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.settings.AltitudeMode
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.BearingUnit
import fr.toolmap.core.settings.CourseDisplay
import fr.toolmap.core.settings.GridDensity
import fr.toolmap.core.settings.GridStyle
import fr.toolmap.core.settings.HeadingDisplay
import fr.toolmap.core.settings.InfoBoxBackground
import fr.toolmap.core.settings.InfoBoxSize
import fr.toolmap.core.settings.LongPressAction
import fr.toolmap.core.settings.MapCanvasSettings
import fr.toolmap.core.settings.MapDisplaySettings
import fr.toolmap.core.settings.NorthReference
import fr.toolmap.core.settings.PlacemarkSettings
import fr.toolmap.core.settings.PlacemarkSize
import fr.toolmap.core.settings.PressureUnit
import fr.toolmap.core.settings.RoutingNoData
import fr.toolmap.core.settings.SlopeUnit
import fr.toolmap.core.settings.StorageLocation
import fr.toolmap.core.settings.ThemeMode
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.core.settings.ZoomStep
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.map.MenuIcons
import fr.toolmap.map.pointApps
import fr.toolmap.storage.TileStorageState
import fr.toolmap.storage.TileStorages
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.TextButton
import java.io.File
import java.text.NumberFormat
import kotlin.math.roundToInt

/** A position the examples of the coordinate formats are written for (the summit of the Grand Veymont). */
private const val EXAMPLE_LAT = 44.8386
private const val EXAMPLE_LON = 5.5108

/** Colours offered for the placemarks (AlpineQuest's colour picker, in a few steps). */
private val PALETTE: List<Int> =
    listOf(
        0xFFE53935,
        0xFFFB8C00,
        0xFFFDD835,
        0xFF43A047,
        0xFF00897B,
        0xFF1E88E5,
        0xFF3949AB,
        0xFF8E24AA,
        0xFFD81B60,
        0xFF6D4C41,
        0xFF757575,
        0xFF212121,
    ).map { it.toInt() }

/** The categories of the settings that are open, and the way to open or fold one. */
private class OpenCategories(
    val open: List<Int>,
    val toggle: (Int) -> Unit,
)

private val LocalOpenCategories = compositionLocalOf { OpenCategories(emptyList()) {} }

/**
 * The settings, in nine categories (P11.10, P11.11): units and coordinates, display, map, grid and information boxes,
 * placemarks and tracks, position and heading, recorder, alerts, storage and backup. Each category is a line with its
 * name and an arrow, closed at first so that the screen stays short. Each option has its icon and shows its value: a
 * tap opens the choice, a switch toggles at once. Every change is saved at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenBackup: (() -> Unit)? = null,
    onOpenMultiZoom: (() -> Unit)? = null,
    onMoveTiles: (File) -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var moreOpen by remember { mutableStateOf(false) }
    // The categories left open, in the order they were opened: Back folds the last one before it leaves the screen.
    var openCategories by rememberSaveable { mutableStateOf(listOf<Int>()) }
    BackHandler(enabled = openCategories.isNotEmpty()) { openCategories = openCategories.dropLast(1) }
    val categoryState =
        remember(openCategories) {
            OpenCategories(openCategories) { title ->
                openCategories = if (title in openCategories) openCategories - title else openCategories + title
            }
        }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings_more))
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_reset)) },
                                onClick = {
                                    moreOpen = false
                                    vm.update { AppSettings() }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalOpenCategories provides categoryState) {
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
            ) {
                UnitsCategory(settings, vm)
                DisplayCategory(settings, vm)
                MapCategory(settings, vm, onOpenMultiZoom)
                OverlayCategory(settings, vm)
                PlacemarkCategory(settings, vm)
                PositionCategory(settings, vm)
                RecorderCategory(settings, vm)
                AlertsCategory(settings, vm)
                DataCategory(onOpenBackup, onMoveTiles)
            }
        }
    }
}

/** Units and coordinates: the formats of the positions, distances, areas, bearing, slope, pressure and north. */
@Composable
private fun UnitsCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    SettingsCategory(R.string.settings_section_units, SettingsIcons.Straighten) {
        ChoiceRow(
            icon = SettingsIcons.Place,
            title = R.string.settings_coordinates,
            options = CoordinateSystem.entries,
            selected = settings.coordinates,
            label = { stringResource(it.labelRes()) },
            value = { "${stringResource(it.labelRes())} · ${example(it)}" },
            detail = { example(it) },
            onSelect = { v -> vm.update { it.copy(coordinates = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.PinDrop,
            title = R.string.settings_secondary_coordinates,
            options = listOf<CoordinateSystem?>(null) + CoordinateSystem.entries,
            selected = settings.secondaryCoordinates,
            label = {
                if (it == null) stringResource(R.string.settings_none) else stringResource(it.labelRes())
            },
            detail = { it?.let { system -> example(system) } },
            onSelect = { v -> vm.update { it.copy(secondaryCoordinates = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.Straighten,
            title = R.string.settings_distance_unit,
            options = UnitSystem.entries,
            selected = settings.units,
            label = { stringResource(it.distanceLabelRes()) },
            onSelect = { v -> vm.update { it.copy(units = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.SquareFoot,
            title = R.string.settings_area_unit,
            options = UnitSystem.entries,
            selected = settings.areaUnits,
            label = { stringResource(it.areaLabelRes()) },
            onSelect = { v -> vm.update { it.copy(areaUnits = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.Rotate90,
            title = R.string.settings_bearing_unit,
            options = BearingUnit.entries,
            selected = settings.bearing,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(bearing = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.Terrain,
            title = R.string.settings_slope_unit,
            options = SlopeUnit.entries,
            selected = settings.slope,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(slope = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.Speed,
            title = R.string.settings_pressure_unit,
            options = PressureUnit.entries,
            selected = settings.pressure,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(pressure = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.North,
            title = R.string.settings_north,
            options = NorthReference.entries,
            selected = settings.north,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(north = v) } },
        )
    }
}

/** Display: the theme, the colours and gloves, the menus, what stays on the screen (status bar, lock, keep awake). */
@Composable
private fun DisplayCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val canvas = settings.canvas
    SettingsCategory(R.string.settings_section_display, SettingsIcons.Brightness6) {
        ChoiceRow(
            icon = SettingsIcons.Brightness6,
            title = R.string.settings_theme,
            options = ThemeMode.entries,
            selected = settings.theme,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(theme = v) } },
        )
        NightRow(settings.maps.nightPercent) { p -> vm.update { it.copy(maps = it.maps.copy(nightPercent = p)) } }
        SwitchRow(
            icon = SettingsIcons.Palette,
            title = R.string.settings_dynamic_color,
            checked = settings.wallpaperColors,
        ) { on -> vm.update { it.copy(wallpaperColors = on) } }
        SwitchRow(
            icon = SettingsIcons.PanTool,
            title = R.string.settings_gloves,
            checked = settings.gloves,
            hint = R.string.settings_gloves_hint,
        ) { on -> vm.update { it.copy(gloves = on) } }
        DialogRow(
            icon = SettingsIcons.MenuOpen,
            title = R.string.settings_menus,
            subtitle =
                listOf(
                    stringResource(
                        if (canvas.menusOpaque) {
                            R.string.settings_menus_opaque
                        } else {
                            R.string.settings_menus_translucent
                        },
                    ),
                    stringResource(
                        if (canvas.menusLeftHanded) {
                            R.string.settings_menus_buttons_left
                        } else {
                            R.string.settings_menus_buttons_right
                        },
                    ),
                ).joinToString(" · "),
        ) {
            DialogCheck(R.string.settings_menus_opaque, canvas.menusOpaque) { on ->
                vm.update { it.copy(canvas = it.canvas.copy(menusOpaque = on)) }
            }
            DialogCheck(R.string.settings_menus_left, canvas.menusLeftHanded) { on ->
                vm.update { it.copy(canvas = it.canvas.copy(menusLeftHanded = on)) }
            }
        }
        SwitchRow(
            icon = SettingsIcons.Fullscreen,
            title = R.string.settings_hide_status_bar,
            checked = settings.hideStatusBar,
            hint = R.string.settings_hide_status_bar_hint,
        ) { on -> vm.update { it.copy(hideStatusBar = on) } }
        SwitchRow(
            icon = SettingsIcons.SmartButton,
            title = R.string.settings_hide_nav_bar,
            checked = settings.hideNavigationBar,
            hint = R.string.settings_hide_nav_bar_hint,
        ) { on -> vm.update { it.copy(hideNavigationBar = on) } }
        SwitchRow(
            icon = SettingsIcons.ScreenLockPortrait,
            title = R.string.settings_lock_screen,
            checked = settings.showOverLockScreen,
            hint = R.string.settings_lock_screen_hint,
        ) { on -> vm.update { it.copy(showOverLockScreen = on) } }
        SwitchRow(
            icon = SettingsIcons.BrightnessHigh,
            title = R.string.settings_keep_screen_on,
            checked = settings.keepScreenOn,
            hint = R.string.settings_keep_screen_on_hint,
        ) { on -> vm.update { it.copy(keepScreenOn = on) } }
        SwitchRow(
            icon = SettingsIcons.ExitToApp,
            title = R.string.settings_confirm_exit,
            checked = settings.confirmExit,
            hint = R.string.settings_confirm_exit_hint,
        ) { on -> vm.update { it.copy(confirmExit = on) } }
    }
}

/** The map itself: its sharpness, the zooms it borrows, its zoom controls and the gestures on it. */
@Composable
private fun MapCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpenMultiZoom: (() -> Unit)?,
) {
    val density = LocalDensity.current.density.toDouble()
    val canvas = settings.canvas
    val context = LocalContext.current
    val apps = remember(context) { pointApps(context) }
    SettingsCategory(R.string.settings_section_canvas, SettingsIcons.Layers) {
        val sharpness = TileSharpness.choices(density)
        val currentMapping = settings.maps.zoomMapping(density)
        // A value saved on another screen may not be offered here: the choice that draws the same is selected.
        val selectedSharpness =
            sharpness.firstOrNull { it == settings.maps.tileMagnification }
                ?: sharpness.firstOrNull { ZoomMapping.forScreen(density, it) == currentMapping }
                ?: TileSharpness.SHARP
        ChoiceRow(
            icon = SettingsIcons.AspectRatio,
            title = R.string.settings_map_resolution,
            options = sharpness,
            selected = selectedSharpness,
            label = { sharpnessLabel(it, density) },
            detail = { sharpnessDetail(it, density) },
            onSelect = { m -> vm.update { it.copy(maps = it.maps.copy(tileMagnification = m)) } },
        )
        OverscalingRow(settings, vm)
        if (onOpenMultiZoom != null) {
            SettingRow(
                icon = SettingsIcons.StackedChart,
                title = stringResource(R.string.settings_multizoom),
                subtitle = stringResource(R.string.settings_multizoom_hint),
                onClick = onOpenMultiZoom,
            )
        }
        Text(
            stringResource(R.string.settings_shared_tiles),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DialogRow(
            icon = SettingsIcons.ZoomIn,
            title = R.string.settings_zoom_controls,
            subtitle =
                "${stringResource(
                    if (canvas.zoomButtons) R.string.settings_zoom_shown else R.string.settings_zoom_hidden,
                )} · " +
                    stringResource(canvas.zoomStep.labelRes()),
        ) {
            DialogCheck(R.string.settings_zoom_buttons, canvas.zoomButtons) { on ->
                vm.update { it.copy(canvas = it.canvas.copy(zoomButtons = on)) }
            }
            Text(stringResource(R.string.settings_zoom_step), style = MaterialTheme.typography.titleSmall)
            ChoiceList(
                options = ZoomStep.entries,
                selected = canvas.zoomStep,
                label = { stringResource(it.labelRes()) },
                detail = null,
            ) { v -> vm.update { it.copy(canvas = it.canvas.copy(zoomStep = v)) } }
        }
        ChoiceRow(
            icon = SettingsIcons.TouchApp,
            title = R.string.settings_long_press,
            options = LongPressAction.entries,
            selected = canvas.longPress,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(canvas = it.canvas.copy(longPress = v)) } },
        )
        ChoiceRow(
            icon = MenuIcons.OpenWith,
            title = R.string.settings_point_app,
            options = listOf<String?>(null) + apps.map { it.packageName },
            selected = settings.pointApp,
            label = { packageName ->
                val app = apps.firstOrNull { it.packageName == packageName }
                app?.label ?: packageName ?: stringResource(R.string.settings_point_app_ask)
            },
            onSelect = { v -> vm.update { it.copy(pointApp = v) } },
        )
        SwitchRow(
            icon = SettingsIcons.Pinch,
            title = R.string.settings_pinch,
            checked = canvas.pinchZoom,
            hint = R.string.settings_pinch_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(pinchZoom = on)) } }
        SwitchRow(
            icon = SettingsIcons.ScreenRotation,
            title = R.string.settings_rotation,
            checked = canvas.manualRotation,
            hint = R.string.settings_rotation_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(manualRotation = on)) } }
        SwitchRow(
            icon = SettingsIcons.Rotate3d,
            title = R.string.settings_perspective,
            checked = canvas.perspective,
            hint = R.string.settings_perspective_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(perspective = on)) } }
        SwitchRow(
            icon = SettingsIcons.Hiking,
            title = R.string.settings_explorer,
            checked = canvas.explorerAuto,
            hint = R.string.settings_explorer_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(explorerAuto = on)) } }
    }
}

/** The grid of the screen and the information boxes of the top left corner. */
@Composable
private fun OverlayCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val canvas = settings.canvas
    SettingsCategory(R.string.settings_section_overlay, SettingsIcons.GridOn) {
        ChoiceRow(
            icon = SettingsIcons.GridOn,
            title = R.string.settings_grid,
            options = GridKind.entries - GridKind.WGS84_DECIMAL,
            selected = settings.screenGrid,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(screenGrid = v) } },
        )
        DialogRow(
            icon = SettingsIcons.BorderStyle,
            title = R.string.settings_grid_style,
            subtitle =
                "${stringResource(canvas.gridDensity.labelRes())} · ${stringResource(canvas.gridStyle.labelRes())}",
        ) {
            Text(stringResource(R.string.settings_grid_style), style = MaterialTheme.typography.titleSmall)
            ChoiceList(
                options = GridDensity.entries,
                selected = canvas.gridDensity,
                label = { stringResource(it.labelRes()) },
                detail = null,
            ) { v -> vm.update { it.copy(canvas = it.canvas.copy(gridDensity = v)) } }
            ChoiceList(
                options = GridStyle.entries,
                selected = canvas.gridStyle,
                label = { stringResource(it.labelRes()) },
                detail = null,
            ) { v -> vm.update { it.copy(canvas = it.canvas.copy(gridStyle = v)) } }
        }
        DialogRow(
            icon = SettingsIcons.ViewQuilt,
            title = R.string.settings_info_boxes,
            subtitle =
                "${stringResource(
                    canvas.infoBoxSize.labelRes(),
                )} · ${stringResource(canvas.infoBoxBackground.labelRes())}",
        ) {
            Text(stringResource(R.string.settings_info_size), style = MaterialTheme.typography.titleSmall)
            ChoiceList(
                options = InfoBoxSize.entries,
                selected = canvas.infoBoxSize,
                label = { stringResource(it.labelRes()) },
                detail = null,
            ) { v -> vm.update { it.copy(canvas = it.canvas.copy(infoBoxSize = v)) } }
            Text(stringResource(R.string.settings_info_background), style = MaterialTheme.typography.titleSmall)
            ChoiceList(
                options = InfoBoxBackground.entries,
                selected = canvas.infoBoxBackground,
                label = { stringResource(it.labelRes()) },
                detail = null,
            ) { v -> vm.update { it.copy(canvas = it.canvas.copy(infoBoxBackground = v)) } }
        }
    }
}

/** The markers and the tracks: size, colours, arrows and outline, names, borders and shadows. */
@Composable
private fun PlacemarkCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    SettingsCategory(R.string.settings_section_placemarks, SettingsIcons.Route) {
        ChoiceRow(
            icon = SettingsIcons.FormatSize,
            title = R.string.settings_placemark_size,
            options = PlacemarkSize.entries,
            selected = settings.placemarks.size,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(placemarks = it.placemarks.copy(size = v)) } },
        )
        ColorRow(
            icon = SettingsIcons.FormatColorFill,
            title = R.string.settings_default_color,
            hint = R.string.settings_default_color_hint,
            color = settings.placemarks.defaultColor,
        ) { c -> vm.update { it.copy(placemarks = it.placemarks.copy(defaultColor = c)) } }
        ColorRow(
            icon = SettingsIcons.Adjust,
            title = R.string.settings_target_color,
            hint = R.string.settings_target_color_hint,
            color = settings.placemarks.targetColor,
        ) { c -> vm.update { it.copy(placemarks = it.placemarks.copy(targetColor = c)) } }
        TrackStyleRow(settings.placemarks, vm)
        RoutingNoDataRow(settings.placemarks.routingNoData) { v ->
            vm.update { it.copy(placemarks = it.placemarks.copy(routingNoData = v)) }
        }
        SwitchRow(
            icon = SettingsIcons.Label,
            title = R.string.settings_labels,
            checked = settings.placemarks.labels,
            hint = R.string.settings_labels_hint,
        ) { on -> vm.update { it.copy(placemarks = it.placemarks.copy(labels = on)) } }
        SwitchRow(
            icon = SettingsIcons.BorderStyle,
            title = R.string.settings_borders,
            checked = settings.placemarks.borders,
            hint = R.string.settings_borders_hint,
        ) { on -> vm.update { it.copy(placemarks = it.placemarks.copy(borders = on)) } }
        SwitchRow(
            icon = SettingsIcons.BlurOn,
            title = R.string.settings_shadows,
            checked = settings.placemarks.shadows,
            hint = R.string.settings_shadows_hint,
        ) { on -> vm.update { it.copy(placemarks = it.placemarks.copy(shadows = on)) } }
    }
}

/** Positioning and heading: how the course and the altitude are shown, how the map follows, what the trail shows. */
@Composable
private fun PositionCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val position = settings.positioning
    SettingsCategory(R.string.settings_section_positioning, SettingsIcons.MyLocation) {
        ChoiceRow(
            icon = SettingsIcons.NearMe,
            title = R.string.settings_course,
            options = CourseDisplay.entries,
            selected = position.course,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(positioning = it.positioning.copy(course = v)) } },
        )
        SwitchRow(
            icon = SettingsIcons.MyLocation,
            title = R.string.settings_auto_relock,
            checked = position.autoRelock,
            hint = R.string.settings_auto_relock_hint,
        ) { on -> vm.update { it.copy(positioning = it.positioning.copy(autoRelock = on)) } }
        SwitchRow(
            icon = SettingsIcons.Timeline,
            title = R.string.settings_trail,
            checked = position.showTrail,
            hint = R.string.settings_trail_hint,
        ) { on -> vm.update { it.copy(positioning = it.positioning.copy(showTrail = on)) } }
        SwitchRow(
            icon = SettingsIcons.BatterySaver,
            title = R.string.settings_disable_background,
            checked = position.disableInBackground,
            hint = R.string.settings_disable_background_hint,
        ) { on -> vm.update { it.copy(positioning = it.positioning.copy(disableInBackground = on)) } }
        SwitchRow(
            icon = SettingsIcons.Public,
            title = R.string.settings_egm96,
            checked = position.egm96,
            hint = R.string.settings_egm96_hint,
        ) { on -> vm.update { it.copy(positioning = it.positioning.copy(egm96 = on)) } }
        ChoiceRow(
            icon = SettingsIcons.Height,
            title = R.string.settings_altitude,
            options = AltitudeMode.entries,
            selected = settings.altitude,
            label = { stringResource(it.labelRes()) },
            detail = { stringResource(it.hintRes()) },
            onSelect = { v -> vm.update { it.copy(altitude = v) } },
        )
        ChoiceRow(
            icon = SettingsIcons.Explore,
            title = R.string.settings_heading,
            options = HeadingDisplay.entries,
            selected = settings.heading,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(heading = v) } },
        )
    }
}

/** The track recorder: warnings, the activity asked at the start, the statistics of a segment, the dashboard. */
@Composable
private fun RecorderCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val recorder = settings.recorder
    SettingsCategory(R.string.settings_section_recorder, SettingsIcons.DirectionsRun) {
        SwitchRow(
            icon = SettingsIcons.GpsOff,
            title = R.string.settings_lost_signal,
            checked = recorder.lostSignalWarning,
            hint = R.string.settings_lost_signal_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(lostSignalWarning = on)) } }
        SwitchRow(
            icon = SettingsIcons.DirectionsRun,
            title = R.string.settings_ask_activity,
            checked = recorder.askActivity,
            hint = R.string.settings_ask_activity_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(askActivity = on)) } }
        SwitchRow(
            icon = SettingsIcons.Replay,
            title = R.string.settings_reset_stats,
            checked = recorder.resetStatsOnSegment,
            hint = R.string.settings_reset_stats_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(resetStatsOnSegment = on)) } }
        SwitchRow(
            icon = SettingsIcons.Dashboard,
            title = R.string.settings_dashboard,
            checked = recorder.dashboardMode,
            hint = R.string.settings_dashboard_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(dashboardMode = on)) } }
    }
}

/** ToolMap's own alerts: proximity of the waypoints and departure from a track, with how they are announced. */
@Composable
private fun AlertsCategory(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val alerts = settings.alerts
    SettingsCategory(R.string.settings_alerts, SettingsIcons.NotificationsActive) {
        SwitchRow(
            icon = SettingsIcons.NotificationsActive,
            title = R.string.settings_alert_proximity,
            checked = alerts.proximityEnabled,
            hint = R.string.settings_alert_proximity_hint,
        ) { on -> vm.update { it.copy(alerts = it.alerts.copy(proximityEnabled = on)) } }
        if (alerts.proximityEnabled) {
            SliderRow(
                icon = SettingsIcons.Radar,
                title = R.string.settings_alert_radius,
                meters = alerts.proximityRadiusMeters,
                range = RADIUS_RANGE,
                step = RADIUS_STEP,
            ) { m -> vm.update { it.copy(alerts = it.alerts.copy(proximityRadiusMeters = m)) } }
        }
        SwitchRow(
            icon = SettingsIcons.AltRoute,
            title = R.string.settings_alert_off_track,
            checked = alerts.offTrackEnabled,
            hint = R.string.settings_alert_off_track_hint,
        ) { on -> vm.update { it.copy(alerts = it.alerts.copy(offTrackEnabled = on)) } }
        if (alerts.offTrackEnabled) {
            SliderRow(
                icon = SettingsIcons.SocialDistance,
                title = R.string.settings_alert_off_track_distance,
                meters = alerts.offTrackMeters,
                range = OFF_TRACK_RANGE,
                step = OFF_TRACK_STEP,
            ) { m -> vm.update { it.copy(alerts = it.alerts.copy(offTrackMeters = m)) } }
        }
        SwitchRow(
            icon = SettingsIcons.Vibration,
            title = R.string.settings_alert_vibrate,
            checked = alerts.vibrate,
        ) { on -> vm.update { it.copy(alerts = it.alerts.copy(vibrate = on)) } }
        SwitchRow(
            icon = SettingsIcons.VolumeUp,
            title = R.string.settings_alert_sound,
            checked = alerts.sound,
        ) { on -> vm.update { it.copy(alerts = it.alerts.copy(sound = on)) } }
    }
}

/**
 * Where the offline maps are kept, moved between the phone and a removable card (ADR-042), and the backup and restore of
 * the data. A move is asked for after a confirmation: [onMoveTiles] closes the app, which then runs it.
 */
@Composable
private fun DataCategory(
    onOpenBackup: (() -> Unit)?,
    onMoveTiles: (File) -> Unit,
) {
    val context = LocalContext.current
    val storage = remember { TileStorages.state(context) }
    var asked by remember { mutableStateOf<StorageLocation?>(null) }
    SettingsCategory(R.string.settings_section_data, SettingsIcons.Storage) {
        ChoiceRow(
            icon = SettingsIcons.Storage,
            title = R.string.settings_storage,
            options = StorageLocation.entries,
            selected = storage.inUse,
            label = { stringResource(it.labelRes()) },
            detail = { storageDetail(it, storage) },
            enabled = { choice -> choice == storage.inUse || storage.moveTarget(choice) != null },
            onSelect = { choice -> if (choice != storage.inUse) asked = choice },
        )
        if (onOpenBackup != null) {
            SettingRow(
                icon = SettingsIcons.Backup,
                title = stringResource(R.string.settings_backup),
                onClick = onOpenBackup,
            )
        }
    }
    asked?.let { choice ->
        val target = storage.moveTarget(choice)
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(R.string.settings_storage_move_title)) },
            text = {
                val destination =
                    stringResource(
                        if (choice == StorageLocation.EXTERNAL) {
                            R.string.settings_storage_dest_card
                        } else {
                            R.string.settings_storage_dest_phone
                        },
                    )
                Text(
                    stringResource(
                        R.string.settings_storage_move_text,
                        Formatter.formatShortFileSize(context, storage.tilesBytes),
                        destination,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        asked = null
                        target?.let(onMoveTiles)
                    },
                ) { Text(stringResource(R.string.settings_storage_move_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** What an option of the storage choice says under it: why it cannot be picked, or what picking it does. */
@Composable
private fun storageDetail(
    choice: StorageLocation,
    storage: TileStorageState,
): String? =
    when {
        choice == storage.inUse && !storage.activeUsable -> stringResource(R.string.settings_storage_card_absent)

        choice == storage.inUse -> null

        storage.moveTarget(
            choice,
        ) != null && choice == StorageLocation.EXTERNAL -> stringResource(R.string.settings_storage_note)

        storage.moveTarget(choice) != null -> stringResource(R.string.settings_storage_note_back)

        choice == StorageLocation.EXTERNAL -> stringResource(R.string.settings_storage_no_card)

        else -> stringResource(R.string.settings_storage_card_absent)
    }

/**
 * A category of settings: a line with its icon, its name and an arrow. A tap opens or closes the settings under it.
 * Closed at first, so that the screen stays short; the choice is kept while the screen is open.
 */
@Composable
private fun SettingsCategory(
    @StringRes title: Int,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    val categories = LocalOpenCategories.current
    val expanded = title in categories.open
    val gloves = LocalAppSettings.current.gloves
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = if (gloves) 72.dp else 56.dp)
                    .clickable(role = Role.Button) { categories.toggle(title) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(20.dp))
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** The zooms a map borrows from its neighbours (AlpineQuest's `default_overscaling_levels`, ADR-031). */
@Composable
private fun OverscalingRow(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val levels = settings.maps.overscalingLevels.coerceIn(0, ScalingPolicy.MAX_LEVELS)
    DialogRow(
        icon = SettingsIcons.Layers,
        title = R.string.settings_overscaling_row,
        subtitle =
            if (levels == 0) {
                stringResource(R.string.settings_overscaling_off)
            } else {
                pluralStringResource(R.plurals.settings_overscaling, levels, levels)
            },
    ) {
        var value by remember(levels) { mutableFloatStateOf(levels.toFloat()) }
        val shown = value.roundToInt()
        Text(
            if (shown == 0) {
                stringResource(R.string.settings_overscaling_off)
            } else {
                pluralStringResource(R.plurals.settings_overscaling, shown, shown)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                val chosen = value.roundToInt().coerceIn(0, ScalingPolicy.MAX_LEVELS)
                vm.update { it.copy(maps = it.maps.copy(overscalingLevels = chosen)) }
            },
            valueRange = 0f..ScalingPolicy.MAX_LEVELS.toFloat(),
            steps = ScalingPolicy.MAX_LEVELS - 1,
        )
        Text(
            pluralStringResource(
                R.plurals.settings_overscaling_hint,
                ScalingPolicy.MAX_ENLARGE,
                ScalingPolicy.MAX_ENLARGE,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Flèches de sens and liseré of the tracks, as one row (AlpineQuest's « Style of tracks »). */
@Composable
private fun TrackStyleRow(
    placemarks: PlacemarkSettings,
    vm: SettingsViewModel,
) {
    val subtitle =
        listOfNotNull(
            stringResource(R.string.settings_track_arrows).takeIf { placemarks.trackArrows },
            stringResource(R.string.settings_track_contour).takeIf { placemarks.trackContour },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.settings_none) }
    DialogRow(icon = SettingsIcons.Route, title = R.string.settings_track_style, subtitle = subtitle) {
        DialogCheck(R.string.settings_track_arrows, placemarks.trackArrows) { on ->
            vm.update { it.copy(placemarks = it.placemarks.copy(trackArrows = on)) }
        }
        DialogCheck(R.string.settings_track_contour, placemarks.trackContour) { on ->
            vm.update { it.copy(placemarks = it.placemarks.copy(trackContour = on)) }
        }
    }
}

/**
 * What a route does when BRouter cannot compute a leg (P19, screen 7 of `docs/previews/p19-routing.html`): the leg by
 * hand and the routing goes on, or the whole route by hand. The choice applies at the tap.
 */
@Composable
private fun RoutingNoDataRow(
    selected: RoutingNoData,
    onSelect: (RoutingNoData) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_routing_no_data)
    SettingRow(
        icon = SettingsIcons.AltRoute,
        title = title,
        subtitle = stringResource(selected.labelRes()),
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_routing_no_data_ask))
                    ChoiceList(
                        options = RoutingNoData.entries,
                        selected = selected,
                        label = { stringResource(it.labelRes()) },
                        detail = {
                            stringResource(
                                when (it) {
                                    RoutingNoData.LEG_BY_HAND -> R.string.settings_routing_leg_by_hand_detail
                                    RoutingNoData.ALL_BY_HAND -> R.string.settings_routing_all_by_hand_detail
                                },
                            )
                        },
                    ) { choice ->
                        onSelect(choice)
                        open = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun RoutingNoData.labelRes(): Int =
    when (this) {
        RoutingNoData.LEG_BY_HAND -> R.string.settings_routing_leg_by_hand
        RoutingNoData.ALL_BY_HAND -> R.string.settings_routing_all_by_hand
    }

/** One option: an icon, its name, its value underneath and an optional control on the right. */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val gloves = LocalAppSettings.current.gloves
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (gloves) 72.dp else 56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    @StringRes title: Int,
    checked: Boolean,
    @StringRes hint: Int? = null,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        icon = icon,
        title = stringResource(title),
        subtitle = hint?.let { stringResource(it) },
        onClick = { onChange(!checked) },
        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
    )
}

/** A choice: the row shows the value, a tap opens the list of options in a dialog. */
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    value: @Composable (T) -> String = label,
    detail: (@Composable (T) -> String?)? = null,
    enabled: (T) -> Boolean = { true },
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(icon = icon, title = stringResource(title), subtitle = value(selected), onClick = { open = true })
    if (open) {
        SettingsDialog(title = stringResource(title), onDismiss = { open = false }) {
            ChoiceList(options, selected, label, detail, enabled) { choice ->
                onSelect(choice)
                open = false
            }
        }
    }
}

/** An option that opens a dialog with several controls (menus, info boxes, zoom buttons, grids, track style). */
@Composable
private fun DialogRow(
    icon: ImageVector,
    @StringRes title: Int,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(icon = icon, title = stringResource(title), subtitle = subtitle, onClick = { open = true })
    if (open) {
        SettingsDialog(title = stringResource(title), onDismiss = { open = false }, content = content)
    }
}

/**
 * « Mode nuit de la carte » (F14): how much the night mode darkens the maps, on a slider in the row itself. The switch
 * is in the main menu.
 */
@Composable
private fun NightRow(
    percent: Int,
    onChange: (Int) -> Unit,
) {
    val step = MapDisplaySettings.NIGHT_STEP_PERCENT
    val range = MapDisplaySettings.MIN_NIGHT_PERCENT..MapDisplaySettings.MAX_NIGHT_PERCENT
    var value by remember(percent) { mutableFloatStateOf(percent.coerceIn(range).toFloat()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = SettingsIcons.Bedtime,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_night_map), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.settings_night_map_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onChange(((value / step).roundToInt() * step).coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1,
            )
            Text(
                stringResource(R.string.settings_night_intensity, value.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A distance, changed with a slider in a dialog. */
@Composable
private fun SliderRow(
    icon: ImageVector,
    @StringRes title: Int,
    meters: Int,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
) {
    val fmt = LocalFormats.current
    DialogRow(icon = icon, title = title, subtitle = fmt.distance(meters.toDouble())) {
        var value by remember(meters) { mutableFloatStateOf(meters.toFloat()) }
        Text(fmt.distance(value.roundToInt().toDouble()), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(((value / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** A colour, shown as a disc; a tap opens the palette. */
@Composable
private fun ColorRow(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes hint: Int,
    color: Int,
    onChange: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        icon = icon,
        title = stringResource(title),
        subtitle = stringResource(hint),
        onClick = { open = true },
        trailing = {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
    )
    if (open) {
        SettingsDialog(title = stringResource(title), onDismiss = { open = false }) {
            PALETTE.chunked(6).forEach { swatches ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    swatches.forEach { swatch ->
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch))
                                    .clickable {
                                        onChange(swatch)
                                        open = false
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (swatch == color) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
private fun <T> ChoiceList(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    detail: (@Composable (T) -> String?)?,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    options.forEach { option ->
        val available = enabled(option)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .selectable(
                        selected = option == selected,
                        enabled = available,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    ).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == selected, onClick = null, enabled = available)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (available) {
                            Color.Unspecified
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.38f,
                            )
                        },
                )
                detail?.invoke(option)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A check box inside a dialog. */
@Composable
private fun DialogCheck(
    @StringRes title: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable { onChange(!checked) }
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
    }
}

/** The name of a sharpness choice (see [TileSharpness]) on a screen of [density]; shared with the map menu. */
@Composable
internal fun sharpnessLabel(
    choice: Double,
    density: Double,
): String =
    when (choice) {
        TileSharpness.SHARP -> stringResource(R.string.settings_sharpness_sharp)
        TileSharpness.ANDROID_SIZE -> stringResource(R.string.settings_sharpness_android, factor(density))
        else -> stringResource(R.string.settings_sharpness_enlarged, factor(choice))
    }

@Composable
internal fun sharpnessDetail(
    choice: Double,
    density: Double,
): String =
    when (choice) {
        TileSharpness.SHARP -> stringResource(R.string.settings_sharpness_sharp_hint)
        TileSharpness.ANDROID_SIZE -> stringResource(R.string.settings_sharpness_android_hint)
        else -> stringResource(R.string.settings_sharpness_enlarged_hint, factor(choice))
    }

/** "1,5", "2", "2,8" in the language of the phone. */
private fun factor(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)

/** A position in the coordinate format, as the example of the settings (« MGRS 31T GL 27960 49623 »). */
private fun example(system: CoordinateSystem): String = CoordinateFormat.format(system, EXAMPLE_LAT, EXAMPLE_LON).text

private fun UnitSystem.distanceLabelRes(): Int =
    when (this) {
        UnitSystem.METRIC -> R.string.settings_unit_distance_metric
        UnitSystem.IMPERIAL -> R.string.settings_unit_distance_imperial
    }

private fun UnitSystem.areaLabelRes(): Int =
    when (this) {
        UnitSystem.METRIC -> R.string.settings_unit_area_metric
        UnitSystem.IMPERIAL -> R.string.settings_unit_area_imperial
    }

internal fun CoordinateSystem.labelRes(): Int =
    when (this) {
        CoordinateSystem.DD -> R.string.settings_coord_dd
        CoordinateSystem.DDM -> R.string.settings_coord_ddm
        CoordinateSystem.DMS -> R.string.settings_coord_dms
        CoordinateSystem.UTM -> R.string.settings_coord_utm
        CoordinateSystem.MGRS -> R.string.settings_coord_mgrs
        CoordinateSystem.LAMBERT93 -> R.string.settings_coord_lambert
    }

internal fun GridKind.labelRes(): Int =
    when (this) {
        GridKind.NONE -> R.string.settings_grid_none
        GridKind.WGS84, GridKind.WGS84_DECIMAL -> R.string.settings_grid_wgs84
        GridKind.UTM -> R.string.settings_grid_utm
        GridKind.LAMBERT93 -> R.string.settings_grid_lambert
        GridKind.MGRS -> R.string.settings_grid_mgrs
    }

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

private fun AltitudeMode.hintRes(): Int =
    when (this) {
        AltitudeMode.AUTO -> R.string.settings_altitude_auto_hint
        AltitudeMode.GPS -> R.string.settings_altitude_gps_hint
        AltitudeMode.BAROMETER -> R.string.settings_altitude_baro_hint
    }

private fun AltitudeMode.labelRes(): Int =
    when (this) {
        AltitudeMode.AUTO -> R.string.settings_altitude_auto
        AltitudeMode.GPS -> R.string.settings_altitude_gps
        AltitudeMode.BAROMETER -> R.string.settings_altitude_baro
    }

private fun BearingUnit.labelRes(): Int =
    when (this) {
        BearingUnit.DEGREES -> R.string.settings_bearing_degrees
        BearingUnit.MILS -> R.string.settings_bearing_mils
        BearingUnit.GRADIANS -> R.string.settings_bearing_gradians
    }

private fun SlopeUnit.labelRes(): Int =
    when (this) {
        SlopeUnit.PERCENT -> R.string.settings_slope_percent
        SlopeUnit.DEGREES -> R.string.settings_slope_degrees
    }

private fun PressureUnit.labelRes(): Int =
    when (this) {
        PressureUnit.HPA -> R.string.settings_pressure_hpa
        PressureUnit.MMHG -> R.string.settings_pressure_mmhg
        PressureUnit.INHG -> R.string.settings_pressure_inhg
    }

private fun NorthReference.labelRes(): Int =
    when (this) {
        NorthReference.TRUE -> R.string.settings_north_true
        NorthReference.MAGNETIC -> R.string.settings_north_magnetic
        NorthReference.GRID -> R.string.settings_north_grid
    }

private fun StorageLocation.labelRes(): Int =
    when (this) {
        StorageLocation.INTERNAL -> R.string.settings_storage_internal
        StorageLocation.EXTERNAL -> R.string.settings_storage_external
    }

private fun InfoBoxSize.labelRes(): Int =
    when (this) {
        InfoBoxSize.SMALL -> R.string.settings_info_size_small
        InfoBoxSize.NORMAL -> R.string.settings_info_size_normal
        InfoBoxSize.LARGE -> R.string.settings_info_size_large
    }

private fun InfoBoxBackground.labelRes(): Int =
    when (this) {
        InfoBoxBackground.TRANSPARENT -> R.string.settings_info_bg_transparent
        InfoBoxBackground.TRANSLUCENT -> R.string.settings_info_bg_translucent
        InfoBoxBackground.OPAQUE -> R.string.settings_info_bg_opaque
    }

private fun GridDensity.labelRes(): Int =
    when (this) {
        GridDensity.FINE -> R.string.settings_grid_fine
        GridDensity.NORMAL -> R.string.settings_grid_normal
        GridDensity.COARSE -> R.string.settings_grid_coarse
    }

private fun GridStyle.labelRes(): Int =
    when (this) {
        GridStyle.SOLID -> R.string.settings_grid_solid
        GridStyle.DASHED -> R.string.settings_grid_dashed
    }

private fun ZoomStep.labelRes(): Int =
    when (this) {
        ZoomStep.HALF -> R.string.settings_zoom_step_half
        ZoomStep.ONE -> R.string.settings_zoom_step_one
        ZoomStep.TWO -> R.string.settings_zoom_step_two
    }

private fun LongPressAction.labelRes(): Int =
    when (this) {
        LongPressAction.NONE -> R.string.settings_long_none
        LongPressAction.WAYPOINT -> R.string.settings_long_waypoint
        LongPressAction.MEASURE -> R.string.settings_long_measure
        LongPressAction.TARGET -> R.string.settings_long_target
    }

private fun CourseDisplay.labelRes(): Int =
    when (this) {
        CourseDisplay.NONE -> R.string.settings_course_none
        CourseDisplay.ARROW -> R.string.settings_course_arrow
    }

private fun HeadingDisplay.labelRes(): Int =
    when (this) {
        HeadingDisplay.NONE -> R.string.settings_heading_none
        HeadingDisplay.NUMBER -> R.string.settings_heading_number
        HeadingDisplay.ARROW -> R.string.settings_heading_arrow
        HeadingDisplay.BOTH -> R.string.settings_heading_both
    }

private fun PlacemarkSize.labelRes(): Int =
    when (this) {
        PlacemarkSize.SMALL -> R.string.settings_size_small
        PlacemarkSize.NORMAL -> R.string.settings_size_normal
        PlacemarkSize.LARGE -> R.string.settings_size_large
    }

private val RADIUS_RANGE = 20..1000
private const val RADIUS_STEP = 10
private val OFF_TRACK_RANGE = 10..500
private const val OFF_TRACK_STEP = 5
