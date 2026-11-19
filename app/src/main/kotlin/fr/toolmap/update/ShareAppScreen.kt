package fr.toolmap.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.toolmap.R
import fr.toolmap.core.update.GitHubReleases
import fr.toolmap.map.shareText
import fr.toolmap.share.QrImage
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets

/*
 * « Partager l'app » (P40.1, ADR-137), the row of the main menu that took the place of « Quitter »: a full page, as
 * « Réglages… », faithful to `docs/previews/partager-app/img/03-A-page.png` (validated on 2026-11-19, « Tout en a »).
 * The QR code of the fixed link of the newest release, the launcher icon in its middle; the link under it, and the
 * round button that hands « ToolMap pour Android : <link> » to the share sheet. Nothing here needs the network.
 */

/** The page « Partager l'app »; ← and Back come back to the main menu (the map opens it again). */
@Composable
fun ShareAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.menu_share_app)
    val message = stringResource(R.string.share_app_message, GitHubReleases.LATEST_APK_LINK)
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = { ToolMapTopBar(title = title, onBack = onBack) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.share_app_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 21.6.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            QrImage(
                GitHubReleases.LATEST_APK_LINK,
                // 320 dp, as drawn; narrower on a phone whose page is narrower.
                modifier =
                    Modifier
                        .widthIn(max = QR_SIDE)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .testTag(SHARE_APP_QR_TAG),
                logo = true,
            )
            Spacer(Modifier.height(26.dp))
            LinkBox(
                link = GitHubReleases.LATEST_APK_LINK,
                onShare = { shareText(context, title, message) },
            )
        }
    }
}

/** The link in its frame, cut only after its « / », and the small round share button at its end. */
@Composable
private fun LinkBox(
    link: String,
    onShare: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // One piece per « / », laid out as words: Android cuts a link before its « / » and its dots, the maquette after.
        FlowRow(
            modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = link },
        ) {
            linkPieces(link).forEach { piece ->
                Text(
                    piece,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 15.sp,
                    lineHeight = 20.7.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        IconButton(
            onClick = onShare,
            modifier = Modifier.size(44.dp),
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
        ) {
            Icon(
                Icons.Filled.Share,
                contentDescription = stringResource(R.string.share_app_send),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** [link] cut after each « / » of its path, where alone a line may end: `https://github.com/`, `JRRocks/`, … */
internal fun linkPieces(link: String): List<String> {
    val scheme = link.substringBefore("://", missingDelimiterValue = "")
    val path = if (scheme.isEmpty()) link else link.substringAfter("://")
    val pieces = Regex("[^/]*/|[^/]+$").findAll(path).map { it.value }.toMutableList()
    if (scheme.isNotEmpty() && pieces.isNotEmpty()) pieces[0] = "$scheme://" + pieces[0]
    return pieces
}

private val QR_SIDE = 320.dp

/** The QR code of the page, for the tests. */
internal const val SHARE_APP_QR_TAG = "shareAppQr"
