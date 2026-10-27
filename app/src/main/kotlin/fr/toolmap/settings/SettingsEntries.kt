package fr.toolmap.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
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
import fr.toolmap.core.settings.MapDisplaySettings
import fr.toolmap.core.settings.NorthReference
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
import fr.toolmap.ui.LocalFormats
import java.io.File

/** The nine categories of the settings, in the order of the list (P11.10), with their short name for the tabs (P29.9). */
enum class SettingsCategory(
    @param:StringRes val title: Int,
    @param:StringRes val tab: Int,
) {
    UNITS(R.string.settings_section_units, R.string.settings_tab_units),
    DISPLAY(R.string.settings_section_display, R.string.settings_tab_display),
    CANVAS(R.string.settings_section_canvas, R.string.settings_tab_canvas),
    OVERLAY(R.string.settings_section_overlay, R.string.settings_tab_overlay),
    PLACEMARKS(R.string.settings_section_placemarks, R.string.settings_tab_placemarks),
    POSITIONING(R.string.settings_section_positioning, R.string.settings_tab_positioning),
    RECORDER(R.string.settings_section_recorder, R.string.settings_tab_recorder),
    ALERTS(R.string.settings_alerts, R.string.settings_tab_alerts),
    DATA(R.string.settings_section_data, R.string.settings_tab_data),
    ;

    /** The icon of the category, the only one of the settings in the primary colour. */
    val icon: ImageVector
        get() =
            when (this) {
                UNITS -> SettingsIcons.Straighten
                DISPLAY -> SettingsIcons.Brightness6
                CANVAS -> SettingsIcons.Layers
                OVERLAY -> SettingsIcons.GridOn
                PLACEMARKS -> SettingsIcons.Route
                POSITIONING -> SettingsIcons.MyLocation
                RECORDER -> SettingsIcons.DirectionsRun
                ALERTS -> SettingsIcons.NotificationsActive
                DATA -> SettingsIcons.Storage
            }
}

/**
 * One setting as the search reads it (P29.9): the [words] of its name (always first), its explanation and its options,
 * and the [row] that changes it, the same on the page of its category and in the results of a search.
 */
internal class SettingEntry(
    val key: Int,
    val words: List<String>,
    val row: @Composable () -> Unit,
)

/** The screens a setting opens, given by the activity. */
internal class SettingsLinks(
    val onOpenBackup: (() -> Unit)?,
    val onOpenMultiZoom: (() -> Unit)?,
    val onMoveTiles: (File) -> Unit,
)

/** The settings of [category], in the order of its page. */
@Composable
internal fun categoryEntries(
    category: SettingsCategory,
    settings: AppSettings,
    vm: SettingsViewModel,
    links: SettingsLinks,
): List<SettingEntry> =
    when (category) {
        SettingsCategory.UNITS -> unitsEntries(settings, vm)
        SettingsCategory.DISPLAY -> displayEntries(settings, vm)
        SettingsCategory.CANVAS -> canvasEntries(settings, vm, links)
        SettingsCategory.OVERLAY -> overlayEntries(settings, vm)
        SettingsCategory.PLACEMARKS -> placemarkEntries(settings, vm)
        SettingsCategory.POSITIONING -> positionEntries(settings, vm)
        SettingsCategory.RECORDER -> recorderEntries(settings, vm)
        SettingsCategory.ALERTS -> alertEntries(settings, vm)
        SettingsCategory.DATA -> dataEntries(links)
    }

/** Units and coordinates: the formats of the positions, distances, areas, bearing, slope, pressure and north. */
@Composable
private fun unitsEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> =
    listOf(
        choiceEntry(
            icon = SettingsIcons.Place,
            title = R.string.settings_coordinates,
            options = CoordinateSystem.entries,
            selected = settings.coordinates,
            label = { stringResource(it.labelRes()) },
            value = { "${stringResource(it.labelRes())} · ${example(it)}" },
            detail = { example(it) },
            onSelect = { v -> vm.update { it.copy(coordinates = v) } },
        ),
        choiceEntry(
            icon = SettingsIcons.PinDrop,
            title = R.string.settings_secondary_coordinates,
            options = listOf<CoordinateSystem?>(null) + CoordinateSystem.entries,
            selected = settings.secondaryCoordinates,
            label = { if (it == null) stringResource(R.string.settings_none) else stringResource(it.labelRes()) },
            detail = { it?.let { system -> example(system) } },
            onSelect = { v -> vm.update { it.copy(secondaryCoordinates = v) } },
        ),
        segmentedEntry(
            icon = SettingsIcons.Straighten,
            title = R.string.settings_distance_unit,
            options = UnitSystem.entries,
            selected = settings.units,
            short = { it.shortRes() },
            full = { it.distanceLabelRes() },
        ) { v -> vm.update { it.copy(units = v) } },
        segmentedEntry(
            icon = SettingsIcons.SquareFoot,
            title = R.string.settings_area_unit,
            options = UnitSystem.entries,
            selected = settings.areaUnits,
            short = { it.shortRes() },
            full = { it.areaLabelRes() },
        ) { v -> vm.update { it.copy(areaUnits = v) } },
        segmentedEntry(
            icon = SettingsIcons.Rotate90,
            title = R.string.settings_bearing_unit,
            options = BearingUnit.entries,
            selected = settings.bearing,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(bearing = v) } },
        segmentedEntry(
            icon = SettingsIcons.Terrain,
            title = R.string.settings_slope_unit,
            options = SlopeUnit.entries,
            selected = settings.slope,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(slope = v) } },
        segmentedEntry(
            icon = SettingsIcons.Speed,
            title = R.string.settings_pressure_unit,
            options = PressureUnit.entries,
            selected = settings.pressure,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(pressure = v) } },
        segmentedEntry(
            icon = SettingsIcons.North,
            title = R.string.settings_north,
            options = NorthReference.entries,
            selected = settings.north,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(north = v) } },
    )

/** Display: the theme, the night mode, the colours and gloves, the menus, what stays on the screen. */
@Composable
private fun displayEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> =
    listOf(
        segmentedEntry(
            icon = SettingsIcons.Brightness6,
            title = R.string.settings_theme,
            options = ThemeMode.entries,
            selected = settings.theme,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(theme = v) } },
        // « Mode nuit de la carte » (F14, ADR-067): how much the night mode darkens; its switch is in the main menu.
        sliderEntry(
            icon = SettingsIcons.Bedtime,
            title = R.string.settings_night_map,
            hint = R.string.settings_night_map_hint,
            value = settings.maps.nightPercent,
            range = MapDisplaySettings.MIN_NIGHT_PERCENT..MapDisplaySettings.MAX_NIGHT_PERCENT,
            step = MapDisplaySettings.NIGHT_STEP_PERCENT,
            label = { stringResource(R.string.settings_night_intensity, it) },
        ) { p -> vm.update { it.copy(maps = it.maps.copy(nightPercent = p)) } },
        switchEntry(SettingsIcons.Palette, R.string.settings_dynamic_color, settings.wallpaperColors) { on ->
            vm.update { it.copy(wallpaperColors = on) }
        },
        switchEntry(
            SettingsIcons.PanTool,
            R.string.settings_gloves,
            settings.gloves,
            R.string.settings_gloves_hint,
        ) { on ->
            vm.update { it.copy(gloves = on) }
        },
        switchEntry(
            SettingsIcons.MenuOpen,
            R.string.settings_menus_opaque,
            settings.canvas.menusOpaque,
            R.string.settings_menus_opaque_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(menusOpaque = on)) } },
        switchEntry(
            SettingsIcons.Fullscreen,
            R.string.settings_hide_status_bar,
            settings.hideStatusBar,
            R.string.settings_hide_status_bar_hint,
        ) { on -> vm.update { it.copy(hideStatusBar = on) } },
        switchEntry(
            SettingsIcons.SmartButton,
            R.string.settings_hide_nav_bar,
            settings.hideNavigationBar,
            R.string.settings_hide_nav_bar_hint,
        ) { on -> vm.update { it.copy(hideNavigationBar = on) } },
        switchEntry(
            SettingsIcons.ScreenLockPortrait,
            R.string.settings_lock_screen,
            settings.showOverLockScreen,
        ) { on ->
            vm.update { it.copy(showOverLockScreen = on) }
        },
        switchEntry(SettingsIcons.BrightnessHigh, R.string.settings_keep_screen_on, settings.keepScreenOn) { on ->
            vm.update { it.copy(keepScreenOn = on) }
        },
        switchEntry(SettingsIcons.ExitToApp, R.string.settings_confirm_exit, settings.confirmExit) { on ->
            vm.update { it.copy(confirmExit = on) }
        },
    )

/** The map itself: its sharpness, the zooms it borrows, its zoom buttons and the gestures on it. */
@Composable
private fun canvasEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
    links: SettingsLinks,
): List<SettingEntry> {
    val density = LocalDensity.current.density.toDouble()
    val canvas = settings.canvas
    val context = LocalContext.current
    val apps = remember(context) { pointApps(context) }
    val sharpness = TileSharpness.choices(density)
    val currentMapping = settings.maps.zoomMapping(density)
    // A value saved on another screen may not be offered here: the choice that draws the same is selected.
    val selectedSharpness =
        sharpness.firstOrNull { it == settings.maps.tileMagnification }
            ?: sharpness.firstOrNull { ZoomMapping.forScreen(density, it) == currentMapping }
            ?: TileSharpness.SHARP
    return listOfNotNull(
        choiceEntry(
            icon = SettingsIcons.AspectRatio,
            title = R.string.settings_map_resolution,
            options = sharpness,
            selected = selectedSharpness,
            label = { sharpnessLabel(it, density) },
            detail = { sharpnessDetail(it, density) },
            onSelect = { m -> vm.update { it.copy(maps = it.maps.copy(tileMagnification = m)) } },
        ),
        // The zooms a map borrows from its neighbours (AlpineQuest's `default_overscaling_levels`, ADR-031).
        sliderEntry(
            icon = SettingsIcons.Layers,
            title = R.string.settings_overscaling_row,
            hint = R.string.settings_overscaling_help,
            value = settings.maps.overscalingLevels,
            range = 0..ScalingPolicy.MAX_LEVELS,
            step = 1,
            label = { levels ->
                if (levels == 0) {
                    stringResource(R.string.settings_overscaling_none)
                } else {
                    pluralStringResource(R.plurals.settings_overscaling_levels, levels, levels)
                }
            },
        ) { levels -> vm.update { it.copy(maps = it.maps.copy(overscalingLevels = levels)) } },
        links.onOpenMultiZoom?.let { open -> multiZoomEntry(open) },
        switchEntry(SettingsIcons.ZoomIn, R.string.settings_zoom_buttons, canvas.zoomButtons) { on ->
            vm.update { it.copy(canvas = it.canvas.copy(zoomButtons = on)) }
        },
        switchEntry(
            SettingsIcons.AlignHorizontalLeft,
            R.string.settings_menus_left,
            canvas.menusLeftHanded,
            R.string.settings_menus_left_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(menusLeftHanded = on)) } },
        segmentedEntry(
            icon = SettingsIcons.LinearScale,
            title = R.string.settings_zoom_step,
            options = ZoomStep.entries,
            selected = canvas.zoomStep,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(canvas = it.canvas.copy(zoomStep = v)) } },
        choiceEntry(
            icon = SettingsIcons.TouchApp,
            title = R.string.settings_long_press,
            options = LongPressAction.entries,
            selected = canvas.longPress,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(canvas = it.canvas.copy(longPress = v)) } },
        ),
        choiceEntry(
            icon = MenuIcons.OpenWith,
            title = R.string.settings_point_app,
            options = listOf<String?>(null) + apps.map { it.packageName },
            selected = settings.pointApp,
            label = { packageName ->
                val app = apps.firstOrNull { it.packageName == packageName }
                app?.label ?: packageName ?: stringResource(R.string.settings_point_app_ask)
            },
            onSelect = { v -> vm.update { it.copy(pointApp = v) } },
        ),
        switchEntry(SettingsIcons.Pinch, R.string.settings_pinch, canvas.pinchZoom) { on ->
            vm.update { it.copy(canvas = it.canvas.copy(pinchZoom = on)) }
        },
        switchEntry(
            SettingsIcons.ScreenRotation,
            R.string.settings_rotation,
            canvas.manualRotation,
            R.string.settings_rotation_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(manualRotation = on)) } },
        switchEntry(
            SettingsIcons.Rotate3d,
            R.string.settings_perspective,
            canvas.perspective,
            R.string.settings_perspective_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(perspective = on)) } },
        switchEntry(
            SettingsIcons.Hiking,
            R.string.settings_explorer,
            canvas.explorerAuto,
            R.string.settings_explorer_hint,
        ) { on -> vm.update { it.copy(canvas = it.canvas.copy(explorerAuto = on)) } },
    )
}

/** « Cartes multi-zoom… », with the note on the tiles shared by the maps of a server in small under it. */
@Composable
private fun multiZoomEntry(open: () -> Unit): SettingEntry {
    val title = stringResource(R.string.settings_multizoom)
    val hint = stringResource(R.string.settings_multizoom_hint)
    val note = stringResource(R.string.settings_shared_tiles_note)
    return SettingEntry(R.string.settings_multizoom, listOf(title, hint, note)) {
        Column(Modifier.clickable(onClick = open)) {
            SettingRow(icon = SettingsIcons.StackedChart, title = title, subtitle = hint, bottomPadding = 4.dp)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

/** The grid of the screen and the information boxes of the top left corner. */
@Composable
private fun overlayEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> {
    val canvas = settings.canvas
    return listOf(
        choiceEntry(
            icon = SettingsIcons.GridOn,
            title = R.string.settings_grid,
            options = GridKind.entries - GridKind.WGS84_DECIMAL,
            selected = settings.screenGrid,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(screenGrid = v) } },
        ),
        segmentedEntry(
            icon = SettingsIcons.Grid4x4,
            title = R.string.settings_grid_density,
            options = GridDensity.entries,
            selected = canvas.gridDensity,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(canvas = it.canvas.copy(gridDensity = v)) } },
        segmentedEntry(
            icon = SettingsIcons.LineStyle,
            title = R.string.settings_grid_lines,
            options = GridStyle.entries,
            selected = canvas.gridStyle,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(canvas = it.canvas.copy(gridStyle = v)) } },
        segmentedEntry(
            icon = SettingsIcons.PhotoSizeSelectSmall,
            title = R.string.settings_info_size,
            options = InfoBoxSize.entries,
            selected = canvas.infoBoxSize,
            short = { it.labelRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(canvas = it.canvas.copy(infoBoxSize = v)) } },
        segmentedEntry(
            icon = SettingsIcons.Opacity,
            title = R.string.settings_info_background,
            options = InfoBoxBackground.entries,
            selected = canvas.infoBoxBackground,
            short = { it.labelRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(canvas = it.canvas.copy(infoBoxBackground = v)) } },
    )
}

/** The markers and the tracks: size, colours, arrows and outline, BRouter, names, borders and shadows. */
@Composable
private fun placemarkEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> {
    val placemarks = settings.placemarks
    return listOf(
        segmentedEntry(
            icon = SettingsIcons.FormatSize,
            title = R.string.settings_placemark_size,
            options = PlacemarkSize.entries,
            selected = placemarks.size,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(placemarks = it.placemarks.copy(size = v)) } },
        colorEntry(
            SettingsIcons.FormatColorFill,
            R.string.settings_default_color,
            R.string.settings_default_color_hint,
            placemarks.defaultColor,
        ) { c -> vm.update { it.copy(placemarks = it.placemarks.copy(defaultColor = c)) } },
        colorEntry(
            SettingsIcons.Adjust,
            R.string.settings_target_color,
            R.string.settings_target_color_hint,
            placemarks.targetColor,
        ) { c -> vm.update { it.copy(placemarks = it.placemarks.copy(targetColor = c)) } },
        switchEntry(
            SettingsIcons.ArrowRightAlt,
            R.string.settings_track_arrows,
            placemarks.trackArrows,
            R.string.settings_track_arrows_hint,
        ) { on -> vm.update { it.copy(placemarks = it.placemarks.copy(trackArrows = on)) } },
        switchEntry(SettingsIcons.BorderOuter, R.string.settings_track_contour, placemarks.trackContour) { on ->
            vm.update { it.copy(placemarks = it.placemarks.copy(trackContour = on)) }
        },
        routingEntry(placemarks.routingNoData) { v ->
            vm.update { it.copy(placemarks = it.placemarks.copy(routingNoData = v)) }
        },
        switchEntry(
            SettingsIcons.Label,
            R.string.settings_labels,
            placemarks.labels,
            R.string.settings_labels_hint,
        ) { on ->
            vm.update { it.copy(placemarks = it.placemarks.copy(labels = on)) }
        },
        switchEntry(
            SettingsIcons.BorderStyle,
            R.string.settings_borders,
            placemarks.borders,
            R.string.settings_borders_hint,
        ) { on -> vm.update { it.copy(placemarks = it.placemarks.copy(borders = on)) } },
        switchEntry(SettingsIcons.BlurOn, R.string.settings_shadows, placemarks.shadows) { on ->
            vm.update { it.copy(placemarks = it.placemarks.copy(shadows = on)) }
        },
    )
}

/** Positioning and heading: how the course and the altitude are shown, how the map follows, what the trail shows. */
@Composable
private fun positionEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> {
    val position = settings.positioning
    return listOf(
        segmentedEntry(
            icon = SettingsIcons.NearMe,
            title = R.string.settings_course,
            options = CourseDisplay.entries,
            selected = position.course,
            short = { it.shortRes() },
            full = { it.labelRes() },
        ) { v -> vm.update { it.copy(positioning = it.positioning.copy(course = v)) } },
        switchEntry(
            SettingsIcons.MyLocation,
            R.string.settings_auto_relock,
            position.autoRelock,
            R.string.settings_auto_relock_hint,
        ) { on -> vm.update { it.copy(positioning = it.positioning.copy(autoRelock = on)) } },
        switchEntry(
            SettingsIcons.Timeline,
            R.string.settings_trail,
            position.showTrail,
            R.string.settings_trail_hint,
        ) { on ->
            vm.update { it.copy(positioning = it.positioning.copy(showTrail = on)) }
        },
        switchEntry(
            SettingsIcons.BatterySaver,
            R.string.settings_disable_background,
            position.disableInBackground,
            R.string.settings_disable_background_hint,
        ) { on -> vm.update { it.copy(positioning = it.positioning.copy(disableInBackground = on)) } },
        switchEntry(SettingsIcons.Public, R.string.settings_egm96, position.egm96, R.string.settings_egm96_hint) { on ->
            vm.update { it.copy(positioning = it.positioning.copy(egm96 = on)) }
        },
        choiceEntry(
            icon = SettingsIcons.Height,
            title = R.string.settings_altitude,
            options = AltitudeMode.entries,
            selected = settings.altitude,
            label = { stringResource(it.labelRes()) },
            detail = { stringResource(it.hintRes()) },
            onSelect = { v -> vm.update { it.copy(altitude = v) } },
        ),
        choiceEntry(
            icon = SettingsIcons.Explore,
            title = R.string.settings_heading,
            options = HeadingDisplay.entries,
            selected = settings.heading,
            label = { stringResource(it.labelRes()) },
            onSelect = { v -> vm.update { it.copy(heading = v) } },
        ),
    )
}

/** The track recorder: warnings, the activity asked at the start, the statistics of a segment, the dashboard. */
@Composable
private fun recorderEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> {
    val recorder = settings.recorder
    return listOf(
        switchEntry(
            SettingsIcons.GpsOff,
            R.string.settings_lost_signal,
            recorder.lostSignalWarning,
            R.string.settings_lost_signal_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(lostSignalWarning = on)) } },
        switchEntry(
            SettingsIcons.DirectionsRun,
            R.string.settings_ask_activity,
            recorder.askActivity,
            R.string.settings_ask_activity_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(askActivity = on)) } },
        switchEntry(
            SettingsIcons.Replay,
            R.string.settings_reset_stats,
            recorder.resetStatsOnSegment,
            R.string.settings_reset_stats_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(resetStatsOnSegment = on)) } },
        switchEntry(
            SettingsIcons.Dashboard,
            R.string.settings_dashboard,
            recorder.dashboardMode,
            R.string.settings_dashboard_hint,
        ) { on -> vm.update { it.copy(recorder = it.recorder.copy(dashboardMode = on)) } },
    )
}

/** ToolMap's own alerts: proximity of the waypoints and departure from a track, with how they are announced. */
@Composable
private fun alertEntries(
    settings: AppSettings,
    vm: SettingsViewModel,
): List<SettingEntry> {
    val alerts = settings.alerts
    val fmt = LocalFormats.current
    return listOfNotNull(
        switchEntry(
            SettingsIcons.NotificationsActive,
            R.string.settings_alert_proximity,
            alerts.proximityEnabled,
            R.string.settings_alert_proximity_hint,
        ) { on -> vm.update { it.copy(alerts = it.alerts.copy(proximityEnabled = on)) } },
        if (alerts.proximityEnabled) {
            sliderEntry(
                icon = SettingsIcons.Radar,
                title = R.string.settings_alert_radius,
                value = alerts.proximityRadiusMeters,
                range = RADIUS_RANGE,
                step = RADIUS_STEP,
                label = { fmt.distance(it.toDouble()) },
            ) { m -> vm.update { it.copy(alerts = it.alerts.copy(proximityRadiusMeters = m)) } }
        } else {
            null
        },
        switchEntry(
            SettingsIcons.WrongLocation,
            R.string.settings_alert_off_track,
            alerts.offTrackEnabled,
            R.string.settings_alert_off_track_hint,
        ) { on -> vm.update { it.copy(alerts = it.alerts.copy(offTrackEnabled = on)) } },
        if (alerts.offTrackEnabled) {
            sliderEntry(
                icon = SettingsIcons.SocialDistance,
                title = R.string.settings_alert_off_track_distance,
                value = alerts.offTrackMeters,
                range = OFF_TRACK_RANGE,
                step = OFF_TRACK_STEP,
                label = { fmt.distance(it.toDouble()) },
            ) { m -> vm.update { it.copy(alerts = it.alerts.copy(offTrackMeters = m)) } }
        } else {
            null
        },
        switchEntry(SettingsIcons.Vibration, R.string.settings_alert_vibrate, alerts.vibrate) { on ->
            vm.update { it.copy(alerts = it.alerts.copy(vibrate = on)) }
        },
        switchEntry(SettingsIcons.VolumeUp, R.string.settings_alert_sound, alerts.sound) { on ->
            vm.update { it.copy(alerts = it.alerts.copy(sound = on)) }
        },
    )
}

/** Where the offline maps are kept (ADR-042), and the backup and restore of the data. */
@Composable
private fun dataEntries(links: SettingsLinks): List<SettingEntry> {
    val storage = stringResource(R.string.settings_storage)
    val locations = StorageLocation.entries.map { stringResource(it.labelRes()) }
    val backup = stringResource(R.string.settings_backup)
    return listOfNotNull(
        SettingEntry(R.string.settings_storage, listOf(storage) + locations) { StorageRow(links.onMoveTiles) },
        links.onOpenBackup?.let { open ->
            SettingEntry(R.string.settings_backup, listOf(backup)) {
                SettingRow(icon = SettingsIcons.Backup, title = backup, modifier = Modifier.clickable(onClick = open))
            }
        },
    )
}

/** Yes or no, with its explanation when it says more than the name. */
@Composable
private fun switchEntry(
    icon: ImageVector,
    @StringRes title: Int,
    checked: Boolean,
    @StringRes hint: Int? = null,
    onChange: (Boolean) -> Unit,
): SettingEntry {
    val name = stringResource(title)
    val help = hint?.let { stringResource(it) }
    return SettingEntry(title, listOfNotNull(name, help)) { SwitchRow(icon, name, help, checked, onChange) }
}

/** A choice of two or three options on a segmented button, [short] naming the segments, [full] the second line. */
@Composable
private fun <T> segmentedEntry(
    icon: ImageVector,
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    short: (T) -> Int,
    full: (T) -> Int,
    onSelect: (T) -> Unit,
): SettingEntry {
    val name = stringResource(title)
    val shorts = options.map { stringResource(short(it)) }
    val fulls = options.map { stringResource(full(it)) }
    return SettingEntry(title, listOf(name) + shorts + fulls) {
        SegmentedRow(icon, name, shorts, fulls, options.indexOf(selected)) { onSelect(options[it]) }
    }
}

/** A value on a slider in the row. */
@Composable
private fun sliderEntry(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes hint: Int? = null,
    value: Int,
    range: IntRange,
    step: Int,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
): SettingEntry {
    val name = stringResource(title)
    val help = hint?.let { stringResource(it) }
    return SettingEntry(title, listOfNotNull(name, help)) {
        SliderRow(icon, name, help, value, range, step, label, onChange)
    }
}

/** A choice in a dialog: the search reads the names of all its options. */
@Composable
private fun <T> choiceEntry(
    icon: ImageVector,
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    value: @Composable (T) -> String = label,
    detail: (@Composable (T) -> String?)? = null,
): SettingEntry {
    val name = stringResource(title)
    val words = listOf(name) + options.map { label(it) }
    return SettingEntry(title, words) {
        ChoiceRow(icon, name, options, selected, label, onSelect, value, detail)
    }
}

@Composable
private fun colorEntry(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes hint: Int,
    color: Int,
    onChange: (Int) -> Unit,
): SettingEntry {
    val name = stringResource(title)
    val help = stringResource(hint)
    return SettingEntry(title, listOf(name, help)) { ColorRow(icon, name, help, color, onChange) }
}

@Composable
private fun routingEntry(
    selected: RoutingNoData,
    onSelect: (RoutingNoData) -> Unit,
): SettingEntry {
    val words =
        listOf(
            stringResource(R.string.settings_routing_no_data),
            stringResource(R.string.settings_routing_no_data_ask),
        ) +
            RoutingNoData.entries.map { stringResource(it.labelRes()) }
    return SettingEntry(R.string.settings_routing_no_data, words) { RoutingNoDataRow(selected, onSelect) }
}

private val RADIUS_RANGE = 20..1000
private const val RADIUS_STEP = 10
private val OFF_TRACK_RANGE = 10..500
private const val OFF_TRACK_STEP = 5
