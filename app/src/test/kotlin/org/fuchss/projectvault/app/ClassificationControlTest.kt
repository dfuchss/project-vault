package org.fuchss.projectvault.app

import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.classification.EmbeddingClassifier
import org.fuchss.projectvault.classification.RuleSource
import org.fuchss.projectvault.classification.SeedCatalog
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.Vault
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Classification screen's non-UI logic: removing a built-in rule must *stick* across the seed
 * reconcile that runs on every open, re-classification must respect MANUAL categories, and the
 * classifier settings must travel with the vault file.
 */
class ClassificationControlTest {

    private fun vaultFile(prefix: String) = File(Files.createTempDirectory(prefix).toFile(), "v.pvault")

    private fun setup(file: File = vaultFile("pv-class")): Triple<VaultRepository, Categorizer, String> {
        val repo = VaultRepository(VaultManager.create(file))
        val categorizer = Categorizer(repo).apply { ensureSeeded() }
        return Triple(repo, categorizer, repo.addAccount("Giro", AccountType.GIRO))
    }

    private fun tx(counterparty: String, hash: String) =
        NewTransaction(LocalDate.of(2026, 7, 1), null, -1160, "EUR", counterparty, "purpose", "Kartenzahlung", hash)

    private fun ruleFor(repo: VaultRepository, keyword: String) =
        repo.categoryRules().single { it.keyword.equals(keyword, ignoreCase = true) }

    // ------------------------------------------------------------ Rule suppression

    @Test
    fun `deleting a built-in rule survives the seed reconcile`() {
        val (repo, categorizer, _) = setup()
        val rewe = ruleFor(repo, "REWE")
        assertEquals(RuleSource.SEED.name, rewe.source)

        categorizer.deleteRule(rewe)
        assertTrue(repo.categoryRules().none { it.keyword.equals("REWE", ignoreCase = true) }, "rule removed")

        // ensureSeeded runs on every open and re-installs missing catalog pairs — but not this one.
        categorizer.ensureSeeded()
        categorizer.ensureSeeded()
        assertTrue(
            repo.categoryRules().none { it.keyword.equals("REWE", ignoreCase = true) },
            "a deleted built-in keyword must not be re-installed by ensureSeeded",
        )
        assertTrue(categorizer.suppressedSeedRules().any { it.keyword == "REWE" }, "listed as removed, so it can be restored")
    }

    @Test
    fun `a deleted built-in rule stays gone after closing and reopening the vault`() {
        val file = vaultFile("pv-class-reopen")
        VaultManager.create(file).let { vault ->
            val repo = VaultRepository(vault)
            val categorizer = Categorizer(repo).apply { ensureSeeded() }
            categorizer.deleteRule(ruleFor(repo, "LIDL"))
            vault.close()
        }
        VaultManager.open(file).let { vault: Vault ->
            val repo = VaultRepository(vault)
            Categorizer(repo).ensureSeeded() // exactly what MainScreen does on open
            assertTrue(
                repo.categoryRules().none { it.keyword.equals("LIDL", ignoreCase = true) },
                "re-opening the vault must not resurrect a removed built-in keyword",
            )
            vault.close()
        }
    }

    @Test
    fun `restoring a removed built-in rule brings it back`() {
        val (repo, categorizer, _) = setup()
        categorizer.deleteRule(ruleFor(repo, "ALDI"))
        val removed = categorizer.suppressedSeedRules().single { it.keyword == "ALDI" }

        categorizer.restoreSeedRule(removed)

        assertEquals("cat-groceries", ruleFor(repo, "ALDI").categoryId)
        assertTrue(categorizer.suppressedSeedRules().none { it.keyword == "ALDI" })
    }

    @Test
    fun `editing a built-in rule takes it over and the original keyword is not re-installed`() {
        val (repo, categorizer, account) = setup()
        val penny = ruleFor(repo, "PENNY")

        categorizer.updateRule(penny, keyword = "PENNYMARKT", categoryId = "cat-groceries", priority = 100)
        categorizer.ensureSeeded()

        val rules = repo.categoryRules()
        assertTrue(rules.none { it.keyword.equals("PENNY", ignoreCase = true) }, "the original keyword stays gone")
        val edited = rules.single { it.keyword == "PENNYMARKT" }
        assertEquals(RuleSource.USER.name, edited.source, "an edited built-in becomes the user's own rule")
        assertEquals(100L, edited.priority)

        // And it still classifies, i.e. ensureSeeded's SEED prune didn't take the taken-over rule away.
        repo.insertTransactions(account, null, listOf(tx("PENNYMARKT Filiale 12", "a")))
        categorizer.classifyAccount(account)
        assertEquals("cat-groceries", repo.transactions(account).single().categoryId)
    }

    @Test
    fun `a suppression whose catalog pair no longer exists is pruned`() {
        val (repo, categorizer, _) = setup()
        // A keyword that isn't in the catalog at all — e.g. left behind by an older SeedCatalog.
        repo.suppressRule("NOT-IN-THE-CATALOG", "cat-groceries")
        assertTrue(repo.suppressedRules().isNotEmpty())

        categorizer.ensureSeeded()

        assertTrue(repo.suppressedRules().none { it.first == "NOT-IN-THE-CATALOG" }, "stale suppressions are cleaned up")
    }

    // ------------------------------------------------------------ Rule editing / match counts

    @Test
    fun `adding a rule by hand classifies matching transactions`() {
        val (repo, categorizer, account) = setup()
        categorizer.addRule("BLUMEN MEYER", "cat-shopping", priority = 100)
        repo.insertTransactions(account, null, listOf(tx("Blumen Meyer Laden", "a")))

        categorizer.classifyAccount(account)

        assertEquals("cat-shopping", repo.transactions(account).single().categoryId)
    }

    @Test
    fun `rule match counts fold diacritics and count occurrences, not wins`() {
        val (repo, categorizer, _) = setup()
        categorizer.addRule("Dönerbude", "cat-restaurants", priority = 100)
        val rules = repo.categoryRules()

        // Both sides are folded, so the "Dönerbude" rule matches a "Doenerbude" text and vice versa.
        val counts = ruleMatchCounts(rules, listOf("Doenerbude Berlin", "REWE SAGT DANKE", "REWE Markt 2"))

        assertEquals(1, counts[rules.single { it.keyword == "Dönerbude" }.id])
        assertEquals(2, counts[rules.single { it.keyword.equals("REWE", ignoreCase = true) }.id])
        assertEquals(0, counts[rules.single { it.keyword.equals("IKEA", ignoreCase = true) }.id])
    }

    @Test
    fun `explain reports the rule that would win`() {
        val (_, categorizer, _) = setup()
        val winner = categorizer.explain("REWE SAGT DANKE 12345")
        assertEquals("REWE", winner?.keyword)
        assertEquals("cat-groceries", winner?.categoryId)
        assertNull(categorizer.explain("Zahlung an Privatperson"), "no rule matches → Tier 2's job")
    }

    // ------------------------------------------------------------ Re-classification

    @Test
    fun `re-running over uncategorized only leaves existing categories alone`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE Markt", "a")))
        categorizer.classifyAccount(account)
        assertEquals("cat-groceries", repo.transactions(account).single().categoryId)

        // The rule that produced it is gone, but the committed category must not move in this scope.
        categorizer.deleteRule(ruleFor(repo, "REWE"))
        val result = categorizer.reclassify(ReclassifyScope.UNCATEGORIZED)

        assertEquals(0, result.cleared)
        assertEquals("cat-groceries", repo.transactions(account).single().categoryId)
    }

    @Test
    fun `re-applying rules to everything reaches already-categorized transactions but never MANUAL ones`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE Markt", "a"), tx("ALDI SUED", "b")))
        categorizer.classifyAccount(account)

        // The user overrides ALDI by hand (MANUAL, no rule learned) — that choice is untouchable.
        val aldi = repo.transactions(account).single { it.counterparty == "ALDI SUED" }
        categorizer.applyToOne(aldi, "cat-shopping")

        // Now the REWE keyword is retargeted: re-applying rules must move the REWE transaction…
        categorizer.updateRule(ruleFor(repo, "REWE"), "REWE", "cat-drugstore", priority = 100)
        val result = categorizer.reclassify(ReclassifyScope.ALL_EXCEPT_MANUAL)

        val byCounterparty = repo.transactions(account).associateBy { it.counterparty }
        assertEquals("cat-drugstore", byCounterparty["REWE Markt"]!!.categoryId, "the edited rule reaches an already-classified transaction")
        assertEquals("cat-shopping", byCounterparty["ALDI SUED"]!!.categoryId, "a MANUAL category is never re-classified")
        assertEquals(CategorySource.MANUAL, byCounterparty["ALDI SUED"]!!.categorySource)
        assertEquals(1, result.cleared, "only the automatic category was dropped")
        assertEquals(1, result.committed)
    }

    @Test
    fun `re-applying rules uncategorizes what no rule matches any more`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE Markt", "a")))
        categorizer.classifyAccount(account)

        categorizer.deleteRule(ruleFor(repo, "REWE"))
        categorizer.reclassify(ReclassifyScope.ALL_EXCEPT_MANUAL)

        assertNull(repo.transactions(account).single().categoryId, "no rule matches → back to uncategorized")
    }

    @Test
    fun `candidate counts describe each scope before it runs`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE Markt", "a"), tx("Unbekannt GmbH", "b")))
        categorizer.classifyAccount(account)

        assertEquals(1, categorizer.reclassifyCandidateCount(ReclassifyScope.UNCATEGORIZED), "only the unmatched one")
        assertEquals(2, categorizer.reclassifyCandidateCount(ReclassifyScope.ALL_EXCEPT_MANUAL), "nothing is MANUAL yet")

        categorizer.applyToOne(repo.transactions(account).first { it.counterparty == "Unbekannt GmbH" }, "cat-other")
        assertEquals(0, categorizer.reclassifyCandidateCount(ReclassifyScope.UNCATEGORIZED))
        assertEquals(1, categorizer.reclassifyCandidateCount(ReclassifyScope.ALL_EXCEPT_MANUAL), "the MANUAL one drops out")
    }

    // ------------------------------------------------------------ The keyword is the user's to choose

    @Test
    fun `an explicit keyword overrides the derived one and decides how far a correction spreads`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(
            tx("Supermarkt Fil 12 Karlsruhe", "a"),
            tx("Supermarkt Fil 12 Ettlingen", "b"),
            tx("Supermarkt Fil 99 Berlin", "c"),
        ))
        val first = repo.transactions(account).first { it.counterparty!!.endsWith("Karlsruhe") }

        // The derived keyword would be the whole merchant ("SUPERMARKT") and hit all three; the user
        // narrows it to one branch, and only that branch moves.
        assertEquals("SUPERMARKT", categorizer.learnedKeywordFor(first))
        categorizer.setCategory(account, first, "cat-groceries", keyword = "Supermarkt Fil 12")

        val byCounterparty = repo.transactions(account).associateBy { it.counterparty }
        assertEquals("cat-groceries", byCounterparty["Supermarkt Fil 12 Karlsruhe"]!!.categoryId)
        assertEquals("cat-groceries", byCounterparty["Supermarkt Fil 12 Ettlingen"]!!.categoryId)
        assertNull(byCounterparty["Supermarkt Fil 99 Berlin"]!!.categoryId, "the narrowed keyword must not reach the other branch")
        assertEquals("SUPERMARKT FIL 12", repo.categoryRules().single { it.source == "USER" }.keyword, "stored folded, however it was typed")
    }

    @Test
    fun `the keyword match counter reports what each candidate keyword would reach`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(
            tx("Supermarkt Fil 12 Karlsruhe", "a"),
            tx("Supermarkt Fil 12 Ettlingen", "b"),
            tx("Supermarkt Fil 99 Berlin", "c"),
        ))
        val first = repo.transactions(account).first { it.counterparty!!.endsWith("Karlsruhe") }
        val count = categorizer.keywordMatchCounter(account, first, "cat-groceries")

        assertEquals(2, count("SUPERMARKT"), "the other two, excluding the one being corrected")
        assertEquals(1, count("Supermarkt Fil 12"), "narrowing the keyword narrows the reach")
        assertEquals(0, count("BAECKEREI"))
        assertEquals(0, count(""), "an empty keyword reaches nothing")
    }

    // ------------------------------------------------------------ Settings

    @Test
    fun `classifier settings default to the classifiers' own defaults`() {
        val (repo, _, _) = setup()
        val settings = ClassifierSettings.load(repo)
        assertTrue(settings.statisticalEnabled && settings.embeddingEnabled)
        assertEquals(ClassifierSettings.DEFAULT_STATISTICAL_THRESHOLD, settings.statisticalThreshold)
        assertEquals(ClassifierSettings.DEFAULT_EMBEDDING_MARGIN, settings.embeddingMargin)
        assertEquals(
            EmbeddingClassifier.DEFAULT_MIN_MARGIN.toDouble(),
            ClassifierSettings.DEFAULT_EMBEDDING_MARGIN,
            1e-6,
            "the settings default must mirror the classifier's own",
        )
    }

    @Test
    fun `a vault holding the old cosine setting is not reinterpreted as a margin`() {
        val file = vaultFile("pv-class-legacy-threshold")
        VaultManager.create(file).let { vault ->
            // What a pre-margin build wrote: a cosine of 0.62. Read as a margin it would silence the
            // suggester entirely, so the new key must simply not see it.
            VaultRepository(vault).setSetting("classification.embedding.threshold", "0.62")
            vault.close()
        }
        VaultManager.open(file).let { vault ->
            val settings = ClassifierSettings.load(VaultRepository(vault))
            assertEquals(ClassifierSettings.DEFAULT_EMBEDDING_MARGIN, settings.embeddingMargin)
            vault.close()
        }
    }

    @Test
    fun `classifier settings round-trip through the vault file`() {
        val file = vaultFile("pv-class-settings")
        val tuned = ClassifierSettings(
            statisticalEnabled = false,
            statisticalThreshold = 0.81,
            embeddingEnabled = true,
            embeddingMargin = 0.042,
        )
        VaultManager.create(file).let { vault ->
            ClassifierSettings.save(VaultRepository(vault), tuned)
            vault.close()
        }
        VaultManager.open(file).let { vault ->
            assertEquals(tuned, ClassifierSettings.load(VaultRepository(vault)), "settings travel with the vault")
            vault.close()
        }
    }

    @Test
    fun `switching both suggesters off leaves classification to the rules alone`() {
        val (repo, categorizer, account) = setup()
        ClassifierSettings.save(repo, ClassifierSettings(statisticalEnabled = false, embeddingEnabled = false))
        repo.insertTransactions(account, null, listOf(tx("REWE Markt", "a"), tx("Unbekannt GmbH", "b")))

        val result = categorizer.classifyAccount(account)

        assertEquals(1, result.committed, "the rule still commits")
        assertEquals(0, result.suggested, "no suggester ran")
        assertTrue(repo.transactions(account).all { it.suggestedCategoryId == null })
    }

    /**
     * Two categories deliberately near-tied — a 0.05 lead for Restaurant over Lebensmittel, nothing
     * else anywhere near. Synthetic vectors on purpose: the real ONNX model is gitignored and may not
     * be provisioned. Prototype texts are "name + keywords", so matching a name/keyword identifies a
     * category's prototype.
     */
    private class TiedEmbedder : Embedder {
        override fun available() = true
        override fun embed(texts: List<String>): List<FloatArray> = texts.map { t ->
            when {
                t.uppercase().contains("RESTAURANT") -> floatArrayOf(1f, 0f, 0f)
                t.uppercase().contains("LEBENSMITTEL") -> floatArrayOf(0.95f, 0.3122f, 0f)
                t.uppercase().contains("ZWEIDEUTIG") -> floatArrayOf(1f, 0f, 0f) // the query
                else -> floatArrayOf(0f, 0f, 1f)
            }
        }
    }

    @Test
    fun `the embedding margin setting actually changes what is proposed`() {
        // The point of the setting: with the old absolute-cosine criterion every plausible value
        // produced the identical outcome (cosines cluster at 0.76-0.88 whatever the text), so the
        // slider was decorative. The margin has to move the operating point end to end.
        val repo = VaultRepository(VaultManager.create(vaultFile("pv-class-margin")))
        val categorizer = Categorizer(repo, TiedEmbedder()).apply { ensureSeeded() }
        val account = repo.addAccount("Giro", AccountType.GIRO)

        ClassifierSettings.save(repo, ClassifierSettings(statisticalEnabled = false, embeddingMargin = 0.03))
        repo.insertTransactions(account, null, listOf(tx("Zweideutig GmbH", "m1")))
        assertEquals(1, categorizer.classifyAccount(account).suggested)
        assertEquals("cat-restaurants", repo.transactions(account).single { it.dedupHash == "m1" }.suggestedCategoryId)

        // Same vault, stricter bar: a 0.05 lead is no longer enough, so the row is left uncategorized.
        ClassifierSettings.save(repo, ClassifierSettings(statisticalEnabled = false, embeddingMargin = 0.10))
        repo.insertTransactions(
            account,
            null,
            listOf(NewTransaction(LocalDate.of(2026, 7, 2), null, -1170, "EUR", "Zweideutig GmbH", "purpose", "Kartenzahlung", "m2")),
        )
        assertEquals(0, categorizer.classifyAccount(account).suggested)
        assertNull(repo.transactions(account).single { it.dedupHash == "m2" }.suggestedCategoryId)
    }

    @Test
    fun `an impossible threshold silences the statistical suggester`() {
        val (repo, categorizer, account) = setup()
        // Confidence is a probability, so nothing can clear 1.0 — the strictest possible setting.
        ClassifierSettings.save(repo, ClassifierSettings(embeddingEnabled = false, statisticalThreshold = 1.0))
        repo.insertTransactions(account, null, listOf(tx("Unbekannt GmbH", "b")))

        assertEquals(0, categorizer.classifyAccount(account).suggested)
        assertFalse(SeedCatalog.rules.isEmpty()) // sanity: the vault really was seeded
        assertNull(repo.transactions(account).single().suggestedCategoryId)
    }
}
