package org.fuchss.projectvault.quotes

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns a held quantity and a unit price into a position value in integer cents.
 *
 * Pure arithmetic, no I/O — the part of live pricing that must be exactly right. Everything runs
 * through [BigDecimal]; a `Double` anywhere here would drift on fractional-share positions.
 */
object Repricing {
    /**
     * `quantity × price`, rounded half-up to cents. Returns `null` if [quantityText] is not a number
     * (a malformed row is skipped rather than valued at zero).
     */
    fun repricedCents(quantityText: String, price: BigDecimal): Long? {
        val quantity = parseQuantity(quantityText) ?: return null
        return quantity.multiply(price)
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .toLong()
    }

    /**
     * Reads a stored holding quantity. Imports write `BigDecimal.toPlainString()` (dot-decimal), but
     * this stays lenient about German notation so a hand-edited or future-template value still
     * parses: `"1.234,5678"` → `1234.5678`, `"12,5"` → `12.5`, `"30.5"` → `30.5`.
     */
    fun parseQuantity(text: String): BigDecimal? {
        val cleaned = text.replace(" ", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        val normalized = when {
            // German: dots group thousands, the comma is the decimal separator.
            cleaned.contains(',') && cleaned.contains('.') -> cleaned.replace(".", "").replace(',', '.')
            cleaned.contains(',') -> cleaned.replace(',', '.')
            else -> cleaned
        }
        return runCatching { BigDecimal(normalized) }.getOrNull()
    }
}
