package org.fuchss.projectvault.quotes

import java.math.BigDecimal

/**
 * A current price for one security, as reported by a trading venue.
 *
 * [price] is kept as [BigDecimal] — quotes carry more than two decimals and money must never round
 * through a `Double` (see the money convention in CLAUDE.md).
 */
data class Quote(
    val isin: String,
    val price: BigDecimal,
    /**
     * True when the venue quotes an absolute amount **per share** (Aktien, ETFs), so a position is
     * worth `quantity × price`.
     *
     * False for percent-of-par quotation (bonds, Nominale): there the price is a percentage and
     * multiplying it by a quantity yields a meaningless number, so such positions are never
     * repriced — they keep their statement value.
     */
    val perShare: Boolean,
    /** Market Identifier Code of the venue the price came from, e.g. `XETR`. */
    val venue: String,
    /** Epoch millis of the trade this price refers to. */
    val asOf: Long,
)
