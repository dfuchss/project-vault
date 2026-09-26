package org.fuchss.projectvault.app

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.fuchss.projectvault.data.db.Txn

/** The list's filtering and sorting, checked without a UI — it is a pure function on purpose. */
class TxnListFilterTest {

    private fun txn(
        id: String,
        day: LocalDate,
        amountCents: Long,
        counterparty: String? = null,
        purpose: String = "",
        categoryId: String? = null,
        suggestedCategoryId: String? = null,
    ) = Txn(
        id = id,
        accountId = "acc",
        bookingDate = day.toEpochDay(),
        valueDate = null,
        amountCents = amountCents,
        currency = "EUR",
        counterparty = counterparty,
        purpose = purpose,
        bookingType = null,
        categoryId = categoryId,
        categorySource = null,
        suggestedCategoryId = suggestedCategoryId,
        importBatchId = null,
        dedupHash = id,
        createdAt = 0,
    )

    private val sample = listOf(
        txn("a", LocalDate.of(2026, 5, 4), -2500, counterparty = "REWE Markt"),
        txn("b", LocalDate.of(2026, 6, 1), 180000, purpose = "Gehalt Juni", categoryId = "cat-salary"),
        txn("c", LocalDate.of(2026, 6, 14), -12000, counterparty = "Möbelhaus", suggestedCategoryId = "cat-home"),
        txn("d", LocalDate.of(2026, 6, 28), -500, purpose = "Bäckerei"),
    )

    private fun run(
        search: String = "",
        filter: String = "ALL",
        period: YearMonth? = null,
        min: Long? = null,
        max: Long? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
        sort: TxnSort = TxnSort.DATE_DESC,
    ) = filterTransactions(sample, search, filter, period, min, max, from, to, sort).map { it.id }

    @Test
    fun `the three-way review filter is disjoint`() {
        assertEquals(listOf("d", "c", "b", "a"), run())
        assertEquals(listOf("d", "a"), run(filter = "NONE"), "a row with a suggestion is not 'uncategorized'")
        assertEquals(listOf("c"), run(filter = "REVIEW"))
        assertEquals(listOf("b"), run(filter = "cat-salary"))
    }

    @Test
    fun `the review filter disappears with the last reviewable row, and takes the view with it`() {
        // "c" is the only row with a pending suggestion, so the pill exists while it does.
        assertTrue(hasReviewable(sample))
        assertEquals("REVIEW", filterAfterReviewDisappears("REVIEW", reviewAvailable = true))

        // Accept or dismiss it and nothing is reviewable any more: the pill goes, and a view left on
        // it falls back to Uncategorized instead of showing an empty list under a filter that is gone.
        val dealtWith = sample.map { if (it.id == "c") it.copy(suggestedCategoryId = null) else it }
        assertFalse(hasReviewable(dealtWith))
        assertEquals("NONE", filterAfterReviewDisappears("REVIEW", reviewAvailable = false))

        // Every other filter is left exactly as it is — only the hidden one is corrected.
        listOf("ALL", "NONE", "cat-salary").forEach {
            assertEquals(it, filterAfterReviewDisappears(it, reviewAvailable = false))
        }

        // The same coercion applied to the live filter state the UI holds.
        val filters = TxnListFilters().apply { filter = "REVIEW" }
        filters.coerceFilter(reviewAvailable = true)
        assertEquals("REVIEW", filters.filter)
        filters.coerceFilter(reviewAvailable = false)
        assertEquals("NONE", filters.filter)
    }

    @Test
    fun `search matches counterparty and purpose, case-insensitively`() {
        assertEquals(listOf("a"), run(search = "rewe"))
        assertEquals(listOf("d"), run(search = "bäck"))
    }

    @Test
    fun `amount bounds compare the magnitude, so a range finds debits and credits alike`() {
        assertEquals(listOf("c", "a"), run(min = 2500, max = 12000))
        assertEquals(listOf("b"), run(min = 100000))
    }

    @Test
    fun `date range and month filter both bound the booking date`() {
        assertEquals(listOf("d", "c"), run(from = LocalDate.of(2026, 6, 10)))
        assertEquals(listOf("c", "b"), run(from = LocalDate.of(2026, 6, 1), to = LocalDate.of(2026, 6, 14)))
        assertEquals(listOf("d", "c", "b"), run(period = YearMonth.of(2026, 6)))
    }

    @Test
    fun `sorting by amount is signed, by date it is chronological`() {
        assertEquals(listOf("b", "d", "a", "c"), run(sort = TxnSort.AMOUNT_DESC))
        assertEquals(listOf("c", "a", "d", "b"), run(sort = TxnSort.AMOUNT_ASC))
        assertEquals(listOf("a", "b", "c", "d"), run(sort = TxnSort.DATE_ASC))
    }

    @Test
    fun `typed amounts are read the way people write them`() {
        assertEquals(1250L, parseAmountInput("12,50"))
        assertEquals(1250L, parseAmountInput("12.50"))
        assertEquals(123456L, parseAmountInput("1.234,56"))
        assertEquals(-2500L, parseAmountInput(" -25 € "))
        assertEquals(2000L, parseAmountInput("20"))
        assertNull(parseAmountInput(""), "an empty bound is no bound")
        assertNull(parseAmountInput("abc"), "a half-typed value must not filter anything away")
    }

    @Test
    fun `typed dates accept the statement format and ISO`() {
        assertEquals(LocalDate.of(2026, 6, 14), parseDateInput("14.06.2026"))
        assertEquals(LocalDate.of(2026, 6, 14), parseDateInput("2026-06-14"))
        assertNull(parseDateInput("14.06."))
        assertNull(parseDateInput(""))
    }
}
