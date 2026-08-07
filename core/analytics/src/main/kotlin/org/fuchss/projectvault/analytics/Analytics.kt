package org.fuchss.projectvault.analytics

import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate

/** A minimal projection of a transaction for analytics (money as signed cents). */
data class AnalyticsTxn(
    val amountCents: Long,
    val date: LocalDate,
    val categoryId: String?,
    val kind: CategoryKind?,
    val counterparty: String? = null,
)

data class IncomeExpense(val incomeCents: Long, val expenseCents: Long) {
    val netCents: Long get() = incomeCents - expenseCents
}

/** Total expense for a category (absolute cents). `categoryId` null = uncategorized. */
data class CategoryTotal(val categoryId: String?, val amountCents: Long)

data class MonthlyFlow(val year: Int, val month: Int, val incomeCents: Long, val expenseCents: Long) {
    val netCents: Long get() = incomeCents - expenseCents
}

/**
 * Pure aggregations for the dashboard. Transfers (category kind TRANSFER) are internal movements and
 * are excluded everywhere; income vs. expense is decided by amount sign so uncategorized rows still
 * count.
 */
object Analytics {

    private fun AnalyticsTxn.isTransfer() = kind == CategoryKind.TRANSFER

    fun incomeExpense(txns: List<AnalyticsTxn>): IncomeExpense {
        var income = 0L
        var expense = 0L
        txns.filterNot { it.isTransfer() }.forEach {
            if (it.amountCents >= 0) income += it.amountCents else expense += -it.amountCents
        }
        return IncomeExpense(income, expense)
    }

    fun spendingByCategory(txns: List<AnalyticsTxn>): List<CategoryTotal> =
        txns.filterNot { it.isTransfer() }
            .filter { it.amountCents < 0 }
            .groupBy { it.categoryId }
            .map { (categoryId, list) -> CategoryTotal(categoryId, list.sumOf { -it.amountCents }) }
            .sortedByDescending { it.amountCents }

    fun monthlyCashflow(txns: List<AnalyticsTxn>): List<MonthlyFlow> =
        txns.filterNot { it.isTransfer() }
            .groupBy { it.date.year to it.date.monthValue }
            .map { (ym, list) ->
                MonthlyFlow(
                    year = ym.first,
                    month = ym.second,
                    incomeCents = list.filter { it.amountCents >= 0 }.sumOf { it.amountCents },
                    expenseCents = list.filter { it.amountCents < 0 }.sumOf { -it.amountCents },
                )
            }
            .sortedWith(compareBy({ it.year }, { it.month }))
}
