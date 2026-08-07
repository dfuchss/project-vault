package org.fuchss.projectvault.app

import org.fuchss.projectvault.classification.CategoryRule
import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.classification.EmbeddingClassifier
import org.fuchss.projectvault.classification.NoopEmbedder
import org.fuchss.projectvault.classification.RuleEngine
import org.fuchss.projectvault.classification.RuleSource
import org.fuchss.projectvault.classification.SeedCatalog
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType

/** How a transaction's committed category was set — recorded so manual choices stay sticky. */
object CategorySource {
    const val MANUAL = "MANUAL"
    const val USER_RULE = "USER_RULE"
    const val SEED_RULE = "SEED_RULE"
}

/** Outcome of a classification pass: rules committed vs. embedding suggestions awaiting review. */
data class ClassifyResult(val committed: Int, val suggested: Int)

// Stable seed-category ids used for account-type defaults (see SeedCatalog).
private const val CAT_TRANSFERS = "cat-transfers"
private const val CAT_INCOME = "cat-income"

/**
 * Transaction categorization wired to the vault. Tier 1 = keyword rules (seed + learned USER rules);
 * Tier 2 = semantic embeddings for what the rules miss (only if an [Embedder] is available). Manual
 * corrections are sticky and are learned as USER rules that propagate. See docs/CLASSIFICATION.md.
 */
class Categorizer(
    private val repo: VaultRepository,
    private val embedder: Embedder = NoopEmbedder,
) {
    /**
     * Reconciles a vault's seed categories + rules to the current [SeedCatalog] — so catalog changes
     * (new categories, renamed categories, moved/added/removed keywords) reach existing vaults on next
     * open. Idempotent, and it never touches USER-learned rules or the user's own categories:
     *  - categories: insert missing; sync name/colour of existing **system** categories to the catalog;
     *  - SEED rules: insert missing (keyword,category) pairs, and prune SEED rules no longer in the
     *    catalog (e.g. a keyword that moved from "Einkommen" to "Gehalt").
     */
    fun ensureSeeded() {
        val byId = repo.categories().associateBy { it.id }
        SeedCatalog.categories.forEach { c ->
            val current = byId[c.id]
            when {
                current == null -> repo.insertCategory(c.id, c.name, c.kind, c.color, isSystem = true)
                current.isSystem == 1L && (current.name != c.name || current.color != c.color) ->
                    repo.updateCategoryMeta(c.id, c.name, c.color)
            }
        }

        val catalogPairs = SeedCatalog.rules.mapTo(HashSet()) { it.keyword.uppercase() to it.categoryId }
        val rules = repo.categoryRules()
        val existingPairs = rules.mapTo(HashSet()) { it.keyword.uppercase() to it.categoryId }
        SeedCatalog.rules.forEach {
            if ((it.keyword.uppercase() to it.categoryId) !in existingPairs) {
                repo.addRule(it.keyword, it.categoryId, it.priority, it.source.name)
            }
        }
        rules.filter { it.source == RuleSource.SEED.name && (it.keyword.uppercase() to it.categoryId) !in catalogPairs }
            .forEach { repo.deleteRuleById(it.id) }
    }

    private fun ruleEngine(): RuleEngine = RuleEngine(
        repo.categoryRules().map { CategoryRule(it.keyword, it.categoryId, it.priority.toInt(), RuleSource.valueOf(it.source)) },
    )

    /**
     * Tier 1 rules **commit** categories (they're reliable); Tier 2 embeddings only **suggest** them
     * (reviewable, so a wrong guess never silently commits or counts). Never touches transactions that
     * already have a committed category. Returns how many were committed vs. suggested.
     */
    fun classifyAccount(accountId: String): ClassifyResult {
        // Savings/deposit accounts: flows are internal movements, so default to a transfer, with
        // interest (Zinsen) as income. Reliable by account type, so it commits.
        val type = repo.account(accountId)?.type
        if (type == AccountType.TAGESGELD) {
            var committed = 0
            repo.transactions(accountId).filter { it.categoryId == null }.forEach { txn ->
                val categoryId = if (textOf(txn).uppercase().contains("ZINS")) CAT_INCOME else CAT_TRANSFERS
                repo.setTransactionCategory(txn.id, categoryId, CategorySource.SEED_RULE)
                committed++
            }
            return ClassifyResult(committed, 0)
        }

        val engine = ruleEngine()
        var committed = 0
        repo.transactions(accountId).filter { it.categoryId == null }.forEach { txn ->
            engine.bestRule(textOf(txn))?.let { rule ->
                val source = if (rule.source == RuleSource.USER) CategorySource.USER_RULE else CategorySource.SEED_RULE
                repo.setTransactionCategory(txn.id, rule.categoryId, source)
                committed++
            }
        }
        val suggested = if (embedder.available()) suggestByEmbeddings(accountId) else 0
        return ClassifyResult(committed, suggested)
    }

    /** Sets an embedding **suggestion** on uncategorized transactions without one. Returns the count. */
    private fun suggestByEmbeddings(accountId: String): Int {
        val needsSuggestion = repo.transactions(accountId)
            .filter { it.categoryId == null && it.suggestedCategoryId == null }
        if (needsSuggestion.isEmpty()) return 0

        val categories = repo.categories()
        val keywordsByCategory = repo.categoryRules().groupBy { it.categoryId }
        val examples = repo.transactions(accountId).filter { it.categoryId != null }

        // Zero-shot prototypes (category name + its keywords) + few-shot examples (committed categories).
        val prototypeTexts = categories.map { c ->
            (listOf(c.name) + keywordsByCategory[c.id].orEmpty().map { it.keyword }).joinToString(" ")
        }
        val exampleTexts = examples.map { textOf(it) }
        val prototypeVectors = if (prototypeTexts.isNotEmpty()) embedder.embed(prototypeTexts) else emptyList()
        val exampleVectors = if (exampleTexts.isNotEmpty()) embedder.embed(exampleTexts) else emptyList()

        val labeled = categories.mapIndexed { i, c -> EmbeddingClassifier.Labeled(c.id, prototypeVectors[i]) } +
            examples.mapIndexed { i, t -> EmbeddingClassifier.Labeled(t.categoryId!!, exampleVectors[i]) }
        val classifier = EmbeddingClassifier(labeled)

        val queries = embedder.embed(needsSuggestion.map { textOf(it) })
        var suggested = 0
        needsSuggestion.forEachIndexed { i, txn ->
            classifier.classify(queries[i])?.let {
                repo.setSuggestedCategory(txn.id, it.categoryId)
                suggested++
            }
        }
        return suggested
    }

    /** Accepts an embedding suggestion: commits it (learning + propagating like a manual set). */
    fun acceptSuggestion(accountId: String, txn: Txn, categoryId: String) {
        setCategory(accountId, txn, categoryId)
        repo.clearSuggestion(txn.id)
    }

    /** Dismisses a suggestion, leaving the transaction uncategorized. */
    fun dismissSuggestion(txn: Txn) = repo.clearSuggestion(txn.id)

    /**
     * Applies a manual (re)classification and learns from it: marks this transaction MANUAL, records
     * a USER rule from its counterparty (so future imports auto-apply it), and re-applies it to other
     * transactions of the same merchant — but never overwrites another transaction the user set
     * MANUALLY, so competing manual choices are respected.
     */
    fun setCategory(accountId: String, txn: Txn, categoryId: String) {
        repo.setTransactionCategory(txn.id, categoryId, CategorySource.MANUAL)
        repo.clearSuggestion(txn.id)
        val counterparty = txn.counterparty ?: return
        val keyword = RuleEngine.keywordFor(counterparty)
        if (keyword.length < 3) return

        repo.deleteUserRuleByKeyword(keyword)
        repo.addRule(keyword, categoryId, priority = 100, source = RuleSource.USER.name)

        repo.transactions(accountId)
            .filter { it.id != txn.id && it.categorySource != CategorySource.MANUAL }
            .forEach { other ->
                if (textOf(other).uppercase().contains(keyword)) {
                    repo.setTransactionCategory(other.id, categoryId, CategorySource.USER_RULE)
                    repo.clearSuggestion(other.id)
                }
            }
    }

    /** Categorizes only this transaction — no rule learned, nothing else touched. */
    fun applyToOne(txn: Txn, categoryId: String) {
        repo.setTransactionCategory(txn.id, categoryId, CategorySource.MANUAL)
        repo.clearSuggestion(txn.id)
    }

    /**
     * How many OTHER existing transactions [setCategory] would reclassify (same merchant, not
     * manually set, currently a different category). The UI uses this to ask before a bulk change.
     */
    fun otherMatchesCount(accountId: String, txn: Txn, categoryId: String): Int {
        val counterparty = txn.counterparty ?: return 0
        val keyword = RuleEngine.keywordFor(counterparty)
        if (keyword.length < 3) return 0
        return repo.transactions(accountId).count { other ->
            other.id != txn.id &&
                other.categorySource != CategorySource.MANUAL &&
                other.categoryId != categoryId &&
                textOf(other).uppercase().contains(keyword)
        }
    }

    private fun textOf(txn: Txn): String = listOfNotNull(txn.counterparty, txn.purpose).joinToString(" ")
}
