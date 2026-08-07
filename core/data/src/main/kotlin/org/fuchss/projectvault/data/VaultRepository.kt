package org.fuchss.projectvault.data

import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.CategoryRule
import org.fuchss.projectvault.data.db.Holding
import org.fuchss.projectvault.data.db.ImportBatch
import org.fuchss.projectvault.data.db.Profile
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.data.db.VaultDatabase
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import java.time.LocalDate
import java.util.UUID

/** A transaction to persist. `dedupHash` makes re-imports idempotent. */
data class NewTransaction(
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val amountCents: Long,
    val currency: String,
    val counterparty: String?,
    val purpose: String,
    val bookingType: String?,
    val dedupHash: String,
)

/** A securities holding to persist for a DEPOT account snapshot. */
data class NewHolding(
    val isin: String?,
    val wkn: String?,
    val name: String,
    val quantity: String,
    val priceText: String?,
    val marketValueCents: Long,
    val currency: String,
)

/** Provenance for one imported file: where entries came from and when. */
data class NewImportBatch(
    val kind: String,               // TRANSACTIONS | DEPOT
    val sourceName: String,
    val institution: String?,
    val statementNumber: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val valuationDate: LocalDate?,
    val endBalanceCents: Long?,
    val reconciled: Boolean,
    val itemCount: Int,
)

/**
 * Domain-friendly access to a [Vault]'s database. Callers work in [LocalDate] and cents; dates are
 * persisted as epoch-day integers. UI code re-queries after mutations to refresh.
 */
class VaultRepository(private val db: VaultDatabase) {

    constructor(vault: Vault) : this(vault.database)

    // --- Profiles ---
    fun profiles(): List<Profile> = db.profileQueries.selectAllProfiles().executeAsList()

    fun addProfile(name: String, color: String? = null): String =
        newId().also { db.profileQueries.insertProfile(it, name, color, now()) }

    fun updateProfile(id: String, name: String, color: String?) =
        db.profileQueries.updateProfile(name, color, id)

    /** Removes a profile. Ownership rows cascade (FK ON DELETE CASCADE), so accounts stay but lose this owner. */
    fun deleteProfile(id: String) = db.profileQueries.deleteProfile(id)

    fun profilesForAccount(accountId: String): List<Profile> =
        db.accountOwnerQueries.selectProfilesForAccount(accountId).executeAsList()

    /** Replaces an account's owners (a joint account simply has more than one). */
    fun setAccountOwners(accountId: String, profileIds: List<String>) {
        db.transaction {
            db.accountOwnerQueries.deleteOwnersForAccount(accountId)
            val share = if (profileIds.isEmpty()) 1.0 else 1.0 / profileIds.size
            profileIds.forEach { db.accountOwnerQueries.insertOwner(accountId, it, share) }
        }
    }

    // --- Accounts ---
    fun accounts(): List<Account> = db.accountQueries.selectAllAccounts().executeAsList()

    fun account(id: String): Account? = db.accountQueries.selectAccount(id).executeAsOneOrNull()

    /**
     * Deletes an account and everything under it (transactions, holdings, import batches, ownership).
     * Children are removed **explicitly in one transaction** rather than relying on the FK cascade, so
     * deletion is reliable even if `PRAGMA foreign_keys` is off — no orphaned rows are ever left.
     */
    fun deleteAccount(id: String) {
        db.transaction {
            db.txnQueries.deleteTxnsByAccount(id)
            db.holdingQueries.deleteHoldingsByAccount(id)
            db.importBatchQueries.deleteBatchesByAccount(id)
            db.accountOwnerQueries.deleteOwnersForAccount(id)
            db.accountQueries.deleteAccount(id)
        }
    }

    fun addAccount(
        name: String,
        type: AccountType,
        institution: String? = null,
        iban: String? = null,
        currency: String = "EUR",
        ownerProfileIds: List<String> = emptyList(),
    ): String = newId().also { id ->
        db.accountQueries.insertAccount(id, name, type, currency, institution, iban, 0, null, now())
        if (ownerProfileIds.isNotEmpty()) setAccountOwners(id, ownerProfileIds)
    }

    /** Current balance for display: the most recent statement's closing balance / depot total. */
    fun currentBalanceCents(accountId: String): Long? =
        db.importBatchQueries.latestBatchForAccount(accountId).executeAsOneOrNull()?.endBalanceCents

    fun netFlowCents(accountId: String): Long =
        db.txnQueries.sumAmountForAccount(accountId).executeAsOne()

    // --- Categories & rules ---
    fun categories(): List<Category> = db.categoryQueries.selectAllCategories().executeAsList()

    fun insertCategory(id: String, name: String, kind: CategoryKind, color: String?, isSystem: Boolean) {
        db.categoryQueries.insertCategory(id, null, name, kind, null, color, if (isSystem) 1L else 0L)
    }

    /** Creates a user-defined category and returns its id. */
    fun addCategory(name: String, kind: CategoryKind, color: String): String =
        newId().also { db.categoryQueries.insertCategory(it, null, name, kind, null, color, 0L) }

    /** Updates a category's display name + colour (used to sync seed categories to the catalog). */
    fun updateCategoryMeta(id: String, name: String, color: String?) =
        db.categoryQueries.updateCategoryMeta(name, color, id)

    /**
     * Deletes a (user-defined) category. Only non-system categories should be passed; the caller is
     * responsible for that check. All references are cleared first so nothing dangles: transactions
     * that used it become uncategorized, its learned rules are removed, and manual recurring series
     * that pointed at it lose the link.
     */
    fun deleteCategory(id: String) {
        db.transaction {
            db.txnQueries.clearCategoryReferences(id)
            db.txnQueries.clearSuggestionReferences(id)
            db.categoryRuleQueries.deleteRulesByCategory(id)
            db.recurringManualQueries.clearCategoryReferences(id)
            db.categoryQueries.deleteCategory(id)
        }
    }

    fun categoryRules(): List<CategoryRule> = db.categoryRuleQueries.selectAllRules().executeAsList()

    fun addRule(keyword: String, categoryId: String, priority: Int, source: String) {
        db.categoryRuleQueries.insertRule(newId(), keyword, categoryId, priority.toLong(), source, now())
    }

    fun deleteUserRuleByKeyword(keyword: String) = db.categoryRuleQueries.deleteUserRuleByKeyword(keyword)

    fun deleteRuleById(id: String) = db.categoryRuleQueries.deleteRuleById(id)

    fun setTransactionCategory(txnId: String, categoryId: String?, source: String?) =
        db.txnQueries.updateTxnCategory(categoryId, source, txnId)

    fun setSuggestedCategory(txnId: String, categoryId: String) =
        db.txnQueries.setSuggestion(categoryId, txnId)

    fun clearSuggestion(txnId: String) = db.txnQueries.clearSuggestion(txnId)

    // --- Import batches (provenance) ---
    fun createBatch(accountId: String, batch: NewImportBatch): String = newId().also { id ->
        db.importBatchQueries.insertBatch(
            id, accountId, batch.kind, batch.sourceName, batch.institution, batch.statementNumber,
            batch.periodStart?.toEpochDay(), batch.periodEnd?.toEpochDay(), batch.valuationDate?.toEpochDay(),
            batch.endBalanceCents, if (batch.reconciled) 1 else 0, now(), batch.itemCount.toLong(),
        )
    }

    fun batches(accountId: String): List<ImportBatch> =
        db.importBatchQueries.selectBatchesForAccount(accountId).executeAsList()

    fun batch(id: String?): ImportBatch? =
        id?.let { db.importBatchQueries.selectBatch(it).executeAsOneOrNull() }

    fun existingDedupHashes(accountId: String): Set<String> =
        db.txnQueries.dedupHashesForAccount(accountId).executeAsList().toSet()

    /** Reverts an import: deletes the batch and everything it created (transactions or holdings). */
    fun deleteBatch(batchId: String) {
        db.transaction {
            db.txnQueries.deleteTxnsByBatch(batchId)
            db.holdingQueries.deleteHoldingsByBatch(batchId)
            db.importBatchQueries.deleteBatch(batchId)
        }
    }

    // --- Transactions ---
    fun transactions(accountId: String): List<Txn> =
        db.txnQueries.selectTxnsForAccount(accountId).executeAsList()

    fun transactionCount(accountId: String): Long =
        db.txnQueries.countTxnsForAccount(accountId).executeAsOne()

    /** Inserts transactions idempotently (dedup index); returns how many were newly inserted. */
    fun insertTransactions(accountId: String, batchId: String?, transactions: List<NewTransaction>): Int {
        val before = transactionCount(accountId)
        db.transaction {
            transactions.forEach { t ->
                db.txnQueries.insertTxn(
                    newId(), accountId, t.bookingDate.toEpochDay(), t.valueDate?.toEpochDay(),
                    t.amountCents, t.currency, t.counterparty, t.purpose, t.bookingType,
                    null, null, null, batchId, t.dedupHash, now(),
                )
            }
        }
        return (transactionCount(accountId) - before).toInt()
    }

    // --- Holdings (dated Depot snapshots) ---
    fun valuationDates(accountId: String): List<Long> =
        db.holdingQueries.valuationDates(accountId).executeAsList()

    fun latestHoldings(accountId: String): List<Holding> {
        val latest = db.holdingQueries.latestValuationDate(accountId).executeAsOne().latest ?: return emptyList()
        return db.holdingQueries.selectHoldingsForValuationDate(accountId, latest).executeAsList()
    }

    fun holdingsForValuationDate(accountId: String, valuationDate: Long): List<Holding> =
        db.holdingQueries.selectHoldingsForValuationDate(accountId, valuationDate).executeAsList()

    /** Stores a dated Depot snapshot, replacing any existing snapshot for the same valuation date. */
    fun storeDepotSnapshot(accountId: String, valuationDate: LocalDate?, batchId: String?, holdings: List<NewHolding>) {
        val day = valuationDate?.toEpochDay()
        db.transaction {
            if (day != null) db.holdingQueries.deleteHoldingsForValuationDate(accountId, day)
            holdings.forEach { h ->
                db.holdingQueries.insertHolding(
                    newId(), accountId, batchId, h.isin, h.wkn, h.name, h.quantity, h.priceText,
                    h.marketValueCents, h.currency, day,
                )
            }
        }
    }

    // --- Recurring overrides (rename / hide a detected series, keyed by analytics merchant key) ---
    fun recurringOverrides(): Map<String, RecurringOverride> =
        db.recurringOverrideQueries.selectAllOverrides().executeAsList()
            .associate { it.merchantKey to RecurringOverride(it.merchantKey, it.label, it.hidden == 1L) }

    fun setRecurringOverride(merchantKey: String, label: String?, hidden: Boolean) =
        db.recurringOverrideQueries.upsertOverride(merchantKey, label, if (hidden) 1L else 0L, now())

    fun clearRecurringOverride(merchantKey: String) =
        db.recurringOverrideQueries.deleteOverride(merchantKey)

    // --- Manual recurring series (user-authored, merged with detected ones in the UI/forecast) ---
    fun manualRecurring(): List<ManualRecurring> =
        db.recurringManualQueries.selectAllManual().executeAsList().map {
            ManualRecurring(it.id, it.label, it.categoryId, it.cadence, it.amountCents, LocalDate.ofEpochDay(it.nextDate))
        }

    fun addManualRecurring(label: String, categoryId: String?, cadence: String, amountCents: Long, nextDate: LocalDate): String =
        newId().also { db.recurringManualQueries.insertManual(it, label, categoryId, cadence, amountCents, nextDate.toEpochDay(), now()) }

    fun updateManualRecurring(id: String, label: String, categoryId: String?, cadence: String, amountCents: Long, nextDate: LocalDate) =
        db.recurringManualQueries.updateManual(label, categoryId, cadence, amountCents, nextDate.toEpochDay(), id)

    fun deleteManualRecurring(id: String) = db.recurringManualQueries.deleteManual(id)

    private fun newId(): String = UUID.randomUUID().toString()
    private fun now(): Long = System.currentTimeMillis()
}

/** A user override for an auto-detected recurring series. */
data class RecurringOverride(val merchantKey: String, val label: String?, val hidden: Boolean)

/** A user-authored recurring series (cadence is a Cadence enum name; amountCents signed). */
data class ManualRecurring(
    val id: String,
    val label: String,
    val categoryId: String?,
    val cadence: String,
    val amountCents: Long,
    val nextDate: LocalDate,
)
