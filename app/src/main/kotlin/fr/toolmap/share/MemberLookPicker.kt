package fr.toolmap.share

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.p2p.ColorWheel
import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The names read out for each icon of [MemberLook.ICONS], same order. */
private val ICON_LABELS =
    listOf(
        R.string.share_icon_initials,
        R.string.share_icon_knight,
        R.string.share_icon_wolf,
        R.string.share_icon_fox,
        R.string.share_icon_eagle,
        R.string.share_icon_octopus,
        R.string.share_icon_trex,
        R.string.share_icon_unicorn,
        R.string.share_icon_dragon,
        R.string.share_icon_ghost,
        R.string.share_icon_alien,
        R.string.share_icon_robot,
        R.string.share_icon_wizard,
        R.string.share_icon_ninja,
        R.string.share_icon_viking,
    )

/**
 * The profile tokens a member can pick, each drawn in the chosen colour as the others will see it on their
 * map, the member's own picture when one was imported, then the buttons to import a picture and to open the colour
 * wheel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemberLookPicker(
    name: String,
    icon: Int,
    argb: Int,
    picture: ByteArray?,
    onIcon: (Int) -> Unit,
    onColor: (Int) -> Unit,
    onPicture: (ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wheel by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) { MemberPicture.fromUri(context, uri) }
                    failed = bytes == null
                    if (bytes != null) onPicture(bytes)
                }
            }
        }
    Text(stringResource(R.string.share_icon), style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val choices =
            MemberLook.ICONS.indices.toList() + if (picture != null) listOf(MemberLook.PICTURE) else emptyList()
        for (id in choices) {
            val selected = id == icon
            val label = stringResource(if (id == MemberLook.PICTURE) R.string.share_icon_picture else ICON_LABELS[id])
            MemberBadge(
                name,
                MemberLook.pack(id, argb),
                size = 40.dp,
                picture = picture,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .border(
                            if (selected) 3.dp else 0.dp,
                            if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            CircleShape,
                        ).clickable(role = Role.RadioButton) { onIcon(id) }
                        .semantics {
                            contentDescription = label
                            this.selected = selected
                        },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(
                    if (picture ==
                        null
                    ) {
                        R.string.share_picture_import
                    } else {
                        R.string.share_picture_change
                    },
                ),
            )
        }
        if (picture != null) {
            OutlinedButton(onClick = { onPicture(null) }) { Text(stringResource(R.string.share_picture_remove)) }
        }
    }
    Text(
        stringResource(if (failed) R.string.share_picture_failed else R.string.share_picture_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
    OutlinedButton(onClick = { wheel = true }, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(20.dp).background(Color(argb), CircleShape))
        Text(stringResource(R.string.share_color_pick), modifier = Modifier.padding(start = 8.dp))
    }
    if (wheel) {
        ColorWheelDialog(
            name = name,
            icon = icon,
            initial = argb,
            picture = picture,
            onPick = {
                wheel = false
                onColor(it)
            },
            onDismiss = { wheel = false },
        )
    }
}

/** The chromatic circle: hue around, saturation from the centre, a slider for the brightness, and the palette. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorWheelDialog(
    name: String,
    icon: Int,
    initial: Int,
    picture: ByteArray?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember { ColorWheel.toHsv(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val argb = ColorWheel.fromHsv(hue, saturation, value)

    fun set(color: Int) {
        val hsv = ColorWheel.toHsv(color)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemberBadge(name, MemberLook.pack(icon, argb), picture = picture)
                Text(stringResource(R.string.share_color_title))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val wheelLabel = stringResource(R.string.share_color_wheel)
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .semantics { contentDescription = wheelLabel }
                            .pointerInput(Unit) {
                                detectTapGestures { p ->
                                    val r = size.width / 2f
                                    ColorWheel.hueSatAt(p.x - r, p.y - r, r).let { (h, s) ->
                                        hue = h
                                        saturation = s
                                    }
                                }
                            }.pointerInput(Unit) {
                                fun pick(p: Offset) {
                                    val r = size.width / 2f
                                    ColorWheel.hueSatAt(p.x - r, p.y - r, r).let { (h, s) ->
                                        hue = h
                                        saturation = s
                                    }
                                }
                                detectDragGestures(onDragStart = ::pick) { change, _ -> pick(change.position) }
                            },
                ) {
                    val r = size.minDimension / 2f
                    val hues = List(HUE_STOPS + 1) { Color(ColorWheel.fromHsv(it * 360f / HUE_STOPS, 1f, 1f)) }
                    drawCircle(Brush.sweepGradient(hues, center), r)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r), r)
                    drawCircle(Color.Black.copy(alpha = 1f - value), r)
                    val (dx, dy) = ColorWheel.pointOf(hue, saturation, r)
                    val knob = Offset(center.x + dx, center.y + dy)
                    drawCircle(Color(argb), KNOB_RADIUS.dp.toPx(), knob)
                    drawCircle(Color.White, KNOB_RADIUS.dp.toPx(), knob, style = Stroke(KNOB_STROKE.dp.toPx()))
                    drawCircle(
                        Color.Black,
                        (KNOB_RADIUS + KNOB_STROKE).dp.toPx(),
                        knob,
                        style = Stroke(1.dp.toPx()),
                    )
                }
                Text(stringResource(R.string.share_color_brightness), style = MaterialTheme.typography.labelLarge)
                Slider(value = value, onValueChange = { value = it }, valueRange = MIN_VALUE..1f)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((i, c) in MemberPalette.colors.withIndex()) {
                        val label = stringResource(R.string.a11y_color_choice, i + 1)
                        Box(
                            modifier =
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(28.dp)
                                    .background(Color(c), CircleShape)
                                    .clickable(role = Role.Button) { set(c) }
                                    .semantics { contentDescription = label },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(argb) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val HUE_STOPS = 12
private const val KNOB_RADIUS = 10
private const val KNOB_STROKE = 3
private const val MIN_VALUE = 0.15f
