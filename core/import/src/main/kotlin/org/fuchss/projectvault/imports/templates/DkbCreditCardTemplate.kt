package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.ParsedTransaction
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.imports.StatementTemplate
import org.fuchss.projectvault.imports.parse.GermanFormats
import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.imports.pdf.PdfLine
import org.fuchss.projectvault.imports.pdf.PdfToken
import java.time.LocalDate
import kotlin.math.abs

/**
 * DKB-VISA-Card "Kreditkartenabrechnung".
 *
 * Layout (from real statements): Beleg date ~x43, Buchung date ~x82, merchant/purpose ~x125,
 * optional foreign-currency block (Währung ~x300, foreign amount ~x400, Kurs ~x480), the EUR
 * amount right-aligned ~x535–555, and a **separate trailing sign token** `+`/`-` ~x573.
 * `-` is a charge (money out → negative), `+` is a credit/payment (positive). The EUR amount is
 * the right-most money token before the sign (so FX amount/rate to its left are ignored).
 * The opening balance is the "Saldo letzte Abrechnung" row; the closing is "Neuer Saldo".
 */
class DkbCreditCardTemplate : StatementTemplate {
    override val id = "dkb-visa"
    override val kind = StatementKind.CREDIT_CARD

    override fun matches(doc: PdfDocument): Boolean {
        val t = doc.text
        return t.contains("DKB-VISA-Card") || (t.contains("Abrechnung vom") && t.contains("Neuer Saldo"))
    }

    override fun parse(doc: PdfDocument): ParsedStatement {
        val lines = doc.lines

        var periodStart: LocalDate? = null
        var periodEnd: LocalDate? = null
        Regex("""Abrechnung vom (\d{2}\.\d{2}\.\d{4}) bis (\d{2}\.\d{2}\.\d{4})""").find(doc.text)?.let {
            periodStart = GermanFormats.findDate(it.groupValues[1])
            periodEnd = GermanFormats.findDate(it.groupValues[2])
        }
        val cardRef = Regex("""DKB-VISA-Card:\s*([0-9X ]{6,})""").find(doc.text)?.groupValues?.get(1)?.trim()

        fun signToken(line: PdfLine): PdfToken? =
            line.tokens.lastOrNull { (it.text == "-" || it.text == "+") && it.x > 500f }

        fun signedAmount(line: PdfLine): Long? {
            val sign = signToken(line) ?: return null
            val amountTok = line.tokens
                .filter { it.x < sign.x && GermanFormats.isAmount(it.text) }
                .maxByOrNull { it.x } ?: return null
            val magnitude = abs(GermanFormats.amountToCents(amountTok.text))
            return if (sign.text == "-") -magnitude else magnitude
        }

        fun merchantOn(line: PdfLine): String =
            line.tokens.filter { it.x in 118f..290f }.joinToString(" ") { it.text }.trim()

        var openingCents: Long? = null
        var closingCents: Long? = null
        val transactions = mutableListOf<MutableCcTx>()

        for (line in lines) {
            val text = line.text
            if (openingCents == null && text.contains("Saldo letzte Abrechnung")) {
                openingCents = signedAmount(line)
                continue
            }
            if (text.contains("Neuer Saldo")) {
                closingCents = signedAmount(line)
                break // legal boilerplate follows the closing balance
            }

            val belegTok = line.tokens.firstOrNull { it.x in 35f..72f && GermanFormats.findDate(it.text) != null }
            if (belegTok != null) {
                val buchungTok = line.tokens.firstOrNull { it.x in 72f..120f && GermanFormats.findDate(it.text) != null }
                val amount = signedAmount(line)
                if (amount != null) {
                    transactions.add(
                        MutableCcTx(
                            bookingDate = GermanFormats.findDate((buchungTok ?: belegTok).text)!!,
                            valueDate = GermanFormats.findDate(belegTok.text),
                            amountCents = amount,
                            merchant = merchantOn(line),
                        )
                    )
                }
            } else if (transactions.isNotEmpty() && signToken(line) == null) {
                merchantOn(line).takeIf { it.isNotEmpty() }?.let { transactions.last().extra.add(it) }
            }
        }

        return ParsedStatement(
            templateId = id,
            kind = StatementKind.CREDIT_CARD,
            institution = "DKB",
            accountRef = cardRef,
            iban = null,
            statementNumber = null,
            periodStart = periodStart,
            periodEnd = periodEnd,
            currency = "EUR",
            openingBalanceCents = openingCents,
            closingBalanceCents = closingCents,
            transactions = transactions.map { it.toParsed() },
        )
    }

    private class MutableCcTx(
        val bookingDate: LocalDate,
        val valueDate: LocalDate?,
        val amountCents: Long,
        val merchant: String,
    ) {
        val extra = mutableListOf<String>()
        fun toParsed(): ParsedTransaction {
            val fullMerchant = (listOf(merchant) + extra).filter { it.isNotBlank() }.joinToString(" ")
            return ParsedTransaction(
                bookingDate = bookingDate,
                valueDate = valueDate,
                amountCents = amountCents,
                counterparty = fullMerchant.ifBlank { null },
                purpose = fullMerchant,
                rawText = fullMerchant,
                bookingType = null,
            )
        }
    }
}
