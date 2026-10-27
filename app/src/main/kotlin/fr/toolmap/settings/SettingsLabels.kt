package fr.toolmap.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.toolmap.R
import fr.toolmap.core.geo.CoordinateFormat
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.TileSharpness
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.settings.AltitudeMode
import fr.toolmap.core.settings.BearingUnit
import fr.toolmap.core.settings.CourseDisplay
import fr.toolmap.core.settings.GridDensity
import fr.toolmap.core.settings.GridStyle
import fr.toolmap.core.settings.HeadingDisplay
import fr.toolmap.core.settings.InfoBoxBackground
import fr.toolmap.core.settings.InfoBoxSize
import fr.toolmap.core.settings.LongPressAction
import fr.toolmap.core.settings.NorthReference
import fr.toolmap.core.settings.PlacemarkSize
import fr.toolmap.core.settings.PressureUnit
import fr.toolmap.core.settings.RoutingNoData
import fr.toolmap.core.settings.SlopeUnit
import fr.toolmap.core.settings.StorageLocation
import fr.toolmap.core.settings.ThemeMode
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.core.settings.ZoomStep
import java.text.NumberFormat

/*
 * The names of the options of the settings: the full name of the dialogs and of the second line of a row, and since
 * P29.9 the short name of a segment (« Métrique » for « Métrique (m, km) »), where the row says the full name under its
 * title.
 */

/** A position the examples of the coordinate formats are written for (the summit of the Grand Veymont). */
private const val EXAMPLE_LAT = 44.8386
private const val EXAMPLE_LON = 5.5108

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
internal fun example(system: CoordinateSystem): String = CoordinateFormat.format(system, EXAMPLE_LAT, EXAMPLE_LON).text

internal fun UnitSystem.distanceLabelRes(): Int =
    when (this) {
        UnitSystem.METRIC -> R.string.settings_unit_distance_metric
        UnitSystem.IMPERIAL -> R.string.settings_unit_distance_imperial
    }

internal fun UnitSystem.areaLabelRes(): Int =
    when (this) {
        UnitSystem.METRIC -> R.string.settings_unit_area_metric
        UnitSystem.IMPERIAL -> R.string.settings_unit_area_imperial
    }

internal fun UnitSystem.shortRes(): Int =
    when (this) {
        UnitSystem.METRIC -> R.string.settings_short_metric
        UnitSystem.IMPERIAL -> R.string.settings_short_imperial
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

internal fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

internal fun ThemeMode.shortRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_short_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

internal fun AltitudeMode.hintRes(): Int =
    when (this) {
        AltitudeMode.AUTO -> R.string.settings_altitude_auto_hint
        AltitudeMode.GPS -> R.string.settings_altitude_gps_hint
        AltitudeMode.BAROMETER -> R.string.settings_altitude_baro_hint
    }

internal fun AltitudeMode.labelRes(): Int =
    when (this) {
        AltitudeMode.AUTO -> R.string.settings_altitude_auto
        AltitudeMode.GPS -> R.string.settings_altitude_gps
        AltitudeMode.BAROMETER -> R.string.settings_altitude_baro
    }

internal fun BearingUnit.labelRes(): Int =
    when (this) {
        BearingUnit.DEGREES -> R.string.settings_bearing_degrees
        BearingUnit.MILS -> R.string.settings_bearing_mils
        BearingUnit.GRADIANS -> R.string.settings_bearing_gradians
    }

internal fun BearingUnit.shortRes(): Int =
    when (this) {
        BearingUnit.DEGREES -> R.string.settings_short_degrees
        BearingUnit.MILS -> R.string.settings_short_mils
        BearingUnit.GRADIANS -> R.string.settings_short_gradians
    }

internal fun SlopeUnit.labelRes(): Int =
    when (this) {
        SlopeUnit.PERCENT -> R.string.settings_slope_percent
        SlopeUnit.DEGREES -> R.string.settings_slope_degrees
    }

internal fun SlopeUnit.shortRes(): Int =
    when (this) {
        SlopeUnit.PERCENT -> R.string.settings_short_percent
        SlopeUnit.DEGREES -> R.string.settings_short_degrees
    }

internal fun PressureUnit.labelRes(): Int =
    when (this) {
        PressureUnit.HPA -> R.string.settings_pressure_hpa
        PressureUnit.MMHG -> R.string.settings_pressure_mmhg
        PressureUnit.INHG -> R.string.settings_pressure_inhg
    }

internal fun PressureUnit.shortRes(): Int =
    when (this) {
        PressureUnit.HPA -> R.string.settings_short_hpa
        PressureUnit.MMHG -> R.string.settings_short_mmhg
        PressureUnit.INHG -> R.string.settings_short_inhg
    }

internal fun NorthReference.labelRes(): Int =
    when (this) {
        NorthReference.TRUE -> R.string.settings_north_true
        NorthReference.MAGNETIC -> R.string.settings_north_magnetic
        NorthReference.GRID -> R.string.settings_north_grid
    }

internal fun NorthReference.shortRes(): Int =
    when (this) {
        NorthReference.TRUE -> R.string.settings_short_north_true
        NorthReference.MAGNETIC -> R.string.settings_short_north_magnetic
        NorthReference.GRID -> R.string.settings_short_north_grid
    }

internal fun StorageLocation.labelRes(): Int =
    when (this) {
        StorageLocation.INTERNAL -> R.string.settings_storage_internal
        StorageLocation.EXTERNAL -> R.string.settings_storage_external
    }

internal fun InfoBoxSize.labelRes(): Int =
    when (this) {
        InfoBoxSize.SMALL -> R.string.settings_info_size_small
        InfoBoxSize.NORMAL -> R.string.settings_info_size_normal
        InfoBoxSize.LARGE -> R.string.settings_info_size_large
    }

internal fun InfoBoxBackground.labelRes(): Int =
    when (this) {
        InfoBoxBackground.TRANSPARENT -> R.string.settings_info_bg_transparent
        InfoBoxBackground.TRANSLUCENT -> R.string.settings_info_bg_translucent
        InfoBoxBackground.OPAQUE -> R.string.settings_info_bg_opaque
    }

internal fun GridDensity.labelRes(): Int =
    when (this) {
        GridDensity.FINE -> R.string.settings_grid_fine
        GridDensity.NORMAL -> R.string.settings_grid_normal
        GridDensity.COARSE -> R.string.settings_grid_coarse
    }

internal fun GridDensity.shortRes(): Int =
    when (this) {
        GridDensity.FINE -> R.string.settings_short_grid_fine
        GridDensity.NORMAL -> R.string.settings_grid_normal
        GridDensity.COARSE -> R.string.settings_short_grid_coarse
    }

internal fun GridStyle.labelRes(): Int =
    when (this) {
        GridStyle.SOLID -> R.string.settings_grid_solid
        GridStyle.DASHED -> R.string.settings_grid_dashed
    }

internal fun GridStyle.shortRes(): Int =
    when (this) {
        GridStyle.SOLID -> R.string.settings_short_grid_solid
        GridStyle.DASHED -> R.string.settings_grid_dashed
    }

internal fun ZoomStep.labelRes(): Int =
    when (this) {
        ZoomStep.HALF -> R.string.settings_zoom_step_half
        ZoomStep.ONE -> R.string.settings_zoom_step_one
        ZoomStep.TWO -> R.string.settings_zoom_step_two
    }

internal fun ZoomStep.shortRes(): Int =
    when (this) {
        ZoomStep.HALF -> R.string.settings_short_zoom_half
        ZoomStep.ONE -> R.string.settings_short_zoom_one
        ZoomStep.TWO -> R.string.settings_short_zoom_two
    }

internal fun LongPressAction.labelRes(): Int =
    when (this) {
        LongPressAction.NONE -> R.string.settings_long_none
        LongPressAction.WAYPOINT -> R.string.settings_long_waypoint
        LongPressAction.MEASURE -> R.string.settings_long_measure
        LongPressAction.TARGET -> R.string.settings_long_target
    }

internal fun CourseDisplay.labelRes(): Int =
    when (this) {
        CourseDisplay.NONE -> R.string.settings_course_none
        CourseDisplay.ARROW -> R.string.settings_course_arrow
    }

internal fun CourseDisplay.shortRes(): Int =
    when (this) {
        CourseDisplay.NONE -> R.string.settings_course_none
        CourseDisplay.ARROW -> R.string.settings_short_course_arrow
    }

internal fun HeadingDisplay.labelRes(): Int =
    when (this) {
        HeadingDisplay.NONE -> R.string.settings_heading_none
        HeadingDisplay.NUMBER -> R.string.settings_heading_number
        HeadingDisplay.ARROW -> R.string.settings_heading_arrow
        HeadingDisplay.BOTH -> R.string.settings_heading_both
    }

internal fun PlacemarkSize.labelRes(): Int =
    when (this) {
        PlacemarkSize.SMALL -> R.string.settings_size_small
        PlacemarkSize.NORMAL -> R.string.settings_size_normal
        PlacemarkSize.LARGE -> R.string.settings_size_large
    }

internal fun PlacemarkSize.shortRes(): Int =
    when (this) {
        PlacemarkSize.SMALL -> R.string.settings_info_size_small
        PlacemarkSize.NORMAL -> R.string.settings_info_size_normal
        PlacemarkSize.LARGE -> R.string.settings_info_size_large
    }

internal fun RoutingNoData.labelRes(): Int =
    when (this) {
        RoutingNoData.LEG_BY_HAND -> R.string.settings_routing_leg_by_hand
        RoutingNoData.ALL_BY_HAND -> R.string.settings_routing_all_by_hand
    }

internal fun RoutingNoData.detailRes(): Int =
    when (this) {
        RoutingNoData.LEG_BY_HAND -> R.string.settings_routing_leg_by_hand_detail
        RoutingNoData.ALL_BY_HAND -> R.string.settings_routing_all_by_hand_detail
    }
