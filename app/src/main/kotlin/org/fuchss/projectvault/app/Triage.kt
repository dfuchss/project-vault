package org.fuchss.projectvault.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType

/**
 * The review inbox: every transaction that still needs a decision, **across all visible accounts**.
 *
 * The per-account list has had the same Uncategorized / To-review filters for a while, but they are
 * per account: with a giro, a card and a savings account, "what still needs filing?" meant visiting
 * three screens in turn and remembering where you stopped. This is that question asked once. It
 * respects the sidebar's profile filter — it shows exactly the accounts the sidebar shows.
 *
 * Why it is worth a top-level place: an uncategorized transaction is not merely untidy. The
 * dashboard decides income vs. expense by amount sign for rows that carry no category (see
 * `Analytics.incomeExpense`), so an unfiled internal transfer is counted as income on the receiving
 * account *and* as expense on the sending one — inflating both halves of the overview until it is
 * categorized. Clearing this list is what makes those numbers true.
 */
@Composable
internal fun TriageScreen(
    repo: VaultRepository,
    accounts: List<Account>,
    categories: List<Category>,
    categoryById: Map<String, Category>,
    bulk: BulkAssign,
    refreshKey: Int,
    status: String?,
    onSetCategory: (String, Txn, String) -> Unit,
    onAcceptSuggestion: (String, Txn, String) -> Unit,
    onDismissSuggestion: (Txn) -> Unit,
    onManageCategories: () -> Unit,
    onChanged: () -> Unit,
) {
    val strings = LocalStrings.current
    // Depot accounts hold snapshots, not transactions, so they contribute nothing to triage.
    val sourceAccounts = remember(accounts) { accounts.filter { it.type != AccountType.DEPOT } }
    val accountById = remember(sourceAccounts) { sourceAccounts.associateBy { it.id } }
    // One read per account per refresh — never per recomposition.
    val txns = remember(sourceAccounts, refreshKey) { sourceAccounts.flatMap { repo.transactions(it.id) } }
    val txnById = remember(txns) { txns.associateBy { it.id } }
    val months = remember(txns) {
        txns.map { YearMonth.from(LocalDate.ofEpochDay(it.bookingDate)) }.distinct().sortedDescending()
    }

    // Opens on whatever the sidebar badge was counting. The badge counts every row without a
    // category, but "Uncategorized" excludes the ones carrying a Tier-2 proposal (those are "To
    // review"), so defaulting blindly to it would show an empty inbox with an "all clear" hint
    // directly under a header saying N pending. When everything waiting is a proposal, open there.
    val filters = remember(txns) {
        TxnListFilters().apply {
            filter = when {
                txns.any { it.categoryId == null && it.suggestedCategoryId == null } -> "NONE"
                hasReviewable(txns) -> "REVIEW"
                else -> "ALL"
            }
        }
    }
    val visible = remember(
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

    var selectedTxnId by remember { mutableStateOf<String?>(null) }
    val selectedTxn = selectedTxnId?.let { txnById[it] }
    val selectedBatch = remember(selectedTxn?.importBatchId, refreshKey) { repo.batch(selectedTxn?.importBatchId) }
    var checked by remember { mutableStateOf(emptySet<String>()) }
    var anchorId by remember { mutableStateOf<String?>(null) }
    var lastBulk by remember { mutableStateOf<BulkResult?>(null) }
    var bulkMessage by remember { mutableStateOf<String?>(null) }

    val pending = remember(txns) { txns.count { it.categoryId == null } }
    // Same rule as the account list: the review filter only exists while there is something to
    // review, and a view sitting on it falls back when the last proposal is dealt with.
    val reviewAvailable = remember(txns) { hasReviewable(txns) }
    LaunchedEffect(reviewAvailable) { filters.coerceFilter(reviewAvailable) }

    Column(Modifier.fillMaxSize()) {
        VaultCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.review, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(strings.reviewPending(pending), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(strings.reviewSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))

        ListWithInspector(
            // Selecting a row must not resize the list — same layout as the account view.
            inspectorVisible = selectedTxn != null,
            modifier = Modifier.weight(1f),
            inspector = {
                // The same inspector the account view uses — triage is mostly "look at one row,
                // decide", and the provenance (which statement it came from) often settles it.
                selectedTxn?.let { txn ->
                    val account = accountById[txn.accountId]
                    TxnInspector(
                        onClose = { selectedTxnId = null },
                        txn = txn,
                        batch = selectedBatch,
                        categories = categories,
                        current = txn.categoryId?.let { categoryById[it] },
                        suggested = txn.suggestedCategoryId?.let { categoryById[it] },
                        accountName = account?.name,
                        onSetCategory = { categoryId -> account?.let { a -> onSetCategory(a.id, txn, categoryId) } },
                        onAcceptSuggestion = { categoryId -> account?.let { a -> onAcceptSuggestion(a.id, txn, categoryId) } },
                        onDismissSuggestion = { onDismissSuggestion(txn) },
                        onManageCategories = onManageCategories,
                    )
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val countLabel = if (visible.size == txns.size) "${txns.size}" else strings.countOf(visible.size, txns.size)
                Text(strings.transactionsHeader(countLabel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            TxnFilterBar(
                filters = filters,
                categories = categories,
                categoryById = categoryById,
                months = months,
                showReviewFilter = reviewAvailable,
                trailing = {
                    if (visible.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                exportTxns(visible, categoryById, { accountById[it.accountId]?.name ?: "" }, strings)
                                    ?.let { message -> bulkMessage = message; lastBulk = null }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(strings.exportCsvButton, style = MaterialTheme.typography.labelMedium) }
                    }
                },
            )
            // Resolved against the rows that still exist: a selection can outlive its transactions
            // (the sidebar's profile filter hides an account, an import is undone), and a bar that
            // says "0 selected" while offering every category is worse than no bar at all.
            val selectedTxns = remember(checked, txnById) { checked.mapNotNull { txnById[it] } }
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
                    onSelectAllMatching = { checked = checked + visible.map { it.id } },
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
            when {
                sourceAccounts.isEmpty() -> EmptyHint(strings.reviewNoAccounts)
                // "Nothing left to file" may only be claimed when nothing is filtered away — a month
                // with no uncategorized rows in it is not an empty inbox.
                visible.isEmpty() && filters.filter == "NONE" && filters.search.isBlank() &&
                    !filters.hasRangeFilter && filters.period == null ->
                    EmptyHint(strings.reviewAllClear)
                visible.isEmpty() -> EmptyHint(strings.noTransactionsMatchFilter)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(visible, key = { it.id }) { txn ->
                        TxnRow(
                            txn = txn,
                            category = txn.categoryId?.let { categoryById[it] },
                            suggested = txn.suggestedCategoryId?.let { categoryById[it] },
                            selected = txn.id == selectedTxnId,
                            checked = txn.id in checked,
                            accountName = accountById[txn.accountId]?.name,
                            onClick = { selectedTxnId = txn.id },
                            onToggleCheck = { shift ->
                                checked = toggleSelection(visible.map { it.id }, checked, anchorId, txn.id, shift)
                                anchorId = txn.id
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Selection after a click on a row's checkbox: plain toggle, or — with shift held — the whole range
 * from the last clicked row to this one, which is how every list the user already knows behaves.
 */
internal fun toggleSelection(
    visibleIds: List<String>,
    current: Set<String>,
    anchor: String?,
    target: String,
    shift: Boolean,
): Set<String> = when {
    shift -> rangeSelection(visibleIds, anchor, target, current)
    target in current -> current - target
    else -> current + target
}

/** An inbox tray glyph (a tray with an arrow dropping into it) — the review view's navigation icon. */
@Composable
internal fun ReviewGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.7.dp.toPx()
        // Tray: two sides dropping to a floor, with the "slot" left open at the top.
        drawPath(
            Path().apply {
                moveTo(w * 0.08f, h * 0.52f)
                lineTo(w * 0.08f, h * 0.9f)
                lineTo(w * 0.92f, h * 0.9f)
                lineTo(w * 0.92f, h * 0.52f)
            },
            color, style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Arrow dropping in.
        val cx = w * 0.5f
        drawLine(color, Offset(cx, h * 0.08f), Offset(cx, h * 0.6f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(cx - w * 0.16f, h * 0.44f), Offset(cx, h * 0.6f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(cx + w * 0.16f, h * 0.44f), Offset(cx, h * 0.6f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}
