package org.fuchss.projectvault.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType

/**
 * A smoke test for the review inbox: it must compose and render against a vault with several
 * accounts. Unlike [ScreenshotTest] it writes nothing — the point is only that the screen paints,
 * which is what catches a crash in the row (it reads the window's keyboard modifiers for
 * shift-click) or in the hand-drawn glyphs before a human ever opens the app.
 */
class TriageRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the review inbox renders across accounts`() {
        val file = File(Files.createTempDirectory("pv-triage").toFile(), "v.pvault")
        val vault = VaultManager.create(file)
        val repo = VaultRepository(vault)
        val categorizer = Categorizer(repo).apply { ensureSeeded() }
        val giro = repo.addAccount("Girokonto", AccountType.GIRO, institution = "DKB")
        val card = repo.addAccount("Kreditkarte", AccountType.KREDITKARTE, institution = "DKB")
        repo.insertTransactions(
            giro, null,
            listOf(
                NewTransaction(LocalDate.of(2026, 6, 2), null, -4215, "EUR", "REWE Markt", "Einkauf", "Kartenzahlung", "g1"),
                NewTransaction(LocalDate.of(2026, 6, 1), null, 245000, "EUR", "Arbeitgeber", "Gehalt", "Gutschrift", "g2"),
            ),
        )
        repo.insertTransactions(
            card, null,
            listOf(NewTransaction(LocalDate.of(2026, 6, 5), null, -1999, "EUR", null, "Streaming-Abo", null, "k1")),
        )
        val categories = repo.categories()

        try {
            val scene = ImageComposeScene(width = 1600, height = 1000, density = Density(1f)) {
                VaultTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        TriageScreen(
                            repo = repo,
                            accounts = repo.accounts(),
                            categories = categories,
                            categoryById = categories.associateBy { it.id },
                            bulk = BulkAssign(repo, categorizer),
                            refreshKey = 0,
                            status = null,
                            onSetCategory = { _, _, _ -> },
                            onAcceptSuggestion = { _, _, _ -> },
                            onDismissSuggestion = {},
                            onManageCategories = {},
                            onChanged = {},
                        )
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
