package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.NewHolding
import org.fuchss.projectvault.data.NewImportBatch
import org.fuchss.projectvault.data.NewTransaction
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.imports.BalanceCheck
import org.fuchss.projectvault.imports.BankCatalog
import org.fuchss.projectvault.imports.DepotCheck
import org.fuchss.projectvault.imports.DepotImporter
import org.fuchss.projectvault.imports.Dedup
import org.fuchss.projectvault.imports.ParsedDepotStatement
import org.fuchss.projectvault.imports.ParsedStatement
import org.fuchss.projectvault.imports.StatementImporter
import org.fuchss.projectvault.imports.StatementKind
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.Bank
import java.io.File
import java.time.LocalDate

/**
 * What can be imported today (DKB Giro/Tagesgeld/Kreditkarte, ING Depot). The single source of truth
 * used both to route an import to the right templates and to restrict account creation: the **bank**
 * decides how a statement is parsed, so an account is created for a (bank, type) pair from
 * [BankCatalog] — never for a hand-typed institution that no template could ever match.
 */
object ImportSupport {
    /** The statement kind an account of this type holds. */
    fun kindFor(type: AccountType): StatementKind = when (type) {
        AccountType.GIRO, AccountType.TAGESGELD -> StatementKind.GIRO
        AccountType.KREDITKARTE -> StatementKind.CREDIT_CARD
        AccountType.DEPOT -> StatementKind.DEPOT
    }

    fun acceptedKinds(type: AccountType): Set<StatementKind> = setOf(kindFor(type))

    /** The banks offered during account creation. */
    val banks: List<Bank> get() = BankCatalog.banks

    /** The account types [bank] can be created for — the products it has a parser for. */
    fun accountTypes(bank: Bank): List<AccountType> =
        AccountType.entries.filter { BankCatalog.isSupported(bank, kindFor(it)) }

    /** The bank an account is held at, or `null` for a legacy account with a free-text institution. */
    fun bankOf(account: Account): Bank? = Bank.fromInstitution(account.institution)

    /**
     * Whether this account can import at all. A legacy account whose institution isn't one of the
     * known banks stays importable — all templates are then tried, as before.
     */
    fun isSupported(account: Account): Boolean =
        bankOf(account)?.let { BankCatalog.isSupported(it, kindFor(account.type)) } ?: true

    /** The banks an import into this account may come from (all of them for a legacy account). */
    fun banksFor(account: Account): Set<Bank> = bankOf(account)?.let(::setOf) ?: Bank.entries.toSet()
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
            val s = I18n.current
            append(s.transactionsCount(rows.size))
            if (duplicateCount > 0) append(s.newVsImported(newCount, duplicateCount))
            append(s.balanceWord)
            append(
                when {
                    check.ok -> s.balanceReconciles
                    !check.verifiable -> s.balanceNotVerifiableShort
                    else -> s.balanceDoesNotReconcile
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
        override val summary get() = I18n.current.holdingsSummary(rows.size, check.ok)
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
            // Route by the account's bank as well as its type: a bank's export format is what decides
            // how a file parses, so an ING file can never be filed into a DKB account (and vice versa).
            val result = DepotImporter().import(file, ImportSupport.banksFor(account))
            val warnings = mismatchWarnings(account.institution, account.iban, result.statement.institution, null).toMutableList()
            val valuationDate = result.statement.valuationDate
            if (valuationDate != null && repo.valuationDates(account.id).contains(valuationDate.toEpochDay())) {
                warnings += I18n.current.snapshotExistsReplace(valuationDate.toString())
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
            // Route by account bank + type: only templates of the account's bank whose kind it accepts
            // are considered, so neither another bank's export nor a card statement can be mis-filed
            // into a Girokonto (either fails with a clear message instead).
            val result = StatementImporter().import(file, accepts, ImportSupport.banksFor(account))
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
                warnings += I18n.current.allDuplicates(rows.size)
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
                warnings += I18n.current.ibanMismatch(accountIban!!, statementIban!!)
            }
            val ab = accountBank?.trim()
            if (!ab.isNullOrBlank() && !statementBank.isNullOrBlank() && !institutionMatches(ab, statementBank)) {
                warnings += I18n.current.bankMismatch(ab, statementBank)
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
