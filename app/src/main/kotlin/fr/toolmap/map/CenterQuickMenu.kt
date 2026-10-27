package fr.toolmap.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.geo.CoordinateFormat
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.toolMap
import fr.toolmap.waypoints.FormatButton

/**
 * The quick menu of the centre point (F1, ADR-043), as the menu of a point in AlpineQuest. Since P14.10 it starts with
 * the point itself, in the tinted block of the waypoint dialog: its coordinates in a format chosen on the spot, and the
 * altitude of the relief there; a touch on the block copies the coordinates. Then three commands for the point, and
 * three icons: copy, share and « Ouvrir avec » (ADR-044). A small arrow under the menu points at the centre. The
 * commands use the engines of the long press and of the main menu, so that the ways to reach them do the same thing.
 */
@Composable
fun CenterQuickMenu(
    lat: Double,
    lon: Double,
    terrainElevation: suspend (lat: Double, lon: Double) -> Double?,
    onTarget: () -> Unit,
    onWaypoint: () -> Unit,
    onMeasure: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onOpenWith: () -> Unit,
) {
    val fmt = LocalFormats.current
    var system by remember { mutableStateOf(fmt.coordinates) }
    var formats by remember { mutableStateOf(false) }
    val coordinates = CoordinateFormat.format(system, lat, lon).text
    // (false, null) while the relief is asked; then true with its altitude, or with nothing when it does not know.
    val relief by produceState<Pair<Boolean, Double?>>(false to null, lat, lon) {
        value = true to terrainElevation(lat, lon)
    }
    val color = MaterialTheme.toolMap.panel
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = color,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.toolMap.tile)
                        .clickable(role = Role.Button) { onCopy(coordinates) }
                        .padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
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
                        FormatButton(system = system, expanded = formats, onExpand = { formats = it }) {
                            system = it
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_wp_peak),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val (asked, meters) = relief
                        Text(
                            when {
                                !asked -> {
                                    stringResource(R.string.waypoint_altitude_pending)
                                }

                                meters == null -> {
                                    stringResource(R.string.waypoint_altitude_unknown)
                                }

                                else -> {
                                    fmt.elevation(meters) + " · " +
                                        stringResource(R.string.waypoint_altitude_terrain)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                MenuRow(stringResource(R.string.point_target), icon = MenuIcons.Target, onClick = onTarget)
                MenuRow(stringResource(R.string.point_waypoint), icon = MenuIcons.AddLocation, onClick = onWaypoint)
                MenuRow(stringResource(R.string.point_measure), icon = MenuIcons.Measure, onClick = onMeasure)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    PointIcon(MenuIcons.Copy, stringResource(R.string.point_copy)) { onCopy(coordinates) }
                    PointIcon(Icons.Filled.Share, stringResource(R.string.point_share_short)) { onShare(coordinates) }
                    PointIcon(MenuIcons.OpenWith, stringResource(R.string.point_open_with), onOpenWith)
                }
            }
        }
        // The tip that points at the centre point, under the menu.
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            val tip =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2, size.height)
                    close()
                }
            drawPath(tip, color)
        }
    }
}

/** An icon with its name under it, for the row of icons of the quick menu: the icons share the width of the row. */
@Composable
private fun RowScope.PointIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Puts [text] on the clipboard, for « Copier » of the coordinates. */
internal fun copyToClipboard(
    context: Context,
    text: String,
) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}

/** Hands [text] to the system share sheet, under [title]. */
internal fun shareText(
    context: Context,
    title: String,
    text: String,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
