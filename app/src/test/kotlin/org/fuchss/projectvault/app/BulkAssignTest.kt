package org.fuchss.projectvault.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind

/**
 * Bulk assignment has two promises: it can only offer categories that fit **every** selected row's
 * sign, and it can always be taken back. Both are checked against a real vault, like [CategorizerTest].
 */
class BulkAssignTest {

    private class Fixture {
        val repo: VaultRepository
        val categorizer: Categorizer
        val bulk: BulkAssign
        val account: String

        init {
            val file = File(Files.createTempDirectory("pv-bulk").toFile(), "v.pvault")
            repo = VaultRepository(VaultManager.create(file))
            categorizer = Categorizer(repo).apply { ensureSeeded() }
            bulk = BulkAssign(repo, categorizer)
            account = repo.addAccount("Giro", AccountType.GIRO)
        }
    }

    private fun debit(hash: String, counterparty: String = "Unbekannt") =
        NewTransaction(LocalDate.of(2026, 7, 1), null, -1160, "EUR", counterparty, "purpose $hash", "Kartenzahlung", hash)

    private fun credit(hash: String) =
        NewTransaction(LocalDate.of(2026, 7, 2), null, 24990, "EUR", "Arbeitgeber", "purpose $hash", "Gutschrift", hash)

    @Test
    fun `a mixed-sign selection may only be given categories admissible for every row`() {
        val f = Fixture()
        val categories = f.repo.categories()

        val debits = listOf(-1160L, -400L)
        val credits = listOf(2500L, 100L)
        val mixed = debits + credits

        assertTrue(admissibleCategories(debits, categories).none { it.kind == CategoryKind.INCOME })
        assertTrue(admissibleCategories(credits, categories).none { it.kind == CategoryKind.EXPENSE })

        // The intersection of "expense or transfer" and "income or transfer" is exactly the transfers.
        val forMixed = admissibleCategories(mixed, categories)
        assertTrue(forMixed.isNotEmpty(), "transfers must remain available for a mixed selection")
        assertTrue(forMixed.all { it.kind == CategoryKind.TRANSFER }, "got ${forMixed.map { it.kind }}")
        assertTrue(isMixedSign(mixed))
        assertTrue(!isMixedSign(debits) && !isMixedSign(credits))

        // A zero amount carries no direction, so it narrows nothing (CategorySign's own rule).
        assertEquals(categories.size, admissibleCategories(listOf(0L), categories).size)
        // And an empty selection cannot rule anything out either.
        assertEquals(categories.size, admissibleCategories(emptyList(), categories).size)
    }

    @Test
    fun `bulk apply sets every row MANUAL without learning a rule`() {
        val f = Fixture()
        f.repo.insertTransactions(f.account, null, listOf(debit("a", "REWE"), debit("b", "REWE"), debit("c", "ALDI")))
        val rulesBefore = f.repo.categoryRules().size

        val selected = f.repo.transactions(f.account).take(2)
        f.bulk.apply(selected, CAT_OTHER)

        val after = f.repo.transactions(f.account).associateBy { it.id }
        selected.forEach {
            assertEquals(CAT_OTHER, after.getValue(it.id).categoryId)
            assertEquals(CategorySource.MANUAL, after.getValue(it.id).categorySource)
        }
        assertEquals(rulesBefore, f.repo.categoryRules().size, "a bulk selection is arbitrary — no rule may be learned")
    }

    @Test
    fun `undo restores the previous category, source and pending suggestion`() {
        val f = Fixture()
        f.repo.insertTransactions(f.account, null, listOf(debit("a"), debit("b"), debit("c")))
        val all = f.repo.transactions(f.account)

        // Three different starting states: a seed-rule category, a pending suggestion, and untouched.
        f.repo.setTransactionCategory(all[0].id, CAT_SALARY, CategorySource.SEED_RULE)
        f.repo.setSuggestedCategory(all[1].id, CAT_OTHER)

        val before = f.repo.transactions(f.account).associateBy { it.id }
        val result = f.bulk.apply(f.repo.transactions(f.account), CAT_OTHER)
        assertTrue(f.repo.transactions(f.account).all { it.categoryId == CAT_OTHER })
        assertTrue(f.repo.transactions(f.account).all { it.suggestedCategoryId == null }, "assigning clears suggestions")

        f.bulk.undo(result)

        f.repo.transactions(f.account).forEach { now ->
            val was = before.getValue(now.id)
            assertEquals(was.categoryId, now.categoryId, "category restored")
            assertEquals(was.categorySource, now.categorySource, "source restored")
            assertEquals(was.suggestedCategoryId, now.suggestedCategoryId, "pending suggestion restored")
        }
        assertNull(f.repo.transactions(f.account).first { it.id == all[2].id }.categoryId)
    }

    @Test
    fun `shift-click selects the range between the anchor and the clicked row`() {
        val ids = listOf("1", "2", "3", "4", "5")
        assertEquals(setOf("2"), toggleSelection(ids, emptySet(), anchor = null, target = "2", shift = false))
        assertEquals(emptySet(), toggleSelection(ids, setOf("2"), anchor = "2", target = "2", shift = false))
        assertEquals(setOf("2", "3", "4"), toggleSelection(ids, setOf("2"), anchor = "2", target = "4", shift = true))
        // Backwards works the same way…
        assertEquals(setOf("2", "3", "4"), toggleSelection(ids, setOf("4"), anchor = "4", target = "2", shift = true))
        // …and without an anchor a shift-click is just a click.
        assertEquals(setOf("3"), toggleSelection(ids, emptySet(), anchor = null, target = "3", shift = true))
    }
}
