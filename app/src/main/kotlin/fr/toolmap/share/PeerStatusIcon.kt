package fr.toolmap.share

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.p2p.ServerState

@Composable
fun peerStateLabel(state: PeerState): String =
    stringResource(
        when (state) {
            PeerState.ONLINE -> R.string.share_peer_online
            PeerState.PAIRED -> R.string.share_peer_paired
            PeerState.LOST_SYNC -> R.string.share_peer_lost_sync
            PeerState.OFFLINE -> R.string.share_peer_offline
            PeerState.UNPAIRED -> R.string.share_peer_unpaired
        },
    )

@Composable
fun serverStateLabel(state: ServerState): String =
    stringResource(
        when (state) {
            ServerState.NONE -> R.string.share_server_none
            ServerState.CONNECTING -> R.string.share_server_connecting
            ServerState.ONLINE -> R.string.share_server_online
            ServerState.UNREACHABLE -> R.string.share_server_unreachable
            ServerState.REFUSED -> R.string.share_server_refused
        },
    )

/** `false` when this device itself cannot reach its coordination server, so it cannot vouch for anyone. */
fun serverUp(ui: ShareUi): Boolean =
    !ui.onServer || (ui.server != ServerState.UNREACHABLE && ui.server != ServerState.REFUSED)

/**
 * One icon per state, told apart by shape as well as colour: a tick (online), two linked rings (paired, not yet
 * connected), an exclamation mark (sync lost), a dash (offline), a cross (not paired).
 */
@Composable
fun PeerStatusIcon(
    state: PeerState,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val label = peerStateLabel(state)
    Canvas(modifier = modifier.size(size).semantics { contentDescription = label }) {
        val d = this.size.minDimension
        when (state) {
            PeerState.ONLINE -> {
                disc(GREEN, d)
                tick(d)
            }

            PeerState.PAIRED -> {
                disc(BLUE, d)
                chain(d)
            }

            PeerState.LOST_SYNC -> {
                disc(AMBER, d)
                exclamation(d)
            }

            PeerState.OFFLINE -> {
                disc(GREY, d)
                dash(d)
            }

            PeerState.UNPAIRED -> {
                disc(RED, d)
                cross(d)
            }
        }
    }
}

private fun DrawScope.disc(
    color: Color,
    d: Float,
) = drawCircle(color, radius = d / 2f)

private fun DrawScope.stroke(d: Float) = Stroke(width = d * STROKE, cap = StrokeCap.Round)

private fun DrawScope.tick(d: Float) {
    val s = stroke(d)
    drawLine(Color.White, Offset(d * 0.28f, d * 0.52f), Offset(d * 0.44f, d * 0.68f), s.width, StrokeCap.Round)
    drawLine(Color.White, Offset(d * 0.44f, d * 0.68f), Offset(d * 0.73f, d * 0.34f), s.width, StrokeCap.Round)
}

private fun DrawScope.chain(d: Float) {
    val s = stroke(d)
    drawCircle(Color.White, radius = d * 0.14f, center = Offset(d * 0.38f, d * 0.5f), style = s)
    drawCircle(Color.White, radius = d * 0.14f, center = Offset(d * 0.62f, d * 0.5f), style = s)
}

private fun DrawScope.exclamation(d: Float) {
    val w = d * STROKE
    drawLine(Color.White, Offset(d * 0.5f, d * 0.24f), Offset(d * 0.5f, d * 0.56f), w, StrokeCap.Round)
    drawCircle(Color.White, radius = w * 0.62f, center = Offset(d * 0.5f, d * 0.74f))
}

private fun DrawScope.dash(d: Float) {
    drawLine(Color.White, Offset(d * 0.28f, d * 0.5f), Offset(d * 0.72f, d * 0.5f), d * STROKE, StrokeCap.Round)
}

private fun DrawScope.cross(d: Float) {
    val w = d * STROKE
    drawLine(Color.White, Offset(d * 0.32f, d * 0.32f), Offset(d * 0.68f, d * 0.68f), w, StrokeCap.Round)
    drawLine(Color.White, Offset(d * 0.68f, d * 0.32f), Offset(d * 0.32f, d * 0.68f), w, StrokeCap.Round)
}

private const val STROKE = 0.11f
private val GREEN = Color(0xFF2E7D32)
private val BLUE = Color(0xFF1565C0)
private val AMBER = Color(0xFFE08A00)
private val GREY = Color(0xFF757575)
private val RED = Color(0xFFC62828)
