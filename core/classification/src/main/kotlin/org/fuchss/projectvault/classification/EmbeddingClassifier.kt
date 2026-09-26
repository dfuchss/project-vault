package org.fuchss.projectvault.classification

import kotlin.math.sqrt

/**
 * Tier 2: classifies a transaction by semantic similarity to labeled vectors — category prototypes
 * (zero-shot) and previously-categorized transactions (few-shot).
 *
 * **Why the decision is a margin, not a cosine.** The bundled multilingual-e5-small embeds every
 * German bank string into a narrow cone: with the model's `"query: "` prefix, *every* fixture text
 * scores 0.76–0.87 against *every* category prototype. An absolute-cosine threshold is therefore
 * decorative — the `ClassifierComparisonTest` sweep showed coverage 1.00 at every σ from 0.62 to
 * 0.85, i.e. the old `minSimilarity` slider could not change a single decision. What actually
 * carries signal is how much the winner beats the *next category*: `top1 − top2`, the
 * [Result.margin]. On the harness that number separates a real opinion (≳ 0.03) from a coin flip
 * (≈ 0.00–0.01), and moving [minMargin] visibly trades coverage for precision.
 *
 * **The runner-up is the best *other category*, not the second-best vector.** With few-shot examples
 * the two nearest vectors are usually two transactions of the same category; the difference between
 * them says nothing about the decision. Per-category maxima are taken first, so agreement between
 * examples reads as confidence rather than as ambiguity.
 *
 * [minSimilarity] survives only as a sanity floor (default [DEFAULT_MIN_SIMILARITY], below anything
 * the model produces for real text) — it keeps a degenerate query, an empty or orthogonal vector,
 * from being decided by a margin between two near-zero numbers. Precision on the harness is driven
 * entirely by [minMargin].
 */
class EmbeddingClassifier(
    private val labeled: List<Labeled>,
    private val minMargin: Float = DEFAULT_MIN_MARGIN,
    private val minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
) {
    data class Labeled(val categoryId: String, val vector: FloatArray)

    /**
     * A proposal: the winning category, its cosine [similarity], and the [margin] over the strongest
     * *other* admissible category — the number the acceptance decision is actually made on, exposed
     * so callers (the `Categorizer` merge) can weigh it against the other suggester.
     */
    data class Result(val categoryId: String, val similarity: Float, val margin: Float)

    /**
     * Proposes a category for [query], or null when no admissible category wins clearly enough.
     *
     * [accepts] narrows the eligible categories — it carries the amount-sign constraint, so a credit
     * is never proposed an expense category. It filters *candidates*, and the margin is computed over
     * the accepted ones only: a forbidden front-runner is not allowed to shrink (or inflate) the
     * confidence of the decision that is actually made.
     *
     * When only one admissible category exists at all there is no runner-up to lose to; the margin is
     * then the winner's own similarity (measured against a zero baseline), so a lone category is
     * treated as confident rather than as unresolvable.
     */
    @JvmOverloads
    fun classify(query: FloatArray, accepts: (String) -> Boolean = { true }): Result? {
        val ranking = rank(query, accepts)
        val category = ranking.winner ?: return null
        if (ranking.top1 < minSimilarity || ranking.margin < minMargin) return null
        return Result(category, ranking.top1, ranking.margin)
    }

    /** Where every admissible category landed, and the lead the decision is made on. */
    private class Ranking(
        val perCategory: Map<String, Float>,
        val winner: String?,
        val runnerUp: String?,
        val top1: Float,
        val top2: Float,
    ) {
        /** No second category → nothing to be confused with; measure the lead against zero. */
        val margin: Float get() = top1 - (if (top2 == Float.NEGATIVE_INFINITY) 0f else top2)
    }

    /**
     * The per-category cosines and the top two of them. Both [classify] and [explain] go through this
     * one function, so an explanation can never describe a ranking other than the one decided on.
     */
    private fun rank(query: FloatArray, accepts: (String) -> Boolean): Ranking {
        // Per-category maximum first: two examples of the same category must not look like a tie.
        val bestByCategory = HashMap<String, Float>()
        for (candidate in labeled) {
            if (!accepts(candidate.categoryId)) continue
            val sim = cosine(query, candidate.vector)
            val previous = bestByCategory[candidate.categoryId]
            if (previous == null || sim > previous) bestByCategory[candidate.categoryId] = sim
        }

        var winner: String? = null
        var runnerUp: String? = null
        var top1 = Float.NEGATIVE_INFINITY
        var top2 = Float.NEGATIVE_INFINITY
        for ((categoryId, sim) in bestByCategory) {
            if (sim > top1) {
                top2 = top1
                runnerUp = winner
                top1 = sim
                winner = categoryId
            } else if (sim > top2) {
                top2 = sim
                runnerUp = categoryId
            }
        }
        return Ranking(bestByCategory, winner, runnerUp, top1, top2)
    }

    /** One category's cosine to the query. [admissible] = it survived the caller's candidate filter. */
    data class CategoryScore(val categoryId: String, val similarity: Float, val admissible: Boolean)

    /**
     * Why [classify] decided as it did: every category's cosine (ranked, the inadmissible ones marked
     * rather than hidden), the two that the [margin] is measured between, and the thresholds it had to
     * clear.
     *
     * The raw [CategoryScore.similarity] numbers are deliberately shown next to the [margin] and not
     * in place of it: they cluster in a narrow band for real bank text — which is precisely why the
     * decision is made on the lead over the runner-up — and seeing "0.83 vs. 0.82" is what makes that
     * legible. [decision] is [classify]'s own answer for the same input, so the two cannot disagree.
     */
    data class Explanation(
        val scores: List<CategoryScore>,
        val winner: String?,
        val runnerUp: String?,
        val similarity: Float?,
        val margin: Float?,
        val minMargin: Float,
        val minSimilarity: Float,
        val decision: Result?,
    )

    /**
     * The same computation [classify] performs, with its working shown — including the categories
     * [accepts] ruled out, which are scored anyway (against the full [labeled] set) so a user can see
     * *that* the sign constraint is what kept a front-runner out. They take no part in the decision.
     */
    @JvmOverloads
    fun explain(query: FloatArray, accepts: (String) -> Boolean = { true }): Explanation {
        val admissible = rank(query, accepts)
        val all = rank(query) { true }
        val scores = all.perCategory
            .map { (id, sim) -> CategoryScore(id, sim, admissible = admissible.perCategory.containsKey(id)) }
            .sortedByDescending { it.similarity }
        return Explanation(
            scores = scores,
            winner = admissible.winner,
            runnerUp = admissible.runnerUp,
            similarity = admissible.winner?.let { admissible.top1 },
            margin = admissible.winner?.let { admissible.margin },
            minMargin = minMargin,
            minSimilarity = minSimilarity,
            decision = classify(query, accepts),
        )
    }

    companion object {
        /**
         * Picked from the `ClassifierComparisonTest` margin sweep: the knee of the curve, where the
         * rows the model has no real opinion about (opaque payees, out-of-taxonomy text) drop out
         * while the in-taxonomy rows are still covered.
         */
        const val DEFAULT_MIN_MARGIN = 0.03f

        /** Sanity floor only — real e5 similarities sit far above this; see the class doc. */
        const val DEFAULT_MIN_SIMILARITY = 0.35f

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom == 0f) 0f else dot / denom
        }
    }
}

/** Default embedder used when no model is provisioned: Tier 2 is simply skipped. */
object NoopEmbedder : Embedder {
    override fun available(): Boolean = false
    override fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(0) }
}
