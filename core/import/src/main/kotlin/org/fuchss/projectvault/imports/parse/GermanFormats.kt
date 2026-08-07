package org.fuchss.projectvault.imports.parse

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private val CURRENCY_SUFFIX = Regex("""\s*(EUR|USD|CHF|GBP|CAD)\s*$""")

/** Parsing helpers for German-formatted money and dates found in bank statements. */
object GermanFormats {

    // Two-decimal money, optional thousands dots, optional leading sign: 1.234,56 / -22,76 / 300,00
    private val AMOUNT = Regex("""^[+-]?\d{1,3}(\.\d{3})*,\d{2}$|^[+-]?\d+,\d{2}$""")

    // dd.mm.yyyy or dd.mm.yy anywhere in the text.
    private val DATE = Regex("""(\d{2})\.(\d{2})\.(\d{4}|\d{2})""")

    /** True if [text] looks like a two-decimal money amount (excludes 4–6 digit FX rates like 1,1402). */
    fun isAmount(text: String): Boolean {
        val t = text.trim().trimEnd('+', '-').trim()
        return AMOUNT.matches(t)
    }

    /**
     * Parses a German money string to signed minor units (cents). Handles a leading sign or a
     * trailing sign (`1.721,97 -`), the latter used on DKB credit-card statements.
     */
    fun amountToCents(raw: String): Long {
        var s = raw.trim()
        var negative = false
        when {
            s.endsWith("-") -> { negative = true; s = s.dropLast(1).trim() }
            s.endsWith("+") -> { s = s.dropLast(1).trim() }
        }
        when {
            s.startsWith("-") -> { negative = true; s = s.drop(1).trim() }
            s.startsWith("+") -> { s = s.drop(1).trim() }
        }
        s = s.replace(".", "").replace(",", ".").replace(" ", "")
        val cents = BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        return if (negative) -cents else cents
    }

    /** True if [text] is a money amount carrying a currency suffix, e.g. "1.056,58 EUR". */
    fun isCurrencyAmount(text: String): Boolean {
        val t = text.trim()
        return CURRENCY_SUFFIX.containsMatchIn(t) && t.any(Char::isDigit) && t.contains(',')
    }

    /** Parses a two-decimal money amount with a currency suffix ("1.056,58 EUR") to cents. */
    fun currencyAmountToCents(text: String): Long = amountToCents(text.trim().replace(CURRENCY_SUFFIX, ""))

    /** Parses a German-formatted decimal (thousands '.', decimal ',') to a [BigDecimal]. */
    fun decimal(text: String): BigDecimal =
        BigDecimal(text.trim().replace(CURRENCY_SUFFIX, "").replace(".", "").replace(",", ".").replace(" ", ""))

    /** Finds the first dd.mm.yyyy / dd.mm.yy date in [text], or null. Two-digit years map to 2000+. */
    fun findDate(text: String): LocalDate? {
        val m = DATE.find(text) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val yRaw = m.groupValues[3]
        val year = if (yRaw.length == 2) 2000 + yRaw.toInt() else yRaw.toInt()
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
}
