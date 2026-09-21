package com.wearos.ancsbridge.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Orbit's own palette, used when the watch face has no dynamic theme to offer.
 *
 * Blue carries the iPhone link and every primary action, green means a healthy
 * connection, and red is only ever a warning. Everything else is the Material 3 dark
 * neutral ramp, so the app sits on the watch's black background the way Pixel's own
 * apps do.
 */
private val OrbitColorScheme = ColorScheme(
    primary = Color(0xFFA8C7FA),
    primaryDim = Color(0xFF7CACF8),
    primaryContainer = Color(0xFF0842A0),
    onPrimary = Color(0xFF062E6F),
    onPrimaryContainer = Color(0xFFD3E3FD),

    secondary = Color(0xFFBFC8DB),
    secondaryDim = Color(0xFF9AA8BE),
    secondaryContainer = Color(0xFF303E52),
    onSecondary = Color(0xFF253141),
    onSecondaryContainer = Color(0xFFD6E3F7),

    tertiary = Color(0xFF6DD58C),
    tertiaryDim = Color(0xFF37BE5F),
    tertiaryContainer = Color(0xFF0F5223),
    onTertiary = Color(0xFF0A3818),
    onTertiaryContainer = Color(0xFFC4EED0),

    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainer = Color(0xFF1E2126),
    surfaceContainerHigh = Color(0xFF282A2F),
    onSurface = Color(0xFFE3E3E7),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),

    background = Color(0xFF000000),
    onBackground = Color(0xFFE3E3E7),

    error = Color(0xFFF2B8B5),
    errorDim = Color(0xFFEC928E),
    errorContainer = Color(0xFF8C1D18),
    onError = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC)
)

/**
 * Follows the watch face's own colours where the watch supports it, so Orbit matches
 * whatever theme the user picked, and falls back to [OrbitColorScheme] otherwise.
 */
@Composable
fun AncsBridgeTheme(content: @Composable () -> Unit) {
    val dynamic = dynamicColorScheme(LocalContext.current)
    // Take the watch face's colours, but keep the two that carry meaning rather than
    // style: green says the link is healthy, red says something needs attention. A
    // lavender tick for "connected" reads as decoration, not as a status.
    val scheme = dynamic?.copy(
        tertiary = OrbitColorScheme.tertiary,
        tertiaryDim = OrbitColorScheme.tertiaryDim,
        tertiaryContainer = OrbitColorScheme.tertiaryContainer,
        onTertiary = OrbitColorScheme.onTertiary,
        onTertiaryContainer = OrbitColorScheme.onTertiaryContainer,
        error = OrbitColorScheme.error,
        errorDim = OrbitColorScheme.errorDim,
        errorContainer = OrbitColorScheme.errorContainer,
        onError = OrbitColorScheme.onError,
        onErrorContainer = OrbitColorScheme.onErrorContainer
    ) ?: OrbitColorScheme
    MaterialTheme(colorScheme = scheme, content = content)
}
