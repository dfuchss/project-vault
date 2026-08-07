package org.fuchss.projectvault.imports.csv

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvFormatTest {

    @Test
    fun `parses quoted semicolon-delimited fields`() {
        val rows = CsvFormat.parse("\"a\";\"b\";\"c\"\n\"1\";\"2\";\"3\"")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `keeps a delimiter that sits inside a quoted field`() {
        val rows = CsvFormat.parse("\"Hälfte; von 105\";\"-52,5\"")
        assertEquals(listOf(listOf("Hälfte; von 105", "-52,5")), rows)
    }

    @Test
    fun `unescapes doubled quotes`() {
        val rows = CsvFormat.parse("\"say \"\"hi\"\"\";\"x\"")
        assertEquals(listOf(listOf("say \"hi\"", "x")), rows)
    }

    @Test
    fun `handles CRLF line endings and a leading BOM`() {
        val doc = CsvFormat.of("﻿\"h1\";\"h2\"\r\n\"v1\";\"v2\"\r\n")
        assertEquals(listOf(listOf("h1", "h2"), listOf("v1", "v2")), doc.rows)
    }

    @Test
    fun `preserves empty trailing fields`() {
        // Trailing empty columns (e.g. blank Kundenreferenz) must not be dropped.
        val rows = CsvFormat.parse("\"a\";\"\";\"\"")
        assertEquals(listOf(listOf("a", "", "")), rows)
    }
}
