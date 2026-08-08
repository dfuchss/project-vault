package org.fuchss.projectvault.imports

import org.fuchss.projectvault.model.Bank

/**
 * Which (bank, statement kind) combinations can actually be parsed today — **derived from the
 * registered templates**, so adding a template is all it takes to offer the combination during
 * account creation. Account creation is restricted to these: the bank decides how a file is parsed,
 * so an account for a bank/product with no parser could never import anything.
 */
object BankCatalog {

    /** Every parsable (bank, kind) pair across the PDF and CSV template registries. */
    val supported: Set<Pair<Bank, StatementKind>> = buildSet {
        StatementImporter.defaultTemplates().forEach { add(it.bank to it.kind) }
        StatementImporter.defaultCsvTemplates().forEach { add(it.bank to it.kind) }
        DepotImporter.defaultTemplates().forEach { add(it.bank to StatementKind.DEPOT) }
        DepotImporter.defaultCsvTemplates().forEach { add(it.bank to StatementKind.DEPOT) }
    }

    /** The banks a user can pick from, in enum order. */
    val banks: List<Bank> = Bank.entries.filter { bank -> supported.any { it.first == bank } }

    /** What [bank] can be imported for — empty means "nothing supported". */
    fun kinds(bank: Bank): Set<StatementKind> =
        supported.filter { it.first == bank }.map { it.second }.toSet()

    fun isSupported(bank: Bank, kind: StatementKind): Boolean = (bank to kind) in supported
}
