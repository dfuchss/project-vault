package org.fuchss.projectvault.data

import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultRepositoryTest {

    private fun repo(): VaultRepository {
        val file = File(Files.createTempDirectory("pvault-repo").toFile(), "v.pvault")
        return VaultRepository(VaultManager.create(file))
    }

    private fun tx(day: Int, cents: Long, hash: String) = NewTransaction(
        bookingDate = LocalDate.of(2026, 7, day),
        valueDate = LocalDate.of(2026, 7, day - 1),
        amountCents = cents,
        currency = "EUR",
        counterparty = "MERCHANT $hash",
        purpose = "test",
        bookingType = "Kartenzahlung",
        dedupHash = hash,
    )

    @Test
    fun `inserts transactions with a batch and de-duplicates on re-import`() {
        val repo = repo()
        val accountId = repo.addAccount("Giro", AccountType.GIRO, institution = "DKB")
        val batch = repo.createBatch(accountId, NewImportBatch("TRANSACTIONS", "aug.pdf", "DKB", "8/2026", null, null, null, 31_842_07, true, 2))

        assertEquals(2, repo.insertTransactions(accountId, batch, listOf(tx(6, -3145, "a"), tx(7, -2199, "b"))))
        assertEquals(1, repo.insertTransactions(accountId, batch, listOf(tx(6, -3145, "a"), tx(8, -640, "c"))))
        assertEquals(3, repo.transactionCount(accountId))

        // Provenance: the batch is retrievable and the closing balance drives the displayed balance.
        assertEquals(1, repo.batches(accountId).size)
        assertEquals(31_842_07, repo.currentBalanceCents(accountId))
        assertEquals(batch, repo.transactions(accountId).first().importBatchId)
    }

    @Test
    fun `assigns joint owners to an account`() {
        val repo = repo()
        val alice = repo.addProfile("Alice", "#E45858")
        val bob = repo.addProfile("Bob", "#4C8BF5")
        val accountId = repo.addAccount("Gemeinschaftskonto", AccountType.GIRO, ownerProfileIds = listOf(alice, bob))

        assertEquals(2, repo.profilesForAccount(accountId).size)
        repo.setAccountOwners(accountId, listOf(alice))
        assertEquals(listOf("Alice"), repo.profilesForAccount(accountId).map { it.name })
    }

    @Test
    fun `keeps dated depot snapshots and replaces same-date snapshot`() {
        val repo = repo()
        val accountId = repo.addAccount("Depot", AccountType.DEPOT, institution = "ING")
        val dateA = LocalDate.of(2026, 3, 31)
        val dateB = LocalDate.of(2026, 4, 30)

        repo.storeDepotSnapshot(accountId, dateA, null, listOf(
            NewHolding("DE1", null, "ACME", "10", null, 63000, "EUR"),
            NewHolding("IE1", null, "ETF", "2.5", null, 63000, "EUR"),
        ))
        repo.storeDepotSnapshot(accountId, dateB, null, listOf(NewHolding("DE1", null, "ACME", "12", null, 88000, "EUR")))

        // Latest snapshot is the newest valuation date; the older snapshot is retained for history.
        assertEquals(1, repo.latestHoldings(accountId).size)
        assertEquals(listOf(dateB.toEpochDay(), dateA.toEpochDay()), repo.valuationDates(accountId))
        assertEquals(2, repo.holdingsForValuationDate(accountId, dateA.toEpochDay()).size)

        // Re-importing the same valuation date replaces just that snapshot.
        repo.storeDepotSnapshot(accountId, dateA, null, listOf(NewHolding("DE1", null, "ACME", "9", null, 51000, "EUR")))
        assertEquals(1, repo.holdingsForValuationDate(accountId, dateA.toEpochDay()).size)
        assertEquals(2, repo.valuationDates(accountId).size)
    }

    @Test
    fun `deleteBatch reverts an import and exposes dedup hashes`() {
        val repo = repo()
        val accountId = repo.addAccount("Giro", AccountType.GIRO)
        val batch = repo.createBatch(accountId, NewImportBatch("TRANSACTIONS", "jul.pdf", "DKB", null, null, null, null, 0, true, 2))
        repo.insertTransactions(accountId, batch, listOf(tx(6, -3145, "a"), tx(7, -2199, "b")))

        assertEquals(setOf("a", "b"), repo.existingDedupHashes(accountId))
        assertEquals(2, repo.transactionCount(accountId))

        repo.deleteBatch(batch)
        assertEquals(0, repo.transactionCount(accountId))
        assertEquals(0, repo.batches(accountId).size)
        assertEquals(emptySet(), repo.existingDedupHashes(accountId))
    }

    @Test
    fun `currentBalance is null before any import`() {
        val repo = repo()
        val accountId = repo.addAccount("Giro", AccountType.GIRO)
        assertNull(repo.currentBalanceCents(accountId))
    }

    @Test
    fun `stores, replaces and clears recurring overrides`() {
        val repo = repo()
        assertEquals(emptyMap(), repo.recurringOverrides())

        repo.setRecurringOverride("SATURN", "Apple Care Plus", hidden = false)
        repo.setRecurringOverride("KREDITKARTE", null, hidden = true)
        val overrides = repo.recurringOverrides()
        assertEquals("Apple Care Plus", overrides["SATURN"]?.label)
        assertEquals(false, overrides["SATURN"]?.hidden)
        assertEquals(true, overrides["KREDITKARTE"]?.hidden)

        repo.setRecurringOverride("SATURN", "AppleCare", hidden = false) // upsert replaces
        assertEquals("AppleCare", repo.recurringOverrides()["SATURN"]?.label)

        repo.clearRecurringOverride("SATURN")
        assertNull(repo.recurringOverrides()["SATURN"])
    }

    @Test
    fun `recurring overrides survive closing and reopening the vault`() {
        val file = File(Files.createTempDirectory("pvault-reopen").toFile(), "v.pvault")
        VaultManager.create(file).let { vault ->
            VaultRepository(vault).setRecurringOverride("MIETE", "Wohnung", hidden = false)
            vault.close()
        }
        VaultManager.open(file).let { vault ->
            assertEquals("Wohnung", VaultRepository(vault).recurringOverrides()["MIETE"]?.label)
            vault.close()
        }
    }

    @Test
    fun `adds, edits and deletes a manual recurring series`() {
        val repo = repo()
        assertEquals(emptyList(), repo.manualRecurring())

        val id = repo.addManualRecurring("Apple Care Plus", "cat-subscriptions", "MONTHLY", -1290, LocalDate.of(2026, 8, 15))
        val added = repo.manualRecurring().single()
        assertEquals("Apple Care Plus", added.label)
        assertEquals(-1290, added.amountCents)
        assertEquals("MONTHLY", added.cadence)
        assertEquals(LocalDate.of(2026, 8, 15), added.nextDate)
        assertEquals("cat-subscriptions", added.categoryId)

        repo.updateManualRecurring(id, "AppleCare", null, "YEARLY", -12900, LocalDate.of(2027, 1, 1))
        val edited = repo.manualRecurring().single()
        assertEquals("AppleCare", edited.label)
        assertEquals("YEARLY", edited.cadence)
        assertNull(edited.categoryId)
        assertEquals(-12900, edited.amountCents)

        repo.deleteManualRecurring(id)
        assertEquals(emptyList(), repo.manualRecurring())
    }

    @Test
    fun `deleting a category uncategorizes its transactions and removes its links`() {
        val repo = repo()
        val account = repo.addAccount("Giro", AccountType.GIRO)
        val catId = repo.addCategory("Custom", CategoryKind.EXPENSE, "#123456")
        repo.insertTransactions(account, null, listOf(tx(2, -1160, "h1")))
        val txn = repo.transactions(account).single()
        repo.setTransactionCategory(txn.id, catId, "MANUAL")
        repo.addRule("CUSTOMSHOP", catId, 100, "USER")
        repo.addManualRecurring("Membership", catId, "MONTHLY", -640, LocalDate.of(2026, 9, 1))

        repo.deleteCategory(catId)

        assertNull(repo.transactions(account).single().categoryId, "transaction becomes uncategorized")
        assertTrue(repo.categoryRules().none { it.categoryId == catId }, "its rules are removed")
        assertNull(repo.manualRecurring().single().categoryId, "manual series loses the link")
        assertTrue(repo.categories().none { it.id == catId }, "the category is gone")
    }

    @Test
    fun `deleting an account removes its transactions, batches and ownership`() {
        val repo = repo()
        val alice = repo.addProfile("Alice")
        val account = repo.addAccount("Giro", AccountType.GIRO, ownerProfileIds = listOf(alice))
        val batch = repo.createBatch(account, NewImportBatch("TRANSACTIONS", "aug.pdf", "DKB", null, null, null, null, 0, true, 2))
        repo.insertTransactions(account, batch, listOf(tx(2, -1160, "a"), tx(3, -2340, "b")))
        // A second account stays untouched.
        val other = repo.addAccount("Tagesgeld", AccountType.TAGESGELD)
        repo.insertTransactions(other, null, listOf(tx(4, -640, "c")))

        repo.deleteAccount(account)

        assertTrue(repo.accounts().none { it.id == account }, "account gone")
        assertEquals(0, repo.transactionCount(account), "its transactions gone")
        assertTrue(repo.batches(account).isEmpty(), "its import batches gone")
        assertTrue(repo.profilesForAccount(account).isEmpty(), "its ownership rows gone")
        assertEquals(1, repo.transactionCount(other), "the other account is untouched")
        assertEquals(1, repo.accounts().size)
    }

    @Test
    fun `opening a vault purges rows orphaned by a vanished account`() {
        val file = File(Files.createTempDirectory("pvault-orphans").toFile(), "v.pvault")
        VaultManager.create(file).let { vault ->
            val repo = VaultRepository(vault)
            val account = repo.addAccount("Giro", AccountType.GIRO)
            val batch = repo.createBatch(account, NewImportBatch("TRANSACTIONS", "x.pdf", "DKB", null, null, null, null, 0, true, 2))
            repo.insertTransactions(account, batch, listOf(tx(2, -1160, "a"), tx(3, -2340, "b")))
            vault.close()
        }
        // Simulate a legacy vault: delete the account row with FK enforcement OFF, so the child rows
        // are left orphaned (exactly the debris the purge-on-open must clean up).
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.execute("PRAGMA foreign_keys=OFF")
                s.executeUpdate("DELETE FROM account")
            }
        }
        // Opening the vault runs the purge.
        VaultManager.open(file).close()

        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { s ->
                listOf("txn", "importBatch").forEach { table ->
                    s.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1), "orphaned rows in $table must be purged")
                    }
                }
            }
        }
    }

    @Test
    fun `manual recurring series survive closing and reopening the vault`() {
        val file = File(Files.createTempDirectory("pvault-reopen-manual").toFile(), "v.pvault")
        VaultManager.create(file).let { vault ->
            VaultRepository(vault).addManualRecurring("Gym", null, "MONTHLY", -3490, LocalDate.of(2026, 9, 1))
            vault.close()
        }
        VaultManager.open(file).let { vault ->
            val m = VaultRepository(vault).manualRecurring().single()
            assertEquals("Gym", m.label)
            assertEquals(LocalDate.of(2026, 9, 1), m.nextDate)
            vault.close()
        }
    }
}
