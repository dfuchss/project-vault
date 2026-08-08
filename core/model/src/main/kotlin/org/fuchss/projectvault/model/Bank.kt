package org.fuchss.projectvault.model

/**
 * The banks whose statements can be parsed. A bank is **not** free text: together with the
 * [AccountType] it decides which import template applies, so an account may only be created for a
 * (bank, type) combination that actually has a parser.
 */
enum class Bank(val displayName: String) {
    DKB("DKB"),
    ING("ING"),
    ;

    companion object {
        /**
         * Resolves a stored or parsed institution string to a bank. Vaults hold the institution as
         * TEXT (older ones with hand-typed values), and statements name their bank in prose, so the
         * match is lenient; `null` means "not one of the supported banks".
         */
        fun fromInstitution(text: String?): Bank? {
            val t = text?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            return entries.firstOrNull { it.name == t } ?: entries.firstOrNull { t.contains(it.name) }
        }
    }
}
