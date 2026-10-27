package fr.toolmap.navigate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.geo.Guidance
import fr.toolmap.core.geo.NavTarget
import fr.toolmap.core.geo.Navigation
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.TextButton

/**
 * Distance and bearing to the target, with an arrow that points to it as the map is drawn on screen (it turns with the
 * map bearing, so it needs no compass of its own).
 */
@Composable
fun TargetHud(
    target: NavTarget,
    guidance: Guidance?,
    gpsFailed: Boolean,
    mapBearing: Double,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = LocalFormats.current
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (guidance != null) Arrow(rotation = (guidance.bearingDegrees - mapBearing).toFloat())
            Column {
                Text(
                    stringResource(R.string.target_title, target.name ?: stringResource(R.string.target_unnamed)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    when {
                        guidance != null && Navigation.arrived(guidance) -> {
                            stringResource(R.string.target_arrived)
                        }

                        guidance != null -> {
                            stringResource(
                                R.string.target_distance_bearing,
                                fmt.distance(guidance.distanceMeters),
                                fmt.bearing(guidance.bearingDegrees),
                                Navigation.compassPoint(guidance.bearingDegrees),
                            )
                        }

                        gpsFailed -> {
                            stringResource(R.string.target_gps_failed)
                        }

                        else -> {
                            stringResource(R.string.target_waiting)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.target_clear)) }
        }
    }
}

@Composable
private fun Arrow(rotation: Float) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(36.dp).rotate(rotation)) {
        val w = size.width
        val h = size.height
        val arrow =
            Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w * 0.85f, h * 0.85f)
                lineTo(w / 2, h * 0.65f)
                lineTo(w * 0.15f, h * 0.85f)
                close()
            }
        drawPath(arrow, color)
        drawCircle(Color.White, radius = w * 0.05f, center = androidx.compose.ui.geometry.Offset(w / 2, h * 0.62f))
    }
}
