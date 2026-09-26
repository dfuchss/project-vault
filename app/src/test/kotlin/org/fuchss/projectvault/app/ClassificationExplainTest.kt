package org.fuchss.projectvault.app

import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Explain tab's data layer: [Categorizer.buildExplainModels] + [ExplainModels.explain].
 *
 * The decisive test is the last one — the explanation's own reported outcome has to be what the
 * classifier actually does with the same rows, or the panel is confidently lying to the user.
 */
class ClassificationExplainTest {

    /** Deterministic fake: anything restaurant-ish maps to one axis, everything else to another. */
    private class FakeEmbedder : Embedder {
        override fun available() = true
        override fun embed(texts: List<String>) = texts.map { t ->
            val u = t.uppercase()
            if (u.contains("RESTAURANT") || u.contains("TRATTORIA")) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
        }
    }

    private fun setup(embedder: Embedder = FakeEmbedder()): Triple<VaultRepository, Categorizer, String> {
        val file = File(Files.createTempDirectory("pv-explain").toFile(), "v.pvault")
        val repo = VaultRepository(VaultManager.create(file))
        val categorizer = Categorizer(repo, embedder).apply { ensureSeeded() }
        return Triple(repo, categorizer, repo.addAccount("Giro", AccountType.GIRO))
    }

    private fun tx(counterparty: String, purpose: String, amountCents: Long, hash: String) =
        NewTransaction(LocalDate.of(2026, 7, 1), null, amountCents, "EUR", counterparty, purpose, "Umsatz", hash)

    private fun verdictOf(explanation: ClassificationExplanation, keyword: String): RuleVerdict? =
        explanation.rules.firstOrNull { it.rule.keyword == keyword }?.verdict

    @Test
    fun `the sign constraint is reported, and it is why a front-runner loses`() {
        val (_, categorizer, _) = setup()
        val models = categorizer.buildExplainModels()
        val text = "AMAZON.de Rueckerstattung Bestellung"

        val credit = models.explain(text, amountCents = 2499)
        assertEquals(listOf(CategoryKind.INCOME, CategoryKind.TRANSFER), credit.allowedKinds)
        // The Amazon rule matches first and is *rejected by the sign*, so the refund rule wins — the
        // single most valuable thing the panel can show, and the reason the whole tab exists.
        assertEquals(RuleVerdict.SIGN_REJECTED, verdictOf(credit, "AMAZON"))
        assertEquals(RuleVerdict.WINNER, verdictOf(credit, "RUECKERSTATTUNG"))
        assertEquals(OutcomeKind.COMMITTED, credit.outcome.kind)
        assertEquals("cat-transfers", credit.outcome.categoryId)
        assertEquals(CategorySource.SEED_RULE, credit.outcome.source)

        // The same text as a debit is an ordinary Amazon purchase again.
        val debit = models.explain(text, amountCents = -2499)
        assertEquals(listOf(CategoryKind.EXPENSE, CategoryKind.TRANSFER), debit.allowedKinds)
        assertEquals(RuleVerdict.WINNER, verdictOf(debit, "AMAZON"))
        assertEquals("cat-shopping", debit.outcome.categoryId)
    }

    @Test
    fun `a rule that loses on the ordering is listed as out-ranked, not hidden`() {
        val (_, categorizer, _) = setup()
        categorizer.addRule("MKTP", "cat-groceries", priority = 100)
        val explanation = categorizer.buildExplainModels().explain("AMZN Mktp DE Amazon", amountCents = -1000)

        // The learned rule has the higher priority and wins despite standing later in the text; the
        // two seed rules matched and are ranked below it rather than dropped.
        assertEquals(RuleVerdict.WINNER, verdictOf(explanation, "MKTP"))
        assertEquals(RuleVerdict.OUTRANKED, verdictOf(explanation, "AMZN"))
        assertEquals(RuleVerdict.OUTRANKED, verdictOf(explanation, "AMAZON"))
        assertEquals("cat-groceries", explanation.outcome.categoryId)
        assertEquals(CategorySource.USER_RULE, explanation.outcome.source)
    }

    @Test
    fun `a rule whose category is switched off says so`() {
        val (repo, categorizer, _) = setup()
        repo.disableCategory("cat-shopping", CAT_OTHER, CAT_INCOME)
        val explanation = categorizer.buildExplainModels().explain("AMAZON EU S.A R.L.", amountCents = -1999)

        assertEquals(RuleVerdict.CATEGORY_DISABLED, verdictOf(explanation, "AMAZON"))
        // Nothing else matches, so the row falls through to Tier 2 exactly as the panel reports.
        assertNull(explanation.winningRule)
        assertTrue(explanation.outcome.kind != OutcomeKind.COMMITTED)
    }

    @Test
    fun `the token evidence and the per-category scores describe the statistical decision`() {
        val (_, categorizer, _) = setup()
        val tier = categorizer.buildExplainModels().explain("Trattoria Napoli", amountCents = -2300).statistical

        assertEquals(TierState.RAN, tier.state)
        val explanation = assertNotNull(tier.explanation)
        // "TRATTORIA" is not a seed keyword, so the model has never seen it — which is the honest
        // reason a text like this gets no statistical proposal, and has to be reportable as such.
        assertTrue("TRATTORIA" in explanation.unknownTokens)
        // Whatever it decides, the scores it reports are the scores it decided on.
        explanation.decision?.let { decision ->
            val top = explanation.scores.filter { it.admissible }.maxByOrNull { it.logScore }
            assertEquals(decision.categoryId, top?.categoryId)
        }
    }

    @Test
    fun `each tier says plainly when it did not run`() {
        val (repo, categorizer, _) = setup(NoopEmbedderForTest)
        val offline = categorizer.buildExplainModels().explain("REWE SAGT DANKE", amountCents = -1234)
        assertEquals(TierState.RAN, offline.statistical.state)
        // No model provisioned — the common case on a machine without the gitignored ONNX file.
        assertEquals(TierState.MODEL_MISSING, offline.embedding.state)
        assertNull(offline.embedding.explanation)

        ClassifierSettings.save(repo, ClassifierSettings(statisticalEnabled = false, embeddingEnabled = false))
        val switchedOff = categorizer.buildExplainModels().explain("REWE SAGT DANKE", amountCents = -1234)
        assertEquals(TierState.SWITCHED_OFF, switchedOff.statistical.state)
        assertEquals(TierState.SWITCHED_OFF, switchedOff.embedding.state)
        assertNull(switchedOff.statistical.explanation)
        // With both suggesters off, a row no rule matches ends up with nothing at all.
        assertEquals(MergeVerdict.NOTHING, switchedOff.merge.verdict)
    }

    @Test
    fun `the explained outcome is what the classifier actually does`() {
        val (repo, categorizer, account) = setup()
        repo.insertTransactions(
            account,
            null,
            listOf(
                tx("REWE SAGT DANKE", "Kartenzahlung", -2345, "a"),          // committed by a seed rule
                tx("AMAZON.de", "Rueckerstattung Bestellung", 2499, "b"),    // committed, but by the refund rule
                tx("Trattoria Napoli", "Kartenzahlung", -4200, "c"),         // no rule: Tier 2 proposes
                tx("Hans Meier", "Ueberweisung", -5000, "d"),                // no rule, no opinion
                tx("Musterfirma GmbH", "Lohn/Gehalt Juli", 245000, "e"),     // a credit committed by a rule
            ),
        )
        categorizer.classifyAccount(account)

        val models = categorizer.buildExplainModels()
        repo.transactions(account).forEach { txn ->
            val explanation = models.explain(explainTextOf(txn), txn.amountCents)
            val expected = when {
                txn.categoryId != null -> OutcomeKind.COMMITTED to txn.categoryId
                txn.suggestedCategoryId != null -> OutcomeKind.SUGGESTED to txn.suggestedCategoryId
                else -> OutcomeKind.NOTHING to null
            }
            assertEquals(
                expected,
                explanation.outcome.kind to explanation.outcome.categoryId,
                "the explanation of \"${explainTextOf(txn)}\" disagrees with what the classifier did",
            )
        }
    }
}

/** An embedder that is simply not there — the state a build without the bundled ONNX model is in. */
private object NoopEmbedderForTest : Embedder {
    override fun available() = false
    override fun embed(texts: List<String>) = texts.map { FloatArray(0) }
}
