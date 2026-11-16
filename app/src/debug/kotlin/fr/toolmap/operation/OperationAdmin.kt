package fr.toolmap.operation

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.draw.RouteIcons
import fr.toolmap.share.ageText
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.toolMap

/** « Administrer » (`tour2/04-admin-sommaire`, `04-admin-limites`; `tour3/06-supprimer-groupe-apres`). */
@Composable
fun AdminPage(
    session: OperationSession,
    facts: OperationFacts,
    extras: Extras,
) {
    val name = facts.operationName.orEmpty()
    AdminScaffold(
        stringResource(R.string.op_admin_title),
        stringResource(R.string.op_admin_subtitle, name, facts.total, facts.memberLimit),
        session,
    ) {
        val waiting = facts.waitingMembers
        val requests = facts.nameRequestsToAnswer
        if (waiting.isNotEmpty() || requests.isNotEmpty()) {
            val fore = MaterialTheme.colorScheme.onTertiaryContainer
            // « En attente », then « Noms à valider » (`tour5/02-admin` A, N4 A): what waits for a decision, in orange.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                if (waiting.isNotEmpty()) {
                    val names = waiting.map { memberName(facts, it) }
                    OrangeRow(
                        OperationIcons.Hourglass,
                        stringResource(R.string.op_waiting),
                        if (names.size <= NAMED_WAITING) {
                            stringResource(R.string.op_waiting_names, names.joinToString(", "))
                        } else {
                            stringResource(R.string.op_waiting_many, names.size)
                        },
                        waiting.size,
                        fore,
                    ) { session.open(OpPage.WaitingList) }
                }
                if (waiting.isNotEmpty() && requests.isNotEmpty()) {
                    HorizontalDivider(color = fore.copy(alpha = ORANGE_DIVIDER_ALPHA))
                }
                if (requests.isNotEmpty()) NameRequestsRow(facts) { session.open(OpPage.NameRequests) }
            }
        }
        Caption(stringResource(R.string.op_roles))
        val state = facts.state
        val creator = state?.creator
        val leader = state?.leader
        val deputy = state?.deputy
        GroupBlock(
            rows =
                listOf(
                    {
                        SummaryLine(
                            OperationIcons.Key,
                            stringResource(R.string.op_role_creator),
                            creator?.let { memberName(facts, it) }.orEmpty(),
                        )
                    },
                    {
                        val admins = facts.adminList
                        SummaryLine(
                            OperationIcons.Shield,
                            stringResource(R.string.op_admins),
                            admins.map { id ->
                                val name = memberName(facts, id)
                                if (id == facts.self) stringResource(R.string.op_self, name) else name
                            }.joinToString(", "),
                            count = admins.size,
                            onClick = { session.open(OpPage.Admins) },
                        )
                    },
                    {
                        SummaryLine(
                            OperationIcons.Crown,
                            stringResource(R.string.op_role_op_chief),
                            when {
                                leader != null && deputy != null -> {
                                    stringResource(
                                        R.string.op_op_chief_and_deputy,
                                        memberName(facts, leader),
                                        memberName(facts, deputy),
                                    )
                                }

                                leader != null -> {
                                    memberName(facts, leader)
                                }

                                else -> {
                                    stringResource(R.string.op_no_chief_named)
                                }
                            },
                            count = listOfNotNull(leader, deputy).size,
                            onClick = { session.open(OpPage.OperationChief) },
                        )
                    },
                ),
        )
        SectionHeader(
            stringResource(R.string.op_groups_title),
            stringResource(R.string.op_count_of, facts.groupOrder.size, facts.groupLimit),
        )
        GroupBlock(
            rows =
                facts.groupOrder.map<Long, @Composable () -> Unit> { g ->
                    {
                        val chief = facts.leaderOf(g)
                        val dep = facts.deputyOf(g)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable { session.open(OpPage.GroupPage(g)) }
                                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GroupDot(facts.palette[g], 24.dp)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(groupTitle(facts.groupName(g)), style = MaterialTheme.typography.bodyLarge)
                                when {
                                    chief != null && dep != null -> {
                                        Sub(
                                            stringResource(
                                                R.string.op_group_chief_and_deputy,
                                                memberName(facts, chief),
                                                memberName(facts, dep),
                                            ),
                                        )
                                    }

                                    chief != null -> {
                                        Sub(stringResource(R.string.op_group_chief, memberName(facts, chief)))
                                    }

                                    else -> {
                                        Sub(stringResource(R.string.op_no_chief), MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                            Count(facts.membersOf(g).size, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } +
                    listOf<@Composable () -> Unit>({
                        val full = facts.groupsFull
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(enabled = !full) { session.sheet(OpSheet.NewGroup) }
                                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val colors = MaterialTheme.colorScheme
                            val tint = if (full) colors.outline else colors.onSurface
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(
                                    stringResource(R.string.op_new_group),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = tint,
                                )
                                if (full) {
                                    Sub(
                                        stringResource(
                                            R.string.op_new_group_full,
                                            facts.groupOrder.size,
                                            facts.groupLimit,
                                        ),
                                    )
                                }
                            }
                        }
                    }),
        )
        if (facts.full) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    OperationIcons.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    rich(stringResource(R.string.op_full_note, facts.total, facts.memberLimit)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** « En attente » (`tour2/03-attente-admin`): who came with the invitation, the most recent first, each with « Placer ». */
@Composable
fun WaitingListPage(
    session: OperationSession,
    facts: OperationFacts,
    extras: Extras,
) {
    val waiting = facts.waitingMembers.sortedByDescending { extras.arrivals[it] ?: 0L }
    AdminScaffold(
        stringResource(R.string.op_waiting),
        stringResource(
            R.string.op_group_subtitle,
            facts.operationName.orEmpty(),
            pluralStringResource(R.plurals.op_persons, waiting.size, waiting.size),
        ),
        session,
    ) {
        InfoBox(OperationIcons.Hourglass, rich(stringResource(R.string.op_waiting_box)), BoxTone.ORANGE)
        if (waiting.isNotEmpty()) {
            GroupBlock(
                rows =
                    waiting.map { id ->
                        {
                            val arrived = extras.arrivals[id]
                            MemberLine(
                                facts,
                                id,
                                subtitle =
                                    arrived?.let { stringResource(R.string.op_arrived, ageText(it, facts.nowMs)) }
                                        ?: stringResource(R.string.op_waiting),
                                minHeight = 72.dp,
                                trailing = {
                                    FilledTonalButton(
                                        onClick = { session.sheet(OpSheet.Place(id)) },
                                        modifier = Modifier.padding(end = 8.dp),
                                    ) {
                                        Text(stringResource(R.string.op_place))
                                    }
                                },
                            )
                        }
                    },
            )
        }
    }
}

/** The page of a group (`tour2/04-admin-groupe`, `04-admin-sans-chef`; `tour3/06-supprimer-groupe`, `09-promotion-adjoint`). */
@Composable
fun GroupPage(
    session: OperationSession,
    facts: OperationFacts,
    group: Long,
) {
    if (facts.state?.groups?.containsKey(group) != true) return
    val members = facts.membersOf(group)
    val chief = facts.leaderOf(group)
    val deputy = facts.deputyOf(group)
    AdminScaffold(
        groupTitle(facts.groupName(group)),
        stringResource(
            R.string.op_group_subtitle,
            facts.operationName.orEmpty(),
            pluralStringResource(R.plurals.op_members, members.size, members.size),
        ),
        session,
    ) {
        if (chief == null) InfoBox(OperationIcons.Info, rich(stringResource(R.string.op_no_chief_box)), BoxTone.ORANGE)
        Caption(stringResource(R.string.op_chief_and_deputy))
        val rows = ArrayList<@Composable () -> Unit>()
        val chiefRow: @Composable () -> Unit =
            if (chief != null) {
                {
                    PostLine(facts, chief, stringResource(R.string.op_role_group_chief)) {
                        session.sheet(OpSheet.Nominate(group, Post.LEADER))
                    }
                }
            } else {
                {
                    VacantLine(
                        noChief = true,
                        palette = facts.palette[group],
                        title = stringResource(R.string.op_no_chief),
                        sub = stringResource(R.string.op_no_chief_named),
                    ) {
                        session.sheet(OpSheet.Nominate(group, Post.LEADER))
                    }
                }
            }
        rows.add(chiefRow)
        if (chief != null || deputy != null) {
            val deputyRow: @Composable () -> Unit =
                if (deputy != null) {
                    {
                        PostLine(facts, deputy, stringResource(R.string.op_role_deputy_same)) {
                            session.sheet(OpSheet.Nominate(group, Post.DEPUTY))
                        }
                    }
                } else {
                    {
                        VacantLine(
                            noChief = false,
                            palette = null,
                            title = stringResource(R.string.op_no_deputy),
                            sub = stringResource(R.string.op_role_group_deputy_long),
                        ) {
                            session.sheet(OpSheet.Nominate(group, Post.DEPUTY))
                        }
                    }
                }
            rows.add(deputyRow)
        }
        GroupBlock(rows = rows)
        SectionHeader(
            stringResource(R.string.op_members_title),
            pluralStringResource(R.plurals.op_members, members.size, members.size),
            dot = facts.palette[group],
        )
        if (members.isNotEmpty()) {
            GroupBlock(
                rows =
                    members.map { id ->
                        {
                            MemberLine(
                                facts,
                                id,
                                subtitle = rolesLine(facts, id),
                                trailing = {
                                    IconButton(onClick = { session.sheet(OpSheet.Member(id)) }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = memberName(facts, id))
                                    }
                                },
                            )
                        }
                    },
            )
        }
        OutlinedButton(
            onClick = { session.sheet(OpSheet.AddMembers(group)) },
            modifier = Modifier.heightIn(min = 52.dp),
        ) {
            Icon(OperationIcons.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.op_add_members))
        }
        // N12 A (`tour5/02-groupe` A): two written lines at the bottom of the page instead of two icons in the bar.
        GroupBlock(
            rows =
                listOf(
                    {
                        ActionLine(
                            OperationIcons.Edit,
                            stringResource(R.string.op_group_rename),
                            null,
                            start = 16.dp,
                        ) { session.sheet(OpSheet.EditGroup(group)) }
                    },
                    {
                        ActionLine(
                            OperationIcons.Delete,
                            stringResource(R.string.op_group_delete),
                            null,
                            color = MaterialTheme.colorScheme.error,
                            chevron = false,
                            start = 16.dp,
                        ) { session.dialog(OpDialog.DeleteGroup(group)) }
                    },
                ),
        )
    }
}

/** The page of the role « Chef de l'opération » (`tour2/04-admin-chef`). */
@Composable
fun OperationChiefPage(
    session: OperationSession,
    facts: OperationFacts,
) {
    val leader = facts.state?.leader
    val deputy = facts.state?.deputy
    AdminScaffold(stringResource(R.string.op_role_op_chief), facts.operationName.orEmpty(), session) {
        InfoBox(OperationIcons.Crown, rich(stringResource(R.string.op_op_chief_box)), BoxTone.TEAL)
        Caption(stringResource(R.string.op_chief))
        GroupBlock(
            rows =
                listOf<@Composable () -> Unit>(
                    if (leader != null) {
                        {
                            PostLine(
                                facts,
                                leader,
                                facts.groupOf(leader)?.let {
                                    groupTitle(facts.groupName(it))
                                }.orEmpty(),
                            ) {
                                session.sheet(OpSheet.Nominate(ROOT, Post.LEADER))
                            }
                        }
                    } else {
                        {
                            VacantLine(
                                noChief = true,
                                palette = null,
                                title = stringResource(R.string.op_no_chief),
                                sub = stringResource(R.string.op_no_chief_named),
                            ) {
                                session.sheet(OpSheet.Nominate(ROOT, Post.LEADER))
                            }
                        }
                    },
                ),
        )
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
            Text(
                stringResource(R.string.op_deputy),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.op_one_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GroupBlock(
            rows =
                listOf<@Composable () -> Unit>(
                    if (deputy != null) {
                        {
                            val group = facts.groupOf(deputy)?.let { groupTitle(facts.groupName(it)) }
                            PostLine(
                                facts,
                                deputy,
                                group?.let { stringResource(R.string.op_same_rights, it) }
                                    ?: stringResource(R.string.op_role_deputy_same),
                            ) {
                                session.sheet(OpSheet.Nominate(ROOT, Post.DEPUTY))
                            }
                        }
                    } else {
                        {
                            VacantLine(
                                noChief = false,
                                palette = null,
                                title = stringResource(R.string.op_no_deputy),
                                sub = stringResource(R.string.op_role_op_deputy),
                            ) {
                                session.sheet(OpSheet.Nominate(ROOT, Post.DEPUTY))
                            }
                        }
                    },
                ),
        )
        Column(Modifier.padding(top = 8.dp)) {
            if (leader != null) {
                TextButton(onClick = { session.setPost(ROOT, Post.LEADER, null) }) {
                    Text(stringResource(R.string.op_leave_vacant))
                }
            }
            if (deputy != null) {
                TextButton(onClick = { session.setPost(ROOT, Post.DEPUTY, null) }) {
                    Text(stringResource(R.string.op_remove_deputy))
                }
            }
        }
    }
}

/** « Administrateurs » (`tour2/05-admins-createur`, `05-admins-retirer`). */
@Composable
fun AdminsPage(
    session: OperationSession,
    facts: OperationFacts,
) {
    val creatorHere = facts.isCreatorHere
    val admins = facts.adminList
    AdminScaffold(stringResource(R.string.op_admins), facts.operationName.orEmpty(), session) {
        if (creatorHere) {
            InfoBox(
                OperationIcons.Shield,
                rich(stringResource(R.string.op_admins_box_creator)),
                BoxTone.TEAL,
            )
        }
        SectionHeader(stringResource(R.string.op_admins), admins.size.toString())
        GroupBlock(
            rows =
                admins.map { id ->
                    {
                        MemberLine(
                            facts,
                            id,
                            you = id == facts.self,
                            subtitle = adminLine(facts, id),
                            minHeight = 64.dp,
                            trailing = {
                                if (creatorHere && id != facts.self) {
                                    TextButton(onClick = { session.dialog(OpDialog.RemoveAdmin(id)) }) {
                                        Text(
                                            stringResource(R.string.op_remove),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                },
        )
        if (creatorHere) {
            OutlinedButton(onClick = { session.sheet(OpSheet.NameAdmin) }, modifier = Modifier.heightIn(min = 52.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.op_name_admin))
            }
        } else {
            val creator = facts.state?.creator
            InfoBox(
                Icons.Filled.Lock,
                rich(stringResource(R.string.op_admins_box_admin, creator?.let { memberName(facts, it) }.orEmpty())),
                BoxTone.GREY,
            )
        }
    }
}

/** The sheets of the administration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSheet(
    session: OperationSession,
    facts: OperationFacts,
    sheet: OpSheet,
) {
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
            when (sheet) {
                is OpSheet.Place -> {
                    PlaceSheet(session, facts, sheet.member)
                }

                is OpSheet.AddMembers -> {
                    AddMembersSheet(session, facts, sheet.group)
                }

                is OpSheet.Nominate -> {
                    NominateSheet(session, facts, sheet.unit, sheet.post)
                }

                OpSheet.NameAdmin -> {
                    NameAdminSheet(session, facts)
                }

                is OpSheet.Member -> {
                    MemberSheet(session, facts, sheet.member)
                }

                is OpSheet.PostChoice -> {
                    PostChoiceSheet(session, facts, sheet.member)
                }

                OpSheet.NewGroup -> {
                    NewGroupSheet(session, facts)
                }

                is OpSheet.EditGroup -> {
                    RenameGroupSheet(session, facts, sheet.group)
                }

                is OpSheet.RenameMember -> {
                    RenameMemberSheet(session, facts, sheet.member)
                }

                OpSheet.MyName -> {
                    MyNameSheet(session, facts)
                }

                OpSheet.RenameOperation -> {
                    RenameOperationSheet(session, facts)
                }

                OpSheet.Successor -> {
                    SuccessorSheet(session, facts)
                }

                OpSheet.Invite, OpSheet.Transfer -> {}
            }
        }
    }
}

/** « Placer Membre 21 · Dans quel groupe ? » (`tour2/03-attente-admin`); also « Changer de groupe ». */
@Composable
private fun PlaceSheet(
    session: OperationSession,
    facts: OperationFacts,
    member: Long,
) {
    val current = facts.groupOf(member)
    var chosen by remember(member) { mutableStateOf(current ?: facts.groupOrder.firstOrNull()) }
    SheetTitle(
        stringResource(R.string.op_place_title, memberName(facts, member)),
        stringResource(R.string.op_place_question),
    )
    for (g in facts.groupOrder) {
        RadioLine(selected = chosen == g, onClick = { chosen = g }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupDot(facts.palette[g], 24.dp)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(groupTitle(facts.groupName(g)), style = MaterialTheme.typography.bodyLarge)
                    val count =
                        pluralStringResource(R.plurals.op_members, facts.membersOf(g).size, facts.membersOf(g).size)
                    val chief = facts.leaderOf(g)
                    Sub(
                        if (chief != null) {
                            stringResource(R.string.op_place_group_chief, count, memberName(facts, chief))
                        } else {
                            stringResource(R.string.op_place_group_no_chief, count)
                        },
                    )
                }
            }
        }
    }
    val pick = chosen
    SheetButton(
        text = pick?.let { stringResource(R.string.op_place_button, groupTitle(facts.groupName(it))) }.orEmpty(),
        enabled = pick != null && pick != current,
    ) { if (pick != null) session.place(member, pick) }
    CancelLine(session)
}

/** « Ajouter au Groupe Nord » (`tour2/04-admin-groupe`): who waits, and the members of the other groups. */
@Composable
private fun AddMembersSheet(
    session: OperationSession,
    facts: OperationFacts,
    group: Long,
) {
    val candidates = facts.waitingMembers + facts.groupOrder.filter { it != group }.flatMap { facts.membersOf(it) }
    var chosen by remember(group) { mutableStateOf(emptySet<Long>()) }
    SheetTitle(
        stringResource(R.string.op_add_title, groupTitle(facts.groupName(group))),
        stringResource(R.string.op_add_sub),
    )
    for (id in candidates) {
        val from = facts.groupOf(id)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { chosen = if (id in chosen) chosen - id else chosen + id }
                .padding(start = 12.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = id in chosen, onCheckedChange = { chosen = if (it) chosen + id else chosen - id })
            OpToken(facts.token(id), 36.dp, Modifier.padding(start = 4.dp))
            Text(
                memberName(facts, id),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 14.dp),
            )
            Text(
                from?.let { groupTitle(facts.groupName(it)) } ?: stringResource(R.string.op_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val moving = chosen.filter { facts.groupOf(it) != null }
    if (moving.isNotEmpty()) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (id in moving) {
                val from = facts.groupOf(id)
                InfoBox(
                    OperationIcons.SwapHoriz,
                    rich(
                        stringResource(
                            R.string.op_moves,
                            memberName(facts, id),
                            groupTitle(
                                from?.let {
                                    facts.groupName(it)
                                },
                            ),
                        ),
                    ),
                    BoxTone.ORANGE,
                )
            }
        }
    }
    SheetButton(
        pluralStringResource(R.plurals.op_add_button, chosen.size, chosen.size),
        enabled = chosen.isNotEmpty(),
    ) {
        session.addMembers(group, candidates.filter { it in chosen })
    }
    CancelLine(session)
}

/** Who holds a post (`tour2/04-admin-chef`): a group's chief or deputy among its members, or the operation's. */
@Composable
private fun NominateSheet(
    session: OperationSession,
    facts: OperationFacts,
    unit: Long,
    post: Post,
) {
    val operation = unit == ROOT
    val holder = if (post == Post.LEADER) facts.leaderOf(unit) else facts.deputyOf(unit)
    val other = if (post == Post.LEADER) facts.deputyOf(unit) else facts.leaderOf(unit)
    var chosen by remember(unit, post) { mutableStateOf(holder) }
    val candidates = if (operation) facts.operationPostCandidates else facts.membersOf(unit)
    if (operation) {
        SheetTitle(
            stringResource(if (post == Post.LEADER) R.string.op_role_op_chief else R.string.op_role_op_deputy),
            stringResource(R.string.op_keeps_roles),
        )
    } else {
        val role = if (post == Post.LEADER) R.string.op_role_group_chief else R.string.op_role_group_deputy_long
        SheetTitle(stringResource(role), stringResource(R.string.op_nominate_sub, groupTitle(facts.groupName(unit))))
    }
    for (id in candidates) {
        // The chief cannot be its own deputy (« Déjà chef de ce groupe »).
        val blocked = post == Post.DEPUTY && id == other
        RadioLine(selected = chosen == id, enabled = !blocked, onClick = { chosen = id }) {
            CandidateLine(
                facts,
                id,
                note =
                    if (blocked) {
                        stringResource(if (operation) R.string.op_role_op_chief else R.string.op_already_chief)
                    } else if (!operation) {
                        ""
                    } else {
                        null
                    },
                enabled = !blocked,
            )
        }
    }
    if (!operation) {
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        RadioLine(selected = chosen == null, onClick = { chosen = null }) {
            Text(
                stringResource(if (post == Post.LEADER) R.string.op_nobody_chief else R.string.op_nobody_deputy),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    val pick = chosen
    val label =
        when {
            pick == null -> {
                stringResource(R.string.op_group_save)
            }

            operation && post == Post.LEADER -> {
                stringResource(
                    R.string.op_nominate_op_chief_button,
                    memberName(facts, pick),
                )
            }

            operation -> {
                stringResource(R.string.op_nominate_op_deputy_button, memberName(facts, pick))
            }

            post == Post.LEADER -> {
                stringResource(R.string.op_nominate_chief_button, memberName(facts, pick))
            }

            else -> {
                stringResource(R.string.op_nominate_deputy_button, memberName(facts, pick))
            }
        }
    SheetButton(label, enabled = pick != holder && !(operation && pick == null)) { session.setPost(unit, post, pick) }
    CancelLine(session)
}

/** « Nommer un administrateur » (`tour2/05-admins-createur`): among the members placed in a group. */
@Composable
private fun NameAdminSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Long?>(null) }
    SheetTitle(stringResource(R.string.op_name_admin), stringResource(R.string.op_keeps_roles))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(R.string.op_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
    val names = facts.adminCandidates.associateWith { facts.name(it).orEmpty() }
    for (id in facts.adminCandidates.filter { names.getValue(it).contains(query.trim(), ignoreCase = true) }) {
        val already = facts.isAdmin(id)
        RadioLine(selected = chosen == id, enabled = !already, onClick = { chosen = id }) {
            CandidateLine(
                facts,
                id,
                note = if (already) stringResource(R.string.op_already_admin) else null,
                enabled = !already,
            )
        }
    }
    val pick = chosen
    SheetButton(
        pick?.let { stringResource(R.string.op_name_admin_button, memberName(facts, it)) }
            ?: stringResource(R.string.op_name_admin),
        enabled = pick != null,
    ) {
        if (pick != null) session.addAdmin(pick)
    }
    CancelLine(session)
}

/** The sheet of a member (`tour2/05-retirer-membre`, `tour3/07-remise-attente-fiche`, `07-remise-attente-adjoint`). */
@Composable
private fun MemberSheet(
    session: OperationSession,
    facts: OperationFacts,
    member: Long,
) {
    if (facts.member(member) == null) return
    val gestures = facts.gestures(member)
    val group = facts.groupOf(member)
    val groupName = group?.let { groupTitle(facts.groupName(it)) }
    val creator = facts.state?.creator?.let { memberName(facts, it) }.orEmpty()
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpToken(facts.token(member), 56.dp)
        Column(Modifier.padding(start = 16.dp)) {
            Text(memberName(facts, member), style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(
                    groupName,
                    facts.postWord(member)?.let { roleText(it) } ?: stringResource(R.string.op_role_member),
                    fr.toolmap.share.peerStateLabel(facts.peerState(member)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // N14 A (`tour5/02-membre` A): « Renommer » first, read like the other gestures.
    if (member != facts.self && facts.mayRename(member)) {
        ActionLine(OperationIcons.Edit, stringResource(R.string.op_rename), stringResource(R.string.op_rename_sub)) {
            session.sheet(OpSheet.RenameMember(member))
        }
    }
    if (gestures.changeGroup && groupName != null) {
        ActionLine(
            OperationIcons.SwapHoriz,
            stringResource(R.string.op_change_group),
            stringResource(R.string.op_change_group_sub, groupName),
        ) {
            session.sheet(OpSheet.Place(member))
        }
    }
    when (gestures.post) {
        PostOffer.CHOOSE -> {
            ActionLine(
                Icons.Filled.Star,
                stringResource(R.string.op_nominate_post),
                stringResource(R.string.op_nominate_post_sub, groupName.orEmpty()),
            ) {
                session.sheet(OpSheet.PostChoice(member))
            }
        }

        PostOffer.DEPUTY -> {
            val chief = group?.let { facts.leaderOf(it) }
            ActionLine(
                Icons.Filled.Star,
                stringResource(R.string.op_name_chief_of, groupName.orEmpty()),
                chief?.let { stringResource(R.string.op_instead_of, memberName(facts, it)) },
            ) { if (group != null) session.setPost(group, Post.LEADER, member) }
            ActionLine(
                OperationIcons.StarBorder,
                stringResource(R.string.op_remove_deputy_post),
                stringResource(R.string.op_stays_member),
            ) {
                if (group != null) session.setPost(group, Post.DEPUTY, null)
            }
        }

        PostOffer.NONE -> {}
    }
    if (gestures.unassign) {
        ActionLine(
            OperationIcons.Hourglass,
            stringResource(R.string.op_unassign),
            stringResource(R.string.op_unassign_sub),
            iconTint = MaterialTheme.colorScheme.tertiary,
        ) { session.dialog(OpDialog.Unassign(member)) }
    }
    if (gestures.nameAdmin != Gesture.HIDDEN || gestures.remove != Gesture.HIDDEN) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
    when (gestures.nameAdmin) {
        Gesture.ALLOWED -> {
            ActionLine(OperationIcons.Shield, stringResource(R.string.op_name_admin_row), null) {
                session.addAdmin(member)
            }
        }

        Gesture.LOCKED -> {
            LockedLine(
                OperationIcons.Shield,
                stringResource(R.string.op_name_admin_row),
                stringResource(R.string.op_reserved_creator, creator),
            )
        }

        Gesture.HIDDEN -> {}
    }
    when (gestures.remove) {
        Gesture.ALLOWED -> {
            ActionLine(
                OperationIcons.PersonRemove,
                stringResource(R.string.op_remove_member),
                null,
                color = MaterialTheme.colorScheme.error,
                chevron = false,
            ) { session.dialog(OpDialog.RemoveMember(member)) }
        }

        Gesture.LOCKED -> {
            LockedLine(
                OperationIcons.PersonRemove,
                stringResource(R.string.op_remove_member),
                stringResource(R.string.op_reserved_creator, creator),
            )
        }

        Gesture.HIDDEN -> {}
    }
}

/** « Nommer chef ou adjoint » of a member in its group (not drawn: its choices are the words of the roles). */
@Composable
private fun PostChoiceSheet(
    session: OperationSession,
    facts: OperationFacts,
    member: Long,
) {
    val group = facts.groupOf(member) ?: return
    var post by remember(member) { mutableStateOf(Post.LEADER) }
    SheetTitle(
        stringResource(R.string.op_nominate_post),
        stringResource(R.string.op_nominate_post_sub, groupTitle(facts.groupName(group))),
    )
    RadioLine(selected = post == Post.LEADER, onClick = { post = Post.LEADER }) {
        Text(stringResource(R.string.op_role_group_chief), style = MaterialTheme.typography.bodyLarge)
    }
    RadioLine(selected = post == Post.DEPUTY, onClick = { post = Post.DEPUTY }) {
        Text(stringResource(R.string.op_role_group_deputy_long), style = MaterialTheme.typography.bodyLarge)
    }
    SheetButton(stringResource(R.string.op_name), enabled = true) { session.setPost(group, post, member) }
    CancelLine(session)
}

/**
 * « Nouveau groupe » of an operation (`tour2/04-admin-groupes`, without the colour); a name another group has is
 * refused (`tour5/04-nom-pris`, N13 A). Renaming is [RenameGroupSheet].
 */
@Composable
private fun NewGroupSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    var name by remember { mutableStateOf("") }
    val valid = fr.toolmap.core.hierarchy.Names.valid(name.trim())
    val taken = name.isNotBlank() && facts.groupNameTaken(name, except = null)
    val rank = facts.groupOrder.size + 1
    SheetTitle(
        stringResource(R.string.op_group_new_title),
        if (rank == 1) {
            stringResource(R.string.op_group_first, facts.groupLimit)
        } else {
            stringResource(R.string.op_group_rank, rank, facts.groupLimit)
        },
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.op_group_name)) },
        singleLine = true,
        isError = taken,
        supportingText = if (taken) ({ Text(stringResource(R.string.op_name_taken)) }) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
    SheetButton(createLabel(name), enabled = valid && !taken) { session.createGroup(name) }
    CancelLine(session)
}

/** The windows of the screens of the operation. */
@Composable
fun OperationDialog(
    session: OperationSession,
    facts: OperationFacts,
    dialog: OpDialog,
    nav: OperationNav,
) {
    val close = { session.dialog(null) }
    when (dialog) {
        is OpDialog.RemoveAdmin -> {
            val id = dialog.member
            val group = facts.groupOf(id)?.let { groupTitle(facts.groupName(it)) }
            val post =
                when (facts.postWord(id)) {
                    RoleWord.GROUP_CHIEF -> stringResource(R.string.op_post_chief)
                    RoleWord.GROUP_DEPUTY -> stringResource(R.string.op_post_deputy)
                    else -> null
                }
            Question(
                title = stringResource(R.string.op_remove_admin_title, memberName(facts, id)),
                body = {
                    if (group != null) {
                        Text(
                            if (post != null) {
                                stringResource(R.string.op_remove_admin_text_post, group, post)
                            } else {
                                stringResource(R.string.op_remove_admin_text, group)
                            },
                        )
                    }
                },
                confirm = stringResource(R.string.op_remove_role),
                danger = true,
                onConfirm = { session.removeAdmin(id) },
                onDismiss = close,
            )
        }

        is OpDialog.RemoveMember -> {
            Question(
                title = stringResource(R.string.op_remove_member_title, memberName(facts, dialog.member)),
                body = { Text(stringResource(R.string.op_remove_member_text)) },
                confirm = stringResource(R.string.op_remove_member),
                danger = true,
                onConfirm = { session.removeMember(dialog.member) },
                onDismiss = close,
            )
        }

        is OpDialog.Unassign -> {
            val id = dialog.member
            val group = facts.groupOf(id)
            Question(
                title = stringResource(R.string.op_unassign_title, memberName(facts, id)),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(
                                R.string.op_unassign_text,
                                group?.let {
                                    groupTitle(facts.groupName(it))
                                }.orEmpty(),
                            ),
                        )
                        val deputy = group?.let { facts.deputyOf(it) }
                        when {
                            group != null && facts.deputyOf(group) == id -> {
                                InfoBox(
                                    Icons.Filled.Star,
                                    rich(stringResource(R.string.op_unassign_deputy)),
                                    BoxTone.ORANGE,
                                )
                            }

                            group != null && facts.leaderOf(group) == id && deputy != null -> {
                                InfoBox(
                                    Icons.Filled.Star,
                                    rich(stringResource(R.string.op_unassign_chief, memberName(facts, deputy))),
                                    BoxTone.ORANGE,
                                )
                            }

                            else -> {}
                        }
                    }
                },
                confirm = stringResource(R.string.op_unassign),
                danger = false,
                onConfirm = { session.unassign(id) },
                onDismiss = close,
            )
        }

        is OpDialog.DeleteGroup -> {
            val group = dialog.group
            val count = facts.membersOf(group).size
            val title = groupTitle(facts.groupName(group))
            Question(
                title = stringResource(R.string.op_delete_title, title),
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (count > 0) Text(rich(pluralStringResource(R.plurals.op_delete_text, count, count)))
                        InfoBox(
                            OperationIcons.SpeakerNotesOff,
                            rich(stringResource(R.string.op_delete_box, title)),
                            BoxTone.GREY,
                        )
                    }
                },
                confirm = stringResource(R.string.op_group_delete),
                danger = true,
                onConfirm = { session.deleteGroup(group) },
                onDismiss = close,
            )
        }

        OpDialog.Quit -> {
            QuitWindow(session, facts)
        }

        OpDialog.CreatorQuit -> {
            CreatorQuitWindow(session, facts, nav)
        }
    }
}

/** A question in a window, its answer on the right (`tour2/05-retirer-confirmation`…). */
@Composable
fun Question(
    title: String,
    body: @Composable () -> Unit,
    confirm: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirm,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The frame of the pages of the administration: the band with the title, a column that scrolls. */
@Composable
internal fun AdminScaffold(
    title: String,
    subtitle: String,
    session: OperationSession,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    actionCount: Int = 0,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val nav by session.nav.collectAsStateWithLifecycle()
    // A capture of the end of the page ([OperationNav.toEnd]); a finger scrolls it otherwise.
    LaunchedEffect(nav.toEnd, scroll.maxValue) { if (nav.toEnd) scroll.scrollTo(scroll.maxValue) }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            FittingSubtitle(subtitle, actionCount) {
                ToolMapTopBar(title = title, subtitle = subtitle, onBack = { session.back() }, actions = actions)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The subtitle of the bar in a slightly smaller size when it would not hold on its line (« Exercice Vallon · 5
 * membres » beside two icons on a 320 dp phone), so that it is never cut by « … ».
 */
@Composable
private fun FittingSubtitle(
    subtitle: String,
    actionCount: Int,
    bar: @Composable () -> Unit,
) {
    val typography = MaterialTheme.typography
    val style = typography.bodySmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val room = maxWidth - BAR_CHROME - BAR_ACTION * actionCount
        val width = with(density) { measurer.measure(subtitle, style).size.width.toDp() }
        if (width <= room) {
            bar()
        } else {
            val scale = (room / width).coerceAtLeast(MIN_SUBTITLE_SCALE)
            val smaller = style.copy(fontSize = style.fontSize * scale, letterSpacing = style.letterSpacing * scale)
            MaterialTheme(typography = typography.copy(bodySmall = smaller)) {
                bar()
            }
        }
    }
}

/** A line of the summary: icon, title, what it holds, the count and the chevron when it opens a page. */
@Composable
private fun SummaryLine(
    icon: ImageVector,
    title: String,
    sub: String,
    count: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub.isNotEmpty()) Sub(sub)
        }
        if (onClick != null) Count(count ?: 0, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A line of the orange block of « Administrer »: « En attente », « Noms à valider » (`tour5/02-admin` A). */
@Composable
fun OrangeRow(
    icon: ImageVector,
    title: String,
    sub: String,
    count: Int,
    fore: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fore, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = fore)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = fore)
        }
        Count(count, fore)
    }
}

@Composable
private fun Count(
    count: Int,
    color: Color,
) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.padding(start = 8.dp),
    )
    Icon(
        RouteIcons.ChevronRight,
        contentDescription = null,
        tint = color,
        modifier = Modifier.padding(start = 4.dp).size(24.dp),
    )
}

@Composable
fun Sub(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A post held: the member, what the post is, « Changer » (`tour2/04-admin-groupe`, `04-admin-chef`). */
@Composable
private fun PostLine(
    facts: OperationFacts,
    member: Long,
    sub: String,
    onChange: () -> Unit,
) {
    MemberLine(
        facts,
        member,
        subtitle = sub,
        minHeight = 72.dp,
        trailing = { TextButton(onClick = onChange) { Text(stringResource(R.string.op_change)) } },
    )
}

/** A post nobody holds: « Sans chef · Aucun chef nommé », « Pas d'adjoint »; « Nommer » (`04-admin-sans-chef`). */
@Composable
private fun VacantLine(
    noChief: Boolean,
    palette: Int?,
    title: String,
    sub: String,
    onName: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (noChief) {
            NoChiefToken(palette, 40.dp)
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.toolMap.tile))
                Icon(
                    OperationIcons.StarBorder,
                    contentDescription = null,
                    tint = WAITING_GREY,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (noChief) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            Sub(sub)
        }
        FilledTonalButton(onClick = onName) { Text(stringResource(R.string.op_name)) }
    }
}

/** A gesture of the sheet of a member. */
@Composable
internal fun ActionLine(
    icon: ImageVector,
    title: String,
    sub: String?,
    color: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color? = null,
    chevron: Boolean = true,
    start: androidx.compose.ui.unit.Dp = 24.dp,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(start = start, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint ?: if (color == colors.onSurface) colors.onSurfaceVariant else color,
            modifier = Modifier.size(26.dp),
        )
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (sub != null) Sub(sub)
        }
        if (chevron) {
            Icon(
                RouteIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A gesture reserved to the creator: grey, with a lock (`tour2/05-retirer-membre`, administrator). */
@Composable
private fun LockedLine(
    icon: ImageVector,
    title: String,
    sub: String,
) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = grey, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = grey)
            Sub(sub)
        }
        Icon(Icons.Filled.Lock, contentDescription = null, tint = grey)
    }
}

/** The main button of a sheet, across it. */
@Composable
fun SheetButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp).heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
fun CancelLine(session: OperationSession) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { session.sheet(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/** « Chef de groupe · Administrateur », « Adjoint · Administrateur », « Membre » (`tour2/04-admin-groupe`). */
@Composable
private fun rolesLine(
    facts: OperationFacts,
    id: Long,
): String {
    val words = facts.roleWords(id)
    return if (words.isEmpty()) {
        stringResource(R.string.op_role_member)
    } else {
        words.map {
            roleText(it)
        }.joinToString(" · ")
    }
}

/** « Créateur · Groupe Est », « Administrateur · adjoint du Groupe Nord » (`tour2/05-admins-createur`). */
@Composable
private fun adminLine(
    facts: OperationFacts,
    id: Long,
): String {
    val role = stringResource(if (facts.isCreator(id)) R.string.op_role_creator else R.string.op_role_admin)
    val group = facts.groupOf(id) ?: return role
    val title = groupTitle(facts.groupName(group))
    val where =
        when (facts.postWord(id)) {
            RoleWord.GROUP_CHIEF -> stringResource(R.string.op_chief_of, title)
            RoleWord.GROUP_DEPUTY -> stringResource(R.string.op_deputy_of, title)
            else -> title
        }
    return stringResource(R.string.op_admin_sub, role, where)
}

private const val NAMED_WAITING = 2
private const val ORANGE_DIVIDER_ALPHA = 0.15f

/** The bar around its title: paddings 4 + 4, the arrow 48, the title inset 12, and 4 to spare. */
private val BAR_CHROME = 72.dp
private val BAR_ACTION = 48.dp
private const val MIN_SUBTITLE_SCALE = 0.8f
