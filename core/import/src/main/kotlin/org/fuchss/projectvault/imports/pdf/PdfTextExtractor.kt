package org.fuchss.projectvault.imports.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs

/** A word-level token with its position on the page (top-left origin after direction adjust). */
data class PdfToken(val text: String, val x: Float, val y: Float, val width: Float) {
    val endX: Float get() = x + width
}

/** A visual line: tokens that share (approximately) a baseline, ordered left-to-right. */
class PdfLine(val y: Float, val tokens: List<PdfToken>) {
    val text: String = tokens.joinToString(" ") { it.text }.trim()
    val startX: Float get() = tokens.firstOrNull()?.x ?: 0f

    /** Text of the tokens whose start x falls within [fromX, toX). Used to read a column. */
    fun textIn(fromX: Float, toX: Float): String =
        tokens.filter { it.x >= fromX && it.x < toX }.joinToString(" ") { it.text }.trim()

    /** Tokens whose start x is at or beyond [fromX]. */
    fun tokensFrom(fromX: Float): List<PdfToken> = tokens.filter { it.x >= fromX }
}

class PdfPage(val number: Int, val lines: List<PdfLine>)

class PdfDocument(val pages: List<PdfPage>) {
    /** All lines across all pages, in reading order. */
    val lines: List<PdfLine> get() = pages.flatMap { it.lines }

    /** Flat text (one line per visual line) — handy for bank-signature detection. */
    val text: String by lazy { lines.joinToString("\n") { it.text } }
}

/**
 * Extracts a digital (non-scanned) PDF into positioned word tokens grouped into visual lines.
 * This is the raw material every per-bank [org.fuchss.projectvault.imports.StatementTemplate] parses;
 * templates rely on token x-positions to separate columns, which flat text loses.
 */
object PdfTextExtractor {

    private const val LINE_TOLERANCE = 3f       // chars within this Δy share a line
    private const val TOKEN_GAP_FACTOR = 0.5f   // gap > factor × space-width ⇒ new token

    fun extract(file: File): PdfDocument {
        Loader.loadPDF(file).use { doc ->
            val collector = PositionCollector().apply { sortByPosition = true }
            collector.getText(doc) // drives processing; we keep the positions, not the string
            val pages = collector.pagePositions.mapIndexed { index, chars ->
                PdfPage(index + 1, groupIntoLines(chars))
            }
            return PdfDocument(pages)
        }
    }

    private fun groupIntoLines(chars: List<TextPosition>): List<PdfLine> {
        if (chars.isEmpty()) return emptyList()
        val sorted = chars.sortedWith(compareBy({ it.yDirAdj }, { it.xDirAdj }))
        val lines = mutableListOf<MutableList<TextPosition>>()
        var currentY = Float.NaN
        for (c in sorted) {
            if (lines.isEmpty() || abs(c.yDirAdj - currentY) > LINE_TOLERANCE) {
                lines.add(mutableListOf(c))
                currentY = c.yDirAdj
            } else {
                lines.last().add(c)
            }
        }
        return lines.map { lineChars ->
            PdfLine(lineChars.first().yDirAdj, mergeTokens(lineChars.sortedBy { it.xDirAdj }))
        }
    }

    private fun mergeTokens(lineChars: List<TextPosition>): List<PdfToken> {
        val tokens = mutableListOf<PdfToken>()
        val sb = StringBuilder()
        var startX = 0f
        var lastEndX = 0f
        var y = 0f
        for (c in lineChars) {
            val ch = c.unicode ?: continue
            val x = c.xDirAdj
            val w = c.widthDirAdj
            if (sb.isEmpty()) {
                sb.append(ch); startX = x; lastEndX = x + w; y = c.yDirAdj
            } else {
                val space = c.widthOfSpace.takeIf { it > 0f } ?: w.takeIf { it > 0f } ?: 2f
                if (x - lastEndX > TOKEN_GAP_FACTOR * space) {
                    tokens.add(PdfToken(sb.toString().trim(), startX, y, lastEndX - startX))
                    sb.setLength(0); sb.append(ch); startX = x; lastEndX = x + w; y = c.yDirAdj
                } else {
                    sb.append(ch); lastEndX = x + w
                }
            }
        }
        if (sb.isNotEmpty()) tokens.add(PdfToken(sb.toString().trim(), startX, y, lastEndX - startX))
        return tokens.filter { it.text.isNotEmpty() }
    }

    private class PositionCollector : PDFTextStripper() {
        val pagePositions = mutableListOf<MutableList<TextPosition>>()
        private var current = mutableListOf<TextPosition>()

        override fun startPage(page: PDPage) {
            current = mutableListOf()
            super.startPage(page)
        }

        override fun endPage(page: PDPage) {
            pagePositions.add(current)
            super.endPage(page)
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            current.addAll(textPositions)
            super.writeString(text, textPositions)
        }
    }
}
