package fr.toolmap.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.view.WindowCompat
import fr.toolmap.R
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.toolMap

/*
 * The one top bar of the screens (P29.0, « Crête », docs/previews/design-00-style.html, variant E): the petrol band
 * that carries the title, the arrow back at the start, the actions as icons at the end. It replaces the « Fermer » in
 * words, the ✕ and the titles of every size of the 13 full screens.
 */

/**
 * The insets a screen under a [ToolMapTopBar] keeps for itself: the sides and the bottom. The bar takes the status bar,
 * so that its band goes up under it.
 */
@Composable
fun screenInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/** Light icons for the subtitle, the arrow and the actions: the bar is dark in both themes. */
private const val LIGHT_THEME_LUMINANCE = 0.5f

/**
 * The top bar of a screen: [title] (and a [subtitle]) in white on the petrol band, [onBack] on the arrow at the start
 * ([navigationIcon] and [navigationDescription] for an editor, which a ✕ cancels), [actions] at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolMapTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    navigationIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    navigationDescription: String = stringResource(R.string.action_back),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tm = MaterialTheme.toolMap
    LightStatusBarIcons()
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = tm.onShellVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(navigationIcon, contentDescription = navigationDescription) }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = tm.shell,
                scrolledContainerColor = tm.shell,
                navigationIconContentColor = tm.onShell,
                titleContentColor = tm.onShell,
                actionIconContentColor = tm.onShell,
            ),
    )
}

/** An action of the top bar that is better said in words (« Enregistrer »): white text on the band. */
@Composable
fun TopBarTextAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tm = MaterialTheme.toolMap
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = tm.onShell,
                disabledContentColor = tm.onShellVariant.copy(alpha = DISABLED_ACTION_ALPHA),
            ),
    ) {
        Text(text)
    }
}

private const val DISABLED_ACTION_ALPHA = 0.6f

/** White icons in the status bar over the band while the bar is shown; the theme's again when it goes. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    val lightTheme = MaterialTheme.colorScheme.background.luminance() > LIGHT_THEME_LUMINANCE
    if (view.isInEditMode) return
    DisposableEffect(view, lightTheme) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose { controller?.isAppearanceLightStatusBars = lightTheme }
    }
}
