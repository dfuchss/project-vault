package org.fuchss.projectvault.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test for the Explain tab's decision path: it must compose and paint with a real
 * explanation in it. Like [TriageRenderTest] it writes nothing — the point is that the tables, the
 * bars and the badges lay out at all, which is what catches a measuring crash before a human opens
 * the app. The panel is rendered directly because [ExplainTab] builds its models through suspending
 * effects that a single headless frame would not wait for.
 */
class ExplainRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the decision path renders for a credit whose front-runner the sign rejects`() {
        val file = File(Files.createTempDirectory("pv-explain-render").toFile(), "v.pvault")
        val vault = VaultManager.create(file)
        val repo = VaultRepository(vault)
        val categorizer = Categorizer(repo).apply { ensureSeeded() }
        val giro = repo.addAccount("Girokonto", AccountType.GIRO, institution = "DKB")
        repo.insertTransactions(
            giro,
            null,
            listOf(
                NewTransaction(LocalDate.of(2026, 6, 2), null, -4215, "EUR", "REWE Markt", "Einkauf", "Kartenzahlung", "g1"),
                NewTransaction(LocalDate.of(2026, 6, 4), null, 2499, "EUR", "AMAZON.de", "Rueckerstattung", "Gutschrift", "g2"),
            ),
        )
        categorizer.classifyAccount(giro)
        val categoryById = repo.categories().associateBy { it.id }

        try {
            val models = categorizer.buildExplainModels()
            // A credit: the Amazon rule matches and is rejected by the sign, so every kind of row the
            // panel can draw (winner, sign-rejected, Tier-2 tables, the merge) is on screen at once.
            val explanation = models.explain("AMAZON.de Rueckerstattung Bestellung", amountCents = 2499)
            val scene = ImageComposeScene(width = 1400, height = 1600, density = Density(1f)) {
                VaultTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column { ExplanationPanel(explanation, categoryById, models) }
                    }
                }
            }
            try {
                assertTrue(scene.render().width > 0)
            } finally {
                scene.close()
            }
        } finally {
            vault.close()
        }
    }
}
