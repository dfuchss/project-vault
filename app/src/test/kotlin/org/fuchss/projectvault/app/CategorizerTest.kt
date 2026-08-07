package org.fuchss.projectvault.app

import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.classification.SeedCatalog
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategorizerTest {

    private fun setup(): Triple<VaultRepository, Categorizer, String> {
        val file = File(Files.createTempDirectory("pv-cat").toFile(), "v.pvault")
        val repo = VaultRepository(VaultManager.create(file))
        val categorizer = Categorizer(repo).apply { ensureSeeded() }
        return Triple(repo, categorizer, repo.addAccount("Giro", AccountType.GIRO))
    }

    private fun tx(counterparty: String, hash: String) =
        NewTransaction(LocalDate.of(2026, 7, 1), null, -1160, "EUR", counterparty, "purpose", "Kartenzahlung", hash)

    /** Deterministic fake: anything restaurant-ish maps to one axis, everything else to another. */
    private class FakeEmbedder : Embedder {
        override fun available() = true
        override fun embed(texts: List<String>) = texts.map { t ->
            val u = t.uppercase()
            if (u.contains("RESTAURANT") || u.contains("TRATTORIA") || u.contains("CAFÉ")) floatArrayOf(1f, 0f, 0f)
            else floatArrayOf(0f, 0f, 1f)
        }
    }

    @Test
    fun `tier 2 embeddings only suggest, and accepting commits`() {
        val file = File(Files.createTempDirectory("pv-cat-e").toFile(), "v.pvault")
        val repo = VaultRepository(VaultManager.create(file))
        val categorizer = Categorizer(repo, FakeEmbedder()).apply { ensureSeeded() }
        val account = repo.addAccount("Giro", AccountType.GIRO)

        // No seed rule matches "Trattoria Napoli"; Tier 2 should SUGGEST (not commit) the restaurant.
        repo.insertTransactions(account, null, listOf(tx("Trattoria Napoli", "a")))
        val result = categorizer.classifyAccount(account)
        assertEquals(0, result.committed)
        assertEquals(1, result.suggested)

        var txn = repo.transactions(account).single()
        assertNull(txn.categoryId, "embedding must not auto-commit")
        assertEquals("cat-restaurants", txn.suggestedCategoryId)

        // Accepting the suggestion commits it and clears the suggestion.
        categorizer.acceptSuggestion(account, txn, txn.suggestedCategoryId!!)
        txn = repo.transactions(account).single()
        assertEquals("cat-restaurants", txn.categoryId)
        assertNull(txn.suggestedCategoryId)
    }

    @Test
    fun `tagesgeld transactions default to transfer, interest to income`() {
        val file = File(Files.createTempDirectory("pv-cat-t").toFile(), "v.pvault")
        val repo = VaultRepository(VaultManager.create(file))
        val categorizer = Categorizer(repo).apply { ensureSeeded() }
        val account = repo.addAccount("Tagesgeld", AccountType.TAGESGELD, institution = "DKB")

        repo.insertTransactions(account, null, listOf(
            tx("Max Mustermann", "a"),                // transfer between own accounts
            NewTransaction(LocalDate.of(2026, 7, 31), null, 1476, "EUR", null, "Zinsen/Kontoabschluss", "Abschluss", "b"),
        ))
        categorizer.classifyAccount(account)

        val byHash = repo.transactions(account)
        assertEquals("cat-transfers", byHash.first { it.counterparty == "Max Mustermann" }.categoryId)
        assertEquals("cat-income", byHash.first { it.purpose.contains("Zinsen") }.categoryId)
    }

    @Test
    fun `credit-card settlement in the giro is detected as a transfer`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("Deutsche Kreditbank Berlin KREDITKARTENABRECHNUNG", "a")))
        categorizer.classifyAccount(account)
        assertEquals("cat-transfers", repo.transactions(account).single().categoryId)
    }

    @Test
    fun `ensureSeeded reconciles categories and rules for an older vault`() {
        val file = File(Files.createTempDirectory("pv-cat-seed").toFile(), "v.pvault")
        val repo = VaultRepository(VaultManager.create(file))
        // Simulate an older vault: income category under its old name, a stale seed rule that has
        // since moved to "Gehalt", and a learned USER rule that must survive reconciliation.
        repo.insertCategory("cat-income", "Einkommen", CategoryKind.INCOME, "#2E7D53", true)
        repo.addRule("LOHN", "cat-income", 0, "SEED")
        repo.addRule("MYSHOP", "cat-shopping", 100, "USER")
        val categorizer = Categorizer(repo)

        categorizer.ensureSeeded()

        // Missing categories backfilled; renamed system category synced; nothing duplicated.
        assertEquals(SeedCatalog.categories.size, repo.categories().size, "missing categories backfilled")
        assertEquals(1, repo.categories().count { it.id == "cat-income" }, "existing category not duplicated")
        assertTrue(repo.categories().any { it.id == "cat-salary" && it.name == "Gehalt" }, "new Gehalt category")
        assertEquals("Weitere Einkünfte", repo.categories().first { it.id == "cat-income" }.name, "rename synced")

        val rules = repo.categoryRules()
        assertTrue(rules.none { it.keyword == "LOHN" && it.categoryId == "cat-income" }, "stale seed rule pruned")
        assertTrue(rules.any { it.keyword == "LOHN" && it.categoryId == "cat-salary" }, "keyword moved to Gehalt")
        assertTrue(rules.any { it.keyword == "MYSHOP" && it.source == "USER" }, "USER rule preserved")

        val count = repo.categoryRules().size
        categorizer.ensureSeeded() // idempotent
        assertEquals(count, repo.categoryRules().size, "second pass changes nothing")
    }

    @Test
    fun `seeds categories and classifies known merchants`() {
        val (repo, categorizer, account) = setup()
        assertEquals(SeedCatalog.categories.size, repo.categories().size)

        repo.insertTransactions(account, null, listOf(
            tx("REWE.Markt/Musterstadt", "a"),
            tx("AMZN.Mktp.DE.X", "b"),
            tx("Random Person 123", "c"),
        ))
        assertEquals(2, categorizer.classifyAccount(account).committed)

        val byCounterparty = repo.transactions(account).associateBy { it.counterparty }
        assertEquals("cat-groceries", byCounterparty["REWE.Markt/Musterstadt"]!!.categoryId)
        assertEquals("cat-shopping", byCounterparty["AMZN.Mktp.DE.X"]!!.categoryId)
        assertNull(byCounterparty["Random Person 123"]!!.categoryId)
    }

    @Test
    fun `reclassifying an already-categorized merchant relearns and propagates`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(
            tx("REWE.Markt.Eins/DE", "a"),
            tx("REWE.Markt.Zwei/DE", "b"),
        ))
        // Both auto-classify to groceries via the seed rule.
        categorizer.classifyAccount(account)
        assertEquals(2, repo.transactions(account).count { it.categoryId == "cat-groceries" })

        // Reclassify one REWE transaction -> the learned USER rule reclassifies the other too.
        val one = repo.transactions(account).first()
        categorizer.setCategory(account, one, "cat-restaurants")
        assertEquals(2, repo.transactions(account).count { it.categoryId == "cat-restaurants" })
    }

    @Test
    fun `applyToOne categorizes only the selected transaction and reports other matches`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE.Markt.Eins/DE", "a"), tx("REWE.Markt.Zwei/DE", "b")))
        val a = repo.transactions(account).first { it.counterparty == "REWE.Markt.Eins/DE" }

        // There is one other REWE transaction that a bulk reclassification would touch.
        assertEquals(1, categorizer.otherMatchesCount(account, a, "cat-shopping"))

        categorizer.applyToOne(a, "cat-shopping")
        val byCounterparty = repo.transactions(account).associateBy { it.counterparty }
        assertEquals("cat-shopping", byCounterparty["REWE.Markt.Eins/DE"]!!.categoryId)
        assertNull(byCounterparty["REWE.Markt.Zwei/DE"]!!.categoryId, "the other transaction must be untouched")
    }

    @Test
    fun `a manual category is not overwritten by another merchant correction`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE.Markt.Eins/DE", "a"), tx("REWE.Markt.Zwei/DE", "b")))
        val txns = repo.transactions(account)
        val a = txns.first { it.counterparty == "REWE.Markt.Eins/DE" }
        val b = txns.first { it.counterparty == "REWE.Markt.Zwei/DE" }

        categorizer.setCategory(account, a, "cat-shopping")     // A manual -> shopping (B follows)
        categorizer.setCategory(account, b, "cat-restaurants")  // B manual -> restaurants; A is MANUAL, must stay

        val byCounterparty = repo.transactions(account).associateBy { it.counterparty }
        assertEquals("cat-shopping", byCounterparty["REWE.Markt.Eins/DE"]!!.categoryId)
        assertEquals("cat-restaurants", byCounterparty["REWE.Markt.Zwei/DE"]!!.categoryId)
    }

    @Test
    fun `learns a rule from a correction and applies it to similar transactions`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(
            tx("Blumen Meyer Laden", "a"),
            tx("Blumen Meyer Filiale", "b"),
        ))
        val first = repo.transactions(account).first { it.counterparty == "Blumen Meyer Laden" }

        categorizer.setCategory(account, first, "cat-shopping")

        val byCounterparty = repo.transactions(account).associateBy { it.counterparty }
        assertEquals("cat-shopping", byCounterparty["Blumen Meyer Laden"]!!.categoryId)
        assertEquals("cat-shopping", byCounterparty["Blumen Meyer Filiale"]!!.categoryId, "learned rule should apply")
    }
}
