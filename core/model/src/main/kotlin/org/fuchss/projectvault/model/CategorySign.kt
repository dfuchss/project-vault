package org.fuchss.projectvault.model

/**
 * Which [CategoryKind]s a transaction of a given signed amount may legitimately carry.
 *
 * Money that comes in can only be income or a movement between your own accounts; money that goes
 * out can only be an expense or such a movement. Filing an incoming payment under an expense
 * category (the classic symptom: a credit landing in "Sonstiges") is never right — it is not merely
 * a bad guess, it is a category error.
 *
 * This is the single source of truth for that policy: the category picker in the UI and the
 * automatic classification (Tier 1 rules and Tier 2 suggestions) both consult it, so what the app
 * offers a user and what it decides on its own can never disagree.
 *
 * A zero amount carries no direction, so nothing is ruled out.
 */
fun allowedKindsForAmount(amountCents: Long): List<CategoryKind> = when {
    amountCents > 0 -> listOf(CategoryKind.INCOME, CategoryKind.TRANSFER)
    amountCents < 0 -> listOf(CategoryKind.EXPENSE, CategoryKind.TRANSFER)
    else -> CategoryKind.entries.toList()
}

/** Whether a category of [kind] may be assigned to a transaction of [amountCents]. */
fun categoryAllowedForAmount(amountCents: Long, kind: CategoryKind): Boolean =
    kind in allowedKindsForAmount(amountCents)
