package org.fuchss.projectvault.app

import org.fuchss.projectvault.classification.CategoryRule
import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.classification.EmbeddingClassifier
import org.fuchss.projectvault.classification.NoopEmbedder
import org.fuchss.projectvault.classification.RuleEngine
import org.fuchss.projectvault.classification.RuleSource
import org.fuchss.projectvault.classification.SeedCatalog
import org.fuchss.projectvault.classification.StatisticalClassifier
import org.fuchss.projectvault.classification.TextNormalizer
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.CategoryRule as RuleRow
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import org.fuchss.projectvault.model.categoryAllowedForAmount
import kotlin.math.exp
import kotlin.math.max

/** How a transaction's committed category was set — recorded so manual choices stay sticky. */
object CategorySource {
    const val MANUAL = "MANUAL"
    const val USER_RULE = "USER_RULE"
    const val SEED_RULE = "SEED_RULE"
}

/** Outcome of a classification pass: rules committed vs. embedding suggestions awaiting review. */
data class ClassifyResult(val committed: Int, val suggested: Int)

/**
 * How far a manual re-run of the classifier reaches. Neither scope ever touches a transaction the
 * user set [CategorySource.MANUAL] — that promise is the whole point of the source column.
 */
enum class ReclassifyScope {
    /** Today's behaviour: only transactions that have no committed category yet. */
    UNCATEGORIZED,

    /** Drop every *automatic* category first, so edited rules reach already-classified transactions. */
    ALL_EXCEPT_MANUAL,
}

/** Outcome of a re-run: how many automatic categories were dropped, then re-committed / proposed. */
data class ReclassifyResult(val cleared: Int, val committed: Int, val suggested: Int)

// Stable seed-category ids used for account-type defaults (see SeedCatalog).
internal const val CAT_TRANSFERS = "cat-transfers"

/** The income-side misc bucket (Weitere Einkünfte) — the fallback for credits, as CAT_OTHER is for debits. */
internal const val CAT_INCOME = "cat-income"

/** The salary seed category — used to base the "expected income" estimate on paychecks only. */
internal const val CAT_SALARY = "cat-salary"

/** The protected expense fallback (Sonstiges). It can never be disabled and is the reassign target. */
internal const val CAT_OTHER = "cat-other"

/**
 * Transaction categorization wired to the vault. Tier 1 = keyword rules (seed + learned USER rules);
 * Tier 2 = two complementary suggesters for what the rules miss — an always-on statistical classifier
 * trained from your labeled transactions, plus semantic embeddings when an [Embedder] is provisioned.
 * Manual corrections are sticky and are learned as USER rules that propagate. See docs/CLASSIFICATION.md.
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
        // Built-in keywords the user removed (or took over) in the rule editor. Re-installing them
        // would silently undo that deletion on every open, so they're skipped — and a suppression
        // whose pair has since left the catalog is stale debris, so it's dropped here too.
        val suppressed = repo.suppressedRules()
        suppressed.filterNot { it in catalogPairs }.forEach { repo.unsuppressRule(it.first, it.second) }

        val rules = repo.categoryRules()
        val existingPairs = rules.mapTo(HashSet()) { it.keyword.uppercase() to it.categoryId }
        SeedCatalog.rules.forEach {
            val pair = it.keyword.uppercase() to it.categoryId
            if (pair !in existingPairs && pair !in suppressed) {
                repo.addRule(it.keyword, it.categoryId, it.priority, it.source.name)
            }
        }
        rules.filter { it.source == RuleSource.SEED.name && (it.keyword.uppercase() to it.categoryId) !in catalogPairs }
            .forEach { repo.deleteRuleById(it.id) }
    }

    /** Ids of categories currently enabled — disabled ones are excluded from all classifier output. */
    private fun enabledCategoryIds(): Set<String> =
        repo.categories().filterTo(HashSet()) { it.enabled == 1L }.mapTo(HashSet()) { it.id }

    private fun ruleEngine(): RuleEngine {
        // Rules that point at a disabled category are ignored, so a disabled category is never
        // auto-committed. The rules stay in the DB, so re-enabling restores the behaviour.
        val enabled = enabledCategoryIds()
        return RuleEngine(
            repo.categoryRules()
                .filter { it.categoryId in enabled }
                .map { CategoryRule(it.keyword, it.categoryId, it.priority.toInt(), RuleSource.valueOf(it.source)) },
        )
    }

    /**
     * Tier 1 rules **commit** categories (they're reliable); Tier 2 only **suggests** them (reviewable,
     * so a wrong guess never silently commits or counts). Never touches transactions that already have
     * a committed category. Returns how many were committed vs. suggested.
     */
    fun classifyAccount(accountId: String): ClassifyResult {
        val committed = commitRules(accountId)
        val suggested = suggest(accountId)
        return ClassifyResult(committed, suggested)
    }

    /**
     * Tier 1 alone: commits a category on every uncategorized transaction a rule (or an account-type
     * default) fires on, and returns how many. Split out from [classifyAccount] because it is the
     * half that writes the user's data, so [reclassify] can run it — and only it — inside a
     * transaction, leaving the throw-prone, commit-nothing Tier-2 pass outside.
     */
    private fun commitRules(accountId: String): Int {
        // Savings/deposit accounts: flows are internal movements, so default to a transfer, with
        // interest (Zinsen) as income. Reliable by account type, so it commits.
        val type = repo.account(accountId)?.type
        if (type == AccountType.TAGESGELD) {
            var committed = 0
            repo.transactions(accountId).filter { it.categoryId == null }.forEach { txn ->
                val categoryId = accountTypeDefault(type, textOf(txn), txn.amountCents) ?: return@forEach
                repo.setTransactionCategory(txn.id, categoryId, CategorySource.SEED_RULE)
                committed++
            }
            return committed
        }

        val engine = ruleEngine()
        val kinds = categoryKinds()
        var committed = 0
        repo.transactions(accountId).filter { it.categoryId == null }.forEach { txn ->
            engine.bestRule(textOf(txn), admissibleFor(txn, kinds))?.let { rule ->
                val source = if (rule.source == RuleSource.USER) CategorySource.USER_RULE else CategorySource.SEED_RULE
                repo.setTransactionCategory(txn.id, rule.categoryId, source)
                committed++
            }
        }
        return committed
    }

    /**
     * Sets a Tier-2 **suggestion** on uncategorized transactions that have none, from two complementary
     * models: an always-on statistical classifier trained from your labeled transactions, plus the
     * semantic embedder when a model is provisioned. Neither commits — suggestions are reviewed in the
     * inspector. Returns how many were suggested.
     *
     * Which of the two runs, and how confident each has to be, comes from the vault's
     * [ClassifierSettings] (the Classification screen's Models tab); untouched vaults get the
     * classifiers' own defaults.
     */
    private fun suggest(accountId: String): Int {
        val needsSuggestion = repo.transactions(accountId)
            .filter { it.categoryId == null && it.suggestedCategoryId == null }
        if (needsSuggestion.isEmpty()) return 0
        val settings = ClassifierSettings.load(repo)
        if (!settings.statisticalEnabled && !settings.embeddingEnabled) return 0

        // Only enabled categories can be suggested — disabled ones are dropped from every model's inputs.
        // Sonstiges is dropped too, and on purpose: it carries no keywords, so its only prototype is
        // the embedding of the word "Sonstiges" — which sits nearest to exactly the texts that say
        // nothing (a private person's name, a bare "Zahlungseingang"), making it the default answer
        // whenever the model has no idea. "I don't know" is better expressed by proposing nothing,
        // which leaves the transaction in the uncategorized filter where it can be dealt with.
        val enabled = enabledCategoryIds()
        val suggestable = { id: String -> id in enabled && id != CAT_OTHER }
        val kinds = categoryKinds()
        val categories = repo.categories().filter { suggestable(it.id) }
        val keywordsByCategory = repo.categoryRules().filter { suggestable(it.categoryId) }.groupBy { it.categoryId }
        val examples = repo.transactions(accountId).filter { it.categoryId != null && suggestable(it.categoryId!!) }

        val statistical = if (settings.statisticalEnabled) {
            buildStatisticalClassifier(categories, keywordsByCategory, examples, settings.statisticalThreshold)
        } else {
            null
        }
        val embeddingClassifier = if (settings.embeddingEnabled && embedder.available()) {
            buildEmbeddingClassifier(categories, keywordsByCategory, examples, settings.embeddingMargin.toFloat())
        } else {
            null
        }
        val queryVectors = if (embeddingClassifier != null) embedder.embed(needsSuggestion.map { textOf(it) }) else emptyList()

        var suggested = 0
        needsSuggestion.forEachIndexed { i, txn ->
            val admissible = admissibleFor(txn, kinds)
            // Each score is normalized against its own bar before the merge — see mergeSuggestion.
            val stat = statistical?.classify(textOf(txn), admissible)
                ?.let { it.categoryId to relativeStrength(it.confidence.toFloat(), settings.statisticalThreshold.toFloat()) }
            val emb = embeddingClassifier?.classify(queryVectors[i], admissible)
                ?.let { it.categoryId to relativeStrength(it.margin, settings.embeddingMargin.toFloat()) }
            mergeSuggestion(stat, emb)?.let {
                repo.setSuggestedCategory(txn.id, it)
                suggested++
            }
        }
        return suggested
    }

    /**
     * Complement policy (tuned by ClassifierComparisonTest): if both models produce a proposal and
     * **agree**, use it (agreement is strong); if they disagree, take the stronger one; if only one
     * produced a proposal, use it. Embeddings are the higher-coverage suggester when the model is
     * loaded; the statistical classifier is the always-on fallback and a high-precision cross-check.
     *
     * "Stronger" cannot be a comparison of the two raw scores: a Naive-Bayes pairwise softmax lives in
     * [0.5, 1] while an embedding margin lives in [0, ~0.15], so whoever compared them directly would
     * hand every disagreement to the statistical model by arithmetic rather than by evidence. Both are
     * therefore passed through [relativeStrength] first — how far each cleared **its own** configured
     * threshold, as a fraction of that threshold — which is a genuinely scale-free comparison. (On the harness fixture the two models never
     * actually disagree, so this changes no measured number; it keeps the tie-break meaningful now
     * that the embedding score is a margin rather than a cosine.)
     */
    internal fun mergeSuggestion(stat: Pair<String, Float>?, emb: Pair<String, Float>?): String? = when {
        stat != null && emb != null -> if (stat.first == emb.first || stat.second >= emb.second) stat.first else emb.first
        else -> (stat ?: emb)?.first
    }

    /**
     * A score's lead over the bar it had to clear, as a **fraction of that bar**, squashed to (0, 1)
     * so two different scales compare.
     *
     * The division is the whole point. Squashing the raw difference looks scale-free but is not: the
     * statistical model can clear its 0.65 bar by 0.35 while an embedding margin can clear its 0.03
     * bar by at most ~0.12, so the model with the wider range wins structurally — a statistical
     * confidence above 0.77 would beat *any* embedding proposal, however decisive. Dividing by the
     * threshold makes the number dimensionless: both models read 0.5 exactly at their own bar, and
     * "twice as far past the bar as it needed" means the same thing on either scale.
     */
    internal fun relativeStrength(score: Float, threshold: Float): Float {
        // A bar of zero accepts everything, so "how far past it" carries no information; the epsilon
        // just keeps the ratio finite rather than pretending the answer is meaningful.
        val bar = max(threshold, 1e-6f)
        return 1f / (1f + exp(-((score - bar) / bar).toDouble()).toFloat())
    }

    /**
     * The category an **account type** commits outright, before any rule is consulted — or null when
     * rules decide normally.
     *
     * Tagesgeld/Festgeld flows are movements between the user's own accounts, so they default to a
     * transfer; interest is income, but only when it is actually credited, since the same statement
     * line also appears negative as withheld Kapitalertragsteuer and that is not income.
     *
     * This lives in one place because the Explain panel has to answer for these rows too. Reading the
     * rule engine for a Tagesgeld row would name a rule and a category that were never applied — an
     * explanation contradicting the decision it claims to describe, which is worse than no
     * explanation at all.
     */
    internal fun accountTypeDefault(type: AccountType?, text: String, amountCents: Long): String? = when {
        type != AccountType.TAGESGELD -> null
        amountCents > 0 && TextNormalizer.fold(text).contains("ZINS") -> CAT_INCOME
        else -> CAT_TRANSFERS
    }

    /** Every category's kind, for the amount-sign constraint below. */
    private fun categoryKinds(): Map<String, CategoryKind> = repo.categories().associate { it.id to it.kind }

    /**
     * The categories [txn] may legitimately be given, by the sign of its amount: money coming in can
     * only be income or a movement between your own accounts, money going out only an expense or such
     * a movement. Filing a credit under an expense category is not a bad guess but a category error,
     * so this constrains the *candidates* of every automatic tier — rules and both suggesters — the
     * same way [allowedKindsForAmount] already constrains the manual picker.
     *
     * Constraining candidates rather than vetoing the winner matters: a rejected front-runner falls
     * through to the next admissible match instead of leaving the transaction unclassified, which is
     * what makes an incoming "AMZN … Rückerstattung" resolve to the refund rule rather than to the
     * Amazon shopping rule that happens to appear earlier in the text.
     *
     * A category the vault no longer knows is left alone rather than rejected — being unable to look
     * up a kind is not evidence of a mismatch.
     */
    private fun admissibleFor(txn: Txn, kinds: Map<String, CategoryKind>): (String) -> Boolean =
        { categoryId -> kinds[categoryId]?.let { categoryAllowedForAmount(txn.amountCents, it) } ?: true }

    /** Statistical model input: seed keywords + category names as cold-start docs, plus your labels. */
    private fun buildStatisticalClassifier(
        categories: List<org.fuchss.projectvault.data.db.Category>,
        keywordsByCategory: Map<String, List<RuleRow>>,
        examples: List<Txn>,
        minConfidence: Double,
    ): StatisticalClassifier {
        val seedDocs = categories.flatMap { c ->
            (listOf(c.name) + keywordsByCategory[c.id].orEmpty().map { it.keyword })
                .map { StatisticalClassifier.Example(it, c.id) }
        }
        val labeled = examples.map { StatisticalClassifier.Example(textOf(it), it.categoryId!!) }
        return StatisticalClassifier(seedDocs + labeled, minConfidence = minConfidence)
    }

    /** Embedding model input: zero-shot prototypes (name + keywords) + few-shot committed examples. */
    private fun buildEmbeddingClassifier(
        categories: List<org.fuchss.projectvault.data.db.Category>,
        keywordsByCategory: Map<String, List<RuleRow>>,
        examples: List<Txn>,
        minMargin: Float,
    ): EmbeddingClassifier {
        val prototypeTexts = categories.map { c ->
            (listOf(c.name) + keywordsByCategory[c.id].orEmpty().map { it.keyword }).joinToString(" ")
        }
        val exampleTexts = examples.map { textOf(it) }
        val prototypeVectors = if (prototypeTexts.isNotEmpty()) embedder.embed(prototypeTexts) else emptyList()
        val exampleVectors = if (exampleTexts.isNotEmpty()) embedder.embed(exampleTexts) else emptyList()
        val labeled = categories.mapIndexed { i, c -> EmbeddingClassifier.Labeled(c.id, prototypeVectors[i]) } +
            examples.mapIndexed { i, t -> EmbeddingClassifier.Labeled(t.categoryId!!, exampleVectors[i]) }
        return EmbeddingClassifier(labeled, minMargin = minMargin)
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
     * a USER rule from its text (so future imports auto-apply it), and re-applies it to every other
     * transaction that keyword matches — but never overwrites another transaction the user set
     * MANUALLY, so competing manual choices are respected.
     *
     * The keyword is derived by [learnedKeywordFor] unless the caller passes one: the confirmation
     * dialog lets the user edit it, because what a correction actually means ("everything from this
     * shop" vs. "anything mentioning Versicherung") is a judgement the app cannot make for them.
     */
    fun setCategory(accountId: String, txn: Txn, categoryId: String, keyword: String? = null) {
        repo.setTransactionCategory(txn.id, categoryId, CategorySource.MANUAL)
        repo.clearSuggestion(txn.id)
        // An explicit [keyword] is the user's own choice from the confirmation dialog and wins over the
        // derived one. It is folded like a derived keyword, so "replace the USER rule for this keyword"
        // keeps matching however it was typed.
        val learned = keyword?.trim()?.takeIf { it.length >= 2 }?.let(TextNormalizer::fold)
            ?: learnedKeywordFor(txn)
            ?: return

        repo.deleteUserRuleByKeyword(learned)
        repo.addRule(learned, categoryId, priority = 100, source = RuleSource.USER.name)

        val kinds = categoryKinds()
        repo.transactions(accountId)
            .filter { it.id != txn.id && it.categorySource != CategorySource.MANUAL }
            .forEach { other ->
                // A merchant you both pay and get money back from appears on both sides of the
                // ledger, so the correction only spreads to transactions whose sign the category
                // actually fits: teaching "Amazon → Shopping" from a purchase must not relabel the
                // refunds.
                if (matchesKeyword(other, learned) && admissibleFor(other, kinds)(categoryId)) {
                    repo.setTransactionCategory(other.id, categoryId, CategorySource.USER_RULE)
                    repo.clearSuggestion(other.id)
                }
            }
    }

    /**
     * Creates a new category and, for any [keywords], learns USER rules (priority 100, so they take
     * effect over seed rules) that auto-classify matching transactions into it on the next pass.
     */
    fun addCategory(name: String, kind: CategoryKind, color: String, keywords: List<String> = emptyList()): String {
        val id = repo.addCategory(name, kind, color)
        keywords.forEach { repo.addRule(it, id, priority = 100, source = RuleSource.USER.name) }
        return id
    }

    /** The current keyword rules for a category (used to pre-fill the edit dialog). */
    fun keywordsFor(categoryId: String): List<String> =
        repo.categoryRules().filter { it.categoryId == categoryId }.map { it.keyword }

    /**
     * Updates a user category's name/colour and **replaces** its keyword rules with [keywords]. Only
     * safe for user categories (all their rules are USER rules); system categories are synced from the
     * catalog by [ensureSeeded], so they aren't edited here.
     */
    fun updateCategory(id: String, name: String, color: String, keywords: List<String>) {
        repo.updateCategoryMeta(id, name, color)
        repo.deleteRulesByCategory(id)
        keywords.forEach { repo.addRule(it, id, priority = 100, source = RuleSource.USER.name) }
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
        val keyword = learnedKeywordFor(txn) ?: return 0
        val kinds = categoryKinds()
        return repo.transactions(accountId).count { other ->
            other.id != txn.id &&
                other.categorySource != CategorySource.MANUAL &&
                other.categoryId != categoryId &&
                matchesKeyword(other, keyword) &&
                admissibleFor(other, kinds)(categoryId)
        }
    }

    /**
     * Builds "how many OTHER transactions would this keyword re-categorize?" as a reusable function —
     * the live number beside the editable keyword in the confirmation dialog. The account's
     * transactions are read **once**, when the counter is built, so retyping the keyword costs no
     * further queries. Matching and the sign constraint are the engine's, so the number is exactly
     * what the learned rule would do.
     */
    fun keywordMatchCounter(accountId: String, txn: Txn, categoryId: String): (String) -> Int {
        val kinds = categoryKinds()
        val candidates = repo.transactions(accountId).filter {
            it.id != txn.id &&
                it.categorySource != CategorySource.MANUAL &&
                it.categoryId != categoryId &&
                admissibleFor(it, kinds)(categoryId)
        }
        return { keyword ->
            if (keyword.isBlank()) {
                0
            } else {
                val engine = keywordEngine(keyword)
                candidates.count { engine.bestRule(textOf(it)) != null }
            }
        }
    }

    /**
     * The keyword a correction would teach — surfaced so the UI can *name* it before offering a bulk
     * apply, rather than calling it "this merchant" and being wrong whenever it isn't one.
     *
     * Normally derived from the counterparty — but a statement line
     * often names the merchant **only in the purpose** (Lastschrift/Dauerauftrag rows, and card/CSV
     * exports that leave the payee blank), and those corrections used to teach nothing at all. So when
     * the counterparty yields no usable token, the purpose is read instead.
     *
     * The purpose is the noisier of the two, so its token must contain a letter: a booking reference
     * or card number identifies one transaction, never a merchant, and would make a rule that can only
     * ever match once. Returns null when neither field offers anything worth learning.
     */
    fun learnedKeywordFor(txn: Txn): String? {
        val fromCounterparty = txn.counterparty?.let(RuleEngine::keywordFor)
        if (fromCounterparty != null && fromCounterparty.length >= 3) return fromCounterparty
        return TextNormalizer.fold(txn.purpose)
            .split(Regex("[^A-Za-z0-9]+"))
            .firstOrNull { it.length >= 3 && it.any(Char::isLetter) }
    }

    /**
     * Does a learned keyword occur in this transaction? Asked of a one-rule [RuleEngine] rather than
     * by a plain `contains`, so propagating a correction matches exactly what the rule it just learned
     * will match on the next pass — same diacritic folding, same word boundaries, forever in step.
     */
    private fun matchesKeyword(txn: Txn, keyword: String): Boolean =
        keywordEngine(keyword).bestRule(textOf(txn)) != null

    // ------------------------------------------------------------ Rule editor (Classification screen)

    /** Adds a rule by hand. Always a USER rule — the SEED set is owned by [SeedCatalog]. */
    fun addRule(keyword: String, categoryId: String, priority: Int = 100) {
        repo.addRule(keyword.trim(), categoryId, priority, RuleSource.USER.name)
    }

    /**
     * Rewrites a rule. Editing a built-in rule **takes it over**: the original catalog pair is
     * suppressed (so [ensureSeeded] doesn't re-install the keyword the user just changed) and the row
     * becomes a USER rule (so [ensureSeeded]'s prune doesn't delete it for having left the catalog).
     * Editing a USER rule is a plain update.
     */
    fun updateRule(rule: RuleRow, keyword: String, categoryId: String, priority: Int) {
        if (rule.source == RuleSource.SEED.name) repo.suppressRule(rule.keyword, rule.categoryId)
        repo.updateRule(rule.id, keyword.trim(), categoryId, priority, RuleSource.USER.name)
    }

    /**
     * Deletes a rule for good. A USER rule simply goes; a built-in one is **also** recorded as
     * suppressed, because seeding is a reconcile — without the record the keyword would be back the
     * next time the vault is opened.
     */
    fun deleteRule(rule: RuleRow) {
        if (rule.source == RuleSource.SEED.name) repo.suppressRule(rule.keyword, rule.categoryId)
        repo.deleteRuleById(rule.id)
    }

    /** The built-in keywords the user removed, newest catalog order — offered for restore in the UI. */
    fun suppressedSeedRules(): List<CategoryRule> {
        val suppressed = repo.suppressedRules()
        return SeedCatalog.rules.filter { (it.keyword.uppercase() to it.categoryId) in suppressed }
    }

    /** Brings a removed built-in keyword back: drop the suppression, then let the reconciler re-add it. */
    fun restoreSeedRule(rule: CategoryRule) {
        repo.unsuppressRule(rule.keyword, rule.categoryId)
        ensureSeeded()
    }

    /** The rule the engine would pick for [text] — the "test a sample" affordance's whole answer. */
    fun explain(text: String): CategoryRule? = ruleEngine().bestRule(text)

    // ------------------------------------------------------------ Explainability (Classification → Explain)

    /**
     * Assembles every model the "why was this classified that way?" panel reasons about, exactly as
     * [suggest] assembles them for a real pass — which is the expensive half of an explanation (one
     * embedding forward pass per category prototype **and** per labeled transaction). So it is built
     * **once**, off the UI thread, and reused for every query; explaining a text afterwards costs one
     * embedding of that text and nothing else. See [ExplainModels.explain].
     *
     * The only deliberate difference from a pass: the few-shot examples are the labeled transactions
     * of **every** account rather than of one, since the panel explains a row the user picked
     * anywhere in the vault. A single-account vault — the usual case — is therefore identical.
     */
    fun buildExplainModels(): ExplainModels {
        val settings = ClassifierSettings.load(repo)
        val enabled = enabledCategoryIds()
        val suggestable = { id: String -> id in enabled && id != CAT_OTHER }
        val categories = repo.categories().filter { suggestable(it.id) }
        val rules = repo.categoryRules()
        val keywordsByCategory = rules.filter { suggestable(it.categoryId) }.groupBy { it.categoryId }
        val examples = repo.accounts()
            .flatMap { repo.transactions(it.id) }
            .filter { it.categoryId != null && suggestable(it.categoryId!!) }
        // Asked once: on a cold start this is what loads the ONNX model, so it is not a cheap getter.
        val embedderAvailable = embedder.available()
        return ExplainModels(
            categorizer = this,
            // Unlike the classifying engine this one keeps the rules that point at a *disabled*
            // category, so the panel can report "this rule matched, but its category is switched off"
            // rather than silently omitting the rule the user is looking for.
            engine = RuleEngine(
                rules.map { CategoryRule(it.keyword, it.categoryId, it.priority.toInt(), RuleSource.valueOf(it.source)) },
            ),
            enabledCategoryIds = enabled,
            categoryKinds = categoryKinds(),
            settings = settings,
            statistical = if (settings.statisticalEnabled) {
                buildStatisticalClassifier(categories, keywordsByCategory, examples, settings.statisticalThreshold)
            } else {
                null
            },
            embedding = if (settings.embeddingEnabled && embedderAvailable) {
                buildEmbeddingClassifier(categories, keywordsByCategory, examples, settings.embeddingMargin.toFloat())
            } else {
                null
            },
            embedder = embedder,
            embedderAvailable = embedderAvailable,
            prototypeCount = categories.size,
            exampleCount = examples.size,
        )
    }

    // ------------------------------------------------------------ Re-classification

    /**
     * How many transactions a [reclassify] with this [scope] would look at — shown in the
     * confirmation so a sweeping re-run is never a surprise. DEPOT accounts have no transactions and
     * so contribute nothing.
     */
    fun reclassifyCandidateCount(scope: ReclassifyScope): Int =
        repo.accounts().sumOf { account ->
            repo.transactions(account.id).count { txn ->
                when (scope) {
                    ReclassifyScope.UNCATEGORIZED -> txn.categoryId == null
                    ReclassifyScope.ALL_EXCEPT_MANUAL -> txn.categorySource != CategorySource.MANUAL
                }
            }
        }

    /**
     * Runs the classifier again over every account. [ReclassifyScope.UNCATEGORIZED] is exactly what
     * an import does. [ReclassifyScope.ALL_EXCEPT_MANUAL] first **drops** every automatically-set
     * category, so an improved or corrected rule finally reaches transactions a previous pass already
     * labelled — which the normal pass can't do, since it only ever fills in blanks.
     *
     * A transaction the user set [CategorySource.MANUAL] is never cleared and never re-labelled, in
     * either scope (docs/CLASSIFICATION.md). Slow enough to want a background thread.
     *
     * **Dropping and re-committing are one atomic step.** They have to be: on their own the drop is
     * destructive and irreversible — the categories it removes were, for some rows, put there by
     * rules that no longer exist, so nothing can put them back. A failure in between (the classifier
     * throwing, the process dying) would leave the vault stripped. The Tier-2 pass runs *after* the
     * transaction commits: it only ever writes reviewable suggestions, and it is the part that loads
     * a ~118 MB model, which has no business inside a write transaction.
     */
    fun reclassify(scope: ReclassifyScope): ReclassifyResult {
        val accounts = repo.accounts()
        val (cleared, committed) = repo.inTransaction {
            var cleared = 0
            var committed = 0
            accounts.forEach { account ->
                if (scope == ReclassifyScope.ALL_EXCEPT_MANUAL) {
                    repo.transactions(account.id)
                        .filter { it.categoryId != null && it.categorySource != CategorySource.MANUAL }
                        .forEach {
                            repo.setTransactionCategory(it.id, null, null)
                            repo.clearSuggestion(it.id)
                            cleared++
                        }
                }
                committed += commitRules(account.id)
            }
            cleared to committed
        }
        val suggested = accounts.sumOf { suggest(it.id) }
        return ReclassifyResult(cleared, committed, suggested)
    }

    /** Everything a classifier reads: the counterparty **and** the purpose, never one without the other. */
    private fun textOf(txn: Txn): String = listOfNotNull(txn.counterparty, txn.purpose).joinToString(" ")
}

/** A [RuleEngine] holding this keyword alone — the cheapest way to ask "would this keyword match?". */
private fun keywordEngine(keyword: String): RuleEngine =
    RuleEngine(listOf(CategoryRule(keyword, categoryId = "", priority = 0, source = RuleSource.USER)))

/**
 * How many of [texts] each rule's keyword occurs in, keyed by rule id — the "this rule currently
 * matches N transactions" column of the rule editor.
 *
 * Each count is asked of the engine itself (one rule at a time) rather than by a substring test, so
 * the column can never drift from the matching the engine actually does — it folds diacritics and
 * requires word boundaries, and a hand-rolled `contains` here would quietly over-count. Note it
 * counts **occurrences**, not wins: a lower-priority rule can match a transaction that another rule
 * ultimately takes. Callers pass the already-loaded transaction texts, so the whole table costs one
 * read of the vault rather than one query per rule.
 */
internal fun ruleMatchCounts(rules: List<RuleRow>, texts: List<String>): Map<String, Int> =
    rules.associate { rule ->
        val engine = keywordEngine(rule.keyword)
        rule.id to texts.count { engine.bestRule(it) != null }
    }
