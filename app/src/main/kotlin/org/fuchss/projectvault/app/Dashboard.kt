package org.fuchss.projectvault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fuchss.projectvault.analytics.Analytics
import org.fuchss.projectvault.analytics.AnalyticsTxn
import org.fuchss.projectvault.analytics.Cadence
import org.fuchss.projectvault.analytics.Recurring
import org.fuchss.projectvault.analytics.RecurringSeries
import org.fuchss.projectvault.data.ManualRecurring
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

// ---------------------------------------------------------------- Dashboard

@Composable
internal fun DashboardScreen(
    accounts: List<Account>,
    repo: VaultRepository,
    categoryById: Map<String, Category>,
    balances: Map<String, Long?>,
    refreshKey: Int,
) {
    val analyticsTxns = remember(accounts, refreshKey) {
        accounts.flatMap { repo.transactions(it.id) }.map { t ->
            AnalyticsTxn(t.amountCents, LocalDate.ofEpochDay(t.bookingDate), t.categoryId, t.categoryId?.let { categoryById[it]?.kind }, t.counterparty)
        }
    }
    val detected = remember(analyticsTxns) { Recurring.detect(analyticsTxns) }
    // User overrides: rename a detected series or hide a false positive (persisted per merchant key).
    var recurVersion by remember { mutableStateOf(0) }
    val overrides = remember(refreshKey, recurVersion) { repo.recurringOverrides() }
    // Manual series the user added by hand (keyed "manual:<id>"), merged with the detected ones so they
    // show in the list and feed the forecast just the same.
    val manual = remember(refreshKey, recurVersion) { repo.manualRecurring() }
    val recurring = remember(detected, overrides, manual) {
        val detectedVisible = detected.filterNot { overrides[it.merchantKey]?.hidden == true }
        (detectedVisible + manual.map { it.toSeries() }).sortedByDescending { kotlin.math.abs(it.typicalAmountCents) }
    }
    // Detected series the user hid — surfaced behind a "N hidden" affordance so they can be un-hidden.
    val hiddenDetected = remember(detected, overrides) {
        detected.filter { overrides[it.merchantKey]?.hidden == true }
            .sortedByDescending { kotlin.math.abs(it.typicalAmountCents) }
    }
    var showingHidden by remember { mutableStateOf(false) }
    var showAllRecurring by remember { mutableStateOf(false) }
    var editingRecurring by remember { mutableStateOf<RecurringSeries?>(null) }
    var editingManual by remember { mutableStateOf<ManualRecurring?>(null) }
    var addingRecurring by remember { mutableStateOf(false) }
    // Candidates for a manually-added series: existing counterparties not already auto-detected, each
    // with its typical (median) amount and last date — so adding a series is a selection, not typing.
    val recurringCandidates = remember(analyticsTxns, detected) {
        val autoKeys = detected.mapTo(HashSet()) { it.merchantKey }
        analyticsTxns.filter { !it.counterparty.isNullOrBlank() }
            .groupBy { Recurring.merchantKey(it.counterparty!!) }
            .filterKeys { it !in autoKeys }
            .map { (key, g) ->
                val sorted = g.sortedBy { it.date }
                RecurringCandidate(
                    merchantKey = key,
                    label = sorted.last().counterparty!!,
                    amountCents = medianL(g.map { it.amountCents }),
                    lastDate = sorted.last().date,
                    categoryId = g.mapNotNull { it.categoryId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    count = g.size,
                )
            }
            .sortedByDescending { kotlin.math.abs(it.amountCents) }
    }
    // Start at NEXT month: the current month is partly actual (and a monthly item that already hit
    // this month has its next occurrence next month), so projecting full future months is reliable
    // and matches the "next 6 months" label.
    val forecastFrom = remember { LocalDate.now().withDayOfMonth(1).plusMonths(1) }
    val forecast = remember(recurring) { Recurring.forecast(recurring, forecastFrom, months = 6) }
    val fixed = remember(recurring) { Recurring.monthlyFixed(recurring) }
    // Estimated discretionary spend (everything that isn't a fixed bill), from the last 12 months, as
    // mean ± std dev — used to make the forecast realistic and to draw its uncertainty band.
    val variable = remember(analyticsTxns, detected) { Recurring.variableMonthlySpending(analyticsTxns, detected) }
    val months = remember(analyticsTxns) { analyticsTxns.map { YearMonth.from(it.date) }.distinct().sortedDescending() }
    // Default to the latest month that has any entries (months is sorted descending).
    var selectedMonth by remember(months) { mutableStateOf(months.firstOrNull()) }
    val periodTxns = remember(analyticsTxns, selectedMonth) {
        val m = selectedMonth
        if (m == null) analyticsTxns else analyticsTxns.filter { YearMonth.from(it.date) == m }
    }
    val periodLabel = selectedMonth?.let(::formatYearMonth) ?: "All time"

    val netWorth = accounts.sumOf { balances[it.id] ?: 0L }
    val incomeExpense = remember(periodTxns) { Analytics.incomeExpense(periodTxns) }
    val byCategory = remember(periodTxns) { Analytics.spendingByCategory(periodTxns) }
    val monthly = remember(analyticsTxns) { Analytics.monthlyCashflow(analyticsTxns) }

    // Salary by month, so that a (partial) month with no income yet can show an approximation instead
    // of appearing as all-expense-no-income: we carry over the **salary** of the most recent earlier
    // month that had one (only paychecks, not one-off/other income). Labelled "Expected income".
    val salaryByMonth = remember(analyticsTxns) {
        analyticsTxns.filter { it.categoryId == CAT_SALARY && it.amountCents > 0 }
            .groupBy { YearMonth.from(it.date) }
            .mapValues { (_, l) -> l.sumOf { it.amountCents } }
    }
    val sel = selectedMonth
    val lastMonthSalary = remember(salaryByMonth, sel) {
        if (sel != null) salaryByMonth.filterKeys { it < sel }.maxByOrNull { it.key }?.value else null
    }
    val isExpectedIncome = sel != null && incomeExpense.incomeCents == 0L && lastMonthSalary != null
    val displayedIncome = if (isExpectedIncome) lastMonthSalary!! else incomeExpense.incomeCents

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (months.isNotEmpty()) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(periodLabel) }
                    RoundedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("All time") }, onClick = { selectedMonth = null; menu = false })
                        months.forEach { m ->
                            DropdownMenuItem(text = { Text(formatYearMonth(m)) }, onClick = { selectedMonth = m; menu = false })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Net worth", formatCents(netWorth), Modifier.weight(1f))
            StatCard(if (isExpectedIncome) "Expected income" else "Income", formatCents(displayedIncome), Modifier.weight(1f), MoneyPositive, estimated = isExpectedIncome)
            StatCard("Expense", formatCents(incomeExpense.expenseCents), Modifier.weight(1f), MoneyNegative)
            StatCard("Net", formatCents(incomeExpense.netCents), Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Spending by category · $periodLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (byCategory.isEmpty()) {
                    Text("No spending yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val totalSpending = byCategory.sumOf { it.amountCents }
                    val max = byCategory.first().amountCents.coerceAtLeast(1)
                    val shown = byCategory.take(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
                            DonutChart(
                                slices = shown.map { parseHexColor(categoryById[it.categoryId]?.color) to it.amountCents.toFloat() },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatCents(totalSpending), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            shown.forEach { total ->
                                val category = total.categoryId?.let { categoryById[it] }
                                CategoryBar(
                                    name = category?.name ?: "Uncategorized",
                                    color = parseHexColor(category?.color),
                                    amount = total.amountCents,
                                    fraction = total.amountCents.toFloat() / max,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Monthly cash flow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (monthly.isEmpty()) {
                    Text("No transactions yet. Import a statement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val window = monthly.takeLast(12)
                    if (window.size >= 2) {
                        Text("Net trend", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        TrendChart(
                            points = window.map { "%02d/%d".format(it.month, it.year) to it.netCents },
                            modifier = Modifier.fillMaxWidth().height(88.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    val max = monthly.maxOf { maxOf(it.incomeCents, it.expenseCents) }.coerceAtLeast(1)
                    monthly.takeLast(12).forEach { m ->
                        val ym = YearMonth.of(m.year, m.month)
                        val isSelected = ym == selectedMonth
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { selectedMonth = ym }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("%02d/%d".format(m.month, m.year), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                            Column(Modifier.weight(1f)) {
                                Bar(m.incomeCents.toFloat() / max, MoneyPositive)
                                Spacer(Modifier.height(3.dp))
                                Bar(m.expenseCents.toFloat() / max, MoneyNegative)
                            }
                            Text(formatCents(m.netCents), Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = if (m.netCents < 0) MoneyNegative else MoneyPositive)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recurring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (recurring.isNotEmpty()) {
                        Text("tap to edit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (hiddenDetected.isNotEmpty()) {
                        TextButton(onClick = { showingHidden = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("${hiddenDetected.size} hidden")
                        }
                    }
                    TextButton(onClick = { addingRecurring = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("+ Add") }
                }
                Spacer(Modifier.height(12.dp))
                if (recurring.isEmpty()) {
                    Text("No recurring transactions detected yet — add one with “+ Add”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val shownRecurring = if (showAllRecurring) recurring else recurring.take(12)
                    shownRecurring.forEach { s ->
                        val manualId = s.merchantKey.removePrefix("manual:").takeIf { s.merchantKey.startsWith("manual:") }
                        val label = if (manualId != null) s.label else overrides[s.merchantKey]?.label ?: s.label
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                if (manualId != null) editingManual = manual.firstOrNull { it.id == manualId } else editingRecurring = s
                            }.padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Badge(s.cadence.name.lowercase())
                                    if (manualId != null) { Spacer(Modifier.width(6.dp)); Badge("manual") }
                                    s.categoryId?.let { categoryById[it] }?.let { Spacer(Modifier.width(6.dp)); CategoryChip(it) }
                                    Spacer(Modifier.width(6.dp))
                                    Text("next ${formatLocalDate(s.nextExpectedDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(formatCents(s.typicalAmountCents), color = if (s.typicalAmountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (recurring.size > 12) {
                        TextButton(onClick = { showAllRecurring = !showAllRecurring }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (showAllRecurring) "Show less" else "Show all ${recurring.size}")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Forecast · next 6 months", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Fixed income ${formatCents(fixed.incomeCents)} · fixed costs ${formatCents(fixed.expenseCents)} · free ${formatCents(fixed.netCents)} / month",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (variable.sampleMonths > 0) {
                    Text(
                        "Variable spending ø ${formatCents(variable.meanCents)} ± ${formatCents(variable.stdDevCents)} / month (last ${variable.sampleMonths} mo)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (forecast.all { it.incomeCents == 0L && it.expenseCents == 0L } && variable.sampleMonths == 0) {
                    Text("Not enough recurring data to forecast yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Roll today's net worth forward by each month's projected net MINUS the estimated
                    // variable spend, so the central line reflects likely reality (not just fixed items).
                    // The band is ±1σ of cumulative variable spend: independent months, so variance adds
                    // and the half-width grows with √k — a widening "cone of uncertainty".
                    val labels = listOf("now") + forecast.map { "%02d/%d".format(it.month, it.year) }
                    val central = ArrayList<Long>(labels.size)
                    val band = ArrayList<Pair<Long, Long>>(labels.size) // (lower, upper) per point
                    var running = netWorth
                    central.add(running); band.add(netWorth to netWorth)
                    forecast.forEachIndexed { i, f ->
                        running += f.netCents - variable.meanCents
                        val halfWidth = (variable.stdDevCents * kotlin.math.sqrt((i + 1).toDouble())).roundToLong()
                        central.add(running); band.add((running - halfWidth) to (running + halfWidth))
                    }
                    val projected = labels.zip(central)
                    val endBalance = central.last()
                    val delta = endBalance - netWorth
                    val endLow = band.last().first
                    val endHigh = band.last().second
                    Text("Projected balance (± 1σ variable spend)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    TrendChart(
                        points = projected,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        anchorZero = false,
                        band = band,
                    )
                    Spacer(Modifier.height(10.dp))
                    val sign = if (delta >= 0) "+" else ""
                    Text(
                        "≈ ${formatCents(endBalance)} by ${labels.last()} · $sign${formatCents(delta)} over 6 months",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (delta < 0) MoneyNegative else MoneyPositive,
                        fontWeight = FontWeight.Medium,
                    )
                    if (endHigh != endLow) {
                        Text(
                            "range ${formatCents(endLow)} … ${formatCents(endHigh)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (band.any { it.first < 0 }) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "⚠ Could go negative within the range — possible cash shortfall.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    val editing = editingRecurring
    if (editing != null) {
        val existing = overrides[editing.merchantKey]
        var name by remember(editing) { mutableStateOf(existing?.label ?: editing.label) }
        AlertDialog(
            onDismissRequest = { editingRecurring = null },
            title = { Text("Recurring series") },
            text = {
                Column {
                    Text(
                        "Detected as \"${editing.label}\" · ${editing.cadence.name.lowercase()} · ${formatCents(editing.typicalAmountCents)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { repo.setRecurringOverride(editing.merchantKey, existing?.label, hidden = true); recurVersion++; editingRecurring = null },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("Hide from recurring", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.setRecurringOverride(editing.merchantKey, name.trim().ifBlank { null }, hidden = false)
                    recurVersion++; editingRecurring = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (existing != null) {
                        TextButton(onClick = { repo.clearRecurringOverride(editing.merchantKey); recurVersion++; editingRecurring = null }) { Text("Reset") }
                    }
                    TextButton(onClick = { editingRecurring = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (addingRecurring || editingManual != null) {
        RecurringSeriesDialog(
            existing = editingManual,
            candidates = recurringCandidates,
            categories = categoryById.values.toList(),
            onSave = { label, categoryId, cadence, amountCents, nextDate ->
                val m = editingManual
                if (m == null) repo.addManualRecurring(label, categoryId, cadence, amountCents, nextDate)
                else repo.updateManualRecurring(m.id, label, categoryId, cadence, amountCents, nextDate)
                recurVersion++; addingRecurring = false; editingManual = null
            },
            onDelete = editingManual?.let { m -> { repo.deleteManualRecurring(m.id); recurVersion++; editingManual = null } },
            onDismiss = { addingRecurring = false; editingManual = null },
        )
    }

    if (showingHidden) {
        HiddenRecurringDialog(
            hidden = hiddenDetected,
            categoryById = categoryById,
            labelFor = { overrides[it.merchantKey]?.label ?: it.label },
            onUnhide = { s ->
                repo.setRecurringOverride(s.merchantKey, overrides[s.merchantKey]?.label, hidden = false)
                recurVersion++
            },
            onDismiss = { showingHidden = false },
        )
    }
}

/** Lists the recurring series the user has hidden, each with an "Unhide" action to restore it. */
@Composable
private fun HiddenRecurringDialog(
    hidden: List<RecurringSeries>,
    categoryById: Map<String, Category>,
    labelFor: (RecurringSeries) -> String,
    onUnhide: (RecurringSeries) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden recurring series") },
        text = {
            if (hidden.isEmpty()) {
                Text("Nothing hidden.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.width(400.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    hidden.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(labelFor(s), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Badge(s.cadence.name.lowercase())
                                    s.categoryId?.let { categoryById[it] }?.let { Spacer(Modifier.width(6.dp)); CategoryChip(it) }
                                }
                            }
                            Text(formatCents(s.typicalAmountCents), color = if (s.typicalAmountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onUnhide(s) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Unhide") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** An existing counterparty offered as the basis for a manually-added recurring series (task #20). */
private data class RecurringCandidate(
    val merchantKey: String,
    val label: String,
    val amountCents: Long,
    val lastDate: LocalDate,
    val categoryId: String?,
    val count: Int,
)

/**
 * Add or edit a recurring series (task #20). Adding is **selection-based, not free text**: the user
 * picks an existing transaction/merchant, and the amount is taken from it (median) — they only choose
 * the cadence, next date, an optional rename and category. Editing an existing manual series adjusts
 * those same fields (amount stays as recorded) and offers Delete.
 */
@Composable
private fun RecurringSeriesDialog(
    existing: ManualRecurring?,
    candidates: List<RecurringCandidate>,
    categories: List<Category>,
    onSave: (label: String, categoryId: String?, cadence: String, amountCents: Long, nextDate: LocalDate) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val editing = existing != null
    var selected by remember { mutableStateOf<RecurringCandidate?>(null) }

    // Step 1 (add only): pick an existing transaction to base the series on.
    if (!editing && selected == null) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(query, candidates) {
            if (query.isBlank()) candidates else candidates.filter { it.label.contains(query, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add recurring · pick a transaction") },
            text = {
                Column(Modifier.width(400.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search counterparty") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (filtered.isEmpty()) {
                        Text("No matching transactions to base a series on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            filtered.forEach { c ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selected = c }.padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${c.count}× · last ${formatLocalDate(c.lastDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(formatCents(c.amountCents), color = if (c.amountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    // Step 2 / edit: amount is derived (read-only); the user sets cadence, next date, name, category.
    val amountCents = if (editing) existing!!.amountCents else selected!!.amountCents
    var name by remember(selected) { mutableStateOf(existing?.label ?: selected?.label ?: "") }
    var cadence by remember(selected) { mutableStateOf(existing?.let { runCatching { Cadence.valueOf(it.cadence) }.getOrNull() } ?: Cadence.MONTHLY) }
    var dateText by remember(selected) {
        mutableStateOf((existing?.nextDate ?: selected!!.lastDate.plusMonths(1)).toString())
    }
    val allowedCats = categories.filter { categoryAllowedForAmount(amountCents, it.kind) }
    var categoryId by remember(selected) {
        mutableStateOf((existing?.categoryId ?: selected?.categoryId)?.takeIf { id -> allowedCats.any { it.id == id } })
    }
    var cadenceMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }

    val nextDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val valid = name.isNotBlank() && nextDate != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Edit recurring series" else "Add recurring series") },
        text = {
            Column(Modifier.width(360.dp)) {
                Text("Amount (from the selected transaction)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCents(amountCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (amountCents < 0) MoneyNegative else MoneyPositive)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box {
                        OutlinedButton(onClick = { cadenceMenu = true }) { Text(cadence.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        RoundedDropdownMenu(expanded = cadenceMenu, onDismissRequest = { cadenceMenu = false }) {
                            Cadence.entries.forEach { c ->
                                DropdownMenuItem(text = { Text(c.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { cadence = c; cadenceMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = dateText, onValueChange = { dateText = it },
                        label = { Text("Next date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f),
                        isError = dateText.isNotBlank() && nextDate == null,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }) {
                        Text("Category: " + (allowedCats.firstOrNull { it.id == categoryId }?.name ?: "None"))
                    }
                    RoundedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { categoryId = null; categoryMenu = false })
                        allowedCats.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { categoryId = c.id; categoryMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(name.trim(), categoryId, cadence.name, amountCents, nextDate!!) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                if (!editing) TextButton(onClick = { selected = null }) { Text("Back") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Median of a list of longs (0 for empty). */
private fun medianL(values: List<Long>): Long {
    if (values.isEmpty()) return 0
    val s = values.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

/** A manual series presented as a [RecurringSeries] so it merges with detected ones (key "manual:<id>"). */
private fun ManualRecurring.toSeries(): RecurringSeries {
    val c = runCatching { Cadence.valueOf(cadence) }.getOrDefault(Cadence.MONTHLY)
    return RecurringSeries(
        merchantKey = "manual:$id",
        label = label,
        categoryId = categoryId,
        cadence = c,
        typicalAmountCents = amountCents,
        lastDate = nextDate.minusMonths(c.months.toLong()),
        nextExpectedDate = nextDate,
        occurrences = 0,
    )
}
