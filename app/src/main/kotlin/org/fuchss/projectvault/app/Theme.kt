package org.fuchss.projectvault.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** User-selectable appearance: follow the OS, or force light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Money colours that read on both light and dark surfaces.
val MoneyNegative = Color(0xFFC5442E)
val MoneyPositive = Color(0xFF2E7D53)

// Brand accent (the orange in the "Vault" wordmark). Slightly brighter on dark for contrast.
private val BrandOrange = Color(0xFFE4572E)
private val BrandOrangeOnDark = Color(0xFFFF7A52)

/**
 * The brand's orange accent, adjusted for the current theme so it reads on either background. Derived
 * from the active surface (not the OS setting) so it stays correct when the user forces light/dark.
 */
@Composable
fun brandAccent(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) BrandOrangeOnDark else BrandOrange

// A small palette users can pick from when creating a profile.
val ProfilePalette = listOf(
    "#15616D", "#E4572E", "#4C8BF5", "#2E7D53", "#B5179E", "#F2A900", "#5A4FCF", "#0F8B8D",
)

private val Light = lightColorScheme(
    primary = Color(0xFF15616D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE7EC),
    onPrimaryContainer = Color(0xFF00363E),
    secondary = Color(0xFF4A6572),
    background = Color(0xFFF6F8F9),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDF1F3),
    onSurfaceVariant = Color(0xFF52606A),
    outline = Color(0xFFC3CDD3),
    outlineVariant = Color(0xFFDCE3E7),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF83D3DE),
    onPrimary = Color(0xFF00363E),
    primaryContainer = Color(0xFF134E58),
    onPrimaryContainer = Color(0xFFCDE7EC),
    secondary = Color(0xFFB2CBD6),
    background = Color(0xFF14181A),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF1B2023),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF3A464C),
    onSurfaceVariant = Color(0xFFBFC8CE),
    outline = Color(0xFF88949B),
    outlineVariant = Color(0xFF3A464C),
    error = Color(0xFFFFB4AB),
)

@Composable
fun VaultTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

/** Parses a "#RRGGBB" hex string to a [Color], falling back to the theme-neutral grey. */
fun parseHexColor(hex: String?): Color = runCatching {
    val clean = hex?.removePrefix("#") ?: return@runCatching Color(0xFF9AA6AD)
    Color(("FF" + clean).toLong(16))
}.getOrDefault(Color(0xFF9AA6AD))
