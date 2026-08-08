package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Holding
import org.fuchss.projectvault.data.db.ImportBatch
import org.fuchss.projectvault.data.db.Profile
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType

// ---------------------------------------------------------------- Account detail

@Composable
internal fun AccountDetail(
    account: Account,
    repo: VaultRepository,
    owners: List<Profile>,
    balance: Long?,
    refreshKey: Int,
    status: String?,
    categories: List<Category>,
    categoryById: Map<String, Category>,
    onImport: () -> Unit,
    onSetCategory: (Txn, String) -> Unit,
    onAcceptSuggestion: (Txn, String) -> Unit,
    onDismissSuggestion: (Txn) -> Unit,
    onDeleteBatch: (ImportBatch) -> Unit,
    onDeleteAccount: () -> Unit,
    onEditOwners: () -> Unit,
    onManageCategories: () -> Unit,
    onClassify: () -> Unit,
) {
    val strings = LocalStrings.current
    val batches = remember(account.id, refreshKey) { repo.batches(account.id) }
    val txns = remember(account.id, refreshKey) {
        if (account.type != AccountType.DEPOT) repo.transactions(account.id) else emptyList()
    }
    var selectedTxnId by remember(account.id) { mutableStateOf<String?>(null) }
    val selectedTxn = txns.firstOrNull { it.id == selectedTxnId }
    var search by remember(account.id) { mutableStateOf("") }
    var filter by remember(account.id) { mutableStateOf("ALL") } // ALL | NONE | REVIEW | <categoryId>
    var period by remember(account.id) { mutableStateOf<YearMonth?>(null) } // null = all time
    val txnMonths = remember(txns) {
        txns.map { YearMonth.from(LocalDate.ofEpochDay(it.bookingDate)) }.distinct().sortedDescending()
    }
    var showImportHistory by remember(account.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // header bar
        VaultCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    // Identity row: account type + inline owner editing. The IBAN lives on its own line
                    // below so a long IBAN can never squeeze the owner chips into unreadable wrapping.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(accountTypeLabel(account.type))
                        // Owners are editable inline: click to assign this account to profiles (joint = several).
                        Surface(onClick = onEditOwners, shape = RoundedCornerShape(50), color = Color.Transparent) {
                            Row(
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (owners.isEmpty()) {
                                    Text(strings.assignOwner, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                } else {
                                    owners.forEach {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Dot(parseHexColor(it.color)); Spacer(Modifier.width(4.dp)); Text(it.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Text(strings.edit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                }
                            }
                        }
                    }
                    account.iban?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (account.type == AccountType.DEPOT) strings.portfolioValueLabel else strings.balanceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(balance?.let(::formatCents) ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (ImportSupport.isSupported(account)) {
                    PrimaryButton(strings.importStatementButton, onClick = onImport)
                }
                if (batches.isNotEmpty()) {
                    TextButton(onClick = { showImportHistory = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(strings.historyButton(batches.size), style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(onClick = onDeleteAccount, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(strings.delete, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (account.type == AccountType.DEPOT) {
                    DepotPane(account, repo, refreshKey)
                } else {
                    val filtered = txns.filter { t ->
                        (period == null || YearMonth.from(LocalDate.ofEpochDay(t.bookingDate)) == period) &&
                            (search.isBlank() || (t.counterparty ?: "").contains(search, true) || t.purpose.contains(search, true)) &&
                            when (filter) {
                                "ALL" -> true
                                // Uncategorized and To-review are disjoint: a txn with a pending
                                // suggestion belongs to "To review", not "Uncategorized".
                                "NONE" -> t.categoryId == null && t.suggestedCategoryId == null
                                "REVIEW" -> t.categoryId == null && t.suggestedCategoryId != null
                                else -> t.categoryId == filter
                            }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val countLabel = if (filtered.size == txns.size) "${txns.size}" else strings.countOf(filtered.size, txns.size)
                        Text(strings.transactionsHeader(countLabel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        val uncategorized = txns.count { it.categoryId == null }
                        if (uncategorized > 0) OutlinedButton(onClick = onClassify) { Text(strings.categorizeN(uncategorized)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    TransactionFilters(
                        search = search,
                        onSearch = { search = it },
                        filter = filter,
                        onFilter = { filter = it },
                        categories = categories,
                        categoryById = categoryById,
                        months = txnMonths,
                        period = period,
                        onPeriod = { period = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (txns.isEmpty()) EmptyHint(strings.noTransactionsImport)
                    else if (filtered.isEmpty()) EmptyHint(strings.noTransactionsMatchFilter)
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered) { txn ->
                            TxnRow(
                                txn = txn,
                                category = txn.categoryId?.let { categoryById[it] },
                                suggested = txn.suggestedCategoryId?.let { categoryById[it] },
                                selected = txn.id == selectedTxnId,
                                onClick = { selectedTxnId = txn.id },
                            )
                        }
                    }
                }
            }
            // Inspector (transaction detail + origin + category picker) — only when a row is selected,
            // so the list uses full width the rest of the time. Import history lives behind "History".
            if (selectedTxn != null && account.type != AccountType.DEPOT) {
                Spacer(Modifier.width(16.dp))
                Column(Modifier.width(300.dp).fillMaxHeight()) {
                    TxnInspector(
                        txn = selectedTxn,
                        batch = repo.batch(selectedTxn.importBatchId),
                        categories = categories,
                        current = selectedTxn.categoryId?.let { categoryById[it] },
                        suggested = selectedTxn.suggestedCategoryId?.let { categoryById[it] },
                        onSetCategory = { onSetCategory(selectedTxn, it) },
                        onAcceptSuggestion = { onAcceptSuggestion(selectedTxn, it) },
                        onDismissSuggestion = { onDismissSuggestion(selectedTxn) },
                        onManageCategories = onManageCategories,
                    )
                }
            }
        }
    }

    if (showImportHistory) {
        ImportHistoryDialog(batches = batches, onDeleteBatch = onDeleteBatch, onDismiss = { showImportHistory = false })
    }
}

@Composable
private fun TxnRow(txn: Txn, category: Category?, suggested: Category?, selected: Boolean, onClick: () -> Unit) {
    val strings = LocalStrings.current
    // The row a pointer is over lights up, so a long list stays easy to track across its full width.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "txn-row",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = container,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(96.dp)) {
                Text(formatEpochDay(txn.bookingDate), style = MaterialTheme.typography.bodySmall)
                txn.valueDate?.let { Text("${strings.valueDateShort} ${formatEpochDay(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f)) {
                Text(txn.counterparty ?: txn.purpose, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    txn.bookingType?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(8.dp)) }
                    when {
                        category != null -> CategoryChip(category)
                        suggested != null -> SuggestedChip(suggested)
                    }
                }
            }
            Text(formatCents(txn.amountCents), color = if (txn.amountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun CategoryChip(category: Category) {
    val color = parseHexColor(category.color)
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(4.dp))
            Text(category.name, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SuggestedChip(category: Category) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, border = BorderStroke(1.dp, parseHexColor(category.color))) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(parseHexColor(category.color))
            Spacer(Modifier.width(4.dp))
            Text("${category.name} ?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransactionFilters(
    search: String,
    onSearch: (String) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    categories: List<Category>,
    categoryById: Map<String, Category>,
    months: List<YearMonth>,
    period: YearMonth?,
    onPeriod: (YearMonth?) -> Unit,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill(strings.filterAll, selected = filter == "ALL") { onFilter("ALL") }
            FilterPill(strings.filterUncategorized, selected = filter == "NONE") { onFilter("NONE") }
            FilterPill(strings.filterToReview, selected = filter == "REVIEW") { onFilter("REVIEW") }

            // A specific category filter lives in a dropdown chip that shows the active category.
            val activeCategory = categoryById[filter]
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
                            selected = c.id == filter,
                            leadingDot = parseHexColor(c.color),
                            onClick = { onFilter(c.id); menu = false },
                        )
                    }
                }
            }

            // A time filter (by month) — only meaningful once transactions span more than one month.
            if (months.size > 1) {
                var periodMenu by remember { mutableStateOf(false) }
                Box {
                    SelectPill(
                        label = period?.let(::formatYearMonth) ?: strings.filterAnyTime,
                        expanded = periodMenu,
                        active = period != null,
                        onClick = { periodMenu = true },
                    )
                    VaultMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                        VaultMenuItem(strings.filterAnyTime, selected = period == null, onClick = { onPeriod(null); periodMenu = false })
                        months.forEach { m ->
                            VaultMenuItem(formatYearMonth(m), selected = period == m, onClick = { onPeriod(m); periodMenu = false })
                        }
                    }
                }
            }
        }
    }
}

/** A compact, pill-shaped search field with a drawn magnifier and an inline clear button. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            MagnifierIcon(muted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(strings.searchPlaceholder, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(22.dp).clip(CircleShape).clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", style = MaterialTheme.typography.labelMedium, color = muted) }
            }
        }
    }
}

/** A magnifier glass drawn with primitives (no icon dependency). */
@Composable
private fun MagnifierIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension * 0.30f
        val c = Offset(size.width * 0.40f, size.height * 0.40f)
        drawCircle(color = color, radius = r, center = c, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            end = Offset(size.width * 0.92f, size.height * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** A selectable filter chip (optionally with a leading colour dot and a dropdown arrow). */
@Composable
private fun FilterPill(
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

@Composable
private fun TxnInspector(
    txn: Txn,
    batch: ImportBatch?,
    categories: List<Category>,
    current: Category?,
    suggested: Category?,
    onSetCategory: (String) -> Unit,
    onAcceptSuggestion: (String) -> Unit,
    onDismissSuggestion: () -> Unit,
    onManageCategories: () -> Unit,
) {
    val strings = LocalStrings.current
    VaultCard(modifier = Modifier.fillMaxWidth(), corner = 16.dp, padding = PaddingValues(16.dp)) {
        Column {
            Text(strings.transaction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            InfoRow(strings.amount, formatCents(txn.amountCents))
            InfoRow(strings.bookingDate, formatEpochDay(txn.bookingDate))
            InfoRow(strings.valueDate, formatEpochDayOrDash(txn.valueDate))
            txn.bookingType?.let { InfoRow(strings.type, it) }

            Spacer(Modifier.height(12.dp))
            Text(strings.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                SelectPill(
                    label = current?.name ?: strings.uncategorized,
                    expanded = expanded,
                    active = current != null,
                    leadingDot = current?.let { parseHexColor(it.color) },
                    onClick = { expanded = true },
                )
                VaultMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // Only offer categories whose kind fits the amount's sign (income/transfer for a
                    // credit, expense/transfer for a debit) — so a debit can't be marked as salary, etc.
                    categories.filter { categoryAllowedForAmount(txn.amountCents, it.kind) }.forEach { c ->
                        VaultMenuItem(
                            label = c.name,
                            selected = c.id == current?.id,
                            leadingDot = parseHexColor(c.color),
                            onClick = { expanded = false; onSetCategory(c.id) },
                        )
                    }
                    VaultMenuDivider()
                    VaultMenuItem(strings.manageCategoriesMenu, emphasis = true, onClick = { expanded = false; onManageCategories() })
                }
            }

            if (current == null && suggested != null) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.suggested, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SuggestedChip(suggested)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row {
                            TextButton(onClick = { onAcceptSuggestion(suggested.id) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(strings.accept) }
                            TextButton(onClick = onDismissSuggestion, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(strings.dismiss) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(strings.purpose, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(txn.purpose, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text(strings.origin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (batch != null) {
                InfoRow(strings.source, batch.sourceName)
                batch.statementNumber?.let { InfoRow(strings.statement, it) }
                val periodStart = batch.periodStart
                val periodEnd = batch.periodEnd
                if (periodStart != null && periodEnd != null) {
                    InfoRow(strings.period, "${formatEpochDay(periodStart)} – ${formatEpochDay(periodEnd)}")
                }
                InfoRow(strings.imported, formatEpochMillis(batch.importedAt))
                InfoRow(strings.reconciled, if (batch.reconciled == 1L) strings.yes else strings.no)
            } else {
                Text("—", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Import history, on demand: a dialog listing each import batch with an "Undo" action. */
@Composable
private fun ImportHistoryDialog(batches: List<ImportBatch>, onDeleteBatch: (ImportBatch) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.importHistoryTitle) },
        text = {
            if (batches.isEmpty()) {
                Text(strings.nothingImportedYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(batches) { b ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(b.sourceName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDeleteBatch(b) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text(strings.undo, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(
                                strings.importHistorySubtitle(b.itemCount.toInt(), formatEpochMillis(b.importedAt), b.reconciled == 1L),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.done) } },
    )
}

// ---------------------------------------------------------------- Depot pane

@Composable
private fun DepotPane(account: Account, repo: VaultRepository, refreshKey: Int) {
    val dates = remember(account.id, refreshKey) { repo.valuationDates(account.id) }
    var selectedDay by remember(account.id, refreshKey) { mutableStateOf(dates.firstOrNull()) }
    val holdings = remember(account.id, selectedDay, refreshKey) {
        selectedDay?.let { repo.holdingsForValuationDate(account.id, it) } ?: emptyList()
    }
    val total = holdings.sumOf { it.marketValueCents }
    val strings = LocalStrings.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(strings.holdings, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        if (dates.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                SelectPill(
                    prefix = strings.snapshotPrefix,
                    label = selectedDay?.let(::formatEpochDay) ?: "—",
                    expanded = expanded,
                    onClick = { expanded = true },
                )
                VaultMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    dates.forEach { day ->
                        VaultMenuItem(formatEpochDay(day), selected = day == selectedDay, onClick = { selectedDay = day; expanded = false })
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (holdings.isNotEmpty()) Text(formatCents(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    if (holdings.isEmpty()) EmptyHint(strings.noHoldingsImport)
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(holdings) { HoldingRow(it) }
    }
}

@Composable
private fun HoldingRow(holding: Holding) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(holding.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(holding.isin, holding.wkn).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(holding.quantity, Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall)
        Text(formatCents(holding.marketValueCents), fontWeight = FontWeight.Medium)
    }
}
