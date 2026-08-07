package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.pdf.PdfDocument
import java.math.BigDecimal
import java.time.LocalDate

/** One securities holding on a Depot statement (a point-in-time position, not a transaction). */
data class DepotPosition(
    val name: String,
    val isin: String?,
    val wkn: String?,
    val quantity: BigDecimal,
    val priceText: String?,       // display price, may carry >2 decimals (e.g. "109,2466 EUR")
    val marketValueCents: Long,   // "Kurswert" in the statement currency
    val currency: String,
)

/** A parsed Depot "Depotauszug": the holdings snapshot for one securities account. */
data class ParsedDepotStatement(
    val templateId: String,
    val institution: String,
    val depotNumber: String?,
    val valuationDate: LocalDate?,   // "Depotauszug per <date>"
    val currency: String,
    val positions: List<DepotPosition>,
    val totalValueCents: Long?,      // "Gesamtkurswert"
    val statedPositionCount: Int?,   // "Anzahl Posten: N"
)

/**
 * Integrity check for a Depot statement, analogous to the transaction balance check: the sum of the
 * positions' market values must equal the stated total, and the count must match. A failure means
 * the parse is untrustworthy and the import must go to manual review.
 */
data class DepotCheck(
    val sumOfPositionsCents: Long,
    val totalValueCents: Long?,
    val differenceCents: Long?,
    val positionCountOk: Boolean,
    val ok: Boolean,
) {
    override fun toString(): String =
        "DepotCheck(ok=$ok, differenceCents=$differenceCents, positionCountOk=$positionCountOk)"
}

data class DepotImportResult(
    val statement: ParsedDepotStatement,
    val check: DepotCheck,
)

/** A per-institution parser for Depot statements. */
interface DepotStatementTemplate {
    val id: String
    fun matches(doc: PdfDocument): Boolean
    fun parse(doc: PdfDocument): ParsedDepotStatement
}
