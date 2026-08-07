package org.fuchss.projectvault.classification

/**
 * Turns text into a dense vector for semantic similarity. Implementations may be unavailable (model
 * not present / failed to load), in which case callers fall back to Tier-1 rules — so embeddings are
 * an enhancement, never a hard dependency.
 */
interface Embedder {
    fun available(): Boolean

    /** Embeds each input; returns one vector per input (same order). */
    fun embed(texts: List<String>): List<FloatArray>

    fun embed(text: String): FloatArray = embed(listOf(text)).first()
}
