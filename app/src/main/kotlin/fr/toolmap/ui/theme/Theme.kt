package fr.toolmap.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

// "Crête" style (P29.0, docs/previews/design-00-style.html, variant E): a petrol shell carries the titles
// (top band of every screen and panel, full-width bottom bar), turquoise marks what is on.
private val Petrol = Color(0xFF163A4A)
private val Turquoise = Color(0xFF14918C)

private val LightColors =
    lightColorScheme(
        primary = Petrol,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E6EC),
        onPrimaryContainer = Color(0xFF0B2A36),
        inversePrimary = Color(0xFF8FCDE0),
        secondary = Turquoise,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD3EEEC),
        onSecondaryContainer = Color(0xFF0B5B57),
        tertiary = Color(0xFF9A5B1E),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFBDCC2),
        onTertiaryContainer = Color(0xFF3A1E05),
        background = Color(0xFFF2F5F6),
        onBackground = Color(0xFF122027),
        surface = Color(0xFFF2F5F6),
        onSurface = Color(0xFF122027),
        surfaceVariant = Color(0xFFE2ECEF),
        onSurfaceVariant = Color(0xFF506169),
        surfaceTint = Petrol,
        inverseSurface = Color(0xFF27353B),
        inverseOnSurface = Color(0xFFEEF3F5),
        outline = Color(0xFFB7C6CC),
        outlineVariant = Color(0xFFD5DEE2),
        surfaceBright = Color(0xFFF7F9FA),
        surfaceDim = Color(0xFFD8E1E4),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F9FA),
        surfaceContainer = Color(0xFFEEF3F5),
        surfaceContainerHigh = Color(0xFFE8EFF2),
        surfaceContainerHighest = Color(0xFFE2ECEF),
    )

// Night: deep petrol surfaces, light turquoise for what is on.
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF7FD6D1),
        onPrimary = Color(0xFF003734),
        primaryContainer = Color(0xFF1E4D5E),
        onPrimaryContainer = Color(0xFFCDEBF3),
        inversePrimary = Petrol,
        secondary = Color(0xFF5CC9C2),
        onSecondary = Color(0xFF003734),
        secondaryContainer = Color(0xFF0E4F4B),
        onSecondaryContainer = Color(0xFFBFEFEB),
        tertiary = Color(0xFFF0B784),
        onTertiary = Color(0xFF4A2800),
        tertiaryContainer = Color(0xFF6A3D10),
        onTertiaryContainer = Color(0xFFFFDCC2),
        background = Color(0xFF0C1A20),
        onBackground = Color(0xFFDDE7EA),
        surface = Color(0xFF0C1A20),
        onSurface = Color(0xFFDDE7EA),
        surfaceVariant = Color(0xFF213842),
        onSurfaceVariant = Color(0xFFA9BAC1),
        surfaceTint = Color(0xFF7FD6D1),
        inverseSurface = Color(0xFFDDE7EA),
        inverseOnSurface = Color(0xFF1A2F38),
        outline = Color(0xFF6F8790),
        outlineVariant = Color(0xFF2C4049),
        surfaceBright = Color(0xFF2A4049),
        surfaceDim = Color(0xFF0C1A20),
        surfaceContainerLowest = Color(0xFF08141A),
        surfaceContainerLow = Color(0xFF11222A),
        surfaceContainer = Color(0xFF152830),
        surfaceContainerHigh = Color(0xFF1A2F38),
        surfaceContainerHighest = Color(0xFF213842),
    )

/** The colours of the shell (title bands, bottom bar) and of what is on, which Material's scheme has no role for. */
@Immutable
data class ToolMapColors(
    /** Title bands of screens and panels, bottom bar. */
    val shell: Color,
    val onShell: Color,
    /** Secondary text on the shell (subtitle, version). */
    val onShellVariant: Color,
    /** The pill behind the selected item of the bottom bar. */
    val navIndicator: Color,
    val onNavIndicator: Color,
    /** Switches that are on, badges and progress. */
    val accent: Color,
    val onAccent: Color,
    /** Panels over the map and grouped lists. */
    val panel: Color,
    /** Tiles and state blocks inside a panel. */
    val tile: Color,
    val onTile: Color,
    /** Information boxes over the map. */
    val info: Color,
    val onInfo: Color,
    val onInfoVariant: Color,
)

private val LightToolMapColors =
    ToolMapColors(
        shell = Petrol,
        onShell = Color.White,
        onShellVariant = Color(0xFFC9D6DC),
        navIndicator = Color(0xFF7FD6D1),
        onNavIndicator = Color(0xFF06302E),
        accent = Turquoise,
        onAccent = Color.White,
        panel = Color.White,
        tile = Color(0xFFE2ECEF),
        onTile = Petrol,
        info = Color.White,
        onInfo = Petrol,
        onInfoVariant = Color(0xFF506169),
    )

private val DarkToolMapColors =
    ToolMapColors(
        shell = Color(0xFF133442),
        onShell = Color.White,
        onShellVariant = Color(0xFFB4C7CF),
        navIndicator = Color(0xFF7FD6D1),
        onNavIndicator = Color(0xFF06302E),
        accent = Color(0xFF5CC9C2),
        onAccent = Color(0xFF003734),
        panel = Color(0xFF11222A),
        tile = Color(0xFF213842),
        onTile = Color(0xFFCDEBF3),
        info = Color(0xFF11222A),
        onInfo = Color(0xFFDDE7EA),
        onInfoVariant = Color(0xFFA9BAC1),
    )

private val LocalToolMapColors = staticCompositionLocalOf { LightToolMapColors }

/** The ToolMap colours of the current theme (light or night). */
val MaterialTheme.toolMap: ToolMapColors
    @Composable
    @ReadOnlyComposable
    get() = LocalToolMapColors.current

/**
 * Three radii: 12 dp (buttons, fields, menus, chips), 14 dp (cards, tiles, buttons that float), 18 dp (panels, sheets,
 * dialogs).
 */
private val ToolMapShapes =
    Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(14.dp),
        extraLarge = RoundedCornerShape(18.dp),
    )

/** Touch targets and text of the "gloves" mode: bigger than the 48 dp / 1.0 of the guidelines. */
private val GLOVES_TARGET = 64.dp
private val DEFAULT_TARGET = 48.dp
private const val GLOVES_FONT_SCALE = 1.2f

/** The side of a touch target in the current mode (48 dp, 64 dp with gloves), for views that are not Material. */
@Composable
fun minTouchTarget(): Dp {
    val size = LocalMinimumInteractiveComponentSize.current
    return if (size.isSpecified) maxOf(size, DEFAULT_TARGET) else DEFAULT_TARGET
}

@Composable
fun ToolMapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    wallpaperColors: Boolean = false,
    gloves: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors =
        when {
            wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }
    val density = LocalDensity.current
    val adjusted = if (gloves) Density(density.density, density.fontScale * GLOVES_FONT_SCALE) else density
    CompositionLocalProvider(
        LocalDensity provides adjusted,
        LocalMinimumInteractiveComponentSize provides if (gloves) GLOVES_TARGET else DEFAULT_TARGET,
        LocalToolMapColors provides if (darkTheme) DarkToolMapColors else LightToolMapColors,
    ) {
        MaterialTheme(colorScheme = colors, shapes = ToolMapShapes, content = content)
    }
}
