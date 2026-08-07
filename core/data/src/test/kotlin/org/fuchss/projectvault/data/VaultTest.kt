package org.fuchss.projectvault.data

import org.fuchss.projectvault.model.AccountType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultTest {

    private fun tempVaultFile(prefix: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        return File(dir, "household.pvault")
    }

    @Test
    fun `create vault, insert joint account, read back`() {
        val file = tempVaultFile("pvault-create")
        VaultManager.create(file).use { vault ->
            val db = vault.database

            db.profileQueries.insertProfile("p1", "Alice", "#E45858", 1_000L)
            db.profileQueries.insertProfile("p2", "Bob", "#4C8BF5", 1_000L)

            // A joint Giro account owned 50/50 by both profiles.
            db.accountQueries.insertAccount(
                "a1", "Gemeinschaftskonto", AccountType.GIRO, "EUR", "DKB", "DE00 0000",
                0L, null, 1_000L,
            )
            db.accountOwnerQueries.insertOwner("a1", "p1", 0.5)
            db.accountOwnerQueries.insertOwner("a1", "p2", 0.5)

            assertEquals(2, db.profileQueries.selectAllProfiles().executeAsList().size)

            val aliceAccounts = db.accountOwnerQueries.selectAccountsForProfile("p1").executeAsList()
            assertEquals(1, aliceAccounts.size)
            assertEquals(AccountType.GIRO, aliceAccounts.first().type)

            assertEquals(2, db.accountOwnerQueries.selectOwnersForAccount("a1").executeAsList().size)
            assertEquals("EUR", db.vaultMetaQueries.selectMeta().executeAsOne().baseCurrency)
        }
        assertTrue(file.exists(), "vault file should be written to disk")
    }

    @Test
    fun `reopening a vault reads persisted data`() {
        val file = tempVaultFile("pvault-reopen")
        VaultManager.create(file).use { vault ->
            vault.database.profileQueries.insertProfile("p1", "Alice", null, 1L)
        }
        VaultManager.open(file).use { vault ->
            assertEquals(1, vault.database.profileQueries.selectAllProfiles().executeAsList().size)
        }
    }
}
