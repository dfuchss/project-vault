package org.fuchss.projectvault.app

// Strings for the account detail screen: header, transaction filters/list, the inspector, the import
// history dialog and the depot pane.

val Strings.balanceLabel get() = translate { en("Balance"); de("Kontostand") }
val Strings.portfolioValueLabel get() = translate { en("Portfolio value"); de("Depotwert") }
val Strings.importStatementButton get() = translate { en("Import statement…"); de("Auszug importieren…") }
fun Strings.historyButton(count: Int) = translate { en("History ($count)"); de("Verlauf ($count)") }
fun Strings.transactionsHeader(countLabel: String) = translate { en("Transactions ($countLabel)"); de("Umsätze ($countLabel)") }
fun Strings.countOf(shown: Int, total: Int) = translate { en("$shown of $total"); de("$shown von $total") }
fun Strings.categorizeN(n: Int) = translate { en("Categorize $n"); de("$n kategorisieren") }
val Strings.noTransactionsImport get() = translate { en("No transactions yet. Import a statement."); de("Noch keine Umsätze. Importiere einen Auszug.") }
val Strings.noTransactionsMatchFilter get() = translate { en("No transactions match the filter."); de("Keine Umsätze passen zum Filter.") }
val Strings.assignOwner get() = translate { en("+ Assign owner"); de("+ Inhaber zuweisen") }
val Strings.valueDateShort get() = translate { en("Value"); de("Wert") }

// filters
val Strings.filterAll get() = translate { en("All"); de("Alle") }
val Strings.filterUncategorized get() = translate { en("Uncategorized"); de("Nicht kategorisiert") }
val Strings.filterToReview get() = translate { en("To review"); de("Zu prüfen") }
val Strings.filterCategory get() = translate { en("Category"); de("Kategorie") }
val Strings.filterAnyTime get() = translate { en("Any time"); de("Beliebiger Zeitraum") }
val Strings.searchPlaceholder get() = translate { en("Search counterparty or purpose"); de("Zahlungspartner oder Verwendungszweck suchen") }

// inspector
val Strings.transaction get() = translate { en("Transaction"); de("Umsatz") }
val Strings.amount get() = translate { en("Amount"); de("Betrag") }
val Strings.bookingDate get() = translate { en("Booking date"); de("Buchungsdatum") }
val Strings.valueDate get() = translate { en("Value date"); de("Wertstellung") }
val Strings.type get() = translate { en("Type"); de("Art") }
val Strings.category get() = translate { en("Category"); de("Kategorie") }
val Strings.uncategorized get() = translate { en("Uncategorized"); de("Nicht kategorisiert") }
val Strings.manageCategoriesMenu get() = translate { en("⚙ Manage categories…"); de("⚙ Kategorien verwalten…") }
val Strings.suggested get() = translate { en("Suggested  "); de("Vorschlag  ") }
val Strings.purpose get() = translate { en("Purpose"); de("Verwendungszweck") }
val Strings.origin get() = translate { en("Origin"); de("Herkunft") }
val Strings.source get() = translate { en("Source"); de("Quelle") }
val Strings.statement get() = translate { en("Statement"); de("Auszug") }
val Strings.period get() = translate { en("Period"); de("Zeitraum") }
val Strings.imported get() = translate { en("Imported"); de("Importiert") }
val Strings.reconciled get() = translate { en("Reconciled"); de("Abgeglichen") }
val Strings.yes get() = translate { en("yes"); de("ja") }
val Strings.no get() = translate { en("no"); de("nein") }

// import history dialog
val Strings.importHistoryTitle get() = translate { en("Import history"); de("Importverlauf") }
val Strings.nothingImportedYet get() = translate { en("Nothing imported yet."); de("Noch nichts importiert.") }
fun Strings.importHistorySubtitle(items: Int, whenText: String, reconciled: Boolean) = translate {
    en("$items items · $whenText · ${if (reconciled) "reconciled" else "unreconciled"}")
    de("$items Einträge · $whenText · ${if (reconciled) "abgeglichen" else "nicht abgeglichen"}")
}

// depot
val Strings.holdings get() = translate { en("Holdings"); de("Bestände") }
fun Strings.snapshotLabel(dateOrDash: String) = translate { en("Snapshot: $dateOrDash"); de("Stand: $dateOrDash") }
val Strings.noHoldingsImport get() = translate { en("No holdings yet. Import a Depotauszug."); de("Noch keine Bestände. Importiere einen Depotauszug.") }
