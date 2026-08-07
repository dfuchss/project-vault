package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.parse.GermanFormats
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GermanFormatsTest {

    @Test
    fun `parses amounts with thousands and decimal separators`() {
        assertEquals(348291, GermanFormats.amountToCents("3.482,91"))
        assertEquals(-3845, GermanFormats.amountToCents("-38,45"))
        assertEquals(27500, GermanFormats.amountToCents("275,00"))
        assertEquals(5821340, GermanFormats.amountToCents("58.213,40"))
    }

    @Test
    fun `handles trailing sign used on credit-card statements`() {
        assertEquals(-288450, GermanFormats.amountToCents("2.884,50 -"))
        assertEquals(27500, GermanFormats.amountToCents("275,00 +"))
    }

    @Test
    fun `isAmount accepts money but rejects fx rates and plain ints`() {
        assertTrue(GermanFormats.isAmount("3.482,91"))
        assertTrue(GermanFormats.isAmount("-38,45"))
        assertFalse(GermanFormats.isAmount("1,2537"))   // fx rate: 4 decimals
        assertFalse(GermanFormats.isAmount("1,738204")) // fx rate: 6 decimals
        assertFalse(GermanFormats.isAmount("3471"))     // plain integer
    }

    @Test
    fun `finds dotted dates with 2- and 4-digit years`() {
        assertEquals(LocalDate.of(2026, 7, 6), GermanFormats.findDate("06.07.2026 Kartenzahlung"))
        assertEquals(LocalDate.of(2026, 6, 22), GermanFormats.findDate("22.06.26"))
        assertNull(GermanFormats.findDate("2026-07-05T21:49")) // ISO timestamp is not a dotted date
    }
}
