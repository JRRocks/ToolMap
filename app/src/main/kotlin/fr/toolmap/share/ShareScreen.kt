package fr.toolmap.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.ShareCadence
import fr.toolmap.draw.RouteIcons
import fr.toolmap.map.MenuIcons
import fr.toolmap.maps.MapsIcons
import fr.toolmap.settings.SettingsIcons
import fr.toolmap.ui.GroupBlock
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.rememberNotificationAsk
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun problemText(problem: ShareProblem): Int =
    when (problem) {
        ShareProblem.BAD_CODE -> R.string.share_problem_bad_code
        ShareProblem.EXPIRED -> R.string.share_problem_expired
        ShareProblem.CLOCK_OFF -> R.string.share_problem_clock
        ShareProblem.OTHER_GROUP -> R.string.share_problem_other_group
        ShareProblem.NOT_EXPECTED -> R.string.share_problem_not_expected
        ShareProblem.NO_NETWORK -> R.string.share_problem_no_network
        ShareProblem.PAIRING_TIMEOUT -> R.string.share_problem_timeout
    }

/** The pages of the sharing screen (P29.8): the main one, and those its lines and its ⋮ open. */
private enum class SharePage { MAIN, PROFILE, SETTINGS, MESSAGES }

/**
 * The sharing screen in « Cordée » (P29.8): your line first, your token as the others see it beside the switch that
 * sends it (out of a group, the page « Ce que les autres voient »); the alert just under it; the others with their
 * state and a map button each; the line « Messages » with the last one and the unread count; « Inviter » floating, the
 * one filled action. The SOS is in the bar; scanning, pasting, the settings, the help and leaving are in its ⋮. The
 * invitation and the sheet of a member are sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    vm: ShareViewModel,
    onBack: () -> Unit,
    onShowOnMap: (lat: Double, lon: Double) -> Unit,
    onGoTo: (lat: Double, lon: Double, name: String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(SharePage.MAIN) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }
    var openMember by remember { mutableStateOf<Long?>(null) }
    var showIntro by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = page != SharePage.MAIN) { page = SharePage.MAIN }
    // Leaving the group closes its pages.
    LaunchedEffect(ui.inGroup) { if (!ui.inGroup && page == SharePage.MESSAGES) page = SharePage.MAIN }

    val permissions =
        remember {
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        }
    val deniedText = stringResource(R.string.share_permission_denied)
    val askNotifications = rememberNotificationAsk(R.string.background_ask_sharing)
    // The settings that may cut the sharing, shown in a dialog as it starts (P21.5 o, c); the notification question
    // comes after it, not on top of it.
    var startAlerts by remember { mutableStateOf<List<ShareAlert>>(emptyList()) }
    val startSharing = {
        vm.startSharing()
        startAlerts = vm.recheckPhone()
        if (startAlerts.isEmpty()) askNotifications()
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                startSharing()
            } else {
                scope.launch { snackbar.showSnackbar(deniedText) }
            }
        }
    LifecycleResumeEffect(vm) {
        vm.recheckPhone()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(resources.getString(it.resId, *it.args.toTypedArray())) }
    }
    val joinedText = ui.justJoined?.let { stringResource(R.string.share_joined, it) }
    LaunchedEffect(ui.justJoined) {
        if (joinedText != null) {
            snackbar.showSnackbar(joinedText)
            vm.clearJoined()
        }
    }
    val toggle = { on: Boolean ->
        if (!on) {
            vm.stopSharing()
        } else if (hasFineLocation(context)) {
            startSharing()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    when (page) {
        SharePage.MAIN -> {
            MainPage(
                ui = ui,
                snackbar = snackbar,
                onBack = onBack,
                onToggle = toggle,
                onSos = { sosDialog = true },
                onCancelSos = vm::cancelSos,
                onInvite = vm::invite,
                onScan = { scanning = true },
                onPaste = { pasting = true },
                onSettings = { page = SharePage.SETTINGS },
                onHelp = { showIntro = true },
                onLeave = { confirmLeave = true },
                onProfile = { page = SharePage.PROFILE },
                onMessages = { page = SharePage.MESSAGES },
                onOpenMember = { openMember = it.id },
                onShowOnMap = onShowOnMap,
                onAlertBack = { vm.recheckPhone() },
            )
        }

        SharePage.PROFILE -> {
            SubPage(stringResource(R.string.share_profile_title), onBack = { page = SharePage.MAIN }) {
                ProfileEditor(ui, vm)
            }
        }

        SharePage.SETTINGS -> {
            SubPage(stringResource(R.string.share_settings_caption), onBack = { page = SharePage.MAIN }) {
                // The four rows of the settings in a group; out of one, your look is your line of the main page.
                if (ui.inGroup) {
                    GroupBlock(
                        rows =
                            listOf(
                                { SendSettingsCard(ui, vm) },
                                { ProfileCard(ui, vm) },
                                { MapDisplayCard(ui, vm) },
                                { ServerCard(ui, vm) },
                            ),
                    )
                } else {
                    GroupBlock(rows = listOf({ MapDisplayCard(ui, vm) }, { ServerCard(ui, vm) }))
                }
            }
        }

        SharePage.MESSAGES -> {
            MessagesPage(ui, onBack = { page = SharePage.MAIN }, onSend = vm::sendText, onRead = vm::markMessagesRead)
        }
    }

    if (showIntro) {
        AlertDialog(
            onDismissRequest = { showIntro = false },
            title = { Text(stringResource(R.string.share_title)) },
            text = { Text(stringResource(R.string.share_intro)) },
            confirmButton = {
                TextButton(onClick = { showIntro = false }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    ui.problem?.let { problem ->
        AlertDialog(
            onDismissRequest = vm::clearProblem,
            title = { Text(stringResource(R.string.share_problem_title)) },
            text = { Text(stringResource(problemText(problem))) },
            confirmButton = { TextButton(onClick = vm::clearProblem) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (ui.excluded) {
        AlertDialog(
            onDismissRequest = vm::clearExcluded,
            title = { Text(stringResource(R.string.share_excluded_title)) },
            text = { Text(stringResource(R.string.share_excluded_text)) },
            confirmButton = { TextButton(onClick = vm::clearExcluded) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (ui.preparing) {
        AlertDialog(
            onDismissRequest = vm::cancelInvitation,
            title = { Text(stringResource(R.string.share_preparing)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(stringResource(R.string.share_preparing_text))
                }
            },
            confirmButton = {
                TextButton(onClick = vm::cancelInvitation) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    ui.invitation?.let { code ->
        CodeSheet(
            title = stringResource(R.string.share_invitation_title),
            hint =
                stringResource(
                    if (ui.onServer) R.string.share_invitation_hint_server else R.string.share_invitation_hint,
                ),
            code = code,
            busy = false,
            primary = if (ui.onServer) null else stringResource(R.string.share_scan_answer) to { scanning = true },
            onClose = vm::cancelInvitation,
        )
    }
    ui.answer?.let { code ->
        CodeSheet(
            title = stringResource(R.string.share_answer_title),
            hint = stringResource(R.string.share_answer_hint),
            code = code,
            busy = ui.pairing,
            primary = null,
            onClose = vm::cancelInvitation,
        )
    }
    if (ui.pairing && ui.answer == null && ui.invitation == null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.share_pairing)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }
    if (scanning) {
        ScanDialog(
            onCode = {
                scanning = false
                vm.handleCode(it)
            },
            onClose = { scanning = false },
        )
    }
    if (pasting) {
        PasteDialog(
            onSubmit = {
                pasting = false
                vm.handleCode(it)
            },
            onClose = { pasting = false },
        )
    }
    if (sosDialog) {
        AlertSendSheet(
            onSend = {
                sosDialog = false
                vm.startSos(it)
            },
            onClose = { sosDialog = false },
        )
    }
    if (startAlerts.isNotEmpty()) {
        ShareStartAlertDialog(
            startAlerts,
            onDismiss = {
                startAlerts = emptyList()
                askNotifications()
            },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.share_leave_title)) },
            text = { Text(stringResource(R.string.share_leave_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveGroup()
                }) { Text(stringResource(R.string.share_leave), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    openMember?.let { id ->
        val member = ui.members.firstOrNull { it.id == id }
        if (member == null) {
            openMember = null
        } else {
            val fix = member.fix
            MemberSheet(
                member = member,
                state = PeerState.of(member, nowMs(), serverUp(ui)),
                onShowOnMap = fix?.let { f -> { onShowOnMap(f.lat, f.lon) } },
                onGoTo = fix?.takeIf { !member.isSelf }?.let { f -> { onGoTo(f.lat, f.lon, member.name) } },
                onSaveTrail = {
                    vm.saveTrail(member)
                    openMember = null
                },
                onRemove = {
                    vm.removeMember(member.id)
                    openMember = null
                },
                onDismiss = { openMember = null },
            )
        }
    }
}

/** What the main page asks the screen to do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainPage(
    ui: ShareUi,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSos: () -> Unit,
    onCancelSos: () -> Unit,
    onInvite: () -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onLeave: () -> Unit,
    onProfile: () -> Unit,
    onMessages: () -> Unit,
    onOpenMember: (MemberView) -> Unit,
    onShowOnMap: (lat: Double, lon: Double) -> Unit,
    onAlertBack: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.share_title),
                subtitle = if (ui.inGroup) hudText(ui) else null,
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.share_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            @Composable
                            fun item(
                                label: Int,
                                icon: ImageVector?,
                                color: Color = Color.Unspecified,
                                action: () -> Unit,
                            ) = DropdownMenuItem(
                                text = { Text(stringResource(label), color = color) },
                                leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
                                onClick = {
                                    menu = false
                                    action()
                                },
                            )
                            if (ui.inGroup) {
                                item(R.string.share_scan, MenuIcons.QrCodeScanner, action = onScan)
                                item(R.string.share_paste, MenuIcons.Link, action = onPaste)
                            }
                            item(R.string.share_settings_caption, SettingsIcons.Dashboard, action = onSettings)
                            item(R.string.share_help, MenuIcons.Help, action = onHelp)
                            if (ui.inGroup) {
                                item(R.string.share_leave, null, MaterialTheme.colorScheme.error, onLeave)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (ui.inGroup) {
                ExtendedFloatingActionButton(
                    onClick = onInvite,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(ShareIcons.PersonAdd, contentDescription = null) },
                    text = { Text(stringResource(R.string.share_invite)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.inGroup) {
                GroupBlock(rows = listOf({ SelfLookLine(ui, onProfile) }))
                NoGroup()
                Button(onClick = onInvite, modifier = Modifier.fillMaxWidth().heightIn(min = MAIN_BUTTON_HEIGHT)) {
                    Icon(ShareIcons.GroupAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.share_create), style = MaterialTheme.typography.titleSmall)
                }
                Column {
                    Text(
                        stringResource(R.string.share_join_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JoinButton(MenuIcons.QrCodeScanner, stringResource(R.string.share_scan), onScan)
                        JoinButton(MenuIcons.Link, stringResource(R.string.share_paste), onPaste)
                    }
                }
            } else {
                val now = nowMs()
                val up = serverUp(ui)
                GroupBlock(
                    rows =
                        listOf({
                            SelfSendLine(ui, now, up, onOpen = { ui.self?.let(onOpenMember) }, onToggle = onToggle)
                        }),
                )
                // The alert of the group (P22, variant C): the line that sends it, or your alert in progress.
                if (ui.ownSos) {
                    OwnAlertCard(sinceMs = ui.ownSosAtMs, myName = ui.myName, onCancel = onCancelSos)
                } else {
                    GroupBlock(rows = listOf({ SendAlertLine(onSos) }))
                }
                if (ui.gpsUnavailable) {
                    Text(stringResource(R.string.share_gps_unavailable), color = MaterialTheme.colorScheme.error)
                }
                ShareAlertCards(ui.alerts, onBack = onAlertBack)
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            stringResource(R.string.share_members),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (ui.onServer) {
                            Text(
                                serverStateLabel(ui.server),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (up) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                            )
                        }
                    }
                    if (ui.others.isEmpty()) {
                        Text(
                            stringResource(R.string.share_no_members),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    } else {
                        GroupBlock(
                            rows =
                                ui.others.map { member ->
                                    {
                                        MemberLine(
                                            member,
                                            PeerState.of(member, now, up),
                                            onOpen = { onOpenMember(member) },
                                            // A member that only listens is not on the map (P22.3).
                                            onShowOnMap =
                                                member.fix
                                                    ?.takeIf { member.sharing != false }
                                                    ?.let { f -> { onShowOnMap(f.lat, f.lon) } },
                                        )
                                    }
                                },
                        )
                    }
                }
                GroupBlock(rows = listOf({ MessagesLine(ui, onMessages) }))
            }
        }
    }
}

/** « Partage actif · 1 connecté(s) » or « Groupe · 4 membres » (you included): the words of the strip on the map. */
@Composable
private fun hudText(ui: ShareUi): String =
    if (ui.sharing) {
        stringResource(R.string.share_hud_sharing, ui.others.count { it.status == MemberStatus.DIRECT })
    } else {
        pluralStringResource(R.plurals.share_hud_watching, ui.members.size, ui.members.size)
    }

/** Out of a group: your token and name as the others will see them; the line opens « Ce que les autres voient ». */
@Composable
private fun SelfLookLine(
    ui: ShareUi,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberBadge(ui.myName, ui.settings.look(), size = 48.dp, picture = ui.settings.pictureBytes())
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(ui.myName, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.share_profile_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(RouteIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * In a group: your token, « Partager ma position » and « Pixel 7 (vous) · En ligne »; the text opens your sheet, the
 * switch apart sends or not (two elements for TalkBack).
 */
@Composable
private fun SelfSendLine(
    ui: ShareUi,
    now: Long,
    up: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val self = ui.self
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 72.dp)
                .clickable(enabled = self != null, onClick = onOpen)
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberBadge(ui.myName, ui.settings.look(), size = 48.dp, picture = ui.settings.pictureBytes())
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(R.string.share_sharing), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.share_self, ui.myName) + " · " +
                        if (ui.sharing && self != null) {
                            peerStateLabel(PeerState.of(self, now, up))
                        } else {
                            stringResource(R.string.share_peer_listening)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(12.dp))
        Switch(checked = ui.sharing, onCheckedChange = onToggle)
    }
}

/** « Aucun groupe »: an empty state that says what a group is for. */
@Composable
private fun NoGroup() {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.toolMap.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ShareIcons.Groups,
                contentDescription = null,
                tint = MaterialTheme.toolMap.onTile,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            stringResource(R.string.share_no_group),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.share_start_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A member of the group: token, name, state and age, its state icon, and a button that shows it on the map. */
@Composable
private fun MemberLine(
    member: MemberView,
    state: PeerState,
    onOpen: () -> Unit,
    onShowOnMap: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberBadge(member.name, member.color, size = 40.dp, picture = member.picture)
            // Online without sharing: « À l'écoute », with the icon of « En ligne » and no age of position (P22.3).
            val listening = member.sharing == false && state == PeerState.ONLINE
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(member.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        if (listening) stringResource(R.string.share_peer_listening) else peerStateLabel(state),
                        member.fix?.takeIf { !listening }?.let { ageText(it.timeSec * MS_PER_S) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PeerStatusIcon(state)
        }
        IconButton(onClick = { onShowOnMap?.invoke() }, enabled = onShowOnMap != null) {
            Icon(
                MapsIcons.Map,
                contentDescription = stringResource(R.string.share_member_map, member.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

/** « Messages »: the last one on one line and the count of the unread, opening their page. */
@Composable
private fun MessagesLine(
    ui: ShareUi,
    onOpen: () -> Unit,
) {
    val last = ui.messages.lastOrNull()
    val unreadText =
        if (ui.unread > 0) pluralStringResource(R.plurals.share_messages_unread, ui.unread, ui.unread) else null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShareIcons.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text(stringResource(R.string.share_messages), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (last == null) {
                    stringResource(R.string.share_messages_none)
                } else {
                    stringResource(R.string.share_messages_last, messageAuthor(last), last.text)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unreadText != null) {
            Text(
                "${ui.unread}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .widthIn(min = 20.dp)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .clearAndSetSemantics { contentDescription = unreadText },
            )
        }
        Icon(RouteIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun messageAuthor(message: ShareMessage): String =
    if (message.mine) stringResource(R.string.share_message_mine) else message.name

/** A page opened from the main one: the bar with its arrow back, the content in a scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = { ToolMapTopBar(title = title, onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** The page of the messages: the last 50 with their time, then the field and « Envoyer ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesPage(
    ui: ShareUi,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onRead: () -> Unit,
) {
    // Open, the page reads what comes in.
    LaunchedEffect(ui.messages.size) { onRead() }
    val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = { ToolMapTopBar(title = stringResource(R.string.share_messages), onBack = onBack) },
        bottomBar = {
            var draft by rememberSaveable { mutableStateOf("") }
            Row(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_DRAFT) },
                    placeholder = { Text(stringResource(R.string.share_message_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                ) { Text(stringResource(R.string.share_message_send)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val shown = ui.messages.takeLast(VISIBLE_MESSAGES)
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.share_messages_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (message in shown) {
                Column {
                    Text(
                        messageAuthor(message) + " · " +
                            time.format(Instant.ofEpochMilli(message.timeMs).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** « Ce que les autres voient », folded in the settings of a group. */
@Composable
private fun ProfileCard(
    ui: ShareUi,
    vm: ShareViewModel,
) {
    FoldCard(title = stringResource(R.string.share_profile_title), summary = ui.myName) {
        ProfileEditor(ui, vm)
    }
}

/** The name the others see, the 15 tokens, the picture and the colour (its page out of a group, P29.8). */
@Composable
private fun ProfileEditor(
    ui: ShareUi,
    vm: ShareViewModel,
) {
    var name by remember(ui.settings.name) { mutableStateOf(ui.settings.name) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            vm.updateSettings { s -> s.copy(name = it) }
        },
        label = { Text(stringResource(R.string.share_name)) },
        placeholder = { Text(stringResource(R.string.share_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    MemberLookPicker(
        name = name.ifBlank { ui.myName.ifBlank { "?" } },
        icon = ui.settings.icon,
        argb = ui.settings.colorArgb(),
        picture = ui.settings.pictureBytes(),
        onIcon = { id -> vm.updateSettings { it.copy(icon = id) } },
        onColor = { c -> vm.updateSettings { it.copy(color = c) } },
        onPicture = { bytes -> vm.updateSettings { it.withPicture(bytes) } },
    )
}

/** What this device draws of the others: their path, or only where they are now. */
@Composable
private fun MapDisplayCard(
    ui: ShareUi,
    vm: ShareViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.share_show_trails), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (ui.settings.showTrails) R.string.share_show_trails_on else R.string.share_show_trails_off,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = ui.settings.showTrails,
            onCheckedChange = { on -> vm.updateSettings { it.copy(showTrails = on) } },
        )
    }
}

/** How often the position goes out, folded under a one-line summary: it is set once and rarely changed. */
@Composable
private fun SendSettingsCard(
    ui: ShareUi,
    vm: ShareViewModel,
) {
    val move =
        if (ui.settings.minMoveMeters == 0) {
            stringResource(R.string.share_move_always)
        } else {
            "${ui.settings.minMoveMeters} m"
        }
    FoldCard(
        title = stringResource(R.string.share_send_settings),
        summary = stringResource(R.string.share_send_summary, secondsText(ui.settings.intervalSec), move),
    ) {
        PresetSlider(
            label = stringResource(R.string.share_interval),
            presets = ShareCadence.PRESETS_S,
            value = ui.settings.intervalSec,
            format = { secondsText(it) },
            onChange = { v -> vm.updateSettings { it.copy(intervalSec = v) } },
        )
        PresetSlider(
            label = stringResource(R.string.share_min_move),
            presets = ShareCadence.MOVE_PRESETS_M,
            value = ui.settings.minMoveMeters,
            format = { if (it == 0) stringResource(R.string.share_move_always) else "$it m" },
            onChange = { v -> vm.updateSettings { it.copy(minMoveMeters = v) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.share_stun))
                Text(stringResource(R.string.share_stun_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = ui.settings.stun, onCheckedChange = { on -> vm.updateSettings { it.copy(stun = on) } })
        }
    }
}

/** « Scanner un code », « Coller un lien »: two outlined buttons with their icon, side by side. */
@Composable
private fun RowScope.JoinButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1)
    }
}

/**
 * A row of the settings folded under its [title] and a one-line [summary]; a tap on it unfolds the [content]. The
 * settings of the sharing screen use it, so that the actions stay in sight.
 */
@Composable
private fun FoldCard(
    title: String,
    summary: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

private fun secondsText(seconds: Int): String =
    if (seconds <
        SECONDS_PER_MINUTE
    ) {
        "$seconds s"
    } else {
        "${seconds / SECONDS_PER_MINUTE} min"
    }

@Composable
private fun PresetSlider(
    label: String,
    presets: List<Int>,
    value: Int,
    format: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
) {
    val index = presets.indexOfFirst { it >= value }.let { if (it < 0) presets.lastIndex else it }
    var position by remember(index) { mutableFloatStateOf(index.toFloat()) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(
                format(presets[position.toInt().coerceIn(0, presets.lastIndex)]),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(presets[position.toInt().coerceIn(0, presets.lastIndex)]) },
            valueRange = 0f..presets.lastIndex.toFloat(),
            steps = presets.size - 2,
        )
    }
}

/** The current time, refreshed every 15 s so that "lost sync" shows up without any other event. */
@Composable
private fun nowMs(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_REFRESH_MS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The coordination server: where new groups are made, with its address and key when the user brings their own. */
@Composable
private fun ServerCard(
    ui: ShareUi,
    vm: ShareViewModel,
) {
    val saved = ui.settings.server
    var url by remember(saved) { mutableStateOf(saved?.dbUrl.orEmpty()) }
    var key by remember(saved) { mutableStateOf(saved?.apiKey.orEmpty()) }
    val filled = url.isNotBlank() || key.isNotBlank()
    val problem = if (filled) RelayConfig.problem(RelayConfig.of(url, key)) else null
    FoldCard(
        title = stringResource(R.string.share_server_title),
        summary =
            stringResource(
                when {
                    saved != null -> R.string.share_server_custom
                    ui.serverForNewGroups != null -> R.string.share_server_builtin
                    else -> R.string.share_server_none_set
                },
            ),
    ) {
        Text(stringResource(R.string.share_server_text), style = MaterialTheme.typography.bodySmall)
        if (!ui.inGroup) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    save(vm, url, key)
                },
                label = { Text(stringResource(R.string.share_server_url)) },
                isError = problem == RelayConfig.Problem.BAD_URL,
                supportingText =
                    if (problem == RelayConfig.Problem.BAD_URL) {
                        { Text(stringResource(R.string.share_server_bad_url)) }
                    } else {
                        null
                    },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    save(vm, url, key)
                },
                label = { Text(stringResource(R.string.share_server_key)) },
                isError = problem == RelayConfig.Problem.BAD_KEY,
                supportingText =
                    if (problem == RelayConfig.Problem.BAD_KEY) {
                        { Text(stringResource(R.string.share_server_bad_key)) }
                    } else {
                        null
                    },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (filled) {
                TextButton(onClick = {
                    url = ""
                    key = ""
                    vm.updateSettings { s -> s.copy(server = null) }
                }) { Text(stringResource(R.string.share_server_reset)) }
            }
        }
    }
}

/** Keeps the pair only when it is usable; an empty pair clears the choice. */
private fun save(
    vm: ShareViewModel,
    url: String,
    key: String,
) {
    val config = RelayConfig.of(url, key)
    vm.updateSettings { s ->
        s.copy(server = if (url.isBlank() && key.isBlank()) null else config.takeIf(RelayConfig::isUsable) ?: s.server)
    }
}

/**
 * A code to show, in a sheet (P29.8): the QR first, what it does, « Envoyer le lien par message » across the sheet and
 * « Fermer » (an invitation of the server stays valid 7 days: closing does not cancel it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeSheet(
    title: String,
    hint: String,
    code: ShareCode,
    busy: Boolean,
    primary: Pair<String, () -> Unit>?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_link_chooser)
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            QrImage(code.qrText, modifier = Modifier.padding(vertical = 16.dp).size(QR_SIZE))
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Button(
                onClick = {
                    val send =
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.link)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 16.dp)
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_send_link))
            }
            if (primary != null) {
                OutlinedButton(
                    onClick = primary.second,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
                ) { Text(primary.first) }
            }
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

/** The camera with a QR code reader (full screen); asks for the camera permission first. */
@Composable
private fun ScanDialog(
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            denied = !ok
        }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when {
                granted && !unavailable -> {
                    QrScanner(onCode = onCode, onUnavailable = {
                        unavailable = true
                    }, modifier = Modifier.fillMaxSize())
                    Text(
                        stringResource(R.string.share_scan_hint),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp),
                    )
                }

                else -> {
                    Text(
                        stringResource(
                            if (unavailable) R.string.share_camera_unavailable else R.string.share_camera_denied,
                        ),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    if (denied) {
                        TextButton(
                            onClick = { launcher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                        ) { Text(stringResource(R.string.share_camera_retry)) }
                    }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
            ) { Text(stringResource(R.string.action_cancel), color = Color.White) }
        }
    }
}

@Composable
private fun PasteDialog(
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.share_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.share_paste_hint))
                OutlinedTextField(value = text, onValueChange = {
                    text = it
                }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.share_paste_ok))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The height of the main buttons of the screen (create, join), as in the preview of P29.0. */
private val MAIN_BUTTON_HEIGHT = 52.dp
private const val MS_PER_S = 1000L
private const val SECONDS_PER_MINUTE = 60
private const val VISIBLE_MESSAGES = 50
private val QR_SIZE = 192.dp
private const val MAX_DRAFT = 200

private const val CLOCK_REFRESH_MS = 15_000L
