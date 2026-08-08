package org.fuchss.projectvault.app

import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.Bank
import org.fuchss.projectvault.model.CategoryKind

// Strings for the dialogs: profiles, categories, add-account and the import review.

// -- Profiles ----------------------------------------------------------------
val Strings.addProfileTitle get() = translate { en("Add profile"); de("Profil hinzufügen") }
val Strings.manageProfilesTitle get() = translate { en("Manage profiles"); de("Profile verwalten") }
fun Strings.deleteProfileTitle(name: String) = translate { en("Delete profile “$name”?"); de("Profil „$name“ löschen?") }
val Strings.deleteProfileNoAccounts get() = translate {
    en("This profile owns no accounts. It will be removed.")
    de("Diesem Profil gehören keine Konten. Es wird entfernt.")
}
fun Strings.deleteProfileBody(accounts: Int) = translate {
    en("It will be removed as an owner from $accounts account(s); those accounts and their data stay.")
    de("Es wird als Inhaber von $accounts Konto/Konten entfernt; diese Konten und ihre Daten bleiben erhalten.")
}
fun Strings.ownersOfTitle(accountName: String) = translate { en("Owners of “$accountName”"); de("Inhaber von „$accountName“") }
val Strings.noProfilesAddFirst get() = translate {
    en("No profiles yet — add one first, then assign it here.")
    de("Noch keine Profile — lege zuerst eins an und weise es dann hier zu.")
}
val Strings.tickOwners get() = translate {
    en("Tick everyone who owns this account (joint = several).")
    de("Markiere alle, denen dieses Konto gehört (gemeinsam = mehrere).")
}

// -- Categories --------------------------------------------------------------
val Strings.newCategoryTitle get() = translate { en("New category"); de("Neue Kategorie") }
val Strings.editCategoryTitle get() = translate { en("Edit category"); de("Kategorie bearbeiten") }
val Strings.newCategoryButton get() = translate { en("＋  New category"); de("＋  Neue Kategorie") }
fun Strings.kindButton(kindLabel: String) = translate { en("Kind: $kindLabel"); de("Art: $kindLabel") }
val Strings.keywordsOptional get() = translate { en("Keywords (optional)"); de("Schlüsselwörter (optional)") }
val Strings.keywords get() = translate { en("Keywords"); de("Schlüsselwörter") }
val Strings.keywordsAddHelp get() = translate {
    en("Comma-separated. Transactions containing one auto-match this category.")
    de("Kommagetrennt. Umsätze, die eines enthalten, passen automatisch zu dieser Kategorie.")
}
val Strings.keywordsEditHelp get() = translate {
    en("Comma-separated. Replaces this category's current keywords.")
    de("Kommagetrennt. Ersetzt die aktuellen Schlüsselwörter dieser Kategorie.")
}
val Strings.manageCategoriesTitle get() = translate { en("Manage categories"); de("Kategorien verwalten") }
fun Strings.deleteCategoryTitle(name: String) = translate { en("Delete category “$name”?"); de("Kategorie „$name“ löschen?") }
val Strings.deleteCategoryBody get() = translate {
    en("Transactions in this category become uncategorized and its learned rules are removed. This can't be undone.")
    de("Umsätze in dieser Kategorie werden nicht mehr kategorisiert und ihre gelernten Regeln entfernt. Das kann nicht rückgängig gemacht werden.")
}
fun Strings.disableCategoryTitle(name: String) = translate { en("Disable “$name”?"); de("„$name“ deaktivieren?") }
fun Strings.disableCategoryBody(count: Int) = translate {
    en(
        "$count transaction${if (count == 1) "" else "s"} in this category will be reassigned to Sonstiges, " +
            "and it will be hidden from the pickers and suggestions. You can re-enable it later.",
    )
    de(
        "$count Umsatz/Umsätze in dieser Kategorie werden Sonstiges zugewiesen " +
            "und die Kategorie wird aus Auswahl und Vorschlägen ausgeblendet. Du kannst sie später wieder aktivieren.",
    )
}
val Strings.categoryManagerHelp get() = translate {
    en(
        "Add categories with optional keywords, or disable expense categories you don't use — disabled ones are " +
            "hidden from the pickers and suggestions, and their transactions move to Sonstiges. You can re-enable them anytime.",
    )
    de(
        "Füge Kategorien mit optionalen Schlüsselwörtern hinzu oder deaktiviere nicht genutzte Ausgabenkategorien — " +
            "deaktivierte werden aus Auswahl und Vorschlägen ausgeblendet und ihre Umsätze zu Sonstiges verschoben. Jederzeit wieder aktivierbar.",
    )
}
fun Strings.kindLabel(kind: CategoryKind) = when (kind) {
    CategoryKind.INCOME -> translate { en("Income"); de("Einnahmen") }
    CategoryKind.EXPENSE -> translate { en("Expenses"); de("Ausgaben") }
    CategoryKind.TRANSFER -> translate { en("Transfers"); de("Umbuchungen") }
}
val Strings.statusDisabled get() = translate { en("Disabled"); de("Deaktiviert") }
val Strings.statusBuiltIn get() = translate { en("Built-in"); de("Vorgegeben") }

// -- Add account -------------------------------------------------------------
val Strings.addAccountTitle get() = translate { en("Add account"); de("Konto hinzufügen") }
fun Strings.typeButton(typeLabel: String) = translate { en("Type: $typeLabel"); de("Typ: $typeLabel") }
fun Strings.bankButton(bankLabel: String) = translate { en("Bank: $bankLabel"); de("Bank: $bankLabel") }
val Strings.ibanOptional get() = translate { en("IBAN (optional)"); de("IBAN (optional)") }
val Strings.ownersJoint get() = translate { en("Owners (joint = several)"); de("Inhaber (gemeinsam = mehrere)") }

/** What the chosen bank/type combination can import — only supported combinations are offered. */
fun Strings.importHint(bank: Bank, type: AccountType) = when (type) {
    AccountType.GIRO, AccountType.TAGESGELD -> translate {
        en("Import: ${bank.displayName} Kontoauszug (PDF) or Umsatzliste (CSV)")
        de("Import: ${bank.displayName} Kontoauszug (PDF) oder Umsatzliste (CSV)")
    }
    AccountType.KREDITKARTE -> translate {
        en("Import: ${bank.displayName} Kreditkartenabrechnung (PDF) or Umsatzliste (CSV)")
        de("Import: ${bank.displayName} Kreditkartenabrechnung (PDF) oder Umsatzliste (CSV)")
    }
    AccountType.DEPOT -> translate {
        en("Import: ${bank.displayName} Depotauszug (PDF) or Depotübersicht (CSV)")
        de("Import: ${bank.displayName} Depotauszug (PDF) oder Depotübersicht (CSV)")
    }
}

// -- Import review -----------------------------------------------------------
val Strings.reviewImportTitle get() = translate { en("Review import"); de("Import prüfen") }
fun Strings.reviewImportMultiTitle(files: Int) = translate { en("Review import · $files files"); de("Import prüfen · $files Dateien") }
fun Strings.itemsAcrossFiles(items: Int, files: Int) = translate { en("$items items across $files files"); de("$items Einträge in $files Dateien") }
fun Strings.filesLabel(files: Int) = translate { en("$files files"); de("$files Dateien") }
val Strings.doesNotReconcileImportable get() = translate {
    en("Does not reconcile (may be incomplete/redacted) — you can still import.")
    de("Stimmt nicht überein (evtl. unvollständig/geschwärzt) — Import trotzdem möglich.")
}
val Strings.balanceNotVerifiable get() = translate {
    en("Balance not verifiable from this export (no opening balance) — safe to import.")
    de("Saldo aus diesem Export nicht prüfbar (kein Anfangssaldo) — Import unbedenklich.")
}
