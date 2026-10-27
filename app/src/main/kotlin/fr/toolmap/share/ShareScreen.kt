package fr.toolmap.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.PeerState
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.ShareCadence
import fr.toolmap.map.MenuIcons
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.rememberNotificationAsk
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** The sharing screen: group, profile, invitation and scanning, members, texts and SOS. */
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
    var scanning by rememberSaveable { mutableStateOf(false) }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }
    var openMember by remember { mutableStateOf<Long?>(null) }
    var showIntro by rememberSaveable { mutableStateOf(false) }

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

    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        topBar = {
            ToolMapTopBar(
                title = stringResource(R.string.share_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showIntro = true }) {
                        Icon(MenuIcons.Help, contentDescription = stringResource(R.string.menu_help))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // What the user came for first (start, join; then in a group: send, see the others, SOS), the settings
            // after, folded.
            if (!ui.inGroup) {
                Text(
                    stringResource(R.string.share_start_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // One filled button for what comes first, outlined ones with their icon for joining (P29.0).
                Button(onClick = vm::invite, modifier = Modifier.fillMaxWidth().heightIn(min = MAIN_BUTTON_HEIGHT)) {
                    Text(stringResource(R.string.share_create), style = MaterialTheme.typography.titleSmall)
                }
                JoinButton(MenuIcons.QrCodeScanner, stringResource(R.string.share_join_scan)) { scanning = true }
                JoinButton(MenuIcons.Link, stringResource(R.string.share_join_paste)) { pasting = true }
                SettingsGroup(
                    { ProfileCard(ui, vm) },
                    { MapDisplayCard(ui, vm) },
                    { ServerCard(ui, vm) },
                )
            } else {
                SharingCard(
                    ui = ui,
                    vm = vm,
                    onToggle = { on ->
                        if (!on) {
                            vm.stopSharing()
                        } else if (hasFineLocation(context)) {
                            startSharing()
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    },
                )
                if (ui.gpsUnavailable) {
                    Text(stringResource(R.string.share_gps_unavailable), color = MaterialTheme.colorScheme.error)
                }
                ShareAlertCards(ui.alerts, onBack = { vm.recheckPhone() })
                MembersCard(ui, onOpen = { openMember = it.id })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = vm::invite, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_invite))
                    }
                    OutlinedButton(onClick = { scanning = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_scan))
                    }
                }
                TextButton(onClick = { pasting = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.share_join_paste))
                }
                if (ui.ownSos) {
                    Button(
                        onClick = vm::stopSos,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(SOS_RED)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.share_sos_stop)) }
                } else {
                    Button(
                        onClick = { sosDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(SOS_RED)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.share_sos_send)) }
                }
                MessagesCard(ui, onSend = vm::sendText)
                SettingsGroup(
                    { SendSettingsCard(ui, vm) },
                    { ProfileCard(ui, vm) },
                    { MapDisplayCard(ui, vm) },
                    { ServerCard(ui, vm) },
                )
                OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.share_leave), color = MaterialTheme.colorScheme.error)
                }
            }
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
        CodeDialog(
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
        CodeDialog(
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
        SosSendDialog(
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
            MemberDialog(
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

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    ui: ShareUi,
    vm: ShareViewModel,
) {
    FoldCard(
        title = stringResource(R.string.share_profile_title),
        summary = ui.settings.name.ifBlank { stringResource(R.string.share_name_hint) },
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
            name = name.ifBlank { "?" },
            icon = ui.settings.icon,
            argb = ui.settings.colorArgb(),
            picture = ui.settings.pictureBytes(),
            onIcon = { id -> vm.updateSettings { it.copy(icon = id) } },
            onColor = { c -> vm.updateSettings { it.copy(color = c) } },
            onPicture = { bytes -> vm.updateSettings { it.withPicture(bytes) } },
        )
    }
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

@Composable
private fun SharingCard(
    ui: ShareUi,
    vm: ShareViewModel,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.share_sharing), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.share_sharing_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = ui.sharing, onCheckedChange = onToggle)
        }
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

/** « Rejoindre : … »: an outlined button with its icon, as high as the button that creates a group. */
@Composable
private fun JoinButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = MAIN_BUTTON_HEIGHT)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * « Réglages du partage », a petrol caption over the settings, which make one white list with lines between them
 * (P29.0, « Crête »), after the actions.
 */
@Composable
private fun SettingsGroup(vararg rows: @Composable () -> Unit) {
    Text(
        stringResource(R.string.share_settings_caption),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 10.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.toolMap.panel),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
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

@Composable
private fun MembersCard(
    ui: ShareUi,
    onOpen: (MemberView) -> Unit,
) {
    val now = nowMs()
    val up = serverUp(ui)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_members), style = MaterialTheme.typography.titleMedium)
            if (ui.onServer) {
                Text(
                    stringResource(R.string.share_server_line, serverStateLabel(ui.server)),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (up) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (ui.others.isEmpty()) Text(stringResource(R.string.share_no_members))
            for (m in ui.members) {
                val state = PeerState.of(m, now, up)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(m) },
                ) {
                    MemberBadge(m.name, m.color, picture = m.picture)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (m.isSelf) stringResource(R.string.share_self, m.name) else m.name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            peerStateLabel(state) +
                                if (m.isSelf) {
                                    " · " +
                                        stringResource(
                                            if (ui.sharing) R.string.share_self_sending else R.string.share_self_silent,
                                        )
                                } else {
                                    m.fix?.let { " · " + ageText(it.timeSec * MS_PER_S) }.orEmpty()
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    PeerStatusIcon(state)
                }
            }
        }
    }
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

@Composable
private fun MessagesCard(
    ui: ShareUi,
    onSend: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_messages), style = MaterialTheme.typography.titleMedium)
            for (message in ui.messages.takeLast(VISIBLE_MESSAGES)) {
                Text(
                    (if (message.mine) stringResource(R.string.share_message_mine) else message.name) + " : " +
                        message.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            var draft by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}

/** A code to show, with the link to send by message instead. */
@Composable
private fun CodeDialog(
    title: String,
    hint: String,
    code: ShareCode,
    busy: Boolean,
    primary: Pair<String, () -> Unit>?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_link_chooser)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(hint)
                QrImage(code.qrText, modifier = Modifier.fillMaxWidth())
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val send =
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code.link)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }) { Text(stringResource(R.string.share_send_link)) }
            }
        },
        confirmButton = {
            if (primary != null) TextButton(onClick = primary.second) { Text(primary.first) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
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

@Composable
private fun SosSendDialog(
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.share_sos_send_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.share_sos_send_text))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_DRAFT) },
                    placeholder = { Text(stringResource(R.string.share_sos_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }) {
                Text(stringResource(R.string.share_sos_send), color = Color(SOS_RED))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val SOS_RED = 0xFFD32F2F

/** The height of the main buttons of the screen (create, join), as in the preview of P29.0. */
private val MAIN_BUTTON_HEIGHT = 52.dp
private const val MS_PER_S = 1000L
private const val SECONDS_PER_MINUTE = 60
private const val VISIBLE_MESSAGES = 8
private const val MAX_DRAFT = 200

private const val CLOCK_REFRESH_MS = 15_000L
