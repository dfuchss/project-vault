package org.fuchss.projectvault.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface as SkiaSurface
import org.fuchss.projectvault.data.NewHolding
import org.fuchss.projectvault.data.NewImportBatch
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
 * Generates the README screenshots by seeding a SYNTHETIC vault (no personal data) and rendering the
 * real screens off-screen to PNGs. Doubles as a UI smoke test: the main screens must compose and
 * render without crashing against realistic data.
 *
 * NOTE: capture heights are kept near real window sizes. At very large canvas heights
 * ImageComposeScene intermittently fails to paint a couple of bare sidebar Text labels (a
 * headless-only quirk; the windowed app is unaffected).
 */
class ScreenshotTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `render app screenshots`() {
        val vaultFile = File(Files.createTempDirectory("pv-shot").toFile(), "demo.pvault")
        val vault = VaultManager.create(vaultFile)
        val repo = VaultRepository(vault)
        seedSampleData(repo)
        val accounts = repo.accounts().associateBy { it.name }
        try {
            render("dashboard.png", 2400, 1680) { MainScreen(vault) {} }
            render("transactions.png", 2400, 1500) { MainScreen(vault, accounts.getValue("Girokonto").id) {} }
            render("depot.png", 2400, 1080) { MainScreen(vault, accounts.getValue("Depot").id) {} }
            // The forecast card lives at the bottom of a long dashboard; render the whole page tall
            // in memory, then crop the card out for a focused docs image.
            renderCropped("forecast.png", 2400, 4200, cropX = 620, cropY = 3158, cropW = 1760, cropH = 700) { MainScreen(vault) {} }
        } finally {
            vault.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        writePng(name, renderScene(width, height, content))
    }

    /** Renders [width]×[height] then crops the given pixel rect before writing (for focused shots). */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderCropped(
        name: String, width: Int, height: Int,
        cropX: Int, cropY: Int, cropW: Int, cropH: Int,
        content: @Composable () -> Unit,
    ) {
        val full = Image.makeFromEncoded(renderScene(width, height, content))
        val surface = SkiaSurface.makeRasterN32Premul(cropW, cropH)
        surface.canvas.drawImage(full, -cropX.toFloat(), -cropY.toFloat())
        writePng(name, surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderScene(width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            VaultTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } }
        }
        try {
            return scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    private fun writePng(name: String, bytes: ByteArray) {
        val out = File(repoRoot(), "docs/screenshots/$name")
        out.parentFile.mkdirs()
        out.writeBytes(bytes)
        assertTrue(out.length() > 0, "$name should be written")
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

    private fun seedSampleData(repo: VaultRepository) {
        val categorizer = Categorizer(repo).apply { ensureSeeded() } // NoopEmbedder: rules only, no model load
        val alice = repo.addProfile("Alice", "#15616D")
        val bob = repo.addProfile("Bob", "#E4572E")

        // Synthetic demo data: fictional profiles, institutions, IBANs, employer, amounts and holdings.
        // Merchant names are real German brands on purpose, so the seed classifier can categorize them
        // and the dashboard shows a realistic spending breakdown. No real person's data is used.
        val giro = repo.addAccount("Girokonto", AccountType.GIRO, "Musterbank", "DE12 3456 7890 1234 5678 90", ownerProfileIds = listOf(alice))
        val card = repo.addAccount("Kreditkarte", AccountType.KREDITKARTE, "Musterbank", null, ownerProfileIds = listOf(alice))
        val tagesgeld = repo.addAccount("Tagesgeld", AccountType.TAGESGELD, "Musterbank", null, ownerProfileIds = listOf(alice, bob))
        val depot = repo.addAccount("Depot", AccountType.DEPOT, "Musterbroker", null, ownerProfileIds = listOf(bob))

        var seq = 0
        fun nt(month: Int, day: Int, cents: Long, counterparty: String) =
            NewTransaction(LocalDate.of(2026, month, day), null, cents, "EUR", counterparty, counterparty, "Kartenzahlung", "s${seq++}")

        // One-off entries that make the monthly net rise and dip below zero, so the dashboard's
        // net-trend chart clearly shows the green(+)/red(−) fluctuation feature.
        val oneOffs = mapOf(
            2 to (-162000L to "Reisebuchung Muster"),
            3 to (-389000L to "Möbelhaus Muster"),      // big purchase → net goes negative
            5 to (118000L to "Bonus Muster GmbH"),      // extra income → net spikes up
            6 to (-238000L to "Urlaubskasse Muster"),   // holiday → net goes negative
        )
        val giroTx = buildList {
            for (m in 1..7) {
                add(nt(m, 1, -87500, "Miete Musterwohnung"))
                add(nt(m, 2, -1099, "Spotify/Stockholm/../SE"))
                add(nt(m, 3, -5140, "REWE.Markt/Musterstadt/../DE"))
                add(nt(m, 8, -2780, "EDEKA/Musterstadt/../DE"))
                add(nt(m, 12, -2240, "Lidl.sagt.Danke/Musterstadt/../DE"))
                add(nt(m, 15, -4830, "Amazon.de/../LU"))
                add(nt(m, 18, -7150, "Aral Tankstelle/Musterstadt/../DE"))
                add(nt(m, 22, -4120, "Restaurant Bella/Musterstadt/../DE"))
                add(nt(m, 28, 328000, "Muster GmbH Lohn/Gehalt"))
                oneOffs[m]?.let { (cents, name) -> add(nt(m, 20, cents, name)) }
            }
        }
        val giroBatch = repo.createBatch(giro, NewImportBatch("TRANSACTIONS", "Kontoauszug_7_2026.pdf", "Musterbank", "7/2026", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, 342180, true, giroTx.size))
        repo.insertTransactions(giro, giroBatch, giroTx)

        val cardBatch = repo.createBatch(card, NewImportBatch("TRANSACTIONS", "Kreditkarte_Juli.pdf", "Musterbank", null, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 7, 22), null, -30380, true, 3))
        repo.insertTransactions(card, cardBatch, listOf(nt(7, 5, -1990, "Netflix Abo"), nt(7, 9, -18740, "MediaMarkt Elektronik"), nt(7, 14, -9650, "Apotheke am Markt")))

        val tagesBatch = repo.createBatch(tagesgeld, NewImportBatch("TRANSACTIONS", "Tagesgeld_6_2026.pdf", "Musterbank", null, null, null, null, 1750000, true, 2))
        repo.insertTransactions(tagesgeld, tagesBatch, listOf(nt(7, 30, -620000, "Sparen"), nt(7, 31, 175000, "Sparen")))

        val depotBatch = repo.createBatch(depot, NewImportBatch("DEPOT", "Depotauszug.pdf", "Musterbroker", null, null, null, LocalDate.of(2026, 7, 15), 1252200, true, 2))
        repo.storeDepotSnapshot(depot, LocalDate.of(2026, 7, 15), depotBatch, listOf(
            NewHolding("DE0000000001", "A00001", "Weltaktien ETF (thesaurierend)", "120,00000", "95,50 EUR", 1146000, "EUR"),
            NewHolding("US0000000002", "A00002", "Muster Technology Inc.", "8", "132,75 EUR", 106200, "EUR"),
        ))

        categorizer.classifyAccount(giro)
        categorizer.classifyAccount(card)
        categorizer.classifyAccount(tagesgeld)
    }
}
