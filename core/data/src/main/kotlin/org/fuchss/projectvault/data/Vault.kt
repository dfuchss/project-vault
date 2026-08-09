package org.fuchss.projectvault.data

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.VaultDatabase
import java.io.File

/**
 * An open vault: the SQLite-backed [VaultDatabase] plus its driver and file location.
 *
 * A vault is a single, portable file (see [VaultManager]); copying the file moves the
 * whole household's data to another device. Call [close] to release the connection.
 */
class Vault internal constructor(
    val database: VaultDatabase,
    private val driver: SqlDriver,
    val path: File,
) : AutoCloseable {
    override fun close() = driver.close()
}

/** Creates and opens portable vault files. */
object VaultManager {
    const val SCHEMA_VERSION: Long = 1
    private const val APP_VERSION = "0.1.0"

    /** Creates a brand-new vault file. Fails if [path] already exists. */
    fun create(
        path: File,
        baseCurrency: String = "EUR",
        now: Long = System.currentTimeMillis(),
    ): Vault {
        require(!path.exists()) { "A vault already exists at $path" }
        path.absoluteFile.parentFile?.mkdirs()
        val driver = connect(path)
        VaultDatabase.Schema.create(driver)
        val database = buildDatabase(driver)
        database.vaultMetaQueries.insertMeta(SCHEMA_VERSION, APP_VERSION, baseCurrency, now, now)
        return Vault(database, driver, path)
    }

    /** Opens an existing vault file. Fails if [path] does not exist. */
    fun open(path: File): Vault {
        require(path.exists()) { "No vault found at $path" }
        val driver = connect(path)
        ensureAuxSchema(driver)
        purgeOrphans(driver)
        val database = buildDatabase(driver)
        // TODO(#2): schema-version check + full migrations when SCHEMA_VERSION advances.
        return Vault(database, driver, path)
    }

    /**
     * One-time cleanup on open: removes rows whose owning account no longer exists — legacy debris
     * from vaults created before account deletion cascaded reliably (see [VaultRepository.deleteAccount]).
     * Idempotent and cheap; a no-op once a vault is clean.
     */
    private fun purgeOrphans(driver: SqlDriver) {
        listOf("txn", "holding", "importBatch", "accountOwner").forEach { table ->
            driver.execute(null, "DELETE FROM $table WHERE accountId NOT IN (SELECT id FROM account)", 0)
        }
    }

    /**
     * Additive, idempotent schema shims for tables introduced after a vault was first created, so
     * older vaults keep opening. `CREATE TABLE IF NOT EXISTS` mirrors the `.sq` definition and is a
     * no-op on new vaults (where [VaultDatabase.Schema] already created the table).
     */
    private fun ensureAuxSchema(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS recurringOverride (
                merchantKey TEXT NOT NULL PRIMARY KEY,
                label       TEXT,
                hidden      INTEGER NOT NULL DEFAULT 0,
                createdAt   INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS recurringManual (
                id          TEXT NOT NULL PRIMARY KEY,
                label       TEXT NOT NULL,
                categoryId  TEXT,
                cadence     TEXT NOT NULL,
                amountCents INTEGER NOT NULL,
                nextDate    INTEGER NOT NULL,
                createdAt   INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        addColumnIfMissing(driver, table = "category", column = "enabled", ddl = "INTEGER NOT NULL DEFAULT 1")
        // Live securities prices: the per-account opt-in, and the quote time behind a repriced holding.
        addColumnIfMissing(driver, table = "account", column = "liveQuotes", ddl = "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(driver, table = "holding", column = "quoteAt", ddl = "INTEGER")
    }

    /**
     * Adds a column to an existing table if it isn't there yet — the ADD COLUMN counterpart to the
     * CREATE TABLE shims above, for columns introduced after a vault was first created. SQLite has no
     * `ADD COLUMN IF NOT EXISTS`, so we check `pragma_table_info` first (no-op on new vaults).
     */
    private fun addColumnIfMissing(driver: SqlDriver, table: String, column: String, ddl: String) {
        val count = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = '$column'",
            { cursor: SqlCursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
        if (count == 0L) {
            driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $ddl", 0)
        }
    }

    private fun connect(path: File): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}").apply {
            execute(null, "PRAGMA foreign_keys = ON", 0)
        }

    private fun buildDatabase(driver: SqlDriver): VaultDatabase =
        VaultDatabase(
            driver = driver,
            accountAdapter = Account.Adapter(typeAdapter = EnumColumnAdapter()),
            categoryAdapter = Category.Adapter(kindAdapter = EnumColumnAdapter()),
        )
}
