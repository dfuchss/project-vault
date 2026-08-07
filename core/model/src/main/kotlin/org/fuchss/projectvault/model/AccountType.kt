package org.fuchss.projectvault.model

/**
 * The kinds of accounts a user can track. German banking terms in comments for
 * clarity; enum names stay ASCII/English for stable persistence.
 */
enum class AccountType {
    GIRO,        // Girokonto (checking)
    TAGESGELD,   // instant-access savings
    DEPOT,       // securities/brokerage
    KREDITKARTE, // credit card
}
