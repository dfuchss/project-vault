package org.fuchss.projectvault.app

// Strings for the Classification screen: the rule editor, the Tier-2 model controls and the
// re-classification actions. Translations use the co-located DSL — see [Strings].

// -- Screen / tabs -----------------------------------------------------------
val Strings.classification get() = translate { en("Classification"); de("Klassifizierung") }
val Strings.classificationSubtitle get() = translate {
    en("Everything that decides a transaction's category: the keyword rules, the two suggestion models, and re-running them.")
    de("Alles, was die Kategorie eines Umsatzes bestimmt: die Stichwortregeln, die beiden Vorschlagsmodelle und ihr erneuter Durchlauf.")
}
val Strings.tabRules get() = translate { en("Rules"); de("Regeln") }
val Strings.tabModels get() = translate { en("Models"); de("Modelle") }
val Strings.tabActions get() = translate { en("Actions"); de("Aktionen") }

// -- Rules tab: list + filters ----------------------------------------------
fun Strings.rulesHeader(shown: Int, total: Int) = translate {
    en("Keyword rules ($shown of $total)")
    de("Stichwortregeln ($shown von $total)")
}
val Strings.rulesIntro get() = translate {
    en("Tier 1. The first rule whose keyword appears in a transaction's text commits its category — highest priority first, then the earliest position in the text, then the longest keyword.")
    de("Stufe 1. Die erste Regel, deren Stichwort im Text eines Umsatzes vorkommt, setzt dessen Kategorie — höchste Priorität zuerst, dann die früheste Stelle im Text, dann das längste Stichwort.")
}
val Strings.addRuleButton get() = translate { en("+ Add rule"); de("+ Regel hinzufügen") }
val Strings.searchKeywordPlaceholder get() = translate { en("Search keyword"); de("Stichwort suchen") }
val Strings.sourcePrefix get() = translate { en("Source"); de("Herkunft") }
val Strings.sourceAny get() = translate { en("Any source"); de("Beliebige Herkunft") }
val Strings.sourceSeed get() = translate { en("Built-in"); de("Eingebaut") }
val Strings.sourceUser get() = translate { en("Learned"); de("Gelernt") }

/** The `categoryRule.source` column rendered for humans — SEED reads as "built-in", USER as "learned". */
fun Strings.ruleSourceLabel(source: String) = when (source) {
    "SEED" -> sourceSeed
    "USER" -> sourceUser
    else -> source
}
val Strings.columnKeyword get() = translate { en("Keyword"); de("Stichwort") }
val Strings.columnCategory get() = translate { en("Category"); de("Kategorie") }
val Strings.columnPriority get() = translate { en("Priority"); de("Priorität") }
val Strings.columnMatches get() = translate { en("Matches"); de("Treffer") }
val Strings.matchesHint get() = translate {
    en("How many of your transactions this keyword occurs in — not how many it wins, since a higher-priority rule may take them.")
    de("In wie vielen deiner Umsätze dieses Stichwort vorkommt — nicht wie viele es gewinnt, denn eine Regel mit höherer Priorität kann sie übernehmen.")
}
val Strings.noRulesMatchFilter get() = translate { en("No rules match the filter."); de("Keine Regeln passen zum Filter.") }
val Strings.unknownCategory get() = translate { en("(unknown category)"); de("(unbekannte Kategorie)") }

// -- Rules tab: add / edit / delete dialogs ----------------------------------
val Strings.addRuleTitle get() = translate { en("New rule"); de("Neue Regel") }
val Strings.editRuleTitle get() = translate { en("Edit rule"); de("Regel bearbeiten") }
val Strings.keywordLabel get() = translate { en("Keyword"); de("Stichwort") }
val Strings.keywordRuleHelp get() = translate {
    en("Matched case- and accent-insensitively anywhere in the counterparty and purpose (DÖNER = DOENER).")
    de("Wird unabhängig von Groß-/Kleinschreibung und Umlauten irgendwo in Zahlungspartner und Verwendungszweck gesucht (DÖNER = DOENER).")
}
val Strings.priorityLabel get() = translate { en("Priority"); de("Priorität") }
val Strings.priorityHelp get() = translate {
    en("Higher wins. Built-in rules are 0; rules learned from your corrections are 100.")
    de("Höher gewinnt. Eingebaute Regeln haben 0; aus Korrekturen gelernte Regeln 100.")
}
val Strings.editSeedRuleNote get() = translate {
    en("Editing a built-in rule makes it yours: it becomes a learned rule and the original keyword is not re-installed.")
    de("Wer eine eingebaute Regel bearbeitet, übernimmt sie: Sie wird zu einer gelernten Regel, und das ursprüngliche Stichwort wird nicht erneut installiert.")
}
val Strings.deleteRuleTitle get() = translate { en("Delete this rule?"); de("Diese Regel löschen?") }
fun Strings.deleteRuleBody(keyword: String, category: String) = translate {
    en("\"$keyword\" → $category will no longer classify anything. Transactions it already categorized keep their category until you re-run classification.")
    de("„$keyword“ → $category klassifiziert nichts mehr. Bereits kategorisierte Umsätze behalten ihre Kategorie, bis du die Klassifizierung erneut ausführst.")
}
val Strings.deleteSeedRuleNote get() = translate {
    en("This is a built-in keyword. It stays removed — re-opening the vault will not bring it back.")
    de("Dies ist ein eingebautes Stichwort. Es bleibt entfernt — beim erneuten Öffnen des Vaults kommt es nicht zurück.")
}

// -- Rules tab: removed built-ins -------------------------------------------
fun Strings.removedBuiltinsHeader(n: Int) = translate {
    en("Removed built-in keywords ($n)")
    de("Entfernte eingebaute Stichwörter ($n)")
}
val Strings.restore get() = translate { en("Restore"); de("Wiederherstellen") }

// -- Rules tab: the rule tester ---------------------------------------------
val Strings.testRuleHeader get() = translate { en("Test a rule"); de("Regel testen") }
val Strings.testRuleIntro get() = translate {
    en("Type a counterparty or purpose to see which rule would win and which category gets committed.")
    de("Gib einen Zahlungspartner oder Verwendungszweck ein, um zu sehen, welche Regel gewinnt und welche Kategorie gesetzt wird.")
}
val Strings.testRulePlaceholder get() = translate {
    en("e.g. REWE SAGT DANKE 12345")
    de("z. B. REWE SAGT DANKE 12345")
}
val Strings.testRuleNoMatch get() = translate {
    en("No rule matches — this transaction would go to Tier 2 for a suggestion.")
    de("Keine Regel passt — dieser Umsatz ginge für einen Vorschlag an Stufe 2.")
}
fun Strings.testRuleWinner(keyword: String, source: String) = translate {
    en("Winning rule: \"$keyword\" ($source)")
    de("Gewinnende Regel: „$keyword“ ($source)")
}
val Strings.testRuleCommits get() = translate { en("Commits"); de("Setzt") }

// -- Models tab --------------------------------------------------------------
val Strings.modelsIntro get() = translate {
    en("Tier 2. For transactions no rule matches, these two propose a category — a suggestion you confirm in the transaction inspector. They never commit on their own.")
    de("Stufe 2. Für Umsätze, auf die keine Regel passt, schlagen diese beiden eine Kategorie vor — ein Vorschlag, den du im Umsatz-Inspektor bestätigst. Sie setzen nie selbst.")
}
val Strings.statisticalModelName get() = translate { en("Statistical (TF-IDF + Naive Bayes)"); de("Statistisch (TF-IDF + Naive Bayes)") }
val Strings.statisticalModelDesc get() = translate {
    en("Trained on-device from your labeled transactions plus the seed keywords, on every pass. No model file, no download — always available.")
    de("Bei jedem Durchlauf auf dem Gerät aus deinen kategorisierten Umsätzen und den Seed-Stichwörtern trainiert. Keine Modelldatei, kein Download — immer verfügbar.")
}
val Strings.embeddingModelName get() = translate { en("Semantic embeddings (multilingual-e5-small)"); de("Semantische Embeddings (multilingual-e5-small)") }
val Strings.embeddingModelDesc get() = translate {
    en("Compares the transaction text to category prototypes and your past labels, and proposes the nearest one — but only when it is clearly nearer than the runner-up. Runs only when the bundled model is present.")
    de("Vergleicht den Umsatztext mit Kategorie-Prototypen und deinen bisherigen Zuordnungen und schlägt die nächstgelegene vor — aber nur, wenn sie deutlich näher liegt als die zweitbeste. Läuft nur, wenn das mitgelieferte Modell vorhanden ist.")
}
val Strings.modelStatusChecking get() = translate { en("Checking…"); de("Prüfe…") }
val Strings.modelStatusLoaded get() = translate { en("Model loaded"); de("Modell geladen") }
val Strings.modelStatusMissing get() = translate { en("Not provisioned"); de("Nicht bereitgestellt") }
val Strings.modelStatusAlwaysOn get() = translate { en("Always available"); de("Immer verfügbar") }
val Strings.modelMissingHint get() = translate {
    en("No embedding model is bundled in this build. The statistical model handles Tier 2 on its own.")
    de("In diesem Build ist kein Embedding-Modell enthalten. Das statistische Modell übernimmt Stufe 2 allein.")
}
val Strings.thresholdConfidence get() = translate { en("Minimum confidence"); de("Mindest-Konfidenz") }
val Strings.thresholdMargin get() = translate { en("Minimum lead over the runner-up"); de("Mindest-Vorsprung vor der Zweitplatzierten") }
val Strings.thresholdHelp get() = translate {
    en("Higher = fewer but safer suggestions; lower = more coverage and more noise.")
    de("Höher = weniger, aber sicherere Vorschläge; niedriger = mehr Abdeckung und mehr Rauschen.")
}
val Strings.marginHelp get() = translate {
    en(
        "How far ahead of the second-best category the best one has to be. Raw similarity says little — " +
            "German bank text scores high against every category — so the lead is what decides. At 0 every " +
            "transaction gets a proposal, including the ones the model has no opinion about; higher = fewer " +
            "but safer suggestions.",
    )
    de(
        "Wie weit die beste Kategorie vor der zweitbesten liegen muss. Die reine Ähnlichkeit sagt wenig aus — " +
            "deutsche Umsatztexte liegen zu jeder Kategorie hoch — entscheidend ist der Vorsprung. Bei 0 erhält " +
            "jeder Umsatz einen Vorschlag, auch die, zu denen das Modell keine Meinung hat; höher = weniger, " +
            "aber sicherere Vorschläge.",
    )
}
fun Strings.defaultValue(value: String) = translate { en("default $value"); de("Standard $value") }
val Strings.settingsScopeNote get() = translate {
    en("These settings are stored in the vault file, so they travel with your data.")
    de("Diese Einstellungen werden in der Vault-Datei gespeichert und wandern so mit deinen Daten mit.")
}
val Strings.bothModelsOffWarning get() = translate {
    en("Both suggesters are off — only the keyword rules will classify anything.")
    de("Beide Vorschlagsmodelle sind aus — nur die Stichwortregeln klassifizieren noch.")
}

// -- Actions tab -------------------------------------------------------------
val Strings.reclassifyHeader get() = translate { en("Re-run classification"); de("Klassifizierung erneut ausführen") }
val Strings.reclassifyIntro get() = translate {
    en("A normal pass only fills in blanks, so an improved rule never reaches transactions that already have a category. Choose how far this run should reach.")
    de("Ein normaler Durchlauf füllt nur Lücken, eine verbesserte Regel erreicht also nie bereits kategorisierte Umsätze. Wähle, wie weit dieser Durchlauf reichen soll.")
}
val Strings.scopeUncategorizedTitle get() = translate { en("Uncategorized only"); de("Nur nicht kategorisierte") }
val Strings.scopeUncategorizedDesc get() = translate {
    en("Exactly what an import does: transactions with no category yet.")
    de("Genau das, was ein Import tut: Umsätze, die noch keine Kategorie haben.")
}
val Strings.scopeAllTitle get() = translate { en("Re-apply rules to everything"); de("Regeln auf alles erneut anwenden") }
val Strings.scopeAllDesc get() = translate {
    en("Drops every automatically-set category first, then classifies again. Transactions you set manually are never touched.")
    de("Verwirft zuerst jede automatisch gesetzte Kategorie und klassifiziert dann erneut. Manuell gesetzte Umsätze bleiben unberührt.")
}
fun Strings.scopeAffects(n: Int) = translate { en("affects $n transaction(s)"); de("betrifft $n Umsatz/Umsätze") }
val Strings.manualAlwaysRespected get() = translate {
    en("Transactions you categorized yourself are MANUAL and are never overwritten by either scope.")
    de("Selbst kategorisierte Umsätze sind MANUELL und werden von keinem der beiden Bereiche überschrieben.")
}
val Strings.reclassifyConfirmTitle get() = translate { en("Re-run classification?"); de("Klassifizierung erneut ausführen?") }
fun Strings.reclassifyConfirmBody(n: Int, scope: String) = translate {
    en("$n transaction(s) will be re-examined ($scope). Manual categories are kept. This can't be undone automatically.")
    de("$n Umsatz/Umsätze werden erneut geprüft ($scope). Manuelle Kategorien bleiben erhalten. Das kann nicht automatisch rückgängig gemacht werden.")
}
val Strings.reclassifyConfirm get() = translate { en("Run"); de("Ausführen") }
val Strings.reclassifyRunning get() = translate { en("Re-classifying…"); de("Klassifiziere neu…") }
fun Strings.reclassifyResult(cleared: Int, committed: Int, suggested: Int) = translate {
    en("Re-classified: $cleared category/categories reset, $committed committed by rules, $suggested suggestion(s) to review.")
    de("Neu klassifiziert: $cleared Kategorie(n) zurückgesetzt, $committed per Regel gesetzt, $suggested Vorschlag/Vorschläge zu prüfen.")
}
val Strings.noAccountsToClassify get() = translate {
    en("No accounts yet — add one and import a statement first.")
    de("Noch keine Konten — lege eines an und importiere zuerst einen Auszug.")
}

// -- Explain tab -------------------------------------------------------------
// Model jargon (TF-IDF, Naive Bayes, cosine, margin, lift) stays untranslated, like the domain terms
// elsewhere in the app — those are the words the literature and the Models tab already use.
val Strings.tabExplain get() = translate { en("Explain"); de("Erklärung") }
val Strings.explainIntro get() = translate {
    en("Pick a transaction — or type any text — and see the whole decision path: what the amount's sign allows, which rule wins, what each of the two Tier-2 models scores, and what comes of it.")
    de("Wähle einen Umsatz — oder gib einen beliebigen Text ein — und sieh den gesamten Entscheidungsweg: was das Vorzeichen des Betrags zulässt, welche Regel gewinnt, was die beiden Stufe-2-Modelle bewerten und was daraus folgt.")
}
val Strings.explainNowNote get() = translate {
    en("This is what the classifier decides for this input NOW — with today's rules, categories and labels. A transaction classified before you changed them can well explain differently than it was decided.")
    de("Dies ist, was der Klassifizierer JETZT für diese Eingabe entscheidet — mit den heutigen Regeln, Kategorien und Zuordnungen. Ein früher klassifizierter Umsatz kann daher anders erklärt werden, als er entschieden wurde.")
}

// -- Explain tab: the input picker ------------------------------------------
val Strings.explainInputHeader get() = translate { en("Input"); de("Eingabe") }
val Strings.explainModeTransaction get() = translate { en("A transaction"); de("Ein Umsatz") }
val Strings.explainModeFreeText get() = translate { en("Free text"); de("Freier Text") }
val Strings.explainSearchPlaceholder get() = translate { en("Search counterparty or purpose"); de("Zahlungspartner oder Verwendungszweck suchen") }
val Strings.explainNoTransactions get() = translate {
    en("No transactions yet — import a statement, or switch to free text.")
    de("Noch keine Umsätze — importiere einen Auszug oder wechsle zu freiem Text.")
}
val Strings.explainNoTxnMatch get() = translate { en("No transaction matches the search."); de("Kein Umsatz passt zur Suche.") }
val Strings.explainPickPrompt get() = translate {
    en("Pick a transaction on the left to see why it got the category it has.")
    de("Wähle links einen Umsatz, um zu sehen, warum er seine Kategorie bekommen hat.")
}
val Strings.explainTypePrompt get() = translate {
    en("Type a counterparty or purpose on the left, and set the amount — the sign changes the answer.")
    de("Gib links einen Zahlungspartner oder Verwendungszweck ein und setze den Betrag — das Vorzeichen ändert die Antwort.")
}
val Strings.explainTextLabel get() = translate { en("Counterparty / purpose"); de("Zahlungspartner / Verwendungszweck") }
val Strings.explainAmountLabel get() = translate { en("Amount (€)"); de("Betrag (€)") }
val Strings.explainAmountHelp get() = translate {
    en("Negative = money out, positive = money in. The sign decides which categories are even possible.")
    de("Negativ = Geld raus, positiv = Geld rein. Das Vorzeichen entscheidet, welche Kategorien überhaupt möglich sind.")
}
val Strings.explainBuildingModels get() = translate {
    en("Building the models — one pass over your labeled transactions. Done once, then reused for every query.")
    de("Modelle werden gebaut — ein Durchlauf über deine kategorisierten Umsätze. Einmalig, danach für jede Abfrage wiederverwendet.")
}
val Strings.explainComputing get() = translate { en("Working…"); de("Berechne…") }
fun Strings.explainModelBasis(prototypes: Int, examples: Int) = translate {
    en("Tier 2 built from $prototypes category prototype(s) and $examples labeled transaction(s).")
    de("Stufe 2 aus $prototypes Kategorie-Prototyp(en) und $examples kategorisierten Umsätzen gebaut.")
}
val Strings.explainCurrentCategory get() = translate { en("Currently"); de("Aktuell") }
val Strings.explainCurrentlySuggested get() = translate { en("Suggested"); de("Vorgeschlagen") }
val Strings.explainUncategorized get() = translate { en("uncategorized"); de("nicht kategorisiert") }

// -- Explain step 1: the amount sign ----------------------------------------
val Strings.explainSignHeader get() = translate { en("1 · What the amount allows"); de("1 · Was der Betrag zulässt") }
fun Strings.explainSignIntro(amount: String) = translate {
    en("$amount, so only these kinds of category may be assigned — by any tier, and by you.")
    de("$amount, also dürfen nur diese Kategoriearten vergeben werden — von jeder Stufe und von dir.")
}
val Strings.explainSignZero get() = translate {
    en("A zero amount carries no direction, so nothing is ruled out.")
    de("Ein Betrag von null hat keine Richtung, also wird nichts ausgeschlossen.")
}
fun Strings.explainAdmissible(n: Int) = translate { en("$n admissible"); de("$n zulässig") }
fun Strings.explainRuledOut(n: Int) = translate { en("$n ruled out"); de("$n ausgeschlossen") }
val Strings.explainRuledOutHint get() = translate {
    en("Filing incoming money under an expense category is not a weak guess but a category error, so these never reach any tier.")
    de("Eingehendes Geld unter einer Ausgabenkategorie zu verbuchen, ist keine schwache Vermutung, sondern ein Kategoriefehler — diese erreichen daher keine Stufe.")
}

// -- Explain step 2: Tier 1 rules -------------------------------------------
val Strings.explainRulesHeader get() = translate { en("2 · Tier 1 — keyword rules"); de("2 · Stufe 1 — Stichwortregeln") }
val Strings.explainRulesIntro get() = translate {
    en("Every rule whose keyword occurs in the text, in the order the engine resolves them: priority, then the earliest position in the text, then the longest keyword. The first admissible one commits.")
    de("Alle Regeln, deren Stichwort im Text vorkommt, in der Reihenfolge der Auflösung: Priorität, dann früheste Stelle im Text, dann längstes Stichwort. Die erste zulässige setzt die Kategorie.")
}
val Strings.explainNoRuleMatches get() = translate {
    en("No rule's keyword occurs in this text, so Tier 1 says nothing and the decision falls to Tier 2.")
    de("Kein Regelstichwort kommt in diesem Text vor — Stufe 1 sagt nichts, die Entscheidung fällt an Stufe 2.")
}
val Strings.columnPosition get() = translate { en("At"); de("Pos.") }
val Strings.columnVerdict get() = translate { en("Verdict"); de("Ergebnis") }
val Strings.verdictWinner get() = translate { en("wins"); de("gewinnt") }
val Strings.verdictOutranked get() = translate { en("out-ranked"); de("überstimmt") }
val Strings.verdictSignRejected get() = translate { en("wrong sign"); de("falsches Vorzeichen") }
val Strings.verdictDisabled get() = translate { en("category off"); de("Kategorie aus") }

// -- Explain steps 3 & 4: the Tier-2 models ---------------------------------
val Strings.explainOutcomeAccountDefault get() = translate {
    en("committed by the account type, before any rule was consulted")
    de("durch die Kontoart gesetzt, noch bevor eine Regel geprüft wurde")
}

fun Strings.explainAccountDefault(accountType: String, category: String) = translate {
    en(
        "This is a $accountType account: its transactions default to “$category” by account type, and the " +
            "rule engine is never consulted. Everything below shows what the rules and models would have " +
            "said — none of it was applied.",
    )
    de(
        "Dies ist ein $accountType-Konto: Umsätze werden per Kontoart auf „$category“ gesetzt, die " +
            "Regel-Engine wird gar nicht befragt. Alles Folgende zeigt, was Regeln und Modelle gesagt " +
            "hätten — angewendet wurde nichts davon.",
    )
}

val Strings.explainNotConsulted get() = translate {
    en("A rule already committed a category, so Tier 2 is not consulted for this transaction. Its scores are shown anyway, for comparison.")
    de("Eine Regel hat bereits eine Kategorie gesetzt, Stufe 2 wird für diesen Umsatz also nicht befragt. Die Werte werden trotzdem zum Vergleich gezeigt.")
}
val Strings.explainStatisticalHeader get() = translate { en("3 · Tier 2 — statistical (TF-IDF + Naive Bayes)"); de("3 · Stufe 2 — statistisch (TF-IDF + Naive Bayes)") }
val Strings.explainEmbeddingHeader get() = translate { en("4 · Tier 2 — semantic embeddings"); de("4 · Stufe 2 — semantische Embeddings") }
val Strings.explainTierSwitchedOff get() = translate {
    en("Switched off in the Models tab — it did not run.")
    de("Im Reiter „Modelle“ ausgeschaltet — es lief nicht.")
}
val Strings.explainTierModelMissing get() = translate {
    en("No embedding model is provisioned in this build, so this tier cannot run at all. The statistical model handles Tier 2 on its own.")
    de("In diesem Build ist kein Embedding-Modell vorhanden, diese Stufe kann also gar nicht laufen. Das statistische Modell übernimmt Stufe 2 allein.")
}
val Strings.explainTierUntrained get() = translate {
    en("Nothing to train on — no enabled categories, so the model has no classes.")
    de("Keine Trainingsgrundlage — keine aktiven Kategorien, das Modell hat also keine Klassen.")
}
val Strings.explainTokenEvidence get() = translate { en("Token evidence"); de("Token-Belege") }
val Strings.explainTokenEvidenceHint get() = translate {
    en("Sorted by lift — how much a token pushed the winner over the runner-up. A token can score high and decide nothing, because every category likes it equally (GMBH, SEPA, DE).")
    de("Sortiert nach Lift — wie stark ein Token den Sieger vor die Zweitplatzierte schiebt. Ein Token kann hoch bewertet sein und nichts entscheiden, weil jede Kategorie es gleichermaßen mag (GMBH, SEPA, DE).")
}
val Strings.columnToken get() = translate { en("Token"); de("Token") }
val Strings.columnWeight get() = translate { en("TF-IDF"); de("TF-IDF") }
val Strings.columnToward get() = translate { en("toward winner"); de("für Sieger") }
val Strings.columnLift get() = translate { en("lift"); de("Lift") }
val Strings.columnLogScore get() = translate { en("log score"); de("Log-Score") }
val Strings.columnShare get() = translate { en("share"); de("Anteil") }
val Strings.columnCosine get() = translate { en("cosine"); de("Cosinus") }
val Strings.explainClassScores get() = translate { en("Where every category landed"); de("Wo jede Kategorie landet") }
val Strings.explainShareHint get() = translate {
    en("The share is a softmax over the admissible categories, shown so the log scores read as proportions. The decision is not made on it, but on the winner's margin over the runner-up.")
    de("Der Anteil ist ein Softmax über die zulässigen Kategorien und dient nur der Lesbarkeit der Log-Scores. Entschieden wird nicht danach, sondern nach dem Vorsprung des Siegers vor der Zweitplatzierten.")
}
fun Strings.explainUnknownTokens(n: Int) = translate {
    en("$n word(s) the model has never seen")
    de("$n Wort/Wörter, die das Modell nie gesehen hat")
}
val Strings.explainAllUnknown get() = translate {
    en("Every word in this text is unknown to the model, so it has nothing to go on — which is the honest reason there is no proposal.")
    de("Jedes Wort dieses Textes ist dem Modell unbekannt, es hat also keine Grundlage — das ist der ehrliche Grund für den fehlenden Vorschlag.")
}
fun Strings.explainStatDecision(category: String, confidence: String, threshold: String) = translate {
    en("Proposes $category — confidence $confidence, clearing the $threshold bar.")
    de("Schlägt $category vor — Konfidenz $confidence, über der Schwelle $threshold.")
}
fun Strings.explainStatNoDecision(confidence: String, threshold: String) = translate {
    en("No proposal: the winner's confidence $confidence stays below the $threshold bar.")
    de("Kein Vorschlag: Die Konfidenz des Siegers $confidence bleibt unter der Schwelle $threshold.")
}
val Strings.explainStatNoVocabulary get() = translate {
    en("No proposal: none of this text's words are in the model's vocabulary."); de("Kein Vorschlag: Keines der Wörter dieses Textes ist im Vokabular des Modells.")
}
val Strings.explainCosineHint get() = translate {
    en("The raw cosines say little on their own — German bank text scores high against every category. What decides is the winner's lead over the best other category.")
    de("Die reinen Cosinus-Werte sagen für sich wenig — deutsche Umsatztexte liegen zu jeder Kategorie hoch. Entscheidend ist der Vorsprung des Siegers vor der besten anderen Kategorie.")
}
fun Strings.explainMarginLine(top1: String, top2: String, margin: String) = translate {
    en("Winner $top1 − runner-up $top2 = margin $margin")
    de("Sieger $top1 − Zweite $top2 = Vorsprung $margin")
}
fun Strings.explainMarginCleared(minMargin: String) = translate {
    en("clears the $minMargin bar → proposal")
    de("über der Schwelle $minMargin → Vorschlag")
}
fun Strings.explainMarginMissed(minMargin: String) = translate {
    en("below the $minMargin bar → no proposal")
    de("unter der Schwelle $minMargin → kein Vorschlag")
}
val Strings.explainEmbNoCandidates get() = translate {
    en("No admissible category to compare against, so nothing is proposed.")
    de("Keine zulässige Kategorie zum Vergleich — daher kein Vorschlag.")
}

// -- Explain step 5: the merge and the outcome ------------------------------
val Strings.explainMergeHeader get() = translate { en("5 · The merge, and what comes of it"); de("5 · Die Zusammenführung und was daraus folgt") }
val Strings.explainMergeIntro get() = translate {
    en("Each proposal is first normalized against its own threshold — a Naive-Bayes confidence lives in [0.5, 1] and an embedding margin in [0, ~0.15], so comparing them raw would hand every disagreement to the statistical model by arithmetic rather than by evidence.")
    de("Jeder Vorschlag wird zuerst an seiner eigenen Schwelle normiert — eine Naive-Bayes-Konfidenz liegt in [0,5; 1], ein Embedding-Vorsprung in [0; ~0,15]. Ein direkter Vergleich würde jede Uneinigkeit rechnerisch statt sachlich dem statistischen Modell zuschlagen.")
}
val Strings.columnStrength get() = translate { en("strength"); de("Stärke") }
val Strings.explainMergeNothing get() = translate { en("Neither model proposed anything."); de("Keines der Modelle hat etwas vorgeschlagen.") }
val Strings.explainMergeAgreed get() = translate { en("Both models agree — agreement is strong evidence, so that category is taken."); de("Beide Modelle stimmen überein — Übereinstimmung ist ein starkes Indiz, diese Kategorie wird genommen.") }
val Strings.explainMergeOnlyStat get() = translate { en("Only the statistical model proposed anything, so its proposal is used."); de("Nur das statistische Modell hat etwas vorgeschlagen, sein Vorschlag wird genommen.") }
val Strings.explainMergeOnlyEmb get() = translate { en("Only the embedding model proposed anything, so its proposal is used."); de("Nur das Embedding-Modell hat etwas vorgeschlagen, sein Vorschlag wird genommen.") }
val Strings.explainMergeStatStronger get() = translate { en("They disagree; the statistical model cleared its own bar by more, so it wins."); de("Sie sind uneins; das statistische Modell hat seine eigene Schwelle deutlicher übertroffen und gewinnt.") }
val Strings.explainMergeEmbStronger get() = translate { en("They disagree; the embedding model cleared its own bar by more, so it wins."); de("Sie sind uneins; das Embedding-Modell hat seine eigene Schwelle deutlicher übertroffen und gewinnt.") }
val Strings.explainOutcomeHeader get() = translate { en("Result"); de("Ergebnis") }
fun Strings.explainOutcomeCommitted(source: String) = translate {
    en("Committed by a rule ($source). Rules are reliable, so Tier 1 sets the category outright.")
    de("Per Regel gesetzt ($source). Regeln sind verlässlich, Stufe 1 setzt die Kategorie also direkt.")
}
val Strings.explainOutcomeSuggested get() = translate {
    en("Proposed as a suggestion. Tier 2 never commits — you accept or dismiss it in the transaction inspector.")
    de("Als Vorschlag hinterlegt. Stufe 2 setzt nie selbst — du nimmst ihn im Umsatz-Inspektor an oder verwirfst ihn.")
}
val Strings.explainOutcomeNothing get() = translate {
    en("Nothing fires: no rule matches and neither model is confident enough. The transaction stays uncategorized, waiting for you.")
    de("Nichts greift: Keine Regel passt und kein Modell ist sicher genug. Der Umsatz bleibt nicht kategorisiert und wartet auf dich.")
}
fun Strings.explainSourceLabel(source: String) = when (source) {
    CategorySource.USER_RULE -> translate { en("learned rule"); de("gelernte Regel") }
    CategorySource.SEED_RULE -> translate { en("built-in rule"); de("eingebaute Regel") }
    else -> source
}

/** Pointer from the Rules tab's Tier-1-only tester to the tab that explains the whole path. */
val Strings.testRuleSeeExplain get() = translate {
    en("Rules only. For the amount-sign constraint, the Tier-2 model scores and the merge, use the Explain tab.")
    de("Nur Regeln. Für die Vorzeichen-Beschränkung, die Werte der Stufe-2-Modelle und ihre Zusammenführung siehe den Reiter „Erklärung“.")
}

/** Foot of a capped table, so a truncated list never reads as the whole list. */
fun Strings.explainMoreRows(n: Int) = translate { en("+$n more"); de("+$n weitere") }
