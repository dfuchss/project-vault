package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.csv.CsvFormat
import org.fuchss.projectvault.model.Bank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bank decides how a statement parses, so an import is routed by the target account's bank as
 * well as its type. All data here is fictional.
 */
class BankRoutingTest {

    private val dkbGiroCsv = """
        "Girokonto";"DE00000000000000000000"

        "Kontostand vom 07.08.2026:";"3.482,91 €"
        ""
        "Buchungsdatum";"Wertstellung";"Status";"Zahlungspflichtige*r";"Zahlungsempfänger*in";"Verwendungszweck";"Umsatztyp";"IBAN";"Betrag (€)";"Gläubiger-ID";"Mandatsreferenz";"Kundenreferenz"
        "07.08.26";"07.08.26";"Gebucht";"ISSUER";"Muster Supermarkt";"Kartenzahlung";"Ausgang";"DE00";"-27,84";"";"";"REF1"
    """.trimIndent()

    private val ingDepotCsv = """
        Depotübersicht vom 07.08.2026 21:28
        Kunde;Max Muster

        Depotnummer;0000000000

        ISIN;Wertpapiername;Stück/Nominale;Einheitskennzeichen;Einstandskurs;Währung;Einstandswert;Währung;Bewertungskurs;Währung;Zeit;Handelsplatz;Kurswert;Währung;Gewinn/Verlust;Währung;Gewinn/Verlust (%)
        US0000000001;MUSTER CORP DL-,01;10;Stück;150,00;EUR;1.500,00;EUR;173,00;EUR;21:28 Uhr;Direkthandel;1.730,00;EUR;230,00;EUR;15,33%
        "";;;;;Depot-Gesamtwert;1.500,00;EUR;;;;;1.730,00;EUR;230,00;EUR;15,33%
    """.trimIndent()

    @Test
    fun `a statement from another bank is rejected instead of mis-filed`() {
        val ex = assertFailsWith<WrongBankException> {
            StatementImporter().importCsv(CsvFormat.of(dkbGiroCsv), "umsaetze.csv", banks = setOf(Bank.ING))
        }
        assertEquals(Bank.DKB, ex.found)
        // The same file into a DKB account parses fine.
        val ok = StatementImporter().importCsv(CsvFormat.of(dkbGiroCsv), "umsaetze.csv", banks = setOf(Bank.DKB))
        assertEquals("DKB", ok.statement.institution)
    }

    @Test
    fun `depot imports are routed by bank too`() {
        val ex = assertFailsWith<WrongBankException> {
            DepotImporter().importCsv(CsvFormat.of(ingDepotCsv), "depot.csv", banks = setOf(Bank.DKB))
        }
        assertEquals(Bank.ING, ex.found)
        assertEquals("ING", DepotImporter().importCsv(CsvFormat.of(ingDepotCsv), "depot.csv", banks = setOf(Bank.ING)).statement.institution)
    }

    @Test
    fun `the catalog reflects the registered templates`() {
        assertEquals(listOf(Bank.DKB, Bank.ING), BankCatalog.banks)
        assertEquals(setOf(StatementKind.GIRO, StatementKind.CREDIT_CARD), BankCatalog.kinds(Bank.DKB))
        assertEquals(setOf(StatementKind.DEPOT), BankCatalog.kinds(Bank.ING))
        assertFalse(BankCatalog.isSupported(Bank.ING, StatementKind.GIRO))
        assertTrue(BankCatalog.isSupported(Bank.DKB, StatementKind.CREDIT_CARD))
    }

    @Test
    fun `institutions stored as text resolve back to a bank`() {
        assertEquals(Bank.DKB, Bank.fromInstitution("DKB"))
        assertEquals(Bank.DKB, Bank.fromInstitution(" dkb "))
        assertEquals(Bank.ING, Bank.fromInstitution("ING-DiBa"))
        assertNull(Bank.fromInstitution("Sparkasse"))
        assertNull(Bank.fromInstitution(null))
        assertNull(Bank.fromInstitution("  "))
    }
}
