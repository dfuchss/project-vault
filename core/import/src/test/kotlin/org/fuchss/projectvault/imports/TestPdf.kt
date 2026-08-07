package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.imports.pdf.PdfLine
import org.fuchss.projectvault.imports.pdf.PdfPage
import org.fuchss.projectvault.imports.pdf.PdfToken

/** Builders for constructing synthetic positioned documents in tests (no real statement data). */
object TestPdf {
    private var yCounter = 0f

    fun tok(x: Float, text: String, width: Float = text.length * 6f): PdfToken =
        PdfToken(text, x, 0f, width)

    fun line(vararg tokens: PdfToken): PdfLine = PdfLine(yCounter++, tokens.toList())

    fun doc(vararg lines: PdfLine): PdfDocument = PdfDocument(listOf(PdfPage(1, lines.toList())))
}
