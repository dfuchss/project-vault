package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.ParsedTransaction
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.imports.StatementTemplate
import org.fuchss.projectvault.imports.parse.GermanFormats
import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.imports.pdf.PdfLine
import java.time.LocalDate
import kotlin.math.abs

/**
 * DKB Girokonto "Kontoauszug".
 *
 * Column layout: date ~x70, "Erläuterung" ~x123, "Betrag Soll EUR" (debit) and "Betrag Haben EUR"
 * (credit) headers to the right. Debit vs. credit is decided by whether an amount's centre sits left
 * or right of the divider between the Soll/Haben header words. A transaction starts on a line whose
 * date-column token begins with a date; indented lines beneath it are merchant/purpose detail.
 *
 * Real statements glue the date, Erläuterung and value date into one token, e.g.
 * `15.07.2026Echtzeitüberweisung / Wert: 14.07.2026`, so we split the date off the front and read the
 * "Wert:" value date. Occasionally an amount renders on its own line just above its date line; that
 * orphan amount is carried to the next transaction.
 */
class DkbGiroTemplate : StatementTemplate {
    override val id = "dkb-giro"
    override val kind = StatementKind.GIRO

    private val datePrefix = Regex("""^(\d{2}\.\d{2}\.\d{4})(.*)$""")
    private val wert = Regex("""Wert:\s*(\d{2}\.\d{2}\.\d{4})""")
    private val wertClause = Regex("""/?\s*Wert:\s*\d{2}\.\d{2}\.\d{4}""")
    private val datum = Regex("""DATUM\s*(\d{2}\.\d{2}\.\d{4})""")
    private val isoDate = Regex("""(\d{4}-\d{2}-\d{2})T""")

    // Matches any DKB deposit "Kontoauszug" (Girokonto, Tagesgeld, Festgeld, …) — they share the
    // same Soll/Haben layout. The credit-card statement uses "Betrag in EUR" instead, so it won't
    // match here; the Depotauszug is handled by a separate importer.
    override fun matches(doc: PdfDocument): Boolean {
        val t = doc.text
        return t.contains("Deutsche Kreditbank") && t.contains("Kontostand am") &&
            t.contains("Betrag Soll") && t.contains("Betrag Haben")
    }

    override fun parse(doc: PdfDocument): ParsedStatement {
        val lines = doc.lines
        val header = lines.firstOrNull { it.text.contains("Betrag Soll") && it.text.contains("Betrag Haben") }
            ?: error("DKB giro: could not locate the Soll/Haben header row")
        val sollX = header.tokens.first { it.text.contains("Soll") }.x
        val habenX = header.tokens.first { it.text.contains("Haben") }.x
        val divider = (sollX + habenX) / 2f
        val descX = header.tokens.firstOrNull { it.text.startsWith("Erl") }?.x ?: 123f

        val fullText = doc.text.replace("\n", " ")
        val statementNumber = Regex("""Kontoauszug\s+(\d+/\d{4})""").find(fullText)?.groupValues?.get(1)
        val accountMatch = Regex("""(?:Girokonto|Tagesgeld|Festgeld|Geldmarktkonto)\s+(\S+),\s*(DE[0-9 ]{6,})""").find(fullText)
        val accountRef = accountMatch?.groupValues?.get(1)
        val iban = accountMatch?.groupValues?.get(2)?.replace(" ", "")?.take(22)

        fun amountToken(line: PdfLine) =
            line.tokens.filter { GermanFormats.isAmount(it.text) }.maxByOrNull { it.x }

        fun signedAmount(text: String, x: Float, width: Float): Long {
            val magnitude = abs(GermanFormats.amountToCents(text))
            return if (x + width / 2f < divider) -magnitude else magnitude
        }

        // Description-column text (excludes the date-column token and amount tokens).
        fun descriptionColumn(line: PdfLine): String =
            line.tokens
                .filter { it.x >= descX - 15f && !GermanFormats.isAmount(it.text) }
                .joinToString(" ") { it.text }.trim()

        var openingCents: Long? = null
        var closingCents: Long? = null
        var periodStart: LocalDate? = null
        var periodEnd: LocalDate? = null
        val transactions = mutableListOf<MutableGiroTx>()
        var pendingAmount: Long? = null // amount that rendered on its own line, for the next tx

        for (line in lines) {
            val text = line.text
            if (openingCents == null && Regex("""Kontostand am .*Auszug Nr""").containsMatchIn(text)) {
                amountToken(line)?.let { openingCents = signedAmount(it.text, it.x, it.width) }
                periodStart = GermanFormats.findDate(text)
                continue
            }
            if (Regex("""Kontostand am .*Uhr""").containsMatchIn(text)) {
                amountToken(line)?.let { closingCents = signedAmount(it.text, it.x, it.width) }
                periodEnd = GermanFormats.findDate(text)
                break
            }

            val amount = amountToken(line)
            val dateColTok = line.tokens.firstOrNull { it.x < descX - 10f && datePrefix.matches(it.text) }

            when {
                dateColTok != null -> {
                    val match = datePrefix.find(dateColTok.text)!!
                    val bookingDate = GermanFormats.findDate(match.groupValues[1])!!
                    // Combine the text after the date with any other description-column tokens on the
                    // line, since "/ Wert: <date>" is sometimes merged into the date token and
                    // sometimes a separate token.
                    val firstLine = listOf(match.groupValues[2], descriptionColumn(line))
                        .filter { it.isNotBlank() }.joinToString(" ")
                    val valueDate = wert.find(firstLine)?.let { GermanFormats.findDate(it.groupValues[1]) }
                    val bookingType = firstLine.replace(wertClause, "").trim().trim('/').trim()
                    val cents = amount?.let { signedAmount(it.text, it.x, it.width) } ?: pendingAmount
                    pendingAmount = null
                    if (cents != null) {
                        transactions += MutableGiroTx(bookingDate, cents, bookingType, valueDate)
                    }
                }
                amount != null && descriptionColumn(line).isEmpty() -> {
                    // Amount rendered on its own line — belongs to the next (date) line.
                    pendingAmount = signedAmount(amount.text, amount.x, amount.width)
                }
                transactions.isNotEmpty() -> {
                    descriptionColumn(line).takeIf { it.isNotEmpty() }?.let { transactions.last().detail += it }
                }
            }
        }

        return ParsedStatement(
            templateId = id,
            kind = StatementKind.GIRO,
            institution = "DKB",
            accountRef = accountRef,
            iban = iban,
            statementNumber = statementNumber,
            periodStart = periodStart,
            periodEnd = periodEnd,
            currency = "EUR",
            openingBalanceCents = openingCents,
            closingBalanceCents = closingCents,
            transactions = transactions.map { it.toParsed() },
        )
    }

    private inner class MutableGiroTx(
        val bookingDate: LocalDate,
        val amountCents: Long,
        val bookingType: String,
        private val wertDate: LocalDate?,
    ) {
        val detail = mutableListOf<String>()

        fun toParsed(): ParsedTransaction {
            val purpose = (listOf(bookingType) + detail).filter { it.isNotBlank() }.joinToString(" | ")
            // Original transaction date for de-dup/provenance: the "Wert:" value date if present,
            // else a DATUM/ISO timestamp date from the detail lines (card auth / transfer date).
            val valueDate = wertDate ?: detail.firstNotNullOfOrNull { originDate(it) }
            return ParsedTransaction(
                bookingDate = bookingDate,
                valueDate = valueDate,
                amountCents = amountCents,
                counterparty = detail.firstOrNull()?.let(::cleanCounterparty)?.ifBlank { null },
                purpose = purpose,
                rawText = purpose,
                bookingType = bookingType.ifBlank { null },
            )
        }

        private fun originDate(detailLine: String): LocalDate? =
            isoDate.find(detailLine)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }
                ?: datum.find(detailLine)?.let { GermanFormats.findDate(it.groupValues[1]) }

        /** Strips trailing "DATUM …, … UHR" and card-auth ISO timestamps from a counterparty line. */
        private fun cleanCounterparty(line: String): String =
            line.substringBefore(" DATUM ")
                .replace(Regex(""" \d{4}-\d{2}-\d{2}T.*$"""), "")
                .trim()
    }
}
