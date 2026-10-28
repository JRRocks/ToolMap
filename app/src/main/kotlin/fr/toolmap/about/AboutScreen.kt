package fr.toolmap.about

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.AppInfo
import fr.toolmap.map.MenuIcons
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
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
 * from a panel, it starts at that panel's [section]. Since P29.10 (« Équerre »): the ToolMap header, the blocks of
 * « Prise en main » with their icons in white groups, the credits and the licences as rows of a list.
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
    // Where the section asked for and the scrolled column stand on the screen, before any scroll.
    var sectionTop by remember { mutableStateOf<Float?>(null) }
    var columnTop by remember { mutableFloatStateOf(0f) }
    val lit = remember { Animatable(if (section == null) 0f else 1f) }
    LaunchedEffect(section, sectionTop) {
        val top = sectionTop
        if (section == null || top == null) return@LaunchedEffect
        scroll.animateScrollTo((top - columnTop).toInt())
        delay(LIT_MS)
        lit.animateTo(0f, tween(FADE_MS))
    }

    /** Where [block] sits on the page, recorded once when it is the section asked for. */
    fun place(block: HelpSection): Modifier =
        if (block != section) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { if (sectionTop == null) sectionTop = it.positionInRoot().y }
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
                    .onGloballyPositioned { columnTop = it.positionInRoot().y }
                    .verticalScroll(scroll)
                    .padding(bottom = 24.dp),
        ) {
            Header(version)
            Text(
                stringResource(R.string.about_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )

            Heading(R.string.about_start_title)
            GroupBlock(
                modifier = Modifier.padding(horizontal = 16.dp),
                rows =
                    listOf(
                        {
                            HelpBlock(
                                MapsIcons.Map,
                                R.string.maps_title,
                                litLevel(HelpSection.MAPS),
                                place(HelpSection.MAPS),
                            ) {
                                Paragraphs(
                                    R.string.about_start_map,
                                    R.string.maps_help_text,
                                    R.string.about_start_offline,
                                )
                            }
                        },
                        {
                            HelpBlock(
                                MenuIcons.Flag,
                                R.string.tracks_title,
                                litLevel(HelpSection.TRACKS),
                                place(HelpSection.TRACKS),
                            ) { Paragraphs(R.string.about_start_tracks) }
                        },
                        {
                            HelpBlock(
                                SettingsIcons.MyLocation,
                                R.string.position_title,
                                litLevel(HelpSection.POSITIONING),
                                place(HelpSection.POSITIONING),
                            ) {
                                for (id in POSITIONING_HELP) {
                                    Text(
                                        AnnotatedString.fromHtml(stringResource(id)),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        },
                        {
                            HelpBlock(SettingsIcons.Straighten, R.string.about_more_title, { 0f }) {
                                Paragraphs(
                                    R.string.about_start_tools,
                                    R.string.about_start_share,
                                    R.string.about_start_backup,
                                )
                            }
                        },
                    ),
            )

            Heading(R.string.about_privacy_title)
            TextGroup(
                R.string.about_privacy_none,
                R.string.about_privacy_tiles,
                R.string.about_privacy_search,
                R.string.about_privacy_elevation,
                R.string.about_privacy_share,
                R.string.about_privacy_backup,
            )

            Heading(R.string.about_limits_title)
            TextGroup(
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
            Note(R.string.about_data_text)
            ListGroup(DATA_CREDITS)

            Heading(R.string.about_licenses_title)
            Note(R.string.about_licenses_text)
            ListGroup(THIRD_PARTY.map { it.name to it.license })
        }
    }
}

/** ToolMap's tile, its name and its version. */
@Composable
private fun Header(version: String) {
    val tm = MaterialTheme.toolMap
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(48.dp).background(tm.shell, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) { Icon(SettingsIcons.Terrain, contentDescription = null, tint = tm.onShell, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(AppInfo.NAME, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The title of a part of the page, read as a title. */
@Composable
private fun Heading(titleRes: Int) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                .semantics { heading() },
    )
}

/** A small grey line under a title, before its list. */
@Composable
private fun Note(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

/** Paragraphs in one white block. */
@Composable
private fun TextGroup(vararg resIds: Int) {
    GroupBlock(
        modifier = Modifier.padding(horizontal = 16.dp),
        rows =
            listOf(
                {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { Paragraphs(*resIds) }
                },
            ),
    )
}

/** A list of names with a grey line under each (the credits, the licences). */
@Composable
private fun ListGroup(items: List<Pair<String, String>>) {
    GroupBlock(
        modifier = Modifier.padding(horizontal = 16.dp),
        rows =
            items.map { (name, detail) ->
                {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
    )
}

/** The positioning panel, which had no help of its own (F11). */
private val POSITIONING_HELP =
    listOf(R.string.help_position_real_time, R.string.help_position_follow, R.string.help_position_recorder)

/**
 * The help of one panel of the bar, under its icon and its name; lit (tinted) while [lit] is above 0, when the panel's
 * « ? » opened the page.
 */
@Composable
private fun HelpBlock(
    icon: ImageVector,
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
                    if (alpha > 0f) drawRect(tint.copy(alpha = alpha))
                }.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.toolMap.onTile)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
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
