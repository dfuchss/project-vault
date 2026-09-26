package org.fuchss.projectvault.classification

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tier 2 (statistical): a lightweight on-device classifier trained from the transactions you've
 * already labeled — a TF-IDF vectorizer feeding a multinomial Naive Bayes model. Pure Kotlin, no
 * bundled model, no download: it learns the vocabulary of *your* merchants and proposes a category
 * for the ones the keyword rules miss.
 *
 * Like [EmbeddingClassifier] it only ever **suggests** — [classify] returns a category with a
 * confidence, or null when nothing clears [minConfidence] (left for manual categorization). It is
 * cheap to rebuild, so callers retrain it in-memory on every classification pass.
 */
class StatisticalClassifier(
    examples: List<Example>,
    private val minConfidence: Double = 0.65,
    private val useBigrams: Boolean = false,
    private val alpha: Double = 1.0,
) {
    /** One labeled training document: the transaction text and the category it belongs to. */
    data class Example(val text: String, val categoryId: String)

    /** A proposed category and the model's confidence (softmax probability of the winning class). */
    data class Result(val categoryId: String, val confidence: Double)

    private val classes: List<String>
    private val logPrior: DoubleArray
    private val idf: Map<String, Double>
    /** token → per-class log P(token | class), indexed by the position of the class in [classes]. */
    private val featureLogProb: Map<String, DoubleArray>
    private val trained: Boolean

    init {
        val docs = examples
            .map { tokenize(it.text) to it.categoryId }
            .filter { it.first.isNotEmpty() }
        val n = docs.size

        // Document frequencies → smoothed IDF (sklearn-style: ln((N+1)/(df+1)) + 1).
        val df = HashMap<String, Int>()
        docs.forEach { (tokens, _) -> tokens.toSet().forEach { df.merge(it, 1, Int::plus) } }
        idf = df.mapValues { (_, d) -> ln((n + 1.0) / (d + 1.0)) + 1.0 }

        classes = docs.map { it.second }.distinct()
        val classIndex = classes.withIndex().associate { (i, c) -> c to i }
        val nClasses = classes.size

        // Accumulate L2-normalized TF-IDF mass per (token, class) and per class — this is exactly a
        // TfidfVectorizer(norm='l2') feeding a MultinomialNB.
        val classDocs = IntArray(nClasses)
        val classTotal = DoubleArray(nClasses)
        val featureWeight = HashMap<String, DoubleArray>()
        docs.forEach { (tokens, category) ->
            val ci = classIndex.getValue(category)
            classDocs[ci]++
            tfidfVector(tokens).forEach { (token, weight) ->
                featureWeight.getOrPut(token) { DoubleArray(nClasses) }[ci] += weight
                classTotal[ci] += weight
            }
        }

        val vocabSize = idf.size
        val lnDenom = DoubleArray(nClasses) { ln(classTotal[it] + alpha * vocabSize) }
        featureLogProb = featureWeight.mapValues { (_, perClass) ->
            DoubleArray(nClasses) { ci -> ln(perClass[ci] + alpha) - lnDenom[ci] }
        }
        logPrior = DoubleArray(nClasses) { ln(classDocs[it].toDouble() / n) }
        trained = n > 0 && nClasses > 0
    }

    /**
     * Proposes a category for [text], or null when the model is untrained, the text shares no known
     * vocabulary, or the winner's confidence is below [minConfidence].
     *
     * [accepts] narrows the classes that may win — used to rule out categories the transaction's
     * amount sign forbids. The runner-up used for the confidence margin is likewise drawn from the
     * accepted classes, so the confidence reported is the confidence of the decision actually made.
     */
    @JvmOverloads
    fun classify(text: String, accepts: (String) -> Boolean = { true }): Result? {
        if (!trained) return null
        val vector = tfidfVector(tokenize(text))
        if (vector.isEmpty()) return null

        val scores = DoubleArray(classes.size) { logPrior[it] }
        vector.forEach { (token, weight) ->
            val perClass = featureLogProb[token] ?: return@forEach
            for (ci in scores.indices) scores[ci] += weight * perClass[ci]
        }

        // Confidence = pairwise softmax between the winner and runner-up (a margin), which stays
        // meaningful however many categories exist. A plain N-way softmax washes out toward 1/N with a
        // large taxonomy (Project Vault seeds ~19 categories), so a strong winner would look unconfident.
        var s1 = Double.NEGATIVE_INFINITY
        var s2 = Double.NEGATIVE_INFINITY
        var winner = -1
        for (i in scores.indices) {
            if (!accepts(classes[i])) continue
            val s = scores[i]
            if (s > s1) { s2 = s1; s1 = s; winner = i } else if (s > s2) { s2 = s }
        }
        if (winner < 0) return null
        val confidence = if (s2 == Double.NEGATIVE_INFINITY) 1.0 else 1.0 / (1.0 + exp(-(s1 - s2)))
        return if (confidence >= minConfidence) Result(classes[winner], confidence) else null
    }

    /** One category's standing for a text. [share] is for display; see [Explanation]. */
    data class ClassScore(val categoryId: String, val logScore: Double, val share: Double, val admissible: Boolean)

    /**
     * What one token contributed. [weight] is its TF-IDF mass in the query, [towardWinner] how much
     * it pushed the winning class, and [lift] how much it pushed the winner **over the runner-up** —
     * the discriminating number. A token can shout (high [towardWinner]) while saying nothing useful
     * (near-zero [lift]) because every category likes it equally; that is exactly the case a user
     * staring at a wrong classification needs to be able to see.
     */
    data class TokenEvidence(val token: String, val weight: Double, val towardWinner: Double, val lift: Double)

    /**
     * Why [classify] decided as it did: the evidence it read and where every class landed.
     *
     * [scores] carries the model's raw log-scores. [ClassScore.share] is a softmax over the
     * admissible classes, included **only** so the numbers are readable as proportions — the
     * acceptance decision is *not* made on it, but on the pairwise winner-vs-runner-up margin in
     * [classify] (a full softmax washes out toward 1/N with a large taxonomy, which is why the
     * decision does not use one). [unknownTokens] are the words that carried no weight at all
     * because the model has never seen them; a text made entirely of those gets no proposal, and
     * saying so is more useful than showing an empty table.
     */
    data class Explanation(
        val tokens: List<TokenEvidence>,
        val scores: List<ClassScore>,
        val decision: Result?,
        val unknownTokens: List<String>,
    )

    /**
     * The same computation [classify] performs, with its working shown. Pure and side-effect free, so
     * the UI can ask it for any text without disturbing anything.
     */
    @JvmOverloads
    fun explain(text: String, accepts: (String) -> Boolean = { true }): Explanation {
        val allTokens = tokenize(text)
        val unknown = allTokens.distinct().filterNot { it in idf }
        val vector = tfidfVector(allTokens)
        if (!trained || vector.isEmpty()) return Explanation(emptyList(), emptyList(), null, unknown)

        val scores = DoubleArray(classes.size) { logPrior[it] }
        vector.forEach { (token, weight) ->
            val perClass = featureLogProb[token] ?: return@forEach
            for (ci in scores.indices) scores[ci] += weight * perClass[ci]
        }

        // Winner and runner-up among the admissible classes only — the same restriction classify()
        // applies, so the explanation describes the decision that was actually made.
        var winner = -1
        var runnerUp = -1
        for (i in scores.indices) {
            if (!accepts(classes[i])) continue
            when {
                winner < 0 || scores[i] > scores[winner] -> { runnerUp = winner; winner = i }
                runnerUp < 0 || scores[i] > scores[runnerUp] -> runnerUp = i
            }
        }

        val evidence = if (winner < 0) {
            emptyList()
        } else {
            vector.mapNotNull { (token, weight) ->
                val perClass = featureLogProb[token] ?: return@mapNotNull null
                val toward = weight * perClass[winner]
                val lift = if (runnerUp >= 0) toward - weight * perClass[runnerUp] else toward
                TokenEvidence(token, weight, toward, lift)
            }.sortedByDescending { it.lift }
        }

        val admissibleScores = scores.indices.filter { accepts(classes[it]) }.map { scores[it] }
        val max = admissibleScores.maxOrNull() ?: 0.0
        val total = admissibleScores.sumOf { exp(it - max) }
        val ranked = scores.indices
            .map { i ->
                val admissible = accepts(classes[i])
                ClassScore(
                    categoryId = classes[i],
                    logScore = scores[i],
                    share = if (admissible && total > 0.0) exp(scores[i] - max) / total else 0.0,
                    admissible = admissible,
                )
            }
            .sortedByDescending { it.logScore }

        return Explanation(evidence, ranked, classify(text, accepts), unknown)
    }

    /** TF-IDF weights for [tokens], keyed by token, L2-normalized (empty if no token is in-vocab). */
    private fun tfidfVector(tokens: List<String>): Map<String, Double> {
        if (tokens.isEmpty()) return emptyMap()
        val raw = tokens.groupingBy { it }.eachCount()
            .mapNotNull { (token, count) -> idf[token]?.let { token to count * it } }
            .toMap()
        val norm = sqrt(raw.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else raw.mapValues { it.value / norm }
    }

    /** Folds diacritics (shared with the rule engine), splits on non-alphanumerics, drops noise. */
    private fun tokenize(text: String): List<String> {
        val unigrams = TextNormalizer.fold(text)
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.length >= 2 && !it.all(Char::isDigit) }
        if (!useBigrams) return unigrams
        return unigrams + unigrams.zipWithNext { a, b -> "${a}_$b" }
    }
}
