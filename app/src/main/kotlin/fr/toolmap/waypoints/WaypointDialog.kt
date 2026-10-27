package fr.toolmap.waypoints

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.geo.CoordinateFormat
import fr.toolmap.core.geo.CoordinateParser
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.ParsedCoordinate
import fr.toolmap.core.track.Waypoint
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.FilledTonalButton
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.delay
import java.util.Locale

/** The coordinates in [system]; degrees keep six decimals so that nothing is lost. */
private fun written(
    system: CoordinateSystem,
    lat: Double,
    lon: Double,
): String =
    if (system == CoordinateSystem.DD) {
        CoordinateFormat.decimalDegrees(lat, lon, decimals = 6)
    } else {
        CoordinateFormat.format(system, lat, lon).text
    }

/** Each format with its full name (menu) and short name (button). */
internal val FORMATS =
    listOf(
        Triple(CoordinateSystem.DD, R.string.waypoint_format_dd, R.string.waypoint_format_short_dd),
        Triple(CoordinateSystem.DDM, R.string.waypoint_format_ddm, R.string.waypoint_format_short_ddm),
        Triple(CoordinateSystem.DMS, R.string.waypoint_format_dms, R.string.waypoint_format_short_dms),
        Triple(CoordinateSystem.UTM, R.string.waypoint_format_utm, R.string.waypoint_format_utm),
        Triple(CoordinateSystem.MGRS, R.string.waypoint_format_mgrs, R.string.waypoint_format_mgrs),
        Triple(CoordinateSystem.LAMBERT93, R.string.waypoint_format_lambert, R.string.waypoint_format_short_lambert),
    )

/** Waits this long after the last keystroke in the coordinates before asking the terrain for an altitude. */
private const val TERRAIN_DEBOUNCE_MILLIS = 400L

/** Narrower than Material's 24 dp sides, so that Cancel, Go there and Save fit on one row of a phone dialog. */
private val COMPACT_BUTTON = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

private fun errorText(reason: ParsedCoordinate.Reason): Int =
    when (reason) {
        ParsedCoordinate.Reason.SYNTAX -> R.string.waypoint_error_syntax
        ParsedCoordinate.Reason.RANGE -> R.string.waypoint_error_range
        ParsedCoordinate.Reason.SEXAGESIMAL -> R.string.waypoint_error_sexagesimal
        ParsedCoordinate.Reason.AXES -> R.string.waypoint_error_axes
        ParsedCoordinate.Reason.GRID -> R.string.waypoint_error_grid
    }

/**
 * Creates or edits a waypoint. Only what serves every time is shown: symbol, name (a new waypoint gets [defaultName],
 * selected so that typing replaces it), the position on one line and its altitude (read from the terrain for a new
 * waypoint). The pencil opens the coordinates typed in any common format and the altitude; the description opens on
 * request. [center] is where the map is looking, to move an existing waypoint there.
 */
@Composable
fun WaypointDialog(
    initial: Waypoint?,
    start: Pair<Double, Double>,
    defaultName: String,
    center: () -> Pair<Double, Double>,
    terrainElevation: suspend (lat: Double, lon: Double) -> Double?,
    onSave: (Waypoint) -> Unit,
    onSaveAndGo: (Waypoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val creating = initial == null
    val fmt = LocalFormats.current
    var name by remember {
        val text = initial?.name ?: defaultName
        mutableStateOf(
            TextFieldValue(text, selection = if (creating) TextRange(0, text.length) else TextRange(text.length)),
        )
    }
    var description by remember { mutableStateOf(initial?.desc.orEmpty()) }
    var describing by remember { mutableStateOf(description.isNotEmpty()) }
    var symbol by remember { mutableStateOf(WaypointSymbols.canonical(initial?.sym)) }
    var picking by remember { mutableStateOf(false) }
    var system by remember { mutableStateOf(fmt.coordinates) }
    var formatMenu by remember { mutableStateOf(false) }
    var coordinates by remember {
        mutableStateOf(written(system, initial?.lat ?: start.first, initial?.lon ?: start.second))
    }
    var editing by remember { mutableStateOf(false) }
    var altitude by remember { mutableStateOf(initial?.ele?.let { String.format(Locale.ROOT, "%.0f", it) }.orEmpty()) }
    // The altitude comes from the terrain until the user types one; only a new waypoint asks the terrain.
    var altitudeTyped by remember { mutableStateOf(!creating) }
    var fromTerrain by remember { mutableStateOf(false) }
    var lookingUp by remember { mutableStateOf(false) }

    val parsed = CoordinateParser.parse(coordinates)
    val position = parsed as? ParsedCoordinate.Success
    val altitudeValue = altitude.trim().replace(',', '.').toDoubleOrNull()
    val altitudeOk = altitude.isBlank() || altitudeValue != null

    LaunchedEffect(position?.lat, position?.lon) {
        if (altitudeTyped || position == null) return@LaunchedEffect
        if (editing) delay(TERRAIN_DEBOUNCE_MILLIS)
        lookingUp = true
        val ele =
            try {
                terrainElevation(position.lat, position.lon)
            } finally {
                lookingUp = false
            }
        if (!altitudeTyped) {
            altitude = ele?.let { String.format(Locale.ROOT, "%.0f", it) }.orEmpty()
            fromTerrain = ele != null
        }
    }

    fun build(): Waypoint? {
        if (position == null || !altitudeOk) return null
        return (initial ?: Waypoint(position.lat, position.lon)).copy(
            lat = position.lat,
            lon = position.lon,
            ele = altitudeValue,
            name = name.text.trim().takeIf { it.isNotEmpty() },
            desc = description.trim().takeIf { it.isNotEmpty() },
            sym = symbol,
        )
    }

    if (picking) {
        WaypointIconPicker(
            selectedId = WaypointSymbols.iconOf(symbol).id,
            onSelect = {
                symbol = it.key
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (creating) R.string.waypoint_title_new else R.string.waypoint_title_edit))
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val nameFocus = remember { FocusRequester() }
                if (creating) LaunchedEffect(Unit) { nameFocus.requestFocus() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SymbolButton(symbol, onClick = { picking = true })
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.waypoint_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(nameFocus),
                    )
                }
                if (editing) {
                    OutlinedTextField(
                        value = coordinates,
                        onValueChange = { coordinates = it },
                        label = { Text(stringResource(R.string.waypoint_coordinates)) },
                        placeholder = { Text(stringResource(R.string.waypoint_coordinates_hint)) },
                        isError = parsed is ParsedCoordinate.Failure,
                        supportingText = {
                            (parsed as? ParsedCoordinate.Failure)?.let { Text(stringResource(errorText(it.reason))) }
                        },
                        trailingIcon = {
                            FormatButton(
                                system = system,
                                expanded = formatMenu,
                                onExpand = { formatMenu = it },
                                onSelect = {
                                    system = it
                                    position?.let { p -> coordinates = written(it, p.lat, p.lon) }
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = altitude,
                        onValueChange = {
                            altitude = it
                            altitudeTyped = true
                            fromTerrain = false
                        },
                        label = { Text(stringResource(R.string.waypoint_altitude)) },
                        isError = !altitudeOk,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!creating) {
                        TextButton(
                            onClick = {
                                val (lat, lon) = center()
                                coordinates = written(system, lat, lon)
                            },
                        ) { Text(stringResource(R.string.waypoint_use_center)) }
                    }
                } else {
                    PositionSummary(
                        coordinates = position?.let { written(system, it.lat, it.lon) } ?: coordinates,
                        altitude =
                            when {
                                lookingUp -> {
                                    stringResource(R.string.waypoint_altitude_pending)
                                }

                                altitudeValue == null -> {
                                    stringResource(R.string.waypoint_altitude_unknown)
                                }

                                fromTerrain -> {
                                    fmt.elevation(altitudeValue) + " · " +
                                        stringResource(R.string.waypoint_altitude_terrain)
                                }

                                else -> {
                                    fmt.elevation(altitudeValue)
                                }
                            },
                        format = {
                            FormatButton(
                                system = system,
                                expanded = formatMenu,
                                onExpand = { formatMenu = it },
                                onSelect = {
                                    system = it
                                    position?.let { p -> coordinates = written(it, p.lat, p.lon) }
                                },
                            )
                        },
                        onEdit = { editing = true },
                    )
                }
                if (describing) {
                    val descriptionFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { if (description.isEmpty()) descriptionFocus.requestFocus() }
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.waypoint_description)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus),
                    )
                } else {
                    TextButton(onClick = { describing = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.waypoint_add_description))
                    }
                }
            }
        },
        // The three actions share one row (the dialog's own slots wrap "Cancel" onto a second line on a phone).
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, contentPadding = COMPACT_BUTTON) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    enabled = build() != null,
                    onClick = { build()?.let(onSaveAndGo) },
                    contentPadding = COMPACT_BUTTON,
                ) { Text(stringResource(R.string.waypoint_go_to), maxLines = 1) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = build() != null, onClick = { build()?.let(onSave) }, contentPadding = COMPACT_BUTTON) {
                    Text(stringResource(R.string.waypoint_save), maxLines = 1)
                }
            }
        },
    )
}

/** The symbol as it looks on the map, with a small pencil: touching it opens the picker. */
@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.waypoint_icon_change)
    Box(
        modifier = Modifier.clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
    ) {
        WaypointIconBadge(WaypointSymbols.iconOf(symbol), 48.dp)
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = label, modifier = Modifier.size(12.dp))
        }
    }
}

/** The position on one line, its altitude on the next, and the pencil that opens both for typing. */
@Composable
private fun PositionSummary(
    coordinates: String,
    altitude: String,
    format: @Composable () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                coordinates,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            format()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_wp_peak), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                altitude,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.waypoint_edit_position))
            }
        }
    }
}

/** The short name of the format shown; touching it lists the six formats, and choosing one rewrites the position. */
@Composable
internal fun FormatButton(
    system: CoordinateSystem,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onSelect: (CoordinateSystem) -> Unit,
) {
    val short = FORMATS.first { it.first == system }.third
    val menuLabel = stringResource(R.string.waypoint_format_menu)
    Box {
        TextButton(onClick = { onExpand(true) }) {
            Text(stringResource(short))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = menuLabel, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpand(false) }) {
            for ((s, full, _) in FORMATS) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(full),
                            color =
                                if (s ==
                                    system
                                ) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    },
                    onClick = {
                        onExpand(false)
                        onSelect(s)
                    },
                )
            }
        }
    }
}
