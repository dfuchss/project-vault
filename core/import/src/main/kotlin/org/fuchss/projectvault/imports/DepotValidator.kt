package org.fuchss.projectvault.imports

/**
 * Safety net for Depot statements: Σ(position market values) must equal the stated Gesamtkurswert,
 * and the number of parsed positions must match the stated "Anzahl Posten".
 */
object DepotValidator {
    fun check(statement: ParsedDepotStatement): DepotCheck {
        val sum = statement.positions.sumOf { it.marketValueCents }
        val total = statement.totalValueCents
        val difference = total?.let { sum - it }
        val countOk = statement.statedPositionCount?.let { it == statement.positions.size } ?: true
        return DepotCheck(
            sumOfPositionsCents = sum,
            totalValueCents = total,
            differenceCents = difference,
            positionCountOk = countOk,
            ok = difference == 0L && countOk,
        )
    }
}
