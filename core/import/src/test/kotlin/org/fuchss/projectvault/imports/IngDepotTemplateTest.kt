package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.TestPdf.doc
import org.fuchss.projectvault.imports.TestPdf.line
import org.fuchss.projectvault.imports.TestPdf.tok
import org.fuchss.projectvault.imports.templates.IngDepotTemplate
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngDepotTemplateTest {

    /** Synthetic ING Depot statement mirroring the real column geometry (no personal data). */
    @Test
    fun `parses positions, isin, and reconciles the total`() {
        val document = doc(
            line(tok(67f, "ING-DiBa AG   60628 Frankfurt am Main")),
            line(tok(330f, "Direkt-Depot Nr.:"), tok(470f, "123")),
            line(tok(67f, "Depotauszug per 31.03.2026")),
            line(tok(67f, "Stücke/Nominale"), tok(180f, "Wertpapier-Informationen"), tok(414f, "Kurs"), tok(506f, "Kurswert")),
            // position 1
            line(tok(67f, "10"), tok(152f, "Stück"), tok(180f, "ACME AG"), tok(389f, "63,00 EUR"), tok(495f, "630,00 EUR")),
            line(tok(180f, "Inhaber-Aktien o.N.")),
            line(tok(180f, "ISIN (WKN):"), tok(248f, "DE0001234567 (ABC123)")),
            // position 2 (fractional quantity, 4-decimal price)
            line(tok(67f, "2,50000"), tok(152f, "Stück"), tok(180f, "WORLD ETF"), tok(379f, "184,0000 EUR"), tok(490f, "460,00 EUR")),
            line(tok(180f, "Registered Shs o.N.")),
            line(tok(180f, "ISIN (WKN):"), tok(248f, "IE0009999999 (XYZ999)")),
            line(tok(180f, "Verwahrart:"), tok(248f, "Wertpapierrechnung")),
            // footer
            line(tok(67f, "Anzahl Posten: 2"), tok(248f, "Gesamtkurswert"), tok(481f, "1.090,00 EUR")),
        )

        val result = DepotImporter().importDocument(document, "depot.pdf")
        val s = result.statement

        assertEquals("ING", s.institution)
        assertEquals("123", s.depotNumber)
        assertEquals(LocalDate.of(2026, 3, 31), s.valuationDate)
        assertEquals(2, s.positions.size)
        assertEquals(109000, s.totalValueCents)

        val acme = s.positions[0]
        assertEquals("DE0001234567", acme.isin)
        assertEquals("ABC123", acme.wkn)
        assertEquals(0, acme.quantity.compareTo(BigDecimal("10")))
        assertEquals(63000, acme.marketValueCents)
        assertTrue(acme.name.contains("ACME AG"))

        val etf = s.positions[1]
        assertEquals("IE0009999999", etf.isin)
        assertEquals(0, etf.quantity.compareTo(BigDecimal("2.5")))
        assertEquals("184,0000 EUR", etf.priceText) // >2 decimals preserved as display text

        assertTrue(result.check.ok, "Σ positions should equal Gesamtkurswert: ${result.check}")
    }
}
