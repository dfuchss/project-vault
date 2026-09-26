package org.fuchss.projectvault.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong
import org.fuchss.projectvault.analytics.Analytics
import org.fuchss.projectvault.analytics.AnalyticsTxn
import org.fuchss.projectvault.analytics.Cadence
import org.fuchss.projectvault.analytics.Recurring
import org.fuchss.projectvault.analytics.RecurringSeries
import org.fuchss.projectvault.data.ManualRecurring
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.model.categoryAllowedForAmount

// ---------------------------------------------------------------- Dashboard

@Composable
internal fun DashboardScreen(
    accounts: List<Account>,
    repo: VaultRepository,
    categoryById: Map<String, Category>,
    balances: Map<String, Long?>,
    refreshKey: Int,
) {
    val strings = LocalStrings.current
    val analyticsTxns = remember(accounts, refreshKey) {
        accounts.flatMap { repo.transactions(it.id) }.map { t ->
            AnalyticsTxn(t.amountCents, LocalDate.ofEpochDay(t.bookingDate), t.categoryId, t.categoryId?.let { categoryById[it]?.kind }, t.counterparty)
        }
    }
    // "Today" is captured once: the rolling windows and the recurring detector's staleness check
    // resolve against it, and re-reading the clock on every recomposition would invalidate the
    // memoized filters for nothing.
    val today = remember { LocalDate.now() }
    // `asOf` lets the detector decide which series are still alive; it clamps itself to the newest
    // transaction, so a vault that has not been fed for months does not lose its whole forecast.
    val detected = remember(analyticsTxns, today) { Recurring.detect(analyticsTxns, asOf = today) }
    // User overrides: rename a detected series or hide a false positive (persisted per merchant key).
    var recurVersion by remember { mutableStateOf(0) }
    val overrides = remember(refreshKey, recurVersion) { repo.recurringOverrides() }
    // Manual series the user added by hand (keyed "manual:<id>"), merged with the detected ones so they
    // show in the list and feed the forecast just the same.
    val manual = remember(refreshKey, recurVersion) { repo.manualRecurring() }
    val recurring = remember(detected, overrides, manual, today) {
        val detectedVisible = detected
            .filterNot { overrides[it.merchantKey]?.hidden == true }
            .filter { it.worthListing(today) }
        // Live series first: an ended one is kept for the record, but it must never push a bill the
        // user is still paying out of the (capped) list.
        (detectedVisible + manual.map { it.toSeries() })
            .sortedWith(compareByDescending<RecurringSeries> { it.isActive }.thenByDescending { kotlin.math.abs(it.typicalAmountCents) })
    }
    val endedCount = remember(recurring) { recurring.count { !it.isActive } }
    // Detected series the user hid — surfaced behind a "N hidden" affordance so they can be un-hidden.
    // Hiding takes a series off the list; it does not keep it alive. One that has ended and is past
    // being worth looking at drops out of here too, on exactly the same rule as a visible one — it is
    // removed because it stopped happening, never because it was hidden. The override itself stays in
    // the vault, so if the payments ever resume the series comes back hidden, as the user asked.
    val hiddenDetected = remember(detected, overrides, today) {
        detected.filter { overrides[it.merchantKey]?.hidden == true && it.worthListing(today) }
            .sortedWith(compareByDescending<RecurringSeries> { it.isActive }.thenByDescending { kotlin.math.abs(it.typicalAmountCents) })
    }
    var showingHidden by remember { mutableStateOf(false) }
    var showAllRecurring by remember { mutableStateOf(false) }
    var editingRecurring by remember { mutableStateOf<RecurringSeries?>(null) }
    var editingManual by remember { mutableStateOf<ManualRecurring?>(null) }
    var addingRecurring by remember { mutableStateOf(false) }
    // Candidates for a manually-added series: existing counterparties not already auto-detected, each
    // with its typical (median) amount and last date — so adding a series is a selection, not typing.
    val recurringCandidates = remember(analyticsTxns, detected) {
        // Only *live* series are off-limits: a merchant whose series has ended may be offered again, so
        // the user can re-add it by hand and keep projecting it if the detector called it dead too soon.
        val autoKeys = detected.filter { it.isActive }.mapTo(HashSet()) { it.merchantKey }
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
    val years = remember(analyticsTxns) { analyticsTxns.map { it.date.year }.distinct().sortedDescending() }
    // Default to the latest month that has any entries (months is sorted descending).
    var period by remember(months) { mutableStateOf(months.firstOrNull()?.let(DashboardPeriod::ofMonth) ?: DashboardPeriod.allTime) }
    var editingRange by remember { mutableStateOf(false) }
    // The donut slice the user drilled into (null = no drill-down open).
    var drillDown by remember { mutableStateOf<CategoryDrillDown?>(null) }
    val range = remember(period, today) { period.resolve(today) }
    val periodTxns = remember(analyticsTxns, range) { analyticsTxns.inPeriod(range) }
    val periodLabel = strings.periodLabel(period)
    // Only a single-month selection drives the month-specific affordances (the highlighted cash-flow
    // row and the expected-income estimate) — those are meaningless over a multi-month range.
    val selectedMonth = period.selectedMonth

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
    // Net is derived from income, so once income is an estimate the net is one too — otherwise the row
    // would show an estimated income next to a net that still assumes zero income.
    val displayedNet = if (isExpectedIncome) displayedIncome - incomeExpense.expenseCents else incomeExpense.netCents

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.overview, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (months.isNotEmpty()) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    SelectPill(label = periodLabel, expanded = menu, active = period.kind != PeriodKind.ALL_TIME, onClick = { menu = true })
                    // One selector for every window the dashboard supports: all time, the rolling
                    // windows, a year, a single month, or a hand-typed range. The groups are separated
                    // by hairlines; the panel scrolls once the list outgrows its cap.
                    VaultMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        VaultMenuItem(strings.allTime, selected = period.kind == PeriodKind.ALL_TIME, onClick = { period = DashboardPeriod.allTime; menu = false })
                        VaultMenuDivider()
                        listOf(PeriodKind.LAST_3M, PeriodKind.LAST_6M, PeriodKind.LAST_12M, PeriodKind.YEAR_TO_DATE).forEach { kind ->
                            VaultMenuItem(
                                label = strings.periodLabel(DashboardPeriod.of(kind)),
                                selected = period.kind == kind,
                                onClick = { period = DashboardPeriod.of(kind); menu = false },
                            )
                        }
                        if (years.isNotEmpty()) {
                            VaultMenuDivider()
                            years.forEach { y ->
                                VaultMenuItem(
                                    label = y.toString(),
                                    selected = period.kind == PeriodKind.YEAR && period.year == y,
                                    onClick = { period = DashboardPeriod.ofYear(y); menu = false },
                                )
                            }
                        }
                        VaultMenuDivider()
                        months.forEach { m ->
                            VaultMenuItem(formatYearMonth(m), selected = selectedMonth == m, onClick = { period = DashboardPeriod.ofMonth(m); menu = false })
                        }
                        VaultMenuDivider()
                        VaultMenuItem(
                            label = strings.customRange,
                            selected = period.kind == PeriodKind.CUSTOM,
                            emphasis = true,
                            onClick = { editingRange = true; menu = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(strings.netWorth, formatCents(netWorth), Modifier.weight(1f))
            // Keep the short "Income" label even when estimated — the "≈ EST" marker conveys the estimate,
            // so a long "Expected income" label never widens the card.
            StatCard(strings.income, formatCents(displayedIncome), Modifier.weight(1f), MoneyPositive, estimated = isExpectedIncome)
            StatCard(strings.expense, formatCents(incomeExpense.expenseCents), Modifier.weight(1f), MoneyNegative)
            StatCard(strings.net, formatCents(displayedNet), Modifier.weight(1f), estimated = isExpectedIncome)
        }

        Spacer(Modifier.height(16.dp))
        VaultCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings.spendingByCategory(periodLabel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (byCategory.isNotEmpty()) {
                        Text(strings.clickCategoryHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (byCategory.isEmpty()) {
                    Text(strings.noSpendingYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val totalSpending = byCategory.sumOf { it.amountCents }
                    // The tail past the cap becomes one "Other" slice, so ring and legend always add up
                    // to the total in the centre instead of silently dropping the smallest categories.
                    val slices = remember(byCategory) { donutSlices(byCategory) }
                    val max = slices.maxOf { it.amountCents }.coerceAtLeast(1)
                    val otherColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
                            DonutChart(
                                slices = slices.map { sliceColor(it, categoryById, otherColor) to it.amountCents.toFloat() },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(strings.total, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatCents(totalSpending), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            slices.forEach { slice ->
                                val color = sliceColor(slice, categoryById, otherColor)
                                val name = sliceLabel(slice, categoryById, strings)
                                // Each legend row is the drill-down affordance: it opens the rows behind
                                // that slice (the "Other" slice opens all the categories it merged).
                                Box(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { drillDown = CategoryDrillDown(name, color, slice.categoryIds.toSet(), slice.amountCents) }
                                        .padding(horizontal = 4.dp),
                                ) {
                                    CategoryBar(
                                        name = name,
                                        color = color,
                                        amount = slice.amountCents,
                                        fraction = slice.amountCents.toFloat() / max,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        VaultCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.monthlyCashFlow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    // This card is a trailing 12-month history — it deliberately ignores the period
                    // pill (a one-month window would leave a single bar and no trend at all). Say so,
                    // so the reader doesn't take it for a filtered view.
                    Text(strings.trailingWindowNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                if (monthly.isEmpty()) {
                    Text(strings.noTransactionsYetImport, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val window = monthly.takeLast(12)
                    if (window.size >= 2) {
                        Text(strings.netTrend, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                .clickable { period = DashboardPeriod.ofMonth(ym) }
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
        VaultCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.recurring, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (recurring.isNotEmpty()) {
                        Text(strings.tapToEdit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (hiddenDetected.isNotEmpty()) {
                        TextButton(onClick = { showingHidden = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(strings.hiddenCount(hiddenDetected.size))
                        }
                    }
                    TextButton(onClick = { addingRecurring = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(strings.addShort) }
                }
                Spacer(Modifier.height(12.dp))
                if (recurring.isEmpty()) {
                    Text(strings.noRecurringYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val shownRecurring = if (showAllRecurring) recurring else recurring.take(12)
                    shownRecurring.forEach { s ->
                        val manualId = s.merchantKey.removePrefix("manual:").takeIf { s.merchantKey.startsWith("manual:") }
                        val label = if (manualId != null) s.label else overrides[s.merchantKey]?.label ?: s.label
                        // An ended series is dimmed rather than dropped: someone who cancelled a contract
                        // should see that the app noticed, not find the line silently gone.
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                if (manualId != null) editingManual = manual.firstOrNull { it.id == manualId } else editingRecurring = s
                            }.padding(horizontal = 6.dp, vertical = 5.dp).alpha(if (s.isActive) 1f else 0.55f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Badge(strings.cadenceLabel(s.cadence))
                                    if (manualId != null) { Spacer(Modifier.width(6.dp)); Badge(strings.manualBadge) }
                                    s.categoryId?.let { categoryById[it] }?.let { Spacer(Modifier.width(6.dp)); CategoryChip(it) }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (s.isActive) strings.nextOccurrence(formatLocalDate(s.nextExpectedDate))
                                        else strings.endedLastSeen(formatYearMonth(YearMonth.from(s.lastDate))),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // A rent increase or a price hike: say what it was, what it is, and since when.
                                s.amountChange?.let { change ->
                                    Text(
                                        strings.amountChanged(
                                            formatCents(change.previousCents),
                                            formatCents(change.currentCents),
                                            formatYearMonth(YearMonth.from(change.since)),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(formatCents(s.typicalAmountCents), color = if (s.typicalAmountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (endedCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            strings.endedExcludedNote(endedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (recurring.size > 12) {
                        TextButton(onClick = { showAllRecurring = !showAllRecurring }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (showAllRecurring) strings.showLess else strings.showAll(recurring.size))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        VaultCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.forecastTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    // The forecast is built from the full history (recurring detection) and a trailing
                    // 12-month mean ± σ of variable spending. Narrowing it to the selected period would
                    // change what σ means and corrupt the cone of uncertainty — so it stays fixed.
                    Text(strings.forecastWindowNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    strings.fixedSummary(formatCents(fixed.incomeCents), formatCents(fixed.expenseCents), formatCents(fixed.netCents)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (variable.sampleMonths > 0) {
                    Text(
                        strings.variableSummary(formatCents(variable.meanCents), formatCents(variable.stdDevCents), variable.sampleMonths),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (forecast.all { it.incomeCents == 0L && it.expenseCents == 0L } && variable.sampleMonths == 0) {
                    Text(strings.notEnoughForecast, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Roll today's net worth forward by each month's projected net MINUS the estimated
                    // variable spend, so the central line reflects likely reality (not just fixed items).
                    // The band is ±1σ of cumulative variable spend: independent months, so variance adds
                    // and the half-width grows with √k — a widening "cone of uncertainty".
                    val labels = listOf(strings.nowLabel) + forecast.map { "%02d/%d".format(it.month, it.year) }
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
                    Text(strings.projectedBalanceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        strings.projectedApprox(formatCents(endBalance), labels.last(), "$sign${formatCents(delta)}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (delta < 0) MoneyNegative else MoneyPositive,
                        fontWeight = FontWeight.Medium,
                    )
                    if (endHigh != endLow) {
                        Text(
                            strings.projectedRange(formatCents(endLow), formatCents(endHigh)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (band.any { it.first < 0 }) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            strings.shortfallWarning,
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
            title = { Text(strings.recurringSeriesTitle) },
            text = {
                Column {
                    Text(
                        strings.detectedAs(editing.label, strings.cadenceLabel(editing.cadence), formatCents(editing.typicalAmountCents)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    editing.amountChange?.let { change ->
                        Text(
                            strings.amountChanged(
                                formatCents(change.previousCents),
                                formatCents(change.currentCents),
                                formatYearMonth(YearMonth.from(change.since)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!editing.isActive) {
                        Text(
                            strings.endedExplanation(formatYearMonth(YearMonth.from(editing.lastDate))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(strings.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { repo.setRecurringOverride(editing.merchantKey, existing?.label, hidden = true); recurVersion++; editingRecurring = null },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text(strings.hideFromRecurring, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repo.setRecurringOverride(editing.merchantKey, name.trim().ifBlank { null }, hidden = false)
                    recurVersion++; editingRecurring = null
                }) { Text(strings.save) }
            },
            dismissButton = {
                Row {
                    if (existing != null) {
                        TextButton(onClick = { repo.clearRecurringOverride(editing.merchantKey); recurVersion++; editingRecurring = null }) { Text(strings.reset) }
                    }
                    TextButton(onClick = { editingRecurring = null }) { Text(strings.cancel) }
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

    if (editingRange) {
        CustomRangeDialog(
            initial = period,
            onApply = { from, to -> period = DashboardPeriod.custom(from, to); editingRange = false },
            onDismiss = { editingRange = false },
        )
    }

    val drill = drillDown
    if (drill != null) {
        // The rows come out of the already-filtered period list — a drill-down costs no extra query.
        val drillRows = remember(periodTxns, drill) { spendingRows(periodTxns, drill.categoryIds) }
        CategoryDrillDownDialog(drill = drill, periodLabel = periodLabel, rows = drillRows, onDismiss = { drillDown = null })
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

/** The slice the user clicked in the spending donut, with everything the drill-down needs to show it. */
private data class CategoryDrillDown(
    val label: String,
    val color: Color,
    /** The categories behind the slice — one, or all of the ones the "Other" slice merged. */
    val categoryIds: Set<String?>,
    val amountCents: Long,
)

/** A slice's colour: its category's, or a neutral tone for the merged "Other" remainder. */
private fun sliceColor(slice: DonutSlice, categoryById: Map<String, Category>, otherColor: Color): Color =
    if (slice.isOther) otherColor else parseHexColor(slice.categoryId?.let { categoryById[it] }?.color)

/** A slice's legend label: the category name, "Uncategorized", or "Other (n categories)". */
private fun sliceLabel(slice: DonutSlice, categoryById: Map<String, Category>, strings: Strings): String = when {
    slice.isOther -> strings.otherCategories(slice.categoryIds.size)
    else -> slice.categoryId?.let { categoryById[it]?.name } ?: strings.uncategorized
}

/**
 * The spending behind one donut slice: every transaction of that category (or of all the categories
 * the "Other" slice merged) inside the selected period, biggest spend first. Uncategorized drills down
 * too — those rows are exactly the ones a user wants to find and label.
 */
@Composable
private fun CategoryDrillDownDialog(
    drill: CategoryDrillDown,
    periodLabel: String,
    rows: List<AnalyticsTxn>,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(drill.color)
                Spacer(Modifier.width(8.dp))
                Text(drill.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(formatCents(drill.amountCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MoneyNegative)
            }
        },
        text = {
            Column(Modifier.width(520.dp)) {
                Text(
                    strings.categoryDetailSubtitle(periodLabel, rows.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (rows.isEmpty()) {
                    Text(strings.noTransactionsInPeriod, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // Lazy: a wide period can put thousands of rows behind a single category.
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(rows) { t ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.counterparty?.takeIf { it.isNotBlank() } ?: strings.unknownCounterparty,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(formatLocalDate(t.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Spending is shown as a magnitude everywhere on this card (the ring's
                                // centre total, the legend bars, this dialog's header), with the red
                                // doing the sign — so the rows negate too rather than being the one
                                // place a minus appears.
                                Text(formatCents(-t.amountCents), color = MoneyNegative, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.done) } },
    )
}

/**
 * A free from/to range. Dates are typed as `YYYY-MM-DD` — the same plain-text entry the recurring
 * dialog uses, since the app has no calendar widget. Either side may stay empty for an open end.
 */
@Composable
private fun CustomRangeDialog(
    initial: DashboardPeriod,
    onApply: (from: LocalDate?, to: LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var fromText by remember { mutableStateOf(initial.customFrom?.toString() ?: "") }
    var toText by remember { mutableStateOf(initial.customTo?.toString() ?: "") }
    fun parse(text: String) = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    val from = parse(fromText)
    val to = parse(toText)
    val fromError = fromText.isNotBlank() && from == null
    val toError = toText.isNotBlank() && to == null
    // An entirely empty range would just be "All time", which the menu already offers.
    val valid = !fromError && !toError && (from != null || to != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.customRangeTitle) },
        text = {
            Column(Modifier.width(360.dp)) {
                OutlinedTextField(
                    value = fromText, onValueChange = { fromText = it },
                    label = { Text(strings.fromDateLabel) }, singleLine = true, isError = fromError,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = toText, onValueChange = { toText = it },
                    label = { Text(strings.toDateLabel) }, singleLine = true, isError = toError,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(strings.customRangeHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(from, to) }) { Text(strings.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
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
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.hiddenRecurringTitle) },
        text = {
            if (hidden.isEmpty()) {
                Text(strings.nothingHidden, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Badge(strings.cadenceLabel(s.cadence))
                                    s.categoryId?.let { categoryById[it] }?.let { Spacer(Modifier.width(6.dp)); CategoryChip(it) }
                                    if (!s.isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            strings.endedLastSeen(formatYearMonth(YearMonth.from(s.lastDate))),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(formatCents(s.typicalAmountCents), color = if (s.typicalAmountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onUnhide(s) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(strings.unhide) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.done) } },
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
    val strings = LocalStrings.current
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
            title = { Text(strings.addRecurringPickTitle) },
            text = {
                Column(Modifier.width(400.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text(strings.searchCounterparty) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (filtered.isEmpty()) {
                        Text(strings.noMatchingTransactions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            filtered.forEach { c ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selected = c }.padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(strings.candidateSubtitle(c.count, formatLocalDate(c.lastDate)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(formatCents(c.amountCents), color = if (c.amountCents < 0) MoneyNegative else MoneyPositive, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
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
        title = { Text(if (editing) strings.editRecurringSeriesTitle else strings.addRecurringSeriesTitle) },
        text = {
            Column(Modifier.width(360.dp)) {
                Text(strings.amountFromTransaction, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCents(amountCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (amountCents < 0) MoneyNegative else MoneyPositive)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(strings.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box {
                        SelectPill(label = strings.cadenceLabel(cadence), expanded = cadenceMenu, onClick = { cadenceMenu = true })
                        VaultMenu(expanded = cadenceMenu, onDismissRequest = { cadenceMenu = false }) {
                            Cadence.entries.forEach { c ->
                                VaultMenuItem(strings.cadenceLabel(c), selected = c == cadence, onClick = { cadence = c; cadenceMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = dateText, onValueChange = { dateText = it },
                        label = { Text(strings.nextDateLabel) }, singleLine = true, modifier = Modifier.weight(1f),
                        isError = dateText.isNotBlank() && nextDate == null,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box {
                    val chosen = allowedCats.firstOrNull { it.id == categoryId }
                    SelectPill(
                        prefix = strings.categoryPrefix,
                        label = chosen?.name ?: strings.none,
                        leadingDot = chosen?.let { parseHexColor(it.color) },
                        expanded = categoryMenu,
                        onClick = { categoryMenu = true },
                    )
                    VaultMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        VaultMenuItem(strings.none, selected = categoryId == null, onClick = { categoryId = null; categoryMenu = false })
                        allowedCats.forEach { c ->
                            VaultMenuItem(
                                label = c.name,
                                selected = c.id == categoryId,
                                leadingDot = parseHexColor(c.color),
                                onClick = { categoryId = c.id; categoryMenu = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(name.trim(), categoryId, cadence.name, amountCents, nextDate!!) }) { Text(strings.save) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(strings.delete, color = MaterialTheme.colorScheme.error) }
                if (!editing) TextButton(onClick = { selected = null }) { Text(strings.back) }
                TextButton(onClick = onDismiss) { Text(strings.cancel) }
            }
        },
    )
}

/**
 * Whether a series still belongs on the recurring list at all. A live one always does; an ended one
 * stays — dimmed — until [RecurringSeries.forgettableAfter], which scales with its cadence, and then
 * drops off. The same rule decides the visible list and the hidden list, so "hidden" never means
 * "kept forever".
 */
private fun RecurringSeries.worthListing(today: LocalDate): Boolean = isActive || today < forgettableAfter

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
