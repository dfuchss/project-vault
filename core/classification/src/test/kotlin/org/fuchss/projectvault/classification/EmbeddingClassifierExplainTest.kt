package org.fuchss.projectvault.classification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The additive [EmbeddingClassifier.explain] entry point. Synthetic vectors throughout — the bundled
 * ONNX model is gitignored and may not be provisioned, so nothing here may depend on it.
 */
class EmbeddingClassifierExplainTest {

    private val groceries = EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0f, 0f))
    private val restaurants = EmbeddingClassifier.Labeled("cat-restaurants", floatArrayOf(0f, 1f, 0f))
    private val salary = EmbeddingClassifier.Labeled("cat-salary", floatArrayOf(0f, 0f, 1f))

    private fun classifier(minMargin: Float = 0.03f) =
        EmbeddingClassifier(listOf(groceries, restaurants, salary), minMargin = minMargin)

    @Test
    fun `every category is scored and ranked, with the top two named`() {
        val explanation = classifier().explain(floatArrayOf(0.9f, 0.4f, 0.1f))

        assertEquals(listOf("cat-groceries", "cat-restaurants", "cat-salary"), explanation.scores.map { it.categoryId })
        assertTrue(explanation.scores.all { it.admissible })
        assertEquals("cat-groceries", explanation.winner)
        assertEquals("cat-restaurants", explanation.runnerUp)
        // The reported margin is the gap between the two named categories, not between two vectors.
        val top1 = explanation.scores[0].similarity
        val top2 = explanation.scores[1].similarity
        assertEquals(top1 - top2, explanation.margin)
        assertEquals(top1, explanation.similarity)
    }

    @Test
    fun `the runner-up is the best other category, so agreeing examples read as confidence`() {
        val labeled = listOf(
            EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0.02f, 0f)),
            EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0.01f, 0f)),
            restaurants,
        )
        val explanation = EmbeddingClassifier(labeled, minMargin = 0.5f).explain(floatArrayOf(1f, 0f, 0f))

        // Two groceries vectors collapse to one row (the per-category maximum), so the runner-up is
        // the restaurant category and the margin is large rather than ~0.
        assertEquals(listOf("cat-groceries", "cat-restaurants"), explanation.scores.map { it.categoryId })
        assertEquals("cat-restaurants", explanation.runnerUp)
        assertTrue((explanation.margin ?: 0f) > 0.9f)
    }

    @Test
    fun `categories the sign rules out are scored and marked, but take no part in the decision`() {
        // A credit: the expense categories are not candidates. They are still scored, because "the
        // nearest category was one your amount forbids" is the explanation the user needs.
        val explanation = classifier(minMargin = 0.1f)
            .explain(floatArrayOf(0.9f, 0f, 0.5f)) { it == "cat-salary" }

        assertEquals("cat-groceries", explanation.scores.first().categoryId)
        assertEquals(false, explanation.scores.first().admissible)
        assertEquals(listOf("cat-salary"), explanation.scores.filter { it.admissible }.map { it.categoryId })
        // A lone admissible category has no runner-up: its lead is measured against zero.
        assertEquals("cat-salary", explanation.winner)
        assertNull(explanation.runnerUp)
        assertEquals(explanation.similarity, explanation.margin)
    }

    @Test
    fun `an ambiguous query reports its margin and no decision`() {
        val explanation = classifier(minMargin = 0.2f).explain(floatArrayOf(0.5f, 0.5f, 0f))

        assertNotNull(explanation.winner, "there is still a leader…")
        assertTrue((explanation.margin ?: 1f) < 0.001f, "…it just does not lead by anything")
        assertNull(explanation.decision)
        assertEquals(0.2f, explanation.minMargin)
    }

    @Test
    fun `no admissible category at all leaves nothing to explain`() {
        val explanation = classifier().explain(floatArrayOf(1f, 0f, 0f)) { false }

        assertNull(explanation.winner)
        assertNull(explanation.margin)
        assertNull(explanation.decision)
        // The inadmissible categories are still listed, all flagged.
        assertEquals(3, explanation.scores.size)
        assertTrue(explanation.scores.none { it.admissible })
    }

    @Test
    fun `the reported decision is always exactly what classify returns`() {
        val queries = listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0.5f, 0.5f, 0f),
            floatArrayOf(0.9f, 0.4f, 0.1f),
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(-1f, 0.2f, 0.3f),
        )
        val filters = listOf<Pair<String, (String) -> Boolean>>(
            "everything" to { true },
            "income side" to { it == "cat-salary" },
            "expense side" to { it != "cat-salary" },
            "nothing" to { false },
        )
        listOf(0f, 0.03f, 0.3f, 0.95f).forEach { margin ->
            val classifier = classifier(margin)
            queries.forEach { query ->
                filters.forEach { (name, accepts) ->
                    assertEquals(
                        classifier.classify(query, accepts),
                        classifier.explain(query, accepts).decision,
                        "explain disagreed with classify at minMargin=$margin ($name)",
                    )
                }
            }
        }
    }
}
