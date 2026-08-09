package org.fuchss.projectvault.quotes

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The money arithmetic behind live pricing. Values are asserted to the exact cent — a `Double`
 * anywhere in this path would show up here as an off-by-one-cent drift.
 */
class RepricingTest {
    @Test
    fun `whole shares at a two-decimal price`() {
        assertEquals(213_576L, Repricing.repricedCents("12", BigDecimal("177.98"))) // 2135,76 €
    }

    @Test
    fun `fractional shares are valued exactly`() {
        // 0.7215 × 128.54 = 92.741... → 92,74 €
        assertEquals(9274L, Repricing.repricedCents("0.7215", BigDecimal("128.54")))
    }

    @Test
    fun `a price with more than two decimals is not truncated before multiplying`() {
        // 30 × 109.2466 = 3277.398 → 3277,40 €; truncating the price first would give 3277,38 €.
        assertEquals(327_740L, Repricing.repricedCents("30", BigDecimal("109.2466")))
    }

    @Test
    fun `rounding is half-up at the cent boundary`() {
        // 1 × 0.005 → 0,01 €
        assertEquals(1L, Repricing.repricedCents("1", BigDecimal("0.005")))
        // 3 × 0.005 = 0.015 → 0,02 €
        assertEquals(2L, Repricing.repricedCents("3", BigDecimal("0.005")))
    }

    @Test
    fun `a classic float-error case stays exact`() {
        // 0.1 + 0.2 territory: 3 × 0.615 = 1.845 → 1,85 €. Doubles would round this to 1,84 €.
        assertEquals(185L, Repricing.repricedCents("3", BigDecimal("0.615")))
    }

    @Test
    fun `large positions do not overflow`() {
        assertEquals(178_000_000_00L, Repricing.repricedCents("1000000", BigDecimal("178.00")))
    }

    @Test
    fun `quantity text is read in both plain and German notation`() {
        assertEquals(BigDecimal("30.5"), Repricing.parseQuantity("30.5"))
        assertEquals(BigDecimal("30.5"), Repricing.parseQuantity("30,5"))
        assertEquals(BigDecimal("1234.5678"), Repricing.parseQuantity("1.234,5678"))
        assertEquals(BigDecimal("12"), Repricing.parseQuantity(" 12 "))
    }

    @Test
    fun `an unparsable quantity is skipped rather than valued at zero`() {
        assertNull(Repricing.repricedCents("", BigDecimal("10")))
        assertNull(Repricing.repricedCents("n/a", BigDecimal("10")))
    }
}
