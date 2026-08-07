package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.DepotPosition
import org.fuchss.projectvault.imports.DepotStatementTemplate
import org.fuchss.projectvault.imports.ParsedDepotStatement
import org.fuchss.projectvault.imports.parse.GermanFormats
import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.imports.pdf.PdfLine
import java.math.BigDecimal

/**
 * ING-DiBa "Direkt-Depot" Depotauszug (securities holdings snapshot).
 *
 * Layout (from a real statement): "Stücke/Nominale" ~x67, "Wertpapier-Informationen" ~x180,
 * "Kurs" ~x414, "Kurswert" ~x506. Each position starts on a line carrying the quantity (~x67), the
 * unit "Stück"/"Nominale", the security name (~x180), the Kurs (price, may have >2 decimals) and the
 * Kurswert (market value); the following indented lines add the name tail, "ISIN (WKN): ...",
 * "Verwahrart", "Lagerland". The footer line has "Anzahl Posten: N" and "Gesamtkurswert <total>".
 */
class IngDepotTemplate : DepotStatementTemplate {
    override val id = "ing-depot"

    override fun matches(doc: PdfDocument): Boolean {
        val t = doc.text
        return t.contains("ING-DiBa") && t.contains("Depotauszug")
    }

    override fun parse(doc: PdfDocument): ParsedDepotStatement {
        val lines = doc.lines
        val fullText = doc.text.replace("\n", " ")

        val valuationDate = Regex("""Depotauszug per (\d{2}\.\d{2}\.\d{4})""").find(fullText)
            ?.let { GermanFormats.findDate(it.groupValues[1]) }
        val depotNumber = Regex("""Direkt-Depot Nr\.:\s*(\S+)""").find(fullText)?.groupValues?.get(1)

        val positions = mutableListOf<DepotPosition>()
        var current: MutableDepotPosition? = null
        var totalCents: Long? = null
        var statedCount: Int? = null
        var inSection = false

        fun flush() {
            current?.let { positions.add(it.build()) }
            current = null
        }

        fun nameOf(line: PdfLine): String =
            line.tokens
                .filter { it.x in 165f..360f && it.text != "Stück" && it.text != "Nominale" }
                .joinToString(" ") { it.text }.trim()

        for (line in lines) {
            val text = line.text
            if (!inSection) {
                if (text.contains("Stücke/Nominale")) inSection = true
                continue
            }
            if (text.contains("Gesamtkurswert") || text.contains("Anzahl Posten")) {
                flush()
                line.tokens.filter { GermanFormats.isCurrencyAmount(it.text) }.maxByOrNull { it.x }
                    ?.let { totalCents = GermanFormats.currencyAmountToCents(it.text) }
                statedCount = Regex("""Anzahl Posten:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
                break
            }

            val amounts = line.tokens.filter { GermanFormats.isCurrencyAmount(it.text) }
            val quantity = line.tokens.firstOrNull()
                ?.takeIf { it.x < 150f }
                ?.let { runCatching { GermanFormats.decimal(it.text) }.getOrNull() }

            if (quantity != null && amounts.isNotEmpty() && (text.contains("Stück") || text.contains("Nominale"))) {
                flush()
                val kurswert = amounts.maxByOrNull { it.x }!!
                val kurs = amounts.filter { it !== kurswert }.maxByOrNull { it.x }
                current = MutableDepotPosition(
                    quantity = quantity,
                    marketValueCents = GermanFormats.currencyAmountToCents(kurswert.text),
                    priceText = kurs?.text,
                ).apply { appendName(nameOf(line)) }
            } else current?.let { pos ->
                val isin = Regex("""ISIN\s*\(WKN\):\s*([A-Z0-9]+)\s*\(([A-Z0-9]+)\)""").find(text)
                when {
                    isin != null -> {
                        pos.isin = isin.groupValues[1]
                        pos.wkn = isin.groupValues[2]
                    }
                    text.startsWith("Verwahrart") || text.startsWith("Lagerland") -> Unit
                    else -> pos.appendName(nameOf(line))
                }
            }
        }
        flush()

        return ParsedDepotStatement(
            templateId = id,
            institution = "ING",
            depotNumber = depotNumber,
            valuationDate = valuationDate,
            currency = "EUR",
            positions = positions,
            totalValueCents = totalCents,
            statedPositionCount = statedCount,
        )
    }

    private class MutableDepotPosition(
        val quantity: BigDecimal,
        val marketValueCents: Long,
        val priceText: String?,
    ) {
        private val nameParts = mutableListOf<String>()
        var isin: String? = null
        var wkn: String? = null

        fun appendName(part: String) {
            if (part.isNotBlank()) nameParts.add(part)
        }

        fun build() = DepotPosition(
            name = nameParts.joinToString(" ").trim(),
            isin = isin,
            wkn = wkn,
            quantity = quantity,
            priceText = priceText,
            marketValueCents = marketValueCents,
            currency = "EUR",
        )
    }
}
