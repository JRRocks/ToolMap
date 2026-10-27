package fr.toolmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.ScreenGridIssue
import fr.toolmap.core.geo.ScreenGridPlan
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.print.PrintFormat
import fr.toolmap.core.settings.barometerOn
import fr.toolmap.core.state.CameraState
import fr.toolmap.data.altitude.AltitudeStatus
import fr.toolmap.maps.MapsIcons
import fr.toolmap.ui.LocalAppSettings
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.toolMap

/**
 * The menu of the position box (F6): it acts on the coordinates the box shows, those of the centre of the map.
 * [onNextFormat] moves the « Format de position » setting to the next format.
 */
class PositionBoxActions(
    val onCopy: (String) -> Unit,
    val onShare: (String) -> Unit,
    val onNextFormat: () -> Unit,
)

/** The format after [system], in the order of the setting: DD, DM, DMS, UTM, MGRS, Lambert 93, then DD again. */
fun nextCoordinateSystem(system: CoordinateSystem): CoordinateSystem =
    CoordinateSystem.entries[(system.ordinal + 1) % CoordinateSystem.entries.size]

/** The widest the scale bar of the chip may be, in dp; its length is then the round distance that fits. */
private const val SCALE_BAR_MAX_DP = 64.0

/**
 * The chips of the top left corner (P29.1, variant E « Pastilles »), one short line each: the centre of the map, the
 * scale, then while the location listens the GPS. A tap opens a chip in detail: the position shows its menu with the
 * grid and the convergence, the scale its zoom ("16/18", the zoom shown over the zoom of the base map), the GPS its
 * satellites, the distance to the target and the barometer, whose line opens the calibration.
 */
@Composable
fun MapInfoBoxes(
    camera: CameraState,
    grid: ScreenGridPlan?,
    mapping: ZoomMapping,
    baseMaxZoom: Int?,
    gps: AltitudeStatus?,
    satellites: Int?,
    targetMeters: Double?,
    onAltitudeClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The compass heading as text, when « Affichage du cap actuel » shows it as a number (P11.8). */
    heading: String? = null,
    /** A touch on the position chip opens its menu (F6). */
    positionActions: PositionBoxActions? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PositionChip(camera, grid, positionActions)
        if (heading != null) HeadingChip(heading)
        ScaleChip(camera, mapping, baseMaxZoom)
        if (gps != null) GpsChip(gps, satellites, targetMeters, onAltitudeClick)
    }
}

@Composable
private fun PositionChip(
    camera: CameraState,
    grid: ScreenGridPlan?,
    actions: PositionBoxActions?,
) {
    val formats = LocalFormats.current
    var menu by remember { mutableStateOf(false) }
    val position = formats.position(camera.lat, camera.lon)
    val secondary = formats.secondaryPosition(camera.lat, camera.lon)
    val note =
        when {
            grid?.issue == ScreenGridIssue.POLAR -> R.string.grid_issue_polar
            grid?.issue == ScreenGridIssue.TOO_WIDE -> R.string.grid_issue_too_wide
            grid?.issue == ScreenGridIssue.OUTSIDE_VALIDITY || position.outsideValidity -> R.string.grid_issue_outside
            else -> null
        }
    val menuLabel = stringResource(R.string.position_box_menu)
    Box {
        InfoChip(
            if (actions == null) {
                Modifier
            } else {
                Modifier.clickable(onClickLabel = menuLabel, role = Role.Button) { menu = true }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipIcon { Icon(Icons.Filled.Place, contentDescription = null, modifier = it) }
                Column(Modifier.padding(start = 6.dp)) {
                    Text(position.text, style = chipValue())
                    // A second format is a setting of its own (« Position secondaire »): it stays in sight.
                    secondary?.let { Text(it.text, style = chipText()) }
                    if (note != null) {
                        Text(stringResource(note), style = chipText(), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (actions != null) {
            PositionMenu(menu, position.text, grid, actions) { menu = false }
        }
    }
}

/**
 * Under the position chip: the grid and its convergence, then copy, share, and the next format. « Format suivant »
 * leaves the menu open, so that the formats can be gone through while the chip shows each one.
 */
@Composable
private fun PositionMenu(
    expanded: Boolean,
    text: String,
    grid: ScreenGridPlan?,
    actions: PositionBoxActions,
    onDismiss: () -> Unit,
) {
    val system = LocalAppSettings.current.coordinates
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (grid != null && !grid.isEmpty) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    stringResource(R.string.grid_chip, grid.systemText, grid.stepText),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                // P24.3: grid north against true north at the centre (validated preview P24.0).
                grid.convergenceDegrees?.let { degrees ->
                    val angle =
                        PrintFormat.declination(
                            degrees,
                            stringResource(R.string.pdf_east),
                            stringResource(R.string.pdf_west),
                        )
                    Text(
                        stringResource(R.string.grid_convergence, angle),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.position_box_copy)) },
            leadingIcon = { Icon(MenuIcons.Copy, contentDescription = null) },
            onClick = {
                onDismiss()
                actions.onCopy(text)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.position_box_share)) },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = {
                onDismiss()
                actions.onShare(text)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.position_box_next_format)) },
            leadingIcon = { Icon(MenuIcons.SwapHoriz, contentDescription = null) },
            trailingIcon = {
                Text(
                    stringResource(
                        R.string.position_box_format_step,
                        stringResource(shortName(system)),
                        stringResource(shortName(nextCoordinateSystem(system))),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = actions.onNextFormat,
        )
    }
}

private fun shortName(system: CoordinateSystem): Int =
    when (system) {
        CoordinateSystem.DD -> R.string.waypoint_format_short_dd
        CoordinateSystem.DDM -> R.string.waypoint_format_short_ddm
        CoordinateSystem.DMS -> R.string.waypoint_format_short_dms
        CoordinateSystem.UTM -> R.string.settings_coord_utm
        CoordinateSystem.MGRS -> R.string.settings_coord_mgrs
        CoordinateSystem.LAMBERT93 -> R.string.waypoint_format_short_lambert
    }

/**
 * The scale as AlpineQuest gives it: the ratio and a bar whose printed length is the ground distance it names, on
 * this screen's density; opened, the zoom shown and the zoom of the base map ("16/18").
 */
@Composable
private fun ScaleChip(
    camera: CameraState,
    mapping: ZoomMapping,
    baseMaxZoom: Int?,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current.density.toDouble()
    val shown = mapping.toTileZoom(camera.zoom)
    val denominator = ScaleBar.denominator(camera.lat, shown, density)
    val bar = ScaleBar.forDisplayedZoom(camera.lat, shown, density, SCALE_BAR_MAX_DP, LocalFormats.current.units)
    val zoom = Math.round(shown).toString() + (baseMaxZoom?.let { "/$it" }.orEmpty())
    val detailLabel = stringResource(R.string.info_chip_details)
    InfoChip(Modifier.clickable(onClickLabel = detailLabel, role = Role.Button) { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipIcon { Icon(MapsIcons.Map, contentDescription = null, modifier = it) }
            Text(
                ScaleBar.ratioText(denominator),
                style = chipValue(),
                modifier = Modifier.padding(start = 6.dp),
            )
            if (bar.meters > 0.0) {
                ScaleBarGraphic(bar.widthDp, Modifier.padding(start = 6.dp))
                Text(bar.label, style = chipText(), modifier = Modifier.padding(start = 6.dp))
            }
            if (open) {
                Text(
                    stringResource(R.string.info_zoom, zoom),
                    style = chipText(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ScaleBarGraphic(
    widthDp: Double,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.size(width = widthDp.toFloat().dp, height = 6.dp)) {
        val y = size.height - 1f
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        drawLine(color, Offset(0f, 0f), Offset(0f, y), strokeWidth = stroke)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, y), strokeWidth = stroke)
    }
}

/**
 * The GPS: the accuracy and the altitude of the last fix. Opened: its satellites, the distance to the target when
 * there is one, and the barometer (pressure and altitude), whose line opens the calibration (P11.9).
 */
@Composable
private fun GpsChip(
    gps: AltitudeStatus,
    satellites: Int?,
    targetMeters: Double?,
    onAltitudeClick: () -> Unit,
) {
    val fmt = LocalFormats.current
    var open by rememberSaveable { mutableStateOf(false) }
    val accent = MaterialTheme.toolMap.accent
    val detailLabel = stringResource(R.string.info_chip_details)
    val searching = stringResource(R.string.info_gps_searching)
    InfoChip(Modifier.clickable(onClickLabel = detailLabel, role = Role.Button) { open = !open }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipIcon(tint = accent) { Icon(MenuIcons.MyLocation, contentDescription = null, modifier = it) }
                val accuracy = gps.accuracyMeters
                if (accuracy == null && gps.altitude == null) {
                    Text(searching, style = chipText())
                } else {
                    accuracy?.let {
                        Text(stringResource(R.string.info_gps_accuracy, fmt.distance(it)), style = chipValue())
                    }
                    gps.altitude?.let {
                        Text(stringResource(R.string.info_altitude, fmt.elevation(it.meters)), style = chipText())
                    }
                }
            }
            if (open) GpsDetail(gps, satellites, targetMeters, onAltitudeClick)
        }
    }
}

@Composable
private fun GpsDetail(
    gps: AltitudeStatus,
    satellites: Int?,
    targetMeters: Double?,
    onAltitudeClick: () -> Unit,
) {
    val fmt = LocalFormats.current
    val start = Modifier.padding(start = CHIP_ICON + 6.dp, top = 2.dp)
    Text(
        if (satellites == null) {
            stringResource(R.string.info_gps_searching)
        } else {
            stringResource(R.string.info_gps_satellites, satellites)
        },
        style = chipText(),
        modifier = start,
    )
    targetMeters?.let { meters ->
        Text(
            stringResource(R.string.info_target_distance, fmt.distance(meters)),
            style = chipText(),
            modifier = start,
        )
    }
    // Only while the barometer gives the altitude: the « Baromètre » tick of the positioning panel (P11.9).
    if (gps.barometerAvailable && gps.mode.barometerOn()) {
        val calibrateLabel = stringResource(R.string.altitude_dialog_title)
        Row(
            Modifier
                .padding(top = 4.dp)
                .heightIn(min = 32.dp)
                .clickable(onClickLabel = calibrateLabel, role = Role.Button, onClick = onAltitudeClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChipIcon { Icon(MenuIcons.Gauge, contentDescription = null, modifier = it) }
            gps.pressureHpa?.let { hpa -> Text(fmt.pressure(hpa), style = chipValue()) }
            val altitude = gps.altitude
            if (altitude != null) {
                Text(stringResource(R.string.info_altitude, fmt.elevation(altitude.meters)), style = chipValue())
            } else {
                Text(stringResource(R.string.altitude_chip_wait), style = chipText())
            }
        }
    }
}

/** The compass heading, as a number in the unit of « Bearing unit ». */
@Composable
private fun HeadingChip(heading: String) {
    InfoChip { Text(stringResource(R.string.info_heading, heading), style = chipValue()) }
}

/** The side of the icon of a chip. */
private val CHIP_ICON = 16.dp

@Composable
private fun ChipIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    icon: @Composable (Modifier) -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides tint) {
        icon(Modifier.size(CHIP_ICON))
    }
}

/** The figure of a chip: 13 sp, medium. */
@Composable
private fun chipValue(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp)

/** The words around the figure: 13 sp, muted. */
@Composable
private fun chipText(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * A chip of the map: one line on the panel colour, 12 dp corners, 32 dp high. « Fond des boîtes » sets how much of
 * the map shows through it, and « Taille des boîtes » scales the density, so that the text and the padding grow
 * together.
 */
@Composable
private fun InfoChip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val canvas = LocalAppSettings.current.canvas
    val density = LocalDensity.current
    val scale = canvas.infoBoxSize.scale
    val alpha = canvas.infoBoxBackground.alpha
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale * scale)) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.toolMap.panel.copy(alpha = alpha),
            contentColor = MaterialTheme.colorScheme.onSurface,
            // No shadow under a see-through chip: it would draw a grey box on the map.
            shadowElevation = if (alpha >= CHIP_SHADOW_ALPHA) 2.dp else 0.dp,
        ) {
            Box(
                Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart,
            ) { content() }
        }
    }
}

private const val CHIP_SHADOW_ALPHA = 0.9f
