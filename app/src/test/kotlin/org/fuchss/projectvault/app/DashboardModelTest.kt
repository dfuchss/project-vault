package org.fuchss.projectvault.app

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.fuchss.projectvault.analytics.Analytics
import org.fuchss.projectvault.analytics.AnalyticsTxn
import org.fuchss.projectvault.model.CategoryKind

/**
 * The dashboard's non-UI logic: resolving a selected period into a date range, and folding the
 * spending breakdown into donut slices. Both are pure, so they are checked here rather than through a
 * rendered screen.
 */
class DashboardModelTest {

    private fun txn(date: String, cents: Long, categoryId: String? = null, kind: CategoryKind? = null, counterparty: String? = null) =
        AnalyticsTxn(cents, LocalDate.parse(date), categoryId, kind, counterparty)

    // -- Period → date range --------------------------------------------------

    @Test
    fun `all time is an open range that filters nothing`() {
        val range = DashboardPeriod.allTime.resolve(LocalDate.of(2026, 3, 15))
        assertTrue(range.isOpen)
        val txns = listOf(txn("2001-01-01", -100), txn("2030-12-31", -100))
        assertEquals(txns, txns.inPeriod(range))
    }

    @Test
    fun `rolling windows are calendar-month aligned and include the whole current month`() {
        // 31 March: a naive "minus one month" would land on 28 February and lose three days.
        val today = LocalDate.of(2026, 3, 31)
        assertEquals(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
            DashboardPeriod.of(PeriodKind.LAST_3M).resolve(today),
        )
        assertEquals(
            DateRange(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 3, 31)),
            DashboardPeriod.of(PeriodKind.LAST_6M).resolve(today),
        )
        assertEquals(
            DateRange(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31)),
            DashboardPeriod.of(PeriodKind.LAST_12M).resolve(today),
        )
    }

    @Test
    fun `a rolling window ends on the last day of the current month even mid-month`() {
        // February in a leap year: the window must end on the 29th, not on "today".
        val range = DashboardPeriod.of(PeriodKind.LAST_3M).resolve(LocalDate.of(2024, 2, 10))
        assertEquals(DateRange(LocalDate.of(2023, 12, 1), LocalDate.of(2024, 2, 29)), range)
        assertTrue(LocalDate.of(2024, 2, 29) in range)
        assertFalse(LocalDate.of(2024, 3, 1) in range)
    }

    @Test
    fun `year to date runs from 1 January up to today, not to the end of the year`() {
        val today = LocalDate.of(2026, 3, 15)
        val range = DashboardPeriod.of(PeriodKind.YEAR_TO_DATE).resolve(today)
        assertEquals(DateRange(LocalDate.of(2026, 1, 1), today), range)
        assertTrue(LocalDate.of(2026, 1, 1) in range)
        assertTrue(today in range)
        assertFalse(LocalDate.of(2026, 3, 16) in range)
        assertFalse(LocalDate.of(2025, 12, 31) in range)
    }

    @Test
    fun `a whole year covers January to December of that year`() {
        val range = DashboardPeriod.ofYear(2025).resolve(LocalDate.of(2026, 3, 15))
        assertEquals(DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)), range)
        assertFalse(LocalDate.of(2026, 1, 1) in range)
    }

    @Test
    fun `a month covers its own length`() {
        assertEquals(
            DateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)),
            DashboardPeriod.ofMonth(YearMonth.of(2024, 2)).resolve(LocalDate.of(2026, 3, 15)),
        )
        assertEquals(
            DateRange(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)),
            DashboardPeriod.ofMonth(YearMonth.of(2025, 2)).resolve(LocalDate.of(2026, 3, 15)),
        )
        assertEquals(YearMonth.of(2025, 2), DashboardPeriod.ofMonth(YearMonth.of(2025, 2)).selectedMonth)
        assertEquals(null, DashboardPeriod.of(PeriodKind.LAST_3M).selectedMonth)
    }

    @Test
    fun `a custom range is inclusive on both ends and may be open on either side`() {
        val today = LocalDate.of(2026, 3, 15)
        val closed = DashboardPeriod.custom(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 20)).resolve(today)
        assertTrue(LocalDate.of(2026, 1, 10) in closed)
        assertTrue(LocalDate.of(2026, 2, 20) in closed)
        assertFalse(LocalDate.of(2026, 1, 9) in closed)
        assertFalse(LocalDate.of(2026, 2, 21) in closed)

        val openStart = DashboardPeriod.custom(null, LocalDate.of(2026, 2, 20)).resolve(today)
        assertFalse(openStart.isOpen)
        assertTrue(LocalDate.of(1999, 1, 1) in openStart)
        assertFalse(LocalDate.of(2026, 2, 21) in openStart)

        val openEnd = DashboardPeriod.custom(LocalDate.of(2026, 1, 10), null).resolve(today)
        assertTrue(LocalDate.of(2999, 1, 1) in openEnd)
        assertFalse(LocalDate.of(2026, 1, 9) in openEnd)
    }

    @Test
    fun `a custom range typed the wrong way round is normalized instead of selecting nothing`() {
        val range = DashboardPeriod.custom(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 1))
            .resolve(LocalDate.of(2026, 3, 15))
        assertEquals(DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1)), range)
    }

    @Test
    fun `filtering keeps exactly the transactions inside the range`() {
        val txns = listOf(
            txn("2025-12-31", -100),
            txn("2026-01-01", -200),
            txn("2026-03-31", -300),
            txn("2026-04-01", -400),
        )
        val q1 = DashboardPeriod.of(PeriodKind.LAST_3M).resolve(LocalDate.of(2026, 3, 15))
        assertEquals(listOf(-200L, -300L), txns.inPeriod(q1).map { it.amountCents })
    }

    // -- Donut slices ---------------------------------------------------------

    private fun totals(vararg amounts: Long) =
        Analytics.spendingByCategory(amounts.mapIndexed { i, a -> txn("2026-01-0${i % 9 + 1}", -a, "cat-$i") })

    @Test
    fun `a short breakdown keeps every category as its own slice`() {
        val slices = donutSlices(totals(500, 300, 100))
        assertEquals(3, slices.size)
        assertTrue(slices.none { it.isOther })
        assertEquals(listOf(500L, 300L, 100L), slices.map { it.amountCents })
    }

    @Test
    fun `the tail is merged into one remainder slice, and slices still add up to the true total`() {
        val breakdown = totals(1000, 900, 800, 700, 600, 500, 400, 300, 200, 100, 50)
        val slices = donutSlices(breakdown, maxSlices = 8)

        assertEquals(8, slices.size)
        val other = slices.last()
        assertTrue(other.isOther)
        // 7 head slices + the merged tail of 4 categories.
        assertEquals(4, other.categoryIds.size)
        assertEquals(300L + 200L + 100L + 50L, other.amountCents)
        // The invariant that makes the chart honest: nothing is dropped.
        assertEquals(breakdown.sumOf { it.amountCents }, slices.sumOf { it.amountCents })
    }

    @Test
    fun `a breakdown exactly at the cap is shown untouched`() {
        val breakdown = totals(8, 7, 6, 5, 4, 3, 2, 1)
        val slices = donutSlices(breakdown, maxSlices = 8)
        assertEquals(8, slices.size)
        assertTrue(slices.none { it.isOther })
        assertEquals(breakdown.sumOf { it.amountCents }, slices.sumOf { it.amountCents })
    }

    @Test
    fun `uncategorized spending is a slice of its own`() {
        val txns = listOf(txn("2026-01-01", -500, "cat-a"), txn("2026-01-02", -250))
        val slices = donutSlices(Analytics.spendingByCategory(txns))
        assertEquals(listOf("cat-a", null), slices.map { it.categoryId })
    }

    // -- Drill-down rows ------------------------------------------------------

    @Test
    fun `drill-down lists a category's expenses, biggest first, excluding income and transfers`() {
        val txns = listOf(
            txn("2026-01-05", -1200, "cat-a", counterparty = "REWE"),
            txn("2026-01-09", -4500, "cat-a", counterparty = "EDEKA"),
            txn("2026-01-11", -300, "cat-b", counterparty = "BAHN"),
            txn("2026-01-12", 900, "cat-a", counterparty = "Refund"),
            txn("2026-01-13", -700, "cat-a", CategoryKind.TRANSFER, "Umbuchung"),
        )
        val rows = spendingRows(txns, setOf("cat-a"))
        assertEquals(listOf("EDEKA", "REWE"), rows.map { it.counterparty })
    }

    @Test
    fun `drilling the remainder slice covers every category it merged, uncategorized included`() {
        val txns = listOf(
            txn("2026-01-05", -100, "cat-a"),
            txn("2026-01-06", -200, "cat-b"),
            txn("2026-01-07", -300),
        )
        val rows = spendingRows(txns, setOf("cat-b", null))
        assertEquals(listOf(-300L, -200L), rows.map { it.amountCents })
    }
}
