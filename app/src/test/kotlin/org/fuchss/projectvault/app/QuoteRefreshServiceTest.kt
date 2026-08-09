package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.NewHolding
import org.fuchss.projectvault.data.NewImportBatch
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.quotes.Quote
import org.fuchss.projectvault.quotes.QuoteProvider
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end live-pricing checks against a temp vault and a stub provider — no network, and only
 * synthetic positions (never anything from a real portfolio).
 */
class QuoteRefreshServiceTest {

    /** Canned prices; records what was asked for so we can assert nothing extra leaves the app. */
    private class StubProvider(private val prices: Map<String, Quote>) : QuoteProvider {
        val asked = mutableListOf<String>()

        override fun available(): Boolean = true

        override fun quote(isin: String): Quote? {
            asked += isin
            return prices[isin]
        }
    }

    private fun quote(isin: String, price: String, perShare: Boolean = true) =
        Quote(isin, BigDecimal(price), perShare, "XETR", ASOF)

    private fun repo(): VaultRepository =
        VaultRepository(VaultManager.create(File(Files.createTempDirectory("pvault-quotes").toFile(), "v.pvault")))

    /** A Depot with an imported statement snapshot, live prices switched on. */
    private fun depotWithStatement(repo: VaultRepository, holdings: List<NewHolding>): String {
        val accountId = repo.addAccount("Depot", AccountType.DEPOT, institution = "ING")
        repo.setLiveQuotesEnabled(accountId, true)
        val batch = repo.createBatch(
            accountId,
            NewImportBatch("DEPOT", "depot.pdf", "ING", null, null, null, STATEMENT_DAY, holdings.sumOf { it.marketValueCents }, true, holdings.size),
        )
        repo.storeDepotSnapshot(accountId, STATEMENT_DAY, batch, holdings)
        return accountId
    }

    @Test
    fun `reprices each position by quantity times current price, leaving the statement untouched`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", "150.00", 180_000, "EUR"),
            NewHolding("DE0007236101", null, "Blue Chip B", "4", "250.00", 100_000, "EUR"),
        ))
        val provider = StubProvider(mapOf(
            "DE0007164600" to quote("DE0007164600", "177.98"),
            "DE0007236101" to quote("DE0007236101", "279.55"),
        ))

        val result = QuoteRefreshService(repo, provider).refresh(accountId, TODAY) as QuoteRefreshService.Result.Refreshed

        // 12 × 177,98 = 2.135,76 €   +   4 × 279,55 = 1.118,20 €
        assertEquals(2, result.repriced)
        assertEquals(0, result.carried)
        assertEquals(213_576L + 111_820L, result.totalCents)

        val live = repo.holdingsForValuationDate(accountId, TODAY.toEpochDay()).associateBy { it.name }
        assertEquals(213_576L, live.getValue("Blue Chip A").marketValueCents)
        assertEquals(ASOF, live.getValue("Blue Chip A").quoteAt)
        // Quantities are copied verbatim — a refresh never invents or changes how much is held.
        assertEquals("12", live.getValue("Blue Chip A").quantity)

        // The imported snapshot is still exactly as the bank reported it.
        val statement = repo.holdingsForValuationDate(accountId, STATEMENT_DAY.toEpochDay()).associateBy { it.name }
        assertEquals(180_000L, statement.getValue("Blue Chip A").marketValueCents)
        assertNull(statement.getValue("Blue Chip A").quoteAt)

        // Only ISINs are ever sent.
        assertEquals(listOf("DE0007164600", "DE0007236101"), provider.asked)
    }

    @Test
    fun `positions that cannot be priced safely keep their statement value`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
            NewHolding("DE000SYNTHBD1", null, "Synthetic Bund", "5000", null, 500_000, "EUR"),  // percent-quoted
            NewHolding("US0000000000", null, "Dollar Position", "10", null, 90_000, "USD"),     // non-EUR
            NewHolding(null, null, "Cash-like, no ISIN", "1", null, 25_000, "EUR"),
            NewHolding("DE000UNKNOWN1", null, "Unlisted here", "3", null, 30_000, "EUR"),       // no quote
        ))
        val provider = StubProvider(mapOf(
            "DE0007164600" to quote("DE0007164600", "177.98"),
            "DE000SYNTHBD1" to quote("DE000SYNTHBD1", "98.75", perShare = false),
            "US0000000000" to quote("US0000000000", "500.00"),
        ))

        val result = QuoteRefreshService(repo, provider).refresh(accountId, TODAY) as QuoteRefreshService.Result.Refreshed

        assertEquals(1, result.repriced)
        assertEquals(4, result.carried)
        // Everything is still in the snapshot: 2.135,76 + 5.000 + 900 + 250 + 300
        assertEquals(213_576L + 500_000L + 90_000L + 25_000L + 30_000L, result.totalCents)
        assertEquals(5, repo.holdingsForValuationDate(accountId, TODAY.toEpochDay()).size)

        val live = repo.holdingsForValuationDate(accountId, TODAY.toEpochDay()).associateBy { it.name }
        assertEquals(500_000L, live.getValue("Synthetic Bund").marketValueCents, "a percent-quoted bond must never be multiplied by its quantity")
        assertEquals(90_000L, live.getValue("Dollar Position").marketValueCents)
        assertNull(live.getValue("Synthetic Bund").quoteAt)
        // A non-EUR position is never even looked up — no point sending an ISIN we cannot use.
        assertTrue("US0000000000" !in provider.asked)
        assertTrue(null !in provider.asked.map { it as String? })
    }

    @Test
    fun `refreshing twice in a day replaces the snapshot instead of piling up`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
        ))
        val service = QuoteRefreshService(repo, StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "177.98"))))
        service.refresh(accountId, TODAY)

        val later = QuoteRefreshService(repo, StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "180.00"))))
        later.refresh(accountId, TODAY)

        assertEquals(1, repo.holdingsForValuationDate(accountId, TODAY.toEpochDay()).size)
        assertEquals(216_000L, repo.holdingsForValuationDate(accountId, TODAY.toEpochDay()).single().marketValueCents)
        // One statement batch + exactly one live batch — no dead batches left behind.
        assertEquals(listOf("DEPOT", "LIVE"), repo.batches(accountId).map { it.kind }.sorted())
        assertEquals(216_000L, repo.currentBalanceCents(accountId))
    }

    @Test
    fun `a statement imported today is never overwritten by a refresh`() {
        val repo = repo()
        val accountId = repo.addAccount("Depot", AccountType.DEPOT, institution = "ING")
        repo.setLiveQuotesEnabled(accountId, true)
        val batch = repo.createBatch(accountId, NewImportBatch("DEPOT", "depot.pdf", "ING", null, null, null, TODAY, 180_000, true, 1))
        repo.storeDepotSnapshot(accountId, TODAY, batch, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
        ))
        val provider = StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "177.98")))

        assertEquals(QuoteRefreshService.Result.StatementToday, QuoteRefreshService(repo, provider).refresh(accountId, TODAY))

        // The imported statement survives untouched, and nothing was fetched.
        assertEquals(180_000L, repo.holdingsForValuationDate(accountId, TODAY.toEpochDay()).single().marketValueCents)
        assertEquals(1, repo.batches(accountId).size)
        assertEquals(emptyList(), provider.asked)
    }

    @Test
    fun `nothing is fetched unless the account opted in`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
        ))
        repo.setLiveQuotesEnabled(accountId, false)
        val provider = StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "177.98")))

        assertEquals(QuoteRefreshService.Result.Unavailable, QuoteRefreshService(repo, provider).refresh(accountId, TODAY))
        assertEquals(emptyList(), provider.asked, "an opted-out account must never reach the network")
        assertEquals(1, repo.valuationDates(accountId).size)
    }

    @Test
    fun `the default service has no provider and so never reaches the network`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
        ))
        assertEquals(QuoteRefreshService.Result.Unavailable, QuoteRefreshService(repo).refresh(accountId, TODAY))
    }

    @Test
    fun `a depot with no imported statement has nothing to reprice`() {
        val repo = repo()
        val accountId = repo.addAccount("Depot", AccountType.DEPOT, institution = "ING")
        repo.setLiveQuotesEnabled(accountId, true)
        val provider = StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "177.98")))

        assertEquals(QuoteRefreshService.Result.NoStatement, QuoteRefreshService(repo, provider).refresh(accountId, TODAY))
    }

    @Test
    fun `when no price comes back nothing is written at all`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
        ))

        // Provider is reachable but answers for nothing — e.g. offline, rate-limited, payload changed.
        assertEquals(QuoteRefreshService.Result.NoQuotes, QuoteRefreshService(repo, StubProvider(emptyMap())).refresh(accountId, TODAY))
        assertEquals(listOf(STATEMENT_DAY.toEpochDay()), repo.valuationDates(accountId))
        assertEquals(1, repo.batches(accountId).size)
    }

    @Test
    fun `a later refresh reprices from the statement, not from the previous live snapshot`() {
        val repo = repo()
        val accountId = depotWithStatement(repo, listOf(
            NewHolding("DE0007164600", null, "Blue Chip A", "12", null, 180_000, "EUR"),
        ))
        QuoteRefreshService(repo, StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "177.98"))))
            .refresh(accountId, TODAY)

        // A day later the quantity must still come from the bank's statement.
        val tomorrow = TODAY.plusDays(1)
        val result = QuoteRefreshService(repo, StubProvider(mapOf("DE0007164600" to quote("DE0007164600", "100.00"))))
            .refresh(accountId, tomorrow) as QuoteRefreshService.Result.Refreshed

        assertEquals(120_000L, result.totalCents) // 12 × 100,00 €
        assertEquals("12", repo.holdingsForValuationDate(accountId, tomorrow.toEpochDay()).single().quantity)
        assertEquals(listOf(STATEMENT_DAY.toEpochDay()), repo.statementValuationDates(accountId))
        assertEquals(setOf(TODAY.toEpochDay(), tomorrow.toEpochDay()), repo.liveValuationDates(accountId))
    }

    private companion object {
        val STATEMENT_DAY: LocalDate = LocalDate.of(2026, 7, 31)
        val TODAY: LocalDate = LocalDate.of(2026, 8, 9)
        const val ASOF = 1_786_132_583_000L
    }
}
