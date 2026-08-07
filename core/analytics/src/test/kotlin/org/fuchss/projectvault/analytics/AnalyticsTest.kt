package org.fuchss.projectvault.analytics

import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsTest {

    private fun tx(cents: Long, day: LocalDate, cat: String?, kind: CategoryKind?) =
        AnalyticsTxn(cents, day, cat, kind)

    private val jul = LocalDate.of(2026, 7, 5)
    private val aug = LocalDate.of(2026, 8, 3)

    private val data = listOf(
        tx(342_500, jul, "cat-income", CategoryKind.INCOME),    // income
        tx(-6_400, jul, "cat-groceries", CategoryKind.EXPENSE), // expense
        tx(-3_100, jul, "cat-groceries", CategoryKind.EXPENSE), // expense
        tx(-1_250, aug, "cat-fuel", CategoryKind.EXPENSE),      // expense
        tx(-8_450, jul, "cat-transfers", CategoryKind.TRANSFER), // ignored
        tx(-740, aug, null, null),                              // uncategorized expense
    )

    @Test
    fun `income and expense exclude transfers and use sign`() {
        val ie = Analytics.incomeExpense(data)
        assertEquals(342_500, ie.incomeCents)
        assertEquals(11_490, ie.expenseCents) // 6400 + 3100 + 1250 + 740
        assertEquals(331_010, ie.netCents)
    }

    @Test
    fun `spending by category is ranked and includes uncategorized`() {
        val byCat = Analytics.spendingByCategory(data)
        assertEquals(CategoryTotal("cat-groceries", 9_500), byCat.first())
        assertEquals(setOf("cat-groceries", "cat-fuel", null), byCat.map { it.categoryId }.toSet())
    }

    @Test
    fun `monthly cashflow groups by month`() {
        val months = Analytics.monthlyCashflow(data)
        assertEquals(2, months.size)
        val july = months.first { it.month == 7 }
        assertEquals(342_500, july.incomeCents)
        assertEquals(9_500, july.expenseCents)
        val august = months.first { it.month == 8 }
        assertEquals(1_990, august.expenseCents)
    }
}
