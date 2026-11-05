package fr.toolmap.maps

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.toolmap.R
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.download.formatBytes
import fr.toolmap.map.MenuRow
import fr.toolmap.map.PanelChevron
import fr.toolmap.settings.SettingsIcons

/*
 * The « Hors ligne » page of « Cartes et calques » (P34, choice A of docs/previews/p34-complements.html, validated on
 * 2026-11-05), opened by « Hors ligne… » at the foot of the list, ← back to it: the offline mode and the coverage as
 * two switches, then the three ways to the saved tiles, each with its state.
 */
@Composable
internal fun OfflinePage(
    offline: Boolean,
    coverage: Boolean,
    jobs: List<ZoneJob>,
    figures: StorageFigures?,
    onOffline: (Boolean) -> Unit,
    onCoverage: (Boolean) -> Unit,
    onSaveArea: () -> Unit,
    onDownloads: () -> Unit,
    onStorage: () -> Unit,
) {
    MenuRow(
        label = stringResource(R.string.maps_option_offline),
        icon = MapsIcons.CloudOff,
        subtitle = stringResource(R.string.maps_option_offline_sub),
        switchOn = offline,
        onClick = { onOffline(!offline) },
    )
    RowDivider()
    MenuRow(
        label = stringResource(R.string.maps_chip_coverage),
        icon = SettingsIcons.GridOn,
        subtitle = stringResource(R.string.maps_coverage_sub),
        switchOn = coverage,
        onClick = { onCoverage(!coverage) },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    MenuRow(
        label = stringResource(R.string.maps_tile_save_area),
        icon = MapsIcons.CropFree,
        subtitle = stringResource(R.string.maps_save_area_sub),
        trailing = { PanelChevron() },
        onClick = onSaveArea,
    )
    RowDivider()
    MenuRow(
        label = stringResource(R.string.menu_downloads),
        icon = MapsIcons.Download,
        subtitle = downloadsSummary(jobs),
        trailing = { PanelChevron() },
        onClick = onDownloads,
    )
    RowDivider()
    MenuRow(
        label = stringResource(R.string.maps_chip_storage),
        icon = SettingsIcons.Storage,
        subtitle = figures?.let { stringResource(R.string.maps_storage_sub, formatBytes(it.tilesBytes)) },
        trailing = { PanelChevron() },
        onClick = onStorage,
    )
}

/** « 1 en cours · 42 % » (the share of the tiles done in the jobs going), « 2 en pause », « Aucun en cours ». */
@Composable
internal fun downloadsSummary(jobs: List<ZoneJob>): String {
    val going = jobs.filter { it.status == ZoneStatus.QUEUED || it.status == ZoneStatus.RUNNING }
    val paused = jobs.count { it.status == ZoneStatus.PAUSED || it.status == ZoneStatus.INTERRUPTED }
    return when {
        going.isNotEmpty() -> {
            val total = going.sumOf { it.progress.total }
            val done = going.sumOf { it.progress.processed }
            val percent = if (total == 0L) 0 else (done * 100 / total).toInt()
            stringResource(R.string.maps_downloads_running, going.size, percent)
        }

        paused > 0 -> {
            stringResource(R.string.maps_downloads_paused, paused)
        }

        else -> {
            stringResource(R.string.maps_downloads_none)
        }
    }
}
