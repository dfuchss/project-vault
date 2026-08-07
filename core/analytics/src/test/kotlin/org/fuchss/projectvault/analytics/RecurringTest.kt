package org.fuchss.projectvault.analytics

import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecurringTest {

    private fun tx(cents: Long, date: LocalDate, counterparty: String?, cat: String? = null, kind: CategoryKind? = null) =
        AnalyticsTxn(cents, date, cat, kind, counterparty)

    @Test
    fun `detects a monthly subscription but not frequent groceries`() {
        val txns = listOf(
            // Spotify: monthly, ~stable
            tx(-1099, LocalDate.of(2026, 5, 8), "Spotify/Stockholm/../SE", "cat-subscriptions"),
            tx(-1099, LocalDate.of(2026, 6, 8), "Spotify/Stockholm/../SE", "cat-subscriptions"),
            tx(-1099, LocalDate.of(2026, 7, 8), "Spotify/Stockholm/../SE", "cat-subscriptions"),
            // REWE: several times a month -> not a monthly recurrence
            tx(-2340, LocalDate.of(2026, 7, 2), "REWE.Markt/DE"),
            tx(-3910, LocalDate.of(2026, 7, 9), "REWE.Markt/DE"),
            tx(-2150, LocalDate.of(2026, 7, 16), "REWE.Markt/DE"),
            tx(-2880, LocalDate.of(2026, 7, 23), "REWE.Markt/DE"),
        )
        val series = Recurring.detect(txns)
        assertEquals(1, series.size)
        val spotify = series.single()
        assertEquals("SPOTIFY", spotify.merchantKey)
        assertEquals(Cadence.MONTHLY, spotify.cadence)
        assertEquals(-1099, spotify.typicalAmountCents)
        assertEquals(LocalDate.of(2026, 8, 8), spotify.nextExpectedDate)
    }

    @Test
    fun `variable spending estimate excludes fixed bills and reports mean and std dev`() {
        val txns = listOf(
            // A fixed monthly bill (detected as recurring) — must be excluded from variable spend.
            tx(-850_00, LocalDate.of(2026, 5, 1), "Miete Musterwohnung"),
            tx(-850_00, LocalDate.of(2026, 6, 1), "Miete Musterwohnung"),
            tx(-850_00, LocalDate.of(2026, 7, 1), "Miete Musterwohnung"),
            // Salary (income) — not an expense, ignored.
            tx(342_500, LocalDate.of(2026, 5, 28), "Muster GmbH Lohn", kind = CategoryKind.INCOME),
            tx(342_500, LocalDate.of(2026, 6, 28), "Muster GmbH Lohn", kind = CategoryKind.INCOME),
            tx(342_500, LocalDate.of(2026, 7, 28), "Muster GmbH Lohn", kind = CategoryKind.INCOME),
            // A transfer — excluded.
            tx(-530_00, LocalDate.of(2026, 7, 15), "Umbuchung Sparen", kind = CategoryKind.TRANSFER),
            // Variable spend: 120€, 240€, 360€ across the three months -> mean 240€, std 97,98€.
            tx(-120_00, LocalDate.of(2026, 5, 10), "REWE.Markt/DE"),
            tx(-240_00, LocalDate.of(2026, 6, 12), "EDEKA/DE"),
            tx(-360_00, LocalDate.of(2026, 7, 14), "Restaurant Muster"),
        )
        val series = Recurring.detect(txns)
        val v = Recurring.variableMonthlySpending(txns, series)
        assertEquals(3, v.sampleMonths)
        assertEquals(240_00, v.meanCents)
        // Population std of {120,240,360}€ = sqrt(9600)€ ≈ 97,98€ = 9798 cents.
        assertTrue(v.stdDevCents in 9700..9900, "std ${v.stdDevCents} not ≈ 9798")
    }

    @Test
    fun `excludes transfers (credit-card settlement, savings) from recurring`() {
        val txns = listOf(
            // Monthly credit-card settlement, categorized as a transfer -> must NOT be recurring.
            tx(-47500, LocalDate.of(2026, 5, 1), "Kreditkartenabrechnung Visa", "cat-transfers", CategoryKind.TRANSFER),
            tx(-47500, LocalDate.of(2026, 6, 1), "Kreditkartenabrechnung Visa", "cat-transfers", CategoryKind.TRANSFER),
            tx(-47500, LocalDate.of(2026, 7, 1), "Kreditkartenabrechnung Visa", "cat-transfers", CategoryKind.TRANSFER),
            // Monthly savings transfer -> also excluded.
            tx(-23400, LocalDate.of(2026, 5, 5), "Sparen Tagesgeld", "cat-transfers", CategoryKind.TRANSFER),
            tx(-23400, LocalDate.of(2026, 6, 5), "Sparen Tagesgeld", "cat-transfers", CategoryKind.TRANSFER),
            tx(-23400, LocalDate.of(2026, 7, 5), "Sparen Tagesgeld", "cat-transfers", CategoryKind.TRANSFER),
            // Monthly salary (income) -> should still be detected.
            tx(328000, LocalDate.of(2026, 5, 28), "Muster GmbH Lohn/Gehalt", "cat-income", CategoryKind.INCOME),
            tx(328000, LocalDate.of(2026, 6, 28), "Muster GmbH Lohn/Gehalt", "cat-income", CategoryKind.INCOME),
            tx(328000, LocalDate.of(2026, 7, 28), "Muster GmbH Lohn/Gehalt", "cat-income", CategoryKind.INCOME),
        )
        val series = Recurring.detect(txns)
        assertEquals(1, series.size, "only the salary is recurring; transfers are excluded")
        assertEquals("MUSTER", series.single().merchantKey)
        assertTrue(series.none { it.categoryId == "cat-transfers" })
    }

    @Test
    fun `forecast places a quarterly series only in its due months`() {
        val q = RecurringSeries("INS", "Versicherung", "cat-insurance", Cadence.QUARTERLY, -34500, LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15), 4)
        val f = Recurring.forecast(listOf(q), from = LocalDate.of(2026, 8, 1), months = 6) // Aug 2026 .. Jan 2027
        val byMonth = f.associateBy { it.month }
        assertEquals(34500, byMonth.getValue(10).expenseCents, "October is due")
        assertEquals(34500, byMonth.getValue(1).expenseCents, "January is due")
        assertEquals(0, byMonth.getValue(8).expenseCents)
        assertEquals(2, f.count { it.expenseCents > 0 }, "quarterly hits exactly twice in 6 months")
    }

    @Test
    fun `forecast advances a series whose next date is in the past into the window`() {
        val m = RecurringSeries("X", "Rent", "cat-housing", Cadence.MONTHLY, -47500, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 10), 3)
        val f = Recurring.forecast(listOf(m), from = LocalDate.of(2026, 8, 1), months = 3) // Aug, Sep, Oct
        assertEquals(3, f.size)
        assertTrue(f.all { it.expenseCents == 47500L }, "every month in the window is covered")
    }

    @Test
    fun `separates a stable monthly salary from the same payer's varied payments`() {
        // Same employer (one merchant key) pays a stable monthly salary plus irregular extras —
        // amount clustering must isolate the salary so it's still detected as monthly income.
        val salary = (1..7).map { m -> tx(328000, LocalDate.of(2026, m, 28), "Muster GmbH Lohn/Gehalt", "cat-salary", CategoryKind.INCOME) }
        val extras = listOf(
            tx(18750, LocalDate.of(2026, 7, 9), "Muster GmbH Reisekosten", "cat-income", CategoryKind.INCOME),
            tx(224900, LocalDate.of(2026, 7, 10), "Muster GmbH Vorschuss", "cat-income", CategoryKind.INCOME),
            tx(61200, LocalDate.of(2026, 6, 15), "Muster GmbH Bonus", "cat-income", CategoryKind.INCOME),
        )
        val income = Recurring.detect(salary + extras).filter { it.typicalAmountCents > 0 }
        assertEquals(1, income.size, "only the stable monthly salary is recurring")
        assertEquals(328000, income.single().typicalAmountCents)
        assertEquals(Cadence.MONTHLY, income.single().cadence)
    }

    @Test
    fun `detect rejects an irregular group whose median gap only looks monthly`() {
        // Gaps of 10 and 50 days -> median 30 (monthly window), but neither gap is actually monthly.
        val txns = listOf(
            tx(-1450, LocalDate.of(2026, 5, 1), "PayPal Sammelkonto"),
            tx(-1450, LocalDate.of(2026, 5, 11), "PayPal Sammelkonto"),
            tx(-1450, LocalDate.of(2026, 6, 30), "PayPal Sammelkonto"),
        )
        assertTrue(Recurring.detect(txns).isEmpty(), "irregular gaps must not be treated as recurring")
    }

    @Test
    fun `detect tolerates a single skipped month`() {
        // Monthly on the 5th, but July is missing -> gaps 31, 61, 31: the majority still match monthly.
        val txns = listOf(
            tx(-1290, LocalDate.of(2026, 5, 5), "Netflix"),
            tx(-1290, LocalDate.of(2026, 6, 5), "Netflix"),
            tx(-1290, LocalDate.of(2026, 8, 5), "Netflix"),
            tx(-1290, LocalDate.of(2026, 9, 5), "Netflix"),
        )
        val series = Recurring.detect(txns)
        assertEquals(1, series.size)
        assertEquals(Cadence.MONTHLY, series.single().cadence)
    }

    @Test
    fun `forecast projects recurring income and expense forward`() {
        val salary = RecurringSeries("MUSTER", "Muster GmbH", "cat-income", Cadence.MONTHLY, 342_500, LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 30), 3)
        val rent = RecurringSeries("MIETE", "Vermieter", "cat-housing", Cadence.MONTHLY, -85_000, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), 3)

        val forecast = Recurring.forecast(listOf(salary, rent), from = LocalDate.of(2026, 8, 1), months = 3)
        assertEquals(3, forecast.size)
        val august = forecast.first { it.month == 8 }
        assertEquals(342_500, august.incomeCents)
        assertEquals(85_000, august.expenseCents)
        assertEquals(257_500, august.netCents)
        assertTrue(forecast.all { it.incomeCents == 342_500L && it.expenseCents == 85_000L })

        val fixed = Recurring.monthlyFixed(listOf(salary, rent))
        assertEquals(342_500, fixed.incomeCents)
        assertEquals(85_000, fixed.expenseCents)
        assertEquals(257_500, fixed.netCents)
    }
}
