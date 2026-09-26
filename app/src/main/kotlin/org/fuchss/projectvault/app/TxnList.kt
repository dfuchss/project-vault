package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Txn

// ---------------------------------------------------------------------------------------------
// The transaction list, shared by the per-account view and the cross-account review inbox: its
// filter/sort model (pure, so it can be reasoned about and tested without a UI), the row, the
// filter bar and the bulk-selection action bar. Both screens show the same list, so they use the
// same pieces rather than two drifting copies.
// ---------------------------------------------------------------------------------------------

/** The amount column's width — shared by every row so the numbers form a column, not a ragged edge. */
private val AmountColumn = 104.dp

/** The inspector panel's own width; the gutter that holds it adds [InspectorGap] in front. */
private val InspectorWidth = 300.dp
private val InspectorGap = 16.dp

/**
 * Below this content width the inspector floats **over** the list instead of taking a gutter beside
 * it. Either way the list's own width never depends on what is selected — which is the whole point:
 * mounting a 316dp panel next to a `weight(1f)` list re-laid out every row the instant one was
 * clicked, re-ellipsizing counterparties and sliding the clicked row out from under the pointer.
 *
 * The threshold leaves the list ~400dp once the gutter is taken, which is still a readable list;
 * only a genuinely small window falls back to the overlay, where the panel unavoidably covers part
 * of what is behind it.
 */
private val InspectorGutterMinWidth = 720.dp

/** The orders a list can be shown in. Date descending is the query's own order and the default. */
internal enum class TxnSort { DATE_DESC, DATE_ASC, AMOUNT_DESC, AMOUNT_ASC }

/**
 * Everything the list filters and sorts by, as one observable holder — a screen keeps a single
 * `remember`ed instance instead of a dozen loose `mutableStateOf`s threaded through call sites.
 *
 * The amount/date bounds are kept as the **text** the user typed and parsed on use: a half-typed
 * "1." or "01.0" must neither throw nor snap the list to something the user didn't ask for.
 */
@Stable
internal class TxnListFilters {
    var search by mutableStateOf("")
    var filter by mutableStateOf("ALL") // ALL | NONE | REVIEW | <categoryId>
    var period by mutableStateOf<YearMonth?>(null) // null = all time
    var sort by mutableStateOf(TxnSort.DATE_DESC)
    var minAmount by mutableStateOf("")
    var maxAmount by mutableStateOf("")
    var fromDate by mutableStateOf("")
    var toDate by mutableStateOf("")

    /** Whether any of the secondary (amount/date) bounds is set — drives the "more filters" pill. */
    val hasRangeFilter: Boolean
        get() = minAmount.isNotBlank() || maxAmount.isNotBlank() || fromDate.isNotBlank() || toDate.isNotBlank()

    fun clearRanges() {
        minAmount = ""; maxAmount = ""; fromDate = ""; toDate = ""
    }

    /** Applies [filterAfterReviewDisappears] to the live state — see there for why it exists. */
    fun coerceFilter(reviewAvailable: Boolean) {
        filter = filterAfterReviewDisappears(filter, reviewAvailable)
    }
}

/**
 * The filter to use once "To review" has nothing left to show. The pill is hidden when no
 * transaction in scope carries a pending suggestion (a control that can do nothing is not shown —
 * the same rule the month dropdown follows), and a view left on a hidden filter would sit there
 * empty with no visible way back. Accepting or dismissing the *last* reviewable row is exactly when
 * that happens, so the filter falls back to Uncategorized, which is the neighbouring question.
 */
internal fun filterAfterReviewDisappears(filter: String, reviewAvailable: Boolean): String =
    if (filter == "REVIEW" && !reviewAvailable) "NONE" else filter

/** Whether anything in scope actually has a Tier-2 proposal waiting — computed from the loaded list. */
internal fun hasReviewable(txns: List<Txn>): Boolean =
    txns.any { it.categoryId == null && it.suggestedCategoryId != null }

private val INPUT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Reads a typed amount as cents, tolerating what people actually type: a comma or a dot as the
 * decimal mark, thousands dots, a currency sign, spaces. Returns null for anything unparseable —
 * which the caller treats as "no bound", so typing never empties the list mid-keystroke.
 */
internal fun parseAmountInput(text: String): Long? {
    val cleaned = text.trim().replace("€", "").replace(" ", "").replace("\u00A0", "")
    if (cleaned.isEmpty()) return null
    // With a comma present it is the decimal mark and dots are thousands separators (German style);
    // otherwise a dot is the decimal mark.
    val normalized = if (cleaned.contains(',')) cleaned.replace(".", "").replace(',', '.') else cleaned
    val value = normalized.toBigDecimalOrNull() ?: return null
    return value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}

/**
 * Reads a typed date as `dd.MM.yyyy` (what every statement in this app shows) or ISO `yyyy-MM-dd`.
 * There is no date picker in this codebase — the dashboard's recurring dialog parses a plain text
 * field the same way — so this is the precedent being followed rather than a calendar being built.
 */
internal fun parseDateInput(text: String): LocalDate? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return runCatching { LocalDate.parse(t, INPUT_DATE) }.getOrNull()
        ?: runCatching { LocalDate.parse(t) }.getOrNull()
}

/**
 * The filtered, sorted list — pure, so it can be `remember`ed on its inputs instead of being
 * recomputed on every recomposition (a hover animation frame used to re-scan the whole account).
 *
 * The amount bounds compare the **magnitude**: a user scanning for "anything around 200 €" reads
 * the column without its sign, and a signed bound would silently exclude every expense from a
 * `200…500` range. Sorting by amount, in contrast, is signed — the natural "biggest credit first".
 */
internal fun filterTransactions(
    txns: List<Txn>,
    search: String,
    filter: String,
    period: YearMonth?,
    minCents: Long?,
    maxCents: Long?,
    from: LocalDate?,
    to: LocalDate?,
    sort: TxnSort,
): List<Txn> {
    val fromDay = from?.toEpochDay()
    val toDay = to?.toEpochDay()
    val filtered = txns.filter { t ->
        (period == null || YearMonth.from(LocalDate.ofEpochDay(t.bookingDate)) == period) &&
            (fromDay == null || t.bookingDate >= fromDay) &&
            (toDay == null || t.bookingDate <= toDay) &&
            (minCents == null || kotlin.math.abs(t.amountCents) >= minCents) &&
            (maxCents == null || kotlin.math.abs(t.amountCents) <= maxCents) &&
            (search.isBlank() || (t.counterparty ?: "").contains(search, true) || t.purpose.contains(search, true)) &&
            when (filter) {
                "ALL" -> true
                // Uncategorized and To-review are disjoint: a txn with a pending suggestion belongs
                // to "To review", not "Uncategorized".
                "NONE" -> t.categoryId == null && t.suggestedCategoryId == null
                "REVIEW" -> t.categoryId == null && t.suggestedCategoryId != null
                else -> t.categoryId == filter
            }
    }
    // `id` breaks ties so the order is stable across recompositions (and matches the query's own
    // ORDER BY bookingDate DESC, id).
    return when (sort) {
        TxnSort.DATE_DESC -> filtered.sortedWith(compareByDescending<Txn> { it.bookingDate }.thenBy { it.id })
        TxnSort.DATE_ASC -> filtered.sortedWith(compareBy<Txn> { it.bookingDate }.thenBy { it.id })
        TxnSort.AMOUNT_DESC -> filtered.sortedWith(compareByDescending<Txn> { it.amountCents }.thenBy { it.id })
        TxnSort.AMOUNT_ASC -> filtered.sortedWith(compareBy<Txn> { it.amountCents }.thenBy { it.id })
    }
}

/**
 * The ids a shift-click selects: everything between the [anchor] and the clicked [target] in the
 * list as currently shown, added to what is already selected. Without an anchor (nothing clicked
 * before, or the anchor scrolled out of the filter) it degrades to selecting the one row — a range
 * to nowhere is not worth guessing at.
 */
internal fun rangeSelection(visibleIds: List<String>, anchor: String?, target: String, current: Set<String>): Set<String> {
    val from = visibleIds.indexOf(anchor)
    val to = visibleIds.indexOf(target)
    if (from < 0 || to < 0) return current + target
    val range = if (from <= to) from..to else to..from
    return current + visibleIds.slice(range)
}

// ---------------------------------------------------------------- Row

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TxnRow(
    txn: Txn,
    category: Category?,
    suggested: Category?,
    selected: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onToggleCheck: (shiftPressed: Boolean) -> Unit,
    accountName: String? = null,
) {
    val strings = LocalStrings.current
    // The row a pointer is over lights up, so a long list stays easy to track across its full width.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            checked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "txn-row",
    )
    // Shift is read at click time (not during composition), so holding it never recomposes the list.
    val windowInfo = LocalWindowInfo.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = container,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TxnCheckbox(checked = checked, onToggle = { onToggleCheck(windowInfo.keyboardModifiers.isShiftPressed) })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(96.dp)) {
                Text(formatEpochDay(txn.bookingDate), style = MaterialTheme.typography.bodySmall)
                txn.valueDate?.let { Text("${strings.valueDateShort} ${formatEpochDay(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f)) {
                Text(txn.counterparty ?: txn.purpose, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // In the cross-account inbox a row is meaningless without its account, so the
                    // account is named there; the per-account list leaves it out (it is the header).
                    accountName?.let { Badge(it); Spacer(Modifier.width(8.dp)) }
                    txn.bookingType?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(8.dp)) }
                    when {
                        category != null -> CategoryChip(category)
                        suggested != null -> SuggestedChip(suggested)
                    }
                }
            }
            // A fixed, right-aligned column: the amounts line up down the list, and they keep their
            // place when the list itself is resized (or when a row gains its selection tint).
            Text(
                formatCents(txn.amountCents),
                Modifier.width(AmountColumn),
                color = if (txn.amountCents < 0) MoneyNegative else MoneyPositive,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/** A dashed-outline chip for a Tier-2 proposal: present, but visibly not a committed category. */
@Composable
internal fun SuggestedChip(category: Category) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, border = BorderStroke(1.dp, parseHexColor(category.color))) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(parseHexColor(category.color))
            Spacer(Modifier.width(4.dp))
            Text("${category.name} ?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The selection affordance: a small rounded box with a hand-drawn tick, in the app's glyph style. */
@Composable
private fun TxnCheckbox(checked: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(
        when {
            checked -> scheme.primary
            hovered -> scheme.outline
            else -> hairline()
        },
        label = "txn-check",
    )
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(5.dp),
        color = if (checked) scheme.primary else Color.Transparent,
        border = BorderStroke(1.5.dp, border),
        interactionSource = interaction,
        modifier = Modifier.size(17.dp),
    ) {
        if (checked) {
            Canvas(Modifier.size(17.dp)) {
                val sw = 1.8.dp.toPx()
                drawLine(scheme.onPrimary, Offset(size.width * 0.26f, size.height * 0.52f), Offset(size.width * 0.44f, size.height * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(scheme.onPrimary, Offset(size.width * 0.44f, size.height * 0.72f), Offset(size.width * 0.76f, size.height * 0.30f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * A transaction list with its detail inspector, laid out so that **selecting a row never moves the
 * list**. On a wide enough content area the inspector's gutter is always reserved (and shows a quiet
 * hint while nothing is selected); on a narrower one the panel is an overlay. What is never done is
 * the obvious thing — adding the panel to the row and letting the list shrink by 316dp in one frame.
 */
@Composable
internal fun ListWithInspector(
    inspectorVisible: Boolean,
    modifier: Modifier = Modifier,
    inspector: @Composable () -> Unit,
    list: @Composable ColumnScope.() -> Unit,
) {
    val strings = LocalStrings.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gutter = maxWidth >= InspectorGutterMinWidth
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight(), content = list)
            if (gutter) {
                Spacer(Modifier.width(InspectorGap))
                Box(Modifier.width(InspectorWidth).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    if (inspectorVisible) {
                        inspector()
                    } else {
                        // The reserved gutter says what it is for rather than sitting there blank.
                        Text(
                            strings.selectTransactionHint,
                            Modifier.padding(top = 24.dp, start = 12.dp, end = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        // Narrow window: the panel floats above the list's right edge. It covers part of the list,
        // but covering is reversible and cheap — resizing the list under the pointer is neither.
        if (!gutter && inspectorVisible) {
            Box(Modifier.align(Alignment.TopEnd).width(InspectorWidth).fillMaxHeight()) { inspector() }
        }
    }
}

/**
 * A row that wraps. The filter bar carries seven-ish controls and sits beside a 300dp inspector
 * gutter, so on a narrow window a plain `Row` compresses its last pill into an unreadable two-line
 * blob instead of moving it down. Hand-rolled rather than `FlowRow`, which is still experimental —
 * the same reason [FlowRowChips] exists.
 */
@Composable
private fun WrappingRow(
    horizontalGap: Dp,
    verticalGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val hGap = horizontalGap.roundToPx()
        val vGap = verticalGap.roundToPx()
        val max = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        // Greedy line breaking: a child that no longer fits starts the next line.
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        var x = 0
        var y = 0
        var lineHeight = 0
        var width = 0
        placeables.forEach { p ->
            if (x > 0 && x + p.width > max) {
                x = 0
                y += lineHeight + vGap
                lineHeight = 0
            }
            positions += x to y
            x += p.width + hGap
            width = maxOf(width, minOf(x - hGap, max))
            lineHeight = maxOf(lineHeight, p.height)
        }
        layout(if (constraints.hasBoundedWidth) max else width, y + lineHeight) {
            placeables.forEachIndexed { i, p -> p.place(positions[i].first, positions[i].second) }
        }
    }
}

// ---------------------------------------------------------------- Filter bar

/**
 * The list's controls: search, the three-way review filter, an optional category and month picker,
 * the sort order and — behind a "more filters" pill, so the common case stays uncluttered — the
 * amount and date-range bounds. [trailing] takes the per-screen actions (export, categorize…).
 */
@Composable
internal fun TxnFilterBar(
    filters: TxnListFilters,
    categories: List<Category>,
    categoryById: Map<String, Category>,
    months: List<YearMonth>,
    showReviewFilter: Boolean = true,
    showCategoryFilter: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val strings = LocalStrings.current
    var rangesOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(value = filters.search, onValueChange = { filters.search = it }, modifier = Modifier.weight(1f))
            trailing()
        }
        WrappingRow(horizontalGap = 8.dp, verticalGap = 8.dp) {
            FilterPill(strings.filterAll, selected = filters.filter == "ALL") { filters.filter = "ALL" }
            FilterPill(strings.filterUncategorized, selected = filters.filter == "NONE") { filters.filter = "NONE" }
            // Only offered when something is actually waiting to be reviewed.
            if (showReviewFilter) {
                FilterPill(strings.filterToReview, selected = filters.filter == "REVIEW") { filters.filter = "REVIEW" }
            }

            // A specific category filter lives in a dropdown chip that shows the active category.
            if (showCategoryFilter) {
                val activeCategory = categoryById[filters.filter]
                var menu by remember { mutableStateOf(false) }
                Box {
                    SelectPill(
                        label = activeCategory?.name ?: strings.filterCategory,
                        expanded = menu,
                        active = activeCategory != null,
                        leadingDot = activeCategory?.let { parseHexColor(it.color) },
                        onClick = { menu = true },
                    )
                    VaultMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        categories.forEach { c ->
                            VaultMenuItem(
                                label = c.name,
                                selected = c.id == filters.filter,
                                leadingDot = parseHexColor(c.color),
                                onClick = { filters.filter = c.id; menu = false },
                            )
                        }
                    }
                }
            }

            // A time filter (by month) — only meaningful once transactions span more than one month.
            if (months.size > 1) {
                var periodMenu by remember { mutableStateOf(false) }
                Box {
                    SelectPill(
                        label = filters.period?.let(::formatYearMonth) ?: strings.filterAnyTime,
                        expanded = periodMenu,
                        active = filters.period != null,
                        onClick = { periodMenu = true },
                    )
                    VaultMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                        VaultMenuItem(strings.filterAnyTime, selected = filters.period == null, onClick = { filters.period = null; periodMenu = false })
                        months.forEach { m ->
                            VaultMenuItem(formatYearMonth(m), selected = filters.period == m, onClick = { filters.period = m; periodMenu = false })
                        }
                    }
                }
            }

            var sortMenu by remember { mutableStateOf(false) }
            Box {
                SelectPill(
                    prefix = strings.sortPrefix,
                    label = strings.sortLabel(filters.sort),
                    expanded = sortMenu,
                    active = filters.sort != TxnSort.DATE_DESC,
                    onClick = { sortMenu = true },
                )
                VaultMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    TxnSort.entries.forEach { s ->
                        VaultMenuItem(strings.sortLabel(s), selected = filters.sort == s, onClick = { filters.sort = s; sortMenu = false })
                    }
                }
            }

            FilterPill(
                if (filters.hasRangeFilter) strings.moreFiltersActive else strings.moreFilters,
                selected = rangesOpen || filters.hasRangeFilter,
            ) { rangesOpen = !rangesOpen }
        }
        if (rangesOpen) {
            WrappingRow(horizontalGap = 8.dp, verticalGap = 8.dp) {
                Text(strings.amountRangeLabel, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MiniField(filters.minAmount, { filters.minAmount = it }, strings.minPlaceholder, 92.dp, invalid = filters.minAmount.isNotBlank() && parseAmountInput(filters.minAmount) == null)
                MiniField(filters.maxAmount, { filters.maxAmount = it }, strings.maxPlaceholder, 92.dp, invalid = filters.maxAmount.isNotBlank() && parseAmountInput(filters.maxAmount) == null)
                Text(strings.dateRangeLabel, Modifier.padding(top = 8.dp, start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MiniField(filters.fromDate, { filters.fromDate = it }, strings.datePlaceholder, 108.dp, invalid = filters.fromDate.isNotBlank() && parseDateInput(filters.fromDate) == null)
                MiniField(filters.toDate, { filters.toDate = it }, strings.datePlaceholder, 108.dp, invalid = filters.toDate.isNotBlank() && parseDateInput(filters.toDate) == null)
                if (filters.hasRangeFilter) {
                    TextButton(onClick = { filters.clearRanges() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(strings.reset, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** A compact text field for the range bounds — the [SearchField] surface without the magnifier. */
@Composable
private fun MiniField(value: String, onValueChange: (String) -> Unit, placeholder: String, width: androidx.compose.ui.unit.Dp, invalid: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (invalid) scheme.error else scheme.outlineVariant),
        modifier = Modifier.width(width),
    ) {
        Box(Modifier.padding(horizontal = 10.dp).height(34.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A selectable filter chip (optionally with a leading colour dot). */
@Composable
internal fun FilterPill(
    label: String,
    selected: Boolean,
    leadingDot: Color? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            selected -> scheme.primaryContainer
            hovered -> scheme.surfaceContainerHighest
            else -> scheme.surfaceContainerHigh
        },
        label = "filter-pill",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = container,
        border = BorderStroke(1.dp, if (selected) scheme.primary.copy(alpha = 0.55f) else hairline()),
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingDot != null) Dot(leadingDot)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
        }
    }
}

// ---------------------------------------------------------------- Bulk selection

/**
 * The action bar for a multi-row selection: how many are picked, the category to give them all, and
 * a way out. It appears only while something is selected, so the list is unchanged until the user
 * starts selecting.
 *
 * The picker offers the **intersection** of what every selected row's sign admits
 * ([admissibleCategories]); when that shortens the list because credits and debits are mixed, the
 * bar says so. Silently skipping the rows a category doesn't fit would be the worse behaviour: the
 * user would be told "n categorized" for a write that touched fewer.
 */
@Composable
internal fun BulkActionBar(
    count: Int,
    amounts: List<Long>,
    categories: List<Category>,
    onApply: (String) -> Unit,
    onClear: () -> Unit,
    onSelectAllMatching: (() -> Unit)?,
) {
    val strings = LocalStrings.current
    val allowed = remember(categories, amounts) { admissibleCategories(amounts, categories) }
    val mixed = remember(amounts) { isMixedSign(amounts) }
    var menu by remember { mutableStateOf(false) }

    VaultCard(modifier = Modifier.fillMaxWidth(), corner = 14.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.selectedCount(count), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Box {
                SelectPill(prefix = strings.categoryPrefixShort, label = strings.assignCategory, expanded = menu, active = true, onClick = { menu = true })
                VaultMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    // Reachable only if the transfer categories have all been disabled: better to say
                    // so than to open an empty menu the user can only close again.
                    if (allowed.isEmpty()) VaultMenuItem(strings.noCommonCategory, onClick = { menu = false })
                    allowed.forEach { c ->
                        VaultMenuItem(
                            label = c.name,
                            leadingDot = parseHexColor(c.color),
                            onClick = { menu = false; onApply(c.id) },
                        )
                    }
                }
            }
            if (mixed) {
                Text(
                    strings.mixedSignNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (onSelectAllMatching != null) {
                TextButton(onClick = onSelectAllMatching, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(strings.selectAllMatching, style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text(strings.clearSelection, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * What just happened to a bulk selection, with the way back. A bulk write with no undo is the thing
 * that makes a user stop trusting bulk tools, so the offer sits in the status line until the next
 * action rather than vanishing with a toast.
 */
@Composable
internal fun BulkUndoLine(message: String, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(strings.undo, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(strings.dismiss, style = MaterialTheme.typography.labelSmall)
        }
    }
}
