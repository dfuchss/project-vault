package org.fuchss.projectvault.app

// Strings for the main screen: import status/progress, and the delete-account / undo-import /
// reclassify confirmations.

// -- Empty states / status ---------------------------------------------------
val Strings.addAccountToStart get() = translate { en("Add an account to get started."); de("Füge ein Konto hinzu, um zu beginnen.") }
val Strings.selectAccount get() = translate { en("Select an account."); de("Wähle ein Konto.") }
fun Strings.readingStatements(n: Int) = translate {
    en("Reading $n statement${if (n == 1) "" else "s"}…")
    de("Lese $n ${if (n == 1) "Auszug" else "Auszüge"}…")
}
fun Strings.importFailed(message: String?) = translate { en("Import failed: $message"); de("Import fehlgeschlagen: $message") }

/** A re-classification that threw. The pass is atomic, so this means "nothing changed", not "half done". */
fun Strings.reclassifyFailed(message: String?) = translate {
    en("Re-classification failed — nothing was changed: $message")
    de("Neuklassifizierung fehlgeschlagen — nichts wurde geändert: $message")
}
fun Strings.filesSkipped(n: Int) = translate {
    en("$n file(s) could not be parsed and were skipped.")
    de("$n Datei(en) konnten nicht gelesen werden und wurden übersprungen.")
}
val Strings.categorizing get() = translate { en("Categorizing…"); de("Kategorisiere…") }
fun Strings.categorizeResult(committed: Int, suggested: Int) = translate {
    en("Applied $committed rule match(es); $suggested suggestion(s) to review.")
    de("$committed Regeltreffer angewendet; $suggested Vorschlag/Vorschläge zu prüfen.")
}
fun Strings.importingItems(n: Int) = translate { en("Importing $n item(s)…"); de("Importiere $n Eintrag/Einträge…") }
fun Strings.importResult(inserted: Int, source: String, categorized: Int, toReview: Int) = translate {
    en("Imported $inserted item(s) from $source; $categorized categorized, $toReview to review.")
    de("$inserted Eintrag/Einträge aus $source importiert; $categorized kategorisiert, $toReview zu prüfen.")
}
val Strings.importStatementsDialogTitle get() = translate {
    en("Import statements (PDF or CSV) — select one or more")
    de("Auszüge importieren (PDF oder CSV) — eine oder mehrere auswählen")
}

// -- Delete account / undo import dialogs -----------------------------------
val Strings.deleteAccountTitle get() = translate { en("Delete account?"); de("Konto löschen?") }
fun Strings.deleteAccountBody(name: String) = translate {
    en("Delete \"$name\" and all its transactions, holdings and import history? This can't be undone.")
    de("„$name“ mit allen Umsätzen, Beständen und dem Importverlauf löschen? Das kann nicht rückgängig gemacht werden.")
}
val Strings.undoImportTitle get() = translate { en("Undo this import?"); de("Diesen Import rückgängig machen?") }
fun Strings.undoImportBody(source: String, items: Int) = translate {
    en("Remove \"$source\" and the $items item(s) it added? This can't be undone.")
    de("„$source“ und die $items dadurch hinzugefügten Einträge entfernen? Das kann nicht rückgängig gemacht werden.")
}
fun Strings.removedImport(source: String) = translate { en("Removed import \"$source\"."); de("Import „$source“ entfernt.") }

// -- Reclassify confirmation -------------------------------------------------
val Strings.thisCategoryFallback get() = translate { en("this category"); de("diese Kategorie") }
val Strings.applyToSimilarTitle get() = translate { en("Apply to matching transactions?"); de("Auf passende Umsätze anwenden?") }

// The dialog never says "this merchant": the keyword is the merchant only when the statement carries
// one (and a word from the purpose otherwise), it is matched against counterparty and purpose alike,
// and the user can edit it — so the copy talks about the keyword it shows, and nothing else.
val Strings.applyToSimilarIntro get() = translate {
    en("This correction can be remembered as a rule. Check the keyword it should match on — it is only a guess at what you mean.")
    de("Diese Korrektur kann als Regel gemerkt werden. Prüfe das Stichwort, auf das sie passen soll — es ist nur eine Vermutung.")
}
fun Strings.applyToSimilarCount(otherCount: Int, categoryName: String) = translate {
    en("$otherCount other transaction(s) contain it and aren't set manually — they would be set to \"$categoryName\".")
    de("$otherCount weitere(r) Umsatz/Umsätze enthalten es und sind nicht manuell gesetzt — sie würden auf „$categoryName“ gesetzt.")
}
fun Strings.applyToSimilarNoOthers(categoryName: String) = translate {
    en("No other transaction contains it. The rule → \"$categoryName\" is still learned, for future imports.")
    de("Kein weiterer Umsatz enthält es. Die Regel → „$categoryName“ wird trotzdem für künftige Importe gemerkt.")
}
val Strings.applyToSimilarNeedsKeyword get() = translate {
    en("Enter at least two characters.")
    de("Gib mindestens zwei Zeichen ein.")
}
val Strings.rememberRuleOnly get() = translate { en("Remember rule"); de("Regel merken") }
fun Strings.applyToAll(total: Int) = translate { en("Apply to all ($total)"); de("Auf alle anwenden ($total)") }
val Strings.onlyThisOne get() = translate { en("Only this one"); de("Nur diesen") }
