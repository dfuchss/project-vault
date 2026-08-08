package org.fuchss.projectvault.app

// Shared strings: generic actions/labels, the vault picker, the top bar and the sidebar sections.
// Translations use the co-located DSL — see [Strings].

// -- Generic actions / labels ------------------------------------------------
val Strings.add get() = translate { en("Add"); de("Hinzufügen") }
val Strings.addShort get() = translate { en("+ Add"); de("+ Neu") }
val Strings.cancel get() = translate { en("Cancel"); de("Abbrechen") }
val Strings.save get() = translate { en("Save"); de("Speichern") }
val Strings.delete get() = translate { en("Delete"); de("Löschen") }
val Strings.done get() = translate { en("Done"); de("Fertig") }
val Strings.remove get() = translate { en("Remove"); de("Entfernen") }
val Strings.edit get() = translate { en("Edit"); de("Bearbeiten") }
val Strings.enable get() = translate { en("Enable"); de("Aktivieren") }
val Strings.disable get() = translate { en("Disable"); de("Deaktivieren") }
val Strings.importAction get() = translate { en("Import"); de("Importieren") }
val Strings.importAll get() = translate { en("Import all"); de("Alle importieren") }
val Strings.accept get() = translate { en("Accept"); de("Übernehmen") }
val Strings.dismiss get() = translate { en("Dismiss"); de("Verwerfen") }
val Strings.reset get() = translate { en("Reset"); de("Zurücksetzen") }
val Strings.back get() = translate { en("Back"); de("Zurück") }
val Strings.manage get() = translate { en("Manage"); de("Verwalten") }
val Strings.forget get() = translate { en("Forget"); de("Entfernen") }
val Strings.undo get() = translate { en("Undo"); de("Rückgängig") }
val Strings.name get() = translate { en("Name"); de("Name") }
val Strings.colour get() = translate { en("Colour"); de("Farbe") }
val Strings.none get() = translate { en("None"); de("Keine") }
val Strings.all get() = translate { en("All"); de("Alle") }

// -- Vault picker ------------------------------------------------------------
val Strings.vaultTagline get() = translate {
    en("Local-first personal finance. Your data stays on your machine.")
    de("Lokale Finanzverwaltung. Deine Daten bleiben auf deinem Gerät.")
}
val Strings.createVault get() = translate { en("Create vault"); de("Vault erstellen") }
val Strings.openVault get() = translate { en("Open vault"); de("Vault öffnen") }
val Strings.openEllipsis get() = translate { en("Open…"); de("Öffnen…") }
val Strings.recent get() = translate { en("RECENT"); de("ZULETZT") }
val Strings.missing get() = translate { en("missing"); de("fehlt") }
fun Strings.vaultNotFound(name: String) = translate { en("Vault not found: $name"); de("Vault nicht gefunden: $name") }

// -- Top bar / navigation ----------------------------------------------------
val Strings.overview get() = translate { en("Overview"); de("Übersicht") }
val Strings.switchLanguageTooltip get() = translate { en("Switch language"); de("Sprache wechseln") }

// -- Sidebar sections --------------------------------------------------------
val Strings.profiles get() = translate { en("Profiles"); de("Profile") }
val Strings.accounts get() = translate { en("Accounts"); de("Konten") }
val Strings.noProfilesShort get() = translate { en("No profiles."); de("Keine Profile.") }
val Strings.noAccountsShort get() = translate { en("No accounts."); de("Keine Konten.") }
val Strings.noProfilesTitle get() = translate { en("No profiles yet"); de("Noch keine Profile") }
val Strings.noProfilesBody get() = translate {
    en("Add people (e.g. household members) to filter accounts by owner and mark joint accounts.")
    de("Füge Personen (z. B. Haushaltsmitglieder) hinzu, um Konten nach Inhaber zu filtern und Gemeinschaftskonten zu kennzeichnen.")
}
val Strings.addProfileAction get() = translate { en("+ Add profile"); de("+ Profil hinzufügen") }
val Strings.noAccountsTitle get() = translate { en("No accounts yet"); de("Noch keine Konten") }
val Strings.noAccountsForProfileTitle get() = translate { en("No accounts for this profile"); de("Keine Konten für dieses Profil") }
val Strings.addAccountBody get() = translate {
    en("Add a Girokonto, Kreditkarte or Depot, then import a statement (PDF or CSV).")
    de("Füge ein Girokonto, eine Kreditkarte oder ein Depot hinzu und importiere dann einen Auszug (PDF oder CSV).")
}
val Strings.noAccountsForProfileBody get() = translate {
    en("This profile doesn't own any accounts. Pick “All” to see every account.")
    de("Diesem Profil gehören keine Konten. Wähle „Alle“, um alle Konten zu sehen.")
}
val Strings.addAccountAction get() = translate { en("+ Add account"); de("+ Konto hinzufügen") }
