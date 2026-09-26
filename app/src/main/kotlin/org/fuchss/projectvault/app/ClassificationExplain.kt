package org.fuchss.projectvault.app

import org.fuchss.projectvault.classification.CategoryRule
import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.classification.EmbeddingClassifier
import org.fuchss.projectvault.classification.RuleEngine
import org.fuchss.projectvault.classification.RuleSource
import org.fuchss.projectvault.classification.StatisticalClassifier
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import org.fuchss.projectvault.model.allowedKindsForAmount
import org.fuchss.projectvault.model.categoryAllowedForAmount

/**
 * The whole decision path for one input — what the Classification screen's **Explain** tab renders,
 * step by step in the order [Categorizer] actually runs it: the amount-sign constraint, Tier 1, the
 * two Tier-2 suggesters, their merge, and what would come of it.
 *
 * It is a *current* answer, not a record: the models are built from the vault as it stands now, so a
 * transaction classified before you edited a rule or corrected a neighbouring row may well explain
 * differently than it was decided. That is the useful reading ("what would happen to this row today")
 * and the only one that can be shown honestly, since nothing about the original pass is stored.
 */
data class ClassificationExplanation(
    val text: String,
    val amountCents: Long,
    /** The kinds the sign admits — the constraint every tier below is filtered by. */
    val allowedKinds: List<CategoryKind>,
    /**
     * Set when the account's **type** decides before anything below runs (Tagesgeld). The rule and
     * model sections are still computed — it is useful to see what they *would* have said — but they
     * did not apply, and the outcome says so.
     */
    val accountTypeDefault: AccountTypeDefault?,
    /** Every rule whose keyword occurs in [text], ranked as the engine ranks them. */
    val rules: List<RuleCandidate>,
    val statistical: StatisticalTier,
    val embedding: EmbeddingTier,
    val merge: MergeStep,
    val outcome: ClassificationOutcome,
) {
    /** The rule that committed, if any — Tier 2 never runs for a row Tier 1 already decided. */
    val winningRule: RuleCandidate? get() = rules.firstOrNull { it.verdict == RuleVerdict.WINNER }
}

/** An account type that commits a category without consulting a rule — see [Categorizer.accountTypeDefault]. */
data class AccountTypeDefault(val accountType: AccountType, val categoryId: String)

/** How one matching rule fared. Everything but [WINNER] is a rule that matched and did **not** apply. */
enum class RuleVerdict {
    /** Highest priority, then earliest position, then longest keyword — among the admissible ones. */
    WINNER,

    /** Admissible, but another rule out-ranked it. */
    OUTRANKED,

    /** Its category's kind is impossible for this amount's sign (an expense on a credit, say). */
    SIGN_REJECTED,

    /** Its category is switched off, so no tier may output it. */
    CATEGORY_DISABLED,
}

/** A rule that matched, where in the (diacritics-folded) text it matched, and why it won or didn't. */
data class RuleCandidate(val rule: CategoryRule, val index: Int, val verdict: RuleVerdict)

/** Whether a Tier-2 suggester ran at all — and when it didn't, which of the three reasons it was. */
enum class TierState {
    RAN,

    /** Switched off in the Models tab. */
    SWITCHED_OFF,

    /** No embedding model is provisioned in this build (the common case on a dev machine). */
    MODEL_MISSING,

    /** Nothing to train on: no enabled categories, so the model has no classes. */
    UNTRAINED,
}

/** Tier 2, statistical: the TF-IDF token evidence and per-category log-scores behind its proposal. */
data class StatisticalTier(
    val state: TierState,
    /** `StatisticalClassifier.minConfidence` the decision had to clear. */
    val threshold: Double,
    val explanation: StatisticalClassifier.Explanation?,
)

/** Tier 2, embeddings: the per-category cosines, the winner's lead, and the bar it had to clear. */
data class EmbeddingTier(
    val state: TierState,
    /** `EmbeddingClassifier.minMargin` the lead had to clear. */
    val minMargin: Double,
    val explanation: EmbeddingClassifier.Explanation?,
)

/** Which suggester the merge took, and on what grounds. See `Categorizer.mergeSuggestion`. */
enum class MergeVerdict { NOTHING, ONLY_STATISTICAL, ONLY_EMBEDDING, AGREED, STATISTICAL_STRONGER, EMBEDDING_STRONGER }

/**
 * The merge of the two Tier-2 proposals. Each side is its proposed category paired with its
 * **relative strength** — how far it cleared its own threshold — because the two raw scores live on
 * incomparable scales (a pairwise softmax in [0.5, 1] vs. a cosine margin in [0, ~0.15]).
 */
data class MergeStep(
    val statistical: Pair<String, Float>?,
    val embedding: Pair<String, Float>?,
    val verdict: MergeVerdict,
    val categoryId: String?,
)

/** What the classifier does with this input in the end. */
enum class OutcomeKind {
    /** The account type decided outright — no rule and no model was consulted. */
    ACCOUNT_DEFAULT,

    /** A rule matched: the category is committed (Tier 2 is not consulted at all). */
    COMMITTED,

    /** Tier 2 proposed one: stored as a suggestion to accept or dismiss, never committed. */
    SUGGESTED,

    /** Nothing fired — the transaction stays uncategorized. */
    NOTHING,
}

data class ClassificationOutcome(
    val kind: OutcomeKind,
    val categoryId: String?,
    /** The `txn.categorySource` a commit would record (USER_RULE / SEED_RULE), else null. */
    val source: String?,
)

/**
 * Every model an explanation needs, built once by [Categorizer.buildExplainModels] and reused across
 * queries — see its KDoc for why that split exists (building embeds the whole taxonomy; explaining
 * embeds one string).
 *
 * [explain] is pure with respect to the vault: it reads nothing and writes nothing, so the panel can
 * ask it about any text, sign and transaction without disturbing a stored category or suggestion.
 * It does run an embedding forward pass, though, so callers keep it off the UI thread.
 */
class ExplainModels internal constructor(
    private val categorizer: Categorizer,
    private val engine: RuleEngine,
    private val enabledCategoryIds: Set<String>,
    private val categoryKinds: Map<String, CategoryKind>,
    private val settings: ClassifierSettings,
    private val statistical: StatisticalClassifier?,
    private val embedding: EmbeddingClassifier?,
    private val embedder: Embedder,
    private val embedderAvailable: Boolean,
    /** How many category prototypes the Tier-2 models were built from. */
    val prototypeCount: Int,
    /** How many labeled transactions were available as few-shot examples / training documents. */
    val exampleCount: Int,
) {
    @JvmOverloads
    fun explain(text: String, amountCents: Long, accountType: AccountType? = null): ClassificationExplanation {
        // Asked first, because when it answers nothing below it ran for this row.
        val accountDefault = categorizer.accountTypeDefault(accountType, text, amountCents)
            ?.let { AccountTypeDefault(accountType!!, it) }
        // The sign constraint, applied exactly as Categorizer.admissibleFor applies it: a category
        // whose kind the vault no longer knows is left alone rather than rejected.
        val admissible: (String) -> Boolean = { id ->
            categoryKinds[id]?.let { categoryAllowedForAmount(amountCents, it) } ?: true
        }
        // What a rule may commit: admissible *and* not switched off (Categorizer.ruleEngine drops
        // rules of disabled categories before the engine ever sees them).
        val eligible: (String) -> Boolean = { id -> id in enabledCategoryIds && admissible(id) }

        // The winner is the first accepted candidate of the engine's own ranking — the same statement
        // as `bestRule`, so the two cannot disagree. Everything above it matched and lost, for one of
        // the three reasons below.
        var winnerFound = false
        val rules = engine.explain(text, eligible).map { candidate ->
            val verdict = when {
                candidate.accepted && !winnerFound -> RuleVerdict.WINNER.also { winnerFound = true }
                candidate.accepted -> RuleVerdict.OUTRANKED
                candidate.rule.categoryId !in enabledCategoryIds -> RuleVerdict.CATEGORY_DISABLED
                else -> RuleVerdict.SIGN_REJECTED
            }
            RuleCandidate(candidate.rule, candidate.index, verdict)
        }

        // Tier 2's models already exclude disabled categories and Sonstiges (they were left out when
        // the models were built), so the sign constraint is all that is left to filter with here —
        // the same predicate `suggest` passes.
        val statExplanation = statistical?.explain(text, admissible)
        val statTier = StatisticalTier(
            state = when {
                !settings.statisticalEnabled -> TierState.SWITCHED_OFF
                prototypeCount == 0 && exampleCount == 0 -> TierState.UNTRAINED
                else -> TierState.RAN
            },
            threshold = settings.statisticalThreshold,
            explanation = statExplanation,
        )

        val embExplanation = embedding?.let { it.explain(embedder.embed(text), admissible) }
        val embTier = EmbeddingTier(
            state = when {
                !settings.embeddingEnabled -> TierState.SWITCHED_OFF
                !embedderAvailable -> TierState.MODEL_MISSING
                prototypeCount == 0 && exampleCount == 0 -> TierState.UNTRAINED
                else -> TierState.RAN
            },
            minMargin = settings.embeddingMargin,
            explanation = embExplanation,
        )

        // Both proposals normalized against their own bar before they are compared — the merge the
        // Categorizer performs, called on the Categorizer so it can never drift from it.
        val stat = statExplanation?.decision?.let {
            it.categoryId to categorizer.relativeStrength(it.confidence.toFloat(), settings.statisticalThreshold.toFloat())
        }
        val emb = embExplanation?.decision?.let {
            it.categoryId to categorizer.relativeStrength(it.margin, settings.embeddingMargin.toFloat())
        }
        val merge = MergeStep(
            statistical = stat,
            embedding = emb,
            verdict = when {
                stat != null && emb != null && stat.first == emb.first -> MergeVerdict.AGREED
                stat != null && emb != null && stat.second >= emb.second -> MergeVerdict.STATISTICAL_STRONGER
                stat != null && emb != null -> MergeVerdict.EMBEDDING_STRONGER
                stat != null -> MergeVerdict.ONLY_STATISTICAL
                emb != null -> MergeVerdict.ONLY_EMBEDDING
                else -> MergeVerdict.NOTHING
            },
            categoryId = categorizer.mergeSuggestion(stat, emb),
        )

        val winner = rules.firstOrNull { it.verdict == RuleVerdict.WINNER }
        val outcome = when {
            accountDefault != null ->
                ClassificationOutcome(OutcomeKind.ACCOUNT_DEFAULT, accountDefault.categoryId, CategorySource.SEED_RULE)
            winner != null -> ClassificationOutcome(
                OutcomeKind.COMMITTED,
                winner.rule.categoryId,
                if (winner.rule.source == RuleSource.USER) CategorySource.USER_RULE else CategorySource.SEED_RULE,
            )
            merge.categoryId != null -> ClassificationOutcome(OutcomeKind.SUGGESTED, merge.categoryId, null)
            else -> ClassificationOutcome(OutcomeKind.NOTHING, null, null)
        }

        return ClassificationExplanation(
            text = text,
            amountCents = amountCents,
            allowedKinds = allowedKindsForAmount(amountCents),
            accountTypeDefault = accountDefault,
            rules = rules,
            statistical = statTier,
            embedding = embTier,
            merge = merge,
            outcome = outcome,
        )
    }
}

/** Everything a classifier reads from a transaction — the counterparty and the purpose, as one text. */
internal fun explainTextOf(txn: Txn): String = listOfNotNull(txn.counterparty, txn.purpose).joinToString(" ")
