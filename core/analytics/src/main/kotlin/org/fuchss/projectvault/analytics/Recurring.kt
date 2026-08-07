package org.fuchss.projectvault.analytics

import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** How often a series recurs. */
enum class Cadence(val months: Int, val approxDays: Int) {
    MONTHLY(1, 30),
    QUARTERLY(3, 91),
    YEARLY(12, 365),
}

/** A detected recurring transaction (subscription, salary, rent, insurance, …). */
data class RecurringSeries(
    val merchantKey: String,
    val label: String,
    val categoryId: String?,
    val cadence: Cadence,
    val typicalAmountCents: Long, // signed: negative = recurring expense
    val lastDate: LocalDate,
    val nextExpectedDate: LocalDate,
    val occurrences: Int,
)

/** A projected future month from recurring series. */
data class ForecastMonth(val year: Int, val month: Int, val incomeCents: Long, val expenseCents: Long) {
    val netCents: Long get() = incomeCents - expenseCents
}

/**
 * Detects recurring transactions and projects them forward. Detection groups by a merchant key,
 * requires a minimum number of occurrences with a regular monthly/quarterly/yearly cadence, and takes
 * the median amount as typical. Frequent, irregular activity (e.g. groceries several times a week)
 * matches no cadence window and is ignored.
 */
object Recurring {

    fun detect(txns: List<AnalyticsTxn>, minOccurrences: Int = 3): List<RecurringSeries> =
        // Transfers (credit-card settlement, Sparen/Umbuchung, deposits) are internal money movements,
        // never a recurring bill or income — exclude them so they don't pollute the recurring list.
        txns.filter { !it.counterparty.isNullOrBlank() && it.kind != CategoryKind.TRANSFER }
            .groupBy { merchantKey(it.counterparty!!) }
            // A recurring series has a stable amount, so split each payer into amount clusters: this
            // isolates a fixed monthly salary from the same employer's bonuses/reimbursements (which
            // would otherwise merge into one noisy, undetectable group), and likewise per merchant.
            .flatMap { (key, group) -> amountClusters(group).map { key to it } }
            .mapNotNull { (key, group) ->
                if (group.size < minOccurrences) return@mapNotNull null
                val sorted = group.sortedBy { it.date }
                val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
                if (gaps.size < 2) return@mapNotNull null
                val cadence = cadenceFor(median(gaps)) ?: return@mapNotNull null
                // Reliability: a majority of the actual gaps must match the cadence, not just the median.
                // This rejects merged/irregular groups (e.g. many charges sharing a "PAYPAL"/"VISA"
                // prefix) whose median only lands in a cadence window by chance — otherwise they'd
                // create a bogus recurring line and a misleading forecast.
                if (gaps.count { cadenceFor(it) == cadence } * 2 <= gaps.size) return@mapNotNull null
                val last = sorted.last().date
                RecurringSeries(
                    merchantKey = key,
                    label = sorted.last().counterparty!!,
                    categoryId = group.mapNotNull { it.categoryId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    cadence = cadence,
                    typicalAmountCents = median(sorted.map { it.amountCents }),
                    lastDate = last,
                    nextExpectedDate = last.plusMonths(cadence.months.toLong()),
                    occurrences = group.size,
                )
            }
            .sortedByDescending { kotlin.math.abs(it.typicalAmountCents) }

    /** Projects the [series] forward for [months] months starting at [from]. */
    fun forecast(series: List<RecurringSeries>, from: LocalDate, months: Int): List<ForecastMonth> {
        val income = HashMap<YearMonth, Long>()
        val expense = HashMap<YearMonth, Long>()
        val horizon = YearMonth.from(from).plusMonths((months - 1).toLong())

        for (s in series) {
            var date = s.nextExpectedDate
            while (YearMonth.from(date) < YearMonth.from(from)) date = date.plusMonths(s.cadence.months.toLong())
            while (YearMonth.from(date) <= horizon) {
                val ym = YearMonth.from(date)
                if (s.typicalAmountCents >= 0) income.merge(ym, s.typicalAmountCents, Long::plus)
                else expense.merge(ym, -s.typicalAmountCents, Long::plus)
                date = date.plusMonths(s.cadence.months.toLong())
            }
        }

        return (0 until months).map { i ->
            val ym = YearMonth.from(from).plusMonths(i.toLong())
            ForecastMonth(ym.year, ym.monthValue, income[ym] ?: 0L, expense[ym] ?: 0L)
        }
    }

    /**
     * The estimated **non-fixed** (variable) monthly spending: everything that isn't a detected
     * recurring bill — groceries, restaurants, one-off shopping, fuel, etc. Returned as a mean and a
     * population standard deviation over the observed months, so a forecast can treat next month's
     * discretionary spend as `mean ± stdDev` rather than pretending only fixed costs exist.
     */
    data class VariableSpending(val meanCents: Long, val stdDevCents: Long, val sampleMonths: Int)

    /**
     * Estimates variable monthly spending from history. An expense is "fixed" (and excluded here) when
     * it matches a detected recurring **expense** series by merchant key and amount (within the same
     * tolerance the detector uses); everything else counts as variable. Variable expenses are summed
     * per calendar month, then reduced to a mean and standard deviation over the **[windowMonths] most
     * recent months** with data (default 12) so the estimate tracks current habits, not old ones.
     */
    fun variableMonthlySpending(txns: List<AnalyticsTxn>, series: List<RecurringSeries>, windowMonths: Int = 12): VariableSpending {
        val fixedExpense = series.filter { it.typicalAmountCents < 0 }.groupBy { it.merchantKey }

        fun isFixed(t: AnalyticsTxn): Boolean {
            val cp = t.counterparty ?: return false
            val matches = fixedExpense[merchantKey(cp)] ?: return false
            return matches.any { kotlin.math.abs(t.amountCents - it.typicalAmountCents) <= tolerance(it.typicalAmountCents) }
        }

        val perMonth = txns
            .filter { it.kind != CategoryKind.TRANSFER && it.amountCents < 0 && !isFixed(it) }
            .groupBy { YearMonth.from(it.date) }
            .mapValues { (_, list) -> list.sumOf { -it.amountCents } }
            .entries.sortedBy { it.key }          // chronological
            .takeLast(windowMonths)               // keep only the most recent window
            .map { it.value }

        if (perMonth.isEmpty()) return VariableSpending(0, 0, 0)
        val mean = perMonth.average()
        val variance = perMonth.sumOf { (it - mean) * (it - mean) } / perMonth.size
        return VariableSpending(mean.roundToLong(), sqrt(variance).roundToLong(), perMonth.size)
    }

    /** The "typical month" from monthly-cadence series: fixed income vs. fixed costs. */
    fun monthlyFixed(series: List<RecurringSeries>): IncomeExpense {
        val monthly = series.filter { it.cadence == Cadence.MONTHLY }
        val income = monthly.filter { it.typicalAmountCents >= 0 }.sumOf { it.typicalAmountCents }
        val expense = monthly.filter { it.typicalAmountCents < 0 }.sumOf { -it.typicalAmountCents }
        return IncomeExpense(income, expense)
    }

    /**
     * Splits a payer's transactions into clusters of similar amount (ascending, greedy: a charge joins
     * the current cluster while within [tolerance] of its median, else starts a new one). Same merchant
     * but very different amounts = different things (salary vs. bonus vs. refund).
     */
    private fun amountClusters(group: List<AnalyticsTxn>): List<List<AnalyticsTxn>> {
        val clusters = mutableListOf<MutableList<AnalyticsTxn>>()
        for (t in group.sortedBy { it.amountCents }) {
            val current = clusters.lastOrNull()
            val rep = current?.let { median(it.map { x -> x.amountCents }) }
            if (current != null && rep != null && kotlin.math.abs(t.amountCents - rep) <= tolerance(rep)) {
                current.add(t)
            } else {
                clusters.add(mutableListOf(t))
            }
        }
        return clusters
    }

    /** Two charges count as "the same" recurring amount within 12.5% (or 2€ for small sums). */
    private fun tolerance(amountCents: Long): Long = maxOf(kotlin.math.abs(amountCents) / 8, 200)

    /** First alphanumeric token of length ≥ 3, uppercased — a stable-ish merchant identity. */
    fun merchantKey(counterparty: String): String {
        val token = counterparty.split(Regex("[^A-Za-z0-9]+")).firstOrNull { it.length >= 3 }
        return (token ?: counterparty.trim()).uppercase()
    }

    private fun cadenceFor(medianGapDays: Long): Cadence? = when (medianGapDays) {
        in 24..38 -> Cadence.MONTHLY
        in 80..100 -> Cadence.QUARTERLY
        in 350..380 -> Cadence.YEARLY
        else -> null
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
