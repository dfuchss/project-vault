package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.csv.CsvDepotTemplate
import org.fuchss.projectvault.imports.csv.CsvDocument
import org.fuchss.projectvault.imports.csv.CsvFormat
import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.imports.pdf.PdfTextExtractor
import org.fuchss.projectvault.imports.templates.IngCsvDepotTemplate
import org.fuchss.projectvault.imports.templates.IngDepotTemplate
import org.fuchss.projectvault.model.Bank
import java.io.File

/**
 * Imports a securities Depot statement: for a PDF, extract → match a [DepotStatementTemplate]; for a
 * `.csv` export (e.g. ING "Depotübersicht"), parse → match a [CsvDepotTemplate]. Either path yields
 * a holdings snapshot run through the [DepotValidator]. Separate from [StatementImporter] because a
 * Depot is a snapshot of positions, not a list of transactions.
 */
class DepotImporter(
    private val templates: List<DepotStatementTemplate> = defaultTemplates(),
    private val csvTemplates: List<CsvDepotTemplate> = defaultCsvTemplates(),
) {
    /** Imports [file], accepting only statements from a bank in [banks] (default: any). */
    fun import(file: File, banks: Set<Bank> = Bank.entries.toSet()): DepotImportResult =
        if (file.extension.equals("csv", ignoreCase = true)) importCsv(CsvFormat.read(file), file.name, banks)
        else importDocument(PdfTextExtractor.extract(file), file.name, banks)

    fun importDocument(
        doc: PdfDocument,
        sourceName: String = "<document>",
        banks: Set<Bank> = Bank.entries.toSet(),
    ): DepotImportResult {
        val template = templates.firstOrNull { it.matches(doc) }
            ?: throw UnsupportedStatementException(sourceName)
        if (template.bank !in banks) throw WrongBankException(sourceName, template.bank, banks)
        val statement = template.parse(doc)
        return DepotImportResult(statement, DepotValidator.check(statement))
    }

    fun importCsv(
        doc: CsvDocument,
        sourceName: String = "<csv>",
        banks: Set<Bank> = Bank.entries.toSet(),
    ): DepotImportResult {
        val template = csvTemplates.firstOrNull { it.matches(doc) }
            ?: throw UnsupportedStatementException(sourceName)
        if (template.bank !in banks) throw WrongBankException(sourceName, template.bank, banks)
        val statement = template.parse(doc)
        return DepotImportResult(statement, DepotValidator.check(statement))
    }

    companion object {
        fun defaultTemplates(): List<DepotStatementTemplate> = listOf(IngDepotTemplate())
        fun defaultCsvTemplates(): List<CsvDepotTemplate> = listOf(IngCsvDepotTemplate())
    }
}
