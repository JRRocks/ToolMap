package fr.toolmap.operation

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationView
import fr.toolmap.draw.RouteIcons
import fr.toolmap.share.QrImage
import fr.toolmap.share.ShareIcons
import fr.toolmap.share.serverStateLabel
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.durationText
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.toolMap

/**
 * The screens of an open operation (P39.8): the page in [OperationSession.nav], its sheet and its window, and what
 * happened to this phone. The newest validated drawing of each is named in its function.
 */
@Composable
fun OperationRoot(
    session: OperationSession,
    onBack: () -> Unit,
) {
    val view by session.view.collectAsStateWithLifecycle()
    val extras by session.extras.collectAsStateWithLifecycle()
    val nav by session.nav.collectAsStateWithLifecycle()
    val now = rememberNow()
    val facts = remember(view, extras, now) { OperationFacts(view, now, extras.lastSeen, extras.selfName) }
    BackHandler { if (!session.back()) onBack() }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    // The messages at the bottom of the screen (`tour2/08-successeur`, `tour4/05-promotion-*`), one after the other.
    val message =
        extras.notices.firstOrNull {
            it is Notice.NewCreator || it is Notice.NewGroupChief || it is Notice.NewOperationChief ||
                it is Notice.RenamedByAdmin || it is Notice.NameAccepted || it is Notice.RhythmChanged ||
                it is Notice.GroupColorChanged
        }
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        val text =
            when (shown) {
                is Notice.NewCreator -> {
                    resources.getString(R.string.op_snack_creator, shown.former, shown.creator)
                }

                is Notice.NewGroupChief -> {
                    resources.getString(
                        R.string.op_snack_promoted_group,
                        shown.former,
                        shown.chief,
                        shown.group,
                    )
                }

                is Notice.NewOperationChief -> {
                    resources.getString(R.string.op_snack_promoted_op, shown.former, shown.chief)
                }

                is Notice.RenamedByAdmin -> {
                    resources.getString(R.string.op_renamed_you, shown.name)
                }

                is Notice.NameAccepted -> {
                    resources.getString(R.string.op_name_accepted, shown.name)
                }

                is Notice.RhythmChanged -> {
                    if (shown.seconds == HierarchyState.DEFAULT_POSITION_INTERVAL_S) {
                        resources.getString(R.string.op_freq_changed_minute)
                    } else {
                        resources.getString(R.string.op_freq_changed, durationText(shown.seconds))
                    }
                }

                is Notice.GroupColorChanged -> {
                    val names = resources.getStringArray(R.array.op_group_color_names)
                    resources.getString(
                        R.string.op_group_color_changed,
                        shown.group,
                        names.getOrElse(shown.color) {
                            ""
                        },
                    )
                }

                else -> {
                    null
                }
            } ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        session.consume(shown)
    }
    LaunchedEffect(view.phase) {
        if (view.phase == OperationPhase.PLACED) {
            extras.notices.filterIsInstance<Notice.GroupDeleted>().forEach(session::consume)
        }
    }
    // It left and waits for another phone to get its departure (M4 A): that card alone, nothing to open from it.
    if (view.phase == OperationPhase.LEAVING) {
        LeavingPage(view, onBack)
        return
    }
    when (val page = nav.page) {
        OpPage.Main -> {
            if (facts.waiting) {
                WaitingPage(session, facts, extras, nav, snackbar, onBack)
            } else {
                MainPage(session, facts, extras, nav, snackbar, onBack)
            }
        }

        OpPage.Admin -> {
            AdminPage(session, facts, extras)
        }

        OpPage.WaitingList -> {
            WaitingListPage(session, facts, extras)
        }

        is OpPage.GroupPage -> {
            GroupPage(session, facts, page.group)
        }

        OpPage.OperationChief -> {
            OperationChiefPage(session, facts)
        }

        OpPage.Admins -> {
            AdminsPage(session, facts)
        }

        is OpPage.GroupList -> {
            GroupListPage(session, facts, page.group)
        }

        OpPage.NameRequests -> {
            NameRequestsPage(session, facts)
        }
    }
    when (val sheet = nav.sheet) {
        OpSheet.Invite -> {
            InviteSheet(session, facts)
        }

        OpSheet.Transfer -> {
            TransferSheet(session, facts)
        }

        null -> {}

        else -> {
            AdminSheet(session, facts, sheet)
        }
    }
    nav.dialog?.let { OperationDialog(session, facts, it, nav) }
    InOperationNotices(session, facts, extras)
}

/** The screen of the operation (`tour3/08-inviter` A, `tour4/08-rappel-A`, `tour4/05-promotion-*`). */
@Composable
private fun MainPage(
    session: OperationSession,
    facts: OperationFacts,
    extras: Extras,
    nav: OperationNav,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    val self = facts.self ?: return
    val sharing by session.sharing.collectAsStateWithLifecycle()
    val reminded by session.reminded.collectAsStateWithLifecycle()
    val ensemble = facts.view.ensembleId?.let { java.lang.Long.toHexString(it) }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = facts.operationName.orEmpty(),
                subtitle =
                    stringResource(
                        R.string.op_subtitle,
                        pluralStringResource(R.plurals.op_members, facts.placedCount, facts.placedCount),
                        pluralStringResource(R.plurals.op_groups, facts.groupOrder.size, facts.groupOrder.size),
                    ),
                onBack = { if (!session.back()) onClose() },
                actions = { OperationMenu(session, facts, nav) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (facts.isCreatorHere && ensemble != null && ensemble !in reminded) {
                ReminderBanner(onTransfer = { session.sheet(OpSheet.Transfer) }, onOk = session::dismissReminder)
            }
            GroupBlock(
                rows =
                    listOf({
                        Column {
                            SelfShareLine(facts, self, sharing, session::setSharing) { session.sheet(OpSheet.MyName) }
                            facts.myNameRequest?.let { request ->
                                PendingNameLine(request, facts.serverUp, onCancel = session::withdrawNameRequest)
                            }
                        }
                    }, { FrequencyLine(facts) { session.sheet(OpSheet.Frequency()) } }),
            )
            if (facts.showsAdminTiles) {
                AdminTiles(
                    facts,
                    onAdmin = { session.open(OpPage.Admin) },
                    onInvite = { session.sheet(OpSheet.Invite) },
                )
            }
            val mine = facts.myGroup
            if (mine != null) {
                val members = facts.membersOf(mine)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(
                        stringResource(R.string.op_my_group, groupTitle(facts.groupName(mine))),
                        stringResource(R.string.op_online_count, facts.onlineIn(mine), members.size),
                        dot = facts.palette[mine],
                    )
                    val others = members.filter { it != self }
                    if (others.isNotEmpty()) GroupBlock(rows = others.map { id -> { MemberLine(facts, id) } })
                }
            }
            val groups = facts.groupOrder.filter { it != mine }
            if (groups.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(stringResource(R.string.op_whole), serverStateLabel(facts.view.server))
                    GroupBlock(rows = groups.map { g -> { GroupLine(facts, g) { session.open(OpPage.GroupList(g)) } } })
                }
            }
            if (extras.notices.isEmpty()) Spacer(Modifier.height(1.dp))
        }
    }
}

/** The ⋮ menu of the screen of the operation (`tour4/08-rappel-B`, U8 A) and the tools of the demonstration. */
@Composable
private fun OperationMenu(
    session: OperationSession,
    facts: OperationFacts,
    nav: OperationNav,
) {
    val demo by session.demo.collectAsStateWithLifecycle()
    var viewAs by remember { mutableStateOf(false) }
    val placed = facts.view.phase == OperationPhase.PLACED
    if (!placed && demo == null) return
    Box {
        IconButton(onClick = { session.menu(true) }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.share_more))
        }
        DropdownMenu(expanded = nav.menu, onDismissRequest = { session.menu(false) }) {
            if (placed && facts.mayRenameOperation) {
                // `tour5/02-operation` A (N10 A); « Changer mon nom » is the pencil of its own line (N5 B).
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.op_menu_rename_op)) },
                    leadingIcon = { Icon(OperationIcons.Edit, contentDescription = null) },
                    onClick = { session.sheet(OpSheet.RenameOperation) },
                )
            }
            if (placed && facts.isCreatorHere) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.op_menu_transfer))
                            Text(
                                stringResource(R.string.op_menu_transfer_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { Icon(OperationIcons.Key, contentDescription = null) },
                    onClick = { session.sheet(OpSheet.Transfer) },
                )
            }
            if (placed) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.op_menu_quit), color = MaterialTheme.colorScheme.error) },
                    onClick = { session.dialog(if (facts.isCreatorHere) OpDialog.CreatorQuit else OpDialog.Quit) },
                )
            }
            if (demo != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.op_demo_view_as)) },
                    onClick = {
                        session.menu(false)
                        viewAs = true
                    },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.op_demo_stop)) }, onClick = session::stopDemo)
            }
        }
    }
    if (viewAs) ViewAsWindow(session, facts, onClose = { viewAs = false })
}

/** « Voir comme » (debug tool of the demonstration): the screens from the place of another member. */
@Composable
private fun ViewAsWindow(
    session: OperationSession,
    facts: OperationFacts,
    onClose: () -> Unit,
) {
    val demo by session.demo.collectAsStateWithLifecycle()
    val world = demo ?: return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.op_demo_view_as_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((n, id) in world.ids.toSortedMap()) {
                    if (n in fr.toolmap.operation.demo.DemoWorld.OFF) continue
                    val words = facts.roleWords(id).map { roleText(it) }
                    val waiting = facts.state?.isWaiting(id) == true
                    val token = facts.token(id).copy(text = n.toString())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onClose()
                                session.viewAs(n)
                            }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OpToken(token, 32.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Membre $n", style = MaterialTheme.typography.bodyLarge)
                            val sub = if (waiting) listOf(stringResource(R.string.op_waiting)) else words
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

/** The reminder to the creator (`tour4/08-rappel-A`, T10 B, U8 A). */
@Composable
private fun ReminderBanner(
    onTransfer: () -> Unit,
    onOk: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                OperationIcons.PhoneIphone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Text(
                rich(stringResource(R.string.op_reminder)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onTransfer) {
                Text(
                    stringResource(R.string.op_reminder_transfer),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            TextButton(onClick = onOk) {
                Text(stringResource(R.string.op_reminder_ok), color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

/** « Partager ma position · Membre 5 (vous) · Nord », the pencil of its name (`tour5/02-demande-B`, N5 B), the switch. */
@Composable
private fun SelfShareLine(
    facts: OperationFacts,
    self: Long,
    sharing: Boolean,
    onToggle: (Boolean) -> Unit,
    onName: () -> Unit,
) {
    val group = facts.myGroup
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpToken(facts.token(self), 48.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(stringResource(R.string.op_share_position), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (group != null) {
                    stringResource(R.string.op_self_group, memberName(facts, self), groupTitle(facts.groupName(group)))
                } else {
                    stringResource(R.string.op_self, memberName(facts, self))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (facts.mayChangeOwnName) OwnNamePencil(onName)
        Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(12.dp))
        Switch(checked = sharing, onCheckedChange = onToggle)
    }
}

/**
 * « Administrer » and « Inviter » side by side (T9 A, `tour3/08-inviter-complet`); one above the other when a phone is
 * too narrow for both titles on one line (320 dp: « Admini… »), so that no word is cut.
 */
@Composable
private fun AdminTiles(
    facts: OperationFacts,
    onAdmin: () -> Unit,
    onInvite: () -> Unit,
) {
    val waiting = facts.waitingMembers.size
    val names = facts.nameRequestsToAnswer.size
    val pending = waiting > 0 || names > 0
    val full = facts.full
    val adminTitle = stringResource(R.string.op_tile_admin)
    val inviteTitle = stringResource(R.string.op_tile_invite)
    val admin: @Composable (Modifier) -> Unit = { modifier ->
        val colors = MaterialTheme.colorScheme
        // `tour5/02-admin` A (N4 A): « 2 en attente », « 1 nom à valider », one under the other.
        val lines =
            listOfNotNull(
                if (waiting > 0) pluralStringResource(R.plurals.op_tile_admin_waiting, waiting, waiting) else null,
                if (names > 0) pluralStringResource(R.plurals.op_tile_names, names, names) else null,
            )
        Tile(
            icon = OperationIcons.ManageAccounts,
            title = adminTitle,
            subtitle = if (pending) lines.joinToString("\n") else stringResource(R.string.op_tile_admin_roles),
            back = if (pending) colors.tertiaryContainer else MaterialTheme.toolMap.panel,
            fore = if (pending) colors.onTertiaryContainer else colors.onSurface,
            enabled = true,
            onClick = onAdmin,
            modifier = modifier,
        )
    }
    val invite: @Composable (Modifier) -> Unit = { modifier ->
        val colors = MaterialTheme.colorScheme
        Tile(
            icon = if (full) OperationIcons.PersonAddDisabled else OperationIcons.PersonAdd,
            title = inviteTitle,
            subtitle =
                if (full) {
                    stringResource(R.string.op_tile_invite_full, facts.total, facts.memberLimit)
                } else {
                    stringResource(R.string.op_count_of, facts.total, facts.memberLimit)
                },
            back = MaterialTheme.toolMap.panel,
            fore = if (full) colors.onSurfaceVariant else colors.onSurface,
            subtitleColor = if (full) colors.tertiary else null,
            enabled = !full && facts.view.mayInvite,
            onClick = onInvite,
            modifier = modifier,
        )
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest =
            maxOf(measurer.measure(adminTitle, style).size.width, measurer.measure(inviteTitle, style).size.width)
        val needed = with(density) { widest.toDp() } + TILE_CHROME
        if ((maxWidth - TILE_GAP) / 2 >= needed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                admin(Modifier.weight(1f))
                invite(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                admin(Modifier.fillMaxWidth())
                invite(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    back: Color,
    fore: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleColor: Color? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(back)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fore, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = fore, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color =
                    subtitleColor ?: fore.copy(alpha = SUBTITLE_ALPHA),
            )
        }
        if (enabled) {
            Icon(
                RouteIcons.ChevronRight,
                contentDescription = null,
                tint = fore,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** A group of « Toute l'opération »: its chief's token, « Chef : Membre 9 · 5 sur 6 en ligne » or « Sans chef ». */
@Composable
private fun GroupLine(
    facts: OperationFacts,
    group: Long,
    onClick: () -> Unit,
) {
    val chief = facts.leaderOf(group)
    val members = facts.membersOf(group)
    val online = stringResource(R.string.op_online_count, facts.onlineIn(group), members.size)
    val orange = MaterialTheme.colorScheme.tertiary
    val noChief = stringResource(R.string.op_no_chief)
    val sub =
        if (chief != null) {
            buildAnnotatedString {
                append(stringResource(R.string.op_group_chief, memberName(facts, chief)) + " · " + online)
            }
        } else {
            buildAnnotatedString {
                withStyle(SpanStyle(color = orange)) { append(noChief) }
                append(" · $online")
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chief != null) OpToken(facts.token(chief), 40.dp) else NoChiefToken(facts.palette[group], 40.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(groupTitle(facts.groupName(group)), style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(RouteIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The list of another group (`tour3/12-noms-liste` A): names in full, states with their age. */
@Composable
private fun GroupListPage(
    session: OperationSession,
    facts: OperationFacts,
    group: Long,
) {
    val members = facts.membersOf(group)
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = groupTitle(facts.groupName(group)),
                subtitle =
                    stringResource(
                        R.string.op_group_subtitle,
                        facts.operationName.orEmpty(),
                        pluralStringResource(R.plurals.op_members, members.size, members.size),
                    ),
                onBack = { session.back() },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionHeader(
                stringResource(R.string.op_members_title),
                stringResource(R.string.op_online_count, facts.onlineIn(group), members.size),
                dot = facts.palette[group],
            )
            GroupBlock(rows = members.map { id -> { MemberLine(facts, id, showAge = true, minHeight = 64.dp) } })
        }
    }
}

/** Waiting for a group (`tour4/01-attente` A, U1 A; `tour3/06-supprimer-groupe-apres` for the banner). */
@Composable
private fun WaitingPage(
    session: OperationSession,
    facts: OperationFacts,
    extras: Extras,
    nav: OperationNav,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    val self = facts.self ?: return
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = facts.operationName.orEmpty(),
                subtitle = stringResource(R.string.op_subtitle_waiting),
                onBack = { if (!session.back()) onClose() },
                actions = { OperationMenu(session, facts, nav) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            extras.notices.filterIsInstance<Notice.GroupDeleted>().firstOrNull()?.let { deleted ->
                InfoBox(
                    OperationIcons.Info,
                    rich(
                        stringResource(
                            R.string.op_group_deleted_banner,
                            deleted.group,
                        ),
                    ),
                    BoxTone.ORANGE,
                )
            }
            GroupBlock(
                rows =
                    listOf({
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 72.dp)
                                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OpToken(facts.token(self), 48.dp)
                                Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                                    Text(
                                        stringResource(R.string.op_my_position),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        stringResource(R.string.op_my_position_sub, memberName(facts, self)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // `tour5/04-attente-nom` (N11 B): the pencil of its name instead of the lock.
                                if (facts.mayChangeOwnName) {
                                    OwnNamePencil { session.sheet(OpSheet.MyName) }
                                } else {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            facts.myNameRequest?.let { request ->
                                PendingNameLine(request, facts.serverUp, onCancel = session::withdrawNameRequest)
                            }
                        }
                    }),
            )
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        OperationIcons.Hourglass,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Text(
                    stringResource(R.string.op_wait_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(if (extras.waitingAgain) R.string.op_wait_text_again else R.string.op_wait_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )
            }
            Caption(stringResource(R.string.op_wait_until))
            GroupBlock(
                rows =
                    listOf(
                        { WaitLine(OperationIcons.VisibilityOff, stringResource(R.string.op_wait_no_positions)) },
                        { WaitLine(OperationIcons.SpeakerNotesOff, stringResource(R.string.op_wait_nothing)) },
                        {
                            WaitLine(
                                OperationIcons.Shield,
                                stringResource(R.string.op_wait_admins_see),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        },
                    ),
            )
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { session.dialog(OpDialog.Quit) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        stringResource(R.string.op_quit),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    stringResource(R.string.op_wait_only_way),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun WaitLine(
    icon: ImageVector,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 18.dp))
    }
}

/**
 * This phone left and waits for another one to get its departure (P39.14.5, M4 A, `tour8/img/14`): the name of the
 * operation and « En train de partir » at the top, no menu; one card: the wait, the day everything is wiped at the latest,
 * nothing that goes or comes any more. Nothing to touch: the departure goes again by itself ([OperationService]).
 */
@Composable
private fun LeavingPage(
    view: OperationView,
    onClose: () -> Unit,
) {
    val name = view.operationName.orEmpty()
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(title = name, subtitle = stringResource(R.string.op_subtitle_leaving), onBack = onClose)
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            GroupBlock(
                rows =
                    listOf(
                        {
                            Column(
                                Modifier.fillMaxWidth().padding(
                                    start = 24.dp,
                                    end = 24.dp,
                                    top = 22.dp,
                                    bottom = 20.dp,
                                ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        OperationIcons.HourglassTop,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                                Text(
                                    stringResource(R.string.op_leaving_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(top = 15.dp),
                                )
                                Text(
                                    stringResource(R.string.op_leaving_text, name),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        },
                        {
                            LeavingLine(
                                OperationIcons.Event,
                                stringResource(R.string.op_leaving_until, leavingDay(view.leavingUntilMs ?: 0L)),
                                stringResource(R.string.op_leaving_until_sub),
                            )
                        },
                        {
                            LeavingLine(
                                OperationIcons.LocationOff,
                                stringResource(R.string.op_leaving_position),
                                stringResource(R.string.op_leaving_position_sub),
                            )
                        },
                    ),
            )
        }
    }
}

@Composable
private fun LeavingLine(
    icon: ImageVector,
    title: String,
    detail: String,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 61.dp).padding(start = 17.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.padding(start = 21.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** « Invitation » (`tour3/08-inviter-invitation`): the code, what waits for the arrival, the detailed count. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    val invitation by session.invitation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooser = stringResource(R.string.share_link_chooser)
    ModalBottomSheet(
        onDismissRequest = { session.sheet(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.op_invite_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            val code = invitation
            if (code == null) {
                Box(Modifier.padding(vertical = 16.dp).size(QR_SIZE), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                QrImage(code.second, modifier = Modifier.padding(vertical = 16.dp).size(QR_SIZE))
            }
            Text(
                stringResource(R.string.op_invite_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(
                    R.string.op_invite_count,
                    facts.total,
                    facts.memberLimit,
                    pluralStringResource(R.plurals.op_placed, facts.placedCount, facts.placedCount),
                    waitingCount(facts.waitingMembers.size),
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.toolMap.tile)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Button(
                onClick = {
                    val link = code?.first ?: return@Button
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, chooser))
                },
                enabled = code != null,
                modifier =
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp).heightIn(min = 52.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_send_link))
            }
            TextButton(
                onClick = { session.sheet(null) },
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** « Transmettre le rôle de créateur » (`tour4/08-rappel-A`; none to choose: `tour3/05-successeur-aucun`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    val candidates = facts.successors
    var chosen by remember(candidates) { mutableStateOf(candidates.firstOrNull()) }
    ModalBottomSheet(
        onDismissRequest = { session.sheet(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            SheetTitle(stringResource(R.string.op_transfer_title), stringResource(R.string.op_transfer_text))
            if (candidates.isEmpty()) {
                NoOtherAdmin(onName = { session.sheet(OpSheet.NameAdmin) })
            } else {
                Caption(stringResource(R.string.op_admins_count, candidates.size), start = 16.dp)
                for (id in candidates) {
                    RadioLine(selected = chosen == id, onClick = { chosen = id }) { CandidateLine(facts, id) }
                }
                val group = facts.myGroup
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (group != null) {
                        NoteLine(
                            Icons.Filled.Check,
                            stringResource(R.string.op_transfer_stay, groupTitle(facts.groupName(group))),
                        )
                    }
                    NoteLine(OperationIcons.PhoneIphone, stringResource(R.string.op_transfer_phone))
                }
                val pick = chosen
                Button(
                    onClick = { if (pick != null) session.transfer(pick) },
                    enabled = pick != null,
                    modifier =
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp).heightIn(min = 52.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.op_transfer_button,
                            pick?.let {
                                memberName(facts, it)
                            }.orEmpty(),
                        ),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { session.sheet(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/** « Aucun autre administrateur » (`tour3/05-successeur-aucun`). */
@Composable
fun NoOtherAdmin(onName: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.toolMap.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                OperationIcons.Shield,
                contentDescription = null,
                tint = MaterialTheme.toolMap.onTile,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            stringResource(R.string.op_no_admin_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.op_no_admin_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onName, modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.op_name_admin))
        }
    }
}

/** A candidate of a sheet: token, name, « Groupe Nord · Adjoint · Administrateur ». */
@Composable
fun CandidateLine(
    facts: OperationFacts,
    id: Long,
    note: String? = null,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(Color.Transparent)) { OpToken(facts.token(id), 40.dp, Modifier.padding(end = 2.dp)) }
        Column(Modifier.padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    memberTitle(facts, id),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                if (enabled) RoleSign(facts, id)
            }
            val group = facts.groupOf(id)
            val words =
                note?.let(::listOf) ?: (
                    listOfNotNull(group?.let { groupTitle(facts.groupName(it)) }) +
                        facts.roleWords(id).filter { it != RoleWord.CREATOR }.map { roleText(it) }
                )
            if (words.isNotEmpty()) {
                Text(
                    words.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
    }
}

@Composable
private fun NoteLine(
    icon: ImageVector,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What happened while in the operation, as windows (`tour3/07-remise-attente-confirmation`, `09-promotion-*`). */
@Composable
private fun InOperationNotices(
    session: OperationSession,
    facts: OperationFacts,
    extras: Extras,
) {
    val notice =
        extras.notices.firstOrNull {
            it is Notice.Unassigned || it is Notice.Creator || it is Notice.GroupChief || it is Notice.OperationChief ||
                it is Notice.NameRefused || it is Notice.AlreadyAnswered
        } ?: return
    val named = nameNoticeText(notice)
    val (icon, title, text) =
        when (notice) {
            is Notice.Unassigned -> {
                Triple(
                    OperationIcons.Hourglass,
                    stringResource(R.string.op_again_title),
                    stringResource(R.string.op_again_text, notice.group),
                )
            }

            is Notice.Creator -> {
                Triple(
                    OperationIcons.Key,
                    stringResource(R.string.op_you_creator_title),
                    if (notice.left) {
                        stringResource(R.string.op_you_creator_text, notice.former)
                    } else {
                        stringResource(R.string.op_you_creator_rights)
                    },
                )
            }

            is Notice.GroupChief -> {
                Triple(
                    Icons.Filled.Star,
                    stringResource(
                        R.string.op_you_chief_title,
                        notice.group,
                    ),
                    stringResource(R.string.op_you_chief_text, notice.former),
                )
            }

            is Notice.OperationChief -> {
                Triple(
                    OperationIcons.Crown,
                    stringResource(R.string.op_you_op_chief_title),
                    stringResource(R.string.op_you_op_chief_text, notice.former),
                )
            }

            is Notice.NameRefused, is Notice.AlreadyAnswered -> {
                Triple(
                    if (notice is Notice.NameRefused) OperationIcons.Edit else OperationIcons.Info,
                    named?.first.orEmpty(),
                    named?.second?.text.orEmpty(),
                )
            }

            else -> {
                return
            }
        }
    if (facts.view.phase == OperationPhase.ENDED) return
    AlertDialog(
        onDismissRequest = { session.consume(notice) },
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = { Text(named?.second ?: androidx.compose.ui.text.AnnotatedString(text)) },
        confirmButton = {
            TextButton(onClick = { session.consume(notice) }) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

private val QR_SIZE = 192.dp
private const val SUBTITLE_ALPHA = 0.8f

/** What a tile holds besides its title: padding 14 + icon 28 + 12, chevron 24 + padding 6, and 2 to spare. */
private val TILE_CHROME = 86.dp
private val TILE_GAP = 8.dp
private const val DISABLED_ALPHA = 0.45f

/** The root of the operation is a unit too: the operation itself. */
internal const val ROOT = HierarchyState.ROOT
