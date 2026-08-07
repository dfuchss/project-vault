package org.fuchss.projectvault.imports

/**
 * The safety net for statement parsing: a statement prints an opening and a closing balance, so
 * opening + Σ(transactions) must equal closing. A mismatch means we misparsed something and the
 * import must be flagged for manual review rather than silently committed.
 */
object BalanceValidator {
    fun check(statement: ParsedStatement): BalanceCheck {
        val sum = statement.transactions.sumOf { it.amountCents }
        val opening = statement.openingBalanceCents
        val closing = statement.closingBalanceCents
        val computed = opening?.plus(sum)
        val difference = if (computed != null && closing != null) computed - closing else null
        return BalanceCheck(
            openingCents = opening,
            closingCents = closing,
            sumCents = sum,
            computedClosingCents = computed,
            differenceCents = difference,
            ok = difference == 0L,
        )
    }
}
