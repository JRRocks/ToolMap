package fr.toolmap.operation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.HiddenName
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.data.hierarchy.NameRequestView
import fr.toolmap.share.ageText
import fr.toolmap.ui.GroupBlock

// Renaming during the operation (P39.13): the answers of 2026-11-16 to `docs/previews/p39-groupes/tour5`
// (N2 A, N3 A, N5 B, N6 A, N7 A, N8 A, N9 B, N10 A, N11 B, N12 A, N13 A, N14 A).

/** A commitment that matches no name: to ask the engine whether a renaming would go through before one is typed. */
private val ANY_NAME = HiddenName(ByteArray(HiddenName.BYTES) { 1 })

/** This phone gives [member] a new name at once (N2 A: the creator and the administrators, themselves included). */
fun OperationFacts.mayRename(member: Long): Boolean = may(Change.RenameMember(member, ANY_NAME))

/** This phone asks for a new name of its own (N11 B: whoever may not rename itself, a member who waits included). */
val OperationFacts.mayRequestName: Boolean get() = self?.let { may(Change.RequestName(it, ANY_NAME)) } == true

/** The pencil of its own line (N5 B): a request, or a new name at once for the creator and the administrators. */
val OperationFacts.mayChangeOwnName: Boolean get() = self?.let { mayRename(it) } == true || mayRequestName

/** « Renommer l'opération… » (N10 A): the creator and the administrators. */
val OperationFacts.mayRenameOperation: Boolean get() = may(Change.RenameEnsemble(ANY_NAME))

/** The request of a new name this phone made and nobody answered yet (N7 A). */
val OperationFacts.myNameRequest: NameRequestView? get() = view.nameRequests.firstOrNull { it.member == self }

/** The requests the creator and the administrators answer (N3 A, N4 A), the oldest first. */
val OperationFacts.nameRequestsToAnswer: List<NameRequestView>
    get() =
        if (!administers) {
            emptyList()
        } else {
            view.nameRequests
                .filter { it.member != self && state?.members?.containsKey(it.member) == true }
                .sortedBy { it.seenAtMs }
        }

/** [name] is the name of a member other than [except], as this phone knows them (N13 A). */
fun OperationFacts.memberNameTaken(
    name: String,
    except: Long?,
): Boolean = view.members.any { it.id != except && sameName(it.name, name) }

/** [name] is the name of a group other than [except] (N13 A). */
fun OperationFacts.groupNameTaken(
    name: String,
    except: Long?,
): Boolean = view.groups.any { it.id != except && sameName(it.name, name) }

/** Two names are the same without regard to capitals or the spaces around them (N13 A, N15 A). */
internal fun sameName(
    known: String?,
    typed: String,
): Boolean = known != null && known.trim().equals(typed.trim(), ignoreCase = true)

/**
 * A sheet that gives a new name (`tour5/02-membre`, `02-groupe-B`, `02-demande`, `02-operation`, `04-nom-pris`): the
 * title, the field (the current name already in it, a cross to empty it), what the change does, a box, the button
 * (grey while the name is not valid, not changed or already taken) and « Annuler ». [more] goes under the field
 * (the colours of « Modifier le groupe »); [moreChanged]: it changed, the button may save an unchanged name.
 */
@Composable
fun NameSheet(
    session: OperationSession,
    title: String,
    label: String,
    current: String?,
    note: String?,
    button: String,
    taken: (String) -> Boolean,
    box: (@Composable (String) -> Unit)? = null,
    more: (@Composable () -> Unit)? = null,
    moreChanged: Boolean = false,
    onSave: (String) -> Unit,
) {
    val draft = session.nav.collectAsStateWithLifecycle().value.nameDraft
    var name by remember(current, draft) { mutableStateOf(draft ?: current.orEmpty()) }
    val trimmed = name.trim()
    val isTaken = trimmed.isNotEmpty() && taken(trimmed)
    val changed = trimmed != current?.trim()
    SheetTitle(title)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(label) },
        singleLine = true,
        isError = isTaken,
        trailingIcon = {
            if (isTaken) {
                // `04-nom-pris`: the mark of the error in place of the cross.
                Icon(
                    OperationIcons.ErrorMark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (name.isNotEmpty()) {
                IconButton(onClick = { name = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.op_name_clear))
                }
            }
        },
        supportingText =
            when {
                isTaken -> {
                    { Text(stringResource(R.string.op_name_taken)) }
                }

                note != null -> {
                    { Text(note) }
                }

                else -> {
                    null
                }
            },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
    // A name already taken: the error alone, without the box (`04-nom-pris`).
    if (box != null && !isTaken) Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) { box(trimmed) }
    more?.invoke()
    SheetButton(button, enabled = Names.valid(trimmed) && (changed || moreChanged) && !isTaken) { onSave(trimmed) }
    CancelLine(session)
}

/** « Renommer Membre 11 » (`tour5/02-membre` A, N2 A, N14 A). */
@Composable
fun RenameMemberSheet(
    session: OperationSession,
    facts: OperationFacts,
    member: Long,
) {
    val current = facts.name(member)
    val shown = memberName(facts, member)
    NameSheet(
        session,
        title = stringResource(R.string.op_rename_member_title, shown),
        label = stringResource(R.string.op_name_field),
        current = current,
        note = stringResource(R.string.op_rename_member_note, shown),
        button = stringResource(R.string.op_group_save),
        taken = { facts.memberNameTaken(it, except = member) },
    ) { session.renameMember(member, it) }
}

/**
 * « Changer mon nom » (`tour5/02-demande`, `02-demande-B`, `04-attente-nom`, `04-nom-pris`): a request that an
 * administrator accepts (N3 A, N11 B), or a new name at once for the creator and the administrators.
 */
@Composable
fun MyNameSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    val self = facts.self ?: return
    val direct = facts.mayRename(self)
    val current = facts.name(self)
    val shown = memberName(facts, self)
    NameSheet(
        session,
        title = stringResource(R.string.op_my_name_title),
        label = stringResource(R.string.op_my_name_field),
        current = current,
        note =
            when {
                !direct -> null
                facts.isCreator(self) -> stringResource(R.string.op_my_name_creator)
                else -> stringResource(R.string.op_my_name_admin)
            },
        button = stringResource(if (direct) R.string.op_group_save else R.string.op_my_name_send),
        taken = { facts.memberNameTaken(it, except = self) },
        box =
            if (direct) {
                null
            } else {
                { _ ->
                    InfoBox(
                        OperationIcons.Shield,
                        rich(
                            stringResource(
                                if (facts.waiting) R.string.op_my_name_box_waiting else R.string.op_my_name_box,
                                shown,
                            ),
                        ),
                        BoxTone.TEAL,
                    )
                }
            },
    ) { if (direct) session.renameMember(self, it, own = true) else session.requestName(it) }
}

/**
 * « Modifier le groupe » (L1 A, `tour8/img/02`, from `tour5/02-groupe-B`): its name and its colour, ticked; the
 * colours of the other groups are pale. Its members stay.
 */
@Composable
fun RenameGroupSheet(
    session: OperationSession,
    facts: OperationFacts,
    group: Long,
) {
    val current = facts.palette[group]
    var picked by remember(group) { mutableStateOf<Int?>(null) }
    val others = facts.palette.filterKeys { it != group }.values.toSet()
    val color = picked?.takeIf { it !in others } ?: current
    val name = facts.groupName(group)
    NameSheet(
        session,
        title = stringResource(R.string.op_group_edit_title),
        label = stringResource(R.string.op_group_name),
        current = name,
        note = stringResource(R.string.op_group_rename_note),
        button = stringResource(R.string.op_group_save),
        taken = { facts.groupNameTaken(it, except = group) },
        more =
            if (color == null) {
                null
            } else {
                { GroupColorPicker(stringResource(R.string.op_group_color_edit), color, others) { picked = it } }
            },
        moreChanged = color != current,
    ) { typed ->
        session.editGroup(group, typed.takeIf { it != name?.trim() }, color.takeIf { it != current })
    }
}

/** « Renommer l'opération » (`tour5/02-operation`, N10 A): the invitations already given stay valid. */
@Composable
fun RenameOperationSheet(
    session: OperationSession,
    facts: OperationFacts,
) {
    NameSheet(
        session,
        title = stringResource(R.string.op_rename_op_title),
        label = stringResource(R.string.op_rename_op_field),
        current = facts.operationName,
        note = stringResource(R.string.op_rename_op_note),
        button = stringResource(R.string.op_rename),
        taken = { false },
        box = { typed ->
            InfoBox(
                OperationIcons.PersonAdd,
                rich(stringResource(R.string.op_rename_op_box, typed.ifEmpty { facts.operationName.orEmpty() })),
                BoxTone.TEAL,
            )
        },
    ) { session.renameOperation(it) }
}

/** The pencil of its own line (N5 B, `tour5/02-demande-B`, `04-attente-nom`): it opens « Changer mon nom ». */
@Composable
fun OwnNamePencil(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                OperationIcons.Edit,
                contentDescription = stringResource(R.string.op_my_name_title),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Under its own line while an administrator has not answered (`tour5/02-attente` A, N7 A): the name asked for, and
 * « Annuler »; out of network, the request leaves when the network is back.
 */
@Composable
fun PendingNameLine(
    request: NameRequestView,
    online: Boolean,
    onCancel: () -> Unit,
) {
    val fore = MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OperationIcons.Hourglass, contentDescription = null, tint = fore, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(
                stringResource(R.string.op_name_pending, request.requested ?: stringResource(R.string.op_name_unknown)),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = fore,
            )
            Text(
                stringResource(if (online) R.string.op_name_pending_sub else R.string.op_name_pending_offline),
                style = MaterialTheme.typography.bodySmall,
                color = fore,
            )
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel), color = fore, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** « Noms à valider » (`tour5/02-admin-valider` A, N3 A, N4 A, N6 A): accept or refuse; the first answer decides. */
@Composable
fun NameRequestsPage(
    session: OperationSession,
    facts: OperationFacts,
) {
    val requests = facts.nameRequestsToAnswer
    AdminScaffold(
        stringResource(R.string.op_names_title),
        stringResource(
            R.string.op_group_subtitle,
            facts.operationName.orEmpty(),
            pluralStringResource(R.plurals.op_names_count, requests.size, requests.size),
        ),
        session,
    ) {
        InfoBox(OperationIcons.Edit, rich(stringResource(R.string.op_names_box)), BoxTone.ORANGE)
        for (request in requests) {
            GroupBlock(rows = listOf({ NameRequestCard(session, facts, request) }))
        }
        Text(
            stringResource(R.string.op_names_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun NameRequestCard(
    session: OperationSession,
    facts: OperationFacts,
    request: NameRequestView,
) {
    val id = request.member
    val group = facts.groupOf(id)
    val where =
        group?.let { groupTitle(facts.groupName(it)) } ?: stringResource(R.string.op_waiting)
    val role = facts.postWord(id)?.let { roleText(it) } ?: stringResource(R.string.op_role_member)
    val asked = stringResource(R.string.op_names_asked, ageText(request.seenAtMs, facts.nowMs))
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpToken(facts.token(id), 40.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    buildAnnotatedString {
                        append(memberName(facts, id))
                        withStyle(SpanStyle(color = grey)) { append("  »  ") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(request.requested ?: "…")
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    listOf(where, role, asked).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = grey,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = { session.answerName(id, accept = false) },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(stringResource(R.string.op_refuse))
            }
            Button(onClick = { session.answerName(id, accept = true) }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.op_accept))
            }
        }
    }
}

/** The line « Noms à valider » of « Administrer » (`tour5/02-admin` A), under « En attente », in the same orange. */
@Composable
fun NameRequestsRow(
    facts: OperationFacts,
    onClick: () -> Unit,
) {
    val requests = facts.nameRequestsToAnswer
    val fore = MaterialTheme.colorScheme.onTertiaryContainer
    val sub =
        if (requests.size == 1) {
            val one = requests.first()
            stringResource(R.string.op_names_one, memberName(facts, one.member), one.requested ?: "…")
        } else {
            pluralStringResource(R.plurals.op_names_count, requests.size, requests.size)
        }
    OrangeRow(OperationIcons.Edit, stringResource(R.string.op_names_title), sub, requests.size, fore, onClick)
}

/** The answer of the window « Déjà répondu » (`tour5/02-admin-valider`, N6 A) and of « Nom non accepté » (N8 A). */
@Composable
fun nameNoticeText(notice: Notice): Pair<String, androidx.compose.ui.text.AnnotatedString>? =
    when (notice) {
        is Notice.NameRefused -> {
            stringResource(R.string.op_name_refused_title) to
                rich(stringResource(R.string.op_name_refused_text, notice.name, notice.current))
        }

        is Notice.AlreadyAnswered -> {
            stringResource(R.string.op_already_title) to
                rich(
                    stringResource(
                        if (notice.accepted) R.string.op_already_accepted else R.string.op_already_refused,
                        notice.by,
                        notice.name,
                    ),
                )
        }

        else -> {
            null
        }
    }
