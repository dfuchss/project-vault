package org.fuchss.projectvault.quotes

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payload-contract checks for the Börse Frankfurt `quote_box/single` response, run offline against
 * captured responses — no network in tests. If the endpoint ever changes shape, these fail loudly
 * instead of the app silently mispricing a portfolio.
 *
 * Fixtures use the largest DAX constituents purely as public, well-known examples — never anything
 * from a real vault (see the "never commit real financial data" convention).
 */
class QuoteBoxParserTest {
    // Captured from api.boerse-frankfurt.de — SAP SE, XFRA.
    private val stock = """
        {"askLimit":0,"askSize":0,"bidLimit":0,"bidSize":0,"changeToPrevDayAbsolute":5.9400,
         "changeToPrevDayInPercent":3.4500,"instrumentStatus":"Active","isin":"DE0007164600",
         "lastPrice":177.98,"lastPriceIndicator":null,"nominal":false,"open":172.44,
         "timestamp":"2026-08-07T20:00:00Z","timestampLastPrice":"2026-08-07T19:56:23Z"}
    """.trimIndent()

    // Captured from api.boerse-frankfurt.de — Siemens AG, XETR.
    private val secondStock = """
        {"changeToPrevDayAbsolute":6.7000,"changeToPrevDayInPercent":2.4500,
         "instrumentStatus":"Active","isin":"DE0007236101","lastPrice":279.55,
         "lastPriceIndicator":"R","nominal":false,"open":273.95,"spreadAbsolute":0,
         "timestamp":"2026-08-07T20:00:00Z","timestampLastPrice":"2026-08-07T18:34:46Z",
         "tradingStatus":"Retail Early/Late"}
    """.trimIndent()

    @Test
    fun `parses a per-share stock quote`() {
        val quote = parseQuoteBox("DE0007164600", "XFRA", stock)!!
        assertEquals(BigDecimal("177.98"), quote.price)
        assertTrue(quote.perShare)
        assertEquals("XFRA", quote.venue)
        assertEquals("DE0007164600", quote.isin)
    }

    @Test
    fun `parses a quote carrying the optional trading-status fields`() {
        val quote = parseQuoteBox("DE0007236101", "XETR", secondStock)!!
        assertEquals(BigDecimal("279.55"), quote.price)
        assertTrue(quote.perShare)
        assertEquals("XETR", quote.venue)
    }

    @Test
    fun `an ETF is per-share too, so it gets a live price`() {
        // Same payload shape, synthetic ISIN — funds quote per share exactly like equities.
        val etf = secondStock.replace("DE0007236101", "IE00SYNTHET1")
        assertTrue(parseQuoteBox("IE00SYNTHET1", "XETR", etf)!!.perShare)
    }

    @Test
    fun `a percent-quoted bond is flagged as not per-share`() {
        val bond = stock.replace(""""nominal":false""", """"nominal":true""")
        assertEquals(false, parseQuoteBox("DE000SYNTHBD1", "XFRA", bond)!!.perShare)
    }

    @Test
    fun `an inactive instrument yields no quote`() {
        val suspended = stock.replace(""""instrumentStatus":"Active"""", """"instrumentStatus":"Suspended"""")
        assertNull(parseQuoteBox("DE0007164600", "XFRA", suspended))
    }

    @Test
    fun `a missing or null price yields no quote instead of zero`() {
        assertNull(parseQuoteBox("DE0007164600", "XFRA", stock.replace(""""lastPrice":177.98""", """"lastPrice":null""")))
        assertNull(parseQuoteBox("DE0007164600", "XFRA", """{"instrumentStatus":"Active","nominal":false}"""))
        assertNull(parseQuoteBox("DE0007164600", "XFRA", stock.replace(""""lastPrice":177.98""", """"lastPrice":0""")))
    }

    @Test
    fun `malformed or unexpected payloads never throw`() {
        assertNull(parseQuoteBox("DE0007164600", "XFRA", ""))
        assertNull(parseQuoteBox("DE0007164600", "XFRA", "<html>rate limited</html>"))
        assertNull(parseQuoteBox("DE0007164600", "XFRA", "[]"))
        assertNull(parseQuoteBox("DE0007164600", "XFRA", """{"lastPrice":{"nested":1},"instrumentStatus":"Active"}"""))
    }

    @Test
    fun `quote time comes from the last trade, falling back to the box timestamp`() {
        // 2026-08-07T19:56:23Z
        assertEquals(1_786_132_583_000L, parseQuoteBox("DE0007164600", "XFRA", stock)!!.asOf)
        val noTradeTime = stock.replace(""""timestampLastPrice":"2026-08-07T19:56:23Z"""", """"timestampLastPrice":null""")
        // falls back to "timestamp": 2026-08-07T20:00:00Z
        assertEquals(1_786_132_800_000L, parseQuoteBox("DE0007164600", "XFRA", noTradeTime)!!.asOf)
    }
}
