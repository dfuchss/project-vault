package org.fuchss.projectvault.classification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingClassifierTest {

    private val groceries = EmbeddingClassifier.Labeled("cat-groceries", floatArrayOf(1f, 0f, 0f))
    private val restaurants = EmbeddingClassifier.Labeled("cat-restaurants", floatArrayOf(0f, 1f, 0f))

    @Test
    fun `nearest labeled vector wins above threshold`() {
        val classifier = EmbeddingClassifier(listOf(groceries, restaurants), minSimilarity = 0.6f)
        val result = classifier.classify(floatArrayOf(0.95f, 0.1f, 0f))
        assertEquals("cat-groceries", result?.categoryId)
        assertTrue((result?.similarity ?: 0f) >= 0.6f)
    }

    @Test
    fun `returns null when nothing clears the threshold`() {
        val classifier = EmbeddingClassifier(listOf(groceries, restaurants), minSimilarity = 0.9f)
        // Equidistant to both prototypes -> cosine ~0.707, below 0.9.
        assertNull(classifier.classify(floatArrayOf(0.5f, 0.5f, 0f)))
    }

    @Test
    fun `cosine is 1 for identical and 0 for orthogonal`() {
        assertEquals(1f, EmbeddingClassifier.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f)))
        assertEquals(0f, EmbeddingClassifier.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
    }
}
