package org.fuchss.projectvault.classification

import org.fuchss.projectvault.classification.StatisticalClassifier.Example
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatisticalClassifierTest {

    private val training = listOf(
        Example("REWE Markt Musterstadt", "cat-groceries"),
        Example("EDEKA Suedstadt Lebensmittel", "cat-groceries"),
        Example("ALDI SUED Filiale", "cat-groceries"),
        Example("Pizzeria Napoli Restaurant", "cat-restaurants"),
        Example("Ristorante La Piazza Abendessen", "cat-restaurants"),
        Example("Doener Imbiss Snack", "cat-restaurants"),
        Example("Spotify Abo Streaming", "cat-subscriptions"),
        Example("Netflix Monatsbeitrag", "cat-subscriptions"),
    )

    @Test
    fun `suggests the category of the most similar labeled transactions`() {
        val classifier = StatisticalClassifier(training, minConfidence = 0.4)
        // A grocery store not in the training set, but the shared "Lebensmittel/Markt" vocabulary lands it.
        assertEquals("cat-groceries", classifier.classify("LIDL Markt Lebensmittel")?.categoryId)
        assertEquals("cat-restaurants", classifier.classify("Trattoria Roma Abendessen")?.categoryId)
        assertEquals("cat-subscriptions", classifier.classify("Netflix Streaming")?.categoryId)
    }

    @Test
    fun `returns null with no shared vocabulary`() {
        val classifier = StatisticalClassifier(training, minConfidence = 0.4)
        assertNull(classifier.classify("Zzxqv Wxyz Qwerty"))
    }

    @Test
    fun `high threshold suppresses low-confidence guesses`() {
        val classifier = StatisticalClassifier(training, minConfidence = 0.99)
        // "Markt" alone is weak evidence across categories, so a strict threshold yields no suggestion.
        assertNull(classifier.classify("Markt"))
    }

    @Test
    fun `an untrained classifier never suggests`() {
        val classifier = StatisticalClassifier(emptyList())
        assertNull(classifier.classify("REWE Markt"))
    }

    @Test
    fun `diacritic-folded tokens match their ascii training form`() {
        val classifier = StatisticalClassifier(training, minConfidence = 0.4)
        // Training used ASCII "Doener"; the umlaut spelling folds to the same token.
        assertEquals("cat-restaurants", classifier.classify("Döner Palast")?.categoryId)
    }

    @Test
    fun `confidence is a probability in the unit interval`() {
        val classifier = StatisticalClassifier(training, minConfidence = 0.0)
        val result = classifier.classify("REWE Lebensmittel")
        assertTrue(result != null && result.confidence in 0.0..1.0)
    }
}
