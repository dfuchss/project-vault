package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.NewHolding
import org.fuchss.projectvault.data.NewImportBatch
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.imports.BalanceCheck
import org.fuchss.projectvault.imports.DepotCheck
import org.fuchss.projectvault.imports.DepotImporter
import org.fuchss.projectvault.imports.Dedup
import org.fuchss.projectvault.imports.ParsedDepotStatement
import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.StatementImporter
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.model.AccountType
import java.io.File
import java.time.LocalDate

/**
 * What each account type can import today (DKB Giro/Tagesgeld/Kreditkarte, ING Depot). The single
 * source of truth used both to route an import to the right templates and to guide the user during
 * account creation.
 */
object ImportSupport {
    fun acceptedKinds(type: AccountType): Set<StatementKind> = when (type) {
        AccountType.GIRO, AccountType.TAGESGELD -> setOf(StatementKind.GIRO)
        AccountType.KREDITKARTE -> setOf(StatementKind.CREDIT_CARD)
        AccountType.DEPOT -> setOf(StatementKind.DEPOT)
    }

    fun isSupported(type: AccountType): Boolean = acceptedKinds(type).isNotEmpty()

    /** The bank to pre-fill for a new account of this type (drives the import mismatch warnings). */
    fun defaultBank(type: AccountType): String = when (type) {
        AccountType.GIRO, AccountType.TAGESGELD, AccountType.KREDITKARTE -> "DKB"
        AccountType.DEPOT -> "ING"
    }

    /** A one-line hint of what can be imported for this account type. */
    fun hint(type: AccountType): String = when (type) {
        AccountType.GIRO, AccountType.TAGESGELD ->
            "Import: DKB Kontoauszug (PDF) or Umsatzliste (CSV)"
        AccountType.KREDITKARTE ->
            "Import: DKB Kreditkartenabrechnung (PDF) or Umsatzliste (CSV)"
        AccountType.DEPOT ->
            "Import: ING Depotauszug (PDF) or Depotübersicht (CSV)"
    }
}

/**
 * The parsed, not-yet-committed result of importing a file — shown in the mandatory review step
 * before anything is written to the vault. Carries [sourceName] for provenance and [warnings] (e.g.
 * the statement's bank/IBAN not matching the selected account).
 */
sealed interface ImportPreview {
    val ok: Boolean

    /** False when the integrity check could not run at all (e.g. a CSV export with no opening balance). */
    val verifiable: Boolean
    val summary: String
    val sourceName: String
    val warnings: List<String>
    val rowCount: Int

    data class Transactions(
        val statement: ParsedStatement,
        val check: BalanceCheck,
        val rows: List<NewTransaction>,
        /** The subset of [rows] not already present (exact or cross-source duplicate) — what commit inserts. */
        val newRows: List<NewTransaction>,
        val newCount: Int,
        val duplicateCount: Int,
        override val sourceName: String,
        override val warnings: List<String>,
    ) : ImportPreview {
        override val ok get() = check.ok
        override val verifiable get() = check.verifiable
        override val rowCount get() = rows.size
        override val summary get() = buildString {
            append("${rows.size} transactions")
            if (duplicateCount > 0) append(" ($newCount new, $duplicateCount already imported)")
            append(" - balance ")
            append(
                when {
                    check.ok -> "reconciles"
                    !check.verifiable -> "not verifiable from this export"
                    else -> "does NOT reconcile (review)"
                }
            )
        }
    }

    data class Depot(
        val statement: ParsedDepotStatement,
        val check: DepotCheck,
        val rows: List<NewHolding>,
        override val sourceName: String,
        override val warnings: List<String>,
    ) : ImportPreview {
        override val ok get() = check.ok
        override val verifiable get() = check.totalValueCents != null
        override val rowCount get() = rows.size
        override val summary get() =
            "${rows.size} holdings - total ${if (check.ok) "reconciles" else "does NOT reconcile (review)"}"
    }
}

/**
 * Ties statement extraction to persistence: [preview] parses a file (no writes) for the review step
 * and flags bank/IBAN mismatches against the target account; [commit] records an import batch
 * (provenance) and persists the reviewed result. Routes by account type.
 */
class ImportService(private val repo: VaultRepository) {

    fun preview(account: Account, file: File): ImportPreview =
        if (account.type == AccountType.DEPOT) {
            val result = DepotImporter().import(file)
            val warnings = mismatchWarnings(account.institution, account.iban, result.statement.institution, null).toMutableList()
            val valuationDate = result.statement.valuationDate
            if (valuationDate != null && repo.valuationDates(account.id).contains(valuationDate.toEpochDay())) {
                warnings += "A snapshot for $valuationDate already exists and will be replaced."
            }
            ImportPreview.Depot(
                statement = result.statement,
                check = result.check,
                sourceName = file.name,
                warnings = warnings,
                rows = result.statement.positions.map {
                    NewHolding(it.isin, it.wkn, it.name, it.quantity.toPlainString(), it.priceText, it.marketValueCents, it.currency)
                },
            )
        } else {
            val accepts = ImportSupport.acceptedKinds(account.type)
            require(accepts.isNotEmpty()) { "Statement import is not supported for ${account.type} accounts." }
            // Route by account type: only templates whose kind the account accepts are considered, so a
            // card statement can't be mis-filed into a Girokonto (it fails with a clear message instead).
            val result = StatementImporter().import(file, accepts)
            val parsed = result.statement.transactions
            val rows = parsed.map {
                NewTransaction(
                    bookingDate = it.bookingDate,
                    valueDate = it.valueDate,
                    amountCents = it.amountCents,
                    currency = result.statement.currency,
                    counterparty = it.counterparty,
                    purpose = it.purpose,
                    bookingType = it.bookingType,
                    dedupHash = Dedup.hash(it),
                )
            }
            // De-dup against what's already stored, matching either by exact content hash (same-source
            // re-import) or by the source-independent coarse key (a CSV imported now vs. the bank's PDF
            // later). One-to-one matching avoids over-merging distinct same-day/same-amount rows.
            val stored = repo.transactions(account.id).map {
                Dedup.Keys(it.dedupHash, Dedup.coarseKey(LocalDate.ofEpochDay(it.bookingDate), it.amountCents))
            }
            val candidates = parsed.map { Dedup.Keys(Dedup.hash(it), Dedup.coarseKey(it.bookingDate, it.amountCents)) }
            val duplicate = Dedup.duplicateFlags(stored, candidates)
            val newRows = rows.filterIndexed { i, _ -> !duplicate[i] }
            val duplicateCount = duplicate.count { it }
            val newCount = rows.size - duplicateCount
            val warnings = mismatchWarnings(account.institution, account.iban, result.statement.institution, result.statement.iban).toMutableList()
            if (rows.isNotEmpty() && newCount == 0) {
                warnings += "Already imported: all ${rows.size} transactions are duplicates (nothing new to add)."
            }
            ImportPreview.Transactions(
                statement = result.statement,
                check = result.balance,
                rows = rows,
                newRows = newRows,
                newCount = newCount,
                duplicateCount = duplicateCount,
                sourceName = file.name,
                warnings = warnings,
            )
        }

    /** Persists the reviewed preview under a new provenance batch. Returns items newly stored. */
    fun commit(accountId: String, preview: ImportPreview): Int = when (preview) {
        is ImportPreview.Transactions -> {
            val batchId = repo.createBatch(
                accountId,
                NewImportBatch(
                    kind = "TRANSACTIONS",
                    sourceName = preview.sourceName,
                    institution = preview.statement.institution,
                    statementNumber = preview.statement.statementNumber,
                    periodStart = preview.statement.periodStart,
                    periodEnd = preview.statement.periodEnd,
                    valuationDate = null,
                    endBalanceCents = preview.statement.closingBalanceCents,
                    reconciled = preview.check.ok,
                    itemCount = preview.newRows.size,
                ),
            )
            // Only the new rows are persisted; duplicates (exact or cross-source) are skipped so a CSV
            // imported now and the bank's PDF later don't double-count.
            val inserted = repo.insertTransactions(accountId, batchId, preview.newRows)
            if (inserted == 0) repo.deleteBatch(batchId) // a fully-duplicate re-import leaves no batch
            inserted
        }
        is ImportPreview.Depot -> {
            val batchId = repo.createBatch(
                accountId,
                NewImportBatch(
                    kind = "DEPOT",
                    sourceName = preview.sourceName,
                    institution = preview.statement.institution,
                    statementNumber = null,
                    periodStart = null,
                    periodEnd = null,
                    valuationDate = preview.statement.valuationDate,
                    endBalanceCents = preview.statement.totalValueCents,
                    reconciled = preview.check.ok,
                    itemCount = preview.rows.size,
                ),
            )
            repo.storeDepotSnapshot(accountId, preview.statement.valuationDate, batchId, preview.rows)
            preview.rows.size
        }
    }

    companion object {
        /** Warnings when the account's bank/IBAN is set but doesn't match the parsed statement. */
        fun mismatchWarnings(
            accountBank: String?,
            accountIban: String?,
            statementBank: String?,
            statementIban: String?,
        ): List<String> {
            val warnings = mutableListOf<String>()
            val ai = accountIban?.let(::normalizeIban)
            val si = statementIban?.let(::normalizeIban)
            if (!ai.isNullOrBlank() && !si.isNullOrBlank() && ai != si) {
                warnings += "IBAN does not match: account $accountIban vs statement $statementIban"
            }
            val ab = accountBank?.trim()
            if (!ab.isNullOrBlank() && !statementBank.isNullOrBlank() && !institutionMatches(ab, statementBank)) {
                warnings += "Bank does not match: account \"$ab\" vs statement \"$statementBank\""
            }
            return warnings
        }

        private fun normalizeIban(iban: String): String = iban.filter { it.isLetterOrDigit() }.uppercase()

        private fun institutionMatches(a: String, b: String): Boolean {
            val ua = a.uppercase()
            val ub = b.uppercase()
            return ua.contains(ub) || ub.contains(ua)
        }
    }
}
