package org.fuchss.projectvault.app

// Strings for the shared transaction list: sorting, the amount/date range filters, multi-select with
// bulk category assignment (and its undo), and the CSV export.

// -- Sorting -----------------------------------------------------------------
val Strings.sortPrefix get() = translate { en("Sort"); de("Sortierung") }
internal fun Strings.sortLabel(sort: TxnSort) = when (sort) {
    TxnSort.DATE_DESC -> translate { en("Newest first"); de("Neueste zuerst") }
    TxnSort.DATE_ASC -> translate { en("Oldest first"); de("Älteste zuerst") }
    TxnSort.AMOUNT_DESC -> translate { en("Amount ↓"); de("Betrag ↓") }
    TxnSort.AMOUNT_ASC -> translate { en("Amount ↑"); de("Betrag ↑") }
}

// -- Amount / date range filters ---------------------------------------------
val Strings.moreFilters get() = translate { en("Amount & date"); de("Betrag & Datum") }
val Strings.moreFiltersActive get() = translate { en("Amount & date •"); de("Betrag & Datum •") }

// The bounds compare the amount without its sign, so "50 – 200" finds a 120 € expense as readily as
// a 120 € credit; the label says "amount", and the placeholders show the expected notation.
val Strings.amountRangeLabel get() = translate { en("Amount"); de("Betrag") }
val Strings.dateRangeLabel get() = translate { en("Date"); de("Datum") }
val Strings.minPlaceholder get() = translate { en("min"); de("min") }
val Strings.maxPlaceholder get() = translate { en("max"); de("max") }
val Strings.datePlaceholder get() = translate { en("dd.mm.yyyy"); de("tt.mm.jjjj") }

// -- Inspector gutter --------------------------------------------------------
// Shown in the reserved detail column while no row is selected, so the space reads as "the details
// go here" rather than as an unexplained margin.
val Strings.selectTransactionHint get() = translate {
    en("Select a transaction to see its details and set its category.")
    de("Wähle einen Umsatz, um Details zu sehen und die Kategorie zu setzen.")
}

// -- Multi-select / bulk assignment ------------------------------------------
fun Strings.selectedCount(n: Int) = translate { en("$n selected"); de("$n ausgewählt") }
val Strings.categoryPrefixShort get() = translate { en("Set"); de("Setzen") }
val Strings.assignCategory get() = translate { en("Category…"); de("Kategorie…") }
val Strings.selectAllMatching get() = translate { en("Select all matching"); de("Alle passenden auswählen") }
val Strings.clearSelection get() = translate { en("Clear"); de("Aufheben") }

// Why the picker is short for a mixed selection: only transfers are legitimate for incoming *and*
// outgoing money (see CategorySign in :core:model). Said out loud, rather than dropping rows quietly.
val Strings.mixedSignNote get() = translate {
    en("Mixed credits and debits — only transfer categories fit every selected row.")
    de("Ein- und Ausgänge gemischt — nur Umbuchungs-Kategorien passen zu allen ausgewählten Zeilen.")
}
val Strings.noCommonCategory get() = translate {
    en("No category fits every selected transaction.")
    de("Keine Kategorie passt zu allen ausgewählten Umsätzen.")
}
fun Strings.bulkAssigned(n: Int, category: String) = translate {
    en("$n transaction(s) set to \"$category\" (manual; no rule learned).")
    de("$n Umsatz/Umsätze auf „$category“ gesetzt (manuell; keine Regel gelernt).")
}
fun Strings.bulkUndone(n: Int) = translate {
    en("Undone — $n transaction(s) restored.")
    de("Rückgängig gemacht — $n Umsatz/Umsätze wiederhergestellt.")
}

// -- CSV export --------------------------------------------------------------
val Strings.exportCsvButton get() = translate { en("Export CSV"); de("CSV exportieren") }
val Strings.exportCsvDialogTitle get() = translate { en("Export transactions as CSV"); de("Umsätze als CSV exportieren") }
fun Strings.exportedRows(n: Int, file: String) = translate {
    en("Exported $n transaction(s) to $file.")
    de("$n Umsatz/Umsätze nach $file exportiert.")
}
fun Strings.exportFailed(message: String?) = translate { en("Export failed: $message"); de("Export fehlgeschlagen: $message") }

/** The CSV column titles, in the order [exportCsv] writes them. */
val Strings.exportColumns get() = listOf(
    translate { en("Booking date"); de("Buchungsdatum") },
    translate { en("Value date"); de("Wertstellung") },
    translate { en("Account"); de("Konto") },
    translate { en("Counterparty"); de("Zahlungspartner") },
    translate { en("Purpose"); de("Verwendungszweck") },
    translate { en("Type"); de("Art") },
    translate { en("Amount"); de("Betrag") },
    translate { en("Category"); de("Kategorie") },
)
