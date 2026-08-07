package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.csv.CsvDocument
import org.fuchss.projectvault.imports.csv.CsvFormat
import org.fuchss.projectvault.imports.csv.CsvStatementTemplate
import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.imports.pdf.PdfTextExtractor
import org.fuchss.projectvault.imports.templates.DkbCreditCardTemplate
import org.fuchss.projectvault.imports.templates.DkbCsvCreditCardTemplate
import org.fuchss.projectvault.imports.templates.DkbCsvGiroTemplate
import org.fuchss.projectvault.imports.templates.DkbGiroTemplate
import java.io.File

/**
 * Entry point for importing a statement file: for a PDF, extract text and match a [StatementTemplate];
 * for a `.csv` bank export, parse it and match a [CsvStatementTemplate]. Either path produces a
 * [ParsedStatement], which is run through the [BalanceValidator] and handed to the UI for the
 * mandatory review step. A failed balance check means the statement is incomplete/misparsed and must
 * not be committed as-is (CSV exports have no opening balance, so their check is "not verifiable").
 */
class StatementImporter(
    private val templates: List<StatementTemplate> = defaultTemplates(),
    private val csvTemplates: List<CsvStatementTemplate> = defaultCsvTemplates(),
) {
    /**
     * Imports [file], accepting only statements whose kind is in [accepts] (default: any). The caller
     * passes the kinds the target account can hold, so a document of the wrong kind (a card statement
     * into a Girokonto) is rejected with a [WrongStatementTypeException] instead of being mis-filed.
     */
    fun import(file: File, accepts: Set<StatementKind> = StatementKind.entries.toSet()): ImportResult =
        if (file.extension.equals("csv", ignoreCase = true)) importCsv(CsvFormat.read(file), file.name, accepts)
        else importDocument(PdfTextExtractor.extract(file), file.name, accepts)

    /** Testable core: takes an already-extracted document (so parsing can be tested without a PDF). */
    fun importDocument(
        doc: PdfDocument,
        sourceName: String = "<document>",
        accepts: Set<StatementKind> = StatementKind.entries.toSet(),
    ): ImportResult {
        val matched = templates.firstOrNull { it.matches(doc) } ?: throw UnsupportedStatementException(sourceName)
        if (matched.kind !in accepts) throw WrongStatementTypeException(sourceName, matched.kind, accepts)
        val statement = matched.parse(doc)
        return ImportResult(statement, BalanceValidator.check(statement))
    }

    /** Testable core for CSV: takes an already-parsed document (so parsing can be tested inline). */
    fun importCsv(
        doc: CsvDocument,
        sourceName: String = "<csv>",
        accepts: Set<StatementKind> = StatementKind.entries.toSet(),
    ): ImportResult {
        val matched = csvTemplates.firstOrNull { it.matches(doc) } ?: throw UnsupportedStatementException(sourceName)
        if (matched.kind !in accepts) throw WrongStatementTypeException(sourceName, matched.kind, accepts)
        val statement = matched.parse(doc)
        return ImportResult(statement, BalanceValidator.check(statement))
    }

    companion object {
        /** Built-in PDF templates, most specific first. Extend this as more banks are added. */
        fun defaultTemplates(): List<StatementTemplate> = listOf(
            DkbGiroTemplate(),
            DkbCreditCardTemplate(),
        )

        /** Built-in CSV templates. */
        fun defaultCsvTemplates(): List<CsvStatementTemplate> = listOf(
            DkbCsvGiroTemplate(),
            DkbCsvCreditCardTemplate(),
        )
    }
}
