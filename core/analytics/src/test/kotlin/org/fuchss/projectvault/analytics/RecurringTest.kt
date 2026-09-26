package org.fuchss.projectvault.analytics

import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // -- Level shifts (a rent increase, a price hike) -------------------------------------------

    @Test
    fun `a mid-history amount change reports the current level, not a blend`() {
        // Rent goes 850 -> 910 in August. The rise stays inside the amount-cluster tolerance, so the
        // old code took the median over all nine months and projected a rent nobody pays.
        val txns = (1..6).map { m -> tx(-850_00, LocalDate.of(2026, m, 1), "Miete Musterwohnung") } +
            (7..9).map { m -> tx(-910_00, LocalDate.of(2026, m, 1), "Miete Musterwohnung") }
        val rent = Recurring.detect(txns, asOf = LocalDate.of(2026, 9, 20)).single()
        assertEquals(-910_00, rent.typicalAmountCents, "the forecast must use what is billed now")
        assertEquals(9, rent.occurrences, "one series, not two")
        val change = assertNotNull(rent.amountChange)
        assertEquals(-850_00, change.previousCents)
        assertEquals(-910_00, change.currentCents)
        assertEquals(LocalDate.of(2026, 7, 1), change.since)
        assertEquals(listOf(-910_00L, -850_00L), rent.levelsCents, "newest level first")
    }

    @Test
    fun `a price jump beyond the cluster tolerance stays one series`() {
        // 850 -> 1000 is far enough apart that amount clustering splits it. Left split, the old rent
        // would be projected forever and the new one would need three months to be detected at all.
        val txns = (1..6).map { m -> tx(-850_00, LocalDate.of(2026, m, 1), "Miete Musterwohnung") } +
            (7..9).map { m -> tx(-1000_00, LocalDate.of(2026, m, 1), "Miete Musterwohnung") }
        val series = Recurring.detect(txns, asOf = LocalDate.of(2026, 9, 20))
        assertEquals(1, series.size, "the old and the new level are one series")
        assertEquals(-1000_00, series.single().typicalAmountCents)
        assertEquals(-850_00, assertNotNull(series.single().amountChange).previousCents)
    }

    @Test
    fun `two standing orders to the same payer are not merged into a level change`() {
        // Rent and a service charge, both monthly to the same landlord, running side by side: they
        // overlap in time, so they are two things — not one price that changed.
        val txns = (1..8).flatMap { m ->
            listOf(
                tx(-850_00, LocalDate.of(2026, m, 1), "Musterverwaltung GmbH"),
                tx(-120_00, LocalDate.of(2026, m, 1), "Musterverwaltung GmbH"),
            )
        }
        val series = Recurring.detect(txns, asOf = LocalDate.of(2026, 8, 20))
        assertEquals(2, series.size, "concurrent series must stay separate")
        assertTrue(series.all { it.amountChange == null })
    }

    @Test
    fun `a one-off outlier in a stable series is not a price change`() {
        val txns = listOf(
            tx(-4999, LocalDate.of(2026, 1, 8), "Fitnessstudio Muster"),
            tx(-4999, LocalDate.of(2026, 2, 8), "Fitnessstudio Muster"),
            tx(-149_00, LocalDate.of(2026, 3, 8), "Fitnessstudio Muster"), // a one-off extra
            tx(-4999, LocalDate.of(2026, 4, 8), "Fitnessstudio Muster"),
            tx(-4999, LocalDate.of(2026, 5, 8), "Fitnessstudio Muster"),
        )
        val gym = Recurring.detect(txns, asOf = LocalDate.of(2026, 5, 20)).first { it.cadence == Cadence.MONTHLY }
        assertEquals(-4999, gym.typicalAmountCents)
        assertNull(gym.amountChange, "a single odd charge is an outlier, not a new price level")
    }

    // -- Life and death of a series -------------------------------------------------------------

    @Test
    fun `a series that stops is marked ended and drops out of the forecast`() {
        val cancelled = (1..4).map { m -> tx(-1299, LocalDate.of(2026, m, 8), "Streamingdienst Muster") }
        // The account keeps being used afterwards, so the vault's data horizon really is September:
        // weekly groceries at seven different sums, which match no cadence and form no series.
        val other = (0 until 36).map { i -> tx(-2000L - (i % 7) * 1300, LocalDate.of(2026, 1, 5).plusDays(i * 7L), "REWE.Markt/DE") }
        val series = Recurring.detect(cancelled + other, asOf = LocalDate.of(2026, 9, 20))
        val sub = series.single { it.merchantKey == "STREAMINGDIENST" }
        assertTrue(!sub.isActive, "last seen in April, five months before the reference date")
        assertEquals(LocalDate.of(2026, 4, 8), sub.lastDate)
        val f = Recurring.forecast(series, from = LocalDate.of(2026, 10, 1), months = 6)
        assertEquals(0, f.sumOf { it.expenseCents }, "a cancelled subscription is not projected")
        assertEquals(0, Recurring.monthlyFixed(series).expenseCents, "nor is it part of a typical month")
    }

    @Test
    fun `a series still within its grace window stays active`() {
        // Last seen six weeks ago: one occurrence is overdue, but billing-day drift and a skipped month
        // are normal — the series keeps its place in the forecast.
        val txns = (1..7).map { m -> tx(-699, LocalDate.of(2026, m, 3), "Cloudspeicher Muster") } +
            listOf(tx(-3450, LocalDate.of(2026, 8, 18), "REWE.Markt/DE"))
        val sub = Recurring.detect(txns, asOf = LocalDate.of(2026, 8, 18)).single { it.merchantKey == "CLOUDSPEICHER" }
        assertTrue(sub.isActive)
    }

    @Test
    fun `a series that resumes after a gap is active again`() {
        val txns = (1..3).map { m -> tx(-2500, LocalDate.of(2026, m, 5), "Zeitschrift Muster") } +
            (7..9).map { m -> tx(-2500, LocalDate.of(2026, m, 5), "Zeitschrift Muster") }
        val s = Recurring.detect(txns, asOf = LocalDate.of(2026, 9, 20)).single()
        assertTrue(s.isActive, "it is being paid again")
        assertEquals(6, s.occurrences)
        assertEquals(LocalDate.of(2026, 10, 5), s.nextExpectedDate)
    }

    @Test
    fun `a yearly series is not stale after eleven months`() {
        val yearly = (2024..2026).map { y -> tx(-289_00, LocalDate.of(y, 3, 1), "Versicherung Muster") }
        val keepsGoing = { d: LocalDate -> tx(-3450, d, "REWE.Markt/DE") }
        val elevenMonths = Recurring.detect(yearly + keepsGoing(LocalDate.of(2027, 2, 1)), asOf = LocalDate.of(2027, 2, 1))
            .single { it.cadence == Cadence.YEARLY }
        assertTrue(elevenMonths.isActive, "a yearly bill 11 months on is simply not due yet")
        assertEquals(LocalDate.of(2027, 3, 1), elevenMonths.nextExpectedDate)

        val thirteenMonths = Recurring.detect(yearly + keepsGoing(LocalDate.of(2027, 4, 5)), asOf = LocalDate.of(2027, 4, 5))
            .single { it.cadence == Cadence.YEARLY }
        assertTrue(!thirteenMonths.isActive, "a yearly bill a month past due has lapsed")
    }

    @Test
    fun `how long an ended series is kept on the list scales with its cadence`() {
        val ended = LocalDate.of(2026, 3, 1)
        fun forget(cadence: Cadence) = RecurringSeries("X", "x", null, cadence, -1000, ended, ended, 3, isActive = false).forgettableAfter
        // Monthly: the floor of half a year — a few weeks would be too little to notice.
        assertEquals(ended.plusDays(180), forget(Cadence.MONTHLY))
        // Quarterly and yearly outgrow the floor: twice the time it took to declare them dead.
        assertEquals(ended.plusDays(224), forget(Cadence.QUARTERLY))
        assertEquals(ended.plusDays(772), forget(Cadence.YEARLY))
        assertTrue(forget(Cadence.MONTHLY) < forget(Cadence.QUARTERLY) && forget(Cadence.QUARTERLY) < forget(Cadence.YEARLY))
    }

    @Test
    fun `staleness is measured against the data, not the wall clock`() {
        // The user has not imported a statement since May. Nothing is known about June onwards, so no
        // series may be declared dead — otherwise a late import would empty the whole forecast.
        val txns = (1..5).map { m -> tx(-1299, LocalDate.of(2026, m, 8), "Streamingdienst Muster") }
        val s = Recurring.detect(txns, asOf = LocalDate.of(2026, 12, 1)).single()
        assertTrue(s.isActive)
    }

    // -- Consumers ------------------------------------------------------------------------------

    @Test
    fun `variable spending recognises payments at an old price level as fixed`() {
        // The rent went up mid-year. Its earlier payments must still count as a fixed cost, or the
        // variable-spending mean and sigma inherit a rent that was never discretionary.
        val rent = (1..6).map { m -> tx(-850_00, LocalDate.of(2026, m, 1), "Miete Musterwohnung") } +
            (7..9).map { m -> tx(-1000_00, LocalDate.of(2026, m, 1), "Miete Musterwohnung") }
        // 200€ of groceries a month, split over two irregular trips so they form no cadence.
        val groceries = (1..9).flatMap { m -> listOf(tx(-100_00, LocalDate.of(2026, m, 4), "REWE.Markt/DE"), tx(-100_00, LocalDate.of(2026, m, 19), "REWE.Markt/DE")) }
        val series = Recurring.detect(rent + groceries, asOf = LocalDate.of(2026, 9, 20))
        val v = Recurring.variableMonthlySpending(rent + groceries, series)
        assertEquals(9, v.sampleMonths)
        assertEquals(200_00, v.meanCents, "only the groceries are variable")
        assertEquals(0, v.stdDevCents)
    }

    @Test
    fun `a hand-authored series is never aged out of the forecast`() {
        // Manual series (`recurringManual`) carry the user's own amount and next date. The staleness
        // rule is about *detected* history and must not overrule what the user declared.
        val manual = RecurringSeries(
            merchantKey = "manual:abc",
            label = "Vereinsbeitrag",
            categoryId = null,
            cadence = Cadence.MONTHLY,
            typicalAmountCents = -1500,
            lastDate = LocalDate.of(2020, 1, 1),
            nextExpectedDate = LocalDate.of(2026, 10, 1),
            occurrences = 0,
        )
        assertTrue(manual.isActive, "hand-authored series default to active")
        val f = Recurring.forecast(listOf(manual), from = LocalDate.of(2026, 10, 1), months = 3)
        assertTrue(f.all { it.expenseCents == 1500L })
        assertEquals(1500, Recurring.monthlyFixed(listOf(manual)).expenseCents)
    }

    @Test
    fun `detection is unaffected by hidden or renamed series`() {
        // Overrides live in the vault and are applied on top of detection by the dashboard; detection
        // itself must keep reporting the same merchant keys so an override never loses its anchor.
        val txns = (1..4).map { m -> tx(-1099, LocalDate.of(2026, m, 8), "Spotify/Stockholm/../SE") }
        val keys = Recurring.detect(txns, asOf = LocalDate.of(2026, 4, 20)).map { it.merchantKey }
        assertEquals(listOf("SPOTIFY"), keys)
    }
}
