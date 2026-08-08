package org.fuchss.projectvault.app

// Strings produced by the import pipeline (non-composable): preview summaries and mismatch warnings.
// Read via `I18n.current` since this runs off the composition.

fun Strings.transactionsCount(n: Int) = translate { en("$n transactions"); de("$n Umsätze") }
fun Strings.newVsImported(newCount: Int, duplicates: Int) = translate {
    en(" ($newCount new, $duplicates already imported)")
    de(" ($newCount neu, $duplicates bereits importiert)")
}
val Strings.balanceReconciles get() = translate { en("reconciles"); de("stimmt überein") }
val Strings.balanceNotVerifiableShort get() = translate { en("not verifiable from this export"); de("aus diesem Export nicht prüfbar") }
val Strings.balanceDoesNotReconcile get() = translate { en("does NOT reconcile (review)"); de("stimmt NICHT überein (prüfen)") }
val Strings.balanceWord get() = translate { en(" - balance "); de(" - Saldo ") }
fun Strings.holdingsSummary(n: Int, reconciles: Boolean) = translate {
    en("$n holdings - total ${if (reconciles) "reconciles" else "does NOT reconcile (review)"}")
    de("$n Bestände - Gesamt ${if (reconciles) "stimmt überein" else "stimmt NICHT überein (prüfen)"}")
}
fun Strings.snapshotExistsReplace(date: String) = translate {
    en("A snapshot for $date already exists and will be replaced.")
    de("Für $date existiert bereits ein Stand und wird ersetzt.")
}
fun Strings.allDuplicates(n: Int) = translate {
    en("Already imported: all $n transactions are duplicates (nothing new to add).")
    de("Bereits importiert: alle $n Umsätze sind Duplikate (nichts Neues hinzuzufügen).")
}
fun Strings.ibanMismatch(accountIban: String, statementIban: String) = translate {
    en("IBAN does not match: account $accountIban vs statement $statementIban")
    de("IBAN stimmt nicht überein: Konto $accountIban vs. Auszug $statementIban")
}
fun Strings.bankMismatch(accountBank: String, statementBank: String) = translate {
    en("Bank does not match: account \"$accountBank\" vs statement \"$statementBank\"")
    de("Bank stimmt nicht überein: Konto „$accountBank“ vs. Auszug „$statementBank“")
}
