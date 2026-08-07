package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.DepotPosition
import org.fuchss.projectvault.imports.ParsedDepotStatement
import org.fuchss.projectvault.imports.csv.CsvDepotTemplate
import org.fuchss.projectvault.imports.csv.CsvDocument
import org.fuchss.projectvault.imports.parse.GermanFormats

/**
 * ING "Depotübersicht" CSV export (latin-1). A holdings snapshot, not transactions.
 *
 * Columns: ISIN; Wertpapiername; Stück/Nominale; Einheit; Einstandskurs; Währung; Einstandswert;
 * Währung; Bewertungskurs; Währung; Zeit; Handelsplatz; Kurswert; Währung; Gewinn/Verlust; …
 * The final "Depot-Gesamtwert" row carries the total in the Kurswert column, which the
 * DepotValidator reconciles against the sum of the positions' market values.
 */
class IngCsvDepotTemplate : CsvDepotTemplate {
    override val id = "ing-csv-depot"

    private val isin = Regex("""^[A-Z]{2}[A-Z0-9]{9}[0-9]$""")

    // Column indices in a Depotübersicht row.
    private companion object {
        const val ISIN = 0; const val NAME = 1; const val QTY = 2
        const val VAL_PRICE = 8; const val VAL_PRICE_CUR = 9
        const val MARKET_VALUE = 12; const val MARKET_VALUE_CUR = 13
        const val TOTAL_LABEL = 5
    }

    override fun matches(doc: CsvDocument): Boolean {
        val header = doc.headerRow("ISIN")
        return doc.cell(0, 0).startsWith("Depot", ignoreCase = true) && header >= 0 &&
            doc.rows[header].any { it.trim() == "Kurswert" }
    }

    override fun parse(doc: CsvDocument): ParsedDepotStatement {
        val valuationDate = GermanFormats.findDate(doc.cell(0, 0))
        val depotNumber = doc.rows
            .firstOrNull { it.firstOrNull()?.trim() == "Depotnummer" }
            ?.getOrNull(1)?.trim()?.ifBlank { null }

        val headerIdx = doc.headerRow("ISIN")
        val dataRows = doc.rows.drop(headerIdx + 1)

        val totalRow = dataRows.firstOrNull { it.getOrNull(TOTAL_LABEL)?.trim()?.contains("Gesamtwert") == true }
        val totalValueCents = totalRow?.getOrNull(MARKET_VALUE)?.let { GermanFormats.amountToCents(it) }

        val positions = dataRows
            .filter { isin.matches(it.getOrNull(ISIN)?.trim().orEmpty()) }
            .map { row ->
                DepotPosition(
                    name = row.getOrNull(NAME).orEmpty().trim(),
                    isin = row.getOrNull(ISIN)?.trim(),
                    wkn = null,
                    quantity = GermanFormats.decimal(row.getOrNull(QTY).orEmpty()),
                    priceText = listOf(row.getOrNull(VAL_PRICE).orEmpty().trim(), row.getOrNull(VAL_PRICE_CUR).orEmpty().trim())
                        .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
                    marketValueCents = GermanFormats.amountToCents(row.getOrNull(MARKET_VALUE).orEmpty()),
                    currency = row.getOrNull(MARKET_VALUE_CUR).orEmpty().trim().ifBlank { "EUR" },
                )
            }

        return ParsedDepotStatement(
            templateId = id,
            institution = "ING",
            depotNumber = depotNumber,
            valuationDate = valuationDate,
            currency = "EUR",
            positions = positions,
            totalValueCents = totalValueCents,
            statedPositionCount = null, // the export lists no explicit count
        )
    }
}
