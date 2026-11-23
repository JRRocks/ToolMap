package fr.toolmap.operation

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.share.ShareIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.launch

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
            val colors = form.palette
            Caption(stringResource(R.string.op_new_groups, form.groups.size, limit))
            GroupBlock(
                rows =
                    form.groups.mapIndexed<String, @Composable () -> Unit> { i, name ->
                        {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GroupDot(colors.getOrNull(i), 24.dp)
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
                                GroupDot(colors.getOrNull(i), 10.dp)
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
        val chosen = form.chosen
        // L1 A (`tour8/img/03`): the colour it has without a choice of its own; picking it again keeps no choice.
        val unpicked =
            if (adding) {
                OperationFacts.paletteOf(chosen + null).last()
            } else {
                OperationFacts.paletteOf(
                    chosen.mapIndexed { i, c ->
                        if (i ==
                            index
                        ) {
                            null
                        } else {
                            c
                        }
                    },
                ).getOrElse(index) { 0 }
            }
        GroupNameSheet(
            adding = adding,
            rank = form.groups.size + 1,
            limit = limit,
            initial = if (adding) "" else form.groups.getOrNull(index).orEmpty(),
            color = if (adding) unpicked else form.palette.getOrElse(index) { unpicked },
            taken = form.palette.filterIndexed { i, _ -> adding || i != index }.toSet(),
            onSave = { name, color ->
                val pick = color.takeIf { it != unpicked }
                session.editCreate { f ->
                    if (adding) {
                        f.copy(groups = f.groups + name, colors = f.chosen + pick, editing = null)
                    } else {
                        f.copy(
                            groups = f.groups.mapIndexed { i, g -> if (i == index) name else g },
                            colors = f.chosen.mapIndexed { i, c -> if (i == index) pick else c },
                            editing = null,
                        )
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
                                colors = f.chosen.filterIndexed { i, _ -> i != index },
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

/**
 * « Nouveau groupe » / « Modifier le groupe » of « Nouvelle opération » (`tour2/04-admin-groupes`; L1 A,
 * `tour8/img/03`): the name and the eight colours, [color] ticked, those of the other groups ([taken]) pale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupNameSheet(
    adding: Boolean,
    rank: Int,
    limit: Int,
    initial: String,
    color: Int,
    taken: Set<Int>,
    onSave: (String, Int) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    var picked by remember(initial) { mutableStateOf<Int?>(null) }
    val shown = picked?.takeIf { it !in taken } ?: color
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
            GroupColorPicker(stringResource(R.string.op_group_color_new), shown, taken) { picked = it }
            Button(
                onClick = { onSave(name.trim(), shown) },
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

/**
 * The windows of the « Opération » mode over the screen of the sharing; [setName] is the name set in the app, empty
 * when none is (N16 A: the phone model is not offered to come in with).
 */
@Composable
fun ShareWindows(
    session: OperationSession,
    setName: () -> String,
) {
    val question by session.join.collectAsStateWithLifecycle()
    val problem by session.joinProblem.collectAsStateWithLifecycle()
    val full by session.full.collectAsStateWithLifecycle()
    val busy by session.demoBusy.collectAsStateWithLifecycle()
    val demoFailed by session.demoFailed.collectAsStateWithLifecycle()
    val extras by session.extras.collectAsStateWithLifecycle()
    question?.let { JoinWindow(session, it, setName) }
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

/**
 * « Rejoindre l'opération ? » with « Votre nom » filled with the name set in the app (`tour3/04-entree-nom`, `-etats`);
 * empty, the cursor in it and a grey help when none is set, red only once a name written is erased (N16 A,
 * `tour5/05-entree-sans-nom`, `-ecrit`). « Rejoindre » waits for a name.
 * The window stays above the keyboard (k1 A, `tour7/img/02-A-clavier-ouvert`, `04-etroit-texte-max`): when it does not
 * fit there, the icon, the title and the buttons keep their place and the middle scrolls, the whole field in sight.
 */
@Composable
private fun JoinWindow(
    session: OperationSession,
    question: JoinQuestion,
    setName: () -> String,
) {
    val preset = remember(question.invitation) { setName().trim() }
    val name = question.name ?: preset
    val missing = name.isBlank()
    // Nothing is wrong yet while the field shows that no name is set (N16 A): red once a name written is erased.
    val error = missing && (question.name != null || preset.isNotEmpty())
    val focus = remember { FocusRequester() }
    val fieldInSight = remember { BringIntoViewRequester() }
    var typing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val room = roomOnScreen()
    if (preset.isEmpty()) LaunchedEffect(question.invitation) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = session::cancelJoin,
        modifier = Modifier.heightIn(max = room),
        icon = { Icon(OperationIcons.AccountTree, contentDescription = null) },
        title = { Text(stringResource(R.string.op_join_title), textAlign = TextAlign.Center) },
        text = {
            ShrinkAboveKeyboard()
            Column(
                Modifier
                    // The keyboard opening shrinks the middle: the field goes back in sight.
                    .onSizeChanged { if (typing) scope.launch { fieldInSight.bringIntoView() } }
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                        isError = error,
                        trailingIcon =
                            when {
                                error -> {
                                    {
                                        Icon(
                                            OperationIcons.ErrorMark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }

                                missing -> {
                                    null
                                }

                                else -> {
                                    { Icon(OperationIcons.Edit, contentDescription = null) }
                                }
                            },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .bringIntoViewRequester(fieldInSight)
                                .onFocusChanged {
                                    typing = it.isFocused
                                    if (typing) scope.launch { fieldInSight.bringIntoView() }
                                },
                    )
                    val colors = MaterialTheme.colorScheme
                    Text(
                        stringResource(
                            when {
                                error -> R.string.op_join_name_missing
                                preset.isEmpty() -> R.string.op_join_name_none
                                else -> R.string.op_join_name_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error) colors.error else colors.onSurfaceVariant,
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

/**
 * The window of the dialog it is drawn in shrinks to the room left above the keyboard, instead of sliding up and
 * leaving its bottom under it (k1 A).
 */
@Composable
private fun ShrinkAboveKeyboard() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val mode = window.attributes.softInputMode
        val wanted = (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) or ADJUST_RESIZE
        if (mode != wanted) window.setSoftInputMode(wanted)
    }
}

/**
 * The height left on the screen between the status bar and the keyboard (or the navigation bar), read from the screen
 * a dialog is opened from (k1 A). A dialog is measured by Compose with the whole screen in mind, keyboard or not, and
 * its own window learns late, or not at all, that the keyboard took the bottom: held to this height, a dialog with a
 * scrolling middle keeps its buttons in sight.
 */
@Composable
private fun roomOnScreen(): Dp {
    val screen = LocalView.current.rootView.height
    val density = LocalDensity.current
    val bars = WindowInsets.safeDrawing
    val room = screen - bars.getTop(density) - bars.getBottom(density)
    return if (screen > 0 && room > 0) with(density) { room.toDp() } else Dp.Unspecified
}

/** Deprecated for the windows drawn behind the system bars; a dialog window still fits them, and is resized. */
@Suppress("DEPRECATION")
private const val ADJUST_RESIZE = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

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
