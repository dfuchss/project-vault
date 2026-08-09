package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.NewHolding
import org.fuchss.projectvault.data.NewImportBatch
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Holding
import org.fuchss.projectvault.quotes.NoopQuoteProvider
import org.fuchss.projectvault.quotes.QuoteProvider
import org.fuchss.projectvault.quotes.Repricing
import java.time.LocalDate

/**
 * Reprices a Depot's positions from live market data — the app-side counterpart to [ImportService].
 *
 * What it does and, more importantly, what it does not: it takes the securities and **quantities**
 * from the most recent imported statement and only recomputes each position's *value* as
 * `quantity × current price`. It never changes a quantity, never adds or removes a position, never
 * touches transactions, and never rewrites the statement's own rows. The bank statement stays the
 * source of truth; a refresh is an additional dated snapshot beside it.
 *
 * Because that snapshot is written through the normal [VaultRepository.storeDepotSnapshot] /
 * [VaultRepository.createBatch] path, it inherits the existing behaviour for free: it shows up in
 * the snapshot picker, refreshing again the same day replaces it rather than piling up, and
 * deleting it from the import history restores the statement value.
 */
class QuoteRefreshService(
    private val repo: VaultRepository,
    private val provider: QuoteProvider = NoopQuoteProvider,
) {
    /** Outcome of a refresh, for the status line. */
    sealed interface Result {
        /** [repriced] positions got a live price; [carried] kept their statement value. */
        data class Refreshed(val repriced: Int, val carried: Int, val totalCents: Long) : Result

        /** Live prices are not switched on for this account, or no provider is configured. */
        data object Unavailable : Result

        /** No imported Depot snapshot to reprice from. */
        data object NoStatement : Result

        /** A statement is already dated today — see [refresh] for why that blocks a refresh. */
        data object StatementToday : Result

        /** The provider answered for nothing at all (offline, rate-limited, endpoint changed). */
        data object NoQuotes : Result
    }

    /**
     * Fetches current prices for [accountId]'s holdings and stores the result as today's snapshot.
     *
     * Blocking (one HTTP call per position) — callers run it off the UI thread.
     */
    fun refresh(accountId: String, today: LocalDate = LocalDate.now()): Result {
        val account = repo.account(accountId) ?: return Result.Unavailable
        if (account.liveQuotes != 1L || !provider.available()) return Result.Unavailable

        val statementDays = repo.statementValuationDates(accountId)
        val baseDay = statementDays.firstOrNull() ?: return Result.NoStatement
        // A live snapshot is always dated today, and storing a snapshot replaces everything on that
        // date. If the user imported a Depotauszug dated today, writing over it would destroy
        // imported data and orphan its import batch — so the statement wins and we do nothing.
        if (today.toEpochDay() in statementDays) return Result.StatementToday

        val base = repo.holdingsForValuationDate(accountId, baseDay)
        if (base.isEmpty()) return Result.NoStatement

        val repriced = base.map { holding -> reprice(holding) }
        if (repriced.none { it.quoteAt != null }) return Result.NoQuotes

        // Drop a previous live snapshot for today first. storeDepotSnapshot would replace its
        // holdings anyway, but the batch row would survive — cluttering the import history and
        // leaving a stale endBalanceCents that currentBalanceCents could still pick up.
        repo.batches(accountId)
            .filter { it.kind == LIVE_KIND && it.valuationDate == today.toEpochDay() }
            .forEach { repo.deleteBatch(it.id) }

        val total = repriced.sumOf { it.marketValueCents }
        val batchId = repo.createBatch(
            accountId,
            NewImportBatch(
                kind = LIVE_KIND,
                sourceName = SOURCE_NAME,
                institution = SOURCE_NAME,
                statementNumber = null,
                periodStart = null,
                periodEnd = null,
                valuationDate = today,
                endBalanceCents = total,
                // Nothing to reconcile against: there is no stated total to check these against.
                reconciled = false,
                itemCount = repriced.count { it.quoteAt != null },
            ),
        )
        repo.storeDepotSnapshot(accountId, today, batchId, repriced)

        return Result.Refreshed(
            repriced = repriced.count { it.quoteAt != null },
            carried = repriced.count { it.quoteAt == null },
            totalCents = total,
        )
    }

    /**
     * One position at the current price, or the statement row unchanged when it cannot be priced
     * safely. Carrying a position over (rather than dropping it) keeps the snapshot total complete
     * and comparable with the statement's.
     */
    private fun reprice(holding: Holding): NewHolding {
        val carried = NewHolding(
            isin = holding.isin,
            wkn = holding.wkn,
            name = holding.name,
            quantity = holding.quantity,
            priceText = holding.priceText,
            marketValueCents = holding.marketValueCents,
            currency = holding.currency,
            quoteAt = null,
        )
        // No ISIN: nothing to look up. Non-EUR: the quote box carries no currency, so a converted
        // value would be a guess — the statement's own number is the honest one.
        val isin = holding.isin?.takeIf { it.isNotBlank() } ?: return carried
        if (!holding.currency.equals("EUR", ignoreCase = true)) return carried

        val quote = provider.quote(isin) ?: return carried
        // Percent-of-par quotation (bonds): quantity × price is meaningless, so never reprice it.
        if (!quote.perShare) return carried
        val valueCents = Repricing.repricedCents(holding.quantity, quote.price) ?: return carried

        return carried.copy(
            priceText = quote.price.toPlainString(),
            marketValueCents = valueCents,
            quoteAt = quote.asOf,
        )
    }

    private companion object {
        /** Batch kind marking a snapshot as fetched rather than imported. */
        const val LIVE_KIND = "LIVE"
        const val SOURCE_NAME = "Börse Frankfurt"
    }
}
