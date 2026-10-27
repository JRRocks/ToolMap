package fr.toolmap.wizard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.wizard.DraftIssue
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.TextButton

private const val MIME_AQX = "application/octet-stream"

/** Issues that keep each step's "Next" button off. */
private val ADDRESS_ISSUES =
    setOf(
        DraftIssue.URL_EMPTY,
        DraftIssue.URL_NOT_HTTP,
        DraftIssue.NO_TILE_PLACEHOLDER,
        DraftIssue.UNKNOWN_PLACEHOLDER,
        DraftIssue.MISSING_PARAM,
    )
private val ACCESS_ISSUES =
    setOf(DraftIssue.HEADER_INVALID, DraftIssue.THREADS_INVALID, DraftIssue.UPDATE_DELAY_INVALID)
private val ZOOM_ISSUES =
    setOf(DraftIssue.NAME_EMPTY, DraftIssue.ZOOM_INVALID, DraftIssue.TILE_SIZE_INVALID, DraftIssue.OPACITY_INVALID)

private val STEPS = WizardStep.entries

private fun WizardStep.titleRes(): Int =
    when (this) {
        WizardStep.TYPE -> R.string.wizard_step_type
        WizardStep.ADDRESS -> R.string.wizard_step_address
        WizardStep.ACCESS -> R.string.wizard_step_access
        WizardStep.ZOOMS -> R.string.wizard_step_zooms
        WizardStep.TEST -> R.string.wizard_step_test
        WizardStep.SAVE -> R.string.wizard_step_save
    }

/**
 * The add-a-map wizard (N2): type -> address (or capabilities) -> access -> zooms and look -> test tile -> save.
 *
 * @param onClose leaves the wizard; the argument is the id of a map to add to the displayed map, or null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWizardScreen(
    vm: SourceWizardViewModel,
    onClose: (addedSourceId: String?) -> Unit,
    onImportAqx: () -> Unit,
    onImportMbTiles: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_AQX)) { uri ->
            if (uri != null) vm.exportTo(uri)
        }

    val leave = { onClose(null) }
    val atStart = state.step == WizardStep.TYPE || (state.isEditing && state.step == WizardStep.ADDRESS)
    BackHandler { if (atStart) leave() else vm.back() }

    val exported = state.exportedTo?.let { stringResource(R.string.wizard_exported, it) }
    val failure = state.error?.let { stringResource(R.string.wizard_error, it) }
    LaunchedEffect(exported) {
        if (exported != null) snackbar.showSnackbar(exported)
    }
    LaunchedEffect(failure) {
        if (failure != null) {
            snackbar.showSnackbar(failure)
            vm.dismissError()
        }
    }

    val blocked = state.blockingIssues.toSet()
    val canContinue =
        when (state.step) {
            WizardStep.TYPE -> false
            WizardStep.ADDRESS -> blocked.none { it in ADDRESS_ISSUES }
            WizardStep.ACCESS -> blocked.none { it in ACCESS_ISSUES }
            WizardStep.ZOOMS -> blocked.none { it in ZOOM_ISSUES }
            WizardStep.TEST -> true
            WizardStep.SAVE -> false
        }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            // An editor: the ✕ cancels.
            ToolMapTopBar(
                title = stringResource(if (state.isEditing) R.string.wizard_title_edit else R.string.wizard_title),
                onBack = leave,
                navigationIcon = Icons.Filled.Close,
                navigationDescription = stringResource(R.string.action_cancel),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = vm::back, enabled = !atStart) { Text(stringResource(R.string.wizard_back)) }
                    when (state.step) {
                        WizardStep.TYPE -> {
                            Unit
                        }

                        WizardStep.SAVE -> {
                            Button(
                                onClick = {
                                    val id = vm.save()
                                    if (id != null) onClose(if (state.addToMap) id else null)
                                },
                                enabled = state.blockingIssues.isEmpty(),
                            ) { Text(stringResource(R.string.wizard_save)) }
                        }

                        else -> {
                            Button(onClick = vm::next, enabled = canContinue) {
                                Text(stringResource(R.string.wizard_next))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val index = STEPS.indexOf(state.step)
            Text(
                stringResource(R.string.wizard_step_of, index + 1, STEPS.size, stringResource(state.step.titleRes())),
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(progress = { (index + 1f) / STEPS.size }, modifier = Modifier.fillMaxWidth())
            when (state.step) {
                WizardStep.TYPE -> {
                    TypeStep(vm, onImportAqx = onImportAqx, onImportMbTiles = onImportMbTiles)
                }

                WizardStep.ADDRESS -> {
                    AddressStep(vm, state)
                }

                WizardStep.ACCESS -> {
                    AccessStep(vm, state)
                }

                WizardStep.ZOOMS -> {
                    ZoomsStep(vm, state)
                }

                WizardStep.TEST -> {
                    TestStep(vm, state)
                }

                WizardStep.SAVE -> {
                    SaveStep(
                        vm = vm,
                        state = state,
                        onExport = { exporter.launch(vm.suggestedFileName()) },
                    )
                }
            }
            IssueLines(
                when (state.step) {
                    WizardStep.ADDRESS -> state.issues.filter { it in ADDRESS_ISSUES || !it.isError }
                    WizardStep.ACCESS -> state.issues.filter { it in ACCESS_ISSUES }
                    WizardStep.ZOOMS -> state.issues.filter { it in ZOOM_ISSUES }
                    else -> emptyList()
                },
            )
        }
    }
}

@Composable
private fun TypeStep(
    vm: SourceWizardViewModel,
    onImportAqx: () -> Unit,
    onImportMbTiles: () -> Unit,
) {
    Text(stringResource(R.string.wizard_type_prompt), style = MaterialTheme.typography.titleSmall)
    KindButton(R.string.wizard_type_xyz, R.string.wizard_type_xyz_desc, primary = true) {
        vm.chooseKind(WizardKind.XYZ)
    }
    KindButton(R.string.wizard_type_wmts, R.string.wizard_type_wmts_desc) { vm.chooseKind(WizardKind.WMTS) }
    KindButton(R.string.wizard_type_wms, R.string.wizard_type_wms_desc) { vm.chooseKind(WizardKind.WMS) }
    HorizontalDivider()
    KindButton(R.string.wizard_type_aqx, R.string.wizard_type_aqx_desc, onClick = onImportAqx)
    KindButton(R.string.wizard_type_mbtiles, R.string.wizard_type_mbtiles_desc, onClick = onImportMbTiles)
}

@Composable
private fun KindButton(
    title: Int,
    description: Int,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (primary) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
        } else {
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
        }
        Text(stringResource(description), style = MaterialTheme.typography.bodySmall)
    }
}

/** Errors in the colour of errors, warnings in the neutral one. */
@Composable
internal fun IssueLines(issues: List<DraftIssue>) {
    for (issue in issues) {
        Text(
            (if (issue.isError) "" else "⚠ ") + stringResource(issue.messageRes()),
            style = MaterialTheme.typography.bodySmall,
            color = if (issue.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun DraftIssue.messageRes(): Int =
    when (this) {
        DraftIssue.NAME_EMPTY -> R.string.wizard_issue_name_empty
        DraftIssue.URL_EMPTY -> R.string.wizard_issue_url_empty
        DraftIssue.URL_NOT_HTTP -> R.string.wizard_issue_url_not_http
        DraftIssue.NO_TILE_PLACEHOLDER -> R.string.wizard_issue_no_tile_placeholder
        DraftIssue.UNKNOWN_PLACEHOLDER -> R.string.wizard_issue_unknown_placeholder
        DraftIssue.MISSING_PARAM -> R.string.wizard_issue_missing_param
        DraftIssue.ZOOM_INVALID -> R.string.wizard_issue_zoom_invalid
        DraftIssue.TILE_SIZE_INVALID -> R.string.wizard_issue_tile_size_invalid
        DraftIssue.UPDATE_DELAY_INVALID -> R.string.wizard_issue_update_delay_invalid
        DraftIssue.HEADER_INVALID -> R.string.wizard_issue_header_invalid
        DraftIssue.THREADS_INVALID -> R.string.wizard_issue_threads_invalid
        DraftIssue.OPACITY_INVALID -> R.string.wizard_issue_opacity_invalid
        DraftIssue.CLEARTEXT -> R.string.wizard_issue_cleartext
        DraftIssue.KEY_IN_URL -> R.string.wizard_issue_key_in_url
    }
