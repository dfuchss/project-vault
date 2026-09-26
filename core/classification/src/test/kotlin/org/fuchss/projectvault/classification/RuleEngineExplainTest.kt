package org.fuchss.projectvault.classification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The additive [RuleEngine.explain] entry point: the ranked candidate list behind a Tier-1 decision.
 * The property that matters is the last test here — an explanation that can disagree with the
 * decision it explains is worse than no explanation.
 */
class RuleEngineExplainTest {

    private val rules = listOf(
        CategoryRule("AMAZON", "cat-shopping", priority = 0, source = RuleSource.SEED),
        CategoryRule("RUECKERSTATTUNG", "cat-transfers", priority = 0, source = RuleSource.SEED),
        CategoryRule("PRIME", "cat-subscriptions", priority = 0, source = RuleSource.SEED),
        CategoryRule("AMAZON PRIME", "cat-subscriptions", priority = 0, source = RuleSource.SEED),
        CategoryRule("AMZN", "cat-groceries", priority = 100, source = RuleSource.USER),
    )
    private val engine = RuleEngine(rules)

    @Test
    fun `every matching rule is listed, ranked the way the engine resolves them`() {
        val candidates = engine.explain("AMAZON PRIME AMZN Mktp DE")

        // Priority first (the learned AMZN rule, though it appears latest in the text), then earliest
        // position, then the longest keyword at that position ("AMAZON PRIME" over "AMAZON").
        assertEquals(
            listOf("AMZN", "AMAZON PRIME", "AMAZON", "PRIME"),
            candidates.map { it.rule.keyword },
        )
        assertTrue(candidates.all { it.accepted }, "no filter was given, so every match is a candidate")
    }

    @Test
    fun `a rule that does not occur in the text is not a candidate at all`() {
        assertEquals(emptyList(), engine.explain("STADTWERKE MUSTERSTADT").map { it.rule.keyword })
    }

    @Test
    fun `the match position is where the keyword stands in the folded text`() {
        val candidate = engine.explain("Zahlung an AMAZON EU").single { it.rule.keyword == "AMAZON" }
        assertEquals("Zahlung an ".length, candidate.index)
    }

    @Test
    fun `rejected candidates stay in the list, flagged rather than dropped`() {
        // The sign constraint of an incoming refund: the shopping rule matches first but may not win.
        val candidates = engine.explain("AMAZON.de Rueckerstattung") { it == "cat-transfers" }

        assertEquals(listOf("AMAZON", "RUECKERSTATTUNG"), candidates.map { it.rule.keyword })
        assertEquals(listOf(false, true), candidates.map { it.accepted })
        // Which is the whole point: the front-runner is visible *and* visibly not the winner.
        assertEquals("RUECKERSTATTUNG", candidates.first { it.accepted }.rule.keyword)
    }

    @Test
    fun `the first accepted candidate is always exactly what bestRule returns`() {
        val texts = listOf(
            "AMAZON PRIME AMZN Mktp DE",
            "AMAZON.de Rueckerstattung",
            "PRIME VIDEO",
            "STADTWERKE MUSTERSTADT",
            "amzn mktp de*ab1c2",
        )
        val filters = listOf<Pair<String, (String) -> Boolean>>(
            "everything" to { true },
            "transfers only" to { it == "cat-transfers" },
            "no shopping" to { it != "cat-shopping" },
            "nothing" to { false },
        )
        texts.forEach { text ->
            filters.forEach { (name, accepts) ->
                assertEquals(
                    engine.bestRule(text, accepts),
                    engine.explain(text, accepts).firstOrNull { it.accepted }?.rule,
                    "explain disagreed with bestRule for \"$text\" ($name)",
                )
            }
        }
    }
}
