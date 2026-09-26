package org.fuchss.projectvault.app

// Strings for the cross-account review inbox (sidebar entry, header, empty states).

val Strings.review get() = translate { en("Review"); de("Prüfen") }
fun Strings.reviewNav(pending: Int) = translate {
    en(if (pending == 0) "Review" else "Review ($pending)")
    de(if (pending == 0) "Prüfen" else "Prüfen ($pending)")
}
fun Strings.reviewPending(n: Int) = translate { en("$n to file"); de("$n offen") }

// Says *why* this list matters, not just what it is: an unfiled internal transfer is counted as
// income on one account and as expense on the other until it carries a category.
val Strings.reviewSubtitle get() = translate {
    en("Everything still to categorize, across all accounts you can see. Uncategorized transfers are counted as income and expense on the dashboard until they are filed.")
    de("Alles noch zu Kategorisierende über alle sichtbaren Konten hinweg. Nicht kategorisierte Umbuchungen zählen in der Übersicht als Einnahme und als Ausgabe, bis sie eingeordnet sind.")
}
val Strings.reviewAllClear get() = translate {
    en("Nothing to review — every transaction has a category.")
    de("Nichts zu prüfen — alle Umsätze sind kategorisiert.")
}
val Strings.reviewNoAccounts get() = translate {
    en("No accounts with transactions yet.")
    de("Noch keine Konten mit Umsätzen.")
}
