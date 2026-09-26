package org.fuchss.projectvault.app

import java.io.File
import java.time.LocalDate
import java.math.BigInteger
import java.time.format.DateTimeFormatter
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Txn

/**
 * CSV export of a transaction list — deliberately a **pure** function (rows in, text out) with the
 * file handling kept separate, so the quoting, the decimal comma and the date format are unit-tested
 * without a UI or a vault.
 *
 * The dialect is the one the app *imports*: semicolon-separated, `"` quoting with `""` escaping,
 * CRLF line ends, `dd.MM.yyyy` dates and decimal commas — i.e. what DKB and ING hand out and what
 * `CsvFormat` in `:core:import` reads. An export that a German spreadsheet (or this app's own
 * importer) opens without a wizard is worth more than one in a generic dialect.
 */

/** One exported line. Built from a [Txn] by [exportRowsOf]; a plain value type so tests need no DB. */
internal data class ExportRow(
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val account: String,
    val counterparty: String?,
    val purpose: String,
    val bookingType: String?,
    val amountCents: Long,
    val category: String?,
)

/** The column titles, in order. Localized by the caller; defaulted here so the pure part stands alone. */
internal val DefaultExportColumns = listOf(
    "Buchungsdatum", "Wertstellung", "Konto", "Zahlungspartner", "Verwendungszweck", "Art", "Betrag", "Kategorie",
)

private val EXPORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private const val SEPARATOR = ';'
private const val LINE_END = "\r\n"

/**
 * Renders [rows] as CSV text. The header comes first, then one line per row in the order given —
 * the caller passes the list exactly as the user sees it, so an export is a snapshot of the current
 * filter and sort rather than of the whole account.
 */
internal fun exportCsv(rows: List<ExportRow>, columns: List<String> = DefaultExportColumns): String {
    val out = StringBuilder()
    out.append(columns.joinToString(SEPARATOR.toString()) { csvField(it) }).append(LINE_END)
    rows.forEach { r ->
        out.append(
            listOf(
                r.bookingDate.format(EXPORT_DATE),
                r.valueDate?.format(EXPORT_DATE) ?: "",
                defuse(r.account),
                defuse(r.counterparty ?: ""),
                defuse(r.purpose),
                defuse(r.bookingType ?: ""),
                csvAmount(r.amountCents),
                defuse(r.category ?: ""),
            ).joinToString(SEPARATOR.toString()) { csvField(it) },
        ).append(LINE_END)
    }
    return out.toString()
}

/** Integer cents as a German decimal, e.g. -1160 → "-11,60". Never a float — see the money convention. */
internal fun csvAmount(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    // Via BigInteger because negating Long.MIN_VALUE overflows back to itself, which would print a
    // doubly-signed nonsense amount. Unreachable with real money, but silent if it ever isn't.
    val abs = cents.toBigInteger().abs()
    return "$sign${abs / BigInteger.valueOf(100)},${(abs % BigInteger.valueOf(100)).toString().padStart(2, '0')}"
}

/**
 * Neutralizes a **text** field that a spreadsheet would read as a formula.
 *
 * Excel and LibreOffice evaluate a field beginning with `=`, `+`, `-` or `@` as a formula, and
 * quoting does not prevent it. That matters here rather than in the abstract: a SEPA
 * *Verwendungszweck* is free text chosen by whoever sent the money, it lands in the vault by being
 * paid, and "export and open in Excel" is exactly what this file is for (CWE-1236). A leading
 * apostrophe is the conventional defusal and is what a spreadsheet strips on display.
 *
 * Only user-derived text goes through this; generated dates and amounts do not, so a negative amount
 * still exports as `-11,60`. The apostrophe survives a re-import as a literal character, which is the
 * accepted cost of not handing the file a way to execute.
 */
internal fun defuse(value: String): String =
    if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'" + value else value

/**
 * Quotes a field when it has to be: a separator, a quote or a line break inside an unquoted field
 * would silently shift every later column. Quotes are doubled, which is what [CsvFormat] reads back.
 */
internal fun csvField(value: String): String =
    if (value.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/** Maps the rows on screen to [ExportRow]s. [accountNameOf] resolves each row's account. */
internal fun exportRowsOf(
    txns: List<Txn>,
    categoryById: Map<String, Category>,
    accountNameOf: (Txn) -> String,
): List<ExportRow> = txns.map { t ->
    ExportRow(
        bookingDate = LocalDate.ofEpochDay(t.bookingDate),
        valueDate = t.valueDate?.let(LocalDate::ofEpochDay),
        account = accountNameOf(t),
        counterparty = t.counterparty,
        purpose = t.purpose,
        bookingType = t.bookingType,
        amountCents = t.amountCents,
        category = t.categoryId?.let { categoryById[it]?.name },
    )
}

/**
 * Writes exported text to disk. UTF-8 **with a BOM**: without one, a German Excel opens the file as
 * latin-1 and every umlaut in a Verwendungszweck turns to mojibake — and the app's own reader
 * ([CsvFormat]) strips the BOM, so a re-import is unaffected.
 */
internal fun writeCsv(file: File, text: String) = file.writeText("﻿$text", Charsets.UTF_8)
