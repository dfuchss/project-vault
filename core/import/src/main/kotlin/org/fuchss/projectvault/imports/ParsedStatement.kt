package org.fuchss.projectvault.imports

import org.fuchss.projectvault.imports.pdf.PdfDocument
import org.fuchss.projectvault.model.Bank
import java.time.LocalDate

/** What kind of statement a template produced. */
enum class StatementKind { GIRO, CREDIT_CARD, DEPOT }

/** A single parsed transaction. [amountCents] is signed: negative = money out. */
data class ParsedTransaction(
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val amountCents: Long,
    val counterparty: String?,
    val purpose: String,
    val rawText: String,
    val bookingType: String? = null,
)

/** The result of parsing one statement file, before it is committed to a vault. */
data class ParsedStatement(
    val templateId: String,
    val kind: StatementKind,
    val institution: String,
    val accountRef: String?,
    val iban: String?,
    val statementNumber: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val currency: String,
    val openingBalanceCents: Long?,
    val closingBalanceCents: Long?,
    val transactions: List<ParsedTransaction>,
)

/**
 * Outcome of the balance-integrity check: opening + Σ(transactions) must equal closing.
 * A non-[ok] result means the parse is untrustworthy and the import must go to manual review.
 */
data class BalanceCheck(
    val openingCents: Long?,
    val closingCents: Long?,
    val sumCents: Long,
    val computedClosingCents: Long?,
    val differenceCents: Long?,
    val ok: Boolean,
) {
    /**
     * Whether the integrity check could actually run. CSV exports print only the current balance, not
     * an opening one, so nothing can be reconciled — that is "not verifiable", which the UI must
     * distinguish from a genuine mismatch (`ok == false && verifiable`).
     */
    val verifiable: Boolean get() = openingCents != null && closingCents != null

    /** Privacy-safe description for logs: reports only the discrepancy, never actual balances. */
    override fun toString(): String =
        "BalanceCheck(ok=$ok, verifiable=$verifiable, differenceCents=$differenceCents)"
}

data class ImportResult(
    val statement: ParsedStatement,
    val balance: BalanceCheck,
)

/** A per-bank/per-document-type parser. Templates are tried in order via [StatementImporter]. */
interface StatementTemplate {
    val id: String

    /** The bank this template parses — used to route by the target account's bank. */
    val bank: Bank

    /** What this template produces — used to route by account type (a Giro account rejects a card statement). */
    val kind: StatementKind
    fun matches(doc: PdfDocument): Boolean
    fun parse(doc: PdfDocument): ParsedStatement
}

/** Thrown when no registered template recognizes a document. */
class UnsupportedStatementException(fileName: String) :
    RuntimeException("No statement template matched \"$fileName\". Add or edit a bank template.")

/**
 * Thrown when a document is recognized but its kind doesn't fit the target account — e.g. importing a
 * credit-card statement into a Girokonto. Guides the user to the right account rather than silently
 * mis-filing the transactions.
 */
class WrongStatementTypeException(fileName: String, val found: StatementKind, val accepted: Set<StatementKind>) :
    RuntimeException(
        "\"$fileName\" looks like a ${found.label} statement, but this account accepts " +
            "${accepted.joinToString(" / ") { it.label }}. Import it into a matching account.",
    )

/**
 * Thrown when a document is recognized but comes from another bank than the target account's — e.g.
 * an ING export dropped onto a DKB account. The bank decides how a file is parsed, so accounts are
 * bound to one and imports must match.
 */
class WrongBankException(fileName: String, val found: Bank, val expected: Set<Bank>) :
    RuntimeException(
        "\"$fileName\" is a ${found.displayName} statement, but this account is held at " +
            "${expected.joinToString(" / ") { it.displayName }}. Import it into a matching account.",
    )

/** Human-readable statement-kind name for messages. */
val StatementKind.label: String
    get() = when (this) {
        StatementKind.GIRO -> "current/savings account"
        StatementKind.CREDIT_CARD -> "credit-card"
        StatementKind.DEPOT -> "securities Depot"
    }
