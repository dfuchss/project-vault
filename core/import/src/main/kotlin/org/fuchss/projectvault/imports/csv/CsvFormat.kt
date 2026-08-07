package org.fuchss.projectvault.imports.csv

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A parsed CSV file: [rows] of string cells, plus the decoded [text] for signature matching. */
data class CsvDocument(val rows: List<List<String>>, val text: String) {
    /** The cell at [r]/[c], trimmed, or "" if out of range. */
    fun cell(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c)?.trim() ?: ""

    /** Index of the first row whose first cell equals [header] (trimmed), or -1. */
    fun headerRow(header: String): Int = rows.indexOfFirst { it.firstOrNull()?.trim() == header }
}

/**
 * A minimal, dependency-free reader for the delimited exports German banks hand out: semicolon
 * separated, double-quoted fields (with `""` escaping), CRLF or LF line ends, and a possible UTF-8
 * BOM. Decoding tries strict UTF-8 first (giro/credit-card exports) and falls back to ISO-8859-1
 * (ING's "Depotübersicht" is latin-1), so umlauts survive either way.
 */
object CsvFormat {

    fun read(file: File): CsvDocument = of(decode(file.readBytes()))

    fun of(text: String): CsvDocument {
        val clean = text.removePrefix("﻿")
        return CsvDocument(parse(clean), clean)
    }

    fun parse(text: String, delimiter: Char = ';'): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var started = false // true once the current row has any content, so trailing blank lines drop
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> { inQuotes = true; started = true }
                c == delimiter -> { row.add(field.toString()); field.clear(); started = true }
                c == '\r' -> {} // swallow; the '\n' ends the row
                c == '\n' -> {
                    if (started || field.isNotEmpty() || row.isNotEmpty()) {
                        row.add(field.toString()); rows.add(row.toList())
                    }
                    row.clear(); field.clear(); started = false
                }
                else -> { field.append(c); started = true }
            }
            i++
        }
        if (started || field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row.toList()) }
        return rows
    }

    private fun decode(bytes: ByteArray): String = runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
}
