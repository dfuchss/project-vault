package org.fuchss.projectvault.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
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
import org.fuchss.projectvault.model.categoryAllowedForAmount

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
    bulk: BulkAssign,
    onImport: () -> Unit,
    onSetCategory: (Txn, String) -> Unit,
    onAcceptSuggestion: (Txn, String) -> Unit,
    onDismissSuggestion: (Txn) -> Unit,
    onDeleteBatch: (ImportBatch) -> Unit,
    onRefreshQuotes: () -> Unit,
    onEnableQuotes: () -> Unit,
    onDeleteAccount: () -> Unit,
    onEditOwners: () -> Unit,
    onManageCategories: () -> Unit,
    onClassify: () -> Unit,
    onChanged: () -> Unit,
) {
    val strings = LocalStrings.current
    val batches = remember(account.id, refreshKey) { repo.batches(account.id) }
    val txns = remember(account.id, refreshKey) {
        if (account.type != AccountType.DEPOT) repo.transactions(account.id) else emptyList()
    }
    var selectedTxnId by remember(account.id) { mutableStateOf<String?>(null) }
    val selectedTxn = txns.firstOrNull { it.id == selectedTxnId }
    // Memoized on the batch id: read straight from the inspector's argument list, this was a SQLite
    // query per recomposition — i.e. one per hover animation frame anywhere on the screen.
    val selectedBatch = remember(selectedTxn?.importBatchId, refreshKey) { repo.batch(selectedTxn?.importBatchId) }
    val filters = remember(account.id) { TxnListFilters() }
    val txnMonths = remember(txns) {
        txns.map { YearMonth.from(LocalDate.ofEpochDay(it.bookingDate)) }.distinct().sortedDescending()
    }
    // Multi-select for bulk categorization: ids (so it survives a re-read of the list), plus the
    // anchor a shift-click extends from and one step of undo for the last bulk write.
    var checked by remember(account.id) { mutableStateOf(emptySet<String>()) }
    var anchorId by remember(account.id) { mutableStateOf<String?>(null) }
    var lastBulk by remember(account.id) { mutableStateOf<BulkResult?>(null) }
    var bulkMessage by remember(account.id) { mutableStateOf<String?>(null) }
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

        if (account.type == AccountType.DEPOT) {
            // A Depot has no per-row inspector, so it simply fills the pane.
            Column(Modifier.weight(1f).fillMaxWidth()) {
                DepotPane(account, repo, refreshKey, onRefreshQuotes, onEnableQuotes)
            }
        } else {
            ListWithInspector(
                // The panel's presence never changes the list's width — see ListWithInspector.
                inspectorVisible = selectedTxn != null,
                modifier = Modifier.weight(1f),
                inspector = {
                    selectedTxn?.let { txn ->
                        TxnInspector(
                            onClose = { selectedTxnId = null },
                            txn = txn,
                            batch = selectedBatch,
                            categories = categories,
                            current = txn.categoryId?.let { categoryById[it] },
                            suggested = txn.suggestedCategoryId?.let { categoryById[it] },
                            onSetCategory = { onSetCategory(txn, it) },
                            onAcceptSuggestion = { onAcceptSuggestion(txn, it) },
                            onDismissSuggestion = { onDismissSuggestion(txn) },
                            onManageCategories = onManageCategories,
                        )
                    }
                },
            ) {
                // Filtering and sorting the whole list is `remember`ed on its inputs. Inline, it
                // re-ran on **every** recomposition — every keystroke in the search field, and
                // every frame of a row's hover animation.
                val filtered = remember(
                    txns, filters.search, filters.filter, filters.period, filters.sort,
                    filters.minAmount, filters.maxAmount, filters.fromDate, filters.toDate,
                ) {
                    filterTransactions(
                        txns = txns,
                        search = filters.search,
                        filter = filters.filter,
                        period = filters.period,
                        minCents = parseAmountInput(filters.minAmount),
                        maxCents = parseAmountInput(filters.maxAmount),
                        from = parseDateInput(filters.fromDate),
                        to = parseDateInput(filters.toDate),
                        sort = filters.sort,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val countLabel = if (filtered.size == txns.size) "${txns.size}" else strings.countOf(filtered.size, txns.size)
                    Text(strings.transactionsHeader(countLabel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val uncategorized = remember(txns) { txns.count { it.categoryId == null } }
                    if (uncategorized > 0) OutlinedButton(onClick = onClassify) { Text(strings.categorizeN(uncategorized)) }
                }
                Spacer(Modifier.height(8.dp))
                // "To review" is shown only while something is waiting for review; if the last
                // such row is accepted or dismissed the filter falls back, so the view can't be
                // left staring at an empty list under a pill that is no longer there.
                val reviewAvailable = remember(txns) { hasReviewable(txns) }
                LaunchedEffect(reviewAvailable) { filters.coerceFilter(reviewAvailable) }
                TxnFilterBar(
                    filters = filters,
                    categories = categories,
                    categoryById = categoryById,
                    months = txnMonths,
                    showReviewFilter = reviewAvailable,
                    trailing = {
                        if (filtered.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    // A cancelled save dialog leaves the status line alone.
                                    exportTxns(filtered, categoryById, { account.name }, strings)
                                        ?.let { message -> bulkMessage = message; lastBulk = null }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text(strings.exportCsvButton, style = MaterialTheme.typography.labelMedium) }
                        }
                    },
                )
                // Resolved against the rows that still exist: a selection can outlive its
                // transactions (undoing the import they came from), and a bar that says "0 selected"
                // while offering every category is worse than no bar at all.
                val selectedTxns = remember(checked, txns) { txns.filter { it.id in checked } }
                if (selectedTxns.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    BulkActionBar(
                        count = selectedTxns.size,
                        amounts = selectedTxns.map { it.amountCents },
                        categories = categories,
                        onApply = { categoryId ->
                            lastBulk = bulk.apply(selectedTxns, categoryId)
                            bulkMessage = strings.bulkAssigned(selectedTxns.size, categoryById[categoryId]?.name ?: "")
                            checked = emptySet(); anchorId = null
                            onChanged()
                        },
                        onClear = { checked = emptySet(); anchorId = null },
                        onSelectAllMatching = { checked = checked + filtered.map { it.id } },
                    )
                }
                val undoable = lastBulk
                val message = bulkMessage
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    if (undoable != null) {
                        BulkUndoLine(
                            message = message,
                            onUndo = {
                                bulk.undo(undoable)
                                bulkMessage = strings.bulkUndone(undoable.changes.size)
                                lastBulk = null
                                onChanged()
                            },
                            onDismiss = { bulkMessage = null; lastBulk = null },
                        )
                    } else {
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (txns.isEmpty()) EmptyHint(strings.noTransactionsImport)
                else if (filtered.isEmpty()) EmptyHint(strings.noTransactionsMatchFilter)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered, key = { it.id }) { txn ->
                        TxnRow(
                            txn = txn,
                            category = txn.categoryId?.let { categoryById[it] },
                            suggested = txn.suggestedCategoryId?.let { categoryById[it] },
                            selected = txn.id == selectedTxnId,
                            checked = txn.id in checked,
                            onClick = { selectedTxnId = txn.id },
                            onToggleCheck = { shift ->
                                checked = toggleSelection(filtered.map { it.id }, checked, anchorId, txn.id, shift)
                                anchorId = txn.id
                            },
                        )
                    }
                }
            }
        }
    }

    if (showImportHistory) {
        ImportHistoryDialog(batches = batches, onDeleteBatch = onDeleteBatch, onDismiss = { showImportHistory = false })
    }
}

/**
 * Exports the rows currently on screen (filtered **and** sorted as shown) to a file the user picks,
 * returning the status line to display — or null when the save dialog was cancelled.
 */
internal fun exportTxns(
    txns: List<Txn>,
    categoryById: Map<String, Category>,
    accountNameOf: (Txn) -> String,
    strings: Strings,
): String? {
    val target = saveFileDialog(strings.exportCsvDialogTitle, "transactions.csv") ?: return null
    return runCatching {
        writeCsv(target, exportCsv(exportRowsOf(txns, categoryById, accountNameOf), strings.exportColumns))
        strings.exportedRows(txns.size, target.name)
    }.getOrElse { strings.exportFailed(it.message) }
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

/** A compact, pill-shaped search field with a drawn magnifier and an inline clear button. */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = LocalStrings.current.searchPlaceholder,
) {
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
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = muted)
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

@Composable
internal fun TxnInspector(
    txn: Txn,
    batch: ImportBatch?,
    categories: List<Category>,
    current: Category?,
    suggested: Category?,
    accountName: String? = null,
    onClose: () -> Unit,
    onSetCategory: (String) -> Unit,
    onAcceptSuggestion: (String) -> Unit,
    onDismissSuggestion: () -> Unit,
    onManageCategories: () -> Unit,
) {
    val strings = LocalStrings.current
    VaultCard(modifier = Modifier.fillMaxWidth(), corner = 16.dp, padding = PaddingValues(16.dp)) {
        Column {
            // The close button is not decoration: below 720dp this card is an overlay pinned over the
            // right of the list, clicking the row again is idempotent, and nothing else deselects — so
            // without it the panel covers the list until you change account or leave the screen.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.transaction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(strings.close, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            // In the cross-account inbox the row's account is part of the identity of what you are
            // looking at; in the account view it is the page header, so it isn't repeated.
            accountName?.let { InfoRow(strings.accountLabel, it) }
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

            // A proposal whose kind the amount's sign forbids is never offered for Accept — the
            // classifier no longer produces one, but vaults categorized before it consulted the sign
            // still hold them, and one click would commit an expense category onto incoming money.
            if (current == null && suggested != null && categoryAllowedForAmount(txn.amountCents, suggested.kind)) {
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
            HorizontalDivider(color = hairline())
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

// Column widths shared by the holdings header and every row, so quantity, price and value line up as
// a real grid instead of drifting with each row's content. The end padding matches the rows' so the
// pane total sits exactly above the value column.
private val QuantityColumn = 96.dp
private val PriceColumn = 116.dp
private val ValueColumn = 132.dp
private val GridPadding = 10.dp

@Composable
private fun DepotPane(
    account: Account,
    repo: VaultRepository,
    refreshKey: Int,
    onRefreshQuotes: () -> Unit,
    onEnableQuotes: () -> Unit,
) {
    val dates = remember(account.id, refreshKey) { repo.valuationDates(account.id) }
    var selectedDay by remember(account.id, refreshKey) { mutableStateOf(dates.firstOrNull()) }
    val liveDays = remember(account.id, refreshKey) { repo.liveValuationDates(account.id) }
    val holdings = remember(account.id, selectedDay, refreshKey) {
        selectedDay?.let { repo.holdingsForValuationDate(account.id, it) } ?: emptyList()
    }
    val total = holdings.sumOf { it.marketValueCents }
    val strings = LocalStrings.current

    fun snapshotLabel(day: Long) =
        if (day in liveDays) strings.liveSnapshotLabel(formatEpochDay(day)) else formatEpochDay(day)

    Row(Modifier.fillMaxWidth().padding(end = GridPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.holdings, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        if (dates.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                SelectPill(
                    prefix = strings.snapshotPrefix,
                    label = selectedDay?.let(::snapshotLabel) ?: "—",
                    expanded = expanded,
                    onClick = { expanded = true },
                )
                VaultMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    dates.forEach { day ->
                        VaultMenuItem(snapshotLabel(day), selected = day == selectedDay, onClick = { selectedDay = day; expanded = false })
                    }
                }
            }
        }
        // Live prices are strictly manual: this button is the only thing that ever fetches.
        if (dates.isNotEmpty()) {
            val enabled = account.liveQuotes == 1L
            IconAction(onClick = if (enabled) onRefreshQuotes else onEnableQuotes) { color ->
                RefreshGlyph(if (enabled) MaterialTheme.colorScheme.primary else color)
            }
        }
        Spacer(Modifier.weight(1f))
        if (holdings.isNotEmpty()) Text(formatCents(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    if (holdings.isEmpty()) {
        EmptyHint(strings.noHoldingsImport)
    } else {
        HoldingsHeader()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(holdings) { HoldingRow(it) }
        }
    }
}

/** Column labels for the holdings grid, on the same widths as [HoldingRow]. */
@Composable
private fun HoldingsHeader() {
    val strings = LocalStrings.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GridPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(strings.securityColumn, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
        Text(strings.quantityColumn, Modifier.width(QuantityColumn), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End)
        Text(strings.priceColumn, Modifier.width(PriceColumn), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End)
        Text(strings.valueColumn, Modifier.width(ValueColumn), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End)
    }
    HorizontalDivider(color = hairline())
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun HoldingRow(holding: Holding) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GridPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(holding.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(holding.isin, holding.wkn).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Numbers are right-aligned so decimal places line up down the column.
        Text(
            holding.quantity,
            Modifier.width(QuantityColumn),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Column(Modifier.width(PriceColumn), horizontalAlignment = Alignment.End) {
            Text(
                holding.priceText ?: "—",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            // Only rows that actually got a live price carry a quote time; statement rows stay plain.
            holding.quoteAt?.let {
                Text(
                    formatQuoteTime(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
        Text(
            formatCents(holding.marketValueCents),
            Modifier.width(ValueColumn),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}
