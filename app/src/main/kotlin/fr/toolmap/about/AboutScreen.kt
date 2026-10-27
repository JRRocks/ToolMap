package fr.toolmap.about

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.AppInfo
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.TextButton
import kotlinx.coroutines.delay

/**
 * The part of « Prise en main » that the « ? » of a panel of the bar opens (R5, F11): the page scrolls to it and lights it
 * up for a moment. The « ? » of the main menu opens the page at its top.
 */
enum class HelpSection { MAPS, TRACKS, POSITIONING }

/** How long the section stays lit before it fades, and how long it fades. */
private const val LIT_MS = 1600L
private const val FADE_MS = 900

/**
 * The help and about page: how to start, what leaves the phone, the known limits, the credits and licences. Opened
 * from a panel, it starts at that panel's [section].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    section: HelpSection? = null,
) {
    val context = LocalContext.current
    val version = remember { versionName(context) }
    val scroll = rememberScrollState()
    var sectionTop by remember { mutableStateOf<Int?>(null) }
    val lit = remember { Animatable(if (section == null) 0f else 1f) }
    LaunchedEffect(section, sectionTop) {
        val top = sectionTop
        if (section == null || top == null) return@LaunchedEffect
        scroll.animateScrollTo(top)
        delay(LIT_MS)
        lit.animateTo(0f, tween(FADE_MS))
    }

    /** Where [block] sits on the page, recorded once when it is the section asked for. */
    fun place(block: HelpSection): Modifier =
        if (block != section) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { if (sectionTop == null) sectionTop = it.positionInParent().y.toInt() }
        }

    fun litLevel(block: HelpSection): () -> Float = if (block == section) ({ lit.value }) else ({ 0f })
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(title = stringResource(R.string.about_title), onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(AppInfo.NAME, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.about_intro))

            Heading(R.string.about_start_title)
            HelpBlock(R.string.maps_title, litLevel(HelpSection.MAPS), place(HelpSection.MAPS)) {
                Paragraphs(R.string.about_start_map, R.string.maps_help_text, R.string.about_start_offline)
            }
            HelpBlock(R.string.tracks_title, litLevel(HelpSection.TRACKS), place(HelpSection.TRACKS)) {
                Paragraphs(R.string.about_start_tracks)
            }
            HelpBlock(R.string.position_title, litLevel(HelpSection.POSITIONING), place(HelpSection.POSITIONING)) {
                for (id in POSITIONING_HELP) {
                    Text(AnnotatedString.fromHtml(stringResource(id)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Paragraphs(
                R.string.about_start_tools,
                R.string.about_start_share,
                R.string.about_start_backup,
            )

            Heading(R.string.about_privacy_title)
            Paragraphs(
                R.string.about_privacy_none,
                R.string.about_privacy_tiles,
                R.string.about_privacy_search,
                R.string.about_privacy_elevation,
                R.string.about_privacy_share,
                R.string.about_privacy_backup,
            )

            Heading(R.string.about_limits_title)
            Paragraphs(
                R.string.about_limits_share_nat,
                R.string.about_limits_share_ip,
                R.string.about_limits_share_wifi,
                R.string.about_limits_share_restart,
                R.string.about_limits_gps,
                R.string.about_limits_background,
                R.string.about_limits_altitude,
                R.string.about_limits_labels,
            )

            Heading(R.string.about_data_title)
            Text(stringResource(R.string.about_data_text), style = MaterialTheme.typography.bodySmall)
            for ((name, credit) in DATA_CREDITS) {
                Text("• $name : $credit", style = MaterialTheme.typography.bodyMedium)
            }

            Heading(R.string.about_licenses_title)
            Text(stringResource(R.string.about_licenses_text), style = MaterialTheme.typography.bodySmall)
            for (library in THIRD_PARTY) {
                Text("• ${library.name} — ${library.license}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Heading(titleRes: Int) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** The positioning panel, which had no help of its own (F11). */
private val POSITIONING_HELP =
    listOf(R.string.help_position_real_time, R.string.help_position_follow, R.string.help_position_recorder)

/**
 * The help of one panel of the bar, under its name; lit (tinted, rounded) while [lit] is above 0, when the panel's
 * « ? » opened the page.
 */
@Composable
private fun HelpBlock(
    titleRes: Int,
    lit: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = MaterialTheme.colorScheme.secondaryContainer
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .drawBehind {
                    val alpha = lit()
                    if (alpha > 0f) {
                        drawRoundRect(tint.copy(alpha = alpha), cornerRadius = CornerRadius(10.dp.toPx()))
                    }
                }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Paragraphs(vararg resIds: Int) {
    for (id in resIds) Text(stringResource(id), style = MaterialTheme.typography.bodyMedium)
}

private fun versionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        "?"
    }
