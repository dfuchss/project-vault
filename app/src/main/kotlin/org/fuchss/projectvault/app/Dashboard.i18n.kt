package org.fuchss.projectvault.app

import org.fuchss.projectvault.analytics.Cadence

// Strings for the dashboard: stat cards, the spending/cashflow/recurring/forecast cards, the recurring
// dialogs, and the chart tooltip.

// -- Stat cards --------------------------------------------------------------
val Strings.netWorth get() = translate { en("Net worth"); de("Vermögen") }
val Strings.income get() = translate { en("Income"); de("Einnahmen") }
val Strings.expense get() = translate { en("Expense"); de("Ausgaben") }
val Strings.net get() = translate { en("Net"); de("Netto") }
val Strings.total get() = translate { en("Total"); de("Gesamt") }
val Strings.allTime get() = translate { en("All time"); de("Gesamter Zeitraum") }
// Short "estimated" marker for the expected-income card; kept compact so it never widens the card.
val Strings.estTag get() = translate { en("≈ EST"); de("≈ CA.") }

// -- Period selector ---------------------------------------------------------
fun Strings.lastMonths(n: Int) = translate { en("Last $n months"); de("Letzte $n Monate") }
val Strings.yearToDate get() = translate { en("Year to date"); de("Laufendes Jahr") }
val Strings.customRange get() = translate { en("Custom range…"); de("Eigener Zeitraum…") }
val Strings.customRangeTitle get() = translate { en("Custom date range"); de("Eigener Zeitraum") }
val Strings.customRangeHint get() = translate {
    en("Leave a field empty for an open end.")
    de("Leer lassen für ein offenes Ende.")
}
val Strings.fromDateLabel get() = translate { en("From (YYYY-MM-DD)"); de("Von (JJJJ-MM-TT)") }
val Strings.toDateLabel get() = translate { en("To (YYYY-MM-DD)"); de("Bis (JJJJ-MM-TT)") }
fun Strings.rangeBetween(from: String, to: String) = translate { en("$from – $to"); de("$from – $to") }
fun Strings.rangeFrom(from: String) = translate { en("from $from"); de("ab $from") }
fun Strings.rangeUntil(to: String) = translate { en("until $to"); de("bis $to") }

/** The label on the period pill — what the dashboard's period-dependent panels currently cover. */
internal fun Strings.periodLabel(period: DashboardPeriod): String = when (period.kind) {
    PeriodKind.ALL_TIME -> allTime
    PeriodKind.LAST_3M -> lastMonths(3)
    PeriodKind.LAST_6M -> lastMonths(6)
    PeriodKind.LAST_12M -> lastMonths(12)
    PeriodKind.YEAR_TO_DATE -> yearToDate
    PeriodKind.YEAR -> period.year?.toString() ?: allTime
    PeriodKind.MONTH -> period.month?.let(::formatYearMonth) ?: allTime
    PeriodKind.CUSTOM -> {
        val from = period.customFrom?.let(::formatLocalDate)
        val to = period.customTo?.let(::formatLocalDate)
        when {
            from != null && to != null -> rangeBetween(from, to)
            from != null -> rangeFrom(from)
            to != null -> rangeUntil(to)
            else -> allTime
        }
    }
}

// -- Cards -------------------------------------------------------------------
fun Strings.spendingByCategory(period: String) = translate { en("Spending by category · $period"); de("Ausgaben nach Kategorie · $period") }
val Strings.noSpendingYet get() = translate { en("No spending yet."); de("Noch keine Ausgaben.") }
val Strings.monthlyCashFlow get() = translate { en("Monthly cash flow"); de("Monatlicher Cashflow") }
// Panels whose statistics are only meaningful over a trailing window say so, so nobody reads them as
// following the period pill.
val Strings.trailingWindowNote get() = translate {
    en("Last 12 months · not affected by the period")
    de("Letzte 12 Monate · unabhängig vom Zeitraum")
}
val Strings.forecastWindowNote get() = translate {
    en("From the last 12 months · not affected by the period")
    de("Aus den letzten 12 Monaten · unabhängig vom Zeitraum")
}
val Strings.noTransactionsYetImport get() = translate { en("No transactions yet. Import a statement."); de("Noch keine Umsätze. Importiere einen Auszug.") }
val Strings.netTrend get() = translate { en("Net trend"); de("Netto-Trend") }
val Strings.recurring get() = translate { en("Recurring"); de("Wiederkehrend") }
val Strings.tapToEdit get() = translate { en("tap to edit"); de("zum Bearbeiten tippen") }
fun Strings.hiddenCount(n: Int) = translate { en("$n hidden"); de("$n ausgeblendet") }
val Strings.noRecurringYet get() = translate {
    en("No recurring transactions detected yet — add one with “+ Add”.")
    de("Noch keine wiederkehrenden Umsätze erkannt — füge eins mit „+ Neu“ hinzu.")
}
val Strings.manualBadge get() = translate { en("manual"); de("manuell") }
fun Strings.nextOccurrence(date: String) = translate { en("next $date"); de("nächste $date") }
// A series whose occurrences stopped coming: shown dimmed with the month it was last seen, never
// silently dropped — a cancelled contract should be visible as cancelled.
fun Strings.endedLastSeen(month: String) = translate { en("ended · last seen $month"); de("beendet · zuletzt $month") }
fun Strings.endedExcludedNote(n: Int) = translate {
    en(if (n == 1) "1 series has ended (dimmed) and is left out of the forecast." else "$n series have ended (dimmed) and are left out of the forecast.")
    de(if (n == 1) "1 Serie ist beendet (ausgegraut) und fließt nicht in die Prognose ein." else "$n Serien sind beendet (ausgegraut) und fließen nicht in die Prognose ein.")
}
fun Strings.endedExplanation(month: String) = translate {
    en("No occurrence since $month, well past its due date — treated as ended and excluded from the forecast. It comes back on its own if it is paid again; hide it to remove it for good, or add it by hand to keep projecting it.")
    de("Seit $month keine Buchung mehr, deutlich über den fälligen Termin hinaus — gilt als beendet und fließt nicht in die Prognose ein. Sie kehrt von selbst zurück, sobald wieder gezahlt wird; zum endgültigen Entfernen ausblenden oder von Hand hinzufügen, um sie weiter einzurechnen.")
}
// A level change inside one series (rent increase, price hike) — the forecast uses the new level.
fun Strings.amountChanged(previous: String, current: String, since: String) = translate {
    en("$previous → $current since $since")
    de("$previous → $current seit $since")
}
val Strings.showLess get() = translate { en("Show less"); de("Weniger anzeigen") }
fun Strings.showAll(n: Int) = translate { en("Show all $n"); de("Alle $n anzeigen") }
val Strings.forecastTitle get() = translate { en("Forecast · next 6 months"); de("Prognose · nächste 6 Monate") }
fun Strings.fixedSummary(income: String, expense: String, net: String) = translate {
    en("Fixed income $income · fixed costs $expense · free $net / month")
    de("Feste Einnahmen $income · feste Kosten $expense · frei $net / Monat")
}
fun Strings.variableSummary(mean: String, stdDev: String, months: Int) = translate {
    en("Variable spending ø $mean ± $stdDev / month (last $months mo)")
    de("Variable Ausgaben ø $mean ± $stdDev / Monat (letzte $months Mon.)")
}
val Strings.notEnoughForecast get() = translate { en("Not enough recurring data to forecast yet."); de("Noch nicht genug wiederkehrende Daten für eine Prognose.") }
val Strings.nowLabel get() = translate { en("now"); de("jetzt") }
val Strings.projectedBalanceLabel get() = translate {
    en("Projected balance (± 1σ variable spend)")
    de("Prognostizierter Saldo (± 1σ variable Ausgaben)")
}
fun Strings.projectedApprox(balance: String, byLabel: String, deltaSigned: String) = translate {
    en("≈ $balance by $byLabel · $deltaSigned over 6 months")
    de("≈ $balance bis $byLabel · $deltaSigned über 6 Monate")
}
fun Strings.projectedRange(low: String, high: String) = translate { en("range $low … $high"); de("Spanne $low … $high") }
val Strings.shortfallWarning get() = translate {
    en("⚠ Could go negative within the range — possible cash shortfall.")
    de("⚠ Könnte im Zeitraum negativ werden — möglicher Liquiditätsengpass.")
}

// -- Spending donut & drill-down ---------------------------------------------
// The tail of the category list is merged into one slice instead of being dropped, so the ring always
// adds up to the total shown in its centre.
fun Strings.otherCategories(n: Int) = translate { en("Other ($n categories)"); de("Weitere ($n Kategorien)") }
val Strings.clickCategoryHint get() = translate { en("click a category for details"); de("Kategorie für Details anklicken") }
fun Strings.categoryDetailSubtitle(period: String, count: Int) = translate {
    en("$period · $count transactions")
    de("$period · $count Umsätze")
}
val Strings.noTransactionsInPeriod get() = translate {
    en("No transactions in this period.")
    de("Keine Umsätze in diesem Zeitraum.")
}
val Strings.unknownCounterparty get() = translate { en("Unknown"); de("Unbekannt") }

// -- Recurring dialogs -------------------------------------------------------
val Strings.recurringSeriesTitle get() = translate { en("Recurring series"); de("Wiederkehrende Serie") }
fun Strings.detectedAs(label: String, cadence: String, amount: String) = translate {
    en("Detected as \"$label\" · $cadence · $amount")
    de("Erkannt als „$label“ · $cadence · $amount")
}
val Strings.hideFromRecurring get() = translate { en("Hide from recurring"); de("Aus Wiederkehrend ausblenden") }
val Strings.hiddenRecurringTitle get() = translate { en("Hidden recurring series"); de("Ausgeblendete wiederkehrende Serien") }
val Strings.nothingHidden get() = translate { en("Nothing hidden."); de("Nichts ausgeblendet.") }
val Strings.unhide get() = translate { en("Unhide"); de("Einblenden") }
val Strings.addRecurringPickTitle get() = translate { en("Add recurring · pick a transaction"); de("Wiederkehrend hinzufügen · Umsatz auswählen") }
val Strings.searchCounterparty get() = translate { en("Search counterparty"); de("Zahlungspartner suchen") }
val Strings.noMatchingTransactions get() = translate {
    en("No matching transactions to base a series on.")
    de("Keine passenden Umsätze als Grundlage für eine Serie.")
}
fun Strings.candidateSubtitle(count: Int, lastDate: String) = translate { en("${count}× · last $lastDate"); de("${count}× · zuletzt $lastDate") }
val Strings.addRecurringSeriesTitle get() = translate { en("Add recurring series"); de("Wiederkehrende Serie hinzufügen") }
val Strings.editRecurringSeriesTitle get() = translate { en("Edit recurring series"); de("Wiederkehrende Serie bearbeiten") }
val Strings.amountFromTransaction get() = translate { en("Amount (from the selected transaction)"); de("Betrag (aus dem ausgewählten Umsatz)") }
val Strings.nextDateLabel get() = translate { en("Next date (YYYY-MM-DD)"); de("Nächstes Datum (JJJJ-MM-TT)") }
val Strings.categoryPrefix get() = translate { en("Category"); de("Kategorie") }
fun Strings.cadenceLabel(cadence: Cadence) = when (cadence) {
    Cadence.MONTHLY -> translate { en("Monthly"); de("Monatlich") }
    Cadence.QUARTERLY -> translate { en("Quarterly"); de("Vierteljährlich") }
    Cadence.YEARLY -> translate { en("Yearly"); de("Jährlich") }
}

// -- Charts ------------------------------------------------------------------
fun Strings.expectedRange(low: String, high: String) = translate { en("exp. $low … $high"); de("erw. $low … $high") }
