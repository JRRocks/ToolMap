package fr.toolmap.operation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.share.ShareIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.toolMap

/** The card « Opération » under the buttons that join (`tour2/01-entree-A`). */
@Composable
fun OperationCard(onCreate: () -> Unit) {
    val accent = MaterialTheme.colorScheme.secondary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, accent, RoundedCornerShape(16.dp))
            .background(MaterialTheme.toolMap.panel)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(OperationIcons.AccountTree, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.op_card_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.op_card_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Text(
                stringResource(R.string.op_card_text, OperationFacts.DEFAULT_MEMBER_LIMIT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCreate, modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.op_card_create))
            }
        }
    }
}

/** The entry of the ⋮ menu of the screen of the sharing that starts the demonstration (debug build type only). */
@Composable
fun DemoMenuItem(
    session: OperationSession,
    close: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.op_demo)) },
        leadingIcon = { Icon(OperationIcons.AccountTree, contentDescription = null) },
        onClick = {
            close()
            session.startDemo()
        },
    )
}

/** « Nouvelle opération » (`tour4/10-creation` A, U9 A; Q9: « Aucun » possible). */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CreatePage(
    session: OperationSession,
    defaultName: () -> String,
    onClose: () -> Unit,
    onCreated: () -> Unit,
) {
    val form by session.createForm.collectAsStateWithLifecycle()
    val limit = OperationFacts.DEFAULT_GROUP_LIMIT
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.op_new_title),
                onBack = onClose,
                navigationIcon = Icons.Filled.Close,
                navigationDescription = stringResource(R.string.action_close),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { v -> session.editCreate { it.copy(name = v) } },
                    label = { Text(stringResource(R.string.op_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        OperationIcons.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp).size(18.dp),
                    )
                    Text(
                        stringResource(R.string.op_new_name_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Caption(stringResource(R.string.op_new_groups, form.groups.size, limit))
            GroupBlock(
                rows =
                    form.groups.mapIndexed<String, @Composable () -> Unit> { i, name ->
                        {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GroupDot(i, 24.dp)
                                Text(
                                    groupTitle(name),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                                )
                                IconButton(onClick = { session.editCreate { it.copy(editing = i) } }) {
                                    Icon(
                                        OperationIcons.Edit,
                                        contentDescription = stringResource(R.string.op_group_edit_title),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } +
                        listOf<@Composable () -> Unit>({
                            val enabled = form.groups.size < limit
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable(enabled = enabled) {
                                        session.editCreate { it.copy(editing = CreateForm.ADDING) }
                                    }
                                    .padding(start = 16.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val colors = MaterialTheme.colorScheme
                                val tint = if (enabled) colors.primary else colors.outline
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    stringResource(R.string.op_new_add_group),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = tint,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }),
            )
            Caption(stringResource(R.string.op_new_my_group))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.groups.forEachIndexed { i, name ->
                    val selected = form.myGroup == i
                    FilterChip(
                        selected = selected,
                        onClick = { session.editCreate { it.copy(myGroup = i) } },
                        label = { Text(name) },
                        leadingIcon = {
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            } else {
                                GroupDot(i, 10.dp)
                            }
                        },
                    )
                }
                FilterChip(
                    selected = form.myGroup == null,
                    onClick = { session.editCreate { it.copy(myGroup = null) } },
                    label = { Text(stringResource(R.string.op_new_none)) },
                )
            }
            InfoBox(OperationIcons.Key, rich(stringResource(R.string.op_new_info)), BoxTone.TEAL)
            Button(
                onClick = { session.create(defaultName(), onCreated) },
                enabled = Names.valid(form.name.trim()) && !form.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (form.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(ShareIcons.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.op_new_create), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    form.editing?.let { index ->
        val adding = index == CreateForm.ADDING
        GroupNameSheet(
            adding = adding,
            rank = form.groups.size + 1,
            limit = limit,
            initial = if (adding) "" else form.groups.getOrNull(index).orEmpty(),
            onSave = { name ->
                session.editCreate { f ->
                    if (adding) {
                        f.copy(groups = f.groups + name, editing = null)
                    } else {
                        f.copy(groups = f.groups.mapIndexed { i, g -> if (i == index) name else g }, editing = null)
                    }
                }
            },
            onDelete =
                if (adding) {
                    null
                } else {
                    {
                        session.editCreate { f ->
                            val mine =
                                when {
                                    f.myGroup == index -> null
                                    f.myGroup != null && f.myGroup > index -> f.myGroup - 1
                                    else -> f.myGroup
                                }
                            f.copy(
                                groups =
                                    f.groups.filterIndexed {
                                        i,
                                        _,
                                        ->
                                        i != index
                                    },
                                myGroup = mine,
                                editing = null,
                            )
                        }
                    }
                },
            onClose = { session.editCreate { it.copy(editing = null) } },
        )
    }
}

/** « Nouveau groupe » / « Modifier le groupe » (`tour2/04-admin-groupes`, without the colour: see the remontée). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupNameSheet(
    adding: Boolean,
    rank: Int,
    limit: Int,
    initial: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    val valid = Names.valid(name.trim())
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            SheetTitle(
                stringResource(if (adding) R.string.op_group_new_title else R.string.op_group_edit_title),
                when {
                    !adding -> null
                    rank == 1 -> stringResource(R.string.op_group_first, limit)
                    else -> stringResource(R.string.op_group_rank, rank, limit)
                },
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.op_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Button(
                onClick = { onSave(name.trim()) },
                enabled = valid,
                modifier =
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp).heightIn(min = 52.dp),
            ) {
                Text(
                    if (adding) {
                        createLabel(name)
                    } else {
                        stringResource(R.string.op_group_save)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                if (onDelete != null) {
                    Spacer(Modifier.width(24.dp))
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.op_group_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** « Créer le Groupe Centre »; « Créer le groupe » while no name is typed (the button is grey then). */
@Composable
fun createLabel(name: String): String =
    if (name.isBlank()) {
        stringResource(R.string.op_group_create_blank)
    } else {
        stringResource(R.string.op_group_create, name.trim())
    }

/** The windows of the « Opération » mode over the screen of the sharing. */
@Composable
fun ShareWindows(
    session: OperationSession,
    defaultName: () -> String,
) {
    val question by session.join.collectAsStateWithLifecycle()
    val problem by session.joinProblem.collectAsStateWithLifecycle()
    val full by session.full.collectAsStateWithLifecycle()
    val busy by session.demoBusy.collectAsStateWithLifecycle()
    val demoFailed by session.demoFailed.collectAsStateWithLifecycle()
    val extras by session.extras.collectAsStateWithLifecycle()
    question?.let { JoinWindow(session, it, defaultName) }
    problem?.let { p ->
        val text =
            when (p) {
                JoinProblem.BAD_CODE -> R.string.share_problem_bad_code
                JoinProblem.EXPIRED -> R.string.share_problem_expired
                JoinProblem.CLOCK_OFF -> R.string.share_problem_clock
                JoinProblem.FAILED -> null
            }
        if (text == null) {
            session.clearJoinProblem()
        } else {
            AlertDialog(
                onDismissRequest = session::clearJoinProblem,
                title = { Text(stringResource(R.string.share_problem_title)) },
                text = { Text(stringResource(text)) },
                confirmButton = {
                    TextButton(onClick = session::clearJoinProblem) { Text(stringResource(R.string.action_ok)) }
                },
            )
        }
    }
    full?.let { name ->
        AlertDialog(
            onDismissRequest = session::clearFull,
            icon = { Icon(OperationIcons.Block, contentDescription = null) },
            title = { Text(stringResource(R.string.op_full_title), textAlign = TextAlign.Center) },
            text = { Text(rich(stringResource(R.string.op_full_text, name, OperationFacts.DEFAULT_MEMBER_LIMIT))) },
            confirmButton = { TextButton(onClick = session::clearFull) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.op_demo)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(stringResource(R.string.op_demo_building))
                }
            },
            confirmButton = {},
        )
    }
    if (demoFailed) {
        AlertDialog(
            onDismissRequest = { session.demoFailed.value = false },
            title = { Text(stringResource(R.string.op_demo)) },
            text = { Text(stringResource(R.string.op_demo_failed)) },
            confirmButton = {
                TextButton(onClick = { session.demoFailed.value = false }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    // What ended the operation for this phone, said on the screen it comes back to.
    when (val notice = extras.notices.firstOrNull { it is Notice.Removed || it is Notice.Dissolved }) {
        is Notice.Removed -> {
            AlertDialog(
                onDismissRequest = { session.consume(notice) },
                title = { Text(stringResource(R.string.op_removed_title)) },
                text = { Text(stringResource(R.string.op_removed_text, notice.operation)) },
                confirmButton = {
                    TextButton(onClick = { session.consume(notice) }) { Text(stringResource(R.string.action_ok)) }
                },
            )
        }

        is Notice.Dissolved -> {
            AlertDialog(
                onDismissRequest = { session.consume(notice) },
                icon = { Icon(OperationIcons.GroupOff, contentDescription = null) },
                title = { Text(stringResource(R.string.op_dissolved_title), textAlign = TextAlign.Center) },
                text = { Text(stringResource(R.string.op_dissolved_text, notice.operation)) },
                confirmButton = {
                    TextButton(onClick = { session.consume(notice) }) { Text(stringResource(R.string.action_ok)) }
                },
            )
        }

        else -> {}
    }
}

/** « Rejoindre l'opération ? » with « Votre nom » already filled (`tour3/04-entree-nom`, `-etats`). */
@Composable
private fun JoinWindow(
    session: OperationSession,
    question: JoinQuestion,
    defaultName: () -> String,
) {
    val name = question.name ?: remember(question.invitation) { defaultName() }
    val missing = name.isBlank()
    AlertDialog(
        onDismissRequest = session::cancelJoin,
        icon = { Icon(OperationIcons.AccountTree, contentDescription = null) },
        title = { Text(stringResource(R.string.op_join_title), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    question.operationName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = session::typeJoinName,
                        label = { Text(stringResource(R.string.op_join_name)) },
                        singleLine = true,
                        isError = missing,
                        trailingIcon = {
                            if (missing) {
                                Icon(
                                    OperationIcons.ErrorMark,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                Icon(OperationIcons.Edit, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val colors = MaterialTheme.colorScheme
                    Text(
                        stringResource(if (missing) R.string.op_join_name_missing else R.string.op_join_name_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missing) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
                Text(stringResource(R.string.op_join_text))
            }
        },
        confirmButton = {
            TextButton(onClick = { session.confirmJoin(name) }, enabled = !missing && !question.busy) {
                Text(stringResource(R.string.op_join_ok))
            }
        },
        dismissButton = { TextButton(onClick = session::cancelJoin) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The small grey caption over a block. */
@Composable
fun Caption(
    text: String,
    start: Dp = 4.dp,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = start, top = 4.dp),
    )
}

/** « 2 en attente » and the others counts of the screens. */
@Composable
fun waitingCount(count: Int): String = pluralStringResource(R.plurals.op_waiting_count, count, count)
