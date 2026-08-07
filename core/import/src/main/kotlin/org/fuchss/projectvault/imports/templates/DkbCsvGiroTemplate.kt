package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.ParsedTransaction
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.imports.csv.CsvDocument
import org.fuchss.projectvault.imports.csv.CsvStatementTemplate
import org.fuchss.projectvault.imports.csv.balanceToCents
import org.fuchss.projectvault.imports.parse.GermanFormats

/**
 * DKB "Umsatzliste" CSV export for a Girokonto / Tagesgeld / Festgeld account.
 *
 * Layout: a header block (`"Girokonto";"DE…"`, a `Kontostand vom …` line), a blank line, then the
 * column header `Buchungsdatum;Wertstellung;Status;Zahlungspflichtige*r;Zahlungsempfänger*in;
 * Verwendungszweck;Umsatztyp;IBAN;Betrag (€);…` and the rows. Only booked ("Gebucht") rows are
 * imported; pending ones are provisional and would break de-dup once they book.
 *
 * The counterparty is the *other* party: the payee on an outgoing entry, the payer on an incoming
 * one. Booking date and signed amount are chosen to match [DkbGiroTemplate] exactly, so a CSV
 * imported now and the bank's PDF imported later de-duplicate against each other.
 */
class DkbCsvGiroTemplate : CsvStatementTemplate {
    override val id = "dkb-csv-giro"
    override val kind = StatementKind.GIRO

    private val accountTypes = setOf("Girokonto", "Tagesgeld", "Festgeld", "Geldmarktkonto")

    override fun matches(doc: CsvDocument): Boolean {
        val type = doc.cell(0, 0)
        val header = doc.headerRow("Buchungsdatum")
        return type in accountTypes && header >= 0 &&
            doc.rows[header].any { it.trim() == "Betrag (€)" } &&
            doc.rows[header].any { it.trim() == "Zahlungsempfänger*in" }
    }

    override fun parse(doc: CsvDocument): ParsedStatement {
        val accountRef = doc.cell(0, 0).ifBlank { null }
        val iban = doc.cell(0, 1).replace(" ", "").ifBlank { null }

        val balanceRow = doc.rows.firstOrNull { it.firstOrNull()?.trim()?.startsWith("Kontostand vom") == true }
        val closingCents = balanceRow?.getOrNull(1)?.let { balanceToCents(it) }
        val periodEnd = balanceRow?.firstOrNull()?.let { GermanFormats.findDate(it) }

        val headerIdx = doc.headerRow("Buchungsdatum")
        val transactions = doc.rows.drop(headerIdx + 1).mapNotNull { row ->
            val bookingDate = GermanFormats.findDate(row.getOrNull(0).orEmpty()) ?: return@mapNotNull null
            if (!row.getOrNull(2).orEmpty().equals("Gebucht", ignoreCase = true)) return@mapNotNull null
            val valueDate = GermanFormats.findDate(row.getOrNull(1).orEmpty())
            val payer = row.getOrNull(3).orEmpty().trim()
            val payee = row.getOrNull(4).orEmpty().trim()
            val purpose = row.getOrNull(5).orEmpty().trim()
            val direction = row.getOrNull(6).orEmpty().trim()
            val amountCents = GermanFormats.amountToCents(row.getOrNull(8).orEmpty())

            val incoming = direction.equals("Eingang", ignoreCase = true)
            val counterparty = (if (incoming) payer else payee).ifBlank { if (incoming) payee else payer }

            ParsedTransaction(
                bookingDate = bookingDate,
                valueDate = valueDate,
                amountCents = amountCents,
                counterparty = counterparty.ifBlank { null },
                purpose = purpose,
                rawText = listOf(counterparty, purpose).filter { it.isNotBlank() }.joinToString(" | "),
                bookingType = direction.ifBlank { null },
            )
        }

        return ParsedStatement(
            templateId = id,
            kind = StatementKind.GIRO,
            institution = "DKB",
            accountRef = accountRef,
            iban = iban,
            statementNumber = null,
            periodStart = null,
            periodEnd = periodEnd,
            currency = "EUR",
            openingBalanceCents = null, // CSV exports carry only the current balance, not an opening one
            closingBalanceCents = closingCents,
            transactions = transactions,
        )
    }
}
