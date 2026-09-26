package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.categoryAllowedForAmount

/**
 * What one transaction looked like before a bulk assignment — everything needed to put it back.
 *
 * The suggestion is captured alongside the committed category because assigning clears it: an undo
 * that restored the category but silently dropped a pending Tier-2 proposal would not be an undo.
 */
internal data class CategoryChange(
    val txnId: String,
    val previousCategoryId: String?,
    val previousSource: String?,
    val previousSuggestedCategoryId: String?,
)

/** A completed bulk assignment, kept for one step of undo (and to name what happened in the UI). */
internal data class BulkResult(val categoryId: String, val changes: List<CategoryChange>)

/**
 * Applies one category to many transactions at once, and remembers how to take it back.
 *
 * Bulk assignment is intentionally the *narrow* kind of correction: each row goes through
 * [Categorizer.applyToOne], which sets it `MANUAL` and touches nothing else. **No keyword rule is
 * learned.** That is not an oversight — a hand-picked selection is arbitrary (a search result, a
 * date range, whatever the user ticked), so there is no merchant to generalize from, and deriving a
 * keyword from an arbitrary set would teach a rule nobody asked for and spread it to future imports.
 * Learning stays where the user opts into it explicitly: the "apply to matching transactions?"
 * dialog on a single correction (see docs/CLASSIFICATION.md).
 */
internal class BulkAssign(private val repo: VaultRepository, private val categorizer: Categorizer) {

    /** Sets [categoryId] on every transaction in [txns], returning the undo record for the write. */
    fun apply(txns: List<Txn>, categoryId: String): BulkResult {
        // Snapshot first: once applyToOne has run, the previous state is gone from the row.
        val changes = txns.map { CategoryChange(it.id, it.categoryId, it.categorySource, it.suggestedCategoryId) }
        txns.forEach { categorizer.applyToOne(it, categoryId) }
        return BulkResult(categoryId, changes)
    }

    /** Puts every row of a [BulkResult] back exactly as it was — category, source and suggestion. */
    fun undo(result: BulkResult) {
        result.changes.forEach { c ->
            repo.setTransactionCategory(c.txnId, c.previousCategoryId, c.previousSource)
            if (c.previousSuggestedCategoryId != null) {
                repo.setSuggestedCategory(c.txnId, c.previousSuggestedCategoryId)
            } else {
                repo.clearSuggestion(c.txnId)
            }
        }
    }
}

/**
 * The categories a whole selection may be given: the **intersection** of what each row's amount sign
 * admits (`categoryAllowedForAmount`, the single source of truth in `:core:model`).
 *
 * A mixed-sign selection therefore offers only transfer categories, because those are the only ones
 * legitimate for a credit *and* a debit. Narrowing the picker is the honest move: the alternative —
 * offering an expense category and quietly skipping the credits — would report "12 transactions
 * categorized" while having changed nine, which is exactly how a bulk tool loses a user's trust.
 * The UI says why the list is short (see [isMixedSign]) rather than leaving it unexplained.
 */
internal fun admissibleCategories(amounts: List<Long>, categories: List<Category>): List<Category> =
    categories.filter { c -> amounts.all { categoryAllowedForAmount(it, c.kind) } }

/** Whether a selection holds both incoming and outgoing money — what shrinks the picker above. */
internal fun isMixedSign(amounts: List<Long>): Boolean =
    amounts.any { it > 0 } && amounts.any { it < 0 }
