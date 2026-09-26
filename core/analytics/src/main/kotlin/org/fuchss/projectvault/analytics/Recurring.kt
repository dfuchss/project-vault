package org.fuchss.projectvault.analytics

import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** How often a series recurs. */
enum class Cadence(val months: Int, val approxDays: Int) {
    MONTHLY(1, 30),
    QUARTERLY(3, 91),
    YEARLY(12, 365),
    ;

    /**
     * How long after its last occurrence a series is still given the benefit of the doubt. The next one
     * is due a period later; three extra weeks absorb billing-day drift (a bill "on the 1st" posting on
     * the 3rd), weekend and holiday shifts, and the odd skipped month. A monthly series therefore ends
     * about seven weeks after its last payment and a yearly one a year and three weeks after it — so a
     * yearly insurance is never called dead at eleven months, when it simply is not due yet.
     */
    val overdueAfterDays: Long get() = approxDays + 21L
}

/**
 * A change in a series' amount: it used to be [previousCents], it is now [currentCents], and the new
 * level has been in force [since] that date. Rent increases, insurance adjustments and price hikes all
 * show up here instead of being silently averaged into the typical amount.
 */
data class AmountChange(val previousCents: Long, val currentCents: Long, val since: LocalDate)

/** A detected recurring transaction (subscription, salary, rent, insurance, …). */
data class RecurringSeries(
    val merchantKey: String,
    val label: String,
    val categoryId: String?,
    val cadence: Cadence,
    /** The **current** level, signed (negative = recurring expense) — not an average over all history. */
    val typicalAmountCents: Long,
    /** When the series was last seen. */
    val lastDate: LocalDate,
    val nextExpectedDate: LocalDate,
    val occurrences: Int,
    /**
     * Whether the series is still alive as of the reference date detection was given. An inactive
     * ("ended") series — a cancelled subscription, a contract that ran out — is kept for the record but
     * is **excluded from the forecast** and from the fixed-cost summary. Manually authored series are
     * always active: the user said they recur, so no clock overrules that.
     */
    val isActive: Boolean = true,
    /** The most recent level change, if the amount stepped up or down during the series' life. */
    val amountChange: AmountChange? = null,
    /** Every level the series has been at, newest first. Empty for hand-authored series. */
    val levelsCents: List<Long> = emptyList(),
) {
    /** The levels this series has ever been billed at (newest first); never empty. */
    val knownLevelsCents: List<Long> get() = levelsCents.ifEmpty { listOf(typicalAmountCents) }

    /**
     * When an ended series stops being worth listing. It is kept around, dimmed, for **twice** the time
     * it took to declare it dead — long enough that a user who cancelled something sees the app noticed,
     * and scaled to how often it happened: a monthly subscription is stale news after a few months,
     * a yearly insurance only after a couple of years. The floor of half a year keeps a monthly series
     * visible for a sensible while rather than a few weeks. Past this date it is history, and it left
     * the forecast the day it was declared ended anyway.
     */
    val forgettableAfter: LocalDate get() = lastDate.plusDays(maxOf(180L, 2L * cadence.overdueAfterDays))
}

/** A projected future month from recurring series. */
data class ForecastMonth(val year: Int, val month: Int, val incomeCents: Long, val expenseCents: Long) {
    val netCents: Long get() = incomeCents - expenseCents
}

/**
 * Detects recurring transactions and projects them forward. Detection groups by a merchant key,
 * requires a minimum number of occurrences with a regular monthly/quarterly/yearly cadence, and takes
 * the **current** amount level as typical. Frequent, irregular activity (e.g. groceries several times a
 * week) matches no cadence window and is ignored.
 *
 * Two things make a series more than a historical average:
 *
 * - **Level shifts.** A rent increase changes the amount mid-life. Amount clustering would split that
 *   into two series (old rent, new rent) and a plain median over all history would blend them, so
 *   [detect] stitches clusters that follow one another in time back into one series and reports the
 *   step as an [AmountChange]. The typical amount is the newest level.
 * - **Life.** A cancelled contract stops producing transactions. [detect] marks a series whose next
 *   occurrence is long overdue as **inactive**, and [forecast] and [monthlyFixed] ignore those, so a
 *   subscription cancelled last year no longer shows up in next year's projection.
 */
object Recurring {

    /**
     * Detects recurring series in [txns].
     *
     * [asOf] is the reference "today" for deciding whether a series is still alive — passed in rather
     * than read from a clock, so this stays pure. It is clamped to the newest transaction in [txns]:
     * this app imports statements by hand, and when the vault has not been fed for two months *every*
     * series would otherwise look dead. Staleness is measured against how far the data reaches, not
     * against the wall clock.
     */
    fun detect(txns: List<AnalyticsTxn>, minOccurrences: Int = 3, asOf: LocalDate? = null): List<RecurringSeries> {
        val dataEnd = txns.maxOfOrNull { it.date } ?: return emptyList()
        val reference = if (asOf != null && asOf < dataEnd) asOf else dataEnd
        return txns
            // Transfers (credit-card settlement, Sparen/Umbuchung, deposits) are internal money
            // movements, never a recurring bill or income — exclude them so they don't pollute the list.
            .filter { !it.counterparty.isNullOrBlank() && it.kind != CategoryKind.TRANSFER }
            .groupBy { merchantKey(it.counterparty!!) }
            // A recurring series has a stable amount, so split each payer into amount clusters: this
            // isolates a fixed monthly salary from the same employer's bonuses/reimbursements (which
            // would otherwise merge into one noisy, undetectable group), and likewise per merchant.
            // `levelChains` then stitches clusters that *succeed* one another back together, so a rent
            // increase stays one series instead of becoming a dead one plus a newborn one.
            .flatMap { (key, group) -> levelChains(group).map { key to it } }
            .mapNotNull { (key, group) -> seriesOf(key, group, minOccurrences, reference) }
            // Live series first, then by size: an ended series is a footnote, not a headline.
            .sortedWith(compareByDescending<RecurringSeries> { it.isActive }.thenByDescending { abs(it.typicalAmountCents) })
    }

    /** Builds one series from an already-grouped, single-identity set of transactions. */
    private fun seriesOf(key: String, group: List<AnalyticsTxn>, minOccurrences: Int, reference: LocalDate): RecurringSeries? {
        if (group.size < minOccurrences) return null
        val sorted = group.sortedBy { it.date }
        val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.size < 2) return null
        val cadence = cadenceFor(median(gaps)) ?: return null
        // Reliability: a majority of the actual gaps must match the cadence, not just the median. This
        // rejects merged/irregular groups (e.g. many charges sharing a "PAYPAL"/"VISA" prefix) whose
        // median only lands in a cadence window by chance — otherwise they'd create a bogus recurring
        // line and a misleading forecast.
        if (gaps.count { cadenceFor(it) == cadence } * 2 <= gaps.size) return null

        val last = sorted.last().date
        val period = cadence.months.toLong()
        val active = ChronoUnit.DAYS.between(last, reference) <= cadence.overdueAfterDays
        // For a live series the next occurrence is the first one that has not come and gone; for an
        // ended one it is simply the one that never arrived.
        var next = last.plusMonths(period)
        if (active) while (next < reference) next = next.plusMonths(period)

        val levels = levelSegments(sorted)
        val current = levels.last()
        val previous = levels.getOrNull(levels.size - 2)
        return RecurringSeries(
            merchantKey = key,
            label = sorted.last().counterparty!!,
            categoryId = group.mapNotNull { it.categoryId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            cadence = cadence,
            typicalAmountCents = currentLevelAmount(current, cadence),
            lastDate = last,
            nextExpectedDate = next,
            occurrences = group.size,
            isActive = active,
            amountChange = previous?.let {
                AmountChange(median(it.map { t -> t.amountCents }), currentLevelAmount(current, cadence), current.first().date)
            },
            levelsCents = levels.asReversed().map { median(it.map { t -> t.amountCents }) },
        )
    }

    /**
     * The amount a series is billed at *now*: the median of its current level, and within that level
     * only its recent occurrences (the last three cadence periods, but never fewer than three
     * transactions). A level is stable by construction, so this normally changes nothing — it keeps a
     * long series that creeps up a percent a year tracking the present instead of its own history.
     */
    private fun currentLevelAmount(level: List<AnalyticsTxn>, cadence: Cadence): Long {
        val cutoff = level.last().date.minusDays(cadence.approxDays * 3L)
        val recent = level.filter { !it.date.isBefore(cutoff) }
        val window = if (recent.size >= 3) recent else level.takeLast(maxOf(3, recent.size))
        return median(window.map { it.amountCents })
    }

    /** Projects the [series] forward for [months] months starting at [from]. Inactive series are skipped. */
    fun forecast(series: List<RecurringSeries>, from: LocalDate, months: Int): List<ForecastMonth> {
        val income = HashMap<YearMonth, Long>()
        val expense = HashMap<YearMonth, Long>()
        val horizon = YearMonth.from(from).plusMonths((months - 1).toLong())

        for (s in series.filter { it.isActive }) {
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
     *
     * This classifies *history*, so it deliberately uses **every** series — ended ones included (a
     * subscription cancelled in spring was still a fixed cost in spring, not discretionary spending) —
     * and matches against **every level** a series has had, so last year's rent is recognised as rent
     * even though the series' current amount is this year's.
     */
    fun variableMonthlySpending(txns: List<AnalyticsTxn>, series: List<RecurringSeries>, windowMonths: Int = 12): VariableSpending {
        val fixedExpense = series.filter { it.typicalAmountCents < 0 }.groupBy { it.merchantKey }

        fun isFixed(t: AnalyticsTxn): Boolean {
            val cp = t.counterparty ?: return false
            val matches = fixedExpense[merchantKey(cp)] ?: return false
            return matches.any { s -> s.knownLevelsCents.any { abs(t.amountCents - it) <= tolerance(it) } }
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

    /**
     * The "typical month" from monthly-cadence series: fixed income vs. fixed costs. Only **live**
     * series count — a cancelled contract is not part of a typical month any more.
     */
    fun monthlyFixed(series: List<RecurringSeries>): IncomeExpense {
        val monthly = series.filter { it.isActive && it.cadence == Cadence.MONTHLY }
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
            if (current != null && rep != null && abs(t.amountCents - rep) <= tolerance(rep)) {
                current.add(t)
            } else {
                clusters.add(mutableListOf(t))
            }
        }
        return clusters
    }

    /**
     * Re-joins amount clusters of one payer that are really **one series at different price levels**.
     *
     * Clustering is by amount, so a rent increase beyond the 12.5% tolerance lands in two clusters. Left
     * apart, the old one is projected forever as a ghost and the new one needs three months before it is
     * detected at all — the rent would vanish from the forecast right after it went up. Two clusters are
     * stitched when they cannot be two things running side by side: the same sign, **no overlap in
     * time** (one ends before the other starts — a landlord charging rent *and* a service charge every
     * month overlaps, and stays split), and the handover no longer than three of the earlier cluster's
     * own gaps, so a contract that ended years before an unrelated one began is not glued to it.
     * Clusters of a single transaction are never stitched: one charge is an outlier, not a price level.
     */
    private fun levelChains(group: List<AnalyticsTxn>): List<List<AnalyticsTxn>> {
        val clusters = amountClusters(group).map { it.sortedBy { t -> t.date } }
        val (candidates, singles) = clusters.partition { it.size >= 2 }
        val chains = mutableListOf<MutableList<List<AnalyticsTxn>>>()
        for (cluster in candidates.sortedBy { it.first().date }) {
            val open = chains.lastOrNull()?.last()
            if (open != null && continues(open, cluster)) chains.last().add(cluster) else chains.add(mutableListOf(cluster))
        }
        return chains.map { chain -> chain.flatten().sortedBy { it.date } } + singles
    }

    /** Whether [next] looks like the continuation of [previous] at a new price level. */
    private fun continues(previous: List<AnalyticsTxn>, next: List<AnalyticsTxn>): Boolean {
        val sameSign = (previous.first().amountCents < 0) == (next.first().amountCents < 0)
        val handover = ChronoUnit.DAYS.between(previous.last().date, next.first().date)
        val stride = median(previous.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) })
        return sameSign && handover > 0 && handover <= stride * 3
    }

    /**
     * Splits a series' occurrences **in time order** into price levels: a charge stays on the current
     * level while it is within [levelBand] of it, otherwise a new level begins. Single-occurrence levels
     * are dropped as outliers (one odd charge in the middle of a stable series is not a price change)
     * and neighbours that end up on the same level after that are merged again. Always returns at least
     * one level, oldest first.
     */
    private fun levelSegments(sorted: List<AnalyticsTxn>): List<List<AnalyticsTxn>> {
        val raw = mutableListOf<MutableList<AnalyticsTxn>>()
        for (t in sorted) {
            val current = raw.lastOrNull()
            val rep = current?.let { median(it.map { x -> x.amountCents }) }
            if (current != null && rep != null && abs(t.amountCents - rep) <= levelBand(rep)) current.add(t) else raw.add(mutableListOf(t))
        }
        val kept = raw.filter { it.size >= 2 }.ifEmpty { return listOf(sorted) }
        val merged = mutableListOf<MutableList<AnalyticsTxn>>()
        for (segment in kept) {
            val previous = merged.lastOrNull()
            val rep = previous?.let { median(it.map { x -> x.amountCents }) }
            if (rep != null && abs(median(segment.map { it.amountCents }) - rep) <= levelBand(rep)) previous.addAll(segment)
            else merged.add(segment.toMutableList())
        }
        return merged
    }

    /** Two charges count as "the same" recurring amount within 12.5% (or 2€ for small sums). */
    private fun tolerance(amountCents: Long): Long = maxOf(abs(amountCents) / 8, 200)

    /**
     * How far a charge may sit from a price level and still be that level: 4% (or 2€). Tighter than
     * [tolerance] — that one decides what belongs to the same *series*, this one whether the price
     * *changed* — so a 5% rent rise reads as a step while a utility bill wobbling by a euro does not.
     */
    private fun levelBand(amountCents: Long): Long = maxOf(abs(amountCents) / 25, 200)

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
