package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind

/**
 * Which category kinds a transaction may take, by sign: a **positive** amount can only be income
 * (Gehalt / Weitere Einkünfte) or a transfer (Umbuchung & Sparen); a **negative** amount only an
 * expense or a transfer — never income. Used to filter the picker and constrain new categories.
 */
internal fun allowedKindsForAmount(amountCents: Long): List<CategoryKind> = when {
    amountCents > 0 -> listOf(CategoryKind.INCOME, CategoryKind.TRANSFER)
    amountCents < 0 -> listOf(CategoryKind.EXPENSE, CategoryKind.TRANSFER)
    else -> CategoryKind.entries.toList()
}

internal fun categoryAllowedForAmount(amountCents: Long, kind: CategoryKind): Boolean =
    kind in allowedKindsForAmount(amountCents)

// ---------------------------------------------------------------- Small reusable bits

@Composable
internal fun SectionHeader(title: String, onAdd: () -> Unit, onManage: (() -> Unit)? = null) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (onManage != null) {
            TextButton(onClick = onManage, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(strings.manage) }
        }
        TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(strings.addShort) }
    }
}

@Composable
internal fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, icon: (@Composable (Color) -> Unit)? = null) {
    // Selected: the accent sweep with a soft coloured glow beneath it, so the current view is
    // unmistakable at a glance. Unselected: transparent, warming on hover.
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val content = if (selected) OnAccent else scheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        shadowElevation = if (selected) 6.dp else 0.dp,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clip(shape)
                .then(
                    when {
                        selected -> Modifier.background(accentBrush())
                        hovered -> Modifier.background(scheme.surfaceContainerHigh)
                        else -> Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { icon(content); Spacer(Modifier.width(10.dp)) }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
        }
    }
}

/** A round, borderless icon button drawn with a [glyph]; [glyph] receives the content colour. */
@Composable
internal fun IconAction(onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) { glyph(color) }
    }
}

/**
 * A one-click theme **toggle** that cycles System → Light → Dark → System, showing a distinct icon
 * for the current mode (contrast circle = follow system, sun = light, moon = dark).
 */
@Composable
internal fun ThemeToggle(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val next = when (mode) { ThemeMode.SYSTEM -> ThemeMode.LIGHT; ThemeMode.LIGHT -> ThemeMode.DARK; ThemeMode.DARK -> ThemeMode.SYSTEM }
    IconAction(onClick = { onChange(next) }) { color ->
        when (mode) {
            ThemeMode.SYSTEM -> SystemThemeGlyph(color)
            ThemeMode.LIGHT -> SunGlyph(color)
            ThemeMode.DARK -> MoonGlyph(color)
        }
    }
}

/**
 * A one-click language **toggle** cycling English ↔ German, showing the current language's two-letter
 * code (EN/DE). Sits next to the theme toggle in the top bar.
 */
@Composable
internal fun LanguageToggle(language: AppLanguage, onChange: (AppLanguage) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = { onChange(language.next()) },
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        modifier = Modifier.height(34.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            GlobeGlyph(color)
            Text(language.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

/** A globe (outline + equator/latitudes + a meridian ellipse) — the "language" symbol for the switcher. */
@Composable
private fun GlobeGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val r = (size.minDimension - stroke) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, radius = r, center = c, style = Stroke(stroke))
        // Equator + two latitude lines.
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = stroke)
        drawLine(color, Offset(c.x - r * 0.78f, c.y - r * 0.5f), Offset(c.x + r * 0.78f, c.y - r * 0.5f), strokeWidth = stroke * 0.8f)
        drawLine(color, Offset(c.x - r * 0.78f, c.y + r * 0.5f), Offset(c.x + r * 0.78f, c.y + r * 0.5f), strokeWidth = stroke * 0.8f)
        // Meridian ellipse + central axis (the "curved longitude" read of a globe).
        drawOval(color, topLeft = Offset(c.x - r * 0.5f, c.y - r), size = Size(r, r * 2), style = Stroke(stroke))
        drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = stroke * 0.8f)
    }
}

/** SYSTEM: a "contrast" circle (outline + filled left half) — follow the OS appearance. */
@Composable
private fun SystemThemeGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val r = (size.minDimension - stroke) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, radius = r, center = c, style = Stroke(stroke))
        drawArc(color, startAngle = 90f, sweepAngle = 180f, useCenter = true, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
    }
}

/** LIGHT: a sun (core + eight rays). */
@Composable
private fun SunGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val core = size.minDimension * 0.22f
        drawCircle(color, radius = core, center = c)
        val sw = 1.6.dp.toPx()
        val inner = core * 1.6f
        val outer = size.minDimension * 0.48f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble())
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            drawLine(color, Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

/** DARK: a crescent moon (a disc with an offset disc bitten out in the surface colour). */
@Composable
private fun MoonGlyph(color: Color) {
    val bg = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension * 0.42f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, radius = r, center = c)
        drawCircle(bg, radius = r * 0.95f, center = Offset(c.x + r * 0.55f, c.y - r * 0.18f))
    }
}

/** A logout/exit glyph (a door open on the right with an arrow leaving it) for the "close vault" action. */
@Composable
internal fun LogoutGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val sw = 1.8.dp.toPx()
        // Door: a panel open on its right side (top, left, bottom).
        drawPath(
            Path().apply {
                moveTo(w * 0.5f, h * 0.14f); lineTo(w * 0.14f, h * 0.14f)
                lineTo(w * 0.14f, h * 0.86f); lineTo(w * 0.5f, h * 0.86f)
            },
            color, style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Arrow leaving through the doorway.
        val midY = h * 0.5f; val end = w * 0.9f
        drawLine(color, Offset(w * 0.42f, midY), Offset(end, midY), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, midY - h * 0.16f), Offset(end, midY), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, midY + h * 0.16f), Offset(end, midY), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Loads a bundled classpath image (e.g. `branding/app-icon.png`) into a [Painter] without the
 *  deprecated `painterResource(String)`; decodes via Skia and memoizes. */
@Composable
internal fun rememberClasspathPainter(path: String): Painter =
    remember(path) { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(readClasspathBytes(path)).toComposeImageBitmap()) }

private fun readClasspathBytes(path: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/$path")) { "resource not found on classpath: $path" }
        .use { it.readBytes() }

/** A small 2×2 "dashboard" glyph drawn to match the current content colour. */
@Composable
internal fun OverviewGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val gap = size.width * 0.16f
        val cell = (size.width - gap) / 2f
        val r = CornerRadius(cell * 0.28f, cell * 0.28f)
        for (row in 0..1) for (col in 0..1) {
            drawRoundRect(
                color = color,
                topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = r,
                alpha = if (row == 0 && col == 0) 1f else 0.55f, // top-left "highlighted" tile
            )
        }
    }
}

/** A toggle chip (profile filters). Same pill language as [SelectPill], without the chevron. */
@Composable
internal fun Chip(label: String, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            selected -> scheme.primaryContainer
            hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHigh
        },
        label = "chip",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = container,
        border = BorderStroke(1.dp, if (selected) scheme.primary.copy(alpha = 0.55f) else hairline()),
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) { Dot(dot); Spacer(Modifier.width(6.dp)) }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    }
}

/** A small static tag (account type, status). Tinted rather than grey so it reads as a label, not a button. */
@Composable
internal fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkTheme()) 0.16f else 0.10f),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

/** The "Project Vault" wordmark: teal "Project", orange "Vault" — theme-aware, readable light/dark. */
@Composable
internal fun Wordmark(style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Project ") }
            withStyle(SpanStyle(color = brandAccent())) { append("Vault") }
        },
        style = style,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Minimal wrapping row of chips (avoids depending on experimental FlowRow). */
@Composable
internal fun FlowRowChips(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

internal fun accountTypeLabel(type: AccountType): String = when (type) {
    AccountType.GIRO -> "Girokonto"
    AccountType.TAGESGELD -> "Tagesgeld"
    AccountType.DEPOT -> "Depot"
    AccountType.KREDITKARTE -> "Kreditkarte"
}
