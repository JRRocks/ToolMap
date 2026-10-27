package fr.toolmap.wizard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import fr.toolmap.R
import fr.toolmap.core.wizard.ApiKeyExtractor
import fr.toolmap.core.wizard.CapabilitiesDocument
import fr.toolmap.core.wizard.CapabilitiesUrlProblem
import fr.toolmap.core.wizard.PreviewTile
import fr.toolmap.core.wizard.WmsLayer
import fr.toolmap.core.wizard.WmtsCapabilities
import fr.toolmap.core.wizard.WmtsLayer
import fr.toolmap.data.wizard.CapabilitiesFetchException
import fr.toolmap.data.wizard.FetchFailure
import fr.toolmap.data.wizard.ProbeResult
import fr.toolmap.data.wizard.ProbeVerdict
import fr.toolmap.download.formatBytes
import fr.toolmap.maps.MapsIcons
import fr.toolmap.ui.CheckRow
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import fr.toolmap.ui.theme.TextButton

private const val MAX_LISTED_LAYERS = 40
private const val MAX_THREADS_SLIDER = 16f
private const val PERCENT = 100f
private val TILE_PLACEHOLDERS = listOf("{\$z}", "{\$x}", "{\$y}", "{\$q}", "{\$!y}", "{\$bbox}")

// ---------------------------------------------------------------------------------------------------------------
// Address
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun AddressStep(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    if (state.kind == WizardKind.XYZ) {
        XyzAddress(vm, state)
    } else {
        ServiceAddress(vm, state)
    }
    if (state.keysMoved.isNotEmpty()) {
        Text(
            stringResource(R.string.wizard_keys_moved, state.keysMoved.joinToString()),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun XyzAddress(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    val urls = state.draft.urls
    var field by remember { mutableStateOf(TextFieldValue(urls.joinToString("\n"))) }
    // Follow changes made elsewhere (key moved to a parameter, layer picked) without disturbing typing.
    LaunchedEffect(urls) {
        if (parseUrls(field.text) != urls) field = TextFieldValue(urls.joinToString("\n"), TextRange(Int.MAX_VALUE))
    }
    OutlinedTextField(
        value = field,
        onValueChange = {
            field = it
            vm.setUrls(it.text)
        },
        label = { Text(stringResource(R.string.wizard_url_label)) },
        supportingText = { Text(stringResource(R.string.wizard_url_help)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )
    Text(stringResource(R.string.wizard_insert), style = MaterialTheme.typography.labelMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TILE_PLACEHOLDERS) { token ->
            FilterChip(
                selected = false,
                onClick = {
                    val start = field.selection.min
                    val end = field.selection.max
                    val text = field.text.replaceRange(start, end, token)
                    field = TextFieldValue(text, TextRange(start + token.length))
                    vm.setUrls(text)
                },
                label = { Text(token) },
            )
        }
    }
    val detected = remember(urls) { vm.detectedKey() }
    if (detected != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.wizard_key_detected,
                        detected.first,
                        ApiKeyExtractor.masked(detected.second),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.wizard_key_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::extractKeysNow) { Text(stringResource(R.string.wizard_key_move)) }
            }
        }
    }
}

@Composable
private fun ServiceAddress(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    OutlinedTextField(
        value = state.serviceAddress,
        onValueChange = vm::setServiceAddress,
        label = { Text(stringResource(R.string.wizard_service_label)) },
        placeholder = { Text(stringResource(R.string.wizard_service_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = vm::readCapabilities,
            enabled = state.capabilities !is CapabilitiesState.Loading && state.serviceAddress.isNotBlank(),
        ) {
            val loading = state.capabilities is CapabilitiesState.Loading
            Text(stringResource(if (loading) R.string.wizard_reading else R.string.wizard_read))
        }
        TextButton(onClick = { vm.chooseKind(WizardKind.XYZ) }) { Text(stringResource(R.string.wizard_manual)) }
    }
    when (val caps = state.capabilities) {
        CapabilitiesState.Idle, CapabilitiesState.Loading -> {
            Unit
        }

        is CapabilitiesState.Failed -> {
            Text(fetchErrorText(caps.error), color = MaterialTheme.colorScheme.error)
        }

        is CapabilitiesState.Loaded -> {
            when (val doc = caps.fetched.document) {
                is CapabilitiesDocument.Wmts -> WmtsLayers(vm, state, doc.capabilities)
                is CapabilitiesDocument.Wms -> WmsLayers(vm, state, doc.capabilities)
            }
        }
    }
}

@Composable
private fun fetchErrorText(error: CapabilitiesFetchException): String =
    when (error.failure) {
        FetchFailure.BAD_URL -> {
            stringResource(
                when (error.message) {
                    CapabilitiesUrlProblem.EMPTY.name -> R.string.wizard_fetch_empty
                    CapabilitiesUrlProblem.TILE_REQUEST.name -> R.string.wizard_fetch_tile_request
                    else -> R.string.wizard_fetch_not_http
                },
            )
        }

        FetchFailure.NETWORK -> {
            stringResource(R.string.wizard_fetch_network, error.message.orEmpty())
        }

        FetchFailure.HTTP -> {
            stringResource(R.string.wizard_fetch_http, error.httpCode ?: 0)
        }

        FetchFailure.TOO_LARGE -> {
            stringResource(R.string.wizard_fetch_too_large)
        }

        FetchFailure.NOT_CAPABILITIES -> {
            stringResource(R.string.wizard_fetch_not_capabilities, error.message.orEmpty())
        }
    }

@Composable
private fun LayerFilter(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    OutlinedTextField(
        value = state.layerFilter,
        onValueChange = vm::setLayerFilter,
        label = { Text(stringResource(R.string.wizard_layers_filter)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun WmtsLayers(
    vm: SourceWizardViewModel,
    state: WizardState,
    caps: WmtsCapabilities,
) {
    val usable = caps.usableLayers
    Text(
        stringResource(R.string.wizard_layers_found, usable.size, caps.layers.size),
        style = MaterialTheme.typography.titleSmall,
    )
    if (usable.isEmpty()) {
        Text(stringResource(R.string.wizard_layers_none))
        return
    }
    LayerFilter(vm, state)
    val needle = state.layerFilter.trim()
    val matches = remember(usable, needle) { usable.filter { it.matches(needle) } }
    for (layer in matches.take(MAX_LISTED_LAYERS)) {
        LayerRow(layer.title, layer.id, selected = state.choice?.layerId == layer.id) { vm.pickWmtsLayer(layer) }
    }
    if (matches.size > MAX_LISTED_LAYERS) {
        Text(
            stringResource(R.string.wizard_layers_more, matches.size - MAX_LISTED_LAYERS),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val choice = state.choice
    val layer = choice?.let { c -> caps.layers.firstOrNull { it.id == c.layerId } }
    if (choice != null && layer != null) {
        Text(stringResource(R.string.wizard_layer_selected, layer.title), style = MaterialTheme.typography.titleSmall)
        if (layer.styles.size > 1) {
            OptionRow(R.string.wizard_style, layer.styles.map { it.id }, choice.styleId) { id ->
                vm.changeWmtsChoice { it.copy(styleId = id) }
            }
        }
        val sets = caps.usableLinks(layer).map { it.matrixSetId }
        if (sets.size > 1) {
            OptionRow(R.string.wizard_matrix_set, sets, choice.matrixSetId) { id ->
                vm.changeWmtsChoice { it.copy(matrixSetId = id) }
            }
        }
        if (layer.formats.size > 1) {
            OptionRow(R.string.wizard_format, layer.formats, choice.format) { f ->
                vm.changeWmtsChoice { it.copy(format = f) }
            }
        }
    }
}

private fun WmtsLayer.matches(needle: String): Boolean =
    needle.isEmpty() || title.contains(needle, ignoreCase = true) || id.contains(needle, ignoreCase = true)

private fun WmsLayer.matches(needle: String): Boolean =
    needle.isEmpty() || title.contains(needle, ignoreCase = true) || name.contains(needle, ignoreCase = true)

@Composable
private fun WmsLayers(
    vm: SourceWizardViewModel,
    state: WizardState,
    caps: fr.toolmap.core.wizard.WmsCapabilities,
) {
    val usable = caps.usableLayers
    Text(
        stringResource(R.string.wizard_layers_found, usable.size, caps.layers.size),
        style = MaterialTheme.typography.titleSmall,
    )
    if (usable.isEmpty()) {
        Text(stringResource(R.string.wizard_layers_none))
        return
    }
    LayerFilter(vm, state)
    val needle = state.layerFilter.trim()
    val matches = remember(usable, needle) { usable.filter { it.matches(needle) } }
    for (layer in matches.take(MAX_LISTED_LAYERS)) {
        LayerRow(layer.title, layer.name, selected = state.choice?.layerId == layer.name) { vm.pickWmsLayer(layer) }
    }
    if (matches.size > MAX_LISTED_LAYERS) {
        Text(
            stringResource(R.string.wizard_layers_more, matches.size - MAX_LISTED_LAYERS),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val choice = state.choice
    if (choice != null && caps.formats.size > 1) {
        OptionRow(R.string.wizard_format, caps.formats, choice.format) { vm.changeWmsFormat(it) }
    }
}

@Composable
private fun LayerRow(
    title: String,
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (id != title) Text(id, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (selected) {
            // The chosen layer: an icon, not a « ✓ » in text (P29.6).
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.wizard_layer_chosen),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        } else {
            TextButton(onClick = onClick) { Text(stringResource(R.string.action_add)) }
        }
    }
}

@Composable
private fun OptionRow(
    label: Int,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option) })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Access
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun AccessStep(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    val draft = state.draft
    Text(stringResource(R.string.wizard_access_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = draft.referer,
        onValueChange = vm::setReferer,
        label = { Text(stringResource(R.string.wizard_referer)) },
        placeholder = { Text("https://data.geopf.fr/") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.userAgent,
        onValueChange = vm::setUserAgent,
        label = { Text(stringResource(R.string.wizard_user_agent)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.headersText,
        onValueChange = vm::setHeaders,
        label = { Text(stringResource(R.string.wizard_headers)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    OutlinedTextField(
        value = draft.cookie,
        onValueChange = vm::setCookie,
        label = { Text(stringResource(R.string.wizard_cookie)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (draft.params.isNotEmpty()) {
        Text(stringResource(R.string.wizard_params), style = MaterialTheme.typography.titleSmall)
        for ((name, value) in draft.params) ParamField(name, value) { vm.setParam(name, it) }
    }
    Text(stringResource(R.string.wizard_threads, draft.maxThreads), style = MaterialTheme.typography.labelMedium)
    Slider(
        value = draft.maxThreads.toFloat(),
        onValueChange = { vm.setMaxThreads(it.toInt()) },
        valueRange = 1f..MAX_THREADS_SLIDER,
        steps = MAX_THREADS_SLIDER.toInt() - 2,
    )
    Text(stringResource(R.string.wizard_update_delay), style = MaterialTheme.typography.labelMedium)
    val delays =
        listOf(
            "" to R.string.wizard_delay_never,
            "1D" to R.string.wizard_delay_day,
            "1W" to R.string.wizard_delay_week,
            "1M" to R.string.wizard_delay_month,
            "3M" to R.string.wizard_delay_quarter,
            "1Y" to R.string.wizard_delay_year,
        )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(delays) { (value, label) ->
            FilterChip(
                selected = draft.updateDelay.trim().uppercase() == value,
                onClick = { vm.setUpdateDelay(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun ParamField(
    name: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(name) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(name) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.wizard_param_hide else R.string.wizard_param_show))
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Zooms and appearance
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun ZoomsStep(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    val draft = state.draft
    OutlinedTextField(
        value = draft.name,
        onValueChange = vm::setName,
        label = { Text(stringResource(R.string.wizard_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = draft.zoomSpec,
        onValueChange = vm::setZoomSpec,
        label = { Text(stringResource(R.string.wizard_zooms)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text(stringResource(R.string.wizard_tile_size), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (size in fr.toolmap.core.wizard.SourceDraft.TILE_SIZES) {
            FilterChip(selected = draft.tileSize == size, onClick = {
                vm.setTileSize(size)
            }, label = { Text("$size px") })
        }
    }
    Text(stringResource(R.string.wizard_type_label), style = MaterialTheme.typography.labelMedium)
    val kinds =
        listOf(
            "roads" to R.string.wizard_kind_roads,
            "topo" to R.string.wizard_kind_topo,
            "satellite" to R.string.wizard_kind_satellite,
            "hybrid" to R.string.wizard_kind_hybrid,
            "hillshade" to R.string.wizard_kind_hillshade,
            "nautical" to R.string.wizard_kind_nautical,
        )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(kinds) { (value, label) ->
            FilterChip(
                selected = draft.type == value,
                onClick = { vm.setType(if (draft.type == value) null else value) },
                label = { Text(stringResource(label)) },
            )
        }
    }
    // The switch at the end of its line, as everywhere else (P29.6); the whole line toggles it.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = draft.isLayer, role = Role.Switch, onValueChange = vm::setLayer),
    ) {
        Text(
            stringResource(R.string.wizard_is_layer),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = draft.isLayer, onCheckedChange = null)
    }
    if (draft.isLayer) {
        Text(stringResource(R.string.wizard_opacity, draft.opacity), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = draft.opacity / PERCENT,
            onValueChange = { vm.setOpacity((it * PERCENT).toInt()) },
        )
    }
    OutlinedTextField(
        value = draft.copyright,
        onValueChange = vm::setCopyright,
        label = { Text(stringResource(R.string.wizard_copyright)) },
        modifier = Modifier.fillMaxWidth(),
    )
    val place = draft.previewLocation
    Text(
        stringResource(
            R.string.wizard_preview_place,
            if (place == null) {
                stringResource(R.string.wizard_preview_paris)
            } else {
                stringResource(R.string.wizard_preview_coords, place.lat, place.lon, place.zoom)
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.hasMapCenter) {
            OutlinedButton(onClick = vm::usePreviewHere) { Text(stringResource(R.string.wizard_preview_here)) }
        }
        if (place != null) {
            TextButton(onClick = vm::clearPreviewLocation) { Text(stringResource(R.string.wizard_preview_reset)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun TestStep(
    vm: SourceWizardViewModel,
    state: WizardState,
) {
    Text(stringResource(R.string.wizard_test_intro), style = MaterialTheme.typography.bodyMedium)
    val running = state.probe is ProbeState.Running
    OutlinedButton(onClick = vm::runProbe, enabled = !running && state.draft.isValid) {
        Text(
            stringResource(
                when {
                    running -> R.string.wizard_test_running
                    state.probe is ProbeState.Done -> R.string.wizard_test_retry
                    else -> R.string.wizard_test_run
                },
            ),
        )
    }
    if (running) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    val done = state.probe as? ProbeState.Done ?: return
    ProbeCard(done.result, onFixAccess = { vm.goTo(WizardStep.ACCESS) }, onFixAddress = { vm.goTo(WizardStep.ADDRESS) })
}

@Composable
private fun ProbeCard(
    result: ProbeResult,
    onFixAccess: () -> Unit,
    onFixAddress: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tile: PreviewTile = result.tile
            Text(
                stringResource(R.string.wizard_test_tile, tile.z, tile.x, tile.y),
                style = MaterialTheme.typography.labelMedium,
            )
            result.image?.let { bytes ->
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.wizard_test_image_description),
                        modifier = Modifier.size(256.dp),
                    )
                }
            }
            if (result.verdict == ProbeVerdict.CERTIFICATE_REJECTED) {
                CertificateRejectedNote()
            } else {
                Text(
                    stringResource(verdictRes(result.verdict)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(
                    R.string.wizard_test_stats,
                    result.httpCode?.toString() ?: "–",
                    result.contentType ?: "–",
                    formatBytes(result.sizeBytes.toLong()),
                    result.latencyMillis,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            result.detail?.let {
                Text(stringResource(R.string.wizard_test_server_said, it), style = MaterialTheme.typography.bodySmall)
            }
            result.url?.let {
                Text(stringResource(R.string.wizard_test_url, it), style = MaterialTheme.typography.labelSmall)
            }
            if (!result.isOk) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.verdict == ProbeVerdict.KEY_OR_REFERER) {
                        OutlinedButton(onClick = onFixAccess) { Text(stringResource(R.string.wizard_test_fix_access)) }
                    }
                    OutlinedButton(onClick = onFixAddress) { Text(stringResource(R.string.wizard_test_fix_address)) }
                }
            }
        }
    }
}

/**
 * A refused certificate: what to install in Android so that ToolMap accepts it (S2, ADR-066). A line with the error
 * icon, not a box inside the card of the result (P29.6).
 */
@Composable
private fun CertificateRejectedNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            MapsIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.wizard_verdict_certificate_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.wizard_verdict_certificate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun verdictRes(verdict: ProbeVerdict): Int =
    when (verdict) {
        ProbeVerdict.OK -> R.string.wizard_test_ok
        ProbeVerdict.KEY_OR_REFERER -> R.string.wizard_verdict_key_or_referer
        ProbeVerdict.NOT_FOUND -> R.string.wizard_verdict_not_found
        ProbeVerdict.BAD_REQUEST -> R.string.wizard_verdict_bad_request
        ProbeVerdict.RATE_LIMITED -> R.string.wizard_verdict_rate_limited
        ProbeVerdict.SERVER_ERROR -> R.string.wizard_verdict_server_error
        ProbeVerdict.NETWORK -> R.string.wizard_verdict_network
        ProbeVerdict.CLEARTEXT_BLOCKED -> R.string.wizard_verdict_cleartext
        ProbeVerdict.CERTIFICATE_REJECTED -> R.string.wizard_verdict_certificate
        ProbeVerdict.NOT_AN_IMAGE -> R.string.wizard_verdict_not_an_image
        ProbeVerdict.EMPTY -> R.string.wizard_verdict_empty
        ProbeVerdict.TEMPLATE_ERROR -> R.string.wizard_verdict_template
        ProbeVerdict.OUT_OF_RANGE -> R.string.wizard_verdict_out_of_range
    }

// ---------------------------------------------------------------------------------------------------------------
// Save
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun SaveStep(
    vm: SourceWizardViewModel,
    state: WizardState,
    onExport: () -> Unit,
) {
    val draft = state.draft
    Text(stringResource(R.string.wizard_save_name, draft.name), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.wizard_save_zooms, draft.zoomSpec, draft.tileSize),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(
            R.string.wizard_save_servers,
            draft.urls.count {
                it.isNotBlank()
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    val problems = state.issues.filter { it.isError }
    if (problems.isNotEmpty()) {
        Text(stringResource(R.string.wizard_save_problems), style = MaterialTheme.typography.titleSmall)
        IssueLines(problems)
    }
    val notes = state.issues.filter { !it.isError }
    if (notes.isNotEmpty()) {
        Text(stringResource(R.string.wizard_save_notes), style = MaterialTheme.typography.titleSmall)
        IssueLines(notes)
    }
    if (vm.willPurgeStoredTiles()) {
        Text(
            stringResource(R.string.wizard_purge_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (!state.isEditing) {
        CheckRow(checked = state.addToMap, onCheckedChange = vm::setAddToMap) {
            Text(stringResource(R.string.wizard_add_to_map), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (draft.params.isNotEmpty()) {
            CheckRow(checked = state.redactKeysOnExport, onCheckedChange = vm::setRedactKeys) {
                Text(stringResource(R.string.wizard_redact), style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedButton(onClick = onExport, enabled = problems.isEmpty()) {
            Text(stringResource(R.string.wizard_export))
        }
    }
}
