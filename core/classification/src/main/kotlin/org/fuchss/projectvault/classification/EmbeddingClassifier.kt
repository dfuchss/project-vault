package org.fuchss.projectvault.classification

import kotlin.math.sqrt

/**
 * Tier 2: classifies a transaction by semantic similarity to labeled vectors — category prototypes
 * (zero-shot) and previously-categorized transactions (few-shot). Nearest vector by cosine wins,
 * provided it clears [minSimilarity]; otherwise the transaction is left for manual categorization.
 */
class EmbeddingClassifier(
    private val labeled: List<Labeled>,
    private val minSimilarity: Float = 0.62f,
) {
    data class Labeled(val categoryId: String, val vector: FloatArray)
    data class Result(val categoryId: String, val similarity: Float)

    fun classify(query: FloatArray): Result? {
        var best: Labeled? = null
        var bestSim = -1f
        for (candidate in labeled) {
            val sim = cosine(query, candidate.vector)
            if (sim > bestSim) { bestSim = sim; best = candidate }
        }
        val winner = best ?: return null
        return if (bestSim >= minSimilarity) Result(winner.categoryId, bestSim) else null
    }

    companion object {
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
