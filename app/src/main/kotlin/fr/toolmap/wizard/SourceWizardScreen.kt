package fr.toolmap.wizard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.wizard.DraftIssue
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
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

/** The name of each stop of the indicator, also said by « Suivant : … ». */
private fun WizardStep.stopRes(): Int =
    when (this) {
        WizardStep.ADDRESS -> R.string.wizard_stop_address
        WizardStep.ACCESS -> R.string.wizard_stop_access
        WizardStep.ZOOMS -> R.string.wizard_stop_zooms
        WizardStep.TEST -> R.string.wizard_stop_test
        WizardStep.SAVE -> R.string.wizard_stop_save
    }

private fun WizardKind.titleRes(): Int =
    when (this) {
        WizardKind.XYZ -> R.string.wizard_type_xyz
        WizardKind.WMTS -> R.string.wizard_type_wmts
        WizardKind.WMS -> R.string.wizard_type_wms
    }

/**
 * The add-a-map wizard (N2), in five steps since P29.6 (« Bascule »): address (or capabilities) -> access -> zooms and
 * look -> test tile -> save. The kind is chosen in the sheet « Ajouter une carte » and is the title of a new map; the
 * five named stops of the indicator take a tick once done and can be touched to go back (all of them in a
 * modification); the filled button names what follows (« Suivant : Accès »… « Enregistrer »). No « Retour » at the
 * first step: the ✕ and the system back close the wizard.
 *
 * @param onClose leaves the wizard; the argument is the id of a map to add to the displayed map, or null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceWizardScreen(
    vm: SourceWizardViewModel,
    onClose: (addedSourceId: String?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_AQX)) { uri ->
            if (uri != null) vm.exportTo(uri)
        }

    val leave = { onClose(null) }
    val atStart = state.step == WizardStep.ADDRESS
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
            WizardStep.ADDRESS -> blocked.none { it in ADDRESS_ISSUES }
            WizardStep.ACCESS -> blocked.none { it in ACCESS_ISSUES }
            WizardStep.ZOOMS -> blocked.none { it in ZOOM_ISSUES }
            WizardStep.TEST -> true
            WizardStep.SAVE -> false
        }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            // An editor: the ✕ cancels. A new map is named by its kind, a modification by the map.
            ToolMapTopBar(
                title =
                    if (state.isEditing) {
                        stringResource(R.string.wizard_title_edit)
                    } else {
                        stringResource(state.kind.titleRes())
                    },
                subtitle = if (state.isEditing) state.draft.name else stringResource(R.string.wizard_new_subtitle),
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!atStart) TextButton(onClick = vm::back) { Text(stringResource(R.string.wizard_back)) }
                    Spacer(Modifier.weight(1f))
                    if (state.step == WizardStep.SAVE) {
                        Button(
                            onClick = {
                                val id = vm.save()
                                if (id != null) onClose(if (state.addToMap) id else null)
                            },
                            enabled = state.blockingIssues.isEmpty(),
                        ) { Text(stringResource(R.string.wizard_save)) }
                    } else {
                        val following = WizardStep.entries[state.step.ordinal + 1]
                        Button(onClick = vm::next, enabled = canContinue) {
                            Text(stringResource(R.string.wizard_next_named, stringResource(following.stopRes())))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Stops(
                current = state.step,
                reached = state.reached,
                // Forward, as « Suivant »: only once the step in progress is right.
                canLeave = { target -> target < state.step || canContinue },
                onStop = vm::goTo,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.step) {
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
}

/**
 * The five named stops: the current one a filled dot, a done one a tick, the others a ring, joined by a line. A stop
 * up to the furthest reached can be touched (forward only while [canLeave] allows it).
 */
@Composable
private fun Stops(
    current: WizardStep,
    reached: WizardStep,
    canLeave: (WizardStep) -> Boolean,
    onStop: (WizardStep) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        WizardStep.entries.forEach { step ->
            val done = step < reached && step != current
            val enabled = step != current && step <= reached && canLeave(step)
            val name = stringResource(step.stopRes())
            val described =
                when {
                    step == current -> stringResource(R.string.wizard_stop_current, name)
                    done -> stringResource(R.string.wizard_stop_done, name)
                    else -> name
                }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Button) { onStop(step) }
                        .semantics(mergeDescendants = true) { contentDescription = described }
                        .padding(vertical = 4.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                    // The line joins the stops: half on each side of a dot, none before the first or after the last.
                    Row(Modifier.fillMaxWidth()) {
                        val first = step.ordinal == 0
                        val last = step.ordinal == WizardStep.entries.lastIndex
                        Box(Modifier.weight(1f).height(2.dp).background(if (first) Color.Transparent else line))
                        Spacer(Modifier.width(20.dp))
                        Box(Modifier.weight(1f).height(2.dp).background(if (last) Color.Transparent else line))
                    }
                    when {
                        step == current -> {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(accent))
                        }

                        done -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        else -> {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(2.dp, line, CircleShape),
                            )
                        }
                    }
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (step == current) FontWeight.Bold else FontWeight.Normal,
                    color =
                        if (step == current) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Errors in the colour of errors, warnings in the neutral one. */
@Composable
internal fun IssueLines(issues: List<DraftIssue>) {
    for (issue in issues) {
        val color = if (issue.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        // A warning has its icon, not a « ⚠ » in the text (P29.6).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!issue.isError) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Text(stringResource(issue.messageRes()), style = MaterialTheme.typography.bodySmall, color = color)
        }
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
