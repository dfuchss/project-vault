package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.VaultManager
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.model.AccountType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end import checks against **synthetic** statement files written to a temp dir — never the
 * private `sample/` data — so nothing personal is read or committed.
 */
class ImportServiceTest {

    @Test
    fun `a CSV import and a later differently-worded export of the same rows do not double-count`() {
        val dir = Files.createTempDirectory("pvault-csv").toFile()
        val repo = VaultRepository(VaultManager.create(File(dir, "v.pvault")))
        val service = ImportService(repo)
        val accountId = repo.addAccount("Giro", AccountType.GIRO, institution = "DKB")
        val account = repo.account(accountId)!!

        // First export (e.g. the CSV the user pulls now). Three booked rows.
        val first = writeCsv(dir, "first.csv", giroRows(
            "07.08.26|-27,84|Muster Supermarkt|Kartenzahlung",
            "06.08.26|-58,10|Muster GmbH|Rechnung 42",
            "01.08.26|3.150,00|Muster Lohn|Gehalt|Eingang",
        ))
        assertEquals(3, service.commit(accountId, service.preview(account, first)))

        // Later export (e.g. the bank's PDF) — SAME date+amount rows but different extracted text,
        // plus one genuinely new row. Only the new row must be added.
        val second = writeCsv(dir, "second.csv", giroRows(
            "07.08.26|-27,84|REWE SAGT DANKE 12345|Kartenzahlung Muster",  // same tx, different wording
            "06.08.26|-58,10|MUSTER GMBH BERLIN|RG 42/2026",               // same tx, different wording
            "01.08.26|3.150,00|MUSTER GMBH LOHN GEHALT|Bezuege|Eingang",   // same tx, different wording
            "05.08.26|-33,40|Neuer Haendler|Neu",                          // genuinely new
        ))
        val preview = service.preview(account, second)
        assertTrue(preview is ImportPreview.Transactions)
        assertEquals(1, (preview as ImportPreview.Transactions).newCount, "only the new row is new")
        assertEquals(3, preview.duplicateCount, "the three re-worded rows are cross-source duplicates")
        assertEquals(1, service.commit(accountId, preview))
        assertEquals(4, repo.transactionCount(accountId), "no double-counting across sources")
    }

    /** Builds a DKB-format giro CSV from `date|amount|counterparty|purpose[|direction]` rows (fictional). */
    private fun giroRows(vararg rows: String): String {
        val header = buildString {
            appendLine("\"Girokonto\";\"DE00000000000000000000\"")
            appendLine()
            appendLine("\"Kontostand vom 07.08.2026:\";\"0,00 €\"")
            appendLine()
            appendLine("\"Buchungsdatum\";\"Wertstellung\";\"Status\";\"Zahlungspflichtige*r\";\"Zahlungsempfänger*in\";\"Verwendungszweck\";\"Umsatztyp\";\"IBAN\";\"Betrag (€)\";\"Gläubiger-ID\";\"Mandatsreferenz\";\"Kundenreferenz\"")
        }
        val body = rows.joinToString("\n") { r ->
            val (date, amount, party, purpose) = r.split("|").let { listOf(it[0], it[1], it[2], it[3]) }
            val direction = r.split("|").getOrElse(4) { "Ausgang" }
            val payer = if (direction == "Eingang") party else "Max Muster"
            val payee = if (direction == "Eingang") "Max Muster" else party
            "\"$date\";\"$date\";\"Gebucht\";\"$payer\";\"$payee\";\"$purpose\";\"$direction\";\"DE00\";\"$amount\";\"\";\"\";\"\""
        }
        return header + body
    }

    private fun writeCsv(dir: File, name: String, content: String): File =
        File(dir, name).apply { writeText(content, Charsets.UTF_8) }
}
