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

    /** Incoming money, with a purpose that carries the meaning (as a real credit line does). */
    private fun credit(counterparty: String, purpose: String, hash: String) =
        NewTransaction(LocalDate.of(2026, 7, 1), null, 24990, "EUR", counterparty, purpose, "Gutschrift", hash)

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
    fun `disabling a category reassigns its entries to Sonstiges and stops its rule committing`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("REWE.Markt.Eins/DE", "a")))
        categorizer.classifyAccount(account)
        assertEquals("cat-groceries", repo.transactions(account).single().categoryId)

        repo.disableCategory("cat-groceries", CAT_OTHER, CAT_INCOME)
        assertEquals(CAT_OTHER, repo.transactions(account).single().categoryId, "existing entry moved to Sonstiges")

        // A fresh REWE transaction must no longer auto-commit to the disabled grocery category.
        repo.insertTransactions(account, null, listOf(tx("REWE.Markt.Zwei/DE", "b")))
        categorizer.classifyAccount(account)
        assertNull(
            repo.transactions(account).first { it.counterparty == "REWE.Markt.Zwei/DE" }.categoryId,
            "a disabled category's rule must not commit",
        )
    }

    @Test
    fun `addCategory with keywords learns rules that classify matching transactions`() {
        val (repo, categorizer, account) = setup()
        val id = categorizer.addCategory("Spenden", CategoryKind.EXPENSE, "#445566", listOf("BETTERPLACE"))
        repo.insertTransactions(account, null, listOf(tx("BETTERPLACE gemeinnuetzig", "a")))
        categorizer.classifyAccount(account)
        assertEquals(id, repo.transactions(account).single().categoryId, "keyword rule classifies into the new category")
    }

    @Test
    fun `editing a category's keywords replaces its rules`() {
        val (repo, categorizer, account) = setup()
        val id = categorizer.addCategory("Spenden", CategoryKind.EXPENSE, "#445566", listOf("BETTERPLACE"))
        categorizer.updateCategory(id, "Spenden", "#445566", listOf("GOFUNDME"))   // drop BETTERPLACE, add GOFUNDME

        repo.insertTransactions(account, null, listOf(tx("BETTERPLACE gGmbH", "a"), tx("GOFUNDME campaign", "b")))
        categorizer.classifyAccount(account)

        val byCp = repo.transactions(account).associateBy { it.counterparty }
        assertNull(byCp["BETTERPLACE gGmbH"]!!.categoryId, "the removed keyword no longer classifies")
        assertEquals(id, byCp["GOFUNDME campaign"]!!.categoryId, "the new keyword classifies")
    }

    /** A transaction whose merchant lives in the purpose only — no counterparty at all. */
    private fun purposeOnly(purpose: String, hash: String) =
        NewTransaction(LocalDate.of(2026, 7, 1), null, -1160, "EUR", null, purpose, "Lastschrift", hash)

    @Test
    fun `learns from the purpose when the statement carries no counterparty`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(
            purposeOnly("Beitrag Kletterhalle Nordwand 07/2026", "a"),
            purposeOnly("Beitrag Kletterhalle Nordwand 08/2026", "b"),
        ))
        val first = repo.transactions(account).first { it.purpose.endsWith("07/2026") }

        categorizer.setCategory(account, first, "cat-leisure")

        val byPurpose = repo.transactions(account).associateBy { it.purpose }
        assertEquals("cat-leisure", byPurpose["Beitrag Kletterhalle Nordwand 07/2026"]!!.categoryId)
        assertEquals(
            "cat-leisure",
            byPurpose["Beitrag Kletterhalle Nordwand 08/2026"]!!.categoryId,
            "a correction on a counterparty-less row must still learn and propagate",
        )
        // …and the learned rule classifies the next import, not just the rows already there.
        repo.insertTransactions(account, null, listOf(purposeOnly("Beitrag Kletterhalle Nordwand 09/2026", "c")))
        categorizer.classifyAccount(account)
        assertEquals("cat-leisure", repo.transactions(account).first { it.purpose.endsWith("09/2026") }.categoryId)
    }

    @Test
    fun `a purely numeric purpose token is never learned as a keyword`() {
        val (repo, categorizer, account) = setup()
        // A reference number identifies one transaction, so it must not become a rule; "Rechnung" may.
        repo.insertTransactions(account, null, listOf(purposeOnly("8829301 Rechnung Hausverwaltung", "a")))
        val txn = repo.transactions(account).single()

        categorizer.setCategory(account, txn, "cat-housing")

        val learned = repo.categoryRules().single { it.source == "USER" }
        assertEquals("RECHNUNG", learned.keyword)
    }

    @Test
    fun `learned keywords propagate across umlaut spellings`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(
            tx("Doenerhaus Karlsruhe", "a"),
            tx("Dönerhaus Karlsruhe", "b"),
        ))
        val folded = repo.transactions(account).first { it.counterparty == "Doenerhaus Karlsruhe" }

        categorizer.setCategory(account, folded, "cat-restaurants")

        assertEquals(
            "cat-restaurants",
            repo.transactions(account).first { it.counterparty == "Dönerhaus Karlsruhe" }.categoryId,
            "propagation folds diacritics on both sides, like the rule engine does",
        )
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

    // ---------------------------------------------------------------- amount sign

    private fun kindOf(repo: VaultRepository, categoryId: String?): CategoryKind? =
        categoryId?.let { id -> repo.categories().firstOrNull { it.id == id }?.kind }

    @Test
    fun `a credit is never committed an expense category`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(
            account,
            null,
            listOf(
                credit("AMZN Mktp DE", "Rueckerstattung Bestellung", "a"),
                credit("Techniker Krankenkasse", "Erstattung Zahnarzt", "b"),
                credit("Arbeitgeber GmbH", "Nettobezuege 07/2026", "c"),
                credit("Stadtwerke Musterstadt", "Nebenkostenabrechnung Guthaben", "d"),
            ),
        )
        categorizer.classifyAccount(account)

        repo.transactions(account).forEach { txn ->
            assertTrue(
                kindOf(repo, txn.categoryId) != CategoryKind.EXPENSE,
                "credit '${txn.counterparty} ${txn.purpose}' got expense category ${txn.categoryId}",
            )
            assertTrue(
                kindOf(repo, txn.suggestedCategoryId) != CategoryKind.EXPENSE,
                "credit '${txn.counterparty} ${txn.purpose}' was proposed expense category ${txn.suggestedCategoryId}",
            )
        }
        // The payroll credit is not merely "not an expense" — it is recognised as salary.
        val salary = repo.transactions(account).single { it.purpose.contains("Nettobezuege") }
        assertEquals(CAT_SALARY, salary.categoryId)
    }

    @Test
    fun `correcting a purchase does not relabel the same merchant's refunds`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(
            account,
            null,
            listOf(tx("Kaufhaus Musterstadt", "a"), credit("Kaufhaus Musterstadt", "Retoure Gutschrift", "b")),
        )
        val purchase = repo.transactions(account).single { it.amountCents < 0 }

        // Teaching "Kaufhaus -> Shopping" from the purchase must leave the credit alone: an expense
        // category cannot describe incoming money, whatever the merchant.
        categorizer.setCategory(account, purchase, "cat-shopping")

        assertEquals("cat-shopping", repo.transactions(account).single { it.amountCents < 0 }.categoryId)
        val refund = repo.transactions(account).single { it.amountCents > 0 }
        assertTrue(kindOf(repo, refund.categoryId) != CategoryKind.EXPENSE, "refund relabelled to ${refund.categoryId}")
        assertEquals(0, categorizer.otherMatchesCount(account, purchase, "cat-shopping"), "refund must not be offered for bulk apply")
    }

    @Test
    fun `disabling an expense category sends its credits to the income fallback`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(account, null, listOf(tx("Rewe Markt", "a"), credit("Rewe Markt", "Erstattung", "b")))
        // Force the legacy state this guards against: a credit sitting in an expense category.
        val refund = repo.transactions(account).single { it.amountCents > 0 }
        repo.setTransactionCategory(refund.id, "cat-groceries", CategorySource.MANUAL)
        categorizer.classifyAccount(account)

        repo.disableCategory("cat-groceries", CAT_OTHER, CAT_INCOME)

        assertEquals(CAT_OTHER, repo.transactions(account).single { it.amountCents < 0 }.categoryId)
        assertEquals(CAT_INCOME, repo.transactions(account).single { it.amountCents > 0 }.categoryId, "credit must not land in Sonstiges")
    }

    @Test
    fun `tier 2 never proposes Sonstiges`() {
        val file = File(Files.createTempDirectory("pv-cat-o").toFile(), "v.pvault")
        val repo = VaultRepository(VaultManager.create(file))
        val categorizer = Categorizer(repo, FakeEmbedder()).apply { ensureSeeded() }
        val account = repo.addAccount("Giro", AccountType.GIRO)

        // An opaque payee no rule matches: Sonstiges used to be the nearest prototype, because it is
        // the only category whose vector is just its own name.
        repo.insertTransactions(account, null, listOf(tx("Hans Meier", "a")))
        categorizer.classifyAccount(account)

        val txn = repo.transactions(account).single()
        assertTrue(txn.suggestedCategoryId != CAT_OTHER, "Sonstiges must never be a Tier-2 proposal")
    }
}
