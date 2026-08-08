package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.ParsedTransaction
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.imports.csv.CsvDocument
import org.fuchss.projectvault.imports.csv.CsvStatementTemplate
import org.fuchss.projectvault.imports.csv.balanceToCents
import org.fuchss.projectvault.imports.parse.GermanFormats
import org.fuchss.projectvault.model.Bank

/**
 * DKB Visa credit-card "Umsatzliste" CSV export.
 *
 * Layout: `"Karte";"Visa Kreditkarte";"4930 … 6857"`, a `Saldo vom …` line, then the column header
 * `Belegdatum;Wertstellung;Status;Beschreibung;Umsatztyp;Betrag (€);Fremdwährungsbetrag` and rows.
 * Amounts are already signed (`-` charge, `+` credit/payment).
 *
 * Date mapping matches [DkbCreditCardTemplate] so CSV and the later PDF de-duplicate: the PDF's
 * booking date is the *Buchung* (settlement) date and its value date is the *Beleg* (purchase) date.
 * In this CSV those are the `Wertstellung` and `Belegdatum` columns respectively.
 */
class DkbCsvCreditCardTemplate : CsvStatementTemplate {
    override val id = "dkb-csv-visa"
    override val bank = Bank.DKB
    override val kind = StatementKind.CREDIT_CARD

    override fun matches(doc: CsvDocument): Boolean {
        val kind = doc.cell(0, 0)
        val header = doc.headerRow("Belegdatum")
        return (kind == "Karte" || kind == "Kreditkarte") && header >= 0 &&
            doc.rows[header].any { it.trim() == "Beschreibung" } &&
            doc.rows[header].any { it.trim() == "Betrag (€)" }
    }

    override fun parse(doc: CsvDocument): ParsedStatement {
        val cardRef = doc.cell(0, 2).ifBlank { doc.cell(0, 1) }.ifBlank { null }

        val balanceRow = doc.rows.firstOrNull { it.firstOrNull()?.trim()?.startsWith("Saldo vom") == true }
        val closingCents = balanceRow?.getOrNull(1)?.let { balanceToCents(it) }
        val periodEnd = balanceRow?.firstOrNull()?.let { GermanFormats.findDate(it) }

        val headerIdx = doc.headerRow("Belegdatum")
        val transactions = doc.rows.drop(headerIdx + 1).mapNotNull { row ->
            val beleg = GermanFormats.findDate(row.getOrNull(0).orEmpty()) ?: return@mapNotNull null
            if (!row.getOrNull(2).orEmpty().equals("Gebucht", ignoreCase = true)) return@mapNotNull null
            val buchung = GermanFormats.findDate(row.getOrNull(1).orEmpty())
            val description = row.getOrNull(3).orEmpty().trim()
            val umsatztyp = row.getOrNull(4).orEmpty().trim()
            val amountCents = GermanFormats.amountToCents(row.getOrNull(5).orEmpty())

            ParsedTransaction(
                bookingDate = buchung ?: beleg, // settlement date — matches the PDF's booking date
                valueDate = beleg,              // purchase date — matches the PDF's value date
                amountCents = amountCents,
                counterparty = description.ifBlank { null },
                purpose = description,
                rawText = description,
                bookingType = umsatztyp.ifBlank { null },
            )
        }

        return ParsedStatement(
            templateId = id,
            kind = StatementKind.CREDIT_CARD,
            institution = "DKB",
            accountRef = cardRef,
            iban = null,
            statementNumber = null,
            periodStart = null,
            periodEnd = periodEnd,
            currency = "EUR",
            openingBalanceCents = null,
            closingBalanceCents = closingCents,
            transactions = transactions,
        )
    }
}
