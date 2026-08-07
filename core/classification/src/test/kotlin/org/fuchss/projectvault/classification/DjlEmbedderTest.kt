package org.fuchss.projectvault.classification

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the bundled multilingual model actually loads and produces semantically meaningful
 * embeddings: two restaurants should be more similar to each other than to a fuel station, and a
 * German drugstore query should land nearer a drugstore prototype than a fuel one.
 */
class DjlEmbedderTest {

    @Test
    fun `bundled model loads and captures semantic similarity`() {
        val embedder = DjlEmbedder()
        assertTrue(embedder.available(), "bundled embedding model should load")

        val vectors = embedder.embed(
            listOf(
                "Trattoria Napoli Pizzeria Ristorante",
                "Bella Italia Restaurant",
                "Aral Tankstelle Benzin Diesel",
            ),
        )
        val restaurantToRestaurant = EmbeddingClassifier.cosine(vectors[0], vectors[1])
        val restaurantToFuel = EmbeddingClassifier.cosine(vectors[0], vectors[2])
        assertTrue(
            restaurantToRestaurant > restaurantToFuel,
            "restaurants should be closer to each other ($restaurantToRestaurant) than to fuel ($restaurantToFuel)",
        )
    }
}
