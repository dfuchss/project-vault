package org.fuchss.projectvault.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** User-selectable appearance: follow the OS, or force light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Money colours that read on both light and dark surfaces.
val MoneyNegative = Color(0xFFC5442E)
val MoneyPositive = Color(0xFF2E7D53)

// Brand accent (the orange in the "Vault" wordmark). Slightly brighter on dark for contrast.
private val BrandOrange = Color(0xFFE4572E)
private val BrandOrangeOnDark = Color(0xFFFF7A52)

/** True when the *active* surface is dark — correct even when the user forces a mode against the OS. */
@Composable
internal fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The brand's orange accent, adjusted for the current theme so it reads on either background. Derived
 * from the active surface (not the OS setting) so it stays correct when the user forces light/dark.
 */
@Composable
fun brandAccent(): Color = if (isDarkTheme()) BrandOrangeOnDark else BrandOrange

// A small palette users can pick from when creating a profile.
val ProfilePalette = listOf(
    "#15616D", "#E4572E", "#4C8BF5", "#2E7D53", "#B5179E", "#F2A900", "#5A4FCF", "#0F8B8D",
)

private val Light = lightColorScheme(
    primary = Color(0xFF116A78),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEAEF),
    onPrimaryContainer = Color(0xFF00323A),
    secondary = Color(0xFF4A6572),
    tertiary = BrandOrange,
    background = Color(0xFFF3F7F9),
    onBackground = Color(0xFF16191B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16191B),
    surfaceVariant = Color(0xFFEAF0F3),
    onSurfaceVariant = Color(0xFF4F5D67),
    // Container ramp — used for the menu/card layering, lightest on top.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFCFD),
    surfaceContainer = Color(0xFFF2F6F8),
    surfaceContainerHigh = Color(0xFFEBF1F4),
    surfaceContainerHighest = Color(0xFFE4EBEF),
    outline = Color(0xFFBCC8CF),
    outlineVariant = Color(0xFFDEE5EA),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD4E0),
    onPrimary = Color(0xFF00323A),
    primaryContainer = Color(0xFF15525D),
    onPrimaryContainer = Color(0xFFD3EFF4),
    secondary = Color(0xFFB2CBD6),
    tertiary = BrandOrangeOnDark,
    background = Color(0xFF0E1315),
    onBackground = Color(0xFFE4E8EA),
    surface = Color(0xFF171E21),
    onSurface = Color(0xFFE4E8EA),
    surfaceVariant = Color(0xFF2B363B),
    onSurfaceVariant = Color(0xFFB9C5CB),
    surfaceContainerLowest = Color(0xFF0B0F11),
    surfaceContainerLow = Color(0xFF141A1D),
    surfaceContainer = Color(0xFF1A2225),
    surfaceContainerHigh = Color(0xFF20292D),
    surfaceContainerHighest = Color(0xFF273236),
    outline = Color(0xFF7C8B92),
    outlineVariant = Color(0xFF323E43),
    error = Color(0xFFFFB4AB),
)

/**
 * Slightly tightened Material type: negative tracking on the big numbers/headings (they read as
 * crisper at large sizes) and a touch of positive tracking on the small uppercase labels.
 */
private val VaultTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(letterSpacing = (-0.6).sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(letterSpacing = (-0.5).sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(letterSpacing = (-0.4).sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(letterSpacing = (-0.1).sp),
        labelSmall = labelSmall.copy(letterSpacing = 0.6.sp),
        labelMedium = labelMedium.copy(letterSpacing = 0.2.sp),
    )
}

/** Softer, larger radii than Material's defaults — the app reads friendlier and less "boxy". */
private val VaultShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VaultTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = VaultTypography,
        shapes = VaultShapes,
        content = content,
    )
}

// ---------------------------------------------------------------- Depth & sheen
//
// Flat fills look dull on a large desktop window, so surfaces get a *very* low-contrast gradient:
// lighter at the top-left, settling into the base colour. The steps are deliberately small (a few
// percent) — enough to catch the eye as depth, never enough to read as a visible band.

/** The window backdrop: the base background with a soft primary-tinted glow in the top-left. */
@Composable
internal fun appBackgroundBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    val glow = scheme.primary.copy(alpha = if (isDarkTheme()) 0.10f else 0.07f)
    return Brush.linearGradient(
        0f to glow.compositeOver(scheme.background),
        0.45f to scheme.background,
        1f to scheme.background,
        start = Offset.Zero,
        end = Offset(1400f, 1100f),
    )
}

/** A card/panel fill: a hair lighter at the top, fading into the container colour. */
@Composable
internal fun surfaceBrush(base: Color = MaterialTheme.colorScheme.surface): Brush {
    val lift = if (isDarkTheme()) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.55f)
    return Brush.verticalGradient(listOf(lift.compositeOver(base), base))
}

/**
 * The accent sweep used on primary actions and the selected navigation item. Fixed brand teals
 * rather than scheme colours: the scheme's primary flips light/dark between themes, which would make
 * the sweep pale on dark. These stay deep enough for white text in both.
 */
@Composable
internal fun accentBrush(): Brush = Brush.linearGradient(
    listOf(Color(0xFF0F5C68), Color(0xFF1B8A9B)),
    start = Offset.Zero,
    end = Offset(420f, 90f),
)

/** Content colour that reads on [accentBrush]. */
internal val OnAccent = Color(0xFFF2FBFC)

/** Hairline that separates a raised surface from the backdrop without drawing a hard box. */
@Composable
internal fun hairline(): Color =
    if (isDarkTheme()) Color.White.copy(alpha = 0.07f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)

/** Composites [this] over [background] — flattens an alpha colour so it can start a gradient. */
private fun Color.compositeOver(background: Color): Color {
    val a = alpha + background.alpha * (1 - alpha)
    if (a == 0f) return Color.Transparent
    fun ch(f: Float, b: Float) = (f * alpha + b * background.alpha * (1 - alpha)) / a
    return Color(ch(red, background.red), ch(green, background.green), ch(blue, background.blue), a)
}

/** Parses a "#RRGGBB" hex string to a [Color], falling back to the theme-neutral grey. */
fun parseHexColor(hex: String?): Color = runCatching {
    val clean = hex?.removePrefix("#") ?: return@runCatching Color(0xFF9AA6AD)
    Color(("FF" + clean).toLong(16))
}.getOrDefault(Color(0xFF9AA6AD))
