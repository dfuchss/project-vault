package org.fuchss.projectvault.classification

import org.fuchss.projectvault.classification.StatisticalClassifier.Example
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Evaluation harness ("test what works best") — it **prints** comparative metrics for the classifier
 * strategies on a synthetic, realistic fixture so the thresholds and the [Categorizer] merge policy
 * are chosen from data, not guessed. It is deliberately not a tight pass/fail gate; the asserts only
 * pin down the load-bearing conclusion (the statistical classifier fills gaps the rules leave, at
 * usable precision). Run: `./gradlew :core:classification:test --tests '*ClassifierComparisonTest*'`.
 *
 * No real financial data (CLAUDE.md): every row is invented, using merchants that are deliberately
 * NOT seed keywords, so the rules-only baseline misses them and the statistical model must generalize
 * from the descriptive German vocabulary shared with sibling transactions + the seed keyword docs.
 */
class ClassifierComparisonTest {

    private data class Sample(val text: String, val gold: String)

    /** The operating point chosen from the sweep below; mirrors StatisticalClassifier's default. */
    private val DEFAULT_THRESHOLD = 0.65

    // ~5 novel merchants per category, clustered by shared descriptive words (not brand names).
    private val fixture = listOf(
        // Lebensmittel — food words: fleisch, wurst, kaese, gemuese, obst, frisch
        Sample("Metzgerei Wagner Fleisch und Wurst frisch", "cat-groceries"),
        Sample("Hofladen Bauer Gemuese Obst frisch regional", "cat-groceries"),
        Sample("Feinkost Mueller Kaese Wurst Aufschnitt", "cat-groceries"),
        Sample("Obsthof Sonnenberg Beeren Obst frisch", "cat-groceries"),
        Sample("Kaeserei Alpenhof Kaese frisch regional", "cat-groceries"),
        // Restaurant & Café — meal words: abendessen, mittagessen, speisen, pasta, nudeln
        Sample("Trattoria Bella Pasta Pizza Abendessen", "cat-restaurants"),
        Sample("Gasthaus Krone Mittagessen warme Speisen", "cat-restaurants"),
        Sample("Ramen Haus Tokio Nudeln Abendessen", "cat-restaurants"),
        Sample("Wirtshaus Adler Mittagessen Speisen Getraenke", "cat-restaurants"),
        Sample("Pasta Bar Roma Nudeln Mittagessen", "cat-restaurants"),
        // Abos & Digitales — subscription words: abo, streaming, monatsbeitrag, jahresabo, cloud
        Sample("Cloudspeicher Anbieter Jahresabo Cloud", "cat-subscriptions"),
        Sample("Musikdienst Premium Streaming Monatsbeitrag Abo", "cat-subscriptions"),
        Sample("Videostreaming Portal Streaming Monatsbeitrag", "cat-subscriptions"),
        Sample("Software Lizenz Jahresabo Cloud Dienst", "cat-subscriptions"),
        Sample("Hoerbuch Dienst Abo Streaming Monatsbeitrag", "cat-subscriptions"),
        // Mobilität — transit words: ticket, fahrschein, monatskarte, nahverkehr, fahrkarte
        Sample("Nahverkehr Monatskarte Ticket Bus", "cat-mobility"),
        Sample("Regionalbus Fahrschein Ticket Fahrt", "cat-mobility"),
        Sample("Verkehrsverbund Monatskarte Nahverkehr Ticket", "cat-mobility"),
        Sample("Fahrkarte Automat Ticket Nahverkehr", "cat-mobility"),
        Sample("Buslinie Fahrschein Fahrkarte Fahrt", "cat-mobility"),
        // Versicherung — insurance words: beitrag, police, versicherungsschutz, tarif
        Sample("Haftpflicht Police Beitrag Versicherungsschutz", "cat-insurance"),
        Sample("Hausrat Tarif Beitrag Police Schutz", "cat-insurance"),
        Sample("Rechtsschutz Police Jahresbeitrag Tarif", "cat-insurance"),
        Sample("Unfallschutz Beitrag Police Versicherungsschutz", "cat-insurance"),
    )

    private val seedDocs: List<Example> =
        SeedCatalog.rules.map { Example(it.keyword, it.categoryId) } +
            SeedCatalog.categories.map { Example(it.name, it.id) }

    @Test
    fun `report precision and coverage per strategy`() {
        val rules = RuleEngine(SeedCatalog.rules)

        // --- Rules-only baseline ---
        val rulesPred = fixture.map { rules.categorize(it.text) }
        val rulesMissed = fixture.filterIndexed { i, _ -> rulesPred[i] == null }
        printLine("rules-only", fixture, rulesPred)

        // --- Statistical, swept over thresholds, leave-one-out (train on seed docs + other rows) ---
        // The sweep is how the default minConfidence in StatisticalClassifier was chosen.
        println("  statistical (leave-one-out, threshold sweep):")
        for (threshold in listOf(0.55, 0.60, 0.65, 0.70, 0.80, 0.90)) {
            val pred = statisticalPredict(threshold)
            printLine("  τ=%.2f".format(threshold), fixture, pred)
        }

        // --- Embeddings, swept over thresholds, leave-one-out. Real run when the model is present. ---
        val embedder: Embedder = runCatching { DjlEmbedder() }.getOrDefault(NoopEmbedder)
        var embPred: List<String?>? = null
        if (embedder.available()) {
            // Embed each fixture text and each seed-category prototype ONCE, then reuse (leave-one-out
            // only changes which few-shot examples are labeled, not the vectors).
            val fixtureVectors = embedder.embed(fixture.map { it.text })
            val keywordsByCat = SeedCatalog.rules.groupBy { it.categoryId }
            val protoLabeled = SeedCatalog.categories.map { c ->
                val text = (listOf(c.name) + keywordsByCat[c.id].orEmpty().map { it.keyword }).joinToString(" ")
                EmbeddingClassifier.Labeled(c.id, embedder.embed(text))
            }
            fun embeddingPredict(minSim: Float): List<String?> = fixture.indices.map { i ->
                val labeled = protoLabeled +
                    fixture.indices.filter { it != i }.map { EmbeddingClassifier.Labeled(fixture[it].gold, fixtureVectors[it]) }
                EmbeddingClassifier(labeled, minSim).classify(fixtureVectors[i])?.categoryId
            }
            println("  embeddings (leave-one-out, threshold sweep):")
            for (minSim in listOf(0.62f, 0.70f, 0.75f, 0.80f, 0.85f)) {
                printLine("  σ=%.2f".format(minSim), fixture, embeddingPredict(minSim))
            }
            embPred = embeddingPredict(0.62f)
        } else {
            println("  embeddings : SKIPPED (model unavailable; available=false)")
        }

        // --- Head-to-head at the shipping defaults + the merge policy the Categorizer uses. ---
        val statPred = statisticalPredict(DEFAULT_THRESHOLD)
        printLine("statistical@0.65", fixture, statPred)
        embPred?.let {
            printLine("embeddings@0.62", fixture, it)
            printLine("merged", fixture, mergedPredict())
        }
        val gapCorrect = rulesMissed.count { s -> statPred[fixture.indexOf(s)] == s.gold }
        println("  gap recovery: statistical correctly labels $gapCorrect / ${rulesMissed.size} rules-missed rows")
        embPred?.let { p ->
            println("  gap recovery: embeddings correctly label ${rulesMissed.count { p[fixture.indexOf(it)] == it.gold }} / ${rulesMissed.size} rules-missed rows")
        }

        // Load-bearing conclusions (loose, to stay robust): the statistical model fills real gaps at
        // usable precision. Everything else above is informational for tuning.
        assertTrue(rulesMissed.isNotEmpty(), "fixture should contain merchants the seed rules miss")
        assertTrue(gapCorrect > 0, "statistical classifier should recover at least some rules-missed rows")
        val statPrecision = precision(fixture, statPred)
        assertTrue(statPrecision >= 0.5, "statistical precision on predicted rows should be usable, was $statPrecision")
    }

    private fun statisticalPredict(threshold: Double): List<String?> = fixture.mapIndexed { i, s ->
        val train = seedDocs + fixture.filterIndexed { j, _ -> j != i }.map { Example(it.text, it.gold) }
        StatisticalClassifier(train, minConfidence = threshold).classify(s.text)?.categoryId
    }

    /** The Categorizer's complement policy applied to both models' leave-one-out predictions. */
    private fun mergedPredict(): List<String?> {
        val embedder = runCatching { DjlEmbedder() }.getOrDefault(NoopEmbedder)
        if (!embedder.available()) return statisticalPredict(DEFAULT_THRESHOLD)
        val fixtureVectors = embedder.embed(fixture.map { it.text })
        val keywordsByCat = SeedCatalog.rules.groupBy { it.categoryId }
        val protoLabeled = SeedCatalog.categories.map { c ->
            val text = (listOf(c.name) + keywordsByCat[c.id].orEmpty().map { it.keyword }).joinToString(" ")
            EmbeddingClassifier.Labeled(c.id, embedder.embed(text))
        }
        return fixture.indices.map { i ->
            val train = seedDocs + fixture.filterIndexed { j, _ -> j != i }.map { Example(it.text, it.gold) }
            val stat = StatisticalClassifier(train, minConfidence = DEFAULT_THRESHOLD).classify(fixture[i].text)
                ?.let { it.categoryId to it.confidence.toFloat() }
            val labeled = protoLabeled +
                fixture.indices.filter { it != i }.map { EmbeddingClassifier.Labeled(fixture[it].gold, fixtureVectors[it]) }
            val emb = EmbeddingClassifier(labeled, 0.62f).classify(fixtureVectors[i])?.let { it.categoryId to it.similarity }
            when {
                stat != null && emb != null -> if (stat.first == emb.first || stat.second >= emb.second) stat.first else emb.first
                else -> (stat ?: emb)?.first
            }
        }
    }

    private fun precision(samples: List<Sample>, pred: List<String?>): Double {
        val predicted = pred.count { it != null }
        if (predicted == 0) return 0.0
        val correct = samples.indices.count { pred[it] != null && pred[it] == samples[it].gold }
        return correct.toDouble() / predicted
    }

    private fun printLine(label: String, samples: List<Sample>, pred: List<String?>) {
        val predicted = pred.count { it != null }
        val correct = samples.indices.count { pred[it] != null && pred[it] == samples[it].gold }
        val prec = if (predicted == 0) 0.0 else correct.toDouble() / predicted
        val coverage = predicted.toDouble() / samples.size
        println(
            "  %-11s: precision=%.2f (%d/%d)  coverage=%.2f (%d/%d)"
                .format(label, prec, correct, predicted, coverage, predicted, samples.size),
        )
    }
}
