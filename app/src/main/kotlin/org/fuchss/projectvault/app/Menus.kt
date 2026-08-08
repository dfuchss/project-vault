package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The app's **select pill** and the menu it opens.
 *
 * Material's stock `DropdownMenu` is a flat, near-square sheet of tightly packed rows that shares no
 * visual language with the pills that trigger it. These replace it: the pill animates (tint + a
 * chevron that flips) while its menu is a rounded, hairline-bordered card of inset rows with a hover
 * highlight and an explicit checkmark on the current value — so the open menu reads as an extension
 * of the pill rather than a system popup.
 */

/** Height of one menu row; the menu caps its height at a whole number of these. */
private val MenuRowHeight = 36.dp

/**
 * The trigger: a pill showing the current [label]. [active] marks a *set* value (a filter that is
 * narrowing the view) with the primary tint; [expanded] drives the chevron and the open-state fill.
 */
@Composable
internal fun SelectPill(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    leadingDot: Color? = null,
    prefix: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlighted = active || expanded
    val container by animateColorAsState(
        when {
            highlighted -> scheme.primaryContainer
            hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHigh
        },
        label = "pill-container",
    )
    val border by animateColorAsState(
        when {
            highlighted -> scheme.primary.copy(alpha = 0.55f)
            hovered -> scheme.outline.copy(alpha = 0.5f)
            else -> hairline()
        },
        label = "pill-border",
    )
    val content = if (highlighted) scheme.onPrimaryContainer else scheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = container,
        border = BorderStroke(1.dp, border),
        interactionSource = interaction,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (leadingDot != null) Dot(leadingDot)
            if (prefix != null) {
                Text(prefix, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.65f))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = content)
            Chevron(expanded = expanded, color = content.copy(alpha = 0.75f))
        }
    }
}

/** A 10dp chevron that rotates 180° while the menu is open. */
@Composable
private fun Chevron(expanded: Boolean, color: Color) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Canvas(Modifier.size(11.dp).rotate(rotation)) {
        val w = size.width
        val h = size.height
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.38f),
            end = Offset(w * 0.5f, h * 0.68f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.68f),
            end = Offset(w * 0.84f, h * 0.38f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The menu surface: rounded, hairline-bordered and shadowed, sitting just below its trigger. Height
 * is capped at [maxRows] rows so a long list (every category, every month) scrolls instead of running
 * the whole window height.
 */
@Composable
internal fun VaultMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxRows: Int = 9,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The popup itself is left transparent and unshadowed: the visible container is [MenuPanel], so
    // the exact surface that ships is a composable that can also be rendered on its own (previews,
    // screenshot checks) instead of being locked inside Material's popup internals.
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = DpOffset(0.dp, 6.dp),
        containerColor = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        MenuPanel(maxRows = maxRows, content = content)
    }
}

/** The menu's visible surface: rounded, hairline-bordered, shadowed, with inset rows. */
@Composable
internal fun MenuPanel(
    modifier: Modifier = Modifier,
    maxRows: Int = 9,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hairline()),
        shadowElevation = 16.dp,
        modifier = modifier.widthIn(min = 190.dp),
    ) {
        Column(
            Modifier
                .heightIn(max = MenuRowHeight * maxRows + 12.dp)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            content = content,
        )
    }
}

/**
 * One row of a [VaultMenu]: inset from the menu edge with its own rounded highlight, a leading dot
 * for colour-coded entries (categories, profiles) and a checkmark when it is the current value.
 */
@Composable
internal fun VaultMenuItem(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    leadingDot: Color? = null,
    emphasis: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            selected -> scheme.primaryContainer
            hovered -> scheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        label = "item-container",
    )
    val content = when {
        selected -> scheme.onPrimaryContainer
        emphasis -> scheme.primary
        else -> scheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = container,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().height(MenuRowHeight),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leadingDot != null) Dot(leadingDot)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || emphasis) FontWeight.SemiBold else FontWeight.Normal,
                color = content,
                // The popup sizes itself to the widest row, so a long name widens the menu rather
                // than wrapping inside a fixed-height row.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) CheckMark(scheme.onPrimaryContainer)
        }
    }
}

/** A hairline separator between groups of menu rows (e.g. values vs. "Manage categories…"). */
@Composable
internal fun VaultMenuDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        color = hairline(),
    )
}

@Composable
private fun CheckMark(color: Color) {
    Canvas(Modifier.size(13.dp)) {
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.16f, h * 0.54f), Offset(w * 0.42f, h * 0.78f), strokeWidth = 1.9.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.86f, h * 0.24f), strokeWidth = 1.9.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * A raised panel: the app's card look in one place — a faintly graded fill, a hairline border and a
 * soft shadow. Used instead of Material's flat `Card` so panels lift off the backdrop.
 */
@Composable
internal fun VaultCard(
    modifier: Modifier = Modifier,
    corner: Dp = 18.dp,
    padding: PaddingValues = PaddingValues(0.dp),
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Surface(
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .clip(shape)
                .background(surfaceBrush())
                .border(1.dp, accent?.copy(alpha = 0.45f) ?: hairline(), shape)
                .padding(padding),
            content = content,
        )
    }
}

/**
 * The app's primary action: the accent sweep, white label, and a lift on hover. Material's filled
 * button paints a flat `primary`, which on the dark theme is a pale mint that reads washed-out at
 * button size — the fixed brand gradient stays rich in both themes.
 */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val elevation by animateDpAsState(if (hovered && enabled) 10.dp else 4.dp, label = "primary-elevation")
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.Transparent,
        shadowElevation = if (enabled) elevation else 0.dp,
        interactionSource = interaction,
        modifier = modifier,
    ) {
        Box(
            Modifier
                .clip(shape)
                .then(if (enabled) Modifier.background(accentBrush()) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
                .padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) OnAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Vertical space that animates with the content it separates (used by expanding sections). */
@Composable
internal fun AnimatedSpacer(height: Dp, visible: Boolean) {
    val h by animateDpAsState(if (visible) height else 0.dp, label = "spacer")
    Spacer(Modifier.height(h))
}

/** Small helper so call sites can put a fixed gap between a pill and its neighbours. */
@Composable
internal fun PillSpacer() = Spacer(Modifier.width(8.dp))
