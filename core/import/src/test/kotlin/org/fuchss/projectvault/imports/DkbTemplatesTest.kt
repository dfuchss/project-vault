package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.TestPdf.doc
import org.fuchss.projectvault.imports.TestPdf.line
import org.fuchss.projectvault.imports.TestPdf.tok
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DkbTemplatesTest {

    /** Synthetic DKB Giro statement mirroring the real column geometry (no personal data). */
    @Test
    fun `dkb giro parses debit and credit and balances`() {
        val document = doc(
            line(tok(43f, "Deutsche Kreditbank AG")),
            line(tok(68f, "Kontoauszug 3/2026"), tok(512f, "Seite 1 von 1")),
            line(tok(68f, "Girokonto 12345678, DE02 1203 0000 0000 0000 00")),
            line(tok(71f, "Datum"), tok(123f, "Erläuterung"), tok(394f, "Betrag Soll EUR"), tok(486f, "Betrag Haben EUR")),
            line(tok(123f, "Kontostand am 03.07.2026, Auszug Nr. 2"), tok(498f, "850,00")),
            // debit transaction (amount in the Soll column) + detail line
            line(tok(70f, "06.07.2026"), tok(123f, "Kartenzahlung"), tok(403f, "-47,50")),
            line(tok(123f, "REWE SAGT DANKE, MUSTERSTADT")),
            // credit transaction (amount in the Haben column) + detail line
            line(tok(70f, "07.07.2026"), tok(123f, "Lohn/Gehalt"), tok(490f, "1.234,00")),
            line(tok(123f, "ARBEITGEBER GMBH")),
            line(tok(123f, "Kontostand am 04.08.2026 um 18:04 Uhr"), tok(498f, "2.036,50")),
        )

        val result = StatementImporter().importDocument(document, "giro.pdf")
        val s = result.statement

        assertEquals(StatementKind.GIRO, s.kind)
        assertEquals("DKB", s.institution)
        assertEquals(85000, s.openingBalanceCents)
        assertEquals(203650, s.closingBalanceCents)
        assertEquals(2, s.transactions.size)

        val debit = s.transactions[0]
        assertEquals(-4750, debit.amountCents)
        assertEquals(LocalDate.of(2026, 7, 6), debit.bookingDate)
        assertEquals("REWE SAGT DANKE, MUSTERSTADT", debit.counterparty)

        val credit = s.transactions[1]
        assertEquals(123400, credit.amountCents)
        assertEquals("ARBEITGEBER GMBH", credit.counterparty)

        assertTrue(result.balance.ok, "expected balance-integrity to hold: ${result.balance}")
    }

    /** Synthetic DKB-VISA statement: trailing +/- signs, an FX row, a continuation line. */
    @Test
    fun `dkb credit card parses signs and fx and balances`() {
        val document = doc(
            line(tok(354f, "www.DKB.de")),
            line(tok(40f, "Ihre Abrechnung vom 01.06.2026 bis 30.06.2026"), tok(356f, "DKB-VISA-Card:"), tok(482f, "4930 00XX XXX")),
            line(tok(43f, "Datum"), tok(82f, "Datum"), tok(125f, "Angabe des Unternehmens /"), tok(534f, "Betrag in")),
            line(tok(82f, "01.06.26"), tok(125f, "Saldo letzte Abrechnung"), tok(535f, "212,00"), tok(573f, "-")),
            line(tok(43f, "02.06.26"), tok(82f, "03.06.26"), tok(125f, "REWE, MUSTERSTADT"), tok(542f, "63,50"), tok(573f, "-")),
            line(tok(43f, "05.06.26"), tok(82f, "06.06.26"), tok(125f, "Ausgleich Kreditkarte"), tok(535f, "150,00"), tok(573f, "+")),
            // FX row: foreign currency + foreign amount + rate must be ignored; EUR is rightmost pre-sign
            line(tok(43f, "10.06.26"), tok(82f, "11.06.26"), tok(125f, "ANTHROPIC* CLAUDE,"), tok(302f, "USD"), tok(400f, "17,82"), tok(480f, "1,1000"), tok(552f, "16,20"), tok(573f, "-")),
            line(tok(125f, "ANTHROPIC.COM")),
            line(tok(125f, "Neuer Saldo"), tok(535f, "141,70"), tok(573f, "-")),
            line(tok(40f, "Sehr geehrter Herr Mustermann")), // boilerplate after closing must be ignored
        )

        val result = StatementImporter().importDocument(document, "visa.pdf")
        val s = result.statement

        assertEquals(StatementKind.CREDIT_CARD, s.kind)
        assertEquals(-21200, s.openingBalanceCents)
        assertEquals(-14170, s.closingBalanceCents)
        assertEquals(3, s.transactions.size)
        assertEquals(-6350, s.transactions[0].amountCents)  // charge
        assertEquals(15000, s.transactions[1].amountCents)  // payment
        assertEquals(-1620, s.transactions[2].amountCents)  // FX charge, EUR value
        assertEquals("ANTHROPIC* CLAUDE, ANTHROPIC.COM", s.transactions[2].counterparty)

        assertTrue(result.balance.ok, "expected balance-integrity to hold: ${result.balance}")
    }

    /**
     * DKB Tagesgeld "Kontoauszug": same Soll/Haben layout as the Girokonto (no "Girokonto" word),
     * with "/ Wert: <date>" as a separate token. Verifies matching, Wert-date extraction/stripping,
     * and DATUM counterparty cleanup.
     */
    @Test
    fun `dkb tagesgeld parses, strips Wert, and reconciles`() {
        val document = doc(
            line(tok(43f, "Deutsche Kreditbank AG")),
            line(tok(68f, "Kontoauszug 6/2026")),
            line(tok(68f, "Tagesgeld 12345678, DE21 1203 0000 0000 0000 00")),
            line(tok(71f, "Datum"), tok(123f, "Erläuterung"), tok(394f, "Betrag Soll EUR"), tok(486f, "Betrag Haben EUR")),
            line(tok(123f, "Kontostand am 03.07.2026, Auszug Nr. 5"), tok(498f, "1.750,00")),
            line(tok(70f, "30.07.2026Echtzeitüberweisung"), tok(130f, "/ Wert: 29.07.2026"), tok(403f, "-620,00")),
            line(tok(123f, "Max Mustermann DATUM 29.07.2026, 21.14 UHR")),
            line(tok(123f, "Kontostand am 04.08.2026 um 18:04 Uhr"), tok(498f, "1.130,00")),
        )

        val s = StatementImporter().importDocument(document, "tagesgeld.pdf")
        assertEquals(StatementKind.GIRO, s.statement.kind)
        assertEquals(1, s.statement.transactions.size)

        val t = s.statement.transactions[0]
        assertEquals(-62000, t.amountCents)
        assertEquals("Echtzeitüberweisung", t.bookingType)
        assertEquals(LocalDate.of(2026, 7, 29), t.valueDate)
        assertEquals("Max Mustermann", t.counterparty)
        assertTrue(s.balance.ok, "expected balance to reconcile: ${s.balance}")
    }
}
