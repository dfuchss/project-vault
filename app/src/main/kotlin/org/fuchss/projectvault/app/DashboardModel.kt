package org.fuchss.projectvault.app

import java.time.LocalDate
import java.time.YearMonth
import org.fuchss.projectvault.analytics.AnalyticsTxn
import org.fuchss.projectvault.analytics.CategoryTotal
import org.fuchss.projectvault.model.CategoryKind

// The dashboard's non-UI logic: which transactions a selected period covers, and how the spending
// donut's slices are formed. Both are pure so they can be unit-tested (`DashboardModelTest`) — the
// date handling deliberately stays here and NOT in `:core:analytics`: `Analytics`/`Recurring` work on
// any `List<AnalyticsTxn>`, so all the dashboard has to contribute is the list a period selects.

/** The kinds of window the period pill offers. */
internal enum class PeriodKind { ALL_TIME, LAST_3M, LAST_6M, LAST_12M, YEAR_TO_DATE, YEAR, MONTH, CUSTOM }

/**
 * A selected dashboard period. Only the field belonging to the [kind] is used ([month] for
 * `MONTH`, [year] for `YEAR`, [customFrom]/[customTo] for `CUSTOM`); the others stay null.
 */
internal data class DashboardPeriod(
    val kind: PeriodKind,
    val month: YearMonth? = null,
    val year: Int? = null,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
) {
    /** The month this period pins to, if it is a single-month selection (used for month-only affordances). */
    val selectedMonth: YearMonth? get() = month.takeIf { kind == PeriodKind.MONTH }

    companion object {
        val allTime = DashboardPeriod(PeriodKind.ALL_TIME)
        fun of(kind: PeriodKind) = DashboardPeriod(kind)
        fun ofMonth(month: YearMonth) = DashboardPeriod(PeriodKind.MONTH, month = month)
        fun ofYear(year: Int) = DashboardPeriod(PeriodKind.YEAR, year = year)
        fun custom(from: LocalDate?, to: LocalDate?) = DashboardPeriod(PeriodKind.CUSTOM, customFrom = from, customTo = to)
    }
}

/** An inclusive date range; a null bound is open (no limit on that side). */
internal data class DateRange(val from: LocalDate?, val to: LocalDate?) {
    /** True when the range limits nothing — filtering can then be skipped entirely. */
    val isOpen: Boolean get() = from == null && to == null

    operator fun contains(date: LocalDate): Boolean =
        (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
}

/**
 * Resolves a period against [today] into a concrete inclusive range.
 *
 * The rolling windows ("last N months") are **calendar-month aligned** and include the whole current
 * month: they start on the 1st of the month N-1 months back and end on the last day of the current
 * month. That keeps them stable regardless of month length (no "31 February" arithmetic) and lines up
 * with the monthly cash-flow rows. **Year to date** is the exception and ends at [today] — "to date"
 * is precisely what it means.
 */
internal fun DashboardPeriod.resolve(today: LocalDate): DateRange = when (kind) {
    PeriodKind.ALL_TIME -> DateRange(null, null)
    PeriodKind.LAST_3M -> rollingMonths(today, 3)
    PeriodKind.LAST_6M -> rollingMonths(today, 6)
    PeriodKind.LAST_12M -> rollingMonths(today, 12)
    PeriodKind.YEAR_TO_DATE -> DateRange(today.withDayOfYear(1), today)
    PeriodKind.YEAR -> (year ?: today.year).let { DateRange(LocalDate.of(it, 1, 1), LocalDate.of(it, 12, 31)) }
    PeriodKind.MONTH -> (month ?: YearMonth.from(today)).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
    // A range typed the wrong way round would silently select nothing, so it is normalized instead.
    PeriodKind.CUSTOM ->
        if (customFrom != null && customTo != null && customFrom.isAfter(customTo)) DateRange(customTo, customFrom)
        else DateRange(customFrom, customTo)
}

/** The last [months] calendar months including the current one. */
private fun rollingMonths(today: LocalDate, months: Int): DateRange {
    val current = YearMonth.from(today)
    return DateRange(current.minusMonths(months - 1L).atDay(1), current.atEndOfMonth())
}

/** The transactions a resolved range covers (the whole list for an open range). */
internal fun List<AnalyticsTxn>.inPeriod(range: DateRange): List<AnalyticsTxn> =
    if (range.isOpen) this else filter { it.date in range }

/**
 * One ring/legend entry of the spending donut. Usually a single category; the last slice may be the
 * **"Other" remainder** that merges the tail, so the ring always adds up to the real total instead of
 * quietly dropping everything past the cap.
 */
internal data class DonutSlice(val categoryIds: List<String?>, val amountCents: Long) {
    val isOther: Boolean get() = categoryIds.size > 1
    /** The single category of a normal slice (null = uncategorized); meaningless for [isOther]. */
    val categoryId: String? get() = categoryIds.firstOrNull()
}

/**
 * Caps [totals] (biggest first) at [maxSlices] entries by folding everything past the first
 * `maxSlices - 1` into one remainder slice. The returned amounts always sum to the input's total.
 */
internal fun donutSlices(totals: List<CategoryTotal>, maxSlices: Int = 8): List<DonutSlice> {
    require(maxSlices >= 2) { "a donut needs room for at least one category plus the remainder" }
    val ranked = totals.sortedByDescending { it.amountCents }
    if (ranked.size <= maxSlices) return ranked.map { DonutSlice(listOf(it.categoryId), it.amountCents) }
    val head = ranked.take(maxSlices - 1).map { DonutSlice(listOf(it.categoryId), it.amountCents) }
    val tail = ranked.drop(maxSlices - 1)
    return head + DonutSlice(tail.map { it.categoryId }, tail.sumOf { it.amountCents })
}

/**
 * The spending rows behind a donut slice: the same transactions `Analytics.spendingByCategory`
 * counted (expenses, transfers excluded) for [categoryIds], biggest spend first.
 */
internal fun spendingRows(txns: List<AnalyticsTxn>, categoryIds: Set<String?>): List<AnalyticsTxn> =
    txns.filter { it.kind != CategoryKind.TRANSFER && it.amountCents < 0 && it.categoryId in categoryIds }
        .sortedWith(compareBy<AnalyticsTxn> { it.amountCents }.thenByDescending { it.date })
