package org.fuchss.projectvault.imports.templates

import org.fuchss.projectvault.imports.StatementImporter
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.imports.WrongStatementTypeException
import org.fuchss.projectvault.imports.csv.CsvFormat
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** All data here is fictional — no personal statement content. */
class DkbCsvTemplatesTest {

    // ---------------- Giro / Tagesgeld ----------------

    private val giroCsv = """
        "Girokonto";"DE00000000000000000000"

        "Kontostand vom 07.08.2026:";"3.482,91 €"
        ""
        "Buchungsdatum";"Wertstellung";"Status";"Zahlungspflichtige*r";"Zahlungsempfänger*in";"Verwendungszweck";"Umsatztyp";"IBAN";"Betrag (€)";"Gläubiger-ID";"Mandatsreferenz";"Kundenreferenz"
        "07.08.26";"07.08.26";"Gebucht";"ISSUER";"Muster Supermarkt";"Kartenzahlung";"Ausgang";"DE00";"-27,84";"";"";"REF1"
        "06.08.26";"06.08.26";"Gebucht";"Max Muster";"Muster GmbH";"Rechnung 42";"Ausgang";"DE00";"-58,10";"";"";"REF2"
        "05.08.26";"05.08.26";"Vorgemerkt";"ACME";"Max Muster";"Pending";"Eingang";"DE00";"175";"";"";""
        "01.08.26";"01.08.26";"Gebucht";"Muster GmbH Lohn";"Max Muster";"Gehalt";"Eingang";"DE00";"3.150,00";"";"";"REF3"
    """.trimIndent()

    @Test
    fun `giro CSV parses signed amounts, dates, direction-based counterparty, and skips pending`() {
        val s = DkbCsvGiroTemplate().parse(CsvFormat.of(giroCsv))
        assertEquals(StatementKind.GIRO, s.kind)
        assertEquals("DKB", s.institution)
        assertEquals("DE00000000000000000000", s.iban)
        assertEquals(348291, s.closingBalanceCents)
        assertEquals(LocalDate.of(2026, 8, 7), s.periodEnd)

        assertEquals(3, s.transactions.size, "the 'Vorgemerkt' (pending) row is skipped")
        val card = s.transactions.first()
        assertEquals(LocalDate.of(2026, 8, 7), card.bookingDate)
        assertEquals(-2784, card.amountCents)
        assertEquals("Muster Supermarkt", card.counterparty) // outgoing -> payee

        val salary = s.transactions.last()
        assertEquals(315000, salary.amountCents)
        assertEquals("Muster GmbH Lohn", salary.counterparty) // incoming -> payer
    }

    @Test
    fun `giro CSV has no opening balance so the balance check is not verifiable`() {
        val s = DkbCsvGiroTemplate().parse(CsvFormat.of(giroCsv))
        assertNull(s.openingBalanceCents)
    }

    @Test
    fun `tagesgeld account type is recognised by the same template`() {
        val csv = giroCsv.replaceFirst("\"Girokonto\"", "\"Tagesgeld\"")
        assertTrue(DkbCsvGiroTemplate().matches(CsvFormat.of(csv)))
    }

    @Test
    fun `giro template does not match a credit-card export`() {
        assertFalse(DkbCsvGiroTemplate().matches(CsvFormat.of(visaCsv)))
    }

    // ---------------- Credit card ----------------

    private val visaCsv = """
        "Karte";"Visa Kreditkarte";"0000 •••• •••• 0000"
        ""
        "Saldo vom 07.08.2026:";"-263,58 EUR"
        ""
        "Belegdatum";"Wertstellung";"Status";"Beschreibung";"Umsatztyp";"Betrag (€)";"Fremdwährungsbetrag"
        "02.08.26";"03.08.26";"Gebucht";"Muster Shop GmbH";"Onlinezahlung";"-72,65";""
        "22.07.26";"23.07.26";"Gebucht";"Ausgleich Kreditkarte";"Lastschrift";"1.845,20";""
        "21.07.26";"23.06.26";"Gebucht";"Muster Retoure";"Gutschrift";"318,45";""
        "20.07.26";"21.07.26";"Vorgemerkt";"Pending Shop";"Onlinezahlung";"-14,30";""
    """.trimIndent()

    @Test
    fun `credit-card CSV maps Wertstellung to booking and Belegdatum to value date`() {
        val s = DkbCsvCreditCardTemplate().parse(CsvFormat.of(visaCsv))
        assertEquals(StatementKind.CREDIT_CARD, s.kind)
        assertEquals(-26358, s.closingBalanceCents)
        assertEquals(3, s.transactions.size, "pending row skipped")

        val charge = s.transactions.first()
        assertEquals(LocalDate.of(2026, 8, 3), charge.bookingDate) // Wertstellung (settlement)
        assertEquals(LocalDate.of(2026, 8, 2), charge.valueDate)   // Belegdatum (purchase)
        assertEquals(-7265, charge.amountCents)
        assertEquals("Muster Shop GmbH", charge.counterparty)

        assertTrue(s.transactions.any { it.amountCents == 184520L }, "payment credit is positive")
        assertTrue(s.transactions.any { it.amountCents == 31845L }, "refund credit is positive")
    }

    @Test
    fun `routing rejects a card statement for a giro account but accepts it for a card account`() {
        val importer = StatementImporter()
        // A Girokonto accepts only GIRO statements — a card export must be rejected clearly.
        val ex = assertFailsWith<WrongStatementTypeException> {
            importer.importCsv(CsvFormat.of(visaCsv), "visa.csv", accepts = setOf(StatementKind.GIRO))
        }
        assertEquals(StatementKind.CREDIT_CARD, ex.found)
        // The same file into a Kreditkarte account (accepts CREDIT_CARD) parses fine.
        val ok = importer.importCsv(CsvFormat.of(visaCsv), "visa.csv", accepts = setOf(StatementKind.CREDIT_CARD))
        assertEquals(StatementKind.CREDIT_CARD, ok.statement.kind)
    }

    // ---------------- Depot ----------------

    private val depotCsv = """
        Depotübersicht vom 07.08.2026 21:28
        Kunde;Max Muster

        Depotnummer;0000000000

        ISIN;Wertpapiername;Stück/Nominale;Einheitskennzeichen;Einstandskurs;Währung;Einstandswert;Währung;Bewertungskurs;Währung;Zeit;Handelsplatz;Kurswert;Währung;Gewinn/Verlust;Währung;Gewinn/Verlust (%)
        US0000000001;MUSTER CORP DL-,01;10;Stück;150,00;EUR;1.500,00;EUR;173,00;EUR;21:28 Uhr;Direkthandel;1.730,00;EUR;230,00;EUR;15,33%
        IE0000000002;MUSTER WORLD ETF;5,5000;Stück;90,00;EUR;495,00;EUR;120,00;EUR;21:28 Uhr;Direkthandel;660,00;EUR;165,00;EUR;33,33%
        "";;;;;Depot-Gesamtwert;1.995,00;EUR;;;;;2.390,00;EUR;395,00;EUR;19,80%
    """.trimIndent()

    @Test
    fun `depot CSV parses positions and reconciles against the Gesamtwert`() {
        val s = IngCsvDepotTemplate().parse(CsvFormat.of(depotCsv))
        assertEquals("ING", s.institution)
        assertEquals(LocalDate.of(2026, 8, 7), s.valuationDate)
        assertEquals(2, s.positions.size)
        assertEquals(239000, s.totalValueCents) // 1.730,00 + 660,00

        val muster = s.positions.first()
        assertEquals("US0000000001", muster.isin)
        assertEquals(173000, muster.marketValueCents)
        assertEquals("173,00 EUR", muster.priceText)
        assertEquals("10", muster.quantity.stripTrailingZeros().toPlainString())

        // The sum of positions equals the stated total.
        assertEquals(s.totalValueCents, s.positions.sumOf { it.marketValueCents })
    }
}
