package org.fuchss.projectvault.app

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CSV formatter is pure, so the things that actually break an export — an unquoted separator
 * inside a Verwendungszweck, a decimal point where a German spreadsheet wants a comma — are checked
 * here rather than by opening a file.
 */
class ExportTest {

    private fun row(
        purpose: String = "Einkauf",
        counterparty: String? = "REWE",
        amountCents: Long = -1160,
        category: String? = "Lebensmittel",
    ) = ExportRow(
        bookingDate = LocalDate.of(2026, 7, 3),
        valueDate = LocalDate.of(2026, 7, 4),
        account = "Girokonto",
        counterparty = counterparty,
        purpose = purpose,
        bookingType = "Kartenzahlung",
        amountCents = amountCents,
        category = category,
    )

    @Test
    fun `header and row use the German bank dialect`() {
        val csv = exportCsv(listOf(row()), listOf("A", "B", "C", "D", "E", "F", "G", "H"))
        val lines = csv.split("\r\n")
        assertEquals("A;B;C;D;E;F;G;H", lines[0])
        assertEquals("03.07.2026;04.07.2026;Girokonto;REWE;Einkauf;Kartenzahlung;-11,60;Lebensmittel", lines[1])
        assertTrue(csv.endsWith("\r\n"), "every line, including the last, is terminated")
    }

    @Test
    fun `fields containing the separator or a quote are quoted and escaped`() {
        val csv = exportCsv(listOf(row(purpose = "Miete; Nebenkosten", counterparty = "Haus \"Anker\" GmbH")))
        val line = csv.split("\r\n")[1]
        assertTrue(line.contains("\"Miete; Nebenkosten\""), "a separator inside a field must be quoted: $line")
        assertTrue(line.contains("\"Haus \"\"Anker\"\" GmbH\""), "quotes are doubled: $line")
        // The quoting has to survive the app's own reader — an export it cannot re-import is broken.
        val cells = org.fuchss.projectvault.imports.csv.CsvFormat.parse(csv)[1]
        assertEquals("Miete; Nebenkosten", cells[4])
        assertEquals("Haus \"Anker\" GmbH", cells[3])
    }

    @Test
    fun `a newline inside a purpose does not break the row`() {
        val csv = exportCsv(listOf(row(purpose = "Zeile 1\nZeile 2")))
        val rows = org.fuchss.projectvault.imports.csv.CsvFormat.parse(csv)
        assertEquals(2, rows.size, "the embedded newline must stay inside its quoted field")
        assertEquals("Zeile 1\nZeile 2", rows[1][4])
    }

    @Test
    fun `amounts are decimal with a comma, never a float`() {
        assertEquals("0,00", csvAmount(0))
        assertEquals("0,07", csvAmount(7))
        assertEquals("-0,07", csvAmount(-7))
        assertEquals("12,50", csvAmount(1250))
        assertEquals("-1234,56", csvAmount(-123456))
        assertEquals("99999999,99", csvAmount(9999999999))
    }

    @Test
    fun `empty optional fields are written as empty cells, not as a dash`() {
        val csv = exportCsv(
            listOf(
                ExportRow(LocalDate.of(2026, 1, 2), null, "Kreditkarte", null, "Zahlung", null, 500, null),
            ),
        )
        assertEquals("02.01.2026;;Kreditkarte;;Zahlung;;5,00;", csv.split("\r\n")[1])
    }

    @Test
    fun `an empty list still exports its header`() {
        val csv = exportCsv(emptyList())
        assertEquals(1, csv.trim().lines().size)
        assertTrue(csv.startsWith(DefaultExportColumns.first()))
    }

    @Test
    fun `a field a spreadsheet would run as a formula is defused`() {
        // A SEPA purpose is free text chosen by whoever sent the money, and this file exists to be
        // opened in a spreadsheet. Quoting does not stop Excel evaluating a leading =/+/-/@.
        assertEquals("'=HYPERLINK(\"http://x\")", defuse("=HYPERLINK(\"http://x\")"))
        assertEquals("'@SUM(A1)", defuse("@SUM(A1)"))
        assertEquals("'+41 Ueberweisung", defuse("+41 Ueberweisung"))
        assertEquals("'-Kategorie", defuse("-Kategorie"))
        // Ordinary text is untouched, so a normal export is byte-identical to before.
        assertEquals("REWE Markt", defuse("REWE Markt"))
        assertEquals("", defuse(""))
    }

    @Test
    fun `amounts keep their sign and survive the extreme value`() {
        // Amounts are generated, not user text, so they are never defused — a debit still reads -11,60.
        assertEquals("-11,60", csvAmount(-1160))
        assertEquals("11,60", csvAmount(1160))
        assertEquals("0,00", csvAmount(0))
        // Negating Long.MIN_VALUE overflows back to itself; via BigInteger it does not.
        assertEquals("-92233720368547758,08", csvAmount(Long.MIN_VALUE))
    }
}
