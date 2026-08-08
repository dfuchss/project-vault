package org.fuchss.projectvault.imports.csv

import org.fuchss.projectvault.imports.ParsedDepotStatement
import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.model.Bank

/**
 * A per-bank/per-document-type parser for a CSV export, mirroring the PDF `StatementTemplate`. It
 * produces the same [ParsedStatement] the PDF path does, so the balance check, de-duplication and
 * persistence downstream are identical regardless of source format.
 */
interface CsvStatementTemplate {
    val id: String

    /** The bank this template parses — used to route by the target account's bank. */
    val bank: Bank

    /** What this template produces — used to route by account type. */
    val kind: StatementKind
    fun matches(doc: CsvDocument): Boolean
    fun parse(doc: CsvDocument): ParsedStatement
}

/** CSV counterpart of `DepotStatementTemplate`: a holdings snapshot rather than a transaction list. */
interface CsvDepotTemplate {
    val id: String

    /** The bank this template parses — used to route by the target account's bank. */
    val bank: Bank
    fun matches(doc: CsvDocument): Boolean
    fun parse(doc: CsvDocument): ParsedDepotStatement
}

/** Shared helper: parse a German account balance like `24.422,30 €` or `-179,37 EUR` to cents. */
internal fun balanceToCents(text: String): Long? {
    val t = text.replace("€", "").replace("EUR", "").trim()
    if (t.isBlank()) return null
    return runCatching { org.fuchss.projectvault.imports.parse.GermanFormats.amountToCents(t) }.getOrNull()
}
