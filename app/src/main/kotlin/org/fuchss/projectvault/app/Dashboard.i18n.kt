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

// -- Cards -------------------------------------------------------------------
fun Strings.spendingByCategory(period: String) = translate { en("Spending by category · $period"); de("Ausgaben nach Kategorie · $period") }
val Strings.noSpendingYet get() = translate { en("No spending yet."); de("Noch keine Ausgaben.") }
val Strings.monthlyCashFlow get() = translate { en("Monthly cash flow"); de("Monatlicher Cashflow") }
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
fun Strings.categoryButton(name: String) = translate { en("Category: $name"); de("Kategorie: $name") }
fun Strings.cadenceLabel(cadence: Cadence) = when (cadence) {
    Cadence.MONTHLY -> translate { en("Monthly"); de("Monatlich") }
    Cadence.QUARTERLY -> translate { en("Quarterly"); de("Vierteljährlich") }
    Cadence.YEARLY -> translate { en("Yearly"); de("Jährlich") }
}

// -- Charts ------------------------------------------------------------------
fun Strings.expectedRange(low: String, high: String) = translate { en("exp. $low … $high"); de("erw. $low … $high") }
