package fr.toolmap.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.SettingsSearch
import fr.toolmap.ui.ToolMapTopBar
import fr.toolmap.ui.screenInsets
import fr.toolmap.ui.theme.DropdownMenu
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.toolMap
import kotlinx.coroutines.launch
import java.io.File

/**
 * The settings (P29.9, « Loupe »). The list opens with « Rechercher un réglage » over the nine categories: what is
 * typed shows the matching categories and the settings themselves, usable there, the category at the start of their
 * second line and the words found in bold. A category opens on a page whose tabs, in the band of the bar, lead to the
 * neighbouring categories; the loupe of the bar goes back to the search. Every change is saved at once; « Rétablir les
 * réglages par défaut » applies at once and offers « Annuler ». Back closes the page, then clears the search, then
 * leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenBackup: (() -> Unit)? = null,
    onOpenMultiZoom: (() -> Unit)? = null,
    onMoveTiles: (File) -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val category = vm.category
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val links =
        remember(onOpenBackup, onOpenMultiZoom, onMoveTiles) {
            SettingsLinks(onOpenBackup, onOpenMultiZoom, onMoveTiles)
        }
    // The loupe of a page asks the field of the list to take the focus, once.
    var focusSearch by remember { mutableStateOf(false) }

    fun back() {
        when {
            vm.category != null -> vm.category = null
            vm.query.isNotEmpty() -> vm.query = ""
            else -> onBack()
        }
    }

    fun reset() {
        val before = settings
        vm.update { AppSettings() }
        scope.launch {
            val answer =
                snackbar.showSnackbar(
                    resources.getString(R.string.settings_reset_done),
                    actionLabel = resources.getString(R.string.action_cancel),
                    duration = SnackbarDuration.Short,
                )
            if (answer == SnackbarResult.ActionPerformed) vm.update { before }
        }
    }

    BackHandler(enabled = category != null || vm.query.isNotEmpty()) { back() }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(screenInsets()),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                ToolMapTopBar(
                    title = stringResource(R.string.settings_title),
                    onBack = ::back,
                    actions = {
                        if (category != null) {
                            IconButton(
                                onClick = {
                                    vm.category = null
                                    focusSearch = true
                                },
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.settings_search))
                            }
                        }
                        MoreMenu(onReset = ::reset)
                    },
                )
                if (category != null) CategoryTabs(category) { vm.category = it }
            }
        },
    ) { padding ->
        if (category == null) {
            ListPage(
                padding = padding,
                settings = settings,
                vm = vm,
                links = links,
                focus = focusSearch,
                onFocused = { focusSearch = false },
            )
        } else {
            CategoryPage(padding, category, settings, vm, links)
        }
    }
}

/** The ⋮ of the bar: « Rétablir les réglages par défaut ». */
@Composable
private fun MoreMenu(onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.settings_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_reset)) },
                onClick = {
                    open = false
                    onReset()
                },
            )
        }
    }
}

/**
 * The tabs of the pages, in the band of the bar: the short names of the nine categories, scrolled so that the one open
 * is in view. No swipe between the pages: it would fight the sliders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTabs(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
) {
    val tm = MaterialTheme.toolMap
    PrimaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = tm.shell,
        contentColor = tm.onShell,
        edgePadding = 4.dp,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selected.ordinal, matchContentSize = true),
                width = Dp.Unspecified,
                color = tm.navIndicator,
            )
        },
        divider = {},
        minTabWidth = 0.dp,
    ) {
        SettingsCategory.entries.forEach { category ->
            Tab(
                selected = category == selected,
                onClick = { onSelect(category) },
                selectedContentColor = tm.onShell,
                unselectedContentColor = tm.onShellVariant,
                text = { Text(stringResource(category.tab), style = MaterialTheme.typography.titleSmall) },
            )
        }
    }
}

/** The page of a category: its settings on the white of the panels, from the top at each change of tab. */
@Composable
private fun CategoryPage(
    padding: PaddingValues,
    category: SettingsCategory,
    settings: AppSettings,
    vm: SettingsViewModel,
    links: SettingsLinks,
) {
    val entries = categoryEntries(category, settings, vm, links)
    key(category) {
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.toolMap.panel)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
        ) {
            entries.forEach { entry -> key(entry.key) { entry.row() } }
        }
    }
}

/** The list: the search field, then the nine categories, or what the search found. */
@Composable
private fun ListPage(
    padding: PaddingValues,
    settings: AppSettings,
    vm: SettingsViewModel,
    links: SettingsLinks,
    focus: Boolean,
    onFocused: () -> Unit,
) {
    val terms = SettingsSearch.terms(vm.query)
    Column(
        modifier =
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
    ) {
        SearchField(query = vm.query, onChange = { vm.query = it }, focus = focus, onFocused = onFocused)
        if (terms.isEmpty()) {
            ListBlock {
                SettingsCategory.entries.forEachIndexed { index, category ->
                    if (index > 0) Hairline()
                    CategoryRow(category, terms, kind = false) { vm.category = category }
                }
            }
        } else {
            val categories =
                SettingsCategory.entries.filter { category ->
                    SettingsSearch.matches(terms, listOf(stringResource(category.title), stringResource(category.tab)))
                }
            // The settings whose name holds the words first, then those found in an explanation or an option.
            val found =
                SettingsCategory.entries.flatMap { category ->
                    val name = stringResource(category.title)
                    categoryEntries(category, settings, vm, links)
                        .filter { SettingsSearch.matches(terms, it.words) }
                        .map { name to it }
                }.sortedBy { (_, entry) -> if (SettingsSearch.matches(terms, entry.words.take(1))) 0 else 1 }
            if (categories.isEmpty() && found.isEmpty()) {
                NothingFound(onClear = { vm.query = "" })
            } else {
                ListBlock {
                    categories.forEachIndexed { index, category ->
                        if (index > 0) Hairline()
                        CategoryRow(category, terms, kind = true) { vm.category = category }
                    }
                    found.forEachIndexed { index, (name, entry) ->
                        if (index > 0 || categories.isNotEmpty()) Hairline()
                        key(entry.key) {
                            CompositionLocalProvider(LocalRowPlace provides RowPlace(name, terms)) { entry.row() }
                        }
                    }
                }
            }
        }
    }
}

/** « Rechercher un réglage »: a white field with its loupe, and a cross that clears it. */
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    focus: Boolean,
    onFocused: () -> Unit,
) {
    val tm = MaterialTheme.toolMap
    val requester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            requester.requestFocus()
            onFocused()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(requester),
        decorationBox = { field ->
            Row(
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .background(tm.panel, shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_search),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_search_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
    )
}

/** A category of the list: its icon in the primary colour, its name, « Catégorie » among the results, an arrow. */
@Composable
private fun CategoryRow(
    category: SettingsCategory,
    terms: List<String>,
    kind: Boolean,
    onOpen: () -> Unit,
) {
    CompositionLocalProvider(LocalRowPlace provides RowPlace(null, terms)) {
        SettingRow(
            icon = category.icon,
            title = stringResource(category.title),
            subtitle = if (kind) stringResource(R.string.settings_search_category) else null,
            modifier = Modifier.clickable(onClick = onOpen),
            iconTint = MaterialTheme.colorScheme.primary,
            endPadding = 12.dp,
            trailing = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

/** No setting holds the words typed: say so, and offer to clear the search. */
@Composable
private fun NothingFound(onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.settings_search_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClear) { Text(stringResource(R.string.settings_search_clear)) }
    }
}

/** The rows of the list or of the results, on one white band the width of the screen. */
@Composable
private fun ListBlock(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.toolMap.panel)) { content() }
}

/** The thin line between two rows, from the start of their texts. */
@Composable
private fun Hairline() {
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
