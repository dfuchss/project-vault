package org.fuchss.projectvault.classification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic synthetic vectors throughout — the bundled ONNX model is gitignored and may not be
 * provisioned, so nothing here may depend on it. (The real-model behaviour is measured, and only
 * reported, by `ClassifierComparisonTest`.)
 */
class EmbeddingClassifierTest {

    private val groceries = EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0f, 0f))
    private val restaurants = EmbeddingClassifier.Labeled("cat-restaurants", floatArrayOf(0f, 1f, 0f))
    private val salary = EmbeddingClassifier.Labeled("cat-salary", floatArrayOf(0f, 0f, 1f))

    @Test
    fun `a clear winner over the runner-up category wins`() {
        val classifier = EmbeddingClassifier(listOf(groceries, restaurants), minMargin = 0.1f)
        val result = classifier.classify(floatArrayOf(0.95f, 0.1f, 0f))
        assertEquals("cat-groceries", result?.categoryId)
        assertTrue((result?.margin ?: 0f) >= 0.1f, "the margin is reported on the result")
        assertTrue((result?.similarity ?: 0f) > 0.9f)
    }

    @Test
    fun `an ambiguous query proposes nothing even though both cosines are high`() {
        // Equidistant to both prototypes: cosine ≈ 0.707 to each — high, but the margin is 0. This is
        // exactly the case the old absolute-cosine criterion accepted and this one rejects.
        val classifier = EmbeddingClassifier(listOf(groceries, restaurants), minMargin = 0.02f)
        assertNull(classifier.classify(floatArrayOf(0.5f, 0.5f, 0f)))
    }

    @Test
    fun `the margin is measured between categories, not between vectors of the same category`() {
        // Three near-identical groceries examples: the two nearest *vectors* are both groceries, so a
        // vector-level margin would be ~0 and the row would be dropped. Agreement must read as
        // confidence instead — the runner-up is the best *other* category.
        val manyGroceries = listOf(
            EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0.02f, 0f)),
            EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0.01f, 0f)),
            EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(0.99f, 0f, 0f)),
            restaurants,
        )
        val result = EmbeddingClassifier(manyGroceries, minMargin = 0.5f).classify(floatArrayOf(1f, 0f, 0f))
        assertEquals("cat-groceries", result?.categoryId)
    }

    @Test
    fun `the candidate filter both narrows the winner and defines the runner-up`() {
        val labeled = listOf(groceries, restaurants, salary)
        // A credit: expense categories are not candidates, so the strong grocery match is invisible and
        // the margin is computed among the accepted ones only.
        val result = EmbeddingClassifier(labeled, minMargin = 0.5f)
            .classify(floatArrayOf(0.3f, 0f, 0.9f)) { it == "cat-salary" || it == "cat-transfers" }
        assertEquals("cat-salary", result?.categoryId)
        assertEquals(
            result?.similarity,
            result?.margin,
            "with one admissible category there is no runner-up, so the lead is measured against zero",
        )
    }

    @Test
    fun `a filter that admits nothing yields no proposal`() {
        assertNull(EmbeddingClassifier(listOf(groceries, restaurants)).classify(floatArrayOf(1f, 0f, 0f)) { false })
    }

    @Test
    fun `an empty label set yields no proposal`() {
        assertNull(EmbeddingClassifier(emptyList()).classify(floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun `a single labeled category is treated as confident, not as unresolvable`() {
        val result = EmbeddingClassifier(listOf(groceries), minMargin = 0.5f).classify(floatArrayOf(1f, 0.1f, 0f))
        assertEquals("cat-groceries", result?.categoryId)
    }

    @Test
    fun `the similarity floor still rejects a degenerate query`() {
        // Nearly orthogonal to everything: the margin happens to clear the bar, the similarity does not.
        val labeled = listOf(groceries, restaurants)
        val classifier = EmbeddingClassifier(labeled, minMargin = 0.01f, minSimilarity = 0.35f)
        assertNull(classifier.classify(floatArrayOf(0.2f, 0.01f, 5f)))
    }

    @Test
    fun `an empty query vector yields no proposal`() {
        assertNull(EmbeddingClassifier(listOf(groceries)).classify(FloatArray(0)))
    }

    @Test
    fun `cosine is 1 for identical and 0 for orthogonal`() {
        assertEquals(1f, EmbeddingClassifier.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)))
        assertEquals(0f, EmbeddingClassifier.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }
}
