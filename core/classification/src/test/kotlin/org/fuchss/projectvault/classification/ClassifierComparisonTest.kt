package org.fuchss.projectvault.classification

import org.fuchss.projectvault.classification.StatisticalClassifier.Example
import org.fuchss.projectvault.model.CategoryKind
import org.fuchss.projectvault.model.categoryAllowedForAmount
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Evaluation harness ("test what works best") — it **prints** comparative metrics for the classifier
 * strategies on a synthetic, realistic fixture so the thresholds and the [Categorizer] merge policy
 * are chosen from data, not guessed. It is deliberately not a tight pass/fail gate; the asserts only
 * pin down the load-bearing conclusions. Run:
 * `./gradlew :core:classification:test --tests '*ClassifierComparisonTest*'`.
 *
 * No real financial data (CLAUDE.md): every row is invented, using merchants that are deliberately
 * NOT seed keywords, so the rules-only baseline misses them and the models must generalize from the
 * descriptive German vocabulary shared with sibling transactions + the seed keyword docs.
 *
 * The fixture has three parts, because a sweep is only meaningful when abstaining can be rewarded:
 *  - **in-taxonomy expenses** (5 categories) — the original rows;
 *  - **in-taxonomy credits and transfers** — so the amount-sign candidate filter is exercised and
 *    income is scored, not just spending;
 *  - **rows with no gold category at all** (`gold == null`): opaque payees, bare "Zahlungseingang",
 *    card-terminal noise. A proposal on one of these is a *false positive*. Without them precision
 *    is bounded below by "every row has a right answer in the taxonomy" and no threshold can ever
 *    be seen to help.
 *
 * `cat-other` (Sonstiges) is excluded from every model's inputs, exactly as `Categorizer.suggest`
 * does — its only prototype is the word "Sonstiges", which sits nearest to precisely the texts that
 * say nothing, so it would soak up every one of the null-gold rows.
 */
class ClassifierComparisonTest {

    /** [gold] `null` = the row has no right answer; the only correct behaviour is to propose nothing. */
    private data class Sample(val text: String, val gold: String?, val amountCents: Long)

    /** The operating point chosen from the sweep below; mirrors StatisticalClassifier's default. */
    private val DEFAULT_THRESHOLD = 0.65

    private val CAT_OTHER = "cat-other"

    // ~5 novel merchants per category, clustered by shared descriptive words (not brand names).
    private val fixture = listOf(
        // Lebensmittel — food words: fleisch, wurst, kaese, gemuese, obst, frisch
        Sample("Metzgerei Wagner Fleisch und Wurst frisch", "cat-groceries", -2_340),
        Sample("Hofladen Bauer Gemuese Obst frisch regional", "cat-groceries", -1_875),
        Sample("Feinkost Mueller Kaese Wurst Aufschnitt", "cat-groceries", -3_120),
        Sample("Obsthof Sonnenberg Beeren Obst frisch", "cat-groceries", -960),
        Sample("Kaeserei Alpenhof Kaese frisch regional", "cat-groceries", -1_450),
        // Restaurant & Café — meal words: abendessen, mittagessen, speisen, pasta, nudeln
        Sample("Trattoria Bella Pasta Pizza Abendessen", "cat-restaurants", -4_280),
        Sample("Gasthaus Krone Mittagessen warme Speisen", "cat-restaurants", -1_690),
        Sample("Ramen Haus Tokio Nudeln Abendessen", "cat-restaurants", -2_750),
        Sample("Wirtshaus Adler Mittagessen Speisen Getraenke", "cat-restaurants", -3_340),
        Sample("Pasta Bar Roma Nudeln Mittagessen", "cat-restaurants", -1_290),
        // Abos & Digitales — subscription words: abo, streaming, monatsbeitrag, jahresabo, cloud
        Sample("Cloudspeicher Anbieter Jahresabo Cloud", "cat-subscriptions", -9_900),
        Sample("Musikdienst Premium Streaming Monatsbeitrag Abo", "cat-subscriptions", -1_099),
        Sample("Videostreaming Portal Streaming Monatsbeitrag", "cat-subscriptions", -1_399),
        Sample("Software Lizenz Jahresabo Cloud Dienst", "cat-subscriptions", -5_900),
        Sample("Hoerbuch Dienst Abo Streaming Monatsbeitrag", "cat-subscriptions", -999),
        // Mobilität — transit words: ticket, fahrschein, monatskarte, nahverkehr, fahrkarte
        Sample("Nahverkehr Monatskarte Ticket Bus", "cat-mobility", -5_800),
        Sample("Regionalbus Fahrschein Ticket Fahrt", "cat-mobility", -420),
        Sample("Verkehrsverbund Monatskarte Nahverkehr Ticket", "cat-mobility", -6_450),
        Sample("Fahrkarte Automat Ticket Nahverkehr", "cat-mobility", -310),
        Sample("Buslinie Fahrschein Fahrkarte Fahrt", "cat-mobility", -280),
        // Versicherung — insurance words: beitrag, police, versicherungsschutz, tarif
        Sample("Haftpflicht Police Beitrag Versicherungsschutz", "cat-insurance", -7_200),
        Sample("Hausrat Tarif Beitrag Police Schutz", "cat-insurance", -5_400),
        Sample("Rechtsschutz Police Jahresbeitrag Tarif", "cat-insurance", -11_800),
        Sample("Unfallschutz Beitrag Police Versicherungsschutz", "cat-insurance", -6_100),
        // --- credits: income. Sign-constrained, so only INCOME/TRANSFER categories are candidates. ---
        // Gehalt — employer words: arbeitgeber, monatsabrechnung, personalnummer, angestellte
        Sample("Nordwind Werke GmbH Monatsabrechnung Personalnummer 8842", "cat-salary", 284_500),
        Sample("Talmann AG Arbeitgeber Monatsabrechnung Mitarbeiterbezug", "cat-salary", 312_400),
        Sample("Sonnenblick Systeme Arbeitgeber Monatsabrechnung Angestellte", "cat-salary", 268_900),
        Sample("Talmann AG Personalabteilung Monatsabrechnung Angestelltenbezug", "cat-salary", 305_100),
        // Weitere Einkünfte — other-income words: jahresausgleich, ertrag, aufwandsentschaedigung
        Sample("Finanzkasse Mittelstadt Jahresausgleich Bescheid", "cat-income", 41_200),
        Sample("Fondsgesellschaft Quartalsausschuettung Wertpapier Ertrag", "cat-income", 8_740),
        Sample("Nebentaetigkeit Vortrag Aufwandsentschaedigung Abrechnung", "cat-income", 25_000),
        // Umbuchung & Sparen — own-account words: eigenes konto, verrechnungskonto, zuweisung
        Sample("Verrechnungskonto eigenes Konto monatliche Zuweisung", "cat-transfers", -50_000),
        Sample("Sparkonto eigenes Konto Guthaben Verschiebung", "cat-transfers", -20_000),
        Sample("Verrechnungskonto Gutschrift aus eigenem Konto Ausgleich", "cat-transfers", 15_000),
        // --- no gold category: the model should propose NOTHING. A proposal here is a false positive. ---
        Sample("Hans Meier Verwendungszweck 884213", null, -12_000),
        Sample("Zahlungseingang 5512 Referenz XQ88", null, 7_500),
        Sample("Kartenzahlung TA-Nr 0815 Terminal 4471", null, -2_190),
        Sample("Martina Kessler Verwendungszweck ohne Angabe", null, -8_000),
        Sample("SEPA Lastschrift Mandat 99213 Glaeubiger DE44", null, -3_450),
        Sample("Gutschrift Referenz 7781 Sammelbuchung", null, 4_900),
    )

    /** Kind per seed category, for the amount-sign candidate filter (the one the app applies). */
    private val kinds: Map<String, CategoryKind> = SeedCatalog.categories.associate { it.id to it.kind }

    /** Exactly `Categorizer.admissibleFor` ∘ "Sonstiges is never proposed". */
    private fun accepts(sample: Sample): (String) -> Boolean = { id ->
        id != CAT_OTHER && (kinds[id]?.let { categoryAllowedForAmount(sample.amountCents, it) } ?: true)
    }

    private val suggestableCategories = SeedCatalog.categories.filter { it.id != CAT_OTHER }

    private val seedDocs: List<Example> =
        SeedCatalog.rules.filter { it.categoryId != CAT_OTHER }.map { Example(it.keyword, it.categoryId) } +
            suggestableCategories.map { Example(it.name, it.id) }

    @Test
    fun `report precision and coverage per strategy`() {
        val rules = RuleEngine(SeedCatalog.rules)

        // --- Rules-only baseline ---
        val rulesPred = fixture.map { rules.categorize(it.text, accepts(it)) }
        val rulesMissed = fixture.filterIndexed { i, _ -> rulesPred[i] == null }
        printLine("rules-only", rulesPred)

        // --- Statistical, swept over thresholds, leave-one-out (train on seed docs + other rows) ---
        // The sweep is how the default minConfidence in StatisticalClassifier was chosen.
        println("  statistical (leave-one-out, threshold sweep):")
        for (threshold in listOf(0.55, 0.60, 0.65, 0.70, 0.80, 0.90)) {
            printLine("  τ=%.2f".format(threshold), statisticalPredict(threshold))
        }

        // --- Embeddings. Real run when the model is present. ---
        val embedder: Embedder = runCatching { DjlEmbedder() }.getOrDefault(NoopEmbedder)
        var embPred: List<String?>? = null
        if (embedder.available()) {
            val space = EmbeddingSpace(embedder)

            // (1) Why the old absolute-cosine criterion was decorative: the similarity of the winner
            //     barely moves between a row the model understands and one it does not.
            println("  embeddings — cosine of the winning category (leave-one-out):")
            val stats = fixture.indices.map { space.topTwo(it) }
            printSpread("    in-taxonomy   ", fixture.indices.filter { fixture[it].gold != null }.map { stats[it].top1 })
            printSpread("    no-gold rows  ", fixture.indices.filter { fixture[it].gold == null }.map { stats[it].top1 })
            println("  embeddings — margin (top1 − top2 over distinct categories):")
            printSpread("    in-taxonomy   ", fixture.indices.filter { fixture[it].gold != null }.map { stats[it].margin })
            printSpread("    no-gold rows  ", fixture.indices.filter { fixture[it].gold == null }.map { stats[it].margin })

            // (2) The old criterion, swept: coverage is pinned at 1.00, so the slider did nothing.
            println("  embeddings, ABSOLUTE COSINE (old criterion, σ sweep):")
            for (minSim in listOf(0.62f, 0.70f, 0.75f, 0.80f, 0.85f)) {
                printLine("  σ=%.2f".format(minSim), space.predict(minMargin = 0f, minSimilarity = minSim))
            }

            // (3) The new criterion, swept. This is how DEFAULT_MIN_MARGIN was picked.
            println("  embeddings, MARGIN (new criterion, m sweep):")
            for (m in listOf(0f, 0.01f, 0.02f, 0.03f, 0.04f, 0.05f, 0.07f, 0.10f, 0.15f)) {
                printLine("  m=%.2f".format(m), space.predict(minMargin = m))
            }

            embPred = space.predict(EmbeddingClassifier.DEFAULT_MIN_MARGIN)

            // --- Head-to-head at the shipping defaults + both merge policies. ---
            val statPred = statisticalPredict(DEFAULT_THRESHOLD)
            printLine("statistical@0.65", statPred)
            printLine("embeddings@m=%.2f".format(EmbeddingClassifier.DEFAULT_MIN_MARGIN), embPred)
            println("  merge policies (%d rows where the two models disagree):".format(disagreements(space)))
            printLine("  raw-compare", mergedPredict(space, normalized = false))
            printLine("  normalized ", mergedPredict(space, normalized = true))
        } else {
            println("  embeddings : SKIPPED (model unavailable; available=false)")
            printLine("statistical@0.65", statisticalPredict(DEFAULT_THRESHOLD))
        }

        val statPred = statisticalPredict(DEFAULT_THRESHOLD)
        val gapCorrect = rulesMissed.count { s -> statPred[fixture.indexOf(s)] == s.gold }
        println("  gap recovery: statistical correctly labels $gapCorrect / ${rulesMissed.size} rules-missed rows")
        embPred?.let { p ->
            println("  gap recovery: embeddings correctly label ${rulesMissed.count { p[fixture.indexOf(it)] == it.gold }} / ${rulesMissed.size} rules-missed rows")
        }

        // Load-bearing conclusions (loose, to stay robust): the statistical model fills real gaps at
        // usable precision. Everything else above is informational for tuning.
        assertTrue(rulesMissed.isNotEmpty(), "fixture should contain merchants the seed rules miss")
        assertTrue(gapCorrect > 0, "statistical classifier should recover at least some rules-missed rows")
        val statPrecision = precision(statPred)
        assertTrue(statPrecision >= 0.5, "statistical precision on predicted rows should be usable, was $statPrecision")
    }

    /**
     * The margin criterion must be *selective* where the old cosine criterion was not: raising it has
     * to cost coverage. Runs only when the model is provisioned (it is gitignored, so CI may not have
     * it); the synthetic-vector tests in [EmbeddingClassifierTest] cover the logic unconditionally.
     */
    @Test
    fun `the margin threshold actually moves the operating point`() {
        val embedder: Embedder = runCatching { DjlEmbedder() }.getOrDefault(NoopEmbedder)
        if (!embedder.available()) {
            println("margin selectivity: SKIPPED (embedding model unavailable)")
            return
        }
        val space = EmbeddingSpace(embedder)
        val loose = coverage(space.predict(minMargin = 0f))
        val tight = coverage(space.predict(minMargin = 0.10f))
        println("margin selectivity: coverage %.2f at m=0.00 → %.2f at m=0.10".format(loose, tight))
        assertTrue(tight < loose, "raising the margin must reduce coverage (it was $loose → $tight)")
    }

    /**
     * Embeds the fixture and the category prototypes **once** and answers leave-one-out queries from
     * the cached vectors (leave-one-out only changes which few-shot examples are labeled).
     */
    private inner class EmbeddingSpace(embedder: Embedder) {
        private val fixtureVectors = embedder.embed(fixture.map { it.text })
        private val protoLabeled: List<EmbeddingClassifier.Labeled> = run {
            val keywordsByCat = SeedCatalog.rules.groupBy { it.categoryId }
            suggestableCategories.map { c ->
                val text = (listOf(c.name) + keywordsByCat[c.id].orEmpty().map { it.keyword }).joinToString(" ")
                EmbeddingClassifier.Labeled(c.id, embedder.embed(text))
            }
        }

        /** Prototypes + every fixture row except [i] as a few-shot example (its gold label). */
        private fun labeledWithout(i: Int) = protoLabeled +
            fixture.indices
                .filter { it != i && fixture[it].gold != null }
                .map { EmbeddingClassifier.Labeled(fixture[it].gold!!, fixtureVectors[it]) }

        fun classify(i: Int, minMargin: Float, minSimilarity: Float = EmbeddingClassifier.DEFAULT_MIN_SIMILARITY) =
            EmbeddingClassifier(labeledWithout(i), minMargin, minSimilarity).classify(fixtureVectors[i], accepts(fixture[i]))

        fun predict(minMargin: Float, minSimilarity: Float = EmbeddingClassifier.DEFAULT_MIN_SIMILARITY): List<String?> =
            fixture.indices.map { classify(it, minMargin, minSimilarity)?.categoryId }

        /** Unfiltered view of a row's decision, for the distribution printout. */
        fun topTwo(i: Int): Spread = classify(i, minMargin = -1f, minSimilarity = -1f)
            .let { Spread(it?.similarity ?: 0f, it?.margin ?: 0f) }
    }

    private data class Spread(val top1: Float, val margin: Float)

    private fun statisticalPredict(threshold: Double): List<String?> = fixture.mapIndexed { i, s ->
        val train = seedDocs + fixture.filterIndexed { j, o -> j != i && o.gold != null }.map { Example(it.text, it.gold!!) }
        StatisticalClassifier(train, minConfidence = threshold).classify(s.text, accepts(s))?.categoryId
    }

    /**
     * The Categorizer's complement policy applied to both models' leave-one-out predictions.
     *
     * [normalized] = the fix for comparing incomparable scales: a Naive-Bayes pairwise softmax lives
     * in [0.5, 1], an embedding margin in [0, ~0.3], so "take the higher number" always meant "take
     * the statistical one". Normalizing each score by how far it cleared **its own** threshold makes
     * the comparison scale-free.
     */
    private fun mergedPredict(space: EmbeddingSpace, normalized: Boolean): List<String?> = fixture.indices.map { i ->
        val s = fixture[i]
        val train = seedDocs + fixture.filterIndexed { j, o -> j != i && o.gold != null }.map { Example(it.text, it.gold!!) }
        val stat = StatisticalClassifier(train, minConfidence = DEFAULT_THRESHOLD).classify(s.text, accepts(s))
        val emb = space.classify(i, EmbeddingClassifier.DEFAULT_MIN_MARGIN)
        when {
            stat == null -> emb?.categoryId
            emb == null -> stat.categoryId
            stat.categoryId == emb.categoryId -> stat.categoryId
            normalized -> {
                val statScore = logistic(stat.confidence.toFloat() - DEFAULT_THRESHOLD.toFloat())
                val embScore = logistic(emb.margin - EmbeddingClassifier.DEFAULT_MIN_MARGIN)
                if (statScore >= embScore) stat.categoryId else emb.categoryId
            }
            else -> if (stat.confidence.toFloat() >= emb.similarity) stat.categoryId else emb.categoryId
        }
    }

    /** How often the two suggesters both fire and name different categories — the merge's only job. */
    private fun disagreements(space: EmbeddingSpace): Int = fixture.indices.count { i ->
        val s = fixture[i]
        val train = seedDocs + fixture.filterIndexed { j, o -> j != i && o.gold != null }.map { Example(it.text, it.gold!!) }
        val stat = StatisticalClassifier(train, minConfidence = DEFAULT_THRESHOLD).classify(s.text, accepts(s))
        val emb = space.classify(i, EmbeddingClassifier.DEFAULT_MIN_MARGIN)
        stat != null && emb != null && stat.categoryId != emb.categoryId
    }

    private fun logistic(x: Float): Float = 1f / (1f + exp(-x.toDouble()).toFloat())

    private fun precision(pred: List<String?>): Double {
        val predicted = pred.count { it != null }
        if (predicted == 0) return 0.0
        val correct = fixture.indices.count { pred[it] != null && pred[it] == fixture[it].gold }
        return correct.toDouble() / predicted
    }

    private fun coverage(pred: List<String?>): Double = pred.count { it != null }.toDouble() / fixture.size

    private fun printLine(label: String, pred: List<String?>) {
        val predicted = pred.count { it != null }
        val correct = fixture.indices.count { pred[it] != null && pred[it] == fixture[it].gold }
        val noGold = fixture.indices.filter { fixture[it].gold == null }
        val falseFire = noGold.count { pred[it] != null }
        val prec = if (predicted == 0) 0.0 else correct.toDouble() / predicted
        println(
            "  %-18s: precision=%.2f (%d/%d)  coverage=%.2f (%d/%d)  false-fire on no-gold rows=%d/%d"
                .format(label, prec, correct, predicted, predicted.toDouble() / fixture.size, predicted, fixture.size, falseFire, noGold.size),
        )
    }

    private fun printSpread(label: String, values: List<Float>) {
        if (values.isEmpty()) return
        val sorted = values.sorted()
        println(
            "  %s min=%.3f p25=%.3f median=%.3f p75=%.3f max=%.3f"
                .format(label, sorted.first(), sorted[sorted.size / 4], sorted[sorted.size / 2], sorted[sorted.size * 3 / 4], sorted.last()),
        )
    }
}
